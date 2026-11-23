package com.past9.phoneaos.agent

import android.content.Context
import android.content.pm.PackageManager
import com.past9.phoneaos.data.AccountRef
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The kinds of action that need a real capability and a confirmed result. Email is one; this is the same idea for
 * messages, calls, calendar changes, bookings, purchases, payments, files, settings and app changes.
 */
enum class ActionKind(val label: String) {
    EMAIL("email"),
    MESSAGE("message"),
    CALL("call"),
    CALENDAR("calendar"),
    BOOKING("booking"),
    PURCHASE("purchase"),
    PAYMENT("payment"),
    FILE_CHANGE("file change"),
    SETTINGS("setting"),
    APP_CHANGE("app change"),
    GENERIC("action"),
}

/**
 * Classifies a request without a model, and recognises the words that claim an action is finished. It is deliberately
 * conservative: a false negative only means the model keeps its prompt rules, while a false positive could replace a
 * truthful sentence.
 */
object ActionIntent {
    private val howTo = Regex("(?i)^\\s*(how (do|can|should|would) (i|we)|what(?:'s| is) the best way to|is it possible to|where can i)\\b")

    private val emailSubject = Regex("(?i)\\b(email|e-mail|mail|gmail|outlook)\\b")
    private val emailWrite = Regex("(?i)\\b(send|write|draft|reply|forward|compose|delete|archive)\\b")
    private val emailVerb = Regex("(?i)\\b(email|mail)\\s+(me|him|her|them|us|\\S+@\\S+|[A-Za-z][a-z]+)\\b")
    private val emailRead = Regex("(?i)\\b(check|read|search|find|open|show|list|summar|unread|inbox)\\b")
    private val emailNotSend = Regex("(?i)\\b(email address|email app|email account|email settings|email password|email provider|email client)\\b")

    private val messageSubject = Regex("(?i)\\b(text|sms|whatsapp|telegram|signal|slack|messenger|dm|message|msg)\\b")
    private val messageAction = Regex("(?i)\\b(send|text|message|msg|reply|tell|forward|dm|ping)\\b")
    private val messageVerb = Regex("(?i)\\b(text|message|msg|dm|ping)\\s+(me|him|her|them|us|\\S+@\\S+|[a-z][a-z]+)\\b")
    private val messageRead = Regex("(?i)\\b(read|check|open|search|show|list|inbox|notification|unread|history)\\b")

    private val callRequest = Regex("(?i)\\b(?:call|phone|dial|ring)\\s+(?!it\\b|this\\b|that\\b|number\\b|the (?:function|api|method|number|police station)\\b|a (?:function|method)\\b|of\\b|center\\b|option\\b|sign\\b|back\\b)([a-z][a-z'.-]*(?:\\s+[a-z][a-z'.-]*){0,4}|\\+?\\d[\\d\\s()-]{5,})")

    private val calendarSubject = Regex("(?i)\\b(calendar|meeting|appointment|event|invite)\\b")
    private val calendarAction = Regex("(?i)\\b(add|create|schedule|set|move|reschedule|cancel|invite|book|put)\\b")

    private val booking = Regex("(?i)\\b(book|reserve)\\s+(?:me\\s+)?(?:a|an|the|my|our|\\d+)?\\s*(table|room|hotel|flight|ticket|tickets|appointment|restaurant|car|taxi|uber|tour|seat|seats|cabin|house|venue|dinner|lunch|breakfast|brunch|meal)\\b")
    private val reservation = Regex("(?i)\\b(make|confirm|cancel|change|book)\\s+(?:a\\s+)?(reservation|booking)\\b|\\b(reservation|booking)\\s+(?:for|at|on)\\b")

    private val purchase = Regex("(?i)\\b(buy|purchase|checkout|add to cart)\\b|\\border\\s+(?:a|an|the|some|my|our|\\d+)\\b")
    private val purchaseNot = Regex("(?i)\\b(history|receipt|status|confirmation|list|how (do|does|to)|why|what is|where is|track)\\b")

