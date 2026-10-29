package com.vivenotes.data

data class ClipboardContent(
    val text: String? = null,
    val html: String? = null,
    val image: ByteArray? = null,
    val imageUrl: String? = null,
)

/** The system clipboard as the canvas reads it, including browser HTML and copied pictures. */
interface CanvasClipboardSource {
    fun hasContent(): Boolean
    suspend fun readContent(): ClipboardContent
    suspend fun imageBytes(address: String): ByteArray?

    /** Claims the system clipboard for an in-app object copy, without exposing document contents. */
    fun markInternalCopy()
    fun ownsInternalCopy(): Boolean
}

/** What a picture's file or address ends in, for the formats `ImageDecoder` reads. */
val PictureFileExtensions = listOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "ico", "wbmp")
