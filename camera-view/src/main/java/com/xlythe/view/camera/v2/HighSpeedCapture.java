package com.xlythe.view.camera.v2;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.view.Surface;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

/**
 * The two camera calls a slow-motion recording needs and an ordinary one does not.
 *
 * <p>Held apart from the sessions because every type named here arrived in API 23 and the library
 * still runs on 21. A method that is never called is never verified, so confining these to a
 * class nothing touches below 23 keeps the old platforms out of them entirely.</p>
 */
@RequiresApi(23)
final class HighSpeedCapture {
    private HighSpeedCapture() {}

    /**
     * Asks the device for a constrained high-speed session.
     *
     * <p>A different kind of session with its own rules, not a flag on the ordinary one: it takes
     * the preview and the recording surface and nothing else, both at a size the camera published
     * for high speed. That is why a recording session built this way has no still-capture reader
     * attached - there is no stream slot for one, and asking for it loses the whole session.</p>
     */
    static void createSession(@NonNull CameraDevice device, @NonNull List<Surface> surfaces,
            @NonNull CameraCaptureSession.StateCallback callback, @NonNull Handler handler)
            throws CameraAccessException {
        device.createConstrainedHighSpeedCaptureSession(surfaces, callback, handler);
    }

    /**
     * Starts the repeating burst the recording is fed by.
     *
     * <p>A single repeating request will not do here. A camera running at 240fps is driven in
     * batches of requests rather than one per frame, and the batch is the camera's to build:
     * createHighSpeedRequestList expands the one request into however many the device wants, and
     * setRepeatingRequest on this session throws rather than doing it for us.</p>
     *
     * @throws IllegalArgumentException from the camera, when the request names a frame rate range
     *     or a control it does not accept in this mode
     */
    static void setRepeatingBurst(@NonNull CameraCaptureSession session,
            @NonNull CaptureRequest request,
            @Nullable CameraCaptureSession.CaptureCallback callback, @NonNull Handler handler)
            throws CameraAccessException {
        CameraConstrainedHighSpeedCaptureSession highSpeed =
                (CameraConstrainedHighSpeedCaptureSession) session;
        session.setRepeatingBurst(
                highSpeed.createHighSpeedRequestList(request), callback, handler);
    }
}
