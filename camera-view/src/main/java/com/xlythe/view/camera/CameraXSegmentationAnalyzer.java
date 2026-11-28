package com.xlythe.view.camera;

import android.media.Image;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.Segmentation;
import com.google.mlkit.vision.segmentation.Segmenter;
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions;
import com.xlythe.view.camera.gl.SegmentationMask;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CameraX ImageAnalysis adapter that turns frames into segmentation masks.
 *
 * <p>Stream mode rather than single-image, which is what it is for: the segmenter keeps its
 * previous result and refines it, which is both faster and steadier across frames than
 * segmenting each one from nothing. A mask that flickers at the edges between frames is the
 * usual sign of the wrong mode.</p>
 *
 * <p>Frames are dropped rather than queued. Analysis delivers one image at a time and the next
 * does not arrive until this one is closed, so a model slower than the frame rate simply sees
 * fewer frames - which is the behaviour wanted. The alternative, a backlog of masks describing
 * moments that have passed, is worse than a mask that is merely recent.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class CameraXSegmentationAnalyzer implements CameraXFrameAnalyzer {
    /** Log tag shared with CameraView. */
    private static final String TAG = CameraView.class.getSimpleName();

    /** The ML Kit selfie segmenter, released by {@link #close}. */
    private final Segmenter mSegmenter;
    /** Hears each mask, on {@link #mCallbackExecutor}. */
    private final CameraView.SegmentationListener mListener;
    /** Where {@link #mListener} is called. */
    private final Executor mCallbackExecutor;
    /** Whether {@link #close} has been called, after which no mask is reported. */
    private final AtomicBoolean mClosed = new AtomicBoolean();
    /**
     * Whether the frames come from a lens shown mirrored, as a front camera is, so the mask is
     * read flipped. Set on the main thread before each bind and read on the analysis thread.
     */
    private volatile boolean mMirrored;

    public CameraXSegmentationAnalyzer(@NonNull CameraView.SegmentationListener listener,
            @NonNull Executor callbackExecutor) {
        this(listener, callbackExecutor,
                Segmentation.getClient(new SelfieSegmenterOptions.Builder()
                        .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                        .build()));
    }

    /** Runs frames through the given segmenter, which a test supplies in ML Kit's place. */
    @VisibleForTesting
    CameraXSegmentationAnalyzer(@NonNull CameraView.SegmentationListener listener,
            @NonNull Executor callbackExecutor, @NonNull Segmenter segmenter) {
        mListener = listener;
        mCallbackExecutor = callbackExecutor;
        mSegmenter = segmenter;
    }

    @MainThread
    @Override
    public void setMirrored(boolean mirrored) {
        mMirrored = mirrored;
    }

    @ExperimentalGetImage
    @Override public void analyze(@NonNull ImageProxy imageProxy) {
        if (mClosed.get()) {
            imageProxy.close();
            return;
        }
        Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            imageProxy.close();
            return;
        }
        // Read as the frame arrives, since a lens switch can tell this analyzer about the next
        // camera while the model is still working on a frame from the last.
        boolean mirrored = mMirrored;
        long timestampNanos = imageProxy.getImageInfo().getTimestamp();
        InputImage image = InputImage.fromMediaImage(
                mediaImage, imageProxy.getImageInfo().getRotationDegrees());
        try {
            mSegmenter.process(image)
                    .addOnSuccessListener(mCallbackExecutor, mask -> {
                        if (mClosed.get()) return;
                        mListener.onSegmentation(new SegmentationMask(mask.getBuffer(),
                                mask.getWidth(), mask.getHeight(), mirrored, timestampNanos));
                    })
                    .addOnFailureListener(mCallbackExecutor,
                            error -> Log.w(TAG, "CameraX segmentation failed", error))
                    .addOnCompleteListener(mCallbackExecutor, ignored -> imageProxy.close());
        } catch (RuntimeException error) {
            imageProxy.close();
            Log.w(TAG, "CameraX segmentation could not start", error);
        }
    }

    @Override public void close() {
        if (mClosed.compareAndSet(false, true)) mSegmenter.close();
    }
}
