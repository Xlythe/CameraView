package com.xlythe.view.camera.edit;

import static com.xlythe.view.camera.edit.InkEvents.event;
import static com.xlythe.view.camera.edit.InkEvents.eraser;
import static com.xlythe.view.camera.edit.InkEvents.finger;
import static com.xlythe.view.camera.edit.InkEvents.leaningPen;
import static com.xlythe.view.camera.edit.InkEvents.move;
import static com.xlythe.view.camera.edit.InkEvents.pen;
import static com.xlythe.view.camera.edit.InkEvents.pointerAction;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/** Events in, strokes out: the lifecycle, the axes, and the ways a stroke is taken back. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public class StylusInputTest {
    private final Recorder recorder = new Recorder();
    private final StylusInput input = new StylusInput(recorder);

    @Test
    public void aPenStrokeDeliversEveryCoalescedSample() {
        assertTrue(input.onTouchEvent(event(MotionEvent.ACTION_DOWN, 0, pen(1, 1, 0.5f)), true));
        assertTrue(input.isStroking());
        input.onTouchEvent(move(10, pen(2, 2, 0.6f), pen(3, 3, 0.7f), pen(4, 4, 0.8f)), true);
        input.onTouchEvent(event(MotionEvent.ACTION_UP, 11, pen(5, 5, 0.8f)), true);
        assertFalse(input.isStroking());
        assertEquals("start, three samples, end", "S M M M E", recorder.log());
        assertEquals(3f, recorder.xs.get(2), 0f);
        assertEquals(0.7f, recorder.pressures.get(2), 1e-6f);
        assertEquals(StylusInput.PRESENT, input.pressure());
    }

    @Test
    public void pressureAboveOneIsScaledByTheHighestSeen() {
        assertEquals(0.5f, input.normalisedPressure(0.5f), 0f);
        assertEquals(1f, input.normalisedPressure(2f), 0f);
        assertEquals(0.5f, input.normalisedPressure(1f), 0f);
        assertTrue(Float.isNaN(input.normalisedPressure(Float.NaN)));
    }

    @Test
    public void aFingerOrMouseHasNoPressureOrTilt() {
        input.onTouchEvent(event(MotionEvent.ACTION_DOWN, 0, finger(0, 1, 1)), true);
        assertTrue(Float.isNaN(recorder.pressures.get(0)));
        assertTrue(Float.isNaN(recorder.tiltXs.get(0)));
        assertEquals(StylusInput.UNKNOWN, input.pressure());
    }

    @Test
    public void aLeaningPenReportsItsTilt() {
        // Leaned 45 degrees with Android's orientation a quarter turn round: as Muse converts
        // it, all of the lean is along x.
        float quarter = (float) (Math.PI / 2);
        float leaned = (float) Math.toRadians(45);
        input.onTouchEvent(event(MotionEvent.ACTION_DOWN, 0,
                leaningPen(1, 1, 0.5f, leaned, quarter)), true);
        assertEquals(StylusInput.PRESENT, input.tilt());
        assertEquals(-45f, recorder.tiltXs.get(0), 1e-3f);
        assertEquals(-45f, StylusInput.tiltX(leaned, quarter), 1e-3f);
        assertEquals(0f, StylusInput.tiltY(leaned, quarter), 1e-3f);
        // Orientation 0: all of it along y.
        assertEquals(0f, StylusInput.tiltX(leaned, 0f), 1e-3f);
        assertEquals(45f, StylusInput.tiltY(leaned, 0f), 1e-3f);
    }

    @Test
    public void aPenWithNoTiltSensorReportsNone() {
        input.onTouchEvent(event(MotionEvent.ACTION_DOWN, 0, pen(1, 1, 0.5f)), true);
        assertTrue(Float.isNaN(recorder.tiltXs.get(0)));
        assertEquals(StylusInput.UNKNOWN, input.tilt());
    }

    @Test
    public void theEraserEndAndBarrelButtonsAreReported() {
        input.onTouchEvent(event(MotionEvent.ACTION_DOWN, 0, eraser(1, 1)), true);
        assertEquals(StylusInput.TOOL_ERASER, recorder.tools.get(0).intValue());
        input.onTouchEvent(event(MotionEvent.ACTION_UP, 1, eraser(1, 1)), true);
        input.onTouchEvent(event(MotionEvent.ACTION_DOWN, 2,
                MotionEvent.BUTTON_STYLUS_PRIMARY, 0, pen(1, 1, 0.5f)), true);
        assertTrue(recorder.primary);
        assertEquals(StylusInput.PRESENT, input.buttons());
    }

    @Test
    public void fingersPassThroughWhenTheyDoNotDraw() {
        assertFalse(input.onTouchEvent(event(MotionEvent.ACTION_DOWN, 0, finger(0, 1, 1)),
                false));
        assertFalse(input.isStroking());
        assertTrue(input.onTouchEvent(event(MotionEvent.ACTION_DOWN, 0, pen(1, 1, 0.5f)),
                false));
    }

    @Test
    public void aPenLandingWhileAFingerDrawsVoidsThePalm() {
        input.onTouchEvent(event(MotionEvent.ACTION_DOWN, 0, finger(0, 1, 1)), true);
        input.onTouchEvent(event(pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), 5,
                finger(0, 2, 2), new InkEvents.Pointer(1, MotionEvent.TOOL_TYPE_STYLUS, 9, 9,
                        0.5f, 0f, 0f)), true);
        assertEquals("S C" + StylusInput.CANCEL_PALM + " S", recorder.log());
        assertEquals(9f, recorder.xs.get(1), 0f);
    }

    @Test
    public void aSecondFingerTakesTheStrokeBack() {
        input.onTouchEvent(event(MotionEvent.ACTION_DOWN, 0, finger(0, 1, 1)), true);
        input.onTouchEvent(event(pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), 5,
                finger(0, 2, 2), finger(1, 50, 50)), true);
        assertFalse(input.isStroking());
        assertEquals("S C" + StylusInput.CANCEL_SECOND_FINGER, recorder.log());
    }

    @Test
    @Config(sdk = 33)
    public void aPalmTheSystemCancelsIsTakenBack() {
        input.onTouchEvent(event(MotionEvent.ACTION_DOWN, 0, finger(0, 1, 1)), true);
        input.onTouchEvent(event(MotionEvent.ACTION_CANCEL, 5, 0, MotionEvent.FLAG_CANCELED,
                finger(0, 2, 2)), true);
        assertEquals("S C" + StylusInput.CANCEL_PALM, recorder.log());

        recorder.events.clear();
        input.onTouchEvent(event(MotionEvent.ACTION_DOWN, 10, finger(0, 1, 1)), true);
        input.onTouchEvent(event(MotionEvent.ACTION_UP, 15, 0, MotionEvent.FLAG_CANCELED,
                finger(0, 2, 2)), true);
        assertEquals("S C" + StylusInput.CANCEL_PALM, recorder.log());

        recorder.events.clear();
        input.onTouchEvent(event(MotionEvent.ACTION_DOWN, 20, finger(0, 1, 1)), true);
        input.onTouchEvent(event(MotionEvent.ACTION_CANCEL, 25, finger(0, 2, 2)), true);
        assertEquals("S C" + StylusInput.CANCEL_SYSTEM, recorder.log());
    }

    @Test
    public void hoveringIsReportedAndEndsWhenThePenTouches() {
        assertTrue(input.onHoverEvent(event(MotionEvent.ACTION_HOVER_MOVE, 0, pen(4, 5, 0f))));
        assertEquals(StylusInput.PRESENT, input.hover());
        assertEquals(4f, recorder.hoverX, 0f);
        input.onHoverEvent(event(MotionEvent.ACTION_HOVER_EXIT, 1, pen(4, 5, 0f)));
        assertTrue(Float.isNaN(recorder.hoverX));
        assertFalse(input.onHoverEvent(event(MotionEvent.ACTION_HOVER_MOVE, 2,
                finger(0, 4, 5))));
    }

    @Test
    public void eventsOutOfOrderAreIgnored() {
        assertFalse(input.onTouchEvent(event(MotionEvent.ACTION_MOVE, 0, pen(1, 1, 0.5f)), true));
        assertTrue(input.onTouchEvent(event(MotionEvent.ACTION_UP, 0, pen(1, 1, 0.5f)), true));
        assertEquals("", recorder.log());
        input.cancel(StylusInput.CANCEL_SYSTEM);
        assertEquals("", recorder.log());
    }

    @Test
    public void predictionIsReportedOnceKnown() {
        input.reportPrediction(true);
        assertEquals(StylusInput.PRESENT, input.prediction());
        int changes = recorder.capabilityChanges;
        input.reportPrediction(true);
        assertEquals("the same news is no news", changes, recorder.capabilityChanges);
    }

    /** Records what the input says. */
    private static final class Recorder implements StylusInput.Listener {
        final List<String> events = new ArrayList<>();
        final List<Float> xs = new ArrayList<>();
        final List<Float> pressures = new ArrayList<>();
        final List<Float> tiltXs = new ArrayList<>();
        final List<Integer> tools = new ArrayList<>();
        boolean primary;
        float hoverX = Float.NaN;
        int capabilityChanges;

        @Override
        public void onStrokeStart(@NonNull StylusInput.Sample sample) {
            events.add("S");
            record(sample);
        }

        @Override
        public void onStrokeSample(@NonNull StylusInput.Sample sample) {
            events.add("M");
            record(sample);
        }

        @Override
        public void onStrokeEnd(@NonNull StylusInput.Sample sample) {
            events.add("E");
        }

        @Override
        public void onStrokeCancel(int reason) {
            events.add("C" + reason);
        }

        @Override
        public void onHover(@Nullable StylusInput.Sample sample) {
            hoverX = sample == null ? Float.NaN : sample.x;
        }

        @Override
        public void onCapabilitiesChanged() {
            capabilityChanges++;
        }

        private void record(StylusInput.Sample sample) {
            xs.add(sample.x);
            pressures.add(sample.pressure);
            tiltXs.add(sample.tiltX);
            tools.add(sample.tool);
            primary |= sample.primaryButton;
        }

        String log() {
            return String.join(" ", events);
        }
    }
}
