package com.vivenotes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** Publishes recognized output to Android's process-external plain-text clipboard. */
internal fun copyRecognizedText(context: Context, label: String, text: String) {
    val clipboard = requireNotNull(context.getSystemService(ClipboardManager::class.java)) {
        "System clipboard is unavailable"
    }
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
