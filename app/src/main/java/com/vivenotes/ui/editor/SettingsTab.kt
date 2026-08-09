package com.vivenotes.ui.editor

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.ScrollingRow
import com.vivenotes.ui.icons.MaterialSymbols

@Immutable
data class AiActions(
    val openIntegrated: () -> Unit,
)

internal object SettingsTags {
    const val INTEGRATED = "settings-integrated"
}

/**
 * The Settings tab.
 *
 * Integrated — local model installation, and later the recognition commands — lives here rather than
 * on a tab of its own. Which models are installed is a property of *this device*, the same kind of
 * answer as the rest of this tab will hold; it is not something you do to a page, which is what
 * every other tab is for.
 */
@Composable
internal fun SettingsTab(ai: AiActions) {
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
    }
}
