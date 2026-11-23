package com.past9.phoneaos.fast

import android.content.Context
import com.past9.phoneaos.agent.sharedHttp
import com.past9.phoneaos.web.httpCall
import com.past9.phoneaos.weather.WeatherOutcome
import com.past9.phoneaos.weather.WeatherQuery
import com.past9.phoneaos.weather.WeatherService
import com.past9.phoneaos.weather.format
import com.past9.phoneaos.weather.spoken
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A small, deterministic answer for a question that does not need a model: arithmetic, units, the time, weather,
 * currency, a dictionary, a country fact, a Wikipedia summary, or a crypto price. [answer] is what a person reads;
 * [detail] is the fuller context a model gets when the answer is used as research rather than spoken directly.
 *
 * `direct = true` means the answer is complete and safe to show without the model. A false value means it is useful
 * evidence, but the model should phrase or qualify it.
 */
data class FastAnswer(
    val kind: FastKind,
    val answer: String,
    val source: String,
    val detail: String = answer,
    val direct: Boolean = true,
) {
    /** The system-prompt block for the main agent. */
    fun note(): String = buildString {
        appendLine("[FAST ANSWER — ${kind.label} · $source]")
        appendLine(detail.take(1_600))
        appendLine("Use this answer. Do not search the web or recompute the same question. Answer briefly and naturally; keep the source only when the user asks where it came from.")
    }
}

enum class FastKind(val label: String) {
    MATH("math"),
    UNITS("unit conversion"),
    TIME("time and date"),
    WEATHER("weather"),
    CURRENCY("currency"),
    DICTIONARY("dictionary"),
    COUNTRY("country facts"),
    WIKIPEDIA("Wikipedia"),
    CRYPTO("crypto price"),
}

/**
 * One registry for the fast paths. Local answerers (math, units, time) never touch the network; the others use
 * keyless APIs with short timeouts and a small in-memory cache. Every failure returns null so the caller falls back
 * to web research or the model instead of showing a wrong answer.
 */
