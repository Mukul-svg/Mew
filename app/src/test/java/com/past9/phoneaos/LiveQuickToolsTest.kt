package com.past9.phoneaos

import com.past9.phoneaos.assist.LiveAssistCore
import com.past9.phoneaos.assist.LiveQuickTools
import com.past9.phoneaos.fast.FastAnswer
import com.past9.phoneaos.fast.FastKind
import com.past9.phoneaos.web.SearchFreshness
import com.past9.phoneaos.web.SearchTopic
import com.past9.phoneaos.web.WebResult
import com.past9.phoneaos.web.WebSearchResponse
import com.past9.phoneaos.weather.WeatherDay
import com.past9.phoneaos.weather.WeatherNow
import com.past9.phoneaos.weather.WeatherOutcome
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LiveQuickToolsTest {
    private fun now() = WeatherNow(
        place = "Durban", country = "South Africa", latitude = -29.85, longitude = 31.02,
        timezone = "Africa/Johannesburg", temperature = 24.0, feelsLike = 26.0, condition = "partly cloudy",
        humidity = 70, precipitation = 0.0, wind = 12.0, isDay = true,
        daily = listOf(WeatherDay("2026-11-23", "partly cloudy", 27.0, 19.0, 20)),
        fetchedAt = 1_800_000_000_000L, unit = "°C", windUnit = "km/h",
    )

    private fun tools(weather: WeatherOutcome = WeatherOutcome.Ok(now()), results: List<WebResult> = emptyList()) =
        LiveQuickTools(
            weatherFor = { _, _ -> weather },
            searchFor = { _, _, _ -> WebSearchResponse("q", results, listOf("Tavily"), 120) },
        )

    @Test fun weatherReturnsSpeakableText() = runBlocking {
        val out = tools().run(LiveAssistCore.WEATHER, JSONObject().put("city", "Durban"))
        val result = out.optString("result")
        assertTrue(result, result.contains("Durban"))
        assertTrue(result, result.contains("24"))
        assertTrue(result, result.contains("Open-Meteo"))
        assertTrue(out.optString("error").isBlank())
    }

    @Test fun weatherWithoutAPlaceAsksForOne() = runBlocking {
        val out = tools(WeatherOutcome.NeedPlace("Which city should I check?")).run(LiveAssistCore.WEATHER, JSONObject())
        assertEquals("Which city should I check?", out.optString("error"))
    }

    @Test fun webSearchReturnsThreeSourcesAndUntrustedRule() = runBlocking {
        val results = listOf(
            WebResult("One", "https://one.test", "first snippet", "Tavily"),
            WebResult("Two", "https://two.test", "second snippet", "Tavily"),
            WebResult("Three", "https://three.test", "third snippet", "Tavily"),
        )
        val out = tools(results = results).run(LiveAssistCore.WEB_SEARCH, JSONObject().put("query", "who won"))
        val result = out.optString("result")
        assertTrue(result, result.contains("1. One"))
        assertTrue(result, result.contains("https://three.test"))
        assertTrue(result, result.contains("untrusted"))
    }

    @Test fun emptySearchTellsLiveToHandOff() = runBlocking {
        val out = tools().run(LiveAssistCore.WEB_SEARCH, JSONObject().put("query", "nothing"))
        assertTrue(out.optString("error").contains("No web results"))
    }

    @Test fun quickAnswerReturnsACheckedResult() = runBlocking {
        val quick = LiveQuickTools(
            weatherFor = { _, _ -> WeatherOutcome.NeedPlace("x") },
            searchFor = { _, _, _ -> WebSearchResponse("q", emptyList(), emptyList(), 0) },
            fastFor = { FastAnswer(FastKind.MATH, "18% of 240 = 43.2", "Calculator") },
        )
        val out = quick.run(LiveAssistCore.QUICK_ANSWER, JSONObject().put("query", "18% of 240"))
        assertTrue(out.optString("result"), out.optString("result").contains("43.2"))
        assertTrue(out.optString("error").isBlank())
    }

    @Test fun anUnknownQuickAnswerTellsLiveToHandOff() = runBlocking {
        val out = tools().run(LiveAssistCore.QUICK_ANSWER, JSONObject().put("query", "something odd"))
        assertTrue(out.optString("error").contains("Hand the question to the agent"))
    }

    @Test fun liveDeclarationsExposeQuickTools() {
        val declarations = LiveAssistCore.tools().getJSONObject(0).getJSONArray("functionDeclarations")
        val names = (0 until declarations.length()).map { declarations.getJSONObject(it).getString("name") }
        assertTrue(names.toString(), names.contains(LiveAssistCore.QUICK_ANSWER))
        assertTrue(names.toString(), names.contains(LiveAssistCore.WEATHER))
        assertTrue(names.toString(), names.contains(LiveAssistCore.WEB_SEARCH))
        val prompt = LiveAssistCore.systemPrompt("Nova", "Sam", false)
        assertTrue(prompt.contains("quick_answer"))
        assertTrue(prompt.contains("web_search"))
        assertTrue(prompt.contains("weather"))
    }
}
