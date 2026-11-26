package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.hardware.Camera;
import android.media.MediaActionSound;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowCamera;
import org.robolectric.shadows.ShadowMediaActionSound;

/** The camera's sounds play when wanted, and always where the platform says they must. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class CameraSoundsTest {
    private final CameraSounds sounds = new CameraSounds();

    @Before
    public void setUp() {
        resetSounds();
    }

    @After
    public void tearDown() {
        sounds.release();
        ShadowCamera.clearCameraInfo();
    }

    @Test
    public void aWantedSoundPlays() {
        sounds.play(MediaActionSound.SHUTTER_CLICK, true);

        assertEquals(1, ShadowMediaActionSound.getPlayCount(MediaActionSound.SHUTTER_CLICK));
    }

    @Test
    public void anUnwantedSoundStaysSilent() {
        sounds.play(MediaActionSound.SHUTTER_CLICK, false);

        assertEquals(0, ShadowMediaActionSound.getPlayCount(MediaActionSound.SHUTTER_CLICK));
    }

    @Test
    public void aRequiredSoundPlaysEvenWhenUnwanted() {
        requireSounds();

        sounds.play(MediaActionSound.START_VIDEO_RECORDING, false);

        assertEquals(1,
                ShadowMediaActionSound.getPlayCount(MediaActionSound.START_VIDEO_RECORDING));
    }

    @Test
    public void soundsAreRequiredOnlyWhereThePlatformSaysSo() {
        assertFalse(CameraSounds.isRequired());

        requireSounds();

        assertTrue(CameraSounds.isRequired());
    }

    /** Released with the camera, and loaded again for the next one. */
    @Test
    public void releasedSoundsPlayAgain() {
        sounds.load();
        sounds.release();

        sounds.play(MediaActionSound.STOP_VIDEO_RECORDING, true);

        assertEquals(1,
                ShadowMediaActionSound.getPlayCount(MediaActionSound.STOP_VIDEO_RECORDING));
    }

    /**
     * Clears the sounds played and whether they are required, which Robolectric's own reset
     * leaves as the last test set it.
     */
    public static void resetSounds() {
        ShadowMediaActionSound.reset();
        ShadowMediaActionSound.setMustPlayShutterSound(false);
    }

    /**
     * Marks this device as one that must play camera sounds, both where API 33 says so and
     * where the old camera API does for earlier releases.
     */
    public static void requireSounds() {
        ShadowMediaActionSound.setMustPlayShutterSound(true);
        Camera.CameraInfo info = new Camera.CameraInfo();
        info.canDisableShutterSound = false;
        ShadowCamera.addCameraInfo(0, info);
    }
}
