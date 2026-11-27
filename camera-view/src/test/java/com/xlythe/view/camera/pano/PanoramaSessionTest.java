package com.xlythe.view.camera.pano;

import static com.xlythe.view.camera.pano.FootprintTest.rolled;
import static com.xlythe.view.camera.pano.RotationSensorTest.addSensor;
import static com.xlythe.view.camera.pano.RotationSensorTest.vector;
import static com.xlythe.view.camera.pano.SphereMathTest.matrixOf;
import static com.xlythe.view.camera.pano.SphereMathTest.uprightFacing;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import com.xlythe.view.camera.Exif;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

/**
 * A whole sweep, from frames and rotation to a file on disk, without a camera.
 *
 * <p>Frames are NV21 buffers and the rotation is fed straight into the sensor's history, so each
 * frame is exactly where the test says it is. The main thread is a queue the test drains, which
 * keeps the order of callbacks deterministic.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class PanoramaSessionTest {
    /** A 4:3 sensor, 60 degrees across its long side. */
    private static final float HORIZONTAL_FOV = 60f;
    private static final float VERTICAL_FOV =
            (float) Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(30)) * 3 / 4));

    private static final int FRAME_WIDTH = 320;
    private static final int FRAME_HEIGHT = 240;

    /** The focal length the frames are really taken with, which is the one published. */
    private static final float TRUE_FOCAL =
            CameraPose.focalLength(FRAME_WIDTH, FRAME_HEIGHT, HORIZONTAL_FOV, VERTICAL_FOV);

    /** Frames a tenth of a second apart, which is a slow, steady sweep. */
    private static final long FRAME_NANOS = 100_000_000L;

    private static final long TIMEOUT_MILLIS = 20_000;

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    /** The main thread, as a queue the test drains. */
    private final ConcurrentLinkedQueue<Runnable> mMain = new ConcurrentLinkedQueue<>();

    private final Executor mMainExecutor = mMain::add;

    private Context context;
    private Sensor gameRotation;
    private RotationSensor sensor;
    private Recorder recorder;
    private File file;

    /** The capture time of the next frame. */
    private long time = 1_000_000_000L;

    @Before
    public void setUp() throws IOException {
        context = RuntimeEnvironment.getApplication();
        gameRotation = addSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        sensor = new RotationSensor(context);
        recorder = new Recorder();
        file = new File(folder.getRoot(), "sweep.jpg");
    }

    @Test
    public void photosphere_finishesOnItsOwn_andSavesTheWholeSphere() throws IOException {
        PanoramaSession session = session(true);
        assertTrue(session.start());
        feed(session, 0f, 0f, 0f, false);
        GuideState first = session.getGuideState();
        assertNotNull(first);
        assertTrue(first.photosphere);
        assertTrue(first.nextTarget >= 0);
        // Do what the guide says: aim at the target it shows, a little off and a little turned
        // each time, as a hand does, until it has nothing left to show.
        float[][] wobble = {{0f, 0f, 0f}, {4f, 0f, 30f}, {-4f, 0f, 60f}, {0f, 4f, 15f},
                {0f, -4f, 45f}};
        int aims = 0;
        while (session.isRunning() && aims < 600) {
            GuideState state = session.getGuideState();
            if (state.nextTarget < 0) {
                break;
            }
            float[] offset = wobble[aims % wobble.length];
            feed(session, state.targetYaws[state.nextTarget] + offset[0],
                    state.targetPitches[state.nextTarget] + offset[1], offset[2], false);
            aims++;
        }
        assertTrue("the guide never ran out of targets", aims < 600);
        awaitResult();
        assertEquals(1, recorder.finishing);
        assertEquals(file, recorder.saved);
        assertEquals(1f, recorder.progress.get(recorder.progress.size() - 1), 0f);

        BitmapFactory.Options bounds = decodeBounds(file);
        assertEquals(2 * bounds.outHeight, bounds.outWidth);
        GPanoMetadata metadata = GPanoMetadata.readFrom(file);
        assertNotNull(metadata);
        assertEquals(bounds.outWidth, metadata.fullPanoWidthPixels);
        assertEquals(bounds.outHeight, metadata.fullPanoHeightPixels);
        assertEquals(bounds.outWidth, metadata.croppedAreaImageWidthPixels);
        assertEquals(0, metadata.croppedAreaLeftPixels);
        assertEquals(0, metadata.croppedAreaTopPixels);
        assertEquals("equirectangular", metadata.projectionType);
        assertTrue(metadata.sourcePhotosCount > 40);
        assertNotNull(metadata.firstPhotoDate);
        assertTrue(metadata.lastPhotoDate >= metadata.firstPhotoDate);
        assertEquals(46.8f, metadata.initialHorizontalFovDegrees, 0.1f);
        assertEquals(60f, metadata.initialVerticalFovDegrees, 0.1f);
        // Without a compass the heading is not known, and is not made up.
        assertNull(metadata.poseHeadingDegrees);
        // The Exif every picture carries, which the photo sphere packet written after it keeps.
        ExifInterface exif = new ExifInterface(file.getPath());
        assertNotNull(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));
        assertEquals(Build.MANUFACTURER, exif.getAttribute(ExifInterface.TAG_MAKE));
        assertEquals(Build.MODEL, exif.getAttribute(ExifInterface.TAG_MODEL));
        assertNotNull(exif.getAttribute(ExifInterface.TAG_SOFTWARE));
        assertTrue(exif.getAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID).matches("[0-9a-f]{32}"));
        assertTrue("no thumbnail", exif.hasThumbnail());
        assertNotNull(exif.getThumbnailBitmap());
    }

    @Test
    public void panorama_finishesAllTheWayRound_andSavesABand() throws IOException {
        addSensor(Sensor.TYPE_ROTATION_VECTOR);
        sensor = new RotationSensor(context);
        // The compass's north is 25 degrees clockwise of the gyroscope's.
        sensor.onReading(false, 1, vector(uprightFacing(0f)));
        sensor.onReading(true, 2, vector(uprightFacing(25f)));
        PanoramaSession session = session(false);
        assertTrue(session.start());
        for (int yaw = 30; yaw <= 390 && session.isRunning(); yaw += 5) {
            feed(session, yaw, 0f, 0f, false);
        }
        awaitResult();
        assertEquals(file, recorder.saved);

        BitmapFactory.Options bounds = decodeBounds(file);
        GPanoMetadata metadata = GPanoMetadata.readFrom(file);
        assertNotNull(metadata);
        assertEquals(metadata.fullPanoWidthPixels, bounds.outWidth);
        assertEquals(metadata.croppedAreaImageWidthPixels, bounds.outWidth);
        // A band round the horizon: a little under the frame's sixty degrees tall.
        float tall = 180f * bounds.outHeight / metadata.fullPanoHeightPixels;
        assertTrue("the band is " + tall + " degrees", tall > 45f && tall < 61f);
        assertEquals(bounds.outHeight, metadata.croppedAreaImageHeightPixels);
        assertEquals(0, metadata.initialViewPitchDegrees, 2);
        // The sweep started at 30 by the gyroscope, which is 55 by the compass.
        assertEquals(55f, metadata.poseHeadingDegrees, 1f);
        assertEquals(55f, metadata.initialViewHeadingDegrees, 1f);
    }

    @Test
    public void stoppingEarly_savesWhatWasSwept() throws IOException {
        PanoramaSession session = session(false);
        assertTrue(session.start());
        for (int yaw = 0; yaw <= 90; yaw += 5) {
            feed(session, yaw, 0f, 0f, false);
        }
        assertTrue(session.isRunning());
        session.finish();
        // Asking twice changes nothing.
        session.finish();
        assertFalse(session.isRunning());
        assertEquals(GuideState.SAVING, session.getGuideState().status);
        awaitResult();
        GPanoMetadata metadata = GPanoMetadata.readFrom(file);
        assertNotNull(metadata);
        float wide = 360f * metadata.croppedAreaImageWidthPixels / metadata.fullPanoWidthPixels;
        // Ninety degrees of sweep, and as much of the first and last frames as was painted.
        assertTrue("the panorama is " + wide + " degrees wide", wide > 110f && wide < 137f);
        // Placed in the middle of the full panorama, so it never runs off the edge.
        assertEquals((metadata.fullPanoWidthPixels - metadata.croppedAreaImageWidthPixels) / 2,
                metadata.croppedAreaLeftPixels);
    }

    @Test
    public void aLensThatMisstatesItsFocalLength_isMeasured()
            throws IOException, InterruptedException {
        // Publishing far wider angles than the frames are taken with, as the emulator's virtual
        // scene does.
        PanoramaSession session = new PanoramaSession(context, false, file, 90, 104f, 88f,
                recorder, mMainExecutor, sensor);
        assertTrue(session.start());
        long firstPainted = 0;
        for (int yaw = 0; yaw <= 120; yaw += 4) {
            readings(yaw, 0f, 0f);
            session.onFrame(frame(time, yaw, 0f, 0f, false, TRUE_FOCAL));
            drain();
            if (firstPainted == 0) {
                firstPainted = System.currentTimeMillis();
                // So that painting the frames again at the measured focal length comes later.
                Thread.sleep(5);
            }
        }
        assertEquals(TRUE_FOCAL, session.getGuideState().focal, TRUE_FOCAL * 0.05f);
        session.finish();
        awaitResult();
        GPanoMetadata metadata = GPanoMetadata.readFrom(file);
        assertNotNull(metadata);
        assertEquals(46.8f, metadata.initialHorizontalFovDegrees, 2.5f);
        assertTrue("the first photo is dated when it was painted again",
                metadata.firstPhotoDate <= firstPainted);
        float wide = 360f * metadata.croppedAreaImageWidthPixels / metadata.fullPanoWidthPixels;
        assertTrue("the panorama is " + wide + " degrees wide", wide > 140f && wide < 168f);
    }

    /** A photosphere measures the lens from the turn between dots, before any is taken. */
    @Test
    public void aPhotosphere_measuresTheLens_whileTurning() {
        PanoramaSession session = new PanoramaSession(context, true, file, 90, 104f, 88f,
                recorder, mMainExecutor, sensor);
        assertTrue(session.start());
        for (int yaw = 0; yaw <= 60; yaw += 3) {
            readings(yaw, 0f, 0f);
            session.onFrame(frame(time, yaw, 0f, 0f, false, TRUE_FOCAL));
            drain();
        }
        assertEquals(TRUE_FOCAL, session.getGuideState().focal, TRUE_FOCAL * 0.05f);
        assertNull("nothing was held on a dot", session.getGuideState().thumbnail);
        session.cancel();
    }

    @Test
    public void stoppingBeforeAnyFrame_fails() {
        PanoramaSession session = session(false);
        assertTrue(session.start());
        session.finish();
        awaitResult();
        assertTrue(recorder.failed);
        assertFalse(file.exists());
    }

    @Test
    public void cancelling_writesNothing() {
        PanoramaSession session = session(false);
        assertTrue(session.start());
        // Started well above the horizon, so the panorama is swept along that pitch.
        feed(session, 0f, 30f, 0f, false);
        assertEquals(30f, session.getGuideState().bandPitch, 0.5f);
        session.cancel();
        session.cancel();
        // A frame after the end is ignored.
        feed(session, 20f, 0f, 0f, false);
        drainFor(200);
        assertNull(recorder.saved);
        assertFalse(recorder.failed);
        assertFalse(file.exists());
    }

    @Test
    public void cancellingWhileSaving_removesThePicture() {
        PanoramaSession session = session(false);
        assertTrue(session.start());
        feed(session, 0f, 0f, 0f, false);
        session.finish();
        session.cancel();
        drainFor(2000);
        assertNull(recorder.saved);
        assertFalse(recorder.failed);
        assertFalse(file.exists());
    }

    @Test
    public void withoutARotationSensor_doesNotStart() {
        Shadows.shadowOf((SensorManager) context.getSystemService(Context.SENSOR_SERVICE))
                .removeSensor(gameRotation);
        sensor = new RotationSensor(context);
        assertFalse(session(false).start());
    }

    @Test
    public void withoutOptics_doesNotStart() {
        PanoramaSession session = new PanoramaSession(context, false, file, 90, 0f, 0f, recorder,
                mMainExecutor, sensor);
        assertFalse(session.start());
        assertFalse(session.isRunning());
    }

    @Test
    public void turningTooFast_paintsNothing() {
        PanoramaSession session = session(false);
        assertTrue(session.start());
        // Ninety degrees a second.
        for (int i = 0; i <= 10; i++) {
            sensor.onReading(false, time + i * 10_000_000L, vector(uprightFacing(i * 0.9f)));
        }
        session.onFrame(frame(time + 50_000_000L, false));
        GuideState state = session.getGuideState();
        assertNotNull(state);
        assertEquals(GuideState.TOO_FAST, state.status);
        assertNull(state.thumbnail);
        assertEquals(0f, state.progress, 0f);
    }

    @Test
    public void aFrameWithNoRotationForIt_isIgnored() {
        PanoramaSession session = session(false);
        assertTrue(session.start());
        session.onFrame(frame(time, false));
        assertNull(session.getGuideState());
        float[] matrix = new float[9];
        assertFalse(session.getDisplayRotation(matrix));
    }

    @Test
    public void aFrameAtThePlaceAlreadyPainted_isNotPaintedAgain() {
        PanoramaSession session = session(false);
        assertTrue(session.start());
        feed(session, 0f, 0f, 0f, false);
        int version = session.getGuideState().thumbnailVersion;
        feed(session, 1f, 0f, 0f, false);
        assertEquals(version, session.getGuideState().thumbnailVersion);
        feed(session, 15f, 0f, 0f, false);
        assertEquals(version + 1, session.getGuideState().thumbnailVersion);
    }

    @Test
    public void legacyFrames_areProcessedOffTheCallersThread() {
        PanoramaSession session = session(false);
        assertTrue(session.start());
        readings(0f, 0f, 0f);
        session.offerFrame(frame(time, false));
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (session.getGuideState() == null && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
        assertNotNull(session.getGuideState());
        session.finish();
        // Offered after the end, and dropped.
        session.offerFrame(frame(time, false));
        awaitResult();
        assertEquals(file, recorder.saved);
    }

    /**
     * A rotation that turns while the gyroscope says the phone is still is settling, as the fusion
     * behind it does when it starts, and nothing is placed by it until it holds still too.
     */
    @Test
    public void aSettlingRotation_placesNothing_untilItAgreesWithTheGyroscope() {
        addSensor(Sensor.TYPE_GYROSCOPE);
        sensor = new RotationSensor(context);
        PanoramaSession session = session(false);
        assertTrue(session.start());
        // The reading swings back thirty degrees a second to where the phone points all along.
        for (int i = 0; i < 5; i++) {
            time += FRAME_NANOS;
            float yaw = 15f - 3f * i;
            sensor.onReading(false, time - 50_000_000L, vector(uprightFacing(yaw + 1.5f)));
            sensor.onReading(false, time, vector(uprightFacing(yaw)));
            gyroscopeStill();
            session.onFrame(frame(time, false));
            drain();
        }
        // The guide says to hold still, and nothing has been placed.
        GuideState settling = session.getGuideState();
        assertNotNull(settling);
        assertEquals(GuideState.SETTLING, settling.status);
        assertNull("a frame was placed while the rotation settled", settling.thumbnail);
        assertTrue(recorder.progress.isEmpty());

        readings(0f, 0f, 0f);
        gyroscopeStill();
        session.onFrame(frame(time, false));
        drain();
        assertEquals(GuideState.SWEEPING, session.getGuideState().status);
        assertNotNull(session.getGuideState().thumbnail);
        assertEquals(1, recorder.progress.size());
        session.cancel();
    }

    /**
     * A photosphere takes a frame only once the reticle has been held on its target for the
     * hold's length; looking away starts the hold again.
     */
    @Test
    public void photosphere_takesAFrame_onlyOnceTheReticleIsHeldOnItsTarget() {
        PanoramaSession session = session(true);
        assertTrue(session.start());
        // The first target is where the sweep starts, and is shown from the first frame on.
        feed(session, 0f, 0f, 0f, false);
        for (int i = 0; i < 4; i++) {
            feed(session, 1f, 0f, 0f, false);
        }
        GuideState holding = session.getGuideState();
        assertNull("taken before the hold was up", holding.thumbnail);
        assertTrue(holding.holdProgress > 0.3f && holding.holdProgress < 0.7f);

        // Looking away lets go; coming back starts over.
        feed(session, 25f, 0f, 0f, false);
        assertEquals(0f, session.getGuideState().holdProgress, 0f);
        feed(session, 0f, 0f, 0f, false);
        for (int i = 0; i < 4; i++) {
            feed(session, 0f, 0f, 0f, false);
        }
        assertNull(session.getGuideState().thumbnail);
        for (int i = 0; i < 4; i++) {
            feed(session, 0f, 0f, 0f, false);
        }
        assertNotNull("never taken", session.getGuideState().thumbnail);
        assertEquals(1, recorder.progress.size());
        session.cancel();
    }

    @Test
    public void panorama_saysHowFarItsFramesReachEitherWay() {
        PanoramaSession session = session(false);
        assertTrue(session.start());
        for (int yaw = 0; yaw >= -60; yaw -= 10) {
            feed(session, yaw, 0f, 0f, false);
        }
        GuideState state = session.getGuideState();
        float half = 46.8f / 2;
        assertEquals(-60f - half, state.sweptLeft, 1f);
        assertEquals(half, state.sweptRight, 1f);
        session.cancel();
    }

    @Test
    public void displayRotation_isTheNewestReadingWhenNothingIsNewer() {
        PanoramaSession session = session(false);
        assertTrue(session.start());
        feed(session, 40f, 0f, 0f, false);
        float[] matrix = new float[9];
        assertTrue(session.getDisplayRotation(matrix));
        CameraPose pose = new CameraPose(matrix, 90, false, 320, 240, 200f);
        assertEquals(40f, pose.yaw(), 0.5f);
    }

    @Test
    public void frontCamera_savesAMirroredPicture_withItsLocation() throws IOException {
        Location location = new Location("test");
        location.setLatitude(37.422);
        location.setLongitude(-122.084);
        recorder.location = location;
        PanoramaSession session = session(false);
        assertTrue(session.start());
        for (int yaw = 0; yaw <= 60; yaw += 5) {
            feed(session, yaw, 0f, 0f, true);
        }
        assertTrue(session.getGuideState().frontFacing);
        session.finish();
        awaitResult();
        assertEquals(file, recorder.saved);
        Location tagged = new Exif(file).getLocation();
        assertNotNull(tagged);
        assertEquals(37.422, tagged.getLatitude(), 1e-3);
    }

    @Test
    public void guideState_withStatus_keepsTheRest() {
        PanoramaSession session = session(true);
        assertTrue(session.start());
        feed(session, 0f, 0f, 0f, false);
        GuideState sweeping = session.getGuideState();
        GuideState complete = sweeping.withStatus(GuideState.COMPLETE);
        assertEquals(1f, complete.progress, 0f);
        assertEquals(-1, complete.nextTarget);
        assertEquals(sweeping.thumbnailVersion, complete.thumbnailVersion);
        assertEquals(sweeping.targetYaws.length, complete.targetYaws.length);
        GuideState again = complete.withStatus(GuideState.SWEEPING);
        assertEquals(sweeping.nextTarget, sweeping.withStatus(GuideState.SWEEPING).nextTarget);
        assertEquals(GuideState.SWEEPING, again.status);
    }

    private PanoramaSession session(boolean photosphere) {
        return new PanoramaSession(context, photosphere, file, 90, HORIZONTAL_FOV, VERTICAL_FOV,
                recorder, mMainExecutor, sensor);
    }

    /** Holds the phone still at a pose, and takes a frame there. */
    private void feed(PanoramaSession session, float yaw, float pitch, float roll,
            boolean front) {
        readings(yaw, pitch, roll);
        session.onFrame(frame(time, yaw, pitch, roll, front, TRUE_FOCAL));
        drain();
    }

    /** Readings either side of the next frame's time, all of the same pose: held still. */
    private void readings(float yaw, float pitch, float roll) {
        time += FRAME_NANOS;
        float[] values = vector(rolled(uprightFacing(yaw, pitch), roll));
        sensor.onReading(false, time - 50_000_000L, values);
        sensor.onReading(false, time - 10_000_000L, values);
        sensor.onReading(false, time, values);
    }

    /** The gyroscope reading nothing either side of the next frame's time. */
    private void gyroscopeStill() {
        sensor.onGyroscopeReading(time - 20_000_000L, new float[] {0f, 0f, 0f});
        sensor.onGyroscopeReading(time, new float[] {0f, 0f, 0f});
    }

    /** What the camera sees of the test scene from a pose, through a lens of this focal length. */
    private static PanoramaFrame frame(long timestamp, float yaw, float pitch, float roll,
            boolean front, float focal) {
        int rotation = front ? 270 : 90;
        CameraPose pose = new CameraPose(matrixOf(rolled(uprightFacing(yaw, pitch), roll)),
                rotation, front, FRAME_WIDTH, FRAME_HEIGHT, focal);
        return PanoramaFrame.fromNv21(TestScene.renderNv21(pose), FRAME_WIDTH, FRAME_HEIGHT,
                rotation, front, timestamp);
    }

    /** A frame with nothing in it, for the tests that never get as far as painting. */
    private static PanoramaFrame frame(long timestamp, boolean front) {
        return frame(timestamp, 0f, 0f, 0f, front, TRUE_FOCAL);
    }

    private void drain() {
        Runnable runnable;
        while ((runnable = mMain.poll()) != null) {
            runnable.run();
        }
    }

    /** Drains the main queue until the sweep reports what came of it. */
    private void awaitResult() {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (recorder.saved == null && !recorder.failed
                && System.currentTimeMillis() < deadline) {
            drain();
            Thread.yield();
        }
        drain();
        assertTrue("the sweep never reported back", recorder.saved != null || recorder.failed);
    }

    private void drainFor(long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            drain();
            Thread.yield();
        }
    }

    private static BitmapFactory.Options decodeBounds(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        assertTrue(options.outWidth > 0);
        return options;
    }

    /** Remembers what the sweep said. */
    private static final class Recorder implements PanoramaSession.Callback {
        final List<Float> progress = new ArrayList<>();
        int finishing;
        @Nullable volatile File saved;
        volatile boolean failed;
        @Nullable Location location;

        @Override
        public void onProgress(float value) {
            progress.add(value);
        }

        @Override
        public void onFinishing() {
            finishing++;
        }

        @Override
        public void onSaved(@NonNull File file) {
            saved = file;
        }

        @Override
        public void onFailed() {
            failed = true;
        }

        @Nullable
        @Override
        public Location getLocation() {
            return location;
        }
    }
}
