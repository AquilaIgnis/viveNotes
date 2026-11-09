package com.vivenotes.fileimport

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import com.vivenotes.fileimport.docx.DocxException
import com.vivenotes.fileimport.docx.DocxFailure
import com.vivenotes.fileimport.docx.DocxPackage
import com.vivenotes.fileimport.docx.DocxPages
import com.vivenotes.model.Mark
import com.vivenotes.richtext.CanvasPasteItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.math.roundToInt

/** How the chosen pages land on the canvas. */
enum class FileImportMode { Objects, Pictures }

class ImportedPageSize(val widthDp: Float, val heightDp: Float)

/** A document open in the importer: its name and the size of each page. */
class ImportedFile internal constructor(
    val name: String,
    val pages: List<ImportedPageSize>,
    internal val token: Any,
)

/**
 * Brings other apps' documents in — the file half of the File tab's Import.
 *
 * Shaped like `PdfExporter` for the same reason: the dialog needs its three steps separately — [open]
 * says how many pages there are, [preview] draws one, and the insert asks for [pagePicture] or
 * [objects]. A document is held open between them, so one job at a time, and [release] when the window
 * closes lets go of its pictures and its staged copy.
 *
 * The rest of the app only sees pages, so another format is another thing this opens.
 */
class FileImporter(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()

    private var open: OpenDocument? = null

    /** Set when the window closes mid-render, so the job in flight shuts the document on its way out. */
    @Volatile
    private var releaseRequested = false

    private class OpenDocument(val pages: DocxPages, val staged: File?, val token: Any = Any())

    private val stagingDirectory: File
        get() = File(appContext.cacheDir, "file-import").apply { mkdirs() }

    /**
     * Reads the document at [source].
     *
     * Copied in first because a Storage Access Framework stream is not seekable and a zip needs to be;
     * it also means nothing after this line depends on the provider staying reachable.
     */
    suspend fun open(source: Uri): ImportedFile = withContext(dispatcher) {
        mutex.withLock {
            closeOpen()
            // Whatever a killed process left here is nobody's; only one document is ever open.
            stagingDirectory.listFiles()?.forEach(File::delete)
            val staged = File(stagingDirectory, UUID.randomUUID().toString())
            try {
                val input = appContext.contentResolver.openInputStream(source)
                    ?: throw DocxException(DocxFailure.Unreadable)
                input.use { copyBounded(it, staged) }
                openStaged(staged, displayNameOf(source))
            } catch (failure: Throwable) {
                staged.delete()
                throw translated(failure)
            }
        }
    }

    /** The test seam: opens a file already on disk, which the importer does not own. */
    internal suspend fun openFile(file: File, name: String): ImportedFile = withContext(dispatcher) {
        mutex.withLock {
            closeOpen()
            try {
                val pages = DocxPages.open(appContext, DocxPackage.open(file))
                remember(pages, null, name)
            } catch (failure: Throwable) {
                throw translated(failure)
            }
        }
    }

    private suspend fun openStaged(staged: File, name: String): ImportedFile {
        val pages = DocxPages.open(appContext, DocxPackage.open(staged))
        return remember(pages, staged, name)
    }

    private fun remember(pages: DocxPages, staged: File?, name: String): ImportedFile {
        val document = OpenDocument(pages, staged)
        open = document
        return ImportedFile(
            name = name,
            pages = pages.sheets.map { ImportedPageSize(it.widthDp, it.heightDp) },
            token = document.token,
        )
    }

    /** The file's [DocxFailure], or [DocxFailure.Unreadable] for the many ways bad input can blow up. */
    private fun translated(failure: Throwable): Throwable = when (failure) {
        is DocxException, is kotlinx.coroutines.CancellationException -> failure
        is OutOfMemoryError -> DocxException(DocxFailure.TooLarge, failure)
        is StackOverflowError, is Exception -> DocxException(DocxFailure.Unreadable, failure)
        else -> failure
    }

    private fun copyBounded(input: InputStream, target: File) {
        target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > DocxPackage.MAX_FILE_BYTES) throw DocxException(DocxFailure.TooLarge)
                output.write(buffer, 0, read)
            }
        }
    }

    private fun displayNameOf(source: Uri): String = runCatching {
        appContext.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: source.lastPathSegment.orEmpty()

    /** Page [index] as a bitmap [widthPx] wide, or null when [file] is no longer the open document. */
    suspend fun preview(file: ImportedFile, index: Int, widthPx: Int): Bitmap? = job {
        pagesOf(file)?.render(index, widthPx)
    }

    private suspend fun <T> job(action: () -> T): T = withContext(dispatcher) {
        mutex.withLock {
            try {
                action()
            } finally {
                if (releaseRequested) closeOpen()
            }
        }
    }

    /**
     * Page [index] as a picture to keep: as sharp as the attachment store will store, sized so its
     * longest side is exactly that, since anything sharper is only thrown away again on the way in.
     */
    suspend fun pagePicture(file: ImportedFile, index: Int): Bitmap? = job {
        file.pages.getOrNull(index)?.let { page ->
            val width = (PICTURE_LONGEST_PX * page.widthDp / maxOf(page.widthDp, page.heightDp)).roundToInt()
            pagesOf(file)?.render(index, width.coerceAtLeast(1))
        }
    }

    /** The chosen pages as canvas objects, or an empty list when [file] is no longer open. */
    internal suspend fun objects(file: ImportedFile, pages: List<Int>, marks: Set<Mark>): List<CanvasPasteItem> = job {
        pagesOf(file)?.items(pages, marks).orEmpty()
    }

    /** Hands [action] the open document's pages, for the layout tests. */
    internal suspend fun <T> withPages(file: ImportedFile, action: (DocxPages) -> T): T? = job {
        pagesOf(file)?.let(action)
    }

    /** Closes the document. Called when the window closes. */
    fun release() {
        // Not waited for, because this runs on the main thread as the window goes. A job in flight
        // sees the flag and closes the document itself.
        releaseRequested = true
        if (mutex.tryLock()) {
            try {
                closeOpen()
            } finally {
                mutex.unlock()
            }
        }
    }

    private fun pagesOf(file: ImportedFile): DocxPages? = open?.takeIf { it.token === file.token }?.pages

    private fun closeOpen() {
        releaseRequested = false
        open?.let { document ->
            runCatching { document.pages.close() }
            document.staged?.delete()
        }
        open = null
    }

    companion object {
        /** Only Word for now; the picker will offer more as they are built. */
        val MIME_TYPES: Array<String> = arrayOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        )

        /** The longest side a page picture is rendered at, which is what the store keeps. */
        const val PICTURE_LONGEST_PX = 2048
    }
}
