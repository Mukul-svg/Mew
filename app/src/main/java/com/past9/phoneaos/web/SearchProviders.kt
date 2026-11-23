package com.past9.phoneaos.web

import com.past9.phoneaos.agent.sharedHttp
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One provider's wire client. It returns raw results; the service owns merging, fallback and health. */
interface SearchClient {
    val name: String
    suspend fun search(query: String, request: WebSearchRequest, key: String?): ProviderResult
}

class ProviderHttpException(val code: Int, val detail: String, val rejected: Boolean = false) : IOException("HTTP $code: $detail")

private const val UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
private const val API_UA = "Mew-Android/1.0"

internal suspend fun httpCall(http: OkHttpClient, request: Request, timeoutMs: Long): Pair<Int, String> {
    // A local timeout is a normal provider failure, not cancellation of the whole turn: it must fall through to the
    // next provider/keyless index. Parent cancellation still propagates.
    val result = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isCancelled) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isCancelled) { response.close(); return }
                    // Provider JSON and search pages are small; never let a hostile page allocate the whole heap.
                    val text = runCatching { response.peekBody(2_000_000).string() }.getOrDefault("")
                    val code = response.code
                    response.close()
                    cont.resume(code to text)
                }
            })
        }
    }
    return result ?: throw IOException("timeout after ${timeoutMs} ms")
}

private fun bodyJson(json: JSONObject): okhttp3.RequestBody = json.toString().toRequestBody(
    "application/json; charset=utf-8".toMediaType()
)

private fun JSONObject.putStrings(name: String, values: List<String>): JSONObject {
    if (values.isNotEmpty()) put(name, JSONArray(values))
    return this
}

private fun JSONObject.results(name: String = "results"): List<JSONObject> {
    val a = optJSONArray(name) ?: return emptyList()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}

private fun cleanSnippet(raw: String?): String = raw.orEmpty()
    .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
    .replace(Regex("<[^>]+>"), " ")
    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&#39;", "'")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(700)

private fun JSONObject.string(vararg names: String): String {
    for (n in names) {
        val s = optString(n, "")
        if (s.isNotBlank()) return s
    }
    return ""
}

private fun localeLanguage(): String = Locale.getDefault().language.ifBlank { "en" }.take(2)
private fun localeCountry(): String = Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"

private fun freshnessRange(now: Long, freshness: SearchFreshness): String? = when (freshness) {
    SearchFreshness.DAY -> java.time.Instant.ofEpochMilli(now - 86_400_000L).toString()
    SearchFreshness.WEEK -> java.time.Instant.ofEpochMilli(now - 7 * 86_400_000L).toString()
    SearchFreshness.MONTH -> java.time.Instant.ofEpochMilli(now - 30 * 86_400_000L).toString()
    SearchFreshness.YEAR -> java.time.Instant.ofEpochMilli(now - 365 * 86_400_000L).toString()
    SearchFreshness.ANY -> null
}

class TavilyClient(
    private val base: String = "https://api.tavily.com",
    private val http: OkHttpClient = sharedHttp,
) : SearchClient {
    override val name = "Tavily"

    override suspend fun search(query: String, request: WebSearchRequest, key: String?): ProviderResult {
        val started = System.currentTimeMillis()
        if (key.isNullOrBlank()) return ProviderResult(name, ms = 0, error = "no key")
        val body = JSONObject()
            .put("query", query)
            .put("search_depth", when (request.depth) {
                SearchDepth.QUICK -> "fast"
                SearchDepth.STANDARD -> "basic"
                SearchDepth.DEEP -> "advanced"
            })
            .put("max_results", request.maxResults.coerceIn(3, 10))
            .put("include_answer", false)
            .put("include_raw_content", false)
            .put("include_published_date", true)
        when (request.topic) {
            SearchTopic.NEWS -> body.put("topic", "news")
            SearchTopic.FINANCE -> body.put("topic", "finance")
            else -> {}
        }
        if (request.freshness != SearchFreshness.ANY) {
            if (request.topic == SearchTopic.NEWS || request.topic == SearchTopic.RESEARCH) {
                body.put("time_range", when (request.freshness) {
                    SearchFreshness.DAY -> "day"; SearchFreshness.WEEK -> "week"
                    SearchFreshness.MONTH -> "month"; else -> "year"
                })
            } else {
                // Prices, weather and scores keep old publish dates; cache age is the useful signal.
                body.put("max_age_hours", when (request.freshness) {
                    SearchFreshness.DAY -> 24; SearchFreshness.WEEK -> 168
                    SearchFreshness.MONTH -> 720; else -> 720
                })
            }
        }
        body.putStrings("include_domains", request.includeDomains)
        body.putStrings("exclude_domains", request.excludeDomains)
        val req = Request.Builder().url("$base/search").post(bodyJson(body))
            .header("Authorization", "Bearer ${key.trim()}").header("User-Agent", API_UA).build()
        val (code, text) = httpCall(http, req, request.depth.callTimeoutMs)
        if (code !in 200..299) throw ProviderHttpException(code, text.take(300), code == 401 || code == 403)
        val root = runCatching { JSONObject(text) }.getOrElse { throw IOException("bad Tavily response") }
        val out = root.results().mapNotNull { r ->
            val url = r.string("url"); if (url.isBlank()) return@mapNotNull null
            WebResult(
                title = cleanSnippet(r.string("title")).ifBlank { WebUrls.host(url) },
                url = url,
                snippet = cleanSnippet(r.string("content")),
                provider = name,
                publishedAt = WebDates.parse(r.optString("published_date").takeIf { it.isNotBlank() }),
                score = r.optDouble("score", 0.0),
            )
        }
        return ProviderResult(name, out, System.currentTimeMillis() - started)
    }
}

