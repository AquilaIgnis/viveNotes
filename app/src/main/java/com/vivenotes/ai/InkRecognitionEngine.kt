package com.vivenotes.ai

import android.graphics.Bitmap
import android.graphics.Color
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class TextRecognitionResult(val text: String, val confidence: Float)
data class FormulaRecognitionResult(val latex: String)

/** Model-agnostic boundary consumed by future lasso and background-indexing workflows. */
interface InkRecognitionEngine {
    suspend fun recognizeText(image: Bitmap): TextRecognitionResult
    suspend fun recognizeFormula(image: Bitmap): FormulaRecognitionResult
}

/** Offline PP-OCRv5 and PP-FormulaNet_plus-S inference through Android ONNX Runtime. */
class OnnxInkRecognitionEngine(
    private val models: AiModelStore,
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : InkRecognitionEngine, Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private val mutex = Mutex()
    private var activeSession: ActiveSession? = null

    override suspend fun recognizeText(image: Bitmap): TextRecognitionResult =
        withContext(inferenceDispatcher) {
            mutex.withLock {
                val session = session(ModelKind.Text)
                val input = preprocessText(image)
                OnnxTensor.createTensor(
                    environment,
                    FloatBuffer.wrap(input.values),
                    longArrayOf(1, 3, TEXT_HEIGHT.toLong(), input.width.toLong()),
                ).use { tensor ->
                    session.run(mapOf(session.inputNames.first() to tensor)).use { output ->
                        @Suppress("UNCHECKED_CAST")
                        val logits = output[0].value as Array<Array<FloatArray>>
                        decodeCtc(logits[0], textCharacters())
                    }
                }
            }
        }

    override suspend fun recognizeFormula(image: Bitmap): FormulaRecognitionResult =
        withContext(inferenceDispatcher) {
            mutex.withLock {
                val files = models.installedFormulaFiles()
                    ?: error("PP-FormulaNet_plus-S is not installed")
                val session = session(ModelKind.Formula)
                val input = preprocessFormula(image)
                OnnxTensor.createTensor(
                    environment,
                    FloatBuffer.wrap(input),
                    longArrayOf(1, 1, FORMULA_SIZE.toLong(), FORMULA_SIZE.toLong()),
                ).use { tensor ->
                    session.run(mapOf(session.inputNames.first() to tensor)).use { output ->
                        @Suppress("UNCHECKED_CAST")
                        val ids = output[0].value as Array<LongArray>
                        FormulaRecognitionResult(
                            latex = FormulaTokenizerDecoder.decode(files.tokenizer, ids[0].toList()),
                        )
                    }
                }
            }
        }

    override fun close() {
        synchronized(this) {
            activeSession?.session?.close()
            activeSession = null
        }
    }

    private fun session(kind: ModelKind): OrtSession {
        activeSession?.takeIf { it.kind == kind }?.let { return it.session }
        activeSession?.session?.close()
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(INFERENCE_THREADS)
        }
        val opened = options.use {
            when (kind) {
                ModelKind.Text -> {
                    val bytes = models.openTextModel().use { input -> input.readBytes() }
                    environment.createSession(bytes, options)
                }
                ModelKind.Formula -> {
                    val file = models.installedFormulaFiles()?.model
                        ?: error("PP-FormulaNet_plus-S is not installed")
                    environment.createSession(file.absolutePath, options)
                }
            }
        }
        activeSession = ActiveSession(kind, opened)
        return opened
    }

    private fun textCharacters(): List<String> = buildList {
        add("") // CTC blank at index zero.
        models.openTextDictionary().bufferedReader().useLines { lines -> addAll(lines.toList()) }
        add(" ") // Paddle's CTC decoder appends the optional space character.
    }

    private data class ActiveSession(val kind: ModelKind, val session: OrtSession)
    private enum class ModelKind { Text, Formula }

    companion object {
        private const val TEXT_HEIGHT = 48
        private const val TEXT_BASE_WIDTH = 320
        private const val TEXT_MAX_WIDTH = 3200
        private const val FORMULA_SIZE = 384
        private const val INFERENCE_THREADS = 4
    }
}

internal data class TextTensor(val values: FloatArray, val width: Int)

