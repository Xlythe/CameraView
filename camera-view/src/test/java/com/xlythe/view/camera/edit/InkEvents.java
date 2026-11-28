package com.xlythe.view.camera.edit;

import android.view.InputDevice;
import android.view.MotionEvent;

/** Touch, pen and mouse events for the ink tests. */
final class InkEvents {
    private InkEvents() {}

    /** One pointer: where, what, how hard, and its lean. */
    static final class Pointer {
        final int id;
        final int tool;
        final float x;
        final float y;
        final float pressure;
        final float tilt;
        final float orientation;

        Pointer(int id, int tool, float x, float y, float pressure, float tilt,
                float orientation) {
            this.id = id;
            this.tool = tool;
            this.x = x;
            this.y = y;
            this.pressure = pressure;
            this.tilt = tilt;
            this.orientation = orientation;
        }
    }

    static Pointer finger(int id, float x, float y) {
        return new Pointer(id, MotionEvent.TOOL_TYPE_FINGER, x, y, 1f, 0f, 0f);
    }

    static Pointer pen(float x, float y, float pressure) {
        return new Pointer(0, MotionEvent.TOOL_TYPE_STYLUS, x, y, pressure, 0f, 0f);
    }

    static Pointer leaningPen(float x, float y, float pressure, float tilt, float orientation) {
        return new Pointer(0, MotionEvent.TOOL_TYPE_STYLUS, x, y, pressure, tilt, orientation);
    }

    static Pointer eraser(float x, float y) {
        return new Pointer(0, MotionEvent.TOOL_TYPE_ERASER, x, y, 0.5f, 0f, 0f);
    }

    static MotionEvent event(int action, long time, Pointer... pointers) {
        return event(action, time, 0, 0, pointers);
    }

    /** An event with a button state and flags, such as Android 13's palm flag. */
    static MotionEvent event(int action, long time, int buttons, int flags, Pointer... pointers) {
        MotionEvent.PointerProperties[] properties =
                new MotionEvent.PointerProperties[pointers.length];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[pointers.length];
        for (int i = 0; i < pointers.length; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = pointers[i].id;
            properties[i].toolType = pointers[i].tool;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = pointers[i].x;
            coords[i].y = pointers[i].y;
            coords[i].pressure = pointers[i].pressure;
            coords[i].setAxisValue(MotionEvent.AXIS_TILT, pointers[i].tilt);
            coords[i].orientation = pointers[i].orientation;
        }
        int source = pointers[0].tool == MotionEvent.TOOL_TYPE_FINGER
                ? InputDevice.SOURCE_TOUCHSCREEN : InputDevice.SOURCE_STYLUS;
        return MotionEvent.obtain(0, time, action, pointers.length, properties, coords, 0,
                buttons, 1f, 1f, 0, 0, source, flags);
    }

    /** A move whose samples before the last are history, as a digitiser's coalesced batch. */
    static MotionEvent move(long time, Pointer... samples) {
        MotionEvent event = event(MotionEvent.ACTION_MOVE, time - samples.length + 1, samples[0]);
        for (int i = 1; i < samples.length; i++) {
            MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
            coords.x = samples[i].x;
            coords.y = samples[i].y;
            coords.pressure = samples[i].pressure;
            coords.setAxisValue(MotionEvent.AXIS_TILT, samples[i].tilt);
            coords.orientation = samples[i].orientation;
            event.addBatch(time - samples.length + 1 + i,
                    new MotionEvent.PointerCoords[] {coords}, 0);
        }
        return event;
    }

    static int pointerAction(int action, int index) {
        return action | (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
    }
}
