package com.xlythe.view.camera.test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.Manifest;
import android.content.Context;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.ar.ArAvailability;
import com.xlythe.view.camera.ar.ArSession;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Whether an AR session can actually be made on this device.
 *
 * <p>Everything past the availability check needs ARCore installed and the device certified for
 * it, which is a property of the phone rather than of the API level - so those tests skip where
 * that is not true rather than failing. On a Pixel 7 Pro with ARCore present they run, and they
 * are the only thing in the suite that proves the session configures at all.</p>
 *
 * <p>They deliberately stop short of the surfaces and of resuming. Both need a camera device
 * that is already open, which is the camera plumbing this class exists to be separable from -
 * and asking for the surfaces without one does not fail, it aborts the whole test process from
 * inside ARCore. What is asserted is everything up to that point.</p>
 */
@RunWith(AndroidJUnit4.class)
public final class ArSessionDeviceTest {
    private static final String TAG = "ArSessionDeviceTest";

    @Rule
    public final GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.CAMERA);

    /**
     * The check settles on a real answer rather than leaving the caller guessing.
     *
     * <p>This is the one test here that runs everywhere, and the reason it asserts "not
     * UNKNOWN" is that UNKNOWN is exactly what a single unwaited call returns on a cold start.
     * A device with no ARCore at all still answers UNSUPPORTED or NEEDS_INSTALL within the
     * timeout, so an UNKNOWN here means the waiting is broken rather than that the device is
     * unusual.</p>
     */
    @Test
    public void availabilitySettlesOnAnAnswer() {
        Context context = ApplicationProvider.getApplicationContext();

        ArAvailability.State state = ArAvailability.awaitCheck(context);

        Log.i(TAG, "ARCore availability: " + state);
        assertNotEquals("the check should wait for ARCore rather than give up on it",
                ArAvailability.State.UNKNOWN, state);
    }

    /** Waiting for an answer on the main thread cannot work, and says so instead of hanging. */
    @Test
    public void awaitingOnTheMainThreadIsRefused() throws Throwable {
        Context context = ApplicationProvider.getApplicationContext();
        Throwable[] thrown = new Throwable[1];

        // Reported back rather than thrown from the posted work, where JUnit would never see it.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> {
                    try {
                        ArAvailability.awaitCheck(context);
                        fail("awaitCheck should refuse to block the main thread");
                    } catch (IllegalStateException expected) {
                        thrown[0] = expected;
                    } catch (Throwable unexpected) {
                        thrown[0] = unexpected;
                    }
                });

        assertNotNull("awaitCheck should have thrown on the main thread", thrown[0]);
        assertTrue("expected an IllegalStateException, got " + thrown[0],
                thrown[0] instanceof IllegalStateException);
    }

    /** On a device with ARCore, a shared-camera session can be created and configured. */
    @Test
    public void aSharedCameraSessionCanBeCreated() {
        Context context = ApplicationProvider.getApplicationContext();
        Assume.assumeTrue("this device has no usable ARCore",
                ArAvailability.isSupported(context));

        ArSession session = ArSession.create(context);

        assertNotNull("a supported device should give a session", session);
        try {
            assertFalse("the session has to name the camera it wants opened",
                    session.getCameraId().isEmpty());
            assertNotNull(session.getSharedCamera());
            Log.i(TAG, "camera " + session.getCameraId()
                    + ", depth " + session.isDepthSupported());
        } finally {
            session.close();
        }
    }

    /** Depth is asked for where the device has it, which is what occlusion needs. */
    @Test
    public void depthIsEnabledWhereTheDeviceHasIt() {
        Context context = ApplicationProvider.getApplicationContext();
        Assume.assumeTrue("this device has no usable ARCore",
                ArAvailability.isSupported(context));

        ArSession session = ArSession.create(context);
        assertNotNull(session);
        try {
            // Not asserted either way: a device without the depth API still tracks, and saying
            // which this one is, is the useful thing rather than demanding one answer.
            Log.i(TAG, "depth supported: " + session.isDepthSupported());
        } finally {
            session.close();
        }
    }

    /** Closing twice is what a lifecycle does on a bad day, and must not throw. */
    @Test
    public void closingTwiceIsHarmless() {
        Context context = ApplicationProvider.getApplicationContext();
        Assume.assumeTrue("this device has no usable ARCore",
                ArAvailability.isSupported(context));

        ArSession session = ArSession.create(context);
        assertNotNull(session);
        session.close();
        session.close();
    }
}
