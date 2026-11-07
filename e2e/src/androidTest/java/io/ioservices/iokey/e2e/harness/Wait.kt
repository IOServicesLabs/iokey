package io.ioservices.iokey.e2e.harness

import android.os.SystemClock

/** Polling instead of fixed sleeps: a slow moment on the emulator must lengthen a wait, not fail a test. */
object Wait {

    /** True as soon as [condition] holds, false once [timeoutMs] has passed without it holding. */
    fun until(timeoutMs: Long, pollMs: Long = 40, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            if (condition()) return true
            if (SystemClock.uptimeMillis() >= deadline) return false
            SystemClock.sleep(pollMs)
        }
    }

    /**
     * Until [read] returns the same value [stableReads] times in a row, or [timeoutMs] passes, in which
     * case the last value read is returned. The caller's assertions decide whether that value is right.
     */
    fun <T> untilStable(timeoutMs: Long, pollMs: Long = 250, stableReads: Int = 3, read: () -> T): T {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var last = read()
        var same = 1
        while (same < stableReads && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(pollMs)
            val next = read()
            if (next == last) same++ else same = 1
            last = next
        }
        return last
    }
}
