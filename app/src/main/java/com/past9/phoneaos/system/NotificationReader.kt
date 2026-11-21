package com.past9.phoneaos.system

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.past9.phoneaos.App
import com.past9.phoneaos.data.NotificationRow
import com.past9.phoneaos.triggers.Routines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Reads notifications ONLY from the apps the user switched on, keeps a week of them on the
 * phone, and fires any "when a notification from X mentions Y" routine.
 */
class NotificationReader : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        /** The listener while the system has it bound: how a notification's own buttons are reached. */
        @Volatile var live: NotificationReader? = null
    }

    /** The last time the 7-day rows were pruned. A prune per notification was a full table scan. */
    @Volatile private var lastPrune = 0L
    private val pruning = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onListenerConnected() {
        live = this
        // Anything posted while the listener was unbound (first grant, a reinstall, the system killing it) is
        // still in the shade: pick it up, but never fire routines for it. One worker, not one coroutine per row.
        scope.launch {
            val shade = runCatching { activeNotifications }.getOrNull().orEmpty()
            shade.forEach { record(it, live = false) }
        }
    }

    override fun onListenerDisconnected() {
        live = null
        runCatching { requestRebind(ComponentName(this, NotificationReader::class.java)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn, live = true)

    private fun handle(sbn: StatusBarNotification, live: Boolean) {
        // The watched-app set lives in the plain settings file: checking it here avoids building the whole graph
        // for every notification from an app the user never allowed.
        val watched = getSharedPreferences("settings", MODE_PRIVATE).getStringSet("notifApps", emptySet()).orEmpty()
        if (sbn.packageName == packageName || sbn.packageName !in watched) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        scope.launch { record(sbn, live) }
    }

    /** One notification, in order. The backfill uses this directly; live posts arrive through [handle]. */
    private suspend fun record(sbn: StatusBarNotification, live: Boolean) {
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val g = App.graph(this)
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        val dao = g.db.notifications()
        // Apps re-post the same notification as it updates; keep one copy per 10 minutes.
        if (dao.dupes(sbn.packageName, title, text, sbn.postTime - 600_000) > 0) return
        dao.insert(NotificationRow(pkg = sbn.packageName, app = app, title = title, text = text, postedAt = sbn.postTime))
        pruneIfDue(dao)
        if (!live) return
        g.db.triggers().list().filter { it.enabled && it.kind == "notification" }.forEach { t ->
            val (pkg, word) = t.spec.split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if ((pkg == sbn.packageName || app.equals(pkg, true)) && (word.isBlank() || title.contains(word, true) || text.contains(word, true)))
                Routines.runNow(this@NotificationReader, t.id, "A notification just arrived from $app: \"$title: ${text.take(400)}\". ")
        }
    }

    /** Seven-day retention is kept; the DELETE runs once a day, not once per notification. */
    private suspend fun pruneIfDue(dao: com.past9.phoneaos.data.NotificationDao) {
        val now = System.currentTimeMillis()
        if (now - lastPrune < 24 * 60 * 60_000L) return
        if (!pruning.compareAndSet(false, true)) return
        try {
            dao.prune(now - 7 * 86_400_000L)
            lastPrune = now
        } finally {
            pruning.set(false)
        }
    }
}
