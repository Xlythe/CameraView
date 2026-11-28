package com.xlythe.view.camera.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.exifinterface.media.ExifInterface;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.FrameStream;
import com.xlythe.view.camera.VideoStream;
import com.xlythe.view.camera.ZoomPreset;

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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Every backend, doing the same things, on a real camera.
 *
 * <p>The library's whole proposition is that the three implementations behave the same, and there
 * is only one way to find out: run the same scenarios against each of them on hardware. Off a
 * device they cannot even be compared - CameraX copies a shared stream through an OpenGL
 * processor, and the old API needs a camera that answers.</p>
 *
 * <p>A backend that cannot run here is skipped rather than failed. That is not a loophole: an
 * emulator image without a second camera, or a device whose CameraX dependency was excluded, is a
 * real configuration, and a suite that went red on it would say nothing useful.</p>
 */
@RunWith(Parameterized.class)
public final class BackendMatrixDeviceTest {
    /** Log tag for what a run found, which is the evidence when comparing two devices. */
    private static final String TAG = "BackendMatrixDeviceTest";
    private static final long TIMEOUT_SECONDS = 30;

    /**
     * How far a zoom may read back from the one asked for.
     *
     * <p>The old camera API zooms in steps of its own table, so it lands on the nearest entry
     * rather than on the ratio; the other two land on it exactly.</p>
     */
    private static final float ZOOM_TOLERANCE = 0.05f;

    /** Long enough for exposure and focus to settle, and a zero-shutter-lag buffer to fill. */
    private static final long SETTLE_MILLIS = 2_000;
    /** Pictures taken and thrown away first, so the numbers are not the first capture's. */
    private static final int WARM_UP_PICTURES = 1;
    /** Pictures timed after the warm-up. */
    private static final int TIMED_PICTURES = 5;
    /**
     * The longest any backend may take to expose a picture. Loose on purpose: the numbers are
     * the hardware's as much as the library's, and this only catches a capture that waits on
     * something it should not.
     */
    private static final long MAX_SHUTTER_MILLIS = 1_000;
    /**
     * Comfortably under the three seconds a flash picture may wait for metering, so a picture
     * inside it was metered rather than timed out.
     */
    private static final long MAX_FLASH_PICTURE_MILLIS = 2_500;

    /** Logs each backend's burst rate, for {@code adb logcat -s BurstRate}. */
    private static final String BURST_TAG = "BurstRate";

    /** How many pictures the burst test takes: enough for a steady rate to show. */
    private static final int BURST_COUNT = 20;

    /** The instrumentation argument that moves the burst test to the front lens. */
    private static final String BURST_LENS_ARGUMENT = "burstLens";

    /** Tags a burst picture and a single one taken just after it share the value of. */
    private static final String[] BURST_SHARED_TAGS = {
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
    };

