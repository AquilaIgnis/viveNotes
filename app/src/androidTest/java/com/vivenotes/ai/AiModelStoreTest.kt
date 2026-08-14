package com.vivenotes.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

/**
 * What the first run does about the optional formula package.
 *
 * Since 2026-08-14 an install fetches it by itself on an unmetered connection, because formula
 * recognition is a headline feature rather than an extra. The mistake that change can make is
 * fetching 224 MB somebody already has, so that is what this pins.
 */
@RunWith(AndroidJUnit4::class)
class AiModelStoreTest {

    /** Records every URL the store reaches for, and refuses all of them. */
    private val attempted: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun refusingDownloader() = VerifiedArtifactDownloader { url: URL ->
        attempted += url.toString()
        throw IOException("the network is not available to this test")
    }

    private suspend fun settle(store: AiModelStore): AiModelsState {
        val state = withTimeout(SETTLE_TIMEOUT_MS) {
            store.state.first { it.formulaLatex !is AiModelInstallState.Verifying }
        }
        // The eager branch runs just after the state it decides from is published, so give it the
        // chance to be wrong before concluding that it wasn't.
        delay(SETTLE_GRACE_MS)
        return state
    }

    /**
     * A package that is already there is never fetched again.
     *
     * This build carries the formula files in the `debug` source set, so the store hydrates them
     * and resolves to Installed — which is exactly the state the eager download must decline to act
     * on. If it ever stops declining, this test spends 224 MB finding out.
     */
    @Test
    fun anInstalledPackageIsNeverFetchedAgain() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AiModelStore(context, downloader = refusingDownloader())

        val state = settle(store)

        assertEquals(AiModelInstallState.Installed, state.formulaLatex)
        assertTrue("reached for $attempted despite the package being installed", attempted.isEmpty())
    }

    /**
     * The switch that keeps a store away from the network entirely.
     *
     * It exists so a test or a build can opt out; asserted here so that "off" cannot quietly stop
     * meaning off.
     */
    @Test
    fun aStoreBuiltWithoutAutoDownloadNeverReachesForTheNetwork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AiModelStore(context, downloader = refusingDownloader(), autoDownload = false)

        settle(store)

        assertTrue("reached for $attempted with auto-download off", attempted.isEmpty())
    }

    private companion object {
        const val SETTLE_TIMEOUT_MS = 60_000L
        const val SETTLE_GRACE_MS = 750L
    }
}
