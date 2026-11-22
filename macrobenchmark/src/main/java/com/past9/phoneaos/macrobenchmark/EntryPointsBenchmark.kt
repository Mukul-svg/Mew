package com.past9.phoneaos.macrobenchmark

import android.content.Intent
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's other entry points: the system assist sheet, the share sheet, and the boot/rearm path. They start their
 * own Activity or receiver, so they have their own startup cost outside MainActivity's navigation graph.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class EntryPointsBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    private fun MacrobenchmarkScope.seed() {
        // The receiver is idempotent; one seed before the first iteration is enough.
        if (!seeded) { seed("seed"); seeded = true }
    }
    private var seeded = false

    @Test fun assistSheet() {
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(StartupTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
            iterations = 6,
            startupMode = androidx.benchmark.macro.StartupMode.COLD,
            setupBlock = {
                seed()
                device.executeShellCommand("pm grant $PACKAGE android.permission.RECORD_AUDIO")
                pressHome()
            },
        ) {
            startActivityAndWait(Intent(Intent.ACTION_ASSIST).apply {
                setClassName(PACKAGE, "$PACKAGE.assist.AssistActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    @Test fun shareSheet() {
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(StartupTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
            iterations = 6,
            startupMode = androidx.benchmark.macro.StartupMode.COLD,
            setupBlock = { seed(); pressHome() },
        ) {
            startActivityAndWait(Intent(Intent.ACTION_SEND).apply {
                setClassName(PACKAGE, "$PACKAGE.share.ShareActivity")
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "A note shared into Mew for the benchmark.")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    @Test fun bootRearm() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(
            androidx.benchmark.macro.TraceSectionMetric("mew.bench.rearm", mode = androidx.benchmark.macro.TraceSectionMetric.Mode.Sum),
            MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
        ),
        iterations = 6,
        setupBlock = { seed(); pressHome() },
    ) {
        trigger("rearm", 8_000)
    }
}
