package com.vivenotes.graph

import com.vivenotes.model.GraphParameter
import com.vivenotes.model.Outline
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/** The graph's function with its sliders' values bound: what is drawn, analysed and integrated. */
fun Outline.Graph.function(): GraphExpression? =
    GraphExpression.parse(expression)?.with(parameters.map(GraphParameter::value))

/** What was placed, at today's slider values — the faint curve a mutation is compared against. */
fun Outline.Graph.originalFunction(): GraphExpression? =
    originalExpression?.let(GraphExpression::parse)?.with(parameters.map(GraphParameter::value))

/**
 * The arithmetic of a graph's sliders that is not drawing: how a value reads, and how play sweeps one.
 */
object GraphSliders {

    /** How long play takes to cross the whole range one way. Desmos's default speed, about. */
    const val SWEEP_MILLIS = 8_000L

    /**
     * `ω = 2.5`: the value to its step's decimal places, a true minus, never `−0`.
     *
     * [value] is passed rather than read from [parameter] so a slider being dragged reads live.
     */
    fun text(parameter: GraphParameter, value: Double = parameter.value): String =
        "${parameter.label} = ${number(value, parameter.step)}"

    /** [value] to [step]'s decimal places. */
    fun number(value: Double, step: Double): String {
        if (!value.isFinite()) return "?"
        val places = if (step > 0.0 && step.isFinite()) {
            BigDecimal.valueOf(step).stripTrailingZeros().scale().coerceIn(0, 6)
        } else {
            3
        }
        val rounded = BigDecimal.valueOf(value).setScale(places, RoundingMode.HALF_UP)
        val plain = if (rounded.signum() == 0) rounded.abs().toPlainString() else rounded.toPlainString()
        return plain.replace('-', '−')
    }

    /**
     * Where play has carried [value] after [elapsedMillis], moving in [direction] (+1 up, −1 down)
     * and turning back at either end, as Desmos's ⇄ does. Answers the new value and direction.
     */
    fun sweep(parameter: GraphParameter, value: Double, direction: Int, elapsedMillis: Long): Pair<Double, Int> {
        if (!parameter.isUsable) return value to direction
        val span = parameter.max - parameter.min
        var next = value + direction * span * elapsedMillis / SWEEP_MILLIS
        var heading = direction
        // A frame long enough to cross the range more than once is folded, not clamped.
        repeat(4) {
            when {
                next > parameter.max -> {
                    next = parameter.max - (next - parameter.max)
                    heading = -1
                }
                next < parameter.min -> {
                    next = parameter.min + (parameter.min - next)
                    heading = 1
                }
            }
        }
        return next.coerceIn(parameter.min, parameter.max) to heading
    }

    /** Whether two slider values read the same: a drag that ends where it began is not an edit. */
    fun same(a: Double, b: Double): Boolean = abs(a - b) <= 1e-12 * maxOf(1.0, abs(a), abs(b))
}
