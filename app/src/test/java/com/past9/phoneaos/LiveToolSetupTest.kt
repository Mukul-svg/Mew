package com.past9.phoneaos

import com.past9.phoneaos.assist.LiveAssistant
import com.past9.phoneaos.browser.CallBridge
import com.past9.phoneaos.voice.LiveToolSetup
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/** The Live model switch keeps hand-off behaviour the same on 3.8 without sending its fields to older models. */
class LiveToolSetupTest {
    private fun tools() = JSONArray().put(JSONObject().put("functionDeclarations", JSONArray()
        .put(JSONObject().put("name", "hand_to_agent").put("description", "do a job"))
        .put(JSONObject().put("name", "play_media"))))

    private fun behavior(tools: JSONArray, index: Int): String? =
        tools.getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(index).optString("behavior").ifBlank { null }

    @Test fun theDefaultLiveModelIs38() {
        assertEquals("gemini-3.8-live", LiveAssistant.DEFAULT_MODEL)
        assertEquals("gemini-3.8-live", CallBridge.DEFAULT_MODEL)
    }

    @Test fun every38DeclarationIsBlockingAndTheSharedOriginalIsUntouched() {
        val original = tools()
        val sent = LiveToolSetup.forModel("models/gemini-3.8-live", original)!!
        assertEquals("BLOCKING", behavior(sent, 0))
        assertEquals("BLOCKING", behavior(sent, 1))
        assertFalse(original.getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0).has("behavior"))
    }

    @Test fun olderModelsGetTheExactSameDeclarationsBack() {
        val original = tools()
        assertSame(original, LiveToolSetup.forModel("gemini-3.1-flash-live-preview", original))
        assertSame(original, LiveToolSetup.forModel("models/gemini-2.5-flash-native-audio-preview", original))
        assertFalse(original.getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0).has("behavior"))
    }

    @Test fun noToolsStaysNull() {
        assertEquals(null, LiveToolSetup.forModel("gemini-3.8-live", null))
    }

    @Test fun anEmptyDeclarationListIsStillACopy() {
        val original = JSONArray().put(JSONObject().put("functionDeclarations", JSONArray()))
        val sent = LiveToolSetup.forModel("gemini-3.8-live", original)!!
        assertEquals(0, sent.getJSONObject(0).getJSONArray("functionDeclarations").length())
    }
}
