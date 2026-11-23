package com.past9.phoneaos

import com.past9.phoneaos.web.ProviderResult
import com.past9.phoneaos.web.SearchClient
import com.past9.phoneaos.web.SearchDepth
import com.past9.phoneaos.web.SearchProvider
import com.past9.phoneaos.web.SearchTopic
import com.past9.phoneaos.web.WebResult
import com.past9.phoneaos.web.WebSearchRequest
import com.past9.phoneaos.web.WebSearchService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebSearchServiceTest {
    private class Fake(
        override val name: String,
        private val results: List<WebResult> = emptyList(),
        private val error: String? = null,
        private val delayMs: Long = 0,
    ) : SearchClient {
        var calls = 0
        override suspend fun search(query: String, request: WebSearchRequest, key: String?): ProviderResult {
            calls++
            if (delayMs > 0) delay(delayMs)
            if (error != null) throw IOException(error)
            return ProviderResult(name, results)
        }
    }

    private fun r(title: String, url: String) = WebResult(title, url, "snippet", "Tavily")

    private val request = WebSearchRequest(listOf("query"), SearchDepth.QUICK, 5, readTop = 0)

    @Before fun clear() = WebSearchService.clearCache()

    @Test fun configuredProviderIsUsedAndCached() = runBlocking {
        val fake = Fake("Tavily", listOf(r("One", "https://one.test/a")))
        val service = WebSearchService({ if (it == SearchProvider.TAVILY) "key" else null }, mapOf(SearchProvider.TAVILY to fake))
        val first = service.search(request)
        val second = service.search(request)
        assertEquals(1, first.results.size)
        assertFalse(first.fromCache)
        assertTrue(second.fromCache)
        assertEquals(1, fake.calls)
    }

    @Test fun keylessRunsWhenEveryConfiguredProviderFails() = runBlocking {
        val broken = Fake("Tavily", error = "boom")
        val bing = Fake("Bing", listOf(r("Bing result", "https://bing.test/a")))
        val service = WebSearchService({ "key" }, mapOf(SearchProvider.TAVILY to broken), keyless = listOf(bing))
        val out = service.search(request)
        assertEquals(1, out.results.size)
        assertEquals("Bing result", out.results.first().title)
        assertEquals(listOf("Bing"), out.providers)
        assertEquals(1, broken.calls)
    }

    @Test fun parallelQueriesBothConfiguredProviders() = runBlocking {
        val a = Fake("Tavily", listOf(r("A", "https://a.test/1")))
        val b = Fake("Exa", listOf(r("B", "https://b.test/2")))
        val service = WebSearchService(
            { if (it == SearchProvider.TAVILY || it == SearchProvider.EXA) "key" else null },
            mapOf(SearchProvider.TAVILY to a, SearchProvider.EXA to b),
        )
        val out = service.search(request.copy(depth = SearchDepth.STANDARD, parallel = true))
        assertEquals(2, a.calls + b.calls)
        assertEquals(2, out.results.size)
    }

    @Test fun singleFlightSharesOneRequest() = runBlocking {
        val fake = Fake("Tavily", listOf(r("One", "https://one.test/a")), delayMs = 150)
        val service = WebSearchService({ "key" }, mapOf(SearchProvider.TAVILY to fake))
        val pair = coroutineScope {
            listOf(async { service.search(request) }, async { service.search(request) }).awaitAll()
        }
        assertEquals(1, fake.calls)
        assertEquals(pair[0].results.size, pair[1].results.size)
    }

    @Test fun preferredProviderGoesFirstButBothAreQueried() = runBlocking {
        val a = Fake("Tavily", listOf(r("A", "https://a.test/1")))
        val b = Fake("Exa", listOf(r("B", "https://b.test/2")))
        val service = WebSearchService(
            { "key" },
            mapOf(SearchProvider.TAVILY to a, SearchProvider.EXA to b),
        )
        val out = service.search(request.copy(depth = SearchDepth.STANDARD, preferred = SearchProvider.EXA, parallel = true))
        assertEquals(2, out.results.size)
        assertEquals(1, b.calls)
        assertEquals(1, a.calls)
    }

    @Test fun keylessIsUsedWhenNoKeysAreSaved() = runBlocking {
        val bing = Fake("Bing", listOf(r("Free", "https://free.test/a")))
        val service = WebSearchService({ null }, emptyMap(), keyless = listOf(bing))
        val out = service.search(request)
        assertEquals(1, out.results.size)
        assertEquals(1, bing.calls)
    }

    @Test fun fallsBackToAnotherConfiguredProviderBeforeKeyless() = runBlocking {
        val primary = Fake("Tavily", error = "boom")
        val secondary = Fake("Exa", listOf(r("Exa result", "https://exa.test/a")))
        val bing = Fake("Bing", listOf(r("Bing result", "https://bing.test/b")))
        val service = WebSearchService(
            { if (it == SearchProvider.TAVILY || it == SearchProvider.EXA) "key" else null },
            mapOf(SearchProvider.TAVILY to primary, SearchProvider.EXA to secondary),
            keyless = listOf(bing),
        )
        val out = service.search(request)
        assertEquals("Exa result", out.results.first().title)
        assertEquals(1, primary.calls)
        assertEquals(1, secondary.calls)
        assertEquals(0, bing.calls)
    }

    @Test fun aRejectedProviderDoesNotBlockTheOther() = runBlocking {
        val rejected = Fake("Tavily", error = "401 unauthorized")
        val good = Fake("Exa", listOf(r("Good", "https://good.test/a")))
        val service = WebSearchService(
            { if (it == SearchProvider.TAVILY || it == SearchProvider.EXA) "key" else null },
            mapOf(SearchProvider.TAVILY to rejected, SearchProvider.EXA to good),
        )
        val out = service.search(request.copy(depth = SearchDepth.STANDARD, parallel = true))
        assertEquals(1, out.results.size)
        assertTrue(out.warnings.any { it.contains("Tavily") })
        assertEquals(1, good.calls)
    }
}
