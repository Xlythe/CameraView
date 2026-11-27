package com.xlythe.view.camera.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraExtensionCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.media.ExifInterface;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.PhotoModeTesting;
import com.xlythe.view.camera.gl.BackgroundBlur;
import com.xlythe.view.camera.gl.BackgroundReplacer;
import com.xlythe.view.camera.gl.SegmentationMask;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Every photo mode a camera offers, on every backend, on a real camera.
 *
 * <p>A mode is the phone maker's own processing - a night mode stacking frames, a portrait blur -
 * and only the device can say which it has. The emulator has none, so there this proves the
 * library's own background blur and the refusals; the point of the rest is a phone.</p>
 *
 * <p>Each picture is copied to the app's external files, under {@code extensions/}, so that what
 * a mode did to it can be looked at rather than only measured.</p>
 */
@RunWith(Parameterized.class)
public final class ExtensionDeviceTest {
    private static final String TAG = "ExtensionDeviceTest";
    /** Long enough for a mode that stacks frames, which can take several seconds. */
    private static final long TIMEOUT_SECONDS = 30;
    /** Long enough for a session to reconfigure, and for exposure, focus and masks to settle. */
    private static final long SETTLE_MILLIS = 3_000;
    /** Every photo mode but NONE, in the order they are tried. */
    private static final int[] PHOTO_MODES = {
            CameraView.PhotoMode.BACKGROUND_BLUR,
            CameraView.PhotoMode.HDR,
            CameraView.PhotoMode.NIGHT,
            CameraView.PhotoMode.FACE_RETOUCH,
            CameraView.PhotoMode.AUTO,
    };

    @Parameterized.Parameters(name = "{0}")
    public static Collection<CameraView.Backend> backends() {
        return Arrays.asList(
                CameraView.Backend.CAMERA2,
                CameraView.Backend.CAMERA_X,
                CameraView.Backend.LEGACY);
    }

