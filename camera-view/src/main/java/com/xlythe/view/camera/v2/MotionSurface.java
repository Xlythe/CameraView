package com.xlythe.view.camera.v2;

import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;

import com.xlythe.view.camera.gl.GlMath;
import com.xlythe.view.camera.stream.MotionPhotoRecorder;
import com.xlythe.view.camera.stream.RenderedMotionInput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * The output a picture session feeds a motion photo's encoder through: every preview frame, at
 * the preview's shape.
 *
 * <p>A back camera writes into the encoder itself. A front camera's still is mirrored like its
 * preview, and an encoder ignores the mirror a camera asks for, so its frames go through a renderer
 * of their own that mirrors them the same way.</p>
 */
@RequiresApi(21)
final class MotionSurface extends SessionImpl.CameraSurface {
    /** The encoder. */
    private final MotionPhotoRecorder mRecorder;
    /** The preview, whose size and shape the clip follows. */
    private final SessionImpl.CameraSurface mPreviewSurface;
    /** The renderer that mirrors a front camera's frames, or null for a back camera. */
    @Nullable
    private RenderedMotionInput mRenderer;
    /** What the camera writes into. */
    @Nullable
    private Surface mSurface;

    MotionSurface(@NonNull Camera2Module module, @NonNull MotionPhotoRecorder recorder,
            @NonNull SessionImpl.CameraSurface previewSurface) {
        super(module);
        mRecorder = recorder;
        mPreviewSurface = previewSurface;
    }

    /**
     * Sizes the clip and starts feeding the encoder.
     *
     * @throws IllegalStateException when the encoder cannot start, or a front camera's frames
     *     cannot be mirrored, either of which leaves the session without clips
     */
    @MainThread
    @Override
    void initialize(StreamConfigurationMap map) {
        Size size = chooseSize(map.getOutputSizes(MediaCodec.class), mPreviewSurface.mSize,
                mRecorder.getMaxSize());
        if (size == null) {
            throw new IllegalStateException("The camera offers no size an encoder takes");
        }
        super.initialize(size);
        @GlMath.Rotation int rotation =
                GlMath.quarterTurn(mCameraView.getRelativeCameraOrientation());
        if (isUsingFrontFacingCamera()) {
            mRenderer = RenderedMotionInput.start(mRecorder, size, rotation, true,
                    false /* cropToFollow */);
            if (mRenderer == null) {
                throw new IllegalStateException("A front camera's clip cannot be mirrored here");
            }
            mSurface = mRenderer.getSurface();
        } else {
            mSurface = mRecorder.getInputSurface(size.getWidth(), size.getHeight(), rotation,
                    MotionPhotoRecorder.INPUT_CAMERA);
        }
        Log.d(TAG, "Feeding motion photo clips at " + size + " turned " + rotation
                + (mRenderer != null ? ", mirrored" : ""));
    }

    /**
     * The size to encode at: the preview's own if an encoder can be fed it, since then the clip is
     * exactly what was on screen, and otherwise the largest size of the same shape that fits.
     * Failing that, the largest of any shape that fits.
     *
     * @return the size, or null when the camera lists none that fits
     */
    @VisibleForTesting
    @Nullable
    static Size chooseSize(@Nullable Size[] supported, @Nullable Size preview, @NonNull Size max) {
        if (supported == null || supported.length == 0) {
            return null;
        }
        int maxLong = Math.max(max.getWidth(), max.getHeight());
        int maxShort = Math.min(max.getWidth(), max.getHeight());
        List<Size> fitting = new ArrayList<>();
        for (Size size : supported) {
            if (Math.max(size.getWidth(), size.getHeight()) <= maxLong
                    && Math.min(size.getWidth(), size.getHeight()) <= maxShort) {
                fitting.add(size);
            }
        }
        if (fitting.isEmpty()) {
            return null;
        }
        if (preview != null) {
            if (fitting.contains(preview)) {
                return preview;
            }
            List<Size> sameShape = new ArrayList<>();
            for (Size size : fitting) {
                if (sameAspectRatio(size, preview)) {
                    sameShape.add(size);
                }
            }
            if (!sameShape.isEmpty()) {
                return Collections.max(sameShape, new CompareSizesByArea());
            }
        }
        return Collections.max(fitting, new CompareSizesByArea());
    }

    @NonNull
    @Override
    Surface getSurface() {
        if (mSurface == null) {
            throw new IllegalStateException("The motion photo output was never set up");
        }
        return mSurface;
    }

    /** Stops the renderer, if there is one. The encoder belongs to the module and carries on. */
    @MainThread
    @Override
    void close() {
        if (mRenderer != null) {
            mRenderer.release();
            mRenderer = null;
        }
        mSurface = null;
    }
}
