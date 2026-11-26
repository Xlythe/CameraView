package com.xlythe.view.camera.test;

import android.app.Activity;
import android.app.KeyguardManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.test.R;

/**
 * Hosts a CameraView for the device tests, on a screen that is actually on.
 *
 * <p>That last part is not incidental. A camera needs somewhere to put its preview, and a window
 * on a sleeping or locked device never becomes visible - so the activity stops short of STARTED,
 * and CameraX, which binds its use cases to the host's lifecycle, correctly declines to open.
 * Camera2 has no such gate and opens anyway, which is what makes the failure so confusing: the
 * same suite on the same device reports one backend working and another broken, and the real
 * answer is that nobody woke the phone up.</p>
 */
public final class CameraTestActivity extends Activity {
    private CameraView cameraView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showOnTopOfEverything();
        setContentView(R.layout.activity_camera_test);
        cameraView = findViewById(R.id.camera);
    }

    /**
     * Turns the screen on, gets past the keyguard, and keeps it that way.
     *
     * <p>Asked for in the activity rather than arranged by adb beforehand, so that a plain {@code
     * connectedAndroidTest} is as reliable as a carefully prepared one.</p>
     */
    private void showOnTopOfEverything() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager keyguard = getSystemService(KeyguardManager.class);
            if (keyguard != null) {
                // Only dismisses a swipe-up keyguard. A device with a real PIN stays locked, and
                // the tests say so rather than timing out one by one.
                keyguard.requestDismissKeyguard(this, null);
            }
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
    }

    @Override
    protected void onStop() {
        cameraView.close();
        super.onStop();
    }

    public CameraView getCameraView() {
        return cameraView;
    }
}
