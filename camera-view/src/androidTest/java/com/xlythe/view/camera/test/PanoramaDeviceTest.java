package com.xlythe.view.camera.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.pano.DeviceOrientation;
import com.xlythe.view.camera.pano.Equirectangular;
import com.xlythe.view.camera.pano.GPanoMetadata;
import com.xlythe.view.camera.pano.PanoramaSession;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Whether a sweep actually produces a panorama on a real camera.
 *
 * <p>The projection, the coverage grid, the metadata and the frame conversion all have their own
 * tests off the device. What cannot be checked there is the join: that frames of the format this
 * camera really produces convert, that the angles of view the backend reports are the right way
 * round for the frames it delivers, and that what comes out the far end is a file a viewer will
 * pan around.</p>
 *
 * <p>Nobody turns the phone during this, so the sweep covers one cell and stops. That is still
 * the whole pipeline - a frame arrives, is placed, is painted, and is saved with its metadata -
 * and the thing it cannot tell you is whether a long sweep lines up with itself. That needs a
 * person and a room.</p>
 */
@RunWith(Parameterized.class)
public final class PanoramaDeviceTest {
    private static final String TAG = "PanoramaDeviceTest";
    private static final long TIMEOUT_SECONDS = 30;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<CameraView.Backend> backends() {
        // The two backends that can deliver frames to an analyzer. The old one cannot, which
        // startPanorama refuses rather than accepting and never honouring.
        return Arrays.asList(CameraView.Backend.CAMERA2, CameraView.Backend.CAMERA_X);
    }

    @Parameterized.Parameter
    public CameraView.Backend backend;