class ExaClient(
    private val base: String = "https://api.exa.ai",
    private val http: OkHttpClient = sharedHttp,
) : SearchClient {
    override val name = "Exa"

    override suspend fun search(query: String, request: WebSearchRequest, key: String?): ProviderResult {
        val started = System.currentTimeMillis()
        if (key.isNullOrBlank()) return ProviderResult(name, ms = 0, error = "no key")
        val contents = JSONObject().put("highlights", JSONObject().put("maxCharacters", 700))
        if (request.topic == SearchTopic.FINANCE) contents.put("maxAgeHours", 6)
        val body = JSONObject()
            .put("query", query)
            .put("type", when (request.depth) {
                SearchDepth.QUICK -> "lite"
                SearchDepth.STANDARD -> "auto"
                SearchDepth.DEEP -> "deep-lite"
            })
            .put("numResults", request.maxResults.coerceIn(3, 10))
            .put("contents", contents)
        when (request.topic) {
            SearchTopic.NEWS -> body.put("category", "news")
            SearchTopic.RESEARCH -> body.put("category", "publication")
            else -> {}
        }
        if (request.topic == SearchTopic.NEWS) {
            val now = System.currentTimeMillis()
            freshnessRange(now, request.freshness)?.let { body.put("startPublishedDate", it) }
        }
        body.putStrings("includeDomains", request.includeDomains)
        body.putStrings("excludeDomains", request.excludeDomains)
        localeCountry().let { body.put("userLocation", it) }
        val req = Request.Builder().url("$base/search").post(bodyJson(body))
            .header("x-api-key", key.trim()).header("User-Agent", API_UA).build()
        val (code, text) = httpCall(http, req, request.depth.callTimeoutMs)
        if (code !in 200..299) throw ProviderHttpException(code, text.take(300), code == 401 || code == 402 || code == 403)
        val root = runCatching { JSONObject(text) }.getOrElse { throw IOException("bad Exa response") }
        val out = root.results().mapNotNull { r ->
            val url = r.string("url"); if (url.isBlank()) return@mapNotNull null
            val highlights = r.optJSONArray("highlights")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } }.orEmpty()
            val snippet = cleanSnippet(highlights.joinToString(" · ").ifBlank { r.string("summary", "text") })
            WebResult(
                title = cleanSnippet(r.string("title")).ifBlank { WebUrls.host(url) },
                url = url,
                snippet = snippet,
                provider = name,
                publishedAt = WebDates.parse(r.optString("publishedDate").takeIf { it.isNotBlank() }),
            )
        }
        return ProviderResult(name, out, System.currentTimeMillis() - started)
    }
}

