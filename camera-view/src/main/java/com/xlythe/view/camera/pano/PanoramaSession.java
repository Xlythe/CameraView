package com.xlythe.view.camera.pano;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xlythe.view.camera.FrameStream;

import java.io.Closeable;
import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * A sweep in progress: frames in, a sphere out.
 *
 * <p>The piece that joins the three halves of a panorama - the camera's frames, the phone's
 * rotation, and the canvas they are painted on - and the only one that has to run while
 * somebody is turning round. It decides nothing about how the sweep is presented: it reports
 * where to point next and how far along it is, and what to draw on screen is the decision of
 * the app.</p>
 *
 * <p>Frames arrive on the analysis thread and are painted there. Painting a frame costs a few
 * milliseconds, and frames that cover ground already covered are dropped before any of that
 * work - a sweep that pauses would otherwise spend its time painting the same place thirty
 * times a second, which is slower and no better.</p>
 */
public final class PanoramaSession implements FrameStream.Analyzer, Closeable {
    /**
     * How wide the sphere is by default, in pixels.
     *
     * <p>Four thousand across is a photosphere of eight megapixels, which is what a phone
     * camera sweep is worth and what viewers are built for. The bitmap behind it is thirty-two
     * megabytes, which is the real limit: twice this is four times the memory and more than
     * many devices will give one allocation.</p>
     */
    public static final int DEFAULT_CANVAS_WIDTH = 4000;

    /** Told how a sweep is going, and what it ended up as. */
    public interface Listener {
        /**
         * How far along the sweep is, and where to point next.
         *
         * <p>Called on the executor given to the session, as often as a frame is painted - so
         * several times a second while someone is turning, and not at all while they are still.
         * </p>
         *
         * @param progress 0 to 1
         * @param nextYawDegrees where to aim next, or NaN when there is nowhere left
         * @param nextPitchDegrees the same for elevation
         */
        void onPanoramaProgress(float progress, float nextYawDegrees, float nextPitchDegrees);

        /** The sweep was saved. */
        void onPanoramaCaptured(@NonNull File file);

        /** The sweep could not be saved, having already said why in the log. */
        void onPanoramaFailed();
    }

    private final DeviceOrientation mOrientation;
    private final PanoramaCoverage mCoverage;
    private final PanoramaCanvas mCanvas;
    private final Listener mListener;
    private final Executor mCallbackExecutor;
    private final float[] mRotation = new float[16];
    private final float[] mDirection = new float[2];
    private final float[] mNext = new float[2];
    private final AtomicBoolean mClosed = new AtomicBoolean();

    /**
     * The angles of view of the lens, across and down the frame as the camera delivers it.
     *
     * <p>Not as the frame is held: a frame arrives in the sensor's own orientation, so for a
     * phone held upright the frame is on its side and its angles of view are the other way
     * round. Which is which is settled per frame, from the rotation the frame reports.</p>
     */
    private final float mSensorHorizontalFieldOfView;
    private final float mSensorVerticalFieldOfView;

    /**
     * @param canvasWidth how wide the sphere should be, {@link #DEFAULT_CANVAS_WIDTH} if unsure
     * @param horizontalFieldOfViewDegrees the lens angle of view across the sensor, from
     *     {@code CameraView.getHorizontalFieldOfView}
     * @param verticalFieldOfViewDegrees its angle of view down the sensor
     * @param callbackExecutor where the listener is called, usually the main thread
     */
    public PanoramaSession(@NonNull Context context, @NonNull PanoramaCoverage coverage,
            int canvasWidth, float horizontalFieldOfViewDegrees,
            float verticalFieldOfViewDegrees, @NonNull Listener listener,
            @NonNull Executor callbackExecutor) {
        mOrientation = new DeviceOrientation(context);
        mCoverage = coverage;
        mCanvas = new PanoramaCanvas(canvasWidth, coverage);
        mListener = listener;
        mCallbackExecutor = callbackExecutor;
        mSensorHorizontalFieldOfView = horizontalFieldOfViewDegrees;
        mSensorVerticalFieldOfView = verticalFieldOfViewDegrees;
    }

    /** Whether this device can report its rotation, without which there is no sweep. */
    public boolean isSupported() {
        return mOrientation.isSupported()
                && mSensorHorizontalFieldOfView > 0f && mSensorVerticalFieldOfView > 0f;
    }

    /** Starts listening to the sensors. The frames come from whoever registered this analyzer. */
    public void start() {
        mOrientation.start();
    }

