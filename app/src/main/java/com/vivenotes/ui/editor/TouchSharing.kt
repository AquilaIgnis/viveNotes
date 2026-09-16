package com.vivenotes.ui.editor

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize

/**
 * Keeps the siblings underneath this layout in the hit path instead of letting them go dead.
 *
 * Compose hit-tests a layout's children back to front and stops at the first one it hits, so two
 * overlapping siblings do not both get a say. That rule is why the page's object layers spent their
 * life nested inside the bare-canvas tap target rather than beside it, and why a full-page layer
 * over the text containers would otherwise swallow every touch on the page.
 * [androidx.compose.ui.node.PointerInputModifierNode] exposes the lever that lifts it —
 * `sharePointerInputWithSiblings` — read per layout from any pointer-input node in that layout's
 * modifier chain, so a marker node with no behaviour of its own is enough.
 *
 * It shares hit testing, not priority, and the difference decides how a layer above has to be
 * written. Compose does not keep the two branches apart: the shared sibling's nodes are appended to
 * the same flat path, so the whole page ends up as one chain with the topmost layout nearest the
 * root. The event tunnels down that chain on [PointerEventPass.Initial] and bubbles back up on
 * [PointerEventPass.Main], so the layer on top is asked first only on the tunnelling pass. A gesture
 * handler that waits for its DOWN on `Main` therefore loses to everything beneath it.
 *
 * That matters because what is beneath is a text container whose editor is a real Android View:
 * `pointerInteropFilter` hands the View the DOWN as the event tunnels past and consumes it there.
 * Anything that means to beat it has to claim the DOWN on the tunnelling pass too.
 */
internal fun Modifier.sharingTouchesWithSiblings(): Modifier = this then ShareTouchesElement

private object ShareTouchesElement : ModifierNodeElement<ShareTouchesNode>() {

    override fun create(): ShareTouchesNode = ShareTouchesNode()

    override fun update(node: ShareTouchesNode) = Unit

    override fun InspectorInfo.inspectableProperties() {
        name = "sharingTouchesWithSiblings"
    }

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun equals(other: Any?): Boolean = other === this
}

/**
 * A pointer-input node that handles no pointers.
 *
 * It exists only to answer `sharePointerInputWithSiblings`, which Compose asks of the layout as a
 * whole — any one node in the chain saying yes is enough — so the real gesture handlers beside and
 * beneath it keep their own behaviour exactly as written.
 */
private class ShareTouchesNode : Modifier.Node(), PointerInputModifierNode {

    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize,
    ) = Unit

    override fun onCancelPointerInput() = Unit

    override fun sharePointerInputWithSiblings(): Boolean = true
}
