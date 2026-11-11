package com.vivenotes.ui.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.fileimport.DocxTestFile
import com.vivenotes.fileimport.FileImportMode
import com.vivenotes.fileimport.FileImporter
import com.vivenotes.fileimport.ImportedFile
import com.vivenotes.fileimport.ImportedPageSize
import com.vivenotes.fileimport.ImportException
import com.vivenotes.fileimport.ImportFailure
import com.vivenotes.R
import com.vivenotes.ui.panel.PanelTags
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class ImportFileDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val importer = FileImporter(context)
    private val file = File(context.cacheDir, "import-dialog-test.docx")

    private var insertedPages: List<Int>? = null
    private var insertedMode: FileImportMode? = null
    private var dismissed = false

    /** A document of [pages] pages, each cut off from the next by a hard page break. */
    private fun writeDocument(pages: Int) {
        val body = List(pages) { index ->
            val breakBefore = if (index == 0) "" else "<w:pageBreakBefore/>"
            DocxTestFile.paragraph("Page body $index", breakBefore)
        }.joinToString("")
        DocxTestFile.write(file, body)
    }

    private fun setDialog(
        // Finished on the main thread, where the app's own window always is. The test clock's effects
        // run on whichever thread woke them, and this one's writes then race the window's first frame.
        open: suspend () -> ImportedFile = { withContext(Dispatchers.Main) { importer.openFile(file, "lecture.docx") } },
        landed: Boolean = true,
    ) {
        compose.setContent {
            ViveNotesTheme {
                ImportFileDialog(
                    importer = importer,
                    open = open,
                    onInsert = { _, pages, mode ->
                        insertedPages = pages
                        insertedMode = mode
                        landed
                    },
                    onDismiss = { dismissed = true },
                )
            }
        }
        compose.waitUntil(WAIT_MS) {
            runCatching { compose.onNodeWithTag(ImportFileTags.page(0)).assertExists() }.isSuccess ||
                runCatching { compose.onNodeWithTag(ImportFileTags.MESSAGE).assertExists() }.isSuccess
        }
    }

    @After
    fun tearDown() {
        importer.release()
        file.delete()
    }

    @Test
    fun noPageStartsChosen() {
        writeDocument(pages = 3)
        setDialog()

        (0 until 3).forEach { compose.onNodeWithTag(ImportFileTags.page(it)).assertIsOff() }
        compose.onNodeWithTag(ImportFileTags.INSERT).assertIsNotEnabled()
    }

    @Test
    fun onlyTheTappedPagesAreInserted() {
        writeDocument(pages = 3)
        setDialog()

        compose.onNodeWithTag(ImportFileTags.page(0)).performClick()
        compose.onNodeWithTag(ImportFileTags.page(2)).performClick()
        compose.onNodeWithTag(ImportFileTags.page(1)).assertIsOff()
        compose.onNodeWithTag(ImportFileTags.INSERT).performClick()
        compose.waitUntil(WAIT_MS) { insertedPages != null }

        assertEquals(listOf(0, 2), insertedPages)
    }

    @Test
    fun objectsAreTheDefault() {
        writeDocument(pages = 1)
        setDialog()
        compose.onNodeWithTag(ImportFileTags.page(0)).performClick()

        compose.onNodeWithTag(ImportFileTags.INSERT).performClick()
        compose.waitUntil(WAIT_MS) { insertedMode != null }

        assertEquals(FileImportMode.Objects, insertedMode)
    }

    @Test
    fun picturesCanBeChosen() {
        writeDocument(pages = 1)
        setDialog()
        compose.onNodeWithTag(ImportFileTags.page(0)).performClick()

        compose.onNodeWithTag(PanelTags.field("Insert as")).performClick()
        compose.onNodeWithText(context.getString(R.string.import_file_as_pictures)).performClick()
        compose.onNodeWithTag(ImportFileTags.INSERT).performClick()
        compose.waitUntil(WAIT_MS) { insertedMode != null }

        assertEquals(FileImportMode.Pictures, insertedMode)
    }

    @Test
    fun selectAllChoosesEveryPageAndClearLeavesNothingToInsert() {
        writeDocument(pages = 2)
        setDialog()

        compose.onNodeWithTag(ImportFileTags.SELECT_ALL).performClick()
        (0 until 2).forEach { compose.onNodeWithTag(ImportFileTags.page(it)).assertIsOn() }
        compose.onNodeWithTag(ImportFileTags.INSERT).assertIsEnabled()

        compose.onNodeWithTag(ImportFileTags.SELECT_NONE).performClick()
        compose.onNodeWithTag(ImportFileTags.page(0)).assertIsOff()
        compose.onNodeWithTag(ImportFileTags.INSERT).assertIsNotEnabled()
    }

    @Test
    fun aSuccessfulInsertClosesTheWindow() {
        writeDocument(pages = 1)
        setDialog()
        compose.onNodeWithTag(ImportFileTags.page(0)).performClick()

        compose.onNodeWithTag(ImportFileTags.INSERT).performClick()
        compose.waitUntil(WAIT_MS) { dismissed }

        assertTrue(dismissed)
    }

    @Test
    fun anInsertThatDoesNotLandSaysSoAndStaysOpen() {
        writeDocument(pages = 1)
        setDialog(landed = false)
        compose.onNodeWithTag(ImportFileTags.page(0)).performClick()

        compose.onNodeWithTag(ImportFileTags.INSERT).performClick()
        compose.waitUntil(WAIT_MS) { insertedPages != null }
        compose.waitForIdle()

        compose.onNodeWithTag(ImportFileTags.MESSAGE).assertIsDisplayed()
        assertFalse(dismissed)
    }

    /** Each failure has its own sentence, and a file that cannot be read offers nothing to insert. */
    @Test
    fun aFileThatWillNotOpenSaysWhy() {
        setDialog(open = { throw ImportException(ImportFailure.TooLarge) })

        compose.onNodeWithText(context.getString(R.string.import_file_too_large)).assertIsDisplayed()
        compose.onNodeWithTag(ImportFileTags.INSERT).assertIsNotEnabled()
    }

    /** A ratio of nothing over nothing threw on the main thread before the grid was drawn. */
    @Test
    fun aPageOfNoSizeStillTakesAPlaceInTheGrid() {
        setDialog(open = { ImportedFile("broken.pdf", listOf(ImportedPageSize(0f, 0f), ImportedPageSize(612f, 0f)), Any()) })

        compose.onNodeWithTag(ImportFileTags.page(0)).assertIsDisplayed()
        compose.onNodeWithTag(ImportFileTags.page(1)).assertExists()
    }

    @Test
    fun aPdfWithAPasswordSaysThatRatherThanThatItIsBroken() {
        setDialog(open = { throw ImportException(ImportFailure.PasswordProtected) })

        compose.onNodeWithText(context.getString(R.string.import_file_password)).assertIsDisplayed()
        compose.onNodeWithTag(ImportFileTags.INSERT).assertIsNotEnabled()
    }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
