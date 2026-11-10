package org.example.research;

import com.github.micycle1.clipper2.Clipper;
import com.github.micycle1.clipper2.core.*;
import com.github.micycle1.clipper2.offset.*;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/** Only fixtures and assertions: all offsets/Boolean operations use Clipper2-java. */
class ClipperGeometryTest {
    private static final double RADIUS_MM = 6.0 / 2;
    private static final int PRECISION = 6;
    private static final double EPS = 0.000001;

    private static PathD path(double... xy) {
        PathD result = new PathD();
        for (int i = 0; i < xy.length; i += 2) result.add(new PointD(xy[i], xy[i + 1]));
        return result;
    }

    private static PathD rectangle(double x, double y, double w, double h) {
        return path(x, y, x + w, y, x + w, y + h, x, y + h);
    }

    private static PathsD paths(PathD... paths) {
        PathsD result = new PathsD();
        result.addAll(Arrays.asList(paths));
        return result;
    }

    private static PathsD offset(PathsD input, double delta) {
        return Clipper.inflatePaths(input, delta, JoinType.Miter, EndType.Polygon, 2.0, 0.001, PRECISION);
    }

    private static void bounds(PathsD result, double x0, double y0, double x1, double y1) {
        assertFalse(result.isEmpty());
        RectD b = Clipper.getBounds(result);
        assertAll(() -> assertEquals(x0, b.left, EPS), () -> assertEquals(y0, b.top, EPS),
            () -> assertEquals(x1, b.right, EPS), () -> assertEquals(y1, b.bottom, EPS),
            () -> assertEquals(x1 - x0, b.right - b.left, EPS),
            () -> assertEquals(y1 - y0, b.bottom - b.top, EPS));
    }

    @Test void outsideRectangle40x30With6mmBit() {
        PathsD result = offset(paths(rectangle(0, 0, 40, 30)), RADIUS_MM);
        assertEquals(1, result.size());
        bounds(result, -3, -3, 43, 33);
        assertEquals(46 * 36, Math.abs(Clipper.area(result)), EPS);
    }

    @Test void insideRectangle40x30With6mmBit() {
        PathsD result = offset(paths(rectangle(0, 0, 40, 30)), -RADIUS_MM);
        assertEquals(1, result.size());
        bounds(result, 3, 3, 37, 27);
        assertEquals(34 * 24, Math.abs(Clipper.area(result)), EPS);
    }

    private static PathsD withHole() {
        PathD hole = rectangle(15, 10, 10, 10);
        Collections.reverse(hole); // opposite winding to outer boundary
        return paths(rectangle(0, 0, 40, 30), hole);
    }

    private static void holeResult(PathsD result, double outerX0, double outerY0, double outerX1,
                                   double outerY1, double holeX0, double holeY0, double holeX1, double holeY1,
                                   double expectedArea) {
        assertEquals(2, result.size());
        PathD outer = result.stream().max(java.util.Comparator.comparingDouble(p -> Math.abs(Clipper.area(p)))).orElseThrow();
        PathD hole = result.stream().filter(p -> p != outer).findFirst().orElseThrow();
        bounds(paths(outer), outerX0, outerY0, outerX1, outerY1);
        bounds(paths(hole), holeX0, holeY0, holeX1, holeY1);
        assertTrue(Clipper.area(outer) * Clipper.area(hole) < 0, "Hole must retain opposite winding");
        assertEquals(expectedArea, Math.abs(Clipper.area(result)), EPS);
        // An area entirely within the hole must remain empty after offsetting.
        assertTrue(Clipper.intersect(result, paths(rectangle(19, 14, 2, 2)), FillRule.NonZero, PRECISION).isEmpty());
    }

    @Test void positiveOffsetExpandsOuterAndShrinksHole() {
        holeResult(offset(withHole(), 3), -3, -3, 43, 33, 18, 13, 22, 17, 1640);
    }

    @Test void negativeOffsetShrinksOuterAndExpandsHole() {
        holeResult(offset(withHole(), -3), 3, 3, 37, 27, 12, 7, 28, 23, 560);
    }

