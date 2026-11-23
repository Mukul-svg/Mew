package com.past9.phoneaos

import com.past9.phoneaos.web.BingRssClient
import com.past9.phoneaos.web.ExaClient
import com.past9.phoneaos.web.FirecrawlClient
import com.past9.phoneaos.web.ProviderHttpException
import com.past9.phoneaos.web.SearchDepth
import com.past9.phoneaos.web.SearchFreshness
import com.past9.phoneaos.web.SearchTopic
import com.past9.phoneaos.web.TavilyClient
import com.past9.phoneaos.web.TinyFishClient
import com.past9.phoneaos.web.WebSearchRequest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SearchProviderTest {
    private val req = WebSearchRequest(listOf("test query"), SearchDepth.QUICK, 5, SearchFreshness.ANY, SearchTopic.GENERAL, readTop = 0)
    private val http = OkHttpClient()

    private fun MockWebServer.base(path: String = ""): String = url(path).toString().trimEnd('/')

    @Test fun tavilyWireAndParse() = runBlocking {
        val s = MockWebServer()
        s.enqueue(MockResponse().setBody("""{"results":[{"title":"T","url":"https://example.com/a","content":"snippet","score":0.82,"published_date":"2026-11-20T10:00:00Z"}]}"""))
        s.start()
        try {
            val r = TavilyClient(s.base(), http).search("test query", req, "tvly-key")
            assertEquals(1, r.results.size)
            assertEquals("T", r.results[0].title)
            assertEquals(0.82, r.results[0].score, 0.001)
            assertNotNull(r.results[0].publishedAt)
            val sent = s.takeRequest()
            assertEquals("/search", sent.path)
            assertEquals("Bearer tvly-key", sent.getHeader("Authorization"))
            val body = JSONObject(sent.body.readUtf8())
            assertEquals("test query", body.getString("query"))
            assertEquals("fast", body.getString("search_depth"))
        } finally { s.shutdown() }
    }

    @Test fun exaWireAndHighlights() = runBlocking {
        val s = MockWebServer()
        s.enqueue(MockResponse().setBody("""{"results":[{"title":"E","url":"https://example.com/e","highlights":["first","second"],"publishedDate":"2026-11-21T00:00:00Z"}]}"""))
        s.start()
        try {
            val r = ExaClient(s.base(), http).search("test query", req, "exa-key")
            assertEquals(1, r.results.size)
            assertTrue(r.results[0].snippet.contains("first"))
            val sent = s.takeRequest()
            assertEquals("/search", sent.path)
            assertEquals("exa-key", sent.getHeader("x-api-key"))
            assertEquals("lite", JSONObject(sent.body.readUtf8()).getString("type"))
        } finally { s.shutdown() }
    }

    @Test fun firecrawlWireAndDataGroups() = runBlocking {
        val s = MockWebServer()
        s.enqueue(MockResponse().setBody("""{"success":true,"data":{"web":[{"title":"F","url":"https://example.com/f","description":"web snippet"}]}}"""))
        s.start()
        try {
            val r = FirecrawlClient(s.base("/v2"), http).search("test query", req, "fc-key")
            assertEquals(1, r.results.size)
            assertEquals("web snippet", r.results[0].snippet)
            val sent = s.takeRequest()
            assertEquals("/v2/search", sent.path)
            assertEquals("Bearer fc-key", sent.getHeader("Authorization"))
            assertEquals(5, JSONObject(sent.body.readUtf8()).getInt("limit"))
        } finally { s.shutdown() }
    }

    @Test fun tinyfishWireAndHeaders() = runBlocking {
        val s = MockWebServer()
        s.enqueue(MockResponse().setBody("""{"results":[{"title":"T","url":"https://example.com/t","snippet":"tiny snippet","date":"2026-11-20"}]}"""))
        s.start()
        try {
            val r = TinyFishClient(s.base(), http).search("test query", req, "tiny-key")
            assertEquals(1, r.results.size)
            assertEquals("tiny snippet", r.results[0].snippet)
            val sent = s.takeRequest()
            assertEquals("tiny-key", sent.getHeader("X-API-Key"))
            assertEquals("test query", sent.requestUrl?.queryParameter("query"))
        } finally { s.shutdown() }
    }

    @Test fun bingRssParsesItems() = runBlocking {
        val s = MockWebServer()
        s.enqueue(MockResponse().setBody("""<?xml version="1.0"?><rss><channel>
            <item><title>One &amp; Two</title><link>https://example.com/1</link><description>First &lt;b&gt;snippet&lt;/b&gt;</description><pubDate>Fri, 20 Nov 2026 10:00:00 GMT</pubDate></item>
            <item><title>Two</title><link>https://example.com/2</link><description>Second</description></item>
        </channel></rss>"""))
        s.start()
        try {
            val r = BingRssClient(http, s.base()).search("test query", req, null)
            assertEquals(2, r.results.size)
            assertEquals("One & Two", r.results[0].title)
            assertTrue(r.results[0].snippet.contains("First"))
            assertNotNull(r.results[0].publishedAt)
            assertEquals("/search?format=rss&count=5&q=test+query", s.takeRequest().path)
        } finally { s.shutdown() }
    }

    @Test fun rejectedKeyIsMarked() = runBlocking {
        val s = MockWebServer()
        s.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":{"error":"bad key"}}"""))
        s.start()
        try {
            val e = runCatching { TavilyClient(s.base(), http).search("q", req, "bad") }.exceptionOrNull()
            assertTrue(e is ProviderHttpException)
            assertTrue((e as ProviderHttpException).rejected)
        } finally { s.shutdown() }
    }
}
