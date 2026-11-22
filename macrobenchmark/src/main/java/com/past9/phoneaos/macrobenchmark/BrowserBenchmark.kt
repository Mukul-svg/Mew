package com.past9.phoneaos.macrobenchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real WebView browser service. It loads a deterministic local page (no network, no account), then scrolls and
 * reloads it while the page is attached to the on-screen Surface. This is the same engine the agent uses through
 * browser tools; a linked computer's browser is not on this phone.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class BrowserBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()
    private var seeded = false

    @Test fun loadAndScroll() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            TraceSectionMetric("mew.browser.goto", mode = TraceSectionMetric.Mode.Sum),
            MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
        ),
        iterations = 6,
        setupBlock = {
            if (!seeded) { seed("seed_screens"); seeded = true }
            startAt("browser")
            trigger("browser", 10_000)
            device.waitForIdle(1_000)
        },
    ) {
        trigger("browser", 6_000)
        swipeFeed(4)
        swipeBack(3)
    }
}
