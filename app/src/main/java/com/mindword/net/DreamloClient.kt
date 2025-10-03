package com.mindword.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class ScoreRow(val name: String, val score: Int, val seconds: Int)

object DreamloClient {
    private const val PRIVATE = "PZaUTyoYn06lWzJ9QAEB1w1PGJkVfomECfnq0drrx30Q"
    private const val PUBLIC  = "68de43378f40bb08d0aa3e4d"

    @Volatile var lastError: String? = null; private set
    private fun err(s: String?) { lastError = s }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun get(url: String): Pair<Int,String> = try {
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { r -> r.code to (r.body?.string().orEmpty()) }
    } catch (e: Exception) { -1 to "" }

    suspend fun addPipe(name: String, score: Int, seconds: Int): Boolean {
        err(null)
        val n = URLEncoder.encode(name, "UTF-8")
        val (code, body) = get("https://www.dreamlo.com/lb/$PRIVATE/add-pipe/$n/$score/$seconds")
        if (code in 200..299 && (body.contains("OK", true) || body.contains("|"))) return true
        err("add failed: HTTP $code")
        return false
    }

    suspend fun topJson(limit: Int = 20): List<ScoreRow> {
        err(null)
        val (code, body) = get("https://www.dreamlo.com/lb/$PUBLIC/json")
        if (code !in 200..299) { err("json failed: HTTP $code"); return emptyList() }
        return try {
            val root = JSONObject(body)
                .getJSONObject("dreamlo")
                .getJSONObject("leaderboard")
            val arr = when {
                root.has("entry") && root.get("entry") is JSONObject -> listOf(root.getJSONObject("entry"))
                root.has("entry") -> root.getJSONArray("entry").let { (0 until it.length()).map { i -> it.getJSONObject(i) } }
                else -> emptyList()
            }
            arr.map {
                ScoreRow(
                    name = it.optString("name"),
                    score = it.optString("score").toIntOrNull() ?: 0,
                    seconds = it.optString("seconds").toIntOrNull() ?: 0
                )
            }.sortedByDescending { it.score }.take(limit)
        } catch (_: Exception) {
            err("bad json")
            emptyList()
        }
    }
}
