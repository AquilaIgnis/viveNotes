package com.vivenotes.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

/**
 * What the first run does about the formula models.
 *
 * An install fetches the default model by itself on an unmetered connection, because formula
 * recognition is a headline feature rather than an extra. The mistake that can make is fetching
 * over 100 MB for somebody whose Math button already works, so that is what this pins. The rules
 * themselves are `FormulaEngineSelectionTest`'s.
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
            store.state.first { state ->
                FormulaEngine.entries.none { state.formula(it) is AiModelInstallState.Verifying }
            }
        }
        // The eager branch runs just after the state it decides from is published, so give it the
        // chance to be wrong before concluding that it wasn't.
        delay(SETTLE_GRACE_MS)
        return state
    }

    /**
     * A device with a formula model installed is never fetched for again.
     *
     * A debug build carries UniMERNet-T in the `debug` source set, so the store hydrates it and
     * resolves to Installed — the state the eager download must decline to act on. If it stops
     * declining, this test spends 113 MB finding out.
     *
     * Having a model is a precondition, not the claim: the `.onnx` files in
     * `app/src/debug/assets/ai/dev/` are gitignored, so a fresh clone reaches this with nothing to
     * hydrate. The subject is the line below it.
     */
    @Test
    fun anInstalledPackageIsNeverFetchedAgain() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AiModelStore(context, downloader = refusingDownloader())

        val state = settle(store)

        assumeTrue(
            "no bundled formula package on this machine — see app/src/debug/assets/ai/dev/",
            state.installedFormulaEngines.isNotEmpty(),
        )
        assertTrue("reached for $attempted despite a model being installed", attempted.isEmpty())
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

    /**
     * Half a debug bundle is no bundle, not a broken install.
     *
     * `app/src/debug/assets/ai/dev/` holds a committed 2 MB tokenizer next to FormulaNet's
     * gitignored 232 MB ONNX, so most builds carry exactly one of the two. Reporting that as Failed cost more than a
     * misleading message: the eager fetch acts only on NotInstalled, so the false failure also
     * turned off the download that would have supplied the missing file, and the pane showed an
     * install error at every launch instead of fetching the package once.
     */
    @Test
    fun anIncompleteBundledPackageLeavesTheStoreReadyToDownload() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AiModelStore(context, downloader = refusingDownloader(), autoDownload = false)

        val state = settle(store)

        assumeTrue(
            "this machine has the whole bundled package — see app/src/debug/assets/ai/dev/",
            state.formulaLatex != AiModelInstallState.Installed,
        )
        assertEquals(
            "an incomplete bundle must read as NotInstalled so the first run can fetch it",
            AiModelInstallState.NotInstalled,
            state.formulaLatex,
        )
    }

    private companion object {
        const val SETTLE_TIMEOUT_MS = 60_000L
        const val SETTLE_GRACE_MS = 750L
    }
}
