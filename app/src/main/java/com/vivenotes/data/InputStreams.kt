package com.vivenotes.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * The next [limit] bytes, or fewer when the stream ends first.
 *
 * What `InputStream.readNBytes(int)` does, which Android only has from 13 on. It reads a chunk at a
 * time rather than allocating [limit] up front, because callers pass a ceiling, one byte past the
 * largest file they accept, that a typical input is far below.
 */
internal fun InputStream.readAtMost(limit: Int): ByteArray {
    require(limit >= 0) { "limit must not be negative: $limit" }
    val out = ByteArrayOutputStream(minOf(limit, READ_CHUNK_BYTES))
    val chunk = ByteArray(minOf(limit, READ_CHUNK_BYTES))
    while (out.size() < limit) {
        val count = read(chunk, 0, minOf(chunk.size, limit - out.size()))
        if (count < 0) break
        out.write(chunk, 0, count)
    }
    return out.toByteArray()
}

private const val READ_CHUNK_BYTES = 16 * 1024
