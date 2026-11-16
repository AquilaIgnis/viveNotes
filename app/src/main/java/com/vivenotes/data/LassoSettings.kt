package com.vivenotes.data

import com.vivenotes.model.Outline
import kotlinx.serialization.Serializable

/** User preferences for what the lasso can pick up. All kinds start enabled. */
@Serializable
data class LassoSettings(
    val ink: Boolean = true,
    val shapes: Boolean = true,
    val text: Boolean = true,
    val tables: Boolean = true,
    val objects: Boolean = true,
) {
    /** Objects is the catch-all: formulas, images, graphs, and future outline kinds. */
    fun allows(outline: Outline): Boolean = when (outline) {
        is Outline.Ink -> ink
        is Outline.Shape -> shapes
        is Outline.Text -> text
        is Outline.Table -> tables
        else -> objects
    }
}
