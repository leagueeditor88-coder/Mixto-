package com.example.automix

import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Check
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ContentUris
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore.Audio.Media
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class MainActivity : ComponentActivity() {

    // --- Constantes de orden ---
    private val SORT_RANDOM = "Aleatorio"
    private val SORT_SMART = "Smart"
    private val SORT_TITLE = "Título"
    private val SORT_RECENT = "Más recientes"
    private val SORT_ARTIST = "Artista"
    private val SORT_BPM = "BPM"

    // --- Estado ---
    private lateinit var prefs: SharedPreferences
    private var svc: AutomixService? = null
    private var allTracks: List<Track> = emptyList()
    private var filteredTracks: List<Track> = emptyList()
    private var randomRank: Map<String, Int> = emptyMap()
    private val bpmCache = ConcurrentHashMap<String, Int>()
    private val keyCache = ConcurrentHashMap<String, String>()
    @Volatile private var analysisRunning = false

    private var percent = 70
    private var fade = 8
    private var skip = 0
    private var transitionStyle = "Desvanecer"
    // Orden principal predeterminado acordado: modificación más reciente primero.
    private var sortMode = SORT_RECENT
    private var lastManualSort = SORT_TITLE
    private var selectedFolders: Set<String> = emptySet()
    private var currentGenre: String? = null

    private val uiState = mutableStateOf(UiSnapshot())

    private fun pushState(update: (UiSnapshot) -> UiSnapshot) {
        uiState.value = update(uiState.value)
    }
    private val collator: java.text.Collator = java.text.Collator.getInstance()

    data class UiSnapshot(
        val tracks: List<Track> = emptyList(),
        val current: Track? = null,
        val isPlaying: Boolean = false,
        val isPaused: Boolean = false,
        val automixEnabled: Boolean = false,
        val sortMode: String = "Aleatorio",
        val statusText: String = "",
        val analysisText: String = "Biblioteca: esperando permiso para analizar",
        val folders: Set<String> = emptySet(),
        val bpmMap: Map<String, Int> = emptyMap(),
        val keyMap: Map<String, String> = emptyMap(),
        val percent: Int = 70,
        val fadeSec: Int = 8,
        val skipSec: Int = 0,
        val transition: String = "Desvanecer",
        val genre: String? = null,
        val currentBpm: Int? = null,
        val currentKey: String? = null,
        val permissionGranted: Boolean = false,
        val searchQuery: String = "",
        val sortField: String = "Título",
        val sortAscending: Boolean = true,
        val filterArtist: String = "",
        val filterAlbum: String = "",
        val filterGenre: String = "",
        val upcomingQueue: List<Track> = emptyList(),
        val positionMs: Int = 0,
        val durationMs: Int = 0,
        val genreScanStatus: String = "Sin iniciar",
        val genreScanProcessed: Int = 0,
        val genreScanTotal: Int = 0,
        val genreScanIdentified: Int = 0,
        val genreScanPending: Int = 0
    )

    private val audioPerm: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (checkSelfPermission(audioPerm) == PackageManager.PERMISSION_GRANTED) {
            loadLibrary()
        } else {
            pushState { it.copy(statusText = "Permiso de música no concedido") }
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as AutomixService.LocalBinder).service
            s.playPercent = percent
            s.fadeSec = fade
            s.skipIntroSec = skip
            s.transitionStyle = transitionStyle
            s.onChange = { refreshNow() }
            svc = s
            s.setRandomSelectionEnabled(sortMode == SORT_RANDOM)
            s.updateMusicAnalysis(bpmCache.toMap(), keyCache.toMap())
            if (filteredTracks.isNotEmpty()) s.reorderQueue(buildDisplayList())
            refreshNow()
        }
        override fun onServiceDisconnected(name: ComponentName?) { svc = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("automix", MODE_PRIVATE)
        percent = prefs.getInt("percent", 70)
        fade = prefs.getInt("fade", 8)
        skip = prefs.getInt("skip", 0)
        transitionStyle = prefs.getString("transition", "Desvanecer") ?: "Desvanecer"
        sortMode = prefs.getString("sort_mode", SORT_RECENT) ?: SORT_RECENT
        if (sortMode == SORT_SMART || sortMode == SORT_BPM) sortMode = SORT_RECENT
        lastManualSort = prefs.getString("sort_manual", SORT_TITLE) ?: SORT_TITLE
        selectedFolders = prefs.getStringSet("folders", emptySet())?.toSet() ?: emptySet()
        try {
            val bj = JSONObject(prefs.getString("bpm_cache", "{}") ?: "{}")
            bj.keys().forEach { k -> bpmCache[k] = bj.optInt(k) }
            val kj = JSONObject(prefs.getString("key_cache", "{}") ?: "{}")
            kj.keys().forEach { k ->
                kj.optString(k).takeIf { it.isNotBlank() && it != "null" }?.let { keyCache[k] = it }
            }
        } catch (_: Exception) {}

        pushState {
            it.copy(
                percent = percent, fadeSec = fade, skipSec = skip,
                transition = transitionStyle, sortMode = sortMode,
                folders = selectedFolders, bpmMap = bpmCache.toMap(), keyMap = keyCache.toMap(),
                sortField = prefs.getString("library_sort_field", "Título") ?: "Título",
                sortAscending = prefs.getBoolean("library_sort_ascending", true)
            )
        }

        setContent {
            AutomixTheme {
                AutomixApp(
                    state = uiState.value,
                    onPlayPause = { togglePlayPause() },
                    onNext = { svc?.next() },
                    onPrev = { svc?.previous() },
                    onStop = { svc?.stopAll(); refreshNow() },
                    onPlayTrack = { idx -> playAt(idx) },
                    onStartAutomix = { toggleAutomix() },
                    onSetSort = { mode -> selectSort(mode) },
                    onSetPercent = { v ->
                        percent = v
                        svc?.playPercent = v
                        prefs.edit().putInt("percent", v).apply()
                        pushState { it.copy(percent = v) }
                    },
                    onSetFade = { v ->
                        fade = v
                        svc?.fadeSec = v
                        prefs.edit().putInt("fade", v).apply()
                        pushState { it.copy(fadeSec = v) }
                    },
                    onSetSkip = { v ->
                        skip = v
                        svc?.skipIntroSec = v
                        prefs.edit().putInt("skip", v).apply()
                        pushState { it.copy(skipSec = v) }
                    },
                    onSetTransition = { style ->
                        transitionStyle = style
                        svc?.transitionStyle = style
                        prefs.edit().putString("transition", style).apply()
                        pushState { it.copy(transition = style) }
                    },
                    onSetFolders = { set ->
                        selectedFolders = set
                        prefs.edit().putStringSet("folders", set).apply()
                        applyFolderFilter()
                    },
                    onDetectBpm = { detectBpmForCurrent() },
                    onDetectKey = { detectKeyForCurrent() },
                    onLookupGenre = { lookupGenreForCurrent() },
                    onStartGenreScan = { startGenreScan() },
                    onPauseGenreScan = { pauseGenreScan() },
                    onMashup = { second -> startMashup(second) },
                    onRequestPermissions = { ensurePermissions() },
                    onSearchChange = { q -> pushState { it.copy(searchQuery = q) } },
                    onSortFieldChange = { f ->
                        prefs.edit().putString("library_sort_field", f).apply()
                        pushState { it.copy(sortField = f) }
                    },
                    onToggleSortDirection = {
                        val next = !uiState.value.sortAscending
                        prefs.edit().putBoolean("library_sort_ascending", next).apply()
                        pushState { it.copy(sortAscending = next) }
                    },
                    onFilterArtist = { a -> pushState { it.copy(filterArtist = a) } },
                    onFilterAlbum = { a -> pushState { it.copy(filterAlbum = a) } },
                    onFilterGenre = { g -> pushState { it.copy(filterGenre = g) } },
                    onSeek = { ms -> svc?.seekTo(ms) },
                    onPlayFiltered = { list, idx ->
                        if (list.isNotEmpty()) {
                            startForegroundService(Intent(this, AutomixService::class.java))
                            svc?.let {
                                it.playPercent = percent; it.fadeSec = fade; it.skipIntroSec = skip
                                it.transitionStyle = transitionStyle
                                it.startOrdered(list, idx)
                            }
                        }
                    },
                    onPlayThisOrder = { list ->
                        if (list.isNotEmpty()) {
                            startForegroundService(Intent(this, AutomixService::class.java))
                            svc?.let {
                                it.playPercent = percent; it.fadeSec = fade; it.skipIntroSec = skip
                                it.transitionStyle = transitionStyle
                                it.startOrdered(list, 0)
                            }
                        }
                    },
                    onReshuffleQueue = { reshuffleQueueHot() }
                )
            }
        }

        lifecycleScope.launch {
            while (true) {
                refreshGenreScanStatus()
                delay(1200)
            }
        }

        bindService(Intent(this, AutomixService::class.java), conn, BIND_AUTO_CREATE)
        ensurePermissions()
    }

    // ---------------- Permisos y biblioteca ----------------

    private fun ensurePermissions() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(audioPerm) != PackageManager.PERMISSION_GRANTED) needed += audioPerm
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.isEmpty()) loadLibrary() else permLauncher.launch(needed.toTypedArray())
    }

    private fun loadLibrary() {
        if (checkSelfPermission(audioPerm) != PackageManager.PERMISSION_GRANTED) return
        val found = mutableListOf<Track>()
        val genreCache = readGenreCache()
        val proj = arrayOf(
    Media._ID, Media.TITLE, Media.ARTIST, Media.DURATION, Media.DATA,
    Media.ALBUM, Media.GENRE, Media.ALBUM_ID, Media.DATE_MODIFIED
)
// Forzar columna ALBUM_ID si existe
        contentResolver.query(
            Media.EXTERNAL_CONTENT_URI, proj,
            "${Media.IS_MUSIC} != 0 AND ${Media.DURATION} >= 60000",
            null, "${Media.TITLE} COLLATE NOCASE ASC"
        )?.use { c ->
            val iId = c.getColumnIndex(Media._ID)
            val iTitle = c.getColumnIndex(Media.TITLE)
            val iArtist = c.getColumnIndex(Media.ARTIST)
            val iDur = c.getColumnIndex(Media.DURATION)
            val iData = c.getColumnIndex(Media.DATA)
            val iAlbum = c.getColumnIndex(Media.ALBUM)
            val iGenre = c.getColumnIndex(Media.GENRE)
            val iAlbumId = c.getColumnIndex(Media.ALBUM_ID)
            val iDateModified = c.getColumnIndex(Media.DATE_MODIFIED)
            while (c.moveToNext()) {
                val path = if (iData >= 0) c.getString(iData) else null
                val album = if (iAlbum >= 0) c.getString(iAlbum) ?: "" else ""
                val mediaGenre = if (iGenre >= 0) c.getString(iGenre)?.takeIf { it != "<unknown>" } ?: "" else ""
                val trackUri = ContentUris.withAppendedId(Media.EXTERNAL_CONTENT_URI, c.getLong(iId))
                val cachedGenre = genreCache.optString(trackUri.toString(), "")
                val genre = mediaGenre.ifBlank { cachedGenre }
                val albumId = if (iAlbumId >= 0 && !c.isNull(iAlbumId)) c.getLong(iAlbumId) else -1L
                found += Track(
                    trackUri,
                    c.getString(iTitle) ?: "Sin título",
                    if (iArtist >= 0) c.getString(iArtist)?.takeIf { it != "<unknown>" } ?: "Artista desconocido"
                    else "Artista desconocido",
                    c.getLong(iDur),
                    path?.let { File(it).parent } ?: "Música",
                    album,
                    genre,
                    albumId,
                    if (iDateModified >= 0 && !c.isNull(iDateModified)) c.getLong(iDateModified) * 1000L else 0L
                )
            }
        }
        allTracks = found
        randomRank = allTracks.shuffled().mapIndexed { i, t -> t.uri.toString() to i }.toMap()
        applyFolderFilter()
        if (found.isEmpty()) {
            pushState { it.copy(statusText = "No encontré canciones en el dispositivo", permissionGranted = true) }
        } else {
            pushState { it.copy(permissionGranted = true) }
            analyzeLibraryInBackground()
        }
    }

    private fun applyFolderFilter() {
        val valid = selectedFolders.filter { f -> allTracks.any { it.folder == f } }.toSet()
        filteredTracks = if (valid.isEmpty()) allTracks else allTracks.filter { it.folder in valid }
        pushState { it.copy(folders = valid) }
        val display = buildDisplayList()
        svc?.reorderQueue(display)
        refreshNow()
    }

    // ---------------- Orden ----------------

    private fun buildDisplayList(): List<Track> = when (sortMode) {
        SORT_RECENT -> filteredTracks.sortedWith(
            compareByDescending<Track> { it.dateModifiedMs > 0L }
                .thenByDescending { it.dateModifiedMs }
                .thenBy { it.title.lowercase() }
        )
        SORT_RANDOM -> filteredTracks.sortedBy { randomRank[it.uri.toString()] ?: Int.MAX_VALUE }
        SORT_SMART -> smartReorder(filteredTracks.sortedWith(Comparator { a, b -> collator.compare(a.title, b.title) }))
        SORT_ARTIST -> filteredTracks.sortedWith(Comparator { a, b ->
            val ua = a.artist == "Artista desconocido"
            val ub = b.artist == "Artista desconocido"
            if (ua != ub) { if (ua) 1 else -1 }
            else {
                val c = collator.compare(a.artist, b.artist)
                if (c != 0) c else collator.compare(a.title, b.title)
            }
        })
        SORT_BPM -> filteredTracks.sortedWith(Comparator { a, b ->
            val x = bpmCache[a.uri.toString()] ?: Int.MAX_VALUE
            val y = bpmCache[b.uri.toString()] ?: Int.MAX_VALUE
            if (x != y) x.compareTo(y) else collator.compare(a.title, b.title)
        })
        else -> filteredTracks.sortedWith(Comparator { a, b -> collator.compare(a.title, b.title) })
    }

    private fun selectSort(mode: String) {
        sortMode = mode
        if (mode == SORT_RANDOM) randomRank = allTracks.shuffled().mapIndexed { i, t -> t.uri.toString() to i }.toMap()
        if (mode == SORT_TITLE || mode == SORT_ARTIST || mode == SORT_BPM || mode == SORT_RECENT) lastManualSort = mode
        prefs.edit().putString("sort_mode", sortMode).putString("sort_manual", lastManualSort).apply()
        val display = buildDisplayList()
        svc?.setRandomSelectionEnabled(mode == SORT_RANDOM)
        svc?.reorderQueue(display)
        pushState { it.copy(sortMode = mode) }
        refreshNow()
    }

    private class SmartItem(val track: Track, val bpm: Int?, val key: String?)

    private fun smartReorder(input: List<Track>): List<Track> {
        if (input.size < 3) return input
        val items = input.map { t -> SmartItem(t, bpmCache[t.uri.toString()], keyCache[t.uri.toString()]) }
        val known = items.filter { it.bpm != null || it.key != null }.toMutableList()
        val unknown = items.filter { it.bpm == null && it.key == null }
        if (known.size < 2) return input
        val ordered = ArrayList<Track>(input.size)
        var last = known.removeAt(0)
        ordered.add(last.track)
        while (known.isNotEmpty()) {
            var bestIdx = 0
            var bestCost = Double.MAX_VALUE
            for (i in known.indices) {
                val cost = transitionCost(last, known[i])
                if (cost < bestCost) { bestCost = cost; bestIdx = i }
            }
            last = known.removeAt(bestIdx)
            ordered.add(last.track)
        }
        for (u in unknown) ordered.add(u.track)
        return ordered
    }

    private fun transitionCost(a: SmartItem, b: SmartItem): Double {
        val x = a.bpm; val y = b.bpm
        val bpmCost = if (x != null && y != null)
            minOf(kotlin.math.abs(x - y), kotlin.math.abs(x - y * 2), kotlin.math.abs(x * 2 - y)).toDouble() / 12.0
        else 2.0
        val ka = a.key; val kb = b.key
        val keyCost = if (ka != null && kb != null) if (harmonicallyCompatible(ka, kb)) 0.0 else 3.0
        else 1.2
        return bpmCost + keyCost
    }

    private val camelotMap: Map<String, String> = mapOf(
        "C major" to "8B", "G major" to "9B", "D major" to "10B", "A major" to "11B", "E major" to "12B", "B major" to "1B",
        "F# major" to "2B", "C# major" to "3B", "G# major" to "4B", "D# major" to "5B", "A# major" to "6B", "F major" to "7B",
        "A minor" to "8A", "E minor" to "9A", "B minor" to "10A", "F# minor" to "11A", "C# minor" to "12A", "G# minor" to "1A",
        "D# minor" to "2A", "A# minor" to "3A", "F minor" to "4A", "C minor" to "5A", "G minor" to "6A", "D minor" to "7A"
    )

    private fun harmonicallyCompatible(a: String, b: String): Boolean {
        val x = camelotMap[a] ?: return false
        val y = camelotMap[b] ?: return false
        val xn = x.dropLast(1).toInt(); val yn = y.dropLast(1).toInt()
        if (xn == yn) return true
        val sameMode = x.last() == y.last()
        val neighbor = (xn % 12) + 1 == yn || (yn % 12) + 1 == xn
        return sameMode && neighbor
    }

    // ---------------- Reproducción ----------------
    /**
     * El botón AUTOMIX es el interruptor maestro. Si hay una pista sonando,
     * activa el motor inteligente sobre esa sesión; si no, inicia una sesión nueva.
     * Al apagarlo no detiene la música: vuelve a reproducción normal.
     */
    private fun toggleAutomix() {
        val s = svc
        if (s == null) {
            Toast.makeText(this, "El reproductor aún se está preparando", Toast.LENGTH_SHORT).show()
            return
        }

        if (s.isAutomixEnabled) {
            s.setAutomixEnabled(false)
            pushState { it.copy(automixEnabled = false, statusText = "Automix desactivado · reproducción normal") }
            refreshNow()
            return
        }

        s.playPercent = percent
        s.fadeSec = fade
        s.skipIntroSec = skip
        s.transitionStyle = transitionStyle
        s.setRandomSelectionEnabled(sortMode == SORT_RANDOM)
        s.updateMusicAnalysis(bpmCache.toMap(), keyCache.toMap())

        if (s.current != null && s.isRunning) {
            // Conserva la canción actual y activa el motor sobre la cola existente.
            s.setAutomixEnabled(true)
        } else {
            val display = buildDisplayList()
            if (display.isEmpty()) {
                Toast.makeText(this, "No hay canciones cargadas", Toast.LENGTH_SHORT).show()
                return
            }
            startForegroundService(Intent(this, AutomixService::class.java))
            s.startAutomix(display, 0)
        }
        pushState { it.copy(automixEnabled = true, statusText = "Automix activo · selección y mezcla automáticas") }
        refreshNow()
    }

