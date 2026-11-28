package com.xlythe.view.camera.shadow;

import android.hardware.Camera;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowCamera;

/**
 * A camera that delivers a picture only when the test says it has been taken, and that accepts a
 * second picture asked for meanwhile, as the Android 4.0 and 5.1 emulators' cameras do. Like
 * android.hardware.Camera it keeps one picture callback, so the second call replaces the first's.
 *
 * <p>Its state is static: call {@link #reset()} before each test.</p>
 */
@Implements(Camera.class)
public class ShadowSlowPictureCamera extends ShadowCamera {
    /** The camera being shadowed, handed back to the picture callback. */
    @RealObject private Camera mRealCamera;

    /** How many times a picture has been asked of the camera. */
    private static int sRequests;
    /** The callback the next picture goes to, or null when none is being taken. */
    private static Camera.PictureCallback sJpeg;
    /** The camera that callback belongs to. */
    private static Camera sCamera;

    /** Forgets every picture asked for. */
    public static void reset() {
        sRequests = 0;
        sJpeg = null;
        sCamera = null;
    }

    /** How many times a picture has been asked of the camera, refused by the module or not. */
    public static int getRequestCount() {
        return sRequests;
    }

    /** Hands the picture being taken to the one callback the camera kept. */
    public static void deliverPicture(byte[] jpeg) {
        Camera.PictureCallback callback = sJpeg;
        sJpeg = null;
        if (callback != null) {
            callback.onPictureTaken(jpeg, sCamera);
        }
    }

    @Implementation
    @Override
    protected void takePicture(Camera.ShutterCallback shutter, Camera.PictureCallback raw,
            Camera.PictureCallback jpeg) {
        sRequests++;
        if (shutter != null) {
            shutter.onShutter();
        }
        sJpeg = jpeg;
        sCamera = mRealCamera;
    }
}
