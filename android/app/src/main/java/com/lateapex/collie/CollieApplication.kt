package com.lateapex.collie

import android.app.Activity
import android.app.Application
import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import com.lateapex.collie.diagnostics.AnrWatchdog
import com.lateapex.collie.diagnostics.DiagnosticsExport
import com.lateapex.collie.ui.NativePreferences

class CollieApplication : Application() {
    lateinit var container: AppContainer
        private set

    @VisibleForTesting
    internal var lifecycleCallbacksRegisteredForTest: Boolean = false
        private set

    private var anrWatchdog: AnrWatchdog? = null

    override fun onCreate() {
        super.onCreate()
        NativePreferences(this).applyTheme()
        container = AppContainer(this)
        // Backstop for a process that died with the share sheet open: never leave a plaintext export behind.
        DiagnosticsExport.exportDir(cacheDir).deleteRecursively()
        installUncaughtExceptionHandler()
        registerActivityLifecycleCallbacks(diagnosticsLifecycleCallbacks())
        lifecycleCallbacksRegisteredForTest = true
        startAnrWatchdog()
        recordHistoricalExitReasons()
    }

    private fun installUncaughtExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // recordNow, not record: the previous handler kills the process before a queued write runs.
            container.diagnostics.recordNow(
                "crash",
                mapOf(
                    "thread" to thread.name,
                    "exception" to throwable.javaClass.name,
                    "message" to throwable.message,
                    "stackTrace" to throwable.stackTraceToString(),
                ),
            )
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun diagnosticsLifecycleCallbacks() = object : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) =
            record(activity, "created")
        override fun onActivityStarted(activity: Activity) = record(activity, "started")
        override fun onActivityResumed(activity: Activity) = record(activity, "resumed")
        override fun onActivityPaused(activity: Activity) = record(activity, "paused")
        override fun onActivityStopped(activity: Activity) = record(activity, "stopped")
        override fun onActivityDestroyed(activity: Activity) = record(activity, "destroyed")
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        private fun record(activity: Activity, event: String) {
            container.diagnostics.record(
                "lifecycle",
                mapOf("activity" to activity.javaClass.simpleName, "event" to event),
            )
        }
    }

    private fun startAnrWatchdog() {
        // The watchdog runs a real background thread that cannot be exercised end-to-end in
        // Robolectric (spec §Testing); starting it there only leaks one thread per test Application
        // and exhausts the unit-suite heap, so skip it under Robolectric.
        if (Build.FINGERPRINT == "robolectric") return
        anrWatchdog = AnrWatchdog(
            mainHandler = Handler(Looper.getMainLooper()),
            onBlocked = { blockedForMs ->
                container.diagnostics.record("anr", mapOf("blockedForMs" to blockedForMs))
            },
        ).also { it.start() }
    }

    /** API 30+ only: the OS's own record of why the PREVIOUS process died, read once at the next
     * launch — catches an ANR/crash the watchdog never got a chance to observe before the process
     * ended. Best-effort: any failure here is itself diagnostic-adjacent, not diagnostic-critical. */
    private fun recordHistoricalExitReasons() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val activityManager = getSystemService(ActivityManager::class.java) ?: return
            val seen = getSharedPreferences(DIAGNOSTICS_STATE, MODE_PRIVATE)
            val lastSeen = seen.getLong(LAST_EXIT_SEEN_AT, 0L)
            // The OS keeps the same few exits for every launch; record each one once, not per launch.
            val reasons = activityManager.getHistoricalProcessExitReasons(null, 0, 5)
                .filter { it.timestamp > lastSeen }
            reasons.maxOfOrNull { it.timestamp }?.let { seen.edit().putLong(LAST_EXIT_SEEN_AT, it).apply() }
            reasons.forEach { info ->
                container.diagnostics.record(
                    "previousProcessExit",
                    mapOf(
                        "reason" to info.reason,
                        "description" to info.description,
                        "timestamp" to info.timestamp,
                    ),
                )
            }
        } catch (_: Exception) {
            // Best-effort; never let a diagnostics read crash startup.
        }
    }

    private companion object {
        const val DIAGNOSTICS_STATE = "collie_diagnostics_state"
        const val LAST_EXIT_SEEN_AT = "last_exit_seen_at"
    }
}
