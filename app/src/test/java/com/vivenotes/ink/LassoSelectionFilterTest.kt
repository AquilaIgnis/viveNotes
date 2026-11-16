package com.vivenotes.ink

import com.vivenotes.data.LassoSettings
import com.vivenotes.data.penSettingsJson
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.seedSegments
import org.junit.Assert.*
import org.junit.Test

class LassoSelectionFilterTest {
    private var segmentId = 0
    private val shape = Outline.Shape("shape", x = 20f, y = 20f, width = 40f, height = 40f,
        segments = seedSegments(ShapeKind.Rectangle, 20f, 20f, 60f, 60f) { "segment-${segmentId++}" })
    private val table = TableBounds("table", InkBounds(80f, 20f, 120f, 60f))
    private val text = TableBounds("text", InkBounds(140f, 20f, 180f, 60f))
    private val equation = Outline.Equation("equation", x = 200f, y = 20f, width = 40f, height = 40f)
    private val image = Outline.Image("image", x = 260f, y = 20f, width = 40f, height = 40f, attachmentId = "photo")
    private val graph = Outline.Graph("graph", x = 320f, y = 20f, width = 40f, height = 40f, expression = "x")

    private fun select(settings: LassoSettings = LassoSettings()) = selectWithLasso(
        strokes = emptyList(), shapes = listOf(shape), tables = listOf(table), texts = listOf(text),
        equations = listOf(equation), images = listOf(image), graphs = listOf(graph),
        path = InkBounds(0f, 0f, 400f, 100f).enclosingPath(), settings = settings,
    )

    @Test fun defaultsSelectEveryKindAndEachSwitchExcludesOnlyItsCategory() {
        assertEquals(6, select()!!.memberCount)
        val noShapes = select(LassoSettings(shapes = false))!!
        assertEquals(5, noShapes.memberCount)
        assertTrue(noShapes.shapeIds.isEmpty())
        val noText = select(LassoSettings(text = false))!!
        assertEquals(5, noText.memberCount)
        assertTrue(noText.textIds.isEmpty())
        val noTables = select(LassoSettings(tables = false))!!
        assertEquals(5, noTables.memberCount)
        assertTrue(noTables.tableIds.isEmpty())
        val noObjects = select(LassoSettings(objects = false))!!
        assertEquals(3, noObjects.memberCount)
        assertTrue(noObjects.equationIds.isEmpty())
        assertTrue(noObjects.imageIds.isEmpty())
        assertTrue(noObjects.graphIds.isEmpty())
    }

    @Test fun everySwitchCanBeOff() {
        assertNull(select(LassoSettings(false, false, false, false, false)))
    }

    @Test fun aLassoTapSkipsExcludedObjectsAndCanReachAnEnabledShapeUnderneath() {
        val overlapping = image.copy(x = shape.x, y = shape.y)
        val point = InkPoint(20f, 40f)
        assertEquals(setOf(image.id), selectByTap(listOf(shape), emptyList(), listOf(overlapping), point)!!.imageIds)
        val selected = selectByTap(listOf(shape), emptyList(), listOf(overlapping), point,
            settings = LassoSettings(objects = false))!!
        assertEquals(setOf(shape.id), selected.shapeIds)
        assertNull(selectByTap(listOf(shape), emptyList(), listOf(overlapping), point,
            settings = LassoSettings(shapes = false, objects = false)))
    }

    @Test fun mixedGroupExpansionCannotBringExcludedKindsBack() {
        val groupedShape = shape.copy(groupId = "mixed")
        val groupedImage = image.copy(groupId = "mixed")
        val groupedText = text.copy(groupId = "mixed")
        val filter = LassoSettings(text = false, objects = false)
        val selected = selectWithLasso(emptyList(), listOf(groupedShape),
            path = InkBounds(10f, 10f, 70f, 70f).enclosingPath(), settings = filter)!!
        val reconciled = selected.reconcile(emptyList(), listOf(groupedShape),
            images = listOf(groupedImage), texts = listOf(groupedText))!!
        assertEquals(setOf(shape.id), reconciled.shapeIds)
        assertEquals(1, reconciled.memberCount)
        assertEquals(filter, reconciled.lassoFilter)
        assertEquals(shape.pageBounds(), reconciled.bounds)
    }

    @Test fun anExcludedUnlockedObjectDoesNotPreventPickingUpALockedShape() {
        val selected = selectWithLasso(emptyList(), listOf(shape.copy(lockGroup = "locked")),
            images = listOf(image), path = InkBounds(0f, 0f, 400f, 100f).enclosingPath(),
            settings = LassoSettings(objects = false))!!
        assertTrue(selected.isLocked)
        assertEquals(setOf(shape.id), selected.shapeIds)
    }

    @Test fun savedFiltersRoundTripAndMissingOrFutureFieldsKeepDefaults() {
        val settings = LassoSettings(ink = false, text = false, objects = false)
        val encoded = penSettingsJson.encodeToString(LassoSettings.serializer(), settings)
        assertEquals(settings, penSettingsJson.decodeFromString(LassoSettings.serializer(), encoded))
        assertEquals(LassoSettings(), penSettingsJson.decodeFromString(LassoSettings.serializer(), "{}"))
        assertEquals(LassoSettings(ink = false), penSettingsJson.decodeFromString(
            LassoSettings.serializer(), """{"ink":false,"futureSetting":true}"""))
    }
}
