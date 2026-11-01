# Graphs

## Make a graph

- **From handwriting:** lasso the formula, tap **Σ Math**, then **Graph**, then **Place on page**.
- **From an equation object:** select it and tap the **Graph** button on its toolkit.
- **From LaTeX:** type it into the Math pane's source field, then **Graph** → **Place on page**.

The graph lands just below the formula it came from.

## What can be graphed

| You write                  | Plotted against            |
| -------------------------- | -------------------------- |
| `x^2 - 4` or `y = x^2 - 4` | x                          |
| `f(x) = …`, `x(t) = …`     | the letter in the brackets |
| several letters            | x, else t, else θ          |

Every other letter becomes a **slider** (up to five). Piecewise and special functions preview in the
Math pane but can't be placed.

## On the page

Tap a graph to select it. Drag it to move it; drag a corner to resize.

**Toolkit:** colour (of the curve) · copy · lock · **Move points** · **Graph options** · delete.
A locked graph stays put, but its points, tracer and sliders still work.

**Graph options menu:**

| Entry                                | Does                                               |
| ------------------------------------ | -------------------------------------------------- |
| No background / Grid / Dotted        | what's behind the curve                            |
| Show equation                        | the formula along the top                          |
| Label points                         | coordinates beside each point                      |
| Show original                        | the first function, faint, after you've changed it |
| Show derivative                      | f′ as a dashed curve                               |
| Trace and tangent                    | a draggable point with its tangent and slope       |
| Area under the curve                 | shaded area with its integral                      |
| Zoom in / Zoom out / Fit to function | the visible window                                 |
| Sliders                              | edit each slider's value, min, max and step        |
| Reset function                       | back to what was placed (after a change)           |

## Points of interest

Zeros, the y-intercept, maxima, minima and inflection points are marked and labelled.

**Move points** on: drag a point to reshape the function.

| Drag                     | The function         |
| ------------------------ | -------------------- |
| a zero of a polynomial   | moves that root only |
| any other zero           | shifts sideways      |
| the y-intercept          | shifts up or down    |
| a max, min or inflection | moves with it        |

Each drag is one undo step.

## Trace and tangent

Slide the tracer along the curve. It shows the point, the slope (`dy/dx`) and the tangent's equation,
and snaps onto nearby points of interest.

## Area under the curve

Drag either dashed bound (or its tab). The label gives `∫ y dx`, plus `∫ |y| dx` when the curve
crosses the axis. Below-axis area is shaded violet.

## Sliders

For a formula like `A sin(ωt + φ)`, each extra letter gets a slider under the plot.

- **Drag or tap** a track to set the value. The value is saved when you lift your finger.
- **▶** sweeps the value back and forth; tap again to pause.
- **Tap a slider's name** to type an exact value or change its range and step.
- Letters that are only added (φ, h, k, b) start at 0; the rest start at 1.

Sliders work only on a selected graph. Their values print with the graph in PDFs.

## Try these

| LaTeX                                    | Shows                               |
| ---------------------------------------- | ----------------------------------- |
| `y = A\sin(\omega t + \phi)`             | amplitude, frequency, phase         |
| `y = A e^{-kt}\cos(\omega t)`            | a damped wave (press play on k)     |
| `y = a(x-h)^2 + k`                       | vertex form of a parabola           |
| `y = A\sin(B(x-C)) + D`                  | all four sine transformations       |
| `x(t) = x_0 + v_0 t + \frac{1}{2} a t^2` | motion with constant acceleration   |
| `f(x) = x^3 - 3x`                        | maxima, minima, an inflection point |
