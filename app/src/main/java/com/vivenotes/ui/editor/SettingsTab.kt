package com.vivenotes.ui.editor

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.ScrollingRow
import com.vivenotes.ui.icons.MaterialSymbols
import com.vivenotes.ui.panel.ToolPane

@Immutable
data class AiActions(
    val openIntegrated: () -> Unit,
)

internal object SettingsTags {
    const val INTEGRATED = "settings-integrated"
    const val HARDWARE = "settings-hardware"
    const val ABOUT = "settings-about"
}

/**
 * The Settings tab.
 *
 * Integrated — local model installation, and later the recognition commands — lives here rather than
 * on a tab of its own. Which models are installed is a property of *this device*, the same kind of
 * answer as the rest of this tab will hold; it is not something you do to a page, which is what
 * every other tab is for. About is the same kind of answer about the app itself.
 */
@Composable
internal fun SettingsTab(ai: AiActions, openPane: (ToolPane) -> Unit) {
    // Held here rather than raised to `NotesApp` beside the notebook dialogs: About needs nothing
    // from the ViewModel, and a modal keeps the ribbon out of reach while it is up, so the tab it
    // belongs to cannot be switched out from under it.
    var aboutOpen by remember { mutableStateOf(false) }

    ScrollingRow(
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Box(Modifier.testTag(SettingsTags.INTEGRATED)) {
            RibbonCommand(
                label = "Integrated",
                onClick = ai.openIntegrated,
                icon = {
                    Icon(
                        imageVector = MaterialSymbols.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }

        Box(Modifier.testTag(SettingsTags.HARDWARE)) {
            RibbonCommand(
                label = "Hardware",
                onClick = { openPane(ToolPane.Hardware) },
                icon = {
                    Icon(
                        imageVector = MaterialSymbols.Keyboard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }

        Box(Modifier.testTag(SettingsTags.ABOUT)) {
            RibbonCommand(
                label = "About",
                onClick = { aboutOpen = true },
                icon = {
                    Icon(
                        imageVector = MaterialSymbols.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }

    if (aboutOpen) {
        AboutDialog(onDismiss = { aboutOpen = false })
    }
}
