package com.xlythe.view.camera.shadow;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.os.Handler;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.annotation.Resetter;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowCameraCaptureSessionImpl;
import org.robolectric.shadows.ShadowCaptureResult;
import org.robolectric.shadows.ShadowTotalCaptureResult;

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
 * real callback does rather than re-entering the caller mid-call. The result carries nothing but
 * the sensor timestamp {@code onCaptureStarted} reported: enough for a caller that only needs to
 * know a frame landed, and for one that pairs a frame with its result.</p>
 */
@Implements(className = "android.hardware.camera2.impl.CameraCaptureSessionImpl",
        isInAndroidSdk = false)
public class ShadowCallbackCameraCaptureSession extends ShadowCameraCaptureSessionImpl {
    private static boolean sDeliverCallbacks = true;
    private static final AtomicInteger sCompletedCaptures = new AtomicInteger();
    private static final AtomicInteger sFrameNumber = new AtomicInteger();
    private static final AtomicLong sLastSensorTimestamp = new AtomicLong();

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

    @Resetter
    public static void reset() {
        sDeliverCallbacks = true;
        sCompletedCaptures.set(0);
        sFrameNumber.set(0);
        sLastSensorTimestamp.set(0);
    }

    @Implementation
    protected int setRepeatingRequest(CaptureRequest request,
            CameraCaptureSession.CaptureCallback callback, Handler handler)
            throws android.hardware.camera2.CameraAccessException {
        int sequenceId = super.setRepeatingRequest(request, callback, handler);
        deliver(request, callback, handler);
        return sequenceId;
    }

    @Implementation
    protected int capture(CaptureRequest request,
            CameraCaptureSession.CaptureCallback callback, Handler handler)
            throws android.hardware.camera2.CameraAccessException {
        int sequenceId = super.capture(request, callback, handler);
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
            TotalCaptureResult result = ShadowTotalCaptureResult.newTotalCaptureResult();
            ShadowCaptureResult shadow = Shadow.extract(result);
            shadow.set(CaptureResult.SENSOR_TIMESTAMP, timestamp);
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
}
