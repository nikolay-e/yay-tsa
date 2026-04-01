package com.yaytsa.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolveUrlTest {

    @Test
    fun `empty url returns empty`() {
        assertEquals("", MediaPlaybackService.resolveUrl("", "https://example.com"))
    }

    @Test
    fun `absolute https url returned as-is`() {
        val url = "https://cdn.example.com/art.jpg"
        assertEquals(url, MediaPlaybackService.resolveUrl(url, "https://other.com"))
    }

    @Test
    fun `absolute http url returned as-is`() {
        val url = "http://cdn.example.com/art.jpg"
        assertEquals(url, MediaPlaybackService.resolveUrl(url, "https://other.com"))
    }

    @Test
    fun `relative url prepends base url`() {
        assertEquals(
            "https://example.com/Items/123/Images/Primary",
            MediaPlaybackService.resolveUrl("/Items/123/Images/Primary", "https://example.com")
        )
    }

    @Test
    fun `relative url trims trailing slash from base`() {
        assertEquals(
            "https://example.com/art.jpg",
            MediaPlaybackService.resolveUrl("/art.jpg", "https://example.com/")
        )
    }

    @Test
    fun `relative url with empty base returned as-is`() {
        assertEquals("/art.jpg", MediaPlaybackService.resolveUrl("/art.jpg", ""))
    }

    @Test
    fun `non-slash non-http url returned as-is`() {
        assertEquals("blob:something", MediaPlaybackService.resolveUrl("blob:something", "https://x.com"))
    }
}
