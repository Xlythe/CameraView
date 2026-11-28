package com.xlythe.view.camera.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;
import android.util.Log;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.MediaTags;
import com.xlythe.view.camera.viewer.MediaListener;
import com.xlythe.view.camera.viewer.MediaView;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Motion photos taken through the real pipeline on each backend: the camera feeds an encoder, the
 * shutter cuts a clip from before it to after it, and the clip is written into the picture.
 *
 * <p>Each file is then read back the way a viewer would. {@link MediaTags} has to find the clip
 * where the file says it is, the clip has to decode, start well before the still and end after it,
 * and its frame at the still's moment has to look like the still - the right way up and the right
 * way round. That comparison is the same coarse brightness grid
 * {@link FrontRecordingMirrorDeviceTest} uses, against the still and against its mirror image; a
 * scene that looks the same either way round decides nothing and skips. The emulator's cameras
 * show a fixed, lopsided scene, which decides it.</p>
 *
 * <p>The still has to come out as it would have without the clip: the same size and the same
 * Exif orientation as a picture taken just before with motion photos off.</p>
 */
@RunWith(Parameterized.class)
public final class MotionPhotoDeviceTest {
    private static final String TAG = "MotionPhoto";

    private static final long TIMEOUT_SECONDS = 30;

    /** Long enough for the encoder to hold a full pre-roll, and exposure to settle. */
    private static final long PRE_ROLL_WAIT_MILLIS = 3_000;

    /** The least of the clip that has to come before the still, given a 1.5 s pre-roll. */
    private static final long MIN_BEFORE_US = 1_000_000;

    /** The least of the clip that has to come after it, given a 1 s post-roll. */
    private static final long MIN_AFTER_US = 500_000;

    /** The grid the frames are compared at. */
    private static final int GRID = 16;

    /** How much better the closer of the two must correlate before the scene decides anything. */
    private static final double DECISIVE_MARGIN = 0.15;

    /** How well the closer of the two must correlate before they count as the same scene. */
    private static final double SAME_SCENE = 0.5;

    /** The least spread of brightness, out of 255, that gives a comparison anything to go on. */
    private static final float MIN_CONTRAST = 24f;

    /**
     * Every backend and lens, or only one backend's when the run is given
     * {@code -e motionBackend CAMERA2}, say.
     */
    @Parameterized.Parameters(name = "{0} {1}")
    public static Collection<Object[]> cases() {
        String only = InstrumentationRegistry.getArguments().getString("motionBackend");
        List<Object[]> cases = new ArrayList<>();
        for (Object[] each : allCases()) {
            if (only == null || only.equals(each[0].toString())) {
                cases.add(each);
            }
        }
        return cases;
    }

    private static List<Object[]> allCases() {
        return Arrays.asList(new Object[][] {
                {CameraView.Backend.CAMERA2, CameraView.LensFacing.BACK},
                {CameraView.Backend.CAMERA2, CameraView.LensFacing.FRONT},
                {CameraView.Backend.CAMERA_X, CameraView.LensFacing.BACK},
                {CameraView.Backend.CAMERA_X, CameraView.LensFacing.FRONT},
                {CameraView.Backend.LEGACY, CameraView.LensFacing.BACK},
                {CameraView.Backend.LEGACY, CameraView.LensFacing.FRONT},
        });
    }