    @Test void regionNarrowerThanBitDisappears() {
        assertTrue(offset(paths(rectangle(0, 0, 40, 5)), -3).isEmpty());
        assertTrue(offset(paths(rectangle(0, 0, 40, 6)), -3).isEmpty(), "Zero-width region has no area");
        bounds(offset(paths(rectangle(0, 0, 40, 6.1)), -3), 3, 3, 37, 3.1);
    }

    private static PathsD dumbbell(double neckWidth) {
        double low = 10 - neckWidth / 2, high = 10 + neckWidth / 2;
        return paths(path(0, 0, 20, 0, 20, low, 40, low, 40, 0, 60, 0,
            60, 20, 40, 20, 40, high, 20, high, 20, 20, 0, 20));
    }

    @Test void narrowNeckSplitsIntoTwoAccessibleRegions() {
        PathsD result = offset(dumbbell(4), -3);
        assertEquals(2, result.size());
        bounds(result, 3, 3, 57, 17);
        assertEquals(2 * 14 * 14, Math.abs(Clipper.area(result)), EPS);
        assertTrue(Clipper.intersect(result, paths(rectangle(25, 9, 10, 2)), FillRule.NonZero, PRECISION).isEmpty());
        assertEquals(1, offset(dumbbell(7), -3).size(), "Wider-than-bit neck remains connected");
    }

    @Test void decimalAndNegativeCoordinatesKeepSixDecimalResolution() {
        PathsD input = paths(rectangle(-12.345678, 7.654321, 40, 30));
        bounds(offset(input, 3), -15.345678, 4.654321, 30.654322, 40.654321);
        bounds(offset(input, -3), -9.345678, 10.654321, 24.654322, 34.654321);
        RectD b = Clipper.getBounds(offset(input, 3));
        double error = Math.max(Math.abs(b.left + 15.345678), Math.abs((b.right - b.left) - 46));
        System.out.println("Decimal fixture maximum checked coordinate/dimension error (mm): " + error);
    }

    @Test void roundJoinsMatchCutterRadiusWithinConfiguredArcTolerance() {
        PathsD input = paths(rectangle(0, 0, 40, 30));
        PathsD result = Clipper.inflatePaths(input, 3, JoinType.Round, EndType.Polygon, 2, 0.001, PRECISION);
        assertEquals(1, result.size());
        RectD b = Clipper.getBounds(result);
        assertEquals(46, b.right - b.left, 0.0021);
        assertEquals(36, b.bottom - b.top, 0.0021);
        assertEquals(1200 + 140 * 3 + Math.PI * 9, Math.abs(Clipper.area(result)), 0.05);
        for (PointD p : result.get(0)) {
            double nearestCorner = Math.min(Math.min(Math.hypot(p.x, p.y), Math.hypot(p.x - 40, p.y)),
                Math.min(Math.hypot(p.x, p.y - 30), Math.hypot(p.x - 40, p.y - 30)));
            assertEquals(3, nearestCorner, 0.000002);
        }
        PathsD inside = Clipper.inflatePaths(input, -3, JoinType.Round, EndType.Polygon, 2, 0.001, PRECISION);
        bounds(inside, 3, 3, 37, 27);
        System.out.println("Round joins: bounds " + (b.right - b.left) + " x " + (b.bottom - b.top)
            + " mm; area error " + Math.abs(Math.abs(Clipper.area(result)) - (1620 + Math.PI * 9)) + " mm^2");
    }

    @Test void offsetsDoNotMutateInputAndIgnoreGlobalWindingReversal() {
        PathsD input = withHole();
        double[][] before = input.stream().map(p -> p.stream().flatMapToDouble(v -> java.util.stream.DoubleStream.of(v.x, v.y)).toArray()).toArray(double[][]::new);
        PathsD expected = offset(input, -3);
        for (int i = 0; i < input.size(); i++) {
            double[] after = input.get(i).stream().flatMapToDouble(v -> java.util.stream.DoubleStream.of(v.x, v.y)).toArray();
            assertArrayEquals(before[i], after);
        }
        input.forEach(Collections::reverse);
        PathsD reversed = offset(input, -3);
        assertTrue(Clipper.xor(expected, reversed, FillRule.NonZero, PRECISION).isEmpty());
    }
}
