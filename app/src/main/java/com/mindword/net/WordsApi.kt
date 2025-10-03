package com.mindword.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object WordsApi {
    // Optional: put your API Ninjas key here to use their rhyme/thesaurus
    private const val API_NINJAS_KEY: String = "" // e.g., "YOUR_KEY"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun get(url: String, headers: Map<String,String> = emptyMap()): String? = try {
        val b = Request.Builder().url(url)
        headers.forEach { (k,v) -> b.addHeader(k, v) }
        http.newCall(b.get().build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    } catch (_: Exception) { null }

    /**
     * Fetch a single lowercase word. Tries to honor desired length range.
     * If the API returns something else, retries a few times.
     */
    fun fetchOne(minLen: Int = 4, maxLen: Int = 6): String? {
        repeat(6) {
            // random-word-api supports length filtering only for exact length; we roll within range
            val targetLen = Random.nextInt(minLen, maxLen + 1)
            val url = "https://random-word-api.herokuapp.com/word?number=1&length=$targetLen"
            val body = get(url) ?: return@repeat
            val arr = try { JSONArray(body) } catch (_: Exception) { return@repeat }
            val w = arr.optString(0).lowercase().filter { it.isLetter() }
            if (w.length in minLen..maxLen) return w
        }
        return null
    }

    /**
     * One-off tip after 5+ guesses: rhyme first, then similar word fallback.
     * Returns a human-readable line like: "Tip: rhymes with 'time'".
     */
    fun tipFor(word: String): String {
        val w = word.lowercase()
        // Prefer API Ninjas if key is set
        if (API_NINJAS_KEY.isNotBlank()) {
            val rhyme = ninjasRhyme(w)
            if (rhyme != null) return "Tip: rhymes with '${rhyme}'."
            val syn = ninjasThesaurus(w)
            if (syn != null) return "Tip: similar to '${syn}'."
        }
        // Free fallback: Datamuse
        val rhyme = datamuse("rel_rhy", w)
        if (rhyme != null) return "Tip: rhymes with '${rhyme}'."
        val similar = datamuse("ml", w)
        if (similar != null) return "Tip: similar to '${similar}'."
        return "Tip: think of a common ${w.length}-letter noun or verb."
    }

    private fun ninjasRhyme(w: String): String? {
        val url = "https://api.api-ninjas.com/v1/rhyme?word=${URLEncoder.encode(w, "UTF-8")}"
        val body = get(url, mapOf("X-Api-Key" to API_NINJAS_KEY)) ?: return null
        val arr = try { JSONArray(body) } catch (_: Exception) { return null }
        return (0 until arr.length()).asSequence().map { arr.optString(it) }.firstOrNull()
    }

    private fun ninjasThesaurus(w: String): String? {
        val url = "https://api.api-ninjas.com/v1/thesaurus?word=${URLEncoder.encode(w, "UTF-8")}"
        val body = get(url, mapOf("X-Api-Key" to API_NINJAS_KEY)) ?: return null
        val obj = try { JSONObject(body) } catch (_: Exception) { return null }
        val syn = obj.optJSONArray("synonyms") ?: return null
        return (0 until syn.length()).asSequence().map { syn.optString(it) }.firstOrNull()
    }

    private fun datamuse(param: String, w: String): String? {
        val url = "https://api.datamuse.com/words?$param=${URLEncoder.encode(w, "UTF-8")}&max=5"
        val body = get(url) ?: return null
        val arr = try { JSONArray(body) } catch (_: Exception) { return null }
        return (0 until arr.length())
            .map { arr.optJSONObject(it)?.optString("word").orEmpty() }
            .firstOrNull { it.isNotBlank() && it != w }
    }
}
