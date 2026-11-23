package com.past9.phoneaos

import com.past9.phoneaos.fast.FastAnswers
import com.past9.phoneaos.fast.FastKind
import com.past9.phoneaos.weather.WeatherService
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FastAnswersTest {
    private lateinit var server: MockWebServer

    private val geocode = """{"results":[{"name":"Durban","country":"South Africa","country_code":"ZA","latitude":-29.85,"longitude":31.02,"timezone":"Africa/Johannesburg"}]}"""
    private val forecast = """{"timezone":"Africa/Johannesburg","current":{"temperature_2m":24.0,"apparent_temperature":26.0,"relative_humidity_2m":70,"precipitation":0,"weather_code":2,"wind_speed_10m":12,"is_day":1},"daily":{"time":["2026-11-23","2026-11-24"],"weather_code":[2,61],"temperature_2m_max":[27,22],"temperature_2m_min":[19,17],"precipitation_probability_max":[20,70]}}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val body = when {
                    path.startsWith("/geo/") -> geocode
                    path.startsWith("/fc/") -> forecast
                    path.startsWith("/rates/") -> """{"result":"success","time_last_update_utc":"Mon, 23 Nov 2026 00:00:01 +0000","rates":{"EUR":0.92,"USD":1.0,"GBP":0.79}}"""
                    path.startsWith("/dict/") -> """[{"word":"serendipity","phonetic":"/ˌsɛrənˈdɪpɪti/","meanings":[{"partOfSpeech":"noun","definitions":[{"definition":"the occurrence of a happy accident","example":"a moment of serendipity"}]}]}]"""
                    path.startsWith("/country/") -> """[{"name":{"common":"Japan"},"capital":["Tokyo"],"population":125000000,"region":"Asia","currencies":{"JPY":{"name":"Japanese yen","symbol":"¥"}},"languages":{"jpn":"Japanese"}}]"""
                    path.startsWith("/wiki") -> """{"pages":[{"title":"Ada Lovelace","description":"English mathematician","excerpt":"Ada Lovelace was an English mathematician chiefly known for her work on Charles Babbage's Analytical Engine."}]}"""
                    path.startsWith("/crypto") -> """{"bitcoin":{"usd":92340.12,"usd_24h_change":-1.24}}"""
                    else -> ""
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
        WeatherService.clearCache()
    }

    private fun answers(): FastAnswers {
        val weather = WeatherService(
            context = null,
            http = OkHttpClient(),
            geocodeBase = server.url("/geo").toString().trimEnd('/'),
            forecastBase = server.url("/fc").toString().trimEnd('/'),
        )
        return FastAnswers(
            context = null,
            weather = weather,
            http = OkHttpClient(),
            currencyBase = server.url("/rates").toString().trimEnd('/'),
            dictionaryBase = server.url("/dict").toString().trimEnd('/'),
            countryBase = server.url("/country").toString().trimEnd('/'),
            wikipediaBase = server.url("/wiki").toString().trimEnd('/'),
            cryptoBase = server.url("/crypto").toString().trimEnd('/'),
        )
    }

    @Test fun mathIsImmediateAndCorrect() = runBlocking {
        val a = answers()
        val percent = a.answer("what is 18% of 240")
        assertEquals(FastKind.MATH, percent?.kind)
        assertTrue(percent!!.answer, percent.answer.contains("43.2"))

        val expression = a.answer("12 * 7 + 5")
        assertEquals(FastKind.MATH, expression?.kind)
        assertTrue(expression!!.answer, expression.answer.contains("89"))

        val root = a.answer("square root of 144")
        assertEquals(FastKind.MATH, root?.kind)
        assertTrue(root!!.answer, root.answer.contains("12"))

        val change = a.answer("increase 200 by 15%")
        assertEquals(FastKind.MATH, change?.kind)
        assertTrue(change!!.answer, change.answer.contains("230"))
    }

    @Test fun unitConversionsAreImmediateAndCorrect() = runBlocking {
        val a = answers()
        val km = a.answer("10 km to miles")
        assertEquals(FastKind.UNITS, km?.kind)
        assertTrue(km!!.answer, km.answer.contains("6.21"))

        val temp = a.answer("72 f to c")
        assertEquals(FastKind.UNITS, temp?.kind)
        assertTrue(temp!!.answer, temp.answer.contains("22.2"))

        val mass = a.answer("how many grams in 2 pounds")
        assertEquals(FastKind.UNITS, mass?.kind)
        assertTrue(mass!!.answer, mass.answer.contains("907.18"))

        val height = a.answer("5 feet 10 inches in cm")
        assertEquals(FastKind.UNITS, height?.kind)
        assertTrue(height!!.answer, height.answer.contains("177.8"))
    }

    @Test fun timeUsesRealZonesAndDates() = runBlocking {
        val a = answers()
        val tokyo = a.answer("what time is it in Tokyo")
        assertEquals(FastKind.TIME, tokyo?.kind)
        assertTrue(tokyo!!.answer, tokyo.answer.contains("Tokyo"))
        assertTrue(tokyo.answer, tokyo.answer.contains("Asia/Tokyo"))

        val today = a.answer("what day is it")
        assertEquals(FastKind.TIME, today?.kind)

        val until = a.answer("days until 25 Dec 2026")
        assertEquals(FastKind.TIME, until?.kind)
        assertTrue(until!!.answer, until.answer.contains("25 December 2026"))
    }

    @Test fun currencyUsesTheKeylessRate() = runBlocking {
        val out = answers().answer("100 usd to eur")
        assertEquals(FastKind.CURRENCY, out?.kind)
        assertTrue(out!!.answer, out.answer.contains("92.00 EUR"))
        assertTrue(out.answer, out.answer.contains("0.92"))

        val named = answers().answer("50 dollars to euros")
        assertEquals(FastKind.CURRENCY, named?.kind)
        assertTrue(named!!.answer, named.answer.contains("46.00 EUR"))
    }

    @Test fun dictionaryReturnsADefinition() = runBlocking {
        val out = answers().answer("define serendipity")
        assertEquals(FastKind.DICTIONARY, out?.kind)
        assertTrue(out!!.answer, out.answer.contains("serendipity"))
        assertTrue(out.answer, out.answer.contains("happy accident"))
    }

    @Test fun countryReturnsTheCapital() = runBlocking {
        val out = answers().answer("capital of Japan")
        assertEquals(FastKind.COUNTRY, out?.kind)
        assertTrue(out!!.answer, out.answer.contains("Tokyo"))
    }

    @Test fun wikipediaReturnsASummaryButIsNotDirect() = runBlocking {
        val out = answers().answer("who is Ada Lovelace")
        assertEquals(FastKind.WIKIPEDIA, out?.kind)
        assertTrue(out!!.answer, out.answer.contains("Ada Lovelace"))
        assertTrue(out.answer, out.answer.contains("mathematician"))
        assertFalse(out.direct)
    }

    @Test fun cryptoReturnsAPrice() = runBlocking {
        val out = answers().answer("bitcoin price")
        assertEquals(FastKind.CRYPTO, out?.kind)
        assertTrue(out!!.answer, out.answer.contains("Bitcoin"))
        assertTrue(out.answer, out.answer.contains("92,340"))
    }

    @Test fun weatherUsesOpenMeteoNotASearch() = runBlocking {
        val out = answers().answer("weather in Durban")
        assertEquals(FastKind.WEATHER, out?.kind)
        assertTrue(out!!.answer, out.answer.contains("Durban"))
        assertTrue(out.answer, out.answer.contains("24"))
        assertTrue(out.answer, out.answer.contains("Today"))
    }

    @Test fun actionsQuestionsAndIdiomsAreNotHijacked() = runBlocking {
        val a = answers()
        listOf(
            "read my email", "call mom", "book a table", "buy a house", "pay my rent",
            "what is the temperature of the sun", "weather app", "what is love",
        ).forEach { assertNull(it, a.answer(it)) }
    }

    @Test fun networkCanBeTurnedOffForLocalAnswersOnly() = runBlocking {
        val a = answers()
        assertNull(a.answer("100 usd to eur", allowNetwork = false))
        val local = a.answer("2 + 2", allowNetwork = false)
        assertEquals(FastKind.MATH, local?.kind)
        assertTrue(local!!.answer, local.answer.contains("4"))
    }

    @Test fun repeatedCurrencyQuestionsReuseTheRate() = runBlocking {
        val a = answers()
        a.answer("100 usd to eur")
        a.answer("100 usd to eur")
        val rateCalls = (0 until server.requestCount).count { i ->
            server.takeRequest().path.orEmpty().startsWith("/rates/")
        }
        assertEquals(1, rateCalls)
    }
}
