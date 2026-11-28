package com.xlythe.view.camera.shadow;

import android.hardware.Camera;
import android.os.Handler;
import android.os.Looper;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.annotation.Resetter;
import org.robolectric.shadows.ShadowCamera;

import java.util.ArrayList;
import java.util.List;

/**
 * A camera whose pictures are JPEGs that arrive later, as a real camera's do.
 *
 * <p>Robolectric's own shadow answers {@code takePicture} before returning, with an empty array.
 * Neither is what a driver does: a picture arrives on a later turn of the looper, and it is a JPEG
 * that Exif can be written into. A burst, which asks for the next picture once the last arrives,
 * needs both to be exercised the way it runs.</p>
 */
@Implements(Camera.class)
@SuppressWarnings("deprecation")
public class ShadowJpegCamera extends ShadowCamera {
    /** The smallest JPEG that Exif can be written into. */
    private static final byte[] EMPTY_JPEG = {
            (byte) 0xFF, (byte) 0xD8, // SOI
            (byte) 0xFF, (byte) 0xD9, // EOI
    };

    private static final List<Runnable> sHeld = new ArrayList<>();
    private static int sPicturesTaken;
    private static boolean sHolding;
    private static Camera sLastCamera;

    @RealObject private Camera realCamera;

    /** The camera most recently configured, which is the one a module has open. */
    public static Camera getLastCamera() {
        return sLastCamera;
    }

    /** How many pictures were asked for since the last reset. */
    public static int getPicturesTaken() {
        return sPicturesTaken;
    }

    /** Keeps pictures back until {@link #releasePictures}, for a test that acts between them. */
    public static void holdPictures() {
        sHolding = true;
    }

    /** Delivers the pictures held back, and stops holding them. */
    public static void releasePictures() {
        sHolding = false;
        List<Runnable> held = new ArrayList<>(sHeld);
        sHeld.clear();
        for (Runnable delivery : held) {
            delivery.run();
        }
    }

    @Resetter
    public static void reset() {
        sHeld.clear();
        sPicturesTaken = 0;
        sHolding = false;
        sLastCamera = null;
    }

    @Implementation
    @Override
    protected void setParameters(Camera.Parameters parameters) {
        sLastCamera = realCamera;
        super.setParameters(parameters);
    }

    @Implementation
    @Override
    protected void takePicture(Camera.ShutterCallback shutter, Camera.PictureCallback raw,
            Camera.PictureCallback jpeg) {
        sPicturesTaken++;
        Runnable delivery = () -> {
            if (shutter != null) {
                shutter.onShutter();
            }
            if (jpeg != null) {
                jpeg.onPictureTaken(EMPTY_JPEG.clone(), realCamera);
            }
        };
        if (sHolding) {
            sHeld.add(delivery);
        } else {
            new Handler(Looper.getMainLooper()).post(delivery);
        }
    }
}
