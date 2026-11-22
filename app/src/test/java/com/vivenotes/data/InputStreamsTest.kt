package com.vivenotes.data

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class InputStreamsTest {
    private val bytes = ByteArray(40_000) { (it % 251).toByte() }

    @Test fun aShorterStreamIsReadToItsEnd() {
        assertArrayEquals(bytes, ByteArrayInputStream(bytes).readAtMost(bytes.size + 1))
    }

    @Test fun aLongerStreamStopsAtTheLimitAndLeavesTheRest() {
        val input = ByteArrayInputStream(bytes)
        assertArrayEquals(bytes.copyOf(20_001), input.readAtMost(20_001))
        assertEquals(bytes.size - 20_001, input.available())
    }

    @Test fun aLimitOfZeroReadsNothing() {
        val input = ByteArrayInputStream(bytes)
        assertEquals(0, input.readAtMost(0).size)
        assertEquals(bytes.size, input.available())
    }

    /** A network or content stream may hand over a few bytes per read; the result is the same. */
    @Test fun aStreamThatTricklesIsStillReadInFull() {
        val trickle = object : InputStream() {
            private val source = ByteArrayInputStream(bytes)
            override fun read(): Int = source.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                source.read(buffer, offset, minOf(length, 3))
        }
        assertArrayEquals(bytes, trickle.readAtMost(Int.MAX_VALUE))
    }
}