/** PP-OCRv5 line resize, BGR channel order, `[-1, 1]` normalization and zero padding. */
internal fun preprocessText(image: Bitmap): TextTensor {
    require(image.width > 0 && image.height > 0) { "Recognition image is empty" }
    val ratio = image.width.toFloat() / image.height
    val width = max(TEXT_BASE_WIDTH_FOR_PREPROCESS, min(TEXT_MAX_WIDTH_FOR_PREPROCESS, ceil(48f * ratio).toInt()))
    val resizedWidth = min(width, ceil(48f * ratio).toInt().coerceAtLeast(1))
    val resized = Bitmap.createScaledBitmap(image, resizedWidth, 48, true)
    val pixels = IntArray(resizedWidth * 48)
    resized.getPixels(pixels, 0, resizedWidth, 0, 0, resizedWidth, 48)

    val plane = 48 * width
    val values = FloatArray(plane * 3)
    pixels.forEachIndexed { index, color ->
        values[index] = normalizeText(Color.blue(color))
        values[plane + index] = normalizeText(Color.green(color))
        values[plane * 2 + index] = normalizeText(Color.red(color))
    }
    return TextTensor(values, width)
}

/** FormulaNet's margin crop, 384-square black pad and one-channel normalization. */
internal fun preprocessFormula(image: Bitmap): FloatArray {
    require(image.width > 0 && image.height > 0) { "Recognition image is empty" }
    val sourcePixels = IntArray(image.width * image.height)
    image.getPixels(sourcePixels, 0, image.width, 0, 0, image.width, image.height)
    val gray = IntArray(sourcePixels.size) { index -> luminance(sourcePixels[index]) }
    val low = gray.minOrNull() ?: 0
    val high = gray.maxOrNull() ?: 0

    var left = image.width
    var top = image.height
    var right = -1
    var bottom = -1
    if (high > low) {
        gray.forEachIndexed { index, value ->
            val normalized = ((value - low).toFloat() / (high - low) * 255f).toInt()
            if (normalized < 200) {
                val x = index % image.width
                val y = index / image.width
                left = min(left, x)
                top = min(top, y)
                right = max(right, x)
                bottom = max(bottom, y)
            }
        }
    }
    val cropped = if (left < right && top < bottom) {
        Bitmap.createBitmap(image, left, top, right - left + 1, bottom - top + 1)
    } else {
        image
    }

    val scale = FORMULA_SIZE_FOR_PREPROCESS.toFloat() / max(cropped.width, cropped.height)
    val scaledWidth = min(FORMULA_SIZE_FOR_PREPROCESS, (cropped.width * scale).toInt().coerceAtLeast(1))
    val scaledHeight = min(FORMULA_SIZE_FOR_PREPROCESS, (cropped.height * scale).toInt().coerceAtLeast(1))
    val scaled = Bitmap.createScaledBitmap(cropped, scaledWidth, scaledHeight, true)
    val scaledPixels = IntArray(scaledWidth * scaledHeight)
    scaled.getPixels(scaledPixels, 0, scaledWidth, 0, 0, scaledWidth, scaledHeight)

    val values = FloatArray(FORMULA_SIZE_FOR_PREPROCESS * FORMULA_SIZE_FOR_PREPROCESS) {
        (0f - 0.7931f) / 0.1738f
    }
    val xOffset = (FORMULA_SIZE_FOR_PREPROCESS - scaledWidth) / 2
    val yOffset = (FORMULA_SIZE_FOR_PREPROCESS - scaledHeight) / 2
    for (y in 0 until scaledHeight) {
        for (x in 0 until scaledWidth) {
            val value = luminance(scaledPixels[y * scaledWidth + x]) / 255f
            values[(y + yOffset) * FORMULA_SIZE_FOR_PREPROCESS + x + xOffset] =
                (value - 0.7931f) / 0.1738f
        }
    }
    return values
}

private fun decodeCtc(logits: Array<FloatArray>, characters: List<String>): TextRecognitionResult {
    val result = StringBuilder()
    var previous = 0
    var confidence = 0f
    var charactersRead = 0
    logits.forEach { row ->
        var bestIndex = 0
        var bestScore = Float.NEGATIVE_INFINITY
        row.forEachIndexed { index, score ->
            if (score > bestScore) {
                bestScore = score
                bestIndex = index
            }
        }
        if (bestIndex != 0 && bestIndex != previous && bestIndex < characters.size) {
            result.append(characters[bestIndex])
            confidence += bestScore
            charactersRead++
        }
        previous = bestIndex
    }
    return TextRecognitionResult(
        text = result.toString(),
        confidence = if (charactersRead == 0) 0f else confidence / charactersRead,
    )
}

private fun normalizeText(channel: Int): Float = (channel / 255f - 0.5f) / 0.5f

private fun luminance(color: Int): Int =
    (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)).toInt()

private const val TEXT_BASE_WIDTH_FOR_PREPROCESS = 320
private const val TEXT_MAX_WIDTH_FOR_PREPROCESS = 3200
private const val FORMULA_SIZE_FOR_PREPROCESS = 384
