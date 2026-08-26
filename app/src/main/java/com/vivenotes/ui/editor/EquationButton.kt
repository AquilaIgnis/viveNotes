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
import com.vivenotes.richtext.createEquationRenderer
import com.vivenotes.ui.icons.MaterialSymbols
import com.vivenotes.ui.panel.FloatingSettingsPanel
import io.ratex.RaTeXView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Test tags for the equation panel, which is one composable in two tabs. */
internal object EquationTags {
    /** The Home tab's ƒ, which writes a formula into the text under the caret. */
    const val INLINE = "insert-equation"

    /** The Draw tab's ƒ, which arms the tool that puts a formula on the canvas. */
    const val OBJECT = "draw-equation"

    const val SOURCE = "equation-source"
    const val PREVIEW = "equation-preview"
    const val SUBMIT = "equation-submit"
}

internal const val EXAMPLE_EQUATION = "{\\displaystyle \\int _{a}^{b}f'(t)\\,dt=f(b)-f(a)}"

/**
 * Write a formula: the ƒ button and the LaTeX panel behind it.
 *
 * **One composable, two tabs, two destinations.** On Home it writes an equation into the text under
 * the caret, as a [com.vivenotes.model.Mark] on a run — a character in a sentence, which is what an
 * equation in a paragraph is. On Draw it arms a tool that puts the same formula on the canvas as an
 * object you can drag and resize. Same panel, same validation, same glyph, because it is the same
 * question either way: *what is the formula?* Where it lands is the tab's business, not the panel's,
 * which is why [onSubmit] is the only thing that differs between the two call sites.
 *
 * That split is the equation's version of the one [TableButton] draws between a typed table and a
 * ruling, and it lands on the opposite answer for a reason: a table's two kinds are one object with
 * different cells, so a setting distinguishes them, while an equation's are genuinely a mark and an
 * object — different types, different toolkits, different places on the page.
 *
 * [existing] is the formula already under the caret. When there is one the panel edits it rather than
 * inserting beside it, which is the difference between Update and Insert.
 *
 * **The panel takes focus, so the caret has to be held.** A focusable popup pulls focus off the
 * Android editor, and with it the selection the insert is aimed at; [onRetainTarget] and
 * [onReleaseTarget] are how the Home tab pins that caret for the life of the panel and lets it go on
 * every exit — outside click, back, tab switch, cancel or submit. The Draw tab needs neither, because
 * it is aiming at a point on the canvas rather than at a caret.
 *
 * The opt-in is for the submit button's [LoadingIndicator]. **The loading indicators are the one part
 * of M3 Expressive still gated in 1.5.0-alpha25** — `MaterialExpressiveTheme`, `MotionScheme`,
 * `ToggleButton` and the wavy progress indicators have all graduated and need no annotation, which is
 * worth knowing before adding one reflexively.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun EquationButton(
    enabled: Boolean,
    onSubmit: (String, MeasuredEquation) -> Unit,
    modifier: Modifier = Modifier,
    existing: String? = null,
    tag: String = EquationTags.INLINE,
    label: String = "Equation",
    /** Marks the button as armed. The Draw tab's tool stays in hand after the panel closes. */
    active: Boolean = false,
    onRetainTarget: () -> Unit = {},
    onReleaseTarget: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var latex by remember { mutableStateOf(EXAMPLE_EQUATION) }
    var targetRetained by remember { mutableStateOf(false) }

    // Retain the exact caret until the panel submits, and release it on every exit path.
    DisposableEffect(targetRetained) {
        onDispose {
            if (targetRetained) onReleaseTarget()
        }
    }

    fun dismiss() {
        expanded = false
        targetRetained = false
    }

    Box(modifier) {
        RibbonButton(
            icon = MaterialSymbols.Function,
            label = label,
            active = active || expanded,
            // Stays live while its own panel is open, or dismissing it would be impossible the moment
            // the panel took the focus that enabled the button.
            enabled = enabled || expanded,
            modifier = Modifier.testTag(tag),
            onClick = {
                editing = existing != null
                latex = existing ?: EXAMPLE_EQUATION
                onRetainTarget()
                targetRetained = true
                expanded = true
            },
        )

        EquationSourceDialog(
            expanded = expanded,
            latex = latex,
            editing = editing,
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
 * Shared between the ribbon's ƒ and the object toolkit's, which is why it is a composable of its own:
 * editing the formula on a placed equation is the same act as writing one, and two copies of a
 * validating LaTeX editor would drift apart the first time either was touched.
 *
 * **A formula is measured on its way through.** The submit path has to render it anyway — the preview
 * is asynchronous, so it can still be showing a stale success when an invalid source is submitted,
 * and the only honest check is to parse it again here. Handing that renderer's metrics to [onSubmit]
 * is what lets an equation arrive on the canvas already knowing its size, for free.
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

    LaunchedEffect(source) {
        previewError = null
        submitError = null
    }

    FloatingSettingsPanel(
        expanded = expanded,
        onDismissRequest = onDismiss,
        title = if (editing) "Edit equation" else "Insert equation",
    ) {
        OutlinedTextField(
            value = source,
            onValueChange = { source = it },
            label = { Text("LaTeX") },
            supportingText = { Text("Enter LaTeX without \$ delimiters.") },
            minLines = 2,
            maxLines = 5,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(EquationTags.SOURCE),
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = "Preview",
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
            TextButton(onClick = onDismiss, enabled = !submitting) { Text("Cancel") }
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
                            submitError = error.message ?: "This equation could not be parsed."
                            submitting = false
                        }
                    }
                },
                modifier = Modifier.testTag(EquationTags.SUBMIT),
            ) {
                if (submitting) {
                    // Uncontained, and sized to the label it replaces — the same call the recognition
                    // panel's in-button spinner makes, for the same reason: a contained indicator
                    // inside a button is a container in a container. `memory/expressivePlan.md` EX6.
                    LoadingIndicator(Modifier.size(18.dp))
                } else {
                    Text(if (editing) "Update" else "Insert")
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
