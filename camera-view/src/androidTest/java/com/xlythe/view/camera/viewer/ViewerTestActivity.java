package com.xlythe.view.camera.viewer;

import android.app.Activity;
import android.app.KeyguardManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

/** Hosts a viewer for the device tests, full screen, on a screen that is on. */
public final class ViewerTestActivity extends Activity {
    /** Where the test puts the viewer. */
    private FrameLayout mRoot;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager keyguard = getSystemService(KeyguardManager.class);
            if (keyguard != null) {
                keyguard.requestDismissKeyguard(this, null);
            }
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        mRoot = new FrameLayout(this);
        mRoot.setBackgroundColor(0xFF000000);
        setContentView(mRoot);
    }

    FrameLayout getRoot() {
        return mRoot;
    }
}
