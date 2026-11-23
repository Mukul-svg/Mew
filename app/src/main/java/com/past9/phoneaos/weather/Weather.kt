package com.past9.phoneaos.weather

import android.Manifest
import android.content.Context
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.int
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.agent.sharedHttp
import com.past9.phoneaos.web.httpCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** A weather question, parsed without a model: city, day range, and whether it is weather at all. */
object WeatherQuery {
    private val notWeather = Regex("(?i)\\b(weather app|open weather|weather widget|weather site|weather website|open the weather|launch the weather)\\b")
    private val falsePositive = Regex("(?i)\\b(temperature of|wind power|rain check|cloudy memory|sunny personality|my room|my house|inside|indoors|the room|the house)\\b")
    private val weatherNoun = Regex("(?i)\\b(weather|forecast)\\b")
    private val weatherOther = Regex("(?i)\\b(temperature|temp|rain|raining|snow|humidity|wind|windy)\\b")
    private val cue = Regex("(?i)\\b(in|at|for|here|today|tomorrow|tonight|now|this week|outside|it)\\b")
    private val asks = Regex("(?i)^\\s*(what'?s|what is|how|will it|is it|should i)\\b")
    private val tails = Regex("(?i)\\s+(today|tomorrow|tonight|this week|this weekend|right now|now|currently|please)\\s*$")
    private val here = setOf("my area", "here", "near me", "my location", "home", "work", "outside", "the area")

    fun looksLike(text: String): Boolean {
        if (notWeather.containsMatchIn(text) || falsePositive.containsMatchIn(text)) return false
        if (weatherNoun.containsMatchIn(text)) return true
        return weatherOther.containsMatchIn(text) && (cue.containsMatchIn(text) || asks.containsMatchIn(text))
    }

    fun city(text: String): String? {
        val first = Regex("(?i)\\b(?:weather|forecast|temperature|temp|rain|raining|snow|humidity|wind)\\b[^.?!]*?\\b(?:in|at|for)\\s+([^?.,!;]+)")
            .find(text)?.groupValues?.getOrNull(1)
        val second = Regex("(?i)\\b(?:in|at|for)\\s+([^?.,!;]+?)\\s+(?:weather|forecast|today|tomorrow|tonight|this week|now|right now)\\b")
            .find(text)?.groupValues?.getOrNull(1)
        var c = (first ?: second ?: return null).trim()
        while (tails.containsMatchIn(c)) c = tails.replace(c, "").trim()
        c = c.trim().trimEnd(',', '.', '?', '!').removePrefix("the ").trim()
        if (c.length !in 2..80 || c.lowercase() in here) return null
        return c
    }

    fun days(text: String): Int = when {
        Regex("(?i)\\b(this week|7 days|seven days|weekend)\\b").containsMatchIn(text) -> 7
        Regex("(?i)\\b(tomorrow|next few days)\\b").containsMatchIn(text) -> 3
        else -> 3
    }
}

data class WeatherDay(val date: String, val condition: String, val max: Double, val min: Double, val rainChance: Int?)
data class WeatherNow(
    val place: String,
    val country: String,
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
    val temperature: Double,
    val feelsLike: Double,
    val condition: String,
    val humidity: Int?,
    val precipitation: Double?,
    val wind: Double?,
    val isDay: Boolean,
    val daily: List<WeatherDay>,
    val fetchedAt: Long,
    val unit: String,
    val windUnit: String,
)

sealed interface WeatherOutcome {
    data class Ok(val now: WeatherNow) : WeatherOutcome
    data class NeedPlace(val message: String) : WeatherOutcome
    data class Failed(val message: String) : WeatherOutcome
}

/**
 * Open-Meteo: no key, no account, no tracking; one geocode and one forecast call, cached. This is the fast path for
 * "what's the weather" so it never needs a web search or a browser.
 */
