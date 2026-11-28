package com.xlythe.view.camera.test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.util.Log;
import android.util.Size;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.CameraView;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * How large a {@link CameraView.Quality#MAX} picture is, read back out of the file.
 *
 * <p>MAX promises the largest picture the camera gives an app, and the only place that promise
 * can be checked is a real sensor: the sizes a phone offers, and which of them a session will
 * actually configure, are the phone's. Each picture is compared with the largest size the camera
 * lists at the picture's own shape, so a backend that settled for a smaller one is caught however
 * it chose the shape. On a sensor that also offers an unbinned mode, the picture has to be the
 * unbinned one.</p>
 *
 * <p>Every size is logged under {@value #TAG}, since what a phone gives is worth knowing even
 * when the test passes.</p>
 */
@RunWith(Parameterized.class)
public final class PictureSizeDeviceTest {
    private static final String TAG = "PictureSizeDeviceTest";

    /** Long enough for a camera to open and for a sensor to read out unbinned. */
    private static final long TIMEOUT_SECONDS = 30;

    /** How far apart two aspect ratios may be and still count as one shape. */
    private static final float ASPECT_TOLERANCE = 0.01f;

    /** The shapes the view is laid out in, which steer the shape of the picture. */
    private enum Shape {
        /** Whatever the test activity gives it, which is the whole screen. */
        FULL,
        /** Three units across to four down, the shape of most sensors held upright. */
        THREE_BY_FOUR
    }

    @Parameterized.Parameters(name = "{0}-{1}-{2}")
    public static Collection<Object[]> parameters() {
        List<Object[]> parameters = new ArrayList<>();
        for (CameraView.Backend backend : new CameraView.Backend[] {
                CameraView.Backend.CAMERA2, CameraView.Backend.CAMERA_X,
                CameraView.Backend.LEGACY}) {
            for (CameraView.LensFacing facing : CameraView.LensFacing.values()) {
                for (Shape shape : Shape.values()) {
                    parameters.add(new Object[] {backend, facing, shape});
                }
            }
        }
        return parameters;
    }

    @Rule
    public final GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);

    /** Skips, rather than fails, a test that the emulator's camera HAL stalled under. */
    @Rule
    public final TestRule cameraHal = DeviceState.skipWhenTheEmulatorCameraHalStalls();

    /** The backend under test. */
    private final CameraView.Backend backend;

    /** Which way the camera under test faces. */
    private final CameraView.LensFacing facing;

    /** How the view is laid out before the camera opens. */
    private final Shape shape;

    public PictureSizeDeviceTest(
            CameraView.Backend backend, CameraView.LensFacing facing, Shape shape) {
        this.backend = backend;
        this.facing = facing;
        this.shape = shape;
    }

    @Before
    public void chooseTheBackend() {
        DeviceState.assumeATestableCamera();
        CameraView.setDefaultBackend(backend);
    }

    @After
    public void restoreTheDefault() {
        CameraView.setDefaultBackend(CameraView.Backend.AUTO);
    }

    /** The picture is as large as anything the camera lists at its shape. */
    @Test
    public void aMaxPictureIsTheLargestTheCameraGives() throws Exception {
        String cameraId = firstCameraFacing(facing);
        Assume.assumeTrue("no " + facing + " camera", cameraId != null);
        List<Size> listed = backend == CameraView.Backend.LEGACY
                ? legacyPictureSizes(facing) : jpegSizes(cameraId, false /* unbinned */);
        List<Size> unbinned = backend == CameraView.Backend.CAMERA2
                ? jpegSizes(cameraId, true /* unbinned */) : new ArrayList<>();

        Picture picture = takePicture(CameraView.Quality.MAX);

        Size size = picture.size;
        Size largestListed = largestAtShapeOf(listed, size);
        Size largestUnbinned = largest(unbinned);
        Log.i(TAG, String.format(Locale.US,
                "%s %s %s: %dx%d (%.1f MP) %s; largest listed at that shape %s, unbinned %s",
                backend, facing, shape, size.getWidth(), size.getHeight(),
                size.getWidth() * (double) size.getHeight() / 1_000_000, picture.colorSpace,
                largestListed, largestUnbinned));
        if (largestListed != null) {
            assertTrue(size + " is smaller than " + largestListed + " on " + backend,
                    area(size) >= area(largestListed));
        }
        if (largestUnbinned != null) {
            assertTrue(size + " is not the unbinned " + largestUnbinned + " on " + backend,
                    area(size) >= area(largestUnbinned));
        }
    }

    /**
     * A MAX picture still arrives while recording, on a camera that says it can take one: a
     * recording leaves less room for the still than an idle camera does, so this is the
     * combination a camera is likeliest to refuse.
     */
    @Test
    public void aMaxPictureCanBeTakenWhileRecording() throws Exception {
        Assume.assumeTrue("recorded once per backend",
                shape == Shape.FULL && facing == CameraView.LensFacing.BACK);
        DeviceState.assumeTheBackendCanRecord(backend);
        File video = File.createTempFile("picture-size", ".mp4",
                ApplicationProvider.getApplicationContext().getCacheDir());
        video.deleteOnExit();
        File file = File.createTempFile("picture-size", ".jpg",
                ApplicationProvider.getApplicationContext().getCacheDir());
        file.deleteOnExit();
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean supported = new AtomicBoolean();
        AtomicReference<File> captured = new AtomicReference<>();
        AtomicReference<CameraView> held = new AtomicReference<>();
        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            scenario.onActivity(activity -> {
                CameraView camera = activity.getCameraView();
                held.set(camera);
                camera.setQuality(CameraView.Quality.MAX);
                DeviceState.turnTheFlashOff(camera);
                camera.setShutterSoundEnabled(false);
                camera.setOnCameraStateChangedListener(
                        new CameraView.OnCameraStateChangedListener() {
                            @Override public void onCameraOpened() {
                                opened.countDown();
                            }

                            @Override public void onCameraClosed() {}
                        });
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
                if (camera.isOpen()) {
                    opened.countDown();
                } else {
                    camera.open();
                }
            });
            assertTrue("the " + backend + " backend did not open "
                            + DeviceState.whyTheCameraMightNotOpen(),
                    opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            Thread.sleep(1_500);
            scenario.onActivity(activity -> held.get().startRecording(video));
            Thread.sleep(2_000);
            scenario.onActivity(activity -> {
                supported.set(held.get().isSnapshotWhileRecordingSupported());
                if (supported.get()) {
                    held.get().takePicture(file);
                }
            });
            if (supported.get()) {
                assertTrue("the snapshot never reported on " + backend,
                        finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            scenario.onActivity(activity -> {
                held.get().stopRecording();
                held.get().close();
            });
        }
        Assume.assumeTrue(backend + " cannot take a picture while recording here",
                supported.get());
        assertNotNull("the snapshot failed on " + backend, captured.get());
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(captured.get().getAbsolutePath(), options);
        Log.i(TAG, backend + " snapshot while recording: "
                + options.outWidth + "x" + options.outHeight);
    }

    /** What a picture's file says about it. */
    private static final class Picture {
        /** Width and height as encoded, before any EXIF rotation. */
        final Size size;

        /** The colour space the decoder reads from the file, or null where it cannot say. */
        @Nullable final String colorSpace;

        Picture(@NonNull Size size, @Nullable String colorSpace) {
            this.size = size;
            this.colorSpace = colorSpace;
        }
    }

    private Picture takePicture(CameraView.Quality quality) throws Exception {
        File file = File.createTempFile("picture-size", ".jpg",
                ApplicationProvider.getApplicationContext().getCacheDir());
        file.deleteOnExit();
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<File> captured = new AtomicReference<>();
        AtomicReference<CameraView> held = new AtomicReference<>();
        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            scenario.onActivity(activity -> {
                CameraView camera = activity.getCameraView();
                held.set(camera);
                if (shape == Shape.THREE_BY_FOUR) {
                    ViewGroup.LayoutParams params = camera.getLayoutParams();
                    params.width = activity.getResources().getDisplayMetrics().widthPixels;
                    params.height = params.width * 4 / 3;
                    camera.setLayoutParams(params);
                }
                camera.setQuality(quality);
                camera.setLensFacing(facing);
                DeviceState.turnTheFlashOff(camera);
                camera.setShutterSoundEnabled(false);
                camera.setOnCameraStateChangedListener(
                        new CameraView.OnCameraStateChangedListener() {
                            @Override public void onCameraOpened() {
                                opened.countDown();
                            }

                            @Override public void onCameraClosed() {}
                        });
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
                if (camera.isOpen()) {
                    opened.countDown();
                } else {
                    camera.open();
                }
            });
            assertTrue("the " + backend + " backend did not open "
                            + DeviceState.whyTheCameraMightNotOpen(),
                    opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            // The view settles after the first layout, and a backend may rebuild its session
            // for the settled size; a picture taken in between is a picture of the old one.
            Thread.sleep(1_500);
            scenario.onActivity(activity -> held.get().takePicture(file));
            assertTrue("the capture never reported on " + backend,
                    finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            scenario.onActivity(activity -> activity.getCameraView().close());
        }
        assertNotNull("the capture failed on " + backend, captured.get());

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(captured.get().getAbsolutePath(), options);
        String colorSpace = Build.VERSION.SDK_INT >= 26 && options.outColorSpace != null
                ? options.outColorSpace.getName() : null;
        return new Picture(new Size(options.outWidth, options.outHeight), colorSpace);
    }

    @Nullable
    private static String firstCameraFacing(CameraView.LensFacing facing)
            throws CameraAccessException {
        int wanted = facing == CameraView.LensFacing.FRONT
                ? CameraMetadata.LENS_FACING_FRONT : CameraMetadata.LENS_FACING_BACK;
        CameraManager manager = cameraManager();
        for (String id : manager.getCameraIdList()) {
            Integer lensFacing =
                    manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (lensFacing != null && lensFacing == wanted) {
                return id;
            }
        }
        return null;
    }

    /**
     * The JPEG sizes a camera lists, from its default map or from its unbinned one.
     *
     * <p>The unbinned map only counts on a camera with the ultra high resolution capability:
     * the key can be present without it, as a placeholder nothing guarantees.</p>
     */
    private static List<Size> jpegSizes(String cameraId, boolean unbinned)
            throws CameraAccessException {
        List<Size> sizes = new ArrayList<>();
        CameraCharacteristics characteristics =
                cameraManager().getCameraCharacteristics(cameraId);
        StreamConfigurationMap map;
        if (unbinned) {
            if (Build.VERSION.SDK_INT < 31 || !hasCapability(characteristics,
                    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)) {
                return sizes;
            }
            map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);
        } else {
            map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        }
        if (map == null) {
            return sizes;
        }
        Size[] output = map.getOutputSizes(ImageFormat.JPEG);
        if (output != null) {
            sizes.addAll(Arrays.asList(output));
        }
        Size[] highResolution = map.getHighResolutionOutputSizes(ImageFormat.JPEG);
        if (highResolution != null) {
            sizes.addAll(Arrays.asList(highResolution));
        }
        return sizes;
    }

    /**
     * The picture sizes the old camera API lists for the camera the view opens on that side.
     *
     * <p>Looked up by facing, the way the view picks it, and not by the camera2 id: the old API
     * numbers its cameras from zero in its own order, so an emulator with no front camera calls
     * its back camera "1" in one API and 0 in the other.</p>
     */
    private static List<Size> legacyPictureSizes(CameraView.LensFacing facing) {
        int wanted = facing == CameraView.LensFacing.FRONT
                ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK;
        int index = -1;
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras() && index == -1; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == wanted) {
                index = i;
            }
        }
        Assume.assumeTrue("the old camera API lists no " + facing + " camera", index != -1);
        List<Size> sizes = new ArrayList<>();
        Camera camera = Camera.open(index);
        try {
            for (Camera.Size size : camera.getParameters().getSupportedPictureSizes()) {
                sizes.add(new Size(size.width, size.height));
            }
        } finally {
            camera.release();
        }
        return sizes;
    }

    private static boolean hasCapability(CameraCharacteristics characteristics, int capability) {
        int[] capabilities =
                characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (capabilities == null) {
            return false;
        }
        for (int available : capabilities) {
            if (available == capability) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static Size largestAtShapeOf(List<Size> sizes, Size shape) {
        List<Size> matching = new ArrayList<>();
        float aspect = (float) shape.getWidth() / shape.getHeight();
        for (Size size : sizes) {
            if (Math.abs((float) size.getWidth() / size.getHeight() - aspect)
                    < ASPECT_TOLERANCE) {
                matching.add(size);
            }
        }
        return largest(matching);
    }

    @Nullable
    private static Size largest(List<Size> sizes) {
        Size largest = null;
        for (Size size : sizes) {
            if (largest == null || area(size) > area(largest)) {
                largest = size;
            }
        }
        return largest;
    }

    private static long area(Size size) {
        return (long) size.getWidth() * size.getHeight();
    }

    private static CameraManager cameraManager() {
        Context context = ApplicationProvider.getApplicationContext();
        return context.getSystemService(CameraManager.class);
    }
}