    private val payment = Regex("(?i)\\b(pay|payment|transfer|wire|remit|send money|top up|recharge|donate)\\b")
    private val paymentObject = Regex("(?i)\\b(bill|invoice|rent|salary|money|him|her|them|me|usd|eur|gbp|inr|zar|amount|account|card|loan|fee|tax|subscription|donation|deposit|balance)\\b|\\d")
    private val paymentNot = Regex("(?i)\\b(pay attention|pay for itself|payment history|payment method|how (do|does|to)|why|what is)\\b")

    private val fileAction = Regex("(?i)\\b(delete|remove|rename|move|copy|create|write|save|edit|overwrite|zip|unzip|upload|download|share)\\b")
    private val fileObject = Regex("(?i)\\b(file|files|folder|folders|directory|document|documents|photo|photos|video|videos|pdf|docx|xlsx|zip|backup|downloads?|storage)\\b")

    private val settingsAction = Regex("(?i)\\b(change|set|turn on|turn off|enable|disable|increase|decrease|toggle|switch)\\b")
    private val settingsObject = Regex("(?i)\\b(setting|settings|wifi|wi-fi|bluetooth|brightness|volume|airplane|flight mode|do not disturb|dnd|rotation|auto-rotate|location|hotspot|alarm|timer|night mode|dark mode|font size)\\b")

    private val appChange = Regex("(?i)\\b(install|uninstall|update|remove)\\s+(?:an?|the|my|this)?\\s*(app|application|package)\\b|\\b(install|uninstall)\\s+([a-z][\\w .-]{2,40})")

    private val genericAction = Regex("(?i)\\b(send|post|upload|publish|submit|book|buy|pay|order|delete|create|schedule|call|message|email|transfer|install|uninstall)\\b")
    private val genericRead = Regex("(?i)\\b(read|check|open|search|show|list|history|how (do|does|to)|what is|where is|why)\\b")
    private val genericNot = Regex("(?i)\\b(call it|call this|call that|call of duty|pay attention|buyer'?s guide|book club|book of|in order to|order of operations|phone number|email address|email app|weather app)\\b")

    private val emailClaim = Regex("(?i)\\b(emailed|email sent|mail sent|sent (?:the |an |your )?(?:email|mail|it|that|invoice)|has it|on its way|delivered|drafted|draft ready|i'?ll send|i will send|i'?m sending|sending it now|you'?ll get|you will get|sent to)\\b")
    private val messageClaim = Regex("(?i)\\b(texted|messaged|sent (?:a |the )?message|whatsapped|slacked|dm'?d|pinged|on its way|delivered|i'?ll text|i will text|i'?ll message|i will message|i'?m sending)\\b")
    private val callClaim = Regex("(?i)\\b(called|phoned|dialed|dialled|rang|i'?ll call|i will call|i'?m calling|calling now)\\b")
    private val calendarClaim = Regex("(?i)\\b(scheduled|added to (?:your|the) calendar|calendar invite|invited|rescheduled|i'?ll schedule|i will schedule|put it in your calendar)\\b")
    private val bookingClaim = Regex("(?i)\\b(booked|reserved|reservation (?:is )?(?:made|confirmed)|booking (?:is )?confirmed|i'?ll book|i will book)\\b")
    private val purchaseClaim = Regex("(?i)\\b(ordered|bought|purchased|checkout (?:is )?complete|i'?ll order|i will order|i'?ll buy|i will buy)\\b")
    private val paymentClaim = Regex("(?i)\\b(paid|payment (?:was )?(?:sent|made)|transferred|sent (?:the )?money|i'?ll pay|i will pay|i'?ll transfer|i will transfer)\\b")
    private val fileClaim = Regex("(?i)\\b(deleted|renamed|moved|copied|saved|created|updated|overwritten|i'?ll delete|i will delete|i'?ll save|i will save)\\b")
    private val settingsClaim = Regex("(?i)\\b(changed|(?<!all )set|turned on|turned off|enabled|disabled|i'?ll (?:change|set|turn)|i will (?:change|set|turn))\\b")
    private val appClaim = Regex("(?i)\\b(installed|uninstalled|updated|removed|i'?ll install|i will install)\\b")
    private val genericClaim = Regex("(?i)\\b(done|completed|finished|confirmed|successfully|all set|went through)\\b")
    private val promise = Regex("(?i)\\b(i'?ll|i will|i'?m going to|let me|i'?m about to)\\b")
    private val negation = Regex("(?i)\\b(not|never|can'?t|cannot|won'?t|unable|nothing|no result|if|once|until|try|attempt|draft)\\b|n't\\b")

