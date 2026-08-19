package com.vivenotes.ui.editor

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.data.ViewSettings
import com.vivenotes.ui.ScrollingRow
import com.vivenotes.ui.panel.ToolPane

@Immutable
data class AiActions(
    val openIntegrated: () -> Unit,
)

internal object SettingsTags {
    const val INTEGRATED = "settings-integrated"
    const val HARDWARE = "settings-hardware"
    const val LINK_PREVIEWS = "settings-link-previews"
    const val ABOUT = "settings-about"
}

/**
 * The Settings tab.
 *
 * Integrated — local model installation, and later the recognition commands — lives here rather than
 * on a tab of its own. Which models are installed is a property of *this device*, the same kind of
 * answer as the rest of this tab will hold; it is not something you do to a page, which is what
 * every other tab is for. About is the same kind of answer about the app itself.
 *
 * Link Previews is on this tab for the same test and not because it is a display option. What the
 * switch really governs is whether pasting a YouTube link makes this device fetch from Google's
 * image host — the app's only request to somewhere its owner did not configure — so it is a fact
 * about the device, and it belongs beside the other two rather than under View.
 */
@Composable
internal fun SettingsTab(
    ai: AiActions,
    openPane: (ToolPane) -> Unit,
    viewSettings: ViewSettings,
    onSetLinkPreviews: (Boolean) -> Unit,
) {
    // Held here rather than raised to `NotesApp` beside the notebook dialogs: About needs nothing
    // from the ViewModel, and a modal keeps the ribbon out of reach while it is up, so the tab it
    // belongs to cannot be switched out from under it.
    var aboutOpen by remember { mutableStateOf(false) }

    ScrollingRow(
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
    ) {
        // All three wear the same blue, and deliberately: every command on this tab answers a
        // question about the device or the app rather than doing something to a page, so a colour
        // that sorted them would be inventing a distinction the tab does not have. What differs is
        // *where* the accent sits in each glyph — the chip's die, the keyboard's keys, the i inside
        // the ring — which is the rule the whole two-tone set follows.
        Box(Modifier.testTag(SettingsTags.INTEGRATED)) {
            RibbonCommand(
                label = "Integrated",
                onClick = ai.openIntegrated,
                icon = { active -> TwoToneIcon({ it.integrated }, active) },
            )
        }

        Box(Modifier.testTag(SettingsTags.HARDWARE)) {
            RibbonCommand(
                label = "Hardware",
                onClick = { openPane(ToolPane.Hardware) },
                icon = { active -> TwoToneIcon({ it.hardware }, active) },
            )
        }

        Box(Modifier.testTag(SettingsTags.LINK_PREVIEWS)) {
            RibbonCommand(
                label = "Link Previews",
                onClick = { onSetLinkPreviews(!viewSettings.linkPreviews) },
                active = viewSettings.linkPreviews,
                icon = { active -> TwoToneIcon({ it.linkPreview }, active) },
            )
        }

        Box(Modifier.testTag(SettingsTags.ABOUT)) {
            RibbonCommand(
                label = "About",
                onClick = { aboutOpen = true },
                icon = { active -> TwoToneIcon({ it.about }, active) },
            )
        }
    }

    if (aboutOpen) {
        AboutDialog(onDismiss = { aboutOpen = false })
    }
}
