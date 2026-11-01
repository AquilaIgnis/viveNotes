package com.vivenotes.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vivenotes.R
import com.vivenotes.richtext.createEquationRenderer
import com.vivenotes.ui.icons.MaterialSymbols
import com.vivenotes.ui.panel.FloatingSettingsPanel
import io.ratex.RaTeXView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Test tags for the equation panel. */
internal object EquationTags {
    /** The Draw tab's ƒ, which arms the tool that puts a formula on the canvas. */
    const val OBJECT = "draw-equation"

    const val SOURCE = "equation-source"
    const val PREVIEW = "equation-preview"
    const val SUBMIT = "equation-submit"
}

internal const val EXAMPLE_EQUATION = "{\\displaystyle \\int _{a}^{b}f'(t)\\,dt=f(b)-f(a)}"

/**
 * Write a formula: the Draw tab's ƒ button and the LaTeX panel behind it.
 *
 * Submitting arms a tool that puts the formula on the canvas as an object you can drag and resize.
 * Formulas in text need no button: typing `$$...$$`, `\(...\)` or `\[...\]` renders in place.
 *
 * The opt-in is for the submit button's [LoadingIndicator], which is the one part of M3 Expressive
 * still gated in 1.5.0-alpha25 — `MaterialExpressiveTheme`, `MotionScheme`, `ToggleButton` and the
 * wavy progress indicators have all graduated and need no annotation.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun EquationButton(
    enabled: Boolean,
    onSubmit: (String, MeasuredEquation) -> Unit,
    modifier: Modifier = Modifier,
    /** Marks the button as armed. The tool stays in hand after the panel closes. */
    active: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }

    fun dismiss() {
        expanded = false
    }

    Box(modifier) {
        RibbonButton(
            icon = MaterialSymbols.Function,
            label = stringResource(R.string.draw_equation),
            active = active || expanded,
            // Stays live while its own panel is open, or dismissing it would be impossible the moment
            // the panel took the focus that enabled the button.
            enabled = enabled || expanded,
            modifier = Modifier.testTag(EquationTags.OBJECT),
            onClick = { expanded = true },
        )

        EquationSourceDialog(
            expanded = expanded,
            latex = EXAMPLE_EQUATION,
            editing = false,
            onDismiss = ::dismiss,
            onSubmit = { source, measured ->
                onSubmit(source, measured)
                dismiss()
            },
        )
    }
}

/**
 * The LaTeX field, its live preview and the two buttons — everything except where the panel hangs.
 *
 * Shared between the Draw tab's ƒ and the object toolkit's: editing the formula on a placed equation
 * is the same act as writing one, and two copies of a validating LaTeX editor would drift apart the
 * first time either was touched.
 *
 * A formula is measured on its way through. The submit path has to render it anyway — the preview is
 * asynchronous, so it can still be showing a stale success when an invalid source is submitted — and
 * handing that renderer's metrics to [onSubmit] lets an equation arrive on the canvas already
 * knowing its size.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun EquationSourceDialog(
    expanded: Boolean,
    latex: String,
    onDismiss: () -> Unit,
    onSubmit: (String, MeasuredEquation) -> Unit,
    editing: Boolean = true,
) {
    var source by remember(latex, expanded) { mutableStateOf(latex) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var submitError by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current
    val equationColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val parseFailed = stringResource(R.string.equation_parse_failed)

    LaunchedEffect(source) {
        previewError = null
        submitError = null
    }

    FloatingSettingsPanel(
        expanded = expanded,
        onDismissRequest = onDismiss,
        title = stringResource(if (editing) R.string.equation_edit else R.string.equation_insert),
    ) {
        OutlinedTextField(
            value = source,
            onValueChange = { source = it },
            label = { Text(stringResource(R.string.recognition_source_latex)) },
            supportingText = { Text(stringResource(R.string.equation_source_hint)) },
            minLines = 2,
            maxLines = 5,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(EquationTags.SOURCE),
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.equation_preview),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (source.isNotBlank()) {
            AndroidView(
                factory = { viewContext ->
                    RaTeXView(viewContext).apply {
                        displayMode = false
                        fontSize = BASE_FONT_DP
                        color = equationColor
                        onError = { previewError = it.message }
                    }
                },
                update = { preview ->
                    preview.color = equationColor
                    preview.latex = source
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(vertical = 8.dp)
                    .testTag(EquationTags.PREVIEW),
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
            TextButton(onClick = onDismiss, enabled = !submitting) { Text(stringResource(R.string.equation_cancel)) }
            Button(
                enabled = source.isNotBlank() && !submitting,
                onClick = {
                    val trimmed = source.trim()
                    submitting = true
                    scope.launch {
                        try {
                            // Preview errors are asynchronous; validate again here so an invalid
                            // formula can never enter the document in the gap.
                            val fontSizePx = with(density) { BASE_FONT_DP.dp.toPx() }
                            val renderer = createEquationRenderer(
                                context = context,
                                latex = trimmed,
                                fontSizePx = fontSizePx,
                                color = equationColor,
                            )
                            // Page units are dp, so the renderer's pixels divide straight back out.
                            val scale = density.density
                            onSubmit(
                                trimmed,
                                MeasuredEquation(
                                    width = renderer.widthPx / scale,
                                    height = (renderer.heightPx + renderer.depthPx) / scale,
                                ),
                            )
                            submitting = false
                        } catch (error: Throwable) {
                            if (error is CancellationException) throw error
                            submitError = error.message ?: parseFailed
                            submitting = false
                        }
                    }
                },
                modifier = Modifier.testTag(EquationTags.SUBMIT),
            ) {
                if (submitting) {
                    // Uncontained, and sized to the label it replaces — the same call the recognition
                    // panel's in-button spinner makes, for the same reason: a contained indicator
                    // inside a button is a container in a container.
                    LoadingIndicator(Modifier.size(18.dp))
                } else {
                    Text(stringResource(if (editing) R.string.equation_update else R.string.equation_submit))
                }
            }
        }
    }
}

/**
 * How big RaTeX makes a formula at [BASE_FONT_DP], in page units.
 *
 * Carried out of the editor rather than measured again later because the editor is the one place that
 * has already parsed the thing. The Home tab's ƒ ignores it — an inline equation is sized by the text
 * it sits in — and the Draw tab's puts it straight into `Outline.Equation`.
 */
data class MeasuredEquation(val width: Float, val height: Float)
