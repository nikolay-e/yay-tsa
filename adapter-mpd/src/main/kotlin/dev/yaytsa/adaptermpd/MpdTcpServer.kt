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

    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val writer = PrintWriter(s.getOutputStream(), true)
                writer.println("OK MPD 0.23.5")
                var commandList: MutableList<String>? = null
                while (running.get()) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue

                    when {
                        trimmed == "command_list_begin" || trimmed == "command_list_ok_begin" -> {
                            commandList = mutableListOf()
                        }
                        trimmed == "command_list_end" -> {
                            val batch = commandList ?: continue
                            commandList = null
                            val sb = StringBuilder()
                            for (cmd in batch) {
                                val result = commandHandler.handle(cmd)
                                if (result.startsWith("ACK")) {
                                    sb.append(result)
                                    break
                                }
                            }
                            sb.append("OK\n")
                            writer.print(sb)
                            writer.flush()
                        }
                        commandList != null -> {
                            commandList.add(trimmed)
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
