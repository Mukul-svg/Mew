package com.past9.phoneaos.wakeup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.past9.phoneaos.MainActivity
import com.past9.phoneaos.agent.sharedHttp
import com.past9.phoneaos.remote.LinkProblem
import com.past9.phoneaos.remote.Pinning
import com.past9.phoneaos.remote.RemoteHub
import com.past9.phoneaos.remote.RpcClient
import com.past9.phoneaos.remote.phoneFacts
import com.past9.phoneaos.system.ForegroundGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The wake channel: a second, tiny link to the computer (or the always-on server) held by a foreground service, so a
 * wake-up reaches the phone in seconds while the app is off screen. It says hello as `wakeOnly` and hears only
 * `wakeups.*` events; the app's own link still pauses 30 s after it leaves the screen. No third party in the path.
 */
object WakeChannel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var rpc: RpcClient? = null
    @Volatile private var loop: Job? = null
    @Volatile private var failures = 0
    @Volatile private var givingUp = false
    @Volatile var connectedTo: String = ""
        private set
    /** Which link the open channel belongs to (mesh, device, pass): a new link must not keep the old computer's channel. */
    @Volatile private var openKey: String = ""
    /** The computer it reached has no wake-ups yet (an older Mew app): it cannot ring this phone. */
    @Volatile var lacksWakeups: Boolean = false
        private set

    private fun key(link: com.past9.phoneaos.remote.Link) = "${link.meshId}|${link.deviceId}|${link.pass.hashCode()}"
    /** Pinned clients by certificate: a fresh TLS context per retry was pure repeat work. */
    private val clients = java.util.concurrent.ConcurrentHashMap<String, okhttp3.OkHttpClient>()
    /** An open socket waits on this instead of a 15 s poll; onClosed wakes it at once. */
    private val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /** Linked and on: keep the channel up (starts the service). Not linked: stop it. */
    fun ensure(context: Context) {
        val link = RemoteHub.link.value ?: run { RemoteHub.init(context) ; RemoteHub.link.value }
        if (link == null || !link.on) { stop(context); return }
        if (openKey.isNotEmpty() && openKey != key(link)) {
            Log.i(WakeHub.TAG, "link changed: the wake channel moves to the new computer")
            loop?.cancel(); loop = null
            rpc?.close(); rpc = null
            openKey = ""; connectedTo = ""; lacksWakeups = false
        }
        ForegroundGuard.start(context, WakeChannelService::class.java)
    }

    fun stop(context: Context) {
        loop?.cancel(); loop = null
        rpc?.close(); rpc = null
        ForegroundGuard.stop(context, WakeChannelService::class.java)
    }

    /** The service is going: its socket and reconnect loop must not outlive it and keep waking the phone. */
    internal fun stopped() {
        loop?.cancel(); loop = null
        rpc?.close(); rpc = null
        openKey = ""; connectedTo = ""; lacksWakeups = false; failures = 0
    }

    val isOpen: Boolean get() = rpc?.isOpen == true

    /** Run by the service: connect, and connect again whenever the link drops (1, 2, 4 ... 30 s). */
    internal fun run(context: Context) {
        if (loop?.isActive == true) return
        givingUp = false
        val app = context.applicationContext
        loop = scope.launch {
            while (true) {
                val link = RemoteHub.link.value
                // Link off: wait for the flow, not a 30-second alarm. A service left alive by the system must not
                // wake the phone every half minute just to learn that it has nothing to do.
                if (link == null || !link.on) { RemoteHub.link.first { it?.on == true }; continue }
                if (rpc?.isOpen == true && openKey == key(link)) { wake.receive(); continue }
                if (rpc != null) { rpc?.close(); rpc = null }
                var opened = false
                for (brain in link.brains.sortedBy { it.priority }) {
                    for (url in brain.urls) {
                        opened = tryOpen(app, url, brain.fp, link.pass, key(link))
                        if (opened) { connectedTo = brain.name; break }
                    }
                    if (opened) break
                }
                if (opened) { failures = 0; continue }
                failures++
                delay(minOf(30_000L, 1_000L shl minOf(failures - 1, 5)))
            }
        }
    }

    private suspend fun tryOpen(app: Context, url: String, fp: String, pass: String, linkKey: String): Boolean {
        val http = clients.getOrPut(fp) { Pinning.client(sharedHttp, Pinning.PinnedTrust({ listOf(fp) })) }
        val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val client = RpcClient(http, url, pass, { hello(app) }, object : RpcClient.Listener {
            override fun onOpen(rpc: RpcClient) {
                openKey = linkKey
                done.complete(true)
                scope.launch { onConnected(app, rpc) }
            }
            override fun onEvent(rpc: RpcClient, name: String, payload: Any?) {
                if (name == "wakeups.changed") WakeSnapshot.fromWire(payload as? JSONObject, System.currentTimeMillis())?.let { WakeHub.apply(app, it) }
            }
            override fun onClosed(rpc: RpcClient, why: String, opened: Boolean, problem: LinkProblem) {
                done.complete(false)
                if (this@WakeChannel.rpc === rpc) this@WakeChannel.rpc = null
                wake.trySend(Unit)
                Log.i(WakeHub.TAG, "wake channel to $url ended: $why ($problem)")
                // A forgotten phone or a changed certificate cannot be fixed by retrying: stop the radio work and
                // let Settings show the rescan message instead of hammering every 30 seconds.
                if (!givingUp && (problem == LinkProblem.Unpaired || problem == LinkProblem.CertChanged)) {
                    givingUp = true
                    scope.launch { stop(app) }
                }
            }
        })
        rpc = client
        client.connect()
        return kotlinx.coroutines.withTimeoutOrNull(15_000) { done.await() } ?: run { client.close(); false }
    }

    private fun hello(context: Context): JSONObject = JSONObject().put("device", phoneFacts(context).toJson().put("wakeOnly", true))

    private suspend fun onConnected(context: Context, rpc: RpcClient) {
        Log.i(WakeHub.TAG, "wake channel open")
        // A slow first answer used to leave the channel open with no wake snapshot for the whole connection.
        // Retry on the same socket with a growing wait, and give up only for an older computer with no wake-ups.
        var wait = 15_000L
        while (rpc.isOpen) {
            val result = runCatching { rpc.call("wakeups.list", listOf(), 15_000) }
            val wire = result.getOrNull()
            val snap = WakeSnapshot.fromWire(wire as? JSONObject, System.currentTimeMillis())
            if (snap != null) {
                WakeHub.apply(context, snap)
                lacksWakeups = false
                break
            }
            val why = result.exceptionOrNull()?.message.orEmpty()
            if (why.contains("not available", ignoreCase = true)) {
                lacksWakeups = true
                Log.w(WakeHub.TAG, "wakeups.list: $why")
                break
            }
            if (result.isFailure) Log.w(WakeHub.TAG, "wakeups.list: $why")
            delay(wait)
            wait = minOf(wait * 2, 300_000L)
        }
        report(context)
        flush(context)
    }

    /** Send his answers the computer has not had yet. */
    fun flush(context: Context) {
        val r = rpc ?: return
        scope.launch {
            for (o in WakeHub.pendingAnswers(context)) {
                val ok = runCatching {
                    if (o.optString("what") == "snooze") r.call("wakeups.snooze", listOf(o.optString("id"), o.optInt("minutes", 5)), 15_000)
                    else r.call("wakeups.ack", listOf(o.optString("id")), 15_000)
                }.isSuccess
                if (!ok) return@launch
                WakeHub.answerDelivered(context, o)
            }
        }
    }

    fun seen(context: Context, id: String) {
        val r = rpc ?: return
        scope.launch { runCatching { r.call("wakeups.seen", listOf(id), 10_000) } }
    }

    /** Tell the computer what this phone can do (Settings > Wake-ups on the computer shows it). */
    fun report(context: Context) {
        val r = rpc ?: return
        scope.launch {
            val checks = WakePermissions.checks(context).map { JSONObject().put("key", it.key).put("label", it.label).put("ok", it.ok).put("help", it.help) }
            runCatching { r.call("wakeups.status", listOf(JSONObject().put("appVersion", com.past9.phoneaos.BuildConfig.VERSION_NAME).put("checks", org.json.JSONArray(checks))), 10_000) }
        }
    }

    /** The test button: rings this phone through the computer, the same path any wake takes. */
    suspend fun test(severity: String): String {
        val r = rpc ?: throw IllegalStateException("Not connected to your computer right now.")
        return r.call("wakeups.test", listOf(severity), 15_000)?.toString().orEmpty()
    }
}

