package com.vivenotes.ai

import android.graphics.Bitmap
import android.graphics.Color
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.vivenotes.model.ocr.TextDetection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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

/**
 * The app's single recognition boundary — every model it owns is reached through here.
 *
 * Named for the lasso workflow it was built for; it now also serves background picture indexing
 * (`memory/imageOcrPlan.md`). One boundary rather than two because the implementation owns the ONNX
 * sessions, and two owners would mean two copies of the same graph competing for the same cores.
 */
interface InkRecognitionEngine {
    suspend fun recognizeText(image: Bitmap): TextRecognitionResult
    suspend fun recognizeFormula(image: Bitmap): FormulaRecognitionResult

    /**
     * Reads every line of text in a whole picture — detection followed by recognition.
     *
     * Distinct from [recognizeText], which reads a bitmap that is already **one line**. Handing a
     * screenshot to that method returns nothing usable: it squeezes the whole picture into a
     * 48-pixel strip.
     */
    suspend fun recognizeImageText(image: Bitmap): ImageTextResult
}

/** Offline PP-OCRv5 and PP-FormulaNet-S inference through Android ONNX Runtime. */
class OnnxInkRecognitionEngine(
    private val models: AiModelStore,
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : InkRecognitionEngine, Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private val mutex = Mutex()
    private val sessions = mutableMapOf<ModelKind, OrtSession>()

    override suspend fun recognizeText(image: Bitmap): TextRecognitionResult =
        withContext(inferenceDispatcher) {
            mutex.withLock {
                runText(session(ModelKind.Text), image, textCharacters())
            }
        }

    override suspend fun recognizeFormula(image: Bitmap): FormulaRecognitionResult =
        withContext(inferenceDispatcher) {
            mutex.withLock {
                val files = models.installedFormulaFiles()
                    ?: error("PP-FormulaNet-S is not installed")
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

    /**
     * Detection followed by line-by-line recognition — `memory/imageOcrPlan.md` IO4, IO7.
     *
     * **One line per `run`, not a batch.** The recognizer takes a fixed-width tensor, so a batch has
     * to be padded to its widest member, and a picture mixes a 46-pixel crop with a 940-pixel one.
     * Measured over real crops in `simulations/image-ocr/bench.py`, six-wide batches are 30% slower
     * than one at a time and sixteen-wide are 2.2× slower. Running singly is also what lets the one
     * mutex above serialize every model this class owns.
     *
     * The whole call holds the lock, which is deliberate: a picture is background work, and letting
     * it interleave with a lasso recognition would only make the interactive one wait in the middle
     * rather than at the start.
     */
    override suspend fun recognizeImageText(image: Bitmap): ImageTextResult =
        withContext(inferenceDispatcher) {
            val scope = this
            // Outside the lock: decoding and normalizing a picture is the half of this that can
            // overlap another picture's inference, and IO7 is built on it doing so.
            val input = preprocessDetection(image)
            mutex.withLock {
                val detector = session(ModelKind.Detect)
                val quads = OnnxTensor.createTensor(
                    environment,
                    FloatBuffer.wrap(input.values),
                    longArrayOf(1, 3, input.height.toLong(), input.width.toLong()),
                ).use { tensor ->
                    detector.run(mapOf(detector.inputNames.first() to tensor)).use { output ->
                        val map = (output[0] as OnnxTensor).floatBuffer
                        val probability = FloatArray(input.width * input.height)
                        map.get(probability)
                        TextDetection.quads(probability, input.width, input.height)
                    }
                }
                if (quads.isEmpty()) return@withLock ImageTextResult.Empty

                // Detection ran on the aligned, side-limited copy; the crops come out of the picture
                // at the size it actually is, which is where the resolution the recognizer needs is.
                val scaleX = image.width.toFloat() / input.width
                val scaleY = image.height.toFloat() / input.height
                val recognizer = session(ModelKind.Text)
                val characters = textCharacters()
                val lines = mutableListOf<ImageTextLine>()
                quads.forEach { detected ->
                    // A picture with two hundred lines is two hundred inferences; a cancelled
                    // indexing pass has to stop between them rather than at the end of them.
                    scope.ensureActive()
                    val quad = detected.scaled(scaleX, scaleY)
                    val strip = cropQuad(image, quad) ?: return@forEach
                    val reading = try {
                        runText(recognizer, strip, characters)
                    } finally {
                        strip.recycle()
                    }
                    val text = reading.text.trim()
                    if (isSearchableReading(text) && reading.confidence >= MIN_LINE_CONFIDENCE) {
                        lines += ImageTextLine(text, reading.confidence, quad.corners)
                    }
                }
                if (lines.isEmpty()) return@withLock ImageTextResult.Empty
                ImageTextResult(
                    lines = TextDetection.readingOrder(lines) { line ->
                        TextDetection.Quad(line.corners, line.confidence)
                    },
                    meanConfidence = lines.map { it.confidence }.average().toFloat(),
                )
            }
        }

    override fun close() {
        synchronized(this) {
            sessions.values.forEach(OrtSession::close)
            sessions.clear()
        }
    }

    private fun runText(
        session: OrtSession,
        image: Bitmap,
        characters: List<String>,
    ): TextRecognitionResult {
        val input = preprocessText(image)
        return OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(input.values),
            longArrayOf(1, 3, TEXT_HEIGHT.toLong(), input.width.toLong()),
        ).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { output ->
                @Suppress("UNCHECKED_CAST")
                val logits = output[0].value as Array<Array<FloatArray>>
                decodeCtc(logits[0], characters)
            }
        }
    }

    /**
     * The session for [kind], opening it if this is the first ask.
     *
     * **Detection and recognition stay resident together; FormulaNet stays exclusive** — IO8. The
     * original rule was one session at a time, which cannot survive a detect-then-recognize pipeline
     * without rebuilding both graphs for every picture. The two OCR graphs are 12 MB between them
     * and are kept; FormulaNet is 231 MB and is the reason a rule existed at all, so it still evicts
     * and is evicted.
     */
    private fun session(kind: ModelKind): OrtSession {
        sessions[kind]?.let { return it }
        val evicted = when (kind) {
            ModelKind.Formula -> sessions.keys.filterNot { it == ModelKind.Formula }
            ModelKind.Text, ModelKind.Detect -> listOf(ModelKind.Formula)
        }
        evicted.forEach { sessions.remove(it)?.close() }

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
                ModelKind.Detect -> {
                    val bytes = models.openDetectionModel().use { input -> input.readBytes() }
                    environment.createSession(bytes, options)
                }
                ModelKind.Formula -> {
                    val file = models.installedFormulaFiles()?.model
                        ?: error("PP-FormulaNet-S is not installed")
                    environment.createSession(file.absolutePath, options)
                }
            }
        }
        sessions[kind] = opened
        return opened
    }

    /**
     * The CTC alphabet, read once.
     *
     * Cached because reading a picture calls this per *line*: re-opening and re-parsing a 436-entry
     * asset for every line of a screenshot was invisible when only a lasso used it.
     */
    private fun textCharacters(): List<String> = characters ?: buildList {
        add("") // CTC blank at index zero.
        models.openTextDictionary().bufferedReader().useLines { lines -> addAll(lines.toList()) }
        add(" ") // Paddle's CTC decoder appends the optional space character.
    }.also { characters = it }

    private var characters: List<String>? = null

    private enum class ModelKind { Text, Detect, Formula }

    companion object {
        private const val TEXT_HEIGHT = 48
        private const val TEXT_BASE_WIDTH = 320
        private const val TEXT_MAX_WIDTH = 3200
        private const val FORMULA_SIZE = 384
        private const val INFERENCE_THREADS = 4

        /**
         * How sure the recognizer has to be before a line is worth indexing.
         *
         * The score is the mean probability of the characters it emitted. Low readings on a
         * photograph are usually texture read as letters, and a search index made of those is worse
         * than one without them — a false hit sends someone to a page that does not contain what
         * they looked for.
         */
        private const val MIN_LINE_CONFIDENCE = 0.5f
    }
}

