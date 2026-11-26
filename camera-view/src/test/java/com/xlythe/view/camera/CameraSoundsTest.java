package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.hardware.Camera;
import android.media.MediaActionSound;
import android.media.SoundPool;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowCamera;
import org.robolectric.shadows.ShadowMediaActionSound;
import org.robolectric.shadows.ShadowSoundPool;

/** The camera's sounds play when wanted, and always where the platform says they must. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class CameraSoundsTest {
    /** Stands in for an app's raw resource; the sound pool is shadowed and never opens it. */
    private static final int CUSTOM_SOUND = 0x7f120001;

    private final Context context = RuntimeEnvironment.getApplication();
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
        sounds.play(context, MediaActionSound.SHUTTER_CLICK, true);

        assertEquals(1, ShadowMediaActionSound.getPlayCount(MediaActionSound.SHUTTER_CLICK));
    }

    @Test
    public void anUnwantedSoundStaysSilent() {
        sounds.play(context, MediaActionSound.SHUTTER_CLICK, false);

        assertEquals(0, ShadowMediaActionSound.getPlayCount(MediaActionSound.SHUTTER_CLICK));
    }

    @Test
    public void aRequiredSoundPlaysEvenWhenUnwanted() {
        requireSounds();

        sounds.play(context, MediaActionSound.START_VIDEO_RECORDING, false);

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
        sounds.load(context);
        sounds.release();

        sounds.play(context, MediaActionSound.STOP_VIDEO_RECORDING, true);

        assertEquals(1,
                ShadowMediaActionSound.getPlayCount(MediaActionSound.STOP_VIDEO_RECORDING));
    }

    @Test
    public void anAppsShutterSoundPlaysInPlaceOfTheClick() {
        sounds.setShutterSound(CUSTOM_SOUND);
        loadCustomSound();

        sounds.play(context, MediaActionSound.SHUTTER_CLICK, true);

        assertTrue(shadowPool().wasResourcePlayed(CUSTOM_SOUND));
        assertEquals(0, ShadowMediaActionSound.getPlayCount(MediaActionSound.SHUTTER_CLICK));
    }

    /** A picture taken before the sound has loaded still clicks, rather than going silent. */
    @Test
    public void theClickStandsInUntilTheSoundLoads() {
        sounds.setShutterSound(CUSTOM_SOUND);
        sounds.load(context);

        sounds.play(context, MediaActionSound.SHUTTER_CLICK, true);

        assertFalse(shadowPool().wasResourcePlayed(CUSTOM_SOUND));
        assertEquals(1, ShadowMediaActionSound.getPlayCount(MediaActionSound.SHUTTER_CLICK));
    }

    @Test
    public void recordingsKeepThePlatformsTones() {
        sounds.setShutterSound(CUSTOM_SOUND);
        loadCustomSound();

        sounds.play(context, MediaActionSound.START_VIDEO_RECORDING, true);

        assertFalse(shadowPool().wasResourcePlayed(CUSTOM_SOUND));
        assertEquals(1,
                ShadowMediaActionSound.getPlayCount(MediaActionSound.START_VIDEO_RECORDING));
    }

    /** Where a sound is required the app's own satisfies it, so it plays even when unwanted. */
    @Test
    public void aRequiredShutterPlaysTheAppsSound() {
        requireSounds();
        sounds.setShutterSound(CUSTOM_SOUND);
        loadCustomSound();

        sounds.play(context, MediaActionSound.SHUTTER_CLICK, false);

        assertTrue(shadowPool().wasResourcePlayed(CUSTOM_SOUND));
    }

    @Test
    public void clearingTheSoundBringsBackTheClick() {
        sounds.setShutterSound(CUSTOM_SOUND);
        loadCustomSound();

        sounds.setShutterSound(0);
        sounds.play(context, MediaActionSound.SHUTTER_CLICK, true);

        assertEquals(1, ShadowMediaActionSound.getPlayCount(MediaActionSound.SHUTTER_CLICK));
        assertEquals(null, sounds.getShutterSoundPool());
    }

    /** Loads the app's sound and has the pool report it ready, as the platform would later. */
    private void loadCustomSound() {
        sounds.load(context);
        shadowPool().notifyResourceLoaded(CUSTOM_SOUND, true);
    }

    private ShadowSoundPool shadowPool() {
        SoundPool pool = sounds.getShutterSoundPool();
        assertTrue("the app's sound should have a pool", pool != null);
        return Shadows.shadowOf(pool);
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
