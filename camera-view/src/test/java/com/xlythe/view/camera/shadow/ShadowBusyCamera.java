package com.xlythe.view.camera.shadow;

import android.hardware.Camera;
import android.os.SystemClock;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowCamera;

import java.util.ArrayList;
import java.util.List;

/**
 * A camera another client holds for a number of opens, and then lets go.
 *
 * <p>Each refused open throws what the old API throws for a camera it cannot have, so the
 * backend sees exactly what a device would show it. Every open is recorded with the camera it
 * asked for and when, so a test can tell which tries were made and how far apart.</p>
 *
 * <p>Its state is static, as the camera service's is: call {@link #reset()} before each test.</p>
 */
@Implements(Camera.class)
public class ShadowBusyCamera extends ShadowCamera {
    /** How many more opens to refuse. */
    private static int sRefusals;
    /** The camera each open asked for, in order. */
    private static final List<Integer> sOpenedIds = new ArrayList<>();
    /** When each open was made, on the uptime clock, in the same order. */
    private static final List<Long> sOpenedAt = new ArrayList<>();

    /** Lets every open through and forgets the ones already made. */
    public static void reset() {
        sRefusals = 0;
        sOpenedIds.clear();
        sOpenedAt.clear();
    }

    /** Refuses the next {@code count} opens, as a camera held by another client does. */
    public static void refuseNextOpens(int count) {
        sRefusals = count;
    }

    /** How many opens have been made, refused or not. */
    public static int getOpenCount() {
        return sOpenedIds.size();
    }

    /** The camera each open asked for, in order. */
    public static List<Integer> getOpenedIds() {
        return new ArrayList<>(sOpenedIds);
    }

    /** When each open was made, on the uptime clock. */
    public static List<Long> getOpenedAt() {
        return new ArrayList<>(sOpenedAt);
    }

    @Implementation
    protected static Camera open(int cameraId) {
        sOpenedIds.add(cameraId);
        sOpenedAt.add(SystemClock.uptimeMillis());
        if (sRefusals > 0) {
            sRefusals--;
            throw new RuntimeException("Fail to connect to camera service");
        }
        return ShadowCamera.open(cameraId);
    }
}