    fun kinds(text: String): Set<ActionKind> {
        val raw = text.trim()
        if (raw.startsWith("[") || howTo.containsMatchIn(raw)) return emptySet()
        val t = raw.lowercase(Locale.ROOT)
        val out = linkedSetOf<ActionKind>()
        if (emailSubject.containsMatchIn(t) && (emailWrite.containsMatchIn(t) || emailVerb.containsMatchIn(t)) && !emailNotSend.containsMatchIn(t) &&
            (!emailRead.containsMatchIn(t) || Regex("(?i)\\b(send|write|draft|reply|forward|compose)\\b").containsMatchIn(t))) out += ActionKind.EMAIL
        if (messageSubject.containsMatchIn(t) && (messageVerb.containsMatchIn(t) || Regex("(?i)\\b(send|reply|forward|tell)\\b").containsMatchIn(t)) && !messageRead.containsMatchIn(t)) out += ActionKind.MESSAGE
        if (callRequest.containsMatchIn(t)) out += ActionKind.CALL
        if (calendarSubject.containsMatchIn(t) && calendarAction.containsMatchIn(t)) out += ActionKind.CALENDAR
        if (booking.containsMatchIn(t) || reservation.containsMatchIn(t)) out += ActionKind.BOOKING
        if (purchase.containsMatchIn(t) && !purchaseNot.containsMatchIn(t)) out += ActionKind.PURCHASE
        if (payment.containsMatchIn(t) && paymentObject.containsMatchIn(t) && !paymentNot.containsMatchIn(t)) out += ActionKind.PAYMENT
        if (fileAction.containsMatchIn(t) && fileObject.containsMatchIn(t)) out += ActionKind.FILE_CHANGE
        if (settingsAction.containsMatchIn(t) && settingsObject.containsMatchIn(t)) out += ActionKind.SETTINGS
        if (appChange.containsMatchIn(t)) out += ActionKind.APP_CHANGE
        if (out.isEmpty() && genericAction.containsMatchIn(t) && !genericRead.containsMatchIn(t) && !genericNot.containsMatchIn(t)) out += ActionKind.GENERIC
        return out
    }

    fun claims(sentence: String): Set<ActionKind> {
        val out = linkedSetOf<ActionKind>()
        if (emailClaim.containsMatchIn(sentence)) out += ActionKind.EMAIL
        if (messageClaim.containsMatchIn(sentence)) out += ActionKind.MESSAGE
        if (callClaim.containsMatchIn(sentence)) out += ActionKind.CALL
        if (calendarClaim.containsMatchIn(sentence)) out += ActionKind.CALENDAR
        if (bookingClaim.containsMatchIn(sentence)) out += ActionKind.BOOKING
        if (purchaseClaim.containsMatchIn(sentence)) out += ActionKind.PURCHASE
        if (paymentClaim.containsMatchIn(sentence)) out += ActionKind.PAYMENT
        if (fileClaim.containsMatchIn(sentence)) out += ActionKind.FILE_CHANGE
        if (settingsClaim.containsMatchIn(sentence)) out += ActionKind.SETTINGS
        if (appClaim.containsMatchIn(sentence)) out += ActionKind.APP_CHANGE
        if (genericClaim.containsMatchIn(sentence)) out += ActionKind.GENERIC
        return out
    }

    fun isPromise(sentence: String): Boolean = promise.containsMatchIn(sentence)

    /** "Not sent", "can't send", "I opened a draft": truthful hedges, not claims. */
    fun isNegated(sentence: String): Boolean = negation.containsMatchIn(sentence)

    /** "Done." / "All set." with no object: it claims whatever action the user asked for. */
    fun isBareDone(sentence: String): Boolean = genericClaim.containsMatchIn(sentence) && !emailClaim.containsMatchIn(sentence) &&
        !messageClaim.containsMatchIn(sentence) && !callClaim.containsMatchIn(sentence) && !calendarClaim.containsMatchIn(sentence) &&
        !bookingClaim.containsMatchIn(sentence) && !purchaseClaim.containsMatchIn(sentence) && !paymentClaim.containsMatchIn(sentence) &&
        !fileClaim.containsMatchIn(sentence) && !settingsClaim.containsMatchIn(sentence) && !appClaim.containsMatchIn(sentence)
}

