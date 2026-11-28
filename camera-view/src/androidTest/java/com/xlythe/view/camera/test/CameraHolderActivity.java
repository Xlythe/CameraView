package com.xlythe.view.camera.test;

import android.app.Activity;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;

/**
 * Holds the back camera from a process of its own, as another app would, until told to let it
 * go, and then finishes.
 *
 * <p>Its process is the one in front, which the camera service ranks above the test's own behind
 * it, so a camera the test asks for in the meantime is turned away as busy.</p>
 */
public final class CameraHolderActivity extends Activity {
    /** The longest to hold the camera if never told to let it go, in milliseconds. */
    static final String EXTRA_MAX_HOLD_MILLIS = "max_hold_millis";
    /** The {@link ResultReceiver} told when the camera is held and when it is let go. */
    static final String EXTRA_RECEIVER = "receiver";

    /**
     * Sent once the camera is open and held, with {@link #EXTRA_LET_GO} to send anything to when
     * the camera should be let go.
     */
    static final int RESULT_HOLDING = 1;
    /**
     * Sent once the camera has been released, with {@link #EXTRA_LETTING_GO_AT} saying when the
     * release began.
     */
    static final int RESULT_RELEASED = 2;
    /** Sent when the camera would not open here at all. */
    static final int RESULT_FAILED = 3;

    /**
     * In {@link #RESULT_RELEASED}, the {@link SystemClock#elapsedRealtime()} just before the
     * camera was released, which no open by another process can come before.
     */
    static final String EXTRA_LETTING_GO_AT = "letting_go_at";

    /** In {@link #RESULT_HOLDING}, the {@link ResultReceiver} that lets the camera go. */
    static final String EXTRA_LET_GO = "let_go";

    /** Where holding and letting go are logged. */
    private static final String TAG = "CameraHolderActivity";

    /** Lets the camera go, when told to or when the longest hold runs out. */
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** The held camera, or null before it opens and after it is released. */
    @Nullable private Camera camera;
    /** Where to report, or null if the caller did not ask to be told. */
    @Nullable private ResultReceiver receiver;
    /** Whether the camera has been opened once already, so a resume does not take it again. */
    private boolean opened;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        receiver = getIntent().getParcelableExtra(EXTRA_RECEIVER);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (opened) {
            return;
        }
        opened = true;
        try {
            camera = Camera.open(backCamera());
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not take the camera", e);
            report(RESULT_FAILED);
            finish();
            return;
        }
        Log.i(TAG, "Holding the camera");
        Bundle holding = new Bundle();
        holding.putParcelable(EXTRA_LET_GO, new ResultReceiver(handler) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                letGo();
            }
        });
        report(RESULT_HOLDING, holding);
        handler.postDelayed(this::letGo, getIntent().getLongExtra(EXTRA_MAX_HOLD_MILLIS, 10_000));
    }

    private void letGo() {
        handler.removeCallbacksAndMessages(null);
        release();
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        release();
        super.onDestroy();
    }

    private void release() {
        if (camera == null) {
            return;
        }
        Bundle released = new Bundle();
        released.putLong(EXTRA_LETTING_GO_AT, SystemClock.elapsedRealtime());
        camera.release();
        camera = null;
        Log.i(TAG, "Released the camera");
        report(RESULT_RELEASED, released);
    }

    private void report(int result) {
        report(result, null);
    }

    private void report(int result, @Nullable Bundle data) {
        if (receiver != null) {
            receiver.send(result, data);
        }
    }

    /** The first back camera the old API lists, which is the one the view opens. */
    private static int backCamera() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                return i;
            }
        }
        return 0;
    }
}
