package com.past9.phoneaos.benchmark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Trace
import com.past9.phoneaos.App
import com.past9.phoneaos.cards.Card
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.data.GoalRow
import com.past9.phoneaos.data.MemoryRow
import com.past9.phoneaos.data.NotificationRow
import com.past9.phoneaos.data.SubKind
import com.past9.phoneaos.data.TaskRow
import com.past9.phoneaos.data.TriggerRow
import com.past9.phoneaos.people.PersonReminders
import com.past9.phoneaos.runtime.SubscriptionRuntime
import com.past9.phoneaos.system.HelperWatchdog
import com.past9.phoneaos.triggers.Routines
import com.past9.phoneaos.triggers.Wakes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Benchmark-only entry points. Macrobenchmark runs the real, non-debuggable app, so it cannot use run-as to seed
 * private data. It sends one explicit broadcast instead, waits for this receiver, then starts the activity at a
 * `benchmark_route`. Nothing here is compiled into a release or a normal debug build.
 */
class BenchmarkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val action = intent.getStringExtra("action").orEmpty()
        val pending = goAsync()
        val cookie = System.nanoTime().toInt()
        Trace.beginAsyncSection("mew.bench.$action", cookie)
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                withContext(Dispatchers.IO) { run(context.applicationContext, action) }
            } catch (t: Throwable) {
                android.util.Log.e("BenchmarkSeed", "action $action failed", t)
            } finally {
                Trace.endAsyncSection("mew.bench.$action", cookie)
                pending.finish()
            }
        }
    }

    private suspend fun run(context: Context, action: String) {
        when (action) {
            "seed_onboard" -> seedOnboard(context)
            // The chat DAO clears its own rows: never run clearAllTables() while the app's Room flows are open.
            "seed_chat" -> { seedOnboard(context); seedChat(context) }
            // Screens are cheap to repeat; clearing the small tables avoids duplicate notifications/routines.
            "seed_screens" -> { seedOnboard(context); seedScreens(context) }
            "seed" -> { seedOnboard(context); seedChat(context); seedScreens(context) }
            "reschedule" -> reschedule(context)
            "rearm" -> com.past9.phoneaos.system.Rearm.all(context)
            "run_routine" -> runRoutine(context)
            "watchdog" -> watchdog(context)
            "node" -> runNode(context)
            "browser" -> runBrowser(context)
            "notifications" -> notifications(context)
            "allow_shell_notifications" -> {
                val s = App.graph(context).settings
                s.setNotifApp("com.android.shell", true)
                s.setNotifApp("com.google.android.gm", true)
            }
            "prune" -> prune(context)
            "clear" -> clear(context)
        }
    }

    // ---- onboarding ---------------------------------------------------------------------------------

    private fun seedOnboard(context: Context) {
        val s = App.graph(context).settings
        s.setAgentName("Nova")
        s.setUserName("Sam")
        s.setAccent("iris")
        s.setMascot("scout")
        s.setOnboarded(true)
        // The first open after 4am otherwise shows the morning brief instead of the seeded chat.
        s.setExtra("morning_seen", java.time.LocalDate.now().toString())
        s.setExtra("reliability_offered", "1")
    }

    /** A benchmark seed is not additive: an earlier class's rows must not make the next class slower. */
    private suspend fun clearSmallTables(context: Context) {
        val g = App.graph(context)
        runCatching { g.db.notifications().prune(Long.MAX_VALUE) }
        runCatching { g.db.triggers().list().forEach { g.db.triggers().delete(it.id) } }
    }

    // ---- chat ---------------------------------------------------------------------------------------

    /**
     * A long, varied thread. The last block is the exact shape that used to split the live call in two: a turn, the
     * agent's "On it" line and reply, a hand-off row written at the end, then a later turn. `Chat.group` folds it.
     */
    private suspend fun seedChat(context: Context) {
        val g = App.graph(context)
        val dao = g.db.chat()
        dao.clear()
        val now = System.currentTimeMillis()
        var at = now - 260 * 60_000L
        var added = 0
        suspend fun add(kind: String, text: String, meta: JSONObject = JSONObject(), whenMs: Long = at) {
            dao.insert(ChatItem(kind = kind, text = text, meta = meta.toString(), createdAt = whenMs))
            at += 45_000
            added++
        }
        for (i in 1..110) {
            add("user", if (i % 7 == 0) "Remember this for later: project $i is with Lerato." else "Message $i: what is the status of the thing we discussed?")
            if (i % 9 == 0) {
                add("activity", "Searched the web", JSONObject().put("tool", "browser"))
                add("activity", "Read the top result", JSONObject().put("tool", "browser"))
                add("activity", "Wrote the answer", JSONObject().put("tool", "agent"))
            }
            add("agent", if (i % 5 == 0) "Done. Here is the short version, with the detail in the thread above." else "Here is what I found for message $i: the answer is in the notes, and nothing is blocked.")
        }
        // OpenUI: the money card from the real cases, plus a question, an approval, a helper and a report.
        add("agent", """
            You have R1,240.50 available in your MyBank main account.

            ```openui-lang
            root = Stack([card])
            card = Card([header, bal, sep, txHeader, tx])
            header = CardHeader("MyBank main account", "Sat 17 Oct 2026, 07:20")
            bal = EntityList([avail, hold], "default", {left: "Item", right: "Amount"})
            avail = {left: "Available balance", right: "R1,240.50", rightVariant: "number"}
            hold = {left: "Bolt pre-auth hold", right: "-R184.20", rightVariant: "number"}
            sep = Separator("horizontal", true)
            txHeader = InlineHeader("Last transactions")
            tx = EntityList([t1, t2], "default", {left: "Date", right: "Amount"})
            t1 = {left: "17 Oct Bolt", right: "-R184.20", rightVariant: "number"}
            t2 = {left: "16 Oct Spar", right: "-R312.60", rightVariant: "number"}
            ```
        """.trimIndent())
        add("question", "Which account should the transfer come from?", JSONObject().put("options", org.json.JSONArray().put("Main account").put("Savings")).put("whyBy", "Nova").put("why", "two accounts fit"))
        add("question", "APPROVAL|Send the quote to the client|To: client@example.com", JSONObject().put("remoteKind", "approval"))
        add("helper", "Check the three suppliers and compare lead times", JSONObject().put("label", "Scout").put("state", "working").put("startedAt", now - 120_000).put("model", "helper"))
        add("activity", "Helper started", JSONObject().put("helper", 1L).put("tool", "agent"))
        g.cards.put(Card(id = "bench-card", title = "Monitor prices", icon = "tag", html = "", headline = "R 3,299 cheapest",
            source = "example.com", openui = "root = Stack([c])\nc = Card([h, t])\nh = CardHeader(\"Prices\", \"today\")\nt = TextContent(\"The cheapest is R3,299.\")", pinned = true))
        add("card", "Live card: Monitor prices", JSONObject().put("card", "bench-card"))
        val report = File(context.filesDir, "benchmark-report.md").apply {
            writeText("# Benchmark report\n\n" + (1..40).joinToString("\n") { "Line $it: the agent wrote a short report." })
        }
        add("report", "Weekly review", JSONObject().put("path", report.path))
        add("notice", "A memory pack was added.")

        // The live call, in the order the database really stores it.
        val live = JSONObject().put("call", true).put("live", true).put("session", "live-bench")
        val t = now - 40_000L
        add("user", "put the numbers in the EOD report", JSONObject(live.toString()).put("turn", 0), t)
        add("activity", "On it: put the numbers in the EOD report", JSONObject().put("tool", "call"), t + 1_000)
        add("agent", "Done: the report is updated.", JSONObject(), t + 6_000)
        // Written by saveCall at session end in 0.16.1 — the row that created a second call card.
        add("agent", "put the numbers in the EOD report", JSONObject(live.toString()).put("handoff", true).put("spoken", "put the numbers in the end of day report"), t + 2_000)
        add("agent", "Anything else?", JSONObject(live.toString()).put("turn", 1), t + 8_000)
        add("notice", "Benchmark chat seeded: 110 turns, OpenUI, a question, a helper, a live call.")
        android.util.Log.i("BenchmarkSeed", "seed_chat done: rows=$added")
    }

    // ---- the other screens' data --------------------------------------------------------------------

    private suspend fun seedScreens(context: Context) {
        val g = App.graph(context)
        clearSmallTables(context)
        val now = System.currentTimeMillis()
        runCatching {
            val goal = g.db.tasks().insertGoal(GoalRow(title = "Move the studio", why = "more light", createdAt = now - 86_400_000))
            listOf(
                TaskRow(goalId = goal, title = "Measure the windows", status = "doing", dueAt = now + 3_600_000, createdAt = now - 7_200_000),
                TaskRow(goalId = goal, title = "Ask the landlord about the deposit", status = "todo", dueAt = now + 86_400_000),
                TaskRow(title = "Send the invoice to Lerato", status = "todo"),
                TaskRow(title = "Book the dentist", status = "done"),
            ).forEach { g.db.tasks().insertTask(it) }
        }
        runCatching {
            listOf(
                MemoryRow(title = "Studio address", body = "14 Orange Grove, second floor.", kind = "fact", pinned = true),
                MemoryRow(title = "Coffee", body = "Sam likes a flat white, no sugar.", kind = "preference"),
                MemoryRow(title = "Lease renewal", body = "Renews 1 March; ask for a two-year term.", kind = "decision"),
                MemoryRow(title = "Invoice how-to", body = "Use the template in Documents/invoices.", kind = "how-to"),
            ).forEach { g.db.memory().insert(it) }
        }
        runCatching {
            val p = g.people.add("Lerato", "Studio neighbour")
            g.people.log(p, "call", "Talked about the shared wall.")
            g.people.addDate(p, "Birthday", LocalDate.now().plusDays(20))
            val q = g.people.add("Sam's mother", "Call on Sundays")
            g.people.log(q, "message", "Sent the photos.")
        }
        runCatching {
            listOf(
                TriggerRow(name = "Morning brief", kind = "daily", spec = "07:00", prompt = "Give me a short morning brief."),
                TriggerRow(name = "Price watch", kind = "interval", spec = "60", prompt = "Check the price and tell me only if it changed."),
                TriggerRow(name = "Inbox watch", kind = "notification", spec = "com.google.android.gm|invoice", prompt = "Read the invoice notification and file it."),
                TriggerRow(name = "Overnight tidy", kind = "interval", spec = "1440", prompt = "Tidy the workspace and summarise."),
            ).forEach { t ->
                val id = g.db.triggers().upsert(t)
                g.db.triggers().get(id)?.let { Routines.schedule(context, it) }
            }
        }
        runCatching {
            val pkg = "com.google.android.gm"
            repeat(180) { i ->
                g.db.notifications().insert(NotificationRow(pkg = pkg, app = "Gmail", title = "Invoice $i", text = "A new invoice arrived for project $i.", postedAt = now - i * 60_000L))
            }
        }
        runCatching {
            File(context.filesDir, "benchmark-report.md").writeText(
                "# Benchmark report\n\n" + (1..40).joinToString("\n") { "Line $it: the agent wrote a short report." }
            )
        }
    }

    // ---- background work ----------------------------------------------------------------------------

    /** Every clock the app owns: routines, wakes, people's dates, the helper watchdog. */
    private suspend fun reschedule(context: Context) {
        val g = App.graph(context)
        Routines.rescheduleAll(context)
        Wakes.rearm(context)
        PersonReminders.syncAll(context)
        HelperWatchdog.ensure(context)
        g.db.triggers().list().forEach { Routines.schedule(context, it) }
    }

    private suspend fun runRoutine(context: Context) {
        val g = App.graph(context)
        val t = g.db.triggers().list().firstOrNull { it.kind == "interval" } ?: return
        Routines.runNow(context, t.id, "Benchmark run")
    }

    /** The same work the 15-minute WorkManager watchdog does, without waiting for its slot. */
    private suspend fun watchdog(context: Context) {
        val g = App.graph(context)
        runCatching { g.runtime.resumeCutOffHelpers() }
        g.runtime.workingHelperRows()
    }

    /** A real Node 24 run: startup, a 5k-item loop and 500 SHA-256 hashes. */
    private suspend fun runNode(context: Context) = withContext(Dispatchers.IO) {
        val rt = SubscriptionRuntime(context, SubKind.CLAUDE)
        if (!rt.nodeAvailable) rt.ensureNode()
        if (!rt.nodeAvailable) return@withContext
        rt.linkBins()
        val script = File(context.cacheDir, "benchmark-node.mjs").apply {
            writeText(
                """
                const items = Array.from({length: 5000}, (_, i) => ({i, v: Math.sin(i)}));
                const sum = items.reduce((a, x) => a + x.v, 0);
                const crypto = await import('node:crypto');
                for (let i = 0; i < 500; i++) crypto.createHash('sha256').update('mew' + i).digest('hex');
                console.log(JSON.stringify({sum, n: items.length}));
                """.trimIndent()
            )
        }
        val p = ProcessBuilder(File(rt.bin, "node").path, script.path)
            .directory(context.cacheDir).redirectErrorStream(true).start()
        p.waitFor(30, TimeUnit.SECONDS)
        script.delete()
    }

    /** A deterministic local page in the real browser engine (no network, no account). */
    private suspend fun runBrowser(context: Context) = withContext(Dispatchers.IO) {
        val g = App.graph(context)
        val profile = g.settings.state.value.browserProfiles.firstOrNull() ?: "Personal"
        val html = """
            <!doctype html><meta name="viewport" content="width=device-width">
            <style>body{font:16px system-ui;margin:0;padding:24px;background:#111;color:#eee}
            .row{display:flex;gap:12px;padding:14px;border-bottom:1px solid #333}.ball{width:48px;height:48px;border-radius:50%;background:#7c5cff}
            h1{font-size:22px}</style>
            <h1>Benchmark page</h1>
            ${(1..40).joinToString("") { "<div class=row><div class=ball></div><div>Row $it: a deterministic local page with text and layout.</div></div>" }}
        """.trimIndent()
        val e = com.past9.phoneaos.browser.BrowserService.await(context, "main", profile)
        e.goto("about:blank")
        // Render the deterministic page into the real browser's document; no network, no new URL capability.
        e.js("(() => { document.open(); document.write(${JSONObject.quote(html)}); document.close(); return 'ok' })()")
    }

    private suspend fun notifications(context: Context) {
        val dao = App.graph(context).db.notifications()
        val now = System.currentTimeMillis()
        repeat(250) { i ->
            dao.insert(NotificationRow(pkg = "com.google.android.gm", app = "Gmail", title = "Bench $i", text = "A notification posted for the benchmark.", postedAt = now - i * 1_000L))
        }
    }

    private suspend fun prune(context: Context) {
        App.graph(context).db.notifications().prune(System.currentTimeMillis() - 7 * 86_400_000L)
    }

    private suspend fun clear(context: Context) {
        val g = App.graph(context)
        g.db.chat().clear()
        g.db.triggers().list().forEach { g.db.triggers().delete(it.id) }
        g.db.notifications().prune(Long.MAX_VALUE)
    }

    companion object {
        const val ACTION = "com.past9.phoneaos.BENCHMARK"
    }
}
