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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
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
 * timestamp {@code onCaptureStarted} reported, the auto-exposure state a test sets, and any other
 * values a test sets: enough for a caller that only needs to know a frame landed, for one that
 * pairs a frame with its result, for one that waits on metering, and for one that copies the
 * result into a file. A test can also hold one-off captures back, to have their frames arrive
 * before their results.</p>
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
    /** The sensor timestamp of the last capture reported. */
    private static final AtomicLong sLastSensorTimestamp = new AtomicLong();
    /** Every request made repeating, oldest first; the last is what the camera is running. */
    private static final List<CaptureRequest> sRepeatingRequests = new CopyOnWriteArrayList<>();
    /** Every one-off request, such as a still or a metering trigger, oldest first. */
    private static final List<CaptureRequest> sCaptures = new CopyOnWriteArrayList<>();
    /** The auto-exposure state results report, or null for none. */
    @Nullable private static Integer sAeState;
    /** The repeating request in force, so a test can deliver more of its frames. */
    @Nullable private static Runnable sNextRepeatingFrame;
    /** Sets each further value a test asked results to report, in the order asked. */
    private static final List<Consumer<ShadowCaptureResult>> sResultValues = new ArrayList<>();
    /** Whether one-off captures are held back until {@link #releaseHeldCaptures}. */
    private static boolean sHoldCaptures;
    /** The one-off captures held back, in the order they were sent. */
    private static final List<Runnable> sHeldCaptures = new ArrayList<>();

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

    /** The sensor timestamp of the last capture reported, which its frame has to carry too. */
    public static long getLastSensorTimestamp() {
        return sLastSensorTimestamp.get();
    }

    /** Every one-off request since the last {@link #reset}, oldest first. */
    public static List<CaptureRequest> getCaptures() {
        return new ArrayList<>(sCaptures);
    }

    /** Has results report this auto-exposure state from now on. */
    public static void setAeState(@Nullable Integer aeState) {
        sAeState = aeState;
    }

    /** Has results report this value from now on, as a camera reports how it took a frame. */
    public static <T> void setResultValue(CaptureResult.Key<T> key, T value) {
        sResultValues.add(shadow -> shadow.set(key, value));
    }

    /** Holds the callbacks of one-off captures back until released, or delivers them again. */
    public static void setHoldCaptures(boolean hold) {
        sHoldCaptures = hold;
    }

    /** Delivers the one-off captures held back so far, in the order they were sent. */
    public static void releaseHeldCaptures() {
        List<Runnable> held = new ArrayList<>(sHeldCaptures);
        sHeldCaptures.clear();
        for (Runnable delivery : held) {
            delivery.run();
        }
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
        sRepeatingRequests.clear();
        sLastSensorTimestamp.set(0);
        sCaptures.clear();
        sAeState = null;
        sNextRepeatingFrame = null;
        sResultValues.clear();
        sHoldCaptures = false;
        sHeldCaptures.clear();
    }

    @Implementation
    protected int setRepeatingRequest(CaptureRequest request,
            CameraCaptureSession.CaptureCallback callback, Handler handler)
            throws android.hardware.camera2.CameraAccessException {
        int sequenceId = super.setRepeatingRequest(request, callback, handler);
        sRepeatingRequests.add(request);
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
        if (sHoldCaptures) {
            sHeldCaptures.add(() -> deliver(request, callback, handler));
        } else {
            deliver(request, callback, handler);
        }
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

    /** A result carrying its sensor timestamp, and whatever else a test asked for. */
    private static TotalCaptureResult newResult(long timestamp) {
        TotalCaptureResult result = ShadowTotalCaptureResult.newTotalCaptureResult();
        ShadowCaptureResult shadow = Shadow.extract(result);
        shadow.set(CaptureResult.SENSOR_TIMESTAMP, timestamp);
        if (sAeState != null) {
            shadow.set(CaptureResult.CONTROL_AE_STATE, sAeState);
        }
        for (Consumer<ShadowCaptureResult> value : sResultValues) {
            value.accept(shadow);
        }
        return result;
    }
}
