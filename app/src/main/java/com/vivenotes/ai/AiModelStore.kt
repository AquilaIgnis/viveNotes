package com.vivenotes.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

private val Context.aiPreferences: DataStore<Preferences> by preferencesDataStore("ai")

/** The two local recognizers deliberately exposed as separate capabilities. */
enum class AiModelId {
    HandwritingText,
    FormulaLatex,
}

/** Installation state shown by the Integrated AI pane. */
sealed interface AiModelInstallState {
    data object NotInstalled : AiModelInstallState
    data object Verifying : AiModelInstallState
    data object Installed : AiModelInstallState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : AiModelInstallState
    data class Failed(val message: String) : AiModelInstallState
}

data class AiModelsState(
    val handwritingText: AiModelInstallState = AiModelInstallState.Verifying,
    /** PP-FormulaNet-S's package. */
    val formulaLatex: AiModelInstallState = AiModelInstallState.Verifying,
    /** UniMERNet-T's package. */
    val uniMerNet: AiModelInstallState = AiModelInstallState.Verifying,
    /** The model the Math button runs — see [formulaEngineInUse]. */
    val formulaEngine: FormulaEngine = DEFAULT_FORMULA_ENGINE,
) {
    fun formula(engine: FormulaEngine): AiModelInstallState = when (engine) {
        FormulaEngine.UniMerNetTiny -> uniMerNet
        FormulaEngine.FormulaNetS -> formulaLatex
    }

    fun withFormula(engine: FormulaEngine, install: AiModelInstallState): AiModelsState =
        when (engine) {
            FormulaEngine.UniMerNetTiny -> copy(uniMerNet = install)
            FormulaEngine.FormulaNetS -> copy(formulaLatex = install)
        }

    val installedFormulaEngines: Set<FormulaEngine>
        get() = FormulaEngine.entries.filterTo(mutableSetOf()) {
            formula(it) == AiModelInstallState.Installed
        }

    /** Whether the Math button has a model behind it: the selected one, installed. */
    val formulaReady: Boolean
        get() = formula(formulaEngine) == AiModelInstallState.Installed
}

/**
 * Owns private, checksum-verified recognition model files.
 *
 * OCR is an app asset, so it is already available offline. Each formula model is a download of
 * over 100 MB, installed as one package (graphs plus tokenizer) only after every staged file
 * verifies. Which packages are installed is derived from those artifacts and their verification
 * markers, never from a preference. The preferences hold only which model the user picked, if they
 * ever did, and which ones they have deleted.
 */
