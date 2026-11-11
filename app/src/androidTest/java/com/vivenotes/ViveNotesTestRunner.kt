package com.vivenotes

import android.app.UiAutomation
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.test.runner.AndroidJUnitRunner

/**
 * Keeps a physical device's display awake for the instrumented suite, and turns the device's
 * autofill service off while it runs.
 *
 * The suite is longer than the tablet's screen timeout. Once the keyguard covers a Compose test
 * activity, `createComposeRule` reports that there is no Compose hierarchy and every later UI test
 * fails as fallout. A test-process wake lock covers activity-free repository tests too, which an
 * activity window's `FLAG_KEEP_SCREEN_ON` cannot do. It is bounded and released in `finally`, and
 * the permission exists only in the androidTest manifest, so the shipped app is unchanged.
 *
 * Google's autofill service opens its save-password dialog as its own activity when a test
 * activity is destroyed, and no test typed anything to trigger it. Once the dialog is up, every
 * test after it is paused under it: "No compose hierarchies found", and screenshots that
 * capture the dialog instead of the page. [finish] puts the service back. A run killed before
 * `finish` (an emulator crash, Ctrl-C) leaves autofill off. The service is also written to the
 * global setting [PAUSED_MARKER], and the next run restores that value rather than the null it finds.
 */
class ViveNotesTestRunner : AndroidJUnitRunner() {

    /** The autofill service the device had before the run; null when it had none. */
    private var pausedAutofillService: String? = null

    @Suppress("DEPRECATION") // Screen-level wake locks are appropriate only for this test harness.
    override fun onStart() {
        pauseAutofill()

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

    override fun finish(resultCode: Int, results: Bundle?) {
        restoreAutofill()
        super.finish(resultCode, results)
    }

    private fun pauseAutofill() {
        // A marker means an earlier run was killed mid-pause: the null it left is not the
        // device's own choice.
        val service = setting("global", PAUSED_MARKER) ?: setting("secure", AUTOFILL_SERVICE) ?: return
        Log.i(TAG, "pausing autofill service $service for the run")
        shell("settings put global $PAUSED_MARKER $service")
        shell("settings delete secure $AUTOFILL_SERVICE")
        pausedAutofillService = service
    }

    private fun restoreAutofill() {
        val service = pausedAutofillService ?: return
        pausedAutofillService = null
        shell("settings put secure $AUTOFILL_SERVICE $service")
        shell("settings delete global $PAUSED_MARKER")
        Log.i(TAG, "restored autofill service $service")
    }

    /** The setting's value, or null when it is unset; `settings get` prints "null" for that. */
    private fun setting(table: String, key: String): String? =
        shell("settings get $table $key").trim().takeUnless { it.isEmpty() || it == "null" }

    /**
     * Runs [command] as the shell user, which may write secure settings, and waits for its output.
     * The flag keeps the device's accessibility services on, as the default connection would
     * suppress them for every test that runs after it.
     */
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                .executeShellCommand(command),
        ).bufferedReader().use { it.readText() }

    private companion object {
        const val TAG = "ViveNotesTestRunner"

        /** `Settings.Secure.AUTOFILL_SERVICE`, which the SDK hides. */
        const val AUTOFILL_SERVICE = "autofill_service"

        /** Holds the paused service until [finish] restores it. */
        const val PAUSED_MARKER = "vivenotes_paused_autofill_service"

        /** Well above the current ~20-minute suite, but still bounded if the runner hangs. */
        const val MAX_SUITE_DURATION_MS = 60L * 60L * 1000L
    }
}