class WeatherService(
    private val context: Context? = null,
    private val http: OkHttpClient = sharedHttp,
    private val geocodeBase: String = "https://geocoding-api.open-meteo.com/v1",
    private val forecastBase: String = "https://api.open-meteo.com/v1",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Place(val name: String, val country: String, val latitude: Double, val longitude: Double, val timezone: String)
    private data class PlaceCache(val at: Long, val place: Place?)
    private data class ForecastCache(val at: Long, val now: WeatherNow)

    suspend fun current(city: String?, days: Int = 3): WeatherOutcome {
        val place = try {
            if (!city.isNullOrBlank()) geocode(city) ?: return WeatherOutcome.Failed("I couldn't find $city.")
            else lastKnown() ?: return WeatherOutcome.NeedPlace("Which city should I check?")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return WeatherOutcome.Failed(e.message?.take(160) ?: "The weather service did not answer.")
        }
        val n = days.coerceIn(1, 7)
        val cached = forecasts[place.latitude to place.longitude]?.takeIf { clock() - it.at < 10 * 60_000L && it.now.daily.size >= n }
        if (cached != null) return WeatherOutcome.Ok(cached.now.copy(place = place.name, country = place.country))
        return try {
            val now = forecast(place, n)
            forecasts[place.latitude to place.longitude] = ForecastCache(clock(), now)
            WeatherOutcome.Ok(now)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            WeatherOutcome.Failed(e.message?.take(160) ?: "The weather service did not answer.")
        }
    }

    private suspend fun geocode(city: String): Place? {
        val key = city.lowercase()
        geocodes[key]?.takeIf { clock() - it.at < 24 * 60 * 60_000L }?.let { return it.place }
        val lang = Locale.getDefault().language.ifBlank { "en" }.take(2)
        val url = "$geocodeBase/search?name=${URLEncoder.encode(city, "UTF-8")}&count=5&language=$lang&format=json"
        val (code, text) = httpCall(http, Request.Builder().url(url).header("User-Agent", "Mew-Android/1.0").build(), 2_800L)
        if (code !in 200..299) return null
        val root = JSONObject(text)
        val all = root.optJSONArray("results")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        val country = Locale.getDefault().country
        val r = all.firstOrNull { it.optString("country_code").equals(country, true) } ?: all.firstOrNull()
        if (r == null) { geocodes[key] = PlaceCache(clock(), null); return null }
        val place = Place(
            r.optString("name"),
            r.optString("country"),
            r.optDouble("latitude"),
            r.optDouble("longitude"),
            r.optString("timezone"),
        )
        geocodes[key] = PlaceCache(clock(), place)
        return place
    }

    private suspend fun forecast(place: Place, days: Int): WeatherNow = withContext(Dispatchers.IO) {
        val us = Locale.getDefault().country.equals("US", true)
        val tempUnit = if (us) "fahrenheit" else "celsius"
        val windUnit = if (us) "mph" else "kmh"
        val url = buildString {
            append(forecastBase).append("/forecast?latitude=").append(place.latitude).append("&longitude=").append(place.longitude)
            append("&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m,is_day")
            append("&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
            append("&timezone=auto&forecast_days=").append(days)
            append("&temperature_unit=").append(tempUnit).append("&wind_speed_unit=").append(windUnit)
            append("&precipitation_unit=").append(if (us) "inch" else "mm")
        }
        val (code, text) = httpCall(http, Request.Builder().url(url).header("User-Agent", "Mew-Android/1.0").build(), 3_500L)
        if (code !in 200..299) throw IllegalStateException("Weather service HTTP $code")
        val root = JSONObject(text)
        val cur = root.optJSONObject("current") ?: throw IllegalStateException("Weather service returned no current conditions")
        val daily = root.optJSONObject("daily")
        val out = mutableListOf<WeatherDay>()
        val times = daily?.optJSONArray("time")
        val codes = daily?.optJSONArray("weather_code")
        val maxs = daily?.optJSONArray("temperature_2m_max")
        val mins = daily?.optJSONArray("temperature_2m_min")
        val rains = daily?.optJSONArray("precipitation_probability_max")
        for (i in 0 until (times?.length() ?: 0)) {
            out += WeatherDay(
                date = times?.optString(i).orEmpty(),
                condition = wmo(codes?.optInt(i) ?: -1),
                max = maxs?.optDouble(i) ?: 0.0,
                min = mins?.optDouble(i) ?: 0.0,
                rainChance = rains?.optInt(i)?.takeIf { !rains.isNull(i) },
            )
        }
        WeatherNow(
            place = place.name.ifBlank { "Your location" },
            country = place.country,
            latitude = place.latitude,
            longitude = place.longitude,
            timezone = root.optString("timezone").ifBlank { place.timezone },
            temperature = cur.optDouble("temperature_2m"),
            feelsLike = cur.optDouble("apparent_temperature"),
            condition = wmo(cur.optInt("weather_code", -1)),
            humidity = cur.optInt("relative_humidity_2m").takeIf { cur.has("relative_humidity_2m") },
            precipitation = cur.optDouble("precipitation").takeIf { cur.has("precipitation") },
            wind = cur.optDouble("wind_speed_10m").takeIf { cur.has("wind_speed_10m") },
            isDay = cur.optInt("is_day", 1) == 1,
            daily = out,
            fetchedAt = clock(),
            unit = if (us) "°F" else "°C",
            windUnit = if (us) "mph" else "km/h",
        )
    }

    private fun lastKnown(): Place? {
        val c = context ?: return null
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        val lm = c.getSystemService(LocationManager::class.java) ?: return null
        val loc = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time } ?: return null
        if (System.currentTimeMillis() - loc.time > 2 * 60 * 60_000L) return null
        return Place("Your location", "", loc.latitude, loc.longitude, java.util.TimeZone.getDefault().id)
    }

    private fun wmo(code: Int): String = when (code) {
        0 -> "clear sky"; 1 -> "mainly clear"; 2 -> "partly cloudy"; 3 -> "overcast"
        45, 48 -> "fog"; 51 -> "light drizzle"; 53 -> "moderate drizzle"; 55 -> "dense drizzle"
        56, 57 -> "freezing drizzle"; 61 -> "slight rain"; 63 -> "moderate rain"; 65 -> "heavy rain"
        66, 67 -> "freezing rain"; 71 -> "slight snow"; 73 -> "moderate snow"; 75 -> "heavy snow"
        77 -> "snow grains"; 80 -> "light showers"; 81 -> "showers"; 82 -> "heavy showers"
        85, 86 -> "snow showers"; 95 -> "thunderstorm"; 96, 99 -> "thunderstorm with hail"
        else -> "unknown"
    }

    companion object {
        private val geocodes = ConcurrentHashMap<String, PlaceCache>()
        private val forecasts = ConcurrentHashMap<Pair<Double, Double>, ForecastCache>()
        fun clearCache() { geocodes.clear(); forecasts.clear() }
    }
}

