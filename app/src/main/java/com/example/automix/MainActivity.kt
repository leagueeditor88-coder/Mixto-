package com.example.automix

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.Color
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore.Audio.Media
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.io.File

class MainActivity : Activity() {

    private var svc: AutomixService? = null
    private var allTracks: List<Track> = emptyList()
    private var tracks: List<Track> = emptyList()
    private var skip = 0
    private var folders: Set<String> = emptySet()
    private var percent = 70
    private var fade = 8

    private lateinit var prefs: SharedPreferences
    private lateinit var nowTitle: TextView
    private lateinit var nowSub: TextView
    private lateinit var pauseBtn: Button
    private lateinit var automixBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var previousBtn: Button
    private lateinit var nextBtn: Button
    private lateinit var list: ListView
    private lateinit var folderBtn: Button
    private lateinit var bpmBtn: Button
    private lateinit var bpmStatus: TextView
    private lateinit var keyBtn: Button
    private lateinit var keyStatus: TextView
    private lateinit var analysisStatus: TextView
    private lateinit var genreBtn: Button
    private lateinit var genreStatus: TextView
    private lateinit var randomModeBtn: Button
    private lateinit var smartModeBtn: Button
    private lateinit var mashupBtn: Button
    private lateinit var transitionBtn: Button
    private var transitionStyle = "Desvanecer"
    private var smartMode = false
    private val bpmCache = mutableMapOf<String, Int>()
    private val keyCache = mutableMapOf<String, String>()
    @Volatile private var libraryAnalysisRunning = false