/** What tools have actually done this turn: attempted is not the same as verified. */
class ActionLedger {
    private val verified = ConcurrentHashMap.newKeySet<ActionKind>()
    private val attempted = ConcurrentHashMap.newKeySet<ActionKind>()

    fun record(tool: String, input: String, result: String, isError: Boolean) {
        val kinds = touched(tool, input)
        if (kinds.isEmpty()) return
        attempted += kinds
        if (!isError && succeeded(tool, input, result)) verified += kinds
    }

    /** A harness tool event has a name but no result: it is attempted, never verified. */
    fun recordExternal(tool: String, detail: String) {
        val kinds = touched(tool, detail)
        if (kinds.isNotEmpty()) attempted += kinds
    }

    fun verified(kind: ActionKind): Boolean = kind in verified
    fun attempted(kind: ActionKind): Boolean = kind in attempted

    private fun touched(tool: String, input: String): Set<ActionKind> {
        val t = tool.lowercase(Locale.ROOT)
        val i = input.lowercase(Locale.ROOT)
        val out = linkedSetOf<ActionKind>()
        val appOrIntent = t.contains("apps_run") || t.contains("composio") || t.contains("android_intent") || t.contains("browser") || t.contains("machine") || t.contains("run_code") || t.contains("screen")
        // Tool slugs use underscores (GMAIL_SEND_EMAIL), so a word-boundary check is not enough.
        val write = Regex("(?i)(send|reply|draft|forward|delete|archive|post|upload|create|update|move|add|book|reserve|order|buy|pay|transfer|call|submit|publish|install|uninstall|set|change)").containsMatchIn(i) ||
            i.contains("mailto") || i.contains("sms:") || i.contains("smsto:") || i.contains("tel:")
        if (appOrIntent && write) {
            val mail = i.contains("gmail") || i.contains("outlook") || i.contains("mailto") || i.contains("send_email") || i.contains("\"email\"") || i.contains("e-mail") ||
                i.contains("com.google.android.gm") || i.contains("com.microsoft.office.outlook") || i.contains("com.android.email")
            if (mail) out += ActionKind.EMAIL
            val msg = i.contains("whatsapp") || i.contains("telegram") || i.contains("signal") || i.contains("slack") || i.contains("messenger") || i.contains("sms:") || i.contains("smsto:") || i.contains("send_message")
            if (msg) out += ActionKind.MESSAGE
            if (i.contains("action_call") || i.contains("tel:") || i.contains("dial")) out += ActionKind.CALL
            if (i.contains("calendar") || i.contains("event") || i.contains("meeting")) out += ActionKind.CALENDAR
            if (i.contains("book") || i.contains("reserve")) out += ActionKind.BOOKING
            if (i.contains("order") || i.contains("buy") || i.contains("checkout") || i.contains("cart")) out += ActionKind.PURCHASE
            if (i.contains("pay") || i.contains("transfer") || i.contains("send money")) out += ActionKind.PAYMENT
            if (i.contains("file") || i.contains("folder") || i.contains("document") || i.contains("delete") || i.contains("rename")) out += ActionKind.FILE_CHANGE
            if (i.contains("setting") || i.contains("wifi") || i.contains("bluetooth") || i.contains("volume") || i.contains("brightness")) out += ActionKind.SETTINGS
            if (i.contains("install") || i.contains("uninstall")) out += ActionKind.APP_CHANGE
        }
        if (t.startsWith("files_") && write) out += ActionKind.FILE_CHANGE
        if (t.contains("settings_") || t.contains("alarm_")) out += ActionKind.SETTINGS
        if (t.contains("calendar_")) out += ActionKind.CALENDAR
        if (out.isEmpty() && appOrIntent && write) out += ActionKind.GENERIC
        return out
    }

