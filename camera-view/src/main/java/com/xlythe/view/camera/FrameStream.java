package com.xlythe.view.camera;

import android.Manifest;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;
import androidx.annotation.RestrictTo;

import com.google.common.util.concurrent.SettableFuture;
import com.xlythe.view.camera.stream.VideoRecorder;

import java.io.Closeable;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static android.os.Process.THREAD_PRIORITY_DISPLAY;
import static android.os.Process.THREAD_PRIORITY_VIDEO;
import static android.os.Process.setThreadPriority;

/**
 * The camera's frames, one at a time, for code that wants to look at them.
 *
 * <p>This is the general form of what the barcode scanner does: attach a surface to whichever
 * backend is running, pull frames off it as fast as they are produced, and hand each one to an
 * analyzer. It exists so that detection - faces, text, objects, a model of your own - does not
 * have to be built into the library to be possible, and so that every analyzer gets the same
 * answer about where a frame's pixels land on screen.</p>
 *
 * <p>Frames arrive on a background thread and only the most recent one is kept, so an analyzer
 * that takes longer than a frame interval drops frames rather than falling behind. The {@link
 * Image} inside a {@link Frame} belongs to the camera and is recycled as soon as
 * {@link Analyzer#onFrame} returns: anything that outlives the call has to be copied out.</p>
 *
 * <p>Start one with {@link CameraView#startFrameAnalysis}.</p>
 */
@RequiresApi(19)
public final class FrameStream implements Closeable {
    private static final String TAG = CameraView.class.getSimpleName();

    /** Maximum time allowed for the backend to hand over a surface. */
    private static final long SURFACE_TIMEOUT_SECONDS = 10;

    /** How long to wait before asking the camera for another frame. */
    private static final long IDLE_POLL_MILLIS = 10;

    /** Two buffers let the camera fill one while the analyzer reads the other. */
    private static final int BUFFER_COUNT = 2;

    /** What an analyzer is given for each frame the camera produces. */
    public interface Analyzer {
        /**
         * Called on a background thread for each frame.
         *
         * <p>The frame's image is recycled when this returns.</p>
         */
        void onFrame(@NonNull Frame frame);
    }

    /** What the analyzer wants from the camera. */
    public static final class Params {
        @Nullable private Size mMaxSize;
        private int mImageFormat = ImageFormat.YUV_420_888;

        /**
         * Caps the frame size, which is otherwise whatever the preview is using.
         *
         * <p>Detection costs scale with pixels, and most detectors are no better at full preview
         * resolution than at a fraction of it. The backend picks the closest size it supports
         * within this bound, so the frames are usually smaller than asked for and never larger.
         * </p>
         */
        @NonNull
        public Params setMaxSize(@Nullable Size maxSize) {
            mMaxSize = maxSize;
            return this;
        }

        @Nullable
        public Size getMaxSize() {
            return mMaxSize;
        }

        /**
         * The pixel format of the frames, {@link ImageFormat#YUV_420_888} by default.
         *
         * <p>YUV is the format every camera can produce without conversion, and the one ML Kit and
         * most native detectors take. {@link ImageFormat#PRIVATE} is worth asking for when the
         * frames only ever go to the GPU.</p>
         */
        @NonNull
        public Params setImageFormat(int imageFormat) {
            mImageFormat = imageFormat;
            return this;
        }

        public int getImageFormat() {
            return mImageFormat;
        }
    }

    /** One frame, valid for the duration of the {@link Analyzer#onFrame} call that received it. */
    public final class Frame {
        private final Image mImage;
        private final int mRotationDegrees;
        private final boolean mMirrored;

        Frame(@NonNull Image image, int rotationDegrees, boolean mirrored) {
            mImage = image;
            mRotationDegrees = rotationDegrees;
            mMirrored = mirrored;
        }

        /** The camera's own image. Do not close it, and do not keep it. */
        @NonNull
        public Image getImage() {
            return mImage;
        }

        public int getWidth() {
            return mImage.getWidth();
        }

        public int getHeight() {
            return mImage.getHeight();
        }

        /**
         * The clockwise rotation, in degrees, that makes this frame upright.
         *
         * <p>Frames come off the sensor in the sensor's own orientation, which on almost every
         * phone is a quarter turn from the way the device is held. This is the value ML Kit's
         * {@code InputImage} and most other detectors take.</p>
         */
        public int getRotationDegrees() {
            return mRotationDegrees;
        }

        /** Whether the frame came from a user-facing lens, whose image is a mirror of the scene. */
        public boolean isMirrored() {
            return mMirrored;
        }

        /** The frame's size once {@link #getRotationDegrees} has been applied. */
        @NonNull
        public Size getUprightSize() {
            return TransformMath.rotateSize(
                    new Size(getWidth(), getHeight()), mRotationDegrees);
        }

