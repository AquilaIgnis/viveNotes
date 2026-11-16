package com.vivenotes.ui.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vivenotes.R
import com.vivenotes.data.LassoSettings

/** The lasso's five independent selection filters. The menu stays open after each change. */
@Composable
fun ColumnScope.LassoPanelContent(settings: LassoSettings, onChange: (LassoSettings) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LassoToggle(stringResource(R.string.lasso_select_ink), settings.ink,
            Modifier.weight(1f).fillMaxHeight()) {
            onChange(settings.copy(ink = it))
        }
        LassoToggle(stringResource(R.string.lasso_select_shapes), settings.shapes,
            Modifier.weight(1f).fillMaxHeight()) {
            onChange(settings.copy(shapes = it))
        }
        LassoToggle(stringResource(R.string.lasso_select_text), settings.text,
            Modifier.weight(1f).fillMaxHeight()) {
            onChange(settings.copy(text = it))
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LassoToggle(stringResource(R.string.lasso_select_tables), settings.tables,
            Modifier.weight(1f).fillMaxHeight()) {
            onChange(settings.copy(tables = it))
        }
        LassoToggle(stringResource(R.string.lasso_select_objects), settings.objects,
            Modifier.weight(1f).fillMaxHeight()) {
            onChange(settings.copy(objects = it))
        }
    }
    Text(
        text = stringResource(R.string.lasso_select_objects) + ": " +
            stringResource(R.string.lasso_objects_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun LassoToggle(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
            Box(Modifier.size(26.dp, 16.dp), contentAlignment = Alignment.Center) {
                Switch(
                    checked = checked,
                    onCheckedChange = null,
                    modifier = Modifier.requiredSize(52.dp, 32.dp).scale(0.5f),
                )
            }
        }
    }
}
