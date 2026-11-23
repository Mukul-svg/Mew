package com.past9.phoneaos.web

/**
 * Rank-based fusion for result lists that use incomparable scores. RRF is parameter-light and robust: a page that
 * several providers/query variants agree on rises, without pretending Tavily's 0.81 and Exa's distance mean the same.
 */
object SearchFusion {
    private const val RRF_K = 60.0

    fun merge(
        lists: List<List<WebResult>>,
        query: String,
        freshness: SearchFreshness,
        max: Int,
        now: Long = System.currentTimeMillis(),
    ): List<WebResult> {
        if (lists.isEmpty() || max <= 0) return emptyList()
        val q = searchTokens(query)
        class Entry(var result: WebResult, var rrf: Double = 0.0, var lexical: Double = 0.0, var score: Double = 0.0, var lists: Int = 0)
        val byUrl = LinkedHashMap<String, Entry>()
        lists.forEach { list ->
            list.forEachIndexed { rank, r ->
                val key = WebUrls.canonical(r.url)
                if (key.isBlank()) return@forEachIndexed
                val e = byUrl.getOrPut(key) { Entry(r.copy(url = key), rrf = 0.0) }
                e.rrf += 1.0 / (RRF_K + rank + 1)
                e.lists++
                if (r.snippet.length > e.result.snippet.length) e.result = e.result.copy(snippet = r.snippet)
                if (e.result.publishedAt == null && r.publishedAt != null) e.result = e.result.copy(publishedAt = r.publishedAt)
                if (e.result.page == null && r.page != null) e.result = e.result.copy(page = r.page)
            }
        }
        byUrl.values.forEach { e ->
            val title = searchTokens(e.result.title)
            val snippet = searchTokens(e.result.snippet)
            val hits = title.count { it in q } * 2 + snippet.count { it in q }
            e.lexical = (hits.toDouble() / (q.size * 3).coerceAtLeast(1)).coerceIn(0.0, 1.0)
            e.score = e.rrf + 0.006 * e.lexical
            if (e.result.score in 0.0..1.0) e.score += 0.002 * e.result.score
            if (freshness != SearchFreshness.ANY) {
                val at = e.result.publishedAt
                if (at != null) {
                    val days = ((now - at).coerceAtLeast(0L) / 86_400_000.0)
                    val window = when (freshness) {
                        SearchFreshness.DAY -> 2.0; SearchFreshness.WEEK -> 14.0
                        SearchFreshness.MONTH -> 60.0; else -> 365.0
                    }
                    e.score += 0.004 * (1.0 - (days / window).coerceIn(0.0, 1.0))
                }
            }
        }
        val sorted = byUrl.values.sortedWith(compareByDescending<Entry> { it.score }.thenBy { it.result.title.lowercase() })
        val kept = mutableListOf<WebResult>()
        val domains = mutableMapOf<String, Int>()
        val titleSets = mutableListOf<Set<String>>()
        for (e in sorted) {
            if (kept.size >= max) break
            val host = WebUrls.host(e.result.url)
            if (host.isNotBlank() && (domains[host] ?: 0) >= 2) continue
            val title = searchTokens(e.result.title)
            if (title.size >= 3 && titleSets.any { prior -> jaccard(prior, title) > 0.85 }) continue
            kept += e.result.copy(score = e.score)
            titleSets += title
            if (host.isNotBlank()) domains[host] = (domains[host] ?: 0) + 1
        }
        return kept
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val union = a.size + b.size - a.count { it in b }
        return if (union <= 0) 0.0 else a.count { it in b }.toDouble() / union
    }
}