class FirecrawlClient(
    private val base: String = "https://api.firecrawl.dev/v2",
    private val http: OkHttpClient = sharedHttp,
) : SearchClient {
    override val name = "Firecrawl"

    override suspend fun search(query: String, request: WebSearchRequest, key: String?): ProviderResult {
        val started = System.currentTimeMillis()
        if (key.isNullOrBlank()) return ProviderResult(name, ms = 0, error = "no key")
        val body = JSONObject()
            .put("query", query)
            .put("limit", request.maxResults.coerceIn(3, 10))
            .put("sources", JSONArray(listOf(if (request.topic == SearchTopic.NEWS) "news" else "web")))
            .put("timeout", request.depth.callTimeoutMs.toInt())
            .put("highlights", true)
        if (request.topic == SearchTopic.RESEARCH) body.put("categories", JSONArray(listOf("research")))
        if (request.topic != SearchTopic.FINANCE && request.freshness != SearchFreshness.ANY) {
            body.put("tbs", when (request.freshness) {
                SearchFreshness.DAY -> "qdr:d"; SearchFreshness.WEEK -> "qdr:w"
                SearchFreshness.MONTH -> "qdr:m"; else -> "qdr:y"
            })
        }
        body.putStrings("includeDomains", request.includeDomains)
        body.putStrings("excludeDomains", request.excludeDomains)
        val req = Request.Builder().url("$base/search").post(bodyJson(body))
            .header("Authorization", "Bearer ${key.trim()}").header("User-Agent", API_UA).build()
        val (code, text) = httpCall(http, req, request.depth.callTimeoutMs)
        if (code !in 200..299) throw ProviderHttpException(code, text.take(300), code == 401 || code == 403)
        val root = runCatching { JSONObject(text) }.getOrElse { throw IOException("bad Firecrawl response") }
        val data = root.opt("data")
        val rows = mutableListOf<JSONObject>()
        if (data is JSONObject) {
            val web = data.optJSONArray("web")
            val news = data.optJSONArray("news")
            for (a in listOf(web, news)) if (a != null) for (i in 0 until a.length()) a.optJSONObject(i)?.let { rows += it }
        } else if (data is JSONArray) {
            for (i in 0 until data.length()) data.optJSONObject(i)?.let { rows += it }
        }
        val out = rows.mapNotNull { r ->
            val url = r.string("url").ifBlank { r.optJSONObject("metadata")?.optString("sourceURL").orEmpty() }
            if (url.isBlank()) return@mapNotNull null
            val md = r.optString("markdown")
            val snippet = cleanSnippet(if (md.isNotBlank()) md else r.string("description", "snippet"))
            WebResult(
                title = cleanSnippet(r.string("title")).ifBlank { WebUrls.host(url) },
                url = url,
                snippet = snippet,
                provider = name,
                publishedAt = WebDates.parse(r.optString("date").takeIf { it.isNotBlank() } ?: r.optJSONObject("metadata")?.optString("publishedTime")),
            )
        }.distinctBy { WebUrls.canonical(it.url) }
        return ProviderResult(name, out, System.currentTimeMillis() - started)
    }
}

class TinyFishClient(
    private val base: String = "https://api.search.tinyfish.ai",
    private val http: OkHttpClient = sharedHttp,
) : SearchClient {
    override val name = "TinyFish"

    override suspend fun search(query: String, request: WebSearchRequest, key: String?): ProviderResult {
        val started = System.currentTimeMillis()
        if (key.isNullOrBlank()) return ProviderResult(name, ms = 0, error = "no key")
        val parsed = runCatching { java.net.URI(base.trimEnd('/') + "/") }.getOrNull()
        val b = HttpUrl.Builder()
            .scheme(parsed?.scheme ?: "https")
            .host(parsed?.host ?: "api.search.tinyfish.ai")
        if ((parsed?.port ?: -1) > 0) b.port(parsed!!.port)
        b.addQueryParameter("query", query)
        b.addQueryParameter("purpose", "Answer the user's question accurately with current sources")
        b.addQueryParameter("language", localeLanguage())
        b.addQueryParameter("location", localeCountry())
        b.addQueryParameter("domain_type", when (request.topic) {
            SearchTopic.NEWS -> "news"; SearchTopic.RESEARCH -> "research_paper"; else -> "web"
        })
        if (request.freshness != SearchFreshness.ANY && request.topic != SearchTopic.RESEARCH) {
            val minutes = when (request.freshness) {
                SearchFreshness.DAY -> 24 * 60; SearchFreshness.WEEK -> 7 * 24 * 60
                SearchFreshness.MONTH -> 30 * 24 * 60; else -> 365 * 24 * 60
            }
            b.addQueryParameter("recency_minutes", minutes.toString())
        }
        if (request.includeDomains.isNotEmpty()) b.addQueryParameter("include_domains", request.includeDomains.joinToString(","))
        if (request.excludeDomains.isNotEmpty()) b.addQueryParameter("exclude_domains", request.excludeDomains.joinToString(","))
        val req = Request.Builder().url(b.build()).get()
            .header("X-API-Key", key.trim()).header("User-Agent", API_UA).build()
        val (code, text) = httpCall(http, req, request.depth.callTimeoutMs)
        if (code !in 200..299) throw ProviderHttpException(code, text.take(300), code == 401 || code == 402 || code == 403)
        val root = runCatching { JSONObject(text) }.getOrElse { throw IOException("bad TinyFish response") }
        val out = root.results().mapNotNull { r ->
            val u = r.string("url"); if (u.isBlank()) return@mapNotNull null
            WebResult(
                title = cleanSnippet(r.string("title")).ifBlank { r.string("site_name").ifBlank { WebUrls.host(u) } },
                url = u,
                snippet = cleanSnippet(r.string("snippet")),
                provider = name,
                publishedAt = WebDates.parse(r.optString("date").takeIf { it.isNotBlank() }),
            )
        }
        return ProviderResult(name, out, System.currentTimeMillis() - started)
    }
}

