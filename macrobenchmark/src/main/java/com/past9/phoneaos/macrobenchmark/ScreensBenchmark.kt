package com.past9.phoneaos.macrobenchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.net.Uri
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every top-level screen the app offers, opened by its real navigation route. Each iteration renders the screen from a
 * warm process and scrolls it, so the frame timing includes composition, the first data read and the list's first layout.
 * Chat is covered in depth by [ChatBenchmark]; the browser by [BrowserBenchmark].
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class ScreensBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()
    private var seeded = false

    private fun MacrobenchmarkScope.seedScreens() {
        if (!seeded) { seed("seed_screens"); seeded = true }
    }

    private fun screen(route: String, scroll: Boolean = true, frames: Boolean = true) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = buildList {
            if (frames) add(FrameTimingMetric())
            add(MemoryUsageMetric(MemoryUsageMetric.Mode.Max))
        },
        iterations = 6,
        setupBlock = {
            seedScreens()
            startAt(route)
            device.waitForIdle(1_000)
        },
    ) {
        if (scroll) { swipeFeed(3); swipeBack(2) }
    }

    @Test fun home() = screen("home")
    @Test fun profile() = screen("profile")
    @Test fun tasks() = screen("tasks")
    @Test fun people() = screen("people")
    @Test fun person() = screen("person/1")
    @Test fun memory() = screen("memory")
    @Test fun memoryPage() = screen("page/1", frames = false)
    @Test fun routines() = screen("routines")
    @Test fun connections() = screen("connections")
    @Test fun settings() = screen("settings")
    @Test fun notifications() = screen("notifications")
    @Test fun recordings() = screen("recordings?box=main", frames = false)
    @Test fun report() = screen("report?path=" + Uri.encode("/data/user/0/com.past9.phoneaos/files/benchmark-report.md"))
    @Test fun style() = screen("style")
    @Test fun miniApps() = screen("miniapps", frames = false)
    @Test fun miniApp() = screen("miniapp/bench", frames = false)
    @Test fun linkedDevices() = screen("linked")
    @Test fun keySetup() = screen("key", scroll = false, frames = false)
    @Test fun callScreen() = screen("call", scroll = false, frames = false)
    @Test fun projectChat() = screen("pchat/bench?name=Studio")
    @Test fun openChat() = screen("chat")
}
