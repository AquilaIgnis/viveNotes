package com.vivenotes.ui.editor

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize

/**
 * Keeps the siblings *underneath* this layout in the hit path instead of letting them go dead.
 *
 * Compose hit-tests a layout's children back to front and **stops at the first one it hits**, so two
 * overlapping siblings do not both get a say and the lower one is never asked. That rule is why the
 * page's object layers spent their life nested inside the bare-canvas tap target rather than beside
 * it, and why a full-page layer laid over the text containers would otherwise swallow every touch on
 * the page: every tap into a text box, the move grip, the resize handles, the caret.
 * [androidx.compose.ui.node.PointerInputModifierNode] exposes the lever that lifts it —
 * `sharePointerInputWithSiblings` — and the flag is read per *layout*, from any pointer-input node in
 * that layout's modifier chain, so a marker node with no behaviour of its own is enough to set it.
 *
 * **It shares hit testing, not priority, and the difference decides how a layer above has to be
 * written.** Compose does not keep the two branches apart: the shared sibling's nodes are *appended*
 * to the same flat path, so the whole page ends up as one chain with the topmost layout nearest the
 * root. The event then tunnels down that chain on [PointerEventPass.Initial] and bubbles back up on
 * [PointerEventPass.Main] — which means the layer on **top** is asked first only on the tunnelling
 * pass, and asked *last* on the bubbling one. A gesture handler that waits for its DOWN on `Main`,
 * the ordinary way to write one, therefore loses to everything beneath it.
 *
 * That is not a detail here, because what is beneath is a text container, and its editor is a real
 * Android View: `pointerInteropFilter` hands the View the DOWN as the event tunnels past and consumes
 * it right there. Anything that means to beat it has to claim the DOWN on the tunnelling pass too —
 * see the object layers in `EditorPane`, which do, and `ShapeLayer` for what that costs.
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
