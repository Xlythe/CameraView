package com.xlythe.view.camera.shadow;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.os.Handler;

import androidx.annotation.Nullable;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.annotation.Resetter;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowCameraCaptureSessionImpl;
import org.robolectric.shadows.ShadowCaptureResult;
import org.robolectric.shadows.ShadowTotalCaptureResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Delivers capture callbacks from Robolectric's capture-session shadow.
 *
 * <p>The shipped shadow accepts a request and returns a sequence id, but never calls the
 * {@link CameraCaptureSession.CaptureCallback} back. A real camera always does, and code that
 * waits for a frame before acting, such as starting a recorder once the first frame has been
 * captured, therefore stalls forever under test. Nothing in such a path can be covered.</p>
 *
 * <p>Each accepted request produces one {@code onCaptureStarted} followed by one
 * {@code onCaptureCompleted}, posted to the handler the caller supplied so it arrives the way a
 * real callback does rather than re-entering the caller mid-call. The result carries the sensor
 * timestamp {@code onCaptureStarted} reported, and the auto-exposure state a test sets: enough for
 * a caller that only needs to know a frame landed, for one that pairs a frame with its result, and
 * for one that waits on metering.</p>
 */
@Implements(className = "android.hardware.camera2.impl.CameraCaptureSessionImpl",
        isInAndroidSdk = false)
public class ShadowCallbackCameraCaptureSession extends ShadowCameraCaptureSessionImpl {
    private static boolean sDeliverCallbacks = true;
    private static final AtomicInteger sCompletedCaptures = new AtomicInteger();
    private static final AtomicInteger sFrameNumber = new AtomicInteger();
    /** The sensor timestamp of the last capture reported. */
    private static final AtomicLong sLastSensorTimestamp = new AtomicLong();
    /** Every one-off request, in the order they were sent. */
    private static final List<CaptureRequest> sCaptures = new ArrayList<>();
    /** The auto-exposure state results report, or null for none. */
    @Nullable private static Integer sAeState;
    /** The repeating request in force, so a test can deliver more of its frames. */
    @Nullable private static Runnable sNextRepeatingFrame;

    @RealObject private CameraCaptureSession realSession;

    /** Turns delivery off, for a test that wants the old do-nothing behavior. */
    public static void setDeliverCallbacks(boolean deliverCallbacks) {
        sDeliverCallbacks = deliverCallbacks;
    }

    /** How many captures have been reported complete. */
    public static int getCompletedCaptureCount() {
        return sCompletedCaptures.get();
    }

    /** The sensor timestamp of the last capture reported, which its frame has to carry too. */
    public static long getLastSensorTimestamp() {
        return sLastSensorTimestamp.get();
    }

    /** The one-off requests sent so far, such as stills and metering triggers. */
    public static List<CaptureRequest> getCaptures() {
        return new ArrayList<>(sCaptures);
    }

    /** Has results report this auto-exposure state from now on. */
    public static void setAeState(@Nullable Integer aeState) {
        sAeState = aeState;
    }

    /** Delivers one more frame of the repeating request, as the camera would continuously. */
    public static void deliverRepeatingFrame() {
        if (sNextRepeatingFrame != null) {
            sNextRepeatingFrame.run();
        }
    }

    @Resetter
    public static void reset() {
        sDeliverCallbacks = true;
        sCompletedCaptures.set(0);
        sFrameNumber.set(0);
        sLastSensorTimestamp.set(0);
        sCaptures.clear();
        sAeState = null;
        sNextRepeatingFrame = null;
    }

    @Implementation
    protected int setRepeatingRequest(CaptureRequest request,
            CameraCaptureSession.CaptureCallback callback, Handler handler)
            throws android.hardware.camera2.CameraAccessException {
        int sequenceId = super.setRepeatingRequest(request, callback, handler);
        sNextRepeatingFrame = () -> deliver(request, callback, handler);
        deliver(request, callback, handler);
        return sequenceId;
    }

    @Implementation
    protected int capture(CaptureRequest request,
            CameraCaptureSession.CaptureCallback callback, Handler handler)
            throws android.hardware.camera2.CameraAccessException {
        int sequenceId = super.capture(request, callback, handler);
        sCaptures.add(request);
        deliver(request, callback, handler);
        return sequenceId;
    }

    private void deliver(CaptureRequest request,
            CameraCaptureSession.CaptureCallback callback, Handler handler) {
        if (!sDeliverCallbacks || callback == null) {
            return;
        }
        Runnable delivery = () -> {
            long frameNumber = sFrameNumber.incrementAndGet();
            long timestamp = System.nanoTime();
            callback.onCaptureStarted(realSession, request, timestamp, frameNumber);
            TotalCaptureResult result = newResult(timestamp);
            sLastSensorTimestamp.set(timestamp);
            callback.onCaptureCompleted(realSession, request, result);
            sCompletedCaptures.incrementAndGet();
        };
        if (handler != null) {
            handler.post(delivery);
        } else {
            delivery.run();
        }
    }

    /** A result carrying its sensor timestamp, and the auto-exposure state a test asked for. */
    private static TotalCaptureResult newResult(long timestamp) {
        TotalCaptureResult result = ShadowTotalCaptureResult.newTotalCaptureResult();
        ShadowCaptureResult shadow = Shadow.extract(result);
        shadow.set(CaptureResult.SENSOR_TIMESTAMP, timestamp);
        if (sAeState != null) {
            shadow.set(CaptureResult.CONTROL_AE_STATE, sAeState);
        }
        return result;
    }
}
