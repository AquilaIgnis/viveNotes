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

internal object AiTabTags {
    const val INTEGRATED = "ai-integrated"
}

/** Entry point for local model installation and, later, recognition commands. */
@Composable
internal fun AiTab(actions: AiActions) {
    ScrollingRow(
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Box(Modifier.testTag(AiTabTags.INTEGRATED)) {
            RibbonCommand(
                label = "Integrated",
                onClick = actions.openIntegrated,
                icon = {
                    Icon(
                        imageVector = MaterialSymbols.Category,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }
}
