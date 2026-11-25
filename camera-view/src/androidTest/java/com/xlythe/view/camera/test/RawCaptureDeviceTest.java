package com.xlythe.view.camera.test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.graphics.BitmapFactory;

import androidx.annotation.NonNull;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.Captions;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RAW capture, on a camera that actually writes it.
 *
 * <p>Both backends grew RAW from the documentation rather than from a sensor: there is no
 * {@code DngCreator} off a device, no RAW stream in any emulator, and CameraX's own two-file
 * request had never been issued. So everything about it - that the stream combination configures,
 * that a result pairs with the frame that produced it, that two files come back from one
 * capture - was reasoned rather than seen. This is where it is seen.</p>
 *
 * <p>A camera that publishes no RAW support skips, which is most tablets and every emulator.</p>
 */
@RunWith(Parameterized.class)
public final class RawCaptureDeviceTest {
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

    /** A DNG lands where the caller asked for it, and is a DNG. */
    @Test
    public void aRawCaptureWritesADng() throws Exception {
        File file = temporaryFile(".dng");

        File written = captureAs(CameraView.PictureFormat.RAW, file);

        assertNotNull("the RAW capture failed on " + backend, written);
        assertTrue("a DNG with no bytes in it", written.length() > 0);
        assertTrue("what was written is not a TIFF, which a DNG is", isTiff(written));
    }

    /**
     * And asking for both gives both, from one capture.
     *
     * <p>CameraX refuses a single destination for this - "Simultaneous capture RAW and JPEG needs
     * two output file options" - so the backend passes two, the RAW first because that is the
     * use case's primary input format. The camera2 backend puts them in the same two places.</p>
     */
    @Test
    public void askingForBothWritesBoth() throws Exception {
        File file = temporaryFile(".jpg");
        File sidecar = Captions.sidecarFor(file, "dng");

        File written = captureAs(CameraView.PictureFormat.RAW_JPEG, file);

        assertNotNull("the RAW+JPEG capture failed on " + backend, written);
        assertTrue("no JPEG at " + file, file.exists() && file.length() > 0);
        assertTrue("the JPEG is not an image",
                BitmapFactory.decodeFile(file.getAbsolutePath()) != null);
        assertTrue("no DNG beside it at " + sidecar, sidecar.exists() && sidecar.length() > 0);
        assertTrue("the file beside it is not a TIFF, which a DNG is", isTiff(sidecar));
    }

    /** Runs one capture in the given format, skipping where the camera does not offer it. */
    private File captureAs(@NonNull CameraView.PictureFormat format, @NonNull File file)
            throws Exception {
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
            // A camera without a RAW stream is an ordinary camera, not a failure.
            Assume.assumeTrue(backend + " does not offer " + format + " on this camera",
                    Boolean.TRUE.equals(offered.get()));

            CameraView camera = held.get();
            assertNotNull(camera);
            DeviceState.turnTheFlashOff(camera);
            try {
                scenario.onActivity(activity -> activity.getCameraView().setPictureFormat(format));
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
                camera.takePicture(file);
                assertTrue("the capture never reported on " + backend,
                        finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            } finally {
                scenario.onActivity(activity -> {
                    activity.getCameraView().setPictureFormat(CameraView.PictureFormat.JPEG);
                    activity.getCameraView().close();
                });
            }
        }
        return captured.get();
    }

    /** A DNG is a TIFF, so it starts with one of the two byte-order marks and the number 42. */
    private static boolean isTiff(@NonNull File file) throws Exception {
        byte[] header = new byte[4];
        try (InputStream stream = new FileInputStream(file)) {
            if (stream.read(header) != header.length) return false;
        }
        boolean little = header[0] == 'I' && header[1] == 'I'
                && header[2] == 42 && header[3] == 0;
        boolean big = header[0] == 'M' && header[1] == 'M'
                && header[2] == 0 && header[3] == 42;
        return little || big;
    }

    private static File temporaryFile(@NonNull String extension) throws Exception {
        File file = File.createTempFile("raw", extension,
                ApplicationProvider.getApplicationContext().getCacheDir());
        assertTrue(file.delete() || !file.exists());
        return file;
    }
}
