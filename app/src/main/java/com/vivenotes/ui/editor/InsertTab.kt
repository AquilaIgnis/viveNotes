package com.vivenotes.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vivenotes.data.DrawTool
import com.vivenotes.data.ShapeSettings
import com.vivenotes.richtext.FormatCommand
import com.vivenotes.richtext.SelectionState
import com.vivenotes.richtext.createEquationRenderer
import com.vivenotes.ui.ScrollingRow
import com.vivenotes.ui.icons.MaterialSymbols
import com.vivenotes.ui.panel.FloatingSettingsPanel
import io.ratex.RaTeXView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal object InsertTags {
    const val EQUATION = "insert-equation"
    const val SOURCE = "equation-source"
    const val PREVIEW = "equation-preview"
    const val SUBMIT = "equation-submit"
}

private const val EXAMPLE_EQUATION = "{\\displaystyle \\int _{a}^{b}f'(t)\\,dt=f(b)-f(a)}"

/**
 * Insert: an inline native LaTeX equation, and the shape picker.
 *
 * The Shape button is the same composable the Draw tab shows, sharing one armed tool and one set of
 * settings — see [ShapeButton] for why it has two homes rather than one.
 */
@Composable
internal fun InsertTab(
    selection: SelectionState,
    onCommand: (FormatCommand) -> Unit,
    pageOpen: Boolean,
    shape: ShapeSettings,
    palette: List<Int>,
    tool: DrawTool,
    onSelectTool: (DrawTool) -> Unit,
    onChangeShape: (ShapeSettings) -> Unit,
    onAddColor: (Int) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var latex by remember { mutableStateOf(EXAMPLE_EQUATION) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var submitError by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var targetRetained by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current
    val equationColor = MaterialTheme.colorScheme.onSurface.toArgb()

    // A focusable popup takes focus from the Android editor. Retain its exact caret until the
    // panel submits, and release it on every exit path (outside click, back, tab switch, or cancel).
    DisposableEffect(targetRetained) {
        onDispose {
            if (targetRetained) onCommand(FormatCommand.ReleaseEquationTarget)
        }
    }

    LaunchedEffect(latex) {
        previewError = null
        submitError = null
    }

    fun dismiss() {
        expanded = false
        targetRetained = false
        submitting = false
    }

    ScrollingRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
    ) {
        ShapeButton(
            shape = shape,
            palette = palette,
            selected = tool == DrawTool.Shape,
            enabled = pageOpen,
            onSelect = { onSelectTool(DrawTool.Shape) },
            onChange = onChangeShape,
            onAddColor = onAddColor,
        )

        Divider()

        Box {
            RibbonButton(
                icon = MaterialSymbols.Function,
                label = "Equation",
                active = expanded,
                enabled = pageOpen && (selection.editorFocused || expanded),
                modifier = Modifier.testTag(InsertTags.EQUATION),
                onClick = {
                    editing = selection.equation != null
                    latex = selection.equation ?: EXAMPLE_EQUATION
                    onCommand(FormatCommand.RetainEquationTarget)
                    targetRetained = true
                    expanded = true
                },
            )

            FloatingSettingsPanel(
                expanded = expanded,
                onDismissRequest = ::dismiss,
                title = if (editing) "Edit equation" else "Insert equation",
            ) {
                OutlinedTextField(
                    value = latex,
                    onValueChange = { latex = it },
                    label = { Text("LaTeX") },
                    supportingText = { Text("Enter LaTeX without \$ delimiters.") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(InsertTags.SOURCE),
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Preview",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (latex.isNotBlank()) {
                    AndroidView(
                        factory = { viewContext ->
                            RaTeXView(viewContext).apply {
                                displayMode = false
                                fontSize = 22f
                                color = equationColor
                                onError = { previewError = it.message }
                            }
                        },
                        update = { preview ->
                            preview.color = equationColor
                            preview.latex = latex
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .padding(vertical = 8.dp)
                            .testTag(InsertTags.PREVIEW),
                    )
                }

                (submitError ?: previewError)?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(6.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = ::dismiss, enabled = !submitting) { Text("Cancel") }
                    Button(
                        enabled = latex.isNotBlank() && !submitting,
                        onClick = {
                            val source = latex.trim()
                            submitting = true
                            scope.launch {
                                try {
                                    // Preview errors are asynchronous; validate again here so an
                                    // invalid formula can never enter the document in the gap.
                                    createEquationRenderer(
                                        context = context,
                                        latex = source,
                                        fontSizePx = with(density) { 22.dp.toPx() },
                                        color = equationColor,
                                    )
                                    onCommand(FormatCommand.InsertEquation(source))
                                    dismiss()
                                } catch (error: Throwable) {
                                    if (error is CancellationException) throw error
                                    submitError = error.message ?: "This equation could not be parsed."
                                    submitting = false
                                }
                            }
                        },
                        modifier = Modifier.testTag(InsertTags.SUBMIT),
                    ) {
                        if (submitting) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(18.dp))
                        } else {
                            Text(if (editing) "Update" else "Insert")
                        }
                    }
                }
            }
        }
    }
}
