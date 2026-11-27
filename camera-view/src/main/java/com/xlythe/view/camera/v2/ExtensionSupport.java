package com.xlythe.view.camera.v2;

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraExtensionCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.xlythe.view.camera.CameraView;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.xlythe.view.camera.ICameraModule.DEBUG;
import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * Which {@link CameraView.PhotoMode photo modes} each camera runs through the phone maker's own
 * processing, which the platform lists per camera from API 31 and runs in a session of its own.
 */
@RequiresApi(21)
final class ExtensionSupport {
    /** The platform's numbers for the extensions, CameraExtensionCharacteristics.EXTENSION_*. */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({NO_EXTENSION, EXTENSION_AUTOMATIC, EXTENSION_FACE_RETOUCH, EXTENSION_BOKEH,
            EXTENSION_HDR, EXTENSION_NIGHT})
    @interface PlatformExtension {}

    /** {@link CameraView.PhotoMode#NONE}, which the platform has no number for. */
    static final int NO_EXTENSION = -1;
    /** CameraExtensionCharacteristics.EXTENSION_AUTOMATIC. */
    static final int EXTENSION_AUTOMATIC = 0;
    /**
     * CameraExtensionCharacteristics.EXTENSION_FACE_RETOUCH, which is API 34's name for the number
     * API 31 called EXTENSION_BEAUTY.
     */
    static final int EXTENSION_FACE_RETOUCH = 1;
    /** CameraExtensionCharacteristics.EXTENSION_BOKEH. */
    static final int EXTENSION_BOKEH = 2;
    /** CameraExtensionCharacteristics.EXTENSION_HDR. */
    static final int EXTENSION_HDR = 3;
    /** CameraExtensionCharacteristics.EXTENSION_NIGHT. */
    static final int EXTENSION_NIGHT = 4;

    /** Where cameras are asked what they have. */
    @NonNull
    private final CameraManager mCameraManager;

    /**
     * The photo modes each camera runs, by camera id. Asking means a call into the extension
     * service, and the answer never changes while the app runs, so each camera is asked once.
     * Guarded by this.
     */
    private final Map<String, Set<Integer>> mPhotoModes = new HashMap<>();

    ExtensionSupport(@NonNull CameraManager cameraManager) {
        mCameraManager = cameraManager;
    }

    /** Whether a camera runs a photo mode through the phone maker's processing. */
    @AnyThread
    boolean offers(@NonNull String cameraId, @CameraView.PhotoMode int photoMode) {
        return photoMode != CameraView.PhotoMode.NONE && photoModes(cameraId).contains(photoMode);
    }

    /**
     * Stops offering a mode a camera listed but could not run.
     *
     * <p>Listing one is a promise about the extension service, not about every session built on
     * it, so one that fails to configure is believed over the list for the rest of the run.</p>
     */
    @AnyThread
    synchronized void forget(@NonNull String cameraId, @CameraView.PhotoMode int photoMode) {
        Set<Integer> offered = mPhotoModes.get(cameraId);
        if (offered != null && offered.contains(photoMode)) {
            Set<Integer> remaining = new HashSet<>(offered);
            remaining.remove(photoMode);
            mPhotoModes.put(cameraId, Collections.unmodifiableSet(remaining));
        }
    }

    /** The modes a camera runs, asked for once. */
    @AnyThread
    @NonNull
    private synchronized Set<Integer> photoModes(@NonNull String cameraId) {
        Set<Integer> offered = mPhotoModes.get(cameraId);
        if (offered == null) {
            offered = Build.VERSION.SDK_INT >= 31
                    ? askForPhotoModes(cameraId)
                    : Collections.<Integer>emptySet();
            mPhotoModes.put(cameraId, offered);
        }
        return offered;
    }

    /**
     * Asks the extension service, counting a mode only where it can carry this library's preview
     * and a still: one that lists no size for either could not be used anyway.
     */
    @RequiresApi(31)
    @NonNull
    private Set<Integer> askForPhotoModes(@NonNull String cameraId) {
        Set<Integer> offered = new HashSet<>();
        try {
            CameraExtensionCharacteristics characteristics =
                    mCameraManager.getCameraExtensionCharacteristics(cameraId);
            for (int extension : characteristics.getSupportedExtensions()) {
                Integer photoMode = fromPlatform(extension);
                if (photoMode != null && canCarryPreviewAndStill(characteristics, extension)) {
                    offered.add(photoMode);
                }
            }
        } catch (CameraAccessException | RuntimeException error) {
            if (DEBUG) Log.d(TAG, "Camera " + cameraId + " cannot list its extensions", error);
        }
        return Collections.unmodifiableSet(offered);
    }

    @RequiresApi(31)
    private static boolean canCarryPreviewAndStill(
            @NonNull CameraExtensionCharacteristics characteristics,
            @PlatformExtension int extension) {
        return !characteristics.getExtensionSupportedSizes(
                        extension, SurfaceTexture.class).isEmpty()
                && (!characteristics.getExtensionSupportedSizes(
                        extension, ImageFormat.JPEG).isEmpty()
                || !characteristics.getExtensionSupportedSizes(
                        extension, ImageFormat.YUV_420_888).isEmpty());
    }

    /** The platform's number for a photo mode, or {@link #NO_EXTENSION} for none. */
    @PlatformExtension
    static int toPlatform(@CameraView.PhotoMode int photoMode) {
        switch (photoMode) {
            case CameraView.PhotoMode.AUTO:
                return EXTENSION_AUTOMATIC;
            case CameraView.PhotoMode.FACE_RETOUCH:
                return EXTENSION_FACE_RETOUCH;
            case CameraView.PhotoMode.BACKGROUND_BLUR:
                return EXTENSION_BOKEH;
            case CameraView.PhotoMode.HDR:
                return EXTENSION_HDR;
            case CameraView.PhotoMode.NIGHT:
                return EXTENSION_NIGHT;
            case CameraView.PhotoMode.NONE:
            default:
                return NO_EXTENSION;
        }
    }

    /** The photo mode a platform number stands for, or null for one without a name here. */
    @Nullable
    static Integer fromPlatform(int extension) {
        switch (extension) {
            case EXTENSION_AUTOMATIC:
                return CameraView.PhotoMode.AUTO;
            case EXTENSION_FACE_RETOUCH:
                return CameraView.PhotoMode.FACE_RETOUCH;
            case EXTENSION_BOKEH:
                return CameraView.PhotoMode.BACKGROUND_BLUR;
            case EXTENSION_HDR:
                return CameraView.PhotoMode.HDR;
            case EXTENSION_NIGHT:
                return CameraView.PhotoMode.NIGHT;
            default:
                return null;
        }
    }
}
