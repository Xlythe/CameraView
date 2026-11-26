package com.xlythe.view.camera.x;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.Preview;
import androidx.camera.core.SessionConfig;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.HighSpeedVideoSessionConfig;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.VideoCapture;

import com.xlythe.view.camera.HighSpeedProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * What CameraX needs in order to record slow motion, and where each half of it comes from.
 *
 * <p>androidx.camera.video 1.7.0 records high speed for real: HighSpeedVideoSessionConfig is a
 * public SessionConfig that ProcessCameraProvider.bindToLifecycle takes, it carries the frame
 * rate range, and setSlowMotionEnabled holds the encoder at thirty frames a second while the
 * camera runs fast - the same arrangement the camera2 backend builds by hand out of
 * MediaRecorder.setCaptureRate. So this is wired rather than declined.</p>
 *
 * <p>The choices themselves are read from the camera2 characteristics underneath, as the
 * {@code v2} backend reads them. CameraX publishes isHighSpeedSupported, the frame rate ranges
 * and the high-speed resolutions on CameraInfoInternal, which is restricted to its own library
 * group; what reaches an app is CameraInfo.getSupportedFrameRateRanges(SessionConfig), which
 * gives frame rates but no sizes, and a size is half of the answer here. Going to the
 * characteristics gets both from the source CameraX itself reads.</p>
 */
final class CameraXHighSpeed {
    private CameraXHighSpeed() {}

    /**
     * Every frame size and frame rate this camera publishes for high-speed recording.
     *
     * @param cameraInfo the camera to ask, or null when none is bound yet
     */
    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    @NonNull
    static List<HighSpeedProfile> publishedProfiles(@Nullable CameraInfo cameraInfo) {
        // The configurations go back to API 21 but the session that runs them arrived in 23.
        if (cameraInfo == null || Build.VERSION.SDK_INT < 23) {
            return Collections.emptyList();
        }
        try {
            Camera2CameraInfo info = Camera2CameraInfo.from(cameraInfo);
            int[] capabilities = info.getCameraCharacteristic(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            if (!contains(capabilities, CameraMetadata
                    .REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)) {
                return Collections.emptyList();
            }
            StreamConfigurationMap map = info.getCameraCharacteristic(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = map == null ? null : map.getHighSpeedVideoSizes();
            if (sizes == null) {
                return Collections.emptyList();
            }
            List<HighSpeedProfile> profiles = new ArrayList<>();
            for (Size size : sizes) {
                profiles.addAll(
                        HighSpeedProfile.forSize(size, map.getHighSpeedVideoFpsRangesFor(size)));
            }
            return HighSpeedProfile.ordered(profiles);
        } catch (IllegalArgumentException | IllegalStateException | LinkageError error) {
            // A CameraInfo that is not camera2's - a fake, or a future implementation - cannot be
            // asked, and a camera that cannot answer has nothing to offer.
            Log.w(TAG, "CameraX could not report its high-speed configurations", error);
            return Collections.emptyList();
        }
    }

    /**
     * The session config a high-speed recording is bound with.
     *
     * <p>A SessionConfig rather than a list of use cases, because that is the only shape CameraX
     * has for one: the frame rate range and the slow-motion decision live on the config, not on
     * either use case. The config refuses a VideoCapture that names a mirror mode or a target
     * frame rate, and a Preview that names a resolution, an aspect ratio or a frame rate, so the
     * two it is given have to be built bare.</p>
     */
    @NonNull
    static SessionConfig sessionConfig(@NonNull VideoCapture<Recorder> videoCapture,
            @NonNull Preview preview, @NonNull HighSpeedProfile profile) {
        return new HighSpeedVideoSessionConfig.Builder(videoCapture)
                .setPreview(preview)
                // Slow motion on, which is what separates a file that plays back slowly from a
                // correctly timed file at a frame rate nothing displays. CameraX does it by
                // holding the encoder's frame rate at thirty while the camera delivers at the
                // rate above.
                .setSlowMotionEnabled(true)
                .setFrameRateRange(profile.getFrameRateRange())
                .build();
    }

    /**
     * The recording quality that asks for a published high-speed size.
     *
     * <p>The size cannot be asked for directly: the session config refuses a resolution on the
     * Preview and CameraX resolves the recording from the Recorder's quality, so the chosen size
     * is expressed as the quality that means it. The fallback is there because a camera that
     * publishes a high-speed size does not promise an encoder profile at that quality.</p>
     */
    @NonNull
    static QualitySelector qualitySelector(@NonNull Size size) {
        Quality quality;
        if (size.getHeight() >= 2160) {
            quality = Quality.UHD;
        } else if (size.getHeight() >= 1080) {
            quality = Quality.FHD;
        } else if (size.getHeight() >= 720) {
            quality = Quality.HD;
        } else {
            quality = Quality.SD;
        }
        return QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality));
    }

    private static boolean contains(@Nullable int[] values, int value) {
        if (values == null) {
            return false;
        }
        for (int candidate : values) {
            if (candidate == value) {
                return true;
            }
        }
        return false;
    }
}
