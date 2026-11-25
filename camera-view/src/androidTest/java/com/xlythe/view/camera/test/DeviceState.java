package com.xlythe.view.camera.test;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.annotation.NonNull;

import org.junit.Assume;
import com.xlythe.view.camera.CameraView;

/**
 * What the device has to be for a camera test to mean anything.
 *
 * <p>A camera needs a visible window to put its preview in, so the state of the screen decides
 * whether these tests are testing the library or the lock screen. {@link CameraTestActivity}
 * turns the screen on and asks the keyguard to stand aside, which covers everything except a
 * device with a real PIN - and that one is worth saying out loud, because the alternative is six
 * tests timing out thirty seconds at a time and blaming the backend.</p>
 */
final class DeviceState {
    private DeviceState() {}

    /**
     * Turns the flash off for a test that does not care about it.
     *
     * <p>Separate from the tests so there is one place to look when a run starts flashing.</p>
     */
    static void turnTheFlashOff(@NonNull CameraView camera) {
        camera.setFlash(CameraView.Flash.OFF);
    }

    /**
     * Skips a test whose whole point is to light the lamp, when someone has asked for a dark run.
     *
     * <p>Pass {@code -Pandroid.testInstrumentationRunnerArguments.lamp=off} and nothing in the
     * suite lights up. The default is to run it, because a torch that never turns on is a torch
     * nobody is testing - but a person sitting next to the phone gets to say otherwise.</p>
     */
    static void assumeTheLampMayBeLit() {
        String lamp = InstrumentationRegistry.getArguments().getString("lamp");
        Assume.assumeFalse("a dark run was asked for with -Plamp=off", "off".equals(lamp));
    }

    /** Skips unless there is a camera to test and a screen to show it on. */
    static void assumeATestableCamera() {
        Context context = ApplicationProvider.getApplicationContext();
        Assume.assumeTrue("no camera on this device",
                context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY));

        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        Assume.assumeFalse("this device is locked with a PIN, pattern or password, so no window"
                        + " can become visible and no camera can open - unlock it and run again",
                keyguard != null && keyguard.isDeviceSecure() && keyguard.isKeyguardLocked());
    }

    /**
     * Whether this is an emulator rather than a phone.
     *
     * <p>Only worth asking where an emulator is known to behave differently from every device,
     * and the difference is the emulator's rather than the library's.</p>
     */
    static boolean isEmulator() {
        return Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.FINGERPRINT.contains("emulator")
                || Build.HARDWARE.equals("goldfish")
                || Build.HARDWARE.equals("ranchu")
                || Build.PRODUCT.contains("sdk");
    }

    /** What to tell someone whose camera never opened, which is usually about the screen. */
    static String whyTheCameraMightNotOpen() {
        Context context = ApplicationProvider.getApplicationContext();
        PowerManager power = context.getSystemService(PowerManager.class);
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return "(screen interactive: " + (power != null && power.isInteractive())
                + ", keyguard locked: " + (keyguard != null && keyguard.isKeyguardLocked()) + ")";
    }
}
