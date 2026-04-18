package dev.yaytsa.adaptermcp

import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/mcp")
class McpController(
    private val tools: McpTools,
) {
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
    ): ResponseEntity<JsonRpcResponse> {
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
                                    tools.listTools().map {
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
                    val result = tools.executeTool(toolName, toolArgs)
                    JsonRpcResponse(
                        id = request.id,
                        result = mapOf("content" to result.content, "isError" to result.isError),
                    )
                }
                "notifications/initialized" ->
                    return ResponseEntity.ok(
                        JsonRpcResponse(id = request.id, result = emptyMap<String, Any>()),
                    )
                else ->
                    JsonRpcResponse(
                        id = request.id,
                        error = JsonRpcError(-32601, "Method not found: ${request.method}"),
                    )
            }
        return ResponseEntity.ok(response)
    }
}
