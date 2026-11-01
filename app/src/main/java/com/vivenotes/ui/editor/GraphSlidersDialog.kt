package com.vivenotes.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vivenotes.R
import com.vivenotes.graph.GraphSliders
import com.vivenotes.model.GraphParameter
import com.vivenotes.ui.icons.MaterialSymbols

internal object GraphSlidersDialogTags {
    const val DIALOG = "graph-sliders-dialog"
    const val APPLY = "graph-sliders-apply"
    fun field(index: Int, field: SliderField) = "graph-sliders-$index-${field.name}"
}

internal enum class SliderField { Value, Min, Max, Step }

/**
 * Each slider's value, range and step, typed — Desmos's bounds, for a value a drag cannot land on
 * exactly or a range −10…10 does not reach.
 *
 * Applies all or nothing: Apply stays disabled while any row does not make a slider, and says why
 * under it. A value outside a new range is pulled into it rather than refused, as the slider would.
 */
@Composable
internal fun GraphSlidersDialog(
    parameters: List<GraphParameter>,
    onDismiss: () -> Unit,
    onApply: (List<GraphParameter>) -> Unit,
) {
    // One text per field, kept as typed so "−" or "2." on the way to "2.5" is not reformatted away.
    val texts = remember(parameters) {
        mutableStateListOf<String>().apply {
            parameters.forEach { parameter ->
                add(GraphSliders.number(parameter.value, parameter.step).toEditable())
                add(GraphSliders.number(parameter.min, decimalsOf(parameter.min)).toEditable())
                add(GraphSliders.number(parameter.max, decimalsOf(parameter.max)).toEditable())
                add(GraphSliders.number(parameter.step, parameter.step).toEditable())
            }
        }
    }
    val edited = parameters.mapIndexed { index, parameter ->
        val base = index * FIELDS
        val value = texts[base].toNumber()
        val min = texts[base + 1].toNumber()
        val max = texts[base + 2].toNumber()
        val step = texts[base + 3].toNumber()
        if (value == null || min == null || max == null || step == null || step > max - min) {
            null
        } else {
            parameter.copy(min = min, max = max, step = step, value = value.coerceIn(minOf(min, max), maxOf(min, max)))
                .takeIf { it.isUsable }
        }
    }
    val valid = edited.all { it != null }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(MaterialSymbols.InstantMix, contentDescription = null) },
        title = { Text(stringResource(R.string.graph_sliders)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                parameters.forEachIndexed { index, parameter ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = parameter.label,
                            style = MaterialTheme.typography.titleMedium.copy(fontStyle = FontStyle.Italic),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            SliderField.entries.forEach { field ->
                                val at = index * FIELDS + field.ordinal
                                OutlinedTextField(
                                    value = texts[at],
                                    onValueChange = { texts[at] = it },
                                    label = { Text(stringResource(field.label)) },
                                    singleLine = true,
                                    isError = edited[index] == null || texts[at].toNumber() == null,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Decimal,
                                        imeAction = ImeAction.Next,
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag(GraphSlidersDialogTags.field(index, field)),
                                )
                            }
                        }
                        if (edited[index] == null) {
                            Text(
                                text = stringResource(R.string.graph_slider_invalid),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(edited.filterNotNull()) },
                enabled = valid,
                modifier = Modifier.testTag(GraphSlidersDialogTags.APPLY),
            ) {
                Text(stringResource(R.string.graph_sliders_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.graph_sliders_cancel)) }
        },
        modifier = Modifier.testTag(GraphSlidersDialogTags.DIALOG),
    )
}

private const val FIELDS = 4

private val SliderField.label: Int
    get() = when (this) {
        SliderField.Value -> R.string.graph_slider_value
        SliderField.Min -> R.string.graph_slider_min
        SliderField.Max -> R.string.graph_slider_max
        SliderField.Step -> R.string.graph_slider_step
    }

/** A bound as it was set: `−10`, `0.5` — no more places than it has. */
private fun decimalsOf(value: Double): Double =
    java.math.BigDecimal.valueOf(value).stripTrailingZeros().scale().coerceIn(0, 6).let { places ->
        if (places == 0) 1.0 else java.math.BigDecimal.ONE.movePointLeft(places).toDouble()
    }

/** What a keyboard can type back: the ASCII minus. */
private fun String.toEditable(): String = replace('−', '-')

/** A typed number, read leniently: a true minus or a decimal comma are what people type. */
private fun String.toNumber(): Double? =
    trim().replace('−', '-').replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
