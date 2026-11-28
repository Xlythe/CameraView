package com.xlythe.view.camera.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Ported from Muse's InkMotionFilterTest, which pins the same behaviour. */
public class MotionFilterTest {
    /** A horizontal drag with a 10 Hz tremor on it, the middle of a hand's tremor range. */
    private static List<float[]> tremorStroke(float amplitude) {
        List<float[]> samples = new ArrayList<>();
        for (int index = 0; index < 120; index++) {
            float t = index / 120f;
            samples.add(new float[] {index * 0.5f,
                    amplitude * (float) Math.sin(2 * Math.PI * 10 * t), index * 1000L / 120L});
        }
        return samples;
    }

    private static List<float[]> run(MotionFilter filter, List<float[]> stroke, float pressure) {
        List<float[]> out = new ArrayList<>();
        for (float[] sample : stroke) {
            filter.filter(sample[0], sample[1], pressure, (long) sample[2]);
            out.add(new float[] {filter.x(), filter.y(), filter.pressure()});
        }
        return out;
    }

    private static float pathLength(List<float[]> points) {
        float total = 0;
        for (int i = 1; i < points.size(); i++) {
            float dx = points.get(i)[0] - points.get(i - 1)[0];
            float dy = points.get(i)[1] - points.get(i - 1)[1];
            total += (float) Math.sqrt(dx * dx + dy * dy);
        }
        return total;
    }

    @Test
    public void theFirstSampleIsNeverMoved() {
        MotionFilter filter = new MotionFilter(1f, 0.5f);
        filter.filter(17f, -4f, Float.NaN, 1000);
        assertEquals(17f, filter.x(), 0f);
        assertEquals(-4f, filter.y(), 0f);
    }

    @Test
    public void aTremorAffectedLineComesOutStraighter() {
        List<float[]> stroke = tremorStroke(2f);
        List<float[]> filtered = run(new MotionFilter(0.9f, 0f), stroke, Float.NaN);
        float straight = stroke.get(stroke.size() - 1)[0] - stroke.get(0)[0];
        float rawTremor = pathLength(stroke) - straight;
        float filteredTremor = pathLength(filtered) - straight;
        assertTrue("raw " + rawTremor + " filtered " + filteredTremor,
                filteredTremor < rawTremor * 0.4f);
    }

    @Test
    public void aFastDeliberateStrokeIsNotDraggedBehindThePen() {
        List<float[]> fast = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            fast.add(new float[] {i * 40f, 0f, i * 8L});
        }
        List<float[]> filtered = run(new MotionFilter(0.9f, 0f), fast, Float.NaN);
        float lag = Math.abs(filtered.get(59)[0] - fast.get(59)[0]);
        assertTrue("lagged by " + lag, lag < 40f);
    }

    @Test
    public void zeroSmoothingLeavesTheStrokeEssentiallyAlone() {
        List<float[]> stroke = tremorStroke(2f);
        List<float[]> filtered = run(new MotionFilter(0f, 0f), stroke, Float.NaN);
        assertTrue(pathLength(filtered) > pathLength(stroke) * 0.9f);
    }

    @Test
    public void theSameInputAlwaysGivesTheSameOutput() {
        List<float[]> first = run(new MotionFilter(0.35f, 0.5f), tremorStroke(2f), 0.5f);
        List<float[]> second = run(new MotionFilter(0.35f, 0.5f), tremorStroke(2f), 0.5f);
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i)[0], second.get(i)[0], 0f);
            assertEquals(first.get(i)[2], second.get(i)[2], 0f);
        }
    }

    @Test
    public void noPressureIsEverInvented() {
        for (float[] sample : run(new MotionFilter(0.9f, 1f), tremorStroke(2f), Float.NaN)) {
            assertTrue(Float.isNaN(sample[2]));
        }
    }

    @Test
    public void expressionGivesADeadStrokeItsVariationBack() {
        Set<Float> flat = new HashSet<>();
        for (float[] sample : run(new MotionFilter(0.9f, 0f), tremorStroke(2f), 0.5f)) {
            flat.add(sample[2]);
        }
        Set<Float> alive = new HashSet<>();
        for (float[] sample : run(new MotionFilter(0.9f, 1f), tremorStroke(2f), 0.5f)) {
            alive.add(sample[2]);
            assertTrue(sample[2] >= 0f && sample[2] <= 1f);
        }
        assertEquals(1, flat.size());
        assertTrue(alive.size() > 1);
    }

    @Test
    public void expressionCannotPushPressureOutOfRange() {
        for (float[] sample : run(new MotionFilter(1f, 1f), tremorStroke(500f), 1f)) {
            assertTrue(sample[2] <= 1f);
        }
    }

    @Test
    public void resetStopsTheLastStrokeDraggingTheNext() {
        MotionFilter filter = new MotionFilter(1f, 0.5f);
        filter.filter(0, 0, Float.NaN, 0);
        filter.filter(1, 0, Float.NaN, 8);
        filter.reset();
        filter.filter(500, 500, Float.NaN, 16);
        assertEquals(500f, filter.x(), 0f);
        assertEquals(500f, filter.y(), 0f);
    }

    @Test
    public void aForkPreviewsWithoutMovingTheRealStroke() {
        MotionFilter filter = new MotionFilter(1f, 0.7f);
        filter.filter(20, 30, 0.5f, 0);
        MotionFilter fork = filter.fork();
        fork.filter(100, 80, 0.8f, 8);
        filter.filter(100, 80, 0.8f, 8);
        assertEquals(fork.x(), filter.x(), 0f);
        assertEquals(fork.y(), filter.y(), 0f);
        assertEquals(fork.pressure(), filter.pressure(), 0f);
    }

    @Test
    public void aStalledClockGivesNoInfinityOrNaN() {
        MotionFilter filter = new MotionFilter(0.8f, 0.5f);
        for (int i = 0; i < 20; i++) {
            filter.filter(i, 0, 0.4f, 5);
            assertTrue(!Float.isInfinite(filter.x()) && !Float.isNaN(filter.x()));
            assertTrue(!Float.isNaN(filter.pressure()));
        }
    }

    @Test
    public void doublingTheSampleRateDoesNotDoubleTheSmoothing() {
        assertEquals(sweep(60), sweep(120), 2f);
    }

    private static float sweep(int hz) {
        MotionFilter filter = new MotionFilter(0.8f, 0f);
        for (int index = 0; index <= hz; index++) {
            float seconds = index / (float) hz;
            filter.filter(seconds * 100f, 0, Float.NaN, (long) (seconds * 1000));
        }
        return filter.x();
    }
}
