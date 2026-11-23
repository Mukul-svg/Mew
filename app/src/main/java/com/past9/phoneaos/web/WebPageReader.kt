package com.past9.phoneaos.web

import com.past9.phoneaos.agent.sharedHttp
import com.past9.phoneaos.tools.WebFetchTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Clean text for the best few URLs. Use the provider that already has a key first (it can render JavaScript and has
 * seen the page), then fall back to a guarded on-device GET. Never more than [MAX_CHARS] per page.
 */
class WebPageReader(
    private val http: OkHttpClient = UrlGuard.http,
    private val providerHttp: OkHttpClient = sharedHttp,
    private val firecrawlBase: String = "https://api.firecrawl.dev/v2",
    private val tavilyBase: String = "https://api.tavily.com",
    private val exaBase: String = "https://api.exa.ai",
) {
    suspend fun read(url: String, provider: SearchProvider?, key: String?, timeoutMs: Long = 5_000L): String? {
        runCatching { UrlGuard.check(url) }.onFailure { return null }
        return runCatching {
            val fromProvider = when (provider) {
                SearchProvider.FIRECRAWL -> if (key.isNullOrBlank()) null else firecrawl(url, key, timeoutMs)
                SearchProvider.TAVILY -> if (key.isNullOrBlank()) null else tavily(url, key, timeoutMs)
                SearchProvider.EXA -> if (key.isNullOrBlank()) null else exa(url, key, timeoutMs)
                else -> null
            }
            (fromProvider ?: plain(url, timeoutMs))?.let { trim(it) }
        }.getOrNull()
    }

    private suspend fun firecrawl(url: String, key: String, timeoutMs: Long): String? {
        val body = JSONObject().put("url", url).put("formats", org.json.JSONArray(listOf("markdown")))
            .put("onlyMainContent", true).put("maxAge", 0).put("timeout", timeoutMs.toInt())
        val req = Request.Builder().url("$firecrawlBase/scrape").post(body.toString().toRequestBody(JSON)).header("Authorization", "Bearer ${key.trim()}").build()
        val (code, text) = httpCall(providerHttp, req, timeoutMs + 1_000)
        if (code !in 200..299) return null
        return runCatching { JSONObject(text).optJSONObject("data")?.optString("markdown") }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private suspend fun tavily(url: String, key: String, timeoutMs: Long): String? {
        val body = JSONObject().put("urls", org.json.JSONArray(listOf(url))).put("include_images", false)
        val req = Request.Builder().url("$tavilyBase/extract").post(body.toString().toRequestBody(JSON)).header("Authorization", "Bearer ${key.trim()}").build()
        val (code, text) = httpCall(providerHttp, req, timeoutMs + 1_000)
        if (code !in 200..299) return null
        return runCatching { JSONObject(text).optJSONArray("results")?.optJSONObject(0)?.optString("raw_content") }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private suspend fun exa(url: String, key: String, timeoutMs: Long): String? {
        val body = JSONObject().put("urls", org.json.JSONArray(listOf(url))).put("text", JSONObject().put("maxCharacters", MAX_CHARS)).put("maxAgeHours", 0)
        val req = Request.Builder().url("$exaBase/contents").post(body.toString().toRequestBody(JSON)).header("x-api-key", key.trim()).build()
        val (code, text) = httpCall(providerHttp, req, timeoutMs + 1_000)
        if (code !in 200..299) return null
        return runCatching { JSONObject(text).optJSONArray("results")?.optJSONObject(0)?.optString("text") }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private suspend fun plain(url: String, timeoutMs: Long): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36").build()
        val (code, text) = httpCall(http, req, timeoutMs)
        if (code !in 200..299) return@withContext null
        val looksHtml = Regex("(?is)<(!doctype|html|body|div|p|span|a|table|article)\\b").containsMatchIn(text.take(4_000))
        if (looksHtml) WebFetchTool.htmlToText(text) else text
    }

    private fun trim(text: String): String = text.replace(Regex("\\s+"), " ").trim().take(MAX_CHARS)

    companion object {
        const val MAX_CHARS = 4_000
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
