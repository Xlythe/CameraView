package com.xlythe.view.camera.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.Exif;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Captures in flight when the camera closes, on every backend.
 *
 * <p>An activity pausing, the user switching apps, {@code close()} and a lens toggle all close
 * the camera, and none of them is the user cancelling a capture. A picture whose shutter has fired
 * is saved with the Exif it would have had anyway, a recording is finalized into a file that
 * plays, and a capture that cannot finish says so through {@code onFailure} rather than going
 * quiet.</p>
 *
 * <p>Each close is made from inside the callback that marks the moment - the shutter for a
 * picture - so it lands in the window under test rather than wherever the scheduler puts it. The
 * one close that cannot be made synchronously, the app switch, checks that it did land in time
 * and skips when it did not.</p>
 */
@RunWith(Parameterized.class)
public final class FinishAfterCloseDeviceTest {
    private static final String TAG = "FinishAfterClose";

    /** Long enough for a camera to open, or a capture to land, on a slow emulator. */
    private static final long TIMEOUT_SECONDS = 30;

    /** How soon a picture that cannot finish has to say so: well inside any capture timeout. */
    private static final long FAIL_FAST_SECONDS = 5;

    /** Long enough for an encoder behind a software-rendered surface to produce frames. */
    private static final long RECORDING_MILLIS = 4_000;

