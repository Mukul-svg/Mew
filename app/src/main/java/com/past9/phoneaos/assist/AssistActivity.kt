package com.past9.phoneaos.assist

import androidx.compose.ui.res.stringResource
import com.past9.phoneaos.R
import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.past9.phoneaos.App
import com.past9.phoneaos.Graph
import com.past9.phoneaos.MainActivity
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.remote.RemoteHub
import com.past9.phoneaos.system.ChatOnScreen
import com.past9.phoneaos.ui.SheetShape
import com.past9.phoneaos.ui.theme.AppTheme
import com.past9.phoneaos.voice.SystemDictation
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The assist sheet: a compact slide-up sheet over whatever app is on screen (and over the lock screen), listening
 * the moment it opens. It is the phone's assistant when the app holds that role, and the same sheet for a wake word,
 * a shortcut, the quick settings tile and the plain "assist" intent.
 *
 * It decides nothing about what a request means. Every request goes whole to the same agent as the main chat (the
 * one on this phone, or the linked computer's), as a normal message that stays in the chat; the sheet then follows
 * that turn through the same chat rows: thinking, what it is doing, the answer, an approval that waits for him.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AssistActivity : ComponentActivity() {
    private lateinit var g: Graph
    private lateinit var core: AssistCore
    private var source = AssistEntry.SHORTCUT
    private var view by mutableStateOf(AssistView(AssistPhase.LISTENING))
    private var rows: List<ChatItem> = emptyList()
    /** What is going on right now (questions waiting, agents at work), for the strip and for routing his words. */
    private var ctx by mutableStateOf<AssistContext?>(null)
    private var choice by mutableStateOf<AskChoice?>(null)
    private var working = false
    private var dictation: SystemDictation? = null
    private var askingPermission = false
    private var follow: Job? = null
    private var closing by mutableStateOf(false)
    /** Live voice in the assistant: a Gemini Live session instead of the one-request listening input. */
    private var liveMode = false
    /** Conversation mode: read each answer aloud and listen again, like a call. The normal sheet when Live is off or could not start. */
    private var talkMode = false
    private val speaker by lazy { com.past9.phoneaos.system.Speaker(this) }
    private var talkJob: Job? = null
    private var talkGen = 0
    private var cardSpeaking = false
    /** Cards already read aloud in this opening, so a recomposition never reads one twice. */
    private val cardSpoken = mutableSetOf<Long>()
    private var liveView by mutableStateOf(LiveView())
    /** Minimise needs "display over other apps": the slide-up sheet that asks for it. */
    private var overlaySheet by mutableStateOf(false)
    private var pendingMinimise = false

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        askingPermission = false
        if (granted && liveMode) startLive()
        else if (granted) startListening() else { liveMode = false; core.listen(); core.micFailed(getString(R.string.assist_mic_off)); refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Over the lock screen only when he switched that on; otherwise the phone asks for the unlock first.
        if (Build.VERSION.SDK_INT >= 27 && AssistPrefs.overLockScreen(this)) { setShowWhenLocked(true); setTurnScreenOn(true) }
        com.past9.phoneaos.voice.LiveTiming.mark("sheet_open")
        g = App.graph(this)
        RemoteHub.init(this)
        source = sourceOf(intent)
        core = AssistCore(micAvailable = SystemDictation.available(this))
        AssistHub.keepIfFresh()
        AssistEntry.setOpen(true)
        AssistEntry.clearNotification(this)
        Log.i(AssistService.TAG, "sheet open source=$source held=${AssistRole.held(this)}")
        val st = g.settings.state.value
        // The same chat the app shows: this phone's own, or the linked computer's.
        follow = lifecycleScope.launch {
            launch { RemoteHub.session.flatMapLatest { (it?.db ?: g.db).chat().all() }.collectLatest { rows = it; refresh() } }
            launch { RemoteHub.session.flatMapLatest { it?.status ?: g.runtime.status }.collectLatest { working = it.working; refresh() } }
            // Test builds: words put in by hand, as if spoken.
            // Live voice: the session is the host's; this sheet only shows it.
            launch { LiveHost.view.collect { liveView = it } }
            launch { AssistContextSource.updates(this@AssistActivity, g).distinctUntilChanged().collect { ctx = it } }
            launch { LiveHost.ended.collect { why -> liveEnded(why) } }
            launch { AssistHub.said.collect { words -> stopListening(); if (!core.listening) core.listen(); heard(words) } }
        }
        setContent {
            val settings by g.settings.state.collectAsState()
            AppTheme(accent = settings.accent, mascot = settings.mascot) { Sheet(settings.agentName.takeIf { it.isNotBlank() && it != "Your agent" } ?: stringResource(R.string.svc_your_agent), settings.onboarded) }
        }
        liveMode = st.onboarded && AssistPrefs.liveVoice(this)
        talkMode = st.onboarded && !liveMode && AssistPrefs.talk(this)
        if (st.onboarded) beginListening()
    }

    /** Called again while it is up (a second press of the side key, the wake word again): start over, listening. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        source = sourceOf(intent)
        // A live session is already open: a second press changes nothing, it is already listening.
        if (liveMode && LiveHost.active) { LiveHost.attached(); return }
        AssistHub.keepIfFresh()
        AssistEntry.clearNotification(this)
        g.stopSpeaking()
        stopTalkSpeech()
        cardSpoken.clear()
        core.listen(); refresh()
        beginListening()
    }

    /** The system's own "assist" and "voice command" intents carry no source: they come from a key or a gesture. */
    private fun sourceOf(i: Intent?): String =
        if (i?.action == AssistEntry.ACTION) AssistEntry.source(i.getStringExtra(AssistEntry.EXTRA_SOURCE)) else AssistEntry.SIDE_KEY

    override fun onResume() {
        super.onResume(); ChatOnScreen.assistShowing = true
        // Back from the phone's overlay page: if he allowed it, carry on with the minimise he asked for.
        if (pendingMinimise) { askingPermission = false; pendingMinimise = false; if (android.provider.Settings.canDrawOverlays(this)) minimise() }
    }
    override fun onPause() { super.onPause(); ChatOnScreen.assistShowing = false }

    override fun onStop() {
        super.onStop()
        // He went somewhere else: the sheet does not linger behind other apps. The turn carries on in the chat.
        if (!isChangingConfigurations && !askingPermission && !core.waitingForUnlock) finish()
    }

    override fun onDestroy() {
        // Leaving the sheet ends a live session, unless it was minimised to the bubble on purpose.
        if (liveMode && !LiveHost.minimised.value) LiveHost.end("")
        stopListening()
        follow?.cancel()
        stopTalkSpeech()
        if (talkMode) runCatching { speaker.release() }
        runCatching { g.stopSpeaking() }
        AssistEntry.setOpen(false)
        // A minimised session still holds the microphone: the wake word keeps out of its way.
        if (LiveHost.active) AssistEntry.setListening(true)
        AssistHub.clear()
        super.onDestroy()
    }

    private fun refresh() {
        view = core.view(rows, working)
    }

    // ---- live voice -----------------------------------------------------------------------------

    /** Joins the live session if one is running (opened again from the bubble, a second press), else starts one. */
    private fun startLive() {
        if (LiveHost.active) { LiveHost.attached(); liveView = LiveHost.view.value; return }
        LiveHost.start(this, source)
    }

    /** Shrinks the sheet to the floating bubble. Asks for "display over other apps" first, in a slide-up sheet, when it is not allowed yet. */
    private fun minimise() {
        if (!android.provider.Settings.canDrawOverlays(this)) { overlaySheet = true; return }
        LiveHost.minimise()
        finish()
    }

    private fun openOverlaySettings() {
        overlaySheet = false
        pendingMinimise = true
        askingPermission = true
        runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))) }
            .onFailure { askingPermission = false; pendingMinimise = false }
    }

    /** The live session is over. Ended on purpose (goodbye, quiet, a tap): the sheet closes. It could not start or broke: the normal sheet takes over, with the reason. */
    private fun liveEnded(why: String) {
        if (why.isBlank()) { finish(); return }
        liveMode = false
        talkMode = AssistPrefs.talk(this)
        core.listen()
        core.micFailed(getString(R.string.assist_live_stopped, why))
        refresh()
        // Conversation mode takes over after the reason has been on screen a moment: the call goes on without Gemini.
        if (talkMode) lifecycleScope.launch { kotlinx.coroutines.delay(2_500); if (!isFinishing && !isDestroyed) { core.listen(); refresh(); beginListening() } }
    }

    // ---- listening ------------------------------------------------------------------------------

    private fun beginListening() {
        if (!core.listening) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { if (liveMode) startLive() else startListening(); return }
        // The phone's own permission question cannot be drawn over the lock screen: there the keyboard takes over.
        if (locked()) { core.micFailed(getString(R.string.assist_unlock_for_mic)); refresh(); return }
        askingPermission = true
        askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startListening() {
        stopListening()
        if (!core.listening) return
        AssistEntry.setListening(true)
        dictation = SystemDictation(this, onText = { t -> core.partial(t); refresh() }, onEnd = { error ->
            dictation = null
            AssistEntry.setListening(false)
            when {
                error != null && core.words.isBlank() -> { core.micFailed(error); refresh() }
                // Conversation: a round that heard nothing counts toward the quiet end; otherwise listen again.
                talkMode && core.words.isBlank() -> { if (core.quiet()) finish() else { core.listen(); refresh(); beginListening() } }
                else -> heard(core.words)
            }
        }, oneShot = true).also { it.start() }
        refresh()
    }

    private fun stopListening() {
        dictation?.cancel(); dictation = null
        AssistEntry.setListening(false)
    }

    // ---- the turn -------------------------------------------------------------------------------

    private fun locked(): Boolean = runCatching { getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true }.getOrDefault(false)

    /** His words are final (spoken or typed): send them, or keep them until the phone is unlocked. */
    private fun heard(words: String, byKeyboard: Boolean = false) {
        // Conversation: goodbye ends the call instead of going to the agent; "stop" ends it when nothing is running.
        if (talkMode && words.isNotBlank()) {
            val name = g.settings.state.value.agentName
            if (AssistCore.isExit(words, name) || (words.trim().equals("stop", ignoreCase = true) && !working)) { endConversation(); return }
        }
        val now = core.finished(words, locked = locked(), byKeyboard = byKeyboard)
        refresh()
        if (now != null) route(now) else if (core.waitingForUnlock) unlock()
    }

    /**
     * What he said may answer something an agent is waiting on (a pairing code, a choice): then it goes to that agent by the
     * same path its card uses, and one line says where. Two that could fit: he picks, in a slide-up sheet. Anything else goes
     * to the agent as before. Approvals are never answered by words.
     */
    private fun route(words: String) {
        when (val r = AssistRouter.fromWords(words, ctx?.asks.orEmpty())) {
            is Routed.Answer -> deliverAnswer(r.ask, r.answer)
            is Routed.Which -> { choice = AskChoice(r.answer, r.candidates); pendingWords = words }
            else -> send(words)
        }
    }

    private var pendingWords = ""

    private fun deliverAnswer(ask: CtxAsk, answer: String) {
        val final = AssistRouter.finalAnswer(ask, answer)
        runCatching { g.runtime.answer(ask.id, final) }
            .onSuccess {
                val to = ask.by.ifBlank { g.settings.state.value.agentName.takeIf { it.isNotBlank() && it != "Your agent" } ?: getString(R.string.svc_your_agent) }
                core.routed(getString(R.string.assist_ctx_sent_to, to, final.take(80)))
            }
            .onFailure { send(pendingWords.ifBlank { final }) }
        pendingWords = ""
        refresh()
    }

    private fun unlock() {
        val km = getSystemService(KeyguardManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { core.unlocked()?.let { send(it) }; refresh() }
        })
    }

    private fun send(words: String) {
        val before = rows.lastOrNull()?.id ?: 0L
        val screen = AssistHub.screen.value
        val text = AssistMessage.compose(words, source, screen, typed = core.wasTyped)
        val images = listOfNotNull(screen?.screenshot?.takeIf { it.isNotBlank() && !screen.blocked })
        lifecycleScope.launch {
            runCatching { g.runtime.sendAccepted(text, images) }
                .onSuccess { core.sent(before) }
                .onFailure { e -> core.sendFailed(getString(R.string.assist_send_failed, e.message.orEmpty().ifBlank { getString(R.string.assist_link_dropped) })) }
            refresh()
        }
    }

    private fun openApp() {
        runCatching { startActivity(Intent(this, MainActivity::class.java).putExtra("open", "chat").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        finish()
    }

    // ---- conversation mode ------------------------------------------------------------------------

    /** Read text aloud: the cloud voice when one is set up, else the phone's own. [onDone] fires once when it finishes. */
    private fun speakAloud(text: String, onDone: () -> Unit) {
        val gen = ++talkGen
        talkJob?.cancel()
        val spoken = com.past9.phoneaos.voice.SpokenResult.cut(com.past9.phoneaos.voice.SpokenResult.speakable(text))
        talkJob = lifecycleScope.launch {
            val f = runCatching { com.past9.phoneaos.voice.Tts.synth(this@AssistActivity, g.settings, spoken) }.getOrNull()
            if (gen != talkGen || isFinishing || isDestroyed) return@launch
            if (f != null) com.past9.phoneaos.voice.Tts.play(f) { if (gen == talkGen) onDone() }
            else speaker.say(spoken) { if (gen == talkGen) onDone() }
        }
    }

    /** Interrupt the voice (a tap, a second press, leaving): nothing pending may start listening again on its own. */
    private fun stopTalkSpeech() {
        talkGen++; talkJob?.cancel(); talkJob = null
        com.past9.phoneaos.voice.Tts.stop(); speaker.stop()
    }

    /** The voice finished: listen again, unless the sheet is going away. */
    private fun talkContinue() {
        if (isFinishing || isDestroyed) return
        core.finishedSpeaking()
        refresh()
        beginListening()
    }

    private fun endConversation() { stopTalkSpeech(); finish() }

    /** What the voice says for a card that waits for him: the agent's own words, plus the options or the yes it needs. */
    private fun cardSpokenText(card: ChatItem, phase: AssistPhase): String {
        val m = AssistCore.meta(card)
        return if (phase == AssistPhase.APPROVAL) {
            val summary = runCatching { com.past9.phoneaos.ui.screens.Approval.of(card).summary }.getOrNull().orEmpty().ifBlank { card.text }
            "$summary. ${getString(R.string.assist_needs_your_yes)}"
        } else {
            val opts = m.optJSONArray("options")
            val options = if (opts != null) (0 until opts.length()).map { opts.optString(it) }.filter { it.isNotBlank() } else emptyList()
            if (options.isEmpty()) card.text else card.text + ". " + options.joinToString(", ")
        }
    }

    // ---- UI -------------------------------------------------------------------------------------

    @Composable
    private fun Sheet(agentName: String, onboarded: Boolean) {
        val cs = MaterialTheme.colorScheme
        val visible = remember { MutableTransitionState(false).apply { targetState = true } }
        var dragY by remember { mutableFloatStateOf(0f) }
        val screen by AssistHub.screen.collectAsState()
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.78f).dp
        fun dismiss() { closing = true; finish() }
        BackHandler { dismiss() }
        // An answer he asked for out loud is read out, once. Conversation mode reads every answer aloud and then
        // listens again; the one-request sheet reads it only when a cloud voice is set up.
        LaunchedEffect(view.answerId, view.phase) {
            val id = view.answerId
            when {
                view.phase == AssistPhase.ANSWERING && id != null && talkMode -> {
                    val answer = view.answer
                    core.startSpeaking(answer, id)
                    speakAloud(answer) { talkContinue() }
                    refresh()
                }
                view.phase == AssistPhase.ANSWERING && id != null && !core.wasTyped -> g.speak(view.answer, id)
                // Conversation: an answer with no chat row of its own (a routed note, or "Done.") is still heard.
                view.phase == AssistPhase.ANSWERING && id == null && talkMode -> {
                    val note = view.answer
                    if (note.isNotBlank()) speakAloud(note) { talkContinue() }
                    else { kotlinx.coroutines.delay(900); if (view.phase == AssistPhase.ANSWERING && view.answerId == null) talkContinue() }
                }
                view.phase == AssistPhase.ERROR && talkMode -> speakAloud(view.problem) { talkContinue() }
            }
        }
        // Conversation: a question or approval that waits for him is read aloud once. A question then listens for his
        // answer; an approval keeps the microphone closed — it is held on the screen, never answered by words.
        LaunchedEffect(view.card?.id, view.phase) {
            if (!talkMode) return@LaunchedEffect
            val card = view.card
            val phase = view.phase
            if (card == null || (phase != AssistPhase.ASKING && phase != AssistPhase.APPROVAL)) {
                if (cardSpeaking) { cardSpeaking = false; stopTalkSpeech() }
                return@LaunchedEffect
            }
            if (!cardSpoken.add(card.id)) return@LaunchedEffect
            cardSpeaking = true
            val text = cardSpokenText(card, phase)
            speakAloud(text) { cardSpeaking = false; if (phase == AssistPhase.ASKING) talkContinue() }
        }
        Box(Modifier.fillMaxSize()) {
            AnimatedVisibility(visible, enter = fadeIn(tween(160)), exit = fadeOut(tween(120))) {
                Box(Modifier.fillMaxSize().background(cs.scrim.copy(alpha = 0.32f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { dismiss() })
            }
            AnimatedVisibility(visible, modifier = Modifier.align(Alignment.BottomCenter), enter = slideInVertically(tween(240)) { it } + fadeIn(tween(100)),
                exit = slideOutVertically(tween(160)) { it }) {
                Surface(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight).offset { IntOffset(0, dragY.toInt()) }
                        .draggable(orientation = Orientation.Vertical, state = rememberDraggableState { dy -> dragY = (dragY + dy).coerceAtLeast(0f) },
                            onDragStopped = { if (dragY > 200f) dismiss() else dragY = 0f }),
                    shape = SheetShape, color = cs.surfaceContainerLow, contentColor = cs.onSurface, tonalElevation = 3.dp,
                ) {
                    Column(Modifier.navigationBarsPadding().imePadding()) {
                        if (!onboarded) NotSetUp(agentName)
                        else if (liveMode) LiveSheetBody(liveView, agentName, AssistWords.screenLine(screen, AssistPrefs.readScreen(this@AssistActivity), this@AssistActivity), LiveActions(
                            onEnd = { if (LiveHost.active) LiveHost.end("") else finish() },
                            onMute = { m -> LiveHost.mute(m) },
                            onTurn = { LiveHost.turn() },
                            onMinimise = { minimise() },
                            onOpenApp = { openApp() },
                            onAnswer = { id, option -> g.runtime.answer(id, option) },
                            onPickAsk = { k, a -> LiveHost.pickAsk(k, a) },
                            onDismissChoice = { LiveHost.dismissChoice() },
                        ))
                        else AssistSheetBody(view, agentName, AssistWords.screenLine(screen, AssistPrefs.readScreen(this@AssistActivity), this@AssistActivity), AssistActions(
                            onMic = { stopTalkSpeech(); g.stopSpeaking(); core.resetQuiet(); core.listen(); refresh(); beginListening() },
                            onEnd = if (talkMode) ::endConversation else null,
                            onDoneSpeaking = { dictation?.finish() ?: heard(core.words) },
                            onStopAgent = { g.runtime.stop() },
                            onKeyboard = { stopListening(); stopTalkSpeech(); g.stopSpeaking(); core.type(); refresh() },
                            onSendTyped = { t -> heard(t, byKeyboard = true) },
                            onOpenApp = { openApp() },
                            onAnswer = { id, option -> g.runtime.answer(id, option) },
                            onUnlock = { unlock() },
                            onPickAsk = { k, a -> choice = null; deliverAnswer(k, a) },
                            onDismissChoice = { choice = null; if (pendingWords.isNotBlank()) { val w = pendingWords; pendingWords = ""; send(w) } },
                        ), context = ctx, choice = choice)
                    }
                }
            }
            if (overlaySheet) OverlayPermissionSheet(agentName, onOpenSettings = { openOverlaySettings() }, onDismiss = { overlaySheet = false })
        }
    }

    @Composable
    private fun NotSetUp(agentName: String) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Text(stringResource(R.string.assist_finish_setup), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.assist_finish_setup_body, agentName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { openApp() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.share_open_app), style = MaterialTheme.typography.titleMedium) }
        }
    }
}
