package com.xlythe.view.camera.v2;

import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.os.Handler;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xlythe.view.camera.ArAnchor;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.ICameraModule;
import com.xlythe.view.camera.ar.ArTracker;
import com.xlythe.view.camera.gl.FrameGeometry;
import com.xlythe.view.camera.gl.GlMath;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stands in for ARCore: a back camera at a known texture size, with surfaces of its own, that
 * resumes when the session goes active and has a frame whenever a test says so.
 *
 * <p>Its session callback reports the session active straight after configuring it, which the
 * camera does and Robolectric's camera does not.</p>
 */
public final class FakeArTracker implements ArTracker {
    /** The texture name it hands the renderer. */
    public static final int TEXTURE = 55;

    /** The size its frames arrive at. */
    public static final Size TEXTURE_SIZE = new Size(1920, 1080);

    /** A pinhole in the middle of the texture. */
    static final float[] LENS = {0.66f, 1.18f, 0.5f, 0.5f, 0f};

    /** The surfaces it asks for in the capture session. */
    final List<Surface> surfaces = Arrays.asList(
            new Surface(new SurfaceTexture(0)), new Surface(new SurfaceTexture(0)));

    /** Told how tracking is going. */
    @Nullable
    volatile Listener listener;

    /** Whether the device callback went through it. */
    public volatile boolean deviceWrapped;

    /** Whether a session callback went through it. */
    volatile boolean sessionWrapped;

    /** Whether resuming fails, as it does when the camera is taken away. */
    volatile boolean resumeFails;

    /** Whether it is resumed. */
    volatile boolean resumed;

    /** Whether advancing finds a new frame. */
    volatile boolean hasFrame = true;

    /** Whether it measures depth. */
    volatile boolean depth = true;

    /** Counts of each call. */
    final AtomicInteger resumes = new AtomicInteger();
    final AtomicInteger pauses = new AtomicInteger();
    final AtomicInteger closes = new AtomicInteger();
    final AtomicInteger created = new AtomicInteger();
    final AtomicInteger advanced = new AtomicInteger();
    final AtomicInteger released = new AtomicInteger();

    /** Who hears of each capture once tracking runs, and where. */
    @Nullable
    volatile CameraCaptureSession.CaptureCallback captureCallback;
    @Nullable
    volatile Handler captureHandler;

    /** Where the last hit test was cast through the buffer. */
    public volatile float hitS = Float.NaN;
    public volatile float hitT = Float.NaN;

    /** What a hit test finds. */
    @Nullable
    volatile ArAnchor anchor = new ArAnchor();

    /** A module for a view, sharing the camera with this tracker. */
    @NonNull
    public static ICameraModule module(@NonNull CameraView view, @NonNull FakeArTracker tracker) {
        return new ArCameraModule(view, tracker);
    }

    /** Tells the module how tracking is going, as ARCore would from the render thread. */
    public void report(@NonNull CameraView.ArTracking tracking) {
        Listener current = listener;
        if (current != null) {
            current.onTrackingChanged(tracking);
        }
    }

    /** How many times it was closed for good. */
    public int closeCount() {
        return closes.get();
    }

    @Override
    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public String getCameraId() {
        return FakeCamera2.BACK_ID;
    }

    @NonNull
    @Override
    public Size getTextureSize() {
        return TEXTURE_SIZE;
    }

    @Override
    public boolean isDepthSupported() {
        return depth;
    }

    @NonNull
    @Override
    public CameraDevice.StateCallback wrap(@NonNull CameraDevice.StateCallback callback,
            @NonNull Handler handler) {
        deviceWrapped = true;
        return callback;
    }

    @NonNull
    @Override
    public CameraCaptureSession.StateCallback wrap(
            @NonNull CameraCaptureSession.StateCallback callback, @NonNull Handler handler) {
        sessionWrapped = true;
        return new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(@NonNull CameraCaptureSession session) {
                callback.onConfigured(session);
                handler.post(() -> callback.onActive(session));
            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                callback.onConfigureFailed(session);
            }
        };
    }

    @NonNull
    @Override
    public List<Surface> getSurfaces() {
        return surfaces;
    }

    @Override
    public void setCaptureCallback(@NonNull CameraCaptureSession.CaptureCallback callback,
            @NonNull Handler handler) {
        captureCallback = callback;
        captureHandler = handler;
    }

    @Override
    public boolean resume() {
        resumes.incrementAndGet();
        if (resumeFails) {
            return false;
        }
        resumed = true;
        return true;
    }

    @Override
    public boolean isResumed() {
        return resumed;
    }

    @Override
    public void pause() {
        pauses.incrementAndGet();
        resumed = false;
    }

    @Override
    public void close() {
        closes.incrementAndGet();
        resumed = false;
    }

    @Nullable
    @Override
    public ArAnchor hitTest(float s, float t) {
        hitS = s;
        hitT = t;
        return anchor;
    }

    @Override
    public int onCreateCameraTexture() {
        created.incrementAndGet();
        return TEXTURE;
    }

    @Override
    public boolean advance() {
        advanced.incrementAndGet();
        return hasFrame;
    }

    @Override
    public void getTransformMatrix(@NonNull float[] into) {
        float[] flip = GlMath.identity();
        flip[5] = -1f;
        flip[13] = 1f;
        float[] matrix = GlMath.multiply(flip, GlMath.cameraRequest(90, false));
        System.arraycopy(matrix, 0, into, 0, matrix.length);
    }

    @Override
    public long getTimestampNanos() {
        return 1_000L + advanced.get();
    }

    @Override
    public void onReleaseCameraTexture(int textureName) {
        released.incrementAndGet();
    }

    @Override
    public void onFrameGeometry(@NonNull FrameGeometry geometry, @Nullable Size bufferSize) {
        geometry.setNormalizedIntrinsics(LENS);
        geometry.setViewMatrix(GlMath.identity());
    }
}
