package com.example.automix

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.content.Intent
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
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.io.ByteArrayOutputStream
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
    val folder: String = "",
    val album: String = "",
    val genre: String = "",
    val albumId: Long = -1L
)

/**
 * Automix: reproduce cada canción hasta [playPercent]% de su duración y hace un
 * crossfade (equal-power) de [fadeSec] segundos hacia la siguiente.
 */
class AutomixService : MediaSessionService() {

    companion object {
        private const val PRELOAD_LEAD_MS = 15_000
    }

    inner class LocalBinder : Binder() {
        val service: AutomixService get() = this@AutomixService
    }

    private val binder = LocalBinder()
    // Media3 usa onBind para sus controladores (acción "androidx.media3.session.MediaSessionService").
    // La Activity enlaza sin acción, así que a ella le damos el binder local.
    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == null) binder else super.onBind(intent)

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

    private class Preload(val mp: MediaPlayer, val idx: Int) {
        var ready = false
        var failed = false
    }

    // Ajustes
    var playPercent = 70
    var fadeSec = 8
    var skipIntroSec = 0
    var transitionStyle: String = "Desvanecer"
    var onChange: (() -> Unit)? = null

    var current: Track? = null
        private set
    var paused = false
        private set
    val isRunning: Boolean get() = running

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
    private var preload: Preload? = null
    private var wrapOrder: MutableList<Int>? = null
    private var sequential = false
    private val handler = Handler(Looper.getMainLooper())
    private var mediaSession: MediaSession? = null
    private var player: AutomixPlayer? = null
    private var artworkToken = 0

    /** Carátula (JPEG) de la canción actual para la notificación y la pantalla de bloqueo. */
    var currentArtwork: ByteArray? = null
        private set

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    // ---------- API pública ----------

    fun start(list: List<Track>, startAt: Int, shuffle: Boolean = true) {
        if (list.isEmpty()) return
        discardPreload()
        sequential = false
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

    fun startOrdered(list: List<Track>, startAt: Int) {
        if (list.isEmpty()) return
        discardPreload()
        tracks = list
        running = true
        errors = 0
        sequential = true
        order = list.indices.toMutableList()
        pos = startAt.coerceIn(0, list.size - 1)
        requestFocus()
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, 300)
        transitionTo(order[pos], 1500)
    }

    fun reorderQueue(list: List<Track>) {
        if (!running || list.isEmpty()) return
        val curUri = current?.uri
        discardPreload()
        tracks = list
        order = list.indices.toMutableList()
        sequential = true
        pos = list.indexOfFirst { it.uri == curUri }
    }

    fun startMashup(list: List<Track>, primaryIndex: Int, secondaryIndex: Int) {
        if (list.size < 2 || primaryIndex !in list.indices || secondaryIndex !in list.indices || primaryIndex == secondaryIndex) return
        loadingToken++
        handler.removeCallbacksAndMessages(null)
        fadeRunnable = null
        mashupFadeRunnable = null
        cur?.release(); cur = null
        out?.release(); out = null
        discardPreload()
        sequential = false
        current = null
        paused = false
        tracks = list
        order = list.indices.toMutableList()
        pos = primaryIndex
        running = true
        errors = 0
        requestFocus()
        val primary = Deck(MediaPlayer())
        val secondary = Deck(MediaPlayer())
        cur = primary
        out = secondary
        current = list[primaryIndex]
        currentArtwork = null
        loadArtworkAsync(list[primaryIndex])
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

    /** Usado por Media3 (notificación, pantalla de bloqueo, auriculares). */
    fun setPlaying(play: Boolean) {
        if (cur == null) return
        if (play && paused) resume() else if (!play && !paused) pause()
    }

    fun stopAll() {
        loadingToken++
        handler.removeCallbacksAndMessages(null)
        fadeRunnable = null
        mashupFadeRunnable = null
        cur?.release(); cur = null
        out?.release(); out = null
        discardPreload()
        current = null
        paused = false
        running = false
        currentArtwork = null
        artworkToken++
        abandonFocus()
        refreshSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        onChange?.invoke()
    }

    // 🆕 Progreso y seek
    /** Devuelve (posición actual en ms, duración total en ms). */
    fun getProgress(): Pair<Int, Int> {
        val c = cur ?: return 0 to 0
        return try {
            val pos = c.mp.currentPosition
            val dur = c.mp.duration.takeIf { it > 0 } ?: 0
            pos to dur
        } catch (_: Exception) {
            0 to 0
        }
    }

    /** Salta a la posición [ms] de la canción actual. */
    fun seekTo(ms: Int) {
        try { cur?.mp?.seekTo(ms) } catch (_: Exception) {}
    }

    /** Devuelve las próximas [limit] canciones en la cola (sin incluir la actual). */
    fun getUpcomingQueue(limit: Int = 2): List<Track> {
        if (!running || order.isEmpty() || tracks.isEmpty()) return emptyList()
        val result = mutableListOf<Track>()
        var i = 1
        while (result.size < limit && i <= order.size) {
            val idx = (pos + i) % order.size
            tracks.getOrNull(order[idx])?.let { result.add(it) }
            i++
        }
        return result
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
            if (!sequential) {
                order = wrapOrder ?: tracks.indices.shuffled().toMutableList()
                wrapOrder = null
            }
            pos = 0
        }
        transitionTo(order[pos], ms)
    }

    private fun preloadNext() {
        if (!running || tracks.isEmpty() || preload != null) return
        val nextIdx = if (pos + 1 < order.size) {
            order[pos + 1]
        } else if (sequential) {
            order[0]
        } else {
            val w = wrapOrder ?: tracks.indices.shuffled().toMutableList().also { wrapOrder = it }
            w[0]
        }
        val track = tracks.getOrNull(nextIdx) ?: return
        val mp = MediaPlayer()
        val pl = Preload(mp, nextIdx)
        try {
            mp.setAudioAttributes(attrs)
            mp.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(applicationContext, track.uri)
            mp.setOnPreparedListener { pl.ready = true }
            mp.setOnErrorListener { p, _, _ ->
                pl.failed = true
                if (preload === pl) preload = null
                try { p.release() } catch (_: Exception) {}
                true
            }
            preload = pl
            mp.prepareAsync()
        } catch (_: Exception) {
            preload = null
            try { mp.release() } catch (_: Exception) {}
        }
    }

    private fun discardPreload() {
        preload?.let { try { it.mp.release() } catch (_: Exception) {} }
        preload = null
        wrapOrder = null
    }

    private fun attachPlaybackListeners(mp: MediaPlayer, token: Int) {
        mp.setOnCompletionListener { p -> if (cur?.mp === p) advance(1000) }
        mp.setOnErrorListener { p, _, _ -> onPlayerFailed(p, token); true }
    }

    private fun transitionTo(idx: Int, ms: Int) {
        val track = tracks[idx]
        val token = ++loadingToken
        triggerAtMs = Int.MAX_VALUE

        val pre = preload
        preload = null
        if (pre != null && pre.idx == idx && !pre.failed) {
            attachPlaybackListeners(pre.mp, token)
            if (pre.ready) {
                begin(Deck(pre.mp), track, ms)
            } else {
                pre.mp.setOnPreparedListener { p ->
                    if (token != loadingToken) p.release() else begin(Deck(p), track, ms)
                }
            }
            return
        }
        if (pre != null) try { pre.mp.release() } catch (_: Exception) {}

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
            attachPlaybackListeners(mp, token)
            mp.prepareAsync()
        } catch (_: Exception) {
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
        currentArtwork = null
        loadArtworkAsync(track)

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
            var delay = 300L
            if (c != null && !paused) {
                try {
                    if (c.mp.isPlaying) {
                        val position = c.mp.currentPosition
                        if (position >= triggerAtMs) {
                            advance(autoFadeMs)
                        } else {
                            if (preload == null && position >= triggerAtMs - PRELOAD_LEAD_MS) preloadNext()
                            val remaining = triggerAtMs - position
                            if (remaining in 1..299) delay = remaining.toLong().coerceAtLeast(50L)
                            // Notificar a la UI cada ~500ms para avanzar la barra de progreso.
                            // (La posición en la notificación la extrapola Media3.)
                            if (position % 500 < 300) onChange?.invoke()
                        }
                    }
                } catch (_: Exception) {}
            }
            handler.postDelayed(this, delay)
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

    // ---------- Media3: sesión, notificación y pantalla de bloqueo ----------

    override fun onCreate() {
        super.onCreate()
        val p = AutomixPlayer(this)
        player = p
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_stat_automix)
            }
        )
        val session = MediaSession.Builder(this, p).build()
        mediaSession = session
        addSession(session)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    /** Pide a Media3 que relea título, carátula, pausa y posición. */
    private fun refreshSession() {
        try { player?.refresh() } catch (_: Exception) {}
    }

    private fun updateNotification() = refreshSession()

    /** Carga la carátula fuera del hilo principal y actualiza la sesión al terminar. */
    private fun loadArtworkAsync(track: Track) {
        val token = ++artworkToken
        Thread {
            val bytes = try {
                loadAlbumArtwork(track.albumId)?.let { bmp ->
                    val side = max(bmp.width, bmp.height)
                    val scaled = if (side > 512) {
                        val f = 512f / side
                        Bitmap.createScaledBitmap(
                            bmp,
                            max(1, (bmp.width * f).toInt()),
                            max(1, (bmp.height * f).toInt()),
                            true
                        )
                    } else bmp
                    ByteArrayOutputStream().use { out ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        out.toByteArray()
                    }
                }
            } catch (_: Throwable) { null }
            handler.post {
                if (token == artworkToken && current?.uri == track.uri) {
                    currentArtwork = bytes
                    refreshSession()
                }
            }
        }.start()
    }

    private fun loadAlbumArtwork(albumId: Long): android.graphics.Bitmap? {
        if (albumId <= 0L) return null
        // Primero usar el URI público de carátulas de MediaStore; es más fiable
        // que depender de la ruta privada/deprecada ALBUM_ART.
        try {
            val artUri = android.content.ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"), albumId
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.loadThumbnail(artUri, android.util.Size(512, 512), null)?.let { return it }
            } else {
                @Suppress("DEPRECATION")
                contentResolver.openInputStream(artUri)?.use { input ->
                    BitmapFactory.decodeStream(input)?.let { return it }
                }
            }
        } catch (_: Exception) { }

        // Compatibilidad con dispositivos que aún exponen la ruta ALBUM_ART.
        return try {
            contentResolver.query(
                MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Albums.ALBUM_ART),
                "${MediaStore.Audio.Albums._ID} = ?",
                arrayOf(albumId.toString()), null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val path = cursor.getString(0)
                    if (!path.isNullOrBlank()) BitmapFactory.decodeFile(path) else null
                } else null
            }
        } catch (_: Exception) { null }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        artworkToken++
        cur?.release(); cur = null
        out?.release(); out = null
        discardPreload()
        abandonFocus()
        val session = mediaSession
        val p = player
        mediaSession = null
        player = null
        session?.release()
        p?.release()
        super.onDestroy()
    }
}
