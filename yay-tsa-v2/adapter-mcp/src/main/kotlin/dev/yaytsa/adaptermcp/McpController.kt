package dev.yaytsa.adaptermcp

import com.fasterxml.jackson.annotation.JsonInclude
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.Principal
import java.util.UUID

@RestController
@RequestMapping("/mcp")
class McpController(
    private val tools: McpTools,
    private val toolProviders: List<McpToolProvider>,
) {
    private val log = LoggerFactory.getLogger(McpController::class.java)

    data class JsonRpcRequest(
        val jsonrpc: String = "2.0",
        val id: Any? = null,
        val method: String,
        val params: Map<String, Any?>? = null,
    )

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class JsonRpcResponse(
        val jsonrpc: String = "2.0",
        val id: Any? = null,
        val result: Any? = null,
        val error: JsonRpcError? = null,
    )

    data class JsonRpcError(
        val code: Int,
        val message: String,
    )

    @PostMapping
    fun handle(
        @RequestBody request: JsonRpcRequest,
        principal: Principal,
    ): ResponseEntity<JsonRpcResponse> {
        if (request.id == null) {
            return ResponseEntity.accepted().build()
        }
        val response =
            when (request.method) {
                "initialize" ->
                    JsonRpcResponse(
                        id = request.id,
                        result =
                            mapOf(
                                "protocolVersion" to "2024-11-05",
                                "capabilities" to mapOf("tools" to mapOf<String, Any>()),
                                "serverInfo" to mapOf("name" to "yaytsa", "version" to "0.1.0"),
                            ),
                    )
                "tools/list" ->
                    JsonRpcResponse(
                        id = request.id,
                        result =
                            mapOf(
                                "tools" to
                                    (tools.listTools() + toolProviders.flatMap { it.definitions() }).map {
                                        mapOf(
                                            "name" to it.name,
                                            "description" to it.description,
                                            "inputSchema" to it.inputSchema,
                                        )
                                    },
                            ),
                    )
                "tools/call" -> {
                    val toolName =
                        (request.params?.get("name") as? String)
                            ?: return ResponseEntity.ok(
                                JsonRpcResponse(id = request.id, error = JsonRpcError(-32602, "Missing tool name")),
                            )

                    @Suppress("UNCHECKED_CAST")
                    val toolArgs = request.params["arguments"] as? Map<String, Any?> ?: emptyMap()
                    // A tool must never let an exception bubble to the Spring exception handler: that
                    // returns an HTTP problem+json (e.g. a DB DataAccessException became a 400), which
                    // breaks the JSON-RPC envelope and reaches the client as an opaque "error occurred
                    // during tool execution" with no id to trace. Convert any failure into a normal
                    // JSON-RPC tool result flagged isError, and log it with a correlation id.
                    val result =
                        try {
                            toolProviders.firstOrNull { it.handles(toolName) }?.execute(toolName, toolArgs, principal.name)
                                ?: tools.executeTool(toolName, toolArgs, principal.name)
                        } catch (ex: Exception) {
                            val errorId = UUID.randomUUID().toString()
                            log.error("MCP tool '{}' failed (errorId={})", toolName, errorId, ex)
                            errorResult(
                                "Tool '$toolName' failed (error id $errorId). This is a server-side error, not a problem with your input.",
                            )
                        }
                    JsonRpcResponse(
                        id = request.id,
                        result = mapOf("content" to result.content, "isError" to result.isError),
                    )
                }
                else ->
                    JsonRpcResponse(
                        id = request.id,
                        error = JsonRpcError(-32601, "Method not found: ${request.method}"),
                    )
            }
        return ResponseEntity.ok(response)
    }
}
