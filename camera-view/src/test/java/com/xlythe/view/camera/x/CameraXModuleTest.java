package com.xlythe.view.camera.x;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Size;

import androidx.annotation.OptIn;
import androidx.camera.core.ExperimentalZeroShutterLag;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.video.Quality;
import androidx.exifinterface.media.ExifInterface;

import androidx.annotation.NonNull;

import com.xlythe.view.camera.Burst;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.Exif;
import com.xlythe.view.camera.ExifCaptureParameters;
import com.xlythe.view.camera.ExifFixtures;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowCameraCharacteristics;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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

    /**
     * A still beside analysis keeps frames only where the camera promises a reprocessable
     * session with a preview, analysis and a JPEG, which CameraDevice lists for FULL and above.
     */
    @Test
    public void aStillBesideAnalysisKeepsFramesFromFullUp() {
        assertTrue(CameraXModule.keepsFramesBesideAnalysis(
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL));
        assertTrue(CameraXModule.keepsFramesBesideAnalysis(
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3));
        assertFalse(CameraXModule.keepsFramesBesideAnalysis(
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED));
        assertFalse(CameraXModule.keepsFramesBesideAnalysis(
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY));
        assertFalse(CameraXModule.keepsFramesBesideAnalysis(
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL));
        assertFalse(CameraXModule.keepsFramesBesideAnalysis(null));
    }

    /**
     * A still beside analysis that keeps no frames is held to the sizes kept frames would hold it
     * to. On the API 37 emulator HIGH's bound puts 1280x720 first, and CameraX holds a still
     * taken from kept frames to 1856x1392: unheld, a picture beside the segmenter is 576x1280,
     * against 836x1856 apart from it.
     */
    @Test
    public void aStillBesideAnalysisIsHeldToTheSizesKeptFramesAllow() {
        ResolutionSelector selector = CameraXModule.imageResolutionSelector(
                CameraView.Quality.HIGH, AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY,
                Collections.singletonList(new Size(1856, 1392)));

        assertEquals(new Size(1920, 1080), selector.getResolutionStrategy().getBoundSize());
        assertEquals(Collections.singletonList(new Size(1856, 1392)),
                selector.getResolutionFilter().filter(Arrays.asList(new Size(1280, 720),
                        new Size(1856, 1392), new Size(1024, 768), new Size(640, 480)), 90));
    }

    /** A MAX still held to some sizes still takes the largest of them first. */
    @Test
    public void aHeldMaxStillIsTheLargestItMayTake() {
        ResolutionSelector selector = CameraXModule.imageResolutionSelector(
                CameraView.Quality.MAX, AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY,
                Arrays.asList(new Size(1920, 1080), new Size(4000, 3000)));

        assertEquals(Arrays.asList(new Size(4000, 3000), new Size(1920, 1080)),
                selector.getResolutionFilter().filter(Arrays.asList(new Size(1920, 1080),
                        new Size(4080, 3060), new Size(4000, 3000)), 90));
    }

    /**
     * A still keeps the order its selector gave the sizes it may take, and keeps every candidate
     * where it may take none of them, as CameraX does when it takes a still without kept frames.
     */
    @Test
    public void aStillHeldToNoCandidateMayTakeAnyOfThem() {
        assertEquals(Arrays.asList(new Size(1920, 1080), new Size(1280, 720)),
                CameraXModule.heldTo(Arrays.asList(new Size(1920, 1080), new Size(1440, 1080),
                        new Size(1280, 720)), Arrays.asList(new Size(1280, 720),
                        new Size(1920, 1080))));
        List<Size> candidates = Arrays.asList(new Size(1280, 720), new Size(640, 480));
        assertEquals(candidates, CameraXModule.heldTo(candidates,
                Collections.singletonList(new Size(1856, 1392))));
    }

    /** A camera on its own reprocesses kept frames at its PRIVATE input sizes. */
    @Test
    public void aCameraReprocessesAtItsPrivateInputSizes() {
        CameraCharacteristics camera = reprocessingCamera(true, ImageFormat.JPEG,
                new Size(1856, 1392), new Size(1280, 720));

        assertEquals(Arrays.asList(new Size(1856, 1392), new Size(1280, 720)),
                CameraXModule.reprocessedSizes(camera, Collections.emptyList()));
        assertEquals(Collections.emptyList(), CameraXModule.reprocessedSizes(
                reprocessingCamera(false, ImageFormat.JPEG), Collections.emptyList()));
    }

    /**
     * Behind a logical camera, a size counts only where every physical camera reprocesses a
     * PRIVATE frame at it into a JPEG.
     */
    @Test
    public void aLogicalCameraReprocessesOnlyWhereEveryPhysicalCameraDoes() {
        CameraCharacteristics logical = reprocessingCamera(true, ImageFormat.JPEG,
                new Size(4000, 3000), new Size(1920, 1080), new Size(1280, 720));
        CameraCharacteristics wide = reprocessingCamera(true, ImageFormat.JPEG,
                new Size(4000, 3000), new Size(1920, 1080), new Size(1280, 720));
        CameraCharacteristics tele = reprocessingCamera(true, ImageFormat.JPEG,
                new Size(1920, 1080), new Size(1280, 720));

        assertEquals(Arrays.asList(new Size(1920, 1080), new Size(1280, 720)),
                CameraXModule.reprocessedSizes(logical, Arrays.asList(wide, tele)));
        assertEquals("a physical camera with no size in common",
                Collections.emptyList(), CameraXModule.reprocessedSizes(logical, Arrays.asList(
                        wide, reprocessingCamera(true, ImageFormat.JPEG, new Size(640, 480)))));
        assertEquals("a physical camera that reprocesses to YUV alone",
                Collections.emptyList(), CameraXModule.reprocessedSizes(logical, Arrays.asList(
                        wide, reprocessingCamera(true, ImageFormat.YUV_420_888,
                                new Size(1920, 1080)))));
        assertEquals("a physical camera that does not reprocess",
                Collections.emptyList(), CameraXModule.reprocessedSizes(logical, Arrays.asList(
                        wide, withoutReprocessing(new Size(1920, 1080)))));
        assertEquals("a physical camera that could not be read",
                Collections.emptyList(), CameraXModule.reprocessedSizes(logical,
                        Arrays.asList(wide, null)));
    }

    /**
     * A camera that takes PRIVATE frames as input at some sizes and reprocesses them to one
     * format. The map is a mock because Robolectric's builds no input-to-output formats.
     */
    private static CameraCharacteristics reprocessingCamera(
            boolean reprocesses, int outputFormat, Size... inputSizes) {
        StreamConfigurationMap map = mock(StreamConfigurationMap.class);
        when(map.getInputSizes(ImageFormat.PRIVATE))
                .thenReturn(inputSizes.length == 0 ? null : inputSizes);
        when(map.getValidOutputFormatsForInput(ImageFormat.PRIVATE))
                .thenReturn(new int[] {outputFormat});
        return camera(map, reprocesses
                ? new int[] {
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE,
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING}
                : new int[] {
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE});
    }

    /** A camera that lists PRIVATE input sizes without the capability to reprocess them. */
    private static CameraCharacteristics withoutReprocessing(Size... inputSizes) {
        StreamConfigurationMap map = mock(StreamConfigurationMap.class);
        when(map.getInputSizes(ImageFormat.PRIVATE)).thenReturn(inputSizes);
        when(map.getValidOutputFormatsForInput(ImageFormat.PRIVATE))
                .thenReturn(new int[] {ImageFormat.JPEG});
        return camera(map, new int[] {
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE});
    }

    private static CameraCharacteristics camera(StreamConfigurationMap map, int[] capabilities) {
        CameraCharacteristics camera = ShadowCameraCharacteristics.newCameraCharacteristics();
        ShadowCameraCharacteristics shadow = Shadow.extract(camera);
        shadow.set(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP, map);
        shadow.set(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES, capabilities);
        return camera;
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

    /**
     * A drawn burst asks for its next picture only once the last is written, so one full-size
     * drawing is in memory at a time, and the app hears of each picture before the next is taken.
     */
    @Test
    public void aDrawnBurstAsksForTheNextPictureOnceTheLastIsWritten() throws IOException {
        File directory = Files.createTempDirectory("drawn-burst").toFile();
        List<String> events = new ArrayList<>();
        Burst burst = new Burst(directory, "drawn", 2, new Burst.Callback() {
            @Override public void onPictureTaken(@NonNull File file) {
                events.add("taken " + file.getName());
            }

            @Override public void onBurstFinished() {
                events.add("finished");
            }
        });

        File first = burst.nextFile();
        assertNotNull(first);
        CameraXModule.onDrawnBurstPictureWritten(burst, first, true, () -> {
            events.add("next");
            File second = burst.nextFile();
            assertNotNull("the burst still has a picture to take", second);
            CameraXModule.onDrawnBurstPictureWritten(burst, second, true,
                    () -> assertNull("and then it has all it takes", burst.nextFile()));
        });

        assertEquals(Arrays.asList("taken drawn_001.jpg", "next", "taken drawn_002.jpg",
                "finished"), events);
        assertTrue(burst.isFinished());
    }

    /** A picture that could not be drawn ends the burst, and no further picture is asked for. */
    @Test
    public void aDrawnBurstPictureThatIsNotWrittenEndsTheBurst() throws IOException {
        File directory = Files.createTempDirectory("drawn-burst").toFile();
        int[] taken = {0};
        int[] finished = {0};
        Burst burst = new Burst(directory, "drawn", 5, new Burst.Callback() {
            @Override public void onPictureTaken(@NonNull File file) {
                taken[0]++;
            }

            @Override public void onBurstFinished() {
                finished[0]++;
            }
        });
        File file = burst.nextFile();
        assertNotNull(file);
        Files.write(file.toPath(), new byte[] {1, 2, 3});

        CameraXModule.onDrawnBurstPictureWritten(burst, file, false,
                () -> { throw new AssertionError("no picture follows a failed one"); });
        ShadowLooper.idleMainLooper();

        assertEquals(0, taken[0]);
        assertEquals(1, finished[0]);
        assertTrue(burst.isEnded());
        assertNull(burst.nextFile());
        assertFalse("a picture that failed is not left behind", file.exists());
    }
}
