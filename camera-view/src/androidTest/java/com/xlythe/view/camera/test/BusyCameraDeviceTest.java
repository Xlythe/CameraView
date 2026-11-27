package com.xlythe.view.camera.test;

import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.Log;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.CameraView;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A camera another app is still holding opens once that app lets it go.
 *
 * <p>Another process, {@link CameraHolderActivity}, comes to the front and takes the camera. The
 * camera service ranks the front process first, so the view, asking from behind it, is turned
 * away as busy. The other process lets go a second later, and the view has to be open soon
 * after.</p>
 *
 * <p>CameraX opens only for a host that is started, so on that backend the view asks once the
 * holder has gone, and this checks only that it opens; the retrying is CameraX's own.</p>
 */
@RunWith(Parameterized.class)
public final class BusyCameraDeviceTest {
    /** Where the timing of each handover is logged. */
    private static final String TAG = "BusyCameraDeviceTest";

    /**
     * How long after the view asks that the other process lets the camera go: half the time a
     * view keeps asking, so the camera is free with time to spare.
     */
    private static final long LET_GO_AFTER_MILLIS = 1_000;

    /** The longest the other process holds the camera, should the test never get to say. */
    private static final long MAX_HOLD_MILLIS = 10_000;

    /** The longest to wait for the view's activity to stop behind the other process. */
    private static final long STOP_TIMEOUT_MILLIS = 5_000;

    /**
     * How long the view's process takes, once its activity has stopped, to be ranked below the
     * process in front. Asked before then, the camera service hands the view the camera.
     */
    private static final long RANKING_MILLIS = 500;

    /** Long enough for the other process to start and take the camera. */
    private static final long HOLDER_TIMEOUT_SECONDS = 15;

    /** Long enough for the camera to be let go and then opened. */
    private static final long OPEN_TIMEOUT_SECONDS = 8;

    /** How often to look whether the view's activity has stopped behind the other process. */
    private static final long STOP_POLL_MILLIS = 20;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<CameraView.Backend> backends() {
        return Arrays.asList(
                CameraView.Backend.LEGACY, CameraView.Backend.CAMERA2, CameraView.Backend.CAMERA_X);
    }

    /** The camera, which is all this test opens. */
    @Rule
    public final GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.CAMERA);

    /** The backend under test. */
    private final CameraView.Backend backend;

    public BusyCameraDeviceTest(CameraView.Backend backend) {
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
    public void aCameraAnotherAppHoldsOpensOnceItIsLetGo() throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        AtomicReference<ResultReceiver> letGo = new AtomicReference<>();
        CountDownLatch released = new CountDownLatch(1);
        AtomicBoolean holderFailed = new AtomicBoolean();
        AtomicLong releasedAt = new AtomicLong(Long.MAX_VALUE);
        ResultReceiver receiver = new ResultReceiver(new Handler(Looper.getMainLooper())) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                switch (resultCode) {
                    case CameraHolderActivity.RESULT_HOLDING:
                        // The receiver is the other process's own subclass, which only this
                        // APK's class loader can find.
                        resultData.setClassLoader(BusyCameraDeviceTest.class.getClassLoader());
                        letGo.set(resultData.getParcelable(CameraHolderActivity.EXTRA_LET_GO));
                        holding.countDown();
                        break;
                    case CameraHolderActivity.RESULT_RELEASED:
                        releasedAt.set(resultData.getLong(
                                CameraHolderActivity.EXTRA_LETTING_GO_AT));
                        released.countDown();
                        break;
                    case CameraHolderActivity.RESULT_FAILED:
                    default:
                        holderFailed.set(true);
                        holding.countDown();
                        break;
                }
            }
        };
        CountDownLatch opened = new CountDownLatch(1);
        AtomicLong askedAt = new AtomicLong();
        AtomicLong openedAt = new AtomicLong();
        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            scenario.onActivity(activity -> activity.startActivity(
                    new Intent(activity, CameraHolderActivity.class)
                            .putExtra(CameraHolderActivity.EXTRA_MAX_HOLD_MILLIS, MAX_HOLD_MILLIS)
                            .putExtra(CameraHolderActivity.EXTRA_RECEIVER, receiver)));
            assertTrue("the other process never took the camera",
                    holding.await(HOLDER_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            Assume.assumeFalse("the camera would not open in the other process",
                    holderFailed.get());
            // Asked once the activity is stopped, since stopping it closes the view.
            long stopDeadline = SystemClock.elapsedRealtime() + STOP_TIMEOUT_MILLIS;
            while (scenario.getState() != Lifecycle.State.CREATED
                    && SystemClock.elapsedRealtime() < stopDeadline) {
                Thread.sleep(STOP_POLL_MILLIS);
            }
            Thread.sleep(RANKING_MILLIS);

            scenario.onActivity(activity -> {
                CameraView camera = activity.getCameraView();
                DeviceState.turnTheFlashOff(camera);
                camera.setOnCameraStateChangedListener(
                        new CameraView.OnCameraStateChangedListener() {
                            @Override public void onCameraOpened() {
                                openedAt.set(SystemClock.elapsedRealtime());
                                opened.countDown();
                            }

                            @Override public void onCameraClosed() {}
                        });
                askedAt.set(SystemClock.elapsedRealtime());
                camera.open();
            });
            Thread.sleep(LET_GO_AFTER_MILLIS);
            letGo.get().send(0, null);
            boolean didOpen = opened.await(OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue("the other process never let the camera go",
                    released.await(HOLDER_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            Assume.assumeTrue("the camera was let go before the view asked for it",
                    askedAt.get() < releasedAt.get());
            assertTrue("the " + backend + " backend did not open the camera once it was let go "
                    + DeviceState.whyTheCameraMightNotOpen(), didOpen);
            Assume.assumeTrue("the camera service gave the view the camera while the other"
                    + " process still held it", openedAt.get() >= releasedAt.get());
            Log.i(TAG, backend + " asked " + (releasedAt.get() - askedAt.get())
                    + " ms before the camera was let go and opened "
                    + (openedAt.get() - releasedAt.get()) + " ms after");
            scenario.onActivity(activity -> activity.getCameraView().close());
        }
    }
}