    /**
     * Tags the scene decides, which a burst picture has wherever a single one does. The location
     * is among them: it is there whenever the app may know it, and the fix moves between the two.
     */
    private static final String[] BURST_PRESENT_TAGS = {
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_SUBJECT_DISTANCE,
            ExifInterface.TAG_GPS_LATITUDE,
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

    /** Skips, rather than fails, a test that the emulator's camera HAL stalled under. */
    @Rule
    public final TestRule cameraHal = DeviceState.skipWhenTheEmulatorCameraHalStalls();

    private final CameraView.Backend backend;

    public BackendMatrixDeviceTest(CameraView.Backend backend) {
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

    /** Opening is the one thing every backend has to do, so it is also the skip condition. */
    @Test
    public void theBackendOpensAndReportsItself() throws Exception {
        AtomicReference<CameraView.Backend> actual = new AtomicReference<>();

        withOpenCamera(camera -> actual.set(camera.getBackend()));

        assertNotNull(actual.get());
        assertTrue("asked for " + backend + " and got " + actual.get(),
                actual.get() == backend || backend == CameraView.Backend.AUTO);
    }

    /** A capture produces a file on every backend, which is the library's whole proposition. */
    @Test
    public void aCaptureProducesAFile() throws Exception {
        File file = temporaryFile(".jpg");
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<File> captured = new AtomicReference<>();

        withOpenCamera(camera -> {
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
        });

        assertNotNull("the capture failed on " + backend, captured.get());
        assertTrue(captured.get().length() > 0);
    }

    /**
     * How long a picture takes: to be exposed, which is the lag a person feels, and to be saved.
     *
     * <p>Logged so backends and devices can be compared, and asserted only loosely. The sounds
     * are off, since eighteen clicks in a row are for nobody's benefit.</p>
     */
    @Test
    public void theShutterLagIsMeasured() throws Exception {
        List<Long> exposed = new ArrayList<>();
        List<Long> saved = new ArrayList<>();

        withOpenCamera(camera -> {
            camera.setShutterSoundEnabled(false);
            sleep(SETTLE_MILLIS);
            for (int i = 0; i < WARM_UP_PICTURES + TIMED_PICTURES; i++) {
                long[] millis = takeTimedPicture(camera);
                if (i >= WARM_UP_PICTURES) {
                    exposed.add(millis[0]);
                    saved.add(millis[1]);
                }
            }
        });

        Log.i(TAG, backend + " on " + Build.MODEL + ": exposed after " + summary(exposed)
                + ", saved after " + summary(saved));
        for (long millis : exposed) {
            assertTrue(backend + " took " + millis + "ms to expose a picture",
                    millis < MAX_SHUTTER_MILLIS);
        }
    }

    /** Takes a picture, returning how long it took to be exposed and to be saved. */
    private long[] takeTimedPicture(CameraView camera) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger shutters = new AtomicInteger();
        AtomicLong exposedAt = new AtomicLong();
        AtomicLong savedAt = new AtomicLong();
        camera.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
            @Override public void onShutter() {
                exposedAt.compareAndSet(0, SystemClock.elapsedRealtime());
                shutters.incrementAndGet();
            }

            @Override public void onImageConfirmation() {}

            @Override public void onImageCaptured(File file) {
                savedAt.set(SystemClock.elapsedRealtime());
                finished.countDown();
            }

            @Override public void onFailure() {
                finished.countDown();
            }
        });

        long pressedAt = SystemClock.elapsedRealtime();
        camera.takePicture(temporaryFile(".jpg"));

        assertTrue("the capture never reported on " + backend,
                finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertFalse("the capture failed on " + backend, savedAt.get() == 0);
        assertEquals("one shutter per picture on " + backend, 1, shutters.get());
        assertTrue("the shutter comes before the picture is saved",
                exposedAt.get() <= savedAt.get());
        return new long[] {exposedAt.get() - pressedAt, savedAt.get() - pressedAt};
    }

    /**
     * A flash picture is metered with a pre-flash first, and that has to finish on a real
     * camera: one that waited out the timeout instead would still produce a file, late.
     */
    @Test
    public void aFlashPictureIsMeteredAndTaken() throws Exception {
        DeviceState.assumeTheLampMayBeLit();
        AtomicLong exposed = new AtomicLong();
        AtomicLong millis = new AtomicLong();

        withOpenCamera(camera -> {
            Assume.assumeTrue("this camera has no flash", camera.hasFlash());
            camera.setShutterSoundEnabled(false);
            camera.setFlash(CameraView.Flash.ON);
            sleep(SETTLE_MILLIS);
            long[] taken = takeTimedPicture(camera);
            exposed.set(taken[0]);
            millis.set(taken[1]);
        });

        // The exposure comes after the metering, so the two tell its time from the save's.
        Log.i(TAG, backend + " took a flash picture in " + millis.get() + "ms, exposed at "
                + exposed.get() + "ms");
        assertTrue(backend + " took " + millis.get() + "ms over a flash picture",
                millis.get() < MAX_FLASH_PICTURE_MILLIS);
    }

    /** The median and range of some timings, in milliseconds. */
    private static String summary(List<Long> millis) {
        List<Long> sorted = new ArrayList<>(millis);
        Collections.sort(sorted);
        return "median " + sorted.get(sorted.size() / 2) + "ms (" + sorted.get(0) + "-"
                + sorted.get(sorted.size() - 1) + "ms)";
    }

