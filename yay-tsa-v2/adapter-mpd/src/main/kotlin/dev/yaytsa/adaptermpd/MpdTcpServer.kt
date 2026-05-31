package dev.yaytsa.adaptermpd

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

@Component
class MpdTcpServer(
    private val commandHandler: MpdCommandHandler,
    @Value("\${yaytsa.mpd.port:6600}") private val port: Int,
    @Value("\${yaytsa.mpd.enabled:false}") private val enabled: Boolean,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)
    private val executor = Executors.newFixedThreadPool(50) { r -> Thread(r, "mpd-conn").apply { isDaemon = true } }
    private var serverSocket: ServerSocket? = null

    companion object {
        private val IDLE_POLL_INTERVAL = 400.milliseconds
        private val IDLE_MAX_BLOCK = 5.minutes
    }

    override fun start() {
        if (!enabled) {
            log.info("MPD server disabled (yaytsa.mpd.enabled=false)")
            return
        }
        running.set(true)
        Thread({
            try {
                val ss = ServerSocket(port)
                serverSocket = ss
                log.info("MPD server listening on port {}", port)
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

    private fun blockingIdle(reader: BufferedReader): String {
        val initialToken = commandHandler.currentStateToken()
        val deadline = System.currentTimeMillis() + IDLE_MAX_BLOCK.inWholeMilliseconds
        while (running.get() && System.currentTimeMillis() < deadline) {
            if (reader.ready()) {
                val pending = reader.readLine()?.trim()
                return if (pending == "noidle") "OK\n" else commandHandler.handle(pending ?: "")
            }
            if (commandHandler.currentStateToken() != initialToken) {
                return "changed: player\nOK\n"
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

    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val writer = PrintWriter(s.getOutputStream(), true)
                writer.println("OK MPD 0.23.5")
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
                            // MPD protocol: emit each sub-command's payload (not just ACKs), with a
                            // `list_OK` separator after each in ok-mode, and a single terminating OK.
                            val sb = StringBuilder()
                            var failed = false
                            for (cmd in batch) {
                                val result = commandHandler.handle(cmd)
                                if (result.startsWith("ACK")) {
                                    sb.append(result)
                                    failed = true
                                    break
                                }
                                sb.append(result.removeSuffix("OK\n"))
                                if (commandListOkMode) sb.append("list_OK\n")
                            }
                            if (!failed) sb.append("OK\n")
                            writer.print(sb)
                            writer.flush()
                        }
                        commandList != null -> {
                            commandList.add(trimmed)
                        }
                        trimmed == "idle" || trimmed.startsWith("idle ") -> {
                            writer.print(blockingIdle(reader))
                            writer.flush()
                        }
                        else -> {
                            val response = commandHandler.handle(trimmed)
                            writer.print(response)
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
