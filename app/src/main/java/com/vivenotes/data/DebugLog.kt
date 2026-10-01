package com.vivenotes.data

import android.util.Log
import com.vivenotes.BuildConfig

/**
 * Logcat lines that exist in debug builds only.
 *
 * Every function is `inline` and guarded by `BuildConfig.DEBUG`, a compile-time constant, so a
 * release APK carries neither the call nor the string it would have built: the message is a lambda
 * that is never reached there. A failure a shipped build has to report still uses `Log` directly.
 *
 * Two tags, one per question a debugging session asks:
 * - [DB]: what was written to `notes.db`, one info line per operation. Reads are not logged — the
 *   editor's flows re-query on every table change, and a line per emission would bury the writes.
 * - [TRANSFER]: the `.vive` import and export pipeline, step by step, at debug level.
 *
 * `adb logcat -s NotesDb NotebookTransfer` shows both.
 */
internal object DebugLog {
    const val DB = "NotesDb"
    const val TRANSFER = "NotebookTransfer"

    inline fun d(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(tag, message())
    }

    inline fun i(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.i(tag, message())
    }

    inline fun w(tag: String, failure: Throwable? = null, message: () -> String) {
        if (BuildConfig.DEBUG) Log.w(tag, message(), failure)
    }

    inline fun e(tag: String, failure: Throwable? = null, message: () -> String) {
        if (BuildConfig.DEBUG) Log.e(tag, message(), failure)
    }
}
