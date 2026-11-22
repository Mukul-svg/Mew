package com.past9.phoneaos.macrobenchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every process and Activity start path, including the graph (Room open, WorkManager, Node discovery) that used to be
 * hidden inside the first frame. The device must be onboarded by the seed; nothing here needs a model key.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()
    private var seeded = false

    private fun MacrobenchmarkScope.seedOnce() {
        if (!seeded) { seed("seed_onboard"); seeded = true }
    }

    /**
     * Cold starts must begin with the process dead, so the seed runs before measureRepeated and the app is then
     * force-stopped. Seeding inside setupBlock would start the process and fail Macrobenchmark's cold-start check.
     */
    private fun seedCold(action: String) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("am broadcast -a $ACTION --es action $action -n $RECEIVER")
        Thread.sleep(12_000)
        // The notification listener is system-bound; if it is enabled, force-stop immediately restarts the app.
        device.executeShellCommand("cmd notification disallow_listener $PACKAGE/com.past9.phoneaos.system.NotificationReader")
        Thread.sleep(1_000)
        device.executeShellCommand("am force-stop $PACKAGE")
        Thread.sleep(2_000)
    }

    @Test fun coldStart() {
        seedCold("seed_onboard")
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(
                StartupTimingMetric(),
                TraceSectionMetric("mew.startup.graph", mode = TraceSectionMetric.Mode.Sum),
                MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
            ),
            iterations = 10,
            startupMode = StartupMode.COLD,
            setupBlock = { pressHome() },
        ) {
            launchApp("home")
        }
    }

    @Test fun warmStart() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(
            StartupTimingMetric(),
            TraceSectionMetric("mew.startup.graph", mode = TraceSectionMetric.Mode.Sum),
            MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
        ),
        iterations = 10,
        startupMode = StartupMode.WARM,
        setupBlock = { seedOnce(); pressHome() },
    ) {
        launchApp("home")
    }

    @Test fun hotStart() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
        iterations = 10,
        startupMode = StartupMode.HOT,
        setupBlock = { seedOnce(); startAt("home"); pressHome() },
    ) {
        launchApp("home")
    }

    @Test fun coldStartDirectlyIntoChat() {
        seedCold("seed_chat")
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(
                StartupTimingMetric(),
                TraceSectionMetric("mew.startup.graph", mode = TraceSectionMetric.Mode.Sum),
                TraceSectionMetric("mew.chat.group", mode = TraceSectionMetric.Mode.Sum),
                MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
            ),
            iterations = 10,
            startupMode = StartupMode.COLD,
            setupBlock = { pressHome() },
        ) {
            launchApp("chat")
        }
    }
}
