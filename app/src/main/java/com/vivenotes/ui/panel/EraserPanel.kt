package com.vivenotes.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.vivenotes.data.EraserMode
import com.vivenotes.data.EraserSettings
import com.vivenotes.ui.icons.LocalRibbonIcons
import com.vivenotes.ui.icons.MaterialSymbols

object EraserPanelTags {
    fun mode(mode: EraserMode) = "eraser-mode-${mode.name}"
}

/** The floating settings shown under the eraser in the Draw ribbon. */
@Composable
fun ColumnScope.EraserPanelContent(
    settings: EraserSettings,
    onChange: (EraserSettings) -> Unit,
) {
    val icons = LocalRibbonIcons.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
    ) {
        EraserModeButton(
            mode = EraserMode.Normal,
            icon = if (settings.mode == EraserMode.Normal) {
                icons.active.eraser
            } else {
                icons.idle.eraser
            },
            tint = Color.Unspecified,
            selected = settings.mode == EraserMode.Normal,
            onClick = { onChange(settings.copy(mode = EraserMode.Normal)) },
        )
        EraserModeButton(
            mode = EraserMode.Object,
            icon = MaterialSymbols.ViewInArOff,
            tint = if (settings.mode == EraserMode.Object) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            selected = settings.mode == EraserMode.Object,
            onClick = { onChange(settings.copy(mode = EraserMode.Object)) },
        )
    }

    Spacer(Modifier.height(6.dp))
    PanelSlider(
        field = "Eraser size",
        label = "Size",
        value = settings.size,
        range = EraserSettings.MIN_SIZE..EraserSettings.MAX_SIZE,
        onChange = { onChange(settings.copy(size = it)) },
    )
}

@Composable
private fun EraserModeButton(
    mode: EraserMode,
    icon: ImageVector,
    tint: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier
            .width(64.dp)
            .testTag(EraserPanelTags.mode(mode))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .border(
                    width = 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = shape,
                )
                .background(
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    shape = shape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(21.dp),
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = mode.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
