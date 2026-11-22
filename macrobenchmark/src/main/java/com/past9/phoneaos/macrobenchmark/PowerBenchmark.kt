package com.past9.phoneaos.macrobenchmark

import android.os.Build
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.PowerMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hardware power. AndroidX only supports this on a physical Pixel 6 or newer; on any other device the test is skipped
 * instead of reporting a misleading number. Run it on battery, unplugged, with the screen at a fixed brightness.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class PowerBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()
    private var seeded = false

    @Test fun chatPower() {
        Assume.assumeTrue("PowerMetric needs physical Pixel 6+", Build.MODEL.startsWith("Pixel"))
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(PowerMetric(PowerMetric.Type.Power())),
            iterations = 3,
            setupBlock = {
                if (!seeded) { seed("seed_chat"); seeded = true }
                startAt("chat")
            },
        ) {
            swipeFeed(8)
            swipeBack(8)
        }
    }

    @Test fun browserPower() {
        Assume.assumeTrue("PowerMetric needs physical Pixel 6+", Build.MODEL.startsWith("Pixel"))
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(PowerMetric(PowerMetric.Type.Power())),
            iterations = 3,
            setupBlock = {
                if (!seeded) { seed("seed_screens"); seeded = true }
                startAt("browser")
                trigger("browser", 10_000)
            },
        ) {
            trigger("browser", 6_000)
            swipeFeed(6)
            swipeBack(6)
        }
    }
}
