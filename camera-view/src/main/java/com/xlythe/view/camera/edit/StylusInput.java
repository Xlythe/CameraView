package com.xlythe.view.camera.edit;

import android.os.Build;
import android.view.MotionEvent;

import androidx.annotation.IntDef;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Turns touch, pen and mouse events into one stroke at a time, as Muse's stylus engine does:
 * every coalesced sample at the digitiser's full rate, pressure scaled into 0 to 1 against the
 * highest the device has reported, tilt from the pen's lean and direction, the eraser end and
 * barrel buttons, and what the device has shown it can report.
 *
 * <p>It owns the stroke's lifecycle, including the ways a stroke turns out not to have been
 * meant: a palm the system cancels after the fact, a pen landing while a finger is drawing, and
 * a second finger, which means a pinch. Each ends the stroke as cancelled, for the caller to
 * take back without leaving anything to undo.</p>
 */
final class StylusInput {
    /** What made a sample. */
    @IntDef({TOOL_UNKNOWN, TOOL_FINGER, TOOL_STYLUS, TOOL_ERASER, TOOL_MOUSE})
    @Retention(RetentionPolicy.SOURCE)
    @interface Tool {}

    static final int TOOL_UNKNOWN = 0;
    static final int TOOL_FINGER = 1;
    static final int TOOL_STYLUS = 2;
    /** A pen's eraser end. */
    static final int TOOL_ERASER = 3;
    static final int TOOL_MOUSE = 4;

    /** Whether the device has shown it reports something. */
    @IntDef({UNKNOWN, PRESENT, ABSENT})
    @Retention(RetentionPolicy.SOURCE)
    @interface Capability {}

    /** Nothing has shown it either way yet: offer no control for it. */
    static final int UNKNOWN = 0;
    /** It has carried real values. */
    static final int PRESENT = 1;
    /** The device cannot report it. */
    static final int ABSENT = 2;

    /** Why a stroke was taken back. */
    @IntDef({CANCEL_SYSTEM, CANCEL_PALM, CANCEL_SECOND_FINGER})
    @Retention(RetentionPolicy.SOURCE)
    @interface CancelReason {}

    /** The system took the touch away, such as for a gesture of its own. */
    static final int CANCEL_SYSTEM = 0;
    /** It was a palm: the system said so, or a pen landed while it was drawing. */
    static final int CANCEL_PALM = 1;
    /** A second finger came down, which is a pinch, not a stroke. */
    static final int CANCEL_SECOND_FINGER = 2;

    /** One sample, reused for every event: read it before the next. */
    static final class Sample {
        /** Where, in the view's pixels. */
        float x;
        float y;
        /** How hard, 0 to 1, or NaN where the tool cannot tell. */
        float pressure;
        /** How far the pen leans towards +x and +y, in degrees, or NaN. */
        float tiltX;
        float tiltY;
        /** When, in milliseconds of uptime. */
        long time;
        /** What made it. */
        @Tool int tool;
        /** Whether the pen's primary barrel button is held. */
        boolean primaryButton;
        /** Whether its secondary barrel button is held. */
        boolean secondaryButton;
    }

    /** Hears strokes. */
    interface Listener {
        void onStrokeStart(@NonNull Sample sample);

        void onStrokeSample(@NonNull Sample sample);

        void onStrokeEnd(@NonNull Sample sample);

        void onStrokeCancel(@CancelReason int reason);

        /** A pen or mouse over the surface without touching it, or null once it leaves. */
        void onHover(@Nullable Sample sample);

        void onCapabilitiesChanged();
    }

    /** Who hears. */
    @NonNull private final Listener mListener;
    /** The sample handed to the listener. */
    private final Sample mSample = new Sample();
    /** The pointer drawing, or -1 while none is. */
    private int mActivePointer = -1;
    /** Whether the stroke is a finger's, which a pen may take over. */
    private boolean mActiveIsFinger;
    /** The highest raw pressure seen, which scales the rest into 0 to 1. */
    private float mMaxPressure = 1f;

    /** What the device has shown it reports. */
    @Capability private int mPressure = UNKNOWN;
    @Capability private int mTilt = UNKNOWN;
    @Capability private int mHover = UNKNOWN;
    @Capability private int mButtons = UNKNOWN;
    @Capability private int mPrediction = UNKNOWN;

    StylusInput(@NonNull Listener listener) {
        mListener = listener;
    }

    /** Whether a stroke is being drawn. */
    boolean isStroking() {
        return mActivePointer >= 0;
    }

    @Capability
    int pressure() {
        return mPressure;
    }

