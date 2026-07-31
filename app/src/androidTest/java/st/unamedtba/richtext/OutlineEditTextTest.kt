package st.unamedtba.richtext

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import st.unamedtba.model.Block
import st.unamedtba.model.Mark
import st.unamedtba.model.Run
import st.unamedtba.model.TOGGLEABLE_MARKS

/**
 * Exercises the path the ribbon actually uses — [FormatCommand] into the live view — rather than
 * the codec in isolation. Strikethrough round-tripped correctly through the codec while still
 * being broken in the app, so the codec tests alone were not enough.
 */
@RunWith(AndroidJUnit4::class)
class OutlineEditTextTest {

    private fun withEditor(body: (OutlineEditText) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = OutlineEditText(instrumentation.targetContext)
            // TextView.checkForResize dereferences layout params once the view has been laid out,
            // so an unparented view needs them set explicitly.
            view.layoutParams = android.view.ViewGroup.LayoutParams(
                RENDER_WIDTH,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            body(view)
        }
    }

    /**
     * A mark can round-trip through the model perfectly and still be invisible on screen. This
     * renders the view and asserts the pixels actually change, which is the only check that
     * matches what a user means by "it works".
     */
    @Test
    fun everyToggleableMarkChangesWhatIsDrawn() {
        withEditor { view ->
            view.setBlocks(listOf(Block(id = "b", runs = listOf(Run("HHHHHHHHHH")))))
            val plain = renderToBitmap(view)

            TOGGLEABLE_MARKS.forEach { mark ->
                view.setBlocks(listOf(Block(id = "b", runs = listOf(Run("HHHHHHHHHH", setOf(mark))))))
                val styled = renderToBitmap(view)
                assertTrue("$mark rendered identically to unstyled text", !plain.sameAs(styled))
            }
        }
    }

    /**
     * Guards the signal the editor defaults are built on.
     *
     * A font or size chosen with nothing selected becomes the app-wide default; chosen over a
     * selection it must not. That decision was briefly made from the last [SelectionState] the
     * ribbon had seen, which can lag the editor by a frame — so formatting a selection would
     * sometimes rewrite the default and restyle every unmarked block on the page. Reporting it
     * from the apply path is what makes the two cases distinguishable at all.
     */
    @Test
    fun setMarkReportsArmedOnlyWhenNothingIsSelected() {
        withEditor { view ->
            val armed = mutableListOf<Mark>()
            view.onMarkArmed = { armed += it }
            view.setBlocks(listOf(Block(id = "b", runs = listOf(Run("hello there")))))

            view.setSelection(0, 5)
            view.apply(FormatCommand.SetMark(Mark.FontSize(24)))
            assertTrue("a mark over a selection is an edit, not a new default: $armed", armed.isEmpty())

            view.setSelection(5)
            view.apply(FormatCommand.SetMark(Mark.FontSize(24)))
            assertEquals(listOf<Mark>(Mark.FontSize(24)), armed)
        }
    }

    private fun renderToBitmap(view: android.view.View): android.graphics.Bitmap {
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(RENDER_WIDTH, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
        )
        val height = view.measuredHeight.coerceAtLeast(1)
        view.layout(0, 0, view.measuredWidth, height)
        val bitmap = android.graphics.Bitmap.createBitmap(
            view.measuredWidth.coerceAtLeast(1),
            height,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        view.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }

    @Test
    fun appliesEveryToggleableMarkOverASelection() {
        withEditor { view ->
            TOGGLEABLE_MARKS.forEach { mark ->
                view.setBlocks(listOf(Block(id = "b", runs = listOf(Run("hello world")))))
                view.setSelection(0, 5)

                view.apply(FormatCommand.ToggleMark(mark))

                val marks = view.blocks().first().runs.first().marks
                assertTrue("$mark was not applied to the selection, got $marks", mark in marks)
            }
        }
    }

    @Test
    fun togglingTwiceOverASelectionRemovesTheMark() {
        withEditor { view ->
            TOGGLEABLE_MARKS.forEach { mark ->
                view.setBlocks(listOf(Block(id = "b", runs = listOf(Run("hello world")))))
                view.setSelection(0, 5)

                view.apply(FormatCommand.ToggleMark(mark))
                view.apply(FormatCommand.ToggleMark(mark))

                val marks = view.blocks().flatMap { it.runs }.flatMap { it.marks }
                assertTrue("$mark survived being toggled off, got $marks", mark !in marks)
            }
        }
    }

    @Test
    fun armsEveryToggleableMarkForTextTypedNext() {
        // Toggling with no selection is the common gesture: put the caret down, hit the button,
        // start typing.
        withEditor { view ->
            TOGGLEABLE_MARKS.forEach { mark ->
                view.setBlocks(listOf(Block(id = "b", runs = listOf(Run("abc")))))
                view.setSelection(3)

                view.apply(FormatCommand.ToggleMark(mark))
                view.text?.insert(3, "XY")

                val typed = view.blocks().first().runs.last()
                assertTrue(
                    "$mark was not armed for typed text; run '${typed.text}' had ${typed.marks}",
                    mark in typed.marks,
                )
            }
        }
    }

    @Test
    fun togglingOffAtTheCaretStopsTheMarkForTextTypedNext() {
        // Regression: inline spans are end-inclusive, so without an explicit suppression set a
        // mark could be armed but never turned off — typing just kept extending the span.
        withEditor { view ->
            TOGGLEABLE_MARKS.forEach { mark ->
                view.setBlocks(listOf(Block(id = "b", runs = listOf(Run("abc", setOf(mark))))))
                view.setSelection(3)

                view.apply(FormatCommand.ToggleMark(mark))
                view.text?.insert(3, "XY")

                val typed = view.blocks().first().runs.last()
                assertTrue(
                    "$mark could not be switched off; run '${typed.text}' still had ${typed.marks}",
                    mark !in typed.marks,
                )
            }
        }
    }

    @Test
    fun reportsTurningAMarkOffInSelectionState() {
        withEditor { view ->
            TOGGLEABLE_MARKS.forEach { mark ->
                var reported: SelectionState? = null
                view.setBlocks(listOf(Block(id = "b", runs = listOf(Run("abc", setOf(mark))))))
                view.setSelection(3)
                view.onSelectionStateChanged = { reported = it }

                view.apply(FormatCommand.ToggleMark(mark))

                assertTrue(
                    "ribbon stayed lit for $mark after switching it off, state was ${reported?.marks}",
                    reported?.has(mark) == false,
                )
            }
        }
    }

    @Test
    fun reportsAppliedMarkBackAsSelectionState() {
        withEditor { view ->
            TOGGLEABLE_MARKS.forEach { mark ->
                var reported: SelectionState? = null
                view.setBlocks(listOf(Block(id = "b", runs = listOf(Run("hello world")))))
                view.onSelectionStateChanged = { reported = it }
                view.setSelection(0, 5)

                view.apply(FormatCommand.ToggleMark(mark))

                assertTrue(
                    "ribbon would not light up for $mark, state was ${reported?.marks}",
                    reported?.has(mark) == true,
                )
            }
        }
    }

    private companion object {
        const val RENDER_WIDTH = 600
    }
}
