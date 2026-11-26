package com.xlythe.view.camera;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.Segmentation;
import com.google.mlkit.vision.segmentation.Segmenter;
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions;
import com.xlythe.view.camera.gl.SegmentationMask;

import java.io.Closeable;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Turns the camera's frames into masks, for any backend that can deliver frames.
 *
 * <p>The counterpart of the CameraX analyzer, written against {@link FrameStream} rather than
 * against CameraX's own analysis - which is what makes it work on the Camera2 backend. The
 * model is the same and so is the arrangement; what differs is only where the frame comes
 * from, and a frame stream hands over the camera's own {@code Image}, which is exactly what the
 * model takes.</p>
 *
 * <p>Stream mode rather than single-image, which is what it is for: the segmenter keeps its
 * previous result and refines it, which is both faster and steadier across frames than
 * segmenting each one from nothing. A mask that flickers at the edges between frames is the
 * usual sign of the wrong mode.</p>
 *
 * <p>Frames are dropped rather than queued. A frame stream keeps only the newest frame, so a
 * model slower than the frame rate simply sees fewer frames - which is the behaviour wanted,
 * because a backlog of masks describing moments that have passed is worse than a mask that is
 * merely recent.</p>
 *
 * @hide
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class SegmentationAnalyzer implements FrameStream.Analyzer, Closeable {
    private static final String TAG = CameraView.class.getSimpleName();

    private final Segmenter mSegmenter;
    private final CameraView.SegmentationListener mListener;
    private final Executor mCallbackExecutor;
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final boolean mMirrored;

    /**
     * @param mirrored true when these frames come from a front camera the library mirrors, so
     *     the mask has to be read back the other way round
     */
    public SegmentationAnalyzer(@NonNull CameraView.SegmentationListener listener,
            @NonNull Executor callbackExecutor, boolean mirrored) {
        mListener = listener;
        mCallbackExecutor = callbackExecutor;
        mMirrored = mirrored;
        mSegmenter = Segmentation.getClient(new SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                .build());
    }

    /**
     * Segments one frame.
     *
     * <p>The frame is not kept: the model is handed it inside this call, which is the only time
     * it is valid, and what comes back is a mask rather than a reference to the frame. Waiting
     * for the result here would hold the stream up for as long as the model takes.</p>
     */
    @Override
    public void onFrame(@NonNull FrameStream.Frame frame) {
        if (mClosed.get()) {
            return;
        }
        try {
            InputImage image = InputImage.fromMediaImage(
                    frame.getImage(), frame.getRotationDegrees());
            mSegmenter.process(image)
                    .addOnSuccessListener(mCallbackExecutor, mask -> {
                        if (mClosed.get()) return;
                        mListener.onSegmentation(new SegmentationMask(
                                mask.getBuffer(), mask.getWidth(), mask.getHeight(), mMirrored));
                    })
                    .addOnFailureListener(mCallbackExecutor,
                            error -> Log.w(TAG, "Segmentation failed", error));
        } catch (RuntimeException error) {
            Log.w(TAG, "Segmentation could not start on this frame", error);
        }
    }

    /** Releases the model. */
    @Override
    public void close() {
        if (mClosed.compareAndSet(false, true)) {
            mSegmenter.close();
        }
    }
}
