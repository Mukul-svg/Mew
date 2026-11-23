package com.past9.phoneaos

import com.past9.phoneaos.weather.WeatherOutcome
import com.past9.phoneaos.weather.WeatherQuery
import com.past9.phoneaos.weather.WeatherService
import com.past9.phoneaos.weather.format
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class WeatherTest {
    private val geocode = """{"results":[{"name":"Durban","country":"South Africa","country_code":"ZA","latitude":-29.85,"longitude":31.02,"timezone":"Africa/Johannesburg"}]}"""
    private val forecast = """{"timezone":"Africa/Johannesburg","current":{"temperature_2m":24.0,"apparent_temperature":26.0,"relative_humidity_2m":70,"precipitation":0,"weather_code":2,"wind_speed_10m":12,"is_day":1},"daily":{"time":["2026-11-23","2026-11-24","2026-11-25"],"weather_code":[2,61,0],"temperature_2m_max":[27,22,29],"temperature_2m_min":[19,17,20],"precipitation_probability_max":[20,70,0]}}"""

    @Test fun recognizesRealWeatherQuestionsAndSkipsFalsePositives() {
        listOf("weather in Durban", "what's the weather like?", "temperature in Paris", "will it rain tomorrow?", "how windy is it?", "forecast for Cape Town").forEach {
            assertTrue(it, WeatherQuery.looksLike(it))
        }
        listOf("open weather app", "weather widget", "what is the temperature of the sun?", "temperature in my room", "how does wind power work?", "rain check", "cloudy memory", "weather site").forEach {
            assertFalse(it, WeatherQuery.looksLike(it))
        }
    }

    @Test fun extractsTheCityAndStripsTimeWords() {
        assertEquals("Durban", WeatherQuery.city("weather in Durban tomorrow"))
        assertEquals("Paris", WeatherQuery.city("what's the temperature in Paris?"))
        assertEquals("Cape Town", WeatherQuery.city("forecast for Cape Town this week"))
        assertNull(WeatherQuery.city("what's the weather like?"))
        assertNull(WeatherQuery.city("weather in my area"))
    }

    @Test fun geocodesForecastsFormatsAndCaches() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(geocode))
        server.enqueue(MockResponse().setBody(forecast))
        server.start()
        try {
            val service = WeatherService(
                context = null,
                http = OkHttpClient(),
                geocodeBase = server.url("/geo").toString().trimEnd('/'),
                forecastBase = server.url("/fc").toString().trimEnd('/'),
            )
            val first = service.current("Durban", 3)
            assertTrue(first is WeatherOutcome.Ok)
            val now = (first as WeatherOutcome.Ok).now
            assertEquals("Durban", now.place)
            assertEquals("South Africa", now.country)
            assertEquals(24.0, now.temperature, 0.01)
            assertEquals("partly cloudy", now.condition)
            assertEquals(3, now.daily.size)
            assertEquals(70, now.daily[1].rainChance)
            val text = now.format()
            assertTrue(text, text.contains("Durban, South Africa"))
            assertTrue(text, text.contains("24"))
            assertTrue(text, text.contains("Open-Meteo"))

            val second = service.current("Durban", 3)
            assertTrue(second is WeatherOutcome.Ok)
            assertEquals("geocode + forecast should be cached", 2, server.requestCount)
            assertEquals("/geo/search", server.takeRequest().path?.substringBefore('?'))
            assertEquals("/fc/forecast", server.takeRequest().path?.substringBefore('?'))
        } finally { server.shutdown() }
    }

    @Test fun noCityAndNoLocationAsksForACityWithoutNetwork() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val service = WeatherService(context = null, http = OkHttpClient(), geocodeBase = server.url("/geo").toString(), forecastBase = server.url("/fc").toString())
            val out = service.current(null, 3)
            assertTrue(out is WeatherOutcome.NeedPlace)
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }
}