        /**
         * Maps this frame's pixels onto the CameraView showing it.
         *
         * <p>Use this to draw over the preview: run the detection in frame coordinates, then map
         * the result through this matrix to get view coordinates. It turns the frame upright for
         * the device's current orientation and scales it to cover the view, which is what the
         * preview itself does with the same frames, so a point on a face lands on that face.</p>
         *
         * <p>Mirroring is left to the caller. {@link #isMirrored} says whether the frame came from
         * a user-facing lens; whether the preview shows it mirrored is up to the backend.</p>
         *
         * @return the mapping, or an identity matrix if the view is not laid out or this stream is
         *     not attached to one
         */
        @NonNull
        public Matrix getFrameToViewTransform() {
            CameraView view = mCameraView;
            if (view == null) {
                return new Matrix();
            }
            return TransformMath.centerCrop(
                    new RectF(0f, 0f, getWidth(), getHeight()),
                    TransformMath.within360(mRotationDegrees - view.getDisplayRotation()),
                    false /* the caller decides about mirroring */,
                    view.getWidth(), view.getHeight());
        }
    }

    /** The backend's surfaces. */
    private final VideoRecorder.Canvas mCanvas;

    /** The view the frames are being previewed on, for {@link Frame#getFrameToViewTransform}. */
    @Nullable
    private final CameraView mCameraView;

    private final Params mParams;
    private final Analyzer mAnalyzer;

    /** Whether the background thread should keep pulling frames. */
    private final AtomicBoolean mIsAlive = new AtomicBoolean();

    /** The thread pulling frames, once started. */
    private final AtomicReference<Thread> mThread = new AtomicReference<>();

    FrameStream(@NonNull VideoRecorder.Canvas canvas, @Nullable CameraView cameraView,
            @NonNull Params params, @NonNull Analyzer analyzer) {
        mCanvas = canvas;
        mCameraView = cameraView;
        mParams = params;
        mAnalyzer = analyzer;
    }

    /** Whether frames are being delivered. */
    public boolean isAnalyzing() {
        return mIsAlive.get();
    }

    /**
     * A stream the library drives itself, rather than one an app asked for.
     *
     * <p>Here because a backend that has no analysis use case to borrow - Camera2 - gets frames
     * the same way an app does, and its module lives in another package. Restricted rather than
     * made public: an app starts a stream through
     * {@link CameraView#startFrameAnalysis(Analyzer)}, which also keeps the one analysis slot
     * accounted for.</p>
     *
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    @NonNull
    public static FrameStream forLibrary(@NonNull VideoRecorder.Canvas canvas,
            @NonNull Params params, @NonNull Analyzer analyzer) {
        return new FrameStream(canvas, null /* view */, params, analyzer);
    }

    /** Starts delivering frames. Does nothing if already started. */
    @RequiresPermission(Manifest.permission.CAMERA)
    public void start() {
        if (!mIsAlive.compareAndSet(false, true)) {
            Log.w(TAG, "FrameStream is already running");
            return;
        }
        Thread thread = new Thread(this::pump, "CameraView-FrameAnalysis");
        thread.setDaemon(true);
        mThread.set(thread);
        thread.start();
    }

    /** Stops delivering frames and waits briefly for the thread to notice. */
    @Override
    public void close() {
        mIsAlive.set(false);
        Thread thread = mThread.getAndSet(null);
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(300);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private void pump() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            setThreadPriority(THREAD_PRIORITY_VIDEO);
        } else {
            setThreadPriority(THREAD_PRIORITY_DISPLAY);
        }

        // The backend decides the frame size, and only tells us when it hands over the surface it
        // wants filled - which it cannot do until we have one to give. The two futures pass the
        // size out and the surface back in across that handshake.
        SettableFuture<Configuration> configuration = SettableFuture.create();
        SettableFuture<Surface> surface = SettableFuture.create();
        VideoRecorder.SurfaceProvider provider = new VideoRecorder.SurfaceProvider() {
            @Override
            public Surface getSurface(int width, int height, int orientation, boolean flipped) {
                configuration.set(new Configuration(width, height, orientation, flipped));
                try {
                    return surface.get(SURFACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (ExecutionException | InterruptedException | TimeoutException error) {
                    throw new RuntimeException(error);
                }
            }

            @Nullable
            @Override
            public Size getMaxSize() {
                return mParams.getMaxSize();
            }
        };
        mCanvas.attachSurface(provider);

        ImageReader reader = null;
        try {
            Configuration frames = Objects.requireNonNull(
                    configuration.get(SURFACE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            reader = ImageReader.newInstance(frames.width, frames.height,
                    mParams.getImageFormat(), BUFFER_COUNT);
            surface.set(reader.getSurface());

            while (mIsAlive.get()) {
                Image image = reader.acquireLatestImage();
                if (image == null) {
                    try {
                        Thread.sleep(IDLE_POLL_MILLIS);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                try {
                    mAnalyzer.onFrame(new Frame(image, frames.orientation, frames.flipped));
                } catch (RuntimeException error) {
                    Log.w(TAG, "Frame analysis failed", error);
                } finally {
                    image.close();
                }
            }
        } catch (ExecutionException | InterruptedException | TimeoutException
                | RuntimeException error) {
            Log.e(TAG, "Exception with the frame analysis stream", error);
        } finally {
            mIsAlive.set(false);
            mCanvas.detachSurface(provider);
            if (reader != null) {
                reader.close();
            }
        }
    }

    /** What the backend reported about the frames it is going to produce. */
    private static final class Configuration {
        final int width;
        final int height;
        final int orientation;
        final boolean flipped;

        Configuration(int width, int height, int orientation, boolean flipped) {
            this.width = width;
            this.height = height;
            this.orientation = orientation;
            this.flipped = flipped;
        }
    }
}
