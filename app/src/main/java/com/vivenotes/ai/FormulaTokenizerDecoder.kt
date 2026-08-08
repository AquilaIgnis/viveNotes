package com.vivenotes.ai

import android.util.JsonReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileReader
import java.nio.charset.StandardCharsets

/** Decodes only the token ids emitted for one formula, without retaining the 50k-token vocabulary. */
internal object FormulaTokenizerDecoder {
    fun decode(tokenizer: File, tokenIds: List<Long>): String {
        val ids = tokenIds
            .takeWhile { it != EOS_TOKEN_ID && it in 0 until VOCAB_SIZE }
            .filter { it != BOS_TOKEN_ID && it != PAD_TOKEN_ID }
            .map(Long::toInt)
        if (ids.isEmpty()) return ""

        val wanted = ids.toSet()
        val tokens = mutableMapOf<Int, String>()
        val specialIds = mutableSetOf<Int>()
        JsonReader(FileReader(tokenizer)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "added_tokens" -> readAddedTokens(reader, specialIds)
                    "model" -> readModel(reader, wanted, tokens)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }

        require(ids.all { it in specialIds || it in tokens }) { "Formula tokenizer does not match model" }
        val encoded = buildString {
            ids.forEach { id -> if (id !in specialIds) append(tokens.getValue(id)) }
        }
        return decodeByteLevel(encoded).trim()
    }

    private fun readAddedTokens(reader: JsonReader, specialIds: MutableSet<Int>) {
        reader.beginArray()
        while (reader.hasNext()) {
            var id: Int? = null
            var special = false
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "id" -> id = reader.nextInt()
                    "special" -> special = reader.nextBoolean()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (special && id != null) specialIds += id
        }
        reader.endArray()
    }

    private fun readModel(
        reader: JsonReader,
        wanted: Set<Int>,
        tokens: MutableMap<Int, String>,
    ) {
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "vocab") {
                reader.beginObject()
                while (reader.hasNext()) {
                    val token = reader.nextName()
                    val id = reader.nextInt()
                    if (id in wanted) tokens[id] = token
                }
                reader.endObject()
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
    }

    /** Inverse of the GPT-2/Hugging Face byte-to-visible-Unicode mapping used by ByteLevel BPE. */
    private fun decodeByteLevel(encoded: String): String {
        val output = ByteArrayOutputStream(encoded.length)
        encoded.codePoints().forEach { codePoint ->
            val byte = BYTE_DECODER[codePoint]
                ?: error("Unsupported ByteLevel code point U+${codePoint.toString(16)}")
            output.write(byte)
        }
        return output.toByteArray().toString(StandardCharsets.UTF_8)
    }

    private val BYTE_DECODER: Map<Int, Int> = buildMap {
        val visibleBytes = buildList {
            addAll(33..126)
            addAll(161..172)
            addAll(174..255)
        }
        val visibleSet = visibleBytes.toSet()
        visibleBytes.forEach { byte -> put(byte, byte) }
        var extra = 0
        for (byte in 0..255) {
            if (byte !in visibleSet) {
                put(256 + extra, byte)
                extra++
            }
        }
    }

    private const val BOS_TOKEN_ID = 0L
    private const val PAD_TOKEN_ID = 1L
    private const val EOS_TOKEN_ID = 2L
    private const val VOCAB_SIZE = 50_000L
}