    /** What closes the camera under a capture. */
    private enum ClosePoint {
        /** The app calls close(). */
        CLOSE,
        /** The user leaves for another app, and the activity stops and closes the camera. */
        APP_SWITCH,
        /** The user flips to the other lens. */
        TOGGLE
    }

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
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION);

    /** The backend under test. */
    private final CameraView.Backend backend;

    public FinishAfterCloseDeviceTest(CameraView.Backend backend) {
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

    @Test
    public void aPictureClosedAfterItsShutterIsSaved() throws Exception {
        pictureClosedAfterItsShutter(ClosePoint.CLOSE);
    }

    @Test
    public void aPictureInterruptedByAnAppSwitchAfterItsShutterIsSaved() throws Exception {
        pictureClosedAfterItsShutter(ClosePoint.APP_SWITCH);
    }

    @Test
    public void aPictureInterruptedByALensToggleAfterItsShutterIsSaved() throws Exception {
        pictureClosedAfterItsShutter(ClosePoint.TOGGLE);
    }

    @Test
    public void aPictureClosedBeforeItsShutterFailsAtOnce() throws Exception {
        pictureClosedBeforeItsShutter(ClosePoint.CLOSE);
    }

    @Test
    public void aPictureInterruptedByALensToggleBeforeItsShutterFailsAtOnce() throws Exception {
        pictureClosedBeforeItsShutter(ClosePoint.TOGGLE);
    }

    @Test
    public void aRecordingClosedMidwayIsFinalized() throws Exception {
        recordingClosedMidway(ClosePoint.CLOSE);
    }

    @Test
    public void aRecordingInterruptedByAnAppSwitchIsFinalized() throws Exception {
        recordingClosedMidway(ClosePoint.APP_SWITCH);
    }

    @Test
    public void aRecordingInterruptedByALensToggleIsFinalized() throws Exception {
        recordingClosedMidway(ClosePoint.TOGGLE);
    }

    /** A picture with no file of its own still reaches the gallery when the camera closes. */
    @Test
    public void aPictureForTheGalleryClosedAfterItsShutterIsFiled() throws Exception {
        Outcome outcome = new Outcome();
        withOpenCamera(h -> {
            outcome.atShutter = () -> close(h, ClosePoint.CLOSE);
            h.camera.setOnImageCapturedListener(outcome);
            h.onMain(() -> h.camera.takePicture());
            outcome.awaitFinished();
        });

        assertFalse(backend + " lost a picture for the gallery to the close", outcome.failed);
        Uri uri = outcome.uri;
        assertNotNull(backend + " never filed the picture", uri);
        try {
            assertTrue("the filed picture has to decode", decodesAsAnImage(uri));
        } finally {
            delete(uri);
        }
    }

    /** And so does a recording with no file of its own. */
    @Test
    public void aRecordingForTheGalleryClosedMidwayIsFiled() throws Exception {
        assumeTheBackendRecords();
        Outcome outcome = new Outcome();
        withOpenCamera(h -> {
            h.camera.setOnVideoCapturedListener(outcome);
            h.onMain(() -> h.camera.startRecording());
            SystemClock.sleep(RECORDING_MILLIS);
            h.onMain(() -> close(h, ClosePoint.CLOSE));
            outcome.awaitFinished();
        });

        assertFalse(backend + " lost a recording for the gallery to the close", outcome.failed);
        Uri uri = outcome.uri;
        assertNotNull(backend + " never filed the recording", uri);
        try {
            assertPlays(uri);
        } finally {
            delete(uri);
        }
    }

    /** A picture waiting to be confirmed is still waiting once the camera opens again. */
    @Test
    public void aPictureAwaitingConfirmationSurvivesAClose() throws Exception {
        File file = temporaryFile(".jpg");
        Outcome outcome = new Outcome();
        withOpenCamera(h -> {
            h.camera.setImageConfirmationEnabled(true);
            h.camera.setOnImageCapturedListener(outcome);
            h.onMain(() -> h.camera.takePicture(file));
            outcome.awaitConfirmation();

            h.onMain(() -> h.camera.close());
            h.reopen();
            AtomicReference<Boolean> pending = new AtomicReference<>();
            h.onMain(() -> pending.set(h.camera.isImageConfirmationPending()));
            assertTrue(backend + " forgot the picture awaiting confirmation", pending.get());
            h.onMain(() -> h.camera.confirmPicture());
            outcome.awaitFinished();
        });

        assertEquals(backend + " did not deliver the confirmed picture", file, outcome.file);
        assertTrue(decodesAsAnImage(file));
    }

    /** As is a recording. */
    @Test
    public void aRecordingAwaitingConfirmationSurvivesAClose() throws Exception {
        assumeTheBackendRecords();
        File file = temporaryFile(".mp4");
        Outcome outcome = new Outcome();
        withOpenCamera(h -> {
            h.camera.setVideoConfirmationEnabled(true);
            h.camera.setOnVideoCapturedListener(outcome);
            h.onMain(() -> h.camera.startRecording(file));
            SystemClock.sleep(RECORDING_MILLIS);
            h.onMain(() -> h.camera.stopRecording());
            outcome.awaitConfirmation();

            h.onMain(() -> h.camera.close());
            h.reopen();
            AtomicReference<Boolean> pending = new AtomicReference<>();
            h.onMain(() -> pending.set(h.camera.isVideoConfirmationPending()));
            assertTrue(backend + " forgot the recording awaiting confirmation", pending.get());
            h.onMain(() -> h.camera.confirmVideo());
            outcome.awaitFinished();
        });

        assertEquals(backend + " did not deliver the confirmed recording", file, outcome.file);
        assertPlays(file);
    }

    /**
     * A picture whose shutter fired as the camera closed goes on to ask for confirmation, and is
     * delivered once confirmed.
     */
    @Test
    public void aPictureClosedAfterItsShutterStillAsksForConfirmation() throws Exception {
        File file = temporaryFile(".jpg");
        Outcome outcome = new Outcome();
        withOpenCamera(h -> {
            h.camera.setImageConfirmationEnabled(true);
            outcome.atShutter = () -> close(h, ClosePoint.CLOSE);
            h.camera.setOnImageCapturedListener(outcome);
            h.onMain(() -> h.camera.takePicture(file));
            outcome.awaitConfirmationOrFailure();
            assertFalse(backend + " lost the picture to the close", outcome.failed);

            h.reopen();
            AtomicReference<Boolean> pending = new AtomicReference<>();
            h.onMain(() -> pending.set(h.camera.isImageConfirmationPending()));
            assertTrue(backend + " did not keep the picture for confirmation", pending.get());
            h.onMain(() -> h.camera.confirmPicture());
            outcome.awaitFinished();
        });

        assertEquals(backend + " did not deliver the confirmed picture", file, outcome.file);
        assertTrue(decodesAsAnImage(file));
    }

    /**
     * Closes the camera the moment a picture's shutter fires, and expects the picture.
     *
     * <p>Its Exif has to match a picture taken a moment earlier on the same lens without a
     * close: the same rotation, the same mirroring, and a location if that one had one. A toggle
     * starts on the front lens, since that is the picture whose mirroring a switch to the back
     * lens could get wrong.</p>
     */
    private void pictureClosedAfterItsShutter(ClosePoint point) throws Exception {
        File file = temporaryFile(".jpg");
        Outcome outcome = new Outcome();
        AtomicReference<String> expected = new AtomicReference<>();
        withOpenCamera(h -> {
            if (point == ClosePoint.TOGGLE) {
                assumeTwoLenses(h);
                h.switchTo(CameraView.LensFacing.FRONT);
            }
            expected.set(exifSignature(takeReferencePicture(h)));

            outcome.atShutter = () -> close(h, point);
            h.camera.setOnImageCapturedListener(outcome);
            h.onMain(() -> h.camera.takePicture(file));
            outcome.awaitFinished();
            if (point == ClosePoint.APP_SWITCH) {
                assumeTheCloseCameFirst(h, outcome);
            }
        });

        Log.i(TAG, backend + " " + point + " after the shutter: "
                + (outcome.failed ? "failed" : "saved " + file.length() + " bytes"));
        assertFalse(backend + " lost a picture whose shutter had fired to " + point,
                outcome.failed);
        assertEquals(file, outcome.file);
        assertTrue("the picture has to decode", decodesAsAnImage(file));
        assertEquals("the picture has to carry the Exif it would have had without the " + point,
                expected.get(), exifSignature(file));
    }

    /**
     * Closes the camera in the same breath as asking for a picture, before anything can have
     * been exposed. The picture fails, promptly, and the camera is usable afterwards.
     */
    private void pictureClosedBeforeItsShutter(ClosePoint point) throws Exception {
        File file = temporaryFile(".jpg");
        Outcome outcome = new Outcome();
        withOpenCamera(h -> {
            if (point == ClosePoint.TOGGLE) {
                assumeTwoLenses(h);
            }
            h.camera.setOnImageCapturedListener(outcome);
            h.onMain(() -> {
                h.camera.takePicture(file);
                close(h, point);
            });
            assertTrue(backend + " said nothing about a picture closed before its shutter",
                    outcome.finished.await(FAIL_FAST_SECONDS, TimeUnit.SECONDS));
            assertTrue(backend + " reported a picture that was never exposed", outcome.failed);
            assertEquals("no shutter for a picture that was never exposed", 1,
                    outcome.shutter.getCount());

            // And the camera comes back, which a close stuck waiting on the picture would not.
            if (point == ClosePoint.CLOSE) {
                h.reopen();
            } else {
                h.awaitReady();
            }
            takeReferencePicture(h);
        });
    }

    /** Closes the camera while recording, and expects a file that plays. */
    private void recordingClosedMidway(ClosePoint point) throws Exception {
        assumeTheBackendRecords();
        File file = temporaryFile(".mp4");
        Outcome outcome = new Outcome();
        withOpenCamera(h -> {
            if (point == ClosePoint.TOGGLE) {
                assumeTwoLenses(h);
            }
            h.camera.setOnVideoCapturedListener(outcome);
            h.onMain(() -> h.camera.startRecording(file));
            SystemClock.sleep(RECORDING_MILLIS);
            h.onMain(() -> close(h, point));
            if (point == ClosePoint.TOGGLE
                    && !outcome.finished.await(2, TimeUnit.SECONDS) && h.isRecording()) {
                // A backend may carry on recording across a toggle and switch lens once the
                // recording ends. That is a capture that finishes too, once it is stopped.
                Log.i(TAG, backend + " kept recording across a toggle");
                h.onMain(() -> h.camera.stopRecording());
            }
            outcome.awaitFinished();
        });

        Log.i(TAG, backend + " " + point + " while recording: "
                + (outcome.failed ? "failed" : "saved " + file.length() + " bytes"));
        assertFalse(backend + " lost a recording to " + point, outcome.failed);
        assertEquals(file, outcome.file);
        assertPlays(file);
    }

    /** Takes a picture with nothing interrupting it, to compare an interrupted one against. */
    private File takeReferencePicture(Harness h) throws Exception {
        File file = temporaryFile(".jpg");
        Outcome outcome = new Outcome();
        h.camera.setOnImageCapturedListener(outcome);
        h.onMain(() -> h.camera.takePicture(file));
        outcome.awaitFinished();
        assertFalse(backend + " could not take a picture at all", outcome.failed);
        return file;
    }

    /** Does what the close point does. Called on the main thread. */
    private static void close(Harness h, ClosePoint point) {
        switch (point) {
            case CLOSE:
                h.camera.close();
                break;
            case APP_SWITCH:
                assertTrue("the test task has to be able to go to the background",
                        h.activity.moveTaskToBack(true));
                break;
            case TOGGLE:
                h.camera.toggleCamera();
                break;
        }
    }

    /**
     * Skips when the picture was saved before an app switch closed the camera, which leaves
     * nothing tested. The switch goes through the system, so it cannot be timed from here.
     */
    private void assumeTheCloseCameFirst(Harness h, Outcome outcome) {
        long closedAt = h.closedAt;
        Assume.assumeTrue("the picture was saved before the app switch closed the camera",
                closedAt != 0 && closedAt <= outcome.finishedAt);
    }

    private void assumeTwoLenses(Harness h) {
        AtomicReference<Boolean> front = new AtomicReference<>();
        h.onMain(() -> front.set(h.camera.hasFrontFacingCamera()));
        Assume.assumeTrue("a toggle needs a second lens", front.get());
    }

    private void assumeTheBackendRecords() {
        if (backend == CameraView.Backend.LEGACY) {
            // The old API records from YUV buffers it has the camera fill.
            DeviceState.assumeYuvBuffersWork();
        }
    }

    /** What a test does with an open camera. */
    private interface CameraAction {
        void run(Harness harness) throws Exception;
    }

    /** The activity, its camera, and what the camera has said about opening and closing. */
    private static final class Harness implements CameraView.OnCameraStateChangedListener {
        final ActivityScenario<CameraTestActivity> scenario;
        final CameraTestActivity activity;
        final CameraView camera;
        volatile CountDownLatch opened = new CountDownLatch(1);
        /** When the camera last reported closing, or zero. */
        volatile long closedAt;

        Harness(ActivityScenario<CameraTestActivity> scenario) {
            this.scenario = scenario;
            AtomicReference<CameraTestActivity> held = new AtomicReference<>();
            scenario.onActivity(held::set);
            activity = held.get();
            camera = activity.getCameraView();
        }

        void onMain(Runnable runnable) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(runnable);
        }

        boolean isRecording() {
            AtomicReference<Boolean> recording = new AtomicReference<>();
            onMain(() -> recording.set(camera.isRecording()));
            return recording.get();
        }

        void open() throws InterruptedException {
            opened = new CountDownLatch(1);
            onMain(() -> {
                camera.setOnCameraStateChangedListener(this);
                if (camera.isOpen()) {
                    opened.countDown();
                } else {
                    camera.open();
                }
            });
            assertTrue("the camera did not open " + DeviceState.whyTheCameraMightNotOpen(),
                    opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }

        void reopen() throws InterruptedException {
            open();
            // Settled, so a capture straight after is not racing the first frames.
            SystemClock.sleep(1_000);
        }

        /** Waits for a camera that reconfigures itself, as after a toggle, to take pictures. */
        void awaitReady() {
            SystemClock.sleep(2_000);
        }

        void switchTo(CameraView.LensFacing facing) {
            onMain(() -> camera.setLensFacing(facing));
            awaitReady();
        }

        @Override
        public void onCameraOpened() {
            opened.countDown();
        }

        @Override
        public void onCameraClosed() {
            closedAt = SystemClock.elapsedRealtime();
        }
    }

    /** Opens the camera, runs the action, and closes it however the action ends. */
    private void withOpenCamera(CameraAction action) throws Exception {
        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            Harness harness = new Harness(scenario);
            harness.open();
            DeviceState.turnTheFlashOff(harness.camera);
            harness.onMain(() -> harness.camera.setShutterSoundEnabled(false));
            // Long enough for exposure to settle, so the first picture is not the odd one out.
            SystemClock.sleep(1_000);
            try {
                action.run(harness);
            } finally {
                harness.onMain(harness.camera::close);
            }
        }
    }

    /** Hears how a capture turned out, and runs something at its shutter. */
    private static final class Outcome implements CameraView.OnImageCapturedListener,
            CameraView.OnVideoCapturedListener {
        final CountDownLatch shutter = new CountDownLatch(1);
        final CountDownLatch confirmation = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        /** Run on the main thread when the shutter fires, or null for nothing. */
        @Nullable volatile Runnable atShutter;
        @Nullable volatile File file;
        @Nullable volatile Uri uri;
        volatile boolean failed;
        /** When the capture finished, either way. */
        volatile long finishedAt;

        void awaitFinished() throws InterruptedException {
            assertTrue("the capture never reported", finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }

        void awaitConfirmation() throws InterruptedException {
            assertTrue("the capture never asked for confirmation",
                    confirmation.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }

        void awaitConfirmationOrFailure() throws InterruptedException {
            long deadline = SystemClock.elapsedRealtime() + TIMEOUT_SECONDS * 1_000;
            while (confirmation.getCount() > 0 && !failed
                    && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(50);
            }
            assertTrue("the capture neither asked for confirmation nor failed",
                    confirmation.getCount() == 0 || failed);
        }

        private void finish() {
            finishedAt = SystemClock.elapsedRealtime();
            finished.countDown();
        }

        @Override public void onShutter() {
            shutter.countDown();
            Runnable runnable = atShutter;
            atShutter = null;
            if (runnable != null) {
                runnable.run();
            }
        }

        @Override public void onImageConfirmation() {
            confirmation.countDown();
        }

        @Override public void onImageCaptured(File captured) {
            file = captured;
            finish();
        }

        @Override public void onImageSaved(@NonNull Uri saved) {
            uri = saved;
            finish();
        }

        @Override public void onVideoConfirmation() {
            confirmation.countDown();
        }

        @Override public void onVideoCaptured(File captured) {
            file = captured;
            finish();
        }

        @Override public void onVideoSaved(@NonNull Uri saved) {
            uri = saved;
            finish();
        }

        @Override public void onFailure() {
            failed = true;
            finish();
        }
    }

    /** The parts of a picture's Exif that a close must not change. */
    private static String exifSignature(File file) throws IOException {
        Exif exif = new Exif(file);
        return "rotation=" + exif.getRotation()
                + " mirrored=" + exif.isFlippedHorizontally()
                + " flipped=" + exif.isFlippedVertically()
                + " located=" + (exif.getLocation() != null)
                + " timed=" + (exif.getTimestamp() != -1);
    }

    private static boolean decodesAsAnImage(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        return options.outWidth > 0 && options.outHeight > 0;
    }

    private static boolean decodesAsAnImage(Uri uri) throws IOException {
        try (InputStream input = resolver().openInputStream(uri)) {
            assertNotNull("the filed picture cannot be opened", input);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(input, null, options);
            return options.outWidth > 0 && options.outHeight > 0;
        }
    }

    /** A file that plays: it says it has video, a duration, and a frame can be decoded from it. */
    private static void assertPlays(File file) throws IOException {
        assertTrue("the recording has no bytes", file.length() > 0);
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            assertPlays(retriever);
        } finally {
            retriever.release();
        }
    }

    private static void assertPlays(Uri uri) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(ApplicationProvider.getApplicationContext(), uri);
            assertPlays(retriever);
        } finally {
            retriever.release();
        }
    }

    private static void assertPlays(MediaMetadataRetriever retriever) {
        assertEquals("the recording has no video track", "yes",
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO));
        String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        assertNotNull("the recording has no duration", duration);
        assertTrue("the recording is empty: " + duration + "ms", Long.parseLong(duration) > 0);
        assertNotNull("no frame of the recording decodes", retriever.getFrameAtTime(0));
    }

    private static ContentResolver resolver() {
        return ApplicationProvider.getApplicationContext().getContentResolver();
    }

    /** Removes a capture the test filed, which is the app's own row to remove. */
    private static void delete(@Nullable Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            resolver().delete(uri, null, null);
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not remove " + uri, error);
        }
    }

    private static File temporaryFile(String extension) throws IOException {
        Context context = ApplicationProvider.getApplicationContext();
        File file = File.createTempFile("finish", extension, context.getCacheDir());
        file.deleteOnExit();
        // A capture writes the file itself, so nothing may be there already for a check on the
        // result to mistake for one.
        assertTrue(file.delete());
        return file;
    }
}
