package com.vivenotes.ink

import com.vivenotes.model.*
import org.junit.Assert.*
import org.junit.Test

class MixedGroupSelectionTest {
    private val group = "mixed"
    private val shape = Outline.Shape("shape", x = 20f, y = 20f, width = 40f, height = 40f, groupId = group)
    private val table = newTable(2, 2, x = 100f, y = 20f).copy(groupId = group)
    private val text = Outline.Text("text", x = 400f, y = 20f, width = 100f,
        blocks = listOf(Block.of("Notes")), groupId = group)
    private val image = Outline.Image("image", x = 550f, y = 20f, width = 50f, height = 50f,
        attachmentId = "photo", groupId = group)
    private val equation = Outline.Equation("equation", x = 650f, y = 20f, groupId = group)
    private val graph = Outline.Graph("graph", x = 750f, y = 20f, expression = "x", groupId = group)
    private val tables = listOf(TableBounds(table.id, InkBounds(100f, 20f, 250f, 100f), groupId = group))
    private val texts = listOf(TableBounds(text.id, InkBounds(400f, 20f, 500f, 100f), groupId = group))

    @Test fun tappingOneKindSelectsEveryMemberOutsideItsBounds() {
        val held = CanvasSelection.ofImage(image).reconcile(emptyList(), listOf(shape), tables,
            listOf(equation), listOf(image), listOf(graph), texts)!!
        assertEquals(setOf(shape.id), held.shapeIds)
        assertEquals(setOf(table.id), held.tableIds)
        assertEquals(setOf(text.id), held.textIds)
        assertEquals(setOf(equation.id), held.equationIds)
        assertEquals(setOf(graph.id), held.graphIds)
        assertEquals(group, held.groupId)
        assertEquals(6, held.memberCount)
        assertTrue(held.canGroup)
        assertFalse(held.isLocked)
    }

    @Test fun textBoxesCanBeLassoedAndMakeASelectionMixed() {
        val held = selectWithLasso(emptyList(), emptyList(), texts = texts,
            path = InkBounds(390f, 10f, 510f, 110f).enclosingPath())!!
        assertEquals(setOf(text.id), held.textIds)
        assertFalse(held.isInkOnly)
        assertFalse(held.isEmpty)
    }

    @Test fun locksAndMovableGroupsExpandTransitivelyAndUngroupingReleasesMembers() {
        val locked = shape.copy(lockGroup = "lock")
        val other = image.copy(groupId = null, lockGroup = "lock")
        val held = CanvasSelection.ofImage(other).reconcile(emptyList(), listOf(locked), tables,
            listOf(equation), listOf(other), listOf(graph), texts)!!
        assertEquals(setOf(text.id), held.textIds)
        assertTrue(held.isLocked)
        assertNull(held.groupId)
        val freed = CanvasSelection.ofImage(other.copy(lockGroup = null)).reconcile(emptyList(),
            listOf(locked), images = listOf(other.copy(lockGroup = null)))!!
        assertEquals(1, freed.memberCount)
        assertFalse(freed.canGroup)
    }

    @Test fun jsonAndCborPreserveGroupsAndOldDocumentsKeepTheirEncoding() {
        val doc = PageDoc(outlines = listOf(shape, table, text, image, equation, graph))
        assertEquals(doc, JsonDocumentCodec.decode(JsonDocumentCodec.encode(doc)))
        assertEquals(doc, CborDocumentCodec.decode(CborDocumentCodec.encode(doc)))
        val old = """{"outlines":[{"t":"shape","id":"shape"}]}"""
        assertEquals(old, decodePageDoc(old).encode())
    }
}
