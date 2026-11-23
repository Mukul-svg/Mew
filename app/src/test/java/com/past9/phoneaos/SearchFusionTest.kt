package com.past9.phoneaos

import com.past9.phoneaos.web.SearchFreshness
import com.past9.phoneaos.web.SearchFusion
import com.past9.phoneaos.web.WebResult
import com.past9.phoneaos.web.WebUrls
import org.junit.Assert.*
import org.junit.Test

class SearchFusionTest {
    private fun r(title: String, url: String, provider: String = "Tavily", snippet: String = "", at: Long? = null) =
        WebResult(title = title, url = url, snippet = snippet, provider = provider, publishedAt = at)

    @Test fun canonicalUrlDropsTrackingAndFragments() {
        assertEquals(
            "https://example.com/a?keep=1",
            WebUrls.canonical("https://www.Example.com/a/?utm_source=x&keep=1&fbclid=y#part"),
        )
        assertEquals("https://example.com", WebUrls.canonical("https://example.com/"))
    }

    @Test fun aPageFoundByBothProvidersWins() {
        val a = listOf(r("One", "https://one.test/a"), r("Shared", "https://shared.test/x"))
        val b = listOf(r("Shared", "https://www.shared.test/x/", "Exa"), r("Three", "https://three.test/c", "Exa"))
        val merged = SearchFusion.merge(listOf(a, b), "shared one", SearchFreshness.ANY, 5)
        assertEquals("Shared", merged.first().title)
        assertEquals(3, merged.size)
    }

    @Test fun sameDomainIsCappedAtTwo() {
        val list = listOf(
            r("A", "https://shop.test/a"),
            r("B", "https://shop.test/b"),
            r("C", "https://shop.test/c"),
            r("D", "https://other.test/d"),
        )
        val merged = SearchFusion.merge(listOf(list), "shop", SearchFreshness.ANY, 5)
        assertEquals(3, merged.size)
        assertEquals(2, merged.count { WebUrls.host(it.url) == "shop.test" })
    }

    @Test fun nearDuplicateTitlesAreDropped() {
        val list = listOf(
            r("The best coffee machine for a small kitchen", "https://a.test/1"),
            r("Best coffee machine for the small kitchen", "https://b.test/2"),
            r("A different subject entirely", "https://c.test/3"),
        )
        val merged = SearchFusion.merge(listOf(list), "best coffee machine small kitchen", SearchFreshness.ANY, 5)
        assertEquals(2, merged.size)
    }

    @Test fun freshResultsGetABoostOnlyWhenAsked() {
        val now = 1_800_000_000_000L
        val old = r("Old", "https://old.test/a", at = now - 30L * 86_400_000L)
        val fresh = r("Fresh", "https://fresh.test/b", at = now - 3_600_000L)
        val all = listOf(old, fresh)
        val neutral = SearchFusion.merge(listOf(all), "news", SearchFreshness.ANY, 2, now)
        assertEquals("Old", neutral.first().title)
        val freshFirst = SearchFusion.merge(listOf(listOf(old, fresh)), "news", SearchFreshness.DAY, 2, now)
        assertEquals("Fresh", freshFirst.first().title)
    }
}