    private val audioPerm: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as AutomixService.LocalBinder).service
            s.playPercent = percent
            s.fadeSec = fade
            s.skipIntroSec = skip
            s.onChange = { refreshNow() }
            svc = s
            refreshNow()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            svc = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("automix", MODE_PRIVATE)
        percent = prefs.getInt("percent", 70)
        fade = prefs.getInt("fade", 8)
        skip = prefs.getInt("skip", 0)
        folders = prefs.getStringSet("folders", emptySet())?.toSet() ?: emptySet()
        try {
            val savedBpm = JSONObject(prefs.getString("bpm_cache", "{}") ?: "{}")
            savedBpm.keys().forEach { k -> bpmCache[k] = savedBpm.optInt(k) }
            val savedKeys = JSONObject(prefs.getString("key_cache", "{}") ?: "{}")
            savedKeys.keys().forEach { k -> savedKeys.optString(k).takeIf { it.isNotBlank() && it != "null" }?.let { keyCache[k] = it } }
        } catch (_: Exception) {}
        buildUi()
        bindService(Intent(this, AutomixService::class.java), conn, BIND_AUTO_CREATE)
        ensurePermissions()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val activeGreen = Color.rgb(46, 125, 50)
    private val neutralTint = Color.rgb(235, 238, 235)
    private val activeText = Color.WHITE
    private val neutralText = Color.rgb(35, 45, 35)

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        backgroundTintList = ColorStateList.valueOf(neutralTint)
        setTextColor(neutralText)
        setOnClickListener { onClick() }
    }

    private fun setActive(button: Button, active: Boolean) {
        button.backgroundTintList = ColorStateList.valueOf(if (active) activeGreen else neutralTint)
        button.setTextColor(if (active) activeText else neutralText)
    }

    private fun addSlider(
        root: LinearLayout, lo: Int, hi: Int, value: Int,
        label: (Int) -> String, onValue: (Int) -> Unit
    ) {
        val tv = TextView(this).apply { text = label(value) }
        val sb = SeekBar(this).apply {
            max = hi - lo
            progress = value - lo
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + lo
                    tv.text = label(v)
                    onValue(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(tv)
        root.addView(sb)
    }

    private fun addRow(root: LinearLayout, vararg views: Button) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        views.forEach {
            row.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(row)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(8))
            setBackgroundColor(Color.rgb(248, 250, 248))
        }
        nowTitle = TextView(this).apply {
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            text = "Automix"
        }
        nowSub = TextView(this).apply {
            textSize = 14f
            text = "Toca una canción o pulsa Automix"
        }
        root.addView(nowTitle)
        root.addView(nowSub)

        automixBtn = btn("Automix") { startAutomix(-1) }
        folderBtn = btn("Carpetas: todas") { showFolderDialog() }
        stopBtn = btn("Stop") { svc?.stopAll(); refreshNow() }
        previousBtn = btn("⏮ Anterior") { svc?.previous() }
        pauseBtn = btn("Pausa") { svc?.togglePause() }
        nextBtn = btn("Siguiente ⏭") { svc?.next() }
        addRow(root, automixBtn, folderBtn, stopBtn)
        addRow(root, previousBtn, pauseBtn, nextBtn)
        randomModeBtn = btn("Aleatorio") {
            smartMode = false
            setActive(randomModeBtn, true)
            setActive(smartModeBtn, false)
            Toast.makeText(this, "Modo aleatorio: orden mezclado", Toast.LENGTH_SHORT).show()
        }
        smartModeBtn = btn("Smart Reorder") {
            smartMode = true
            setActive(randomModeBtn, false)
            setActive(smartModeBtn, true)
            Toast.makeText(this, "Smart Reorder combina BPM y tonalidad armónica; el análisis de biblioteca se guarda en el dispositivo", Toast.LENGTH_LONG).show()
            startAutomix(-1)
        }
        mashupBtn = btn("Mashup opcional") { showMashupDialog() }
        transitionBtn = btn("Transición: Desvanecer") { showTransitionDialog() }
        addRow(root, randomModeBtn, smartModeBtn, mashupBtn)
        root.addView(transitionBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setActive(randomModeBtn, true)
        bpmBtn = btn("Detectar BPM actual") { detectCurrentBpm() }
        root.addView(bpmBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bpmStatus = TextView(this).apply {
            text = "BPM: sin analizar"
            textSize = 14f
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(bpmStatus)

        keyBtn = btn("Detectar tonalidad actual") { detectCurrentKey() }
        root.addView(keyBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        keyStatus = TextView(this).apply {
            text = "Tonalidad: sin analizar"
            textSize = 14f
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(keyStatus)

        analysisStatus = TextView(this).apply {
            text = "Biblioteca: esperando permiso para analizar BPM y tonalidad"
            textSize = 13f
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(analysisStatus)

        genreBtn = btn("Buscar género online") { searchCurrentGenre() }
        root.addView(genreBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        genreStatus = TextView(this).apply {
            text = "Género: sin consultar"
            textSize = 14f
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(genreStatus)

        addSlider(root, 40, 95, percent, { "Reproducir $it% de cada canción" }) {
            percent = it
            svc?.playPercent = it
            prefs.edit().putInt("percent", it).apply()
        }
        addSlider(root, 2, 15, fade, { "Transición: $it segundos" }) {
            fade = it
            svc?.fadeSec = it
            prefs.edit().putInt("fade", it).apply()
        }
        addSlider(root, 0, 45, skip, { if (it == 0) "No saltar intro" else "Saltar los primeros $it s" }) {
            skip = it
            svc?.skipIntroSec = it
            prefs.edit().putInt("skip", it).apply()
        }

        list = ListView(this)
        list.setOnItemClickListener { _, _, position, _ -> startAutomix(position) }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun ensurePermissions() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(audioPerm) != PackageManager.PERMISSION_GRANTED) needed += audioPerm
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.isEmpty()) loadTracks() else requestPermissions(needed.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 1) {
            if (checkSelfPermission(audioPerm) == PackageManager.PERMISSION_GRANTED) {
                // Wait until the permission dialog has fully closed before querying MediaStore.
                window.decorView.post { safelyLoadTracks() }
            } else {
                nowSub.text = "Permiso de música no concedido"
                analysisStatus.text = "Concede acceso a música para cargar la biblioteca."
            }
        }
    }

    private fun safelyLoadTracks() {
        try {
            loadTracks()
        } catch (t: Throwable) {
            libraryAnalysisRunning = false
            nowSub.text = "No se pudo cargar la biblioteca"
            analysisStatus.text = "Error al leer archivos. Cierra y vuelve a abrir Automix."
            Toast.makeText(this, "Error leyendo la biblioteca: ${t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            android.util.Log.e("Automix", "MediaStore library scan failed", t)
        }
    }

    private fun loadTracks() {
        if (checkSelfPermission(audioPerm) != PackageManager.PERMISSION_GRANTED) {
            nowSub.text = "Necesito permiso para leer tu música"
            return
        }
        val found = mutableListOf<Track>()
        val proj = arrayOf(Media._ID, Media.TITLE, Media.ARTIST, Media.DURATION, Media.DATA)
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
            require(iId >= 0 && iTitle >= 0 && iDur >= 0) { "MediaStore missing required columns" }
            while (c.moveToNext()) {
                val path = if (iData >= 0) c.getString(iData) else null
                found += Track(
                    ContentUris.withAppendedId(Media.EXTERNAL_CONTENT_URI, c.getLong(iId)),
                    c.getString(iTitle) ?: "Sin título",
                    if (iArtist >= 0) c.getString(iArtist)?.takeIf { it != "<unknown>" } ?: "Artista desconocido" else "Artista desconocido",
                    c.getLong(iDur),
                    path?.let { File(it).parent } ?: "Música"
                )
            }
        }
        allTracks = found
        applyFilter()
        if (found.isEmpty()) {
            nowSub.text = "No encontré canciones en el dispositivo"
            analysisStatus.text = "No hay canciones compatibles en la biblioteca."
        } else {
            val missing = found.count { !bpmCache.containsKey(it.uri.toString()) || !keyCache.containsKey(it.uri.toString()) }
            analysisStatus.text = "Biblioteca: ${found.size} canciones · ${found.size - missing} analizadas · ${bpmCache.size} BPM · ${keyCache.size} tonalidades"
            if (missing > 0) analyzeLibraryInBackground()
        }
    }

    private fun applyFilter() {
        val valid = folders.filter { f -> allTracks.any { it.folder == f } }.toSet()
        tracks = if (valid.isEmpty()) allTracks else allTracks.filter { it.folder in valid }
        list.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            tracks.map { "${it.title} — ${it.artist}" }
        )
        folderBtn.text = if (valid.isEmpty()) "Carpetas: todas" else "Carpetas (${valid.size})"
        setActive(folderBtn, valid.isNotEmpty())
    }

    private fun showFolderDialog() {
        val counts = allTracks.groupingBy { it.folder }.eachCount()
        val paths = counts.keys.sortedBy { it.lowercase() }
        if (paths.isEmpty()) {
            Toast.makeText(this, "No hay carpetas con música", Toast.LENGTH_SHORT).show()
            return
        }
        val names = paths.map { p -> p.substringAfterLast('/').ifEmpty { "/" } + " (" + counts[p] + ")" }.toTypedArray()
        val sel = folders.toMutableSet()
        val checked = BooleanArray(paths.size) { paths[it] in sel }
        AlertDialog.Builder(this)
            .setTitle("Carpetas a mezclar")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                val p = paths[which]
                if (isChecked) sel.add(p) else sel.remove(p)
            }
            .setPositiveButton("Aplicar") { _, _ ->
                folders = sel
                prefs.edit().putStringSet("folders", sel).apply()
                applyFilter()
                Toast.makeText(this, "Se aplica al pulsar Automix", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Todas") { _, _ ->
                folders = emptySet()
                prefs.edit().remove("folders").apply()
                applyFilter()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun startAutomix(startAt: Int) {
        if (tracks.isEmpty()) {
            Toast.makeText(this, "No hay canciones cargadas", Toast.LENGTH_SHORT).show()
            return
        }
        startForegroundService(Intent(this, AutomixService::class.java))
        svc?.let {
            it.playPercent = percent
            it.fadeSec = fade
            it.skipIntroSec = skip
            it.transitionStyle = transitionStyle
            val selectedUri = tracks.getOrNull(startAt)?.uri
            val source = if (smartMode) smartReorder(tracks) else tracks
            val index = if (selectedUri != null) source.indexOfFirst { track -> track.uri == selectedUri } else -1
            it.start(source, index, shuffle = !smartMode)
            if (smartMode) {
                val known = source.count { track -> bpmCache.containsKey(track.uri.toString()) && keyCache.containsKey(track.uri.toString()) }
                Toast.makeText(this, "Smart Reorder: $known de ${source.size} canciones tienen BPM y tonalidad analizados", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun smartReorder(input: List<Track>): List<Track> {
        val remaining = input.toMutableList()
        if (remaining.size < 3) return remaining
        val known = remaining.filter { bpmCache.containsKey(it.uri.toString()) || keyCache.containsKey(it.uri.toString()) }.toMutableList()
        val unknown = remaining.filterNot { it in known }
        if (known.size < 2) return input
        val ordered = mutableListOf<Track>()
        ordered += known.removeAt(0)
        while (known.isNotEmpty()) {
            val last = ordered.last()
            val lastBpm = bpmCache[last.uri.toString()]
            val lastKey = keyCache[last.uri.toString()]
            val next = known.minByOrNull { candidate ->
                val bpm = bpmCache[candidate.uri.toString()]
                val key = keyCache[candidate.uri.toString()]
                val bpmCost = if (lastBpm != null && bpm != null) minOf(kotlin.math.abs(lastBpm - bpm), kotlin.math.abs(lastBpm - bpm * 2), kotlin.math.abs(lastBpm * 2 - bpm)).toDouble() / 12.0 else 2.0
                val keyCost = if (lastKey != null && key != null) {
                    if (harmonicallyCompatible(lastKey, key)) 0.0 else 3.0
                } else 1.2
                bpmCost + keyCost
            } ?: break
            known.remove(next)
            ordered += next
        }
        ordered += unknown
        return ordered
    }

    private fun harmonicallyCompatible(a: String, b: String): Boolean {
        val camelot = mapOf(
            "C major" to "8B", "G major" to "9B", "D major" to "10B", "A major" to "11B", "E major" to "12B", "B major" to "1B", "F# major" to "2B", "C# major" to "3B", "G# major" to "4B", "D# major" to "5B", "A# major" to "6B", "F major" to "7B",
            "A minor" to "8A", "E minor" to "9A", "B minor" to "10A", "F# minor" to "11A", "C# minor" to "12A", "G# minor" to "1A", "D# minor" to "2A", "A# minor" to "3A", "F minor" to "4A", "C minor" to "5A", "G minor" to "6A", "D minor" to "7A"
        )
        val x = camelot[a] ?: return false
        val y = camelot[b] ?: return false
        val xn = x.dropLast(1).toInt(); val yn = y.dropLast(1).toInt()
        val adjacent = (xn == yn || (xn % 12) + 1 == yn || (yn % 12) + 1 == xn || x.last() != y.last() && xn == yn)
        return adjacent
    }

    private fun persistAnalysisCaches() {
        val bpmJson = JSONObject()
        bpmCache.forEach { (k, v) -> bpmJson.put(k, v) }
        val keyJson = JSONObject()
        keyCache.forEach { (k, v) -> keyJson.put(k, v) }
        prefs.edit().putString("bpm_cache", bpmJson.toString()).putString("key_cache", keyJson.toString()).apply()
    }

    private fun analyzeLibraryInBackground() {
        if (libraryAnalysisRunning) return
        libraryAnalysisRunning = true
        Thread {
            val candidates = allTracks.toList().filter { !bpmCache.containsKey(it.uri.toString()) || !keyCache.containsKey(it.uri.toString()) }
            var completed = 0
            for (track in candidates) {
                if (isFinishing || isDestroyed) break
                val id = track.uri.toString()
                try {
                    if (!bpmCache.containsKey(id)) BpmDetector.detect(this, track.uri)?.let { bpmCache[id] = it }
                    if (!keyCache.containsKey(id)) KeyDetector.detect(this, track.uri)?.let { keyCache[id] = it }
                    persistAnalysisCaches()
                } catch (t: Throwable) {
                    android.util.Log.e("Automix", "Analysis failed for $id", t)
                }
                completed++
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        analysisStatus.text = "Analizando biblioteca: $completed/${candidates.size} · BPM ${bpmCache.size} · tonalidades ${keyCache.size}"
                        if (svc?.current?.uri == track.uri) {
                            bpmStatus.text = bpmCache[id]?.let { "BPM estimado: $it" } ?: "BPM: no detectado"
                            keyStatus.text = keyCache[id]?.let { "Tonalidad estimada: $it" } ?: "Tonalidad: no detectada"
                        }
                    }
                }
            }
            libraryAnalysisRunning = false
            runOnUiThread {
                if (!isFinishing && !isDestroyed) analysisStatus.text = "Análisis terminado · BPM ${bpmCache.size} · tonalidades ${keyCache.size}. Los resultados quedan guardados."
            }
        }.start()
    }

    private fun detectCurrentKey() {
        val track = svc?.current
        if (track == null) {
            Toast.makeText(this, "Primero reproduce una canción", Toast.LENGTH_SHORT).show()
            return
        }
        keyBtn.isEnabled = false
        keyBtn.text = "Analizando tonalidad…"
        keyStatus.text = "Tonalidad: analizando audio local…"
        Thread {
            val key = try { KeyDetector.detect(this, track.uri) } catch (t: Throwable) {
                android.util.Log.e("Automix", "Key analysis failed", t); null
            }
            runOnUiThread {
                keyBtn.isEnabled = true
                keyBtn.text = "Detectar tonalidad actual"
                if (key != null) { keyCache[track.uri.toString()] = key; persistAnalysisCaches() }
                keyStatus.text = if (key != null) "Tonalidad estimada: $key (guardada)" else "No pude estimar la tonalidad de esta canción"
                setActive(keyBtn, key != null)
            }
        }.start()
    }

    private fun showTransitionDialog() {
        val options = arrayOf("Desvanecer", "Ascender")
        val selected = options.indexOf(transitionStyle).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Estilo de transición")
            .setSingleChoiceItems(options, selected) { dialog, which ->
                transitionStyle = options[which]
                transitionBtn.text = "Transición: $transitionStyle"
                svc?.transitionStyle = transitionStyle
                setActive(transitionBtn, true)
                dialog.dismiss()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showMashupDialog() {
        if (tracks.size < 2) {
            Toast.makeText(this, "Necesitas al menos dos canciones", Toast.LENGTH_SHORT).show()
            return
        }
        val names = tracks.map { "${it.title} — ${it.artist}" }.toTypedArray()
        val currentIndex = tracks.indexOfFirst { it.uri == svc?.current?.uri }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Mashup: elige la segunda canción")
            .setSingleChoiceItems(names, -1) { dialog, which ->
                if (which == currentIndex) {
                    Toast.makeText(this, "Elige una canción distinta a la principal", Toast.LENGTH_SHORT).show()
                    return@setSingleChoiceItems
                }
                startForegroundService(Intent(this, AutomixService::class.java))
                svc?.let {
                    it.playPercent = percent
                    it.fadeSec = fade
                    it.skipIntroSec = skip
                    it.transitionStyle = transitionStyle
                    it.startMashup(tracks, currentIndex, which)
                }
                dialog.dismiss()
                Toast.makeText(this, "Mashup iniciado: superposición aproximada de 30 segundos", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun detectCurrentBpm() {
        val track = svc?.current
        if (track == null) {
            Toast.makeText(this, "Primero reproduce una canción", Toast.LENGTH_SHORT).show()
            return
        }
        bpmBtn.isEnabled = false
        bpmBtn.text = "Analizando audio…"
        bpmStatus.text = "BPM: analizando los primeros 90 segundos…"
        Thread {
            val bpm = try { BpmDetector.detect(this, track.uri) } catch (t: Throwable) {
                android.util.Log.e("Automix", "BPM analysis failed", t); null
            }
            runOnUiThread {
                bpmBtn.isEnabled = true
                bpmBtn.text = "Detectar BPM actual"
                if (bpm != null) { bpmCache[track.uri.toString()] = bpm; persistAnalysisCaches() }
                bpmStatus.text = if (bpm != null) "BPM estimado: $bpm (guardado para Smart Reorder)" else "No pude estimar el BPM de esta canción"
                setActive(bpmBtn, bpm != null)
            }
        }.start()
    }

    private fun searchCurrentGenre() {
        val track = svc?.current
        if (track == null) {
            Toast.makeText(this, "Primero reproduce una canción", Toast.LENGTH_SHORT).show()
            return
        }
        genreBtn.isEnabled = false
        genreBtn.text = "Buscando género…"
        genreStatus.text = "Consultando MusicBrainz por Internet…"
        Thread {
            val result = lookupGenreOnline(track.title, track.artist)
            runOnUiThread {
                genreBtn.isEnabled = true
                genreBtn.text = "Buscar género online"
                genreStatus.text = result ?: "No encontré un género fiable. Prueba con el título y artista correctos."
                setActive(genreBtn, result != null)
            }
        }.start()
    }

    /** Looks up public MusicBrainz metadata. Genre labels are community tags and may be absent. */
    private fun lookupGenreOnline(title: String, artist: String): String? {
        fun requestJson(urlText: String): JSONObject? {
            var connection: HttpURLConnection? = null
            return try {
                connection = (URL(urlText).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                    setRequestProperty("User-Agent", "AutomixAndroid/1.1 (local music genre lookup)")
                    setRequestProperty("Accept", "application/json")
                }
                if (connection.responseCode !in 200..299) null
                else JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            } catch (_: Exception) {
                null
            } finally {
                connection?.disconnect()
            }
        }
        return try {
            val qTitle = title.replace("\"", " ").trim()
            val qArtist = artist.replace("\"", " ").trim()
            val query = "recording:\"$qTitle\" AND artist:\"$qArtist\""
            val encoded = URLEncoder.encode(query, "UTF-8")
            val search = requestJson("https://musicbrainz.org/ws/2/recording/?query=$encoded&fmt=json&limit=5")
                ?: return "Sin conexión o MusicBrainz no respondió."
            val recordings = search.optJSONArray("recordings") ?: return null
            var bestId: String? = null
            var bestTitle: String? = null
            for (i in 0 until recordings.length()) {
                val item = recordings.optJSONObject(i) ?: continue
                val candidateTitle = item.optString("title", "")
                val credits = item.optJSONArray("artist-credit")
                val candidateArtist = if (credits != null && credits.length() > 0) {
                    credits.optJSONObject(0)?.optJSONObject("artist")?.optString("name", "") ?: ""
                } else ""
                if (candidateTitle.equals(title, ignoreCase = true) &&
                    (artist.equals("Artista desconocido", true) || candidateArtist.contains(artist, true) || artist.contains(candidateArtist, true))) {
                    bestId = item.optString("id", "").takeIf { it.isNotBlank() }
                    bestTitle = candidateTitle
                    break
                }
                if (bestId == null && candidateTitle.equals(title, ignoreCase = true)) {
                    bestId = item.optString("id", "").takeIf { it.isNotBlank() }
                    bestTitle = candidateTitle
                }
            }
            val id = bestId ?: return lookupItunesGenre(title, artist)
                ?: "No encontré coincidencias para «$title» — $artist."
            val detail = requestJson("https://musicbrainz.org/ws/2/recording/$id?inc=genres+tags+artist-credits&fmt=json")
            val genres = linkedSetOf<String>()
            fun collect(arrayName: String) {
                val arr = detail?.optJSONArray(arrayName) ?: return
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val name = obj.optString("name", "").trim()
                    if (name.isNotBlank()) genres.add(name)
                }
            }
            collect("genres")
            collect("tags")
            if (genres.isEmpty()) {
                lookupItunesGenre(title, artist)
                    ?: "Encontré «${bestTitle ?: title}», pero no encontré etiquetas de género fiables en MusicBrainz ni iTunes."
            } else {
                "Género/etiquetas: ${genres.take(5).joinToString(", ")}\nFuente: MusicBrainz (etiquetas comunitarias; pueden ser imprecisas)."
            }
        } catch (_: Exception) {
            "No pude consultar el género. Comprueba tu conexión a Internet."
        }
    }


    /** Fallback de búsqueda: el catálogo de iTunes devuelve el género principal cuando está disponible. */
    private fun lookupItunesGenre(title: String, artist: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val term = URLEncoder.encode("$title $artist", "UTF-8")
            connection = (URL("https://itunes.apple.com/search?term=$term&entity=song&limit=8").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", "AutomixAndroid/1.2")
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) return null
            val json = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val results = json.optJSONArray("results") ?: return null
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val trackName = item.optString("trackName", "")
                val artistName = item.optString("artistName", "")
                val genre = item.optString("primaryGenreName", "")
                if (genre.isNotBlank() && trackName.equals(title, true) &&
                    (artist.equals("Artista desconocido", true) || artistName.contains(artist, true) || artist.contains(artistName, true))) {
                    return "Género probable: $genre\nCoincidencia: $trackName — $artistName\nFuente: catálogo de iTunes."
                }
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun refreshNow() {
        val s = svc
        val t = s?.current
        nowTitle.text = t?.title ?: "Automix"
        nowSub.text = t?.artist ?: "Toca una canción o pulsa Automix"
        if (t != null) {
            val id = t.uri.toString()
            bpmStatus.text = bpmCache[id]?.let { "BPM estimado: $it (guardado)" } ?: "BPM: pendiente de análisis"
            keyStatus.text = keyCache[id]?.let { "Tonalidad estimada: $it (guardada)" } ?: "Tonalidad: pendiente de análisis"
        }
        pauseBtn.text = if (s?.paused == true) "Reanudar" else "Pausa"
        val playing = t != null
        setActive(automixBtn, playing && s?.paused == false)
        setActive(pauseBtn, s?.paused == true)
        setActive(stopBtn, false)
        setActive(previousBtn, false)
        setActive(nextBtn, false)
    }

    override fun onDestroy() {
        svc?.onChange = null
        try { unbindService(conn) } catch (_: Exception) {}
        super.onDestroy()
    }
}
