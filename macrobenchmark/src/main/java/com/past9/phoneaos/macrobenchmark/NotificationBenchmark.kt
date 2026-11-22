package com.past9.phoneaos.macrobenchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The notification listener path: the system posts through the shade, the app's NotificationListenerService records
 * the row, de-duplicates it and (once a day) prunes. Feed it with `cmd notification post`, which needs notification
 * access to be granted; the test grants it with the shell command before measuring.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class NotificationBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun systemNotificationIntake() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(
            TraceSectionMetric("mew.notif.record", mode = TraceSectionMetric.Mode.Sum),
            MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
        ),
        iterations = 6,
        setupBlock = {
            seed("seed_screens")
            grantNotificationAccess()
            startAt("home")
            pressHome()
        },
    ) {
        postNotifications(30)
        Thread.sleep(3_000)
    }
}
