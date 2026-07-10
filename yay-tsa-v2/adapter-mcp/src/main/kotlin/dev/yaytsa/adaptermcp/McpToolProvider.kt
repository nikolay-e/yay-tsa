package dev.yaytsa.adaptermcp

// Tool families plug into McpController through this interface instead of growing McpTools,
// keeping each family's dependencies in its own component (detekt constructor/function limits).
interface McpToolProvider {
    fun definitions(): List<McpToolDefinition>

    fun handles(name: String): Boolean

    fun execute(
        name: String,
        clientArgs: Map<String, Any?>,
        authenticatedUserId: String,
    ): McpToolResult
}
