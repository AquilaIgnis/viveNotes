package com.vivenotes.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
data class FileActions(
    val openVersionHistory: () -> Unit,
)

internal object FileTags {
    const val VERSION_HISTORY = "file-version-history"
}

/** Commands about the open file rather than its content or the device running the app. */
@Composable
internal fun FileTab(actions: FileActions, pageOpen: Boolean) {
    ScrollingRow(
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Box(Modifier.testTag(FileTags.VERSION_HISTORY)) {
            RibbonCommand(
                label = "Version History",
                onClick = actions.openVersionHistory,
                enabled = pageOpen,
                icon = {
                    Icon(
                        imageVector = MaterialSymbols.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }
}
