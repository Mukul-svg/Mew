package com.past9.phoneaos.system

import android.os.Trace

/**
 * Trace sections the Macrobenchmark suite reads with [androidx.benchmark.macro.TraceSectionMetric]. They are free on a
 * normal build (Trace is a no-op unless tracing is on) and give a stable name to the work that used to be guessed at
 * from frame drops: the chat fold, a live hand-off, a background worker, Node, the browser and notification intake.
 */
object TraceSections {
    /** A short, same-thread section: reading and folding the chat, one database batch, a list build. */
    inline fun <T> section(name: String, block: () -> T): T {
        Trace.beginSection(name)
        return try {
            block()
        } finally {
            Trace.endSection()
        }
    }

    /** A section that may cross a suspension or a thread (a worker, Node, a page load, a hand-off). */
    suspend fun <T> async(name: String, block: suspend () -> T): T {
        val cookie = System.nanoTime().toInt()
        Trace.beginAsyncSection(name, cookie)
        return try {
            block()
        } finally {
            Trace.endAsyncSection(name, cookie)
        }
    }
}
