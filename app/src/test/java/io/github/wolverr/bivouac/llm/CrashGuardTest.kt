package io.github.wolverr.bivouac.llm

import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
import android.app.ApplicationExitInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashGuardTest {
    @Test fun memoryKillsOnScreenCount() {
        assertTrue(CrashGuard.countsAgainstSettings(ApplicationExitInfo.REASON_LOW_MEMORY, 0, IMPORTANCE_FOREGROUND))
        assertTrue(CrashGuard.countsAgainstSettings(ApplicationExitInfo.REASON_SIGNALED, 9, IMPORTANCE_FOREGROUND))
        assertTrue(CrashGuard.countsAgainstSettings(ApplicationExitInfo.REASON_CRASH_NATIVE, 0, IMPORTANCE_FOREGROUND))
    }

    @Test fun backgroundKillsAndUpdatesDont() {
        // The Redmi, 2026-10-15: LOW_MEMORY at importance 400 while another app was open.
        assertFalse(CrashGuard.countsAgainstSettings(ApplicationExitInfo.REASON_LOW_MEMORY, 0, IMPORTANCE_CACHED))
        assertFalse(CrashGuard.countsAgainstSettings(ApplicationExitInfo.REASON_PACKAGE_UPDATED, 0, IMPORTANCE_FOREGROUND))
        assertFalse(CrashGuard.countsAgainstSettings(ApplicationExitInfo.REASON_USER_REQUESTED, 0, IMPORTANCE_FOREGROUND))
    }
}
