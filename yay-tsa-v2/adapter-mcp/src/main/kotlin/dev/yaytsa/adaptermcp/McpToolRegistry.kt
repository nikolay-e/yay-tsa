package dev.yaytsa.adaptermcp

data class McpToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any>,
)

data class McpToolResult(
    val content: List<Map<String, Any>>,
    val isError: Boolean = false,
)

fun textResult(text: String) = McpToolResult(listOf(mapOf("type" to "text", "text" to text)))

fun errorResult(text: String) = McpToolResult(listOf(mapOf("type" to "text", "text" to text)), isError = true)
