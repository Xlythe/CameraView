package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.Camera;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureResult;
import android.os.Build;
import android.util.Rational;
import android.util.SizeF;

import androidx.exifinterface.media.ExifInterface;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowCameraCharacteristics;
import org.robolectric.shadows.ShadowCameraManager;
import org.robolectric.shadows.ShadowCaptureResult;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** What the library fills in about how a picture was taken, and that it never overwrites. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class ExifCaptureParametersTest {
    private Exif mExif;

    @Before
    public void setUp() throws IOException {
        mExif = new Exif(Mockito.mock(InputStream.class));
    }

    /** A camera with one fixed lens describes everything about the lens a picture came through. */
    @Test
    public void aSingleLensDescribesThePicture() {
        CameraCharacteristics camera = camera(4.38f, 1.8f, new SizeF(5.6f, 4.2f));
        characteristic(camera, CameraCharacteristics.FLASH_INFO_AVAILABLE, false);
        characteristic(camera, CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT,
                CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB);

        ExifCaptureParameters.forCamera(camera, Collections.emptyList()).applyTo(mExif);

        assertEquals(4.38, number(ExifInterface.TAG_FOCAL_LENGTH), 0.001);
        assertEquals(1.8, number(ExifInterface.TAG_F_NUMBER), 0.001);
        // 4.38mm on a sensor with a 7mm diagonal frames like 27mm on 35mm film.
        assertEquals("27", mExif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM));
        assertEquals(1.696, number(ExifInterface.TAG_MAX_APERTURE_VALUE), 0.001);
        assertEquals(1.696, number(ExifInterface.TAG_APERTURE_VALUE), 0.001);
        assertEquals("4380/1000,4380/1000,1800/1000,1800/1000",
                mExif.getAttribute(ExifInterface.TAG_LENS_SPECIFICATION));
        assertEquals("32", mExif.getAttribute(ExifInterface.TAG_FLASH));
        assertEquals("2", mExif.getAttribute(ExifInterface.TAG_SENSING_METHOD));
        assertEquals("0", mExif.getAttribute(ExifInterface.TAG_EXPOSURE_MODE));
        assertEquals("2", mExif.getAttribute(ExifInterface.TAG_EXPOSURE_PROGRAM));
        assertEquals("0", mExif.getAttribute(ExifInterface.TAG_WHITE_BALANCE));
        assertEquals("0", mExif.getAttribute(ExifInterface.TAG_SCENE_CAPTURE_TYPE));
    }

    /** What the camera wrote stands, every value of it. */
    @Test
    public void theCamerasOwnValuesStand() {
        mExif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "6810/1000");
        mExif.setAttribute(ExifInterface.TAG_F_NUMBER, "1.85");
        mExif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, "24");
        mExif.setAttribute(ExifInterface.TAG_EXPOSURE_MODE, "1");
        mExif.setAttribute(ExifInterface.TAG_APERTURE_VALUE, "1770/1000");

        ExifCaptureParameters.forCamera(camera(4.38f, 1.8f, new SizeF(5.6f, 4.2f)),
                Collections.emptyList()).applyTo(mExif);

        assertEquals(6.81, number(ExifInterface.TAG_FOCAL_LENGTH), 0.001);
        assertEquals(1.85, number(ExifInterface.TAG_F_NUMBER), 0.001);
        assertEquals("24", mExif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM));
        assertEquals("1", mExif.getAttribute(ExifInterface.TAG_EXPOSURE_MODE));
        assertEquals(1.77, number(ExifInterface.TAG_APERTURE_VALUE), 0.001);
    }

    /** Zero is how FocalLengthIn35mmFilm says "unknown", so a zero is filled in. */
    @Test
    public void anUnknownEquivalentFocalLengthIsFilledIn() {
        mExif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, "0");

        ExifCaptureParameters.forCamera(camera(4.38f, 1.8f, new SizeF(5.6f, 4.2f)),
                Collections.emptyList()).applyTo(mExif);

        assertEquals("27", mExif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM));
    }

    /** A logical camera's lens is the one whose focal length the file names. */
    @Test
    public void aLogicalCameraFindsTheLensFromTheFocalLength() {
        mExif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "6800/1000");

        ExifCaptureParameters.forCamera(logicalCamera(), physicalCameras()).applyTo(mExif);

        assertEquals(1.7, number(ExifInterface.TAG_F_NUMBER), 0.001);
        // 6.8mm on the main sensor's 12.25mm diagonal.
        assertEquals("24", mExif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM));
        assertEquals("2200/1000,19000/1000,2200/1000,3500/1000",
                mExif.getAttribute(ExifInterface.TAG_LENS_SPECIFICATION));
    }

    /** Without a focal length in the file, there is no knowing which lens took it. */
    @Test
    public void aLogicalCameraGuessesNoLens() {
        ExifCaptureParameters.forCamera(logicalCamera(), physicalCameras()).applyTo(mExif);

        assertNull(mExif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH));
        assertNull(mExif.getAttribute(ExifInterface.TAG_F_NUMBER));
        assertNull(mExif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM));
        assertNull(mExif.getAttribute(ExifInterface.TAG_MAX_APERTURE_VALUE));
        assertEquals("2200/1000,19000/1000,2200/1000,3500/1000",
                mExif.getAttribute(ExifInterface.TAG_LENS_SPECIFICATION));
    }

    /** Two lenses with the same focal length cannot be told apart. */
    @Test
    public void twoLensesWithOneFocalLengthAreNotToldApart() {
        mExif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "6800/1000");

        ExifCaptureParameters.forCamera(logicalCamera(), Arrays.asList(
                camera(6.8f, 1.7f, new SizeF(9.8f, 7.35f)),
                camera(6.8f, 2.0f, new SizeF(5.6f, 4.2f)))).applyTo(mExif);

        assertNull(mExif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM));
        assertNull(mExif.getAttribute(ExifInterface.TAG_F_NUMBER));
    }

    /** A lens with no sensor size gives no equivalent, and one with no aperture no f-number. */
    @Test
    public void aLensWithoutItsSensorOrApertureSaysWhatItCan() {
        CameraCharacteristics camera = ShadowCameraCharacteristics.newCameraCharacteristics();
        characteristic(camera,
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS, new float[] {5f});

        ExifCaptureParameters.forCamera(camera, Collections.emptyList()).applyTo(mExif);

        assertEquals(5, number(ExifInterface.TAG_FOCAL_LENGTH), 0.001);
        assertNull(mExif.getAttribute(ExifInterface.TAG_F_NUMBER));
        assertNull(mExif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM));
        assertNull(mExif.getAttribute(ExifInterface.TAG_SENSING_METHOD));
        assertNull("a flash nobody mentioned is not a flash that is absent",
                mExif.getAttribute(ExifInterface.TAG_FLASH));
    }

    /** A camera with nothing to say about its lens still says how it exposed. */
    @Test
    public void aCameraWithNoOpticsStillFillsTheDefaults() {
        new ExifCaptureParameters().applyTo(mExif);

        assertNull(mExif.getAttribute(ExifInterface.TAG_LENS_SPECIFICATION));
        assertEquals("2", mExif.getAttribute(ExifInterface.TAG_EXPOSURE_PROGRAM));
    }

    /** The shortest and longest focal lengths, and an f-number per end where it is known. */
    @Test
    public void theLensSpecificationCoversEveryLens() {
        ExifCaptureParameters.Lens zoom = new ExifCaptureParameters.Lens(
                new float[] {4f, 12f}, new float[] {2f, 4f}, null);
        assertNull("a zoom whose aperture changes does not say which goes where",
                ExifCaptureParameters.lensSpecification(Collections.singletonList(zoom)));

        ExifCaptureParameters.Lens constant = new ExifCaptureParameters.Lens(
                new float[] {4f, 12f}, new float[] {2.8f}, null);
        assertEquals("4000/1000,12000/1000,2800/1000,2800/1000",
                ExifCaptureParameters.lensSpecification(Collections.singletonList(constant)));

        ExifCaptureParameters.Lens variable = new ExifCaptureParameters.Lens(
                new float[] {5f}, new float[] {2.4f, 1.5f}, null);
        assertEquals("a fixed lens with a variable aperture is widest at its widest",
                "5000/1000,5000/1000,1500/1000,1500/1000",
                ExifCaptureParameters.lensSpecification(Collections.singletonList(variable)));

        assertNull(ExifCaptureParameters.lensSpecification(Collections.emptyList()));
        assertNull(ExifCaptureParameters.lensSpecification(Collections.singletonList(
                new ExifCaptureParameters.Lens(new float[] {0f}, null, null))));
    }

    /** No zoom is no zoom on any camera; a zoom is digital only where it can only be a crop. */
    @Test
    public void digitalZoomIsOnlyRecordedWhereItIsCertain() {
        ExifCaptureParameters single = ExifCaptureParameters.forCamera(
                camera(4.38f, 1.8f, null), Collections.emptyList());
        assertEquals("1/1", single.setZoomRatio(1f).values()
                .get(ExifInterface.TAG_DIGITAL_ZOOM_RATIO));
        assertEquals("2500/1000", single.setZoomRatio(2.5f).values()
                .get(ExifInterface.TAG_DIGITAL_ZOOM_RATIO));

        ExifCaptureParameters logical =
                ExifCaptureParameters.forCamera(logicalCamera(), physicalCameras());
        assertEquals("1/1", logical.setZoomRatio(1f).values()
                .get(ExifInterface.TAG_DIGITAL_ZOOM_RATIO));
        assertNull("a telephoto is not a crop", logical.setZoomRatio(3f).values()
                .get(ExifInterface.TAG_DIGITAL_ZOOM_RATIO));
        assertNull("nor is an ultra-wide", logical.setZoomRatio(0.6f).values()
                .get(ExifInterface.TAG_DIGITAL_ZOOM_RATIO));
    }

    /** The bias is the compensation index in the camera's own steps. */
    @Test
    public void theExposureBiasIsTheIndexTimesTheStep() {
        CameraCharacteristics camera = camera(4.38f, 1.8f, null);
        characteristic(camera,
                CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP, new Rational(1, 3));
        ExifCaptureParameters parameters =
                ExifCaptureParameters.forCamera(camera, Collections.emptyList());

        assertEquals("0/1", parameters.setExposureCompensation(0).values()
                .get(ExifInterface.TAG_EXPOSURE_BIAS_VALUE));
        assertEquals("-2/3", parameters.setExposureCompensation(-2).values()
                .get(ExifInterface.TAG_EXPOSURE_BIAS_VALUE));

        parameters.applyTo(mExif);
        assertEquals(-0.667, number(ExifInterface.TAG_EXPOSURE_BIAS_VALUE), 0.001);
    }

    /** Without a step there is no saying how far an index went, though zero is still zero. */
    @Test
    public void anIndexWithoutAStepIsLeftOut() {
        ExifCaptureParameters parameters = new ExifCaptureParameters();

        assertNull(parameters.setExposureCompensation(2).values()
                .get(ExifInterface.TAG_EXPOSURE_BIAS_VALUE));
        assertEquals("0/1", parameters.setExposureCompensation(0).values()
                .get(ExifInterface.TAG_EXPOSURE_BIAS_VALUE));
    }

    /** No flash, or a flash turned off, did not fire; on and auto are the camera's to say. */
    @Test
    public void theFlashIsRecordedWhereItCertainlyDidNotFire() {
        ExifCaptureParameters parameters = new ExifCaptureParameters();

        assertEquals("32", parameters.setFlash(CameraView.Flash.AUTO, false).values()
                .get(ExifInterface.TAG_FLASH));
        assertEquals("16", parameters.setFlash(CameraView.Flash.OFF, true).values()
                .get(ExifInterface.TAG_FLASH));
        assertNull(parameters.setFlash(CameraView.Flash.ON, true).values()
                .get(ExifInterface.TAG_FLASH));
    }

    /**
     * A night mode is a night scene, background blur a portrait, and every other mode, Exif having
     * no name for it, a standard scene.
     */
    @Test
    public void aPhotoModeCanBeAScene() {
        ExifCaptureParameters parameters = new ExifCaptureParameters();

        assertEquals("3", parameters.setPhotoMode(CameraView.PhotoMode.NIGHT).values()
                .get(ExifInterface.TAG_SCENE_CAPTURE_TYPE));
        assertEquals("2", parameters.setPhotoMode(CameraView.PhotoMode.BACKGROUND_BLUR).values()
                .get(ExifInterface.TAG_SCENE_CAPTURE_TYPE));
        assertEquals("0", parameters.setPhotoMode(CameraView.PhotoMode.HDR).values()
                .get(ExifInterface.TAG_SCENE_CAPTURE_TYPE));
        assertEquals("0", parameters.setPhotoMode(CameraView.PhotoMode.NONE).values()
                .get(ExifInterface.TAG_SCENE_CAPTURE_TYPE));
    }

    /**
     * A photo mode's scene stands over the one the frame reports, since a picture drawn through
     * the library's blur comes from a frame the camera took as an ordinary one.
     */
    @Test
    public void aPhotoModesSceneStandsOverTheFramesOwn() {
        CaptureResult ordinary = Mockito.mock(CaptureResult.class);
        Mockito.when(ordinary.get(CaptureResult.CONTROL_MODE))
                .thenReturn(CameraMetadata.CONTROL_MODE_AUTO);

        assertEquals("2", new ExifCaptureParameters()
                .setPhotoMode(CameraView.PhotoMode.BACKGROUND_BLUR)
                .setCaptureResult(ordinary).values().get(ExifInterface.TAG_SCENE_CAPTURE_TYPE));
        assertEquals("0", new ExifCaptureParameters()
                .setPhotoMode(CameraView.PhotoMode.NONE)
                .setCaptureResult(ordinary).values().get(ExifInterface.TAG_SCENE_CAPTURE_TYPE));
    }

    /** What a frame reports about its exposure goes in, for a picture the library encoded. */
    @Test
    public void aCaptureResultDescribesItsFrame() {
        CameraCharacteristics camera = camera(4.38f, 1.8f, new SizeF(5.6f, 4.2f));
        characteristic(camera,
                CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP, new Rational(1, 2));
        characteristic(camera, CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION,
                CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE);
        CaptureResult result = ShadowCaptureResult.newCaptureResult();
        ShadowCaptureResult frame = Shadow.extract(result);
        frame.set(CaptureResult.SENSOR_EXPOSURE_TIME, 10_000_000L);
        frame.set(CaptureResult.SENSOR_SENSITIVITY, 400);
        frame.set(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST, 200);
        frame.set(CaptureResult.LENS_APERTURE, 1.8f);
        frame.set(CaptureResult.LENS_FOCAL_LENGTH, 4.38f);
        frame.set(CaptureResult.LENS_FOCUS_DISTANCE, 2f);
        frame.set(CaptureResult.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
        frame.set(CaptureResult.FLASH_STATE, CameraMetadata.FLASH_STATE_READY);
        frame.set(CaptureResult.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
        frame.set(CaptureResult.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT);
        frame.set(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION, -2);
        if (Build.VERSION.SDK_INT >= 30) {
            frame.set(CaptureResult.CONTROL_ZOOM_RATIO, 2f);
        } else {
            characteristic(camera, CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE,
                    new Rect(0, 0, 4000, 3000));
            frame.set(CaptureResult.SCALER_CROP_REGION, new Rect(1000, 750, 3000, 2250));
        }
        frame.set(CaptureResult.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

        ExifCaptureParameters.forCamera(camera, Collections.emptyList())
                .setCaptureResult(result)
                .applyTo(mExif);

        assertEquals(0.01, number(ExifInterface.TAG_EXPOSURE_TIME), 1e-9);
        assertEquals("ISO is the sensor's gain times the boost after it", "800",
                mExif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY));
        assertEquals(1.8, number(ExifInterface.TAG_F_NUMBER), 0.001);
        assertEquals(4.38, number(ExifInterface.TAG_FOCAL_LENGTH), 0.001);
        assertEquals("two diopters is half a metre", 0.5,
                number(ExifInterface.TAG_SUBJECT_DISTANCE), 0.001);
        assertEquals("16", mExif.getAttribute(ExifInterface.TAG_FLASH));
        assertEquals("1", mExif.getAttribute(ExifInterface.TAG_WHITE_BALANCE));
        assertEquals(-1, number(ExifInterface.TAG_EXPOSURE_BIAS_VALUE), 0.001);
        assertEquals(2, number(ExifInterface.TAG_DIGITAL_ZOOM_RATIO), 0.001);
        assertEquals("0", mExif.getAttribute(ExifInterface.TAG_SCENE_CAPTURE_TYPE));
        assertEquals("27", mExif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM));
        assertEquals("1/100s is 6.64 stops", 6.644,
                number(ExifInterface.TAG_SHUTTER_SPEED_VALUE), 0.001);
    }

    /** A manual exposure, an extreme gain and a long exposure are all written as they were. */
    @Test
    public void aManualFrameIsWrittenAsManual() {
        CaptureResult result = ShadowCaptureResult.newCaptureResult();
        ShadowCaptureResult frame = Shadow.extract(result);
        frame.set(CaptureResult.SENSOR_EXPOSURE_TIME, 30_000_000_000L);
        frame.set(CaptureResult.SENSOR_SENSITIVITY, 100_000);
        frame.set(CaptureResult.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
        frame.set(CaptureResult.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO);
        frame.set(CaptureResult.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE);
        frame.set(CaptureResult.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_NIGHT);

        new ExifCaptureParameters().setCaptureResult(result).applyTo(mExif);

        assertEquals(30, number(ExifInterface.TAG_EXPOSURE_TIME), 0.001);
        assertEquals("65535", mExif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY));
        assertEquals("1", mExif.getAttribute(ExifInterface.TAG_EXPOSURE_MODE));
        assertEquals("1", mExif.getAttribute(ExifInterface.TAG_EXPOSURE_PROGRAM));
        assertEquals("0", mExif.getAttribute(ExifInterface.TAG_WHITE_BALANCE));
        assertEquals("3", mExif.getAttribute(ExifInterface.TAG_SCENE_CAPTURE_TYPE));
        assertEquals("an exposure over a second has a negative shutter speed value", -4.907,
                number(ExifInterface.TAG_SHUTTER_SPEED_VALUE), 0.001);
    }

    /** Values a frame cannot vouch for are left out rather than guessed. */
    @Test
    public void whatAFrameCannotVouchForIsLeftOut() {
        CameraCharacteristics camera = camera(4.38f, 1.8f, null);
        characteristic(camera, CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION,
                CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED);
        CaptureResult result = ShadowCaptureResult.newCaptureResult();
        ShadowCaptureResult frame = Shadow.extract(result);
        frame.set(CaptureResult.LENS_FOCUS_DISTANCE, 2f);
        frame.set(CaptureResult.SENSOR_EXPOSURE_TIME, 0L);
        frame.set(CaptureResult.SENSOR_SENSITIVITY, 0);
        frame.set(CaptureResult.LENS_APERTURE, 0f);
        frame.set(CaptureResult.LENS_FOCAL_LENGTH, 0f);
        frame.set(CaptureResult.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE);
        frame.set(CaptureResult.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_ACTION);

        ExifCaptureParameters parameters = ExifCaptureParameters
                .forCamera(camera, Collections.emptyList())
                .setCaptureResult(result);

        assertFalse("relative diopters are no distance",
                parameters.values().containsKey(ExifInterface.TAG_SUBJECT_DISTANCE));
        assertFalse(parameters.values().containsKey(ExifInterface.TAG_EXPOSURE_TIME));
        assertFalse(parameters.values().containsKey(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY));
        assertFalse(parameters.values().containsKey(ExifInterface.TAG_F_NUMBER));
        assertFalse("an action scene is none of the four the tag names",
                parameters.values().containsKey(ExifInterface.TAG_SCENE_CAPTURE_TYPE));
    }

    /** Focus at infinity has no distance a rational can be written as. */
    @Test
    public void focusAtInfinityIsLeftOut() {
        CameraCharacteristics camera = camera(4.38f, 1.8f, null);
        characteristic(camera, CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION,
                CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED);
        CaptureResult result = ShadowCaptureResult.newCaptureResult();
        ShadowCaptureResult frame = Shadow.extract(result);
        frame.set(CaptureResult.LENS_FOCUS_DISTANCE, 0f);

        assertFalse(ExifCaptureParameters.forCamera(camera, Collections.emptyList())
                .setCaptureResult(result).values()
                .containsKey(ExifInterface.TAG_SUBJECT_DISTANCE));
    }

    /** Before the zoom ratio control, a frame's zoom is its crop of the active array. */
    @Test
    public void aCropRegionIsAZoom() {
        CameraCharacteristics camera = camera(4.38f, 1.8f, null);
        characteristic(camera,
                CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE, new Rect(0, 0, 4000, 3000));
        CaptureResult result = ShadowCaptureResult.newCaptureResult();
        ShadowCaptureResult frame = Shadow.extract(result);
        frame.set(CaptureResult.SCALER_CROP_REGION, new Rect(1000, 750, 3000, 2250));

        assertEquals("2000/1000", ExifCaptureParameters.forCamera(camera, Collections.emptyList())
                .setCaptureResult(result).values().get(ExifInterface.TAG_DIGITAL_ZOOM_RATIO));
    }

    /** A crop with nothing to measure it against, or of nothing, says nothing. */
    @Test
    public void anUnmeasurableCropIsNoZoom() {
        CaptureResult result = ShadowCaptureResult.newCaptureResult();
        ShadowCaptureResult frame = Shadow.extract(result);
        frame.set(CaptureResult.SCALER_CROP_REGION, new Rect(0, 0, 2000, 1500));
        assertNull(new ExifCaptureParameters().setCaptureResult(result).values()
                .get(ExifInterface.TAG_DIGITAL_ZOOM_RATIO));

        CameraCharacteristics camera = camera(4.38f, 1.8f, null);
        characteristic(camera,
                CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE, new Rect(0, 0, 4000, 3000));
        frame.set(CaptureResult.SCALER_CROP_REGION, new Rect(0, 0, 0, 0));
        assertNull(ExifCaptureParameters.forCamera(camera, Collections.emptyList())
                .setCaptureResult(result).values().get(ExifInterface.TAG_DIGITAL_ZOOM_RATIO));
    }

    /** The flash tag's bits, for every state a frame reports. */
    @Test
    public void aFramesFlashIsDecoded() {
        assertNull(ExifCaptureParameters.flashOf(null, null, null));
        assertEquals(Integer.valueOf(0x20), ExifCaptureParameters.flashOf(
                CameraMetadata.FLASH_STATE_UNAVAILABLE, null, null));
        assertEquals("fired, in auto", Integer.valueOf(0x19), ExifCaptureParameters.flashOf(
                CameraMetadata.FLASH_STATE_FIRED,
                CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH, null));
        assertEquals("fired, compulsorily", Integer.valueOf(0x09), ExifCaptureParameters.flashOf(
                CameraMetadata.FLASH_STATE_FIRED,
                CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH, null));
        assertEquals("fired partly, in auto with red-eye reduction", Integer.valueOf(0x59),
                ExifCaptureParameters.flashOf(CameraMetadata.FLASH_STATE_PARTIAL,
                        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE, null));
        assertEquals("not fired, suppressed", Integer.valueOf(0x10),
                ExifCaptureParameters.flashOf(CameraMetadata.FLASH_STATE_READY,
                        CameraMetadata.CONTROL_AE_MODE_ON, CameraMetadata.FLASH_MODE_OFF));
        assertEquals("not fired, mode unknown", Integer.valueOf(0),
                ExifCaptureParameters.flashOf(CameraMetadata.FLASH_STATE_CHARGING,
                        CameraMetadata.CONTROL_AE_MODE_ON, null));
        assertEquals(Integer.valueOf(0), ExifCaptureParameters.flashOf(
                CameraMetadata.FLASH_STATE_READY, CameraMetadata.CONTROL_AE_MODE_ON,
                CameraMetadata.FLASH_MODE_TORCH));
        assertEquals(Integer.valueOf(0), ExifCaptureParameters.flashOf(
                CameraMetadata.FLASH_STATE_READY, null, null));
    }

    /** A zero aperture value beside a real f-number is a placeholder, and is replaced. */
    @Test
    public void aPlaceholderApexValueIsReplaced() {
        mExif.setAttribute(ExifInterface.TAG_F_NUMBER, "1.85");
        mExif.setAttribute(ExifInterface.TAG_APERTURE_VALUE, "0/1");
        mExif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "0.01");
        mExif.setAttribute(ExifInterface.TAG_SHUTTER_SPEED_VALUE, "0/1");

        new ExifCaptureParameters().applyTo(mExif);

        assertEquals(1.775, number(ExifInterface.TAG_APERTURE_VALUE), 0.001);
        assertEquals(6.644, number(ExifInterface.TAG_SHUTTER_SPEED_VALUE), 0.001);
    }

    /** But at f/1 and one second, zero is the right answer, and stays. */
    @Test
    public void aTrueZeroApexValueStays() {
        mExif.setAttribute(ExifInterface.TAG_F_NUMBER, "1.0");
        mExif.setAttribute(ExifInterface.TAG_APERTURE_VALUE, "0/1");
        mExif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "1.0");
        mExif.setAttribute(ExifInterface.TAG_SHUTTER_SPEED_VALUE, "0/1");

        new ExifCaptureParameters().applyTo(mExif);

        assertEquals("0/1", mExif.getAttribute(ExifInterface.TAG_APERTURE_VALUE));
        assertEquals("0/1", mExif.getAttribute(ExifInterface.TAG_SHUTTER_SPEED_VALUE));
    }

    /** The legacy camera's parameters, at the moment of the capture. */
    @Test
    @SuppressWarnings("deprecation")
    public void theLegacyParametersDescribeThePicture() {
        Camera.Parameters camera = legacyParameters("focal-length=4.0;horizontal-view-angle=60;"
                + "vertical-view-angle=45;exposure-compensation=2;exposure-compensation-step=0.5;"
                + "zoom-supported=true;zoom-ratios=100,200;zoom=0;whitebalance=auto;"
                + "flash-mode-values=off,auto;flash-mode=off;scene-mode=auto");

        ExifCaptureParameters.forLegacyCamera(camera).applyTo(mExif);

        assertEquals(4, number(ExifInterface.TAG_FOCAL_LENGTH), 0.001);
        assertEquals("from the angle of view", "30",
                mExif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM));
        assertEquals(1, number(ExifInterface.TAG_EXPOSURE_BIAS_VALUE), 0.001);
        assertEquals(1, number(ExifInterface.TAG_DIGITAL_ZOOM_RATIO), 0.001);
        assertEquals("0", mExif.getAttribute(ExifInterface.TAG_WHITE_BALANCE));
        assertEquals("16", mExif.getAttribute(ExifInterface.TAG_FLASH));
        assertEquals("0", mExif.getAttribute(ExifInterface.TAG_SCENE_CAPTURE_TYPE));
        assertNull("camera1 names no aperture, so the lens cannot be specified",
                mExif.getAttribute(ExifInterface.TAG_LENS_SPECIFICATION));
    }

    /** And everything it may leave out, or say otherwise. */
    @Test
    @SuppressWarnings("deprecation")
    public void theLegacyParametersMaySayLittle() {
        ExifCaptureParameters bare = ExifCaptureParameters.forLegacyCamera(legacyParameters(
                "whitebalance=daylight;zoom-supported=true;zoom-ratios=100,200;zoom=1;"
                        + "scene-mode=sports"));
        assertNull("a camera1 zoom might be a lens change",
                bare.values().get(ExifInterface.TAG_DIGITAL_ZOOM_RATIO));
        assertEquals("1", bare.values().get(ExifInterface.TAG_WHITE_BALANCE));
        assertEquals("no flash modes, no flash", "32",
                bare.values().get(ExifInterface.TAG_FLASH));
        assertNull(bare.values().get(ExifInterface.TAG_SCENE_CAPTURE_TYPE));
        assertNull(bare.values().get(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM));

        assertEquals("32", ExifCaptureParameters.forLegacyCamera(legacyParameters(
                "flash-mode-values=off")).values().get(ExifInterface.TAG_FLASH));
        assertNull("auto flash is the camera's to report", ExifCaptureParameters
                .forLegacyCamera(legacyParameters("flash-mode-values=off,auto;flash-mode=auto"))
                .values().get(ExifInterface.TAG_FLASH));
        assertNull(ExifCaptureParameters.forLegacyCamera(legacyParameters(
                "zoom-supported=true;zoom=4;zoom-ratios=100,200")).values()
                .get(ExifInterface.TAG_DIGITAL_ZOOM_RATIO));
        assertNull(ExifCaptureParameters.forLegacyCamera(legacyParameters("zoom-supported=true"))
                .values().get(ExifInterface.TAG_DIGITAL_ZOOM_RATIO));
        assertEquals("3", ExifCaptureParameters.forLegacyCamera(legacyParameters(
                "scene-mode=night-portrait")).values().get(ExifInterface.TAG_SCENE_CAPTURE_TYPE));
        assertEquals("3", ExifCaptureParameters.forLegacyCamera(legacyParameters(
                "scene-mode=night")).values().get(ExifInterface.TAG_SCENE_CAPTURE_TYPE));
        assertEquals("2", ExifCaptureParameters.forLegacyCamera(legacyParameters(
                "scene-mode=portrait")).values().get(ExifInterface.TAG_SCENE_CAPTURE_TYPE));
        assertEquals("1", ExifCaptureParameters.forLegacyCamera(legacyParameters(
                "scene-mode=landscape")).values().get(ExifInterface.TAG_SCENE_CAPTURE_TYPE));
        assertNull(ExifCaptureParameters.forLegacyCamera(legacyParameters(
                "exposure-compensation=2")).values().get(ExifInterface.TAG_EXPOSURE_BIAS_VALUE));
    }

    /** An angle of view no lens could have gives no equivalent. */
    @Test
    public void anImpossibleAngleOfViewHasNoEquivalent() {
        assertEquals(0, ExifCaptureParameters.equivalentFocalLength(0, 45));
        assertEquals(0, ExifCaptureParameters.equivalentFocalLength(60, 180));
        assertEquals(0, ExifCaptureParameters.equivalentFocalLength(Float.NaN, 45));
        assertEquals(30, ExifCaptureParameters.equivalentFocalLength(60, 45));
    }

    /** A tag reads as a number whether ExifInterface prints it as a rational or a decimal. */
    @Test
    public void tagsReadAsNumbers() {
        assertEquals(2.5, ExifCaptureParameters.number("5/2"), 0);
        assertEquals(2.5, ExifCaptureParameters.number("2.5"), 0);
        assertTrue(Double.isNaN(ExifCaptureParameters.number(null)));
        assertTrue(Double.isNaN(ExifCaptureParameters.number("1/0")));
        assertTrue(Double.isNaN(ExifCaptureParameters.number("not a number")));
    }

    /** Read through the camera service, as the backends do. */
    @Test
    public void aCameraIsReadThroughTheCameraService() {
        CameraManager manager = (CameraManager) RuntimeEnvironment.getApplication()
                .getSystemService(Context.CAMERA_SERVICE);
        ShadowCameraManager shadowManager = Shadow.extract(manager);
        shadowManager.addCamera("7", camera(4.38f, 1.8f, new SizeF(5.6f, 4.2f)));

        ExifCaptureParameters.forCamera(manager, "7").applyTo(mExif);
        assertEquals("27", mExif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM));

        ExifCaptureParameters unknown = ExifCaptureParameters.forCamera(manager, "no such camera");
        assertEquals("a camera that cannot be read still gives the defaults", "2",
                unknown.values().get(ExifInterface.TAG_EXPOSURE_PROGRAM));
    }

    private double number(String tag) {
        return ExifCaptureParameters.number(mExif.getAttribute(tag));
    }

    private static <T> void characteristic(
            CameraCharacteristics camera, CameraCharacteristics.Key<T> key, T value) {
        ShadowCameraCharacteristics shadow = Shadow.extract(camera);
        shadow.set(key, value);
    }

    private static CameraCharacteristics camera(float focalLength, float aperture, SizeF sensor) {
        CameraCharacteristics camera = ShadowCameraCharacteristics.newCameraCharacteristics();
        characteristic(camera, CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
                new float[] {focalLength});
        characteristic(camera,
                CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES, new float[] {aperture});
        if (sensor != null) {
            characteristic(camera, CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE, sensor);
        }
        return camera;
    }

    /** A logical camera, whose own focal length is its main lens's, as phones publish them. */
    private static CameraCharacteristics logicalCamera() {
        CameraCharacteristics camera = camera(6.8f, 1.7f, new SizeF(9.8f, 7.35f));
        characteristic(camera, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES, new int[] {
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA});
        return camera;
    }

    /** An ultra-wide, a main and a telephoto camera behind {@link #logicalCamera}. */
    private static List<CameraCharacteristics> physicalCameras() {
        return Arrays.asList(
                camera(2.2f, 2.2f, new SizeF(5.6f, 4.2f)),
                camera(6.8f, 1.7f, new SizeF(9.8f, 7.35f)),
                camera(19f, 3.5f, new SizeF(4.0f, 3.0f)));
    }

    @SuppressWarnings("deprecation")
    private static Camera.Parameters legacyParameters(String flattened) {
        Camera.Parameters parameters = Camera.open().getParameters();
        parameters.unflatten(flattened);
        return parameters;
    }
}