private fun playAt(index: Int) {
        val display = buildDisplayList()
        if (display.isEmpty()) {
            Toast.makeText(this, "No hay canciones cargadas", Toast.LENGTH_SHORT).show()
            return
        }
        startForegroundService(Intent(this, AutomixService::class.java))
        svc?.let {
            it.playPercent = percent; it.fadeSec = fade; it.skipIntroSec = skip
            it.transitionStyle = transitionStyle
            it.startOrdered(display, index.coerceAtLeast(0))
        }
    }

    private fun reshuffleQueueHot() {
        val s = svc
        if (s?.current == null) {
            Toast.makeText(this, "Reproduce algo primero", Toast.LENGTH_SHORT).show()
            return
        }
        val currentUri = s.current?.uri
        val currentTrack = filteredTracks.firstOrNull { it.uri == currentUri }
        val others = filteredTracks.filter { it.uri != currentUri }.shuffled()
        val newQueue = if (currentTrack != null) listOf(currentTrack) + others else others
        s.reorderQueue(newQueue)
        Toast.makeText(this, "🔀 Cola re-barajada (${others.size} canciones)", Toast.LENGTH_SHORT).show()
        refreshNow()
    }

    private fun shuffleWithBpmVariety(list: List<Track>): List<Track> {
        if (list.size < 3) return list.shuffled()
        val buckets = mutableMapOf<Int, MutableList<Track>>()
        for (t in list) {
            val bpm = bpmCache[t.uri.toString()] ?: 0
            val bucket = if (bpm == 0) -1 else bpm / 10
            buckets.getOrPut(bucket) { mutableListOf() }.add(t)
        }
        buckets.values.forEach { it.shuffle() }
        val bucketKeys = buckets.keys.filter { it >= 0 }.shuffled().toMutableList()
        val result = mutableListOf<Track>()
        val unknown = buckets[-1]?.toMutableList() ?: mutableListOf()
        while (bucketKeys.isNotEmpty() || unknown.isNotEmpty()) {
            bucketKeys.shuffle()
            val iter = bucketKeys.iterator()
            while (iter.hasNext()) {
                val key = iter.next()
                val bucket = buckets[key] ?: continue
                if (bucket.isEmpty()) {
                    iter.remove()
                } else {
                    result.add(bucket.removeAt(0))
                }
            }
            if (unknown.isNotEmpty() && result.size % 3 == 0) {
                result.add(unknown.removeAt(0))
            }
        }
        result.addAll(unknown)
        for (i in result.indices) {
            if (i > 0 && i < result.size - 1 && Math.random() < 0.15) {
                val j = (i + 1).coerceAtMost(result.size - 1)
                val tmp = result[i]
                result[i] = result[j]
                result[j] = tmp
            }
        }
        return result
    }

    private fun playRandom() {
        if (filteredTracks.isEmpty()) {
            Toast.makeText(this, "No hay canciones cargadas", Toast.LENGTH_SHORT).show()
            return
        }
        val shuffled = shuffleWithBpmVariety(filteredTracks)
        startForegroundService(Intent(this, AutomixService::class.java))
        svc?.let {
            it.playPercent = percent
            it.fadeSec = fade
            it.skipIntroSec = skip
            it.transitionStyle = transitionStyle
            it.startOrdered(shuffled, 0)
        }
        Toast.makeText(this, "🎲 Nueva mezcla: ${shuffled.size} canciones", Toast.LENGTH_SHORT).show()
        refreshNow()
    }

    private fun togglePlayPause() {
        val s = svc ?: return
        if (s.current == null && filteredTracks.isNotEmpty()) {
            playAt(0)
        } else {
            s.togglePause()
        }
    }

    private fun startMashup(secondIdx: Int) {
        val display = buildDisplayList()
        if (display.size < 2) { Toast.makeText(this, "Necesitas al menos dos canciones", Toast.LENGTH_SHORT).show(); return }
        val cur = svc?.current
        val firstIdx = display.indexOfFirst { it.uri == cur?.uri }.coerceAtLeast(0)
        if (firstIdx == secondIdx) { Toast.makeText(this, "Elige otra canción", Toast.LENGTH_SHORT).show(); return }
        startForegroundService(Intent(this, AutomixService::class.java))
        svc?.startMashup(display, firstIdx, secondIdx)
    }

    // ---------------- Análisis ----------------

    /** Reutiliza la misma rejilla BPM que usa Automix; solo recurre al detector antiguo si falla. */
    private fun detectBpmShared(uri: Uri): Int? {
        val grid = try { BpmAnalysisRepository.getOrDetect(this, uri) } catch (_: Throwable) { null }
        return grid?.bpm?.let { kotlin.math.round(it).toInt() }
            ?: try { BpmDetector.detect(this, uri) } catch (_: Throwable) { null }
    }

    private fun analyzeLibraryInBackground() {
        if (analysisRunning) return
        analysisRunning = true
        // Captura el orden preferido en el hilo de UI y analiza primero las próximas
        // canciones; después completa el resto de la biblioteca en segundo plano.
        val preferredOrder = buildDisplayList()
        Thread {
            val orderedIds = preferredOrder.map { it.uri.toString() }
            val byId = allTracks.associateBy { it.uri.toString() }
            val prioritized = (orderedIds.mapNotNull { byId[it] } + allTracks)
                .distinctBy { it.uri.toString() }
            val pending = prioritized.filter {
                !bpmCache.containsKey(it.uri.toString()) || !keyCache.containsKey(it.uri.toString())
            }
            var done = 0
            for (t in pending) {
                val id = t.uri.toString()
                try {
                    if (!bpmCache.containsKey(id)) detectBpmShared(t.uri)?.let { bpmCache[id] = it }
                    if (!keyCache.containsKey(id)) KeyAnalysisRepository.getOrDetect(this, t.uri)?.let { keyCache[id] = it }
                    persistCaches()
                } catch (_: Throwable) {}
                done++
                val d = done
                runOnUiThread {
                    svc?.updateMusicAnalysis(bpmCache.toMap(), keyCache.toMap())
                    pushState {
                        it.copy(
                            analysisText = "Analizando: $d/${pending.size} · BPM ${bpmCache.size} · tonalidades ${keyCache.size}",
                            bpmMap = bpmCache.toMap(), keyMap = keyCache.toMap()
                        )
                    }
                    refreshNow()
                }
            }
            analysisRunning = false
            runOnUiThread {
                pushState { it.copy(analysisText = "Análisis terminado · BPM ${bpmCache.size} · tonalidades ${keyCache.size}") }
                if (sortMode == SORT_BPM || sortMode == SORT_SMART) {
                    svc?.reorderQueue(buildDisplayList())
                    refreshNow()
                }
            }
        }.start()
    }

    private fun persistCaches() {
        val bj = JSONObject(); bpmCache.forEach { (k, v) -> bj.put(k, v) }
        val kj = JSONObject(); keyCache.forEach { (k, v) -> kj.put(k, v) }
        prefs.edit().putString("bpm_cache", bj.toString()).putString("key_cache", kj.toString()).apply()
    }

    private fun detectBpmForCurrent() {
        val t = svc?.current ?: run { Toast.makeText(this, "Reproduce una canción primero", Toast.LENGTH_SHORT).show(); return }
        Thread {
            val bpm = detectBpmShared(t.uri)
            runOnUiThread {
                if (bpm != null) { bpmCache[t.uri.toString()] = bpm; persistCaches() }
                svc?.updateMusicAnalysis(bpmCache.toMap(), keyCache.toMap())
                pushState { it.copy(currentBpm = bpm, bpmMap = bpmCache.toMap()) }
                refreshNow()
            }
        }.start()
    }

    private fun detectKeyForCurrent() {
        val t = svc?.current ?: run { Toast.makeText(this, "Reproduce una canción primero", Toast.LENGTH_SHORT).show(); return }
        Thread {
            val key = try { KeyAnalysisRepository.getOrDetect(this, t.uri) } catch (_: Throwable) { null }
            runOnUiThread {
                if (key != null) { keyCache[t.uri.toString()] = key; persistCaches() }
                svc?.updateMusicAnalysis(bpmCache.toMap(), keyCache.toMap())
                pushState { it.copy(currentKey = key, keyMap = keyCache.toMap()) }
                refreshNow()
            }
        }.start()
    }

    // ---------------- Identificación de géneros en segundo plano ----------------

    private fun readGenreCache(): JSONObject = try {
        JSONObject(prefs.getString("genre_cache", "{}") ?: "{}")
    } catch (_: Exception) { JSONObject() }

    private fun startGenreScan() {
        if (checkSelfPermission(audioPerm) != PackageManager.PERMISSION_GRANTED) {
            ensurePermissions()
            return
        }
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<GenreIdentificationWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            GenreIdentificationWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
        pushState { it.copy(genreScanStatus = "Preparando análisis…") }
        Toast.makeText(this, "Identificación musical en segundo plano", Toast.LENGTH_SHORT).show()
    }

    private fun pauseGenreScan() {
        WorkManager.getInstance(this).cancelUniqueWork(GenreIdentificationWorker.UNIQUE_WORK_NAME)
        pushState { it.copy(genreScanStatus = "Pausando…") }
    }

    private suspend fun refreshGenreScanStatus() {
        try {
            val infos = withContext(Dispatchers.IO) {
                WorkManager.getInstance(this@MainActivity)
                    .getWorkInfosForUniqueWork(GenreIdentificationWorker.UNIQUE_WORK_NAME).get()
            }
            val info = infos.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
                ?: infos.lastOrNull()
            if (info != null) {
                val data = if (info.state == WorkInfo.State.SUCCEEDED) info.outputData else info.progress
                val processed = data.getInt("processed", 0)
                val total = data.getInt("total", allTracks.size)
                val identified = data.getInt("identified", 0)
                val pending = data.getInt("pending", (total - processed).coerceAtLeast(0))
                val status = when (info.state) {
                    WorkInfo.State.ENQUEUED -> "En espera de conexión"
                    WorkInfo.State.RUNNING -> data.getString("status") ?: "Analizando biblioteca…"
                    WorkInfo.State.SUCCEEDED -> "Análisis completado"
                    WorkInfo.State.CANCELLED -> "Pausado"
                    WorkInfo.State.FAILED -> "Error; puedes reintentar"
                    else -> "Preparando análisis…"
                }
                val previous = uiState.value.genreScanStatus
                pushState { it.copy(genreScanStatus = status, genreScanProcessed = processed,
                    genreScanTotal = total, genreScanIdentified = identified, genreScanPending = pending) }
                if (info.state == WorkInfo.State.SUCCEEDED && previous != "Análisis completado") {
                    val cache = readGenreCache()
                    allTracks = allTracks.map { t ->
                        val g = cache.optString(t.uri.toString(), "")
                        if (t.genre.isBlank() && g.isNotBlank()) t.copy(genre = g) else t
                    }
                    filteredTracks = filteredTracks.map { t -> allTracks.firstOrNull { it.uri == t.uri } ?: t }
                    pushState { it.copy(tracks = buildDisplayList()) }
                    refreshNow()
                }
            } else {
                val cache = readGenreCache()
                val identified = cache.length()
                pushState { it.copy(genreScanIdentified = identified,
                    genreScanTotal = allTracks.size,
                    genreScanPending = (allTracks.size - identified).coerceAtLeast(0)) }
            }
        } catch (_: Exception) { /* el estado se actualizará en el siguiente ciclo */ }
    }

    // ---------------- Búsqueda web de género para la canción actual ----------------

    private fun lookupGenreForCurrent() {
        val t = svc?.current ?: run {
            Toast.makeText(this, "Reproduce una canción primero", Toast.LENGTH_SHORT).show()
            return
        }
        pushState { it.copy(genre = "Buscando información web…") }
        Thread {
            val result = try { GenreWebLookup.lookup(t.title, t.artist, t.album) } catch (_: Throwable) { null }
            runOnUiThread {
                if (!result.isNullOrBlank()) {
                    val cache = readGenreCache()
                    cache.put(t.uri.toString(), result)
                    val evidence = try { JSONObject(prefs.getString("genre_evidence", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
                    evidence.put(t.uri.toString(), JSONObject().put("source", "Búsqueda web")
                        .put("confidence", "Media").put("status", "Probable").put("genre", result))
                    prefs.edit().putString("genre_cache", cache.toString())
                        .putString("genre_evidence", evidence.toString()).apply()
                    allTracks = allTracks.map { track -> if (track.uri == t.uri && track.genre.isBlank()) track.copy(genre = result) else track }
                    filteredTracks = filteredTracks.map { track -> allTracks.firstOrNull { it.uri == track.uri } ?: track }
                    pushState { it.copy(genre = "Género identificado: $result", tracks = buildDisplayList()) }
                    refreshNow()
                } else {
                    pushState { it.copy(genre = "No se encontró evidencia suficiente; queda sin clasificar") }
                }
            }
        }.start()
    }

    // ---------------- Refresh de UI ----------------

    private fun refreshNow() {
        val s = svc
        val t = s?.current
        val upcoming = s?.getUpcomingQueue(2) ?: emptyList()
        val (pos, dur) = s?.getProgress() ?: (0 to 0)
        val display = buildDisplayList()
        pushState {
            it.copy(
                tracks = display,
                current = t,
                isPlaying = t != null && s?.paused == false,
                isPaused = s?.paused == true,
                automixEnabled = s?.isAutomixEnabled == true,
                currentBpm = t?.let { tr -> bpmCache[tr.uri.toString()] },
                currentKey = t?.let { tr -> keyCache[tr.uri.toString()] },
                bpmMap = bpmCache.toMap(),
                keyMap = keyCache.toMap(),
                upcomingQueue = upcoming,
                positionMs = pos,
                durationMs = dur
            )
        }
    }

    override fun onDestroy() {
        svc?.onChange = null
        try { unbindService(conn) } catch (_: Exception) {}
        super.onDestroy()
    }
}

// ====================== UI ======================

@Composable
fun AutomixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF2E6F40),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFD8F3DC),
            onPrimaryContainer = Color(0xFF1B4332),
            background = Color(0xFFF8F9FA),
            surface = Color.White,
            onSurface = Color(0xFF1A1A1A),
            surfaceVariant = Color(0xFFE9ECEF)
        ),
        content = content
    )
}

