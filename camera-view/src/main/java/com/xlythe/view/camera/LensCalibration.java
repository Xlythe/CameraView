package com.xlythe.view.camera;

import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.os.Build;
import android.util.Size;
import android.util.SizeF;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import com.xlythe.view.camera.gl.FrameGeometry;
import com.xlythe.view.camera.gl.FrameRenderThread;
import com.xlythe.view.camera.gl.GlMath;

/**
 * The optics and the mounting of one camera, read from what it publishes about itself.
 *
 * <p>A nominal angle of view, worked out from the sensor's size and the lens's focal length,
 * assumes the optical centre is the middle of the sensor and the lens points straight out of the
 * back of the phone. Neither is quite true, and a marker drawn from those assumptions sits a few
 * pixels beside the thing it marks. A camera that has been calibrated says how far off it is -
 * {@code LENS_INTRINSIC_CALIBRATION} for the pinhole, {@code LENS_POSE_ROTATION} and
 * {@code LENS_POSE_TRANSLATION} for where it sits on the device - and this reads that, falling
 * back to the nominal figures only for a camera that publishes nothing better.</p>
 *
 * <p>The pinhole is kept in the region of the sensor processed streams are cut from: the active
 * array when the camera corrects its lens's distortion, which turns the picture into the ideal
 * pinhole the calibration describes, and the array before correction when it cannot. A stream is
 * a centred crop of that region, so {@link #streamIntrinsics} can say what any buffer of any
 * shape saw.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
@RequiresApi(21)
public final class LensCalibration {
    /** The focal length across the region, in its pixels. */
    private final float mFocalX;

    /** The focal length down the region, in its pixels. */
    private final float mFocalY;

    /** Where the optical axis meets the region, in pixels from its left. */
    private final float mPrincipalX;

    /** Where the optical axis meets the region, in pixels from its top. */
    private final float mPrincipalY;

    /** How far a row is sheared across, which is zero for every camera seen so far. */
    private final float mSkew;

    /** The width of the region streams are cut from, in pixels. */
    private final int mRegionWidth;

    /** The height of that region. */
    private final int mRegionHeight;

    /**
     * Whether the pinhole is the camera's own calibration rather than a nominal one, and so is
     * worth preferring over the angle of view worked out from the sensor's size.
     */
    private final boolean mCalibrated;

    /** Into the camera's space from the device's, as {@link GlMath#projection} expects it. */
    private final float[] mDeviceToCamera;

    @VisibleForTesting
    LensCalibration(float focalX, float focalY, float principalX, float principalY, float skew,
            int regionWidth, int regionHeight, boolean calibrated,
            @NonNull float[] deviceToCamera) {
        mFocalX = focalX;
        mFocalY = focalY;
        mPrincipalX = principalX;
        mPrincipalY = principalY;
        mSkew = skew;
        mRegionWidth = regionWidth;
        mRegionHeight = regionHeight;
        mCalibrated = calibrated;
        mDeviceToCamera = deviceToCamera.clone();
    }

    /**
     * Reads a camera's calibration, or returns null for one that publishes too little to place
     * anything by: no active array, or neither a calibration nor a focal length and sensor size.
     */
    @AnyThread
    @Nullable
    public static LensCalibration from(@NonNull CameraCharacteristics characteristics) {
        Rect active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (active == null || active.isEmpty()) {
            return null;
        }
        Rect preCorrection = Build.VERSION.SDK_INT >= 23 ? characteristics.get(
                CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE) : null;
        boolean corrected = Build.VERSION.SDK_INT >= 28 && correctsDistortion(characteristics.get(
                CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES));
        Rect region = corrected || preCorrection == null || preCorrection.isEmpty()
                ? active : preCorrection;

        float[] deviceToCamera = deviceToCamera(characteristics);
        float[] intrinsics = Build.VERSION.SDK_INT >= 23
                ? characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) : null;
        if (intrinsics != null && intrinsics.length >= 5
                && intrinsics[0] > 0f && intrinsics[1] > 0f) {
            // Published against the array before correction. A corrected stream maps that array
            // onto the active one by scaling alone, which is what the camera does with every
            // other coordinate it reports.
            float scaleX = 1f;
            float scaleY = 1f;
            if (region == active && preCorrection != null && !preCorrection.isEmpty()) {
                scaleX = (float) active.width() / preCorrection.width();
                scaleY = (float) active.height() / preCorrection.height();
            }
            return new LensCalibration(intrinsics[0] * scaleX, intrinsics[1] * scaleY,
                    intrinsics[2] * scaleX, intrinsics[3] * scaleY, intrinsics[4] * scaleX,
                    region.width(), region.height(), true, deviceToCamera);
        }

        float[] focalLengths =
                characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        SizeF physical = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (focalLengths == null || focalLengths.length == 0 || focalLengths[0] <= 0f
                || physical == null || physical.getWidth() <= 0f || physical.getHeight() <= 0f) {
            return null;
        }
        // The physical size covers the whole pixel array, so a pixel's pitch comes from that and
        // the focal length in pixels follows. The first focal length, as the angle of view takes
        // it: the one a zoom ratio of one means.
        Size pixelArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        float arrayWidth = pixelArray == null ? active.width() : pixelArray.getWidth();
        float arrayHeight = pixelArray == null ? active.height() : pixelArray.getHeight();
        return new LensCalibration(
                focalLengths[0] * arrayWidth / physical.getWidth(),
                focalLengths[0] * arrayHeight / physical.getHeight(),
                region.width() / 2f, region.height() / 2f, 0f,
                region.width(), region.height(), false, deviceToCamera);
    }

    /** Whether the camera can correct its lens's distortion, which it then does by default. */
    private static boolean correctsDistortion(@Nullable int[] modes) {
        if (modes == null) {
            return false;
        }
        for (int mode : modes) {
            if (mode != CameraMetadata.DISTORTION_CORRECTION_MODE_OFF) {
                return true;
            }
        }
        return false;
    }

    /**
     * Into the camera's space from the device's, from the pose the camera publishes or, failing
     * that, from which way it faces and how its sensor is turned.
     */
    @NonNull
    private static float[] deviceToCamera(@NonNull CameraCharacteristics characteristics) {
        Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        boolean front = facing != null && facing == CameraMetadata.LENS_FACING_FRONT;
        int sensorOrientation = orientation == null ? 0 : orientation;
        float[] rotation = null;
        float[] translation = null;
        if (Build.VERSION.SDK_INT >= 23 && isDeviceRelative(characteristics)) {
            rotation = characteristics.get(CameraCharacteristics.LENS_POSE_ROTATION);
            translation = characteristics.get(CameraCharacteristics.LENS_POSE_TRANSLATION);
        }
        return deviceToCameraMatrix(rotation, translation, sensorOrientation, front);
    }

    /**
     * Whether the published pose is relative to the device's own axes, which it is unless it says
     * otherwise: a car's camera reports against the car, which no sensor in the device reads in.
     */
    @RequiresApi(23)
    private static boolean isDeviceRelative(@NonNull CameraCharacteristics characteristics) {
        if (Build.VERSION.SDK_INT < 28) {
            return true;
        }
        Integer reference = characteristics.get(CameraCharacteristics.LENS_POSE_REFERENCE);
        return reference == null
                || reference == CameraMetadata.LENS_POSE_REFERENCE_PRIMARY_CAMERA
                || reference == CameraMetadata.LENS_POSE_REFERENCE_GYROSCOPE;
    }

    /**
     * Into the camera's GL eye space from the device's sensor coordinate system.
     *
     * <p>The camera publishes its rotation into a frame with x along the sensor's rows, y down
     * its columns and z out along the optical axis; GL eye space keeps x and turns the other two
     * round, looking down -z with y up. Without a published rotation, it is the one a camera
     * mounted square to the device has: looking out of the back or the front, with its sensor
     * turned by the orientation it reports, which is how far its picture is turned to stand
     * upright on the device's natural screen.</p>
     *
     * @param quaternion {x, y, z, w} from {@code LENS_POSE_ROTATION}, or null
     * @param translation the optical centre in metres from {@code LENS_POSE_TRANSLATION}, or null
     */
    @VisibleForTesting
    @NonNull
    static float[] deviceToCameraMatrix(@Nullable float[] quaternion,
            @Nullable float[] translation, int sensorOrientation, boolean front) {
        float[][] rotation = quaternion == null ? null : rotationOf(quaternion);
        if (rotation == null) {
            rotation = mountedRotation(sensorOrientation, front);
        }
        float tx = 0f;
        float ty = 0f;
        float tz = 0f;
        if (translation != null && translation.length >= 3) {
            tx = translation[0];
            ty = translation[1];
            tz = translation[2];
        }
        float[] matrix = GlMath.identity();
        for (int row = 0; row < 3; row++) {
            // GL eye space is the published frame with y and z turned round.
            float sign = row == 0 ? 1f : -1f;
            float offset = 0f;
            for (int column = 0; column < 3; column++) {
                float value = sign * rotation[row][column];
                matrix[column * 4 + row] = value;
                offset -= value * (column == 0 ? tx : column == 1 ? ty : tz);
            }
            matrix[12 + row] = offset;
        }
        return matrix;
    }

    /** A unit quaternion {x, y, z, w} as a rotation matrix, or null for one that is not usable. */
    @Nullable
    private static float[][] rotationOf(@NonNull float[] quaternion) {
        if (quaternion.length < 4) {
            return null;
        }
        float x = quaternion[0];
        float y = quaternion[1];
        float z = quaternion[2];
        float w = quaternion[3];
        float norm = (float) Math.sqrt(x * x + y * y + z * z + w * w);
        if (!(norm > 1e-6f)) {
            return null;
        }
        x /= norm;
        y /= norm;
        z /= norm;
        w /= norm;
        return new float[][] {
                {1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)},
                {2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)},
                {2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)},
        };
    }

    /**
     * The rotation of a camera mounted square to the device, into the camera's published frame.
     *
     * <p>Held upright on the natural screen, a back camera's picture runs along the device's x
     * and down its -y and looks along its -z; a front camera's, unmirrored, runs along -x, as a
     * photograph of the person holding it does, and looks along +z. The sensor is that picture
     * turned back by the orientation it reports.</p>
     */
    @NonNull
    private static float[][] mountedRotation(int sensorOrientation, boolean front) {
        float[] across = front ? new float[] {-1, 0, 0} : new float[] {1, 0, 0};
        float[] down = {0, -1, 0};
        float[] out = front ? new float[] {0, 0, 1} : new float[] {0, 0, -1};
        double radians = Math.toRadians(((sensorOrientation % 360) + 360) % 360);
        float cos = (float) Math.round(Math.cos(radians));
        float sin = (float) Math.round(Math.sin(radians));
        float[][] rotation = new float[3][3];
        for (int column = 0; column < 3; column++) {
            rotation[0][column] = cos * across[column] + sin * down[column];
            rotation[1][column] = -sin * across[column] + cos * down[column];
            rotation[2][column] = out[column];
        }
        return rotation;
    }

    /**
     * What a buffer of a given size saw, as {@link GlMath#projection} takes it.
     *
     * <p>A stream is the region the camera is showing - the whole region, or the centred part a
     * zoom leaves - cropped about its centre to the stream's own shape and scaled to its size. So
     * the same lens has different intrinsics in a wide preview and a squarer photograph, and in
     * each as the zoom changes.</p>
     *
     * @param zoomRatio the zoom ratio the camera is running at, 1 for none
     * @param into receives {fx/w, fy/h, cx/w, cy/h, skew/w}
     * @return false for a buffer with no size, leaving {@code into} alone
     */
    @AnyThread
    public boolean streamIntrinsics(@NonNull Size buffer, float zoomRatio,
            @NonNull float[] into) {
        if (buffer.getWidth() <= 0 || buffer.getHeight() <= 0) {
            return false;
        }
        float zoom = zoomRatio > 0f ? zoomRatio : 1f;
        float width = mRegionWidth / zoom;
        float height = mRegionHeight / zoom;
        float left = (mRegionWidth - width) / 2f;
        float top = (mRegionHeight - height) / 2f;
        float aspect = (float) buffer.getWidth() / buffer.getHeight();
        if (aspect > width / height) {
            float cropped = width / aspect;
            top += (height - cropped) / 2f;
            height = cropped;
        } else {
            float cropped = height * aspect;
            left += (width - cropped) / 2f;
            width = cropped;
        }
        into[0] = mFocalX / width;
        into[1] = mFocalY / height;
        into[2] = (mPrincipalX - left) / width;
        into[3] = (mPrincipalY - top) / height;
        into[4] = mSkew / width;
        return true;
    }

    /**
     * The angle of view across the whole region in degrees, from the calibrated focal length,
     * or 0 when the camera publishes no calibration and the nominal figures are all there is.
     *
     * @param horizontal along the sensor's long side, as an angle of view is quoted
     */
    @AnyThread
    public float fieldOfViewDegrees(boolean horizontal) {
        if (!mCalibrated) {
            return 0f;
        }
        float extent = horizontal ? mRegionWidth : mRegionHeight;
        float focal = horizontal ? mFocalX : mFocalY;
        return (float) Math.toDegrees(2 * Math.atan(extent / (2 * focal)));
    }

    /** Into the camera's space from the device's, which is the view matrix outside AR. */
    @AnyThread
    @NonNull
    public float[] getDeviceToCameraMatrix() {
        return mDeviceToCamera.clone();
    }

    /**
     * Hands the calibration of whichever camera is open to the render thread, with the zoom it
     * is running at.
     */
    public static final class Geometry implements FrameRenderThread.GeometrySource {
        /** The open camera's calibration, or null while nothing is known. */
        @Nullable private volatile LensCalibration mCalibration;

        /** The zoom ratio the camera was last asked for, 1 for none. */
        private volatile float mZoomRatio = 1f;

        /** Scratch for each frame's intrinsics. Render thread only. */
        private final float[] mIntrinsics = new float[GlMath.INTRINSICS_SIZE];

        @AnyThread
        public void setCalibration(@Nullable LensCalibration calibration) {
            mCalibration = calibration;
        }

        /** The zoom ratio the camera is running at, however it gets there. */
        @AnyThread
        public void setZoomRatio(float zoomRatio) {
            mZoomRatio = zoomRatio;
        }

        @WorkerThread
        @Override
        public void onFrameGeometry(@NonNull FrameGeometry geometry, @Nullable Size bufferSize) {
            LensCalibration calibration = mCalibration;
            if (calibration == null) {
                return;
            }
            geometry.setViewMatrix(calibration.mDeviceToCamera);
            if (bufferSize != null
                    && calibration.streamIntrinsics(bufferSize, mZoomRatio, mIntrinsics)) {
                geometry.setNormalizedIntrinsics(mIntrinsics);
            }
        }
    }
}
