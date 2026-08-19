package com.vivenotes.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * [AttachmentBytes] over a scratch directory, so a sync suite never writes into the `filesDir` of
 * the tablet it is running on.
 *
 * The behaviour it has to reproduce faithfully is [publish]: bytes are proved before they are named,
 * and a file already sitting there is already those bytes.
 */
class TemporaryAttachmentBytes(private val directory: File) : AttachmentBytes {

    private val _arrivals = MutableStateFlow(0L)
    override val arrivals: StateFlow<Long> = _arrivals.asStateFlow()

    init {
        directory.mkdirs()
    }

    override fun fileFor(id: String): File = File(directory, id)

    override fun stagingFor(id: String): File = File(directory, "$id.sync")

    override fun publish(staged: File, id: String): Boolean {
        val target = fileFor(id)
        if (target.exists()) {
            staged.delete()
            return true
        }
        if (!staged.renameTo(target)) {
            staged.delete()
            return false
        }
        _arrivals.value += 1
        return true
    }

    /** Puts bytes there as an import would, for a test that starts from a picture this device has. */
    fun write(id: String, bytes: ByteArray) {
        fileFor(id).writeBytes(bytes)
    }
}
