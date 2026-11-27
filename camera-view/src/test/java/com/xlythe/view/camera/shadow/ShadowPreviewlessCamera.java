package com.xlythe.view.camera.shadow;

import android.graphics.SurfaceTexture;
import android.hardware.Camera;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowCamera;

import java.io.IOException;

/**
 * A camera that opens but will not take a preview, as one does once another client has taken it
 * back: the open succeeds and the first thing done with the camera fails.
 */
@Implements(Camera.class)
public class ShadowPreviewlessCamera extends ShadowCamera {
    @Implementation
    protected void setPreviewTexture(SurfaceTexture surfaceTexture) throws IOException {
        throw new IOException("setPreviewTexture failed");
    }
}
