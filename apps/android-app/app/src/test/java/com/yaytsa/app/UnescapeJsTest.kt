package com.yaytsa.app

import org.junit.Assert.assertEquals
import org.junit.Test

class UnescapeJsTest {

    @Test
    fun `unescapes quoted json string from evaluateJavascript`() {
        // evaluateJavascript wraps result in quotes and escapes inner quotes
        val raw = "\"{ \\\"t\\\": \\\"Song\\\" }\""
        assertEquals("{ \"t\": \"Song\" }", WebViewActivity.unescapeJs(raw))
    }

    @Test
    fun `passes through unquoted string`() {
        val raw = "{ \"t\": \"Song\" }"
        assertEquals(raw, WebViewActivity.unescapeJs(raw))
    }

    @Test
    fun `handles escaped backslashes`() {
        val raw = "\"path\\\\to\\\\file\""
        assertEquals("path\\to\\file", WebViewActivity.unescapeJs(raw))
    }

    @Test
    fun `handles empty quoted string`() {
        assertEquals("", WebViewActivity.unescapeJs("\"\""))
    }

    @Test
    fun `handles simple quoted content`() {
        assertEquals("hello", WebViewActivity.unescapeJs("\"hello\""))
    }
}
