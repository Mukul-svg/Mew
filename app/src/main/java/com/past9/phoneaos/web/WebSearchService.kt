package com.past9.phoneaos.web

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One search engine for the tool and for automatic research.
 *
 * - configured providers run in parallel where the depth allows it;
 * - a slow or failed provider never sinks the search;
 * - an in-memory cache and single-flight make repeated fact checks instant;
 * - keyless Bing/DDG keep the feature useful before the user adds a key.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebSearchService(
    private val keyFor: (SearchProvider) -> String?,
    private val clients: Map<SearchProvider, SearchClient> = SearchProviderFactory.clients(),
    private val keyless: List<SearchClient> = SearchProviderFactory.keyless(),
    private val pageReader: WebPageReader = WebPageReader(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class CacheEntry(val at: Long, val response: WebSearchResponse)
    private class Health(var failures: Int = 0, var cooldownUntil: Long = 0, var rejected: Boolean = false, var lastMs: Long = 0)

    suspend fun search(request: WebSearchRequest): WebSearchResponse =
        com.past9.phoneaos.system.TraceSections.async("mew.web.search") { searchInner(request) }

    private suspend fun searchInner(request: WebSearchRequest): WebSearchResponse {
        val started = clock()
        val deadline = started + request.depth.timeoutMs
        val queries = request.queries.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(request.depth.maxQueries)
        if (queries.isEmpty()) return WebSearchResponse("", emptyList(), emptyList(), 0, listOf("empty query"))
        val configured = SearchProvider.order.filter { !keyFor(it).isNullOrBlank() }
        val preferred = request.preferred?.takeIf { it in configured }
        val order = if (preferred != null) listOf(preferred) + configured.filter { it != preferred } else configured
        val selected = selectProviders(order, request)
        val key = cacheKey(queries, request, selected)
        cached(key)?.let { return it.copy(fromCache = true) }
        val existing = inflight[key]
        if (existing != null) return existing.await()
        val gate = kotlinx.coroutines.CompletableDeferred<WebSearchResponse>()
        val prior = inflight.putIfAbsent(key, gate)
        if (prior != null) return prior.await()
        try {
            val response = execute(queries, request, selected, configured, started, deadline)
            putCache(key, response)
            gate.complete(response)
            return response
        } catch (e: CancellationException) {
            gate.cancel(e)
            throw e
        } catch (t: Throwable) {
            gate.completeExceptionally(t)
            throw t
        } finally {
            inflight.remove(key, gate)
        }
    }

    /** Settings > Web search > Test. Small, real, and explicit about what failed. */
    suspend fun test(preferred: SearchProvider? = null, parallel: Boolean = true): String {
        val request = WebSearchRequest(listOf("latest Android version"), SearchDepth.STANDARD, 3, SearchFreshness.WEEK, preferred = preferred, parallel = parallel, readTop = 0)
        val r = search(request)
        if (r.results.isEmpty()) return "No results" + r.warnings.takeIf { it.isNotEmpty() }?.joinToString("; ", prefix = ": ") .orEmpty()
        return "${r.results.size} results in ${r.durationMs} ms from ${r.providers.joinToString().ifBlank { "keyless search" }}\n" +
            "1. ${r.results.first().title}\n${r.results.first().url}"
    }

    private fun selectProviders(order: List<SearchProvider>, request: WebSearchRequest): List<SearchProvider> {
        if (order.isEmpty()) return emptyList()
        val max = when (request.depth) {
            SearchDepth.QUICK -> 1
            SearchDepth.STANDARD -> if (request.parallel) 2 else 1
            SearchDepth.DEEP -> if (request.parallel) 4 else 1
        }
        val healthy = synchronized(health) {
            order.filter { p -> health[p]?.let { !it.rejected && it.cooldownUntil <= clock() } ?: true }
        }
        val usable = healthy.ifEmpty { synchronized(health) { order.filter { health[it]?.rejected != true } } }
        return usable.take(max)
    }

    private suspend fun execute(
        queries: List<String>,
        request: WebSearchRequest,
        selected: List<SearchProvider>,
        allConfigured: List<SearchProvider>,
        started: Long,
        deadline: Long,
    ): WebSearchResponse {
        val lists = mutableListOf<List<WebResult>>()
        val providers = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (selected.isNotEmpty()) {
            val jobs = selected.flatMap { p -> queries.map { q -> p to q } }.take(when (request.depth) {
                SearchDepth.QUICK -> 1; SearchDepth.STANDARD -> 4; SearchDepth.DEEP -> 8
            })
            val parts = runJobs(jobs, request, deadline)
            parts.forEach { part ->
                if (part.results.isNotEmpty()) {
                    lists += part.results
                    providers += part.provider
                }
                part.error?.let { warnings += "${part.provider}: ${it.take(120)}" }
            }
        }

        // The selected provider failed or was rate-limited: try the other configured keys before leaving the paid path.
        if (lists.isEmpty()) {
            val rest = allConfigured.filter { it !in selected }.take(2)
            if (rest.isNotEmpty()) {
                val parts = runJobs(rest.map { it to queries.first() }, request, deadline)
                parts.forEach { part ->
                    if (part.results.isNotEmpty()) {
                        lists += part.results
                        providers += part.provider
                    }
                    part.error?.let { warnings += "${part.provider}: ${it.take(120)}" }
                }
            }
        }

        if (lists.isEmpty()) {
            var remaining = (deadline - clock()).coerceAtLeast(0)
            for (client in keyless) {
                if (remaining <= 0) break
                val part = try {
                    withTimeoutOrNull(remaining) { client.search(queries.first(), request, null) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ProviderResult(client.name, error = e.message)
                }
                if (part != null) {
                    if (part.results.isNotEmpty()) {
                        lists += part.results
                        providers += part.provider
                        break
                    }
                    part.error?.let { warnings += "${part.provider}: ${it.take(120)}" }
                }
                remaining = (deadline - clock()).coerceAtLeast(0L)
            }
        }

        val merged = SearchFusion.merge(lists, queries.joinToString(" "), request.freshness, request.maxResults.coerceIn(1, 10), clock())
        val results = if (request.readTop > 0 && merged.isNotEmpty()) readTop(merged, selected, request) else merged
        return WebSearchResponse(
            query = queries.first(),
            results = results,
            providers = providers.distinct(),
            durationMs = clock() - started,
            warnings = warnings.distinct(),
        )
    }

    private suspend fun runJobs(jobs: List<Pair<SearchProvider, String>>, request: WebSearchRequest, deadline: Long): List<ProviderResult> = coroutineScope {
        val budget = (deadline - clock()).coerceAtLeast(0)
        if (budget <= 0) return@coroutineScope emptyList()
        val deferred: List<Deferred<ProviderResult>> = jobs.map { (provider, query) ->
            async(Dispatchers.IO) {
                val client = clients[provider]
                if (client == null) return@async ProviderResult(provider.label, error = "no client")
                try {
                    val r = client.search(query, request, keyFor(provider))
                    note(provider, r)
                    r
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val rejected = (e as? ProviderHttpException)?.rejected == true
                    note(provider, ProviderResult(provider.label, error = e.message ?: e::class.java.simpleName, keyRejected = rejected))
                    ProviderResult(provider.label, error = e.message ?: e::class.java.simpleName, keyRejected = rejected)
                }
            }
        }
        val all = withTimeoutOrNull(minOf(request.depth.timeoutMs, budget)) { deferred.awaitAll() }
        if (all != null) return@coroutineScope all
        deferred.forEach { it.cancel() }
        deferred.mapNotNull { runCatching { it.getCompleted() }.getOrNull() }
    }

    private suspend fun readTop(merged: List<WebResult>, selected: List<SearchProvider>, request: WebSearchRequest): List<WebResult> {
        val n = request.readTop.coerceIn(0, 4)
        if (n == 0) return merged
        val chosen = merged.take(n)
        val reads = coroutineScope {
            chosen.map { r ->
                async(Dispatchers.IO) {
                    val provider = SearchProvider.entries.firstOrNull { it.label == r.provider }
                    val key = provider?.let { keyFor(it) }
                    val page = runCatching { pageReader.read(r.url, provider, key, 5_000L) }.getOrNull()
                    if (page.isNullOrBlank()) r else r.copy(page = page)
                }
            }.awaitAll()
        }
        return reads + merged.drop(n)
    }

    private fun note(provider: SearchProvider, result: ProviderResult) {
        synchronized(health) {
            val h = health.getOrPut(provider) { Health() }
            if (result.error == null) {
                h.failures = 0; h.cooldownUntil = 0; h.rejected = false; h.lastMs = result.ms
            } else {
                h.failures++
                if (result.keyRejected || result.error.contains("401") || result.error.contains("403") || result.error.contains("402")) {
                    h.rejected = true; h.cooldownUntil = clock() + 3_600_000L
                } else if (h.failures >= 2) {
                    h.cooldownUntil = clock() + 60_000L
                }
            }
        }
    }

    private fun cached(key: String): WebSearchResponse? = synchronized(cache) {
        val e = cache[key] ?: return null
        if (clock() - e.at > CACHE_TTL_MS) { cache.remove(key); null } else e.response
    }

    private fun putCache(key: String, response: WebSearchResponse) {
        if (response.results.isEmpty()) return
        synchronized(cache) { cache[key] = CacheEntry(clock(), response) }
    }

    private fun cacheKey(queries: List<String>, request: WebSearchRequest, selected: List<SearchProvider>): String =
        listOf(
            queries.joinToString("|"), request.depth.wire, request.maxResults.toString(), request.freshness.wire,
            request.topic.wire, request.readTop.toString(), request.preferred?.id.orEmpty(), request.parallel.toString(),
            selected.joinToString(",") { it.id }, request.includeDomains.joinToString(","), request.excludeDomains.joinToString(","),
        ).joinToString("::")

    companion object {
        private const val CACHE_TTL_MS = 5 * 60_000L
        private val cache = object : LinkedHashMap<String, CacheEntry>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean = size > 128
        }
        private val inflight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<WebSearchResponse>>()
        private val health = java.util.concurrent.ConcurrentHashMap<SearchProvider, Health>()

        /** A key, the preferred provider or the parallel switch changed: old results and circuit state are stale. */
        fun clearCache() {
            synchronized(cache) { cache.clear() }
            inflight.clear()
            health.clear()
        }
    }
}
