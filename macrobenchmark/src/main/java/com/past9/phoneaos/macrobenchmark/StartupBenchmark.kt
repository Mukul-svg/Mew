package com.past9.phoneaos.macrobenchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
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

    @Test fun coldStart() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(
            StartupTimingMetric(),
            TraceSectionMetric("mew.startup.graph", mode = TraceSectionMetric.Mode.Sum),
            MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
        ),
        iterations = 10,
        startupMode = StartupMode.COLD,
        setupBlock = { seedOnce(); pressHome() },
    ) {
        launchApp("home")
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

    @Test fun coldStartDirectlyIntoChat() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(
            StartupTimingMetric(),
            TraceSectionMetric("mew.startup.graph", mode = TraceSectionMetric.Mode.Sum),
            TraceSectionMetric("mew.chat.group", mode = TraceSectionMetric.Mode.Sum),
            MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
        ),
        iterations = 10,
        startupMode = StartupMode.COLD,
        setupBlock = { if (!seeded) { seed("seed_chat"); seeded = true }; pressHome() },
    ) {
        launchApp("chat")
    }
}
