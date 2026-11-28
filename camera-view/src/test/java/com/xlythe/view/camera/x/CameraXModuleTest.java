package com.xlythe.view.camera.x;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Size;

import androidx.annotation.OptIn;
import androidx.camera.core.ExperimentalZeroShutterLag;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.video.Quality;
import androidx.exifinterface.media.ExifInterface;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.Exif;
import com.xlythe.view.camera.ExifCaptureParameters;
import com.xlythe.view.camera.ExifFixtures;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public final class CameraXModuleTest {
    /** A sensor-oriented 16:9 buffer, the shape CameraX hands back for a 16:9 preview. */
    private static final Size LANDSCAPE_BUFFER = new Size(1920, 1080);
    /** The whole buffer, which is the crop CameraX reports when no ViewPort is set. */
    private static final Rect FULL_CROP = new Rect(0, 0, 1920, 1080);

    @Test
    public void surfaceCoordinatesConvertCameraViewRangeAndClamp() {
        assertEquals(0f, CameraXMath.toSurfaceCoordinate(-1000, 400), 0f);
        assertEquals(200f, CameraXMath.toSurfaceCoordinate(0, 400), 0f);
        assertEquals(400f, CameraXMath.toSurfaceCoordinate(1000, 400), 0f);
        assertEquals(0f, CameraXMath.toSurfaceCoordinate(-2000, 400), 0f);
        assertEquals(400f, CameraXMath.toSurfaceCoordinate(2000, 400), 0f);
    }

    @Test
    public void surfaceCoordinateHandlesMissingLayout() {
        assertEquals(0f, CameraXMath.toSurfaceCoordinate(0, 0), 0f);
        assertEquals(0f, CameraXMath.toSurfaceCoordinate(0, -1), 0f);
    }

    @Test
    public void meteringSizeUsesLargestDimensionAndClamps() {
        assertEquals(0.15f, CameraXMath.toMeteringSize(new Rect(-150, -100, 150, 100)), 0.0001f);
        assertEquals(0.01f, CameraXMath.toMeteringSize(new Rect()), 0f);
        assertEquals(1f, CameraXMath.toMeteringSize(new Rect(-2000, -2000, 2000, 2000)), 0f);
    }

    @Test
    public void rotationsNormalizeIntoASingleTurn() {
        assertEquals(0, CameraXMath.within360(0));
        assertEquals(270, CameraXMath.within360(-90));
        assertEquals(90, CameraXMath.within360(450));
        assertEquals(180, CameraXMath.within360(-180));
    }

    @Test
    public void quarterTurnsTransposeTheBuffer() {
        assertEquals(new Size(1080, 1920), CameraXMath.rotateSize(LANDSCAPE_BUFFER, 90));
        assertEquals(new Size(1080, 1920), CameraXMath.rotateSize(LANDSCAPE_BUFFER, 270));
        assertEquals(LANDSCAPE_BUFFER, CameraXMath.rotateSize(LANDSCAPE_BUFFER, 0));
        assertEquals(LANDSCAPE_BUFFER, CameraXMath.rotateSize(LANDSCAPE_BUFFER, 180));
    }

    /**
     * The camera already turns the buffer by the sensor orientation, so only the display rotation
     * is left. These are the values androidx.camera.view.PreviewTransformation arrives at.
     */
    @Test
    public void remainingRotationOnlyUndoesTheDisplayRotation() {
        // Back camera, sensor 90. Portrait leaves nothing, landscape leaves -90.
        assertEquals(0, CameraXMath.remainingRotationDegrees(90, 90, false));
        assertEquals(270, CameraXMath.remainingRotationDegrees(0, 90, false));
        // Front camera, sensor 270, mirrored. The same leftovers, despite the other convention.
        assertEquals(0, CameraXMath.remainingRotationDegrees(270, 270, true));
        assertEquals(270, CameraXMath.remainingRotationDegrees(0, 270, true));
        // A front camera with mirroring turned off reverses the direction.
        assertEquals(90, CameraXMath.remainingRotationDegrees(0, 270, false));
    }

    @Test
    public void identityTransformPreservesMatchingBufferAndView() {
        Matrix transform = CameraXMath.createTextureTransform(
                new Size(400, 300), new Rect(0, 0, 400, 300), 0, false, false, 0, 400, 300);
        RectF mapped = new RectF(0, 0, 400, 300);
        transform.mapRect(mapped);
        assertEquals(new RectF(0, 0, 400, 300), mapped);
    }

    @Test
    public void rotatedTransformCenterCropsIntoView() {
        Matrix transform = CameraXMath.createTextureTransform(
                new Size(400, 300), new Rect(0, 0, 400, 300), 90, false, false, 0, 300, 400);
        assertFalse(transform.isIdentity());
        float[] center = {150f, 200f};
        transform.mapPoints(center);
        assertTrue(Float.isFinite(center[0]));
        assertTrue(Float.isFinite(center[1]));
    }

    /**
     * A 16:9 preview in a 16:9 portrait view needs no correction at all, because the camera has
     * already turned the buffer upright. Turning it again here lays the preview on its side.
     */
    @Test
    public void cameraTransformedPreviewNeedsNoCorrectionWhenTheAspectRatioMatches() {
        Matrix transform = CameraXMath.createTextureTransform(
                LANDSCAPE_BUFFER, FULL_CROP, 90, false, true, 90, 1080, 1920);
        assertTrue(transform.isIdentity());
    }

    /** The same holds for a mirrored front camera, whose flip the camera has also applied. */
    @Test
    public void cameraTransformedPreviewDoesNotMirrorTwice() {
        Matrix transform = CameraXMath.createTextureTransform(
                LANDSCAPE_BUFFER, FULL_CROP, 270, true, true, 270, 1080, 1920);
        assertTrue(transform.isIdentity());
    }

    /**
     * On a taller view the preview is scaled up and centred so it fills the view without being
     * stretched. These are the exact bounds Camera2Module.transformPreview produces, which is what
     * keeps the two backends interchangeable.
     */
    @Test
    public void cameraTransformedPreviewCenterCropsLikeCamera2() {
        Matrix transform = CameraXMath.createTextureTransform(
                LANDSCAPE_BUFFER, FULL_CROP, 90, false, true, 90, 1080, 2400);
        RectF mapped = new RectF(0, 0, 1080, 2400);
        transform.mapRect(mapped);
        assertEquals(-135f, mapped.left, 0.5f);
        assertEquals(0f, mapped.top, 0.5f);
        assertEquals(1215f, mapped.right, 0.5f);
        assertEquals(2400f, mapped.bottom, 0.5f);
    }

    /** Without a camera transform CameraX expects the full rotation to be applied here. */
    @Test
    public void previewWithoutCameraTransformStillAppliesTheFullRotation() {
        Matrix withTransform = CameraXMath.createTextureTransform(
                LANDSCAPE_BUFFER, FULL_CROP, 90, false, true, 90, 1080, 1920);
        Matrix withoutTransform = CameraXMath.createTextureTransform(
                LANDSCAPE_BUFFER, FULL_CROP, 90, false, false, 90, 1080, 1920);
        assertFalse(withoutTransform.isIdentity());
        assertFalse(withTransform.equals(withoutTransform));
    }

    @Test
    public void degenerateGeometryFallsBackToTheIdentity() {
        assertTrue(CameraXMath.createTextureTransform(
                new Size(0, 0), FULL_CROP, 90, false, true, 90, 1080, 1920).isIdentity());
        assertTrue(CameraXMath.createTextureTransform(
                LANDSCAPE_BUFFER, new Rect(), 90, false, true, 90, 1080, 1920).isIdentity());
        assertTrue(CameraXMath.createTextureTransform(
                LANDSCAPE_BUFFER, FULL_CROP, 90, false, true, 90, 0, 0).isIdentity());
    }

    /**
     * A portrait 16:9 stream in a portrait 16:9 view already fills it. The camera turns the
     * buffer upright on its way to a SurfaceView, so nothing is left to do.
     */
    @Test
    public void surfaceViewPreviewNeedsNoCorrectionWhenTheAspectRatioMatches() {
        assertTrue(CameraXMath.createSurfaceViewTransform(
                LANDSCAPE_BUFFER, FULL_CROP, 90, false, 90, 1080, 1920).isIdentity());
        // A mirrored front camera, whose flip the camera applies too.
        assertTrue(CameraXMath.createSurfaceViewTransform(
                LANDSCAPE_BUFFER, FULL_CROP, 270, true, 270, 1080, 1920).isIdentity());
    }

    /**
     * On a taller view the SurfaceView is scaled up and centred, to exactly the bounds the
     * TextureView path and Camera2 produce, so switching to an HDR preview moves nothing.
     */
    @Test
    public void surfaceViewPreviewCenterCropsLikeTheTextureView() {
        Matrix transform = CameraXMath.createSurfaceViewTransform(
                LANDSCAPE_BUFFER, FULL_CROP, 90, false, 90, 1080, 2400);
        assertEquals(new RectF(-135f, 0f, 1215f, 2400f), shown(transform, 1080, 2400));
        assertScaleAndTranslationOnly(transform);
    }

    /**
     * In landscape the display rotation is undone by the compositor rather than by the transform,
     * so the stream arrives landscape and is cropped top and bottom, not turned.
     */
    @Test
    public void surfaceViewPreviewLeavesTheDisplayRotationToTheCompositor() {
        Matrix transform = CameraXMath.createSurfaceViewTransform(
                LANDSCAPE_BUFFER, FULL_CROP, 0, false, 90, 2400, 1080);
        assertEquals(new RectF(0f, -135f, 2400f, 1215f), shown(transform, 2400, 1080));
        assertScaleAndTranslationOnly(transform);

        // A front camera in landscape is left the same turn, despite mirroring.
        Matrix front = CameraXMath.createSurfaceViewTransform(
                LANDSCAPE_BUFFER, FULL_CROP, 0, true, 270, 2400, 1080);
        assertEquals(new RectF(0f, -135f, 2400f, 1215f), shown(front, 2400, 1080));
    }

    /**
     * A ViewPort crop is what has to fill the view, and the rest of the buffer hangs off its
     * edges. Here a 4:3 crop out of the middle of a 16:9 buffer, in a 3:4 portrait view: the
     * whole buffer is 1080x1920 once upright, of which the crop is the middle 1440 rows.
     */
    @Test
    public void surfaceViewPreviewFillsTheViewWithTheCrop() {
        Matrix transform = CameraXMath.createSurfaceViewTransform(
                LANDSCAPE_BUFFER, new Rect(240, 0, 1680, 1080), 90, false, 90, 1080, 1440);
        assertEquals(new RectF(0f, -240f, 1080f, 1680f), shown(transform, 1080, 1440));
        assertScaleAndTranslationOnly(transform);
    }

    /**
     * Where the crop sits in the buffer follows the camera's turn: a crop off one end of the
     * sensor's long side is the top of a back camera's portrait frame, and the bottom of a front
     * camera's, which is turned the other way.
     */
    @Test
    public void surfaceViewPreviewFollowsTheCropThroughTheCameraTurn() {
        Rect leftEnd = new Rect(0, 0, 960, 1080);
        Matrix back = CameraXMath.createSurfaceViewTransform(
                LANDSCAPE_BUFFER, leftEnd, 90, false, 90, 1080, 960);
        // Upright, the left end of a sensor turned a quarter clockwise is the top of the frame,
        // so the buffer hangs off the bottom of the view.
        assertEquals(new RectF(0f, 0f, 1080f, 1920f), shown(back, 1080, 960));

        // So the buffer hangs off the top instead.
        Matrix front = CameraXMath.createSurfaceViewTransform(
                LANDSCAPE_BUFFER, leftEnd, 270, true, 270, 1080, 960);
        assertEquals(new RectF(0f, -960f, 1080f, 960f), shown(front, 1080, 960));
    }

    @Test
    public void degenerateSurfaceViewGeometryFallsBackToTheIdentity() {
        assertTrue(CameraXMath.createSurfaceViewTransform(
                LANDSCAPE_BUFFER, new Rect(), 90, false, 90, 1080, 1920).isIdentity());
        assertTrue(CameraXMath.createSurfaceViewTransform(
                LANDSCAPE_BUFFER, FULL_CROP, 90, false, 90, 0, 0).isIdentity());
        assertTrue(CameraXMath.createSurfaceViewTransform(
                new Size(0, 0), FULL_CROP, 90, false, 90, 1080, 1920).isIdentity());
    }

    /** Where a SurfaceView's stretched buffer lands once the transform is applied to the view. */
    private static RectF shown(Matrix transform, int viewWidth, int viewHeight) {
        RectF bounds = new RectF(0, 0, viewWidth, viewHeight);
        transform.mapRect(bounds);
        bounds.set(Math.round(bounds.left), Math.round(bounds.top),
                Math.round(bounds.right), Math.round(bounds.bottom));
        return bounds;
    }

    /** A View can scale and move a SurfaceView, and cannot turn, skew or flip one. */
    private static void assertScaleAndTranslationOnly(Matrix transform) {
        float[] values = new float[9];
        transform.getValues(values);
        assertEquals(0f, values[Matrix.MSKEW_X], 0f);
        assertEquals(0f, values[Matrix.MSKEW_Y], 0f);
        assertTrue(values[Matrix.MSCALE_X] > 0f);
        assertTrue(values[Matrix.MSCALE_Y] > 0f);
    }

    @Test
    public void qualityPrioritiesAreOrderedAndNeverEmpty() {
        assertEquals(Arrays.asList(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD),
                CameraXModule.videoQualityPriority(CameraView.Quality.MAX));
        assertEquals(Arrays.asList(Quality.FHD, Quality.HD, Quality.SD),
                CameraXModule.videoQualityPriority(CameraView.Quality.HIGH));
        assertEquals(Arrays.asList(Quality.HD, Quality.SD),
                CameraXModule.videoQualityPriority(CameraView.Quality.MEDIUM));
        assertEquals(Arrays.asList(Quality.SD),
                CameraXModule.videoQualityPriority(CameraView.Quality.LOW));
    }

    @Test
    public void imageQualityTargetsAreDistinct() {
        assertEquals(new Size(1920, 1080), CameraXModule.imageTargetSize(CameraView.Quality.HIGH));
        assertEquals(new Size(1280, 720), CameraXModule.imageTargetSize(CameraView.Quality.MEDIUM));
        assertEquals(new Size(720, 480), CameraXModule.imageTargetSize(CameraView.Quality.LOW));
    }

    /** An effect gives way to a custom stream, whose second Preview CameraX cannot tell apart. */
    @Test
    public void anEffectGivesWayOnlyToACustomStream() {
        assertTrue("the ordinary case binds an effect",
                CameraXModule.canBindFrameEffect(false));
        assertFalse("a custom stream needs a second Preview CameraX cannot tell apart",
                CameraXModule.canBindFrameEffect(true));
    }

    /**
     * While frames are drawn the still is built as a JPEG whatever format was asked for, as it is
     * on Camera2. CameraX throws out of a bind that pairs an effect with a raw or Ultra HDR still,
     * so getting this wrong costs the camera, not the effect.
     */
    @Test
    public void aDrawnStillIsBuiltAsAJpegWhateverTheFormat() {
        for (CameraView.PictureFormat format : CameraView.PictureFormat.values()) {
            assertEquals(format + " while drawing", CameraView.PictureFormat.JPEG,
                    CameraXModule.boundPictureFormat(format, true));
            assertEquals(format + " otherwise", format,
                    CameraXModule.boundPictureFormat(format, false));
        }
    }

    /**
     * A picture comes from kept frames where the camera keeps them, below MAX and for anything
     * but RAW. A drawn still is no exception, since it is taken apart from the effect and drawn
     * after: taken without kept frames, it came out smaller than the undrawn picture.
     */
    @OptIn(markerClass = ExperimentalZeroShutterLag.class)
    @Test
    public void aStillIsTakenFromKeptFramesWhereTheCameraKeepsThem() {
        assertEquals(ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG, CameraXModule.captureMode(
                CameraView.Quality.HIGH, true, CameraView.PictureFormat.JPEG));
        assertEquals(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY, CameraXModule.captureMode(
                CameraView.Quality.HIGH, false, CameraView.PictureFormat.JPEG));
        assertEquals(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY, CameraXModule.captureMode(
                CameraView.Quality.HIGH, true, CameraView.PictureFormat.RAW));
        assertEquals(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY, CameraXModule.captureMode(
                CameraView.Quality.MAX, true, CameraView.PictureFormat.JPEG));
    }

    /**
     * A capture whose Exif has no room for the camera's own tags keeps the picture and the rest
     * of its metadata, rather than passing the save's complaint on.
     */
    @Test
    public void aCaptureIsKeptWhenItsTagsDoNotFit() throws IOException {
        File file = File.createTempFile("camerax", ".jpg");
        file.deleteOnExit();
        Files.write(file.toPath(), ExifFixtures.overfullCapture());

        CameraXModule.writeExif(RuntimeEnvironment.getApplication(), file, null,
                new ExifCaptureParameters());

        Exif saved = new Exif(file);
        assertNotNull(saved.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));
        assertNull(saved.getTag(ExifFixtures.TAG_UNDEFINED_BY_ANY_STANDARD));
    }

    /**
     * MAX asks for the largest still the camera gives, slow sizes included, whatever the view's
     * shape: on a 4:3 view held to a 4K bound it used to settle for 1920x1440 on a 12 MP sensor.
     */
    @Test
    public void aMaxStillIsTheLargestOfAnyShape() {
        ResolutionSelector selector = CameraXModule.imageResolutionSelector(
                CameraView.Quality.MAX, AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY);

        assertEquals(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY,
                selector.getResolutionStrategy());
        assertEquals(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE,
                selector.getAllowedResolutionMode());
        assertEquals(Arrays.asList(new Size(4080, 3060), new Size(4000, 2252),
                        new Size(3840, 2160), new Size(1920, 1440)),
                selector.getResolutionFilter().filter(Arrays.asList(new Size(4000, 2252),
                        new Size(3840, 2160), new Size(1920, 1440), new Size(4080, 3060)), 90));
    }

    /** Every other quality stays bounded, at the rate the camera streams at. */
    @Test
    public void otherStillsKeepTheirBound() {
        ResolutionSelector selector = CameraXModule.imageResolutionSelector(
                CameraView.Quality.HIGH, AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY);

        assertEquals(new Size(1920, 1080), selector.getResolutionStrategy().getBoundSize());
        assertEquals(ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                selector.getResolutionStrategy().getFallbackRule());
        assertEquals(ResolutionSelector.PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION,
                selector.getAllowedResolutionMode());
        assertNull(selector.getResolutionFilter());
        assertEquals(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY,
                selector.getAspectRatioStrategy());
    }

    @Test
    public void largestFirstKeepsEverySize() {
        assertEquals(Arrays.asList(new Size(640, 480), new Size(480, 640), new Size(320, 240)),
                CameraXModule.largestFirst(Arrays.asList(
                        new Size(320, 240), new Size(640, 480), new Size(480, 640))));
    }

    /**
     * A recording leaves a picture to the still output bound beside it, so it is refused only
     * where none could be bound: the same answer isSnapshotWhileRecordingSupported gives.
     */
    @Test
    public void aSnapshotIsTakenWhileRecordingWhereItIsSupported() {
        assertTrue("a snapshot the camera says it can take",
                CameraXModule.acceptsPicture(true, true, false, true, true));
        assertFalse("a recording with no still beside it",
                CameraXModule.acceptsPicture(true, true, false, true, false));
        assertTrue("no recording at all",
                CameraXModule.acceptsPicture(true, true, false, false, false));
    }

    /** Whatever the recording, a closed camera, a missing still or a picture in flight refuses. */
    @Test
    public void aPictureNeedsAnOpenCameraAndAFreeStill() {
        assertFalse("closed", CameraXModule.acceptsPicture(false, true, false, false, false));
        assertFalse("no still output",
                CameraXModule.acceptsPicture(true, false, false, true, true));
        assertFalse("a picture already in flight",
                CameraXModule.acceptsPicture(true, true, true, true, true));
    }

    @Test
    public void durationLimitWaitsForUsableMedia() {
        assertEquals(-1, CameraXModule.remainingDurationMillis(1_500, 0));
        assertEquals(500, CameraXModule.remainingDurationMillis(1_500, 1_000));
        assertEquals(0, CameraXModule.remainingDurationMillis(1_500, 1_500));
        assertEquals(0, CameraXModule.remainingDurationMillis(1_500, 2_000));
        assertEquals(-1, CameraXModule.remainingDurationMillis(
                CameraView.INDEFINITE_VIDEO_DURATION, 2_000));
    }
}
