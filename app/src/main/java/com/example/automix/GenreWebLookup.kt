package com.example.automix

import android.text.Html
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Búsqueda web HTML pública; no usa una API musical ni requiere una clave. */
object GenreWebLookup {
    /** Busca en resultados web públicos (HTML), sin una API musical ni clave de servicio. */
    fun lookup(title: String, artist: String, album: String): String? {
        if (title.isBlank() || artist.isBlank() || title == "Sin título" || artist == "Artista desconocido") return null
        val albumTerm = if (album.isNotBlank()) " \"$album\"" else ""
        val query = URLEncoder.encode("\"$title\" \"$artist\"$albumTerm género musical", "UTF-8")
        val url = "https://html.duckduckgo.com/html/?q=$query"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6500
            readTimeout = 6500
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en;q=0.7")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            // Cada resultado se evalúa por separado para no mezclar la canción de un
            // resultado con un género mencionado en otro resultado no relacionado.
            val cards = Regex("(?is)<div[^>]*class=[\"'][^\"']*\\bresult\\b[^\"']*[\"'][^>]*>(.*?)(?=<div[^>]*class=[\"'][^\"']*\\bresult\\b|</body>)")
                .findAll(html).map { normalize(stripHtml(it.groupValues[1])) }.filter { it.isNotBlank() }.toList()
            if (cards.isEmpty()) return null
            val normalizedTitle = normalize(title)
            val normalizedArtist = normalize(artist)
            val candidates = linkedSetOf<String>()
            for (text in cards) {
                val mentionsTrack = text.contains(normalizedTitle) || text.contains(normalizedTitle.take(12))
                val mentionsArtist = text.contains(normalizedArtist) || text.contains(normalizedArtist.take(12))
                if (!mentionsTrack || !mentionsArtist) continue
                val genreMatches = GENRE_TERMS.filter { term ->
                    Regex("(?<![\\p{L}\\p{N}])${Regex.escape(term.first)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(text)
                }
                candidates.addAll(genreMatches.map { it.second })
            }
            // Solo aceptar si los resultados vinculados a la canción coinciden en un único género.
            if (candidates.size == 1) candidates.first() else null
        } finally {
            connection.disconnect()
        }
    }

    private val GENRE_TERMS = listOf(
        "vallenato" to "Vallenato", "salsa" to "Salsa", "merengue" to "Merengue",
        "bachata" to "Bachata", "reggaeton" to "Reguetón", "reguetón" to "Reguetón",
        "cumbia" to "Cumbia", "champeta" to "Champeta", "porro" to "Porro",
        "rock" to "Rock", "pop" to "Pop", "jazz" to "Jazz", "blues" to "Blues",
        "reggae" to "Reggae", "hip hop" to "Hip-hop", "hip-hop" to "Hip-hop",
        "rap" to "Rap", "electrónica" to "Electrónica", "electronic" to "Electrónica",
        "house" to "House", "techno" to "Techno", "metal" to "Metal", "punk" to "Punk",
        "bolero" to "Bolero", "ranchera" to "Ranchera", "banda" to "Banda",
        "norteño" to "Norteño", "regional mexicano" to "Regional mexicano",
        "música llanera" to "Música llanera", "joropo" to "Joropo", "tango" to "Tango",
        "flamenco" to "Flamenco", "folk" to "Folk", "country" to "Country",
        "gospel" to "Gospel", "clásica" to "Clásica", "música clásica" to "Clásica"
    )
}