class FastAnswers(
    private val context: Context?,
    private val weather: WeatherService = WeatherService(context),
    private val http: OkHttpClient = sharedHttp,
    private val clock: () -> Long = System::currentTimeMillis,
    private val currencyBase: String = "https://open.er-api.com/v6/latest",
    private val dictionaryBase: String = "https://api.dictionaryapi.dev/api/v2/entries/en",
    private val countryBase: String = "https://restcountries.com/v3.1/name",
    private val wikipediaBase: String = "https://en.wikipedia.org/w/rest.php/v1/search/page",
    private val cryptoBase: String = "https://api.coingecko.com/api/v3/simple/price",
) {
    private data class Cached(val at: Long, val answer: FastAnswer?)
    private data class Rates(val at: Long, val base: String, val rates: JSONObject, val date: String)
    private data class Coin(val at: Long, val text: String)

    private val cache = ConcurrentHashMap<String, Cached>()
    private val rates = ConcurrentHashMap<String, Rates>()
    private val coins = ConcurrentHashMap<String, Coin>()

    /**
     * @param deadlineMs the total time the user is willing to wait. Local answers return immediately; network
     *   answers are cut off and the caller falls back.
     */
    suspend fun answer(text: String, deadlineMs: Long = 3_000L, allowNetwork: Boolean = true): FastAnswer? {
        val q = text.trim().replace(Regex("\\s+"), " ")
        if (q.length !in 2..500 || q.startsWith("[")) return null
        val key = q.lowercase(Locale.ROOT)
        cache[key]?.let { if (clock() - it.at < ttl(it.answer)) return it.answer }
        val hit = try {
            withTimeoutOrNull(deadlineMs) {
                math(q) ?: units(q) ?: time(q) ?: if (allowNetwork) network(q) else null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (cache.size > 256) cache.clear()
        cache[key] = Cached(clock(), hit)
        return hit
    }

    private suspend fun network(q: String): FastAnswer? =
        weatherAnswer(q) ?: currency(q) ?: dictionary(q) ?: country(q) ?: wikipedia(q) ?: crypto(q)

    private fun ttl(a: FastAnswer?): Long = when (a?.kind) {
        null -> 20_000L
        FastKind.TIME -> 20_000L
        FastKind.CRYPTO -> 60_000L
        FastKind.WEATHER -> 10 * 60_000L
        FastKind.CURRENCY -> 30 * 60_000L
        FastKind.WIKIPEDIA -> 60 * 60_000L
        FastKind.DICTIONARY, FastKind.COUNTRY -> 24 * 60 * 60_000L
        FastKind.MATH, FastKind.UNITS -> 24 * 60 * 60_000L
    }

    // ---- math ---------------------------------------------------------------------------------

    private fun math(text: String): FastAnswer? {
        val t = normalizeMath(text)
        if (t.isBlank()) return null

        percentOf(t)?.let { return it }
        percentChange(t)?.let { return it }
        whatPercent(t)?.let { return it }
        average(t)?.let { return it }
        difference(t)?.let { return it }
        split(t)?.let { return it }

        if (!looksLikeMath(text, t)) return null
        val expr = stripMathLead(t)
        if (expr.length > 120) return null
        if (expr.matches(Regex("-?\\d+(?:\\.\\d+)?"))) return null
        val value = Calculator.eval(expr) ?: return null
        val shown = expr.replace("*", " × ").replace("/", " ÷ ").replace("+", " + ").replace("-", " − ")
            .replace(Regex("\\s+"), " ").trim()
        return FastAnswer(FastKind.MATH, "$shown = ${num(value)}", "Calculator", direct = true)
    }

    private fun normalizeMath(s: String): String = s.lowercase(Locale.ROOT)
        .replace("×", "*").replace("÷", "/").replace("−", "-").replace("–", "-")
        .replace(Regex("(?i)\\bplus\\b"), "+")
        .replace(Regex("(?i)\\bminus\\b"), "-")
        .replace(Regex("(?i)\\btimes\\b"), "*")
        .replace(Regex("(?i)\\bmultiplied by\\b"), "*")
        .replace(Regex("(?i)\\bdivided by\\b"), "/")
        .replace(Regex("(?i)\\bover\\b"), "/")
        .replace(Regex("(?i)\\bto the power of\\b"), "^")
        .replace(Regex("(?i)\\bsquared\\b"), "^2")
        .replace(Regex("(?i)\\bcubed\\b"), "^3")
        .replace(Regex("(?i)\\bsquare root of\\b"), "sqrt")
        .replace(Regex("(?i)\\bsqrt\\s+(\\d+(?:\\.\\d+)?)"), "sqrt($1)")
        .replace(Regex("(?<=\\d)\\s*x\\s*(?=\\d)"), "*")
        .replace(Regex("(?<=\\d),(?=\\d{3}\\b)"), "")
        .trim()

    private fun stripMathLead(s: String): String = s
        .replace(Regex("(?i)^\\s*(what(?:'s| is)|calculate|compute|solve|evaluate|how much is|how many is|answer)\\s+"), "")
        .replace(Regex("(?i)\\s+please\\s*$"), "")
        .replace(Regex("[?=]"), "")
        .trim()

    private fun looksLikeMath(original: String, normalized: String): Boolean {
        if (!original.any { it.isDigit() }) return false
        val cue = Regex("(?i)\\b(what is|what's|calculate|compute|solve|evaluate|how much|how many|plus|minus|times|multiplied|divided|square root|squared|cubed|power|percent|%)\\b")
        if (cue.containsMatchIn(original)) return true
        val expr = stripMathLead(normalized)
        return expr.any { it in "+-*/^%" } && expr.matches(Regex("[0-9+\\-*/^%(). a-z]+"))
    }

    private fun percentOf(t: String): FastAnswer? {
        val m = Regex("(-?\\d+(?:\\.\\d+)?)\\s*(?:%|percent)\\s+of\\s+(-?\\d+(?:\\.\\d+)?)").find(t) ?: return null
        val pct = m.groupValues[1].toDoubleOrNull() ?: return null
        val base = m.groupValues[2].toDoubleOrNull() ?: return null
        val v = pct / 100.0 * base
        return FastAnswer(FastKind.MATH, "${num(pct)}% of ${num(base)} = ${num(v)}", "Calculator")
    }

    private fun percentChange(t: String): FastAnswer? {
        val by = Regex("(?i)(increase|add|decrease|subtract|reduce|discount)\\s+(-?\\d+(?:\\.\\d+)?)\\s+by\\s+(-?\\d+(?:\\.\\d+)?)\\s*(?:%|percent)").find(t)
        if (by != null) {
            val base = by.groupValues[2].toDoubleOrNull() ?: return null
            val pct = by.groupValues[3].toDoubleOrNull() ?: return null
            val up = by.groupValues[1].lowercase(Locale.ROOT) in setOf("increase", "add")
            val v = if (up) base * (1 + pct / 100.0) else base * (1 - pct / 100.0)
            val word = if (up) "increased by" else "decreased by"
            return FastAnswer(FastKind.MATH, "${num(base)} $word ${num(pct)}% = ${num(v)}", "Calculator")
        }
        val m = Regex("(?i)(increase|add|decrease|subtract|reduce|discount)\\s+(-?\\d+(?:\\.\\d+)?)\\s*(?:%|percent)\\s+(?:to|from|on)\\s+(-?\\d+(?:\\.\\d+)?)").find(t) ?: return null
        val pct = m.groupValues[2].toDoubleOrNull() ?: return null
        val base = m.groupValues[3].toDoubleOrNull() ?: return null
        val up = m.groupValues[1].lowercase(Locale.ROOT) in setOf("increase", "add")
        val v = if (up) base * (1 + pct / 100.0) else base * (1 - pct / 100.0)
        val word = if (up) "increased by" else "decreased by"
        return FastAnswer(FastKind.MATH, "${num(base)} $word ${num(pct)}% = ${num(v)}", "Calculator")
    }

    private fun whatPercent(t: String): FastAnswer? {
        val m = Regex("(?i)(-?\\d+(?:\\.\\d+)?)\\s+is\\s+what\\s+(?:%|percent)\\s+of\\s+(-?\\d+(?:\\.\\d+)?)").find(t) ?: return null
        val a = m.groupValues[1].toDoubleOrNull() ?: return null
        val b = m.groupValues[2].toDoubleOrNull() ?: return null
        if (b == 0.0) return null
        return FastAnswer(FastKind.MATH, "${num(a)} is ${num(a / b * 100)}% of ${num(b)}", "Calculator")
    }

    private fun average(t: String): FastAnswer? {
        val m = Regex("(?i)average\\s+of\\s+([-\\d.,\\s]+)").find(t) ?: return null
        val nums = m.groupValues[1].split(Regex("[,\\s]+")).mapNotNull { it.toDoubleOrNull() }
        if (nums.size < 2) return null
        return FastAnswer(FastKind.MATH, "Average of ${nums.joinToString(", ") { num(it) }} = ${num(nums.average())}", "Calculator")
    }

    private fun difference(t: String): FastAnswer? {
        val m = Regex("(?i)difference\\s+between\\s+(-?\\d+(?:\\.\\d+)?)\\s+and\\s+(-?\\d+(?:\\.\\d+)?)").find(t) ?: return null
        val a = m.groupValues[1].toDoubleOrNull() ?: return null
        val b = m.groupValues[2].toDoubleOrNull() ?: return null
        return FastAnswer(FastKind.MATH, "Difference between ${num(a)} and ${num(b)} = ${num(abs(a - b))}", "Calculator")
    }

    private fun split(t: String): FastAnswer? {
        val m = Regex("(?i)(?:split|share)\\s+(-?\\d+(?:\\.\\d+)?)\\s+(?:between|among|by)\\s+(\\d+)").find(t) ?: return null
        val total = m.groupValues[1].toDoubleOrNull() ?: return null
        val n = m.groupValues[2].toIntOrNull() ?: return null
        if (n <= 0) return null
        return FastAnswer(FastKind.MATH, "${num(total)} split $n ways = ${num(total / n)} each", "Calculator")
    }

    // ---- units --------------------------------------------------------------------------------

    private data class Measure(val dim: String, val factor: Double, val name: String, val aliases: List<String>)

    private fun units(text: String): FastAnswer? {
        val t = text.lowercase(Locale.ROOT).replace("°", "").trim()
        val ftIn = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:ft|feet|foot)\\s*(\\d+(?:\\.\\d+)?)\\s*(?:in|inch|inches)\\s*(?:to|in)\\s*([a-z]+)").find(t)
        if (ftIn != null) {
            val feet = ftIn.groupValues[1].toDoubleOrNull() ?: return null
            val inches = ftIn.groupValues[2].toDoubleOrNull() ?: return null
            val to = unitOf(ftIn.groupValues[3]) ?: return null
            if (to.dim != "length") return null
            val meters = feet * 0.3048 + inches * 0.0254
            return FastAnswer(FastKind.UNITS, "${num(feet)} ft ${num(inches)} in = ${num(meters / to.factor)} ${to.name}", "Unit conversion")
        }
        val how = Regex("how many\\s+([a-z]+)\\s+(?:are\\s+)?in\\s+(-?\\d+(?:\\.\\d+)?)\\s*([a-z]+)").find(t)
        if (how != null) {
            val to = unitOf(how.groupValues[1]) ?: return null
            val amount = how.groupValues[2].toDoubleOrNull() ?: return null
            val from = unitOf(how.groupValues[3]) ?: return null
            return convert(amount, from, to)
        }
        val m = Regex("(?:convert|change)?\\s*(-?\\d+(?:\\.\\d+)?)\\s*([a-z]+)\\s*(?:to|into|in|=)\\s*([a-z]+)").find(t) ?: return null
        val amount = m.groupValues[1].toDoubleOrNull() ?: return null
        val from = unitOf(m.groupValues[2]) ?: return null
        val to = unitOf(m.groupValues[3]) ?: return null
        // Currency codes are not units; the currency answerer owns those.
        if (m.groupValues[2].uppercase(Locale.ROOT) in CURRENCY_CODES || m.groupValues[3].uppercase(Locale.ROOT) in CURRENCY_CODES) return null
        return convert(amount, from, to)
    }

    private fun convert(amount: Double, from: Measure, to: Measure): FastAnswer? {
        if (from.dim != to.dim) return null
        val v = when {
            from.dim == "temperature" -> temperature(amount, from.aliases.first(), to.aliases.first()) ?: return null
            else -> amount * from.factor / to.factor
        }
        return FastAnswer(FastKind.UNITS, "${num(amount)} ${from.name} = ${num(v)} ${to.name}", "Unit conversion")
    }

    private fun temperature(v: Double, from: String, to: String): Double? {
        val c = when (from) {
            "c" -> v
            "f" -> (v - 32) * 5 / 9
            "k" -> v - 273.15
            else -> return null
        }
        return when (to) {
            "c" -> c
            "f" -> c * 9 / 5 + 32
            "k" -> c + 273.15
            else -> null
        }
    }

    private fun unitOf(raw: String): Measure? {
        val s = raw.lowercase(Locale.ROOT).trim().removeSuffix(".")
        val singular = when {
            s.endsWith("ies") -> s.dropLast(3) + "y"
            s.endsWith("es") && !s.endsWith("ses") -> s.dropLast(2)
            s.endsWith("s") && !s.endsWith("ss") -> s.dropLast(1)
            else -> s
        }
        return UNITS.firstOrNull { u -> u.aliases.any { it == s || it == singular } }
    }

    // ---- time ---------------------------------------------------------------------------------

    private fun time(text: String): FastAnswer? {
        val t = text.trim()
        val between = Regex("(?i)^how many days (?:are )?between\\s+(.+?)\\s+and\\s+(.+?)\\??$").find(t)
        if (between != null) {
            val a = parseDate(between.groupValues[1]) ?: return null
            val b = parseDate(between.groupValues[2]) ?: return null
            return FastAnswer(FastKind.TIME, "${abs(java.time.temporal.ChronoUnit.DAYS.between(a, b))} days between ${pretty(a)} and ${pretty(b)}.", "Calendar")
        }
        val until = Regex("(?i)^(?:how many days|days)\\s+(?:until|till|to)\\s+(.+?)\\??$").find(t)
        if (until != null) {
            val date = parseDate(until.groupValues[1]) ?: return null
            val days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), date)
            val phrase = if (days == 0L) "today" else if (days > 0) "$days days until ${pretty(date)}." else "${-days} days since ${pretty(date)}."
            return FastAnswer(FastKind.TIME, if (days == 0L) "It is ${pretty(date)} today." else phrase, "Calendar")
        }
        val dateIn = Regex("(?i)^(?:what(?:'s| is) the date|what date is it|what day is it|today'?s date)\\s+(?:in|at)\\s+(.+?)\\??$").find(t)
        if (dateIn != null) {
            val zone = zoneFor(dateIn.groupValues[1]) ?: return null
            val z = ZonedDateTime.now(zone)
            val day = z.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.getDefault()))
            return FastAnswer(FastKind.TIME, "It is $day in ${placeName(dateIn.groupValues[1])} (${zone.id}).", "Clock")
        }
        val timeIn = Regex("(?i)^(?:what(?:'s| is) the time|what time is it|current time|time)\\s+(?:in|at)\\s+(.+?)\\??$").find(t)
        if (timeIn != null) {
            val zone = zoneFor(timeIn.groupValues[1]) ?: return null
            return clockAnswer(zone, placeName(timeIn.groupValues[1]))
        }
        if (Regex("(?i)^(?:what(?:'s| is) the time|what time is it|current time)\\??$").matches(t)) return clockAnswer(ZoneId.systemDefault(), "here")
        if (Regex("(?i)^(?:what(?:'s| is) the date|what date is it|what day is it|today'?s date)\\??$").matches(t)) {
            val z = ZonedDateTime.now()
            return FastAnswer(FastKind.TIME, "It is ${z.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.getDefault()))} (${z.zone.id}).", "Clock")
        }
        return null
    }

    private fun clockAnswer(zone: ZoneId, place: String): FastAnswer {
        val z = ZonedDateTime.now(zone)
        val time = z.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()))
        val day = z.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()))
        return FastAnswer(FastKind.TIME, "It is $time in $place ($day, ${zone.id}).", "Clock")
    }

    private fun zoneFor(raw: String): ZoneId? {
        val place = cleanPlace(raw)
        CITY_ZONES[place]?.let { return runCatching { ZoneId.of(it) }.getOrNull() }
        if (place.contains("/")) runCatching { ZoneId.of(place) }.getOrNull()?.let { return it }
        runCatching { ZoneId.of(raw.trim()) }.getOrNull()?.let { return it }
        return null
    }

    private fun cleanPlace(raw: String): String = raw.lowercase(Locale.ROOT)
        .replace(Regex("(?i)\\s+(right now|now|today|please|currently)\\s*$"), "")
        .replace(Regex("(?i)^the\\s+"), "")
        .trim().trim(',', '.', '?', '!')

    private fun placeName(raw: String): String = cleanPlace(raw).split(" ").joinToString(" ") { w ->
        if (w.length <= 2) w.uppercase(Locale.getDefault()) else w.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    private fun parseDate(raw: String): LocalDate? {
        val s = raw.trim().replace(Regex("^(?i)(on|by|the)\\s+"), "").trim().trim(',', '.', '?', '!')
        val key = s.lowercase(Locale.ROOT)
        val today = LocalDate.now()
        HOLIDAYS[key]?.let { (m, d) ->
            var date = LocalDate.of(today.year, m, d)
            if (date < today) date = date.plusYears(1)
            return date
        }
        val formats = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d/M/yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("M/d/yyyy", Locale.ENGLISH),
        )
        formats.forEach { f -> runCatching { return LocalDate.parse(s, f) } }
        val noYear = listOf(
            DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMMM d", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH),
        )
        noYear.forEach { f ->
            runCatching {
                val md = java.time.MonthDay.parse(s, f)
                var date = md.atYear(today.year)
                if (date < today) date = date.plusYears(1)
                return date
            }
        }
        return null
    }

    private fun pretty(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()))

    // ---- weather -------------------------------------------------------------------------------

    private suspend fun weatherAnswer(text: String): FastAnswer? {
        if (!WeatherQuery.looksLike(text)) return null
        val city = WeatherQuery.city(text)
        // A compound request ("weather and the move") is not a weather-only turn: leave it to the agent.
        if (city == null && Regex("(?i)\\b(and|then|also|plus)\\b").containsMatchIn(text)) return null
        val out = try {
            weather.current(city, WeatherQuery.days(text))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        return when (out) {
            is WeatherOutcome.Ok -> FastAnswer(FastKind.WEATHER, out.now.spoken(), "Open-Meteo", out.now.format(), direct = true)
            // No city and no allowed recent fix: let the agent use its memory of home or ask in its own words.
            is WeatherOutcome.NeedPlace -> FastAnswer(FastKind.WEATHER, out.message, "Weather", out.message, direct = false)
            is WeatherOutcome.Failed -> null
        }
    }

    // ---- currency ------------------------------------------------------------------------------

    private suspend fun currency(text: String): FastAnswer? {
        val t = text.lowercase(Locale.ROOT).replace(",", "")
            .replace(Regex("\\b(?:dollars?|bucks?)\\b"), "usd")
            .replace(Regex("\\b(?:euros?)\\b"), "eur")
            .replace(Regex("\\b(?:pounds?|sterling)\\b"), "gbp")
            .replace(Regex("\\b(?:rupees?)\\b"), "inr")
            .replace(Regex("\\b(?:yens?)\\b"), "jpy")
            .replace(Regex("\\b(?:rands?)\\b"), "zar")
            .replace(Regex("\\b(?:naira)\\b"), "ngn")
            .replace(Regex("\\b(?:shillings?)\\b"), "kes")
            .replace(Regex("\\b(?:reais?)\\b"), "brl")
            .replace(Regex("\\b(?:pesos?)\\b"), "mxn")
            .replace(Regex("\\b(?:dirhams?)\\b"), "aed")
            .replace(Regex("\\b(?:riyals?)\\b"), "sar")
            .replace(Regex("\\b(?:yuan|renminbi)\\b"), "cny")
            .replace(Regex("\\b(?:won)\\b"), "krw")
        val rateOnly = Regex("(?:exchange rate|rate)\\s+(?:for\\s+)?([a-z]{3})\\s+(?:to|in|against)\\s+([a-z]{3})").find(t)
        val howMuch = Regex("how much (?:is|are)\\s+(-?\\d+(?:\\.\\d+)?)\\s*([a-z]{3})\\s+(?:in|to|into)\\s+([a-z]{3})").find(t)
        val convert = Regex("(?:convert|change)?\\s*(-?\\d+(?:\\.\\d+)?)\\s*([a-z]{3})\\s+(?:to|into|in)\\s+([a-z]{3})").find(t)
        val amount: Double
        val from: String
        val to: String
        when {
            howMuch != null -> {
                amount = howMuch.groupValues[1].toDoubleOrNull() ?: return null
                from = howMuch.groupValues[2].uppercase(Locale.ROOT)
                to = howMuch.groupValues[3].uppercase(Locale.ROOT)
            }
            convert != null -> {
                amount = convert.groupValues[1].toDoubleOrNull() ?: return null
                from = convert.groupValues[2].uppercase(Locale.ROOT)
                to = convert.groupValues[3].uppercase(Locale.ROOT)
            }
            rateOnly != null -> {
                amount = 1.0
                from = rateOnly.groupValues[1].uppercase(Locale.ROOT)
                to = rateOnly.groupValues[2].uppercase(Locale.ROOT)
            }
            else -> return null
        }
        if (from !in CURRENCY_CODES || to !in CURRENCY_CODES || from == to) return null
        val r = ratesFor(from) ?: return null
        val rate = r.rates.optDouble(to, Double.NaN)
        if (rate.isNaN() || rate <= 0) return null
        val value = amount * rate
        val line = "${money(amount)} $from = ${money(value)} $to (1 $from = ${num(rate)} $to, ${r.date})"
        return FastAnswer(FastKind.CURRENCY, line, "open.er-api.com", direct = true)
    }

    private suspend fun ratesFor(base: String): Rates? {
        rates[base]?.takeIf { clock() - it.at < 30 * 60_000L }?.let { return it }
        val root = getJson("$currencyBase/$base", 2_200L) ?: return null
        if (!root.optString("result").equals("success", true)) return null
        val table = root.optJSONObject("rates") ?: return null
        val date = root.optString("time_last_update_utc").take(16).ifBlank { LocalDate.now().toString() }
        val r = Rates(clock(), base, table, date)
        rates[base] = r
        return r
    }

    // ---- dictionary ----------------------------------------------------------------------------

    private suspend fun dictionary(text: String): FastAnswer? {
        val t = text.trim().trimEnd('?', '.', '!')
        val patterns = listOf(
            Regex("(?i)^(?:define|definition of|meaning of)\\s+(?:the word\\s+)?([a-z][a-z' -]{0,40})$"),
            Regex("(?i)^what does\\s+([a-z][a-z' -]{0,40}?)\\s+mean$"),
            Regex("(?i)^what is the meaning of\\s+(?:the word\\s+)?([a-z][a-z' -]{0,40})$"),
            Regex("(?i)^what(?:'s| is)\\s+([a-z][a-z' -]{0,40}?)\\s+mean(?:ing)?$"),
        )
        val word = patterns.firstNotNullOfOrNull { it.find(t)?.groupValues?.get(1)?.trim() } ?: return null
        if (word.isBlank() || word.split(" ").size > 3) return null
        val url = "$dictionaryBase/" + URLEncoder.encode(word, "UTF-8")
        val arr = getJsonArray(url, 2_200L) ?: return null
        val entry = arr.optJSONObject(0) ?: return null
        val meaning = entry.optJSONArray("meanings")?.optJSONObject(0) ?: return null
        val pos = meaning.optString("partOfSpeech")
        val defs = meaning.optJSONArray("definitions") ?: return null
        val first = defs.optJSONObject(0) ?: return null
        val definition = first.optString("definition").trim()
        if (definition.isBlank()) return null
        val example = first.optString("example").trim()
        val phonetic = entry.optString("phonetic").trim().ifBlank { entry.optJSONArray("phonetics")?.optJSONObject(0)?.optString("text").orEmpty() }
        val text = buildString {
            append(word)
            if (phonetic.isNotBlank()) append(" ").append(phonetic)
            if (pos.isNotBlank()) append(" (").append(pos).append(')')
            append(": ").append(definition)
            if (example.isNotBlank()) append(" Example: \"").append(example.take(160)).append('"')
        }
        return FastAnswer(FastKind.DICTIONARY, text, "dictionaryapi.dev", direct = true)
    }

    // ---- country -------------------------------------------------------------------------------

    private data class Country(val name: String, val capital: String, val population: Long, val region: String, val currencies: String, val languages: String)

    private suspend fun country(text: String): FastAnswer? {
        val t = text.trim().trimEnd('?', '.', '!')
        val capital = Regex("(?i)^(?:what(?:'s| is) )?(?:the )?capital of\\s+(.+)$").find(t)
        val population = Regex("(?i)^(?:what(?:'s| is) )?(?:the )?population of\\s+(.+)$").find(t)
        val money = Regex("(?i)^(?:what )?(?:currency|money) (?:does|do) (.+?) use$").find(t)
        val language = Regex("(?i)^(?:what )?(?:language|languages) (?:do they|does .+?) speak in\\s+(.+)$").find(t)
        val where = Regex("(?i)^where is\\s+(.+)$").find(t)
        val name = listOf(capital, population, money, language, where).firstNotNullOfOrNull { it?.groupValues?.get(1)?.trim() } ?: return null
        val c = countryInfo(name) ?: return null
        val answer = when {
            capital != null -> "The capital of ${c.name} is ${c.capital}."
            population != null -> "${c.name} has a population of about ${group(c.population)}."
            money != null -> "${c.name} uses ${c.currencies.ifBlank { "its own currency" }}."
            language != null -> "The main language${if (c.languages.contains(",")) "s" else ""} in ${c.name}: ${c.languages}."
            else -> "${c.name} is in ${c.region}. Its capital is ${c.capital}."
        }
        return FastAnswer(FastKind.COUNTRY, answer, "restcountries.com", direct = true)
    }

    private suspend fun countryInfo(name: String): Country? {
        val url = "$countryBase/" + URLEncoder.encode(name, "UTF-8") +
            "?fields=name,capital,population,region,currencies,languages"
        val arr = getJsonArray(url, 2_200L) ?: return null
        val o = arr.optJSONObject(0) ?: return null
        val common = o.optJSONObject("name")?.optString("common").orEmpty().ifBlank { name }
        val caps = o.optJSONArray("capital")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } }.orEmpty()
        val currencies = o.optJSONObject("currencies")?.let { c ->
            c.keys().asSequence().mapNotNull { code ->
                val d = c.optJSONObject(code) ?: return@mapNotNull null
                val n = d.optString("name")
                val s = d.optString("symbol")
                listOfNotNull(code, n.takeIf { it.isNotBlank() }?.let { "($it)" }, s.takeIf { it.isNotBlank() }).joinToString(" ")
            }.joinToString(", ")
        }.orEmpty()
        val languages = o.optJSONObject("languages")?.let { l -> l.keys().asSequence().mapNotNull { l.optString(it).takeIf(String::isNotBlank) }.joinToString(", ") }.orEmpty()
        return Country(common, caps.joinToString(", "), o.optLong("population"), o.optString("region"), currencies, languages)
    }

    // ---- Wikipedia -----------------------------------------------------------------------------

    private suspend fun wikipedia(text: String): FastAnswer? {
        val t = text.trim().trimEnd('?', '.', '!')
        val patterns = listOf(
            Regex("(?i)^who (?:is|was|are|were)\\s+(.+)$"),
            Regex("(?i)^tell me about\\s+(.+)$"),
            Regex("(?i)^(?:wikipedia|wiki)\\s+(.+)$"),
            Regex("(?i)^when was\\s+(.+?)\\s+born$"),
            Regex("(?i)^how old is\\s+(.+)$"),
            Regex("(?i)^where is\\s+(.+)$"),
            Regex("(?i)^what (?:is|are|was|were)\\s+(.+)$"),
        )
        val subject = patterns.firstNotNullOfOrNull { it.find(t)?.groupValues?.get(1)?.trim() } ?: return null
        if (subject.length !in 2..80) return null
        // A single lowercase common word ("what is love", "who is sam") is a conversation, not an encyclopaedia lookup.
        if (subject.split(" ").size == 1 && subject.firstOrNull()?.isUpperCase() != true) return null
        if (Regex("(?i)\\b(your|my|our|you|me|i|this|that|it|these|those|the best|a good|the weather|the time|the date|the latest|the news|the price|the score|the temperature|the forecast|going on|wrong|up|love|life|happiness|the meaning of life|the point)\\b").containsMatchIn(subject)) return null
        val q = URLEncoder.encode(subject, "UTF-8")
        val root = getJson("$wikipediaBase?q=$q&limit=1", 2_200L) ?: return null
        val page = root.optJSONArray("pages")?.optJSONObject(0) ?: return null
        val title = page.optString("title").ifBlank { subject }
        val description = stripHtml(page.optString("description")).trim()
        val excerpt = stripHtml(page.optString("excerpt")).trim()
        if (excerpt.length < 20) return null
        val url = "https://en.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8")
        val body = buildString {
            append(title)
            if (description.isNotBlank()) append(" — ").append(description)
            append(": ").append(excerpt.take(700))
            append("\nSource: ").append(url)
        }
        return FastAnswer(FastKind.WIKIPEDIA, body, "Wikipedia", detail = body, direct = false)
    }

    // ---- crypto --------------------------------------------------------------------------------

    private suspend fun crypto(text: String): FastAnswer? {
        val t = text.lowercase(Locale.ROOT)
        val coin = COINS.keys.firstOrNull { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(t) } ?: return null
        val cue = Regex("(?i)\\b(price|worth|value|cost|rate|how much is|how much are|trade|trading)\\b").containsMatchIn(t) || t.trim() == coin
        if (!cue) return null
        val vs = CURRENCY_CODES.firstOrNull { c -> Regex("(?i)\\b(in|to)\\s+" + c + "\\b").containsMatchIn(t) } ?: "USD"
        val key = "$coin|$vs"
        coins[key]?.takeIf { clock() - it.at < 60_000L }?.let { return FastAnswer(FastKind.CRYPTO, it.text, "CoinGecko", direct = true) }
        val id = COINS[coin] ?: return null
        val url = "$cryptoBase?ids=$id&vs_currencies=${vs.lowercase(Locale.ROOT)}&include_24hr_change=true"
        val root = getJson(url, 2_200L) ?: return null
        val o = root.optJSONObject(id) ?: return null
        val price = o.optDouble(vs.lowercase(Locale.ROOT), Double.NaN)
        if (price.isNaN()) return null
        val change = o.optDouble("${vs.lowercase(Locale.ROOT)}_24h_change", Double.NaN)
        val line = buildString {
            append(coin.replaceFirstChar { it.titlecase(Locale.getDefault()) }).append(": ")
            append(money(price)).append(' ').append(vs)
            if (!change.isNaN()) append(" (24h ").append(if (change >= 0) "+" else "").append("%.2f".format(Locale.US, change)).append("%)")
        }
        coins[key] = Coin(clock(), line)
        return FastAnswer(FastKind.CRYPTO, line, "CoinGecko", direct = true)
    }

    // ---- HTTP and formatting -------------------------------------------------------------------

    private suspend fun getJson(url: String, timeoutMs: Long): JSONObject? {
        val (code, text) = httpCall(http, Request.Builder().url(url).header("User-Agent", "Mew-Android/1.0").build(), timeoutMs)
        if (code !in 200..299) return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private suspend fun getJsonArray(url: String, timeoutMs: Long): org.json.JSONArray? {
        val (code, text) = httpCall(http, Request.Builder().url(url).header("User-Agent", "Mew-Android/1.0").build(), timeoutMs)
        if (code !in 200..299) return null
        return runCatching { org.json.JSONArray(text) }.getOrNull()
    }

    companion object {
        fun clearCache() {
            // The instance caches are per runtime; the weather and rate caches are the durable ones.
            WeatherService.clearCache()
        }

        private fun num(v: Double): String {
            if (v.isNaN() || v.isInfinite()) return "—"
            if (abs(v) >= 1e15 || (abs(v) < 1e-6 && v != 0.0)) return "%.6e".format(Locale.US, v)
            val b = BigDecimal.valueOf(v).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros()
            return b.toPlainString()
        }

        private fun money(v: Double): String = "%,.2f".format(Locale.US, v)

        private fun group(v: Long): String = "%,d".format(Locale.US, v)

        private fun stripHtml(s: String): String = s
            .replace(Regex("<[^>]+>"), "")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&").replace("&nbsp;", " ")

        private val HOLIDAYS = mapOf(
            "christmas" to (12 to 25), "christmas day" to (12 to 25),
            "new year" to (1 to 1), "new year's day" to (1 to 1), "new years day" to (1 to 1),
            "halloween" to (10 to 31), "valentine's day" to (2 to 14), "valentines day" to (2 to 14),
            "independence day" to (7 to 4),
        )

        private val CURRENCY_CODES = setOf(
            "USD", "EUR", "GBP", "JPY", "INR", "ZAR", "AUD", "CAD", "CHF", "CNY", "NGN", "KES", "BRL", "MXN",
            "SGD", "HKD", "AED", "SAR", "TRY", "RUB", "SEK", "NOK", "DKK", "PLN", "CZK", "THB", "IDR", "MYR",
            "PHP", "KRW", "NZD", "EGP", "GHS", "UGX", "TZS", "RWF", "ETB", "MAD", "DZD", "TND", "QAR", "KWD",
            "BHD", "OMR", "JOD", "ILS", "PKR", "BDT", "LKR", "NPR", "VND", "TWD", "ARS", "CLP", "COP", "PEN",
        )

        private val COINS = mapOf(
            "bitcoin" to "bitcoin", "btc" to "bitcoin",
            "ethereum" to "ethereum", "eth" to "ethereum",
            "solana" to "solana", "sol" to "solana",
            "dogecoin" to "dogecoin", "doge" to "dogecoin",
            "ripple" to "ripple", "xrp" to "ripple",
            "cardano" to "cardano", "ada" to "cardano",
            "bnb" to "binancecoin", "binance coin" to "binancecoin",
            "litecoin" to "litecoin", "ltc" to "litecoin",
            "tron" to "tron", "trx" to "tron",
            "polkadot" to "polkadot", "dot" to "polkadot",
            "chainlink" to "chainlink", "link" to "chainlink",
            "avalanche" to "avalanche-2", "avax" to "avalanche-2",
            "shiba inu" to "shiba-inu", "shib" to "shiba-inu",
            "tether" to "tether", "usdt" to "tether",
            "usdc" to "usd-coin",
            "toncoin" to "the-open-network", "ton" to "the-open-network",
        )

        private val CITY_ZONES = mapOf(
            "london" to "Europe/London", "paris" to "Europe/Paris", "berlin" to "Europe/Berlin",
            "madrid" to "Europe/Madrid", "rome" to "Europe/Rome", "amsterdam" to "Europe/Amsterdam",
            "brussels" to "Europe/Brussels", "vienna" to "Europe/Vienna", "zurich" to "Europe/Zurich",
            "stockholm" to "Europe/Stockholm", "oslo" to "Europe/Oslo", "copenhagen" to "Europe/Copenhagen",
            "helsinki" to "Europe/Helsinki", "dublin" to "Europe/Dublin", "lisbon" to "Europe/Lisbon",
            "warsaw" to "Europe/Warsaw", "prague" to "Europe/Prague", "budapest" to "Europe/Budapest",
            "athens" to "Europe/Athens", "istanbul" to "Europe/Istanbul", "moscow" to "Europe/Moscow",
            "kyiv" to "Europe/Kyiv", "kiev" to "Europe/Kyiv", "bucharest" to "Europe/Bucharest",
            "sofia" to "Europe/Sofia", "belgrade" to "Europe/Belgrade", "zagreb" to "Europe/Zagreb",
            "new york" to "America/New_York", "nyc" to "America/New_York", "washington" to "America/New_York",
            "washington dc" to "America/New_York", "boston" to "America/New_York", "miami" to "America/New_York",
            "atlanta" to "America/New_York", "toronto" to "America/Toronto", "montreal" to "America/Toronto",
            "chicago" to "America/Chicago", "houston" to "America/Chicago", "dallas" to "America/Chicago",
            "austin" to "America/Chicago", "denver" to "America/Denver", "phoenix" to "America/Phoenix",
            "los angeles" to "America/Los_Angeles", "la" to "America/Los_Angeles", "san francisco" to "America/Los_Angeles",
            "seattle" to "America/Los_Angeles", "vancouver" to "America/Vancouver", "las vegas" to "America/Los_Angeles",
            "mexico city" to "America/Mexico_City", "bogota" to "America/Bogota", "lima" to "America/Lima",
            "santiago" to "America/Santiago", "buenos aires" to "America/Argentina/Buenos_Aires",
            "sao paulo" to "America/Sao_Paulo", "rio de janeiro" to "America/Sao_Paulo",
            "johannesburg" to "Africa/Johannesburg", "cape town" to "Africa/Johannesburg", "durban" to "Africa/Johannesburg",
            "pretoria" to "Africa/Johannesburg", "nairobi" to "Africa/Nairobi", "lagos" to "Africa/Lagos",
            "abuja" to "Africa/Lagos", "accra" to "Africa/Accra", "cairo" to "Africa/Cairo",
            "casablanca" to "Africa/Casablanca", "addis ababa" to "Africa/Addis_Ababa", "kampala" to "Africa/Kampala",
            "dar es salaam" to "Africa/Dar_es_Salaam", "dubai" to "Asia/Dubai", "abu dhabi" to "Asia/Dubai",
            "riyadh" to "Asia/Riyadh", "doha" to "Asia/Qatar", "kuwait city" to "Asia/Kuwait",
            "tel aviv" to "Asia/Jerusalem", "jerusalem" to "Asia/Jerusalem", "mumbai" to "Asia/Kolkata",
            "delhi" to "Asia/Kolkata", "new delhi" to "Asia/Kolkata", "bangalore" to "Asia/Kolkata",
            "bengaluru" to "Asia/Kolkata", "hyderabad" to "Asia/Kolkata", "chennai" to "Asia/Kolkata",
            "kolkata" to "Asia/Kolkata", "karachi" to "Asia/Karachi", "lahore" to "Asia/Karachi",
            "islamabad" to "Asia/Karachi", "dhaka" to "Asia/Dhaka", "colombo" to "Asia/Colombo",
            "kathmandu" to "Asia/Kathmandu", "bangkok" to "Asia/Bangkok", "jakarta" to "Asia/Jakarta",
            "kuala lumpur" to "Asia/Kuala_Lumpur", "singapore" to "Asia/Singapore", "manila" to "Asia/Manila",
            "hong kong" to "Asia/Hong_Kong", "shanghai" to "Asia/Shanghai", "beijing" to "Asia/Shanghai",
            "shenzhen" to "Asia/Shanghai", "taipei" to "Asia/Taipei", "tokyo" to "Asia/Tokyo",
            "osaka" to "Asia/Tokyo", "seoul" to "Asia/Seoul", "sydney" to "Australia/Sydney",
            "melbourne" to "Australia/Melbourne", "brisbane" to "Australia/Brisbane", "perth" to "Australia/Perth",
            "auckland" to "Pacific/Auckland", "wellington" to "Pacific/Auckland", "honolulu" to "Pacific/Honolulu",
            "anchorage" to "America/Anchorage", "reykjavik" to "Atlantic/Reykjavik",
        )

        private val UNITS = listOf(
            // length
            Measure("length", 0.001, "mm", listOf("mm", "millimeter", "millimetre")),
            Measure("length", 0.01, "cm", listOf("cm", "centimeter", "centimetre")),
            Measure("length", 1.0, "m", listOf("m", "meter", "metre")),
            Measure("length", 1000.0, "km", listOf("km", "kilometer", "kilometre")),
            Measure("length", 0.0254, "in", listOf("in", "inch", "inches")),
            Measure("length", 0.3048, "ft", listOf("ft", "foot", "feet")),
            Measure("length", 0.9144, "yd", listOf("yd", "yard", "yards")),
            Measure("length", 1609.344, "mi", listOf("mi", "mile", "miles")),
            Measure("length", 1852.0, "nmi", listOf("nmi", "nautical mile", "nautical miles")),
            // mass
            Measure("mass", 0.001, "g", listOf("g", "gram", "grams")),
            Measure("mass", 1.0, "kg", listOf("kg", "kilogram", "kilograms")),
            Measure("mass", 1000.0, "t", listOf("t", "tonne", "tonnes", "ton", "tons")),
            Measure("mass", 0.0283495, "oz", listOf("oz", "ounce", "ounces")),
            Measure("mass", 0.453592, "lb", listOf("lb", "pound", "pounds")),
            Measure("mass", 6.35029, "st", listOf("st", "stone", "stones")),
            Measure("mass", 0.000001, "mg", listOf("mg", "milligram", "milligrams")),
            // volume
            Measure("volume", 0.001, "ml", listOf("ml", "milliliter", "millilitre")),
            Measure("volume", 1.0, "l", listOf("l", "liter", "litre")),
            Measure("volume", 0.00492892, "tsp", listOf("tsp", "teaspoon", "teaspoons")),
            Measure("volume", 0.0147868, "tbsp", listOf("tbsp", "tablespoon", "tablespoons")),
            Measure("volume", 0.236588, "cup", listOf("cup", "cups")),
            Measure("volume", 0.473176, "pt", listOf("pt", "pint", "pints")),
            Measure("volume", 0.946353, "qt", listOf("qt", "quart", "quarts")),
            Measure("volume", 3.78541, "gal", listOf("gal", "gallon", "gallons")),
            // speed
            Measure("speed", 1.0, "m/s", listOf("m/s", "meter per second", "metre per second")),
            Measure("speed", 0.277778, "km/h", listOf("km/h", "kph", "kilometer per hour", "kilometre per hour")),
            Measure("speed", 0.44704, "mph", listOf("mph", "mile per hour", "miles per hour")),
            Measure("speed", 0.514444, "kn", listOf("kn", "knot", "knots")),
            // area
            Measure("area", 1.0, "m²", listOf("m2", "sqm", "square meter", "square metre")),
            Measure("area", 10_000.0, "ha", listOf("ha", "hectare", "hectares")),
            Measure("area", 4046.86, "acre", listOf("acre", "acres")),
            Measure("area", 0.092903, "ft²", listOf("ft2", "sqft", "square foot", "square feet")),
            Measure("area", 2_589_988.0, "mi²", listOf("mi2", "sqmi", "square mile", "square miles")),
            Measure("area", 1_000_000.0, "km²", listOf("km2", "sqkm", "square kilometer", "square kilometre")),
            // data
            Measure("data", 1.0, "byte", listOf("byte", "bytes")),
            Measure("data", 1024.0, "KB", listOf("kb", "kilobyte", "kilobytes")),
            Measure("data", 1024.0 * 1024, "MB", listOf("mb", "megabyte", "megabytes")),
            Measure("data", 1024.0 * 1024 * 1024, "GB", listOf("gb", "gigabyte", "gigabytes")),
            Measure("data", 1024.0 * 1024 * 1024 * 1024, "TB", listOf("tb", "terabyte", "terabytes")),
            // time
            Measure("duration", 1.0, "second", listOf("s", "sec", "second", "seconds")),
            Measure("duration", 60.0, "minute", listOf("min", "minute", "minutes")),
            Measure("duration", 3600.0, "hour", listOf("h", "hr", "hour", "hours")),
            Measure("duration", 86400.0, "day", listOf("day", "days")),
            Measure("duration", 604800.0, "week", listOf("week", "weeks")),
            Measure("duration", 31_557_600.0, "year", listOf("year", "years")),
            // temperature (aliases first value is the canonical key used by the formula)
            Measure("temperature", 1.0, "°C", listOf("c", "celsius", "centigrade")),
            Measure("temperature", 1.0, "°F", listOf("f", "fahrenheit")),
            Measure("temperature", 1.0, "K", listOf("k", "kelvin")),
        )
    }
}