    private fun succeeded(tool: String, input: String, result: String): Boolean {
        val r = result.lowercase(Locale.ROOT)
        val i = input.lowercase(Locale.ROOT)
        if (r.contains("not sent") || r.contains("nothing was sent") || r.contains("\"failed\":true") || r.contains("\"failed\": true")) return false
        // A structured Composio success can still carry an "error": null field; trust the explicit success flag.
        if ((tool.contains("apps_run") || tool.contains("composio")) &&
            (r.contains("\"successful\":true") || r.contains("\"successful\": true") || r.contains("\"success\":true") || r.contains("\"success\": true"))) return true
        if (Regex("(?i)\\b(failed|failure|error|couldn'?t|could not|can'?t|cannot|not allowed|refused|not connected|isn'?t connected|nothing was|not sent|did not run|timed out|no account|unknown tool)\\b").containsMatchIn(r)) return false
        if (r.contains("opened a draft") || r.contains("nothing was sent")) return false
        if (tool.equals("android_intent", true)) {
            if (!r.startsWith("sent")) return false
            // A dialer or a mail composer is not a completed call or send.
            if (i.contains("action_dial")) return false
            if (i.contains("action_send")) return false
            if (i.contains("tel:") && !i.contains("action_call")) return false
            if (i.contains("mailto") || i.contains("smsto") || i.contains("sms:")) return false
            return true
        }
        if (tool.contains("browser") || tool.contains("screen")) {
            return Regex("(?i)\\b(confirmed|booked|ordered|paid|sent|created|updated|deleted|saved)\\b").containsMatchIn(r)
        }
        if (tool.contains("apps_run") || tool.contains("composio")) {
            return r.contains("\"successful\":true") || r.contains("\"successful\": true") ||
                r.contains("\"success\":true") || r.contains("\"success\": true") ||
                Regex("(?i)^\\s*(sent|done|created|updated|deleted|booked|ordered|paid|saved)").containsMatchIn(r)
        }
        if (tool.startsWith("files_")) return !r.contains("failed")
        if (tool.contains("run_code") || tool.contains("machine")) {
            return Regex("(?i)\\b(sent|booked|ordered|paid|created|updated|deleted|saved|done|success)\\b").containsMatchIn(r)
        }
        return Regex("(?i)\\b(sent|booked|ordered|paid|created|updated|deleted|saved|done|success)\\b").containsMatchIn(r)
    }
}

/** Everything a turn needs to keep its claims truthful. */
data class ActionState(
    val kinds: Set<ActionKind> = emptySet(),
    val missing: Map<ActionKind, String> = emptyMap(),
    val notes: String = "",
    val verifyClaims: Boolean = true,
    val ledger: ActionLedger = ActionLedger(),
) {
    /** A user-facing line when the action definitely cannot run, or null when it may be possible. */
    val blocker: String? get() = kinds.firstNotNullOfOrNull { missing[it] }

    fun withVerifyClaims(value: Boolean): ActionState = copy(verifyClaims = value)
}

/**
 * Capability preflight before the model runs, plus the final claim check before a reply is stored. The preflight
 * checks only what can be checked cheaply and definitely (a connected email account, a named messaging app, telephony,
 * a calendar app). High-risk actions that may be possible in a browser are not blocked; their claims are simply held
 * until a tool confirms them.
 */