    /** The sphere as it stands, for an app that wants to show the sweep filling in. */
    @NonNull
    public Bitmap getBitmap() {
        return mCanvas.getBitmap();
    }

    /** How much of what was asked for has been covered, 0 to 1. */
    public float getProgress() {
        return mCoverage.getProgress();
    }

    /** Whether there is nothing left to point at. */
    public boolean isComplete() {
        return mCoverage.isComplete();
    }

    @Override
    public void onFrame(@NonNull FrameStream.Frame frame) {
        if (mClosed.get() || !mOrientation.getRotationMatrix(mRotation)) {
            return;
        }
        // Which way the middle of the frame is pointing, which is what decides whether this
        // frame is worth painting at all.
        boolean upright = isUpright(frame.getRotationDegrees());
        float horizontal = upright ? mSensorHorizontalFieldOfView : mSensorVerticalFieldOfView;
        float vertical = upright ? mSensorVerticalFieldOfView : mSensorHorizontalFieldOfView;
        if (!Equirectangular.directionFor(0.5f, 0.5f, mRotation, horizontal, vertical,
                mDirection)) {
            return;
        }
        if (!mCoverage.cover(mDirection[0], mDirection[1])) {
            // Already painted. Dropped before the conversion, which is the expensive part.
            return;
        }
        Bitmap bitmap = YuvFrame.toBitmap(
                frame.getImage(), frame.getRotationDegrees(), frame.isMirrored());
        if (bitmap == null) {
            return;
        }
        try {
            mCanvas.draw(bitmap, mRotation, horizontal, vertical);
        } finally {
            bitmap.recycle();
        }
        reportProgress();
    }

    /**
     * Whether a frame with this rotation is already the way up it will be drawn.
     *
     * <p>A frame is turned upright before it is painted, and turning it a quarter turn swaps
     * its width and height - and so its angles of view. Painting an upright frame with the
     * sensor's own angles of view stretches every frame by the aspect ratio of the sensor,
     * which on a finished sweep is a horizon that does not line up with itself.</p>
     */
    private static boolean isUpright(int rotationDegrees) {
        int turned = ((rotationDegrees % 360) + 360) % 360;
        return turned == 0 || turned == 180;
    }

    private void reportProgress() {
        float progress = mCoverage.getProgress();
        float nextYaw = Float.NaN;
        float nextPitch = Float.NaN;
        if (mCoverage.suggestNext(mDirection[0], mDirection[1], mNext)) {
            nextYaw = mNext[0];
            nextPitch = mNext[1];
        }
        float yaw = nextYaw;
        float pitch = nextPitch;
        mCallbackExecutor.execute(() -> {
            if (!mClosed.get()) {
                mListener.onPanoramaProgress(progress, yaw, pitch);
            }
        });
    }

    /**
     * Stops the sweep and writes what it got.
     *
     * <p>Whatever it got: a sweep that was abandoned halfway is still a panorama of half a
     * room, and the metadata says which half - so stopping early produces a smaller picture
     * rather than no picture. Blocks while the file is written, so it belongs off the main
     * thread.</p>
     *
     * @param quality 0 to 100, as a JPEG takes
     */
    public void finish(@NonNull File file, int quality) {
        if (mClosed.get()) {
            mListener.onPanoramaFailed();
            return;
        }
        mOrientation.stop();
        float heading = mCoverage.getStartYaw();
        boolean saved = mCanvas.getFramesDrawn() > 0 && mCanvas.save(file, quality, heading);
        if (!saved) {
            Log.w(TAG, "The sweep painted " + mCanvas.getFramesDrawn()
                    + " frames and could not be saved");
        }
        boolean succeeded = saved;
        mCallbackExecutor.execute(() -> {
            if (succeeded) {
                mListener.onPanoramaCaptured(file);
            } else {
                mListener.onPanoramaFailed();
            }
        });
    }

    /** Gives the canvas back. Nothing may be painted or saved afterwards. */
    @Override
    public void close() {
        if (mClosed.compareAndSet(false, true)) {
            mOrientation.stop();
            mCanvas.recycle();
        }
    }

    /** What this session was asked to cover, for an app drawing its own guidance. */
    @NonNull
    public PanoramaCoverage getCoverage() {
        return mCoverage;
    }

    /** The newest direction the camera was pointed, or null before any frame arrived. */
    @Nullable
    public float[] getLastDirection() {
        return mCanvas.getFramesDrawn() == 0 ? null : new float[] {mDirection[0], mDirection[1]};
    }
}
