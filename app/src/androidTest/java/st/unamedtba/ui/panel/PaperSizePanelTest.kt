package st.unamedtba.ui.panel

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import st.unamedtba.model.Orientation
import st.unamedtba.model.PageStyle
import st.unamedtba.model.PaperDimensions
import st.unamedtba.model.PaperSize
import st.unamedtba.model.PrintMargins
import st.unamedtba.ui.theme.UnamedTbaTheme

/**
 * The Paper Size pane is a form, and a form's job is to commit exactly what was typed and nothing
 * else. The half-typed and out-of-range cases are the interesting ones: "" and "99" are states a
 * number field passes through, and neither is a page.
 */
class PaperSizePanelTest {

    @get:Rule
    val compose = createComposeRule()

    private var size: PaperSize? = null
    private var orientation: Orientation? = null
    private var custom: PaperDimensions? = null
    private var margins: PrintMargins? = null

    private fun setPanel(style: PageStyle = PageStyle()) {
        compose.setContent {
            UnamedTbaTheme {
                Column {
                    PaperSizePanelContent(
                        style = style,
                        onPickSize = { size = it },
                        onPickOrientation = { orientation = it },
                        onSetCustomPaper = { custom = it },
                        onSetMargins = { margins = it },
                    )
                }
            }
        }
    }

    private fun field(name: String) = compose.onNodeWithTag(PanelTags.field(name))

    private fun type(name: String, text: String) {
        field(name).performTextClearance()
        field(name).performTextInput(text)
    }

    @Test
    fun theSizeFieldOffersEverySheetIncludingCustom() {
        setPanel()

        field("Size").performClick()

        // Present rather than displayed: the list may be taller than the window, which is part of
        // why this control moved out of the ribbon and into a pane.
        compose.onNodeWithText("Custom").assertExists()
        compose.onNodeWithText("A4").performClick()

        assertEquals(PaperSize.A4, size)
    }

    @Test
    fun orientationTurnsTheSheetWithoutResizingIt() {
        setPanel(PageStyle(paper = PaperSize.A4))

        field("Orientation").performClick()
        compose.onNodeWithText("Landscape").performClick()

        assertEquals(Orientation.Landscape, orientation)
        assertNull("turning the page must not resize it", size)
    }

    /** An unbounded page has no orientation to turn — the canvas grows whichever way you write. */
    @Test
    fun anAutoPageCannotBeTurned() {
        setPanel(PageStyle(paper = PaperSize.Auto))

        field("Orientation").performClick()

        compose.onNodeWithText("Landscape").assertDoesNotExist()
        assertNull(orientation)
    }

    /** A named size still shows its dimensions: "B5" means nothing without them. */
    @Test
    fun aNamedSizeShowsItsDimensionsWithoutOfferingToEditThem() {
        setPanel(PageStyle(paper = PaperSize.A4))

        compose.onNodeWithText("8.27").assertIsDisplayed()
        compose.onNodeWithText("11.69").assertIsDisplayed()
        field("Width").assertIsNotEnabled()
        field("Height").assertIsNotEnabled()
    }

    @Test
    fun aCustomWidthIsCommittedAsTyped() {
        setPanel(PageStyle(paper = PaperSize.Custom, customPaper = PaperDimensions(8.5f, 11f)))

        type("Width", "6")

        assertEquals(PaperDimensions(6f, 11f), custom)
    }

    @Test
    fun anEmptyFieldIsNotAPageSize() {
        setPanel(PageStyle(paper = PaperSize.Custom, customPaper = PaperDimensions(8.5f, 11f)))

        field("Width").performTextClearance()

        assertNull("an empty field was committed as a width", custom)
    }

    @Test
    fun aSizeOutsideWhatAPageCanBeIsRefused() {
        setPanel(PageStyle(paper = PaperSize.Custom, customPaper = PaperDimensions(8.5f, 11f)))

        type("Width", "500")

        assertNull("a 500 inch page was accepted", custom)
    }

    @Test
    fun eachMarginCommitsToItsOwnEdge() {
        setPanel(PageStyle(paper = PaperSize.A4))

        type("Top", "0.5")
        assertEquals(PrintMargins(topInches = 0.5f), margins)

        type("Right", "0.25")
        assertEquals(PrintMargins(rightInches = 0.25f), margins)
    }

    @Test
    fun aMarginWiderThanTheSheetIsRefused() {
        setPanel(PageStyle(paper = PaperSize.A4))

        type("Left", "99")

        assertNull("a margin wider than the sheet was accepted", margins)
    }
}
