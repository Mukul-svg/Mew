package com.past9.phoneaos

import com.past9.phoneaos.web.QueryPlanner
import com.past9.phoneaos.web.ResearchGate
import com.past9.phoneaos.web.SearchDepth
import com.past9.phoneaos.web.SearchFreshness
import com.past9.phoneaos.web.SearchTopic
import com.past9.phoneaos.web.WebSearchMode
import org.junit.Assert.*
import org.junit.Test

class WebSearchPlannerTest {
    @Test fun publicQuestionsAreResearched() {
        listOf(
            "Who won the 2026 FIFA World Cup?",
            "What is the capital of France?",
            "Latest news on the Springboks",
            "Compare the iPhone 17 and the Pixel 10",
            "Is it safe to travel to Kenya right now?",
            "How much does the new MacBook cost?",
            "What does the law say about refunds in South Africa?",
            "Explain how DNS works",
        ).forEach { assertTrue(it, ResearchGate.shouldResearch(it, WebSearchMode.QUESTIONS)) }
    }

    @Test fun personalActionsMathAndSmallTalkAreSkipped() {
        listOf(
            "What's on my calendar?",
            "Open WhatsApp",
            "Can you send a message to Mom?",
            "Remind me to call the dentist",
            "Add milk to my list",
            "How much battery do I have?",
            "What is 17 * 3?",
            "Write a poem about the sea",
            "Fix this code ```val x = 1```",
            "Thanks!",
            "Hold",
            "What time is it?",
        ).forEach { assertFalse(it, ResearchGate.shouldResearch(it, WebSearchMode.QUESTIONS)) }
    }

    @Test fun alwaysSearchesPublicStatementsButStillSkipsActions() {
        assertTrue(ResearchGate.shouldResearch("The sky is blue", WebSearchMode.ALWAYS))
        assertFalse(ResearchGate.shouldResearch("Open WhatsApp", WebSearchMode.ALWAYS))
        assertFalse(ResearchGate.shouldResearch("Who won the World Cup?", WebSearchMode.OFF))
    }

    @Test fun depthFollowsComplexity() {
        assertEquals(SearchDepth.QUICK, ResearchGate.depthFor("Who is the president of South Africa?"))
        assertEquals(SearchDepth.STANDARD, ResearchGate.depthFor("Compare the iPhone 17 and Pixel 10"))
        assertEquals(SearchDepth.DEEP, ResearchGate.depthFor("Do deep research on the best home safes"))
    }

    @Test fun comparisonFansOutIntoFocusedQueries() {
        val q = QueryPlanner.plan("Compare the iPhone 17 and the Pixel 10")
        assertEquals(3, q.size)
        assertTrue(q.any { it.contains("iPhone 17", true) })
        assertTrue(q.any { it.contains("Pixel 10", true) })
        val standard = QueryPlanner.forDepth(q, SearchDepth.STANDARD)
        assertEquals(2, standard.size)
        assertTrue(standard.none { it.startsWith("compare", true) })
        val vs = QueryPlanner.plan("Notion vs Obsidian")
        assertTrue(vs.any { it.equals("Notion", true) })
        assertTrue(vs.any { it.equals("Obsidian", true) })
    }

    @Test fun freshnessAndTopicAreDetected() {
        assertEquals(SearchFreshness.DAY, QueryPlanner.freshnessFor("latest news today"))
        assertEquals(SearchFreshness.WEEK, QueryPlanner.freshnessFor("current price of gold"))
        assertEquals(SearchTopic.NEWS, QueryPlanner.topicFor("who won the match last night"))
        assertEquals(SearchTopic.FINANCE, QueryPlanner.topicFor("bitcoin exchange rate"))
        assertEquals(SearchTopic.RESEARCH, QueryPlanner.topicFor("clinical trial paper on arxiv"))
        assertEquals(SearchFreshness.ANY, QueryPlanner.freshnessFor("history of the Roman empire"))
    }

    @Test fun siteOperatorsBecomeDomainFilters() {
        val d = QueryPlanner.stripDomains("best laptop site:theverge.com -site:reddit.com")
        assertEquals("best laptop", d.query)
        assertEquals(listOf("theverge.com"), d.include)
        assertEquals(listOf("reddit.com"), d.exclude)
    }
}
