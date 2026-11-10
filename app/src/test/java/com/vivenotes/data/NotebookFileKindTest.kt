package com.vivenotes.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which import a staged file goes to: a ZIP is a `.vive`, anything else is OneNote's to try. */
class NotebookFileKindTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun aZipIsAVive() {
        assertTrue(NotebookTransferManager.isVive(bytes(0x50, 0x4B, 0x03, 0x04)))
        // An empty archive's only record is its end: still a ZIP, and the .vive check says why not.
        assertTrue(NotebookTransferManager.isVive(bytes(0x50, 0x4B, 0x05, 0x06)))
    }

    @Test
    fun oneNotesContainersAreNot() {
        // A .onepkg is a cabinet.
        assertFalse(NotebookTransferManager.isVive("MSCF".toByteArray()))
        // A section begins with its file-type GUID, {7B5C52E4-D88C-4DA7-AEB1-5378D02996D3}.
        assertFalse(NotebookTransferManager.isVive(bytes(0xE4, 0x52, 0x5C, 0x7B)))
    }

    @Test
    fun aFileTooShortToTellIsNot() {
        assertFalse(NotebookTransferManager.isVive(ByteArray(0)))
        assertFalse(NotebookTransferManager.isVive(bytes(0x50)))
    }
}
