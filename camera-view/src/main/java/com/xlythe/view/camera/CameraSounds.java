package com.xlythe.view.camera;

import android.hardware.Camera;
import android.media.MediaActionSound;
import android.os.Build;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * The platform's shutter click and recording tones, played where a caller wants them and always
 * where the law requires them.
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

    /** Loads the sounds ahead of the first capture, so its click is not late. */
    synchronized void load() {
        if (mSound != null) {
            return;
        }
        mSound = new MediaActionSound();
        mSound.load(MediaActionSound.SHUTTER_CLICK);
        mSound.load(MediaActionSound.START_VIDEO_RECORDING);
        mSound.load(MediaActionSound.STOP_VIDEO_RECORDING);
    }

    /**
     * Plays one of {@link MediaActionSound}'s sounds.
     *
     * @param wanted whether the caller asked for sounds; ignored where they are required
     */
    synchronized void play(@Sound int sound, boolean wanted) {
        if (!wanted && !isRequired()) {
            return;
        }
        load();
        mSound.play(sound);
    }

    synchronized void release() {
        if (mSound != null) {
            mSound.release();
            mSound = null;
        }
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
