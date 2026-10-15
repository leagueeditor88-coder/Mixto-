package com.example.automix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class Track(
    val uri: Uri,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val folder: String = ""
)

/**
 * Automix: reproduce cada canción hasta [playPercent]% de su duración y hace un
 * crossfade (equal-power) de [fadeSec] segundos hacia la siguiente. También admite
 * un mashup básico opcional de dos pistas con superposición temporal.
 */
class AutomixService : Service() {

    companion object {
        const val ACTION_PAUSE = "com.example.automix.PAUSE"
        const val ACTION_NEXT = "com.example.automix.NEXT"
        const val ACTION_PREV = "com.example.automix.PREV"
        private const val CHANNEL = "automix"
        private const val NOTIF_ID = 1
    }

    inner class LocalBinder : Binder() {
        val service: AutomixService get() = this@AutomixService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    private class Deck(val mp: MediaPlayer) {
        var vol = 0f
        fun set(v: Float) {
            vol = v
            try { mp.setVolume(v, v) } catch (_: Exception) {}
        }
        fun release() {
            try { mp.release() } catch (_: Exception) {}
        }
    }

    // Ajustes (los cambia la Activity)
    var playPercent = 70
    var fadeSec = 8
    var skipIntroSec = 0
    var transitionStyle: String = "Desvanecer"
    var onChange: (() -> Unit)? = null

    var current: Track? = null
        private set
    var paused = false
        private set

    private var running = false
    private var tracks: List<Track> = emptyList()
    private var order: MutableList<Int> = mutableListOf()
    private var pos = -1
    private var cur: Deck? = null
    private var out: Deck? = null
    private var loadingToken = 0
    private var errors = 0
    private var triggerAtMs = Int.MAX_VALUE
    private var autoFadeMs = 8000
    private var fadeRunnable: Runnable? = null
    private var pausedByFocus = false
    private var focusReq: AudioFocusRequest? = null
    private var mashupFadeRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    // ---------- API pública ----------

    fun start(list: List<Track>, startAt: Int, shuffle: Boolean = true) {
        if (list.isEmpty()) return
        tracks = list
        running = true
        errors = 0
        buildOrder(startAt, shuffle)
        pos = 0
        requestFocus()
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, 300)
        transitionTo(order[0], 1500)
    }

    /** Starts a basic two-track mashup: both tracks play at once, then the second fades out. */
    fun startMashup(list: List<Track>, primaryIndex: Int, secondaryIndex: Int) {
        if (list.size < 2 || primaryIndex !in list.indices || secondaryIndex !in list.indices || primaryIndex == secondaryIndex) return
        loadingToken++
        handler.removeCallbacksAndMessages(null)
        fadeRunnable = null
        mashupFadeRunnable = null
        cur?.release(); cur = null
        out?.release(); out = null
        current = null
        paused = false
        tracks = list
        order = list.indices.toMutableList()
        pos = primaryIndex
        running = true
        errors = 0
        requestFocus()
        ensureForeground()
        val primary = Deck(MediaPlayer())
        val secondary = Deck(MediaPlayer())
        cur = primary
        out = secondary
        current = list[primaryIndex]
        var ready = 0
        var failed = false
        fun readyToPlay() {
            ready++
            if (ready != 2 || failed) return
            try {
                primary.set(0.78f)
                secondary.set(0f)
                primary.mp.start()
                secondary.mp.start()
                val start = SystemClock.uptimeMillis()
                val fadeIn = object : Runnable {
                    override fun run() {
                        val progress = ((SystemClock.uptimeMillis() - start).toFloat() / 3500f).coerceIn(0f, 1f)
                        secondary.set(0.62f * progress)
                        if (progress < 1f) handler.postDelayed(this, 40)
                    }
                }
                handler.post(fadeIn)
                val hold = object : Runnable {
                    override fun run() {
                        val t0 = SystemClock.uptimeMillis()
                        val fadeOut = object : Runnable {
                            override fun run() {
                                val progress = ((SystemClock.uptimeMillis() - t0).toFloat() / 5000f).coerceIn(0f, 1f)
                                secondary.set(0.62f * (1f - progress))
                                if (progress >= 1f) {
                                    secondary.release()
                                    if (out === secondary) out = null
                                    mashupFadeRunnable = null
                                } else handler.postDelayed(this, 40)
                            }
                        }
                        mashupFadeRunnable = fadeOut
                        handler.post(fadeOut)
                    }
                }
                mashupFadeRunnable = hold
                handler.postDelayed(hold, 30000)
                val duration = primary.mp.duration.takeIf { it > 0 } ?: list[primaryIndex].durationMs.toInt()
                val fade = min(fadeSec * 1000, duration / 4)
                autoFadeMs = fade
                val skip = min(skipIntroSec * 1000, duration / 4)
                triggerAtMs = ((duration.toLong() * playPercent / 100).toInt() - fade).coerceAtLeast(skip + fade)
                handler.removeCallbacks(poll)
                handler.postDelayed(poll, 300)
                paused = false
                updateNotification()
                onChange?.invoke()
            } catch (_: Exception) {
                stopAll()
            }
        }
        try {
            primary.mp.setAudioAttributes(attrs)
            primary.mp.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            primary.mp.setDataSource(applicationContext, list[primaryIndex].uri)
            primary.mp.setOnPreparedListener { readyToPlay() }
            primary.mp.setOnErrorListener { _, _, _ -> failed = true; stopAll(); true }
            secondary.mp.setAudioAttributes(attrs)
            secondary.mp.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            secondary.mp.setDataSource(applicationContext, list[secondaryIndex].uri)
            secondary.mp.setOnPreparedListener { readyToPlay() }
            secondary.mp.setOnErrorListener { _, _, _ -> failed = true; stopAll(); true }
            primary.mp.prepareAsync()
            secondary.mp.prepareAsync()
        } catch (_: Exception) {
            stopAll()
        }
    }

