package com.xlythe.view.camera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps the camera's focus and exposure on a subject, and reports where the subject goes.
 *
 * <p>The backend hands over preview frames on a background thread, where {@link SubjectTracker}
 * finds the subject in each. Everything that touches the camera or the app is posted to the main
 * thread, tagged with the request it belongs to, so a frame that was in flight when tracking
 * stopped or moved to a new subject is dropped rather than reported.</p>
 */
final class SubjectTracking implements LumaFrame.Listener {
    private static final String TAG = CameraView.TAG;

    /**
     * How long to wait for a frame before deciding the subject is gone. A session that stops
     * delivering frames would otherwise leave the app's box frozen over the preview.
     */
    static final long FRAME_TIMEOUT_MILLIS = 3000;

    /** The least time between two changes to the camera's regions, each of which costs a request. */
    static final long MIN_AREA_INTERVAL_MILLIS = 100;

    /** How far, in focus-area units across a range of 2000, the subject moves before the camera is told. */
    static final int MIN_AREA_SHIFT = 40;

    /** How much the subject's size changes, as a fraction, before the camera is told. */
    private static final float MIN_AREA_RESIZE = 0.15f;

    /** The view the subject is reported on, and whose listener hears about it. */
    private final CameraView mView;

    /** The backend that supplies the frames and takes the focus area. */
    private final ICameraModule mModule;

    /** Finds the subject in each frame. Touched only on the frame thread. */
    private final SubjectTracker mTracker = new SubjectTracker();

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /** Gives the subject up once frames have stopped arriving. */
    private final Runnable mFrameTimeout = this::onFrameTimeout;

    /** A tap waiting for the next frame to pick its subject from, or null for none. */
    private final AtomicReference<Request> mPendingRequest = new AtomicReference<>();

    /** Bumped on the main thread by every start and stop; results tagged otherwise are stale. */
    private volatile int mGeneration;

    /** The view's size and orientation, refreshed on the main thread for the frame thread to read. */
    private volatile Geometry mGeometry = new Geometry(0, 0, 0);

    /** Whether the backend is delivering frames. Main thread only. */
    private boolean mFramesRunning;

    /** Whether a subject is being followed, or waiting on a frame to be picked. Main thread only. */
    private boolean mTracking;

    /** The generation the frame thread's tracker belongs to. Frame thread only. */
    private int mTrackerGeneration;

    /** The last area the camera was told about, and when. Frame thread only. */
    @Nullable
    private Rect mLastArea;

    /** See {@link #mLastArea}. */
    private long mLastAreaMillis;

    SubjectTracking(@NonNull CameraView view, @NonNull ICameraModule module) {
        mView = view;
        mModule = module;
    }

    /**
     * Starts the frames tracking needs ahead of a tap, so the first one is answered at once.
     *
     * @return whether frames are running
     */
    @MainThread
    boolean prepare() {
        if (mFramesRunning) {
            return true;
        }
        if (!mModule.isSubjectTrackingSupported()) {
            return false;
        }
        mFramesRunning = startFrames();
        return mFramesRunning;
    }

    /**
     * Picks the subject under a point on the view, replacing any subject already followed.
     *
     * @return whether tracking started. False where this camera cannot track, in which case
     *     nothing will be reported.
     */
    @MainThread
    boolean start(float x, float y) {
        if (!prepare()) {
            return false;
        }
        mGeneration++;
        mTracking = true;
        refreshGeometry();
        mPendingRequest.set(new Request(x, y, mGeneration));
        mMainHandler.removeCallbacks(mFrameTimeout);
        mMainHandler.postDelayed(mFrameTimeout, FRAME_TIMEOUT_MILLIS);
        return true;
    }

    /**
     * Stops following the subject and gives focus back to the camera.
     *
     * @param reportLost whether to tell the app, which it needs whenever the stop was not its own
     */
    @MainThread
    void stop(boolean reportLost) {
        if (!mTracking) {
            return;
        }
        mGeneration++;
        mTracking = false;
        mPendingRequest.set(null);
        mMainHandler.removeCallbacks(mFrameTimeout);
        mModule.setSubjectArea(null);
        CameraView.SubjectTrackingListener listener = mView.getSubjectTrackingListener();
        if (reportLost && listener != null) {
            listener.onSubjectLost();
        }
    }