/** Bing's RSS endpoint is the reliable keyless fallback. */
class BingRssClient(
    private val http: OkHttpClient = sharedHttp,
    private val base: String = "https://www.bing.com",
) : SearchClient {
    override val name = "Bing"

    override suspend fun search(query: String, request: WebSearchRequest, key: String?): ProviderResult {
        val started = System.currentTimeMillis()
        val url = "$base/search?format=rss&count=${request.maxResults.coerceIn(3, 10)}&q=${URLEncoder.encode(query, "UTF-8")}"
        val req = Request.Builder().url(url).get().header("User-Agent", UA).build()
        val (code, text) = httpCall(http, req, request.depth.callTimeoutMs)
        if (code !in 200..299) throw ProviderHttpException(code, text.take(200))
        val items = Regex("(?s)<item>(.*?)</item>").findAll(text).map { it.groupValues[1] }.toList()
        val out = items.mapNotNull { item ->
            val link = xmlTag(item, "link"); if (link.isBlank()) return@mapNotNull null
            WebResult(
                title = cleanSnippet(xmlTag(item, "title")).ifBlank { WebUrls.host(link) },
                url = link,
                snippet = cleanSnippet(xmlTag(item, "description")),
                provider = name,
                publishedAt = WebDates.parse(xmlTag(item, "pubDate").takeIf { it.isNotBlank() }),
            )
        }
        return ProviderResult(name, out, System.currentTimeMillis() - started)
    }

    private fun xmlTag(xml: String, name: String): String {
        val m = Regex("(?s)<$name(?:\\s[^>]*)?>(?:<!\\[CDATA\\[(.*?)\\]\\]>|(.*?))</$name>").find(xml) ?: return ""
        return unescapeXml((m.groupValues[1].ifBlank { m.groupValues[2] }).trim())
    }
}

/** Last resort. It is often challenged, so it runs after Bing and returns an explicit error when empty. */
class DuckDuckGoClient(
    private val http: OkHttpClient = sharedHttp,
    private val base: String = "https://html.duckduckgo.com",
) : SearchClient {
    override val name = "DuckDuckGo"

    override suspend fun search(query: String, request: WebSearchRequest, key: String?): ProviderResult {
        val started = System.currentTimeMillis()
        val url = "$base/html/?q=${URLEncoder.encode(query, "UTF-8")}"
        val req = Request.Builder().url(url).get().header("User-Agent", UA).build()
        val (code, text) = httpCall(http, req, request.depth.callTimeoutMs)
        if (code !in 200..299) throw ProviderHttpException(code, text.take(200))
        val links = Regex("(?s)<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>").findAll(text).toList()
        if (links.isEmpty()) return ProviderResult(name, ms = System.currentTimeMillis() - started, error = "no results (challenge)")
        val snippets = Regex("(?s)<a[^>]*class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</a>").findAll(text).map { it.groupValues[1] }.toList()
        val out = links.mapIndexedNotNull { i, m ->
            val href = unescapeXml(m.groupValues[1]).let { if (it.startsWith("//")) "https:$it" else it }
            val decoded = runCatching {
                val u = href.toHttpUrlOrNull() ?: return@runCatching href
                u.queryParameter("uddg")?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: href
            }.getOrDefault(href)
            if (decoded.startsWith("http").not()) return@mapIndexedNotNull null
            WebResult(
                title = cleanSnippet(m.groupValues[2]).ifBlank { WebUrls.host(decoded) },
                url = decoded,
                snippet = cleanSnippet(snippets.getOrNull(i)),
                provider = name,
            )
        }.distinctBy { WebUrls.canonical(it.url) }
        return ProviderResult(name, out, System.currentTimeMillis() - started)
    }
}

private fun unescapeXml(s: String): String = s
    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
    .replace("&#39;", "'")
    .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value }

object SearchProviderFactory {
    fun clients(
        http: OkHttpClient = sharedHttp,
        bases: Map<SearchProvider, String> = emptyMap(),
    ): Map<SearchProvider, SearchClient> = mapOf(
        SearchProvider.TAVILY to TavilyClient(bases[SearchProvider.TAVILY] ?: "https://api.tavily.com", http),
        SearchProvider.EXA to ExaClient(bases[SearchProvider.EXA] ?: "https://api.exa.ai", http),
        SearchProvider.FIRECRAWL to FirecrawlClient(bases[SearchProvider.FIRECRAWL] ?: "https://api.firecrawl.dev/v2", http),
        SearchProvider.TINYFISH to TinyFishClient(bases[SearchProvider.TINYFISH] ?: "https://api.search.tinyfish.ai", http),
    )

    fun keyless(http: OkHttpClient = sharedHttp): List<SearchClient> = listOf(BingRssClient(http), DuckDuckGoClient(http))
}
