package com.vivenotes.fileimport.pdf

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.graphics.pdf.RenderParams
import android.graphics.pdf.models.selection.SelectionBoundary
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresExtension
import java.io.Closeable

/**
 * Which of the platform's PDF renderers reads this device's files.
 *
 * Reading where a page's text is arrived with Android 15's `PdfRenderer`. Android 11 to 14 get the
 * same calls as `PdfRendererPreV` through a system update, SDK extension 13. Without either, a page
 * can only be drawn, so an import brings each one in as a picture.
 */
internal enum class PdfBackend(val readsText: Boolean) {
    Platform(readsText = true),
    PreV(readsText = true),
    PicturesOnly(readsText = false),
    ;

    companion object {
        fun forThisDevice(): PdfBackend = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> Platform
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= PREV_EXTENSION -> PreV
            else -> PicturesOnly
        }

        /** Every backend this device can run. Android 15 also carries `PdfRendererPreV`. */
        fun onThisDevice(): List<PdfBackend> = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) add(Platform)
            if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= PREV_EXTENSION) add(PreV)
            add(PicturesOnly)
        }
    }
}

/** A PDF open in one of the [PdfBackend]s; only one coroutine at a time, as the renderers require. */
internal interface PdfSource : Closeable {
    val pageCount: Int
    val readsText: Boolean
    fun openPage(index: Int): PdfSourcePage
}

internal interface PdfSourcePage : Closeable {
    /** In points, 1/72 of an inch. */
    val width: Int
    val height: Int

    /** Null where this renderer cannot read text, and the page is only a picture. */
    val text: PdfPageText?

    /** Draws the page into [bitmap] through [transform]; what is blank on the page stays untouched. */
    fun render(bitmap: Bitmap, transform: Matrix)
}

/**
 * What a page says about its text, as plain values, so that [PdfPages] reads it the same way from
 * either renderer and the version-gated model classes stay behind these two implementations.
 */
internal interface PdfPageText {
    /** All of the page's text, in the renderer's reading order. */
    fun text(): String

    /** What selecting the characters from [index] to the next finds: one character and its box. */
    fun selected(index: Int): PdfSelected?

    fun links(): List<PdfLink>
}

internal class PdfSelected(val char: Char, val box: RectF)

internal class PdfLink(val uri: String, val boxes: List<RectF>)

/** Opens [descriptor] with [backend]; the source does not close the descriptor. */
internal fun openPdfSource(descriptor: ParcelFileDescriptor, backend: PdfBackend): PdfSource = when (backend) {
    PdfBackend.Platform -> PlatformPdfSource(descriptor, readsText = true)
    PdfBackend.PicturesOnly -> PlatformPdfSource(descriptor, readsText = false)
    PdfBackend.PreV ->
        if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= PREV_EXTENSION) {
            PreVPdfSource(descriptor)
        } else {
            throw IllegalStateException("PdfRendererPreV needs S extension $PREV_EXTENSION")
        }
}

/** `PdfRenderer`, which reads text from Android 15 on and draws pages on every version. */
private class PlatformPdfSource(descriptor: ParcelFileDescriptor, readsText: Boolean) : PdfSource {
    private val renderer = PdfRenderer(descriptor)
    override val readsText: Boolean =
        readsText && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
    override val pageCount: Int get() = renderer.pageCount
    override fun openPage(index: Int): PdfSourcePage = PlatformPdfPage(renderer.openPage(index), readsText)
    override fun close() = renderer.close()
}

private class PlatformPdfPage(private val page: PdfRenderer.Page, readsText: Boolean) : PdfSourcePage {
    override val width: Int get() = page.width
    override val height: Int get() = page.height
    override val text: PdfPageText? =
        if (readsText && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) PlatformPdfText(page) else null

    override fun render(bitmap: Bitmap, transform: Matrix) =
        page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

    override fun close() = page.close()
}

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
private class PlatformPdfText(private val page: PdfRenderer.Page) : PdfPageText {
    override fun text(): String = page.textContents.joinToString("") { it.text }

    override fun selected(index: Int): PdfSelected? =
        page.selectContent(SelectionBoundary(index), SelectionBoundary(index + 1))
            ?.selectedTextContents
            ?.firstOrNull { it.bounds.isNotEmpty() && it.text.isNotEmpty() }
            ?.let { PdfSelected(it.text.first(), it.bounds.first()) }

    override fun links(): List<PdfLink> = page.linkContents.map { PdfLink(it.uri.toString(), it.bounds) }
}

/** `PdfRendererPreV`: the Android 15 calls, on Android 11 to 14 with S extension 13. */
@RequiresExtension(extension = Build.VERSION_CODES.S, version = PREV_EXTENSION)
private class PreVPdfSource(descriptor: ParcelFileDescriptor) : PdfSource {
    private val renderer = PdfRendererPreV(descriptor)
    override val readsText: Boolean = true
    override val pageCount: Int get() = renderer.pageCount
    override fun openPage(index: Int): PdfSourcePage = PreVPdfPage(renderer.openPage(index))
    override fun close() = renderer.close()
}

@RequiresExtension(extension = Build.VERSION_CODES.S, version = PREV_EXTENSION)
private class PreVPdfPage(private val page: PdfRendererPreV.Page) : PdfSourcePage {
    override val width: Int get() = page.width
    override val height: Int get() = page.height
    override val text: PdfPageText = PreVPdfText(page)

    // What `PdfRenderer.Page.render` builds for its mode, so both renderers draw a page alike.
    override fun render(bitmap: Bitmap, transform: Matrix) =
        page.render(bitmap, null, transform, RenderParams.Builder(RenderParams.RENDER_MODE_FOR_DISPLAY).build())

    override fun close() = page.close()
}

@RequiresExtension(extension = Build.VERSION_CODES.S, version = PREV_EXTENSION)
private class PreVPdfText(private val page: PdfRendererPreV.Page) : PdfPageText {
    override fun text(): String = page.textContents.joinToString("") { it.text }

    override fun selected(index: Int): PdfSelected? =
        page.selectContent(SelectionBoundary(index), SelectionBoundary(index + 1))
            ?.selectedTextContents
            ?.firstOrNull { it.bounds.isNotEmpty() && it.text.isNotEmpty() }
            ?.let { PdfSelected(it.text.first(), it.bounds.first()) }

    override fun links(): List<PdfLink> = page.linkContents.map { PdfLink(it.uri.toString(), it.bounds) }
}

/** The S extension version that brought `PdfRendererPreV`. */
private const val PREV_EXTENSION = 13
