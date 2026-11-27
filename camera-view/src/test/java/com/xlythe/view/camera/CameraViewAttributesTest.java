package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.AttributeSet;

import com.xlythe.view.camera.v2.FakeCamera2;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.AttributeSetBuilder;
import org.robolectric.annotation.Config;

/**
 * The attributes a layout can set on a CameraView.
 *
 * <p>Every one of these is a line in a layout file somewhere, and until now none of them were read
 * by anything but the app that wrote them: the defaults were covered and the parsing was not. An
 * attribute that silently does nothing is the worst kind of bug in a view, because the layout
 * says it is on.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class CameraViewAttributesTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        FakeCamera2.addBackAndFrontCameras(context);
        // The legacy backend reads the old API's camera list as it is built, so a layout naming
        // it needs cameras registered there too.
        addLegacyCamera(0, android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK, 90);
        addLegacyCamera(1, android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT, 270);
    }

    @After
    public void tearDown() {
        CameraView.setDefaultBackend(CameraView.Backend.AUTO);
        org.robolectric.shadows.ShadowCamera.clearCameraInfo();
    }

    private static void addLegacyCamera(int id, int facing, int orientation) {
        android.hardware.Camera.CameraInfo info = new android.hardware.Camera.CameraInfo();
        info.facing = facing;
        info.orientation = orientation;
        org.robolectric.shadows.ShadowCamera.addCameraInfo(id, info);
    }

    @Test
    public void everyQualityCanBeNamedInALayout() {
        assertEquals(CameraView.Quality.MAX, from(builder -> builder
                .addAttribute(R.attr.quality, "max")).getQuality());
        assertEquals(CameraView.Quality.HIGH, from(builder -> builder
                .addAttribute(R.attr.quality, "high")).getQuality());
        assertEquals(CameraView.Quality.MEDIUM, from(builder -> builder
                .addAttribute(R.attr.quality, "medium")).getQuality());
        assertEquals(CameraView.Quality.LOW, from(builder -> builder
                .addAttribute(R.attr.quality, "low")).getQuality());
    }

    @Test
    public void everyFlashModeCanBeNamedInALayout() {
        assertEquals(CameraView.Flash.AUTO, from(builder -> builder
                .addAttribute(R.attr.flash, "auto")).getFlash());
        assertEquals(CameraView.Flash.ON, from(builder -> builder
                .addAttribute(R.attr.flash, "on")).getFlash());
        assertEquals(CameraView.Flash.OFF, from(builder -> builder
                .addAttribute(R.attr.flash, "off")).getFlash());
    }

    @Test
    public void theLensCanBeNamedInALayout() {
        assertEquals(CameraView.LensFacing.BACK, from(builder -> builder
                .addAttribute(R.attr.lensFacing, "back")).getLensFacing());
        assertEquals(CameraView.LensFacing.FRONT, from(builder -> builder
                .addAttribute(R.attr.lensFacing, "front")).getLensFacing());
    }

    /**
     * The dynamic range is held as asked for until a camera can be asked about it.
     *
     * <p>A layout is read before the camera opens, so an attribute cannot be checked against the
     * hardware yet; it is kept and offered when the session is built, and declined there if the
     * camera cannot do it.</p>
     */
    @Test
    public void theDynamicRangeCanBeNamedInALayout() {
        assertEquals(CameraView.DynamicRange.SDR, from(builder -> builder
                .addAttribute(R.attr.dynamicRange, "sdr")).getDynamicRange());
        assertEquals(CameraView.DynamicRange.HDR, from(builder -> builder
                .addAttribute(R.attr.dynamicRange, "hdr")).getDynamicRange());
    }

    @Test
    public void everyExtensionCanBeNamedInALayout() {
        assertEquals(CameraView.Extension.NONE, from(builder -> builder
                .addAttribute(R.attr.extension, "none")).getExtension());
        assertEquals(CameraView.Extension.BOKEH, from(builder -> builder
                .addAttribute(R.attr.extension, "bokeh")).getExtension());
        assertEquals(CameraView.Extension.HDR, from(builder -> builder
                .addAttribute(R.attr.extension, "hdr")).getExtension());
        assertEquals(CameraView.Extension.NIGHT, from(builder -> builder
                .addAttribute(R.attr.extension, "night")).getExtension());
        assertEquals(CameraView.Extension.FACE_RETOUCH, from(builder -> builder
                .addAttribute(R.attr.extension, "faceRetouch")).getExtension());
        assertEquals(CameraView.Extension.AUTO, from(builder -> builder
                .addAttribute(R.attr.extension, "auto")).getExtension());
    }

    @Test
    public void everyPhotoModeCanBeNamedInALayout() {
        assertEquals(CameraView.PhotoMode.NONE, from(builder -> builder
                .addAttribute(R.attr.photoMode, "none")).getPhotoMode());
        assertEquals(CameraView.PhotoMode.BACKGROUND_BLUR, from(builder -> builder
                .addAttribute(R.attr.photoMode, "backgroundBlur")).getPhotoMode());
        assertEquals(CameraView.PhotoMode.HDR, from(builder -> builder
                .addAttribute(R.attr.photoMode, "hdr")).getPhotoMode());
        assertEquals(CameraView.PhotoMode.NIGHT, from(builder -> builder
                .addAttribute(R.attr.photoMode, "night")).getPhotoMode());
        assertEquals(CameraView.PhotoMode.FACE_RETOUCH, from(builder -> builder
                .addAttribute(R.attr.photoMode, "faceRetouch")).getPhotoMode());
        assertEquals(CameraView.PhotoMode.AUTO, from(builder -> builder
                .addAttribute(R.attr.photoMode, "auto")).getPhotoMode());
    }

    /** A layout that names both attributes gets the newer one. */
    @Test
    public void thePhotoModeAttributeWinsOverTheOlderOne() {
        CameraView view = from(builder -> builder
                .addAttribute(R.attr.extension, "night")
                .addAttribute(R.attr.photoMode, "backgroundBlur"));

        assertEquals(CameraView.PhotoMode.BACKGROUND_BLUR, view.getPhotoMode());
        assertEquals(CameraView.Extension.BOKEH, view.getExtension());
    }

    @Test
    public void theBackendCanBeNamedInALayout() {
        assertEquals(CameraView.Backend.LEGACY, from(builder -> builder
                .addAttribute(R.attr.backend, "legacy")).getBackend());
        assertEquals(CameraView.Backend.CAMERA2, from(builder -> builder
                .addAttribute(R.attr.backend, "camera2")).getBackend());
    }

    /** A layout that names no backend follows the process-wide default. */
    @Test
    public void aLayoutThatNamesNoBackendFollowsTheDefault() {
        CameraView.setDefaultBackend(CameraView.Backend.LEGACY);

        assertEquals(CameraView.Backend.LEGACY, from(builder -> builder
                .addAttribute(R.attr.pinchToZoomEnabled, "true")).getBackend());
    }

    @Test
    public void theBooleanAttributesAreRead() {
        CameraView view = from(builder -> builder
                .addAttribute(R.attr.confirmImages, "true")
                .addAttribute(R.attr.confirmVideos, "true")
                .addAttribute(R.attr.pinchToZoomEnabled, "false")
                .addAttribute(R.attr.matchPreviewAspectRatio, "false"));

        assertTrue(view.isImageConfirmationEnabled());
        assertTrue(view.isVideoConfirmationEnabled());
        assertFalse(view.isPinchToZoomEnabled());
        assertFalse(view.isMatchPreviewAspectRatioEnabled());
    }

    @Test
    public void theLimitsAreRead() {
        CameraView view = from(builder -> builder
                .addAttribute(R.attr.maxVideoDuration, "15000")
                .addAttribute(R.attr.maxVideoSize, "4096"));

        assertEquals(15_000L, view.getMaxVideoDuration());
        assertEquals(4_096L, view.getMaxVideoSize());
    }

    /**
     * The attributes a camera has to agree to are read and held, not checked at inflation.
     *
     * <p>Stabilization, the torch, low-light boost and exposure are capabilities rather than
     * settings, and a layout is read before there is a camera to ask. They are kept as asked for
     * and offered when the session is built - which is what lets a layout request them at all,
     * and why a view that cannot honour one still inflates.</p>
     *
     * <p>The torch is read from a layout of its own, because it and low-light boost turn each other
     * off.</p>
     */
    @Test
    public void theCapabilityAttributesAreReadAndHeld() {
        CameraView view = from(builder -> builder
                .addAttribute(R.attr.stabilizationEnabled, "true")
                .addAttribute(R.attr.lowLightBoostEnabled, "true")
                .addAttribute(R.attr.exposureCompensation, "2"));
        CameraView torch = from(builder -> builder
                .addAttribute(R.attr.torchEnabled, "true"));

        assertTrue(view.isStabilizationEnabled());
        assertTrue(view.isLowLightBoostEnabled());
        assertEquals(2, view.getExposureCompensation());
        assertTrue(torch.isTorchEnabled());
    }

    /** Defaults, so the attribute tests above are known to be changing something. */
    @Test
    public void aViewWithNoAttributesTakesTheDefaults() {
        CameraView view = new CameraView(context);

        assertEquals(CameraView.Quality.HIGH, view.getQuality());
        assertEquals(CameraView.Flash.AUTO, view.getFlash());
        assertEquals(CameraView.LensFacing.BACK, view.getLensFacing());
        assertTrue(view.isPinchToZoomEnabled());
        assertTrue(view.isMatchPreviewAspectRatioEnabled());
        assertFalse(view.isImageConfirmationEnabled());
        assertFalse(view.isVideoConfirmationEnabled());
        assertEquals(CameraView.DynamicRange.SDR, view.getDynamicRange());
        assertEquals(CameraView.Extension.NONE, view.getExtension());
        assertEquals(CameraView.PhotoMode.NONE, view.getPhotoMode());
        assertEquals(ICameraModule.DEFAULT_PICTURE_QUALITY, view.getPictureQuality());
        assertTrue(view.isShutterSoundEnabled());
    }

    @Test
    public void aLayoutCanSilenceTheShutter() {
        CameraView view = from(builder -> builder
                .addAttribute(R.attr.shutterSoundEnabled, "false"));

        assertFalse(view.isShutterSoundEnabled());
    }

    /** Builds a CameraView from the attributes a layout would have set on it. */
    private CameraView from(Attributes attributes) {
        AttributeSetBuilder builder = Robolectric.buildAttributeSet();
        attributes.apply(builder);
        AttributeSet set = builder.build();
        CameraView view = new CameraView(context, set);
        view.layout(0, 0, 1080, 1920);
        return view;
    }

    /** What a layout says about a CameraView. */
    private interface Attributes {
        void apply(AttributeSetBuilder builder);
    }
}
