package com.vivenotes.data

import org.junit.Assert.*
import org.junit.Test

class SvgPictureSourceTest {
    private fun source(attributes: String = "", body: String = "") =
        """<svg xmlns="http://www.w3.org/2000/svg" $attributes>$body</svg>""".encodeToByteArray()

    @Test fun viewBoxAndPhysicalUnitsHavePortableDimensions() {
        val box = SvgPictureSource.read(source("viewBox=\"-10 5 600 300\""), 2048)!!
        assertEquals(600 to 300, box.width to box.height)
        val units = SvgPictureSource.read(source("width=\"2in\" height=\"72pt\""), 2048)!!
        assertEquals(192 to 96, units.width to units.height)
        val partial = SvgPictureSource.read(source("width=\"400\" viewBox=\"0 0 20 10\""), 2048)!!
        assertEquals(400 to 200, partial.width to partial.height)
    }

    @Test fun referenceDimensionsAreBoundedWithoutChangingTheViewport() {
        val large = SvgPictureSource.read(source("width=\"8192\" height=\"4096\""), 2048)!!
        assertEquals(2048 to 1024, large.width to large.height)
        assertEquals(8192f, large.viewportWidth)
        assertEquals(4096f, large.viewportHeight)
        val relative = SvgPictureSource.read(source("width=\"100%\" height=\"100%\" viewBox=\"0 0 400 200\""), 2048)!!
        assertEquals(400 to 200, relative.width to relative.height)
        val fallback = SvgPictureSource.read(source(), 2048)!!
        assertEquals(300 to 150, fallback.width to fallback.height)
    }

    @Test fun utf8AndUtf16UseTheSameDimensions() {
        val xml = """<?xml version="1.0" encoding="UTF-16"?><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 16"/>"""
        val parsed = SvgPictureSource.read(xml.toByteArray(Charsets.UTF_16), 2048)!!
        assertEquals(32 to 16, parsed.width to parsed.height)
        assertTrue(parsed.text.startsWith("<svg"))
    }

    @Test fun malformedNonSvgAndInvalidDimensionsAreRefused() {
        listOf(
            "<html/>", "<svg>", "<svg xmlns='urn:other'/>",
            "<svg width='0'/>", "<svg height='-10'/>", "<svg width='1e999'/>",
            "<svg viewBox='0 0 0 10'/>", "<svg viewBox='0 0 NaN 10'/>",
        ).forEach { assertNull(it, SvgPictureSource.read(it.encodeToByteArray(), 2048)) }
        assertNull(SvgPictureSource.read(ByteArray(SvgPictureSource.MAX_BYTES + 1), 2048))
    }

    @Test fun declarationsEntitiesAndExcessiveNestingAreRefused() {
        val entity = """<!DOCTYPE svg [<!ENTITY secret SYSTEM "file:///etc/passwd">]><svg>&secret;</svg>"""
        assertNull(SvgPictureSource.read(entity.encodeToByteArray(), 2048))
        assertNull(SvgPictureSource.read(entity.toByteArray(Charsets.UTF_16), 2048))
        assertNull(SvgPictureSource.read(source(body = "<g>".repeat(130) + "</g>".repeat(130)), 2048))
    }

    @Test fun standardSvgDoctypeIsNotFetchedAndOriginalInputIsUnchanged() {
        val xml = """<!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "http://127.0.0.1:1/never-fetch.dtd"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 10"/>"""
        val bytes = xml.encodeToByteArray()
        val parsed = SvgPictureSource.read(bytes, 2048)!!
        assertEquals(20 to 10, parsed.width to parsed.height)
        assertFalse(parsed.text.contains("DOCTYPE"))
        assertArrayEquals(xml.encodeToByteArray(), bytes)
    }
}
