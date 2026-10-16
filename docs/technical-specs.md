# Ink 🖋️

## Colors

Ink, shapes and tables don't store black or white as a fixed color. They store a "follows theme" flag, and the actual color is picked each time the page is drawn.

1. Saving the choice: each stroke has a colorFollowsTheme column (data/db/Entities.kt:348). In the pen panel, tapping the swatch that matches the automatic ink on
   the current canvas sets it to true. Any other color sets it to false (ui/panel/PenPanel.kt:203).
2. Picking the color when drawing: automaticColorOr() (data/PenSettings.kt:333) decides what color to paint:
   - true → paint with the canvas's text color.
   - false → keep the color the user picked.
   - null (strokes saved before the flag existed) → pure white or pure black is treated as automatic, anything else as picked.

   On screen, CanvasInkPainter (ink/PageStroke.kt:581) passes in LocalCanvasColors.current.text. That's why switching between light and dark flips the ink,
   the same way text already flipped. Shape borders (ShapeLayer.kt:549) and table borders (TableContainer.kt:346) go through the same function.

3. PDF export: PdfCanvasColors.forPage() (pdf/PageMeasure.kt:60) always uses the light canvas, whatever Switch Background is set to. So the canvas text color is black,
   and PageRenderer resolves automatic ink against it (pdf/PageRenderer.kt:192). Ink written white on a dark canvas comes out black on white paper. If a page has its own
   background color, paintedWith() works out a readable ink color for that background instead.
