package com.vivenotes.data

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Base64
import java.util.UUID

/**
 * The system clipboard as the canvas reads it: another app's text, HTML and pictures, and the claim
 * an in-app object copy stakes on it so that a later copy elsewhere takes precedence.
 *
 * Android lets only the app in focus read the clipboard, and tells the user each time one does.
 * [hasContent] and [ownsInternalCopy] look at the clip's description alone, which it does not.
 */
class SystemClipboardContent(
    private val context: Context,
    private val download: suspend (String) -> ByteArray? = { PictureHttp(allowHttp = true).image(it, MAX_BYTES) },
) : CanvasClipboardSource {
    private val clipboard: ClipboardManager? get() = context.getSystemService(ClipboardManager::class.java)
    @Volatile private var token: String? = null

    override fun hasContent(): Boolean = safely(false) {
        val description = clipboard?.primaryClipDescription ?: return@safely false
        (0 until description.mimeTypeCount).map(description::getMimeType).any { type ->
            type != INTERNAL_MIME && type != ClipDescription.MIMETYPE_TEXT_INTENT &&
                (type.startsWith("text/") || type.startsWith("image/"))
        }
    }

    override suspend fun readContent(): ClipboardContent = withContext(Dispatchers.IO) {
        val clip = safely(null) { clipboard?.primaryClip } ?: return@withContext ClipboardContent()
        if (clip.description.hasMimeType(INTERNAL_MIME) || clip.itemCount == 0) return@withContext ClipboardContent()
        val items = (0 until clip.itemCount).map(clip::getItemAt)
        val first = items.first()
        val pictureUri = items.mapNotNull { it.uri }.firstOrNull { it.scheme == "content" }
        val linkUri = items.mapNotNull { it.uri }.firstOrNull { it.scheme == "https" || it.scheme == "http" }
        ClipboardContent(
            text = safely(null) { first.text?.toString()?.takeIf { it.length <= MAX_CHARS } },
            html = safely(null) { first.htmlText?.takeIf { it.length <= MAX_CHARS } },
            image = pictureUri?.let(::pictureBytes),
            imageUrl = linkUri?.toString(),
        ).also {
            DebugLog.i(DebugLog.CLIPBOARD) {
                "canvas paste: text=${it.text != null}, html=${it.html != null}, image=${it.image != null}, url=${it.imageUrl != null}"
            }
        }
    }

    override suspend fun imageBytes(address: String): ByteArray? = withContext(Dispatchers.IO) {
        if (address.startsWith("data:image/", true)) {
            return@withContext safely(null) {
                val comma = address.indexOf(',')
                if (comma < 0 || !address.substring(0, comma).endsWith(";base64", true) || address.length > MAX_BYTES * 2) null
                else Base64.getMimeDecoder().decode(address.substring(comma + 1)).takeIf { it.size <= MAX_BYTES }
            }
        }
        val uri = safely<Uri?>(null) { Uri.parse(address) } ?: return@withContext null
        if (uri.scheme?.lowercase() !in setOf("https", "http") || uri.host.isNullOrEmpty()) return@withContext null
        try {
            download(address)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            DebugLog.i(DebugLog.CLIPBOARD) { "picture fetch failed: ${error.javaClass.simpleName}" }
            null
        }
    }

    override fun markInternalCopy() {
        val copied = UUID.randomUUID().toString()
        token = copied
        safely(Unit) {
            clipboard?.setPrimaryClip(ClipData(ClipDescription(copied, arrayOf(INTERNAL_MIME)), ClipData.Item("")))
        }
    }

    override fun ownsInternalCopy(): Boolean = safely(false) {
        val expected = token ?: return@safely false
        val description = clipboard?.primaryClipDescription ?: return@safely false
        description.hasMimeType(INTERNAL_MIME) && description.label?.toString() == expected
    }

    /** Calls [onChanged] whenever another copy replaces the clip, while this app has the focus. */
    fun observe(onChanged: () -> Unit): AutoCloseable {
        val listener = ClipboardManager.OnPrimaryClipChangedListener { onChanged() }
        clipboard?.addPrimaryClipChangedListener(listener)
        return AutoCloseable { clipboard?.removePrimaryClipChangedListener(listener) }
    }

    /** A picture another app shared through the clip, read through the grant the clip carries. */
    private fun pictureBytes(uri: Uri): ByteArray? = safely(null) {
        val resolver = context.contentResolver
        if (resolver.getType(uri)?.startsWith("image/") != true) return@safely null
        resolver.openInputStream(uri)?.use { it.readBounded() }
    }

    private fun InputStream.readBounded(): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = read(buffer)
            if (count < 0) return out.toByteArray().takeIf { it.isNotEmpty() }
            if (out.size() + count > MAX_BYTES) return null
            out.write(buffer, 0, count)
        }
    }

    private inline fun <T> safely(fallback: T, read: () -> T): T = try { read() } catch (error: Exception) {
        // Clipboard owners and URLs can contain document content; log only the failure's type.
        DebugLog.i(DebugLog.CLIPBOARD) { "clipboard operation failed: ${error.javaClass.simpleName}" }
        fallback
    }

    companion object {
        internal const val MAX_BYTES = 64 * 1024 * 1024
        private const val MAX_CHARS = 4 * 1024 * 1024
        private const val INTERNAL_MIME = "application/x-vivenotes-canvas-copy"
    }
}
