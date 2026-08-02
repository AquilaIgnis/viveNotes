package com.vivenotes.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NavigationPaneGestureTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun swipingNotebookRailLeftRequestsItsCollapse() {
        var collapseRequested = false
        compose.setContent {
            ViveNotesTheme {
                Box(Modifier.width(232.dp).height(400.dp)) {
                    NotebookRail(
                        tree = emptyList(),
                        selectedSectionId = null,
                        onSelectSection = {},
                        onToggleNotebook = { _, _ -> },
                        onAddSection = {},
                        onAddNotebook = {},
                        onSwipeLeft = { collapseRequested = true },
                    )
                }
            }
        }

        compose.onRoot().performTouchInput { swipeLeft() }

        assertTrue("notebook rail did not recognize the left swipe", collapseRequested)
    }

    @Test
    fun swipingPageListLeftRequestsItsCollapse() {
        var collapseRequested = false
        compose.setContent {
            ViveNotesTheme {
                Box(Modifier.width(260.dp).height(400.dp)) {
                    PageListPane(
                        pages = emptyList(),
                        selectedPageId = null,
                        onSelectPage = {},
                        onAddPage = {},
                        onDeletePage = {},
                        onSwipeLeft = { collapseRequested = true },
                    )
                }
            }
        }

        compose.onRoot().performTouchInput { swipeLeft() }

        assertTrue("page list did not recognize the left swipe", collapseRequested)
    }
}
