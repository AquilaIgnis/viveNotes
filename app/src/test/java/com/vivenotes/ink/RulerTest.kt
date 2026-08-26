package com.vivenotes.ink

import com.vivenotes.data.RulerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot

/**
 * The ruler's arithmetic — `memory/rulerPlan.md` RD8.
 *
 * Projection onto a rotated edge looks right in several arrangements and is correct in one, and
 * nothing about it is visible until a stroke lands somewhere strange. The rotated cases are the ones
 * that matter: at angle 0 a sign error in the frame conversion is invisible.
 */
class RulerTest {

    private fun straight(
        centerX: Float = 500f,
        centerY: Float = 400f,
        angle: Float = 0f,
        size: Float = 800f,
    ) = Ruler(centerX, centerY, angle, RulerKind.Straight, size)

    private fun protractor(
        centerX: Float = 500f,
        centerY: Float = 400f,
        angle: Float = 0f,
        size: Float = 800f,
    ) = Ruler(centerX, centerY, angle, RulerKind.Protractor, size)

    private fun assertPoint(message: String, expected: InkPoint, actual: InkPoint) {
        assertEquals("$message x", expected.x, actual.x, 0.01f)
        assertEquals("$message y", expected.y, actual.y, 0.01f)
    }

    // --- the frame ---------------------------------------------------------------------------

    @Test
    fun `local and page coordinates are inverses at any angle`() {
        val ruler = straight(angle = 0.7f)
        val point = InkPoint(613f, 122f)

        assertPoint("round trip", point, ruler.toPage(ruler.toLocal(point)))
    }

    // --- the straightedge --------------------------------------------------------------------

    /** Where a stroke would land if it were starting here — the down's own reading. */
    private fun Ruler.snapFrom(point: InkPoint) = snap(point, sideOf(point))

    @Test
    fun `a stroke above the ruler lands on its upper edge`() {
        val ruler = straight()
        // 30dp above the band's top edge, which sits at 400 - 60.
        val snapped = ruler.snapFrom(InkPoint(560f, 400f - Ruler.BAND_DP / 2f - 30f))

        assertPoint("upper edge", InkPoint(560f, 400f - Ruler.BAND_DP / 2f), snapped)
    }

    /** Two long edges, so it draws from either side — RD5. */
    @Test
    fun `a stroke below the ruler lands on its lower edge`() {
        val ruler = straight()
        val snapped = ruler.snapFrom(InkPoint(560f, 400f + Ruler.BAND_DP / 2f + 30f))

        assertPoint("lower edge", InkPoint(560f, 400f + Ruler.BAND_DP / 2f), snapped)
    }

    /**
     * The edge is the *stroke's*, not the sample's — RD5a.
     *
     * The bug this holds off was reported from a device: a line swept along the ruler and over its
     * body flipped onto the far edge halfway, and joined the two halves with a run straight across
     * the ruler's face. Asking each point which edge is nearer is what does that, because the
     * answer changes the moment the hand crosses the middle.
     */
    @Test
    fun `a stroke crossing the ruler stays on the edge it started from`() {
        val ruler = straight()
        val start = InkPoint(400f, 400f - Ruler.BAND_DP / 2f - 10f)
        val side = ruler.sideOf(start)

        assertEquals("started above it", RulerSide.Negative, side)
        // The hand has crossed to the far side of the body. The line has not.
        val crossed = ruler.snap(InkPoint(600f, 400f + Ruler.BAND_DP / 2f + 10f), side)

        assertPoint("still the upper edge", InkPoint(600f, 400f - Ruler.BAND_DP / 2f), crossed)
        assertEquals(
            "and the sample on its own would have said otherwise",
            RulerSide.Positive,
            ruler.sideOf(InkPoint(600f, 400f + Ruler.BAND_DP / 2f + 10f)),
        )
    }

    @Test
    fun `a ruler runs out, so the ends clamp`() {
        val ruler = straight()
        val past = ruler.snapFrom(InkPoint(5000f, 300f))

        assertEquals("clamped to the + end", 500f + 400f, past.x, 0.01f)
        assertEquals(400f - Ruler.BAND_DP / 2f, past.y, 0.01f)
    }

    /**
     * The case angle 0 cannot catch: rotated a quarter turn, "along" is the page's y axis and
     * "across" is its x, so a frame conversion with a sign error sends the stroke sideways.
     */
    @Test
    fun `a rotated ruler snaps across its own axis, not the page's`() {
        val ruler = straight(angle = (PI / 2).toFloat())

        // 40dp to the right of the centre is now 40dp *across* the ruler, so it lands on the edge
        // 60dp out — at the same distance along, which is the page's y.
        val snapped = ruler.snapFrom(InkPoint(500f + 40f, 400f + 200f))

        assertEquals("stayed at its distance along", 400f + 200f, snapped.y, 0.01f)
        assertEquals("pulled onto the near edge", 500f + Ruler.BAND_DP / 2f, snapped.x, 0.01f)
    }

