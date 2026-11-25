package com.xlythe.view.camera.x;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Size;

import androidx.camera.video.Quality;

import com.xlythe.view.camera.CameraView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

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
        assertEquals(new Size(3840, 2160), CameraXModule.imageTargetSize(CameraView.Quality.MAX));
        assertEquals(new Size(1920, 1080), CameraXModule.imageTargetSize(CameraView.Quality.HIGH));
        assertEquals(new Size(1280, 720), CameraXModule.imageTargetSize(CameraView.Quality.MEDIUM));
        assertEquals(new Size(720, 480), CameraXModule.imageTargetSize(CameraView.Quality.LOW));
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
