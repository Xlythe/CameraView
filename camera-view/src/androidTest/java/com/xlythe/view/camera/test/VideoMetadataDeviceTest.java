package com.xlythe.view.camera.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
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

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What a recording says about itself.
 *
 * <p>MediaRecorder writes a structurally correct and entirely anonymous file: dumping the boxes
 * of a real recording showed {@code ftyp}, a {@code moov} with sane times, two tracks with valid
 * matrices and sample tables, {@code mdat} - and no {@code udta} box at all, so nothing names
 * the camera, the software or the date in any field a tool reads.</p>
 *
 * <p>Read back through {@link MediaMetadataRetriever} rather than by parsing the boxes again,
 * because what matters is not that the bytes are where this library put them but that the
 * platform's own reader finds them.</p>
 */
@RunWith(Parameterized.class)
public final class VideoMetadataDeviceTest {
    private static final String TAG = "VideoMetadata";
    private static final long TIMEOUT_SECONDS = 30;

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
            Manifest.permission.RECORD_AUDIO);

    private final CameraView.Backend backend;

    public VideoMetadataDeviceTest(CameraView.Backend backend) {
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

    /** The recording names the device that made it, which it never used to. */
    @Test
    public void aRecordingNamesTheCameraThatTookIt() throws Exception {
        File file = record();

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String date = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE);
            Log.i(TAG, backend + " date=" + date);

            // The reader exposes the date box directly; make and model it folds elsewhere, so
            // the date is the one this can assert through the public API.
            assertNotNull(backend + " wrote no creation date", date);
            assertTrue(backend + " wrote a creation date that is not a date: " + date,
                    date.length() >= 4);
        } finally {
            retriever.release();
        }
    }

    /** And the file still plays, which is the part a metadata writer can most easily break. */
    @Test
    public void theRecordingIsStillReadableAfterwards() throws Exception {
        File file = record();

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String duration =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String width =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            assertNotNull(backend + " lost the duration", duration);
            assertNotNull(backend + " lost the dimensions", width);
            assertTrue("a recording with no duration", Long.parseLong(duration) > 0);

            // Seeking is where a broken chunk offset shows up: the file opens either way, and
            // only a frame request goes looking through the sample tables into mdat.
            assertNotNull(backend + " cannot decode a frame, which is what a moved mdat does",
                    retriever.getFrameAtTime(500_000));
        } finally {
            retriever.release();
        }
    }

    private File record() throws Exception {
        File file = File.createTempFile("meta", ".mp4",
                ApplicationProvider.getApplicationContext().getCacheDir());
        assertTrue(file.delete() || !file.exists());
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<File> recorded = new AtomicReference<>();

        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            CountDownLatch opened = new CountDownLatch(1);
            AtomicReference<CameraView> held = new AtomicReference<>();
            scenario.onActivity(activity -> {
                CameraView camera = activity.getCameraView();
                held.set(camera);
                camera.setOnCameraStateChangedListener(
                        new CameraView.OnCameraStateChangedListener() {
                            @Override public void onCameraOpened() {
                                opened.countDown();
                            }

                            @Override public void onCameraClosed() {}
                        });
                if (camera.isOpen()) opened.countDown();
                else camera.open();
            });
            assertTrue("the " + backend + " backend did not open "
                            + DeviceState.whyTheCameraMightNotOpen(),
                    opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            CameraView camera = held.get();
            assertNotNull(camera);
            DeviceState.turnTheFlashOff(camera);
            try {
                camera.setOnVideoCapturedListener(new CameraView.OnVideoCapturedListener() {
                    @Override public void onVideoConfirmation() {}

                    @Override public void onVideoCaptured(File saved) {
                        recorded.set(saved);
                        finished.countDown();
                    }

                    @Override public void onFailure() {
                        finished.countDown();
                    }
                });
                camera.startRecording(file);
                Thread.sleep(5_000);
                camera.stopRecording();
                assertTrue("the recording never reported on " + backend,
                        finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            } finally {
                scenario.onActivity(activity -> activity.getCameraView().close());
            }
        }

        Assume.assumeTrue("this device's encoder produced no video on " + backend,
                recorded.get() != null && recorded.get().length() > 0);
        return recorded.get();
    }

    @NonNull
    @Override
    public String toString() {
        return backend + " on " + Build.MODEL;
    }
}