/** "Weather — Durban, South Africa (Open-Meteo, now)" and a few lines, ready for the model or Live to read out. */
fun WeatherNow.format(includeDays: Boolean = true): String {
    val time = java.time.Instant.ofEpochMilli(fetchedAt).atZone(java.time.ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))
    val b = StringBuilder()
    b.append("Weather — ").append(place)
    if (country.isNotBlank()) b.append(", ").append(country)
    b.append(" · Open-Meteo · ").append(time).append('\n')
    b.append("Now: ").append(fmt(temperature)).append(unit).append(", ").append(condition)
    b.append(" · feels ").append(fmt(feelsLike)).append(unit)
    humidity?.let { b.append(" · humidity ").append(it).append('%') }
    wind?.let { b.append(" · wind ").append(fmt(it)).append(' ').append(windUnit) }
    precipitation?.let { b.append(" · rain ").append(fmt(it)).append(if (unit == "°F") " in" else " mm") }
    if (includeDays && daily.isNotEmpty()) {
        daily.take(7).forEachIndexed { i, d ->
            b.append('\n').append(if (i == 0) "Today" else dayLabel(d.date, i))
            b.append(": ").append(fmt(d.max)).append('/').append(fmt(d.min)).append(unit)
            b.append(", ").append(d.condition)
            d.rainChance?.let { b.append(", ").append(it).append("% rain") }
        }
    }
    return b.toString()
}

/** The one-or-two-line version for a direct answer or a spoken reply. */
fun WeatherNow.spoken(): String = buildString {
    append(place)
    if (country.isNotBlank()) append(", ").append(country)
    append(": ").append(fmt(temperature)).append(unit).append(", ").append(condition)
    if (daily.isNotEmpty()) {
        val today = daily.first()
        append(". Today ").append(fmt(today.max)).append('/').append(fmt(today.min)).append(unit)
        today.rainChance?.let { append(", ").append(it).append("% rain") }
    }
    if (daily.size > 1) {
        val t = daily[1]
        append(". Tomorrow ").append(fmt(t.max)).append('/').append(fmt(t.min)).append(unit).append(", ").append(t.condition)
        t.rainChance?.let { append(", ").append(it).append("% rain") }
    }
    append('.')
}

private fun fmt(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)
private fun dayLabel(date: String, index: Int): String = runCatching {
    LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEE d MMM"))
}.getOrElse { if (index == 1) "Tomorrow" else "Day ${index + 1}" }

/** A direct weather tool for the agent, helpers, routines and Live voice. */
class WeatherTool(
    private val context: Context,
    private val service: WeatherService = WeatherService(context),
) : Tool {
    override val spec = ToolSpec("weather",
        "Current weather and a short forecast from Open-Meteo. Use this instead of web_search for weather; it is faster and needs no key. Give the city when the user named one. Without a city it uses a recent location only if location access is already allowed, otherwise it asks which city.",
        schema(emptyList(),
            "city" to str("City or place; leave out to use the user's known location"),
            "days" to int("Forecast days, 1-7 (default 3)")))

    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val city = input.optString("city").trim().take(80).ifBlank { null }
        val days = input.optInt("days", 3).coerceIn(1, 7)
        val id = ctx.activity("Checking the weather${city?.let { " in $it" } ?: ""}", JSONObject().put("tool", "weather"))
        return when (val out = service.current(city, days)) {
            is WeatherOutcome.Ok -> {
                ctx.updateActivity(id, "Weather · ${out.now.place} · ${fmt(out.now.temperature)}${out.now.unit}", JSONObject().put("tool", "weather"))
                out.now.format()
            }
            is WeatherOutcome.NeedPlace -> {
                ctx.updateActivity(id, "Weather · need a city", JSONObject().put("tool", "weather"))
                out.message
            }
            is WeatherOutcome.Failed -> {
                ctx.updateActivity(id, "Weather unavailable", JSONObject().put("tool", "weather"))
                "Weather unavailable: ${out.message}"
            }
        }
    }
}