fun formatTime(ms: Int): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

@Composable
fun AlbumArt(albumId: Long, size: Dp) {
    val context = LocalContext.current
    var bitmap by remember(albumId) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(albumId) {
        if (albumId <= 0) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            var bmp: android.graphics.Bitmap? = null

            // Intento 1: content://media/external/audio/albumart/{id}
            try {
                val uri1 = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    albumId
                )
                bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.loadThumbnail(uri1, android.util.Size(512, 512), null)
                } else {
                    @Suppress("DEPRECATION")
                    android.provider.MediaStore.Images.Media.getBitmap(resolver, uri1)
                }
            } catch (_: Exception) { bmp = null }

            // Intento 2: content://media/external/audio/albums/{id} (API vieja)
            if (bmp == null) {
                try {
                    @Suppress("DEPRECATION")
                    val uri2 = ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albums"),
                        albumId
                    )
                    bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        resolver.loadThumbnail(uri2, android.util.Size(512, 512), null)
                    } else {
                        @Suppress("DEPRECATION")
                        android.provider.MediaStore.Images.Media.getBitmap(resolver, uri2)
                    }
                } catch (_: Exception) { bmp = null }
            }

            // Intento 3: como último recurso, obtener el álbum por MediaStore.Audio.Albums
            if (bmp == null) {
                try {
                    val albumUri = ContentUris.withAppendedId(
                        android.provider.MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                        albumId
                    )
                    val cursor = resolver.query(
                        albumUri,
                        arrayOf(android.provider.MediaStore.Audio.Albums.ALBUM_ART),
                        null, null, null
                    )
                    cursor?.use { c ->
                        if (c.moveToFirst()) {
                            val artPath = c.getString(0)
                            if (artPath != null) {
                                val file = java.io.File(artPath)
                                if (file.exists()) {
                                    bmp = android.graphics.BitmapFactory.decodeFile(artPath)
                                }
                            }
                        }
                    }
                } catch (_: Exception) { bmp = null }
            }

            if (bmp != null) {
                withContext(Dispatchers.Main) { bitmap = bmp }
            }
        }
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                Icons.Default.MusicNote,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(size / 2)
            )
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomixApp(
    state: MainActivity.UiSnapshot,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onStop: () -> Unit,
    onPlayTrack: (Int) -> Unit,
    onStartAutomix: () -> Unit,
    onSetSort: (String) -> Unit,
    onSetPercent: (Int) -> Unit,
    onSetFade: (Int) -> Unit,
    onSetSkip: (Int) -> Unit,
    onSetTransition: (String) -> Unit,
    onSetFolders: (Set<String>) -> Unit,
    onDetectBpm: () -> Unit,
    onDetectKey: () -> Unit,
    onLookupGenre: () -> Unit,
    onStartGenreScan: () -> Unit,
    onPauseGenreScan: () -> Unit,
    onMashup: (Int) -> Unit,
    onRequestPermissions: () -> Unit,
    onReshuffleQueue: () -> Unit,
    onSearchChange: (String) -> Unit,
    onSortFieldChange: (String) -> Unit,
    onToggleSortDirection: () -> Unit,
    onFilterArtist: (String) -> Unit,
    onFilterAlbum: (String) -> Unit,
    onFilterGenre: (String) -> Unit,
    onSeek: (Int) -> Unit,
    onPlayFiltered: (List<Track>, Int) -> Unit,
    onPlayThisOrder: (List<Track>) -> Unit
) {
    var tab by remember { mutableStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AUTOMIX", fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.primary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, null) },
                    label = { Text("Reproductor") },
                    selected = tab == 0,
                    onClick = { tab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.QueueMusic, null) },
                    label = { Text("Biblioteca") },
                    selected = tab == 1,
                    onClick = { tab = 1 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.PlayArrow, null) },
                    label = { Text("Mashup") },
                    selected = tab == 2,
                    onClick = { tab = 2 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text("Ajustes") },
                    selected = tab == 3,
                    onClick = { tab = 3 }
                )
            }
        }
    ) { pad ->
        Box(modifier = Modifier.padding(pad)) {
            when (tab) {
                0 -> PlayerScreen(
                    state = state,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onPrev = onPrev,
                    onStop = onStop,
                    onPlayTrack = onPlayTrack,
                    onStartAutomix = onStartAutomix,
                    onSetSort = onSetSort,
                    onRequestPermissions = onRequestPermissions,
                    onSeek = onSeek
                )
                1 -> LibraryScreen(
                    state = state,
                    onSearchChange = onSearchChange,
                    onSortChange = onSortFieldChange,
                    onToggleSortDirection = onToggleSortDirection,
                    onFilterArtist = onFilterArtist,
                    onFilterAlbum = onFilterAlbum,
                    onFilterGenre = onFilterGenre,
                    onPlayTrack = onPlayFiltered,
                    onPlayThisOrder = onPlayThisOrder,
                    onStartGenreScan = onStartGenreScan,
                    onPauseGenreScan = onPauseGenreScan
                )
                2 -> MashupScreen(state, onMashup)
                3 -> SettingsScreen(
                    state = state,
                    onSetPercent = onSetPercent,
                    onSetFade = onSetFade,
                    onSetSkip = onSetSkip,
                    onSetTransition = onSetTransition,
                    onDetectBpm = onDetectBpm,
                    onDetectKey = onDetectKey,
                    onLookupGenre = onLookupGenre
                )
            }
        }
    }
}

