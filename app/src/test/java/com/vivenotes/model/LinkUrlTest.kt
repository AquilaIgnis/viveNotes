package com.vivenotes.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkUrlTest {
    @Test fun acceptsWebAddressesWithOrWithoutScheme() {
        assertEquals("https://example.com/a?q=1", normalizedLinkUrl(" example.com/a?q=1 "))
        assertEquals("http://example.com", normalizedLinkUrl("http://example.com"))
    }

    @Test fun rejectsNonWebAndMalformedAddresses() {
        listOf("javascript:alert(1)", "file:///etc/passwd", "https://user@host.test/",
            "https://", "example.com/has space", "").forEach {
            assertNull(it, normalizedLinkUrl(it))
        }
    }
}
