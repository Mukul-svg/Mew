package com.past9.phoneaos.benchmark

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.past9.phoneaos.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Benchmark-only deep link: `mew://benchmark/seed`.
 *
 * Revyl (and any other cloud device that has no adb shell) can open this. It asks the benchmark receiver to clear
 * and seed the app, waits for the goAsync seed to finish, then opens the real chat. The activity is compiled only
 * into the benchmark build, so a release has no such entry point.
 */
class BenchmarkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sendBroadcast(
            Intent(BenchmarkReceiver.ACTION)
                .setPackage(packageName)
                .putExtra("action", "seed_chat")
        )
        lifecycleScope.launch {
            // The receiver seeds off the main thread; 12 s is the same budget the Macrobenchmark helper uses.
            delay(12_000)
            startActivity(
                Intent(this@BenchmarkActivity, MainActivity::class.java)
                    .putExtra("benchmark_route", "chat")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
        }
    }
}
