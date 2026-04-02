package com.yaytsa.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import java.net.URI
import kotlin.concurrent.thread

class MediaPlaybackService : Service() {

    private lateinit var mediaSession: MediaSessionCompat
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentArtwork: Bitmap? = null
    private var currentArtworkUrl: String? = null
    var baseUrl: String = ""
    var commandCallback: ((String) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): MediaPlaybackService = this@MediaPlaybackService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yaytsa:playback")

        mediaSession = MediaSessionCompat(this, "YayTsaSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { commandCallback?.invoke("play") }
                override fun onPause() { commandCallback?.invoke("pause") }
                override fun onSkipToNext() { commandCallback?.invoke("nexttrack") }
                override fun onSkipToPrevious() { commandCallback?.invoke("previoustrack") }
                override fun onRewind() { commandCallback?.invoke("rewind") }
                override fun onFastForward() { commandCallback?.invoke("forward") }
                override fun onSeekTo(pos: Long) {
                    commandCallback?.invoke("seekto:${pos / 1000.0}")
                }
                override fun onStop() { commandCallback?.invoke("stop") }
            })
            isActive = true
        }

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(ALL_ACTIONS)
                .setState(PlaybackStateCompat.STATE_PAUSED, 0, 1.0f)
                .build()
        )

        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> commandCallback?.invoke("play")
            ACTION_PAUSE -> commandCallback?.invoke("pause")
            ACTION_NEXT -> commandCallback?.invoke("nexttrack")
            ACTION_PREV -> commandCallback?.invoke("previoustrack")
            ACTION_REW -> commandCallback?.invoke("rewind")
            ACTION_FWD -> commandCallback?.invoke("forward")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    fun updateMetadata(title: String, artist: String, album: String, artworkUrl: String) {
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)

        currentArtwork?.let {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
        }
        mediaSession.setMetadata(builder.build())
        updateNotification()

        val resolvedUrl = resolveUrl(artworkUrl, baseUrl)
        if (resolvedUrl.isNotEmpty() && resolvedUrl != currentArtworkUrl) {
            currentArtworkUrl = resolvedUrl
            thread {
                try {
                    val bitmap = BitmapFactory.decodeStream(URI(resolvedUrl).toURL().openStream())
                    if (bitmap != null) {
                        currentArtwork = bitmap
                        mediaSession.setMetadata(
                            MediaMetadataCompat.Builder()
                                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                                .build()
                        )
                        updateNotification()
                    }
                } catch (_: Exception) {
                    currentArtworkUrl = null
                }
            }
        }
    }

    fun updatePlaybackState(state: String, positionMs: Long, durationMs: Long, playbackRate: Float) {
        val pbState = when (state) {
            "playing" -> PlaybackStateCompat.STATE_PLAYING
            "paused" -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_NONE
        }

        if (pbState == PlaybackStateCompat.STATE_PLAYING) {
            if (wakeLock?.isHeld != true) wakeLock?.acquire(MAX_WAKE_LOCK_MS)
        } else {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(ALL_ACTIONS)
                .setState(pbState, positionMs, playbackRate)
                .build()
        )

        if (durationMs > 0) {
            mediaSession.controller?.metadata?.let { current ->
                mediaSession.setMetadata(
                    MediaMetadataCompat.Builder(current)
                        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
                        .build()
                )
            }
        }

        updateNotification()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val isPlaying = mediaSession.controller?.playbackState?.state == PlaybackStateCompat.STATE_PLAYING

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, WebViewActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val metadata = mediaSession.controller?.metadata
        val title = metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: "Yay-Tsa"
        val artist = metadata?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: ""

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(R.drawable.ic_music_note)
            .setLargeIcon(currentArtwork)
            .setContentIntent(contentIntent)
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(1, 2, 3)
            )
            .addAction(R.drawable.ic_skip_previous, "Previous", actionIntent(ACTION_PREV, 1))
            .addAction(R.drawable.ic_replay_10, "Rewind", actionIntent(ACTION_REW, 2))
            .addAction(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                if (isPlaying) "Pause" else "Play",
                actionIntent(if (isPlaying) ACTION_PAUSE else ACTION_PLAY, 3)
            )
            .addAction(R.drawable.ic_forward_10, "Forward", actionIntent(ACTION_FWD, 4))
            .addAction(R.drawable.ic_skip_next, "Next", actionIntent(ACTION_NEXT, 5))
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .build()
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, MediaPlaybackService::class.java).also { it.action = action },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Music Playback", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows current track and playback controls"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "yaytsa_playback"
        const val NOTIFICATION_ID = 1
        const val ACTION_PLAY = "com.yaytsa.app.ACTION_PLAY"
        const val ACTION_PAUSE = "com.yaytsa.app.ACTION_PAUSE"
        const val ACTION_NEXT = "com.yaytsa.app.ACTION_NEXT"
        const val ACTION_PREV = "com.yaytsa.app.ACTION_PREV"
        const val ACTION_REW = "com.yaytsa.app.ACTION_REW"
        const val ACTION_FWD = "com.yaytsa.app.ACTION_FWD"
        private const val MAX_WAKE_LOCK_MS = 4 * 60 * 60 * 1000L

        fun resolveUrl(url: String, baseUrl: String): String {
            if (url.isEmpty()) return ""
            if (url.startsWith("http://") || url.startsWith("https://")) return url
            if (url.startsWith("/") && baseUrl.isNotEmpty()) return baseUrl.trimEnd('/') + url
            return url
        }
    }
}

private const val ALL_ACTIONS =
    PlaybackStateCompat.ACTION_PLAY or
    PlaybackStateCompat.ACTION_PAUSE or
    PlaybackStateCompat.ACTION_PLAY_PAUSE or
    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
    PlaybackStateCompat.ACTION_REWIND or
    PlaybackStateCompat.ACTION_FAST_FORWARD or
    PlaybackStateCompat.ACTION_SEEK_TO or
    PlaybackStateCompat.ACTION_STOP