/** Holds the wake channel open while the app is off screen. Low-key ongoing notification; restarted by the system. */
class WakeChannelService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ForegroundGuard.enter(this, NOTE_ID, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0) { build(this) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If Android restarted this sticky service in a fresh process, load the saved link first: without it
        // the old loop waited 30 s forever, kept the notification, and never reconnected.
        RemoteHub.init(this)
        if (RemoteHub.link.value?.on != true) { stopSelf(); return START_NOT_STICKY }
        WakeChannel.run(this)
        WakeHub.evaluate(this)
        return START_STICKY
    }

    override fun onDestroy() { WakeChannel.stopped(); ForegroundGuard.left(this); super.onDestroy() }

    companion object {
        const val NOTE_ID = 7301
        const val CHANNEL = "wake_channel"

        fun build(context: Context): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && nm?.getNotificationChannel(CHANNEL) == null) {
                nm?.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(com.past9.phoneaos.R.string.wake_ready), NotificationManager.IMPORTANCE_MIN).apply {
                    description = context.getString(com.past9.phoneaos.R.string.wake_ready_channel_desc); setShowBadge(false)
                })
            }
            val open = PendingIntent.getActivity(context, 7302, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(context.getString(com.past9.phoneaos.R.string.wake_ready)).setContentText(context.getString(com.past9.phoneaos.R.string.wake_ready_text))
                .setOngoing(true).setPriority(NotificationCompat.PRIORITY_MIN).setContentIntent(open).build()
        }
    }
}
