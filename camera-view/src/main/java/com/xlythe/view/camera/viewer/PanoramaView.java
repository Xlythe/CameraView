package com.xlythe.view.camera.viewer;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Shows a panorama as a strip to pan along. It fills the view from top to bottom, and is dragged
 * or flung across and pinched to zoom, with a double tap to zoom in and back out.
 *
 * <p>A panorama whose photo sphere (GPano) metadata says it goes all the way round has no ends:
 * panning past its right edge carries straight on into its left. It opens looking the way its
 * metadata says to, or at its middle. Any other picture is shown the same way, with ends.</p>
 *
 * <p>The panorama is decoded in tiles, and holds no more memory than a
 * {@link LargeImageView} the same size: at most five times the view's own pixel count in decoded
 * pixels, however long the panorama is.</p>
 */
public class PanoramaView extends LargeImageView {
    /** Where the panorama sits on the sphere, or null if it does not say. */
    @Nullable
    private GPano mGPano;

    public PanoramaView(@NonNull Context context) {
        this(context, null);
    }

    public PanoramaView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PanoramaView(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @MainThread
    @Override
    void onImageOpened(@Nullable GPano gpano) {
        mGPano = gpano;
    }

    @MainThread
    @Override
    boolean wraps() {
        return mGPano != null && mGPano.isFullCircle();
    }

    @MainThread
    @Override
    float minimumScale(@NonNull Viewport viewport) {
        return viewport.fillHeightScale();
    }

    /** The column that looks the way the metadata says to open, if it says and the picture has it. */
    @MainThread
    @Override
    float initialCenterX(int imageWidth) {
        GPano gpano = mGPano;
        if (gpano == null || Float.isNaN(gpano.initialHeadingDegrees)) {
            return super.initialCenterX(imageWidth);
        }
        // How far round from the full panorama's left edge the heading is, as a fraction.
        double around = (gpano.initialHeadingDegrees - gpano.poseHeadingDegrees + 180.0) / 360.0;
        around -= Math.floor(around);
        double x = (around - gpano.left) / gpano.width;
        if (x < 0 || x > 1) {
            return super.initialCenterX(imageWidth);
        }
        return (float) (x * imageWidth);
    }
}