    @Capability
    int tilt() {
        return mTilt;
    }

    @Capability
    int hover() {
        return mHover;
    }

    @Capability
    int buttons() {
        return mButtons;
    }

    @Capability
    int prediction() {
        return mPrediction;
    }

    /** Says whether predicted samples are available, once it is known. */
    void reportPrediction(boolean available) {
        set(available ? PRESENT : ABSENT, Field.PREDICTION);
    }

    /**
     * Handles a touch event.
     *
     * @param fingers whether fingers draw; when they do not, their events are not handled
     * @return whether the event was handled
     */
    @MainThread
    boolean onTouchEvent(@NonNull MotionEvent event, boolean fingers) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int index = event.getActionIndex();
                int tool = toolOf(event, index);
                if (isStroking()) {
                    if (mActiveIsFinger && (tool == TOOL_STYLUS || tool == TOOL_ERASER)) {
                        // A pen landing while a finger draws: the finger was a palm.
                        cancel(CANCEL_PALM);
                    } else if (mActiveIsFinger && tool == TOOL_FINGER) {
                        cancel(CANCEL_SECOND_FINGER);
                        return true;
                    } else {
                        return true;
                    }
                }
                if (tool == TOOL_FINGER && !fingers) {
                    return false;
                }
                mActivePointer = event.getPointerId(index);
                mActiveIsFinger = tool == TOOL_FINGER;
                fill(event, index, -1);
                mListener.onHover(null);
                mListener.onStrokeStart(mSample);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!isStroking()) {
                    return false;
                }
                int index = event.findPointerIndex(mActivePointer);
                if (index < 0) {
                    return true;
                }
                // The coalesced samples first, oldest first: the digitiser's rate, not the
                // screen's.
                for (int h = 0; h < event.getHistorySize(); h++) {
                    fill(event, index, h);
                    mListener.onStrokeSample(mSample);
                }
                fill(event, index, -1);
                mListener.onStrokeSample(mSample);
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP: {
                if (!isStroking()) {
                    return event.getActionMasked() == MotionEvent.ACTION_UP;
                }
                int index = event.getActionIndex();
                if (event.getPointerId(index) != mActivePointer) {
                    return true;
                }
                if (isCanceled(event)) {
                    // Android 13's verdict that the pointer lifting was a palm all along.
                    cancel(CANCEL_PALM);
                    return true;
                }
                fill(event, index, -1);
                mActivePointer = -1;
                mListener.onStrokeEnd(mSample);
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                if (isStroking()) {
                    cancel(isCanceled(event) ? CANCEL_PALM : CANCEL_SYSTEM);
                }
                return true;
            }
            default:
                return false;
        }
    }

    /** Handles a hover event from a pen or mouse. */
    @MainThread
    boolean onHoverEvent(@NonNull MotionEvent event) {
        if (isStroking()) {
            return false;
        }
        int tool = toolOf(event, 0);
        if (tool == TOOL_FINGER) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                set(PRESENT, Field.HOVER);
                fill(event, 0, -1);
                mListener.onHover(mSample);
                return true;
            case MotionEvent.ACTION_HOVER_EXIT:
                mListener.onHover(null);
                return true;
            default:
                return false;
        }
    }

    /** Ends the stroke as one that was never meant. */
    void cancel(@CancelReason int reason) {
        if (!isStroking()) {
            return;
        }
        mActivePointer = -1;
        mListener.onStrokeCancel(reason);
    }

    /** Whether Android 13 or later marked the event as a palm. */
    private static boolean isCanceled(@NonNull MotionEvent event) {
        return Build.VERSION.SDK_INT >= 33 && (event.getFlags() & MotionEvent.FLAG_CANCELED) != 0;
    }

    /**
     * Reads one sample of a pointer into {@link #mSample}, from its history or, for -1, the
     * event's own values.
     */
    private void fill(@NonNull MotionEvent event, int index, int history) {
        Sample sample = mSample;
        boolean current = history < 0;
        sample.x = current ? event.getX(index) : event.getHistoricalX(index, history);
        sample.y = current ? event.getY(index) : event.getHistoricalY(index, history);
        sample.time = current ? event.getEventTime() : event.getHistoricalEventTime(history);
        sample.tool = toolOf(event, index);
        int buttons = event.getButtonState();
        sample.primaryButton = (buttons & buttonPrimary()) != 0;
        sample.secondaryButton = (buttons & buttonSecondary()) != 0;
        if (sample.primaryButton || sample.secondaryButton) {
            set(PRESENT, Field.BUTTONS);
        }
        boolean pen = sample.tool == TOOL_STYLUS || sample.tool == TOOL_ERASER;
        if (pen) {
            float raw = current ? event.getPressure(index)
                    : event.getHistoricalPressure(index, history);
            sample.pressure = normalisedPressure(raw);
            set(PRESENT, Field.PRESSURE);
            float tilt = current ? event.getAxisValue(MotionEvent.AXIS_TILT, index)
                    : event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, index, history);
            float orientation = current ? event.getAxisValue(MotionEvent.AXIS_ORIENTATION, index)
                    : event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, index, history);
            if (tilt == 0f) {
                // A pen with no tilt sensor reports a constant 0, which says nothing.
                sample.tiltX = Float.NaN;
                sample.tiltY = Float.NaN;
            } else {
                sample.tiltX = tiltX(tilt, orientation);
                sample.tiltY = tiltY(tilt, orientation);
                set(PRESENT, Field.TILT);
            }
        } else {
            // A finger's or a mouse's pressure is contact area or a constant, not how hard.
            sample.pressure = Float.NaN;
            sample.tiltX = Float.NaN;
            sample.tiltY = Float.NaN;
        }
    }

    /** Raw pressure, which some digitisers report above 1, scaled into 0 to 1. */
    @VisibleForTesting
    float normalisedPressure(float raw) {
        if (Float.isNaN(raw)) {
            return Float.NaN;
        }
        if (raw > mMaxPressure) {
            mMaxPressure = raw;
        }
        return Brush.clamp(raw / mMaxPressure, 0f, 1f);
    }

    /** A predicted raw pressure scaled as the real ones are, without counting it as seen. */
    float scaledPressure(float raw) {
        return Float.isNaN(raw) ? Float.NaN : Brush.clamp(raw / mMaxPressure, 0f, 1f);
    }

    /**
     * How far a pen leans towards +x, in degrees, from Android's tilt (radians from upright) and
     * orientation (radians clockwise from pointing up the screen).
     */
    @VisibleForTesting
    static float tiltX(float tilt, float orientation) {
        // The direction the pen leans, clockwise from +x.
        double azimuth = orientation + Math.PI / 2;
        return (float) Math.toDegrees(Math.atan(Math.cos(azimuth) * Math.tan(tilt)));
    }

    /** How far a pen leans towards +y, in degrees, as {@link #tiltX} describes. */
    @VisibleForTesting
    static float tiltY(float tilt, float orientation) {
        double azimuth = orientation + Math.PI / 2;
        return (float) Math.toDegrees(Math.atan(Math.sin(azimuth) * Math.tan(tilt)));
    }

    @Tool
    static int toolOf(@NonNull MotionEvent event, int index) {
        switch (event.getToolType(index)) {
            case MotionEvent.TOOL_TYPE_STYLUS:
                return TOOL_STYLUS;
            case MotionEvent.TOOL_TYPE_ERASER:
                return TOOL_ERASER;
            case MotionEvent.TOOL_TYPE_FINGER:
                return TOOL_FINGER;
            case MotionEvent.TOOL_TYPE_MOUSE:
                return TOOL_MOUSE;
            default:
                return TOOL_UNKNOWN;
        }
    }

    /** The primary barrel button, as the release names it. */
    private static int buttonPrimary() {
        return Build.VERSION.SDK_INT >= 23 ? MotionEvent.BUTTON_STYLUS_PRIMARY
                : MotionEvent.BUTTON_SECONDARY;
    }

    /** The secondary barrel button, as the release names it. */
    private static int buttonSecondary() {
        return Build.VERSION.SDK_INT >= 23 ? MotionEvent.BUTTON_STYLUS_SECONDARY
                : MotionEvent.BUTTON_TERTIARY;
    }

    /** Which capability a change is to. */
    private enum Field { PRESSURE, TILT, HOVER, BUTTONS, PREDICTION }

    /** Records what a capability has shown, telling the listener if that changed anything. */
    private void set(@Capability int value, @NonNull Field field) {
        int before;
        switch (field) {
            case PRESSURE:
                before = mPressure;
                mPressure = value;
                break;
            case TILT:
                before = mTilt;
                mTilt = value;
                break;
            case HOVER:
                before = mHover;
                mHover = value;
                break;
            case BUTTONS:
                before = mButtons;
                mButtons = value;
                break;
            default:
                before = mPrediction;
                mPrediction = value;
                break;
        }
        if (before != value) {
            mListener.onCapabilitiesChanged();
        }
    }
}
