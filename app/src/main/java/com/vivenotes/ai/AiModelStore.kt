package com.vivenotes.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

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
    val formulaLatex: AiModelInstallState = AiModelInstallState.Verifying,
)

/**
 * Owns private, checksum-verified recognition model files.
 *
 * OCR is an app asset, so it is already available offline. FormulaNet-S is more than 220 MB and is
 * installed as one package (graph plus tokenizer) only after every staged file verifies. State is
 * derived from those artifacts and their verification marker, never from a preference boolean.
 */
class AiModelStore internal constructor(
    context: Context,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val downloader: VerifiedArtifactDownloader = VerifiedArtifactDownloader(),
    /**
     * Whether a first run fetches the formula package by itself.
     *
     * A parameter rather than a constant so a test can build a store that will never reach for the
     * network. Debug builds do not reach it anyway — they carry the package in `ai/dev` and resolve
     * to Installed before the question is asked.
     */
    private val autoDownload: Boolean = true,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val modelsRoot = File(appContext.filesDir, MODELS_DIRECTORY)
    private val formulaDirectory = File(modelsRoot, FORMULA_DIRECTORY)

    private val _state = MutableStateFlow(AiModelsState())
    val state: StateFlow<AiModelsState> = _state.asStateFlow()

    private var formulaDownload: Job? = null

    init {
        scope.launch {
            cleanupStaleDownloads()
            val textState = verifyBundledTextModel()
            val installedFormulaState = verifyInstalledFormulaPackage()
            val formulaState = if (installedFormulaState == AiModelInstallState.Installed) {
                installedFormulaState
            } else {
                installBundledFormulaPackageIfPresent() ?: installedFormulaState
            }
            _state.value = AiModelsState(textState, formulaState)
            // Formula recognition is a headline feature, not an extra, so a first run fetches it
            // rather than waiting to be asked — see [autoDownloadAllowed] for the one condition.
            if (formulaState == AiModelInstallState.NotInstalled && autoDownloadAllowed()) {
                downloadFormula()
            }
        }
    }

    /**
     * Whether the formula package may be fetched without anyone asking for it.
     *
     * **Unmetered connections only, and that is the whole of the condition.** 224 MB pulled onto a
     * cellular allowance because an app opened is a real cost to somebody who never asked for it,
     * and the fact that the feature is worth having does not make it the app's money to spend. On a
     * metered connection the pane keeps its Download button, so the choice is still available — it
     * is made by the person paying for it.
     *
     * `VALIDATED` as well as `NOT_METERED`, because a captive-portal Wi-Fi reports unmetered and
     * would otherwise start a 224 MB transfer that cannot succeed. A download that fails leaves the
     * package untouched and retries on the next launch, so being conservative here costs a delay
     * and nothing else.
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

    /** Starts the optional FormulaNet-S package download. Repeated taps share the active job. */
    fun downloadFormula() {
        if (formulaDownload?.isActive == true) return
        formulaDownload = scope.launch {
            val textState = _state.value.handwritingText
            val staging = File(modelsRoot, ".$FORMULA_DIRECTORY-${System.nanoTime()}.part")
            var completedBytes = 0L
            try {
                modelsRoot.mkdirsOrThrow()
                staging.mkdirsOrThrow()
                _state.value = AiModelsState(
                    handwritingText = textState,
                    formulaLatex = AiModelInstallState.Downloading(0, FORMULA_TOTAL_BYTES),
                )

                FORMULA_ARTIFACTS.forEach { artifact ->
                    downloader.download(
                        artifact = artifact,
                        destination = File(staging, artifact.fileName),
                        onBytes = { currentFileBytes ->
                            _state.value = AiModelsState(
                                handwritingText = textState,
                                formulaLatex = AiModelInstallState.Downloading(
                                    downloadedBytes = completedBytes + currentFileBytes,
                                    totalBytes = FORMULA_TOTAL_BYTES,
                                ),
                            )
                        },
                    )
                    completedBytes += artifact.bytes
                }

                _state.value = AiModelsState(textState, AiModelInstallState.Verifying)
                File(staging, VERIFIED_MARKER).writeText(FORMULA_MARKER)
                installStagedDirectory(staging, formulaDirectory)
                _state.value = AiModelsState(textState, AiModelInstallState.Installed)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                staging.deleteRecursively()
                _state.value = AiModelsState(textState, AiModelInstallState.NotInstalled)
                throw cancelled
            } catch (failure: Exception) {
                // The pane can only show one short line, and several of the ways this fails —
                // a redirect refused, a TLS chain rejected, a digest mismatch — arrive with a
                // message too terse to act on. The stack trace is the difference between
                // "Download failed" and knowing which hop failed, so it goes to the log.
                Log.w(TAG, "FormulaNet package download failed after $completedBytes bytes", failure)
                staging.deleteRecursively()
                _state.value = AiModelsState(
                    handwritingText = textState,
                    formulaLatex = AiModelInstallState.Failed(
                        failure.message?.takeIf { it.isNotBlank() } ?: "Download failed",
                    ),
                )
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

    /** Opens the bundled English PP-OCRv5 graph after its asset has been verified. */
    fun openTextModel() = appContext.assets.open(TEXT_MODEL_ASSET)

    /** Opens the bundled English PP-OCRv5 CTC dictionary after its asset has been verified. */
    fun openTextDictionary() = appContext.assets.open(TEXT_DICTIONARY_ASSET)

    /**
     * Opens the bundled PP-OCRv5 text detector after its asset has been verified.
     *
     * Bundled rather than downloaded — `memory/imageOcrPlan.md` IO1. 4.7 MB beside a 7.9 MB
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

    private fun verifyInstalledFormulaPackage(): AiModelInstallState {
        if (!formulaDirectory.isDirectory) return AiModelInstallState.NotInstalled
        return try {
            val marker = File(formulaDirectory, VERIFIED_MARKER)
            if (!marker.isFile || marker.readText() != FORMULA_MARKER) {
                FORMULA_ARTIFACTS.forEach { artifact ->
                    verifyFile(File(formulaDirectory, artifact.fileName), artifact)
                }
                marker.writeText(FORMULA_MARKER)
            } else {
                FORMULA_ARTIFACTS.forEach { artifact ->
                    val file = File(formulaDirectory, artifact.fileName)
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

    /**
     * Debug builds carry both optional files so a clean emulator install needs no network.
     *
     * **All of them, or none.** `pp-formulanet-s.onnx` is gitignored — 232 MB — while the 2 MB
     * tokenizer beside it is committed, so *half a package* is the ordinary state of a fresh clone,
     * not a symptom of anything. Half is therefore no bundled package at all: returning null leaves
     * the caller on whatever the installed package says, which is NotInstalled, and NotInstalled is
     * the one state the first-run fetch acts on.
     *
     * Gating on "any file present" instead — which is what this did until 2026-08-27 — reported
     * `Failed("Bundled FormulaNet model is unavailable")` on every clone that had not hand-placed
     * the ONNX. That was not merely a wrong message. Because the eager fetch fires only on
     * NotInstalled, the false Failed also suppressed the download that would have fixed it, so the
     * package never arrived on its own and the pane showed an install error at every launch.
     */
    private fun installBundledFormulaPackageIfPresent(): AiModelInstallState? {
        val bundledNames = appContext.assets.list(DEBUG_FORMULA_ASSETS_DIRECTORY)?.toSet().orEmpty()
        if (!FORMULA_ARTIFACTS.all { it.fileName in bundledNames }) return null

        val staging = File(modelsRoot, ".$FORMULA_DIRECTORY-debug-${System.nanoTime()}.part")
        return try {
            modelsRoot.mkdirsOrThrow()
            staging.mkdirsOrThrow()
            FORMULA_ARTIFACTS.forEach { artifact ->
                copyVerifiedAsset(
                    assetPath = "$DEBUG_FORMULA_ASSETS_DIRECTORY/${artifact.fileName}",
                    destination = File(staging, artifact.fileName),
                    artifact = artifact,
                )
            }
            File(staging, VERIFIED_MARKER).writeText(FORMULA_MARKER)
            installStagedDirectory(staging, formulaDirectory)
            AiModelInstallState.Installed
        } catch (failure: Exception) {
            Log.w(TAG, "Bundled FormulaNet package could not be hydrated", failure)
            staging.deleteRecursively()
            AiModelInstallState.Failed("Bundled FormulaNet model is unavailable")
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
        modelsRoot.listFiles()
            ?.filter { it.name.startsWith(".$FORMULA_DIRECTORY-") && it.name.endsWith(".part") }
            ?.forEach(File::deleteRecursively)
    }

    private fun File.mkdirsOrThrow() {
        require(isDirectory || mkdirs()) { "Cannot create model directory" }
    }

    data class FormulaModelFiles(val model: File, val tokenizer: File)

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
         * contents — [FORMULA_MARKER] is what says which model is in there.
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
         * **`PP-FormulaNet_plus-S` was tried on 2026-08-10 and reverted the same day**, because the
         * user reported handwriting recognition was markedly worse with it. Do not swap it back in
         * on the strength of its published numbers; they do not measure this app's input.
         *
         * The swap looked free, and mechanically it was. Both are the *same architecture retrained*,
         * verified rather than assumed: walking both ONNX graphs — including the decoder inside the
         * generation `Loop`, which a top-level read misses — gives 836 tensors and 57,916,120
         * parameters each, a `[50000, 384]` embedding and a `[384, 50000]` output projection in
         * both. The `tokenizer.json` is byte-identical (SHA-256 `2811d827…`) across `_plus`,
         * non-`_plus` and the copy bundled here, so a swap needs no tokenizer change and the export
         * is not at fault.
         *
         * **The weights are simply worse on ink.** `_plus` gains 88.71% vs 87.00% En-BLEU and 53.32%
         * vs 45.71% Zh-BLEU by training on a broader *printed* corpus and on Chinese — and with the
         * parameter count and vocabulary fixed, that capacity comes from somewhere. English
         * handwriting is what it came from here. No published benchmark separates handwriting for
         * either model, so this was found on the device and nowhere else.
         *
         * The lesson for the next candidate: printed BLEU does not predict this app's accuracy, and
         * the eval set in `memory/ai.md` open question 4 is the only thing that will.
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
        private val FORMULA_TOTAL_BYTES = FORMULA_ARTIFACTS.sumOf(ModelArtifact::bytes)
        private val FORMULA_MARKER = FORMULA_ARTIFACTS.joinToString("\n") {
            "${it.fileName}:${it.bytes}:${it.sha256}"
        }
    }
}
