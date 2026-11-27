package com.xlythe.view.camera.shadow;

import android.media.MediaRecorder;

import androidx.annotation.Nullable;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowMediaRecorder;

/**
 * Remembers what a recording was told that Robolectric's shadow does not: where it was made,
 * whether it was given a microphone, and the rate a time-lapse captures at.
 *
 * <p>MediaRecorder hands the location and the capture rate straight to the native recorder, and
 * Robolectric's shadow keeps no record of either, so without this neither can be seen at all. The
 * audio source is kept per recorder by Robolectric, which a test that never sees the recorder
 * cannot read.</p>
 */
@Implements(MediaRecorder.class)
public class ShadowGeotaggingMediaRecorder extends ShadowMediaRecorder {
    /** The latitude and longitude of the last call, or null when there has been none. */
    @Nullable private static float[] sLocation;

    /** The last audio source any recorder was given, or null when none has been. */
    @Nullable private static Integer sAudioSource;

    /** The last capture rate any recorder was given, or null when none has been. */
    @Nullable private static Double sCaptureRate;

    @Implementation
    protected void setLocation(float latitude, float longitude) {
        sLocation = new float[] {latitude, longitude};
    }

    @Override
    @Implementation
    protected void setAudioSource(int audioSource) {
        super.setAudioSource(audioSource);
        sAudioSource = audioSource;
    }

    @Implementation
    protected void setCaptureRate(double fps) {
        sCaptureRate = fps;
    }

    /** The latitude and longitude the last recording was given, or null for none. */
    @Nullable
    public static float[] getLocation() {
        return sLocation;
    }

    /** The audio source the last recording was given, or null if none was. */
    @Nullable
    public static Integer getLastAudioSource() {
        return sAudioSource;
    }

    /** The capture rate the last recording was given, or null if none was. */
    @Nullable
    public static Double getLastCaptureRate() {
        return sCaptureRate;
    }

    /**
     * Forgets the last location. A static outlives the test that set it, and a {@code @Resetter}
     * here is not called between tests, so a test that reads {@link #getLocation} calls this first.
     */
    public static void forgetLocation() {
        sLocation = null;
    }

    /** Forgets everything, for the same reason as {@link #forgetLocation}: call it in a setUp. */
    public static void forgetEverything() {
        sLocation = null;
        sAudioSource = null;
        sCaptureRate = null;
    }
}