    @Rule
    public final GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.CAMERA);

    @Before
    public void useTheBackendUnderTest() {
        DeviceState.assumeATestableCamera();
        CameraView.setDefaultBackend(backend);
    }

    @After
    public void restoreTheDefault() {
        CameraView.setDefaultBackend(CameraView.Backend.AUTO);
    }

    /** Collects what the sweep reports, from whichever thread it reports on. */
    private static final class Sweep implements PanoramaSession.Listener {
        private final CountDownLatch mProgressed = new CountDownLatch(1);
        private final CountDownLatch mFinished = new CountDownLatch(1);
        private final AtomicInteger mReports = new AtomicInteger();
        private final AtomicReference<Float> mProgress = new AtomicReference<>(0f);
        private final AtomicReference<File> mSaved = new AtomicReference<>();
        private final AtomicBoolean mFailed = new AtomicBoolean();

        @Override
        public void onPanoramaProgress(float progress, float nextYaw, float nextPitch) {
            mReports.incrementAndGet();
            mProgress.set(progress);
            mProgressed.countDown();
        }

        @Override
        public void onPanoramaCaptured(@NonNull File file) {
            mSaved.set(file);
            mFinished.countDown();
        }

        @Override
        public void onPanoramaFailed() {
            mFailed.set(true);
            mFinished.countDown();
        }
    }

    /**
     * The claim: a sweep paints what the camera sees and saves it as a panorama.
     *
     * <p>One test rather than several because opening a camera is most of the runtime and every
     * assertion here needs the same opened one: that frames reach the sweep at all, that they
     * convert and get placed, and that the file is a panorama rather than a wide photograph.</p>
     *
     * <p>A sphere rather than a band, so that it runs whichever way the phone happens to be
     * lying. A flat panorama only accepts frames near the horizon - correctly, since a picture
     * of the floor does not belong in one - and a phone face up on a desk points at the floor,
     * so that sweep is {@link #aFlatSweepNeedsThePhoneHeldUp} instead.</p>
     */
    @Test
    public void aSweepWritesAPanorama() throws Exception {
        Sweep sweep = new Sweep();
        File file = new File(
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                        .getTargetContext().getCacheDir(),
                "panorama-" + backend + ".jpg");
        if (file.exists()) {
            assertTrue(file.delete());
        }

        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            CountDownLatch opened = new CountDownLatch(1);
            AtomicReference<CameraView> held = new AtomicReference<>();
            AtomicBoolean analysable = new AtomicBoolean();
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

            assertTrue(backend + " did not open " + DeviceState.whyTheCameraMightNotOpen(),
                    opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            CameraView camera = held.get();
            assertNotNull(camera);
            DeviceState.turnTheFlashOff(camera);
            scenario.onActivity(activity ->
                    analysable.set(activity.getCameraView().isFrameAnalysisSupported()));
            Assume.assumeTrue(backend + " cannot deliver frames to an analyzer",
                    analysable.get());

            AtomicReference<RuntimeException> refused = new AtomicReference<>();
            scenario.onActivity(activity -> {
                try {
                    activity.getCameraView().startPanorama(
                            CameraView.PanoramaMode.PHOTOSPHERE, sweep);
                } catch (RuntimeException error) {
                    refused.set(error);
                }
            });
            // A device with no rotation sensor is a real configuration and is refused by name,
            // which is a skip rather than a failure.
            Assume.assumeTrue("this device cannot report its rotation: " + refused.get(),
                    refused.get() == null);

            assertTrue("no frame was ever placed, so either none arrived or none could be"
                            + " converted - the angles of view and the frame format are what to"
                            + " look at",
                    sweep.mProgressed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            Log.i(TAG, backend + " placed " + sweep.mReports.get() + " frames, progress "
                    + sweep.mProgress.get());

            scenario.onActivity(activity -> activity.getCameraView().stopPanorama(file));
            assertTrue("the sweep never reported what became of it",
                    sweep.mFinished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            scenario.onActivity(activity -> activity.getCameraView().close());
        }

        assertTrue("the sweep reported a failure having placed frames", !sweep.mFailed.get());
        File saved = sweep.mSaved.get();
        assertNotNull("nothing was saved", saved);
        assertTrue("the file is empty", saved.length() > 0);

        Bitmap decoded = BitmapFactory.decodeFile(saved.getAbsolutePath());
        assertNotNull("what was saved is not a readable picture", decoded);
        try {
            // A sphere is the whole canvas, which is two to one. Anything else means the crop
            // took a bite out of a sweep that asked for everything.
            assertEquals("a photosphere is twice as wide as it is tall",
                    decoded.getWidth(), decoded.getHeight() * 2);
        } finally {
            decoded.recycle();
        }

        String xmp = GPanoMetadata.readXmp(saved);
        assertNotNull("a panorama without GPano metadata is just a wide photograph", xmp);
        assertTrue(xmp, xmp.contains("GPano:ProjectionType=\"equirectangular\""));
        assertTrue("the full sphere has to be described for the crop to mean anything",
                xmp.contains("GPano:FullPanoWidthPixels=\""
                        + PanoramaSession.DEFAULT_CANVAS_WIDTH + "\""));
    }

    /**
     * A flat panorama takes frames near the horizon, which needs the phone held up.
     *
     * <p>Skipped rather than failed when it is not, because refusing a frame of the floor is
     * what a flat panorama is for: the band is a few degrees either side of eye level, and a
     * phone lying on a desk points ninety degrees below it. Stand the phone up and this runs.
     * </p>
     */
    @Test
    public void aFlatSweepNeedsThePhoneHeldUp() throws Exception {
        float pitch = measurePitch();
        Assume.assumeTrue("the camera is pointed " + Math.round(pitch) + " degrees from the"
                        + " horizon, so a flat panorama has nothing to put in its band - stand"
                        + " the phone upright and run again",
                Math.abs(pitch) < 20f);
        Sweep sweep = new Sweep();

        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            openCamera(scenario);
            AtomicReference<RuntimeException> refused = new AtomicReference<>();
            scenario.onActivity(activity -> {
                try {
                    activity.getCameraView().startPanorama(
                            CameraView.PanoramaMode.PANORAMA, sweep);
                } catch (RuntimeException error) {
                    refused.set(error);
                }
            });
            Assume.assumeTrue(refused.get() == null);

            assertTrue("a camera pointed at the horizon should place a frame in a flat panorama",
                    sweep.mProgressed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            scenario.onActivity(activity -> {
                activity.getCameraView().stopPanorama(new File(
                        activity.getCacheDir(), "band-" + backend + ".jpg"));
                activity.getCameraView().close();
            });
            assertTrue(sweep.mFinished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            File saved = sweep.mSaved.get();
            assertNotNull("a flat sweep saved nothing", saved);
            Bitmap decoded = BitmapFactory.decodeFile(saved.getAbsolutePath());
            assertNotNull(decoded);
            try {
                assertTrue("a band should be far wider than it is tall, got "
                                + decoded.getWidth() + "x" + decoded.getHeight(),
                        decoded.getWidth() > decoded.getHeight() * 4);
            } finally {
                decoded.recycle();
            }
        }
    }

    /**
     * How far from the horizon the camera points, through the conversion the sweep itself uses.
     *
     * <p>Read rather than assumed, because whether a flat sweep can mean anything depends on
     * how the phone is lying and nothing else in the suite knows that.</p>
     */
    private static float measurePitch() throws InterruptedException {
        Context context = ApplicationProvider.getApplicationContext();
        SensorManager sensors =
                (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Assume.assumeNotNull(sensors);
        Sensor sensor = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (sensor == null) {
            sensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }
        Assume.assumeNotNull("this device cannot report its rotation", sensor);
        CountDownLatch read = new CountDownLatch(1);
        float[] measured = new float[1];
        SensorEventListener listener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                float[] enu = new float[16];
                float[] panorama = new float[16];
                float[] direction = new float[2];
                SensorManager.getRotationMatrixFromVector(enu, event.values);
                DeviceOrientation.toPanoramaFrame(enu, panorama);
                if (Equirectangular.directionFor(0.5f, 0.5f, panorama, 60f, 45f, direction)) {
                    measured[0] = direction[1];
                    read.countDown();
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        };
        sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME);
        try {
            assertTrue("the rotation sensor never reported",
                    read.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            sensors.unregisterListener(listener);
        }
        return measured[0];
    }

    /** Opens the camera and waits for it, since every test here needs exactly that. */
    private void openCamera(ActivityScenario<CameraTestActivity> scenario)
            throws InterruptedException {
        CountDownLatch opened = new CountDownLatch(1);
        AtomicBoolean analysable = new AtomicBoolean();
        scenario.onActivity(activity -> {
            CameraView camera = activity.getCameraView();
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
        assertTrue(backend + " did not open " + DeviceState.whyTheCameraMightNotOpen(),
                opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        scenario.onActivity(activity -> {
            DeviceState.turnTheFlashOff(activity.getCameraView());
            analysable.set(activity.getCameraView().isFrameAnalysisSupported());
        });
        Assume.assumeTrue(backend + " cannot deliver frames to an analyzer", analysable.get());
    }

    /** A stationary phone covers a little and not nothing, which is what proves frames landed. */
    @Test
    public void aStationarySweepCoversSomeOfTheSphereAndNotAllOfIt() throws Exception {
        Sweep sweep = new Sweep();

        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            CountDownLatch opened = new CountDownLatch(1);
            AtomicBoolean analysable = new AtomicBoolean();
            scenario.onActivity(activity -> {
                CameraView camera = activity.getCameraView();
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
            assertTrue(opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            scenario.onActivity(activity -> {
                DeviceState.turnTheFlashOff(activity.getCameraView());
                analysable.set(activity.getCameraView().isFrameAnalysisSupported());
            });
            Assume.assumeTrue(analysable.get());

            AtomicReference<RuntimeException> refused = new AtomicReference<>();
            scenario.onActivity(activity -> {
                try {
                    activity.getCameraView().startPanorama(
                            CameraView.PanoramaMode.PHOTOSPHERE, sweep);
                } catch (RuntimeException error) {
                    refused.set(error);
                }
            });
            Assume.assumeTrue(refused.get() == null);

            assertTrue(sweep.mProgressed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            float progress = sweep.mProgress.get();
            scenario.onActivity(activity -> {
                PanoramaSession session = activity.getCameraView().getPanoramaSession();
                assertNotNull("the sweep should be reachable while it runs", session);
                activity.getCameraView().stopPanorama(new File(
                        activity.getCacheDir(), "sphere-" + backend + ".jpg"));
                activity.getCameraView().close();
            });

            assertTrue("a frame landed somewhere, so progress cannot be zero", progress > 0f);
            assertTrue("a phone on a desk cannot have covered the sphere, got " + progress,
                    progress < 1f);
            assertEquals("and the sweep should not have been reported complete",
                    false, sweep.mFailed.get());
        }
    }
}
