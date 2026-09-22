package com.vivenotes.model

import java.net.URI

/** A web address suitable for an external link, accepting a host without a typed scheme. */
fun normalizedLinkUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) return null
    val url = if ("://" in trimmed) trimmed else "https://$trimmed"
    val parsed = runCatching { URI(url) }.getOrNull() ?: return null
    if (parsed.scheme?.lowercase() !in setOf("http", "https")) return null
    if (parsed.host.isNullOrBlank() || parsed.rawUserInfo != null) return null
    return url
}
