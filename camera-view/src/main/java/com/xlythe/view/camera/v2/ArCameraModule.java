package com.xlythe.view.camera.v2;

import android.Manifest;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.opengl.GLES20;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;

import com.google.ar.core.Coordinates2d;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.ar.ArAvailability;
import com.xlythe.view.camera.ar.ArCoreFrame;
import com.xlythe.view.camera.ar.ArFrameListener;
import com.xlythe.view.camera.ar.ArSession;
import com.xlythe.view.camera.ar.ArTrackingFailure;
import com.xlythe.view.camera.gl.CameraTextureProvider;
import com.xlythe.view.camera.gl.FrameRenderThread;
import com.xlythe.view.camera.gl.GlMath;
import com.xlythe.view.camera.gl.OesTextureProgram;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.xlythe.view.camera.ICameraModule.DEBUG;
import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * Camera2, with ARCore tracking the world through the same camera.
 *
 * <p>Built on {@link Camera2Module} rather than beside it because ARCore has no CameraX path: a
 * shared-camera session hands over Camera2 surfaces and wraps Camera2 callbacks, and every line
 * of its reference does the same. What is different here is only the four things ARCore
 * insists on - the camera it chose, its callbacks wrapped around ours, its surfaces in the
 * session, and a capture request built from {@code TEMPLATE_RECORD} - so those are the four
 * seams this overrides and nothing else.</p>
 *
 * <h2>The camera frame goes through GL</h2>
 *
 * <p>ARCore reads the camera out of a texture that the caller creates, so the frames cannot go
 * straight from the camera to the preview the way the plain Camera2 path sends them: they land
 * on a texture ARCore consumes, and are drawn from there onto the preview. That is what
 * {@link FrameRenderThread} already does for the CameraX effect path, so it is reused as a
 * pass-through - the preview still gets the frame at the same size, oriented by the same view
 * transform as before, and the only thing added is the one draw that lets ARCore see it.</p>
 *
 * <h2>What AR mode does not do</h2>
 *
 * <p>Stills and recordings are not available while AR is running. Both work by swapping the
 * capture session for one built around an encoder or a reader, and a shared-camera session
 * cannot be swapped without stopping tracking - so rather than drop tracking silently, they
 * report as unsupported. An app that needs both offers AR as a mode and leaves it.</p>
 *
 * <h2>Falling back</h2>
 *
 * <p>A device that cannot start a session - no ARCore, an old ARCore, a manifest with no AR
 * declaration - gets plain Camera2 and a warning rather than a camera that does not open. The
 * camera is the point; AR is the extra, and {@link #isArActive()} is how an app asks which it
 * got.</p>
 */
@RequiresApi(24)
public class ArCameraModule extends Camera2Module {
    /**
     * The preview cannot usefully be larger than this, and ARCore will not run above it.
     *
     * <p>ARCore publishes camera configurations of its own and tracking is tuned for them;
     * 1080p is the largest any of them uses, and a preview above it costs frames in the one
     * pipeline that has to keep up with tracking.</p>
     */
    private static final Size MAX_PREVIEW_SIZE = new Size(1920, 1080);

    /** Key the preview is registered under with the render thread. */
    private final Object mPreviewOutputKey = new Object();

    private final FrameRenderThread mRenderThread = new FrameRenderThread("CameraViewArRenderer");

    /** Reused for every frame, because this runs on the render thread. */
    private final ArCoreFrame mFrame = new ArCoreFrame();

    /**
     * The corners of the quad the camera frame is drawn on, as normalized device coordinates.
     *
     * <p>In the order the quad declares them - bottom left, bottom right, top left - so that
     * what comes back lines up with the texture coordinates the shader is given.</p>
     */
    private static final float[] QUAD_CORNERS_NDC = {-1f, -1f, 1f, -1f, -1f, 1f};

    /** Where those corners land on the camera texture, filled in once per frame. */
    private final float[] mQuadCornersTexture = new float[QUAD_CORNERS_NDC.length];

    /**
     * How to sample the camera texture, as ARCore says to.
     *
     * <p>Written on the render thread and read there too, but under a lock all the same: the
     * preview output reads it from the draw that a capture callback asked for, and the two are
     * not the same post.</p>
     */
    private final float[] mTextureMatrix = GlMath.identity();

    @Nullable
    private ArSession mArSession;

    /** The size the camera is writing, which the preview is drawn from. */
    @Nullable
    private Size mPreviewSize;

    @Nullable
    private volatile ArFrameListener mArFrameListener;

    /** ARCore's own surfaces, which must be asked for once and reused. */
    @Nullable
    private List<Surface> mArSurfaces;

    /** Whether tracking is running, so that resuming twice does not throw. */
    private boolean mArResumed;

    /** Why the last frame was not tracked, or null when it was, so a reason is reported once. */
    @Nullable
    private ArTrackingFailure mFailure;


    public ArCameraModule(CameraView cameraView) {
        super(cameraView);
    }

    /**
     * AR while a session is running, and Camera2 once it has fallen back.
     *
     * <p>Which is the whole reason the backend is asked rather than inferred: this class is a
     * Camera2 module either way, and an app offering an AR mode needs to know whether it got
     * one.</p>
     */
    @NonNull
    @Override
    public CameraView.Backend getBackend() {
        return isArActive() ? CameraView.Backend.AR : CameraView.Backend.CAMERA2;
    }

    /**
     * No, because this backend is already drawing the frames.
     *
     * <p>ARCore owns the texture the camera is read into and this module draws from it, so a
     * second pipeline would be a second render thread competing for the same frames. An app in
     * AR mode draws onto the world through {@link #setArFrameListener} and its own overlay,
     * which is the arrangement AR is for.</p>
     */
    @Override
    public boolean isFrameEffectSupported() {
        return false;
    }

    /** What to tell about every tracked frame, or null for nothing. */
    @Override
    public void setArFrameListener(@Nullable ArFrameListener listener) {
        mArFrameListener = listener;
    }

    @Nullable
    @Override
    public ArFrameListener getArFrameListener() {
        return mArFrameListener;
    }

    /** Whether AR is actually running, as opposed to this having fallen back to Camera2. */
    public boolean isArActive() {
        return mArSession != null;
    }

    /**
     * Brings up ARCore and the render pipeline, then opens the camera.
     *
     * <p>In that order, and the order is the contract. ARCore has to exist before the camera
     * opens, because it names the camera to open and has to be given the surfaces the app will
     * add before the device exists; and its own surfaces can only be asked for afterwards,
     * which is why they are collected in the session rather than here.</p>
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    @Override
    public void open() {
        if (mArSession == null) {
            startAr();
        }
        super.open();
    }

    @Override
    public void close() {
        super.close();
        stopAr();
    }

    /**
     * Starts a session, or leaves AR off.
     *
     * <p>Everything here is a reason to carry on without AR rather than to fail: a device with
     * no ARCore, a driver that will not give us a GL context, a camera whose sizes cannot be
     * read. Each leaves {@link #isArActive()} false and the camera working.</p>
     */
    private void startAr() {
        ArAvailability.State state = ArAvailability.check(getContext());
        if (state == ArAvailability.State.UNSUPPORTED) {
            // Not UNKNOWN or NEEDS_INSTALL: those may change, and an app that asked for AR
            // deserves the attempt. Only a definite no is worth not trying.
            Log.w(TAG, "AR was asked for but this device cannot do it; using Camera2");
            return;
        }
        ArSession session = ArSession.create(getContext());
        if (session == null) {
            Log.w(TAG, "AR was asked for but no session could be started; using Camera2");
            return;
        }
        Size previewSize = chooseArPreviewSize(session.getCameraId());
        if (previewSize == null) {
            Log.w(TAG, "AR was asked for but its camera reports no preview sizes; using Camera2");
            session.close();
            return;
        }
        mRenderThread.setCameraTextureProvider(new ArTextureProvider(session));
        if (!mRenderThread.start()) {
            Log.w(TAG, "AR was asked for but GL is unavailable on this device; using Camera2");
            session.close();
            return;
        }
        try {
            // An empty list, and said rather than skipped: ARCore works out the camera
            // configuration to ask the device for from the whole surface list, and telling it
            // there are none of ours is how it knows the session is two surfaces rather than
            // three. On this phone three is one more than the scaler has, and the camera
            // reports it by starving one of them rather than by refusing the session.
            session.getSharedCamera().setAppSurfaces(
                    session.getCameraId(), Collections.emptyList());
        } catch (RuntimeException error) {
            Log.w(TAG, "AR was asked for but its camera refused the surface list; using Camera2",
                    error);
            mRenderThread.stop();
            session.close();
            return;
        }
        mArSession = session;
        mPreviewSize = previewSize;
        updateDisplayGeometry(session);
        if (DEBUG) {
            Log.d(TAG, "AR session on camera " + session.getCameraId() + " at " + previewSize
                    + ", depth " + session.isDepthSupported());
        }
    }

    private void stopAr() {
        ArSession session = mArSession;
        mArSession = null;
        mPreviewSize = null;
        mArSurfaces = null;
        mArResumed = false;
        mFailure = null;
        if (session == null) {
            return;
        }
        session.pause();
        // Before the session closes, because the renderer is still asking it for frames, and
        // it blocks until the render thread has stopped.
        mRenderThread.removeOutput(mPreviewOutputKey);
        mRenderThread.stop();
        session.close();
    }

    /**
     * The largest size the camera and ARCore will both accept, up to 1080p.
     *
     * <p>Chosen from the camera rather than from the view, because this one stream feeds both
     * the preview and tracking: a size picked for a small view would hand ARCore less of the
     * scene than it could have had.</p>
     */
    @Nullable
    private Size chooseArPreviewSize(@NonNull String cameraId) {
        try {
            StreamConfigurationMap map = getCameraManager().getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                return null;
            }
            Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
            if (sizes == null) {
                return null;
            }
            Size best = null;
            for (Size size : sizes) {
                if (size.getWidth() > MAX_PREVIEW_SIZE.getWidth()
                        || size.getHeight() > MAX_PREVIEW_SIZE.getHeight()) {
                    continue;
                }
                if (best == null
                        || (long) size.getWidth() * size.getHeight()
                                > (long) best.getWidth() * best.getHeight()) {
                    best = size;
                }
            }
            return best;
        } catch (CameraAccessException | RuntimeException error) {
            Log.w(TAG, "Unable to read the sizes the AR camera supports", error);
            return null;
        }
    }

    /**
     * The display rotation as a {@link Surface} constant, which is what ARCore takes.
     *
     * <p>Derived from {@link #getDisplayRotation()} rather than read again, because that one
     * reports degrees - it is what the rest of the library works in - and handing 90 to a method
     * expecting ROTATION_90 is a quarter turn of everything drawn, with nothing to say so.</p>
     */
    private int getDisplaySurfaceRotation() {
        switch (getDisplayRotation()) {
            case 90:
                return Surface.ROTATION_90;
            case 180:
                return Surface.ROTATION_180;
            case 270:
                return Surface.ROTATION_270;
            default:
                return Surface.ROTATION_0;
        }
    }

    /**
     * Tells the session the shape of the view, which is the space poses come back in.
     *
     * <p>The view rather than the camera frame, so that everything an overlay is handed - the
     * projection, a hit test, the depth lookup - is already in the coordinates it draws in. The
     * two differ whenever the preview is cropped to fit, and both crop centrally to the shape of
     * the view, so they agree about what is on screen.</p>
     */
    private void updateDisplayGeometry(@NonNull ArSession session) {
        int width = getWidth();
        int height = getHeight();
        if (width > 0 && height > 0) {
            session.setDisplayGeometry(getDisplaySurfaceRotation(), width, height);
        }
    }

    // The four seams ARCore needs, each one unchanged when AR is not running.

    @NonNull
    @Override
    String getActiveCamera() throws CameraAccessException {
        ArSession session = mArSession;
        return session == null ? super.getActiveCamera() : session.getCameraId();
    }

    @NonNull
    @Override
    CameraDevice.StateCallback onOpeningCamera(@NonNull CameraDevice.StateCallback callback) {
        ArSession session = mArSession;
        return session == null ? callback
                : session.getSharedCamera().createARDeviceStateCallback(callback, getHandler());
    }

    @NonNull
    @Override
    CameraCaptureSession.StateCallback onConfiguringSession(
            @NonNull CameraCaptureSession.StateCallback callback) {
        ArSession session = mArSession;
        return session == null ? callback
                : session.getSharedCamera().createARSessionStateCallback(callback, getHandler());
    }

    @NonNull
    @Override
    Session createIdleSession() {
        Size previewSize = mPreviewSize;
        if (mArSession == null || previewSize == null) {
            return super.createIdleSession();
        }
        return new ArCaptureSession(this, previewSize);
    }

    /**
     * The surfaces ARCore needs in the session, asked for once and then remembered.
     *
     * <p>Once, and that is the point of caching them. ARCore builds these readers from the open
     * camera device, and asking twice is asking it to build them twice - so the session would be
     * configured with one set and the repeating request would target another. The camera accepts
     * that: the preview keeps running, because the texture is shared either way, and only the
     * motion-tracking reader is left receiving nothing, so the symptom is a session that tracks
     * nothing for no stated reason.</p>
     */
    @NonNull
    List<Surface> getArSurfaces() {
        ArSession session = mArSession;
        if (session == null) {
            return Collections.emptyList();
        }
        List<Surface> surfaces = mArSurfaces;
        if (surfaces == null) {
            surfaces = Collections.unmodifiableList(new ArrayList<>(session.getSurfaces()));
            mArSurfaces = surfaces;
        }
        return surfaces;
    }

    /**
     * Starts tracking, now that there is a running capture session to track from.
     *
     * <p>This late because ARCore will not resume without one: the camera has to be delivering
     * frames into its surfaces before it has anything to track with. The capture callback goes
     * on afterwards for the same reason - it is how ARCore follows the requests the session is
     * already issuing.</p>
     */
    void onCaptureSessionActive() {
        ArSession session = mArSession;
        if (session == null || mArResumed) {
            return;
        }
        if (!session.resume()) {
            Log.w(TAG, "The AR session would not resume; the camera carries on without tracking");
            return;
        }
        mArResumed = true;
        updateDisplayGeometry(session);
        addPreviewOutput();
        try {
            // How the preview is driven. Nothing lands on the renderer's own texture - ARCore
            // owns the one the camera is read into - so there is no frame-available event to
            // draw from, and this callback on the requests the session is already issuing is
            // what takes its place. It has to go on after the resume, which is when ARCore
            // starts following them.
            session.getSharedCamera().setCaptureCallback(
                    new CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureCompleted(
                                @NonNull CameraCaptureSession captureSession,
                                @NonNull CaptureRequest request,
                                @NonNull TotalCaptureResult result) {
                            onFrameCaptured(result);
                        }
                    }, getHandler());
        } catch (RuntimeException error) {
            Log.w(TAG, "The AR session would not take a capture callback, so the preview will"
                    + " not advance", error);
        }
    }

    /**
     * A frame has been captured, so there is something new to draw.
     *
     * <p>Called from whichever capture callback is still being delivered - ours while we own
     * the repeating request, ARCore's once it has taken it over - because the renderer has no
     * way of noticing a frame on a texture it is not the consumer of.</p>
     */
    void onFrameCaptured(@NonNull TotalCaptureResult result) {
        Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
        mRenderThread.requestDraw(timestamp == null ? System.nanoTime() : timestamp);
    }

    /**
     * Resizes the preview output, because the renderer draws at the size of the view.
     *
     * <p>Which the plain Camera2 path does not have to do: it draws at the size of the camera
     * buffer and lets a view transform fit it. Here the frame is already cut to the shape of
     * the viewport ARCore was told about, so a change of shape has to reach both.</p>
     */
    void onPreviewLayoutChanged() {
        ArSession session = mArSession;
        if (session == null || !mArResumed) {
            return;
        }
        updateDisplayGeometry(session);
        addPreviewOutput();
    }

    /**
     * Draws the camera frame onto the preview, which is all the render pass does.
     *
     * <p>At the size of the view and with ARCore's own transform, which between them put the
     * frame where it belongs without a view transform on top: ARCore was told the shape of the
     * viewport, so the crop and the rotation it reports already fit it. That is why this path
     * clears the transform the plain Camera2 path sets - applying both would correct twice.</p>
     */
    private void addPreviewOutput() {
        SurfaceTexture preview = getSurfaceTexture();
        int width = getWidth();
        int height = getHeight();
        if (preview == null || width <= 0 || height <= 0) {
            Log.w(TAG, "The view has no texture to draw the AR preview into");
            return;
        }
        Size size = new Size(width, height);
        preview.setDefaultBufferSize(width, height);
        setTransform(new Matrix());
        mRenderThread.addOutput(mPreviewOutputKey, new Surface(preview), size, size,
                GlMath.ScaleType.FILL_CENTER,
                (surfaceTextureMatrix, into) -> {
                    // The argument is ignored: it describes a texture no camera wrote to.
                    synchronized (mTextureMatrix) {
                        System.arraycopy(mTextureMatrix, 0, into, 0, into.length);
                    }
                });
    }

    /**
     * Gives ARCore the texture the camera writes into, and lets it consume each frame.
     *
     * <p>The reason the AR path needs GL at all. ARCore reads the camera from a texture it was
     * given the name of, in the context that owns it, and {@code update()} is what advances
     * both that texture and tracking - so it stands in for the renderer consuming the frame
     * rather than happening alongside it.</p>
     */
    private final class ArTextureProvider implements CameraTextureProvider {
        private final ArSession mSession;

        private ArTextureProvider(@NonNull ArSession session) {
            mSession = session;
        }

        /**
         * Makes the texture, and hands the same one to ARCore.
         *
         * <p>The same one, which is the whole arrangement. ARCore binds a
         * {@link SurfaceTexture} of its own to whatever name it is given, puts the surface of
         * that texture in the session, and consumes it on {@code update()} - so the texture the
         * renderer draws from and the texture ARCore writes into have to be one object, and the
         * camera needs no surface of ours at all.</p>
         */
        @Override
        public int onCreateCameraTexture() {
            int textureName = OesTextureProgram.newExternalTexture();
            mSession.setCameraTextureName(textureName);
            return textureName;
        }

        /**
         * Advances tracking, which is also what puts the frame on the texture.
         *
         * <p>True, always: {@code update()} is the consume. The renderer must not also call
         * {@code updateTexImage}, because the {@link SurfaceTexture} it would call it on is not
         * the one ARCore attached - ours has no camera writing to it and never will.</p>
         */
        @Override
        public boolean onCameraFrameAvailable(@NonNull SurfaceTexture texture) {
            com.google.ar.core.Frame frame = mSession.update();
            if (frame != null) {
                readTextureTransform(frame);
                deliver(frame);
            }
            return true;
        }

        /**
         * Works out how to sample the camera texture, which ARCore alone can say.
         *
         * <p>It knows the rotation, the mirror and the crop that fit the camera image to the
         * viewport it was told about, and it reports them by transforming coordinates rather
         * than as a matrix - so the matrix is built from where the corners of the quad land.
         * The mapping is affine, which is why three corners are enough.</p>
         */
        private void readTextureTransform(@NonNull com.google.ar.core.Frame frame) {
            try {
                frame.transformCoordinates2d(
                        Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, QUAD_CORNERS_NDC,
                        Coordinates2d.TEXTURE_NORMALIZED, mQuadCornersTexture);
            } catch (RuntimeException error) {
                // Not yet available on the first frames, where the previous matrix still fits.
                return;
            }
            float originU = mQuadCornersTexture[0];
            float originV = mQuadCornersTexture[1];
            synchronized (mTextureMatrix) {
                // Column-major, and the same shape of matrix a SurfaceTexture reports: the
                // texture coordinate of a quad vertex is the origin plus its u and v times the
                // step to the next corner along each.
                mTextureMatrix[0] = mQuadCornersTexture[2] - originU;
                mTextureMatrix[1] = mQuadCornersTexture[3] - originV;
                mTextureMatrix[2] = 0f;
                mTextureMatrix[3] = 0f;
                mTextureMatrix[4] = mQuadCornersTexture[4] - originU;
                mTextureMatrix[5] = mQuadCornersTexture[5] - originV;
                mTextureMatrix[6] = 0f;
                mTextureMatrix[7] = 0f;
                mTextureMatrix[8] = 0f;
                mTextureMatrix[9] = 0f;
                mTextureMatrix[10] = 1f;
                mTextureMatrix[11] = 0f;
                mTextureMatrix[12] = originU;
                mTextureMatrix[13] = originV;
                mTextureMatrix[14] = 0f;
                mTextureMatrix[15] = 1f;
            }
        }

        private void deliver(@NonNull com.google.ar.core.Frame frame) {
            ArFrameListener listener = mArFrameListener;
            if (listener == null || getWidth() <= 0 || getHeight() <= 0) {
                return;
            }
            // The view, not the frame: everything handed out is in the coordinates an overlay
            // draws in, which is what updateDisplayGeometry told the session to project into.
            boolean tracking = mFrame.set(frame, getWidth(), getHeight(),
                    mSession.isDepthSupported());
            if (tracking) {
                mFailure = null;
                try {
                    listener.onArFrame(mFrame);
                } catch (RuntimeException error) {
                    // On the render thread, where an uncaught exception takes the preview with it.
                    Log.e(TAG, "An AR frame listener threw", error);
                } finally {
                    mFrame.clear();
                }
                return;
            }
            // On change rather than per frame: the reason is for showing someone, and a message
            // that is rewritten thirty times a second is the same message.
            ArTrackingFailure failure = ArCoreFrame.failureFor(frame);
            if (failure == mFailure) {
                return;
            }
            mFailure = failure;
            try {
                listener.onArNotTracking(failure);
            } catch (RuntimeException error) {
                Log.e(TAG, "An AR frame listener threw", error);
            }
        }

        @Override
        public void onReleaseCameraTexture(int textureName) {
            // Only here, and only after the session has been paused: ARCore holds this name and
            // this is the last call on the render thread.
            int[] names = {textureName};
            GLES20.glDeleteTextures(names.length, names, 0);
        }
    }

    // Capturing while tracking would mean a different session, which ARCore will not survive.

    @Override
    public void takePicture(@NonNull File file) {
        if (!isArActive()) {
            super.takePicture(file);
            return;
        }
        Log.w(TAG, "A picture cannot be taken while AR is tracking");
        onImageFailed();
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @Override
    public void startRecording(@NonNull File file) {
        if (!isArActive()) {
            super.startRecording(file);
            return;
        }
        Log.w(TAG, "A recording cannot be started while AR is tracking");
        onVideoFailed();
    }
}
