package com.vivenotes.richtext

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.vivenotes.model.Block
import com.vivenotes.model.Mark
import com.vivenotes.model.OBJECT_REPLACEMENT_CHARACTER
import com.vivenotes.model.Run
import com.vivenotes.model.TOGGLEABLE_MARKS

/**
 * Exercises the path the ribbon actually uses — [FormatCommand] into the live view — rather than
 * the codec in isolation. Strikethrough round-tripped correctly through the codec while still
 * being broken in the app, so the codec tests alone were not enough.
 */
@RunWith(AndroidJUnit4::class)
class OutlineEditTextTest {

    @Test
    fun insertsAnEquationAtTheCaretAndReportsItForEditing() {
        withEditor { view ->
            val latex = "{\\displaystyle x^2}"
            var selection: SelectionState? = null
            view.setBlocks(listOf(Block(id = "b", runs = listOf(Run("before after")))))
            view.onSelectionStateChanged = { selection = it }
            view.setSelection(7)

            view.apply(FormatCommand.InsertEquation(latex))

            assertEquals(
                listOf(
                    Run("before "),
                    Run(OBJECT_REPLACEMENT_CHARACTER.toString(), setOf(Mark.Equation(latex))),
                    Run("after"),
                ),
                view.blocks().single().runs,
            )
            assertEquals(latex, selection?.equation)
            assertEquals(8, view.selectionStart)
        }
    }

    @Test
    fun insertingAtAnExistingEquationUpdatesItInsteadOfAddingAnother() {
        withEditor { view ->
            val old = Mark.Equation("x")
            val replacement = "x^2"
            view.setBlocks(
                listOf(
                    Block(
                        id = "b",
                        runs = listOf(
                            Run("a"),
                            Run(OBJECT_REPLACEMENT_CHARACTER.toString(), setOf(old)),
                            Run("b"),
                        ),
                    ),
                ),
            )
            view.setSelection(2)

            view.apply(FormatCommand.InsertEquation(replacement))

            val runs = view.blocks().single().runs
            assertEquals(1, runs.count { run -> run.marks.any { it is Mark.Equation } })
            assertEquals(Mark.Equation(replacement), runs.single { it.marks.any { mark -> mark is Mark.Equation } }.marks.single())
            assertEquals("a${OBJECT_REPLACEMENT_CHARACTER}b", view.text.toString())
        }
    }

