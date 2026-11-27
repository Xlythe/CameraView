package com.xlythe.view.camera.ar;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.media.Image;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.annotation.WorkerThread;

import com.google.ar.core.Anchor;
import com.google.ar.core.Camera;
import com.google.ar.core.CameraConfig;
import com.google.ar.core.CameraConfigFilter;
import com.google.ar.core.CameraIntrinsics;
import com.google.ar.core.Config;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Session;
import com.google.ar.core.SharedCamera;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.FatalException;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableException;
import com.xlythe.view.camera.ArAnchor;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.ICameraModule;
import com.xlythe.view.camera.gl.FrameGeometry;
import com.xlythe.view.camera.gl.FrameRenderThread;
import com.xlythe.view.camera.gl.GlMath;
import com.xlythe.view.camera.gl.OesTextureProgram;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An ARCore session sharing the camera, and everything it says about each frame.
 *
 * <p>Shared rather than exclusive, because the camera stays this library's: ARCore names the
 * camera, wraps the device and session callbacks, and hands over the surfaces it needs in the
 * capture session, and Camera2 does the rest. It copies each frame into the texture it is given
 * the name of, inside {@link Session#update()}, so it stands in for the renderer's own texture -
 * which is why this is the render thread's {@link FrameRenderThread.ExternalCameraTexture}. The
 * same update says where the camera is, so it is the {@link FrameRenderThread.GeometrySource}
 * too, and a frame is drawn with the pose it was tracked at.</p>
 *
 * <p>The camera's texture is described in ARCore's terms, by transforming points between
 * coordinate systems, and drawn in the renderer's, through a matrix shaped like the one a
 * SurfaceTexture reports. {@link ArMath} turns one into the other, with the turn the camera would
 * have asked for put back in, so a frame from ARCore is drawn exactly as one straight from the
 * camera is.</p>
 *
 * <p>Created, resumed, paused and closed on the main thread. Everything about a frame happens on
 * the render thread, inside {@link #onCameraFrameAvailable}.</p>
 */
@RequiresApi(24)
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class ArCoreSession implements ArTracker {
    /** Log tag shared with the camera modules. */
    private static final String TAG = ICameraModule.TAG;

    /** How long a hit test waits for the render thread to answer it. */
    private static final long HIT_TEST_TIMEOUT_MILLIS = 500;

    /** The texture's origin and the far end of each of its axes, in its own coordinates. */
    private static final float[] TEXTURE_CORNERS = {0f, 0f, 1f, 0f, 0f, 1f};

    /** The whole of an image, as {@link ArMath#textureBounds} gives an extent. */
    private static final float[] FULL_IMAGE = {0f, 0f, 1f, 1f};

    /** Work that needs the frame just tracked, run on the render thread after the update. */
    private interface FrameTask {
        @WorkerThread
        void run(@NonNull Frame frame);
    }

    /** An anchor ARCore tracks, and the handle the app holds on it. */
    private static final class Anchored {
        /** ARCore's anchor, detached once the handle is. */
        final Anchor mAnchor;

        /** What the app holds, told where the anchor is after every frame. */
        final ArAnchor mHandle;

        Anchored(@NonNull Anchor anchor, @NonNull ArAnchor handle) {
            mAnchor = anchor;
            mHandle = handle;
        }
    }

    /** The session. */
    private final Session mSession;

    /** ARCore's side of the camera, whose callbacks and surfaces the capture session takes. */
    private final SharedCamera mSharedCamera;

    /** The camera ARCore chose, which is the one to open. */
    private final String mCameraId;

    /** The size of the texture ARCore copies frames into, which is the size frames are drawn at. */
    private final Size mTextureSize;

    /** Whether this device measures depth, and so whether a frame can hide what is behind. */
    private final boolean mDepthSupported;

    /** The turn and mirror the camera would ask a SurfaceTexture for. */
    private final float[] mCameraRequest;

    /** Told how tracking is going, or null for nobody. */
    @Nullable
    private volatile Listener mListener;

    /** Work waiting for a frame. */
    private final ConcurrentLinkedQueue<FrameTask> mFrameTasks = new ConcurrentLinkedQueue<>();

    /** The render thread, which runs work against the frame it holds, or null before it starts. */
    @Nullable
    private volatile Handler mRenderHandler;

    /** The frame the render thread holds, valid until its next update, or null for none. */
    @Nullable
    private volatile Frame mFrame;

    /** ARCore's surfaces for the camera that is open, asked for once per open. Main thread. */
    @Nullable
    private List<Surface> mSurfaces;

    /** Whether ARCore is resumed. Main thread. */
    private boolean mResumed;

    // What the last frame said, all of it written and read on the render thread.

    /** Where the texture's corners last landed in the camera image. */
    private final float[] mCorners = new float[TEXTURE_CORNERS.length];

    /** {@link #mCorners} as of the last time the matrices were worked out from them. */
    private final float[] mLastCorners = new float[TEXTURE_CORNERS.length];

    /** The matrix to sample the texture with, shaped like a SurfaceTexture's. */
    private float[] mTextureMatrix;

    /** The buffer's intrinsics, as {@link GlMath#projection} takes them. */
    private float[] mIntrinsics = new float[GlMath.INTRINSICS_SIZE];

    /** Whether {@link #mIntrinsics} and {@link #mTextureMatrix} describe the camera. */
    private boolean mHasIntrinsics;

    /** Whether the last frame was tracked. */
    private boolean mTracking;

    /** The camera's pose in the world for the last tracked frame, camera-to-world. */
    private final float[] mPose = GlMath.identity();

    /** The world into the camera's space, for the last tracked frame. */
    private float[] mViewMatrix = GlMath.identity();

    /** When the last frame was captured, on the camera's clock. */
    private long mTimestampNanos;

    /** The texture the depth is uploaded into, or 0 before the first depth image. */
    private int mDepthTextureName;

    /** When the depth in {@link #mDepthTextureName} was measured, or 0 for never. */
    private long mDepthTimestampNanos;

    /** Depth rows packed tight, for an image whose rows are padded. */
    @Nullable
    private ByteBuffer mDepthRows;

    /** What tracking was last reported as, so only a change is reported. */
    @Nullable
    private CameraView.ArTracking mReported;

    /** Every anchor being followed. */
    private final List<Anchored> mAnchors = new ArrayList<>();

    /** Scratch for an anchor's pose. */
    private final float[] mAnchorPose = new float[GlMath.MATRIX_SIZE];

    private ArCoreSession(@NonNull Session session, @NonNull SharedCamera sharedCamera,
            @NonNull String cameraId, @NonNull Size textureSize, boolean depthSupported,
            int sensorOrientation) {
        mSession = session;
        mSharedCamera = sharedCamera;
        mCameraId = cameraId;
        mTextureSize = textureSize;
        mDepthSupported = depthSupported;
        mCameraRequest = GlMath.cameraRequest(sensorOrientation, false);
        // Until ARCore can say where its texture sits, it is taken to hold the whole image the
        // way every camera texture does: read out from the top, which GL counts from the bottom.
        mTextureMatrix = GlMath.multiply(
                ArMath.bufferToTexture(TEXTURE_CORNERS), mCameraRequest);
    }

    /**
     * Starts a session on the back camera, measuring depth where the device can, or returns null
     * where ARCore will not start one.
     *
     * <p>Null rather than an exception for every reason there is - ARCore missing, too old,
     * declined, or a device it does not support - because each is something the camera carries on
     * without, and the reason is logged.</p>
     */
    @MainThread
    @Nullable
    public static ArCoreSession create(@NonNull Context context) {
        Session session;
        try {
            session = new Session(context, EnumSet.of(Session.Feature.SHARED_CAMERA));
        } catch (FatalException | UnavailableDeviceNotCompatibleException error) {
            // ARCore said this device runs AR before it tried the device's camera. One it cannot
            // use is found out here, for good, so AR stops being offered.
            Log.w(TAG, "ARCore will not start a session on this device", error);
            ArSupport.onSessionRefused();
            return null;
        } catch (UnavailableException | RuntimeException | LinkageError error) {
            Log.w(TAG, "ARCore will not start a session here", error);
            return null;
        }
        try {
            List<CameraConfig> backConfigs = session.getSupportedCameraConfigs(
                    new CameraConfigFilter(session)
                            .setFacingDirection(CameraConfig.FacingDirection.BACK));
            if (!backConfigs.isEmpty()) {
                // ARCore lists its preferred configuration first.
                session.setCameraConfig(backConfigs.get(0));
            }
            Config config = new Config(session);
            config.setFocusMode(Config.FocusMode.AUTO);
            // The renderer asks for a frame when the camera delivers one, so ARCore has no reason
            // to block it waiting for the next.
            config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
            config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL);
            config.setLightEstimationMode(Config.LightEstimationMode.DISABLED);
            boolean depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC);
            if (depthSupported) {
                config.setDepthMode(Config.DepthMode.AUTOMATIC);
            }
            session.configure(config);
            // Nothing here is drawn in ARCore's view coordinates, but its transforms between
            // coordinate systems answer nothing until it has been told of a display.
            session.setDisplayGeometry(Surface.ROTATION_0, 1080, 1920);

            CameraConfig cameraConfig = session.getCameraConfig();
            String cameraId = cameraConfig.getCameraId();
            SharedCamera sharedCamera = session.getSharedCamera();
            // An empty list, said rather than left unsaid: ARCore picks what to ask of the camera
            // from every surface the capture session will hold.
            sharedCamera.setAppSurfaces(cameraId, Collections.emptyList());
            CameraManager manager =
                    (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            Integer orientation = manager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SENSOR_ORIENTATION);
            Log.i(TAG, "ARCore shares camera " + cameraId + " at " + cameraConfig.getTextureSize()
                    + (depthSupported ? ", measuring depth" : ", without depth"));
            return new ArCoreSession(session, sharedCamera, cameraId,
                    cameraConfig.getTextureSize(), depthSupported,
                    orientation == null ? 90 : orientation);
        } catch (android.hardware.camera2.CameraAccessException | RuntimeException
                | LinkageError error) {
            Log.w(TAG, "ARCore will not start a session here", error);
            session.close();
            return null;
        }
    }

    @MainThread
    @Override
    public void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    @NonNull
    @Override
    public String getCameraId() {
        return mCameraId;
    }

    @NonNull
    @Override
    public Size getTextureSize() {
        return mTextureSize;
    }

    @Override
    public boolean isDepthSupported() {
        return mDepthSupported;
    }

    @NonNull
    @Override
    public CameraDevice.StateCallback wrap(@NonNull CameraDevice.StateCallback callback,
            @NonNull Handler handler) {
        mSurfaces = null;
        return mSharedCamera.createARDeviceStateCallback(callback, handler);
    }

    @NonNull
    @Override
    public CameraCaptureSession.StateCallback wrap(
            @NonNull CameraCaptureSession.StateCallback callback, @NonNull Handler handler) {
        return mSharedCamera.createARSessionStateCallback(callback, handler);
    }

    /**
     * The surfaces ARCore needs in the capture session, asked for once per open camera.
     *
     * <p>Only once the camera is open: ARCore builds them from the device, and asking any earlier
     * aborts the process from native code. And only once per device, because asking again builds
     * a second set - a session configured with one and a request aimed at the other runs, and
     * tracks nothing.</p>
     */
    @MainThread
    @NonNull
    @Override
    public List<Surface> getSurfaces() {
        List<Surface> surfaces = mSurfaces;
        if (surfaces == null) {
            surfaces = Collections.unmodifiableList(
                    new ArrayList<>(mSharedCamera.getArCoreSurfaces()));
            mSurfaces = surfaces;
        }
        return surfaces;
    }

    @MainThread
    @Override
    public void setCaptureCallback(@NonNull CameraCaptureSession.CaptureCallback callback,
            @NonNull Handler handler) {
        mSharedCamera.setCaptureCallback(callback, handler);
    }

    /**
     * Starts tracking, once the capture session is active: ARCore follows the requests a session
     * is already making and will not resume without one.
     */
    @MainThread
    @Override
    public boolean resume() {
        if (mResumed) {
            return true;
        }
        try {
            mSession.resume();
            mResumed = true;
            return true;
        } catch (CameraNotAvailableException | RuntimeException error) {
            Log.w(TAG, "ARCore would not resume", error);
            return false;
        }
    }

    @MainThread
    @Override
    public boolean isResumed() {
        return mResumed;
    }

    @MainThread
    @Override
    public void pause() {
        if (!mResumed) {
            return;
        }
        mResumed = false;
        mFrame = null;
        try {
            mSession.pause();
        } catch (RuntimeException error) {
            Log.w(TAG, "ARCore would not pause", error);
        }
    }

    @MainThread
    @Override
    public void close() {
        pause();
        for (Anchored anchored : mAnchors) {
            anchored.mHandle.detach();
        }
        mAnchors.clear();
        try {
            mSession.close();
        } catch (RuntimeException error) {
            Log.w(TAG, "ARCore would not close", error);
        }
    }

    /**
     * Places an anchor on the first surface ARCore finds through a point of the buffer, in the
     * frame the render thread holds, or in the next one if it holds none yet.
     *
     * <p>Waits for the render thread, because a frame is only valid there, until the next update,
     * and a hit test needs one. It does not wait for a new frame: the camera's capture results
     * reach ARCore through the main thread, so none arrives while the main thread waits.</p>
     *
     * @param s across the buffer from its left, from 0 to 1
     * @param t up the buffer from its bottom, from 0 to 1
     * @return the anchor, or null where nothing was hit, AR is not tracking, or no frame came
     */
    @MainThread
    @Nullable
    @Override
    public ArAnchor hitTest(float s, float t) {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ArAnchor> result = new AtomicReference<>();
        AtomicBoolean abandoned = new AtomicBoolean();
        mFrameTasks.add(frame -> {
            try {
                ArAnchor anchor = hitTest(frame, s, t);
                if (anchor != null && abandoned.get()) {
                    // Nobody is waiting for it any more, so nobody could ever detach it.
                    anchor.detach();
                }
                result.set(anchor);
            } finally {
                done.countDown();
            }
        });
        Handler renderer = mRenderHandler;
        if (renderer != null) {
            renderer.post(this::runFrameTasksOnHeldFrame);
        }
        try {
            if (!done.await(HIT_TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                abandoned.set(true);
                Log.w(TAG, "No frame arrived to place an anchor in");
                return null;
            }
        } catch (InterruptedException error) {
            abandoned.set(true);
            Thread.currentThread().interrupt();
            return null;
        }
        return result.get();
    }

    @WorkerThread
    @Nullable
    private ArAnchor hitTest(@NonNull Frame frame, float s, float t) {
        if (!mTracking || !mHasIntrinsics) {
            return null;
        }
        float[] ray = ArMath.worldRay(mIntrinsics, mPose, s, t);
        for (HitResult hit : frame.hitTest(ray, 0, ray, 3)) {
            Trackable trackable = hit.getTrackable();
            if (trackable.getTrackingState() != TrackingState.TRACKING) {
                continue;
            }
            if (trackable instanceof Plane
                    && !((Plane) trackable).isPoseInPolygon(hit.getHitPose())) {
                // Past the edge of what has been seen of the plane, where it may not continue.
                continue;
            }
            ArAnchor handle = new ArAnchor();
            Anchored anchored = new Anchored(hit.createAnchor(), handle);
            mAnchors.add(anchored);
            follow(anchored);
            return handle;
        }
        return null;
    }

    @WorkerThread
    @Override
    public int onCreateCameraTexture() {
        int textureName = OesTextureProgram.newExternalTexture();
        mSession.setCameraTextureName(textureName);
        Looper looper = Looper.myLooper();
        mRenderHandler = looper == null ? null : new Handler(looper);
        return textureName;
    }

    /**
     * Advances tracking, which is also what puts the frame on the texture.
     *
     * @return false for an update that brought no new frame, which happens before ARCore resumes,
     *     as it pauses, and when it was told of a capture before the frame arrived
     */
    @WorkerThread
    @Override
    public boolean advance() {
        Frame frame;
        try {
            frame = mSession.update();
        } catch (CameraNotAvailableException | RuntimeException error) {
            mFrame = null;
            // Paused, which an update racing a pause on the main thread meets.
            if (ICameraModule.DEBUG) Log.d(TAG, "ARCore has no frame to give", error);
            return false;
        }
        mFrame = frame;
        try {
            long timestampNanos = frame.getTimestamp();
            if (timestampNanos == 0 || timestampNanos == mTimestampNanos) {
                // A frame of nothing, which ARCore gives before the camera's first.
                return false;
            }
            mTimestampNanos = timestampNanos;
            Camera camera = frame.getCamera();
            readTexture(frame, camera);
            mTracking = camera.getTrackingState() == TrackingState.TRACKING;
            if (mTracking) {
                camera.getPose().toMatrix(mPose, 0);
                mViewMatrix = ArMath.invertRigid(mPose);
                readDepth(frame);
            }
            followAnchors();
            report(trackingOf(camera));
            return true;
        } finally {
            runFrameTasks(frame);
        }
    }

    /** Runs the work waiting for a frame against the one the render thread holds, if any. */
    @WorkerThread
    private void runFrameTasksOnHeldFrame() {
        Frame frame = mFrame;
        if (frame != null) {
            runFrameTasks(frame);
        }
    }

    @WorkerThread
    private void runFrameTasks(@NonNull Frame frame) {
        FrameTask task;
        while ((task = mFrameTasks.poll()) != null) {
            try {
                task.run(frame);
            } catch (RuntimeException error) {
                Log.w(TAG, "Work for an AR frame failed", error);
            }
        }
    }

    /**
     * Works out the texture's matrix and the buffer's intrinsics from where the texture sits in
     * the camera image, which is what ARCore's pose and intrinsics describe.
     */
    @WorkerThread
    private void readTexture(@NonNull Frame frame, @NonNull Camera camera) {
        try {
            frame.transformCoordinates2d(Coordinates2d.TEXTURE_NORMALIZED, TEXTURE_CORNERS,
                    Coordinates2d.IMAGE_NORMALIZED, mCorners);
        } catch (RuntimeException error) {
            // Not every ARCore can relate the two, and the texture's own intrinsics serve.
            Arrays.fill(mCorners, Float.NaN);
        }
        if (mHasIntrinsics && Arrays.equals(mCorners, mLastCorners)) {
            return;
        }
        try {
            if (isFinite(mCorners)) {
                // Where the texture sits in the image ARCore tracks with, which says which way
                // round it holds the frame as well as how much of it.
                float[] bounds = ArMath.textureBounds(mCorners);
                CameraIntrinsics image = camera.getImageIntrinsics();
                float[] focal = image.getFocalLength();
                float[] principal = image.getPrincipalPoint();
                int[] dimensions = image.getImageDimensions();
                mIntrinsics = ArMath.bufferIntrinsics(focal[0], focal[1], principal[0],
                        principal[1], dimensions[0], dimensions[1], bounds);
                mTextureMatrix =
                        GlMath.multiply(ArMath.bufferToTexture(mCorners), mCameraRequest);
            } else {
                // Unrelated to the image until tracking starts, so the texture's own intrinsics,
                // for a texture held the way the camera reads its frames out.
                CameraIntrinsics texture = camera.getTextureIntrinsics();
                float[] focal = texture.getFocalLength();
                float[] principal = texture.getPrincipalPoint();
                int[] dimensions = texture.getImageDimensions();
                mIntrinsics = ArMath.bufferIntrinsics(focal[0], focal[1], principal[0],
                        principal[1], dimensions[0], dimensions[1], FULL_IMAGE);
            }
            System.arraycopy(mCorners, 0, mLastCorners, 0, mCorners.length);
            mHasIntrinsics = true;
        } catch (IllegalArgumentException error) {
            Log.w(TAG, "ARCore described its camera in a way that cannot be drawn", error);
        }
    }

    /** Whether every value is a number, which ARCore's transforms are not before they can be. */
    private static boolean isFinite(@NonNull float[] values) {
        for (float value : values) {
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Uploads the newest depth image, as two bytes a texel, low then high.
     *
     * <p>Luminance and alpha rather than a 16-bit format, because every GL ES 2 device has those
     * and a shader puts the two bytes back together in one dot product. Nearest rather than linear
     * sampling, because blending the low byte of one texel into the next is not an average of
     * their distances.</p>
     */
    @WorkerThread
    private void readDepth(@NonNull Frame frame) {
        if (!mDepthSupported) {
            return;
        }
        Image depth;
        try {
            depth = frame.acquireDepthImage16Bits();
        } catch (NotYetAvailableException error) {
            // Too early, or the phone has not moved enough to measure anything yet.
            return;
        } catch (RuntimeException error) {
            Log.w(TAG, "ARCore would not hand over depth", error);
            return;
        }
        try {
            if (depth.getTimestamp() == mDepthTimestampNanos) {
                return;
            }
            Image.Plane plane = depth.getPlanes()[0];
            int width = depth.getWidth();
            int height = depth.getHeight();
            ByteBuffer pixels = packRows(plane, width, height);
            if (mDepthTextureName == 0) {
                int[] names = new int[1];
                GLES20.glGenTextures(1, names, 0);
                mDepthTextureName = names[0];
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mDepthTextureName);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                        GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                        GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                        GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                        GLES20.GL_CLAMP_TO_EDGE);
            } else {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mDepthTextureName);
            }
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 2);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE_ALPHA, width,
                    height, 0, GLES20.GL_LUMINANCE_ALPHA, GLES20.GL_UNSIGNED_BYTE, pixels);
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            mDepthTimestampNanos = depth.getTimestamp();
        } finally {
            depth.close();
        }
    }

    /** The plane's pixels with no padding between rows, as GL reads them. */
    @WorkerThread
    @NonNull
    private ByteBuffer packRows(@NonNull Image.Plane plane, int width, int height) {
        ByteBuffer source = plane.getBuffer();
        int rowBytes = width * 2;
        int rowStride = plane.getRowStride();
        if (rowStride == rowBytes && plane.getPixelStride() == 2) {
            source.position(0);
            return source;
        }
        ByteBuffer rows = mDepthRows;
        if (rows == null || rows.capacity() < rowBytes * height) {
            rows = ByteBuffer.allocateDirect(rowBytes * height).order(ByteOrder.nativeOrder());
            mDepthRows = rows;
        }
        rows.clear();
        for (int row = 0; row < height; row++) {
            source.limit(Math.min(source.capacity(), row * rowStride + rowBytes));
            source.position(row * rowStride);
            rows.put(source);
        }
        rows.flip();
        return rows;
    }

    /** Tells every handle where its anchor is now, and lets go of the ones that are done. */
    @WorkerThread
    private void followAnchors() {
        Iterator<Anchored> anchors = mAnchors.iterator();
        while (anchors.hasNext()) {
            Anchored anchored = anchors.next();
            if (anchored.mHandle.isDetached()
                    || anchored.mAnchor.getTrackingState() == TrackingState.STOPPED) {
                anchored.mAnchor.detach();
                anchored.mHandle.detach();
                anchors.remove();
                continue;
            }
            follow(anchored);
        }
    }

    @WorkerThread
    private void follow(@NonNull Anchored anchored) {
        if (mTracking && anchored.mAnchor.getTrackingState() == TrackingState.TRACKING) {
            anchored.mAnchor.getPose().toMatrix(mAnchorPose, 0);
            anchored.mHandle.onPose(mAnchorPose);
        } else {
            anchored.mHandle.onPose(null);
        }
    }

    @WorkerThread
    private void report(@NonNull CameraView.ArTracking tracking) {
        if (tracking == mReported) {
            return;
        }
        mReported = tracking;
        Listener listener = mListener;
        if (listener != null) {
            listener.onTrackingChanged(tracking);
        }
    }

    /** What a camera's tracking state comes to, in the terms an app shows someone. */
    @NonNull
    static CameraView.ArTracking trackingOf(@NonNull Camera camera) {
        TrackingState state = camera.getTrackingState();
        if (state == TrackingState.TRACKING) {
            return CameraView.ArTracking.TRACKING;
        }
        if (state == TrackingState.STOPPED) {
            return CameraView.ArTracking.STOPPED;
        }
        switch (camera.getTrackingFailureReason()) {
            case INSUFFICIENT_LIGHT:
                return CameraView.ArTracking.INSUFFICIENT_LIGHT;
            case EXCESSIVE_MOTION:
                return CameraView.ArTracking.EXCESSIVE_MOTION;
            case INSUFFICIENT_FEATURES:
                return CameraView.ArTracking.INSUFFICIENT_FEATURES;
            default:
                return CameraView.ArTracking.INITIALIZING;
        }
    }

    @WorkerThread
    @Override
    public void getTransformMatrix(@NonNull float[] into) {
        System.arraycopy(mTextureMatrix, 0, into, 0, GlMath.MATRIX_SIZE);
    }

    @WorkerThread
    @Override
    public long getTimestampNanos() {
        return mTimestampNanos;
    }

    @WorkerThread
    @Override
    public void onFrameGeometry(@NonNull FrameGeometry geometry, @Nullable Size bufferSize) {
        if (mHasIntrinsics) {
            geometry.setNormalizedIntrinsics(mIntrinsics);
        }
        if (mTracking) {
            geometry.setViewMatrix(mViewMatrix);
            if (mDepthTimestampNanos != 0) {
                geometry.setDepthTextureName(mDepthTextureName);
            }
        }
    }

    @WorkerThread
    @Override
    public void onReleaseCameraTexture(int textureName) {
        mRenderHandler = null;
        mFrame = null;
        int[] names = mDepthTextureName == 0
                ? new int[] {textureName} : new int[] {textureName, mDepthTextureName};
        GLES20.glDeleteTextures(names.length, names, 0);
        mDepthTextureName = 0;
        mDepthTimestampNanos = 0;
        mHasIntrinsics = false;
        mTracking = false;
        mReported = null;
    }
}
