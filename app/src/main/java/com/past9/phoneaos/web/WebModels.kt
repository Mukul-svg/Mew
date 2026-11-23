package com.past9.phoneaos.web

import java.net.URI
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** The search providers Mew can use. Order is the default quality order. */
enum class SearchProvider(val id: String, val label: String, val secretName: String) {
    TAVILY("tavily", "Tavily", "websearch_tavily"),
    EXA("exa", "Exa", "websearch_exa"),
    FIRECRAWL("firecrawl", "Firecrawl", "websearch_firecrawl"),
    TINYFISH("tinyfish", "TinyFish", "websearch_tinyfish");

    companion object {
        /** Default order when the user has several keys. */
        val order = listOf(TAVILY, EXA, FIRECRAWL, TINYFISH)
        fun of(id: String?): SearchProvider? = entries.firstOrNull { it.id.equals(id?.trim(), true) }
    }
}

enum class SearchDepth(val wire: String) {
    QUICK("quick"), STANDARD("standard"), DEEP("deep");

    val timeoutMs: Long get() = when (this) { QUICK -> 2_400L; STANDARD -> 4_200L; DEEP -> 10_000L }
    val callTimeoutMs: Long get() = when (this) { QUICK -> 2_000L; STANDARD -> 3_200L; DEEP -> 7_500L }
    val maxQueries: Int get() = when (this) { QUICK -> 1; STANDARD -> 2; DEEP -> 3 }
    val defaultResults: Int get() = when (this) { QUICK -> 5; STANDARD -> 7; DEEP -> 9 }
    val defaultReadTop: Int get() = when (this) { QUICK -> 0; STANDARD -> 0; DEEP -> 3 }

    companion object {
        fun of(raw: String?): SearchDepth = entries.firstOrNull { it.wire.equals(raw?.trim(), true) } ?: STANDARD
    }
}

enum class SearchFreshness(val wire: String) {
    ANY("any"), DAY("day"), WEEK("week"), MONTH("month"), YEAR("year");

    companion object {
        fun of(raw: String?): SearchFreshness = entries.firstOrNull { it.wire.equals(raw?.trim(), true) } ?: ANY
    }
}

enum class SearchTopic(val wire: String) {
    GENERAL("general"), NEWS("news"), FINANCE("finance"), RESEARCH("research");

    companion object {
        fun of(raw: String?): SearchTopic = entries.firstOrNull { it.wire.equals(raw?.trim(), true) } ?: GENERAL
    }
}

enum class WebSearchMode(val wire: String) {
    QUESTIONS("questions"), ALWAYS("always"), OFF("off");

    companion object {
        fun of(raw: String?): WebSearchMode = entries.firstOrNull { it.wire.equals(raw?.trim(), true) } ?: QUESTIONS
    }
}

/** One result, reduced to what the model needs. */
data class WebResult(
    val title: String,
    val url: String,
    val snippet: String,
    val provider: String,
    val publishedAt: Long? = null,
    val score: Double = 0.0,
    /** Clean page text when read_top asked for it. */
    val page: String? = null,
)

data class WebSearchRequest(
    val queries: List<String>,
    val depth: SearchDepth = SearchDepth.STANDARD,
    val maxResults: Int = depth.defaultResults,
    val freshness: SearchFreshness = SearchFreshness.ANY,
    val topic: SearchTopic = SearchTopic.GENERAL,
    val includeDomains: List<String> = emptyList(),
    val excludeDomains: List<String> = emptyList(),
    val readTop: Int = depth.defaultReadTop,
    val preferred: SearchProvider? = null,
    val parallel: Boolean = true,
) {
    val primaryQuery: String get() = queries.firstOrNull().orEmpty()
}

data class WebSearchResponse(
    val query: String,
    val results: List<WebResult>,
    val providers: List<String>,
    val durationMs: Long,
    val warnings: List<String> = emptyList(),
    val fromCache: Boolean = false,
) {
    val empty: Boolean get() = results.isEmpty()
}

/** A single provider's answer. Errors are data: one failed branch must not sink the search. */
data class ProviderResult(
    val provider: String,
    val results: List<WebResult> = emptyList(),
    val ms: Long = 0,
    val error: String? = null,
    val keyRejected: Boolean = false,
)

/** Parse the many date shapes providers use. Null is honest: no date is better than a wrong one. */
object WebDates {
    private val iso = DateTimeFormatter.ISO_DATE_TIME
    private val rfc = DateTimeFormatter.RFC_1123_DATE_TIME
    private val day = DateTimeFormatter.ISO_LOCAL_DATE
    private val relative = Regex("(?i)^\\s*(\\d+)\\s*(minute|hour|day|week|month|year)s?\\s+ago\\s*$")

    fun parse(raw: String?, now: Long = System.currentTimeMillis()): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        runCatching { return Instant.parse(s).toEpochMilli() }
        runCatching { return ZonedDateTime.parse(s, iso).toInstant().toEpochMilli() }
        runCatching { return ZonedDateTime.parse(s, rfc).toInstant().toEpochMilli() }
        runCatching { return java.time.LocalDate.parse(s, day).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }
        relative.matchEntire(s)?.let { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return null
            val unit = when (m.groupValues[2].lowercase()) {
                "minute" -> 60_000L; "hour" -> 3_600_000L; "day" -> 86_400_000L
                "week" -> 7 * 86_400_000L; "month" -> 30 * 86_400_000L; else -> 365 * 86_400_000L
            }
            return now - n * unit
        }
        // 2026-11-23T10:00:00Z / 2026-11-23 10:00:00
        runCatching { return java.time.LocalDateTime.parse(s.replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }
        return null
    }
}

/** Canonical URLs are what makes dedupe across providers work. */
object WebUrls {
    private val tracking = setOf("utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "fbclid", "gclid", "mc_cid", "mc_eid", "ref", "source", "igshid")

    fun canonical(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return s
        return runCatching {
            val u = URI(s)
            val scheme = u.scheme?.lowercase() ?: return s
            if (scheme != "http" && scheme != "https") return s
            var host = u.host?.lowercase() ?: return s
            if (host.startsWith("www.")) host = host.removePrefix("www.")
            val port = if (u.port > 0) ":${u.port}" else ""
            val path = u.path.orEmpty().ifBlank { "/" }.let { if (it.length > 1 && it.endsWith('/')) it.trimEnd('/') else it }
            val cleanPath = if (path == "/") "" else path
            val query = u.rawQuery.orEmpty().split('&').filter { part ->
                part.isNotBlank() && part.substringBefore('=').lowercase() !in tracking
            }.joinToString("&")
            buildString {
                append(scheme).append("://").append(host).append(port).append(cleanPath)
                if (query.isNotEmpty()) append('?').append(query)
            }
        }.getOrDefault(s)
    }

    fun host(url: String): String = runCatching { URI(url).host?.lowercase()?.removePrefix("www.").orEmpty() }.getOrDefault("")
}

/** Stop words kept deliberately small: search queries are short. */
internal val searchStopWords = setOf(
    "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "is", "are", "was", "were", "be", "been",
    "what", "who", "when", "where", "why", "how", "which", "is", "it", "that", "this", "these", "those", "do", "does",
    "did", "can", "could", "should", "would", "will", "my", "our", "your", "me", "i", "we", "you", "about", "from",
)

internal fun searchTokens(text: String): Set<String> =
    text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 && it !in searchStopWords }.toSet()
