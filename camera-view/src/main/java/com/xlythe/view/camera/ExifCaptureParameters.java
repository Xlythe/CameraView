package com.xlythe.view.camera;

import android.graphics.Rect;
import android.hardware.Camera;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureResult;
import android.os.Build;
import android.util.Log;
import android.util.Rational;
import android.util.SizeF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;
import androidx.exifinterface.media.ExifInterface;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How a picture was taken, as the Exif tags that describe it: what the library knows of the
 * camera's optics and of the request it made, and, for a picture the library encodes itself,
 * what the camera reported for the frame. {@link #applyTo} fills only what the file lacks, so a
 * value the camera wrote always stands.
 *
 * <pre>{@code
 * ExifCaptureParameters.forCamera(cameraManager, cameraId)
 *         .setCaptureResult(result)
 *         .applyTo(exif);
 * }</pre>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class ExifCaptureParameters {
    private static final String TAG = ExifCaptureParameters.class.getSimpleName();

    /** The diagonal of a 36x24mm frame, which a 35mm-equivalent focal length is scaled to. */
    private static final double FULL_FRAME_DIAGONAL_MM = Math.hypot(36, 24);

    /** Closer than this, two focal lengths are one lens and a rational's rounding. */
    private static final double SAME_FOCAL_LENGTH_MM = 0.01;

    /** Thousandths, which is finer than any lens or exposure setting is specified to. */
    private static final long RATIONAL_SCALE = 1000;

    /** Nanoseconds in a second, and the longest exposure a rational can hold in them. */
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long MAX_NANOS_IN_A_RATIONAL = 0xFFFFFFFFL;

    /** The most a SHORT tag, such as PhotographicSensitivity, can hold in its two bytes. */
    private static final int MAX_SHORT = 0xFFFF;

    private static final String EXPOSURE_MODE_AUTO = "0";
    private static final String EXPOSURE_MODE_MANUAL = "1";
    private static final String EXPOSURE_PROGRAM_MANUAL = "1";
    /** The camera sets the exposure itself, which is what every phone's auto-exposure does. */
    private static final String EXPOSURE_PROGRAM_NORMAL = "2";
    private static final String WHITE_BALANCE_AUTO = "0";
    private static final String WHITE_BALANCE_MANUAL = "1";
    private static final String SCENE_STANDARD = "0";
    private static final String SCENE_LANDSCAPE = "1";
    private static final String SCENE_PORTRAIT = "2";
    private static final String SCENE_NIGHT = "3";
    /** A single sensor with a colour filter over it: every Bayer sensor. */
    private static final String SENSING_ONE_CHIP_COLOR_AREA = "2";
    private static final String NO_DIGITAL_ZOOM = "1/1";
    private static final String NO_EXPOSURE_BIAS = "0/1";

    /** The Flash tag's bits: whether it fired, its mode, whether there is one, and red-eye. */
    private static final int FLASH_FIRED = 0x01;
    private static final int FLASH_MODE_COMPULSORY_FIRING = 0x08;
    private static final int FLASH_MODE_COMPULSORY_SUPPRESSION = 0x10;
    private static final int FLASH_MODE_AUTO = 0x18;
    private static final int FLASH_NO_FUNCTION = 0x20;
    private static final int FLASH_RED_EYE_REDUCTION = 0x40;

    /** One lens the camera can take a picture through, as its characteristics describe it. */
    @VisibleForTesting
    static final class Lens {
        /** Its focal lengths in millimetres: one for a fixed lens, several for a zoom. */
        @NonNull final float[] focalLengths;

        /** The f-numbers it can be set to, which phones fix at one. Empty when unknown. */
        @NonNull final float[] apertures;

        /**
         * The sensor behind it, as {width, height} in millimetres, or null when unknown. Plain
         * numbers rather than a SizeF, which the old camera API's releases do not have.
         */
        @Nullable final float[] sensorSize;

        Lens(@Nullable float[] focalLengths, @Nullable float[] apertures,
                @Nullable float[] sensorSize) {
            this.focalLengths = focalLengths == null ? new float[0] : focalLengths;
            this.apertures = apertures == null ? new float[0] : apertures;
            this.sensorSize = sensorSize;
        }

        boolean hasFocalLength(double focalLength) {
            for (float candidate : focalLengths) {
                if (Math.abs(candidate - focalLength) < SAME_FOCAL_LENGTH_MM) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Its widest f-number at any of its focal lengths, or NaN when that depends on which: a
         * zoom lens whose aperture changes with it says which apertures it has, not where.
         */
        double widestAperture() {
            if (apertures.length == 0 || (apertures.length > 1 && focalLengths.length > 1)) {
                return Double.NaN;
            }
            float widest = apertures[0];
            for (float aperture : apertures) {
                widest = Math.min(widest, aperture);
            }
            return widest;
        }
    }

    /**
     * The lenses behind the camera: its own, or for a logical camera, those of the physical
     * cameras it switches between. Matching a file's focal length against them says which one
     * took the picture.
     */
    private final List<Lens> mLenses = new ArrayList<>();

    /** Tag values known before the file is read, in the order they were set. */
    private final Map<String, String> mValues = new LinkedHashMap<>();

    /** Whether this is a logical camera, which takes each picture through one of several lenses. */
    private boolean mLogical;

    /**
     * Whether a zoom ratio can only be a crop. A logical camera reaches part of its range by
     * switching to a longer lens, so there a ratio says nothing about how much was cropped.
     */
    private boolean mZoomIsDigital;

    /**
     * One exposure-compensation step in EV, as a numerator over {@link #mExposureStepDenominator}.
     * Plain numbers rather than a Rational, which the old camera API's releases do not have.
     */
    private long mExposureStepNumerator;

    /** The denominator of {@link #mExposureStepNumerator}, or 0 when the camera offers no step. */
    private long mExposureStepDenominator;

    /** The sensor's active array, which a crop region's zoom is measured against. */
    @Nullable private Rect mActiveArray;

    /** Whether the camera reports focus distances in real diopters, rather than relative ones. */
    private boolean mFocusDistanceCalibrated;

    /**
     * The scene a photo mode was taken for, which stands over the one the frame reports, or null
     * for none.
     */
    @Nullable
    private String mPhotoModeScene;

    /**
     * Starts from what every capture the library asks for has in common: exposure and white
     * balance left to the camera, in its standard scene mode.
     */
    public ExifCaptureParameters() {
        mValues.put(ExifInterface.TAG_EXPOSURE_MODE, EXPOSURE_MODE_AUTO);
        mValues.put(ExifInterface.TAG_EXPOSURE_PROGRAM, EXPOSURE_PROGRAM_NORMAL);
        mValues.put(ExifInterface.TAG_WHITE_BALANCE, WHITE_BALANCE_AUTO);
        mValues.put(ExifInterface.TAG_SCENE_CAPTURE_TYPE, SCENE_STANDARD);
    }

    /**
     * What a camera2 camera's characteristics say, including those of the physical cameras
     * behind a logical one. A camera that cannot be read gives the common defaults alone.
     */
    @RequiresApi(21)
    @NonNull
    public static ExifCaptureParameters forCamera(
            @NonNull CameraManager manager, @NonNull String cameraId) {
        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            List<CameraCharacteristics> physicalCameras = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= 28 && isLogicalMultiCamera(characteristics)) {
                for (String physicalId : characteristics.getPhysicalCameraIds()) {
                    physicalCameras.add(manager.getCameraCharacteristics(physicalId));
                }
            }
            return forCamera(characteristics, physicalCameras);
        } catch (Exception error) {
            // Exception rather than CameraAccessException: the legacy camera's releases load this
            // class too, and before API 19 a catch of an exception type they lack fails to load it.
            Log.w(TAG, "Unable to read the camera's optics for its Exif", error);
            return new ExifCaptureParameters();
        }
    }

    @RequiresApi(21)
    @VisibleForTesting
    @NonNull
    static ExifCaptureParameters forCamera(@NonNull CameraCharacteristics characteristics,
            @NonNull List<CameraCharacteristics> physicalCameras) {
        ExifCaptureParameters parameters = new ExifCaptureParameters();
        boolean logical = isLogicalMultiCamera(characteristics);
        if (logical) {
            for (CameraCharacteristics physical : physicalCameras) {
                parameters.mLenses.add(lensOf(physical));
            }
        } else {
            parameters.mLenses.add(lensOf(characteristics));
        }
        parameters.mLogical = logical;
        parameters.mZoomIsDigital = !logical;
        Rational step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        if (step != null && step.getDenominator() != 0) {
            parameters.mExposureStepNumerator = step.getNumerator();
            parameters.mExposureStepDenominator = step.getDenominator();
        }
        parameters.mActiveArray =
                characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        Integer calibration =
                characteristics.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION);
        parameters.mFocusDistanceCalibrated = calibration != null && calibration
                != CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED;
        if (Boolean.FALSE.equals(characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) {
            parameters.mValues.put(ExifInterface.TAG_FLASH, Integer.toString(FLASH_NO_FUNCTION));
        }
        Integer filter =
                characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
        if (filter != null
                && filter >= CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
                && filter <= CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR) {
            parameters.mValues.put(ExifInterface.TAG_SENSING_METHOD, SENSING_ONE_CHIP_COLOR_AREA);
        }
        return parameters;
    }

    @RequiresApi(21)
    private static boolean isLogicalMultiCamera(@NonNull CameraCharacteristics characteristics) {
        if (Build.VERSION.SDK_INT < 28) {
            return false;
        }
        int[] capabilities =
                characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (capabilities == null) {
            return false;
        }
        for (int capability : capabilities) {
            if (capability
                    == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) {
                return true;
            }
        }
        return false;
    }

    @RequiresApi(21)
    @NonNull
    private static Lens lensOf(@NonNull CameraCharacteristics characteristics) {
        SizeF sensor = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        return new Lens(
                characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS),
                characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES),
                sensor == null ? null : new float[] {sensor.getWidth(), sensor.getHeight()});
    }

    /**
     * What the legacy camera's parameters say at the moment of a capture. They name no sensor
     * size, so the 35mm-equivalent focal length comes from the angle of view they report.
     */
    @SuppressWarnings("deprecation")
    @NonNull
    public static ExifCaptureParameters forLegacyCamera(@NonNull Camera.Parameters camera) {
        ExifCaptureParameters parameters = new ExifCaptureParameters();
        float focalLength = readOrZero(camera::getFocalLength);
        if (focalLength > 0) {
            parameters.mLenses.add(new Lens(new float[] {focalLength}, null, null));
        }
        // A camera1 camera can be a logical camera underneath, which zooms by changing lens.
        parameters.mZoomIsDigital = false;
        float step = readOrZero(camera::getExposureCompensationStep);
        if (step > 0) {
            parameters.mExposureStepNumerator = Math.round(step * RATIONAL_SCALE);
            parameters.mExposureStepDenominator = (long) RATIONAL_SCALE;
        }
        parameters.setExposureCompensation(camera.getExposureCompensation());
        if (camera.isZoomSupported()) {
            List<Integer> ratios = camera.getZoomRatios();
            int zoom = camera.getZoom();
            if (ratios != null && zoom >= 0 && zoom < ratios.size()) {
                // In hundredths, so 100 is no zoom at all.
                parameters.setZoomRatio(ratios.get(zoom) / 100f);
            }
        }
        int equivalent = equivalentFocalLength(readOrZero(camera::getHorizontalViewAngle),
                readOrZero(camera::getVerticalViewAngle));
        if (equivalent > 0) {
            parameters.mValues.put(
                    ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, Integer.toString(equivalent));
        }
        String whiteBalance = camera.getWhiteBalance();
        if (whiteBalance != null) {
            parameters.mValues.put(ExifInterface.TAG_WHITE_BALANCE,
                    Camera.Parameters.WHITE_BALANCE_AUTO.equals(whiteBalance)
                            ? WHITE_BALANCE_AUTO : WHITE_BALANCE_MANUAL);
        }
        List<String> flashModes = camera.getSupportedFlashModes();
        if (flashModes == null || flashModes.isEmpty()
                || (flashModes.size() == 1
                        && Camera.Parameters.FLASH_MODE_OFF.equals(flashModes.get(0)))) {
            parameters.mValues.put(ExifInterface.TAG_FLASH, Integer.toString(FLASH_NO_FUNCTION));
        } else if (Camera.Parameters.FLASH_MODE_OFF.equals(camera.getFlashMode())) {
            parameters.mValues.put(ExifInterface.TAG_FLASH,
                    Integer.toString(FLASH_MODE_COMPULSORY_SUPPRESSION));
        }
        parameters.putScene(legacyScene(camera.getSceneMode()));
        return parameters;
    }

    /** One of the legacy camera's decimal parameters. */
    private interface FloatParameter {
        float read();
    }

    /**
     * A decimal parameter, or zero where the camera left it out: each is parsed from a string the
     * camera provides, and parsing a missing one throws.
     */
    private static float readOrZero(@NonNull FloatParameter parameter) {
        try {
            return parameter.read();
        } catch (RuntimeException error) {
            return 0;
        }
    }

    /**
     * The 35mm-equivalent focal length for an angle of view, across the diagonal as for a lens,
     * or zero when the angles are not ones a lens could have.
     */
    @VisibleForTesting
    static int equivalentFocalLength(float horizontalDegrees, float verticalDegrees) {
        if (!(horizontalDegrees > 0 && horizontalDegrees < 180
                && verticalDegrees > 0 && verticalDegrees < 180)) {
            return 0;
        }
        double halfDiagonal = Math.hypot(Math.tan(Math.toRadians(horizontalDegrees) / 2),
                Math.tan(Math.toRadians(verticalDegrees) / 2));
        return (int) Math.round(FULL_FRAME_DIAGONAL_MM / (2 * halfDiagonal));
    }

    @Nullable
    private static String legacyScene(@Nullable String sceneMode) {
        if (sceneMode == null || Camera.Parameters.SCENE_MODE_AUTO.equals(sceneMode)) {
            return SCENE_STANDARD;
        }
        switch (sceneMode) {
            case Camera.Parameters.SCENE_MODE_LANDSCAPE:
                return SCENE_LANDSCAPE;
            case Camera.Parameters.SCENE_MODE_PORTRAIT:
                return SCENE_PORTRAIT;
            case Camera.Parameters.SCENE_MODE_NIGHT:
            case Camera.Parameters.SCENE_MODE_NIGHT_PORTRAIT:
                return SCENE_NIGHT;
            default:
                // A sports or a party scene is none of the four the tag can say.
                return null;
        }
    }

    /**
     * How far exposure compensation was set, as an index in the camera's own steps rather than in
     * EV. The tag is written as the index times the step the camera reports, so index 4 in steps
     * of 1/6 is +2/3 EV. An index of 0 is written as no bias on any camera; any other index is
     * left out where the camera reported no step, since what it is worth in EV is unknown.
     */
    @NonNull
    public ExifCaptureParameters setExposureCompensation(int index) {
        if (index == 0) {
            mValues.put(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, NO_EXPOSURE_BIAS);
        } else if (mExposureStepDenominator != 0) {
            mValues.put(ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
                    index * mExposureStepNumerator + "/" + mExposureStepDenominator);
        } else {
            mValues.remove(ExifInterface.TAG_EXPOSURE_BIAS_VALUE);
        }
        return this;
    }

    /**
     * How far the picture was zoomed. Recorded as digital zoom only where it can only have been a
     * crop: at no zoom on any camera, and at any zoom on one with a single lens.
     */
    @NonNull
    public ExifCaptureParameters setZoomRatio(float ratio) {
        if (Math.abs(ratio - 1) < 1f / RATIONAL_SCALE) {
            mValues.put(ExifInterface.TAG_DIGITAL_ZOOM_RATIO, NO_DIGITAL_ZOOM);
        } else if (mZoomIsDigital && ratio > 1) {
            mValues.put(ExifInterface.TAG_DIGITAL_ZOOM_RATIO, rational(ratio));
        } else {
            mValues.remove(ExifInterface.TAG_DIGITAL_ZOOM_RATIO);
        }
        return this;
    }

    /**
     * The flash setting. Off, or no flash at all, says it did not fire; on and auto do not say
     * whether it did, which is the camera's to report.
     */
    @NonNull
    public ExifCaptureParameters setFlash(@NonNull CameraView.Flash flash, boolean hasFlashUnit) {
        if (!hasFlashUnit) {
            mValues.put(ExifInterface.TAG_FLASH, Integer.toString(FLASH_NO_FUNCTION));
        } else if (flash == CameraView.Flash.OFF) {
            mValues.put(ExifInterface.TAG_FLASH,
                    Integer.toString(FLASH_MODE_COMPULSORY_SUPPRESSION));
        } else {
            mValues.remove(ExifInterface.TAG_FLASH);
        }
        return this;
    }

    /**
     * The photo mode the picture was taken in, as the scene it was taken for: a night scene, a
     * portrait for background blur, and a standard scene for the rest, which Exif has no name for.
     */
    @NonNull
    public ExifCaptureParameters setPhotoMode(@CameraView.PhotoMode int photoMode) {
        switch (photoMode) {
            case CameraView.PhotoMode.NIGHT:
                mPhotoModeScene = SCENE_NIGHT;
                break;
            case CameraView.PhotoMode.BACKGROUND_BLUR:
                mPhotoModeScene = SCENE_PORTRAIT;
                break;
            default:
                mPhotoModeScene = null;
                break;
        }
        putScene(mPhotoModeScene == null ? SCENE_STANDARD : mPhotoModeScene);
        return this;
    }

    private void putScene(@Nullable String scene) {
        if (scene == null) {
            mValues.remove(ExifInterface.TAG_SCENE_CAPTURE_TYPE);
        } else {
            mValues.put(ExifInterface.TAG_SCENE_CAPTURE_TYPE, scene);
        }
    }

    /**
     * What the camera reported for the frame a picture was made from: its exposure, sensitivity,
     * aperture, focal length, focus distance, flash, white balance and zoom. For a picture the
     * library encodes itself, whose file has none of these until they are copied in.
     */
    @RequiresApi(21)
    @NonNull
    public ExifCaptureParameters setCaptureResult(@NonNull CaptureResult result) {
        Long exposureNanos = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        if (exposureNanos != null && exposureNanos > 0) {
            mValues.put(ExifInterface.TAG_EXPOSURE_TIME, exposureTime(exposureNanos));
        }
        Integer sensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY);
        if (sensitivity != null && sensitivity > 0) {
            long iso = sensitivity;
            if (Build.VERSION.SDK_INT >= 24) {
                // The gain applied after the raw sensor, in hundredths, which is part of what a
                // reader means by ISO.
                Integer boost = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST);
                if (boost != null && boost > 0) {
                    iso = iso * boost / 100;
                }
            }
            mValues.put(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                    Long.toString(Math.min(iso, MAX_SHORT)));
        }
        Float aperture = result.get(CaptureResult.LENS_APERTURE);
        if (aperture != null && aperture > 0) {
            mValues.put(ExifInterface.TAG_F_NUMBER, rational(aperture));
        }
        Float focalLength = result.get(CaptureResult.LENS_FOCAL_LENGTH);
        if (focalLength != null && focalLength > 0) {
            mValues.put(ExifInterface.TAG_FOCAL_LENGTH, rational(focalLength));
        }
        Float diopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
        // Zero diopters is focus at infinity, which the tag can only say as a numerator that
        // ExifInterface will not write, so it is left out.
        if (mFocusDistanceCalibrated && diopters != null && diopters > 0) {
            mValues.put(ExifInterface.TAG_SUBJECT_DISTANCE, rational(1 / diopters));
        }
        Integer aeMode = result.get(CaptureResult.CONTROL_AE_MODE);
        if (aeMode != null) {
            boolean manual = aeMode == CameraMetadata.CONTROL_AE_MODE_OFF;
            mValues.put(ExifInterface.TAG_EXPOSURE_MODE,
                    manual ? EXPOSURE_MODE_MANUAL : EXPOSURE_MODE_AUTO);
            mValues.put(ExifInterface.TAG_EXPOSURE_PROGRAM,
                    manual ? EXPOSURE_PROGRAM_MANUAL : EXPOSURE_PROGRAM_NORMAL);
        }
        Integer flash = flashOf(result.get(CaptureResult.FLASH_STATE), aeMode,
                result.get(CaptureResult.FLASH_MODE));
        if (flash != null) {
            mValues.put(ExifInterface.TAG_FLASH, Integer.toString(flash));
        }
        Integer awbMode = result.get(CaptureResult.CONTROL_AWB_MODE);
        if (awbMode != null) {
            mValues.put(ExifInterface.TAG_WHITE_BALANCE,
                    awbMode == CameraMetadata.CONTROL_AWB_MODE_AUTO
                            ? WHITE_BALANCE_AUTO : WHITE_BALANCE_MANUAL);
        }
        Integer compensation = result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION);
        if (compensation != null) {
            setExposureCompensation(compensation);
        }
        Float zoom = zoomOf(result);
        if (zoom != null) {
            setZoomRatio(zoom);
        }
        Integer controlMode = result.get(CaptureResult.CONTROL_MODE);
        // A photo mode's scene stands: the frame a picture is drawn from reports the camera's
        // ordinary scene, knowing nothing of the processing done to it after.
        if (controlMode != null && mPhotoModeScene == null) {
            putScene(controlMode == CameraMetadata.CONTROL_MODE_USE_SCENE_MODE
                    ? sceneOf(result.get(CaptureResult.CONTROL_SCENE_MODE)) : SCENE_STANDARD);
        }
        return this;
    }

    /** An exposure as a rational of seconds: in nanoseconds where they fit, else microseconds. */
    @NonNull
    private static String exposureTime(long nanos) {
        if (nanos <= MAX_NANOS_IN_A_RATIONAL) {
            return nanos + "/" + NANOS_PER_SECOND;
        }
        return nanos / 1000 + "/" + NANOS_PER_SECOND / 1000;
    }

    /** The Flash tag for what a frame reports, or null when it does not say. */
    @VisibleForTesting
    @Nullable
    static Integer flashOf(@Nullable Integer state, @Nullable Integer aeMode,
            @Nullable Integer flashMode) {
        if (state == null) {
            return null;
        }
        if (state == CameraMetadata.FLASH_STATE_UNAVAILABLE) {
            return FLASH_NO_FUNCTION;
        }
        int value = state == CameraMetadata.FLASH_STATE_FIRED
                || state == CameraMetadata.FLASH_STATE_PARTIAL ? FLASH_FIRED : 0;
        if (aeMode != null) {
            switch (aeMode) {
                case CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE:
                    value |= FLASH_MODE_AUTO | FLASH_RED_EYE_REDUCTION;
                    break;
                case CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH:
                    value |= FLASH_MODE_AUTO;
                    break;
                case CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH:
                    value |= FLASH_MODE_COMPULSORY_FIRING;
                    break;
                default:
                    if (flashMode != null && flashMode == CameraMetadata.FLASH_MODE_OFF) {
                        value |= FLASH_MODE_COMPULSORY_SUPPRESSION;
                    }
                    break;
            }
        }
        return value;
    }

    /** How far a frame was zoomed, from its ratio or its crop of the active array. */
    @RequiresApi(21)
    @Nullable
    private Float zoomOf(@NonNull CaptureResult result) {
        if (Build.VERSION.SDK_INT >= 30) {
            Float ratio = result.get(CaptureResult.CONTROL_ZOOM_RATIO);
            if (ratio != null && ratio > 0) {
                return ratio;
            }
        }
        Rect crop = result.get(CaptureResult.SCALER_CROP_REGION);
        if (crop == null || mActiveArray == null || crop.width() <= 0 || crop.height() <= 0) {
            return null;
        }
        // The narrower of the two, since a crop to another aspect ratio is not a zoom.
        return Math.min((float) mActiveArray.width() / crop.width(),
                (float) mActiveArray.height() / crop.height());
    }

    @Nullable
    private static String sceneOf(@Nullable Integer sceneMode) {
        if (sceneMode == null) {
            return null;
        }
        switch (sceneMode) {
            case CameraMetadata.CONTROL_SCENE_MODE_DISABLED:
                return SCENE_STANDARD;
            case CameraMetadata.CONTROL_SCENE_MODE_LANDSCAPE:
                return SCENE_LANDSCAPE;
            case CameraMetadata.CONTROL_SCENE_MODE_PORTRAIT:
                return SCENE_PORTRAIT;
            case CameraMetadata.CONTROL_SCENE_MODE_NIGHT:
            case CameraMetadata.CONTROL_SCENE_MODE_NIGHT_PORTRAIT:
                return SCENE_NIGHT;
            default:
                return null;
        }
    }

    /**
     * Fills in every tag here that the file lacks, then what follows from the file's own values:
     * the lens that took it, and the APEX forms of its exposure and aperture. Call it before
     * {@link Exif#save()}.
     */
    public void applyTo(@NonNull Exif exif) {
        for (Map.Entry<String, String> value : mValues.entrySet()) {
            fill(exif, value.getKey(), value.getValue());
        }

        double focalLength = number(exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH));
        if (Double.isNaN(focalLength) && !mLogical && mLenses.size() == 1
                && mLenses.get(0).focalLengths.length == 1) {
            // One lens with one focal length: the picture can only have come through it.
            focalLength = mLenses.get(0).focalLengths[0];
            fill(exif, ExifInterface.TAG_FOCAL_LENGTH, rational(focalLength));
        }
        Lens lens = Double.isNaN(focalLength) ? null : lensWithFocalLength(focalLength);
        if (lens != null) {
            if (lens.apertures.length == 1) {
                fill(exif, ExifInterface.TAG_F_NUMBER, rational(lens.apertures[0]));
            }
            double widest = lens.widestAperture();
            if (!Double.isNaN(widest)) {
                fill(exif, ExifInterface.TAG_MAX_APERTURE_VALUE, rational(apex(widest)));
            }
            if (lens.sensorSize != null && lens.sensorSize[0] > 0 && lens.sensorSize[1] > 0) {
                long equivalent = Math.round(focalLength * FULL_FRAME_DIAGONAL_MM
                        / Math.hypot(lens.sensorSize[0], lens.sensorSize[1]));
                fill(exif, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
                        Long.toString(Math.min(equivalent, MAX_SHORT)));
            }
        }
        String specification = lensSpecification(mLenses);
        if (specification != null) {
            fill(exif, ExifInterface.TAG_LENS_SPECIFICATION, specification);
        }

        // The APEX values, which say the same as FNumber and ExposureTime on a log scale. Some
        // cameras write them as zero beside a real f-number, which is a placeholder rather than
        // f/1, so a zero that contradicts its source is replaced too.
        double fNumber = number(exif.getAttribute(ExifInterface.TAG_F_NUMBER));
        if (fNumber > 0 && contradicts(exif, ExifInterface.TAG_APERTURE_VALUE, fNumber)) {
            exif.setAttribute(ExifInterface.TAG_APERTURE_VALUE, rational(apex(fNumber)));
        }
        double exposure = number(exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME));
        if (exposure > 0 && contradicts(exif, ExifInterface.TAG_SHUTTER_SPEED_VALUE, exposure)) {
            exif.setAttribute(ExifInterface.TAG_SHUTTER_SPEED_VALUE,
                    rational(-Math.log(exposure) / Math.log(2)));
        }
    }

    /**
     * Whether an APEX tag is missing, or zero though its source is not the one value that makes
     * it zero.
     */
    private static boolean contradicts(@NonNull Exif exif, @NonNull String tag, double source) {
        String value = exif.getAttribute(tag);
        return value == null || (number(value) == 0 && source != 1);
    }

    /** Sets a tag where the file has none, or has the zero that stands for unknown. */
    private static void fill(@NonNull Exif exif, @NonNull String tag, @NonNull String value) {
        String existing = exif.getAttribute(tag);
        if (existing == null || (ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM.equals(tag)
                && number(existing) == 0)) {
            exif.setAttribute(tag, value);
        }
    }

    /** The one lens with this focal length, or null when none has it or more than one does. */
    @Nullable
    private Lens lensWithFocalLength(double focalLength) {
        Lens match = null;
        for (Lens lens : mLenses) {
            if (lens.hasFocalLength(focalLength)) {
                if (match != null) {
                    return null;
                }
                match = lens;
            }
        }
        return match;
    }

    /**
     * The shortest and longest focal lengths across the lenses, and the widest f-number at each,
     * as LensSpecification holds them. Null where either f-number is unknown: the standard writes
     * that as 0/0, which ExifInterface saves as 0/1, and a reader would take for f/0.
     */
    @VisibleForTesting
    @Nullable
    static String lensSpecification(@NonNull List<Lens> lenses) {
        double shortest = Double.NaN;
        double longest = Double.NaN;
        for (Lens lens : lenses) {
            for (float focalLength : lens.focalLengths) {
                if (focalLength <= 0) {
                    continue;
                }
                shortest = Double.isNaN(shortest) ? focalLength : Math.min(shortest, focalLength);
                longest = Double.isNaN(longest) ? focalLength : Math.max(longest, focalLength);
            }
        }
        if (Double.isNaN(shortest)) {
            return null;
        }
        double widestAtShortest = widestApertureAt(lenses, shortest);
        double widestAtLongest = widestApertureAt(lenses, longest);
        if (Double.isNaN(widestAtShortest) || Double.isNaN(widestAtLongest)) {
            return null;
        }
        return rational(shortest) + "," + rational(longest) + ","
                + rational(widestAtShortest) + "," + rational(widestAtLongest);
    }

    /** The widest f-number of any lens with this focal length, or NaN when none says. */
    private static double widestApertureAt(@NonNull List<Lens> lenses, double focalLength) {
        double widest = Double.NaN;
        for (Lens lens : lenses) {
            double aperture = lens.widestAperture();
            if (lens.hasFocalLength(focalLength) && !Double.isNaN(aperture)) {
                widest = Double.isNaN(widest) ? aperture : Math.min(widest, aperture);
            }
        }
        return widest;
    }

    /** An f-number as the APEX aperture value: two log2 of it. */
    private static double apex(double fNumber) {
        return 2 * Math.log(fNumber) / Math.log(2);
    }

    /**
     * A tag's value as a number, whether ExifInterface reads it as a rational or a decimal, or
     * NaN when it is absent or not a number.
     */
    @VisibleForTesting
    static double number(@Nullable String value) {
        if (value == null) {
            return Double.NaN;
        }
        try {
            int slash = value.indexOf('/');
            if (slash < 0) {
                return Double.parseDouble(value);
            }
            double denominator = Double.parseDouble(value.substring(slash + 1));
            return denominator == 0
                    ? Double.NaN : Double.parseDouble(value.substring(0, slash)) / denominator;
        } catch (NumberFormatException error) {
            return Double.NaN;
        }
    }

    @NonNull
    private static String rational(double value) {
        return String.format(Locale.US, "%d/%d", Math.round(value * RATIONAL_SCALE),
                RATIONAL_SCALE);
    }

    @VisibleForTesting
    @NonNull
    Map<String, String> values() {
        return mValues;
    }
}
