package com.xlythe.view.camera.shadow;

import android.hardware.Camera;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowCamera;

/**
 * A camera that refuses to take a picture, as the old API does by throwing for a picture asked
 * for while another is being taken or while the preview is stopped.
 */
@Implements(Camera.class)
public class ShadowRefusingCamera extends ShadowCamera {
    @Implementation
    @Override
    protected void takePicture(Camera.ShutterCallback shutter, Camera.PictureCallback raw,
            Camera.PictureCallback jpeg) {
        throw new RuntimeException("takePicture failed");
    }
}