    /** Stops tracking, telling the app, and lets the backend stop producing frames for it. */
    @MainThread
    void release() {
        stop(true /* reportLost */);
        if (mFramesRunning) {
            mFramesRunning = false;
            mModule.stopLumaFrames();
        }
    }

    /** Whether a subject is being followed. */
    @MainThread
    boolean isTracking() {
        return mTracking;
    }

    /**
     * Suppressed because the permission is checked here, and lint cannot follow a check made
     * through {@link PermissionChecker}. Without it nothing starts.
     */
    @SuppressLint("MissingPermission")
    @MainThread
    private boolean startFrames() {
        if (!PermissionChecker.hasPermissions(mView.getContext(), Manifest.permission.CAMERA)) {
            return false;
        }
        return mModule.startLumaFrames(this);
    }

    @WorkerThread
    @Override
    public void onFrame(@NonNull LumaFrame frame) {
        Request request = mPendingRequest.getAndSet(null);
        if (request != null) {
            mTrackerGeneration = request.generation;
            Geometry geometry = mGeometry;
            float[] point = {request.x, request.y};
            Matrix viewToFrame = new Matrix();
            frameToView(frame, geometry).invert(viewToFrame);
            viewToFrame.mapPoints(point);
            mLastArea = null;
            if (!mTracker.start(frame.luma, frame.width, frame.height, frame.rowStride,
                    point[0], point[1])) {
                int generation = request.generation;
                mMainHandler.post(() -> onNothingToTrack(generation, request.x, request.y));
                return;
            }
            report(frame, geometry, mTracker.getBounds(), request.generation);
            return;
        }

        if (mGeneration != mTrackerGeneration) {
            mTracker.stop();
        }
        if (!mTracker.isTracking()) {
            return;
        }
        RectF bounds = mTracker.track(frame.luma, frame.width, frame.height, frame.rowStride);
        int generation = mTrackerGeneration;
        if (bounds == null) {
            mMainHandler.post(() -> onLost(generation));
            return;
        }
        report(frame, mGeometry, bounds, generation);
    }

    /** Hands a frame's result to the main thread, and moves the camera's regions if worth it. */
    @WorkerThread
    private void report(@NonNull LumaFrame frame, @NonNull Geometry geometry,
            @NonNull RectF bounds, int generation) {
        RectF onView = new RectF(bounds);
        frameToView(frame, geometry).mapRect(onView);
        Rect area = toFocusArea(bounds, frame.width, frame.height);
        long now = SystemClock.uptimeMillis();
        boolean moveArea = shouldMoveArea(mLastArea, area, now - mLastAreaMillis);
        if (moveArea) {
            mLastArea = area;
            mLastAreaMillis = now;
        }
        mMainHandler.post(() -> onTracked(generation, onView, moveArea ? area : null));
    }

    @MainThread
    private void onTracked(int generation, @NonNull RectF onView, @Nullable Rect area) {
        if (generation != mGeneration || !mTracking) {
            return;
        }
        refreshGeometry();
        mMainHandler.removeCallbacks(mFrameTimeout);
        mMainHandler.postDelayed(mFrameTimeout, FRAME_TIMEOUT_MILLIS);
        if (area != null) {
            mModule.setSubjectArea(area);
        }
        CameraView.SubjectTrackingListener listener = mView.getSubjectTrackingListener();
        if (listener != null) {
            listener.onSubjectTracked(onView);
        }
    }

    @MainThread
    private void onLost(int generation) {
        if (generation == mGeneration) {
            stop(true /* reportLost */);
        }
    }

    /**
     * Answers a tap on something flat with the focus the tap would have had anyway, so tapping a
     * blank wall still focuses on it.
     */
    @MainThread
    private void onNothingToTrack(int generation, float x, float y) {
        if (generation != mGeneration) {
            return;
        }
        stop(true /* reportLost */);
        mView.focusAt(x, y);
    }