    @Test
    fun `every snapped point on one side is collinear`() {
        val ruler = straight(angle = 0.4f)
        val edge = (0..10).map { step ->
            // A wobbling hand: the along-axis advances, the across-axis wanders.
            val wobble = if (step % 2 == 0) 22f else -14f
            val point = ruler.toPage(InkPoint(-300f + step * 60f, Ruler.BAND_DP / 2f + 20f + wobble))
            ruler.snap(point, RulerSide.Positive)
        }

        val first = edge.first()
        val last = edge.last()
        val length = hypot(last.x - first.x, last.y - first.y)
        edge.forEach { point ->
            // Twice the triangle's area over its base is the distance from the line.
            val cross = (last.x - first.x) * (point.y - first.y) -
                (last.y - first.y) * (point.x - first.x)
            assertEquals("$point is off the line", 0f, cross / length, 0.01f)
        }
    }

    @Test
    fun `the body is grabbed, the space past the edge is not`() {
        val ruler = straight()

        val half = Ruler.BAND_DP / 2f

        assertTrue("centre", ruler.grabs(InkPoint(500f, 400f)))
        assertTrue("inside the band", ruler.grabs(InkPoint(700f, 400f + half - 2f)))
        assertFalse("past the edge", ruler.grabs(InkPoint(700f, 400f + half + 2f)))
        assertFalse("past the end", ruler.grabs(InkPoint(500f + 420f, 400f)))
    }

    @Test
    fun `a stroke engages on the ruler and just past it, but not away from it`() {
        val ruler = straight()
        val edge = 400f + Ruler.BAND_DP / 2f

        assertTrue("on it", ruler.engages(InkPoint(500f, 400f), Ruler.SNAP_TOLERANCE_DP))
        assertTrue("just past", ruler.engages(InkPoint(500f, edge + 10f), Ruler.SNAP_TOLERANCE_DP))
        assertFalse(
            "well away",
            ruler.engages(InkPoint(500f, edge + Ruler.SNAP_TOLERANCE_DP + 5f), Ruler.SNAP_TOLERANCE_DP),
        )
    }

    /**
     * Two fingers twisting on the ruler turn it about its own centre, which is what makes it feel
     * like an object held rather than a control operated — and, unlike a handle at one end, works
     * on a ruler whose ends are off the screen.
     */
    @Test
    fun `turning is about its own centre, and does not move it`() {
        val ruler = straight()

        val turned = ruler.turnedBy((PI / 2).toFloat())

        assertEquals("the centre is the pivot", 500f, turned.centerX, 0.01f)
        assertEquals(400f, turned.centerY, 0.01f)
        // A quarter turn puts the `+` end directly below the centre.
        assertPoint("the + end swung round", InkPoint(500f, 800f), turned.toPage(InkPoint(400f, 0f)))
    }

    /** Turns compose, because a twist arrives as a stream of small deltas rather than one angle. */
    @Test
    fun `successive turns accumulate`() {
        val ruler = straight()

        val turned = ruler.turnedBy(0.3f).turnedBy(0.4f).turnedBy(-0.1f)

        assertEquals(0.6f, turned.angleRadians, 0.001f)
    }

    // --- the degree dial ---------------------------------------------------------------------

    /**
     * The dial counts anticlockwise, which is the opposite of the stored angle.
     *
     * Screen coordinates put `+y` downwards, so a positive stored angle tips the right-hand end of
     * the ruler *down* — and a ruler tipped down to the right reads 315°, not 45°. The two cases
     * below are the ones that would look identical under either convention if only one were checked.
     */
    @Test
    fun `the dial reads anticlockwise from the ruler lying flat`() {
        assertEquals(0, straight().degrees())
        assertEquals("tipped down to the right", 315, straight(angle = (PI / 4).toFloat()).degrees())
        assertEquals("tipped up to the right", 45, straight(angle = (-PI / 4).toFloat()).degrees())
        assertEquals(270, straight(angle = (PI / 2).toFloat()).degrees())
        assertEquals(90, straight(angle = (-PI / 2).toFloat()).degrees())
        // Twice round: a ruler says where it is, not how it got there.
        assertEquals(315, straight(angle = (PI / 4 + 2 * PI).toFloat()).degrees())
    }

    /** From one of the eight positions a tap is exactly 45°, which is what the gesture promises. */
    @Test
    fun `tapping the dial steps a clean eighth of a turn`() {
        var ruler = straight()
        listOf(45, 90, 135, 180, 225, 270, 315, 0).forEach { expected ->
            ruler = ruler.turnedToNextEighth()
            assertEquals(expected, ruler.degrees())
        }
    }

    /** And from a hand-turned angle it tidies up rather than carrying the untidiness forward. */
    @Test
    fun `tapping the dial from a free angle lands on the next eighth`() {
        // -0.23 rad reads 13° on the dial, so a tap should tidy it to 45 rather than creep to 58.
        assertEquals(45, straight(angle = -0.23f).turnedToNextEighth().degrees())
        assertEquals("57 degrees should tidy to 90", 90, straight(angle = -1.0f).turnedToNextEighth().degrees())
    }

