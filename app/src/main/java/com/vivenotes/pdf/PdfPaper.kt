package com.vivenotes.pdf

import com.vivenotes.model.Orientation
import com.vivenotes.model.PageStyle
import com.vivenotes.model.PaperDimensions
import com.vivenotes.model.PaperSize
import com.vivenotes.model.PrintMargins
import kotlin.math.roundToInt

/**
 * The sheet an export is laid onto — `memory/pdfExportPlan.md` PD2.
 *
 * Two coordinate systems meet here and neither is negotiable. A page is measured in **dp at 160 to
 * the inch** ([PageStyle.DP_PER_INCH]) — that is what makes a page at 100% zoom physically the size
 * it claims — and a PDF is measured in **PostScript points at 72 to the inch**, which is what
 * `PdfDocument.PageInfo.Builder` takes. So [POINTS_PER_DP] is 0.45 and A4 comes out 595 × 842, the
 * numbers every other tool prints.
 *
 * [tileWidthDp] and [tileHeightDp] — the printable area, the sheet less its margins — are what the
 * canvas is actually cut into (PD3). Cutting at the *sheet* size instead would put a band of every
 * page underneath the printer's margin, where the writing on it is simply gone.
 */
data class PdfPaper(
    val widthDp: Float,
    val heightDp: Float,
    val margins: PrintMargins,
) {
    val marginLeftDp: Float get() = margins.leftInches * PageStyle.DP_PER_INCH
    val marginTopDp: Float get() = margins.topInches * PageStyle.DP_PER_INCH
    val marginRightDp: Float get() = margins.rightInches * PageStyle.DP_PER_INCH
    val marginBottomDp: Float get() = margins.bottomInches * PageStyle.DP_PER_INCH

    /**
     * The printable area, floored at [MIN_TILE_DP].
     *
     * Margins are entered by hand and can be set wider than the sheet ([PrintMargins.MAX_INCHES] is
     * 4 inches, which swallows an A6 twice over). A tile of zero or negative width would divide the
     * canvas into infinitely many pages, so the floor is not a nicety — it is what stops a typo in
     * a text field from hanging the export.
     */
    val tileWidthDp: Float get() = (widthDp - marginLeftDp - marginRightDp).coerceAtLeast(MIN_TILE_DP)

    val tileHeightDp: Float get() = (heightDp - marginTopDp - marginBottomDp).coerceAtLeast(MIN_TILE_DP)

    val widthPoints: Int get() = (widthDp * POINTS_PER_DP).roundToInt().coerceAtLeast(1)

    val heightPoints: Int get() = (heightDp * POINTS_PER_DP).roundToInt().coerceAtLeast(1)

    companion object {
        /** 72 points to the inch over 160 dp to the inch. */
        const val POINTS_PER_DP: Float = 72f / PageStyle.DP_PER_INCH

        /** Small enough to be a deliberate margin setting, large enough to still be a page. */
        const val MIN_TILE_DP: Float = 48f

        /**
         * The sheet for a chosen size, turned by [orientation].
         *
         * [PaperSize.Auto] resolves to A4 rather than to nothing: an unbounded page still has to be
         * cut into sheets of *some* size, and A4 is the one the reference drawing names. The rest
         * is [PageStyle]'s own arithmetic, borrowed rather than repeated so that a size which is
         * one thing on the canvas cannot be another thing in the export.
         */
        fun of(
            size: PaperSize,
            orientation: Orientation,
            custom: PaperDimensions? = null,
            margins: PrintMargins = PrintMargins(),
        ): PdfPaper {
            val resolved = if (size == PaperSize.Auto) PaperSize.A4 else size
            val (width, height) = PageStyle(
                paper = resolved,
                orientation = orientation,
                customPaper = custom,
            ).pageSizeDp ?: (PaperSize.A4.widthInches * PageStyle.DP_PER_INCH to
                PaperSize.A4.heightInches * PageStyle.DP_PER_INCH)
            return PdfPaper(width, height, margins)
        }

        /** What a page proposes for itself: its own paper, its own orientation, its own margins. */
        fun matching(style: PageStyle): PdfPaper =
            of(style.paper, style.orientation, style.customPaper, style.margins)
    }
}
