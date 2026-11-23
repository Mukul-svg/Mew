package com.past9.phoneaos

import com.past9.phoneaos.agent.ActionClaim
import com.past9.phoneaos.agent.ActionGuard
import com.past9.phoneaos.agent.ActionIntent
import com.past9.phoneaos.agent.ActionKind
import com.past9.phoneaos.agent.ActionLedger
import com.past9.phoneaos.agent.ActionState
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.data.AccountRef
import com.past9.phoneaos.data.AccountRules
import com.past9.phoneaos.tools.AppGuard
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ActionGuardTest {
    private class Ctx : ToolContext {
        override val agentLabel = "main"
        override suspend fun activity(text: String, meta: JSONObject): Long = 1
        override suspend fun updateActivity(id: Long, text: String, meta: JSONObject) {}
        override suspend fun ask(question: String, options: List<String>): String? = "Approve"
        override suspend fun notify(title: String, body: String) {}
    }

    private fun guard(
        email: Boolean = false,
        accounts: List<AccountRef> = emptyList(),
        installed: (String) -> Boolean = { false },
    ) = ActionGuard(
        context = null,
        composioEnabled = { email },
        accountsFor = { accounts },
        installed = installed,
    )

    @Test fun everyActionFamilyIsRecognised() {
        val cases = mapOf(
            "send an email to Sam" to ActionKind.EMAIL,
            "email Sam that I'm late" to ActionKind.EMAIL,
            "send a WhatsApp message to Sam" to ActionKind.MESSAGE,
            "text my mom" to ActionKind.MESSAGE,
            "call Sam" to ActionKind.CALL,
            "dial 555-1234" to ActionKind.CALL,
            "schedule a meeting with Sam" to ActionKind.CALENDAR,
            "book a table for two" to ActionKind.BOOKING,
            "buy a pair of shoes" to ActionKind.PURCHASE,
            "pay my rent" to ActionKind.PAYMENT,
            "delete the PDF" to ActionKind.FILE_CHANGE,
            "turn on bluetooth" to ActionKind.SETTINGS,
            "install WhatsApp" to ActionKind.APP_CHANGE,
        )
        cases.forEach { (text, kind) ->
            assertTrue(text, kind in ActionIntent.kinds(text))
        }
    }

    @Test fun readingQuestionsAndIdiomsAreNotActions() {
        listOf(
            "read my email", "search my inbox", "what is email", "how do I send an email",
            "call it a day", "call this function", "phone number", "pay attention",
            "buyer's guide", "weather in Durban", "what is 2+2", "tell me about Japan",
        ).forEach { assertTrue(it, ActionIntent.kinds(it).isEmpty()) }
    }

    @Test fun missingEmailBecomesAUserFacingBlocker() = runBlocking {
        val state = guard(email = false).preflight("send an email to Sam")
        assertTrue(ActionKind.EMAIL in state.kinds)
        assertTrue(ActionKind.EMAIL in state.missing)
        assertNotNull(state.blocker)
        assertTrue(state.blocker!!.contains("isn't connected"))
        assertTrue(state.notes.contains("EMAIL: NOT AVAILABLE"))
    }

    @Test fun aConnectedEmailAccountPassesPreflight() = runBlocking {
        val state = guard(email = true, accounts = listOf(AccountRef("a1", "gmail", "me@example.com"))).preflight("send an email to Sam")
        assertFalse(ActionKind.EMAIL in state.missing)
        assertNull(state.blocker)
        assertTrue(state.notes.contains("EMAIL: CONNECTED"))
    }

    @Test fun aNamedMessagingAppMustExistOrBeConnected() = runBlocking {
        val missing = guard(installed = { false }).preflight("send a WhatsApp message to Sam")
        assertTrue(ActionKind.MESSAGE in missing.missing)
        assertTrue(missing.blocker!!.contains("WhatsApp"))

        val present = guard(installed = { it == "com.whatsapp" }).preflight("send a WhatsApp message to Sam")
        assertFalse(ActionKind.MESSAGE in present.missing)
    }

    @Test fun aSuccessfulSendIsTheOnlyThingThatAllowsASentClaim() {
        val state = ActionState(kinds = setOf(ActionKind.EMAIL))
        assertNotNull(ActionClaim.correct("Email sent to Sam.", state))
        assertTrue(ActionClaim.correct("Email sent to Sam.", state)!!.contains("haven't sent"))

        state.ledger.record("apps_run", """{"slug":"GMAIL_SEND_EMAIL"}""", """{"successful":true}""", false)
        assertTrue(state.ledger.verified(ActionKind.EMAIL))
        assertNull(ActionClaim.correct("Email sent to Sam.", state))
    }

    @Test fun aFailedAttemptIsReportedAsNotConfirmed() {
        val state = ActionState(kinds = setOf(ActionKind.EMAIL))
        state.ledger.record("apps_run", """{"slug":"GMAIL_SEND_EMAIL"}""", """{"successful":false,"error":"bad key"}""", false)
        assertTrue(state.ledger.attempted(ActionKind.EMAIL))
        assertFalse(state.ledger.verified(ActionKind.EMAIL))
        val corrected = ActionClaim.correct("I sent the invoice.", state)
        assertNotNull(corrected)
        assertTrue(corrected!!.contains("hasn't been confirmed"))
    }

    @Test fun aMailDraftIsNotASentEmail() {
        val state = ActionState(kinds = setOf(ActionKind.EMAIL))
        state.ledger.record(
            "android_intent",
            """{"action":"android.intent.action.VIEW","data":"mailto:sam@example.com"}""",
            "opened a draft; nothing was sent. The user still has to press Send.",
            false,
        )
        assertTrue(state.ledger.attempted(ActionKind.EMAIL))
        assertFalse(state.ledger.verified(ActionKind.EMAIL))
        assertNotNull(ActionClaim.correct("Email sent to Sam.", state))
    }

    @Test fun aPromiseWithoutAnAccountIsReplaced() {
        val state = ActionState(
            kinds = setOf(ActionKind.EMAIL),
            missing = mapOf(ActionKind.EMAIL to "Gmail/Outlook isn't connected, so I can't send email."),
        )
        val corrected = ActionClaim.correct("I'll send that now.", state)
        assertEquals("Gmail/Outlook isn't connected, so I can't send email.", corrected)
    }

    @Test fun aBareDoneIsCorrectedForWhateverWasAsked() {
        val state = ActionState(kinds = setOf(ActionKind.BOOKING))
        val corrected = ActionClaim.correct("Done, all set.", state)
        assertNotNull(corrected)
        assertTrue(corrected!!.contains("haven't booked"))
    }

    @Test fun negatedAndDraftSentencesAreNotRewritten() {
        val state = ActionState(kinds = setOf(ActionKind.EMAIL))
        listOf(
            "The email was not sent.",
            "I can't send it without an account.",
            "I haven't sent it yet.",
            "I opened a draft for you to send.",
        ).forEach { assertNull(it, ActionClaim.correct(it, state)) }
    }

    @Test fun unrelatedSentencesAreKept() {
        val state = ActionState(
            kinds = setOf(ActionKind.EMAIL),
            missing = mapOf(ActionKind.EMAIL to "Gmail/Outlook isn't connected, so I can't send email."),
        )
        val corrected = ActionClaim.correct("The weather is nice. I'll send the email.", state)
        assertNotNull(corrected)
        assertTrue(corrected!!.contains("The weather is nice."))
        assertTrue(corrected.contains("isn't connected"))
    }

    @Test fun subscriptionModeOnlyCorrectsKnownMissingCapabilities() {
        val state = ActionState(kinds = setOf(ActionKind.EMAIL), verifyClaims = false)
        assertNull(ActionClaim.correct("Email sent to Sam.", state))
    }

    @Test fun connectedAppActionsStillRefuseAnAppWithNoAccount() = runBlocking {
        val guard = AppGuard({ AccountRules.DEFAULT }, { emptyList() }, { null }, requireAccount = true)
        val verdict = guard.check(Ctx(), listOf("GMAIL_SEND_EMAIL"), null, "{}")
        assertNotNull(verdict.refuse)
        assertTrue(verdict.refuse!!.contains("isn't connected"))
        assertTrue(verdict.refuse!!.contains("Do not say anything was sent"))
    }
}
