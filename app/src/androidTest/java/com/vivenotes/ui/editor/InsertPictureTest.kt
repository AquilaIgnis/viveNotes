package com.vivenotes.ui.editor

import android.graphics.Bitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.vivenotes.data.DrawTool
import com.vivenotes.data.EditorDefaults
import com.vivenotes.data.EraserSettings
import com.vivenotes.data.HighlighterSettings
import com.vivenotes.data.ImageSource
import com.vivenotes.data.OnlineImage
import com.vivenotes.data.OnlineImageSearchResult
import com.vivenotes.data.OnlineImages
import com.vivenotes.data.PEN_COLORS
import com.vivenotes.data.PenPreset
import com.vivenotes.data.SearchCursor
import com.vivenotes.data.ShapeSettings
import com.vivenotes.data.ViewSettings
import com.vivenotes.model.PageStyle
import com.vivenotes.richtext.SelectionState
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The Picture button's menu, and its "Search online" dialog against a stand-in for Openverse. */
class InsertPictureTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun eachMenuEntryReportsItsSource() {
        val picked = mutableListOf<PictureSource>()
        setRibbon { picked += it }

        compose.onNodeWithTag(HomeTags.PICTURE).performClick()
        compose.onNodeWithTag(HomeTags.PICTURE_DEVICE).performClick()
        compose.onNodeWithTag(HomeTags.PICTURE).performClick()
        compose.onNodeWithTag(HomeTags.PICTURE_ONLINE).performClick()