    fun next() {
        if (running) advance(2000)
    }

    fun previous() {
        if (!running || tracks.isEmpty()) return
        pos = max(0, pos - 1)
        transitionTo(order[pos], 2000)
    }

    fun togglePause() {
        if (cur == null) return
        if (paused) resume() else pause()
    }

    fun stopAll() {
        loadingToken++
        handler.removeCallbacksAndMessages(null)
        fadeRunnable = null
        mashupFadeRunnable = null
        cur?.release(); cur = null
        out?.release(); out = null
        current = null
        paused = false
        running = false
        abandonFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        onChange?.invoke()
    }

    // ---------- Lógica de Automix ----------

    private fun buildOrder(first: Int, shuffle: Boolean = true) {
        order = if (shuffle) tracks.indices.shuffled().toMutableList() else tracks.indices.toMutableList()
        if (first in tracks.indices) {
            order.remove(first)
            order.add(0, first)
        }
    }

    private fun advance(ms: Int) {
        if (tracks.isEmpty()) return
        pos++
        if (pos >= order.size) {
            buildOrder(-1, true)
            pos = 0
        }
        transitionTo(order[pos], ms)
    }

    private fun transitionTo(idx: Int, ms: Int) {
        val track = tracks[idx]
        val token = ++loadingToken
        triggerAtMs = Int.MAX_VALUE
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(attrs)
            mp.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(applicationContext, track.uri)
            mp.setOnPreparedListener { p ->
                if (token != loadingToken) {
                    p.release()
                } else {
                    begin(Deck(p), track, ms)
                }
            }
            mp.setOnCompletionListener { p -> if (cur?.mp === p) advance(1000) }
            mp.setOnErrorListener { p, _, _ -> onPlayerFailed(p, token); true }
            mp.prepareAsync()
        } catch (e: Exception) {
            try { mp.release() } catch (_: Exception) {}
            if (token == loadingToken) handler.post { failAndSkip() }
        }
    }

    private fun onPlayerFailed(p: MediaPlayer, token: Int) {
        val isCur = cur?.mp === p
        if (!isCur && out?.mp === p) return
        try { p.release() } catch (_: Exception) {}
        if (!isCur && token != loadingToken) return
        if (isCur) cur = null
        failAndSkip()
    }

    private fun failAndSkip() {
        if (++errors > min(tracks.size, 10)) {
            stopAll()
        } else {
            advance(0)
        }
    }

    private fun begin(deck: Deck, track: Track, ms: Int) {
        errors = 0
        finishFade()
        val old = cur
        cur = deck
        current = track

        val d = deck.mp.duration.takeIf { it > 0 } ?: track.durationMs.toInt()
        val fade = min(fadeSec * 1000, d / 4)
        autoFadeMs = fade
        val skip = min(skipIntroSec * 1000, d / 4)
        val target = (d.toLong() * playPercent / 100).toInt() - fade
        val lo = skip + fade
        triggerAtMs = target.coerceIn(lo, max(lo, d - fade - 300))

        deck.set(0f)
        if (skip > 0) {
            try { deck.mp.seekTo(skip.toLong(), MediaPlayer.SEEK_CLOSEST) } catch (_: Exception) {}
        }
        deck.mp.start()
        paused = false
        out = old
        runFade(old, deck, ms)
        updateNotification()
        onChange?.invoke()
    }

    private fun finishFade() {
        fadeRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable = null
        mashupFadeRunnable?.let { handler.removeCallbacks(it) }
        mashupFadeRunnable = null
        out?.release()
        out = null
    }

    private fun runFade(from: Deck?, to: Deck, ms: Int) {
        if (ms <= 0) {
            to.set(1f)
            from?.release()
            out = null
            return
        }
        val fromStart = from?.vol ?: 0f
        val toStart = to.vol
        val t0 = SystemClock.uptimeMillis()
        val r = object : Runnable {
            override fun run() {
                val p = ((SystemClock.uptimeMillis() - t0).toFloat() / ms).coerceIn(0f, 1f)
                val a = PI / 2 * p.toDouble()
                if (transitionStyle.equals("Ascender", true)) {
                    to.set(toStart + (1f - toStart) * (p * p).coerceIn(0f, 1f))
                    from?.set(fromStart * (1f - p))
                } else {
                    to.set(toStart + (1f - toStart) * sin(a).toFloat())
                    from?.set(fromStart * cos(a).toFloat())
                }
                if (p >= 1f) {
                    from?.release()
                    if (out === from) out = null
                    fadeRunnable = null
                } else {
                    handler.postDelayed(this, 40)
                }
            }
        }
        fadeRunnable = r
        handler.post(r)
    }

    private val poll = object : Runnable {
        override fun run() {
            val c = cur
            if (c != null && !paused) {
                try {
                    if (c.mp.isPlaying && c.mp.currentPosition >= triggerAtMs) advance(autoFadeMs)
                } catch (_: Exception) {}
            }
            handler.postDelayed(this, 300)
        }
    }

    private fun pause() {
        try { cur?.mp?.pause() } catch (_: Exception) {}
        try { out?.mp?.pause() } catch (_: Exception) {}
        paused = true
        updateNotification()
        onChange?.invoke()
    }

    private fun resume() {
        try { cur?.mp?.start() } catch (_: Exception) {}
        try { out?.mp?.start() } catch (_: Exception) {}
        paused = false
        updateNotification()
        onChange?.invoke()
    }

    // ---------- Audio focus ----------

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (!paused && cur != null) {
                    pausedByFocus = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    pause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedByFocus) {
                    pausedByFocus = false
                    resume()
                }
            }
        }
    }

    private fun requestFocus() {
        val am = getSystemService(AudioManager::class.java)
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusReq = req
        am.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        focusReq?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focusReq = null
    }

    // ---------- Notificación / servicio en primer plano ----------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        when (intent?.action) {
            ACTION_PAUSE -> togglePause()
            ACTION_NEXT -> next()
            ACTION_PREV -> previous()
        }
        return START_NOT_STICKY
    }

    private fun ensureForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Reproducción", NotificationManager.IMPORTANCE_LOW)
        )
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        fun act(action: String, icon: Int, label: String): Notification.Action {
            val pi = PendingIntent.getService(
                this, action.hashCode(),
                Intent(this, AutomixService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE
            )
            return Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build()
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(current?.title ?: "Automix")
            .setContentText(current?.artist ?: "Listo")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(act(ACTION_PREV, android.R.drawable.ic_media_previous, "Anterior"))
            .addAction(
                act(
                    ACTION_PAUSE,
                    if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                    if (paused) "Reanudar" else "Pausa"
                )
            )
            .addAction(act(ACTION_NEXT, android.R.drawable.ic_media_next, "Siguiente"))
            .build()
    }

    private fun updateNotification() {
        if (running) {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        cur?.release(); cur = null
        out?.release(); out = null
        abandonFocus()
        super.onDestroy()
    }
}