class ActionGuard(
    private val context: Context?,
    private val composioEnabled: () -> Boolean = { false },
    private val accountsFor: suspend (List<String>) -> List<AccountRef> = { emptyList() },
    private val installed: (String) -> Boolean = { pkg ->
        val pm = context?.packageManager
        if (pm == null) false else runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
    },
    private val lookupTimeoutMs: Long = 2_500L,
) {
    suspend fun preflight(text: String): ActionState {
        val kinds = ActionIntent.kinds(text)
        if (kinds.isEmpty()) return ActionState()
        val missing = linkedMapOf<ActionKind, String>()
        val notes = mutableListOf<String>()
        for (kind in kinds) {
            when (kind) {
                ActionKind.EMAIL -> {
                    val accounts = try {
                        if (!composioEnabled()) emptyList() else kotlinx.coroutines.withTimeoutOrNull(lookupTimeoutMs) { accountsFor(listOf("gmail", "outlook")) }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    when {
                        accounts == null -> notes += note("EMAIL: UNCONFIRMED", "Couldn't check the email account. Before promising, call apps_connected (or COMPOSIO_MANAGE_CONNECTIONS list). Never claim sent without a tool confirmation.")
                        accounts.isNullOrEmpty() -> {
                            missing[ActionKind.EMAIL] = "Gmail/Outlook isn't connected, so I can't send email. You can connect it in Connections, or I can open a draft for you to send yourself."
                            notes += note("EMAIL: NOT AVAILABLE", "No Gmail or Outlook account is connected. Do not promise or claim a send. Say so plainly, offer Connections, and you may open a draft for the user to send themselves.")
                        }
                        else -> notes += note("EMAIL: CONNECTED", "Use the connected app's send action with the right account. Ask for approval with the exact content. Never say sent until the tool confirms it.")
                    }
                }
                ActionKind.MESSAGE -> {
                    val app = namedMessageApp(text.lowercase(Locale.ROOT))
                    if (app != null) {
                        val hasApp = installed(app.pkg)
                        val hasAccount = try {
                            if (!composioEnabled()) false else kotlinx.coroutines.withTimeoutOrNull(lookupTimeoutMs) { accountsFor(listOf(app.slug)) }?.isNotEmpty() == true
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            false
                        }
                        if (!hasApp && !hasAccount) {
                            missing[ActionKind.MESSAGE] = "${app.name} isn't installed or connected, so I can't send that message. You can connect ${app.name} in Connections, or I can use SMS instead."
                            notes += note("MESSAGE: ${app.name.uppercase(Locale.ROOT)} NOT AVAILABLE", "${app.name} is not installed and no ${app.name} account is connected. Do not promise a message there. Offer SMS or Connections.")
                        } else {
                            notes += note("MESSAGE: ${app.name.uppercase(Locale.ROOT)}", "Send through ${app.name}. Ask for approval with the exact words. Never say sent until the tool confirms it.")
                        }
                    } else {
                        notes += note("MESSAGE", "SMS or a connected messaging app. Ask for approval with the exact words. Never say sent until the tool confirms it.")
                    }
                }
                ActionKind.CALL -> {
                    val telephony = context?.packageManager?.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) ?: true
                    if (!telephony) {
                        missing[ActionKind.CALL] = "This phone can't place calls."
                        notes += note("CALL: NOT AVAILABLE", "This device has no telephony. Do not promise a call.")
                    } else {
                        notes += note("CALL", "The dialer can be opened; only a call tool result confirms a call was placed. Never say called until then.")
                    }
                }
                ActionKind.CALENDAR -> notes += note("CALENDAR", "Check for a connected calendar or app. Ask for approval with the exact event, time and attendees. Never say added until the tool confirms it.")
                ActionKind.BOOKING -> notes += note("BOOKING", "This commits the user. Ask for approval with the exact booking and price first. Only say booked or confirmed after a tool result confirms it; a page click is not a confirmation.")
                ActionKind.PURCHASE -> notes += note("PURCHASE", "This spends money. Ask for approval with the exact item, price and seller first. Only say ordered after a tool result confirms it; a cart update is not an order.")
                ActionKind.PAYMENT -> notes += note("PAYMENT", "This moves money. Ask for approval with the exact amount and recipient first. Only say paid after a tool result confirms it.")
                ActionKind.FILE_CHANGE -> notes += note("FILE", "Ask before deleting or overwriting. Only say changed after the file tool confirms it.")
                ActionKind.SETTINGS -> notes += note("SETTING", "Only say changed after the settings tool confirms it.")
                ActionKind.APP_CHANGE -> notes += note("APP", "Only say installed or removed after the install tool confirms it. Android may require the user's own tap.")
                ActionKind.GENERIC -> notes += note("ACTION", "Ask for approval before anything that sends, spends, deletes or changes. Never claim it is done until a tool confirms it.")
            }
        }
        return ActionState(kinds, missing, notes.joinToString("\n"), verifyClaims = true)
    }

    private fun note(title: String, body: String) = "[ACTION CHECK — $title]\n$body"

    private data class MessageApp(val name: String, val pkg: String, val slug: String)

    private fun namedMessageApp(t: String): MessageApp? = when {
        t.contains("whatsapp") -> MessageApp("WhatsApp", "com.whatsapp", "whatsapp")
        t.contains("telegram") -> MessageApp("Telegram", "org.telegram.messenger", "telegram")
        t.contains("signal") -> MessageApp("Signal", "org.thoughtcrime.securesms", "signal")
        t.contains("slack") -> MessageApp("Slack", "com.Slack", "slack")
        t.contains("messenger") -> MessageApp("Messenger", "com.facebook.orca", "facebook")
        else -> null
    }
}