        assertEquals(listOf(PictureSource.Device, PictureSource.Online), picked)
    }

    @Test
    fun aSearchFillsTheGridAndATapInsertsTheDownloadedBytes() {
        val images = FakeImages(hits = 3)
        var inserted: ByteArray? = null
        var dismissed = false
        setDialog(images, onInsert = { inserted = it }, onDismiss = { dismissed = true })

        compose.onNodeWithTag(OnlineImageTags.QUERY).performTextInput("fox")
        compose.onNodeWithTag(OnlineImageTags.QUERY).performImeAction()
        compose.waitUntil { compose.onAllNodesWithTag(OnlineImageTags.RESULT).fetchSemanticsNodes().size == 3 }
        compose.onAllNodesWithTag(OnlineImageTags.RESULT)[1].performClick()
        compose.waitUntil { dismissed }

        assertEquals(listOf("fox" to null), images.searches)
        assertArrayEquals(FakeImages.bytesFor("fox-1"), inserted)
    }

    @Test
    fun whereOpenverseTakesOverIsMarked() {
        setDialog(FakeImages(hits = 4, openverseFrom = 2))

        compose.onNodeWithTag(OnlineImageTags.QUERY).performTextInput("cell")
        compose.onNodeWithTag(OnlineImageTags.QUERY).performImeAction()
        compose.waitUntil { compose.onAllNodesWithTag(OnlineImageTags.RESULT).fetchSemanticsNodes().size == 4 }

        compose.onNodeWithTag(OnlineImageTags.SOURCE_HEADER).assertIsDisplayed()
    }

    @Test
    fun noMarkerWhenOneSourceAnsweredEverything() {
        setDialog(FakeImages(hits = 3))

        compose.onNodeWithTag(OnlineImageTags.QUERY).performTextInput("cell")
        compose.onNodeWithTag(OnlineImageTags.QUERY).performImeAction()
        compose.waitUntil { compose.onAllNodesWithTag(OnlineImageTags.RESULT).fetchSemanticsNodes().size == 3 }

        compose.onAllNodesWithTag(OnlineImageTags.SOURCE_HEADER).assertCountEquals(0)
    }

    @Test
    fun nothingFoundSaysSoInsteadOfShowingAnEmptyGrid() {
        setDialog(FakeImages(hits = 0))

        compose.onNodeWithTag(OnlineImageTags.QUERY).performTextInput("zzz")
        compose.onNodeWithTag(OnlineImageTags.QUERY).performImeAction()

        compose.waitUntil { compose.onAllNodes(hasText("No pictures found", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag(OnlineImageTags.RESULT).assertCountEquals(0)
    }

    @Test
    fun aRateLimitIsExplainedRatherThanShownAsNoResults() {
        setDialog(FakeImages(hits = 0, result = OnlineImageSearchResult.RateLimited))

        compose.onNodeWithTag(OnlineImageTags.QUERY).performTextInput("cat")
        compose.onNodeWithTag(OnlineImageTags.QUERY).performImeAction()

        compose.waitUntil { compose.onAllNodes(hasText("Try again in a minute", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(OnlineImageTags.MESSAGE).assertIsDisplayed()
    }

    @Test
    fun aFailedDownloadKeepsTheDialogOpenAndSaysSo() {
        var dismissed = false
        setDialog(FakeImages(hits = 2, downloads = false), onDismiss = { dismissed = true })

        compose.onNodeWithTag(OnlineImageTags.QUERY).performTextInput("owl")
        compose.onNodeWithTag(OnlineImageTags.QUERY).performImeAction()
        compose.waitUntil { compose.onAllNodesWithTag(OnlineImageTags.RESULT).fetchSemanticsNodes().size == 2 }
        compose.onAllNodesWithTag(OnlineImageTags.RESULT)[0].performClick()

        compose.waitUntil { compose.onAllNodes(hasText("couldn’t be downloaded", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(!dismissed)
    }

    @Test
    fun aTileWithNoPreviewSaysSoAndCanStillBeInserted() {
        var inserted: ByteArray? = null
        setDialog(FakeImages(hits = 1, previews = false), onInsert = { inserted = it })

        compose.onNodeWithTag(OnlineImageTags.QUERY).performTextInput("meiosis")
        compose.onNodeWithTag(OnlineImageTags.QUERY).performImeAction()
        // The tile is one merged button; the icon is only findable beneath it.
        compose.waitUntil {
            compose.onAllNodesWithTag(OnlineImageTags.NO_PREVIEW, useUnmergedTree = true)
                .fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag(OnlineImageTags.RESULT).performClick()
        compose.waitUntil { inserted != null }

        assertArrayEquals(FakeImages.bytesFor("meiosis-0"), inserted)
    }

    private fun setDialog(
        images: OnlineImages,
        onInsert: (ByteArray) -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        compose.setContent {
            ViveNotesTheme {
                OnlineImageDialog(images = images, onInsert = onInsert, onDismiss = onDismiss)
            }
        }
    }

    private fun setRibbon(onInsertPicture: (PictureSource) -> Unit) {
        compose.setContent {
            ViveNotesTheme {
                Ribbon(
                    selection = SelectionState(),
                    activeTab = RibbonTab.Document,
                    onTabChange = {},
                    onCommand = {},
                    defaults = EditorDefaults(),
                    onSetDefault = {},
                    onInsertPicture = onInsertPicture,
                    pageStyle = PageStyle(),
                    viewSettings = ViewSettings(),
                    view = ViewActions(
                        setRuleLines = {},
                        setDefaultRuleLines = {},
                        setPageColor = {},
                        setHideTitle = {},
                        setZoom = {},
                        zoomIn = {},
                        zoomOut = {},
                        zoomToPageWidth = {},
                        setTabsLayout = {},
                        setCanvasDark = {},
                        setLinkPreviews = {},
                        openPane = {},
                    ),
                    pens = List(PenPreset.COUNT) { PenPreset.starting(it) },
                    palette = PEN_COLORS,
                    eraser = EraserSettings(),
                    highlighter = HighlighterSettings(),
                    shape = ShapeSettings(),
                    tool = DrawTool.None,
                    allowFinger = false,
                    draw = DrawActions(
                        selectTool = {},
                        updatePen = { _, _ -> },
                        updateEraser = {},
                        setDrawWithFinger = {},
                    ),
                    pageOpen = true,
                )
            }
        }
    }

    private class FakeImages(
        private val hits: Int,
        private val result: OnlineImageSearchResult? = null,
        private val downloads: Boolean = true,
        private val previews: Boolean = true,
        /** Hits from this index on are Openverse's; the ones before it are Commons'. */
        private val openverseFrom: Int = hits,
    ) : OnlineImages {
        val searches = mutableListOf<Pair<String, SearchCursor?>>()

        override suspend fun search(query: String, from: SearchCursor?): OnlineImageSearchResult {
            searches += query to from
            return result ?: OnlineImageSearchResult.Found(
                images = List(hits) { index ->
                    OnlineImage(
                        id = "$query-$index",
                        title = "$query $index",
                        url = "https://example.org/$query-$index.jpg",
                        thumbnail = "https://example.org/$query-$index/thumb",
                        width = 400,
                        height = 300,
                        source = if (index < openverseFrom) {
                            ImageSource.WikimediaCommons
                        } else {
                            ImageSource.Openverse
                        },
                    )
                },
                next = null,
            )
        }

        override suspend fun thumbnail(image: OnlineImage): Bitmap? =
            if (previews) Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888) else null

        override suspend fun download(image: OnlineImage): ByteArray? =
            if (downloads) bytesFor(image.id) else null

        companion object {
            fun bytesFor(id: String): ByteArray = id.encodeToByteArray()
        }
    }
}
