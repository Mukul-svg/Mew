package com.past9.phoneaos.macrobenchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The chat is the app's hottest screen: every turn is grouped, every helper step and every call row is folded here.
 * These tests cover a long thread, the OpenUI card inside it, and the live-call block after a hand-off (the 0.16.1 fix).
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class ChatBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()
    private var seeded = false

    private fun MacrobenchmarkScope.seedChat() {
        if (!seeded) { seed("seed_chat"); seeded = true }
    }

    private val metrics = listOf(
        FrameTimingMetric(),
        TraceSectionMetric("mew.chat.group", mode = TraceSectionMetric.Mode.Sum),
        MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
    )

    @Test fun scrollLongThread() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = metrics,
        iterations = 8,
        setupBlock = {
            seedChat()
            startAt("chat")
            device.wait(Until.hasObject(By.textContains("Anything else?")), 15_000)
            swipeFeed(10)
        },
    ) {
        swipeFeed(8)
        swipeBack(8)
    }

    @Test fun scrollThroughOpenUiCard() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = metrics,
        iterations = 8,
        setupBlock = {
            seedChat()
            startAt("chat")
            for (i in 1..14) {
                if (device.hasObject(By.textContains("Available balance"))) break
                device.swipe(device.displayWidth / 2, (device.displayHeight * 0.75f).toInt(), device.displayWidth / 2, (device.displayHeight * 0.28f).toInt(), 16)
                device.waitForIdle(200)
            }
            check(device.hasObject(By.textContains("Available balance"))) { "OpenUI money card not in the seeded chat" }        },
    ) {
        swipeFeed(4)
        swipeBack(4)
    }

    @Test fun expandTheLiveCallBlock() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = metrics,
        iterations = 6,
        setupBlock = {
            seedChat()
            startAt("chat")
            device.wait(Until.hasObject(By.textContains("Live voice")), 15_000)
            check(clickText("Live voice")) { "live call row not found" }
            device.waitForIdle()
        },
    ) {
        swipeFeed(3)
        swipeBack(3)
    }

    @Test fun questionAndApprovalCards() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = metrics,
        iterations = 6,
        setupBlock = {
            seedChat()
            startAt("chat")
            for (i in 1..8) {
                if (device.hasObject(By.textContains("Which account"))) break
                device.swipe(device.displayWidth / 2, (device.displayHeight * 0.7f).toInt(), device.displayWidth / 2, (device.displayHeight * 0.3f).toInt(), 16)
            }
            check(device.hasObject(By.textContains("Which account"))) { "question card not found" }
        },
    ) {
        swipeFeed(2)
        swipeBack(2)
    }
}
