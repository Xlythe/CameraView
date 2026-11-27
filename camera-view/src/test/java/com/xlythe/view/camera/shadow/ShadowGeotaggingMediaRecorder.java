package com.xlythe.view.camera.shadow;

import android.media.MediaRecorder;

import androidx.annotation.Nullable;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowMediaRecorder;

/**
 * Remembers where a recording was told it was made.
 *
 * <p>MediaRecorder hands the location straight to the native recorder, and Robolectric's shadow
 * keeps no record of it, so without this a recording's location cannot be seen at all.</p>
 */
@Implements(MediaRecorder.class)
public class ShadowGeotaggingMediaRecorder extends ShadowMediaRecorder {
    /** The latitude and longitude of the last call, or null when there has been none. */
    @Nullable private static float[] sLocation;

    @Implementation
    protected void setLocation(float latitude, float longitude) {
        sLocation = new float[] {latitude, longitude};
    }

    /** The latitude and longitude the last recording was given, or null for none. */
    @Nullable
    public static float[] getLocation() {
        return sLocation;
    }

    /**
     * Forgets the last location. A static outlives the test that set it, and a {@code @Resetter}
     * here is not called between tests, so a test that reads {@link #getLocation} calls this first.
     */
    public static void forgetLocation() {
        sLocation = null;
    }
}
