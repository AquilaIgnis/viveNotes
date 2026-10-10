package com.vivenotes.data

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.play.core.review.ReviewException
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

private val Context.reviewPreferences: DataStore<Preferences> by preferencesDataStore("review")

/**
 * Asks Google Play for its in-app rating sheet on either of two occasions, each once: the
 * [SESSIONS_BEFORE_ASKING]th session, and the first time a notebook is closed.
 *
 * Play decides whether the sheet actually appears and never says whether it did or what was rated,
 * so there is no pre-prompt and nothing here reacts to the outcome: Play's policy forbids asking
 * first and only forwarding the happy answers. If both occasions land close together, Play's own
 * quota drops the second.
 *
 * Observes `ProcessLifecycleOwner`, which already debounces a rotation, so the only Activity being
 * rebuilt is not a session.
 *
 * A sideloaded build — debug, testRelease, or the F-Droid APK — is never asked: Play cannot take a
 * rating for an install it did not make, and the request would go to the Store for nothing.
 */
class ReviewPrompt(
    context: Context,
    private val scope: CoroutineScope,
) : DefaultLifecycleObserver {

    private val context = context.applicationContext
    private val store = this.context.reviewPreferences
    private val asking = Mutex()
    private val fromPlay: Boolean by lazy(::installedByPlay)

    /** True from the session that should ask until that ask has been made. */
    val sessionsReached: Flow<Boolean>
        get() = if (!fromPlay) {
            flowOf(false)
        } else {
            store.data.map { sessionsReached(it[SESSIONS] ?: 0, it[ASKED_FOR_SESSIONS] == true) }
        }

    override fun onStart(owner: LifecycleOwner) {
        val now = System.currentTimeMillis()
        scope.launch {
            store.edit { prefs ->
                if (startsSession(prefs[LAST_STOPPED], now)) {
                    prefs[SESSIONS] = (prefs[SESSIONS] ?: 0) + 1
                }
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        val now = System.currentTimeMillis()
        scope.launch { store.edit { it[LAST_STOPPED] = now } }
    }

    /** Called once [sessionsReached] turns true. */
    suspend fun askForSessions(activity: Activity) = ask(activity, ASKED_FOR_SESSIONS)

    suspend fun afterNotebookClosed(activity: Activity) = ask(activity, ASKED_ON_CLOSE)

    /**
     * Needs the Activity because the sheet is drawn over its window.
     *
     * Recorded once Play has handed over a request, before the sheet is launched. Launching opens
     * Play's own Activity over this one, and a caller tied to this one's lifecycle can be cancelled
     * by that; recording afterwards would have it ask again the moment the sheet closed. A failed
     * request is not recorded, so the occasion tries again next time.
     */
    private suspend fun ask(activity: Activity, asked: Preferences.Key<Boolean>) {
        if (!fromPlay || !asking.tryLock()) return
        try {
            if (store.data.first()[asked] == true) return
            val manager = ReviewManagerFactory.create(activity)
            val request = manager.requestReview()
            store.edit { it[asked] = true }
            manager.launchReview(activity, request)
        } catch (e: ReviewException) {
            Log.w(TAG, "Play declined the review request: ${e.errorCode}", e)
        } finally {
            asking.unlock()
        }
    }

    private fun installedByPlay(): Boolean = runCatching {
        context.packageManager.getInstallSourceInfo(context.packageName)
            .installingPackageName == PLAY_STORE
    }.getOrDefault(false)

    internal companion object {
        const val SESSIONS_BEFORE_ASKING = 20

        /**
         * How long the app must have been out of sight for coming back to count as a new session.
         * Glancing at another app mid-lecture is not a second visit.
         */
        const val SESSION_GAP_MS = 30 * 60 * 1000L

        fun startsSession(lastStopped: Long?, now: Long): Boolean =
            lastStopped == null || now - lastStopped >= SESSION_GAP_MS

        fun sessionsReached(sessions: Int, asked: Boolean): Boolean =
            !asked && sessions >= SESSIONS_BEFORE_ASKING

        private const val TAG = "ReviewPrompt"
        private const val PLAY_STORE = "com.android.vending"
        private val SESSIONS = intPreferencesKey("sessions")
        private val LAST_STOPPED = longPreferencesKey("last_stopped")
        private val ASKED_FOR_SESSIONS = booleanPreferencesKey("asked_for_sessions")
        private val ASKED_ON_CLOSE = booleanPreferencesKey("asked_on_close")
    }
}
