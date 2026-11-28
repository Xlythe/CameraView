package com.xlythe.view.camera;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.util.Size;
import android.util.SizeF;

import androidx.annotation.NonNull;

import com.xlythe.view.camera.gl.FrameGeometry;
import com.xlythe.view.camera.gl.GlMath;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowCameraCharacteristics;

/**
 * Reading where a camera's optical centre is and which way it faces, from what it publishes, and
 * what a buffer of any shape then saw.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public final class LensCalibrationTest {
    private static final float TOLERANCE = 1e-4f;

    /** A 4:3 sensor's active array. */
    private static final Rect ACTIVE = new Rect(0, 0, 4000, 3000);

    /** The array before distortion correction, a little larger, as a corrected camera has. */
    private static final Rect PRE_CORRECTION = new Rect(0, 0, 4040, 3030);

    /**
     * A back camera's published pose: a half turn about the diagonal between the device's x and
     * -y, which is a sensor mounted turned a quarter, as most phones' main cameras are.
     */
    private static final float[] BACK_POSE = {0.70710677f, -0.70710677f, 0f, 0f};

    /** A calibration off the middle of the array: {fx, fy, cx, cy, skew} in its pixels. */
    private static final float[] CALIBRATION = {3200f, 3210f, 2030f, 1480f, 0f};

    /** A calibrated camera that does not correct its lens, so its streams cut the whole array. */
    @NonNull
    private static CameraCharacteristics calibratedCamera() {
        CameraCharacteristics camera = ShadowCameraCharacteristics.newCameraCharacteristics();
        set(camera, CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE, ACTIVE);
        set(camera, CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE, ACTIVE);
        set(camera, CameraCharacteristics.LENS_INTRINSIC_CALIBRATION, CALIBRATION);
        set(camera, CameraCharacteristics.LENS_FACING, CameraMetadata.LENS_FACING_BACK);
        set(camera, CameraCharacteristics.SENSOR_ORIENTATION, 90);
        return camera;
    }

    @Test
    public void aCameraWithNoActiveArrayHasNoCalibration() {
        assertNull(LensCalibration.from(ShadowCameraCharacteristics.newCameraCharacteristics()));
        CameraCharacteristics empty = ShadowCameraCharacteristics.newCameraCharacteristics();
        set(empty, CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE, new Rect());
        assertNull(LensCalibration.from(empty));
    }

    @Test
    public void aCameraThatPublishesNoOpticsHasNoCalibration() {
        CameraCharacteristics camera = ShadowCameraCharacteristics.newCameraCharacteristics();
        set(camera, CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE, ACTIVE);
        assertNull(LensCalibration.from(camera));
        set(camera, CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS, new float[] {5f});
        assertNull("a focal length with no sensor to measure it against says nothing",
                LensCalibration.from(camera));
    }

    /** The calibrated focal length gives the angle of view, which a nominal one would not. */
    @Test
    public void aCalibratedLensGivesItsOwnAngleOfView() {
        LensCalibration calibration = LensCalibration.from(calibratedCamera());
        assertNotNull(calibration);
        assertEquals(Math.toDegrees(2 * Math.atan(4000 / (2.0 * 3200))),
                calibration.fieldOfViewDegrees(true), TOLERANCE);
        assertEquals(Math.toDegrees(2 * Math.atan(3000 / (2.0 * 3210))),
                calibration.fieldOfViewDegrees(false), TOLERANCE);
    }

    /** A camera with only a nominal lens is placed by it, but does not claim an angle of view. */
    @Test
    public void aNominalLensIsCentredAndClaimsNoAngle() {
        CameraCharacteristics camera = ShadowCameraCharacteristics.newCameraCharacteristics();
        set(camera, CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE, ACTIVE);
        set(camera, CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS, new float[] {4f});
        set(camera, CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE, new SizeF(6.4f, 4.8f));
        set(camera, CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE, new Size(4000, 3000));
        LensCalibration calibration = LensCalibration.from(camera);
        assertNotNull(calibration);
        assertEquals(0f, calibration.fieldOfViewDegrees(true), 0f);

        float[] intrinsics = new float[GlMath.INTRINSICS_SIZE];
        assertTrue(calibration.streamIntrinsics(new Size(4000, 3000), 1f, intrinsics));
        assertArrayEquals(new float[] {4f / 6.4f, 4f / 4.8f, 0.5f, 0.5f, 0f}, intrinsics,
                TOLERANCE);
    }

    /**
     * A camera that corrects its lens writes the active array as the ideal pinhole, so a
     * calibration published against the larger array before correction is scaled onto it.
     */
    @Test
    public void aCorrectedLensIsScaledOntoTheActiveArray() {
        CameraCharacteristics camera = calibratedCamera();
        set(camera, CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE,
                PRE_CORRECTION);
        set(camera, CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES, new int[] {
                CameraMetadata.DISTORTION_CORRECTION_MODE_OFF,
                CameraMetadata.DISTORTION_CORRECTION_MODE_FAST});
        LensCalibration calibration = LensCalibration.from(camera);
        assertNotNull(calibration);

        float[] intrinsics = new float[GlMath.INTRINSICS_SIZE];
        assertTrue(calibration.streamIntrinsics(new Size(4000, 3000), 1f, intrinsics));
        float scaleX = 4000f / 4040f;
        float scaleY = 3000f / 3030f;
        assertArrayEquals(new float[] {
                3200f * scaleX / 4000f, 3210f * scaleY / 3000f,
                2030f * scaleX / 4000f, 1480f * scaleY / 3000f, 0f}, intrinsics, TOLERANCE);
    }

    /** A camera that cannot correct its lens keeps the array its calibration was taken on. */
    @Test
    public void anUncorrectedLensKeepsTheArrayBeforeCorrection() {
        CameraCharacteristics camera = calibratedCamera();
        set(camera, CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE,
                PRE_CORRECTION);
        set(camera, CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES,
                new int[] {CameraMetadata.DISTORTION_CORRECTION_MODE_OFF});
        LensCalibration calibration = LensCalibration.from(camera);
        assertNotNull(calibration);

        float[] intrinsics = new float[GlMath.INTRINSICS_SIZE];
        assertTrue(calibration.streamIntrinsics(new Size(4040, 3030), 1f, intrinsics));
        assertArrayEquals(new float[] {3200f / 4040f, 3210f / 3030f, 2030f / 4040f,
                1480f / 3030f, 0f}, intrinsics, TOLERANCE);
    }

    /**
     * A wide buffer from a 4:3 sensor is a band across the middle of it, so it sees as far across
     * and less far down - and its principal point moves with the band.
     */
    @Test
    public void aWideBufferIsABandAcrossTheMiddle() {
        LensCalibration calibration = LensCalibration.from(calibratedCamera());
        assertNotNull(calibration);
        float[] intrinsics = new float[GlMath.INTRINSICS_SIZE];
        assertTrue(calibration.streamIntrinsics(new Size(1920, 1080), 1f, intrinsics));
        float bandHeight = 4000f * 1080f / 1920f;
        float bandTop = (3000f - bandHeight) / 2f;
        assertArrayEquals(new float[] {3200f / 4000f, 3210f / bandHeight, 2030f / 4000f,
                (1480f - bandTop) / bandHeight, 0f}, intrinsics, TOLERANCE);
    }

    /** A tall buffer is a column down the middle. */
    @Test
    public void aTallBufferIsAColumnDownTheMiddle() {
        LensCalibration calibration = LensCalibration.from(calibratedCamera());
        assertNotNull(calibration);
        float[] intrinsics = new float[GlMath.INTRINSICS_SIZE];
        assertTrue(calibration.streamIntrinsics(new Size(1000, 1500), 1f, intrinsics));
        float columnWidth = 3000f * 1000f / 1500f;
        float columnLeft = (4000f - columnWidth) / 2f;
        assertEquals(3200f / columnWidth, intrinsics[0], TOLERANCE);
        assertEquals((2030f - columnLeft) / columnWidth, intrinsics[2], TOLERANCE);
    }

    /** Zooming in shows the middle of the array, so the lens is longer in the buffer's terms. */
    @Test
    public void aZoomedBufferSeesTheMiddle() {
        LensCalibration calibration = LensCalibration.from(calibratedCamera());
        assertNotNull(calibration);
        float[] intrinsics = new float[GlMath.INTRINSICS_SIZE];
        assertTrue(calibration.streamIntrinsics(new Size(4000, 3000), 2f, intrinsics));
        assertArrayEquals(new float[] {3200f / 2000f, 3210f / 1500f, (2030f - 1000f) / 2000f,
                (1480f - 750f) / 1500f, 0f}, intrinsics, TOLERANCE);
        assertTrue("a ratio that is not one is no zoom",
                calibration.streamIntrinsics(new Size(4000, 3000), 0f, intrinsics));
        assertEquals(3200f / 4000f, intrinsics[0], TOLERANCE);
    }

    @Test
    public void aBufferWithNoSizeSawNothing() {
        LensCalibration calibration = LensCalibration.from(calibratedCamera());
        assertNotNull(calibration);
        float[] intrinsics = {9f, 9f, 9f, 9f, 9f};
        assertFalse(calibration.streamIntrinsics(new Size(0, 1080), 1f, intrinsics));
        assertFalse(calibration.streamIntrinsics(new Size(1920, 0), 1f, intrinsics));
        assertArrayEquals(new float[] {9f, 9f, 9f, 9f, 9f}, intrinsics, 0f);
    }

    /**
     * The published pose and the one a camera mounted square to the device would have agree for a
     * main camera turned a quarter - which is what makes the fallback safe to use.
     */
    @Test
    public void thePublishedPoseAgreesWithTheMountingItDescribes() {
        assertArrayEquals(
                LensCalibration.deviceToCameraMatrix(null, null, 90, false),
                LensCalibration.deviceToCameraMatrix(BACK_POSE, null, 90, false), TOLERANCE);
    }

    /**
     * A back camera turned a quarter looks out of the back of the device, with the top of its
     * picture towards the right edge of the screen held upright: its picture is turned a quarter
     * clockwise to be shown, which takes its top edge to the right.
     */
    @Test
    public void aBackCameraLooksOutOfTheBack() {
        float[] matrix = LensCalibration.deviceToCameraMatrix(null, null, 90, false);
        // Out of the back is the device's -z, which the camera looks down as its own -z.
        assertArrayEquals(new float[] {0f, 0f, -1f}, apply(matrix, 0f, 0f, -1f), TOLERANCE);
        // Up its picture, which is GL's +y, is the device's +x.
        assertArrayEquals(new float[] {0f, 1f, 0f}, apply(matrix, 1f, 0f, 0f), TOLERANCE);
        // Across its picture, GL's +x, is down the screen.
        assertArrayEquals(new float[] {1f, 0f, 0f}, apply(matrix, 0f, -1f, 0f), TOLERANCE);
    }

    /** A front camera looks out of the screen. */
    @Test
    public void aFrontCameraLooksOutOfTheScreen() {
        float[] matrix = LensCalibration.deviceToCameraMatrix(null, null, 270, true);
        assertArrayEquals(new float[] {0f, 0f, -1f}, apply(matrix, 0f, 0f, 1f), TOLERANCE);
    }

    /** A camera mounted the way the screen is has its picture upright on it. */
    @Test
    public void anUnturnedSensorIsUprightOnTheDevice() {
        float[] matrix = LensCalibration.deviceToCameraMatrix(null, null, 0, false);
        assertArrayEquals(new float[] {1f, 0f, 0f}, apply(matrix, 1f, 0f, 0f), TOLERANCE);
        assertArrayEquals(new float[] {0f, 1f, 0f}, apply(matrix, 0f, 1f, 0f), TOLERANCE);
    }

    /** Where the lens sits on the device is where the camera's space starts. */
    @Test
    public void theLensPositionIsTheCamerasOrigin() {
        float[] translation = {0.01f, 0.06f, -0.004f};
        float[] matrix = LensCalibration.deviceToCameraMatrix(BACK_POSE, translation, 90, false);
        assertArrayEquals(new float[] {0f, 0f, 0f},
                apply(matrix, translation[0], translation[1], translation[2]), TOLERANCE);
    }

    /** A quaternion with no length is no rotation, and the mounting stands in for it. */
    @Test
    public void anUnusableQuaternionFallsBackToTheMounting() {
        assertArrayEquals(LensCalibration.deviceToCameraMatrix(null, null, 90, false),
                LensCalibration.deviceToCameraMatrix(new float[4], null, 90, false), TOLERANCE);
        assertArrayEquals(LensCalibration.deviceToCameraMatrix(null, null, 90, false),
                LensCalibration.deviceToCameraMatrix(new float[2], null, 90, false), TOLERANCE);
    }

    /** A pose reported against something other than the device is not used. */
    @Test
    @Config(sdk = 37)
    public void aPoseAgainstSomethingElseIsNotTheDevices() {
        CameraCharacteristics camera = calibratedCamera();
        set(camera, CameraCharacteristics.LENS_POSE_ROTATION, new float[] {0f, 0f, 0f, 1f});
        set(camera, CameraCharacteristics.LENS_POSE_REFERENCE,
                CameraMetadata.LENS_POSE_REFERENCE_AUTOMOTIVE);
        LensCalibration calibration = LensCalibration.from(camera);
        assertNotNull(calibration);
        assertArrayEquals(LensCalibration.deviceToCameraMatrix(null, null, 90, false),
                calibration.getDeviceToCameraMatrix(), TOLERANCE);

        set(camera, CameraCharacteristics.LENS_POSE_REFERENCE,
                CameraMetadata.LENS_POSE_REFERENCE_PRIMARY_CAMERA);
        LensCalibration devices = LensCalibration.from(camera);
        assertNotNull(devices);
        assertArrayEquals("an identity rotation is used as published",
                LensCalibration.deviceToCameraMatrix(new float[] {0f, 0f, 0f, 1f}, null, 90,
                        false), devices.getDeviceToCameraMatrix(), TOLERANCE);
    }

    /** The geometry handed to the renderer is the open camera's, for the buffer being drawn. */
    @Test
    public void theRendererIsToldTheOpenCamerasGeometry() {
        LensCalibration.Geometry source = new LensCalibration.Geometry();
        FrameGeometry geometry = new FrameGeometry();

        source.onFrameGeometry(geometry, new Size(4000, 3000));
        assertFalse("nothing is known before a camera opens", geometry.hasIntrinsics());
        assertFalse(geometry.hasViewMatrix());

        LensCalibration calibration = LensCalibration.from(calibratedCamera());
        source.setCalibration(calibration);
        source.setZoomRatio(2f);
        source.onFrameGeometry(geometry, new Size(4000, 3000));
        assertTrue(geometry.hasIntrinsics());
        assertEquals(3200f / 2000f, geometry.getIntrinsics()[0], TOLERANCE);
        assertTrue(geometry.hasViewMatrix());
        assertNotNull(calibration);
        assertArrayEquals(calibration.getDeviceToCameraMatrix(), geometry.getViewMatrix(), 0f);

        geometry.clear();
        source.onFrameGeometry(geometry, null);
        assertFalse("a buffer of unknown size has no intrinsics", geometry.hasIntrinsics());
        assertTrue("but the camera is still where it is", geometry.hasViewMatrix());
    }

    @NonNull
    private static float[] apply(@NonNull float[] matrix, float x, float y, float z) {
        float[] out = new float[3];
        float[] point = {x, y, z, 1f};
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 4; column++) {
                out[row] += matrix[column * 4 + row] * point[column];
            }
        }
        return out;
    }

    private static <T> void set(@NonNull CameraCharacteristics camera,
            @NonNull CameraCharacteristics.Key<T> key, @NonNull T value) {
        ShadowCameraCharacteristics shadow = Shadow.extract(camera);
        shadow.set(key, value);
    }
}
