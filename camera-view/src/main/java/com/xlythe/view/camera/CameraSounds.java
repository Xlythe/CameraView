package com.xlythe.view.camera;

import android.content.Context;
import android.hardware.Camera;
import android.media.AudioAttributes;
import android.media.MediaActionSound;
import android.media.SoundPool;
import android.os.Build;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RawRes;
import androidx.annotation.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * The platform's shutter click and recording tones, played where a caller wants them and always
 * where the law requires them. The click can be swapped for a sound of the app's own.
 *
 * <p>Only the old camera API has the platform play these itself. Camera2 and CameraX leave them
 * to the app, which in Japan and Korea is obliged to play them.</p>
 */
@AnyThread
final class CameraSounds {
    /** The sounds a camera plays, of the four {@link MediaActionSound} has. */
    @IntDef({
            MediaActionSound.SHUTTER_CLICK,
            MediaActionSound.START_VIDEO_RECORDING,
            MediaActionSound.STOP_VIDEO_RECORDING})
    @Retention(RetentionPolicy.SOURCE)
    @interface Sound {}

    /** Created on first use and released with the camera, since it holds a sound pool. */
    @Nullable
    private MediaActionSound mSound;

    /** The app's own shutter sound, or 0 for the platform's click. */
    @RawRes
    private int mShutterSoundRes;

    /** Holds the app's shutter sound. Null while there is none, or until it is loaded. */
    @Nullable
    private SoundPool mShutterSoundPool;

    /** The app's shutter sound within {@link #mShutterSoundPool}, or 0 until it has loaded. */
    private int mShutterSoundId;

    /** Loads the sounds ahead of the first capture, so its click is not late. */
    synchronized void load(@NonNull Context context) {
        if (mSound == null) {
            mSound = new MediaActionSound();
            mSound.load(MediaActionSound.SHUTTER_CLICK);
            mSound.load(MediaActionSound.START_VIDEO_RECORDING);
            mSound.load(MediaActionSound.STOP_VIDEO_RECORDING);
        }
        if (mShutterSoundRes != 0 && mShutterSoundPool == null) {
            loadShutterSound(context.getApplicationContext(), mShutterSoundRes);
        }
    }

    /**
     * Loads the app's shutter sound into a pool with the same audio attributes as the platform's
     * click, so it is just as audible where a shutter sound is required.
     */
    private void loadShutterSound(@NonNull Context context, @RawRes int soundRes) {
        SoundPool pool = new SoundPool.Builder()
                .setMaxStreams(1)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                        .build())
                .build();
        pool.setOnLoadCompleteListener((loaded, sampleId, status) -> onShutterSoundLoaded(
                loaded, sampleId, status));
        mShutterSoundPool = pool;
        mShutterSoundId = 0;
        if (pool.load(context, soundRes, 1 /* priority */) == 0) {
            Log.w(TAG, "Could not load the shutter sound; the platform's click plays instead");
        }
    }

    private synchronized void onShutterSoundLoaded(SoundPool pool, int sampleId, int status) {
        if (pool != mShutterSoundPool) {
            return;
        }
        if (status == 0) {
            mShutterSoundId = sampleId;
        } else {
            Log.w(TAG, "Could not load the shutter sound; the platform's click plays instead");
        }
    }

    /**
     * Plays one of {@link MediaActionSound}'s sounds, or the app's own in place of the click.
     *
     * @param wanted whether the caller asked for sounds; ignored where they are required
     */
    synchronized void play(@NonNull Context context, @Sound int sound, boolean wanted) {
        if (!wanted && !isRequired()) {
            return;
        }
        load(context);
        if (sound == MediaActionSound.SHUTTER_CLICK && mShutterSoundId != 0) {
            mShutterSoundPool.play(mShutterSoundId, 1f, 1f, 1 /* priority */, 0 /* loop */, 1f);
            return;
        }
        // Includes a shutter sound that has not finished loading: a click late in arriving is
        // better than a picture taken in silence.
        mSound.play(sound);
    }

    /** Sets the app's own shutter sound, or 0 for the platform's click. */
    synchronized void setShutterSound(@RawRes int soundRes) {
        if (soundRes == mShutterSoundRes) {
            return;
        }
        mShutterSoundRes = soundRes;
        releaseShutterSound();
    }

    @RawRes
    synchronized int getShutterSound() {
        return mShutterSoundRes;
    }

    synchronized void release() {
        if (mSound != null) {
            mSound.release();
            mSound = null;
        }
        releaseShutterSound();
    }

    private void releaseShutterSound() {
        if (mShutterSoundPool != null) {
            mShutterSoundPool.release();
            mShutterSoundPool = null;
        }
        mShutterSoundId = 0;
    }

    @VisibleForTesting
    @Nullable
    synchronized SoundPool getShutterSoundPool() {
        return mShutterSoundPool;
    }

    /**
     * Whether this device must play camera sounds, which some countries require.
     *
     * <p>Before API 33 the old camera API is the only place the platform says so. If it cannot
     * be asked, the sound plays: silencing a required one is the worse mistake.</p>
     */
    @SuppressWarnings("deprecation")
    static boolean isRequired() {
        if (Build.VERSION.SDK_INT >= 33) {
            return MediaActionSound.mustPlayShutterSound();
        }
        try {
            Camera.CameraInfo info = new Camera.CameraInfo();
            for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                Camera.getCameraInfo(i, info);
                if (!info.canDisableShutterSound) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not ask whether camera sounds may be silenced", e);
            return true;
        }
    }
}