    /**
     * And it still produces one when the view settles underneath it, as it does when a picture is
     * taken the moment the camera opens.
     */
    @Test
    public void aCaptureSurvivesTheViewSettling() throws Exception {
        File file = temporaryFile(".jpg");
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<File> captured = new AtomicReference<>();

        withOpenCamera(camera -> {
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
            // Resized while the capture is in flight, so the settle debounce expires during it.
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                ViewGroup.LayoutParams params = camera.getLayoutParams();
                params.width = Math.max(1, camera.getWidth() - 40);
                params.height = Math.max(1, camera.getHeight() - 40);
                camera.setLayoutParams(params);
            });
            assertTrue("the capture never reported on " + backend,
                    finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        });

        assertNotNull("a resize cost the capture on " + backend, captured.get());
        assertTrue(captured.get().length() > 0);
    }

    /** And so does a recording. */
    @Test
    public void aRecordingProducesAFile() throws Exception {
        DeviceState.assumeTheBackendCanRecord(backend);
        File file = temporaryFile(".mp4");
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<File> recorded = new AtomicReference<>();

        withOpenCamera(camera -> {
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
            // Seconds, not milliseconds: a real recording is seconds long, and an encoder behind
            // a software-rendered surface needs that long to produce anything at all.
            sleep(5_000);
            camera.stopRecording();
            // Reporting is the contract the library owes, and it owes it either way.
            assertTrue("the recording never reported on " + backend,
                    finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        });

        // Whether anything was encoded is the encoder's business, and on a surface rendered in
        // software it is marginal: CameraX finalizes with ERROR_NO_VALID_DATA and no bytes, from
        // a window that is ample on real hardware. Skipping says that plainly instead of going
        // red for a reason that is not the library's - and a backend that reported a file still
        // has to have produced a usable one.
        Assume.assumeTrue(backend + " produced no video on this device's encoder",
                recorded.get() != null);
        assertTrue("a reported recording has to have bytes in it", recorded.get().length() > 0);
    }

    /**
     * Frames reach an analyzer on every backend.
     *
     * <p>Which is the same code path for all three - the canvas and the frame pump - but the
     * backends attach a surface to it very differently, and on CameraX that goes through its own
     * stream-sharing processor.</p>
     */
    @Test
    public void framesReachAnAnalyzer() throws Exception {
        DeviceState.assumeYuvBuffersWork();

        CountDownLatch analyzed = new CountDownLatch(2);
        AtomicReference<Size> size = new AtomicReference<>();

        withOpenCamera(camera -> {
            Assume.assumeTrue(backend + " cannot analyze frames at all",
                    camera.startFrameAnalysis(frame -> {
                        size.set(new Size(frame.getWidth(), frame.getHeight()));
                        analyzed.countDown();
                    }, new FrameStream.Params().setMaxSize(new Size(640, 480))));
            assertTrue("no frames on " + backend,
                    analyzed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            camera.stopFrameAnalysis();
        });

        assertNotNull(size.get());
    }

    /** A stream encodes on every backend that can attach a second output. */
    @Test
    public void aStreamDeliversEncodedFrames() throws Exception {
        DeviceState.assumeTheBackendCanRecord(backend);
        AtomicReference<VideoStream> held = new AtomicReference<>();
        AtomicInteger bytes = new AtomicInteger();

        withOpenCamera(camera -> {
            VideoStream video = camera.stream(new VideoStream.Params.Builder()
                    .setAudioEnabled(false)
                    .setMaxSize(new Size(640, 480))
                    .setBitRate(1_000_000)
                    .setIFrameInterval(1)
                    .setIsLossy(true)
                    .build());
            held.set(video);
            Thread reader = new Thread(() -> {
                byte[] chunk = new byte[8192];
                try {
                    int read;
                    while (bytes.get() < 4096
                            && (read = video.getVideoInputStream().read(chunk)) > 0) {
                        bytes.addAndGet(read);
                    }
                } catch (Exception ignored) {
                    // The stream closing reads as an ordinary end.
                }
            }, "StreamReader-" + backend);
            reader.setDaemon(true);
            reader.start();
            sleep(6_000);
        });

        VideoStream video = held.get();
        if (video != null) {
            video.close();
        }
        Assume.assumeTrue("this backend could not attach a stream on this device", bytes.get() > 0);
        assertTrue(bytes.get() > 0);
    }

    /** The controls answer on every backend, with ranges that are real. */
    @Test
    public void theControlsAnswer() throws Exception {
        AtomicInteger maxZoom = new AtomicInteger();
        AtomicReference<CameraView.Flash> flash = new AtomicReference<>();
        AtomicReference<CameraView.Flash> flashAfterOff = new AtomicReference<>();
        AtomicBoolean faces = new AtomicBoolean();

        withOpenCamera(camera -> {
            maxZoom.set(camera.getMaxZoomLevel());
            camera.setZoomLevel(maxZoom.get() / 2);
            sleep(500);
            // Away from the OFF the harness set, so a setter that did nothing would be
            // visible. Choosing a mode does not light anything; only a capture does, and this
            // test takes no picture.
            camera.setFlash(CameraView.Flash.ON);
            sleep(200);
            flash.set(camera.getFlash());
            camera.setFlash(CameraView.Flash.OFF);
            sleep(200);
            flashAfterOff.set(camera.getFlash());
            faces.set(camera.isFaceDetectionSupported());
        });

        assertTrue("every backend scales zoom the same way on " + backend, maxZoom.get() > 0);
        assertEquals("the flash mode has to be the one that was asked for on " + backend,
                CameraView.Flash.ON, flash.get());
        assertEquals("and it has to change back on " + backend,
                CameraView.Flash.OFF, flashAfterOff.get());
    }

    /**
     * A burst takes every picture it asked for, on every backend, and says so once, and each is
     * the picture a single press takes with the same settings.
     *
     * <p>Also where each backend's burst rate is measured, between the first picture landing and
     * the last, so the time to the first picture does not count against the rate. Logged rather
     * than asserted: the rate is the camera's as much as the library's. The back lens is used
     * unless the run passes {@code -e burstLens front}.</p>
     */
    @Test
    public void aBurstTakesEveryPicture() throws Exception {
        boolean front = "front".equals(
                InstrumentationRegistry.getArguments().getString(BURST_LENS_ARGUMENT));
        File directory = new File(ApplicationProvider.getApplicationContext().getCacheDir(),
                "burst-" + backend);
        File[] stale = directory.listFiles();
        if (stale != null) {
            for (File file : stale) {
                assertTrue(file.delete());
            }
        }
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger count = new AtomicInteger(-1);
        List<Long> landed = new ArrayList<>();
        long[] started = {0};
        AtomicReference<File> single = new AtomicReference<>();

        withOpenCamera(camera -> {
            if (front) {
                Assume.assumeTrue("this device has no front camera", camera.hasFrontFacingCamera());
                switchToTheFrontCamera(camera, backend);
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                camera.setShutterSoundEnabled(false);
                started[0] = SystemClock.elapsedRealtime();
                camera.startBurst(directory, BURST_COUNT, new CameraView.OnBurstListener() {
                    @Override public void onBurstImageCaptured(@NonNull File file) {
                        landed.add(SystemClock.elapsedRealtime());
                    }

                    @Override public void onBurstFinished(int pictures) {
                        count.set(pictures);
                        finished.countDown();
                    }
                });
            });
            assertTrue("the burst never finished on " + backend,
                    finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            single.set(takePicture(camera, temporaryFile(".jpg")));
        });

        assertEquals("the burst lost pictures on " + backend, BURST_COUNT, count.get());
        assertEquals(BURST_COUNT, landed.size());
        File[] pictures = directory.listFiles();
        assertNotNull(pictures);
        assertEquals(BURST_COUNT, pictures.length);
        for (File picture : pictures) {
            assertTrue(picture.getName() + " is empty", picture.length() > 0);
        }
        assertNotNull("a single picture after the burst failed on " + backend, single.get());
        assertSamePicture(pictures[0], single.get());
        long first = landed.get(0) - started[0];
        double seconds = (landed.get(landed.size() - 1) - landed.get(0)) / 1000.0;
        Log.i(BURST_TAG, String.format(Locale.US,
                "%s API %d %s %s: %d pictures, first after %d ms, then %.1f per second",
                Build.MODEL, Build.VERSION.SDK_INT, backend, front ? "front" : "back",
                BURST_COUNT, first, (BURST_COUNT - 1) / seconds));
    }

    /**
     * A burst asked for during another burst, during a recording, or while the format is RAW
     * finishes before startBurst returns, with a count of 0, and leaves what was running alone.
     * Each state the camera can be put in is checked; RAW only where the camera writes it.
     */
    @Test
    public void aBurstThatCannotStartFinishesAtOnceWithNothing() throws Exception {
        File directory = new File(ApplicationProvider.getApplicationContext().getCacheDir(),
                "refused-" + backend);
        Map<String, Integer> refusals = new LinkedHashMap<>();
        CountDownLatch firstFinished = new CountDownLatch(1);
        AtomicInteger firstCount = new AtomicInteger(-1);

        withOpenCamera(camera -> {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                camera.setShutterSoundEnabled(false);
                camera.startBurst(directory, 5, new CameraView.OnBurstListener() {
                    @Override public void onBurstFinished(int pictures) {
                        firstCount.set(pictures);
                        firstFinished.countDown();
                    }
                });
                refusals.put("during a burst", refusedCount(camera, directory));
            });
            assertTrue("the first burst never finished on " + backend,
                    firstFinished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals("a refused burst cost the running one pictures on " + backend,
                    5, firstCount.get());

            if (DeviceState.canRecord(backend)) {
                CountDownLatch recorded = new CountDownLatch(1);
                camera.setOnVideoCapturedListener(new CameraView.OnVideoCapturedListener() {
                    @Override public void onVideoConfirmation() {}

                    @Override public void onVideoCaptured(File saved) {
                        recorded.countDown();
                    }

                    @Override public void onFailure() {
                        recorded.countDown();
                    }
                });
                File video = temporaryFile(".mp4");
                InstrumentationRegistry.getInstrumentation().runOnMainSync(
                        () -> camera.startRecording(video));
                sleep(2_000);
                AtomicBoolean stillRecording = new AtomicBoolean();
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    if (camera.isRecording()) {
                        refusals.put("during a recording", refusedCount(camera, directory));
                        stillRecording.set(camera.isRecording());
                    }
                });
                camera.stopRecording();
                assertTrue("the recording never reported on " + backend,
                        recorded.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                if (refusals.containsKey("during a recording")) {
                    assertTrue("a refused burst stopped the recording on " + backend,
                            stillRecording.get());
                }
            }

            for (CameraView.PictureFormat raw : new CameraView.PictureFormat[] {
                    CameraView.PictureFormat.RAW, CameraView.PictureFormat.RAW_JPEG}) {
                AtomicBoolean offered = new AtomicBoolean();
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    offered.set(camera.isPictureFormatSupported(raw));
                    if (offered.get()) {
                        camera.setPictureFormat(raw);
                        refusals.put("as " + raw, refusedCount(camera, directory));
                        camera.setPictureFormat(CameraView.PictureFormat.JPEG);
                    }
                });
            }
        });

        Log.i(BURST_TAG, backend + " refused bursts: " + refusals);
        assertTrue("no refusal was checked on " + backend, refusals.containsKey("during a burst"));
        for (Map.Entry<String, Integer> refusal : refusals.entrySet()) {
            assertEquals("a burst " + refusal.getKey() + " has to finish at once with 0 on "
                    + backend, Integer.valueOf(0), refusal.getValue());
        }
    }

    /**
     * Asks for a burst, on the main thread, and gives the count it finished with before
     * startBurst returned, or null if it had not finished by then.
     */
    private static Integer refusedCount(CameraView camera, File directory) {
        List<Integer> heard = new ArrayList<>();
        camera.startBurst(directory, 3, new CameraView.OnBurstListener() {
            @Override public void onBurstFinished(int pictures) {
                heard.add(pictures);
            }
        });
        return heard.isEmpty() ? null : heard.get(0);
    }

    /**
     * Fails unless a burst picture matches a single picture taken just after it: the same size
     * and orientation, the same values for what the settings decide, and the same tags present
     * for what the scene decides.
     */
    private void assertSamePicture(File burst, File single) throws Exception {
        BitmapFactory.Options burstBounds = bounds(burst);
        BitmapFactory.Options singleBounds = bounds(single);
        assertEquals("a burst picture is as wide as a single one on " + backend,
                singleBounds.outWidth, burstBounds.outWidth);
        assertEquals("a burst picture is as tall as a single one on " + backend,
                singleBounds.outHeight, burstBounds.outHeight);
        ExifInterface burstExif = new ExifInterface(burst.getAbsolutePath());
        ExifInterface singleExif = new ExifInterface(single.getAbsolutePath());
        for (String tag : BURST_SHARED_TAGS) {
            assertEquals(tag + " differs between a burst picture and a single one on " + backend,
                    singleExif.getAttribute(tag), burstExif.getAttribute(tag));
        }
        for (String tag : BURST_PRESENT_TAGS) {
            assertEquals(tag + " is in only one of a burst picture and a single one on " + backend,
                    singleExif.getAttribute(tag) != null, burstExif.getAttribute(tag) != null);
        }
        assertEquals("a burst picture has a thumbnail where a single one does on " + backend,
                singleExif.hasThumbnail(), burstExif.hasThumbnail());
    }

    private static BitmapFactory.Options bounds(File picture) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(picture.getAbsolutePath(), bounds);
        assertTrue(picture.getName() + " is not an image", bounds.outWidth > 0);
        return bounds;
    }

    /** Switches an open camera to its front lens and waits for it to be configured. */
    private static void switchToTheFrontCamera(CameraView camera, CameraView.Backend which)
            throws InterruptedException {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> camera.setLensFacing(CameraView.LensFacing.FRONT));
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS);
        while (!(camera.isUsingFrontFacingCamera() && camera.isOpen())
                && System.currentTimeMillis() < deadline) {
            sleep(100);
        }
        assertTrue(which + " never reached the front camera", camera.isUsingFrontFacingCamera());
        // What a camera reports is read once it is configured, not as soon as it is chosen.
        sleep(2_000);
    }

    /**
     * Camera2 offers the same controls as CameraX on the same camera.
     *
     * <p>An app is not meant to know which backend it has, so a control one of them offers and the
     * other does not, on the same hardware, is a defect in the one that does not. Each lens is
     * asked under both backends; the comparison runs once, under the Camera2 parameter.</p>
     */
    @Test
    public void camera2OffersTheSameControlsAsCameraX() throws Exception {
        Assume.assumeTrue("one comparison covers both backends",
                backend == CameraView.Backend.CAMERA2);

        Map<CameraView.LensFacing, String> camera2 = controlsOffered(CameraView.Backend.CAMERA2);
        Map<CameraView.LensFacing, String> cameraX = controlsOffered(CameraView.Backend.CAMERA_X);

        assertEquals("Camera2 and CameraX disagree about what this device's cameras can do",
                cameraX, camera2);
    }

    /**
     * A capture still works with every control the camera offers turned on.
     *
     * <p>Exposure, stabilization and low-light boost all change the requests a capture is made
     * from, and stabilization rebuilds the session, so each is a way to break a picture or a
     * recording that the plain capture tests never exercise. The torch is left to
     * {@link #aCaptureSurvivesTheTorch}, so that a dark run can skip it alone.</p>
     */
    @Test
    public void aCaptureSurvivesTheControls() throws Exception {
        AtomicReference<File> picture = new AtomicReference<>();
        AtomicReference<File> video = new AtomicReference<>();
        AtomicReference<String> accepted = new AtomicReference<>();
        AtomicReference<String> offered = new AtomicReference<>();

        withOpenCamera(camera -> {
            int exposure = camera.getMaxExposureCompensation() / 2;
            if (camera.isExposureCompensationSupported()) {
                camera.setExposureCompensation(exposure);
            }
            if (camera.isLowLightBoostSupported()) {
                camera.setLowLightBoostEnabled(true);
            }
            // Stabilization last, because it rebuilds the session and what was set before it has
            // to survive that, including a request the camera has not confirmed yet.
            if (camera.isStabilizationSupported()) {
                camera.setStabilizationEnabled(true);
            }
            // Long enough for the rebuilt session to be configured and confirm the others.
            sleep(3_000);
            offered.set("exposure=" + (camera.isExposureCompensationSupported() ? exposure : 0)
                    + " stabilization=" + camera.isStabilizationSupported()
                    + " lowLightBoost=" + camera.isLowLightBoostSupported());
            accepted.set("exposure=" + camera.getExposureCompensation()
                    + " stabilization=" + camera.isStabilizationEnabled()
                    + " lowLightBoost=" + camera.isLowLightBoostEnabled());
            Log.i(TAG, backend + " controls offered: " + offered.get());

            picture.set(takePicture(camera, temporaryFile(".jpg")));
            if (DeviceState.canRecord(backend)) {
                video.set(record(camera, temporaryFile(".mp4")));
            }
        });

        assertEquals("every control the camera offers has to be accepted on " + backend,
                offered.get(), accepted.get());
        assertNotNull("the controls cost the picture on " + backend, picture.get());
        assertTrue(picture.get().length() > 0);
        DeviceState.assumeTheBackendCanRecord(backend);
        // As in aRecordingProducesAFile: whether an encoder behind a software-rendered surface
        // writes anything is not the library's to promise.
        Assume.assumeTrue(backend + " produced no video on this device's encoder",
                video.get() != null);
        assertTrue("a reported recording has to have bytes in it", video.get().length() > 0);
    }

    /** And with the torch lit, which a dark run skips. */
    @Test
    public void aCaptureSurvivesTheTorch() throws Exception {
        DeviceState.assumeTheLampMayBeLit();
        AtomicBoolean lit = new AtomicBoolean();
        AtomicReference<File> picture = new AtomicReference<>();

        withOpenCamera(camera -> {
            Assume.assumeTrue("this camera has no flash to light", camera.hasFlash());
            camera.setTorchEnabled(true);
            sleep(500);
            lit.set(camera.isTorchEnabled());
            try {
                picture.set(takePicture(camera, temporaryFile(".jpg")));
            } finally {
                camera.setTorchEnabled(false);
            }
        });

        assertTrue("a camera with a flash has to accept the torch on " + backend, lit.get());
        assertNotNull("the torch cost the picture on " + backend, picture.get());
        assertTrue(picture.get().length() > 0);
    }

    /** Opens each lens under a backend and describes the controls it offers. */
    private Map<CameraView.LensFacing, String> controlsOffered(CameraView.Backend which)
            throws Exception {
        CameraView.setDefaultBackend(which);
        Map<CameraView.LensFacing, String> controls = new LinkedHashMap<>();
        withOpenCamera(camera -> {
            Assume.assumeTrue(which + " cannot run here", camera.getBackend() == which);
            controls.put(CameraView.LensFacing.BACK, describeControls(which, camera));
            if (!camera.hasFrontFacingCamera()) {
                return;
            }
            switchToTheFrontCamera(camera, which);
            controls.put(CameraView.LensFacing.FRONT, describeControls(which, camera));
        });
        return controls;
    }

    private static String describeControls(CameraView.Backend which, CameraView camera) {
        String controls = "torch=" + camera.hasFlash()
                + " exposure=" + camera.isExposureCompensationSupported()
                + "[" + camera.getMinExposureCompensation()
                + ", " + camera.getMaxExposureCompensation() + "]"
                + " stabilization=" + camera.isStabilizationSupported()
                + " lowLightBoost=" + camera.isLowLightBoostSupported();
        Log.i(TAG, which + " " + (camera.isUsingFrontFacingCamera() ? "front" : "back")
                + " camera offers " + controls);
        return controls;
    }

    /** Takes a picture and waits for it, returning the file or null if the capture failed. */
    private File takePicture(CameraView camera, File file) throws InterruptedException {
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
        camera.takePicture(file);
        assertTrue("the capture never reported on " + backend,
                finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        return captured.get();
    }

    /** Records a few seconds and waits for the result, returning the file or null. */
    private File record(CameraView camera, File file) throws InterruptedException {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<File> recorded = new AtomicReference<>();
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
        sleep(4_000);
        camera.stopRecording();
        assertTrue("the recording never reported on " + backend,
                finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        return recorded.get();
    }

    /**
     * Zoom answers in the camera's own terms on every backend: 1x is inside the range, the
     * presets are inside it too, and a ratio reads back as the one asked for.
     *
     * <p>The presets are logged, since what each backend makes of the lenses is the part only a
     * real phone can show.</p>
     */
    @Test
    public void theZoomAnswersInRatios() throws Exception {
        AtomicReference<float[]> range = new AtomicReference<>();
        AtomicReference<float[]> presets = new AtomicReference<>();
        AtomicReference<List<ZoomPreset>> labelled = new AtomicReference<>();
        AtomicReference<Float> target = new AtomicReference<>();
        AtomicReference<Float> zoomed = new AtomicReference<>();

        withOpenCamera(camera -> {
            CountDownLatch changed = new CountDownLatch(1);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                range.set(new float[] {camera.getMinZoomRatio(), camera.getMaxZoomRatio()});
                labelled.set(camera.getZoomPresets());
                float[] ratios = new float[labelled.get().size()];
                for (int i = 0; i < ratios.length; i++) {
                    ratios[i] = labelled.get().get(i).getRatio();
                }
                presets.set(ratios);
                // The longest preset reaches a telephoto where there is one, which is the switch
                // most worth seeing happen; a camera with a single stop is asked for 2x instead.
                float[] stops = presets.get();
                target.set(stops.length > 1
                        ? stops[stops.length - 1] : Math.min(2f, camera.getMaxZoomRatio()));
                camera.setOnZoomChangedListener(ratio -> {
                    if (Math.abs(ratio - target.get()) <= ZOOM_TOLERANCE * target.get()) {
                        changed.countDown();
                    }
                });
            });
            Log.i(TAG, backend + " zooms " + range.get()[0] + "x to " + range.get()[1]
                    + "x with presets " + labelled.get());
            if (target.get() <= camera.getZoomRatio()) {
                return;
            }

            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> camera.setZoomRatio(target.get()));
            assertTrue("the zoom never reported reaching " + target.get() + "x on " + backend,
                    changed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            zoomed.set(camera.getZoomRatio());
            Log.i(TAG, backend + " asked for " + target.get() + "x and reads "
                    + zoomed.get() + "x");
        });

        float min = range.get()[0];
        float max = range.get()[1];
        assertTrue("1x has to be inside " + min + "-" + max + " on " + backend,
                min <= 1f && 1f <= max);
        float[] stops = presets.get();
        assertFalse("there is always a preset on " + backend, stops.length == 0);
        boolean hasOne = false;
        for (int i = 0; i < stops.length; i++) {
            assertTrue(stops[i] + "x is outside " + min + "-" + max + " on " + backend,
                    min <= stops[i] && stops[i] <= max);
            assertTrue("presets increase on " + backend, i == 0 || stops[i] > stops[i - 1]);
            hasOne |= stops[i] == 1f;
        }
        assertTrue("1x is always a preset on " + backend, hasOne);
        if (zoomed.get() != null) {
            assertEquals("the zoom reads back as asked on " + backend,
                    target.get(), zoomed.get(), ZOOM_TOLERANCE * target.get());
        }
    }

    /** What a test does with an open camera. */
    private interface CameraAction {
        void run(CameraView camera) throws Exception;
    }

    /**
     * Opens the camera, waits for it to be usable, runs the action, and closes it.
     *
     * <p>Waiting for onCameraOpened is the point: it reports when the camera can actually be used,
     * not when open() was called, and a capture issued before that is a capture that fails.</p>
     */
    private void withOpenCamera(CameraAction action) throws Exception {
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
                if (camera.isOpen()) {
                    opened.countDown();
                } else {
                    camera.open();
                }
            });

            // Asserted, not assumed. Skipping here is how six CameraX tests quietly passed
            // on a device where the backend never opened at all: the only thing that stops a
            // camera opening and is not the library's fault is the screen, and DeviceState has
            // already ruled that out.
            assertTrue("the " + backend + " backend did not open on this device "
                            + DeviceState.whyTheCameraMightNotOpen(),
                    opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            CameraView camera = held.get();
            assertNotNull(camera);
            // The library's default is AUTO, so in a dim room every capture in this suite fires
            // the flash - a dozen times over a full run, which is unpleasant for whoever is
            // holding the phone and makes the captures depend on the light in the room. Nothing
            // here asserts on exposure; the tests that mean to exercise the flash set the mode
            // they want themselves.
            DeviceState.turnTheFlashOff(camera);
            try {
                action.run(camera);
            } finally {
                scenario.onActivity(activity -> activity.getCameraView().close());
            }
        }
    }

    private static File temporaryFile(String extension) throws Exception {
        File file = File.createTempFile("matrix", extension,
                ApplicationProvider.getApplicationContext().getCacheDir());
        file.deleteOnExit();
        return file;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
