package ai.impact.data.skills

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Finds the best-matching English Wikipedia article for a question and returns its introduction. */
object Wikipedia {

    data class Article(val title: String, val extract: String, val url: String)

    class LookupException(message: String) : IOException(message)

    private val questionWords = Regex(
        "^(?:please\\s+)?(?:can you\\s+)?(?:tell me (?:about|who|what)|what (?:is|are|was|were)|who (?:is|are|was|were)|" +
            "where (?:is|are|was)|when (?:is|was|did)|how (?:does|do|did)|why (?:is|are|do|does|did)|explain|define|search(?: for)?|look up)\\s+",
        RegexOption.IGNORE_CASE
    )

    /** "on Wikipedia", "from wiki", "Wikipedia:" — the source, not part of what to search for. */
    private val wikiMention = Regex("\\b(?:(?:on|from|in|using|via)\\s+)?wiki(?:pedia)?\\b:?", RegexOption.IGNORE_CASE)

    /** Turns "who was Ada Lovelace?" into a search Wikipedia handles well ("Ada Lovelace"). */
    fun searchTerms(question: String): String {
        val trimmed = question.trim().trimEnd('?', '.', '!')
            .replace(wikiMention, " ").replace(Regex("\\s+"), " ").trim()
            .replace(Regex("^(?:please\\s+)?(?:look\\s?up|search(?:\\s+(?:for|about))?|find)\\s+", RegexOption.IGNORE_CASE), "")
        val stripped = questionWords.replaceFirst(trimmed, "").removePrefix("the ").removePrefix("a ").trim()
        return stripped.ifBlank { trimmed }
    }

    suspend fun lookup(question: String): Article = withContext(Dispatchers.IO) {
        val query = URLEncoder.encode(searchTerms(question), "UTF-8")
        val url = URL(
            "https://en.wikipedia.org/w/api.php?action=query&format=json&formatversion=2&redirects=1" +
                "&generator=search&gsrlimit=1&gsrsearch=$query&prop=extracts|info&inprop=url" +
                "&exintro=1&explaintext=1&exsentences=8"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            // Wikimedia asks every client to identify itself.
            setRequestProperty("User-Agent", "TinyAI-Android/1.1 (on-device assistant)")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw LookupException("Wikipedia didn't answer (HTTP ${connection.responseCode}). Try again in a moment.")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parse(body) ?: throw LookupException("Wikipedia has no article matching \"${searchTerms(question)}\".")
        } finally {
            connection.disconnect()
        }
    }

    /** Reads the first page out of a `formatversion=2` query response. */
    fun parse(json: String): Article? {
        val pages = JSONObject(json).optJSONObject("query")?.optJSONArray("pages") ?: return null
        val page = pages.optJSONObject(0) ?: return null
        val title = page.optString("title").takeIf { it.isNotBlank() } ?: return null
        val extract = page.optString("extract").trim().takeIf { it.isNotBlank() } ?: return null
        val url = page.optString("fullurl").ifBlank {
            "https://en.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8")
        }
        return Article(title, extract, url)
    }
}
