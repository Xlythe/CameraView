package com.xlythe.view.camera;

import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.Segmentation;
import com.google.mlkit.vision.segmentation.Segmenter;
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions;
import com.xlythe.view.camera.gl.SegmentationMask;

import java.io.Closeable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
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
 * <p>Frames are dropped rather than queued. A frame stream keeps only the newest frame, and each
 * frame is held until the model has finished with it, so a model slower than the frame rate
 * simply sees fewer frames - which is the behaviour wanted, because a backlog of masks describing
 * moments that have passed is worse than a mask that is merely recent.</p>
 *
 * @hide
 */
@RequiresApi(21)
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class SegmentationAnalyzer implements FrameStream.Analyzer, Closeable {
    /** Log tag shared with CameraView. */
    private static final String TAG = CameraView.class.getSimpleName();

    /** How long a frame may take before it is logged as slow. It is waited for regardless. */
    private static final long SLOW_FRAME_MILLIS = 10_000;

    /** A class only ML Kit's selfie segmentation has, looked up to see whether the app has it. */
    private static final String ML_KIT_CLASS = "com.google.mlkit.vision.segmentation.Segmentation";

    /** The ML Kit selfie segmenter, released by {@link #close}. */
    private final Segmenter mSegmenter;
    /** Hears each mask, on {@link #mCallbackExecutor}. */
    private final CameraView.SegmentationListener mListener;
    /** Where {@link #mListener} is called. */
    private final Executor mCallbackExecutor;
    /** Whether {@link #close} has been called, after which no mask is reported. */
    private final AtomicBoolean mClosed = new AtomicBoolean();

    public SegmentationAnalyzer(@NonNull CameraView.SegmentationListener listener,
            @NonNull Executor callbackExecutor) {
        this(listener, callbackExecutor,
                Segmentation.getClient(new SelfieSegmenterOptions.Builder()
                        .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                        .build()));
    }

    /**
     * Whether ML Kit's selfie segmentation can be loaded.
     *
     * <p>Checked by name and without initializing the class, so a missing dependency is a false
     * here rather than a NoClassDefFoundError out of the constructor.</p>
     */
    @AnyThread
    static boolean isAvailable() {
        try {
            Class.forName(ML_KIT_CLASS, false, SegmentationAnalyzer.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError error) {
            return false;
        }
    }

    /** Runs frames through the given segmenter, which a test supplies in ML Kit's place. */
    @VisibleForTesting
    SegmentationAnalyzer(@NonNull CameraView.SegmentationListener listener,
            @NonNull Executor callbackExecutor, @NonNull Segmenter segmenter) {
        mListener = listener;
        mCallbackExecutor = callbackExecutor;
        mSegmenter = segmenter;
    }

    /**
     * Segments one frame, returning once the model has finished reading it.
     *
     * <p>The frame is valid only inside this call: the stream closes it, and on stopping closes
     * its reader, as soon as this returns. The model reads it on a thread of its own, so this
     * waits for the result, which is a mask rather than a reference to the frame.</p>
     *
     * <p>The mask is mirrored when the frame is, as a front camera's is, since the model sees the
     * frame unmirrored and the preview shows it mirrored. That is asked of each frame, not fixed
     * when segmenting starts, so a mask stays the right way round across a lens switch.</p>
     */
    @WorkerThread
    @Override
    public void onFrame(@NonNull FrameStream.Frame frame) {
        if (mClosed.get()) {
            return;
        }
        Task<com.google.mlkit.vision.segmentation.SegmentationMask> segmented;
        try {
            segmented = mSegmenter.process(InputImage.fromMediaImage(
                    frame.getImage(), frame.getRotationDegrees()));
        } catch (RuntimeException error) {
            Log.w(TAG, "Segmentation could not start on this frame", error);
            return;
        }
        com.google.mlkit.vision.segmentation.SegmentationMask mask;
        try {
            mask = MlKitTasks.awaitFrameRead(segmented, SLOW_FRAME_MILLIS, "Segmenting a frame");
        } catch (ExecutionException | CancellationException error) {
            Log.w(TAG, "Segmentation failed", error);
            return;
        }
        SegmentationMask result = new SegmentationMask(
                mask.getBuffer(), mask.getWidth(), mask.getHeight(), frame.isMirrored());
        mCallbackExecutor.execute(() -> {
            if (!mClosed.get()) {
                mListener.onSegmentation(result);
            }
        });
    }

    /** Releases the model. */
    @Override
    public void close() {
        if (mClosed.compareAndSet(false, true)) {
            mSegmenter.close();
        }
    }
}