    @Rule
    public final GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
    );

    private final CameraView.Backend backend;

    /** The activity the camera is in, while a test has it open. */
    @Nullable
    private ActivityScenario<CameraTestActivity> scenario;

    /** The picture {@link #takeAndDescribePicture} last kept. */
    @Nullable
    private File lastPicture;

    public ExtensionDeviceTest(CameraView.Backend backend) {
        this.backend = backend;
    }

    @Before
    public void chooseTheBackend() {
        DeviceState.assumeATestableCamera();
        CameraView.setDefaultBackend(backend);
    }

    @After
    public void restoreTheDefaults() {
        CameraView.setDefaultBackend(CameraView.Backend.AUTO);
        PhotoModeTesting.forceBlurFallback(false);
    }

    /**
     * Each backend offers what the camera has: the old API nothing, and the others the phone
     * maker's modes, with background blur offered wherever frames can be drawn.
     */
    @Test
    public void offersWhatTheCameraHas() throws Exception {
        withOpenCamera(camera -> {
            // The first question reaches the phone maker's extension service, on this thread.
            long asked = SystemClock.elapsedRealtime();
            camera.isPhotoModeSupported(CameraView.PhotoMode.NIGHT);
            Log.i(TAG, backend + " answered its first photo mode question in "
                    + (SystemClock.elapsedRealtime() - asked) + " ms");
            for (CameraView.LensFacing lens : lenses(camera)) {
                useLens(lens);
                Set<String> offered = names(offered(camera));
                Log.i(TAG, backend + " " + lens + " offers " + offered);
                assertTrue(camera.isPhotoModeSupported(CameraView.PhotoMode.NONE));
                if (backend == CameraView.Backend.LEGACY) {
                    assertEquals(names(new int[] {CameraView.PhotoMode.NONE}), offered);
                    continue;
                }
                assertEquals("background blur is offered wherever frames can be drawn",
                        camera.isFrameEffectSupported(),
                        camera.isPhotoModeSupported(CameraView.PhotoMode.BACKGROUND_BLUR));
                if (backend == CameraView.Backend.CAMERA2) {
                    String cameraId = cameraIdFor(lens);
                    logWhatThePlatformSays(cameraId);
                    Set<String> expected = platformOffers(cameraId);
                    if (camera.isFrameEffectSupported()) {
                        expected.add(name(CameraView.PhotoMode.BACKGROUND_BLUR));
                    }
                    assertEquals("the platform's list for camera " + cameraId, expected, offered);
                }
            }
        });
    }

    /**
     * A picture comes back through every mode each lens offers, and through none, so the two can
     * be compared. While a mode is on the zoom is held to the mode's own range.
     */
    @Test
    public void everyPhotoModeTakesAPicture() throws Exception {
        withOpenCamera(camera -> {
            for (CameraView.LensFacing lens : lenses(camera)) {
                useLens(lens);
                takeAndDescribePicture(camera, lens, CameraView.PhotoMode.NONE, "");
                for (int photoMode : PHOTO_MODES) {
                    if (!camera.isPhotoModeSupported(photoMode)) {
                        continue;
                    }
                    camera.setPhotoMode(photoMode);
                    sleep(SETTLE_MILLIS);
                    assertEquals(photoMode, camera.getPhotoMode());
                    Log.i(TAG, String.format(Locale.US, "%s %s %s zooms %.2f to %.2f",
                            backend, lens, name(photoMode), camera.getMinZoomRatio(),
                            camera.getMaxZoomRatio()));
                    assertFalse("pictures are JPEGs while a mode is on",
                            camera.isPictureFormatSupported(CameraView.PictureFormat.RAW));
                    takeAndDescribePicture(camera, lens, photoMode, "");
                }
                camera.setPhotoMode(CameraView.PhotoMode.NONE);
                sleep(SETTLE_MILLIS);
            }
        });
    }

    /**
     * Background blur through the library's own blur, forced on a camera that has one of its own
     * so the two can be compared on one phone. With no person in the frame the segmenter takes
     * the whole scene for background, so the picture comes out softer than one without the mode.
     */
    @Test
    public void theLibraryBlursTheBackgroundItself() throws Exception {
        Assume.assumeTrue("the old camera API draws no frames",
                backend != CameraView.Backend.LEGACY);
        DeviceState.assumeYuvBuffersWork();
        PhotoModeTesting.forceBlurFallback(true);
        withOpenCamera(camera -> {
            Assume.assumeTrue("this build reports no frame effects",
                    camera.isPhotoModeSupported(CameraView.PhotoMode.BACKGROUND_BLUR));
            for (CameraView.LensFacing lens : lenses(camera)) {
                useLens(lens);
                double sharp = takeAndDescribePicture(
                        camera, lens, CameraView.PhotoMode.NONE, "-before-library-blur");
                camera.setPhotoMode(CameraView.PhotoMode.BACKGROUND_BLUR);
                sleep(SETTLE_MILLIS);
                double blurred = takeAndDescribePicture(
                        camera, lens, CameraView.PhotoMode.BACKGROUND_BLUR, "-library");
                assertEquals("a blurred background is a portrait", "2",
                        new ExifInterface(lastPicture.getAbsolutePath())
                                .getAttribute(ExifInterface.TAG_SCENE_CAPTURE_TYPE));
                camera.setPhotoMode(CameraView.PhotoMode.NONE);
                sleep(SETTLE_MILLIS);
                assertTrue(String.format(Locale.US,
                                "the library's blur should soften the %s picture: edges %.2f"
                                        + " against %.2f without it", lens, blurred, sharp),
                        blurred < sharp);
            }
        });
    }

    /**
     * The library's blur, handed a mask by hand that keeps only the top-left quarter of the
     * picture as shown, keeps that quarter sharp and blurs the other three, on either lens. A
     * mask drawn upside down, or mirrored, would keep another quarter sharp instead.
     */
    @Test
    public void theLibrarysBlurKeepsWhatTheMaskSays() throws Exception {
        Assume.assumeTrue("the old camera API draws no frames",
                backend != CameraView.Backend.LEGACY);
        withOpenCamera(camera -> {
            Assume.assumeTrue("this build reports no frame effects",
                    camera.isFrameEffectSupported());
            for (CameraView.LensFacing lens : lenses(camera)) {
                useLens(lens);
                takeAndDescribePicture(camera, lens, CameraView.PhotoMode.NONE, "-before-mask");
                double[] sharp = quarterEdges(lastPicture);

                BackgroundBlur blur = new BackgroundBlur();
                blur.setMask(topLeftQuarter());
                camera.setFrameRenderer(blur);
                sleep(SETTLE_MILLIS);
                takeAndDescribePicture(camera, lens, CameraView.PhotoMode.NONE, "-top-left-mask");
                double[] masked = quarterEdges(lastPicture);
                camera.setFrameRenderer(null);
                sleep(SETTLE_MILLIS);

                String quarters = String.format(Locale.US,
                        "%s %s: edges by quarter %.2f %.2f %.2f %.2f, masked %.2f %.2f %.2f %.2f",
                        backend, lens, sharp[0], sharp[1], sharp[2], sharp[3],
                        masked[0], masked[1], masked[2], masked[3]);
                Log.i(TAG, quarters);
                double kept = masked[0] / sharp[0];
                for (int quarter = 1; quarter < 4; quarter++) {
                    assertTrue("the top-left quarter is the one kept sharp: " + quarters,
                            masked[quarter] / sharp[quarter] < kept);
                }
            }
        });
    }

    /**
     * A mode stays on, and takes pictures, through what a camera app does around it: closing and
     * opening the camera, switching lens and back, and changing the quality and the flash.
     */
    @Test
    public void aPhotoModeLastsThroughTheCameraLifecycle() throws Exception {
        withOpenCamera(camera -> {
            int photoMode = anyPhotoMode(camera);
            Assume.assumeTrue(backend + " offers no photo mode on this camera",
                    photoMode != CameraView.PhotoMode.NONE);
            camera.setPhotoMode(photoMode);
            sleep(SETTLE_MILLIS);
            takeAndDescribePicture(camera, CameraView.LensFacing.BACK, photoMode, "-on");

            reopen();
            assertEquals("the mode outlives the camera closing", photoMode,
                    camera.getPhotoMode());
            takeAndDescribePicture(camera, CameraView.LensFacing.BACK, photoMode, "-reopened");

            for (CameraView.LensFacing lens : lenses(camera)) {
                useLens(lens);
                assertEquals(photoMode, camera.getPhotoMode());
                takeAndDescribePicture(camera, lens, photoMode, "-switched");
            }
            useLens(CameraView.LensFacing.BACK);

            camera.setQuality(CameraView.Quality.MEDIUM);
            camera.setFlash(CameraView.Flash.OFF);
            sleep(SETTLE_MILLIS);
            assertEquals(photoMode, camera.getPhotoMode());
            takeAndDescribePicture(
                    camera, CameraView.LensFacing.BACK, photoMode, "-medium-quality");
            assertFalse("still refusing analysis after all that",
                    camera.startFrameAnalysis(frame -> {}));
            camera.setQuality(CameraView.Quality.HIGH);
        });
    }

    /**
     * A mode produces a preview and pictures, and nothing else: recording, analysis, barcode
     * scanning, background replacement and streaming are refused while it is on, and come back
     * once it is off.
     */
    @Test
    public void aPhotoModeRefusesWhatItCannotProduce() throws Exception {
        withOpenCamera(camera -> {
            int photoMode = anyPhotoMode(camera);
            Assume.assumeTrue(backend + " offers no photo mode on this camera",
                    photoMode != CameraView.PhotoMode.NONE);
            camera.setPhotoMode(photoMode);
            sleep(SETTLE_MILLIS);

            AtomicBoolean failed = new AtomicBoolean();
            CountDownLatch reported = new CountDownLatch(1);
            camera.setOnVideoCapturedListener(new CameraView.OnVideoCapturedListener() {
                @Override public void onVideoConfirmation() {}

                @Override public void onVideoCaptured(File saved) {
                    reported.countDown();
                }

                @Override public void onFailure() {
                    failed.set(true);
                    reported.countDown();
                }
            });
            camera.startRecording(temporaryFile(".mp4"));
            boolean answered = reported.await(5, TimeUnit.SECONDS);
            if (camera.isRecording()) {
                camera.stopRecording();
                reported.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            Log.i(TAG, backend + " recording with " + name(photoMode) + ": answered="
                    + answered + " failed=" + failed.get());
            assertTrue("a recording with " + name(photoMode) + " on has to be refused",
                    failed.get());

            assertFalse("analysis with " + name(photoMode) + " on has to be refused",
                    camera.startFrameAnalysis(frame -> {}));
            assertFalse("a barcode scan with " + name(photoMode) + " on has to be refused",
                    camera.enterBarcodeScanner(barcodes -> {},
                            com.xlythe.view.camera.Barcode.Format.QR_CODE));
            assertFalse("background replacement with " + name(photoMode) + " on has to be"
                            + " refused",
                    camera.enterBackgroundReplacement(new BackgroundReplacer()));
            assertThrows("a stream with " + name(photoMode) + " on has to be refused",
                    IllegalStateException.class, camera::stream);

            // And with it off again, the camera analyzes, and the mode cannot start beside it.
            camera.setPhotoMode(CameraView.PhotoMode.NONE);
            sleep(SETTLE_MILLIS);
            if (DeviceState.canFillYuvBuffers()) {
                assertTrue(camera.startFrameAnalysis(frame -> {}));
                camera.setPhotoMode(photoMode);
                assertEquals("a mode set while frames are analyzed has to be refused",
                        CameraView.PhotoMode.NONE, camera.getPhotoMode());
                camera.stopFrameAnalysis();
            }
        });
    }

    /**
     * Takes a picture on one lens and logs what came back: how long it took, how it was exposed,
     * the scene it is tagged with, and how bright and how sharp it is.
     *
     * @return how sharp it is, as the average difference between neighbouring pixels
     */
    private double takeAndDescribePicture(CameraView camera, CameraView.LensFacing lens,
            int photoMode, String suffix) throws Exception {
        File file = temporaryFile(".jpg");
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<File> captured = new AtomicReference<>();
        camera.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
            @Override public void onImageConfirmation() {}

            @Override public void onImageCaptured(File saved) {
                captured.set(saved);
                finished.countDown();
            }

            @Override public void onFailure() {
                finished.countDown();
            }
        });
        DeviceState.turnTheFlashOff(camera);
        long start = SystemClock.elapsedRealtime();
        camera.takePicture(file);
        String what = backend + " " + lens + " " + name(photoMode) + suffix;
        assertTrue("the " + what + " capture never reported",
                finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        long millis = SystemClock.elapsedRealtime() - start;
        assertNotNull("the " + what + " capture failed", captured.get());
        assertTrue(captured.get().length() > 0);

        File kept = keep(captured.get(), backend + "-" + lens + "-" + name(photoMode) + suffix);
        lastPicture = kept;
        ExifInterface exif = new ExifInterface(kept.getAbsolutePath());
        double edges = edgeStrength(kept);
        Log.i(TAG, String.format(Locale.US,
                "%s: %d ms, %d bytes, %sx%s, exposure %s s, ISO %s, scene %s, mean luma %.1f,"
                        + " edges %.2f",
                what, millis, kept.length(),
                exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH),
                exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH),
                exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME),
                exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS),
                exif.getAttribute(ExifInterface.TAG_SCENE_CAPTURE_TYPE),
                meanLuma(kept), edges));
        return edges;
    }

    /** Closes the camera and opens it again, waiting for it to settle. */
    private void reopen() throws Exception {
        CountDownLatch opened = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            CameraView camera = activity.getCameraView();
            camera.setOnCameraStateChangedListener(
                    new CameraView.OnCameraStateChangedListener() {
                        @Override public void onCameraOpened() {
                            opened.countDown();
                        }

                        @Override public void onCameraClosed() {}
                    });
            camera.close();
            camera.open();
        });
        assertTrue("the camera did not open again",
                opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        sleep(SETTLE_MILLIS);
    }

    /** A mask that keeps the top-left quarter of the picture as shown, and nothing else. */
    private static SegmentationMask topLeftQuarter() {
        int width = 64;
        int height = 64;
        ByteBuffer confidence = ByteBuffer.allocateDirect(width * height * 4)
                .order(ByteOrder.nativeOrder());
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                confidence.putFloat(x < width / 2 && y < height / 2 ? 1f : 0f);
            }
        }
        confidence.position(0);
        return new SegmentationMask(confidence, width, height, false /* mirrored */);
    }

    /**
     * How sharp each quarter of a picture is, upright as its Exif says it is shown: top left, top
     * right, bottom left, bottom right.
     */
    private static double[] quarterEdges(File file) throws IOException {
        Bitmap bitmap = decodeUpright(file);
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = pixels(bitmap);
        double[] sums = new double[4];
        long[] counts = new long[4];
        for (int y = 0; y < height - 1; y++) {
            for (int x = 0; x < width - 1; x++) {
                int quarter = (y < height / 2 ? 0 : 2) + (x < width / 2 ? 0 : 1);
                int here = luma(pixels[y * width + x]);
                sums[quarter] += Math.abs(here - luma(pixels[y * width + x + 1]))
                        + Math.abs(here - luma(pixels[(y + 1) * width + x]));
                counts[quarter] += 2;
            }
        }
        bitmap.recycle();
        double[] edges = new double[4];
        for (int quarter = 0; quarter < 4; quarter++) {
            edges[quarter] = counts[quarter] == 0 ? 0 : sums[quarter] / counts[quarter];
        }
        return edges;
    }

    /** A reduced copy of a picture, turned and flipped the way its Exif says it is shown. */
    private static Bitmap decodeUpright(File file) throws IOException {
        Bitmap reduced = decodeReduced(file);
        int orientation = new ExifInterface(file.getAbsolutePath()).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setScale(1, -1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(-90);
                break;
            default:
                return reduced;
        }
        Bitmap upright = Bitmap.createBitmap(
                reduced, 0, 0, reduced.getWidth(), reduced.getHeight(), matrix, true);
        if (upright != reduced) {
            reduced.recycle();
        }
        return upright;
    }

    /** Switches to a lens, and waits for it to settle. */
    private void useLens(CameraView.LensFacing lens) {
        AtomicBoolean switched = new AtomicBoolean();
        scenario.onActivity(activity -> {
            CameraView camera = activity.getCameraView();
            switched.set(camera.getLensFacing() != lens);
            camera.setLensFacing(lens);
        });
        if (switched.get()) {
            sleep(SETTLE_MILLIS);
        }
    }

    /** The back lens, and the front one where there is one, to be switched to in turn. */
    private static CameraView.LensFacing[] lenses(CameraView camera) {
        return camera.hasFrontFacingCamera()
                ? new CameraView.LensFacing[] {CameraView.LensFacing.BACK,
                        CameraView.LensFacing.FRONT}
                : new CameraView.LensFacing[] {CameraView.LensFacing.BACK};
    }

    /** The photo modes the camera currently says it offers, NONE included. */
    private static int[] offered(CameraView camera) {
        int[] all = new int[PHOTO_MODES.length + 1];
        int count = 0;
        all[count++] = CameraView.PhotoMode.NONE;
        for (int photoMode : PHOTO_MODES) {
            if (camera.isPhotoModeSupported(photoMode)) {
                all[count++] = photoMode;
            }
        }
        return Arrays.copyOf(all, count);
    }

    /** Any photo mode the camera offers, or NONE. */
    private static int anyPhotoMode(CameraView camera) {
        for (int photoMode : PHOTO_MODES) {
            if (camera.isPhotoModeSupported(photoMode)) {
                return photoMode;
            }
        }
        return CameraView.PhotoMode.NONE;
    }

    /** The camera the camera2 backend opens for a lens: the first the platform lists for it. */
    @NonNull
    private static String cameraIdFor(CameraView.LensFacing lens) throws Exception {
        CameraManager manager = cameraManager();
        int facing = lens == CameraView.LensFacing.FRONT
                ? CameraMetadata.LENS_FACING_FRONT : CameraMetadata.LENS_FACING_BACK;
        for (String id : manager.getCameraIdList()) {
            Integer lensFacing = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING);
            if (lensFacing != null && lensFacing == facing) {
                return id;
            }
        }
        throw new IllegalStateException("no camera faces " + lens);
    }

    /**
     * What the platform says a camera offers: its extensions that can carry a preview and a
     * still, and NONE.
     */
    private static Set<String> platformOffers(String cameraId) throws Exception {
        Set<String> offered = new TreeSet<>();
        offered.add(name(CameraView.PhotoMode.NONE));
        if (Build.VERSION.SDK_INT < 31) {
            return offered;
        }
        CameraExtensionCharacteristics extensions =
                cameraManager().getCameraExtensionCharacteristics(cameraId);
        for (int extension : extensions.getSupportedExtensions()) {
            Integer photoMode = photoModeFor(extension);
            boolean preview = !extensions.getExtensionSupportedSizes(
                    extension, SurfaceTexture.class).isEmpty();
            boolean still = !extensions.getExtensionSupportedSizes(
                    extension, ImageFormat.JPEG).isEmpty()
                    || !extensions.getExtensionSupportedSizes(
                            extension, ImageFormat.YUV_420_888).isEmpty();
            if (photoMode != null && preview && still) {
                offered.add(name(photoMode));
            }
        }
        return offered;
    }

    /** Logs everything the platform says about a camera's extensions, for the record. */
    private static void logWhatThePlatformSays(String cameraId) throws Exception {
        CameraCharacteristics characteristics =
                cameraManager().getCameraCharacteristics(cameraId);
        if (Build.VERSION.SDK_INT >= 30) {
            Log.i(TAG, "camera " + cameraId + " zoom " + characteristics.get(
                    CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE));
        }
        if (Build.VERSION.SDK_INT < 31) {
            return;
        }
        CameraExtensionCharacteristics extensions =
                cameraManager().getCameraExtensionCharacteristics(cameraId);
        for (int extension : extensions.getSupportedExtensions()) {
            StringBuilder line = new StringBuilder("camera ").append(cameraId)
                    .append(" extension ").append(extension)
                    .append(" preview ").append(extensions.getExtensionSupportedSizes(
                            extension, SurfaceTexture.class))
                    .append(" jpeg ").append(extensions.getExtensionSupportedSizes(
                            extension, ImageFormat.JPEG))
                    .append(" yuv ").append(extensions.getExtensionSupportedSizes(
                            extension, ImageFormat.YUV_420_888));
            if (Build.VERSION.SDK_INT >= 33) {
                line.append(" requests ")
                        .append(extensions.getAvailableCaptureRequestKeys(extension));
            }
            if (Build.VERSION.SDK_INT >= 35) {
                line.append(" zoom ").append(extensions.get(
                        extension, CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE));
            }
            Log.i(TAG, line.toString());
        }
    }

    /** The photo mode an extension number stands for, or null for one without a mode. */
    @Nullable
    private static Integer photoModeFor(int extension) {
        switch (extension) {
            case CameraExtensionCharacteristics.EXTENSION_AUTOMATIC:
                return CameraView.PhotoMode.AUTO;
            case CameraExtensionCharacteristics.EXTENSION_BOKEH:
                return CameraView.PhotoMode.BACKGROUND_BLUR;
            case CameraExtensionCharacteristics.EXTENSION_HDR:
                return CameraView.PhotoMode.HDR;
            case CameraExtensionCharacteristics.EXTENSION_NIGHT:
                return CameraView.PhotoMode.NIGHT;
            case 1 /* EXTENSION_FACE_RETOUCH, and EXTENSION_BEAUTY before it */:
                return CameraView.PhotoMode.FACE_RETOUCH;
            default:
                return null;
        }
    }

    /** A photo mode's name, for the log and the pictures' file names. */
    private static String name(int photoMode) {
        switch (photoMode) {
            case CameraView.PhotoMode.NONE:
                return "NONE";
            case CameraView.PhotoMode.BACKGROUND_BLUR:
                return "BACKGROUND_BLUR";
            case CameraView.PhotoMode.HDR:
                return "HDR";
            case CameraView.PhotoMode.NIGHT:
                return "NIGHT";
            case CameraView.PhotoMode.FACE_RETOUCH:
                return "FACE_RETOUCH";
            case CameraView.PhotoMode.AUTO:
                return "AUTO";
            default:
                return "UNKNOWN(" + photoMode + ")";
        }
    }

    private static Set<String> names(int[] photoModes) {
        Set<String> names = new TreeSet<>();
        for (int photoMode : photoModes) {
            names.add(name(photoMode));
        }
        return names;
    }

    private static CameraManager cameraManager() {
        Context context = ApplicationProvider.getApplicationContext();
        return context.getSystemService(CameraManager.class);
    }

    /** The picture's average brightness, 0 to 255, from a reduced copy. */
    private static double meanLuma(File file) {
        Bitmap bitmap = decodeReduced(file);
        long sum = 0;
        int[] pixels = pixels(bitmap);
        for (int pixel : pixels) {
            sum += luma(pixel);
        }
        bitmap.recycle();
        return (double) sum / pixels.length;
    }

    /**
     * The average difference in brightness between neighbouring pixels of a reduced copy, which a
     * blurred background lowers.
     */
    private static double edgeStrength(File file) {
        Bitmap bitmap = decodeReduced(file);
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = pixels(bitmap);
        long sum = 0;
        long count = 0;
        for (int y = 0; y < height - 1; y++) {
            for (int x = 0; x < width - 1; x++) {
                int here = luma(pixels[y * width + x]);
                sum += Math.abs(here - luma(pixels[y * width + x + 1]))
                        + Math.abs(here - luma(pixels[(y + 1) * width + x]));
                count += 2;
            }
        }
        bitmap.recycle();
        return count == 0 ? 0 : (double) sum / count;
    }

    private static Bitmap decodeReduced(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 8;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        assertNotNull("the picture does not decode", bitmap);
        return bitmap;
    }

    private static int[] pixels(Bitmap bitmap) {
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        return pixels;
    }

    private static int luma(int pixel) {
        return (299 * ((pixel >> 16) & 0xff) + 587 * ((pixel >> 8) & 0xff) + 114 * (pixel & 0xff))
                / 1000;
    }

    /** Copies a picture where it can be pulled off the device once the run is over. */
    private static File keep(File picture, String name) throws IOException {
        Context context = ApplicationProvider.getApplicationContext();
        File directory = new File(context.getExternalFilesDir(null), "extensions");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("cannot create " + directory);
        }
        File kept = new File(directory, name + ".jpg");
        try (InputStream in = new FileInputStream(picture);
             OutputStream out = new FileOutputStream(kept)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
        return kept;
    }

    /** What a test does with an open camera. */
    private interface CameraAction {
        void run(CameraView camera) throws Exception;
    }

    /** Opens the camera on its back lens, runs the action, and closes the camera again. */
    private void withOpenCamera(CameraAction action) throws Exception {
        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            this.scenario = scenario;
            CountDownLatch opened = new CountDownLatch(1);
            AtomicReference<CameraView> held = new AtomicReference<>();
            scenario.onActivity(activity -> {
                CameraView camera = activity.getCameraView();
                held.set(camera);
                camera.setShutterSoundEnabled(false);
                DeviceState.turnTheFlashOff(camera);
                camera.setOnCameraStateChangedListener(
                        new CameraView.OnCameraStateChangedListener() {
                            @Override public void onCameraOpened() {
                                opened.countDown();
                            }

                            @Override public void onCameraClosed() {}
                        });
                if (camera.isOpen()) {
                    opened.countDown();
                } else {
                    camera.open();
                }
            });
            assertTrue("the " + backend + " backend did not open on this device "
                            + DeviceState.whyTheCameraMightNotOpen(),
                    opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            CameraView camera = held.get();
            assertNotNull(camera);
            // Some cameras only say which modes they have once they are up.
            sleep(SETTLE_MILLIS);
            try {
                useLens(CameraView.LensFacing.BACK);
                action.run(camera);
            } finally {
                scenario.onActivity(activity -> {
                    CameraView view = activity.getCameraView();
                    view.setPhotoMode(CameraView.PhotoMode.NONE);
                    view.close();
                });
                this.scenario = null;
            }
        }
    }

    private static File temporaryFile(String extension) throws Exception {
        File file = File.createTempFile("extension", extension,
                ApplicationProvider.getApplicationContext().getCacheDir());
        file.deleteOnExit();
        return file;
    }

    private static void sleep(long millis) {
        SystemClock.sleep(millis);
    }
}
