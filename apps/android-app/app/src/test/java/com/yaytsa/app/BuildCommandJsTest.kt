package com.yaytsa.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildCommandJsTest {

    @Test
    fun `play returns js that invokes play handler`() {
        val js = WebViewActivity.buildCommandJs("play")
        assertNotNull(js)
        assertTrue(js!!.contains("['play']"))
        assertTrue(js.contains("a.play()"))
    }

    @Test
    fun `pause returns js that invokes pause handler`() {
        val js = WebViewActivity.buildCommandJs("pause")
        assertNotNull(js)
        assertTrue(js!!.contains("['pause']"))
        assertTrue(js.contains("a.pause()"))
    }

    @Test
    fun `nexttrack returns js that invokes nexttrack handler`() {
        val js = WebViewActivity.buildCommandJs("nexttrack")
        assertNotNull(js)
        assertTrue(js!!.contains("['nexttrack']"))
    }

    @Test
    fun `previoustrack returns js with fallback to seek zero`() {
        val js = WebViewActivity.buildCommandJs("previoustrack")
        assertNotNull(js)
        assertTrue(js!!.contains("['previoustrack']"))
        assertTrue(js.contains("a.currentTime=0"))
    }

    @Test
    fun `seekto embeds seconds value`() {
        val js = WebViewActivity.buildCommandJs("seekto:42.5")
        assertNotNull(js)
        assertTrue(js!!.contains("seekTime:42.5"))
        assertTrue(js.contains("a.currentTime=42.5"))
    }

    @Test
    fun `rewind subtracts 10 seconds`() {
        val js = WebViewActivity.buildCommandJs("rewind")
        assertNotNull(js)
        assertTrue(js!!.contains("currentTime-10"))
    }

    @Test
    fun `forward adds 10 seconds`() {
        val js = WebViewActivity.buildCommandJs("forward")
        assertNotNull(js)
        assertTrue(js!!.contains("currentTime+10"))
    }

    @Test
    fun `unknown command returns null`() {
        assertNull(WebViewActivity.buildCommandJs("unknown"))
        assertNull(WebViewActivity.buildCommandJs(""))
        assertNull(WebViewActivity.buildCommandJs("stop"))
    }
}
