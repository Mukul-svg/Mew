package com.past9.phoneaos.web

/**
 * Decides whether a user turn should be researched before the model answers.
 *
 * This is deliberately pure and conservative about actions and personal data: a wrong "yes" adds a second of
 * latency and spends a search credit; a wrong "no" can produce an unverified fact. Public questions are searched;
 * commands, the user's own data, maths and code that is already in the message are not.
 */
object ResearchGate {
    private val explicitSearch = Regex("(?i)\\b(web search|search the web|search online|google|look up|lookup|fact[- ]?check|research (this|that|it)|find online|check online)\\b")
    private val smallTalk = Regex("(?i)^\\s*(hi|hello|hey|good (morning|afternoon|evening|night)|thanks|thank you|ok|okay|yes|yeah|no|nope|sure|please|stop|hold|cancel|never ?mind|got it|nice|cool|lol)\\s*[.!?]*\\s*$")
    private val action = Regex("(?i)^\\s*(please\\s+)?(open|launch|send|text|call|message|email|set|remind|add|create|make|delete|remove|book|buy|order|play|pause|resume|stop|start|turn|connect|disconnect|install|uninstall|download|upload|share|take|record|capture|show me my|find my|check my|read my|search my|clear|forget|pin|unpin|archive|reply|forward|like|follow|post|pay|transfer|move|copy|rename|schedule|snooze|cancel|translate|summarize|rewrite|write|draft|compose|generate|draw|edit)\\b")
    private val politeAction = Regex("(?i)^\\s*(can|could|would|will)\\s+you\\s+(open|launch|send|text|call|message|email|set|remind|add|create|delete|remove|book|buy|order|play|pause|resume|stop|start|turn|connect|install|download|upload|share|take|record|pay|transfer|move|copy|rename|schedule|cancel|snooze|archive|reply|forward|like|follow|post|translate|summarize|rewrite|write|draft|compose|generate|draw|edit)\\b")
    private val personal = Regex("(?i)\\b(my|our|me|i)\\b.{0,45}\\b(calendar|schedule|tasks?|to-?dos?|reminders?|memor(y|ies)|chats?|messages?|notifications?|contacts?|photos?|files?|downloads?|location|battery|storage|wi-?fi|bluetooth|apps?|settings?|routines?|computer|watch|screen|phone|manager|boss|doctor|dentist|therapist|wife|husband|partner|girlfriend|boyfriend|family|friends?|mother|father|mom|dad|sister|brother|child|son|daughter|ip address)\\b")
    private val personalStart = Regex("(?i)^\\s*(what did i|where am i|what time is it|what'?s the time|what'?s the date|what day is it|how much battery|how much storage|what'?s on my|who is my|when is my|do i have|(latest|last|newest|recent)\\s+(message|email|text|notification|call|photo|file|order|delivery))\\b")
    private val mathOnly = Regex("^[\\s\\d+\\-*/().=%^]+$")
    private val mathQuestion = Regex("(?i)^\\s*(what is|what'?s|calculate|compute|solve)\\s+[\\d\\s+\\-*/().=%^]+\\??\\s*$")
    private val questionStart = Regex("(?i)^\\s*(who|what|when|where|why|how|which|whose|whom|is|are|was|were|do|does|did|can|could|should|would|will|has|have|had|am|tell me about|explain|describe|define|compare|difference between)\\b")
    private val publicTrigger = Regex("(?i)\\b(latest|today|tonight|now|current|currently|recent|recently|this (week|month|year)|news|breaking|live|price|cost|rate|score|stock|weather|forecast|release|released|update|election|who won|how much is|how many|review|reviews|best|vs\\.?|versus|law|legal|health|symptom|medicine|travel|visa|flight|hotel|recipe|history|meaning|definition|birth|age|net worth)\\b")
    private val codeBlock = Regex("(?s)```|stack ?trace|exception:|traceback")

    fun shouldResearch(text: String, mode: WebSearchMode): Boolean {
        if (mode == WebSearchMode.OFF) return false
        val t = text.trim()
        if (t.length < 3) return false
        if (smallTalk.matches(t)) return false
        if (explicitSearch.containsMatchIn(t)) return true
        if (mathOnly.matches(t) || mathQuestion.matches(t)) return false
        // A pasted stack trace or code block is the user's own material: read it, don't search it.
        if (codeBlock.containsMatchIn(t)) return false
        if (personal.containsMatchIn(t) || personalStart.containsMatchIn(t)) return false
        if (action.containsMatchIn(t) || politeAction.containsMatchIn(t)) return false
        if (mode == WebSearchMode.ALWAYS) return true
        if (questionStart.containsMatchIn(t) || t.endsWith("?")) return true
        return publicTrigger.containsMatchIn(t)
    }

    fun depthFor(text: String): SearchDepth {
        val t = text.lowercase()
        if (Regex("\\b(deep research|in depth|comprehensive|thorough|multiple sources|research this|long report)\\b").containsMatchIn(t)) return SearchDepth.DEEP
        val complex = Regex("\\b(compare|vs\\.?|versus|review|best|difference|pros and cons|should i|is it worth|latest|news)\\b").containsMatchIn(t) ||
            t.count { it == '?' } > 1 || t.length > 140
        return if (complex) SearchDepth.STANDARD else SearchDepth.QUICK
    }
}

