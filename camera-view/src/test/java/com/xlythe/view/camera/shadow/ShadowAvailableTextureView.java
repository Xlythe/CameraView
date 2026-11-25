package com.xlythe.view.camera.shadow;

import android.graphics.SurfaceTexture;
import android.view.TextureView;

import androidx.annotation.Nullable;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowView;

/**
 * Gives a {@link TextureView} the SurfaceTexture a device would give it.
 *
 * <p>A real TextureView produces one only once it has a hardware layer, which never happens off a
 * device, so {@code isAvailable()} stays false and {@code onSurfaceTextureAvailable} is never
 * called. Robolectric ships no shadow for TextureView, so a view that waits for its surface -
 * which is every camera preview - waits forever, and none of the code that runs once the preview
 * has somewhere to draw can be reached at all.</p>
 *
 * <p>The listener is told on the next main-looper pass rather than inside
 * {@code setSurfaceTextureListener}, because a device delivers it asynchronously and a caller that
 * registers its listener from a constructor would otherwise be re-entered before it finished
 * building itself.</p>
 */
@Implements(TextureView.class)
public class ShadowAvailableTextureView extends ShadowView {
    /** Big enough that nothing divides by its dimensions and gets zero. */
    private static final int DEFAULT_WIDTH = 1080;

    private static final int DEFAULT_HEIGHT = 1920;

    @RealObject private TextureView realTextureView;

    @Nullable private SurfaceTexture mSurfaceTexture;

    @Implementation
    protected boolean isAvailable() {
        return surfaceTexture() != null;
    }

    @Implementation
    @Nullable
    protected SurfaceTexture getSurfaceTexture() {
        return surfaceTexture();
    }

    @Implementation
    protected void setSurfaceTextureListener(
            @Nullable TextureView.SurfaceTextureListener listener) {
        if (listener == null) {
            return;
        }
        SurfaceTexture texture = surfaceTexture();
        realTextureView.post(() -> listener.onSurfaceTextureAvailable(texture, width(), height()));
    }

    /** Reports the size the view was laid out at, or a plausible one if it never was. */
    private int width() {
        int width = realTextureView.getWidth();
        return width > 0 ? width : DEFAULT_WIDTH;
    }

    private int height() {
        int height = realTextureView.getHeight();
        return height > 0 ? height : DEFAULT_HEIGHT;
    }

    @Nullable
    private SurfaceTexture surfaceTexture() {
        if (mSurfaceTexture == null) {
            mSurfaceTexture = new SurfaceTexture(0);
        }
        return mSurfaceTexture;
    }
}
