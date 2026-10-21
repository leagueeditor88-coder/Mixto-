package com.example.automix

import android.text.Html
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

/**
 * Identifica géneros mediante contraste de varios buscadores web HTML.
 * No consulta APIs musicales ni requiere claves de pago. Los resultados de motores
 * distintos se deduplican por URL y la corroboración final se cuenta por dominio web.
 * Las interfaces de IA conversacional no se llaman directamente: requieren una API
 * autenticada o una integración propia y no deben simularse como si fueran buscadores.
 *
 * Política AMX-GEN-WEB-003: no inferir el género por artista, álbum o metadatos locales.
 * Cada evidencia debe mencionar el título y el artista en el mismo resultado.
 */
object GenreWebLookup {
    const val METHOD_VERSION = 3

    data class LookupResult(
        val genre: String?,
        val status: String,
        val confidence: String,
        val sources: List<String>,
        val supportingEvidence: List<String>,
        val queriesUsed: Int,
        val matchedResults: Int,
        val checkedAt: Long = System.currentTimeMillis(),
        val engineDiagnostics: List<String> = emptyList(),
        val durationMs: Long = 0
    ) {
        val identified: Boolean get() = !genre.isNullOrBlank() && status == STATUS_IDENTIFIED
    }

    private data class SearchResult(
        val title: String,
        val snippet: String,
        val url: String,
        val domain: String
    )

    private data class GenreTerm(
        val phrase: String,
        val label: String,
        val parent: String? = null,
        val specificity: Int = 1
    )

    private data class Vote(
        val term: GenreTerm,
        val result: SearchResult,
        val supportingText: String
    )

    const val STATUS_IDENTIFIED = "IDENTIFICADO"
    const val STATUS_INSUFFICIENT = "EVIDENCIA_INSUFICIENTE"
    const val STATUS_UNAVAILABLE = "BUSCADOR_NO_DISPONIBLE"

    /** Compatibilidad con las llamadas antiguas: solo devuelve géneros respaldados. */
    fun lookup(title: String, artist: String, album: String): String? =
        lookupDetailed(title, artist, album).genre