class AiModelStore internal constructor(
    context: Context,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val downloader: VerifiedArtifactDownloader = VerifiedArtifactDownloader(),
    /**
     * Whether a launch without the default formula model fetches it by itself.
     *
     * A parameter rather than a constant so a test can build a store that will never reach for the
     * network. Debug builds do not reach it anyway — they carry UniMERNet-T in `ai/dev` and resolve
     * to Installed before the question is asked.
     */
    private val autoDownload: Boolean = true,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val modelsRoot = File(appContext.filesDir, MODELS_DIRECTORY)
    private val formulaDirectory = File(modelsRoot, FORMULA_DIRECTORY)
    private val uniMerNetDirectory = File(modelsRoot, UNIMERNET_DIRECTORY)
    private val packages: Map<FormulaEngine, FormulaPackage> = mapOf(
        FormulaEngine.UniMerNetTiny to FormulaPackage(uniMerNetDirectory, UNIMERNET_ARTIFACTS, "UniMERNet"),
        FormulaEngine.FormulaNetS to FormulaPackage(formulaDirectory, FORMULA_ARTIFACTS, "FormulaNet"),
    )

    private val _state = MutableStateFlow(AiModelsState())
    val state: StateFlow<AiModelsState> = _state.asStateFlow()

    private val downloads = mutableMapOf<FormulaEngine, Job>()

    /** The model the user tapped Use on, if they ever did — [formulaEngineInUse]'s `chosen`. */
    @Volatile
    private var chosenFormulaEngine: FormulaEngine? = null

    init {
        scope.launch {
            cleanupStaleDownloads()
            val textState = verifyBundledTextModel()
            val formula = FormulaEngine.entries.associateWith { resolvePackage(packages.getValue(it)) }
            val preferences = runCatching { appContext.aiPreferences.data.first() }.getOrNull()
            val chosen = preferences?.get(FORMULA_ENGINE_KEY)?.let(::formulaEngineNamed)
            val deleted = preferences?.get(DELETED_FORMULA_ENGINES_KEY).orEmpty()
                .mapNotNullTo(mutableSetOf(), ::formulaEngineNamed)
            val installed = formula.filterValues { it == AiModelInstallState.Installed }.keys
            chosenFormulaEngine = chosen
            _state.value = AiModelsState(
                handwritingText = textState,
                formulaLatex = formula.getValue(FormulaEngine.FormulaNetS),
                uniMerNet = formula.getValue(FormulaEngine.UniMerNetTiny),
                formulaEngine = formulaEngineInUse(chosen, installed),
            )
            // Formula recognition is a headline feature, not an extra, so a launch without the
            // default model fetches it rather than waiting to be asked — an upgrade that has only
            // FormulaNet-S included. [formulaEngineToFetch] says when not to, and
            // [autoDownloadAllowed] says when the network is fit for it.
            val fetch = formulaEngineToFetch(installed, deleted)
            if (fetch != null &&
                formula.getValue(fetch) == AiModelInstallState.NotInstalled &&
                autoDownloadAllowed()
            ) {
                download(fetch)
            }
        }
    }

    /**
     * Whether a formula model may be fetched without anyone asking for it.
     *
     * Unmetered connections only. Over 100 MB pulled onto a cellular allowance because an app opened
     * is a real cost to somebody who never asked for it. On a metered connection the card keeps its
     * Download button, so the choice is still available.
     *
     * `VALIDATED` as well as `NOT_METERED`, because a captive-portal Wi-Fi reports unmetered and
     * would start a transfer that cannot succeed. A failed download leaves the package untouched and
     * retries on the next launch.
     */
    private fun autoDownloadAllowed(): Boolean {
        if (!autoDownload) return false
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
            ?: return false
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Downloads one formula model's package. Repeated taps share the active job. */
    fun download(engine: FormulaEngine) {
        synchronized(downloads) {
            if (downloads[engine]?.isActive == true) return
            downloads[engine] = scope.launch { fetch(engine, packages.getValue(engine)) }
        }
    }

    private suspend fun fetch(engine: FormulaEngine, formulaPackage: FormulaPackage) {
        val staging = File(modelsRoot, ".${formulaPackage.directory.name}-${System.nanoTime()}.part")
        var completedBytes = 0L
        try {
            modelsRoot.mkdirsOrThrow()
            staging.mkdirsOrThrow()
            setPackageState(engine, AiModelInstallState.Downloading(0, formulaPackage.totalBytes))

            formulaPackage.artifacts.forEach { artifact ->
                downloader.download(
                    artifact = artifact,
                    destination = File(staging, artifact.fileName),
                    onBytes = { currentFileBytes ->
                        setPackageState(
                            engine,
                            AiModelInstallState.Downloading(
                                downloadedBytes = completedBytes + currentFileBytes,
                                totalBytes = formulaPackage.totalBytes,
                            ),
                        )
                    },
                )
                completedBytes += artifact.bytes
            }

            setPackageState(engine, AiModelInstallState.Verifying)
            File(staging, VERIFIED_MARKER).writeText(formulaPackage.marker)
            installStagedDirectory(staging, formulaPackage.directory)
            _state.update { current ->
                val next = current.withFormula(engine, AiModelInstallState.Installed)
                next.copy(
                    formulaEngine = formulaEngineInUse(chosenFormulaEngine, next.installedFormulaEngines),
                )
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            staging.deleteRecursively()
            setPackageState(engine, AiModelInstallState.NotInstalled)
            throw cancelled
        } catch (failure: Exception) {
            // The pane can only show one short line, and several of the ways this fails — a
            // redirect refused, a TLS chain rejected, a digest mismatch — arrive with a message
            // too terse to act on. The stack trace is the difference between "Download failed"
            // and knowing which hop failed, so it goes to the log.
            Log.w(
                TAG,
                "${formulaPackage.name} package download failed after $completedBytes bytes",
                failure,
            )
            staging.deleteRecursively()
            setPackageState(
                engine,
                AiModelInstallState.Failed(
                    failure.message?.takeIf { it.isNotBlank() } ?: "Download failed",
                ),
            )
        }
    }

    /**
     * One package's state alone. A copy, not a fresh [AiModelsState]: rebuilding the whole state
     * from the fields one download knows about would reset the others on every progress tick.
     */
    private fun setPackageState(engine: FormulaEngine, install: AiModelInstallState) {
        _state.update { it.withFormula(engine, install) }
    }

    /** Which model the Math button runs. Only an installed model can be chosen. */
    fun selectFormulaEngine(engine: FormulaEngine) {
        if (_state.value.formula(engine) != AiModelInstallState.Installed) return
        chosenFormulaEngine = engine
        _state.update { it.copy(formulaEngine = engine) }
        scope.launch {
            appContext.aiPreferences.edit { it[FORMULA_ENGINE_KEY] = engine.name }
        }
    }

    /**
     * Removes an installed formula model from this device.
     *
     * The state changes before any file goes, so no new recognition starts on a package being
     * deleted. One already running holds its graphs open and finishes, which on Linux outlives the
     * unlink. A deleted model in use hands the Math button to the other one if it is installed —
     * [formulaEngineAfterDelete].
     *
     * The delete is remembered, and from then on no launch fetches that model unasked.
     * In a debug build the bundled copy comes back on the next launch, as FormulaNet's always has.
     */
    fun delete(engine: FormulaEngine) {
        if (_state.value.formula(engine) != AiModelInstallState.Installed) return
        _state.update { current ->
            val next = current.withFormula(engine, AiModelInstallState.NotInstalled)
            next.copy(
                formulaEngine = formulaEngineAfterDelete(
                    selected = current.formulaEngine,
                    deleted = engine,
                    stillInstalled = next.installedFormulaEngines,
                ),
            )
        }
        scope.launch {
            packages.getValue(engine).directory.deleteRecursively()
            appContext.aiPreferences.edit {
                it[DELETED_FORMULA_ENGINES_KEY] = it[DELETED_FORMULA_ENGINES_KEY].orEmpty() + engine.name
            }
        }
    }

    /** Returns verified FormulaNet-S files for the runtime, or null until the whole package exists. */
    fun installedFormulaFiles(): FormulaModelFiles? {
        if (_state.value.formulaLatex != AiModelInstallState.Installed) return null
        return FormulaModelFiles(
            model = File(formulaDirectory, FORMULA_MODEL.fileName),
            tokenizer = File(formulaDirectory, FORMULA_TOKENIZER.fileName),
        )
    }

    /** UniMERNet-T's verified graphs and tokenizer, or null until the whole package exists. */
    fun installedUniMerNetFiles(): UniMerNetFiles? {
        if (_state.value.uniMerNet != AiModelInstallState.Installed) return null
        return UniMerNetFiles(
            encoder = File(uniMerNetDirectory, UNIMERNET_ENCODER.fileName),
            decoder = File(uniMerNetDirectory, UNIMERNET_DECODER.fileName),
            tokenizer = File(uniMerNetDirectory, FORMULA_TOKENIZER.fileName),
        )
    }

    /** Opens the bundled English PP-OCRv5 graph after its asset has been verified. */
    fun openTextModel() = appContext.assets.open(TEXT_MODEL_ASSET)

    /** Opens the bundled English PP-OCRv5 CTC dictionary after its asset has been verified. */
    fun openTextDictionary() = appContext.assets.open(TEXT_DICTIONARY_ASSET)

    /**
     * Opens the bundled PP-OCRv5 text detector after its asset has been verified.
     *
     * Bundled rather than downloaded. 4.7 MB beside a 7.9 MB
     * recognizer is not the size that made FormulaNet optional, and OCR that needs a network round
     * trip before it works is OCR that mostly does not work.
     */
    fun openDetectionModel() = appContext.assets.open(DETECTION_MODEL_ASSET)

    /**
     * All three bundled OCR assets, verified together.
     *
     * Together because they are one capability: the detector finds the lines and the recognizer
     * reads them, and either one alone reads a picture as nothing. A half-verified pipeline reported
     * as Installed would be a promise the panel cannot keep.
     */
    private fun verifyBundledTextModel(): AiModelInstallState = try {
        verifyAsset(TEXT_MODEL_ASSET, TEXT_MODEL_BYTES, TEXT_MODEL_SHA256)
        verifyAsset(TEXT_DICTIONARY_ASSET, TEXT_DICTIONARY_BYTES, TEXT_DICTIONARY_SHA256)
        verifyAsset(DETECTION_MODEL_ASSET, DETECTION_MODEL_BYTES, DETECTION_MODEL_SHA256)
        AiModelInstallState.Installed
    } catch (failure: Exception) {
        AiModelInstallState.Failed("Bundled OCR model is unavailable")
    }

    private fun verifyInstalledPackage(
        directory: File,
        artifacts: List<ModelArtifact>,
        expectedMarker: String,
    ): AiModelInstallState {
        if (!directory.isDirectory) return AiModelInstallState.NotInstalled
        return try {
            val marker = File(directory, VERIFIED_MARKER)
            if (!marker.isFile || marker.readText() != expectedMarker) {
                artifacts.forEach { artifact ->
                    verifyFile(File(directory, artifact.fileName), artifact)
                }
                marker.writeText(expectedMarker)
            } else {
                artifacts.forEach { artifact ->
                    val file = File(directory, artifact.fileName)
                    require(file.isFile && file.length() == artifact.bytes) {
                        "${artifact.fileName} is incomplete"
                    }
                }
            }
            AiModelInstallState.Installed
        } catch (_: Exception) {
            AiModelInstallState.NotInstalled
        }
    }

    /** An installed package, else a debug build's bundled copy of it, else NotInstalled. */
    private fun resolvePackage(formulaPackage: FormulaPackage): AiModelInstallState {
        val installed = verifyInstalledPackage(
            formulaPackage.directory,
            formulaPackage.artifacts,
            formulaPackage.marker,
        )
        if (installed == AiModelInstallState.Installed) return installed
        return hydrateBundledPackageIfPresent(formulaPackage) ?: installed
    }

    /**
     * Debug builds can carry a formula package so a clean emulator install needs no network.
     *
     * All of its files, or none. The ONNX graphs are gitignored while the 2 MB tokenizer beside them
     * is committed, so half a package is the ordinary state of a fresh clone. Half is therefore no
     * bundled package at all: returning null leaves the caller on NotInstalled, which is the one
     * state the first-run fetch acts on.
     *
     * Gating on "any file present" instead reported `Failed("Bundled FormulaNet model is
     * unavailable")` on every clone that had not hand-placed the ONNX — and because the eager fetch
     * fires only on NotInstalled, that false Failed also suppressed the download that would have
     * fixed it.
     *
     * Copied out of the APK rather than opened in place: ONNX Runtime opens a model by path without
     * loading it onto the Java heap, and an 82 MB decoder read into a byte array, as the small OCR
     * graphs are, is a large share of an app's heap.
     */
    private fun hydrateBundledPackageIfPresent(formulaPackage: FormulaPackage): AiModelInstallState? {
        val bundled = appContext.assets.list(DEBUG_FORMULA_ASSETS_DIRECTORY)?.toSet().orEmpty()
        if (!formulaPackage.artifacts.all { it.fileName in bundled }) return null

        val directory = formulaPackage.directory
        val staging = File(modelsRoot, ".${directory.name}-debug-${System.nanoTime()}.part")
        return try {
            modelsRoot.mkdirsOrThrow()
            staging.mkdirsOrThrow()
            formulaPackage.artifacts.forEach { artifact ->
                copyVerifiedAsset(
                    assetPath = "$DEBUG_FORMULA_ASSETS_DIRECTORY/${artifact.fileName}",
                    destination = File(staging, artifact.fileName),
                    artifact = artifact,
                )
            }
            File(staging, VERIFIED_MARKER).writeText(formulaPackage.marker)
            installStagedDirectory(staging, directory)
            AiModelInstallState.Installed
        } catch (failure: Exception) {
            Log.w(TAG, "Bundled ${formulaPackage.name} package could not be hydrated", failure)
            staging.deleteRecursively()
            AiModelInstallState.Failed("Bundled ${formulaPackage.name} model is unavailable")
        }
    }

    private fun copyVerifiedAsset(
        assetPath: String,
        destination: File,
        artifact: ModelArtifact,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        appContext.assets.open(assetPath).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    written += count
                    require(written <= artifact.bytes)
                }
                output.fd.sync()
            }
        }
        require(written == artifact.bytes && digest.digest().hex() == artifact.sha256) {
            "Bundled ${artifact.fileName} failed verification"
        }
    }

    private fun verifyAsset(path: String, bytes: Long, sha256: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        var length = 0L
        appContext.assets.open(path).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                length += count
            }
        }
        require(length == bytes && digest.digest().hex() == sha256)
    }

    private fun verifyFile(file: File, artifact: ModelArtifact) {
        require(file.isFile && file.length() == artifact.bytes)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        require(digest.digest().hex() == artifact.sha256)
    }

    private fun installStagedDirectory(staging: File, destination: File) {
        if (destination.exists()) destination.deleteRecursively()
        try {
            Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(staging.toPath(), destination.toPath())
        }
    }

    /** A process kill cannot run the coroutine's cleanup block, so discard its private staging dir. */
    private fun cleanupStaleDownloads() {
        val prefixes = packages.values.map { ".${it.directory.name}-" }
        modelsRoot.listFiles()
            ?.filter { file -> prefixes.any(file.name::startsWith) && file.name.endsWith(".part") }
            ?.forEach(File::deleteRecursively)
        // UniMERNet-T's slot while it was a debug-only experiment. Nothing reads it any more, so
        // without this it is 113 MB stranded on every device that ran one of those builds.
        File(modelsRoot, LEGACY_UNIMERNET_DIRECTORY).deleteRecursively()
    }

    private fun File.mkdirsOrThrow() {
        require(isDirectory || mkdirs()) { "Cannot create model directory" }
    }

    /** One formula model's files: where they install, what they are, and its name in a log line. */
    private class FormulaPackage(
        val directory: File,
        val artifacts: List<ModelArtifact>,
        val name: String,
    ) {
        val marker: String = artifacts.marker()
        val totalBytes: Long = artifacts.sumOf(ModelArtifact::bytes)
    }

    data class FormulaModelFiles(val model: File, val tokenizer: File)
    data class UniMerNetFiles(val encoder: File, val decoder: File, val tokenizer: File)

    companion object {
        private const val TAG = "AiModelStore"
        private const val MODELS_DIRECTORY = "ai_models"

        /**
         * The install slot, **deliberately not renamed** when the model inside it changed.
         *
         * It reads like a leftover — it says `-s-` and now holds `_plus-s` — and renaming it is the
         * obvious tidy-up. Don't. [installStagedDirectory] deletes the destination before moving the
         * new package in, so reusing one path is what keeps exactly one copy on disk; a new name
         * would strand the old 231 MB in app-private storage on every device that had already
         * installed it, invisibly and for good. The name is a slot, not a description of its
         * contents — the package's verification marker is what says which model is in there.
         */
        private const val FORMULA_DIRECTORY = "pp-formulanet-s-v1"
        private const val VERIFIED_MARKER = ".verified"
        private const val COPY_BUFFER_BYTES = 64 * 1024
        internal const val DEBUG_FORMULA_ASSETS_DIRECTORY = "ai/dev"

        internal const val TEXT_MODEL_ASSET = "ai/en_pp-ocrv5_mobile_rec.onnx"
        internal const val TEXT_DICTIONARY_ASSET = "ai/ppocrv5_en_dict.txt"

        /**
         * PP-OCRv5_mobile_det, from the same pinned oar-ocr v0.3.0 release as everything else here.
         *
         * It is not a second recognizer and cannot replace one: its only output is a per-pixel
         * probability map, `[1, 1, H, W]`, with no box, class or score tensor anywhere in the graph.
         * Turning that map into text quads is `model/ocr/TextDetection.kt`.
         */
        internal const val DETECTION_MODEL_ASSET = "ai/pp-ocrv5_mobile_det.onnx"
        private const val TEXT_MODEL_BYTES = 7_876_014L
        private const val TEXT_DICTIONARY_BYTES = 1_416L
        private const val DETECTION_MODEL_BYTES = 4_826_518L
        private const val DETECTION_MODEL_SHA256 =
            "1eb7b4f7ab657ebd1c66d5f79bca7497f29768a2e3c15e52daecbba1a8e4a039"
        private const val TEXT_MODEL_SHA256 =
            "8307465d3c9ef2ba4055c3bd0be55aafe11f518630212b7598b70ccb376028ac"
        private const val TEXT_DICTIONARY_SHA256 =
            "e025a66d31f327ba0c232e03f407ae8d105e1e709e7ccb3f408aa778c24e70d6"

        internal const val FORMULA_MODEL_URL =
            "https://github.com/GreatV/oar-ocr/releases/download/v0.3.0/pp-formulanet-s.onnx"
        internal const val FORMULA_TOKENIZER_URL =
            "https://huggingface.co/PaddlePaddle/PP-FormulaNet-L_safetensors/resolve/main/" +
                "tokenizer.json"

        /**
         * PP-FormulaNet-S.
         *
         * `PP-FormulaNet_plus-S` was tried on 2026-08-10 and reverted the same day, because
         * handwriting recognition was markedly worse with it. Do not swap it back in on the
         * strength of its published numbers; they do not measure this app's input.
         *
         * Both are the same architecture retrained, verified rather than assumed: walking both ONNX
         * graphs — including the decoder inside the generation `Loop` — gives 836 tensors and
         * 57,916,120 parameters each, with a `[50000, 384]` embedding and a `[384, 50000]` output
         * projection in both, and a byte-identical `tokenizer.json` (SHA-256 `2811d827…`). So a swap
         * needs no tokenizer change and the export is not at fault.
         *
         * The weights are simply worse on ink. `_plus` gains 88.71% vs 87.00% En-BLEU and 53.32% vs
         * 45.71% Zh-BLEU by training on a broader printed corpus and on Chinese, and with parameter
         * count and vocabulary fixed that capacity comes from somewhere — English handwriting, here.
         *
         * Printed BLEU does not predict this app's accuracy; only a real eval set will.
         */
        private val FORMULA_MODEL = ModelArtifact(
            fileName = "pp-formulanet-s.onnx",
            bytes = 231_878_904L,
            sha256 = "0ee32c7bfbd9e586364f89f71860476ccb5334e35674a61f3df5e0553d6a6dcc",
            url = FORMULA_MODEL_URL,
        )
        private val FORMULA_TOKENIZER = ModelArtifact(
            fileName = "pp-formulanet-tokenizer.json",
            bytes = 2_140_014L,
            sha256 = "2811d82701ec97c192fa256aa2b4516929373870ae660326cc5b1dc879b95ff2",
            url = FORMULA_TOKENIZER_URL,
        )
        private val FORMULA_ARTIFACTS = listOf(FORMULA_MODEL, FORMULA_TOKENIZER)

        /** The slot, named for the package like FormulaNet's — see [FORMULA_DIRECTORY] on renaming. */
        private const val UNIMERNET_DIRECTORY = "unimernet-tiny-v1"
        private const val LEGACY_UNIMERNET_DIRECTORY = "unimernet-tiny-dev"
        private val FORMULA_ENGINE_KEY = stringPreferencesKey("formula_engine")

        /** Every formula model the user has deleted, by name — see [formulaEngineToFetch]. */
        private val DELETED_FORMULA_ENGINES_KEY = stringSetPreferencesKey("deleted_formula_models")

        private fun formulaEngineNamed(name: String): FormulaEngine? =
            FormulaEngine.entries.firstOrNull { it.name == name }

        /**
         * The project's own release of the two graphs below. A model release, tagged apart from the
         * app's `v*` tags and never marked Latest.
         */
        internal const val UNIMERNET_RELEASE_URL =
            "https://github.com/AquilaIgnis/viveNotes/releases/download/models-unimernet-tiny-v1/"

        /**
         * UniMERNet-T (`wanderkid/unimernet_tiny`, Apache-2.0) as `export_unimernet.py` and
         * `quantize_unimernet.py` in `simulations/formula-models` write it: int8 weights in MatMul
         * and Gather, float convolutions. The export is deterministic, and two runs give these bytes
         * exactly, so a change here means the export changed and the README's parity check against
         * PyTorch has to be re-run.
         */
        private val UNIMERNET_ENCODER = ModelArtifact(
            fileName = "unimernet-tiny-encoder-int8.onnx",
            bytes = 29_081_861L,
            sha256 = "cf6cc98a54c97254adee4e251f56af2da045062503663b001a254303dab2aa97",
            url = UNIMERNET_RELEASE_URL + "unimernet-tiny-encoder-int8.onnx",
        )
        private val UNIMERNET_DECODER = ModelArtifact(
            fileName = "unimernet-tiny-decoder-int8.onnx",
            bytes = 82_108_192L,
            sha256 = "b765a9c522f43cff7dcc6379e9c39dfc441d6ee189822cb71be8f5e7cd703a69",
            url = UNIMERNET_RELEASE_URL + "unimernet-tiny-decoder-int8.onnx",
        )

        /** UniMERNet's `tokenizer.json` is FormulaNet's byte for byte, bar one trailing newline. */
        private val UNIMERNET_ARTIFACTS = listOf(UNIMERNET_ENCODER, UNIMERNET_DECODER, FORMULA_TOKENIZER)

        /** A formula model's whole download, for its card. */
        internal fun downloadBytes(engine: FormulaEngine): Long = when (engine) {
            FormulaEngine.UniMerNetTiny -> UNIMERNET_ARTIFACTS
            FormulaEngine.FormulaNetS -> FORMULA_ARTIFACTS
        }.sumOf(ModelArtifact::bytes)

        private fun List<ModelArtifact>.marker(): String =
            joinToString("\n") { "${it.fileName}:${it.bytes}:${it.sha256}" }
    }
}
