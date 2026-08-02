package com.vivenotes.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Test

class RibbonGlyphsTest {

    @Test
    fun insertTextAccentsTheFrameButKeepsTheLetterNeutral() {
        val neutral = Color(0xFF112233)
        val accent = Color(0xFF4477AA)
        val paths = insertTextGlyph(neutral, accent).root.filterIsInstance<VectorPath>()

        assertEquals("frame plus four handles", 5, paths.count { it.stroke == SolidColor(accent) })
        assertEquals("the T", 1, paths.count { it.fill == SolidColor(neutral) })
        assertEquals("the accent leaked into the T", 0, paths.count { it.fill == SolidColor(accent) })
    }
}
