package com.vivenotes.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Where the camera app writes the photo the Picture menu's "Camera" asks it for.
 *
 * `TakePicture` does not hand a photo back; it lends the camera app a URI to write into, and that
 * has to be one another app can be granted — hence the FileProvider rather than a plain path.
 *
 * The system camera app rather than an in-app viewfinder: it holds its own camera permission, so
 * this app declares none and asks for none. Declaring CAMERA would make it worse, not better —
 * `ACTION_IMAGE_CAPTURE` then refuses to run until that permission is granted.
 *
 * In `cacheDir`, because the file is transit only: the photo is re-encoded into [AttachmentStore]
 * the moment it arrives.
 */
internal object CameraCapture {
    private const val DIRECTORY = "camera"

    fun newTarget(context: Context): Uri {
        val directory = File(context.cacheDir, DIRECTORY)
        // One capture at a time. Whatever an earlier one left here has been imported or abandoned.
        directory.deleteRecursively()
        directory.mkdirs()
        val file = File(directory, "capture-${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.camera", file)
    }
}
