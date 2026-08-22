package com.vivenotes

import android.os.PowerManager
import androidx.test.runner.AndroidJUnitRunner

/**
 * Keeps a physical device's display awake for the instrumented suite.
 *
 * The suite is longer than the tablet's screen timeout. Once the keyguard covers a Compose test
 * activity, `createComposeRule` reports that there is no Compose hierarchy and every later UI test
 * fails as fallout. A test-process wake lock covers activity-free repository tests too, which an
 * activity window's `FLAG_KEEP_SCREEN_ON` cannot do. It is bounded and released in `finally`, and
 * the permission exists only in the androidTest manifest, so the shipped app is unchanged.
 */
class ViveNotesTestRunner : AndroidJUnitRunner() {

    @Suppress("DEPRECATION") // Screen-level wake locks are appropriate only for this test harness.
    override fun onStart() {
        val power = targetContext.getSystemService(PowerManager::class.java)
        val displayLock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "ViveNotes:instrumented-tests",
        ).apply {
            setReferenceCounted(false)
            acquire(MAX_SUITE_DURATION_MS)
        }

        try {
            super.onStart()
        } finally {
            if (displayLock.isHeld) displayLock.release()
        }
    }

    private companion object {
        /** Well above the current ~20-minute suite, but still bounded if the runner hangs. */
        const val MAX_SUITE_DURATION_MS = 60L * 60L * 1000L
    }
}
