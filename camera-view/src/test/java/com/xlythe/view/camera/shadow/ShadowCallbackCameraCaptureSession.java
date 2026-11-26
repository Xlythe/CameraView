package com.xlythe.view.camera.shadow;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.os.Handler;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.annotation.Resetter;
import org.robolectric.shadows.ShadowCameraCaptureSessionImpl;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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
 * real callback does rather than re-entering the caller mid-call. The result is an empty
 * {@link TotalCaptureResult}: enough for a caller that only needs to know a frame landed, and the
 * honest answer for a device with no sensor behind it.</p>
 *
 * <p>Every request is also kept, so a test can read back what the backend asked the camera for.
 * Robolectric runs no {@link Resetter} for a shadow a test names in its own {@code @Config}, so
 * such a test calls {@link #reset} itself before it starts.</p>
 */
@Implements(className = "android.hardware.camera2.impl.CameraCaptureSessionImpl",
        isInAndroidSdk = false)
public class ShadowCallbackCameraCaptureSession extends ShadowCameraCaptureSessionImpl {
    private static boolean sDeliverCallbacks = true;
    private static final AtomicInteger sCompletedCaptures = new AtomicInteger();
    private static final AtomicInteger sFrameNumber = new AtomicInteger();
    /** Every request made repeating, oldest first; the last is what the camera is running. */
    private static final List<CaptureRequest> sRepeatingRequests = new CopyOnWriteArrayList<>();
    /** Every one-off capture, oldest first, which is where a still is asked for. */
    private static final List<CaptureRequest> sCaptureRequests = new CopyOnWriteArrayList<>();

    @RealObject private CameraCaptureSession realSession;

    /** Turns delivery off, for a test that wants the old do-nothing behavior. */
    public static void setDeliverCallbacks(boolean deliverCallbacks) {
        sDeliverCallbacks = deliverCallbacks;
    }

    /** How many captures have been reported complete. */
    public static int getCompletedCaptureCount() {
        return sCompletedCaptures.get();
    }

    /** The request the camera is repeating, or null when none has been made. */
    public static CaptureRequest getLastRepeatingRequest() {
        return sRepeatingRequests.isEmpty() ? null
                : sRepeatingRequests.get(sRepeatingRequests.size() - 1);
    }

    /** Every request made repeating since the last {@link #reset}, oldest first. */
    public static List<CaptureRequest> getRepeatingRequests() {
        return new ArrayList<>(sRepeatingRequests);
    }

    /** Every one-off capture since the last {@link #reset}, oldest first. */
    public static List<CaptureRequest> getCaptureRequests() {
        return new ArrayList<>(sCaptureRequests);
    }

    @Resetter
    public static void reset() {
        sDeliverCallbacks = true;
        sCompletedCaptures.set(0);
        sFrameNumber.set(0);
        sRepeatingRequests.clear();
        sCaptureRequests.clear();
    }

    @Implementation
    protected int setRepeatingRequest(CaptureRequest request,
            CameraCaptureSession.CaptureCallback callback, Handler handler)
            throws android.hardware.camera2.CameraAccessException {
        int sequenceId = super.setRepeatingRequest(request, callback, handler);
        sRepeatingRequests.add(request);
        deliver(request, callback, handler);
        return sequenceId;
    }

    @Implementation
    protected int capture(CaptureRequest request,
            CameraCaptureSession.CaptureCallback callback, Handler handler)
            throws android.hardware.camera2.CameraAccessException {
        int sequenceId = super.capture(request, callback, handler);
        sCaptureRequests.add(request);
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
            callback.onCaptureStarted(realSession, request, System.nanoTime(), frameNumber);
            // TotalCaptureResult has only a package-private constructor, so it has to be built
            // reflectively; its accessors return empty rather than throwing.
            TotalCaptureResult result =
                    ReflectionHelpers.callConstructor(TotalCaptureResult.class);
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