/** A tiny recursive-descent calculator. No eval, no reflection, no arbitrary code. */
private object Calculator {
    fun eval(input: String): Double? = runCatching { Parser(input).parse() }.getOrNull()?.takeIf { it.isFinite() }

    private class Parser(private val s: String) {
        private var i = 0

        fun parse(): Double? {
            val v = expr() ?: return null
            skip()
            return if (i == s.length) v else null
        }

        private fun expr(): Double? {
            var v = term() ?: return null
            while (true) {
                skip()
                v = when {
                    eat('+') -> v + (term() ?: return null)
                    eat('-') -> v - (term() ?: return null)
                    else -> return v
                }
            }
        }

        private fun term(): Double? {
            var v = power() ?: return null
            while (true) {
                skip()
                v = when {
                    eat('*') -> v * (power() ?: return null)
                    eat('/') -> {
                        val d = power() ?: return null
                        if (d == 0.0) return null
                        v / d
                    }
                    eat('%') -> {
                        val d = power() ?: return null
                        if (d == 0.0) return null
                        v % d
                    }
                    else -> return v
                }
            }
        }

        private fun power(): Double? {
            val base = unary() ?: return null
            skip()
            return if (eat('^')) {
                val e = power() ?: return null
                Math.pow(base, e)
            } else base
        }

