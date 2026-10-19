package com.example.automix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import java.util.concurrent.Executors
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.ConcurrentHashMap

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
 * Motor de reproducción de Automix.
 * En sesión AUTOMIX ([setDjSyncEnabled] = true) usa la rejilla rítmica disponible,
 * el planificador de transiciones y beatmatching conservador. El porcentaje indica
 * el punto objetivo inicial de transición; no detecta por sí solo frases vocales.
 * Fuera de AUTOMIX conserva la reproducción normal y el crossfade configurado.
 */
class AutomixService : Service() {

    companion object {
        const val ACTION_PAUSE = "com.example.automix.PAUSE"
        const val ACTION_NEXT = "com.example.automix.NEXT"
        const val ACTION_PREV = "com.example.automix.PREV"
        private const val CHANNEL = "automix"
        private const val NOTIF_ID = 1
        private const val PRELOAD_LEAD_MS = 15_000
    }

    inner class LocalBinder : Binder() {
        val service: AutomixService get() = this@AutomixService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    private class Deck(val mp: ExoPlayer) {
        var vol = 0f
        /** Ganancia de sonoridad por pista; el crossfade sigue controlando vol. */
        var loudnessGain = 1f
        var trackUri: Uri? = null
        fun set(v: Float) {
            vol = v
            try { mp.volume = (v * loudnessGain).coerceIn(0f, 1f) } catch (_: Exception) {}
        }
        fun applyLoudnessGain(gain: Float) {
            loudnessGain = gain.coerceIn(0.65f, 1.35f)
            try { mp.volume = (vol * loudnessGain).coerceIn(0f, 1f) } catch (_: Exception) {}
        }
        fun release() {
            try { mp.release() } catch (_: Exception) {}
        }
    }

    private class Preload(val mp: ExoPlayer, val idx: Int) {
        var ready = false
        var failed = false
    }

    // Ajustes
    var playPercent = 70
    var fadeSec = 8
    var skipIntroSec = 0
    var transitionStyle: String = "Desvanecer"
    // Estilo efectivo para la transición actual; no sobrescribe la preferencia manual.
    @Volatile private var activeTransitionStyle: String = "Desvanecer"
    private var transitionGraceUsedForTrack = false
    var onChange: (() -> Unit)? = null

    // La sincronización rítmica depende del estado de la sesión AUTOMIX, no del
    // criterio de orden de biblioteca (Aleatorio/Título/Artista/BPM).
    @Volatile private var djSyncEnabled: Boolean = false

    fun setDjSyncEnabled(enabled: Boolean) {
        djSyncEnabled = enabled
        if (!enabled) {
            activeTransitionStyle = transitionStyle
            // La pista activa vuelve gradualmente a velocidad normal al salir de DJ Sync.
            cur?.let { rampPlaybackSpeed(it.mp, 1f) }
        }
    }

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
    // Punto fijo de inicio y plazo máximo de búsqueda para la canción actual.
    // Los análisis tardíos o el ajuste al beat no deben reiniciar los 15 segundos.
    private var transitionSearchStartMs = Int.MAX_VALUE
    private var transitionSearchDeadlineMs = Int.MAX_VALUE
    private var autoFadeMs = 8000

    private data class TransitionTiming(val triggerAtMs: Int, val fadeMs: Int)

    /** Calcula el cambio dentro del tramo audible, limitando el crossfade para pistas cortas. */
    private fun calculateTransitionTiming(
        durationMs: Long,
        audibleStartMs: Long,
        audibleEndMs: Long
    ): TransitionTiming {
        val duration = durationMs.coerceAtLeast(1L)
        val start = audibleStartMs.coerceIn(0L, duration)
        val end = audibleEndMs.coerceIn(start, duration)
        val activeLength = (end - start).coerceAtLeast(1L)
        val configuredFade = fadeSec.coerceAtLeast(0).toLong() * 1000L
        val fade = min(configuredFade, activeLength / 4L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val percent = playPercent.coerceIn(1, 100).toLong()
        val target = start + (activeLength * percent / 100L)
        val lower = (start + fade).coerceAtMost(duration)
        val upper = (end - fade - 250L).coerceAtLeast(lower).coerceAtMost(duration)
        return TransitionTiming(target.coerceIn(lower, upper).toInt(), fade)
    }
    private var fadeRunnable: Runnable? = null
    // Ajuste de tempo progresivo para evitar cambios bruscos de velocidad.
    private var beatmatchRunnable: Runnable? = null
    private var beatmatchGeneration = 0
    private var pausedByFocus = false
    private var focusReq: AudioFocusRequest? = null
    private var mashupFadeRunnable: Runnable? = null
    private var preload: Preload? = null
    private var preloadRetryAfterUptimeMs = 0L
    private var wrapOrder: MutableList<Int>? = null
    private var sequential = false
    private val handler = Handler(Looper.getMainLooper())
    private var mediaSession: MediaSessionCompat? = null
    // El análisis se ejecuta fuera del hilo principal y se reutiliza por URI.
    private val boundaryExecutor = Executors.newSingleThreadExecutor()
    private val boundaryCache = ConcurrentHashMap<String, AudioBoundaryDetector.Boundaries>()
    private val structureCache = ConcurrentHashMap<String, TrackStructureAnalyzer.Analysis>()
    private val boundaryAttempted = ConcurrentHashMap.newKeySet<String>()
    // Normalización moderada por RMS/peak, analizada offline y almacenada por URI.
    private val loudnessExecutor = Executors.newSingleThreadExecutor()
    private val loudnessCache = ConcurrentHashMap<String, AudioLoudnessAnalyzer.Result>()
    private val loudnessAttempted = ConcurrentHashMap.newKeySet<String>()
    private val loudnessRamps = ConcurrentHashMap<Deck, Runnable>()
    // Rejilla rítmica estimada en segundo plano; no bloquea la reproducción.
    private val beatGridExecutor = Executors.newSingleThreadExecutor()
    private val beatGridCache = ConcurrentHashMap<String, BeatGridDetector.BeatGrid>()
    private val beatGridAttempted = ConcurrentHashMap.newKeySet<String>()
    // Ajuste moderado de velocidad para aproximar los BPM durante la transición.
    // Se limita para evitar cambios audibles excesivos.
    private val minBeatmatchSpeed = 0.94f
    private val maxBeatmatchSpeed = 1.06f

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val playerAttrs = androidx.media3.common.AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    /** Crea un deck Media3. El servicio conserva el control de audio focus. */
    private fun createPlayer(): ExoPlayer = ExoPlayer.Builder(applicationContext).build().apply {
        setAudioAttributes(playerAttrs, false)
        repeatMode = Player.REPEAT_MODE_OFF
        volume = 0f
    }

    private fun preparePlayer(player: ExoPlayer, uri: Uri) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
    }

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
        loudnessRamps.values.forEach { handler.removeCallbacks(it) }
        loudnessRamps.clear()
        beatmatchGeneration++
        beatmatchRunnable = null
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
        ensureForeground()
        val primary = Deck(createPlayer())
        val secondary = Deck(createPlayer())
        primary.trackUri = list[primaryIndex].uri
        secondary.trackUri = list[secondaryIndex].uri
        applyCachedLoudness(primary, list[primaryIndex])
        applyCachedLoudness(secondary, list[secondaryIndex])
        analyzeLoudness(primary, list[primaryIndex])
        analyzeLoudness(secondary, list[secondaryIndex])
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
                primary.mp.play()
                secondary.mp.play()
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
                val duration = primary.mp.duration.takeIf { it > 0L }?.toInt() ?: list[primaryIndex].durationMs.toInt()
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
        var primaryReady = false
        var secondaryReady = false
        fun listener(isPrimary: Boolean) = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    if (isPrimary && !primaryReady) { primaryReady = true; readyToPlay() }
                    if (!isPrimary && !secondaryReady) { secondaryReady = true; readyToPlay() }
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                failed = true
                stopAll()
            }
        }
        try {
            primary.mp.addListener(listener(true))
            secondary.mp.addListener(listener(false))
            preparePlayer(primary.mp, list[primaryIndex].uri)
            preparePlayer(secondary.mp, list[secondaryIndex].uri)
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
        loudnessRamps.clear()
        beatmatchGeneration++
        beatmatchRunnable = null
        fadeRunnable = null
        mashupFadeRunnable = null
        cur?.release(); cur = null
        out?.release(); out = null
        discardPreload()
        current = null
        paused = false
        running = false
        abandonFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        onChange?.invoke()
    }

    // 🆕 Progreso y seek
    /** Devuelve (posición actual en ms, duración total en ms). */
    fun getProgress(): Pair<Int, Int> {
        val c = cur ?: return 0 to 0
        return try {
            val pos = c.mp.currentPosition.toInt()
            val dur = c.mp.duration.takeIf { it > 0L }?.toInt() ?: 0
            pos to dur
        } catch (_: Exception) {
            0 to 0
        }
    }

    /** Salta a la posición [ms] de la canción actual. */
    fun seekTo(ms: Int) {
        try { cur?.mp?.seekTo(ms.toLong()) } catch (_: Exception) {}
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
        // En DJ, decide automáticamente estilo y duración usando las rejillas ya analizadas.
        // Si faltan datos fiables, el planificador conserva una decisión conservadora.
        val planned = if (djSyncEnabled) planNextTransition() else null
        val effectiveFadeMs = planned?.fadeMs ?: ms
        activeTransitionStyle = transitionStyle
        pos++
        if (pos >= order.size) {
            if (!sequential) {
                order = wrapOrder ?: tracks.indices.shuffled().toMutableList()
                wrapOrder = null
            }
            pos = 0
        }
        transitionTo(order[pos], effectiveFadeMs)
    }

    private fun planNextTransition(): TransitionPlanner.Plan? {
        if (tracks.isEmpty() || order.isEmpty() || pos !in order.indices) return null
        val nextIdx = if (pos + 1 < order.size) order[pos + 1] else if (sequential) order.firstOrNull() else (wrapOrder ?: tracks.indices.shuffled().toMutableList().also { wrapOrder = it }).firstOrNull()
        val outgoingTrack = tracks.getOrNull(order[pos]) ?: return null
        val incomingTrack = tracks.getOrNull(nextIdx ?: return null) ?: return null
        val outgoingGrid = beatGridCache[outgoingTrack.uri.toString()]
        val incomingGrid = beatGridCache[incomingTrack.uri.toString()]
        return TransitionPlanner.plan(outgoingGrid, incomingGrid, fadeSec, transitionStyle)
    }

    private fun preloadNext() {
        if (!running || tracks.isEmpty() || preload != null) return
        // Evita crear y destruir el reproductor repetidamente si una pista no se puede precargar.
        if (SystemClock.uptimeMillis() < preloadRetryAfterUptimeMs) return
        val nextIdx = if (pos + 1 < order.size) {
            order[pos + 1]
        } else if (sequential) {
            order[0]
        } else {
            val w = wrapOrder ?: tracks.indices.shuffled().toMutableList().also { wrapOrder = it }
            w[0]
        }
        val track = tracks.getOrNull(nextIdx) ?: return
        requestBeatGrid(track)
        val mp = createPlayer()
        val pl = Preload(mp, nextIdx)
        mp.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    pl.ready = true
                    preloadRetryAfterUptimeMs = 0L
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (cur?.mp === mp || out?.mp === mp) {
                    onPlayerFailed(mp, loadingToken)
                } else {
                    pl.failed = true
                    preloadRetryAfterUptimeMs = SystemClock.uptimeMillis() + 5000L
                    if (preload === pl) preload = null
                    try { mp.release() } catch (_: Exception) {}
                }
            }
        })
        try {
            preload = pl
            preparePlayer(mp, track.uri)
        } catch (_: Exception) {
            preload = null
            preloadRetryAfterUptimeMs = SystemClock.uptimeMillis() + 5000L
            try { mp.release() } catch (_: Exception) {}
        }
    }

    private fun discardPreload() {
        preload?.let { try { it.mp.release() } catch (_: Exception) {} }
        preload = null
        wrapOrder = null
    }

    private fun attachPlaybackListeners(mp: ExoPlayer) {
        mp.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && cur?.mp === mp) advance(1000)
            }
        })
    }

    private fun transitionTo(idx: Int, ms: Int) {
        if (!djSyncEnabled) activeTransitionStyle = transitionStyle
        transitionGraceUsedForTrack = false
        val track = tracks[idx]
        val token = ++loadingToken
        triggerAtMs = Int.MAX_VALUE
        transitionSearchStartMs = Int.MAX_VALUE
        transitionSearchDeadlineMs = Int.MAX_VALUE

        val pre = preload
        preload = null
        if (pre != null && pre.idx == idx && !pre.failed) {
            attachPlaybackListeners(pre.mp)
            if (pre.ready || pre.mp.playbackState == Player.STATE_READY) {
                begin(Deck(pre.mp), track, ms)
            } else {
                pre.mp.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            pre.mp.removeListener(this)
                            if (token != loadingToken) pre.mp.release() else begin(Deck(pre.mp), track, ms)
                        }
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        pre.mp.removeListener(this)
                        onPlayerFailed(pre.mp, token)
                    }
                })
            }
            return
        }
        if (pre != null) try { pre.mp.release() } catch (_: Exception) {}

        val mp = createPlayer()
        try {
            attachPlaybackListeners(mp)
            mp.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        mp.removeListener(this)
                        if (token != loadingToken) mp.release() else begin(Deck(mp), track, ms)
                    }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    mp.removeListener(this)
                    onPlayerFailed(mp, token)
                }
            })
            preparePlayer(mp, track.uri)
        } catch (_: Exception) {
            try { mp.release() } catch (_: Exception) {}
            if (token == loadingToken) handler.post { failAndSkip() }
        }
    }

    private fun onPlayerFailed(p: ExoPlayer, token: Int) {
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
        val previousTrack = current
        deck.trackUri = track.uri
        cur = deck
        current = track

        val d = (deck.mp.duration.takeIf { it > 0L }?.toInt() ?: track.durationMs.toInt()).coerceAtLeast(1)
        val skip = min(skipIntroSec.coerceAtLeast(0).toLong() * 1000L, d.toLong() / 4L).toInt()
        val timing = calculateTransitionTiming(d.toLong(), skip.toLong(), d.toLong())
        autoFadeMs = timing.fadeMs
        transitionSearchStartMs = timing.triggerAtMs
        transitionSearchDeadlineMs = minOf(
            timing.triggerAtMs.toLong() + 15_000L,
            d.toLong() - autoFadeMs.toLong() - 1_200L
        ).coerceAtLeast(timing.triggerAtMs.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        // En AUTOMIX no se cambia inmediatamente al llegar al porcentaje: ese es
        // el inicio de la búsqueda. Si ningún análisis entrega candidato, el plazo
        // de 15 s actúa como fallback aunque el análisis musical falle o llegue tarde.
        triggerAtMs = if (djSyncEnabled) transitionSearchDeadlineMs else timing.triggerAtMs

        deck.set(0f)
        applyCachedLoudness(deck, track)
        analyzeLoudness(deck, track)
        // Beatmatching: ajusta la velocidad del tema entrante respecto al saliente
        // solo cuando ambas rejillas tienen una confianza suficiente.
        applyBeatmatch(old, deck, currentBeforeChange = previousTrack, incomingTrack = track)
        if (skip > 0) {
            try { deck.mp.seekTo(skip.toLong()) } catch (_: Exception) {}
        }
        // Alineación de fase conservadora: solo adelanta unos milisegundos
        // cuando ambas rejillas son fiables y el ajuste es prácticamente inaudible.
        alignBeatPhase(old, deck, previousTrack, track)
        deck.mp.play()
        paused = false
        out = old
        runFade(old, deck, ms)
        // Analiza en segundo plano; si termina a tiempo, corrige el punto de
        // transición y elimina un silencio inicial sin bloquear la reproducción.
        analyzeBoundaries(deck, track, d)
        analyzeBeatGrid(deck, track, d)
        requestBeatGridForNext()
        updateNotification()
        onChange?.invoke()
    }


    /**
     * Intenta hacer coincidir la fase rítmica de ambas pistas sin saltos audibles.
     * Nunca rebobina y nunca adelanta más de 140 ms; si el análisis es dudoso,
     * las rejillas no coinciden razonablemente o falta una pista, no hace nada.
     */
    private fun alignBeatPhase(
        outgoing: Deck?,
        incoming: Deck,
        outgoingTrack: Track?,
        incomingTrack: Track
    ) {
        if (!djSyncEnabled || outgoing == null || outgoingTrack == null) return
        val outGrid = beatGridCache[outgoingTrack.uri.toString()] ?: return
        val inGrid = beatGridCache[incomingTrack.uri.toString()] ?: return
        if (outGrid.confidence < 0.18 || inGrid.confidence < 0.18) return
        if (outGrid.bpm !in 60.0..180.0 || inGrid.bpm !in 60.0..180.0) return

        val outSpeed = try { outgoing.mp.playbackParameters.speed.toDouble() } catch (_: Exception) { 1.0 }
        val inSpeed = try { incoming.mp.playbackParameters.speed.toDouble() } catch (_: Exception) { 1.0 }
        val outEffectiveBpm = outGrid.bpm * outSpeed
        val inEffectiveBpm = inGrid.bpm * inSpeed
        if (kotlin.math.abs(outEffectiveBpm - inEffectiveBpm) / outEffectiveBpm.coerceAtLeast(1.0) > 0.035) return

        val outPos = try { outgoing.mp.currentPosition.toDouble() } catch (_: Exception) { return }
        val inPos = try { incoming.mp.currentPosition.toDouble() } catch (_: Exception) { return }
        val outPeriod = 60000.0 / outGrid.bpm
        val inPeriod = 60000.0 / inGrid.bpm
        if (outPeriod <= 0.0 || inPeriod <= 0.0) return

        fun phase(position: Double, anchorMs: Double, period: Double): Double {
            val raw = (position - anchorMs) % period
            return ((raw + period) % period) / period
        }

        // Cuando ambos detectores identifican con confianza el downbeat,
        // comparamos la fase del compás completo (4/4), no solo la del beat.
        // Si no, usamos la rejilla de beats normal como alternativa conservadora.
        val useBarPhase = outGrid.barConfidence >= 0.25 && inGrid.barConfidence >= 0.25 &&
            outGrid.barOffsetBeats in 0..3 && inGrid.barOffsetBeats in 0..3
        val outAnchor = outGrid.firstBeatMs.toDouble() +
            if (useBarPhase) outGrid.barOffsetBeats * outPeriod else 0.0
        val inAnchor = inGrid.firstBeatMs.toDouble() +
            if (useBarPhase) inGrid.barOffsetBeats * inPeriod else 0.0
        val outPhase = phase(outPos, outAnchor, if (useBarPhase) outPeriod * 4.0 else outPeriod)
        val inPhase = phase(inPos, inAnchor, if (useBarPhase) inPeriod * 4.0 else inPeriod)
        val phaseDifference = (outPhase - inPhase + 1.0) % 1.0
        val correctionMs = phaseDifference * (if (useBarPhase) inPeriod * 4.0 else inPeriod)

        // Solo aplicamos correcciones muy pequeñas: un desfase grande puede
        // significar un downbeat mal inferido y no debe provocar saltos de audio.
        if (!correctionMs.isFinite() || correctionMs < 12.0 || correctionMs > 140.0) return
        val duration = try { incoming.mp.duration } catch (_: Exception) { C.TIME_UNSET.toLong() }
        if (duration > 0L && inPos + correctionMs >= duration - 250L) return
        try { incoming.mp.seekTo((inPos + correctionMs).toLong()) } catch (_: Exception) {}
    }

    /**
     * Cambia la velocidad gradualmente en lugar de aplicar un salto instantáneo.
     * Cada nueva petición invalida el ajuste anterior para no dejar tareas activas
     * sobre un reproductor que ya haya cambiado de pista.
     */
    private fun rampPlaybackSpeed(player: ExoPlayer, targetSpeed: Float, durationMs: Long = 1800L) {
        beatmatchRunnable?.let { handler.removeCallbacks(it) }
        beatmatchRunnable = null
        val generation = ++beatmatchGeneration
        val target = targetSpeed.coerceIn(0.90f, 1.10f)
        val start = try { player.playbackParameters.speed } catch (_: Exception) { target }
        if (!start.isFinite() || kotlin.math.abs(start - target) < 0.004f || durationMs <= 0L) {
            try { player.setPlaybackParameters(PlaybackParameters(target, 1f)) } catch (_: Exception) {}
            return
        }

        val startAt = SystemClock.uptimeMillis()
        val rampDuration = durationMs.coerceAtLeast(300L)
        val task = object : Runnable {
            override fun run() {
                if (generation != beatmatchGeneration) return
                val progress = ((SystemClock.uptimeMillis() - startAt).toFloat() / rampDuration)
                    .coerceIn(0f, 1f)
                // Smoothstep: arranque y final más suaves que una interpolación lineal.
                val eased = progress * progress * (3f - 2f * progress)
                val speed = start + (target - start) * eased
                try {
                    // Ajusta el tempo manteniendo el tono original (pitch = 1.0).
                    player.setPlaybackParameters(PlaybackParameters(speed, 1f))
                } catch (_: Exception) {
                    beatmatchRunnable = null
                    return
                }
                if (progress >= 1f) {
                    beatmatchRunnable = null
                } else {
                    handler.postDelayed(this, 80L)
                }
            }
        }
        beatmatchRunnable = task
        handler.post(task)
    }

    private fun applyBeatmatch(
        outgoing: Deck?,
        incoming: Deck,
        currentBeforeChange: Track?,
        incomingTrack: Track
    ) {
        if (!djSyncEnabled) {
            rampPlaybackSpeed(incoming.mp, 1f)
            return
        }
        if (outgoing == null || currentBeforeChange == null) {
            rampPlaybackSpeed(incoming.mp, 1f)
            return
        }
        val outgoingGrid = beatGridCache[currentBeforeChange.uri.toString()]
        val incomingGrid = beatGridCache[incomingTrack.uri.toString()]
        if (outgoingGrid == null || incomingGrid == null ||
            outgoingGrid.confidence < 0.08 || incomingGrid.confidence < 0.08 ||
            outgoingGrid.bpm !in 60.0..180.0 || incomingGrid.bpm !in 60.0..180.0
        ) {
            // Si no hay análisis fiable, vuelve a velocidad normal sin un salto brusco.
            rampPlaybackSpeed(incoming.mp, 1f)
            return
        }

        val outgoingSpeed = try { outgoing.mp.playbackParameters.speed } catch (_: Exception) { 1f }
        val targetBpm = outgoingGrid.bpm * outgoingSpeed

        // Los detectores de BPM pueden confundir el pulso principal con medio
        // tiempo o doble tiempo (por ejemplo, 70 frente a 140 BPM). Antes de
        // modificar la velocidad, comparamos las equivalencias musicales válidas.
        // Esto evita aplicar un ajuste de ±6 % cuando en realidad existe un error
        // de octava en la estimación del BPM.
        val incomingCandidates = listOf(incomingGrid.bpm, incomingGrid.bpm * 2.0, incomingGrid.bpm / 2.0)
            .filter { it in 60.0..180.0 }
        val normalizedIncomingBpm = incomingCandidates.minByOrNull {
            kotlin.math.abs(kotlin.math.ln(it / targetBpm.coerceAtLeast(1.0)))
        } ?: incomingGrid.bpm
        val rawRatio = targetBpm / normalizedIncomingBpm

        // Si la diferencia real sigue siendo grande, no forzamos la mezcla:
        // conservamos velocidad normal en vez de acelerar/decelerar demasiado.
        if (!rawRatio.isFinite() || rawRatio < minBeatmatchSpeed || rawRatio > maxBeatmatchSpeed) {
            rampPlaybackSpeed(incoming.mp, 1f)
            return
        }
        rampPlaybackSpeed(incoming.mp, rawRatio.toFloat())
    }

    private fun requestBeatGridForNext() {
        if (tracks.isEmpty() || order.isEmpty()) return
        val nextIdx = if (pos + 1 < order.size) {
            order[pos + 1]
        } else if (sequential) {
            order[0]
        } else {
            val w = wrapOrder ?: tracks.indices.shuffled().toMutableList().also { wrapOrder = it }
            w.firstOrNull() ?: return
        }
        tracks.getOrNull(nextIdx)?.let(::requestBeatGrid)
    }

    /** Inicia el análisis de la pista siguiente sin bloquear el hilo de reproducción. */
    private fun requestBeatGrid(track: Track) {
        val key = track.uri.toString()
        if (beatGridCache.containsKey(key) || !beatGridAttempted.add(key)) return
        try {
            beatGridExecutor.execute {
                val grid = BpmAnalysisRepository.getOrDetect(applicationContext, track.uri)
                if (grid != null) beatGridCache[key] = grid
            }
        } catch (_: Exception) {
            beatGridAttempted.remove(key)
        }
    }

    /** Aplica la ganancia ya calculada sin tocar el volumen base del crossfade. */
    private fun applyCachedLoudness(deck: Deck, track: Track) {
        val result = loudnessCache[track.uri.toString()] ?: return
        deck.applyLoudnessGain(result.gain)
    }

    /** Decodifica en segundo plano; si el análisis termina durante la reproducción, suaviza el cambio. */
    private fun analyzeLoudness(deck: Deck, track: Track) {
        val key = track.uri.toString()
        loudnessCache[key]?.let { rampLoudnessGain(deck, it.gain); return }
        if (!loudnessAttempted.add(key)) return
        try {
            loudnessExecutor.execute {
                val result = AudioLoudnessAnalyzer.analyze(applicationContext, track.uri)
                if (result != null) loudnessCache[key] = result
                handler.post {
                    if (result != null && running &&
                        (cur === deck || out === deck) && deck.trackUri == track.uri) {
                        rampLoudnessGain(deck, result.gain)
                    }
                }
            }
        } catch (_: Exception) {
            loudnessAttempted.remove(key)
        }
    }

    /** Ramp de ganancia de 1.2 s para evitar saltos perceptibles al completar el análisis. */
    private fun rampLoudnessGain(deck: Deck, targetGain: Float) {
        loudnessRamps.remove(deck)?.let { handler.removeCallbacks(it) }
        val startGain = deck.loudnessGain
        val target = targetGain.coerceIn(0.65f, 1.35f)
        if (kotlin.math.abs(startGain - target) < 0.015f) {
            deck.applyLoudnessGain(target)
            return
        }
        val startTime = SystemClock.uptimeMillis()
        val runnable = object : Runnable {
            override fun run() {
                if (deck.mp.playbackState == Player.STATE_IDLE) {
                    loudnessRamps.remove(deck)
                    return
                }
                val p = ((SystemClock.uptimeMillis() - startTime) / 1200f).coerceIn(0f, 1f)
                val eased = p * p * (3f - 2f * p)
                deck.applyLoudnessGain(startGain + (target - startGain) * eased)
                if (p < 1f) handler.postDelayed(this, 40L) else loudnessRamps.remove(deck)
            }
        }
        loudnessRamps[deck] = runnable
        handler.post(runnable)
    }

    private fun analyzeBoundaries(deck: Deck, track: Track, durationMs: Int) {
        val key = track.uri.toString()
        boundaryCache[key]?.let { applyBoundaries(deck, track, it, durationMs); return }
        if (!boundaryAttempted.add(key)) return
        boundaryExecutor.execute {
            val signal = AudioBoundaryDetector.analyze(applicationContext, track.uri)
            val result = signal?.boundaries
            if (result != null) boundaryCache[key] = result
            if (signal != null) {
                TrackStructureAnalyzer.estimate(signal.energyLevels, signal.boundaries.durationMs, signal.windowMs)
                    ?.let { structureCache[key] = it }
            }
            handler.post {
                if (result != null && cur?.mp === deck.mp && current?.uri == track.uri && running) {
                    applyBoundaries(deck, track, result, durationMs)
                }
            }
        }
    }

    private fun applyBoundaries(
        deck: Deck,
        track: Track,
        boundaries: AudioBoundaryDetector.Boundaries,
        durationMs: Int
    ) {
        if (cur?.mp !== deck.mp || current?.uri != track.uri || !running) return
        val key = track.uri.toString()
        val duration = durationMs.toLong().takeIf { it > 0L } ?: boundaries.durationMs
        val detectedStart = boundaries.audibleStartMs.coerceIn(0L, duration)
        val userSkip = (skipIntroSec * 1000L).coerceAtLeast(0L)
        val desiredStart = max(userSkip, detectedStart).coerceAtMost(duration / 4L)
        val currentPosition = try { deck.mp.currentPosition } catch (_: Exception) { return }

        // Solo hace seek automático si el análisis termina al principio de la pista;
        // nunca rebobina una canción que ya lleva un tiempo reproduciéndose.
        if (currentPosition <= 1200L && desiredStart > currentPosition + 250L) {
            try { deck.mp.seekTo(desiredStart.toLong()) } catch (_: Exception) {}
        }

        val effectiveEnd = boundaries.audibleEndMs.coerceIn(desiredStart, duration)
        val timing = calculateTransitionTiming(duration, desiredStart, effectiveEnd)
        autoFadeMs = timing.fadeMs
        // El punto porcentual es fijo para esta pista. El análisis no reinicia la
        // ventana: se buscan oportunidades hasta 15 s después de ese punto.
        val percentTarget = timing.triggerAtMs.toLong()
        val safeNow = (currentPosition + 150L).coerceAtMost(duration)
        val endSafetyLimit = (effectiveEnd - autoFadeMs.toLong() - 1_200L).coerceAtLeast(safeNow)
        transitionSearchStartMs = timing.triggerAtMs
        transitionSearchDeadlineMs = minOf(percentTarget + 15_000L, effectiveEnd - autoFadeMs.toLong() - 1_200L)
            .coerceAtLeast(percentTarget).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val searchDeadline = max(transitionSearchDeadlineMs.toLong(), safeNow).coerceAtMost(endSafetyLimit)
        val baseTarget = max(percentTarget, safeNow).coerceAtMost(searchDeadline)
        val outgoingGrid = beatGridCache[key]
        val incomingTrack = getUpcomingQueue(1).firstOrNull()
        incomingTrack?.let { requestBeatGrid(it) }
        val incomingGrid = incomingTrack?.let { beatGridCache[it.uri.toString()] }
        val decision = if (djSyncEnabled) {
            SmartTransitionDecisionEngine.decide(
                durationMs = duration,
                audibleStartMs = desiredStart,
                audibleEndMs = effectiveEnd,
                currentPositionMs = currentPosition.toLong(),
                searchStartMs = percentTarget,
                requestedFadeMs = autoFadeMs,
                safetyMarginMs = 1_200L,
                maxSearchWindowMs = 15_000L,
                structure = structureCache[key],
                outgoingGrid = outgoingGrid,
                incomingGrid = incomingGrid
            )
        } else null
        val selectedTarget = decision?.transitionAtMs?.toLong() ?: baseTarget
        val upper = max(searchDeadline, safeNow).coerceAtMost(endSafetyLimit)
        val unsnapped = selectedTarget.coerceIn(safeNow, upper).toInt()
        val snapped = outgoingGrid?.let { snapToBeat(unsnapped, duration, currentPosition, autoFadeMs, it) } ?: unsnapped
        triggerAtMs = snapped.toLong().coerceIn(safeNow, upper).toInt()
        onChange?.invoke()
    }

    private fun analyzeBeatGrid(deck: Deck, track: Track, durationMs: Int) {
        val key = track.uri.toString()
        beatGridCache[key]?.let { applyBeatGrid(deck, track, durationMs, it); return }
        if (!beatGridAttempted.add(key)) return
        try {
            beatGridExecutor.execute {
                val grid = BpmAnalysisRepository.getOrDetect(applicationContext, track.uri)
                if (grid != null) beatGridCache[key] = grid
                handler.post {
                    if (grid != null && cur?.mp === deck.mp && current?.uri == track.uri && running) {
                        applyBeatGrid(deck, track, durationMs, grid)
                    }
                }
            }
        } catch (_: Exception) {
            beatGridAttempted.remove(key)
        }
    }

    private fun applyBeatGrid(deck: Deck, track: Track, durationMs: Int, grid: BeatGridDetector.BeatGrid) {
        if (cur?.mp !== deck.mp || current?.uri != track.uri || !running) return
        val duration = durationMs.toLong().coerceAtLeast(1L)
        val currentPosition = try { deck.mp.currentPosition.coerceAtLeast(0L) } catch (_: Exception) { return }
        val key = track.uri.toString()
        val audibleStart = boundaryCache[key]?.audibleStartMs ?: (skipIntroSec.coerceAtLeast(0) * 1000L)
        val audibleEnd = boundaryCache[key]?.audibleEndMs ?: duration
        val safeNow = currentPosition + 150L
        val endSafetyLimit = (audibleEnd - autoFadeMs.toLong() - 1_200L).coerceAtLeast(safeNow)
        val fixedStart = if (transitionSearchStartMs != Int.MAX_VALUE) transitionSearchStartMs.toLong() else triggerAtMs.toLong()
        val fixedDeadline = if (transitionSearchDeadlineMs != Int.MAX_VALUE) transitionSearchDeadlineMs.toLong()
            else minOf(fixedStart + 15_000L, audibleEnd - autoFadeMs.toLong() - 1_200L)
        val deadline = max(fixedDeadline, safeNow).coerceAtMost(endSafetyLimit)
        val nextTrack = getUpcomingQueue(1).firstOrNull()
        nextTrack?.let { requestBeatGrid(it) }
        val nextGrid = nextTrack?.let { beatGridCache[it.uri.toString()] }
        val decision = if (djSyncEnabled) SmartTransitionDecisionEngine.decide(
            durationMs = duration,
            audibleStartMs = audibleStart,
            audibleEndMs = audibleEnd,
            currentPositionMs = currentPosition,
            searchStartMs = fixedStart,
            requestedFadeMs = autoFadeMs,
            safetyMarginMs = 1_200L,
            maxSearchWindowMs = 15_000L,
            structure = structureCache[key],
            outgoingGrid = grid,
            incomingGrid = nextGrid
        ) else null
        val selected = decision?.transitionAtMs?.toLong() ?: triggerAtMs.toLong()
        val snappedRaw = snapToBeat(selected.toInt(), duration, currentPosition, autoFadeMs, grid)
        val snapped = snappedRaw.toLong().coerceIn(safeNow, deadline).coerceIn(0L, duration).toInt()
        if (snapped != triggerAtMs) {
            triggerAtMs = snapped
            onChange?.invoke()
        }
        // Si la rejilla entrante termina de analizarse durante la reproducción,
        // aplica el ajuste de BPM mientras todavía existe el deck saliente.
        val outgoing = out
        if (outgoing != null) {
            val previousTrack = outgoing.trackUri?.let { uri -> tracks.firstOrNull { it.uri == uri } }
            if (previousTrack != null) applyBeatmatch(outgoing, deck, previousTrack, track)
        }
    }

    /**
     * Margen musical limitado: si DJ está activo y el punto cae justo antes de un
     * compás fiable, permite esperar hasta 2.2 s al siguiente compás. Esto evita
     * algunos cortes a mitad de frase musical; no equivale a detectar voces.
     */
    private fun tryApplyTransitionGrace(positionMs: Int, durationMs: Int): Boolean {
        if (!djSyncEnabled || transitionGraceUsedForTrack || durationMs <= 0) return false
        val track = current ?: return false
        val grid = beatGridCache[track.uri.toString()] ?: return false
        if (grid.confidence < 0.15 || grid.barConfidence < 0.35 ||
            !grid.beatPeriodMs.isFinite() || grid.beatPeriodMs <= 0.0 ||
            grid.barOffsetBeats !in 0..3) return false
        val barMs = grid.beatPeriodMs * 4.0
        val anchor = grid.firstBeatMs.toDouble() + grid.barOffsetBeats * grid.beatPeriodMs
        val nextBarIndex = kotlin.math.floor((positionMs - anchor) / barMs).toLong() + 1L
        val nextBar = (anchor + nextBarIndex * barMs).toLong()
        val waitMs = nextBar - positionMs
        transitionGraceUsedForTrack = true
        val audibleStart = boundaryCache[track.uri.toString()]?.audibleStartMs
            ?: (skipIntroSec.coerceAtLeast(0) * 1000L)
        val audibleEnd = boundaryCache[track.uri.toString()]?.audibleEndMs ?: durationMs.toLong()
        val percentStart = audibleStart + ((audibleEnd - audibleStart).coerceAtLeast(1L) * playPercent.coerceIn(1, 100) / 100L)
        val hardSearchDeadline = minOf(
            percentStart + 15_000L,
            audibleEnd - autoFadeMs.toLong() - 1_200L
        ).coerceAtLeast(percentStart)
        if (waitMs !in 250L..2200L || nextBar > hardSearchDeadline || nextBar + autoFadeMs >= durationMs - 100L) return false
        triggerAtMs = nextBar.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        onChange?.invoke()
        return true
    }

    /** Ajusta el cambio al beat más cercano solo si no desplaza demasiado el punto calculado. */
    private fun snapToBeat(
        targetMs: Int,
        durationMs: Long,
        currentPositionMs: Long,
        fadeMs: Int,
        grid: BeatGridDetector.BeatGrid
    ): Int {
        if (!djSyncEnabled) return targetMs
        // Una confianza demasiado baja hace que la rejilla pueda estar desplazada;
        // en ese caso es preferible conservar el punto de transición calculado.
        if (grid.confidence < 0.08 || !grid.bpm.isFinite() || grid.bpm !in 60.0..180.0) return targetMs
        val period = grid.beatPeriodMs
        if (!period.isFinite() || period <= 0.0) return targetMs
        val phase = grid.firstBeatMs.toDouble()
        if (!phase.isFinite() || phase < 0.0) return targetMs
        val target = targetMs.toDouble()

        // Si la estimación del compás 4/4 es clara, preferimos el inicio de compás.
        // Solo se aplica en los modos que ya habilitan la protección rítmica y con
        // un desplazamiento pequeño; de lo contrario se conserva el beat normal.
        val useBar = grid.barConfidence >= 0.25 && grid.barOffsetBeats in 0..3
        val unit = if (useBar) period * 4.0 else period
        val anchor = phase + if (useBar) grid.barOffsetBeats * period else 0.0
        val beatIndex = kotlin.math.round((target - anchor) / unit)
        val candidate = (anchor + beatIndex * unit).toLong()

        // No forzar el cambio a un compás lejano: el desplazamiento permitido
        // es menor para barras que para beats, evitando transiciones tardías.
        val maxShift = if (useBar) unit * 0.12 else period * 0.20
        if (kotlin.math.abs(candidate - targetMs.toLong()) > maxShift) return targetMs
        val lower = max(currentPositionMs + 150L, fadeMs.toLong())
        val upper = (durationMs - fadeMs.toLong() - 250L).coerceAtLeast(lower)
        if (candidate < lower || candidate > upper) return targetMs
        return candidate.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
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
                when {
                    activeTransitionStyle.equals("Ascender", true) -> {
                        to.set(toStart + (1f - toStart) * (p * p).coerceIn(0f, 1f))
                        from?.set(fromStart * (1f - p))
                    }
                    activeTransitionStyle.equals("Crossfade", true) -> {
                        // Curva de potencia constante: la energía percibida cae menos al centro.
                        to.set(toStart + (1f - toStart) * sin(a).toFloat())
                        from?.set(fromStart * cos(a).toFloat())
                    }
                    else -> {
                        // Desvanecer: fundido lineal de salida y entrada.
                        to.set(toStart + (1f - toStart) * p)
                        from?.set(fromStart * (1f - p))
                    }
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
                        val position = c.mp.currentPosition.toInt()
                        if (position >= triggerAtMs) {
                            if (!tryApplyTransitionGrace(position, c.mp.duration.toInt().coerceAtLeast(0))) {
                                advance(autoFadeMs)
                            }
                        } else {
                            if (preload == null && position >= triggerAtMs - PRELOAD_LEAD_MS) preloadNext()
                            val remaining = triggerAtMs - position
                            if (remaining in 1..299) delay = remaining.toLong().coerceAtLeast(50L)
                            // 🆕 Notificar a la UI cada ~500ms para avanzar la barra de progreso
                            updateMediaSessionPlaybackState()
                            if (position % 500 < 300) onChange?.invoke()
                        }
                    }
                } catch (_: Exception) {}
            }
            handler.postDelayed(this, delay)
        }
    }

    /** Mantiene el estado de Android Auto, pantalla de bloqueo y controles multimedia
     * sincronizado con el deck que realmente está reproduciendo. */
    private fun updateMediaSessionPlaybackState() {
        val deck = cur
        val state = when {
            deck == null && running -> PlaybackStateCompat.STATE_BUFFERING
            deck == null -> PlaybackStateCompat.STATE_STOPPED
            paused -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_PLAYING
        }
        val position = try {
            deck?.mp?.currentPosition?.coerceAtLeast(0L) ?: 0L
        } catch (_: Exception) {
            0L
        }
        val duration = try {
            deck?.mp?.duration?.takeIf { it > 0L }
                ?: current?.durationMs?.takeIf { it > 0L }
                ?: 0L
        } catch (_: Exception) {
            current?.durationMs?.coerceAtLeast(0L) ?: 0L
        }
        val safePosition = if (duration > 0L) position.coerceAtMost(duration) else position
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(state, safePosition, if (state == PlaybackStateCompat.STATE_PLAYING) 1f else 0f, SystemClock.elapsedRealtime())
                .build()
        )
    }

    private fun pause() {
        try { cur?.mp?.pause() } catch (_: Exception) {}
        try { out?.mp?.pause() } catch (_: Exception) {}
        paused = true
        updateNotification()
        onChange?.invoke()
    }

    private fun resume() {
        try { cur?.mp?.play() } catch (_: Exception) {}
        try { out?.mp?.play() } catch (_: Exception) {}
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

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, "AutomixMediaSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (paused) togglePause()
                }

                override fun onPause() {
                    if (!paused) togglePause()
                }

                override fun onSkipToNext() {
                    next()
                }

                override fun onSkipToPrevious() {
                    previous()
                }

                override fun onSeekTo(pos: Long) {
                    val duration = try {
                        cur?.mp?.duration?.takeIf { it > 0L }
                            ?: current?.durationMs?.takeIf { it > 0L }
                            ?: Int.MAX_VALUE.toLong()
                    } catch (_: Exception) {
                        current?.durationMs?.takeIf { it > 0L } ?: Int.MAX_VALUE.toLong()
                    }
                    seekTo(pos.coerceIn(0L, min(duration, Int.MAX_VALUE.toLong()).toInt().toLong()).toInt())
                    updateMediaSessionPlaybackState()
                }
            })
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            isActive = true
        }
    }

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

        // POST_NOTIFICATIONS controla la visibilidad normal de notificaciones en Android 13+,
        // pero no debe usarse para omitir la promoción del servicio a primer plano.
        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (_: Exception) {
            // Evita que una excepción no controlada cierre el proceso.
        }
    }

    private fun updateNotification() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, createNotification())
        } catch (_: Exception) {}
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

    private fun createNotification(): Notification {
        val track = current
        val title = track?.title ?: "Automix"
        val artist = track?.artist ?: "Reproduciendo música"

        val mainIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = Intent(this, AutomixService::class.java).apply { action = ACTION_PAUSE }
        val pausePending = PendingIntent.getService(
            this, 1, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = Intent(this, AutomixService::class.java).apply { action = ACTION_NEXT }
        val nextPending = PendingIntent.getService(
            this, 2, nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent = Intent(this, AutomixService::class.java).apply { action = ACTION_PREV }
        val prevPending = PendingIntent.getService(
            this, 3, prevIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        val playPauseText = if (paused) "Reproducir" else "Pausar"

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track?.album ?: "")
            .putLong(
                MediaMetadataCompat.METADATA_KEY_DURATION,
                try { cur?.mp?.duration?.takeIf { it > 0L } ?: (track?.durationMs ?: 0L) }
                catch (_: Exception) { track?.durationMs ?: 0L }
            )

        track?.let { loadAlbumArtwork(it.albumId) }?.let { artwork ->
            metadataBuilder
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, artwork)
        }
        mediaSession?.isActive = track != null
        mediaSession?.setMetadata(metadataBuilder.build())
        updateMediaSessionPlaybackState()

        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Anterior", prevPending)
            .addAction(playPauseIcon, playPauseText, pausePending)
            .addAction(android.R.drawable.ic_media_next, "Siguiente", nextPending)
            .setOngoing(!paused)
            .setOnlyAlertOnce(true)

        builder.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2)
                .setMediaSession(mediaSession?.sessionToken)
        )

        return builder.build()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        cur?.release(); cur = null
        out?.release(); out = null
        discardPreload()
        abandonFocus()
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        boundaryExecutor.shutdownNow()
        beatGridExecutor.shutdownNow()
        loudnessExecutor.shutdownNow()
        boundaryCache.clear()
        structureCache.clear()
        boundaryAttempted.clear()
        loudnessCache.clear()
        loudnessAttempted.clear()
        loudnessRamps.clear()
        beatGridCache.clear()
        beatGridAttempted.clear()
        super.onDestroy()
    }
}
