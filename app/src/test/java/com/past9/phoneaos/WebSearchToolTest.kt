package com.past9.phoneaos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.data.SettingsStore
import com.past9.phoneaos.web.ProviderResult
import com.past9.phoneaos.web.SearchClient
import com.past9.phoneaos.web.SearchDepth
import com.past9.phoneaos.web.SearchProvider
import com.past9.phoneaos.web.WebResult
import com.past9.phoneaos.web.WebSearchRequest
import com.past9.phoneaos.web.WebSearchService
import com.past9.phoneaos.web.WebSearchTool
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebSearchToolTest {
    private class Ctx : ToolContext {
        override val agentLabel = "main"
        val said = mutableListOf<String>()
        override suspend fun activity(text: String, meta: JSONObject): Long { said += text; return 1 }
        override suspend fun updateActivity(id: Long, text: String, meta: JSONObject) { said += text }
        override suspend fun ask(question: String, options: List<String>): String? = null
        override suspend fun notify(title: String, body: String) {}
    }

    private class Fake(private val results: List<WebResult>) : SearchClient {
        override val name = "Tavily"
        var last: WebSearchRequest? = null
        override suspend fun search(query: String, request: WebSearchRequest, key: String?): ProviderResult {
            last = request
            return ProviderResult(name, results)
        }
    }

    @Test fun formatsResultsAndLogsOneActivity() = runBlocking {
        val fake = Fake(listOf(WebResult("Cape Town weather", "https://weather.test/cape", "Sunny, 24 C", "Tavily", publishedAt = 1_800_000_000_000L)))
        val service = WebSearchService({ "key" }, mapOf(SearchProvider.TAVILY to fake))
        val tool = WebSearchTool(SettingsStore(ApplicationProvider.getApplicationContext<Context>()), service)
        val ctx = Ctx()
        val out = tool.run(JSONObject().put("query", "Cape Town weather").put("depth", "quick").put("read_top", 0), ctx)
        assertTrue(out, out.contains("Web search"))
        assertTrue(out, out.contains("1. Cape Town weather"))
        assertTrue(out, out.contains("https://weather.test/cape"))
        assertTrue(ctx.said.any { it.contains("Searched the web") })
        assertEquals(SearchDepth.QUICK, fake.last?.depth)
    }

    @Test fun blankQueryIsRefusedBeforeAnyRequest() = runBlocking {
        val fake = Fake(emptyList())
        val service = WebSearchService({ "key" }, mapOf(SearchProvider.TAVILY to fake))
        val tool = WebSearchTool(SettingsStore(ApplicationProvider.getApplicationContext<Context>()), service)
        val out = tool.run(JSONObject(), Ctx())
        assertTrue(out.contains("needs a query"))
        assertNull(fake.last)
    }

    @Test fun emptyResultsSuggestTheBrowser() = runBlocking {
        val service = WebSearchService({ "key" }, mapOf(SearchProvider.TAVILY to Fake(emptyList())), keyless = emptyList())
        val tool = WebSearchTool(SettingsStore(ApplicationProvider.getApplicationContext<Context>()), service)
        val out = tool.run(JSONObject().put("query", "nothing at all"), Ctx())
        assertTrue(out, out.contains("No web results"))
        assertTrue(out, out.contains("browser_open"))
    }
}
