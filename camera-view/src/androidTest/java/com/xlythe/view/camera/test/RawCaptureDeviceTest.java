package com.xlythe.view.camera.test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.exifinterface.media.ExifInterface;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.CameraView;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RAW capture, on a camera that writes it.
 *
 * <p>A DNG needs DngCreator and a sensor's own metadata, neither of which exists off a device, so
 * this is where a DNG is seen at all. It is opened as a DNG rather than checked for a header,
 * and its size is held to the sensor's geometry, which is the only size DngCreator writes.</p>
 *
 * <p>A camera that publishes no RAW support skips, as does a backend that declines it.</p>
 */
@RunWith(Parameterized.class)
public final class RawCaptureDeviceTest {
    private static final String TAG = "RawCaptureDeviceTest";

    /** The TIFF tag a DNG announces itself with. */
    private static final int TAG_DNG_VERSION = 50706;

    /** Long enough for a camera to open and a full-sensor frame to be written on an emulator. */
    private static final long TIMEOUT_SECONDS = 30;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<CameraView.Backend> backends() {
        return Arrays.asList(CameraView.Backend.CAMERA2, CameraView.Backend.CAMERA_X);
    }

    @Rule
    public final GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.CAMERA);

    private final CameraView.Backend backend;

    public RawCaptureDeviceTest(CameraView.Backend backend) {
        this.backend = backend;
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

    /** A DNG lands where the caller asked for it, and opens as one. */
    @Test
    public void aRawCaptureWritesADng() throws Exception {
        File file = temporaryFile(".dng");

        File written = captureAs(CameraView.PictureFormat.RAW, file, false);

        assertNotNull("the RAW capture failed on " + backend, written);
        assertIsASensorSizedDng(written);
    }

    /**
     * Closing the camera while the DNG is being written leaves the write to finish.
     *
     * <p>Closing tears down the session and its readers, and the DNG is written straight out of a
     * reader's buffer. A reader released mid-write leaves DngCreator reading freed memory, which
     * ends the process rather than failing the capture.</p>
     */
    @Test
    public void closingTheCameraMidWriteKeepsTheDng() throws Exception {
        File file = temporaryFile(".dng");

        File written = captureAs(CameraView.PictureFormat.RAW, file, true);

        assertNotNull("the RAW capture failed on " + backend, written);
        assertIsASensorSizedDng(written);
    }

    /** Asking for both gives both from one capture, the DNG beside the JPEG under its name. */
    @Test
    public void aRawJpegCaptureWritesBoth() throws Exception {
        File file = temporaryFile(".jpg");
        File dng = new File(file.getParentFile(),
                file.getName().substring(0, file.getName().lastIndexOf('.')) + ".dng");

        File written = captureAs(CameraView.PictureFormat.RAW_JPEG, file, false);

        assertNotNull("the RAW+JPEG capture failed on " + backend, written);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        assertTrue("the JPEG does not decode", bounds.outWidth > 0 && bounds.outHeight > 0);
        Log.i(TAG, backend + " wrote a " + bounds.outWidth + "x" + bounds.outHeight + " JPEG of "
                + file.length() + " bytes");
        assertTrue("no DNG beside the JPEG at " + dng, dng.isFile());
        assertIsASensorSizedDng(dng);
    }

    /**
     * Opens a DNG and holds its size to the sensor's.
     *
     * <p>DngCreator only writes a frame the size of the pre-correction active array or the pixel
     * array, so any other size means the file is not what it claims to be.</p>
     */
    private void assertIsASensorSizedDng(@NonNull File dng) throws Exception {
        assertTrue("an empty DNG at " + dng, dng.length() > 0);
        assertTrue("the file at " + dng + " is not a DNG", hasDngVersion(dng));
        ExifInterface exif = new ExifInterface(dng.getAbsolutePath());
        Size size = new Size(exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0),
                exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0));
        Log.i(TAG, backend + " wrote a " + size + " DNG of " + dng.length() + " bytes");
        Set<Size> sensorSizes = sensorSizes();
        assertTrue("a " + size + " DNG is not the size of any sensor here: " + sensorSizes,
                sensorSizes.contains(size));
    }

    /**
     * Whether a file is a TIFF whose first directory carries DNGVersion, which is what makes a
     * TIFF a DNG. Read by hand, because ExifInterface only looks for that tag in the Exif
     * directory, where DngCreator does not put it.
     */
    private static boolean hasDngVersion(@NonNull File file) throws Exception {
        byte[] bytes = new byte[(int) Math.min(file.length(), 64 * 1024)];
        try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
            input.readFully(bytes);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (bytes[0] == 'I' && bytes[1] == 'I') {
            buffer.order(ByteOrder.LITTLE_ENDIAN);
        } else if (bytes[0] != 'M' || bytes[1] != 'M') {
            return false;
        }
        if (buffer.getShort(2) != 42) {
            return false;
        }
        int directory = buffer.getInt(4);
        int entries = buffer.getShort(directory) & 0xFFFF;
        for (int i = 0; i < entries; i++) {
            if ((buffer.getShort(directory + 2 + i * 12) & 0xFFFF) == TAG_DNG_VERSION) {
                return true;
            }
        }
        return false;
    }

    /** Every size DngCreator could have written, across the cameras on this device. */
    @NonNull
    private static Set<Size> sensorSizes() throws CameraAccessException {
        Context context = ApplicationProvider.getApplicationContext();
        CameraManager manager = context.getSystemService(CameraManager.class);
        Set<Size> sizes = new HashSet<>();
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            Rect preCorrection = characteristics.get(
                    CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE);
            if (preCorrection != null) {
                sizes.add(new Size(preCorrection.width(), preCorrection.height()));
            }
            Size pixelArray =
                    characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
            if (pixelArray != null) {
                sizes.add(pixelArray);
            }
        }
        return sizes;
    }

    /**
     * Runs one capture in the given format, skipping where the camera does not offer it.
     *
     * @param closeMidWrite closes the camera as soon as the DNG starts reaching disk, rather than
     *     after the capture reports
     */
    private File captureAs(@NonNull CameraView.PictureFormat format, @NonNull File file,
            boolean closeMidWrite) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<File> captured = new AtomicReference<>();

        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            CountDownLatch opened = new CountDownLatch(1);
            AtomicReference<CameraView> held = new AtomicReference<>();
            AtomicReference<Boolean> offered = new AtomicReference<>();
            scenario.onActivity(activity -> {
                CameraView camera = activity.getCameraView();
                held.set(camera);
                camera.setOnCameraStateChangedListener(
                        new CameraView.OnCameraStateChangedListener() {
                            @Override public void onCameraOpened() {
                                offered.set(camera.isPictureFormatSupported(format));
                                opened.countDown();
                            }

                            @Override public void onCameraClosed() {}
                        });
                if (camera.isOpen()) {
                    offered.set(camera.isPictureFormatSupported(format));
                    opened.countDown();
                } else {
                    camera.open();
                }
            });

            assertTrue("the " + backend + " backend did not open "
                            + DeviceState.whyTheCameraMightNotOpen(),
                    opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            Assume.assumeTrue(backend + " does not offer " + format + " on this camera",
                    Boolean.TRUE.equals(offered.get()));

            CameraView camera = held.get();
            assertNotNull(camera);
            DeviceState.turnTheFlashOff(camera);
            try {
                scenario.onActivity(activity -> {
                    CameraView view = activity.getCameraView();
                    view.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
                        @Override public void onImageConfirmation() {}

                        @Override public void onImageCaptured(File saved) {
                            captured.set(saved);
                            finished.countDown();
                        }

                        @Override public void onFailure() {
                            finished.countDown();
                        }
                    });
                    // Straight after the format change, the way a caller writes it: the capture
                    // has to wait for the session the new format needs rather than fail.
                    view.setPictureFormat(format);
                    view.takePicture(file);
                });
                if (closeMidWrite) {
                    long deadline = SystemClock.uptimeMillis() + TimeUnit.SECONDS.toMillis(
                            TIMEOUT_SECONDS);
                    while (file.length() == 0 && finished.getCount() > 0
                            && SystemClock.uptimeMillis() < deadline) {
                        SystemClock.sleep(1);
                    }
                    assertTrue("the capture reported before its DNG started to be written",
                            finished.getCount() > 0);
                    scenario.onActivity(activity -> activity.getCameraView().close());
                }
                assertTrue("the capture never reported on " + backend,
                        finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            } finally {
                // Back to JPEG before closing, which rebuilds the session while the last write may
                // still hold a frame: the reader has to outlive it rather than crash the process.
                scenario.onActivity(activity -> {
                    activity.getCameraView().setPictureFormat(CameraView.PictureFormat.JPEG);
                    activity.getCameraView().close();
                });
            }
        }
        return captured.get();
    }

    private static File temporaryFile(@NonNull String extension) throws Exception {
        File file = File.createTempFile("raw", extension,
                ApplicationProvider.getApplicationContext().getCacheDir());
        assertTrue(file.delete() || !file.exists());
        file.deleteOnExit();
        return file;
    }
}