internal data class TextTensor(val values: FloatArray, val width: Int)

/**
 * PP-OCRv5 line resize, BGR channel order, `[-1, 1]` normalization and zero padding.
 *
 * **The rows are copied one at a time because the two buffers have different strides**, and getting
 * that wrong is silent. The resized bitmap is [resizedWidth] wide; the tensor is [width] wide, which
 * is at least [TEXT_BASE_WIDTH_FOR_PREPROCESS], so any line with an aspect ratio below about 6.7:1
 * is padded. Walking the pixel array with a single running index — as this did until 2026-08-13 —
 * writes row *r* at offset `r * resizedWidth` into a plane whose rows start every `width`, which
 * shears the image diagonally by a few pixels per row and turns a legible line into noise.
 *
 * It was found by reading a picture: of three lines drawn on one bitmap, only the one whose crop
 * happened to be wider than 320 came back, and it came back perfectly. The other two were sheared.
 * Every narrow lasso selection had the same thing done to it since recognition shipped.
 *
 * The padding stays zero, which is mid-grey once `[-1, 1]` normalization is undone, and is what
 * PaddleOCR pads with after its own normalization.
 */
internal fun preprocessText(image: Bitmap): TextTensor {
    require(image.width > 0 && image.height > 0) { "Recognition image is empty" }
    val ratio = image.width.toFloat() / image.height
    val width = max(TEXT_BASE_WIDTH_FOR_PREPROCESS, min(TEXT_MAX_WIDTH_FOR_PREPROCESS, ceil(48f * ratio).toInt()))
    val resizedWidth = min(width, ceil(48f * ratio).toInt().coerceAtLeast(1))
    val resized = Bitmap.createScaledBitmap(image, resizedWidth, 48, true)
    val pixels = IntArray(resizedWidth * 48)
    resized.getPixels(pixels, 0, resizedWidth, 0, 0, resizedWidth, 48)
    if (resized !== image) resized.recycle()

    val plane = 48 * width
    val values = FloatArray(plane * 3)
    for (y in 0 until 48) {
        val source = y * resizedWidth
        val target = y * width
        for (x in 0 until resizedWidth) {
            val color = pixels[source + x]
            values[target + x] = normalizeText(Color.blue(color))
            values[plane + target + x] = normalizeText(Color.green(color))
            values[plane * 2 + target + x] = normalizeText(Color.red(color))
        }
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

    // **Padded white, the colour of the page it surrounds.** This filled the square with black and
    // then pasted a white crop into the middle of it, so every formula arrived framed in a hard
    // border the model had never seen in training — the normalization mean of 0.79 is itself the
    // statement that these images are mostly white. Measured over page 3, padding white is worth
    // about +0.08 mean token accuracy on its own: `simulations/formula-render`.
    val values = FloatArray(FORMULA_SIZE_FOR_PREPROCESS * FORMULA_SIZE_FOR_PREPROCESS) {
        (FORMULA_PAD_LEVEL - 0.7931f) / 0.1738f
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

/** Paper, on the 0..1 scale the crop is normalized on. See [preprocessFormula]. */
private const val FORMULA_PAD_LEVEL = 1f
