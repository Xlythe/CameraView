package com.xlythe.view.camera.test;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.annotation.NonNull;

import org.junit.Assume;
import org.junit.AssumptionViolatedException;
import org.junit.rules.TestRule;
import org.junit.runners.model.Statement;
import com.xlythe.view.camera.CameraView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * What the device has to be for a camera test to mean anything.
 *
 * <p>A camera needs a visible window to put its preview in, so the state of the screen decides
 * whether these tests are testing the library or the lock screen. {@link CameraTestActivity}
 * turns the screen on and asks the keyguard to stand aside, which covers everything except a
 * device with a real PIN - and that one is worth saying out loud, because the alternative is six
 * tests timing out thirty seconds at a time and blaming the backend.</p>
 */
public final class DeviceState {
    /** The fake camera HAL in emulator images older than API 29. */
    private static final String CAMERA_HAL_TAG = "EmulatedCamera_FakeCamera3";
    /** What that HAL logs when it has stopped completing capture requests. */
    private static final String CAMERA_HAL_STALL =
            "waitForReadout: Timed out waiting for in-flight queue to shrink";
    /** Room in the log for two tests' worth of camera logging, which is what is searched. */
    private static final String LOG_BUFFER_SIZE = "16M";

    /** When the test before the running one started, in epoch milliseconds, or 0 before any. */
    private static long sPreviousTestStartedAt;
    /** Whether the log buffer has been enlarged yet in this process. */
    private static boolean sLogBufferEnlarged;

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
    public static boolean isEmulator() {
        return Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.FINGERPRINT.contains("emulator")
                || Build.HARDWARE.equals("goldfish")
                || Build.HARDWARE.equals("ranchu")
                || Build.PRODUCT.contains("sdk");
    }

    /**
     * Whether the camera can fill YUV_420_888 buffers without taking the device down.
     *
     * <p>Not on an emulator older than API 29: its graphics allocator logs that it is taking an
     * experimental path for the format, and the emulator process exits or hangs, so every test
     * after it is lost to "device offline". The library asks for those buffers to analyze frames
     * and scan barcodes, and the old camera API asks for them itself to record or stream. An app
     * cannot crash the emulator, and the same tests pass on the API 37 emulator, so there is
     * nothing here for the library to fix.</p>
     */
    public static boolean canFillYuvBuffers() {
        return !(isEmulator() && Build.VERSION.SDK_INT < 29);
    }

    /** Skips a test that needs {@link #canFillYuvBuffers}. */
    public static void assumeYuvBuffersWork() {
        Assume.assumeTrue("this emulator dies filling YUV_420_888 buffers", canFillYuvBuffers());
    }

    /**
     * Whether a backend can record or stream here.
     *
     * <p>The old camera API records and streams from YUV_420_888 buffers it has the camera fill,
     * so on an emulator that cannot fill them a recording takes the emulator down: the graphics
     * allocator logs its experimental path and adb loses the device, along with every test still
     * to run. The other two backends hand the camera an encoder's surface and record here.</p>
     */
    public static boolean canRecord(@NonNull CameraView.Backend backend) {
        return backend != CameraView.Backend.LEGACY || canFillYuvBuffers();
    }

    /** Skips a test that records or streams, when {@link #canRecord} says the backend cannot. */
    public static void assumeTheBackendCanRecord(@NonNull CameraView.Backend backend) {
        Assume.assumeTrue("this emulator dies when the old camera API fills YUV_420_888 buffers"
                + " to record or stream", canRecord(backend));
    }

    /**
     * Skips a test that failed because the emulator's camera HAL stalled.
     *
     * <p>The fake camera HAL in emulator images older than API 29 stops completing capture requests
     * at random on a slow host: it logs {@value #CAMERA_HAL_STALL}, fails the camera device, and
     * cameraserver then takes seconds to close it. Whichever test had the camera fails, and so can
     * the next one, whose open collides with that close. A failing test is skipped only when the
     * HAL logged that stall while it or the test before it ran; every other failure still fails,
     * and on a phone or a newer emulator the rule does nothing.</p>
     */
    @NonNull
    public static TestRule skipWhenTheEmulatorCameraHalStalls() {
        return (base, description) -> new Statement() {
            @Override
            public void evaluate() throws Throwable {
                long startedAt = System.currentTimeMillis();
                long since = sPreviousTestStartedAt == 0 ? startedAt : sPreviousTestStartedAt;
                sPreviousTestStartedAt = startedAt;
                if (!hasTheStallingCameraHal()) {
                    base.evaluate();
                    return;
                }
                enlargeTheLogBuffer();
                try {
                    base.evaluate();
                } catch (AssumptionViolatedException skipped) {
                    throw skipped;
                } catch (Throwable failure) {
                    if (theCameraHalStalledSince(since)) {
                        throw new AssumptionViolatedException("this emulator's camera HAL stopped"
                                + " completing capture requests: " + CAMERA_HAL_STALL, failure);
                    }
                    throw failure;
                }
            }
        };
    }

    /** Whether this is an emulator whose camera HAL is the one that stalls. */
    private static boolean hasTheStallingCameraHal() {
        return isEmulator() && Build.VERSION.SDK_INT < 29;
    }

    private static void enlargeTheLogBuffer() throws IOException {
        if (!sLogBufferEnlarged) {
            shell("logcat -G " + LOG_BUFFER_SIZE);
            sLogBufferEnlarged = true;
        }
    }

    /** Whether the camera HAL logged its stall at or after the given epoch time. */
    private static boolean theCameraHalStalledSince(long sinceMillis) throws IOException {
        for (String line : shell("logcat -d -v epoch -s " + CAMERA_HAL_TAG + ":E").split("\n")) {
            if (!line.contains(CAMERA_HAL_STALL)) {
                continue;
            }
            try {
                // An epoch-format line starts with the seconds since 1970, to the millisecond.
                double seconds = Double.parseDouble(line.trim().split("\\s+", 2)[0]);
                if ((long) (seconds * 1000) >= sinceMillis) {
                    return true;
                }
            } catch (NumberFormatException notAStamp) {
                // A line logcat wrapped, which carries no time of its own.
            }
        }
        return false;
    }

    /** Runs a command as the shell user, which may read and size the log, and returns its output. */
    @NonNull
    private static String shell(@NonNull String command) throws IOException {
        ParcelFileDescriptor output = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(command);
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ParcelFileDescriptor.AutoCloseInputStream(output), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line).append('\n');
            }
        }
        return text.toString();
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