/**
 * The last line of defence before a reply is stored. A sentence that claims an action is finished is kept only when
 * the turn's ledger has a verified success for that kind; a missing capability or an unconfirmed attempt replaces it
 * with a truthful line. Sentences that are not claims are left untouched.
 */
object ActionClaim {
    fun correct(text: String, state: ActionState): String? {
        if (state.kinds.isEmpty() || text.isBlank()) return null
        val sentences = text.split(Regex("(?<=[.!?])\\s+|\\n")).filter { it.isNotBlank() }
        val out = mutableListOf<String>()
        var changed = false
        for (sentence in sentences) {
            if (ActionIntent.isNegated(sentence)) {
                out += sentence
                continue
            }
            val claims = ActionIntent.claims(sentence)
            var relevant = claims.filter { it in state.kinds }
            // "Done." or "All set." with no object claims whatever the user asked for.
            if (relevant.isEmpty() && ActionIntent.isBareDone(sentence)) relevant = state.kinds.toList()
            if (relevant.isEmpty()) {
                out += sentence
                continue
            }
            val kind = relevant.first()
            val replacement = when {
                kind in state.missing -> state.missing[kind]
                !state.verifyClaims -> null
                state.ledger.verified(kind) -> null
                // A generic request can be done by any tool, and we cannot map every such tool to a result. Specific
                // families (email, messages, calls, bookings, payments, files, settings, apps) are verified above.
                kind == ActionKind.GENERIC -> null
                ActionIntent.isPromise(sentence) -> "I'm working on that; nothing is confirmed yet."
                state.ledger.attempted(kind) -> unconfirmed(kind)
                else -> notDone(kind)
            }
            if (replacement == null) out += sentence else { out += replacement; changed = true }
        }
        return if (!changed) null else out.joinToString(" ")
    }

    private fun unconfirmed(kind: ActionKind): String = when (kind) {
        ActionKind.EMAIL -> "The email hasn't been confirmed as sent."
        ActionKind.MESSAGE -> "The message hasn't been confirmed as sent."
        ActionKind.CALL -> "I can't confirm the call went through."
        ActionKind.CALENDAR -> "The calendar change hasn't been confirmed."
        ActionKind.BOOKING -> "The booking hasn't been confirmed."
        ActionKind.PURCHASE -> "The order hasn't been confirmed."
        ActionKind.PAYMENT -> "The payment hasn't been confirmed."
        ActionKind.FILE_CHANGE -> "The file change hasn't been confirmed."
        ActionKind.SETTINGS -> "The setting change hasn't been confirmed."
        ActionKind.APP_CHANGE -> "The app change hasn't been confirmed."
        ActionKind.GENERIC -> "That hasn't been confirmed yet."
    }

    private fun notDone(kind: ActionKind): String = when (kind) {
        ActionKind.EMAIL -> "I haven't sent the email yet."
        ActionKind.MESSAGE -> "I haven't sent the message yet."
        ActionKind.CALL -> "I haven't placed the call yet."
        ActionKind.CALENDAR -> "I haven't changed the calendar yet."
        ActionKind.BOOKING -> "I haven't booked it yet."
        ActionKind.PURCHASE -> "I haven't ordered it yet."
        ActionKind.PAYMENT -> "I haven't paid it yet."
        ActionKind.FILE_CHANGE -> "I haven't changed the file yet."
        ActionKind.SETTINGS -> "I haven't changed the setting yet."
        ActionKind.APP_CHANGE -> "I haven't changed the app yet."
        ActionKind.GENERIC -> "I haven't done that yet."
    }
}