    /**
     * Gives the subject up when frames stop, and restarts them, since a backend that stopped
     * feeding them may have dropped the stream they came from.
     */
    @MainThread
    private void onFrameTimeout() {
        Log.w(TAG, "Subject tracking stopped receiving frames");
        release();
        prepare();
    }

    @MainThread
    private void refreshGeometry() {
        mGeometry = new Geometry(mView.getWidth(), mView.getHeight(), mView.getDisplayRotation());
    }

    /**
     * Maps a frame onto the view, as the preview shows it.
     *
     * <p>A frame is in the sensor's orientation, so it is turned upright for the display's current
     * rotation and scaled to cover the view. A user-facing lens is shown mirrored, and a mirror
     * reverses which way the display's rotation turns the picture, hence the sum rather than the
     * difference for one.</p>
     */
    @VisibleForTesting
    @NonNull
    static Matrix frameToView(@NonNull LumaFrame frame, @NonNull Geometry geometry) {
        int rotation = frame.mirrored
                ? frame.sensorOrientation + geometry.displayRotation
                : frame.sensorOrientation - geometry.displayRotation;
        return TransformMath.centerCrop(new RectF(0, 0, frame.width, frame.height),
                TransformMath.within360(rotation), frame.mirrored,
                geometry.width, geometry.height);
    }

    /**
     * The area a camera focuses on for a subject at these bounds, from -1000 to 1000 across the
     * frame, which is the space {@link ICameraModule#focus} takes. Frames are in the sensor's
     * orientation and unmirrored, as that space is, so no turning is needed.
     */
    @VisibleForTesting
    @NonNull
    static Rect toFocusArea(@NonNull RectF bounds, int width, int height) {
        Rect area = new Rect(
                normalize(bounds.left, width),
                normalize(bounds.top, height),
                normalize(bounds.right, width),
                normalize(bounds.bottom, height));
        if (area.width() == 0) {
            area.right = Math.min(1000, area.left + 1);
            area.left = area.right - 1;
        }
        if (area.height() == 0) {
            area.bottom = Math.min(1000, area.top + 1);
            area.top = area.bottom - 1;
        }
        return area;
    }

    /**
     * Whether a new area is worth a request: each change rewrites the camera's repeating request
     * and can restart its focus search, so a subject standing still keeps the area it has.
     */
    @VisibleForTesting
    static boolean shouldMoveArea(@Nullable Rect last, @NonNull Rect next, long sinceLastMillis) {
        if (last == null) {
            return true;
        }
        if (sinceLastMillis < MIN_AREA_INTERVAL_MILLIS) {
            return false;
        }
        boolean moved = Math.abs(next.centerX() - last.centerX()) > MIN_AREA_SHIFT
                || Math.abs(next.centerY() - last.centerY()) > MIN_AREA_SHIFT;
        boolean resized = Math.abs(next.width() - last.width()) > last.width() * MIN_AREA_RESIZE
                || Math.abs(next.height() - last.height()) > last.height() * MIN_AREA_RESIZE;
        return moved || resized;
    }

    private static int normalize(float value, int size) {
        int normalized = Math.round(-1000 + 2000 * value / size);
        return Math.max(-1000, Math.min(1000, normalized));
    }

    /** A tap waiting for a frame. */
    private static final class Request {
        /** Where the tap landed, in view pixels. */
        final float x;

        /** See {@link #x}. */
        final float y;

        /** The start this tap belongs to. */
        final int generation;

        Request(float x, float y, int generation) {
            this.x = x;
            this.y = y;
            this.generation = generation;
        }
    }

    /** What the frame thread needs to know about the view to map a frame onto it. */
    @VisibleForTesting
    static final class Geometry {
        /** The view's size, in pixels. */
        final int width;

        /** See {@link #width}. */
        final int height;

        /** The display's rotation, in degrees. */
        final int displayRotation;

        Geometry(int width, int height, int displayRotation) {
            this.width = width;
            this.height = height;
            this.displayRotation = displayRotation;
        }
    }
}