        private fun unary(): Double? {
            skip()
            if (eat('+')) return unary()
            if (eat('-')) return -(unary() ?: return null)
            return primary()
        }

        private fun primary(): Double? {
            skip()
            if (eat('(')) {
                val v = expr() ?: return null
                skip()
                if (!eat(')')) return null
                return v
            }
            if (i < s.length && (s[i].isDigit() || s[i] == '.')) {
                val start = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                return s.substring(start, i).toDoubleOrNull()
            }
            if (i < s.length && s[i].isLetter()) {
                val start = i
                while (i < s.length && (s[i].isLetter() || s[i] == '_')) i++
                val name = s.substring(start, i).lowercase(Locale.ROOT)
                skip()
                if (eat('(')) {
                    val arg = expr() ?: return null
                    skip()
                    if (!eat(')')) return null
                    return function(name, arg)
                }
                return when (name) {
                    "pi" -> Math.PI
                    "e" -> Math.E
                    else -> null
                }
            }
            return null
        }

        private fun function(name: String, v: Double): Double? = when (name) {
            "sqrt" -> if (v < 0) null else sqrt(v)
            "abs" -> abs(v)
            "round" -> round(v)
            "floor" -> floor(v)
            "ceil" -> ceil(v)
            "exp" -> exp(v)
            "ln" -> if (v <= 0) null else ln(v)
            "log", "log10" -> if (v <= 0) null else log10(v)
            "sin" -> sin(Math.toRadians(v))
            "cos" -> cos(Math.toRadians(v))
            "tan" -> tan(Math.toRadians(v))
            else -> null
        }

        private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }

        private fun eat(c: Char): Boolean {
            if (i < s.length && s[i] == c) { i++; return true }
            return false
        }
    }
}