    /** Ejecuta varias búsquedas específicas y evalúa la evidencia por canción y dominio. */
    fun lookupDetailed(title: String, artist: String, album: String): LookupResult {
        if (title.isBlank() || artist.isBlank() || title == "Sin título" || artist == "Artista desconocido") {
            return LookupResult(null, STATUS_INSUFFICIENT, "Ninguna", emptyList(), emptyList(), 0, 0)
        }

        val queries = buildQueries(title.trim(), artist.trim(), album.trim())
        val allResults = mutableListOf<SearchResult>()
        val startedAt = System.nanoTime()
        var successfulRequests = 0
        var failedRequests = 0
        val engineDiagnostics = mutableListOf<String>()
        // Una consulta por motor; cada uno deja estado HTTP, resultados interpretables y duración.
        SEARCH_ENGINES.forEachIndexed { index, engine ->
            val query = queries[index % queries.size]
            when (val response = search(query, engine)) {
                is SearchResponse.Success -> {
                    successfulRequests++
                    allResults += response.results
                    engineDiagnostics += response.diagnostic
                }
                is SearchResponse.Unavailable -> {
                    failedRequests++
                    engineDiagnostics += response.diagnostic
                }
            }
            if (index < SEARCH_ENGINES.lastIndex) Thread.sleep(220)
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L

        if (successfulRequests == 0 && failedRequests > 0) {
            return LookupResult(null, STATUS_UNAVAILABLE, "Ninguna", emptyList(), emptyList(), 0, 0,
                engineDiagnostics = engineDiagnostics.toList(), durationMs = elapsedMs)
        }

        val normalizedTitle = normalize(title)
        val normalizedArtist = normalize(artist)
        val votes = mutableListOf<Vote>()
        val inspectedEvidence = mutableListOf<Pair<SearchResult, String>>()
        val matched = allResults.distinctBy { it.url }.filter { result ->
            val resultText = normalize("${result.title} ${result.snippet}")
            // No usar prefijos arbitrarios: pueden coincidir con canciones distintas.
            val titleMatch = normalizedTitle.length >= 4 && resultText.contains(normalizedTitle)
            val artistMatch = normalizedArtist.length >= 3 && resultText.contains(normalizedArtist)
            titleMatch && artistMatch
        }

        // Inspeccionar páginas reales además de snippets. Se limita el número para que
        // el escaneo no haga peticiones ilimitadas por cada canción.
        val pagesToInspect = matched.distinctBy { it.domain }.take(MAX_PAGES_TO_INSPECT)
        for ((index, result) in pagesToInspect.withIndex()) {
            if (index > 0) Thread.sleep(250)
            val pageText = fetchPageText(result.url)
            val evidenceText = if (pageText.isNotBlank()) pageText else "${result.title} ${result.snippet}"
            val combined = normalize(evidenceText)
            // La página debe seguir identificando la misma canción y artista; no basta
            // con que una página del artista mencione un género en otra sección.
            if (!combined.contains(normalizedTitle) || !combined.contains(normalizedArtist)) continue
            inspectedEvidence += result to compact(evidenceText)
            val foundTerms = GENRE_TERMS.filter { term -> containsPhrase(combined, normalize(term.phrase)) }
            if (foundTerms.isEmpty()) continue

            // Elimina etiquetas padre cuando la misma evidencia aporta un subgénero explícito.
            val specificTerms = foundTerms.filterNot { candidate ->
                foundTerms.any { other ->
                    other.label != candidate.label && other.specificity > candidate.specificity &&
                        (other.parent == candidate.label || normalize(other.phrase).contains(normalize(candidate.phrase)))
                }
            }.distinctBy { it.label }

            // Si una ficha específica menciona géneros incompatibles, no adivinar cuál aplica.
            if (specificTerms.size != 1) continue
            val term = specificTerms.single()
            votes += Vote(term, result, compact(evidenceText))
        }

        if (votes.isEmpty()) {
            return LookupResult(null, STATUS_INSUFFICIENT, "Baja",
                inspectedEvidence.map { it.first.url }.distinct().take(MAX_SAVED_SOURCES),
                inspectedEvidence.map { it.second }.distinct().take(MAX_SAVED_SNIPPETS),
                successfulRequests, matched.size, engineDiagnostics = engineDiagnostics.toList(), durationMs = elapsedMs)
        }

        // Cada dominio aporta como máximo un voto por etiqueta, aunque aparezca en varias consultas.
        val grouped = votes.groupBy { it.term.label }.mapValues { (_, items) ->
            items.distinctBy { it.result.domain }
        }
        val eligible = grouped.filterValues { it.size >= MIN_INDEPENDENT_DOMAINS }
        if (eligible.isEmpty()) {
            return LookupResult(null, STATUS_INSUFFICIENT, "Baja",
                inspectedEvidence.map { it.first.url }.distinct().take(MAX_SAVED_SOURCES),
                inspectedEvidence.map { it.second }.distinct().take(MAX_SAVED_SNIPPETS),
                successfulRequests, matched.size, engineDiagnostics = engineDiagnostics.toList(), durationMs = elapsedMs)
        }

        val ranked = eligible.entries.sortedWith(
            compareByDescending<Map.Entry<String, List<Vote>>> { it.value.size }
                .thenByDescending { it.value.maxOfOrNull { vote -> vote.term.specificity } ?: 0 }
        )
        val winner = ranked.first()
        val secondCount = ranked.getOrNull(1)?.value?.size ?: 0
        if (winner.value.size == secondCount) {
            return LookupResult(null, STATUS_INSUFFICIENT, "Baja",
                inspectedEvidence.map { it.first.url }.distinct().take(MAX_SAVED_SOURCES),
                inspectedEvidence.map { it.second }.distinct().take(MAX_SAVED_SNIPPETS),
                successfulRequests, matched.size, engineDiagnostics = engineDiagnostics.toList(), durationMs = elapsedMs)
        }

        val winnerVotes = winner.value
        val chosenTerm = winnerVotes.maxByOrNull { it.term.specificity }!!.term
        val confidence = if (winnerVotes.size >= 3 && winnerVotes.size - secondCount >= 2) "Alta" else "Media"
        return LookupResult(
            genre = chosenTerm.label,
            status = STATUS_IDENTIFIED,
            confidence = confidence,
            sources = winnerVotes.map { it.result.url }.distinct().take(MAX_SAVED_SOURCES),
            supportingEvidence = winnerVotes.map { it.supportingText }.distinct().take(MAX_SAVED_SNIPPETS),
            queriesUsed = successfulRequests,
            matchedResults = matched.size,
            engineDiagnostics = engineDiagnostics.toList(),
            durationMs = elapsedMs
        )
    }

    private sealed class SearchResponse {
        data class Success(val results: List<SearchResult>, val diagnostic: String) : SearchResponse()
        data class Unavailable(val diagnostic: String) : SearchResponse()
    }

    private fun buildQueries(title: String, artist: String, album: String): List<String> {
        val t = "\"${title.replace('"', ' ')}\""
        val a = "\"${artist.replace('"', ' ')}\""
        val albumPart = if (album.isNotBlank()) " \"${album.replace('"', ' ')}\"" else ""
        return listOf(
            "$t $a$albumPart género musical canción estilo subgénero",
            "$t $a$albumPart music genre song style subgenre"
        ).distinct()
    }

    private enum class SearchEngine(val baseUrl: String) {
        GOOGLE("https://www.google.com/search?q="),
        BING("https://www.bing.com/search?q="),
        DUCKDUCKGO("https://html.duckduckgo.com/html/?q="),
        YAHOO("https://search.yahoo.com/search?p="),
        BRAVE("https://search.brave.com/search?q=")
    }

    private val SEARCH_ENGINES = listOf(
        SearchEngine.GOOGLE, SearchEngine.BING, SearchEngine.DUCKDUCKGO,
        SearchEngine.YAHOO, SearchEngine.BRAVE
    )

    private fun search(query: String, engine: SearchEngine): SearchResponse {
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000L
        val encoded = URLEncoder.encode(query, "UTF-8")
        var connection: HttpURLConnection? = null
        return try {
            val activeConnection = (URL(engine.baseUrl + encoded).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
                setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en;q=0.7")
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
            }
            connection = activeConnection
            val code = activeConnection.responseCode
            if (code !in 200..299) {
                val label = when (code) {
                    401, 403 -> "bloqueado"
                    429 -> "límite de solicitudes"
                    in 500..599 -> "error del servidor"
                    else -> "error HTTP"
                }
                return SearchResponse.Unavailable("${engine.name}: $label HTTP $code; ${elapsed()} ms")
            }
            val html = activeConnection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val finalHost = try { URL(activeConnection.url.toString()).host.lowercase(Locale.ROOT) } catch (_: Exception) { "desconocido" }
            val pageTitle = Regex("(?is)<title\\b[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)
                ?.let(::stripHtml)?.take(100)?.ifBlank { "sin título" } ?: "sin título"
            val lowerHtml = html.lowercase(Locale.ROOT)
            val blockedMarker = listOf("unusual traffic", "captcha", "anomaly.js", "bots use duckduckgo",
                "verify you are human", "consent.google.com", "before you continue", "automated queries")
                .firstOrNull { lowerHtml.contains(it) }
            if (html.isBlank() || html.length < 300 || blockedMarker != null) {
                val label = when {
                    blockedMarker != null -> "posible bloqueo/consentimiento ($blockedMarker)"
                    else -> "HTML vacío/no interpretable"
                }
                return SearchResponse.Unavailable("${engine.name}: $label; HTTP $code; bytes=${html.length}; host=$finalHost; título=${pageTitle.take(55)}; ${elapsed()} ms")
            }
            val cards = when (engine) {
                SearchEngine.DUCKDUCKGO -> Regex(
                    "(?is)<div[^>]*class=[\\\"'][^\\\"']*\\bresult\\b[^\\\"']*[\\\"'][^>]*>(.*?)(?=<div[^>]*class=[\\\"'][^\\\"']*\\bresult\\b|</body>)"
                ).findAll(html).map { it.groupValues[1] }.toList()
                SearchEngine.BING -> Regex("(?is)<li[^>]*class=[\\\"'][^\\\"']*b_algo[^\\\"']*[\\\"'][^>]*>(.*?)(?=<li[^>]*class=[\\\"'][^\\\"']*b_algo|</ol>)")
                    .findAll(html).map { it.groupValues[1] }.toList()
                SearchEngine.YAHOO -> Regex("(?is)<div[^>]*class=[\\\"'][^\\\"']*algo[^\\\"']*[\\\"'][^>]*>(.*?)(?=<div[^>]*class=[\\\"'][^\\\"']*algo|</ol>)")
                    .findAll(html).map { it.groupValues[1] }.toList()
                SearchEngine.BRAVE -> Regex("(?is)<div[^>]*class=[\\\"'][^\\\"']*snippet[^\\\"']*[\\\"'][^>]*>(.*?)(?=<div[^>]*class=[\\\"'][^\\\"']*snippet|</body>)")
                    .findAll(html).map { it.groupValues[1] }.toList()
                SearchEngine.GOOGLE -> Regex("(?is)<div[^>]*class=[\\\"'][^\\\"']*MjjYud[^\\\"']*[\\\"'][^>]*>(.*?)(?=<div[^>]*class=[\\\"'][^\\\"']*MjjYud|</body>)")
                    .findAll(html).map { it.groupValues[1] }.toList()
            }
            val anchorRegex = Regex("(?is)<a\\b[^>]*href=[\\\"']([^\\\"']+)[\\\"'][^>]*>(.*?)</a>")
            fun resultFrom(anchor: MatchResult, context: String): SearchResult? {
                val hrefRaw = anchor.groupValues[1]
                val anchorHtml = anchor.groupValues[2]
                // Los buscadores no comparten etiqueta: Bing/Yahoo suelen usar h2; otros h3 o clases.
                val hasTitleMarker = Regex("(?is)<h[1-4]\\b").containsMatchIn(anchorHtml) ||
                    Regex("(?is)class=[\\\"'][^\\\"']*(result__a|b_ttl|title|heading)[^\\\"']*[\\\"']").containsMatchIn(anchor.groupValues[0])
                if (!hasTitleMarker) return null
                val titleText = stripHtml(anchorHtml)
                val href = decodeSearchUrl(hrefRaw)
                val host = try { URL(href).host.lowercase(Locale.ROOT).removePrefix("www.") } catch (_: Exception) { "" }
                val domain = registrableDomain(host)
                if (titleText.isBlank() || domain.isBlank() || isSearchEngineDomain(host) ||
                    !(href.startsWith("http://") || href.startsWith("https://"))) return null
                val snippet = stripHtml(context).replace(titleText, " ").take(900)
                return SearchResult(titleText, snippet, href, domain)
            }
            val fromCards: List<SearchResult> = cards.flatMap { card ->
                anchorRegex.findAll(card).mapNotNull { anchor -> resultFrom(anchor, card) }.toList()
            }.distinctBy { it.url }
            // Respaldo cuando el motor cambia las clases del contenedor, pero conserva títulos h1-h4.
            val fallback: List<SearchResult> = if (fromCards.isEmpty()) {
                anchorRegex.findAll(html).mapNotNull { anchor ->
                    val aStart = anchor.range.first
                    val context = html.substring(maxOf(0, aStart - 700), minOf(html.length, anchor.range.last + 1100))
                    resultFrom(anchor, context)
                }.distinctBy { it.url }.take(12).toList()
            } else emptyList<SearchResult>()
            val results = (fromCards + fallback).distinctBy { it.url }
            val linkCount = anchorRegex.findAll(html).count()
            val pageKind = when {
                results.isNotEmpty() -> "resultados extraídos"
                cards.isNotEmpty() -> "contenedores=${cards.size}, enlaces=$linkCount, títulos no reconocidos"
                lowerHtml.contains("no results") || lowerHtml.contains("no results found") -> "página indica sin resultados"
                else -> "estructura HTML no reconocida"
            }
            val label = if (results.isEmpty()) "sin resultados extraíbles" else "éxito"
            SearchResponse.Success(results, "${engine.name}: $label; HTTP $code; bytes=${html.length}; host=$finalHost; título=${pageTitle.take(55)}; bloques=${cards.size}; enlaces=$linkCount; resultados=${results.size}; diagnóstico=$pageKind; ${elapsed()} ms")
        } catch (e: java.net.SocketTimeoutException) {
            SearchResponse.Unavailable("${engine.name}: timeout; ${elapsed()} ms; ${e.message ?: "sin detalle"}")
        } catch (e: java.net.UnknownHostException) {
            SearchResponse.Unavailable("${engine.name}: fallo DNS/conexión; ${elapsed()} ms; ${e.message ?: "sin detalle"}")
        } catch (e: Exception) {
            SearchResponse.Unavailable("${engine.name}: ${e.javaClass.simpleName}; ${elapsed()} ms; ${e.message ?: "sin detalle"}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun isSearchEngineDomain(host: String): Boolean = listOf(
        "google.com", "bing.com", "duckduckgo.com", "yahoo.com", "brave.com"
    ).any { host == it || host.endsWith(".$it") }

    /** Descarga solo HTML público de resultados relevantes, sin cookies ni credenciales. */
    private fun fetchPageText(value: String): String {
        val pageUrl = try { URL(value) } catch (_: Exception) { return "" }
        if (pageUrl.protocol != "http" && pageUrl.protocol != "https") return ""
        val connection = try { pageUrl.openConnection() as? HttpURLConnection ?: return "" } catch (_: Exception) { return "" }
        connection.apply {
            requestMethod = "GET"
            connectTimeout = PAGE_TIMEOUT_MS
            readTimeout = PAGE_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
            setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en;q=0.7")
        }
        return try {
            if (connection.responseCode !in 200..299) return ""
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var total = 0
                while (total < MAX_PAGE_BYTES) {
                    val count = input.read(buffer, 0, minOf(buffer.size, MAX_PAGE_BYTES - total))
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                    total += count
                }
            }
            val html = String(output.toByteArray(), Charsets.UTF_8)
                .replace(Regex("(?is)<!--.*?-->"), " ")
                .replace(Regex("(?is)<(script|style|noscript|svg|iframe)[^>]*>.*?</\\1>"), " ")
                .replace(Regex("(?is)<(nav|footer|header)[^>]*>.*?</\\1>"), " ")
            stripHtml(html).take(MAX_PAGE_TEXT_CHARS)
        } catch (_: Exception) {
            ""
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeSearchUrl(value: String): String {
        val decodedHtml = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
        val raw = if (decodedHtml.startsWith("//")) "https:$decodedHtml" else decodedHtml
        return try {
            val parsed = URL(raw)
            if (parsed.host.contains("duckduckgo.com") && parsed.query != null) {
                val target = parsed.query.split('&').firstOrNull { it.startsWith("uddg=") }?.substringAfter('=')
                if (target != null) java.net.URLDecoder.decode(target, "UTF-8") else raw
            } else raw
        } catch (_: Exception) { raw }
    }

    private fun stripHtml(value: String): String = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
        .replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun containsPhrase(text: String, phrase: String): Boolean =
        phrase.isNotBlank() && Regex("(?<![\\p{L}\\p{N}])${Regex.escape(phrase)}(?![\\p{L}\\p{N}])")
            .containsMatchIn(text)

    private fun compact(value: String): String = value.replace(Regex("\\s+"), " ").trim().take(420)

    /** Agrupa subdominios comunes para que m.sitio.com y sitio.com no cuenten como dos fuentes. */
    private fun registrableDomain(host: String): String {
        if (host.isBlank()) return ""
        val labels = host.removePrefix("www.").split('.').filter { it.isNotBlank() }
        if (labels.size <= 2) return labels.joinToString(".")
        val lastTwo = labels.takeLast(2).joinToString(".")
        val lastThree = labels.takeLast(3).joinToString(".")
        return if (lastTwo in MULTI_LABEL_PUBLIC_SUFFIXES) lastThree else lastTwo
    }

    private val MULTI_LABEL_PUBLIC_SUFFIXES = setOf(
        "co.uk", "org.uk", "ac.uk", "com.au", "net.au", "org.au", "co.nz",
        "com.br", "com.mx", "com.co", "com.ar", "com.pe", "com.ve", "com.ec",
        "co.za", "com.tr", "co.in", "com.sg", "com.my", "com.ph", "com.tw"
    )

    private const val MIN_INDEPENDENT_DOMAINS = 2
    private const val MAX_PAGES_TO_INSPECT = 4
    private const val MAX_PAGE_BYTES = 350_000
    private const val MAX_PAGE_TEXT_CHARS = 100_000
    private const val PAGE_TIMEOUT_MS = 3000
    private const val MAX_SAVED_SOURCES = 5
    private const val MAX_SAVED_SNIPPETS = 5

    private val GENRE_TERMS = listOf(
        // Subgéneros específicos: se prefieren frente a la etiqueta padre cuando hay pruebas.
        GenreTerm("vallenato romantico", "Vallenato romántico", "Vallenato", 3),
        GenreTerm("vallenato tradicional", "Vallenato tradicional", "Vallenato", 3),
        GenreTerm("vallenato nueva ola", "Vallenato nueva ola", "Vallenato", 3),
        GenreTerm("vallenato parrandero", "Vallenato parrandero", "Vallenato", 3),
        GenreTerm("vallenato costumbrista", "Vallenato costumbrista", "Vallenato", 3),
        GenreTerm("salsa colombiana", "Salsa colombiana", "Salsa", 3),
        GenreTerm("salsa brava", "Salsa brava", "Salsa", 3),
        GenreTerm("salsa romantica", "Salsa romántica", "Salsa", 3),
        GenreTerm("salsa dura", "Salsa dura", "Salsa", 3),
        GenreTerm("salsa choke", "Salsa choke", "Salsa", 3),
        GenreTerm("merengue tipico", "Merengue típico", "Merengue", 3),
        GenreTerm("merengue dominicano", "Merengue dominicano", "Merengue", 3),
        GenreTerm("bachata tradicional", "Bachata tradicional", "Bachata", 3),
        GenreTerm("bachata urbana", "Bachata urbana", "Bachata", 3),
        GenreTerm("bachata dominicana", "Bachata dominicana", "Bachata", 3),
        GenreTerm("cumbia peruana", "Cumbia peruana", "Cumbia", 3),
        GenreTerm("cumbia mexicana", "Cumbia mexicana", "Cumbia", 3),
        GenreTerm("cumbia villera", "Cumbia villera", "Cumbia", 3),
        GenreTerm("cumbia sonidera", "Cumbia sonidera", "Cumbia", 3),
        GenreTerm("cumbia colombiana", "Cumbia colombiana", "Cumbia", 3),
        GenreTerm("reggaeton romantico", "Reguetón romántico", "Reguetón", 3),
        GenreTerm("reggaeton old school", "Reguetón old school", "Reguetón", 3),
        GenreTerm("reggaeton clasico", "Reguetón clásico", "Reguetón", 3),
        GenreTerm("trap latino", "Trap latino", "Trap", 3),
        GenreTerm("dembow dominicano", "Dembow dominicano", "Dembow", 3),
        GenreTerm("champeta urbana", "Champeta urbana", "Champeta", 3),
        GenreTerm("latin pop", "Pop latino", "Pop", 3),
        GenreTerm("indie pop", "Indie pop", "Pop", 3),
        GenreTerm("pop rock", "Pop rock", "Rock", 3),
        GenreTerm("indie rock", "Indie rock", "Rock", 3),
        GenreTerm("r and b contemporaneo", "R&B contemporáneo", "R&B", 3),
        GenreTerm("contemporary r&b", "R&B contemporáneo", "R&B", 3),
        GenreTerm("afrobeats", "Afrobeats", null, 2),
        GenreTerm("dancehall", "Dancehall", "Reggae", 3),
        GenreTerm("funk brasileiro", "Funk brasileño", null, 2),
        GenreTerm("bossa nova", "Bossa nova", null, 2),
        GenreTerm("pop latino", "Pop latino", "Pop", 3),
        GenreTerm("rock alternativo", "Rock alternativo", "Rock", 3),
        GenreTerm("rock en espanol", "Rock en español", "Rock", 3),
        GenreTerm("heavy metal", "Heavy metal", "Metal", 3),
        GenreTerm("death metal", "Death metal", "Metal", 3),
        GenreTerm("black metal", "Black metal", "Metal", 3),
        GenreTerm("hip hop latino", "Hip-hop latino", "Hip-hop", 3),
        GenreTerm("rap latino", "Rap latino", "Rap", 3),
        GenreTerm("house progresivo", "House progresivo", "House", 3),
        GenreTerm("deep house", "Deep house", "House", 3),
        GenreTerm("tech house", "Tech house", "House", 3),
        GenreTerm("drum and bass", "Drum and bass", "Electrónica", 3),
        GenreTerm("regional mexicano", "Regional mexicano", null, 2),
        GenreTerm("musica llanera", "Música llanera", null, 2),
        GenreTerm("musica clasica", "Música clásica", null, 2),
        GenreTerm("vallenato", "Vallenato", null, 1),
        GenreTerm("salsa", "Salsa", null, 1),
        GenreTerm("merengue", "Merengue", null, 1),
        GenreTerm("bachata", "Bachata", null, 1),
        GenreTerm("reggaeton", "Reguetón", null, 1),
        GenreTerm("regueton", "Reguetón", null, 1),
        GenreTerm("cumbia", "Cumbia", null, 1),
        GenreTerm("champeta", "Champeta", null, 1),
        GenreTerm("porro", "Porro", null, 1),
        GenreTerm("joropo", "Joropo", null, 1),
        GenreTerm("tango", "Tango", null, 1),
        GenreTerm("flamenco", "Flamenco", null, 1),
        GenreTerm("bolero", "Bolero", null, 1),
        GenreTerm("ranchera", "Ranchera", null, 1),
        GenreTerm("norteno", "Norteño", null, 1),
        GenreTerm("banda", "Banda", null, 1),
        GenreTerm("rock", "Rock", null, 1),
        GenreTerm("pop", "Pop", null, 1),
        GenreTerm("jazz", "Jazz", null, 1),
        GenreTerm("blues", "Blues", null, 1),
        GenreTerm("reggae", "Reggae", null, 1),
        GenreTerm("hip hop", "Hip-hop", null, 1),
        GenreTerm("hip-hop", "Hip-hop", null, 1),
        GenreTerm("rap", "Rap", null, 1),
        GenreTerm("trap", "Trap", null, 1),
        GenreTerm("dembow", "Dembow", null, 1),
        GenreTerm("r&b", "R&B", null, 1),
        GenreTerm("electronica", "Electrónica", null, 1),
        GenreTerm("electronic", "Electrónica", null, 1),
        GenreTerm("house", "House", null, 1),
        GenreTerm("techno", "Techno", null, 1),
        GenreTerm("metal", "Metal", null, 1),
        GenreTerm("punk", "Punk", null, 1),
        GenreTerm("folk", "Folk", null, 1),
        GenreTerm("country", "Country", null, 1),
        GenreTerm("gospel", "Gospel", null, 1),
        GenreTerm("musica clasica", "Música clásica", null, 1)
    )
}
