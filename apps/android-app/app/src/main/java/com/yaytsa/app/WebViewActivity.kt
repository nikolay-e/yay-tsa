package com.yaytsa.app

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

class WebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var mediaService: MediaPlaybackService? = null
    private var serviceBound = false
    private var serviceStarted = false
    private val handler = Handler(Looper.getMainLooper())
    private var polling = false

    private var lastTitle = ""
    private var lastArtist = ""
    private var lastAlbum = ""
    private var lastArtwork = ""
    private var lastState = ""

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as MediaPlaybackService.LocalBinder).getService()
            mediaService = service
            serviceBound = true
            service.baseUrl = intent.getStringExtra(EXTRA_HOST_URL) ?: ""
            service.commandCallback = { command -> runOnUiThread { dispatchCommand(command) } }

            if (lastTitle.isNotEmpty()) {
                service.updateMetadata(lastTitle, lastArtist, lastAlbum, lastArtwork)
            }
            if (lastState.isNotEmpty()) {
                service.updatePlaybackState(lastState, 0, 0, 1.0f)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mediaService = null
            serviceBound = false
        }
    }

    private fun dispatchCommand(command: String) {
        val js = buildCommandJs(command) ?: return
        webView.evaluateJavascript(js, null)
    }

    private val pollRunnable: Runnable = Runnable {
        if (!polling) return@Runnable
        pollMediaSession()
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    private fun pollMediaSession() {
        webView.evaluateJavascript(POLL_JS) { raw ->
            if (raw != null && raw != "null") {
                handlePollResult(raw)
            }
        }
    }

    private fun handlePollResult(raw: String) {
        val json = try {
            JSONObject(unescapeJs(raw))
        } catch (_: Exception) {
            return
        }
        if (json.has("err")) return

        val title = json.optString("t", "")
        val artist = json.optString("a", "")
        val album = json.optString("al", "")
        val artwork = json.optString("art", "")
        val state = json.optString("s", "none")
        val positionMs = (json.optDouble("pos", 0.0) * 1000).toLong()
        val durationMs = (json.optDouble("dur", 0.0) * 1000).toLong()

        if (title.isNotEmpty() && state == "playing") {
            ensureServiceStarted()
        }

        val metadataChanged = title != lastTitle || artist != lastArtist || album != lastAlbum || artwork != lastArtwork
        if (title.isNotEmpty() && metadataChanged) {
            lastTitle = title; lastArtist = artist; lastAlbum = album; lastArtwork = artwork
            mediaService?.updateMetadata(title, artist, album, artwork)
        }

        if (state != lastState || state == "playing") {
            lastState = state
            mediaService?.updatePlaybackState(state, positionMs, durationMs, 1.0f)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview)
        hideStatusBar()
        requestNotificationPermission()

        val hostUrl = intent.getStringExtra(EXTRA_HOST_URL) ?: ""
        webView = findViewById(R.id.webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                view?.evaluateJavascript(EARLY_HOOK_JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                startPolling()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean = true
        }

        webView.loadUrl(hostUrl)
    }

    private fun hideStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.statusBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100
            )
        }
    }

    private fun ensureServiceStarted() {
        if (serviceStarted) return
        serviceStarted = true
        val serviceIntent = Intent(this, MediaPlaybackService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun startPolling() {
        if (!polling) {
            polling = true
            handler.post(pollRunnable)
        }
    }

    private fun stopPolling() {
        polling = false
        handler.removeCallbacks(pollRunnable)
    }

    override fun onDestroy() {
        stopPolling()
        if (serviceBound) {
            mediaService?.commandCallback = null
            unbindService(serviceConnection)
            serviceBound = false
        }
        if (serviceStarted) {
            stopService(Intent(this, MediaPlaybackService::class.java))
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java in favor of OnBackPressedCallback")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    companion object {
        const val EXTRA_HOST_URL = "HOST_URL"
        private const val POLL_INTERVAL_MS = 1000L

        fun unescapeJs(raw: String): String {
            return if (raw.startsWith("\"")) {
                raw.substring(1, raw.length - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
            } else raw
        }

        fun buildCommandJs(command: String): String? = when {
            command.startsWith("seekto:") -> {
                val sec = command.substringAfter("seekto:")
                "(function(){var h=(window._yaytsa_handlers||{})['seekto'];if(h){try{h({seekTime:$sec});}catch(e){}return;}var a=document.querySelector('audio');if(a)a.currentTime=$sec;})();"
            }
            command == "play" ->
                "(function(){var h=(window._yaytsa_handlers||{})['play'];if(h){try{h();}catch(e){}return;}var a=document.querySelector('audio');if(a)a.play();})();"
            command == "pause" ->
                "(function(){var h=(window._yaytsa_handlers||{})['pause'];if(h){try{h();}catch(e){}return;}var a=document.querySelector('audio');if(a)a.pause();})();"
            command == "nexttrack" ->
                "(function(){var h=(window._yaytsa_handlers||{})['nexttrack'];if(h){try{h();}catch(e){}return;}})();"
            command == "previoustrack" ->
                "(function(){var h=(window._yaytsa_handlers||{})['previoustrack'];if(h){try{h();}catch(e){}return;}var a=document.querySelector('audio');if(a)a.currentTime=0;})();"
            command == "rewind" ->
                "(function(){var a=document.querySelector('audio');if(a)a.currentTime=Math.max(0,a.currentTime-10);})();"
            command == "forward" ->
                "(function(){var a=document.querySelector('audio');if(a)a.currentTime=Math.min(a.duration||0,a.currentTime+10);})();"
            else -> null
        }

        private val POLL_JS = """
            (function() {
                var ms = navigator.mediaSession;
                if (!ms) return JSON.stringify({err:'no_ms'});
                var m = ms.metadata;
                if (!m) return JSON.stringify({err:'no_meta'});
                var art = '';
                if (m.artwork && m.artwork.length > 0) art = m.artwork[m.artwork.length - 1].src || '';
                var audios = document.querySelectorAll('audio');
                var audioPlaying = false;
                for (var i = 0; i < audios.length; i++) {
                    if (!audios[i].paused) { audioPlaying = true; break; }
                }
                var s = ms.playbackState;
                if (!s || s === 'none') s = audioPlaying ? 'playing' : 'paused';
                var pos = 0, dur = 0;
                for (var i = 0; i < audios.length; i++) {
                    if (!audios[i].paused || audios[i].currentTime > 0) {
                        pos = audios[i].currentTime || 0;
                        dur = audios[i].duration || 0;
                        if (isNaN(dur)) dur = 0;
                        break;
                    }
                }
                return JSON.stringify({t:m.title||'',a:m.artist||'',al:m.album||'',art:art,s:s,pos:pos,dur:dur});
            })();
        """.trimIndent()

        val EARLY_HOOK_JS = """
            (function() {
                if (window._yaytsa_early) return;
                window._yaytsa_early = true;

                if (typeof MediaMetadata === 'undefined') {
                    window.MediaMetadata = function MediaMetadata(init) {
                        this.title  = (init && init.title)  || '';
                        this.artist = (init && init.artist) || '';
                        this.album  = (init && init.album)  || '';
                        this.artwork = (init && init.artwork) || [];
                    };
                }

                window._yaytsa_handlers = {};
                var real = navigator.mediaSession || null;

                var session = {
                    metadata: null,
                    playbackState: 'none',
                    setActionHandler: function(action, h) {
                        if (h) { window._yaytsa_handlers[action] = h; }
                        else { delete window._yaytsa_handlers[action]; }
                        if (real) { try { real.setActionHandler(action, h); } catch(e) {} }
                    },
                    setPositionState: function(state) {
                        if (real) { try { real.setPositionState(state); } catch(e) {} }
                    }
                };

                try {
                    Object.defineProperty(navigator, 'mediaSession', {
                        get: function() { return session; },
                        set: function() {},
                        configurable: true,
                        enumerable: true
                    });
                } catch(e) {
                    try { navigator.mediaSession = session; } catch(e2) {}
                }
            })();
        """.trimIndent()
    }
}
