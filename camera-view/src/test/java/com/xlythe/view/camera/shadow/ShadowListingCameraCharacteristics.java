package com.xlythe.view.camera.shadow;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;
import org.robolectric.shadows.ShadowCameraCharacteristics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Answers which request keys a camera lists, which Robolectric cannot.
 *
 * <p>The real list is decoded from native metadata tags, so off a device the call throws an
 * AssertionError rather than returning. Registered for every test in robolectric.properties.
 * A camera lists no keys unless a test names some, and since Robolectric runs no resetter for a
 * shadow registered this way, a test that names them calls {@link #reset} itself.</p>
 */
@Implements(CameraCharacteristics.class)
public class ShadowListingCameraCharacteristics extends ShadowCameraCharacteristics {
    /** The request keys every camera lists. */
    private static final List<CaptureRequest.Key<?>> sRequestKeys = new ArrayList<>();

    /** Has every camera list these request keys. */
    public static void setAvailableCaptureRequestKeys(CaptureRequest.Key<?>... keys) {
        sRequestKeys.clear();
        sRequestKeys.addAll(Arrays.asList(keys));
    }

    @Resetter
    public static void reset() {
        sRequestKeys.clear();
    }

    @Implementation
    protected List<CaptureRequest.Key<?>> getAvailableCaptureRequestKeys() {
        return Collections.unmodifiableList(new ArrayList<>(sRequestKeys));
    }
}
