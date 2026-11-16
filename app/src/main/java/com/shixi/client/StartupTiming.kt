package com.shixi.client

import android.os.SystemClock
import android.util.Log

/** Timing only: never records media identifiers, URLs, account data or exception messages. */
internal object StartupTiming {
    private var started = 0L
    private var attempt = 0L
    @Synchronized fun begin() {
        started = SystemClock.elapsedRealtime()
        attempt++
        Log.i("ShixiStartup", "attempt=$attempt stage=play elapsedMs=0")
    }
    @Synchronized fun mark(stage: String) {
        if (started == 0L) return
        val elapsed = SystemClock.elapsedRealtime() - started
        if (elapsed > 30_000) return
        Log.i("ShixiStartup", "attempt=$attempt stage=$stage elapsedMs=$elapsed")
        if (stage == "playing") started = 0L
    }
}