/**
 * Turns a user question into a few focused queries. Provider docs all recommend short queries and splitting a
 * complex question into independent sub-queries; this is the deterministic version, with no extra model call.
 */
object QueryPlanner {
    data class Domains(val query: String, val include: List<String>, val exclude: List<String>)

    private val leading = Regex("(?i)^\\s*(please\\s+|can you\\s+|could you\\s+|would you\\s+|tell me\\s+|i want to know\\s+|do you know\\s+|i need to know\\s+)+")
    private val siteToken = Regex("(?i)(?:^|\\s)(-?)site:([^\\s]+)")

    fun plan(text: String, freshness: SearchFreshness = freshnessFor(text), topic: SearchTopic = topicFor(text)): List<String> {
        val cleaned = clean(text)
        if (cleaned.isBlank()) return emptyList()
        val out = linkedSetOf(cleaned)

        // "compare X and Y" / "X vs Y": the two sides are useful independent queries.
        val compareAnd = Regex("(?i)^\\s*compare\\s+(.+?)\\s+(?:and|with|to)\\s+(.+?)\\s*$")
        val versus = Regex("(?i)^\\s*(.+?)\\s+(?:vs\\.?|versus)\\s+(.+?)\\s*$")
        val diff = Regex("(?i)^\\s*difference between\\s+(.+?)\\s+and\\s+(.+?)\\s*$")
        (diff.matchEntire(cleaned) ?: compareAnd.matchEntire(cleaned) ?: versus.matchEntire(cleaned))?.let { m ->
            val left = m.groupValues[1].trim().trim('"', '\'')
            val right = m.groupValues[2].trim().trim('"', '\'')
            if (left.length >= 2 && right.length >= 2 && left.length < 90 && right.length < 90) {
                out += if (left.contains(" ", true) && !left.contains("compare", true)) "\"$left\"" else left
                out += if (right.contains(" ", true)) "\"$right\"" else right
            }
        }
        if (freshness != SearchFreshness.ANY && out.none { it.contains("latest", true) || it.contains("recent", true) }) {
            out += "latest $cleaned"
        }
        if (topic == SearchTopic.NEWS && out.none { it.contains("news", true) }) out += "$cleaned news"
        return out.map { it.take(1_500) }.filter { it.length >= 2 }.distinct().take(3)
    }

    fun clean(text: String): String =
        stripDomains(text.trim()).query
            .replace(leading, "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('?', '.', '!', ';', ':')
            .take(1_500)

    /**
     * A comparison's two sides are more useful than the combined wording at standard depth. A quick lookup still
     * uses the combined question; deep uses all three.
     */
    fun forDepth(queries: List<String>, depth: SearchDepth): List<String> {
        if (queries.isEmpty()) return queries
        val comparison = queries.size >= 3 && (
            queries[0].contains(" vs ", true) || queries[0].contains(" versus ", true) ||
                queries[0].startsWith("compare ", true) || queries[0].startsWith("difference between ", true)
            )
        if (depth == SearchDepth.STANDARD && comparison) return queries.drop(1).take(2)
        return queries.take(depth.maxQueries)
    }

    fun freshnessFor(text: String): SearchFreshness {
        val t = text.lowercase()
        return when {
            Regex("\\b(today|tonight|right now|live|breaking|this morning|this afternoon)\\b").containsMatchIn(t) -> SearchFreshness.DAY
            Regex("\\b(latest|this week|current|recent|news|update|release|price|score|weather|forecast)\\b").containsMatchIn(t) -> SearchFreshness.WEEK
            Regex("\\b(this month|past month|last month)\\b").containsMatchIn(t) -> SearchFreshness.MONTH
            Regex("\\b(this year|last year|20\\d{2})\\b").containsMatchIn(t) -> SearchFreshness.YEAR
            else -> SearchFreshness.ANY
        }
    }

    fun topicFor(text: String): SearchTopic {
        val t = text.lowercase()
        return when {
            Regex("\\b(stock|share price|exchange rate|market cap|crypto|bitcoin|interest rate|inflation|earnings|finance)\\b").containsMatchIn(t) -> SearchTopic.FINANCE
            Regex("\\b(research paper|study|journal|arxiv|pubmed|clinical trial|paper|publication)\\b").containsMatchIn(t) -> SearchTopic.RESEARCH
            Regex("\\b(news|breaking|headline|who won|election|match|game|score|latest|today)\\b").containsMatchIn(t) -> SearchTopic.NEWS
            else -> SearchTopic.GENERAL
        }
    }

    /** Pull `site:` / `-site:` out of the query; providers get explicit domain filters instead. */
    fun stripDomains(text: String): Domains {
        val include = mutableListOf<String>()
        val exclude = mutableListOf<String>()
        val stripped = siteToken.replace(text) { m ->
            val host = m.groupValues[2].trim().trim('"', '\'').removePrefix("www.").lowercase()
            if (host.matches(Regex("^[a-z0-9.-]+\\.[a-z]{2,}$"))) {
                if (m.groupValues[1] == "-") exclude += host else include += host
            }
            " "
        }.replace(Regex("\\s+"), " ").trim()
        return Domains(stripped, include.distinct().take(8), exclude.distinct().take(8))
    }
}
