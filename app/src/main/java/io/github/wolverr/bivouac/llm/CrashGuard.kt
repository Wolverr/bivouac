package io.github.wolverr.bivouac.llm

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Notices when Android killed the app for memory while it was loading a model or
 * answering, so the next load can use smaller settings instead of dying again.
 *
 * A marker file is written before the risky phase and removed after it; a marker
 * still present at startup means the process died in between. On Android 11+ the
 * system's exit record says whether it was for low memory; older versions trust the marker.
 */
class CrashGuard(context: Context) {
    data class Crash(val modelId: String, val phase: String, val detail: String, val lowMemory: Boolean)

    private val app = context.applicationContext
    private val marker = File(app.filesDir, "inflight.txt")
    private val prefs = app.getSharedPreferences("crash_guard", Context.MODE_PRIVATE)

    /** The crash from the previous run, if any; reading it clears the marker. */
    val previous: Crash? = readPrevious()

    fun begin(modelId: String, phase: String, detail: String) =
        runCatching { marker.writeText("$modelId\n$phase\n$detail\n${System.currentTimeMillis()}") }

    fun end() {
        marker.delete()
    }

    /** Budget multiplier for [modelId]: 1.0, shrinking by a quarter per memory crash (floor 0.4). */
    fun safety(modelId: String): Double = prefs.getFloat("safety_$modelId", 1f).toDouble()

    /** Settings loaded without trouble for a full answer: slowly regain headroom. */
    fun recovered(modelId: String) {
        val s = safety(modelId)
        if (s < 1.0) prefs.edit().putFloat("safety_$modelId", minOf(1.0, s + 0.05).toFloat()).apply()
    }

    private fun readPrevious(): Crash? {
        if (!marker.exists()) return null
        val lines = runCatching { marker.readLines() }.getOrDefault(emptyList())
        marker.delete()
        if (lines.size < 4) return null
        val since = lines[3].toLongOrNull() ?: 0L
        val lowMemory = lastExitWasLowMemory(since)
        if (lowMemory != false) {
            val id = lines[0]
            prefs.edit().putFloat("safety_$id", maxOf(0.4, safety(id) * 0.75).toFloat()).apply()
        }
        Log.w(TAG, "previous run died during ${lines[1]} (${lines[2]}), low memory: $lowMemory")
        return Crash(lines[0], lines[1], lines[2], lowMemory != false)
    }

    /** true / false from the system's exit record; null when unknown (Android 10). */
    private fun lastExitWasLowMemory(since: Long): Boolean? {
        if (Build.VERSION.SDK_INT < 30) return null
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val exit = runCatching { am.getHistoricalProcessExitReasons(null, 0, 1).firstOrNull() }.getOrNull() ?: return null
        if (exit.timestamp < since) return null
        return countsAgainstSettings(exit.reason, exit.status, exit.importance)
    }

    companion object {
        private const val TAG = "bivouac-crashguard"

        /**
         * A kill that smaller settings would have avoided: low memory (the low-memory killer often
         * reports SIGKILL instead) or a native crash, while the app was on screen. Killed in the
         * background (importance 400, cached), it was Android reclaiming a backgrounded app holding
         * the model; shrinking the budget for that dropped repacking on the Redmi and halved prompt
         * reading in every answer after it.
         */
        fun countsAgainstSettings(reason: Int, status: Int, importance: Int): Boolean {
            val memory = reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
                (reason == ApplicationExitInfo.REASON_SIGNALED && status == 9) ||
                reason == ApplicationExitInfo.REASON_CRASH_NATIVE
            return memory && importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE
        }
    }
}