@Composable
fun PlayerScreen(
    state: MainActivity.UiSnapshot,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onStop: () -> Unit,
    onPlayTrack: (Int) -> Unit,
    onStartAutomix: () -> Unit,
    onSetSort: (String) -> Unit,
    onRequestPermissions: () -> Unit,
    onSeek: (Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AlbumArt(
                        albumId = state.current?.albumId ?: -1L,
                        size = 160.dp
                    )

                    Spacer(Modifier.height(20.dp))

                    Text(
                        state.current?.title ?: "Sin reproducción",
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        state.current?.artist ?: "Toca una canción",
                        fontSize = 15.sp,
                        color = Color.Gray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.currentBpm?.let { InfoChip("$it BPM") }
                        state.currentKey?.let { InfoChip(shortKey(it)) }
                        InfoChip("${state.tracks.size} pistas")
                    }

                    // 🎯 Barra de progreso interactiva
                    
             Spacer(Modifier.height(16.dp))
                    var isDragging by remember { mutableStateOf(false) }
                    var dragValue by remember { mutableFloatStateOf(0f) }
                    val dur = state.durationMs.coerceAtLeast(1)
                    val sliderPos = if (isDragging) dragValue else state.positionMs.toFloat()

                    Slider(
                        value = sliderPos.coerceIn(0f, dur.toFloat()),
                        onValueChange = {
                            isDragging = true
                            dragValue = it
                        },
                        onValueChangeFinished = {
                            onSeek(dragValue.toInt())
                            isDragging = false
                        },
                        valueRange = 0f..dur.toFloat(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatTime(sliderPos.toInt()), fontSize = 11.sp, color = Color.Gray)
                        Text(formatTime(state.durationMs), fontSize = 11.sp, color = Color.Gray)
                    }

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = onStartAutomix,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.automixEnabled) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            if (state.automixEnabled) Icons.Default.Stop else Icons.Default.PlayArrow,
                            null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (state.automixEnabled) "DETENER AUTOMIX" else "AUTOMIX",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            letterSpacing = 2.sp
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onPrev) {
                            Icon(Icons.Default.SkipPrevious, "Anterior", modifier = Modifier.size(32.dp))
                        }
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(64.dp)
                        ) {
                            IconButton(onClick = onPlayPause, modifier = Modifier.fillMaxSize()) {
                                Icon(
                                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    "Play/Pausa",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                        IconButton(onClick = onNext) {
                            Icon(Icons.Default.SkipNext, "Siguiente", modifier = Modifier.size(32.dp))
                        }
                        IconButton(onClick = onStop) {
                            Icon(Icons.Default.Close, "Stop", modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
        }

        item {
            if (state.upcomingQueue.isNotEmpty()) {
                CompactQueue(
                    upcoming = state.upcomingQueue,
                    totalCount = state.upcomingQueue.size,
                    bpmMap = state.bpmMap,
                    keyMap = state.keyMap,
                    onPlayTrack = { track ->
                        val idx = state.tracks.indexOfFirst { it.uri == track.uri }
                        if (idx >= 0) onPlayTrack(idx)
                    }
                )
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                ModeButton(
                    "Aleatorio",
                    Icons.Default.Shuffle,
                    state.sortMode == "Aleatorio",
                    Modifier.fillMaxWidth(),
                    Color(0xFFFF9800)
                ) { onSetSort("Aleatorio") }
            }
        }

        if (!state.permissionGranted && state.tracks.isEmpty()) {
            item {
                Text(
                    "Esperando permiso de audio…",
                    color = Color.Gray,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }

        item {
            TextButton(
                onClick = onRequestPermissions,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Recargar biblioteca (${state.tracks.size})",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun InfoChip(text: String) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(text, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

private fun shortKey(key: String) = if (key.endsWith(" minor")) key.removeSuffix(" minor") + "m" else key.removeSuffix(" major")

@Composable
fun ModeButton(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    accentColor: Color = Color(0xFFFF9800),
    onClick: () -> Unit
) {
    // Rojo = modo no seleccionado; cada modo conserva su color distintivo al activarse.
    val bg = if (selected) accentColor else Color(0xFFD32F2F)
    val fg = Color.White
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = bg,
        modifier = modifier.height(80.dp).clickable(onClick = onClick),
        shadowElevation = if (selected) 5.dp else 1.dp
    ) {
        Column(
            Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                title,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = fg
            )
            if (selected) {
                Spacer(Modifier.height(3.dp))
                Text("ACTIVO", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = fg)
            }
        }
    }
}

@Composable
fun TrackItem(track: Track, bpm: Int?, key: String?, playing: Boolean, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = if (playing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, fontSize = 12.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(bpm?.toString() ?: "—", fontSize = 12.sp, color = Color.Gray)
                Text(key?.let { shortKey(it) } ?: "—", fontSize = 12.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
fun CompactQueue(
    upcoming: List<Track>,
    totalCount: Int,
    bpmMap: Map<String, Int>,
    keyMap: Map<String, String>,
    onPlayTrack: (Track) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val visible = if (expanded) upcoming else upcoming.take(2)

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.QueueMusic,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "A continuación",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (upcoming.size > 2) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(
                            if (expanded) "Ver menos ▲" else "Ver todas ($totalCount) ▼",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (visible.isEmpty()) {
                Text(
                    "Sin más canciones en la cola",
                    fontSize = 11.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            } else {
                visible.forEachIndexed { i, track ->
                    val isNextTrack = i == 0
                    val id = track.uri.toString()
                    val bpm = bpmMap[id]
                    val musicalKey = keyMap[id]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isNextTrack) Color(0xFF35C979).copy(alpha = 0.18f)
                                else Color.Transparent
                            )
                            .clickable { onPlayTrack(track) }
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (isNextTrack) "▶" else "${i + 1}.",
                            fontSize = 11.sp,
                            fontWeight = if (isNextTrack) FontWeight.Bold else FontWeight.Normal,
                            color = if (isNextTrack) Color(0xFF35C979) else Color.Gray,
                            modifier = Modifier.width(22.dp)
                        )
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    track.title,
                                    fontSize = 12.sp,
                                    fontWeight = if (isNextTrack) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (isNextTrack) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "SIGUIENTE",
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF168A4B)
                                    )
                                }
                            }
                            Text(
                                track.artist,
                                fontSize = 10.sp,
                                color = Color.Gray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("BPM ${bpm?.toString() ?: "—"}", fontSize = 9.sp, color = if (bpm != null) MaterialTheme.colorScheme.primary else Color.Gray)
                                Text("Tono ${musicalKey ?: "Analizando…"}", fontSize = 9.sp, color = if (musicalKey != null) MaterialTheme.colorScheme.primary else Color.Gray)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MashupScreen(state: MainActivity.UiSnapshot, onMashup: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Mashup", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text("Elige una segunda pista para superponer con la actual.", color = Color.Gray, fontSize = 14.sp)
        if (state.tracks.size < 2) {
            Text("Necesitas al menos dos canciones en la biblioteca.", color = Color.Gray)
            return
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.tracks) { t ->
                val idx = state.tracks.indexOf(t)
                val isCurrent = state.current?.uri == t.uri
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !isCurrent) { onMashup(idx) }
                ) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t.title, Modifier.weight(1f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (isCurrent) Text("(actual)", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: MainActivity.UiSnapshot,
    onSearchChange: (String) -> Unit,
    onSortChange: (String) -> Unit,
    onToggleSortDirection: () -> Unit,
    onFilterArtist: (String) -> Unit,
    onFilterAlbum: (String) -> Unit,
    onFilterGenre: (String) -> Unit,
    onPlayTrack: (List<Track>, Int) -> Unit,
    onPlayThisOrder: (List<Track>) -> Unit,
    onStartGenreScan: () -> Unit,
    onPauseGenreScan: () -> Unit
) {
    val tabs = listOf("Canciones", "Álbumes", "Artistas", "Géneros", "Carpetas")
    val context = LocalContext.current
    val libraryPrefs = remember(context) { context.getSharedPreferences("automix", Context.MODE_PRIVATE) }
    var selectedTab by remember { mutableIntStateOf(libraryPrefs.getInt("library_tab", 0).coerceIn(0, 4)) }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var selectedAlbum by remember { mutableStateOf<String?>(null) }
    var selectedArtist by remember { mutableStateOf<String?>(null) }
    var selectedGenre by remember { mutableStateOf<String?>(null) }
    var sortExpanded by remember { mutableStateOf(false) }

    val filtered = remember(
        state.tracks, state.searchQuery, state.filterArtist,
        state.filterAlbum, state.filterGenre, state.sortField,
        state.sortAscending, state.bpmMap
    ) {
        var list = state.tracks
        val q = state.searchQuery.trim().lowercase()
        if (q.isNotEmpty()) list = list.filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) ||
                it.album.lowercase().contains(q) || it.genre.lowercase().contains(q) || it.folder.lowercase().contains(q)
        }
        if (state.filterArtist.isNotEmpty()) list = list.filter { it.artist == state.filterArtist }
        if (state.filterAlbum.isNotEmpty()) list = list.filter { it.album == state.filterAlbum }
        if (state.filterGenre.isNotEmpty()) list = list.filter { it.genre == state.filterGenre }
        val asc = state.sortAscending
        when (state.sortField) {
            "Artista" -> if (asc) list.sortedBy { it.artist.lowercase() } else list.sortedByDescending { it.artist.lowercase() }
            "Álbum" -> if (asc) list.sortedBy { it.album.lowercase() } else list.sortedByDescending { it.album.lowercase() }
            "BPM" -> if (asc) list.sortedBy { state.bpmMap[it.uri.toString()] ?: Int.MAX_VALUE }
                     else list.sortedByDescending { state.bpmMap[it.uri.toString()] ?: Int.MIN_VALUE }
            "Género" -> if (asc) list.sortedBy { it.genre.lowercase() } else list.sortedByDescending { it.genre.lowercase() }
            "Más antiguos" -> list.sortedBy { it.dateModifiedMs.takeIf { d -> d > 0 } ?: Long.MAX_VALUE }.let { if (asc) it else it.reversed() }
            "Más recientes" -> list.sortedByDescending { it.dateModifiedMs }.let { if (asc) it else it.reversed() }
            "Duración" -> if (asc) list.sortedBy { it.durationMs } else list.sortedByDescending { it.durationMs }
            else -> if (asc) list.sortedBy { it.title.lowercase() } else list.sortedByDescending { it.title.lowercase() }
        }
    }
    val artists = remember(filtered) { filtered.groupBy { it.artist }.toSortedMap(String.CASE_INSENSITIVE_ORDER) }
    val albums = remember(filtered) { filtered.groupBy { if (it.album.isBlank()) "Álbum desconocido" else it.album }.toSortedMap(String.CASE_INSENSITIVE_ORDER) }
    val genres = remember(filtered) { filtered.groupBy { if (it.genre.isBlank()) "Género desconocido" else it.genre }.toSortedMap(String.CASE_INSENSITIVE_ORDER) }
    val folders = remember(filtered) { filtered.groupBy { it.folder.ifBlank { "Música" } }.toSortedMap(String.CASE_INSENSITIVE_ORDER) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 8.dp) {
            tabs.forEachIndexed { index, title ->
                Tab(selected = selectedTab == index, onClick = {
                    selectedTab = index
                    libraryPrefs.edit().putInt("library_tab", index).apply()
                    selectedFolder = null; selectedAlbum = null; selectedArtist = null; selectedGenre = null
                }, text = { Text(title, maxLines = 1) })
            }
        }
        OutlinedTextField(
            value = state.searchQuery, onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            placeholder = { Text("Buscar canciones, artistas, álbumes…") },
            leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true
        )
        if (selectedTab == 0) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { sortExpanded = true }, modifier = Modifier.fillMaxWidth().height(42.dp)) {
                        Icon(Icons.Default.Sort, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text("Ordenar: ${state.sortField}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                        listOf("Título", "Artista", "Álbum", "Género", "BPM", "Más antiguos", "Más recientes", "Duración").forEach { field ->
                            DropdownMenuItem(text = { Text(when(field) { "Más antiguos" -> "Más antiguos primero"; "Más recientes" -> "Más recientes primero"; "Duración" -> "Duración"; else -> field }) }, onClick = { onSortChange(field); sortExpanded = false })
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(onClick = onToggleSortDirection) {
                    Icon(if (state.sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, "Cambiar dirección")
                }
            }
            Text("${filtered.size} canciones", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(filtered, key = { it.uri.toString() }) { track ->
                    val idx = filtered.indexOf(track)
                    TrackItem(track, state.bpmMap[track.uri.toString()], state.keyMap[track.uri.toString()], state.current?.uri == track.uri) { onPlayTrack(filtered, idx) }
                }
            }
            Button(onClick = { onPlayThisOrder(filtered) }, modifier = Modifier.fillMaxWidth().padding(12.dp).height(48.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Reproducir este orden", fontWeight = FontWeight.Bold)
            }
        } else if (selectedTab == 1) {
            if (selectedAlbum != null) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selectedAlbum = null }) { Icon(Icons.Default.ArrowBack, "Volver") }
                    Text(selectedAlbum!!, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val albumTracks = albums[selectedAlbum].orEmpty()
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    items(albumTracks, key = { it.uri.toString() }) { t -> TrackItem(t, state.bpmMap[t.uri.toString()], state.keyMap[t.uri.toString()], state.current?.uri == t.uri) { onPlayTrack(albumTracks, albumTracks.indexOf(t)) } }
                }
            } else {
                LazyVerticalGrid(GridCells.Adaptive(145.dp), Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    gridItems(albums.keys.toList(), key = { it }) { album ->
                        val list = albums[album].orEmpty(); val sample = list.firstOrNull()
                        Card(modifier = Modifier.fillMaxWidth().clickable { selectedAlbum = album }, shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.padding(10.dp)) {
                                if (sample != null) AlbumArt(sample.albumId, 120.dp) else Icon(Icons.Default.Album, null, modifier = Modifier.size(120.dp))
                                Spacer(Modifier.height(8.dp)); Text(album, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${list.firstOrNull()?.artist ?: "Artista desconocido"} · ${list.size} temas", fontSize = 11.sp, color = Color.Gray, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        } else if (selectedTab == 2) {
            if (selectedArtist != null) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { selectedArtist = null }) { Icon(Icons.Default.ArrowBack, "Volver") }; Text(selectedArtist!!, fontWeight = FontWeight.Bold) }
                val artistTracks = artists[selectedArtist].orEmpty()
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp)) { items(artistTracks, key = { it.uri.toString() }) { t -> TrackItem(t, state.bpmMap[t.uri.toString()], state.keyMap[t.uri.toString()], state.current?.uri == t.uri) { onPlayTrack(artistTracks, artistTracks.indexOf(t)) } } }
            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    artists.forEach { (artist, list) -> item(key = artist) { Card(Modifier.fillMaxWidth().clickable { selectedArtist = artist }) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp)); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(artist, fontWeight = FontWeight.SemiBold); Text("${list.size} canciones", fontSize = 12.sp, color = Color.Gray) }; Icon(Icons.Default.ChevronRight, null) } } } }
                }
            }
        } else if (selectedTab == 3) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Identificación musical", fontWeight = FontWeight.Bold)
                            Text(state.genreScanStatus, fontSize = 12.sp, color = Color.Gray)
                        }
                        if (state.genreScanStatus == "Analizando biblioteca…" || state.genreScanStatus == "Preparando análisis…" || state.genreScanStatus == "Pausando…" || state.genreScanStatus == "En espera de conexión") {
                            TextButton(onClick = onPauseGenreScan) { Text("Pausar") }
                        } else {
                            TextButton(onClick = onStartGenreScan) {
                                Text(when {
                                    state.genreScanStatus == "Pausado" || state.genreScanStatus.startsWith("Error") -> "Reanudar"
                                    state.genreScanStatus == "Análisis completado" -> "Reintentar pendientes"
                                    else -> "Iniciar"
                                })
                            }
                        }
                    }
                    val progress = if (state.genreScanTotal > 0) (state.genreScanProcessed.toFloat() / state.genreScanTotal).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text("${state.genreScanProcessed}/${state.genreScanTotal} revisadas · ${state.genreScanIdentified} identificadas · ${state.genreScanPending} pendientes", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(top = 6.dp))
                    Text("Prioridad: metadatos locales → búsqueda web. Los resultados web se guardan como probables; si la evidencia es ambigua, la canción queda sin clasificar. Los resultados ya guardados no se vuelven a buscar. Esta versión no necesita API ni clave de servicio; no incluye todavía un modelo de IA remoto.", fontSize = 11.sp, color = Color.Gray)
                }
            }
            if (selectedGenre != null) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { selectedGenre = null }) { Icon(Icons.Default.ArrowBack, "Volver") }; Text(selectedGenre!!, fontWeight = FontWeight.Bold) }
                val genreTracks = genres[selectedGenre].orEmpty()
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp)) { items(genreTracks, key = { it.uri.toString() }) { t -> TrackItem(t, state.bpmMap[t.uri.toString()], state.keyMap[t.uri.toString()], state.current?.uri == t.uri) { onPlayTrack(genreTracks, genreTracks.indexOf(t)) } } }
            } else {
                LazyVerticalGrid(GridCells.Adaptive(145.dp), Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    gridItems(genres.keys.toList(), key = { it }) { genre ->
                        val list = genres[genre].orEmpty()
                        Card(Modifier.fillMaxWidth().clickable { selectedGenre = genre }, shape = RoundedCornerShape(14.dp)) { Column(Modifier.padding(16.dp)) { Icon(Icons.Default.LibraryMusic, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp)); Spacer(Modifier.height(12.dp)); Text(genre, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis); Text("${list.size} canciones", fontSize = 12.sp, color = Color.Gray) } }
                    }
                }
            }
        } else {
            if (selectedFolder != null) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { selectedFolder = null }) { Icon(Icons.Default.ArrowBack, "Volver") }; Text(selectedFolder!!, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                val folderTracks = folders[selectedFolder].orEmpty()
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp)) { items(folderTracks, key = { it.uri.toString() }) { t -> TrackItem(t, state.bpmMap[t.uri.toString()], state.keyMap[t.uri.toString()], state.current?.uri == t.uri) { onPlayTrack(folderTracks, folderTracks.indexOf(t)) } } }
            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    folders.forEach { (folder, list) -> item(key = folder) { Card(Modifier.fillMaxWidth().clickable { selectedFolder = folder }) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp)); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(folder.substringAfterLast('/'), fontWeight = FontWeight.SemiBold); Text("${list.size} canciones", fontSize = 12.sp, color = Color.Gray); if (folder.contains('/')) Text(folder, fontSize = 10.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis) }; Icon(Icons.Default.ChevronRight, null) } } } }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterDropdown(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val display = if (selected.isEmpty()) label else selected
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
        ) {
            Text(display, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Todos", fontSize = 13.sp) },
                onClick = { onSelect(""); expanded = false }
            )
            options.filter { it.isNotEmpty() }.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt, fontSize = 13.sp) },
                    onClick = { onSelect(opt); expanded = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: MainActivity.UiSnapshot,
    onSetPercent: (Int) -> Unit,
    onSetFade: (Int) -> Unit,
    onSetSkip: (Int) -> Unit,
    onSetTransition: (String) -> Unit,
    onDetectBpm: () -> Unit,
    onDetectKey: () -> Unit,
    onLookupGenre: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Ajustes", fontWeight = FontWeight.Bold, fontSize = 20.sp)

        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Estilo de transición", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Desvanecer", "Ascender").forEach { style ->
                        FilterChip(
                            selected = state.transition == style,
                            onClick = { onSetTransition(style) },
                            label = { Text(style) }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("Reproducir ${state.percent}% de cada canción", fontWeight = FontWeight.Bold)
                Slider(
                    value = state.percent.toFloat(),
                    onValueChange = { onSetPercent(it.toInt()) },
                    valueRange = 40f..95f
                )
                Text("Transición: ${state.fadeSec} segundos", fontWeight = FontWeight.Bold)
                Slider(
                    value = state.fadeSec.toFloat(),
                    onValueChange = { onSetFade(it.toInt()) },
                    valueRange = 2f..15f
                )
                Text(
                    if (state.skipSec == 0) "No saltar intro"
                    else "Saltar los primeros ${state.skipSec} s",
                    fontWeight = FontWeight.Bold
                )
                Slider(
                    value = state.skipSec.toFloat(),
                    onValueChange = { onSetSkip(it.toInt()) },
                    valueRange = 0f..45f
                )
            }
        }

        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Análisis de la pista actual", fontWeight = FontWeight.Bold)
                Button(onClick = onDetectBpm, modifier = Modifier.fillMaxWidth()) {
                    Text(state.currentBpm?.let { "BPM: $it" } ?: "Detectar BPM")
                }
                Button(onClick = onDetectKey, modifier = Modifier.fillMaxWidth()) {
                    Text(state.currentKey?.let { "Tonalidad: ${shortKey(it)}" } ?: "Detectar tonalidad")
                }
                Button(onClick = onLookupGenre, modifier = Modifier.fillMaxWidth()) {
                    Text("Buscar género (Wikipedia / Deezer)")
                }
                state.genre?.let { Text(it, fontSize = 13.sp, color = Color.Gray) }
                if (state.analysisText.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(state.analysisText, fontSize = 12.sp, color = Color.Gray)
                }
            }
        }
    }
}
