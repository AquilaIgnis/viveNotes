package com.vivenotes.ai

import android.content.Context
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
        }
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

    private fun verifyBundledTextModel(): AiModelInstallState = try {
        verifyAsset(TEXT_MODEL_ASSET, TEXT_MODEL_BYTES, TEXT_MODEL_SHA256)
        verifyAsset(TEXT_DICTIONARY_ASSET, TEXT_DICTIONARY_BYTES, TEXT_DICTIONARY_SHA256)
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

    /** Debug builds carry both optional files so a clean emulator install needs no network. */
    private fun installBundledFormulaPackageIfPresent(): AiModelInstallState? {
        val bundledNames = appContext.assets.list(DEBUG_FORMULA_ASSETS_DIRECTORY)?.toSet().orEmpty()
        if (FORMULA_ARTIFACTS.none { it.fileName in bundledNames }) return null

        val staging = File(modelsRoot, ".$FORMULA_DIRECTORY-debug-${System.nanoTime()}.part")
        return try {
            modelsRoot.mkdirsOrThrow()
            staging.mkdirsOrThrow()
            FORMULA_ARTIFACTS.forEach { artifact ->
                require(artifact.fileName in bundledNames) { "Debug FormulaNet package is incomplete" }
                copyVerifiedAsset(
                    assetPath = "$DEBUG_FORMULA_ASSETS_DIRECTORY/${artifact.fileName}",
                    destination = File(staging, artifact.fileName),
                    artifact = artifact,
                )
            }
            File(staging, VERIFIED_MARKER).writeText(FORMULA_MARKER)
            installStagedDirectory(staging, formulaDirectory)
            AiModelInstallState.Installed
        } catch (_: Exception) {
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
        private const val MODELS_DIRECTORY = "ai_models"
        private const val FORMULA_DIRECTORY = "pp-formulanet-s-v1"
        private const val VERIFIED_MARKER = ".verified"
        private const val COPY_BUFFER_BYTES = 64 * 1024
        internal const val DEBUG_FORMULA_ASSETS_DIRECTORY = "ai/dev"

        internal const val TEXT_MODEL_ASSET = "ai/en_pp-ocrv5_mobile_rec.onnx"
        internal const val TEXT_DICTIONARY_ASSET = "ai/ppocrv5_en_dict.txt"
        private const val TEXT_MODEL_BYTES = 7_876_014L
        private const val TEXT_DICTIONARY_BYTES = 1_416L
        private const val TEXT_MODEL_SHA256 =
            "8307465d3c9ef2ba4055c3bd0be55aafe11f518630212b7598b70ccb376028ac"
        private const val TEXT_DICTIONARY_SHA256 =
            "e025a66d31f327ba0c232e03f407ae8d105e1e709e7ccb3f408aa778c24e70d6"

        internal const val FORMULA_MODEL_URL =
            "https://github.com/GreatV/oar-ocr/releases/download/v0.3.0/pp-formulanet-s.onnx"
        internal const val FORMULA_TOKENIZER_URL =
            "https://huggingface.co/PaddlePaddle/PP-FormulaNet-L_safetensors/resolve/main/" +
                "tokenizer.json"
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