    @Test
    fun changingSizeAtAnInsertedEquationAppliesToTheEquationObject() {
        withEditor { view ->
            val equation = Mark.Equation("x^2")
            var selection: SelectionState? = null
            view.setBlocks(
                listOf(
                    Block(
                        id = "b",
                        runs = listOf(
                            Run("a"),
                            Run(OBJECT_REPLACEMENT_CHARACTER.toString(), setOf(equation)),
                            Run("b"),
                        ),
                    ),
                ),
            )
            view.onSelectionStateChanged = { selection = it }
            view.setSelection(2)

            view.apply(FormatCommand.SetMark(Mark.FontSize(36)))

            val equationRun = view.blocks().single().runs.single { equation in it.marks }
            assertEquals(setOf<Mark>(equation, Mark.FontSize(36)), equationRun.marks)
            assertEquals(36, selection?.fontSize)
        }
    }

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
            // Set as the canvas sets it, so what these tests call the base size is the size the app
            // actually draws unmarked text at rather than whatever TextView happens to default to.
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, BASE_SIZE.toFloat())
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
     * Guards the signal that tells the ribbon a pick was armed rather than applied.
     *
     * A font or size chosen with nothing selected describes what is about to be typed; chosen over
     * a selection it is an edit and nothing else. Telling the two apart from the last
     * [SelectionState] the ribbon had seen cannot work — that view can lag the editor by a frame —
     * so it is reported from the apply path, which knows the real caret.
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

    /**
     * The ribbon path for the stacking bug. Every off/on cycle used to leave the size reduction
     * that goes with a script behind and add another on the way back, so the text shrank a little
     * further on each tap while reading back as an ordinary subscript. Nothing that inspected marks
     * could see it; the drawn height is what gives it away.
     */
    @Test
    fun tappingAScriptButtonRepeatedlyReturnsToTheSizeItStarted() {
        withEditor { view ->
            listOf(Mark.Subscript, Mark.Superscript).forEach { mark ->
                view.setBlocks(listOf(Block(id = "b", runs = listOf(Run("hello world")))))
                view.setSelection(0, 5)
                val editable = requireNotNull(view.text)

                view.apply(FormatCommand.ToggleMark(mark))
                val once = editable.getSpans(0, 5, ScriptSizeSpan::class.java).size

                repeat(4) {
                    view.apply(FormatCommand.ToggleMark(mark))
                    view.apply(FormatCommand.ToggleMark(mark))
                }

                assertEquals(
                    "$mark stacked its size reduction across taps",
                    once,
                    editable.getSpans(0, 5, ScriptSizeSpan::class.java).size,
                )
                assertTrue(mark in view.blocks().first().runs.first().marks)
            }
        }
    }

    /** One script replaces the other over a selection, rather than both landing on the text. */
    @Test
    fun oneScriptButtonReplacesTheOtherOverASelection() {
        withEditor { view ->
            view.setBlocks(listOf(Block(id = "b", runs = listOf(Run("hello world")))))
            view.setSelection(0, 5)

            view.apply(FormatCommand.ToggleMark(Mark.Subscript))
            view.apply(FormatCommand.ToggleMark(Mark.Superscript))

            val marks = view.blocks().first().runs.first().marks
            assertTrue("superscript did not take, got $marks", Mark.Superscript in marks)
            assertTrue("subscript survived underneath, got $marks", Mark.Subscript !in marks)
        }
    }

    /** And at a caret, where the exclusion has to hold over what is typed next instead. */
    @Test
    fun armingOneScriptDisarmsTheOtherForTextTypedNext() {
        withEditor { view ->
            view.setBlocks(listOf(Block(id = "b", runs = listOf(Run("abc")))))
            view.setSelection(3)

            view.apply(FormatCommand.ToggleMark(Mark.Subscript))
            view.apply(FormatCommand.ToggleMark(Mark.Superscript))
            view.text?.insert(3, "XY")

            val typed = view.blocks().first().runs.last()
            assertTrue("superscript was not armed, got ${typed.marks}", Mark.Superscript in typed.marks)
            assertTrue("subscript stayed armed, got ${typed.marks}", Mark.Subscript !in typed.marks)
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

    // --- font size ---------------------------------------------------------------------------

    /**
     * The bug in `docs/screenshots/font.png`: text resized to 20 while the ribbon still read 12.
     *
     * Setting a size removed nothing, because a span's real size was compared against a zeroed
     * placeholder and never matched. The new span drew over the old one, so the text looked right
     * while the stale span was still there to be read back — first one out of the set won.
     */
    @Test
    fun resizingTextReplacesTheSizeItAlreadyHad() {
        withEditor { view ->
            var reported: SelectionState? = null
            view.setBlocks(listOf(Block(id = "b", runs = listOf(Run("resize me", setOf(Mark.FontSize(12)))))))
            view.onSelectionStateChanged = { reported = it }
            view.setSelection(0, 9)

            view.apply(FormatCommand.SetMark(Mark.FontSize(20)))

            assertEquals("the ribbon must read what the text is", 20, reported?.fontSize)
            assertEquals(
                setOf<Mark>(Mark.FontSize(20)),
                view.blocks().single().runs.single().marks,
            )
        }
    }

    @Test
    fun highlightedTextReportsItsOwnSize() {
        withEditor { view ->
            var reported: SelectionState? = null
            view.setBlocks(
                listOf(
                    Block(
                        id = "b",
                        runs = listOf(Run("plain "), Run("large", setOf(Mark.FontSize(28)))),
                    ),
                ),
            )
            view.onSelectionStateChanged = { reported = it }

            view.setSelection(6, 11)
            assertEquals(28, reported?.fontSize)

            view.setSelection(0, 6)
            assertEquals("unmarked text is drawn at the base size", BASE_SIZE, reported?.fontSize)
        }
    }

    /**
     * A size mark is absent both from text at the base size and from a selection with several
     * sizes in it. Only the first is a number the ribbon can show.
     */
    @Test
    fun aSelectionSpanningTwoSizesReportsNoSize() {
        withEditor { view ->
            var reported: SelectionState? = null
            view.setBlocks(
                listOf(
                    Block(
                        id = "b",
                        runs = listOf(
                            Run("big", setOf(Mark.FontSize(28))),
                            Run("small", setOf(Mark.FontSize(9))),
                        ),
                    ),
                ),
            )
            view.onSelectionStateChanged = { reported = it }

            view.setSelection(0, 8)

            assertNull("a mixed selection has no one size to show", reported?.fontSize)
        }
    }

    @Test
    fun theCaretReportsTheSizeOfTheTextItSitsIn() {
        withEditor { view ->
            var reported: SelectionState? = null
            view.setBlocks(
                listOf(
                    Block(
                        id = "b",
                        runs = listOf(Run("plain "), Run("large", setOf(Mark.FontSize(28)))),
                    ),
                ),
            )
            view.onSelectionStateChanged = { reported = it }

            view.setSelection(9)
            assertEquals("inside the run", 28, reported?.fontSize)

            view.setSelection(11)
            assertEquals("at its end, where typing would continue it", 28, reported?.fontSize)

            view.setSelection(3)
            assertEquals("back in unmarked text", BASE_SIZE, reported?.fontSize)
        }
    }

    /**
     * Inline spans do not grow backwards, so text inserted at the head of a run carries nothing of
     * its own — and used to be stamped with the app-wide default, changing size mid-line.
     */
    @Test
    fun typingAtTheStartOfALineKeepsThatLinesSize() {
        withEditor { view ->
            view.defaultMarks = setOf(Mark.FontSize(9))
            view.setBlocks(listOf(Block(id = "b", runs = listOf(Run("twenty", setOf(Mark.FontSize(20)))))))
            view.setSelection(0)

            view.text?.insert(0, "X")

            assertEquals(
                listOf(Run("Xtwenty", setOf(Mark.FontSize(20)))),
                view.blocks().single().runs,
            )
        }
    }

    @Test
    fun pressingEnterCarriesTheLinesSizeOntoTheNext() {
        withEditor { view ->
            view.defaultMarks = setOf(Mark.FontSize(9))
            view.setBlocks(listOf(Block(id = "b", runs = listOf(Run("first", setOf(Mark.FontSize(20)))))))
            view.setSelection(5)

            view.text?.insert(5, "\n")
            view.text?.insert(6, "second")

            assertEquals(
                listOf(Run("second", setOf(Mark.FontSize(20)))),
                view.blocks().last().runs,
            )
        }
    }

    /** The default is the fallback for text with no neighbour, not a rule that overrides one. */
    @Test
    fun typingWithNothingToInheritTakesTheDefault() {
        withEditor { view ->
            view.defaultMarks = setOf(Mark.FontSize(9))
            view.setBlocks(listOf(Block(id = "b")))
            view.setSelection(0)

            view.text?.insert(0, "new")

            assertEquals(
                listOf(Run("new", setOf(Mark.FontSize(9)))),
                view.blocks().single().runs,
            )
        }
    }

    /**
     * Text arriving with formatting of its own keeps it. Inheritance fills a gap; it does not
     * overwrite, and a mark laid over a whole pasted range would flatten every size in it.
     */
    @Test
    fun insertingAlreadyStyledTextLeavesItAlone() {
        withEditor { view ->
            view.defaultMarks = setOf(Mark.FontSize(9))
            view.setBlocks(listOf(Block(id = "b", runs = listOf(Run("host", setOf(Mark.FontSize(20)))))))
            view.setSelection(0)

            val pasted = SpannableCodec.render(
                listOf(
                    Block(
                        id = "p",
                        runs = listOf(Run("A", setOf(Mark.FontSize(36))), Run("B", setOf(Mark.FontSize(8)))),
                    ),
                ),
                view.editorStyle,
            )
            view.text?.insert(0, pasted)

            assertEquals(
                listOf(
                    Run("A", setOf(Mark.FontSize(36))),
                    Run("B", setOf(Mark.FontSize(8))),
                    Run("host", setOf(Mark.FontSize(20))),
                ),
                view.blocks().single().runs,
            )
        }
    }

    /** A size picked at the caret is about to be typed, so it outranks what the line hands down. */
    @Test
    fun aSizePickedAtTheCaretBeatsTheOneTheLineWouldPassOn() {
        withEditor { view ->
            var reported: SelectionState? = null
            view.setBlocks(listOf(Block(id = "b", runs = listOf(Run("twelve", setOf(Mark.FontSize(12)))))))
            view.setSelection(6)
            view.onSelectionStateChanged = { reported = it }

            view.apply(FormatCommand.SetMark(Mark.FontSize(36)))
            assertEquals(36, reported?.fontSize)

            view.text?.insert(6, "!")
            assertEquals(
                listOf(Run("twelve", setOf(Mark.FontSize(12))), Run("!", setOf(Mark.FontSize(36)))),
                view.blocks().single().runs,
            )
        }
    }

    /**
     * The same kind-versus-value comparison that broke resizing also meant "None" in the colour
     * picker matched nothing and removed nothing.
     */
    @Test
    fun clearingAColourRemovesWhicheverColourWasThere() {
        withEditor { view ->
            view.setBlocks(
                listOf(Block(id = "b", runs = listOf(Run("red", setOf(Mark.TextColor(0xFFE53935.toInt())))))),
            )
            view.setSelection(0, 3)

            view.apply(FormatCommand.ClearMark(Mark.TextColor(0)))

            assertEquals(emptySet<Mark>(), view.blocks().single().runs.single().marks)
        }
    }

    private companion object {
        const val RENDER_WIDTH = 600

        /** What `EditorPane` sets the editor's own text size to; see `EditorDefaults`. */
        const val BASE_SIZE = 15
    }
}
