package com.past9.phoneaos

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.past9.phoneaos.cards.Card
import com.past9.phoneaos.cards.CardScripts
import com.past9.phoneaos.triggers.Watchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a phone or an emulator, not on the build server. Proves the bundled Node path that
 * watchers and card scripts use, with no model call:
 *
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.past9.phoneaos.RuntimeProbeDeviceTest
 */
@RunWith(AndroidJUnit4::class)
class RuntimeProbeDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun aWatcherRunsOnBundledNodeAndCarriesState() = runBlocking {
        val script = """
            const previous = JSON.parse(process.env.WATCH_STATE || "{}");
            const n = (previous.n || 0) + 1;
            console.log(JSON.stringify({ fire: false, message: "watcher ok " + n, state: { n } }));
        """.trimIndent()

        val first = Watchers.execute(context, script, "{}")
        assertTrue(first.raw, first.ok)
        assertFalse(first.raw, first.fire)
        assertEquals("watcher ok 1", first.message)
        assertEquals(1, JSONObject(first.state).optInt("n"))

        val second = Watchers.execute(context, script, first.state)
        assertTrue(second.raw, second.ok)
        assertEquals("watcher ok 2", second.message)
        assertEquals(2, JSONObject(second.state).optInt("n"))
    }

    @Test fun aCardScriptReturnsItsNewData() = runBlocking {
        val card = Card(id = "runtime-probe", title = "Runtime probe", html = "")
        val result = CardScripts.run(context, card, script = """
            const n = JSON.parse(process.env.CARD_DATA || "null");
            console.log(JSON.stringify({ data: { headline: "runtime ok", previous: n } }));
        """.trimIndent())

        assertTrue(result.log, result.ok)
        val data = JSONObject(result.data!!)
        assertEquals("runtime ok", data.optString("headline"))
        assertTrue(data.has("previous"))
    }
}
