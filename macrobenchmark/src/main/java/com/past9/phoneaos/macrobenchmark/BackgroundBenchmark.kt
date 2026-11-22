package com.past9.phoneaos.macrobenchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Background work, measured in the real process: WorkManager routines and their model call, the helper watchdog,
 * Node, system clocks/alarms, and the notification table. The benchmark-only receiver triggers one path at a time;
 * the trace sections are the ones the app writes in production code.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class BackgroundBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()
    private var seeded = false

    private fun MacrobenchmarkScope.seedEverything() {
        if (!seeded) { seed("seed"); seeded = true }
    }

    @Test fun rescheduleEveryClock() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(
            TraceSectionMetric("mew.bench.reschedule", mode = TraceSectionMetric.Mode.Sum),
            MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
        ),
        iterations = 6,
        setupBlock = { seedEverything(); pressHome() },
    ) {
        trigger("reschedule", 8_000)
    }

    @Test fun routineWorker() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(
            TraceSectionMetric("mew.bg.routine", mode = TraceSectionMetric.Mode.Sum),
            TraceSectionMetric("mew.bench.run_routine", mode = TraceSectionMetric.Mode.Sum),
            MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
        ),
        iterations = 5,
        setupBlock = { seedEverything(); pressHome() },
    ) {
        // WorkManager starts the worker asynchronously; the trace metric only counts the worker's own section.
        trigger("run_routine", 12_000)
    }

    @Test fun helperWatchdog() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(
            TraceSectionMetric("mew.bench.watchdog", mode = TraceSectionMetric.Mode.Sum),
            MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
        ),
        iterations = 6,
        setupBlock = { seedEverything(); pressHome() },
    ) {
        trigger("watchdog", 6_000)
    }

    @Test fun nodeRuntime() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(
            TraceSectionMetric("mew.node.run", mode = TraceSectionMetric.Mode.Sum),
            TraceSectionMetric("mew.bench.node", mode = TraceSectionMetric.Mode.Sum),
            MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
        ),
        iterations = 5,
        setupBlock = { seedEverything(); pressHome() },
    ) {
        trigger("node", 15_000)
    }

    @Test fun notificationWrites() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(
            TraceSectionMetric("mew.bench.notifications", mode = TraceSectionMetric.Mode.Sum),
            TraceSectionMetric("mew.bench.prune", mode = TraceSectionMetric.Mode.Sum),
            MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
        ),
        iterations = 6,
        setupBlock = { seedEverything(); pressHome() },
    ) {
        trigger("notifications", 8_000)
        trigger("prune", 4_000)
    }

    /** A frame check while the app is in the background: nothing should be drawing, and the process must stay small. */
    @Test fun idleBackgroundProcess() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
        iterations = 5,
        setupBlock = { seedEverything(); startAt("home"); pressHome() },
    ) {
        device.waitForIdle(2_000)
    }
}
