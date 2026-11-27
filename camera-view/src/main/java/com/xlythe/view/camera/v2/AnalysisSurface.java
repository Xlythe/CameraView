package com.xlythe.view.camera.v2;

import android.annotation.TargetApi;
import android.graphics.ImageFormat;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.xlythe.view.camera.stream.VideoRecorder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * A surface whose frames are only read, carried by the still-capture session beside the preview.
 *
 * <p>A preview, a YUV stream no larger than the preview and a full-size JPEG is a combination
 * every camera2 device guarantees, from the LEGACY level up, which is what lets a reader of frames
 * keep the still capture where a stream of its own session could not.</p>
 */
@TargetApi(21)
class AnalysisSurface extends SessionImpl.CameraSurface {
    /** How close two shapes have to be, as a ratio of their aspect ratios, to count as one. */
    private static final double ASPECT_RATIO_TOLERANCE = 0.02;

    /** Hands over the surface to fill, once the size is chosen. */
    private final VideoRecorder.SurfaceProvider mSurfaceProvider;

    /** The preview this stream is sized against. */
    private final SessionImpl.CameraSurface mPreviewSurface;

    /** The reader's surface, which belongs to the provider rather than to this session. */
    @Nullable
    private Surface mSurface;

    AnalysisSurface(Camera2Module camera2Module, VideoRecorder.SurfaceProvider surfaceProvider,
            SessionImpl.CameraSurface previewSurface) {
        super(camera2Module);
        mSurfaceProvider = surfaceProvider;
        mPreviewSurface = previewSurface;
    }

    @Override
    void initialize(StreamConfigurationMap map) {
        Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
        super.initialize(chooseSize(sizes == null ? Collections.emptyList() : Arrays.asList(sizes),
                mPreviewSurface.mSize, mSurfaceProvider.getMaxSize()));
        mSurface = mSurfaceProvider.getSurface(getWidth(), getHeight(),
                mCameraView.getSensorOrientation(), isUsingFrontFacingCamera());
    }

    @Override
    Surface getSurface() {
        return mSurface;
    }

    @Override
    void close() {
        // The surface belongs to the reader that handed it over, which outlives this session.
    }

    /**
     * The largest size of the preview's shape within both the preview and the reader's bound.
     *
     * <p>The same shape keeps the two streams showing the same field of view, so a position found
     * in a frame is the same position on the preview. Within the preview keeps the combination
     * guaranteed. Where neither can be had, the nearest thing: the largest within the bounds, or
     * failing that the smallest size there is.</p>
     */
    @VisibleForTesting
    @NonNull
    static Size chooseSize(@NonNull List<Size> sizes, @NonNull Size preview,
            @Nullable Size maxSize) {
        if (sizes.isEmpty()) {
            throw new IllegalStateException("Camera reports no YUV sizes");
        }
        int maxWidth = preview.getWidth();
        int maxHeight = preview.getHeight();
        if (maxSize != null) {
            maxWidth = Math.min(maxWidth, Math.max(maxSize.getWidth(), maxSize.getHeight()));
            maxHeight = Math.min(maxHeight, Math.min(maxSize.getWidth(), maxSize.getHeight()));
        }
        double aspectRatio = (double) preview.getWidth() / preview.getHeight();
        List<Size> within = new ArrayList<>();
        List<Size> matching = new ArrayList<>();
        for (Size size : sizes) {
            if (size.getWidth() > maxWidth || size.getHeight() > maxHeight) {
                continue;
            }
            within.add(size);
            double ratio = (double) size.getWidth() / size.getHeight();
            if (Math.abs(ratio / aspectRatio - 1) <= ASPECT_RATIO_TOLERANCE) {
                matching.add(size);
            }
        }
        CompareSizesByArea byArea = new CompareSizesByArea();
        if (!matching.isEmpty()) {
            return Collections.max(matching, byArea);
        }
        if (!within.isEmpty()) {
            return Collections.max(within, byArea);
        }
        Size smallest = Collections.min(sizes, byArea);
        Log.w(TAG, "No YUV size within " + maxWidth + "x" + maxHeight + ", using " + smallest);
        return smallest;
    }
}
