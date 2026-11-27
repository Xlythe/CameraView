package com.xlythe.view.camera.pano;

import android.content.Context;
import android.graphics.Bitmap;
import android.location.Location;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import com.xlythe.view.camera.Exif;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A panorama or photosphere being swept: frames and rotation in, a picture with its photo sphere
 * metadata out.
 *
 * <p>Frames are placed by where the phone was pointing when each was captured, which is what
 * lets this run on any device with a rotation sensor and no stitching library - and why the sweep
 * has to be a turn on the spot, since no rotation reconciles two views of something near the
 * camera taken from different places.</p>
 *
 * <p>Not every frame is painted. One is painted when it is aimed somewhere no painted frame was
 * aimed near, or when it finishes off the target the guide is pointing at; and never while the
 * phone is turning fast enough to blur it. A sweep that pauses would otherwise spend its time
 * painting the same place thirty times a second, which is slower and blurs the seams.</p>
 *
 * <p>Frames arrive on whichever thread the backend delivers them on: the frame analysis thread
 * for an {@code Image}, or this session's own worker for a legacy preview frame, which arrives on
 * the main thread and is handed over rather than processed there. Saving happens on the same
 * worker once the sweep is over.</p>
 */
@RequiresApi(19)
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class PanoramaSession implements GuideState.Source {
    /** Told how the sweep is going, always on the main thread. */
    public interface Callback {
        /** How much of what was asked for is covered, from 0 to 1. */
        @UiThread
        void onProgress(float progress);

        /** The sweep is over, so frames can stop. The picture is being written. */
        @UiThread
        void onFinishing();

        /** The picture was written. */
        @UiThread
        void onSaved(@NonNull File file);

        /** Nothing could be written, having said why in the log. */
        @UiThread
        void onFailed();

        /** Where the picture was taken, without waiting for a fix; null when unknown. */
        @AnyThread
        @Nullable
        Location getLocation();
    }

    private static final String TAG = "CameraView";

    /** Above this, a frame is smeared by the turn and the rotation is least certain. */
    static final float MAX_TURN_DEGREES_PER_SECOND = 45f;

    /** Frames are converted at most this long, which is as much detail as the canvas keeps. */
    static final int MAX_FRAME_SIDE = 1280;

    /** The widest a photosphere is saved: eight megapixels, which is what viewers expect. */
    static final int PHOTOSPHERE_MAX_WIDTH = 4096;

    /** The widest a panorama is saved. Only a band of it is ever kept, so it can be sharper. */
    static final int PANORAMA_MAX_WIDTH = 6144;

    /** The guide's picture of the sphere: two pixels to a degree. */
    static final int THUMBNAIL_WIDTH = 720;

    /** A frame aimed this fraction of the frame's narrower side from every painted one is new. */
    static final float PAINT_SPACING = 0.25f;

    /** A photosphere frame that would cover this many new cells is worth painting anyway. */
    static final int MIN_FRESH_CELLS = 30;

    /** Aimed within this of the target the guide points at, a frame finishes it off. */
    static final float ALIGNED_DEGREES = 6f;

    /** A panorama started within this of level is swept along the horizon itself. */
    static final float LEVEL_DEGREES = 10f;

    /** A measured focal length this close to the published one is the published one. */
    static final float RECALIBRATION_THRESHOLD = 0.03f;

    /** A frame taking longer than this to arrive is not a measure of the preview's delay. */
    private static final long MAX_LATENCY_NANOS = 500_000_000L;

    /** What the picture is filled with where nothing was painted. */
    private static final int UNPAINTED = 0xFF000000;

    /** The sweep is taking frames. */
    private static final int RUNNING = 0;

    /** The sweep is over and its picture is being written. */
    private static final int FINISHING = 1;

    /** The picture was written, or the sweep was cancelled. */
    private static final int DONE = 2;

    /** For the app's name, which goes in the metadata. */
    private final Context mContext;

    /** Whether the whole sphere is wanted, rather than a band round it. */
    private final boolean mPhotosphere;

    /** Where the picture goes. */
    private final File mFile;

    /** JPEG quality, from 1 to 100. */
    private final int mQuality;

    /** The lens's angle of view across the sensor's width, in degrees. */
    private final float mSensorHorizontalFov;

    /** The lens's angle of view down the sensor's height, in degrees. */
    private final float mSensorVerticalFov;

    /** Told how it is going. */
    private final Callback mCallback;

    /** Runs the callbacks, on the main thread. */
    private final Executor mMainExecutor;

    /** Where the rotation comes from. */
    private final RotationSensor mSensor;

    /** Converts legacy frames and writes the picture, off the main thread. */
    private final ExecutorService mWorker;

    /** One of {@link #RUNNING}, {@link #FINISHING} and {@link #DONE}. */
    private final AtomicInteger mState = new AtomicInteger(RUNNING);

    /** Whether a legacy frame is being processed, in which case the next one is dropped. */
    private final AtomicBoolean mBusy = new AtomicBoolean();

    /** Whether finishing has been asked for since everything was covered. */
    private final AtomicBoolean mCompletionPosted = new AtomicBoolean();

    /** Guards everything below, which is touched by frames and by the save. */
    private final Object mLock = new Object();

    /** The sphere, from the first frame on. */
    @Nullable private SphereCanvas mCanvas;

    /** What is covered, from the first frame on. */
    @Nullable private SweepCoverage mCoverage;

    /** Reused between frames, so converting one does not allocate its planes. */
    private final PanoramaFrame.Scratch mScratch = new PanoramaFrame.Scratch();

    /** Where every painted frame was aimed, as unit vectors. */
    private final List<float[]> mPainted = new ArrayList<>();

    /** Measures the lens's own focal length from the first frames painted. */
    private final LensCalibration mCalibration = new LensCalibration();

    /**
     * The frames painted before the calibration settled, kept to be painted again if it finds
     * the published focal length was wrong.
     */
    private final List<KeptFrame> mUncalibrated = new ArrayList<>();

    /** What the published focal length is multiplied by: 1 until the calibration says otherwise. */
    private float mFocalScale = 1f;

    /** The guide's small copy of the sphere, updated as frames are painted. */
    @Nullable private int[] mThumbnail;

    /** The target the guide is pointing at, or -1. */
    private int mNextTarget = -1;

    /** The yaw the sweep started at, which the canvas is centred on. */
    private float mStartYaw;

    /** The pitch a panorama is swept along. */
    private float mBandPitch;

    /** Whether frames come from the front lens, in which case the picture is mirrored. */
    private boolean mFrontFacing;

    /** The angle of view across the picture as it was held, for the metadata. */
    private float mUprightHorizontalFov;

    /** The angle of view down the picture as it was held. */
    private float mUprightVerticalFov;

    /** When the first painted frame arrived, in wall-clock milliseconds. */
    private long mFirstPhotoMillis;

    /** When the last one did. */
    private long mLastPhotoMillis;

    /** The last progress reported, so it is reported only when it moves. */
    private float mReportedProgress = -1f;

    /** Bumped each time the thumbnail changes. */
    private int mThumbnailVersion;

    /** The newest published copy of the thumbnail, shared with the guide. */
    @Nullable private int[] mPublishedThumbnail;

    /** What the guide shows. */
    @Nullable private volatile GuideState mGuideState;

    /** How far behind the sensor the camera's frames are, averaged, in nanoseconds. */
    private volatile long mLatencyNanos;

    /**
     * @param sensorHorizontalFov the lens's angle of view across the sensor's width
     * @param sensorVerticalFov its angle of view down the sensor's height
     */
    public PanoramaSession(@NonNull Context context, boolean photosphere, @NonNull File file,
            int quality, float sensorHorizontalFov, float sensorVerticalFov,
            @NonNull Callback callback, @NonNull Executor mainExecutor) {
        this(context, photosphere, file, quality, sensorHorizontalFov, sensorVerticalFov,
                callback, mainExecutor, new RotationSensor(context));
    }

    @VisibleForTesting
    PanoramaSession(@NonNull Context context, boolean photosphere, @NonNull File file,
            int quality, float sensorHorizontalFov, float sensorVerticalFov,
            @NonNull Callback callback, @NonNull Executor mainExecutor,
            @NonNull RotationSensor sensor) {
        mContext = context.getApplicationContext();
        mPhotosphere = photosphere;
        mFile = file;
        mQuality = quality;
        mSensorHorizontalFov = sensorHorizontalFov;
        mSensorVerticalFov = sensorVerticalFov;
        mCallback = callback;
        mMainExecutor = mainExecutor;
        mSensor = sensor;
        mWorker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "CameraView-Panorama");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Starts reading the rotation, ahead of the frames so the first one has a rotation to go
     * with it.
     *
     * @return false if this device cannot report its rotation or the lens's angles of view are
     *     unknown, either of which leaves nothing to place a frame by
     */
    @UiThread
    public boolean start() {
        if (!mSensor.isAvailable() || mSensorHorizontalFov <= 0f || mSensorVerticalFov <= 0f) {
            mState.set(DONE);
            mWorker.shutdown();
            return false;
        }
        mSensor.start();
        return true;
    }

    /** Whether the sweep is still taking frames. */
    @AnyThread
    public boolean isRunning() {
        return mState.get() == RUNNING;
    }

    /**
     * Takes a frame that may be processed later, on this session's own thread, or dropped if the
     * last one is still being processed. For frames that arrive on the main thread and own their
     * bytes, which is how the legacy camera delivers them.
     */
    @AnyThread
    public void offerFrame(@NonNull PanoramaFrame frame) {
        if (mState.get() != RUNNING || !mBusy.compareAndSet(false, true)) {
            return;
        }
        try {
            mWorker.execute(() -> {
                try {
                    onFrame(frame);
                } finally {
                    mBusy.set(false);
                }
            });
        } catch (RejectedExecutionException ended) {
            // The sweep ended between the check above and now; the frame is not wanted.
            mBusy.set(false);
        }
    }

    /** Takes a frame, now. Its image need only stay open until this returns. */
    @WorkerThread
    public void onFrame(@NonNull PanoramaFrame frame) {
        if (mState.get() != RUNNING) {
            return;
        }
        synchronized (mLock) {
            if (mState.get() == RUNNING) {
                process(frame);
            }
        }
    }

    private void process(@NonNull PanoramaFrame frame) {
        long delay = SystemClock.elapsedRealtimeNanos() - frame.timestampNanos;
        if (delay >= 0 && delay < MAX_LATENCY_NANOS) {
            long latency = mLatencyNanos;
            mLatencyNanos = latency == 0 ? delay : (latency * 7 + delay) / 8;
        }

        float[] quaternion = new float[4];
        if (!mSensor.history.sample(frame.timestampNanos, quaternion)) {
            return;
        }
        float[] rotation = new float[9];
        SphereMath.rotationMatrix(quaternion, rotation);
        int step = frame.shrinkFactor(MAX_FRAME_SIDE);
        int width = frame.width / step;
        int height = frame.height / step;
        float focal = mFocalScale * CameraPose.focalLength(
                width, height, mSensorHorizontalFov, mSensorVerticalFov);
        if (focal <= 0f) {
            return;
        }
        CameraPose pose = new CameraPose(
                rotation, frame.rotationDegrees, frame.frontFacing, width, height, focal);
        if (mCanvas == null) {
            begin(pose);
        }
        SweepCoverage coverage = mCoverage;

        boolean tooFast =
                mSensor.history.speed(frame.timestampNanos) > MAX_TURN_DEGREES_PER_SECOND;
        boolean painted = false;
        if (!tooFast && isWorthPainting(pose, coverage)) {
            ArgbImage image = frame.toArgb(MAX_FRAME_SIDE, mScratch);
            paint(image, pose);
            if (!mCalibration.isDone()) {
                mUncalibrated.add(new KeptFrame(image, rotation, pose));
                mCalibration.offer(image, rotation, pose);
                if (mCalibration.isDone()) {
                    pose = recalibrate(pose, rotation);
                }
            }
            painted = true;
        }
        coverage = mCoverage;
        mNextTarget = coverage.nextTarget(pose.forward, mNextTarget);
        boolean complete = coverage.isComplete();
        mGuideState = guideState(pose,
                complete ? GuideState.COMPLETE : tooFast ? GuideState.TOO_FAST
                        : GuideState.SWEEPING);
        if (painted) {
            reportProgress(coverage.getProgress());
        }
        if (complete && mCompletionPosted.compareAndSet(false, true)) {
            mMainExecutor.execute(this::finish);
        }
    }

    /** Paints a frame and marks what it covered. */
    private void paint(@NonNull ArgbImage image, @NonNull CameraPose pose) {
        mCanvas.paint(image, pose);
        mCoverage.cover(pose);
        mPainted.add(pose.forward.clone());
        mCanvas.updateThumbnail(mThumbnail, THUMBNAIL_WIDTH, THUMBNAIL_WIDTH / 2);
        mPublishedThumbnail = mThumbnail.clone();
        mThumbnailVersion++;
        long now = System.currentTimeMillis();
        if (mFirstPhotoMillis == 0) {
            mFirstPhotoMillis = now;
        }
        mLastPhotoMillis = now;
    }

    /**
     * Applies what the calibration measured. If the lens differs from what it published, the
     * frames painted so far were each stretched about their own centres, so the sweep starts
     * again from them at the measured focal length.
     *
     * @return the newest frame's pose at the focal length now in use
     */
    @NonNull
    private CameraPose recalibrate(@NonNull CameraPose newest, @NonNull float[] rotation) {
        float scale = mCalibration.getScale();
        List<KeptFrame> kept = new ArrayList<>(mUncalibrated);
        mUncalibrated.clear();
        if (Math.abs(scale - 1f) <= RECALIBRATION_THRESHOLD) {
            return newest;
        }
        Log.i(TAG, "The lens's focal length is " + scale + " times what it publishes");
        mFocalScale = scale;
        mCanvas.release();
        mCanvas = null;
        mCoverage = null;
        mPainted.clear();
        mNextTarget = -1;
        mFirstPhotoMillis = 0;
        for (KeptFrame frame : kept) {
            CameraPose pose = new CameraPose(frame.rotation, frame.sensorRotation,
                    frame.frontFacing, frame.image.width, frame.image.height,
                    frame.focal * scale);
            if (mCanvas == null) {
                begin(pose);
            }
            paint(frame.image, pose);
        }
        return new CameraPose(rotation, newest.sensorRotation, newest.frontFacing,
                newest.width, newest.height, newest.focal * scale);
    }

    /** Sets the sweep up around the first frame: it is the middle of the picture. */
    private void begin(@NonNull CameraPose pose) {
        float pixelsPerDegree = Math.min(pose.focal * (float) Math.PI / 180f,
                (mPhotosphere ? PHOTOSPHERE_MAX_WIDTH : PANORAMA_MAX_WIDTH) / 360f);
        int width = 2 * Math.max(1, Math.round(180f * pixelsPerDegree));
        mStartYaw = pose.yaw();
        float pitch = pose.pitch();
        mBandPitch = Math.abs(pitch) < LEVEL_DEGREES ? 0f : pitch;
        mFrontFacing = pose.frontFacing;
        mUprightHorizontalFov = pose.uprightHorizontalFov();
        mUprightVerticalFov = pose.uprightVerticalFov();
        mCanvas = new SphereCanvas(width, mStartYaw);
        mCoverage = mPhotosphere
                ? SweepCoverage.forPhotosphere(mStartYaw, pose.narrowestFov())
                : SweepCoverage.forPanorama(mStartYaw, mBandPitch, mUprightHorizontalFov);
        mThumbnail = new int[THUMBNAIL_WIDTH * THUMBNAIL_WIDTH / 2];
    }

    private boolean isWorthPainting(@NonNull CameraPose pose, @NonNull SweepCoverage coverage) {
        float nearest = Float.MAX_VALUE;
        for (float[] painted : mPainted) {
            nearest = Math.min(nearest, SphereMath.angleBetween(painted, pose.forward));
        }
        if (nearest >= PAINT_SPACING * pose.narrowestFov()) {
            return true;
        }
        int fresh = coverage.countUncovered(pose);
        if (fresh == 0) {
            return false;
        }
        if (mPhotosphere && fresh >= MIN_FRESH_CELLS) {
            return true;
        }
        if (mNextTarget < 0) {
            return false;
        }
        float[] target = new float[3];
        SphereMath.direction(coverage.getTargetYaw(mNextTarget),
                coverage.getTargetPitch(mNextTarget), target);
        return SphereMath.angleBetween(target, pose.forward) <= ALIGNED_DEGREES;
    }

    @NonNull
    private GuideState guideState(@NonNull CameraPose pose, @GuideState.Status int status) {
        SweepCoverage coverage = mCoverage;
        int targets = coverage.getTargetCount();
        float[] yaws = new float[targets];
        float[] pitches = new float[targets];
        boolean[] done = new boolean[targets];
        for (int t = 0; t < targets; t++) {
            yaws[t] = coverage.getTargetYaw(t);
            pitches[t] = coverage.getTargetPitch(t);
            done[t] = coverage.isTargetComplete(t);
        }
        return new GuideState(mPhotosphere, status, coverage.getProgress(), pose.width,
                pose.height, pose.sensorRotation, pose.frontFacing, pose.focal, mStartYaw,
                mBandPitch, yaws, pitches, done,
                status == GuideState.SWEEPING || status == GuideState.TOO_FAST
                        ? mNextTarget : -1,
                mPublishedThumbnail, THUMBNAIL_WIDTH, mThumbnailVersion);
    }

    private void reportProgress(float progress) {
        if (progress - mReportedProgress < 0.005f && progress < 1f) {
            return;
        }
        mReportedProgress = progress;
        mMainExecutor.execute(() -> {
            if (mState.get() == RUNNING) {
                mCallback.onProgress(progress);
            }
        });
    }

    @Nullable
    @Override
    @AnyThread
    public GuideState getGuideState() {
        return mGuideState;
    }

    @Override
    @AnyThread
    public boolean getDisplayRotation(@NonNull float[] matrix) {
        float[] quaternion = new float[4];
        OrientationHistory history = mSensor.history;
        // The preview on screen is as far behind the sensor as the frames are, so the guide is
        // drawn for that moment and stays put on what it marks.
        long shown = SystemClock.elapsedRealtimeNanos() - mLatencyNanos;
        if (!history.sample(shown, quaternion) && !history.sample(history.newestTime(),
                quaternion)) {
            return false;
        }
        SphereMath.rotationMatrix(quaternion, matrix);
        return true;
    }

    /**
     * Ends the sweep and writes what it covered, however much that is: half a sweep is a
     * panorama of half a room, and the metadata says which half.
     */
    @UiThread
    public void finish() {
        if (!mState.compareAndSet(RUNNING, FINISHING)) {
            return;
        }
        mSensor.stop();
        mCallback.onFinishing();
        GuideState state = mGuideState;
        if (state != null) {
            mGuideState = state.withStatus(GuideState.SAVING);
        }
        mWorker.execute(this::save);
        mWorker.shutdown();
    }

    /** Ends the sweep and writes nothing, including a picture that was being written. */
    @UiThread
    public void cancel() {
        int previous = mState.getAndSet(DONE);
        if (previous == DONE) {
            return;
        }
        mSensor.stop();
        if (previous == RUNNING) {
            mWorker.execute(this::release);
            mWorker.shutdown();
        }
    }

    @WorkerThread
    private void release() {
        synchronized (mLock) {
            if (mCanvas != null) {
                mCanvas.release();
            }
        }
    }

    @WorkerThread
    private void save() {
        boolean saved = false;
        try {
            saved = write();
        } catch (IOException | RuntimeException | OutOfMemoryError error) {
            Log.e(TAG, "Failed to save the panorama", error);
        }
        boolean succeeded = saved;
        mMainExecutor.execute(() -> {
            if (!mState.compareAndSet(FINISHING, DONE)) {
                // Cancelled while it was being written.
                if (mFile.exists() && !mFile.delete()) {
                    Log.w(TAG, "Failed to remove a cancelled panorama");
                }
                return;
            }
            if (succeeded) {
                mCallback.onSaved(mFile);
            } else {
                mCallback.onFailed();
            }
        });
    }

    /** Writes the picture, its Exif and its photo sphere metadata. */
    @WorkerThread
    private boolean write() throws IOException {
        Bitmap bitmap;
        GPanoMetadata metadata;
        synchronized (mLock) {
            SphereCanvas canvas = mCanvas;
            if (canvas == null || canvas.getFramesPainted() == 0) {
                Log.w(TAG, "The sweep ended before a frame was painted");
                return false;
            }
            int bandRow = mPhotosphere ? -1
                    : Math.max(0, Math.min(canvas.height - 1, (int) canvas.yOf(mBandPitch)));
            int[] region = canvas.coveredRegion(bandRow);
            if (region == null) {
                return false;
            }
            int width = region[2];
            int height = region[3];
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                canvas.readRow(region[1] + y, region[0], width, row, 0, UNPAINTED);
                if (mFrontFacing) {
                    // The preview of a front lens is mirrored, and so is what it saves.
                    reverse(row);
                }
                bitmap.setPixels(row, 0, width, 0, y, width, 1);
            }
            metadata = metadata(canvas, region);
            canvas.release();
            mCanvas = null;
        }
        try (FileOutputStream out = new FileOutputStream(mFile)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, mQuality, out)) {
                return false;
            }
        } finally {
            bitmap.recycle();
        }
        Exif exif = new Exif(mFile);
        exif.attachTimestamp();
        Location location = mCallback.getLocation();
        if (location != null) {
            exif.attachLocation(location);
        }
        exif.save();
        metadata.writeTo(mFile);
        return true;
    }

    /**
     * The photo sphere metadata for a region of the canvas.
     *
     * <p>The picture is placed in the middle of the full panorama rather than where it sits on the
     * canvas: the canvas's left edge is just wherever the sweep started opposite, and a region
     * across it would otherwise have to run off the right of the full panorama and back in on the
     * left, which viewers do not follow. The heading says where the middle is instead.</p>
     */
    @NonNull
    private GPanoMetadata metadata(@NonNull SphereCanvas canvas, @NonNull int[] region) {
        int width = region[2];
        int height = region[3];
        GPanoMetadata metadata = new GPanoMetadata();
        metadata.usePanoramaViewer = true;
        metadata.captureSoftware = applicationName();
        metadata.stitchingSoftware = "CameraView";
        metadata.projectionType = GPanoMetadata.EQUIRECTANGULAR;
        metadata.croppedAreaImageWidthPixels = width;
        metadata.croppedAreaImageHeightPixels = height;
        metadata.fullPanoWidthPixels = canvas.width;
        metadata.fullPanoHeightPixels = canvas.height;
        metadata.croppedAreaLeftPixels = (canvas.width - width) / 2;
        metadata.croppedAreaTopPixels = region[1];

        float degreesPerPixel = 360f / canvas.width;
        float middleYaw = canvas.yawOrigin
                + (region[0] + width / 2f - canvas.width / 2f) * degreesPerPixel;
        // The middle of the full panorama, which is up to half a pixel from the picture's own.
        float fullMiddleYaw = middleYaw + (canvas.width / 2f
                - (metadata.croppedAreaLeftPixels + width / 2f)) * degreesPerPixel;
        float offset = mSensor.getHeadingOffset();
        if (!Float.isNaN(offset)) {
            metadata.poseHeadingDegrees = degrees360(fullMiddleYaw + offset);
            metadata.initialViewHeadingDegrees = Math.round(degrees360(middleYaw + offset)) % 360;
        }
        // The canvas is levelled by gravity, so the panorama is neither tipped nor rolled.
        metadata.posePitchDegrees = 0f;
        metadata.poseRollDegrees = 0f;
        metadata.initialViewPitchDegrees =
                Math.round(90f - (region[1] + height / 2f) * 180f / canvas.height);
        metadata.initialViewRollDegrees = 0;
        metadata.initialHorizontalFovDegrees = mUprightHorizontalFov;
        metadata.initialVerticalFovDegrees = mUprightVerticalFov;
        metadata.firstPhotoDate = mFirstPhotoMillis;
        metadata.lastPhotoDate = mLastPhotoMillis;
        metadata.sourcePhotosCount = canvas.getFramesPainted();
        metadata.exposureLockUsed = false;
        metadata.initialCameraDolly = 0f;
        return metadata;
    }

    @Nullable
    private String applicationName() {
        CharSequence label = mContext.getApplicationInfo().loadLabel(mContext.getPackageManager());
        return label == null ? null : label.toString();
    }

    /** A painted frame, kept until the lens is calibrated. */
    private static final class KeptFrame {
        /** The frame as it was painted. */
        final ArgbImage image;

        /** The device's rotation when it was taken. */
        final float[] rotation;

        /** The clockwise turn that stands it upright. */
        final int sensorRotation;

        /** Whether it came from a lens facing the user. */
        final boolean frontFacing;

        /** The focal length it was painted with, which is the published one. */
        final float focal;

        KeptFrame(@NonNull ArgbImage image, @NonNull float[] rotation, @NonNull CameraPose pose) {
            this.image = image;
            this.rotation = rotation.clone();
            sensorRotation = pose.sensorRotation;
            frontFacing = pose.frontFacing;
            focal = pose.focal;
        }
    }

    private static float degrees360(float degrees) {
        return ((degrees % 360f) + 360f) % 360f;
    }

    private static void reverse(@NonNull int[] row) {
        for (int i = 0, j = row.length - 1; i < j; i++, j--) {
            int swap = row[i];
            row[i] = row[j];
            row[j] = swap;
        }
    }
}
