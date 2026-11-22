package com.past9.phoneaos.macrobenchmark

import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

/**
 * The benchmark build's own app id. It is fixed in :app's benchmark build type (applicationIdSuffix ".bench"),
 * so the benchmark never touches the real Mew install or its data. The test APK is self-instrumenting, so its
 * targetContext is the test package; this constant is the app under test.
 */
const val PACKAGE = "com.past9.phoneaos.bench"

/** The class names do not change with the benchmark app id; only the package/applicationId does. */
const val APP_CLASS = "com.past9.phoneaos.MainActivity"
const val RECEIVER = "$PACKAGE/com.past9.phoneaos.benchmark.BenchmarkReceiver"
const val ACTION = "com.past9.phoneaos.BENCHMARK"

/** Starts the real app and, only in the benchmark build, opens the screen the test needs. */
fun MacrobenchmarkScope.startAt(route: String) {
    launchApp(route)
    device.waitForIdle()
    device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), 15_000)
}

/** The bare launch used by the startup benchmarks: nothing but the real activity in the measured block. */
fun MacrobenchmarkScope.launchApp(route: String) {
    val intent = Intent(Intent.ACTION_MAIN).apply {
        setClassName(PACKAGE, APP_CLASS)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        putExtra("benchmark_route", route)
    }
    startActivityAndWait(intent)
}

/** One explicit broadcast; the receiver does the seed and finishes its goAsync result. */
fun MacrobenchmarkScope.seed(action: String) {
    device.executeShellCommand("am broadcast -a $ACTION --es action $action -n $RECEIVER")
    // Seeding opens the graph and writes Room rows; give the receiver's goAsync job time to finish before measuring.
    val bulk = action == "seed" || action == "seed_chat" || action == "seed_screens"
    Thread.sleep(if (bulk) 12_000 else 4_000)
}

/** The benchmark-only receiver also owns the background triggers, so a test can time one without WorkManager's delay. */
fun MacrobenchmarkScope.trigger(action: String, waitMs: Long = 8_000) {
    device.executeShellCommand("am broadcast -a $ACTION --es action $action -n $RECEIVER")
    Thread.sleep(waitMs)
}

fun MacrobenchmarkScope.swipeFeed(times: Int = 5) {
    repeat(times) {
        device.swipe(device.displayWidth / 2, (device.displayHeight * 0.78f).toInt(), device.displayWidth / 2, (device.displayHeight * 0.22f).toInt(), 16)
        device.waitForIdle(300)
    }
}

fun MacrobenchmarkScope.swipeBack(times: Int = 3) {
    repeat(times) {
        device.swipe(device.displayWidth / 2, (device.displayHeight * 0.25f).toInt(), device.displayWidth / 2, (device.displayHeight * 0.78f).toInt(), 16)
        device.waitForIdle(300)
    }
}

fun MacrobenchmarkScope.clickText(text: String): Boolean {
    val found = device.wait(Until.findObject(By.textContains(text)), 2_000) ?: return false
    found.click()
    return true
}

/** Waits for a seeded chat/agent line so screen benchmarks do not measure an empty cold frame. */
fun MacrobenchmarkScope.waitForChat() {
    device.wait(Until.hasObject(By.textContains("Message 1:")), 15_000)
    device.waitForIdle()
}

fun MacrobenchmarkScope.grantNotificationAccess() {
    device.executeShellCommand("cmd notification allow_listener $PACKAGE/com.past9.phoneaos.system.NotificationReader")
    seed("allow_shell_notifications")
}

fun MacrobenchmarkScope.postNotifications(n: Int) {
    repeat(n) { i ->
        device.executeShellCommand("cmd notification post -S bigtext -t 'Mew bench $i' tag$i 'A notification posted for the benchmark.'")
        Thread.sleep(40)
    }
}