    @Rule
    public final GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO);

    @Rule
    public final TestRule halStalls = DeviceState.skipWhenTheEmulatorCameraHalStalls();

    private final CameraView.Backend backend;
    private final CameraView.LensFacing lensFacing;

    public MotionPhotoDeviceTest(CameraView.Backend backend, CameraView.LensFacing lensFacing) {
        this.backend = backend;
        this.lensFacing = lensFacing;
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

    /** The claim: a picture taken with motion photos on carries its clip, as it should be. */
    @Test
    public void aPictureCarriesTheMomentsAroundItsShutter() throws Exception {
        try (Session session = open()) {
            File plain = session.takePicture(false);
            File motion = session.takePicture(true);
            assertNull("a picture taken with motion photos off is a plain still",
                    MediaTags.open(plain).getMotionPhoto());

            Clip clip = Clip.of(motion);
            Log.i(TAG, String.format(Locale.US, "%s %s: clip %d bytes, %d ms with the still at"
                            + " %d ms, %dx%d turned %d; delivered %d ms after the shutter; still"
                            + " %s",
                    backend, lensFacing, clip.length, clip.durationUs / 1000,
                    clip.presentationUs / 1000, clip.width, clip.height, clip.rotation,
                    session.lastDeliveryMillis, describe(motion)));
            assertTrue("only " + clip.presentationUs + " us of the clip is before the still",
                    clip.presentationUs >= MIN_BEFORE_US);
            assertTrue("only " + (clip.durationUs - clip.presentationUs)
                            + " us of the clip is after the still",
                    clip.durationUs - clip.presentationUs >= MIN_AFTER_US);
            keep(motion);
            assertSameStill(plain, motion);
            assertLooksLikeTheStill(motion, clip);
            assertMediaViewPlaysIt(session, motion);
        }
    }

    /** Turned off, pictures are plain again; turned back on, they carry clips again. */
    @Test
    public void turningItOffAndOnTakesEffect() throws Exception {
        try (Session session = open()) {
            assertNotNull(MediaTags.open(session.takePicture(true)).getMotionPhoto());
            assertNull(MediaTags.open(session.takePicture(false)).getMotionPhoto());
            assertNotNull(MediaTags.open(session.takePicture(true)).getMotionPhoto());
        }
    }

    /** The encoder is fed again after the camera closes and opens, and after a lens switch. */
    @Test
    public void aReopenedOrSwitchedCameraStillTakesThem() throws Exception {
        try (Session session = open()) {
            session.setMotionPhotoEnabled(true);
            session.reopen();
            assertNotNull("after a reopen",
                    MediaTags.open(session.takePicture(true)).getMotionPhoto());
            if (session.toggle()) {
                assertNotNull("after a lens switch",
                        MediaTags.open(session.takePicture(true)).getMotionPhoto());
            }
        }
    }

    /** A picture taken while recording has no clip, and the recording is not cut short. */
    @Test
    public void aPictureWhileRecordingIsAPlainStill() throws Exception {
        DeviceState.assumeTheBackendCanRecord(backend);
        try (Session session = open()) {
            session.setMotionPhotoEnabled(true);
            Assume.assumeTrue("this camera takes no pictures while recording",
                    session.camera.isSnapshotWhileRecordingSupported());
            File video = session.startRecording();
            SystemClock.sleep(1_500);
            File picture = session.takePicture(true);
            File recorded = session.stopRecording();
            assertNull(MediaTags.open(picture).getMotionPhoto());
            assertTrue("the recording survived the picture", recorded.length() > 0);
            assertEquals(video, recorded);
            assertNotNull("and clips are back once it ends",
                    MediaTags.open(session.takePicture(true)).getMotionPhoto());
        }
    }

    // ---------------------------------------------------------------------------------------

    /**
     * Copies a motion photo where adb can pull it, as {@code motion/<backend>-<lens>.jpg} in the
     * app's external files, to open in a gallery afterwards.
     */
    private void keep(@NonNull File picture) throws IOException {
        File external = ApplicationProvider.getApplicationContext().getExternalFilesDir(null);
        if (external == null) {
            return;
        }
        File directory = new File(external, "motion");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            return;
        }
        File kept = new File(directory, String.format(Locale.US, "%s-%s.jpg",
                backend, lensFacing).toLowerCase(Locale.US));
        Clip.copy(picture, 0, picture.length(), kept);
    }

    private static String describe(@NonNull File picture) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(picture.getAbsolutePath(), bounds);
        ExifInterface exif = new ExifInterface(picture.getAbsolutePath());
        return bounds.outWidth + "x" + bounds.outHeight + " turned " + exif.getRotationDegrees()
                + (exif.isFlipped() ? " flipped" : "") + ", " + picture.length() + " bytes";
    }

    private static void assertSameStill(@NonNull File plain, @NonNull File motion)
            throws IOException {
        BitmapFactory.Options before = new BitmapFactory.Options();
        before.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(plain.getAbsolutePath(), before);
        BitmapFactory.Options after = new BitmapFactory.Options();
        after.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(motion.getAbsolutePath(), after);
        assertEquals("the still's width", before.outWidth, after.outWidth);
        assertEquals("the still's height", before.outHeight, after.outHeight);
        ExifInterface plainExif = new ExifInterface(plain.getAbsolutePath());
        ExifInterface motionExif = new ExifInterface(motion.getAbsolutePath());
        assertEquals("the still's orientation",
                plainExif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0),
                motionExif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0));
    }

    /**
     * The clip's frame at the still's moment, shown as its tag says, against the still shown as
     * its Exif says: the same way up, and the same way round.
     */
    private void assertLooksLikeTheStill(@NonNull File motion, @NonNull Clip clip)
            throws IOException {
        Bitmap still = ShownFrames.ofPicture(motion);
        Bitmap frame = ShownFrames.ofRecording(clip.mp4, clip.presentationUs);
        assertNotNull("no frame decodes at the still's moment", frame);
        assertEquals("the clip is shown " + (frame.getWidth() > frame.getHeight()
                        ? "landscape" : "portrait") + " beside a still that is not",
                still.getWidth() > still.getHeight(), frame.getWidth() > frame.getHeight());
        Bitmap shapedFrame = ShownFrames.centredTo(frame, still.getWidth(), still.getHeight());
        float[] shown = ShownFrames.brightness(still, GRID, GRID);
        float[] clipped = ShownFrames.brightness(shapedFrame, GRID, GRID);
        float darkest = Float.MAX_VALUE;
        float brightest = 0f;
        for (float cell : shown) {
            darkest = Math.min(darkest, cell);
            brightest = Math.max(brightest, cell);
        }
        Assume.assumeTrue("the scene is too dark or too even to compare (brightness "
                        + darkest + " to " + brightest + " of 255)",
                brightest - darkest > MIN_CONTRAST);
        double same = ShownFrames.correlation(clipped, shown);
        double mirrored = ShownFrames.correlation(clipped, ShownFrames.mirrored(shown, GRID, GRID));
        String scores = String.format(Locale.US,
                "(correlation with the still %.2f, with its mirror image %.2f)", same, mirrored);
        Log.i(TAG, backend + " " + lensFacing + " " + scores);
        assertTrue("the clip is not the scene the still is " + scores,
                Math.max(same, mirrored) > SAME_SCENE);
        Assume.assumeTrue("the scene looks the same either way round " + scores,
                Math.abs(same - mirrored) > DECISIVE_MARGIN);
        assertTrue("the " + lensFacing + " clip is the mirror image of its still " + scores,
                same > mirrored);
    }

    /** MediaView finds the clip in the file and plays it while the picture is held. */
    private void assertMediaViewPlaysIt(@NonNull Session session, @NonNull File motion)
            throws InterruptedException {
        CountDownLatch loaded = new CountDownLatch(1);
        AtomicInteger type = new AtomicInteger(-1);
        AtomicReference<MediaView> view = new AtomicReference<>();
        session.scenario.onActivity(activity -> {
            activity.getCameraView().close();
            MediaView media = new MediaView(activity);
            media.setMediaListener(new MediaListener() {
                @Override
                public void onMediaLoaded(int mediaType) {
                    type.set(mediaType);
                    loaded.countDown();
                }

                @Override
                public void onMediaFailed() {
                    loaded.countDown();
                }
            });
            activity.addContentView(media, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            media.setFile(motion);
            view.set(media);
        });
        assertTrue("MediaView never loaded the picture",
                loaded.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals("MediaView shows it as a motion photo", MediaView.MOTION_PHOTO, type.get());
        // Prepared off the main thread after the still shows.
        SystemClock.sleep(1_000);
        AtomicBoolean playing = new AtomicBoolean();
        session.scenario.onActivity(activity -> {
            view.get().performLongClick();
        });
        long deadline = SystemClock.uptimeMillis() + 3_000;
        while (!playing.get() && SystemClock.uptimeMillis() < deadline) {
            session.scenario.onActivity(activity -> playing.set(view.get().isPlaying()));
            SystemClock.sleep(100);
        }
        assertTrue("holding the picture plays its clip", playing.get());
    }

    // ---------------------------------------------------------------------------------------

    /** The clip a motion photo carries, copied out to an MP4 of its own. */
    private static final class Clip {
        File mp4;
        long length;
        long presentationUs;
        long durationUs;
        int width;
        int height;
        int rotation;

        @NonNull
        static Clip of(@NonNull File picture) throws IOException {
            MediaTags.MotionPhoto motionPhoto = MediaTags.open(picture).getMotionPhoto();
            assertNotNull("the picture is not a motion photo", motionPhoto);
            assertEquals(MediaTags.MotionPhoto.FORMAT_MOTION_PHOTO, motionPhoto.getFormat());
            assertEquals("the clip runs to the end of the file", picture.length(),
                    motionPhoto.getVideoOffset() + motionPhoto.getVideoLength());
            Clip clip = new Clip();
            clip.length = motionPhoto.getVideoLength();
            clip.presentationUs = motionPhoto.getPresentationTimestampUs();
            clip.mp4 = File.createTempFile("clip", ".mp4",
                    ApplicationProvider.getApplicationContext().getCacheDir());
            copy(picture, motionPhoto.getVideoOffset(), motionPhoto.getVideoLength(), clip.mp4);
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(clip.mp4.getAbsolutePath());
                clip.durationUs = 1000L * Long.parseLong(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION));
                clip.width = Integer.parseInt(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                clip.height = Integer.parseInt(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                clip.rotation = Integer.parseInt(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            } finally {
                retriever.release();
            }
            return clip;
        }

        private static void copy(@NonNull File from, long offset, long length, @NonNull File to)
                throws IOException {
            try (InputStream input = new FileInputStream(from);
                 OutputStream output = new FileOutputStream(to)) {
                long skipped = 0;
                while (skipped < offset) {
                    skipped += input.skip(offset - skipped);
                }
                byte[] buffer = new byte[64 * 1024];
                long left = length;
                int read;
                while (left > 0
                        && (read = input.read(buffer, 0, (int) Math.min(buffer.length, left))) > 0) {
                    output.write(buffer, 0, read);
                    left -= read;
                }
            }
        }
    }

    /** One open camera in the test activity, on this test's backend and lens. */
    private final class Session implements AutoCloseable {
        final ActivityScenario<CameraTestActivity> scenario;
        CameraView camera;
        /** From the shutter to the picture being reported, for the last picture taken. */
        long lastDeliveryMillis;
        @Nullable private File mRecording;
        @Nullable private CountDownLatch mRecorded;
        @Nullable private volatile File mRecordedFile;

        Session() {
            scenario = ActivityScenario.launch(CameraTestActivity.class);
        }

        void open() throws InterruptedException {
            CountDownLatch opened = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                camera = activity.getCameraView();
                camera.setLensFacing(lensFacing);
                DeviceState.turnTheFlashOff(camera);
                camera.setShutterSoundEnabled(false);
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
            assertTrue("the camera did not open " + DeviceState.whyTheCameraMightNotOpen(),
                    opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }

        void reopen() throws InterruptedException {
            scenario.onActivity(activity -> activity.getCameraView().close());
            open();
        }

        /** Switches lens, and reports whether there was another to switch to. */
        boolean toggle() throws InterruptedException {
            AtomicBoolean hasOther = new AtomicBoolean();
            scenario.onActivity(activity -> hasOther.set(camera.hasFrontFacingCamera()));
            if (!hasOther.get()) {
                return false;
            }
            scenario.onActivity(activity -> camera.toggleCamera());
            SystemClock.sleep(2_000);
            return true;
        }

        void setMotionPhotoEnabled(boolean enabled) {
            scenario.onActivity(activity -> camera.setMotionPhotoEnabled(enabled));
        }

        /** Takes a picture with motion photos on or off, waiting out a pre-roll when on. */
        @NonNull
        File takePicture(boolean motion) throws Exception {
            AtomicBoolean supported = new AtomicBoolean();
            scenario.onActivity(activity -> {
                camera.setMotionPhotoEnabled(motion);
                supported.set(camera.isMotionPhotoSupported());
            });
            if (motion) {
                Assume.assumeTrue(backend + " cannot take motion photos with the " + lensFacing
                        + " camera here", supported.get());
            }
            SystemClock.sleep(PRE_ROLL_WAIT_MILLIS);
            File file = File.createTempFile("motion", ".jpg",
                    ApplicationProvider.getApplicationContext().getCacheDir());
            CountDownLatch captured = new CountDownLatch(1);
            AtomicBoolean failed = new AtomicBoolean();
            AtomicLong shutterAt = new AtomicLong();
            scenario.onActivity(activity -> {
                camera.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
                    @Override public void onImageConfirmation() {}

                    @Override public void onImageCaptured(File saved) {
                        captured.countDown();
                    }

                    @Override public void onFailure() {
                        failed.set(true);
                        captured.countDown();
                    }
                });
                shutterAt.set(SystemClock.uptimeMillis());
                camera.takePicture(file);
            });
            assertTrue("the picture never arrived",
                    captured.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            lastDeliveryMillis = SystemClock.uptimeMillis() - shutterAt.get();
            assertFalse("the picture failed", failed.get());
            assertTrue("the picture is empty", file.length() > 0);
            return file;
        }

        @NonNull
        File startRecording() throws IOException {
            mRecording = File.createTempFile("motion", ".mp4",
                    ApplicationProvider.getApplicationContext().getCacheDir());
            mRecorded = new CountDownLatch(1);
            CountDownLatch recorded = mRecorded;
            scenario.onActivity(activity -> {
                camera.setOnVideoCapturedListener(new CameraView.OnVideoCapturedListener() {
                    @Override public void onVideoConfirmation() {}

                    @Override public void onVideoCaptured(File saved) {
                        mRecordedFile = saved;
                        recorded.countDown();
                    }

                    @Override public void onFailure() {
                        recorded.countDown();
                    }
                });
                camera.startRecording(mRecording);
            });
            return mRecording;
        }

        @NonNull
        File stopRecording() throws InterruptedException {
            scenario.onActivity(activity -> camera.stopRecording());
            assertTrue("the recording never finished", mRecorded != null
                    && mRecorded.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertNotNull("the recording failed", mRecordedFile);
            return mRecordedFile;
        }

        @Override
        public void close() {
            scenario.onActivity(activity -> {
                CameraView view = activity.getCameraView();
                view.setMotionPhotoEnabled(false);
                view.close();
            });
            scenario.close();
        }
    }

    @NonNull
    private Session open() throws InterruptedException {
        Session session = new Session();
        session.open();
        AtomicBoolean hasLens = new AtomicBoolean();
        session.scenario.onActivity(activity ->
                hasLens.set(activity.getCameraView().getLensFacing() == lensFacing));
        if (!hasLens.get()) {
            session.close();
            Assume.assumeTrue("this device has no " + lensFacing + " camera", false);
        }
        return session;
    }
}