    @Test
    fun `the dial is a target in the middle, and the rest of the body is not`() {
        val ruler = straight()

        assertTrue("dead centre", ruler.grabsDial(InkPoint(500f, 400f)))
        assertTrue("just off centre", ruler.grabsDial(InkPoint(500f + Ruler.DIAL_RADIUS_DP, 400f)))
        assertFalse("out along the ruler", ruler.grabsDial(InkPoint(700f, 400f)))
        assertTrue("but that is still the body", ruler.grabs(InkPoint(700f, 400f)))
    }

    /**
     * The semicircle carries a dial too, but not at its centre: that point is the middle of the
     * flat edge, so a dial there would straddle the edge and cover the mark the protractor is lined
     * up by. It sits up inside the disc.
     */
    @Test
    fun `the semicircle's dial sits inside the disc, not on its flat edge`() {
        val ruler = protractor()
        val dial = ruler.dialCenter()

        assertEquals("on the centre line", 0f, dial.x, 0.01f)
        assertTrue("up inside the drawing half", dial.y < -Ruler.DIAL_RADIUS_DP)
        assertTrue("and clear of the arc", -dial.y < ruler.reach - Ruler.DIAL_RADIUS_DP)

        val onPage = ruler.toPage(dial)
        assertTrue("the dial is a target there", ruler.grabsDial(onPage))
        assertFalse("and the flat edge's centre is not", ruler.grabsDial(InkPoint(500f, 400f)))
        assertTrue("but it is still somewhere you can take hold", ruler.grabs(onPage))
    }

    /** And it travels with the ruler, so a turned protractor keeps its dial inside the disc. */
    @Test
    fun `the semicircle's dial follows a rotated protractor`() {
        val ruler = protractor(angle = (PI / 2).toFloat())

        assertTrue(ruler.grabsDial(ruler.toPage(ruler.dialCenter())))
    }

    /** The dial travels with the ruler, so it is found in the middle at any angle. */
    @Test
    fun `the dial follows a rotated ruler`() {
        val ruler = straight(angle = 1.1f)

        assertTrue(ruler.grabsDial(InkPoint(500f, 400f)))
    }

    // --- the semicircle ----------------------------------------------------------------------

    @Test
    fun `the semicircle snaps onto its arc at the radius`() {
        val ruler = protractor()
        // Somewhere above the centre, inside the disc.
        val snapped = ruler.snapFrom(InkPoint(600f, 300f))

        assertEquals("on the arc", 400f, hypot(snapped.x - 500f, snapped.y - 400f), 0.01f)
        assertTrue("stayed on the drawing side", snapped.y <= 400f)
    }

    /** Radial projection keeps the direction from the centre; only the distance changes. */
    @Test
    fun `the arc is reached along the line from the centre`() {
        val ruler = protractor()
        val snapped = ruler.snapFrom(InkPoint(500f, 100f))

        assertPoint("straight up", InkPoint(500f, 0f), snapped)
    }

    @Test
    fun `below the flat edge clamps to the end the stroke set out from`() {
        val ruler = protractor()

        assertPoint("+ end", InkPoint(900f, 400f), ruler.snapFrom(InkPoint(700f, 600f)))
        assertPoint("- end", InkPoint(100f, 400f), ruler.snapFrom(InkPoint(300f, 600f)))

        // The arc has one drawing edge, so the held side is only this tie-break — and it holds:
        // a stroke that began at the `+` end does not leap the whole arc because the hand dipped
        // below the flat edge over on the left.
        val fromThePlusEnd = ruler.sideOf(InkPoint(700f, 300f))
        assertEquals(RulerSide.Positive, fromThePlusEnd)
        assertPoint(
            "stayed at the end it came from",
            InkPoint(900f, 400f),
            ruler.snap(InkPoint(300f, 600f), fromThePlusEnd),
        )
    }

    /** No direction to project along, so it takes an end rather than dividing by zero. */
    @Test
    fun `the exact centre is not a division by zero`() {
        val snapped = protractor().snapFrom(InkPoint(500f, 400f))

        assertEquals(400f, hypot(snapped.x - 500f, snapped.y - 400f), 0.01f)
    }

    @Test
    fun `the semicircle is only the half it draws`() {
        val ruler = protractor()

        assertTrue("above the flat edge", ruler.grabs(InkPoint(500f, 300f)))
        assertFalse("below it", ruler.grabs(InkPoint(500f, 500f)))
        assertFalse("outside the arc", ruler.grabs(InkPoint(500f, -100f)))
    }

    @Test
    fun `a rotated semicircle carries its flat edge with it`() {
        val ruler = protractor(angle = (PI / 2).toFloat())

        // Turned a quarter turn, the half it draws is to the page's left of the flat edge.
        assertTrue("the drawing half followed", ruler.grabs(InkPoint(700f, 400f)))
        assertFalse("the empty half followed", ruler.grabs(InkPoint(300f, 400f)))
    }
}
