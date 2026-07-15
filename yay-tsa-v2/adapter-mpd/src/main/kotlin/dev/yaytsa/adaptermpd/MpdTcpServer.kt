package dev.yaytsa.adaptermpd

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

@Component
class MpdTcpServer(
    private val commandHandler: MpdCommandHandler,
    @Value("\${yaytsa.mpd.port:6600}") private val port: Int,
    @Value("\${yaytsa.mpd.enabled:false}") private val enabled: Boolean,
    @Value("\${yaytsa.mpd.bind-address:127.0.0.1}") private val bindAddress: String,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)
    private val executor = Executors.newFixedThreadPool(50) { r -> Thread(r, "mpd-conn").apply { isDaemon = true } }
    private var serverSocket: ServerSocket? = null

    companion object {
        private val IDLE_POLL_INTERVAL = 400.milliseconds
        private val IDLE_MAX_BLOCK = 5.minutes
        private const val ACCEPT_BACKLOG = 50
    }

    override fun start() {
        if (!enabled) {
            log.info("MPD server disabled (yaytsa.mpd.enabled=false)")
            return
        }
        running.set(true)
        Thread({
            try {
                val ss = ServerSocket(port, ACCEPT_BACKLOG, InetAddress.getByName(bindAddress))
                serverSocket = ss
                log.info("MPD server listening on {}:{}", bindAddress, port)
                while (running.get()) {
                    val client = ss.accept()
                    executor.submit { handleClient(client) }
                }
            } catch (e: Exception) {
                if (running.get()) log.error("MPD server error", e)
            }
        }, "mpd-accept").apply { isDaemon = true }.start()
    }

    override fun stop() {
        running.set(false)
        serverSocket?.close()
        executor.shutdownNow()
    }

    override fun isRunning(): Boolean = running.get()

    private fun changedSubsystems(
        initial: MpdCommandHandler.SubsystemSnapshot,
        current: MpdCommandHandler.SubsystemSnapshot,
        subsystemFilter: Set<String>,
    ): List<String> =
        buildList {
            if (current.playlistVersion != initial.playlistVersion) add("playlist")
            if (current.playerToken != initial.playerToken) add("player")
        }.filter { subsystemFilter.isEmpty() || it in subsystemFilter }

    private fun blockingIdle(
        reader: BufferedReader,
        subsystemFilter: Set<String>,
    ): String {
        val initial = commandHandler.observeSubsystems()
        val deadline = System.currentTimeMillis() + IDLE_MAX_BLOCK.inWholeMilliseconds
        while (running.get() && System.currentTimeMillis() < deadline) {
            if (reader.ready()) {
                val pending = reader.readLine()?.trim()
                return if (pending == "noidle") "OK\n" else commandHandler.handle(pending ?: "")
            }
            val changed = changedSubsystems(initial, commandHandler.observeSubsystems(), subsystemFilter)
            if (changed.isNotEmpty()) {
                return changed.joinToString("") { "changed: $it\n" } + "OK\n"
            }
            try {
                Thread.sleep(IDLE_POLL_INTERVAL.inWholeMilliseconds)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return "OK\n"
            }
        }
        return "OK\n"
    }

    private fun runCommandList(
        batch: List<String>,
        okMode: Boolean,
    ): String {
        // MPD protocol: emit each sub-command's payload (not just ACKs), with a
        // `list_OK` separator after each in ok-mode, and a single terminating OK.
        // On failure the ACK offset carries the failing command's index in the list.
        val sb = StringBuilder()
        var failed = false
        for ((index, cmd) in batch.withIndex()) {
            val result = commandHandler.handle(cmd, index)
            if (result.startsWith("ACK")) {
                sb.append(result)
                failed = true
                break
            }
            sb.append(result.removeSuffix("OK\n"))
            if (okMode) sb.append("list_OK\n")
        }
        if (!failed) sb.append("OK\n")
        return sb.toString()
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))
                writer.write("OK MPD 0.23.5\n")
                writer.flush()
                var commandList: MutableList<String>? = null
                var commandListOkMode = false
                while (running.get()) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue

                    when {
                        trimmed == "command_list_begin" || trimmed == "command_list_ok_begin" -> {
                            commandListOkMode = trimmed == "command_list_ok_begin"
                            commandList = mutableListOf()
                        }
                        trimmed == "command_list_end" -> {
                            val batch = commandList ?: continue
                            commandList = null
                            writer.write(runCommandList(batch, commandListOkMode))
                            writer.flush()
                        }
                        commandList != null -> {
                            commandList.add(trimmed)
                        }
                        trimmed == "idle" || trimmed.startsWith("idle ") -> {
                            val subsystemFilter =
                                trimmed
                                    .removePrefix("idle")
                                    .trim()
                                    .split(' ')
                                    .filter { it.isNotBlank() }
                                    .toSet()
                            writer.write(blockingIdle(reader, subsystemFilter))
                            writer.flush()
                        }
                        else -> {
                            writer.write(commandHandler.handle(trimmed))
                            writer.flush()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.debug("MPD connection closed: {}", e.message)
        }
    }
}
