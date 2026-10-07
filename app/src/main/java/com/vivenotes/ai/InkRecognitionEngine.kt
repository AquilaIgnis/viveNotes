package com.vivenotes.ai

import android.graphics.Bitmap
import android.graphics.Color
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.vivenotes.model.ocr.TextDetection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class TextRecognitionResult(val text: String, val confidence: Float)
data class FormulaRecognitionResult(val latex: String)

/**
 * The app's single recognition boundary — every model it owns is reached through here.
 *
 * Named for the lasso workflow it was built for; it now also serves background picture indexing.
 * One boundary rather than two, because the implementation owns the ONNX
 * sessions, and two owners would mean two copies of the same graph competing for the same cores.
 */
interface InkRecognitionEngine {
    /**
     * How many recognitions run at once. Background callers fan out a little wider than this, so
     * the next bitmap is being prepared while these are in the graph — and no wider, since a caller
     * past that only waits, holding its bitmap.
     */
    val parallelism: Int get() = 1

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

/**
 * Offline PP-OCRv5 and formula inference through Android ONNX Runtime. Formulas go to
 * PP-FormulaNet-S, or to UniMERNet-T in a debug build that carries it — whichever [formulaEngine]
 * names.
 *
 * **Inference runs in [parallelism] lanes, and the work around it runs beside them.** An OCR `run`
 * holds one lane, and the OCR sessions are shared between lanes — `OrtSession.run` is thread-safe,
 * so a second lane costs one more set of activations, not a second copy of the graph. A formula
 * model holds every lane, because opening it closes the OCR sessions and it is 231 MB on its own.
 *
 * Preprocessing, detection post-processing, crops and CTC decoding hold no lane. They used to sit
 * inside one engine-wide mutex with the inference, which made a notebook's pictures and handwriting
 * strictly one item at a time even though the graph was only busy for part of each.
 *
 * [formulaEngine] is asked on every formula rather than fixed. The app reads the store's switch;
 * a test names its model outright, because instrumented tests share the app's process and its
 * stored preferences, and flipping the switch from a test would flip it for the tablet's owner.
 */
class OnnxInkRecognitionEngine(
    private val models: AiModelStore,
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.Default,
    lanes: Int = defaultInferenceLanes(),
    private val formulaEngine: () -> FormulaEngine = { models.state.value.formulaEngine },
) : InkRecognitionEngine, Closeable {
    private val environment = OrtEnvironment.getEnvironment()

    override val parallelism: Int = lanes.coerceAtLeast(1)

    private val lanePermits = Semaphore(parallelism)

    /** Serializes FormulaNet's claim on every lane, so two claims cannot each end up holding half. */
    private val exclusive = Mutex()
    private val sessions = mutableMapOf<ModelKind, OrtSession>()

    override suspend fun recognizeText(image: Bitmap): TextRecognitionResult =
        withContext(inferenceDispatcher) { readLine(image) }

    override suspend fun recognizeFormula(image: Bitmap): FormulaRecognitionResult =
        when (formulaEngine()) {
            FormulaEngine.FormulaNetS -> recognizeWithFormulaNet(image)
            FormulaEngine.UniMerNetTiny -> recognizeWithUniMerNet(image)
        }

    private suspend fun recognizeWithFormulaNet(image: Bitmap): FormulaRecognitionResult =
        withContext(inferenceDispatcher) {
            val files = models.installedFormulaFiles()
                ?: error("PP-FormulaNet-S is not installed")
            val input = preprocessFormula(image)
            everyLane {
                val session = session(ModelKind.Formula)
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

    private suspend fun recognizeWithUniMerNet(image: Bitmap): FormulaRecognitionResult =
        withContext(inferenceDispatcher) {
            val files = models.installedUniMerNetFiles() ?: error("UniMERNet-T is not installed")
            val input = preprocessUniMerNet(image)
            val ids = everyLane { decodeUniMerNet(input) }
            FormulaRecognitionResult(latex = FormulaTokenizerDecoder.decode(files.tokenizer, ids))
        }

    /**
     * Greedy decoding over UniMERNet-T's two graphs, with the decoder's cache carried by hand.
     *
     * FormulaNet-S's export has the generation loop inside its graph. This one does not, so the loop
     * is here. The encoder runs once. Each decoder step takes the last token and the self-attention
     * cache, and returns the next token and the cache one longer. The cache stays in native memory
     * throughout: a result's output tensors go straight back in as the next step's inputs, and a
     * result is closed only once the step after it has finished reading it.
     *
     * The cache shapes come from the graph, with its one dynamic axis, the prefix length, set to
     * zero for the first step. Keys are half as wide as values in this decoder.
     */
    private fun decodeUniMerNet(pixels: FloatArray): List<Long> {
        val encoder = session(ModelKind.UniMerNetEncoder)
        val decoder = session(ModelKind.UniMerNetDecoder)
        val ids = ArrayList<Long>()
        OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(pixels),
            longArrayOf(1, 1, UNIMERNET_HEIGHT.toLong(), UNIMERNET_WIDTH.toLong()),
        ).use { image ->
            encoder.run(mapOf(UNIMERNET_PIXELS to image)).use { encoded ->
                val memory = encoded[0] as OnnxTensor
                val emptyKeys = emptyCache(decoder, UNIMERNET_PAST_KEYS)
                val emptyValues = emptyCache(decoder, UNIMERNET_PAST_VALUES)
                var keys = emptyKeys
                var values = emptyValues
                var previous: OrtSession.Result? = null
                var token = UNIMERNET_START_TOKEN
                try {
                    repeat(UNIMERNET_MAX_TOKENS) {
                        val step = OnnxTensor.createTensor(
                            environment,
                            LongBuffer.wrap(longArrayOf(token)),
                            longArrayOf(1, 1),
                        ).use { tokenTensor ->
                            decoder.run(
                                mapOf(
                                    UNIMERNET_TOKEN to tokenTensor,
                                    UNIMERNET_MEMORY to memory,
                                    UNIMERNET_PAST_KEYS to keys,
                                    UNIMERNET_PAST_VALUES to values,
                                ),
                            )
                        }
                        previous?.close()
                        previous = step
                        token = (step[0].value as LongArray)[0]
                        if (token == UNIMERNET_END_TOKEN) return ids
                        ids += token
                        keys = step[1] as OnnxTensor
                        values = step[2] as OnnxTensor
                    }
                } finally {
                    previous?.close()
                    emptyKeys.close()
                    emptyValues.close()
                }
            }
        }
        return ids
    }

    private fun emptyCache(decoder: OrtSession, input: String): OnnxTensor {
        val info = decoder.inputInfo.getValue(input).info as TensorInfo
        val shape = info.shape.map { if (it < 0) 0L else it }.toLongArray()
        return OnnxTensor.createTensor(environment, FloatBuffer.allocate(0), shape)
    }

    /**
     * Detection, then every line recognized side by side.
     *
     * One line per `run`, not a batch. The recognizer takes a fixed-width tensor, so a batch has to
     * be padded to its widest member, and a picture mixes a 46-pixel crop with a 940-pixel one.
     * Measured over real crops in `simulations/image-ocr/bench.py`, six-wide batches are 30% slower
     * than one at a time and sixteen-wide are 2.2× slower.
     *
     * Only the two graphs hold a lane. Labelling the probability map costs about what detection
     * does, and it, the crops and their tensors are prepared while other lines are in the graph.
     */
    override suspend fun recognizeImageText(image: Bitmap): ImageTextResult =
        withContext(inferenceDispatcher) {
            val input = preprocessDetection(image)
            val probability = inLane {
                val detector = session(ModelKind.Detect)
                OnnxTensor.createTensor(
                    environment,
                    FloatBuffer.wrap(input.values),
                    longArrayOf(1, 3, input.height.toLong(), input.width.toLong()),
                ).use { tensor ->
                    detector.run(mapOf(detector.inputNames.first() to tensor)).use { output ->
                        val probability = FloatArray(input.width * input.height)
                        (output[0] as OnnxTensor).floatBuffer.get(probability)
                        probability
                    }
                }
            }
            val quads = TextDetection.quads(probability, input.width, input.height)
            if (quads.isEmpty()) return@withContext ImageTextResult.Empty

            // Detection ran on the aligned, side-limited copy; the crops come out of the picture at
            // the size it actually is, which is where the resolution the recognizer needs is.
            val scaleX = image.width.toFloat() / input.width
            val scaleY = image.height.toFloat() / input.height
            // Bounds the strips alive at once: a picture with two hundred lines would otherwise crop
            // all two hundred before the first reached the graph.
            val inFlight = Semaphore(parallelism * 2)
            val lines = coroutineScope {
                quads.map { detected ->
                    async {
                        inFlight.withPermit {
                            // A cancelled indexing pass has to stop between lines rather than after
                            // the last of them.
                            ensureActive()
                            val quad = detected.scaled(scaleX, scaleY)
                            val strip = cropQuad(image, quad) ?: return@withPermit null
                            val reading = try {
                                readLine(strip)
                            } finally {
                                strip.recycle()
                            }
                            val text = reading.text.trim()
                            if (isSearchableReading(text) && reading.confidence >= MIN_LINE_CONFIDENCE) {
                                ImageTextLine(text, reading.confidence, quad.corners)
                            } else {
                                null
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            if (lines.isEmpty()) return@withContext ImageTextResult.Empty
            ImageTextResult(
                lines = TextDetection.readingOrder(lines) { line ->
                    TextDetection.Quad(line.corners, line.confidence)
                },
                meanConfidence = lines.map { it.confidence }.average().toFloat(),
            )
        }

    override fun close() {
        synchronized(sessions) {
            sessions.values.forEach(OrtSession::close)
            sessions.clear()
        }
    }

    /** One line through the recognizer: the tensor is built and decoded outside the lane. */
    private suspend fun readLine(image: Bitmap): TextRecognitionResult {
        val input = preprocessText(image)
        val logits = inLane {
            val session = session(ModelKind.Text)
            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(input.values),
                longArrayOf(1, 3, TEXT_HEIGHT.toLong(), input.width.toLong()),
            ).use { tensor ->
                session.run(mapOf(session.inputNames.first() to tensor)).use { output ->
                    // `value` copies out of native memory, so it outlives the closed result.
                    @Suppress("UNCHECKED_CAST")
                    (output[0].value as Array<Array<FloatArray>>)[0]
                }
            }
        }
        return decodeCtc(logits, textCharacters)
    }

    /** Runs [block] holding one inference lane. Every OCR `run` goes through here. */
    private suspend inline fun <T> inLane(block: () -> T): T = lanePermits.withPermit(block)

    /**
     * Runs [block] holding every lane, for FormulaNet.
     *
     * Taken one permit at a time behind [exclusive]. The semaphore is fair, so an OCR line asking
     * after this started queues behind it rather than starving it. Counted, so a cancellation
     * halfway through the claim gives back exactly what it took.
     */
    private suspend fun <T> everyLane(block: () -> T): T {
        var held = 0
        try {
            exclusive.withLock {
                while (held < parallelism) {
                    lanePermits.acquire()
                    held++
                }
            }
            return block()
        } finally {
            repeat(held) { lanePermits.release() }
        }
    }

    /**
     * The session for [kind], opening it if this is the first ask. Called only while holding a lane.
     *
     * **Detection and recognition stay resident together; FormulaNet stays exclusive**. The
     * original rule was one session at a time, which cannot survive a detect-then-recognize pipeline
     * without rebuilding both graphs for every picture. The two OCR graphs are 12 MB between them
     * and are kept; FormulaNet is 231 MB and is the reason a rule existed at all, so it still evicts
     * and is evicted.
     *
     * Eviction never closes a session in use: FormulaNet runs holding every lane, so no OCR `run` is
     * in flight when it evicts them, and an OCR lane evicting FormulaNet means FormulaNet is not
     * running. The lock only stops two lanes opening the same graph at once.
     *
     * The rule is by [ModelKind.family]: opening a graph closes every graph of another family. OCR's
     * two graphs are one family and so are UniMERNet's encoder and decoder, so each pair stays
     * resident together, while either formula model evicts OCR and the other formula model.
     */
    private fun session(kind: ModelKind): OrtSession = synchronized(sessions) {
        sessions[kind] ?: open(kind)
    }

    private fun open(kind: ModelKind): OrtSession {
        sessions.keys.filter { it.family != kind.family }.forEach { sessions.remove(it)?.close() }

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
                ModelKind.UniMerNetEncoder, ModelKind.UniMerNetDecoder -> {
                    val files = models.installedUniMerNetFiles()
                        ?: error("UniMERNet-T is not installed")
                    val file = if (kind == ModelKind.UniMerNetEncoder) files.encoder else files.decoder
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
     * Cached because reading a picture needs it per *line*: re-opening and re-parsing a 436-entry
     * asset for every line of a screenshot was invisible when only a lasso used it. `lazy`'s lock
     * because lines now decode on several threads at once.
     */
    private val textCharacters: List<String> by lazy {
        buildList {
            add("") // CTC blank at index zero.
            models.openTextDictionary().bufferedReader().useLines { lines -> addAll(lines.toList()) }
            add(" ") // Paddle's CTC decoder appends the optional space character.
        }
    }

    /** The graphs that stay resident together — see [session]. */
    private enum class ModelFamily { Ocr, FormulaNet, UniMerNet }

    private enum class ModelKind(val family: ModelFamily) {
        Text(ModelFamily.Ocr),
        Detect(ModelFamily.Ocr),
        Formula(ModelFamily.FormulaNet),
        UniMerNetEncoder(ModelFamily.UniMerNet),
        UniMerNetDecoder(ModelFamily.UniMerNet),
    }

    companion object {
        private const val TEXT_HEIGHT = 48
        private const val TEXT_BASE_WIDTH = 320
        private const val TEXT_MAX_WIDTH = 3200
        private const val FORMULA_SIZE = 384
        private const val INFERENCE_THREADS = 4
        private const val MAX_INFERENCE_LANES = 2

        /** The names `simulations/formula-models/export_unimernet.py` gives the graphs' tensors. */
        private const val UNIMERNET_PIXELS = "pixels"
        private const val UNIMERNET_TOKEN = "token"
        private const val UNIMERNET_MEMORY = "memory"
        private const val UNIMERNET_PAST_KEYS = "past_keys"
        private const val UNIMERNET_PAST_VALUES = "past_values"

        /** `<s>` starts a formula and `</s>` ends it, in the tokenizer FormulaNet shares. */
        private const val UNIMERNET_START_TOKEN = 0L
        private const val UNIMERNET_END_TOKEN = 2L

        /**
         * A ceiling, not a length. The longest formula on the Recognition Test page is 35 tokens.
         * This only stops a model that never emits `</s>` from holding every lane for good.
         */
        private const val UNIMERNET_MAX_TOKENS = 512

        /**
         * One inference lane per [INFERENCE_THREADS] cores, and at most two.
         *
         * Measured in `simulations/image-ocr/lanes.py` over pure inference, one shared session per
         * graph: on eight cores two four-thread lanes finish a pass 1.21× faster than one, while on
         * four cores they are 0.87× — slower, because each `run` already spreads over four threads.
         * Narrower lanes win throughput only by giving up the lasso: one-thread lanes read a single
         * line three times slower. The rest of the speed is in what no longer waits for a lane.
         */
        fun defaultInferenceLanes(
            cores: Int = Runtime.getRuntime().availableProcessors(),
        ): Int = (cores / INFERENCE_THREADS).coerceIn(1, MAX_INFERENCE_LANES)

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
 * The rows are copied one at a time because the two buffers have different strides, and getting
 * that wrong is silent. The resized bitmap is [resizedWidth] wide; the tensor is [width] wide, so
 * any line with an aspect ratio below about 6.7:1 is padded. Walking the pixel array with a single
 * running index writes row r at offset `r * resizedWidth` into a plane whose rows start every
 * `width`, which shears the image diagonally and turns a legible line into noise.
 *
 * Found by reading a picture: of three lines on one bitmap, only the one whose crop happened to be
 * wider than 320 came back. Every narrow lasso selection had the same thing done to it since
 * recognition shipped.
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

/** FormulaNet's margin crop, 384-square white pad and one-channel normalization. */
internal fun preprocessFormula(image: Bitmap): FloatArray {
    require(image.width > 0 && image.height > 0) { "Recognition image is empty" }
    val cropped = cropToInk(image)

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

/**
 * UniMERNet-T's input. It uses the same margin crop, then one bilinear scale to fit 192×672,
 * centred on **black**, then FormulaNet's normalization, because UniMERNet's eval processor uses
 * the same mean and deviation.
 *
 * Black is the opposite of [preprocessFormula], for the same reason: pad with what the model was
 * trained on. UniMERNet's processor pads with `ImageOps.expand`, whose fill is 0.
 *
 * That processor resizes in two steps, short side to 192 and then a thumbnail into 672×192, with a
 * different filter for each. One bilinear scale lands in the same box. `simulations/formula-models`
 * read all 264 of its rasters identically both ways.
 */
internal fun preprocessUniMerNet(image: Bitmap): FloatArray {
    require(image.width > 0 && image.height > 0) { "Recognition image is empty" }
    val cropped = cropToInk(image)
    val scale = min(
        UNIMERNET_HEIGHT.toFloat() / cropped.height,
        UNIMERNET_WIDTH.toFloat() / cropped.width,
    )
    val scaledWidth = (cropped.width * scale).toInt().coerceIn(1, UNIMERNET_WIDTH)
    val scaledHeight = (cropped.height * scale).toInt().coerceIn(1, UNIMERNET_HEIGHT)
    val scaled = Bitmap.createScaledBitmap(cropped, scaledWidth, scaledHeight, true)
    val pixels = IntArray(scaledWidth * scaledHeight)
    scaled.getPixels(pixels, 0, scaledWidth, 0, 0, scaledWidth, scaledHeight)

    val values = FloatArray(UNIMERNET_HEIGHT * UNIMERNET_WIDTH) { (0f - 0.7931f) / 0.1738f }
    val xOffset = (UNIMERNET_WIDTH - scaledWidth) / 2
    val yOffset = (UNIMERNET_HEIGHT - scaledHeight) / 2
    for (y in 0 until scaledHeight) {
        for (x in 0 until scaledWidth) {
            val value = luminance(pixels[y * scaledWidth + x]) / 255f
            values[(y + yOffset) * UNIMERNET_WIDTH + x + xOffset] = (value - 0.7931f) / 0.1738f
        }
    }
    return values
}

/**
 * The bounding box of the ink, as both formula models crop it: luminance stretched to the full
 * range, and anything under 200 of it counted as ink. The image itself when nothing qualifies.
 */
private fun cropToInk(image: Bitmap): Bitmap {
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
    return if (left < right && top < bottom) {
        Bitmap.createBitmap(image, left, top, right - left + 1, bottom - top + 1)
    } else {
        image
    }
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

/** UniMERNet-T's fixed input, the size its eval processor pads every formula to. */
private const val UNIMERNET_HEIGHT = 192
private const val UNIMERNET_WIDTH = 672

/** Paper, on the 0..1 scale the crop is normalized on. See [preprocessFormula]. */
private const val FORMULA_PAD_LEVEL = 1f
