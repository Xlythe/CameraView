package com.xlythe.fragment.camera;

import android.Manifest;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Vibrator;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.LinearInterpolator;
import android.widget.CompoundButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.xlythe.view.camera.CameraView;

import java.io.File;
import java.util.Formatter;
import java.util.Locale;

import com.xlythe.view.camera.R;

public abstract class CameraFragment extends Fragment implements CameraView.OnImageCapturedListener, CameraView.OnVideoCapturedListener {
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };
    private static final int REQUEST_CODE_REQUIRED_PERMISSIONS = 3;
    private static final String PHOTO_DESTINATION = "photo.jpg";
    private static final String VIDEO_DESTINATION = "video.mp4";

    private View mCameraHolder;

    private View mPermissionPrompt;

    private CameraView mCamera;

    private View mPermissionRequest;

    private View mCapture;

    @Nullable
    private TextView mDuration;

    @Nullable
    private ProgressBar mProgress;

    @Nullable
    private CompoundButton mToggle;

    private ProgressBarAnimator mAnimator = new ProgressBarAnimator();

    private DisplayManager.DisplayListener mDisplayListener;

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CODE_REQUIRED_PERMISSIONS) {
            if (hasPermissions(getContext(), REQUIRED_PERMISSIONS)) {
                showCamera();
            } else {
                showPermissionPrompt();
            }
        }
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        if (Build.VERSION.SDK_INT > 17) {
            mDisplayListener = new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {}

                @Override
                public void onDisplayRemoved(int displayId) {}

                @Override
                public void onDisplayChanged(int displayId) {
                    if (hasPermissions(getContext(), REQUIRED_PERMISSIONS)) {
                        if (mCamera.isOpen()) {
                            mCamera.close();
                            mCamera.open();
                        }
                    }
                }
            };
            DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            displayManager.registerDisplayListener(mDisplayListener, new Handler());
        }
    }

    @Override
    public void onDetach() {
        if (Build.VERSION.SDK_INT > 17) {
            DisplayManager displayManager = (DisplayManager) getContext().getSystemService(Context.DISPLAY_SERVICE);
            displayManager.unregisterDisplayListener(mDisplayListener);
        }
        super.onDetach();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (hasPermissions(getContext(), REQUIRED_PERMISSIONS)) {
            showCamera();
        } else {
            showPermissionPrompt();
        }
    }

    @Override
    public void onStop() {
        mCamera.close();
        super.onStop();
    }

    public void setEnabled(boolean enabled) {
        mCapture.setEnabled(enabled);
    }

    public boolean isEnabled() {
        return mCapture.isEnabled();
    }

    public void setQuality(CameraView.Quality quality) {
        mCamera.setQuality(quality);
    }

    public CameraView.Quality getQuality() {
        return mCamera.getQuality();
    }

    public void setMaxVideoDuration(int duration) {
        mCamera.setMaxVideoDuration(duration);
    }

    public int getMaxVideoDuration() {
        return mCamera.getMaxVideoDuration();
    }

    public void setMaxVideoSize(int size) {
        mCamera.setMaxVideoSize(size);
    }

    public int getMaxVideoSize() {
        return mCamera.getMaxVideoSize();
    }

    protected void onTakePicture() {}

    protected void onRecordStart() {}

    protected void onRecordStop() {}

    private void showCamera() {
        mCameraHolder.setVisibility(View.VISIBLE);
        mPermissionPrompt.setVisibility(View.GONE);
        mCamera.open();
    }

    private void showPermissionPrompt() {
        mCameraHolder.setVisibility(View.GONE);
        mPermissionPrompt.setVisibility(View.VISIBLE);
    }

    private String stringForTime(int timeMs) {
        int totalSeconds = timeMs / 1000;

        int seconds = totalSeconds % 60;
        int minutes = (totalSeconds / 60) % 60;
        int hours = totalSeconds / 3600;

        Formatter formatter = new Formatter(Locale.getDefault());

        if (hours > 0) {
            return formatter.format("%d:%02d:%02d", hours, minutes, seconds).toString();
        } else {
            return formatter.format("%02d:%02d", minutes, seconds).toString();
        }
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        mCameraHolder = view.findViewById(R.id.layout_camera);
        mPermissionPrompt = view.findViewById(R.id.layout_permissions);
        mPermissionRequest = view.findViewById(R.id.request_permissions);
        mCamera = (CameraView) view.findViewById(R.id.camera);
        mToggle = (CompoundButton) view.findViewById(R.id.toggle);
        mCapture = mCameraHolder.findViewById(R.id.capture);
        mProgress = (ProgressBar) view.findViewById(R.id.progress);
        mDuration = (TextView) view.findViewById(R.id.duration);

        if (mCameraHolder == null) {
            throw new IllegalStateException("No View found with id R.id.layout_camera");
        }

        if (mCamera == null) {
            throw new IllegalStateException("No CameraView found with id R.id.camera");
        }

        if (mCapture == null) {
            throw new IllegalStateException("No CameraView found with id R.id.capture");
        }

        if (mPermissionPrompt == null) {
            throw new IllegalStateException("No View found with id R.id.layout_permissions");
        }

        if (mPermissionRequest == null) {
            throw new IllegalStateException("No View found with id R.id.request_permissions");
        }

        mCamera.setOnImageCapturedListener(this);
        mCamera.setOnVideoCapturedListener(this);

        mCapture.setOnTouchListener(new OnTouchListener(getContext()));

        mPermissionRequest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_REQUIRED_PERMISSIONS);
            }
        });

        if (mToggle != null) {
            mToggle.setVisibility(mCamera.hasFrontFacingCamera() ? View.VISIBLE : View.GONE);
            mToggle.setChecked(mCamera.isUsingFrontFacingCamera());
            mToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    mCamera.toggleCamera();
                }
            });
        }

        if (mProgress != null) {
            mProgress.setMax(10000);
        }

        if (mDuration != null) {
            mDuration.setVisibility(View.GONE);
        }
    }

    /**
     * Returns true if all given permissions are available
     */
    public static boolean hasPermissions(Context context, String... permissions) {
        boolean ok = true;
        for (String permission : permissions) {
            ok = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
            if (!ok) break;
        }
        return ok;
    }

    private class ProgressBarAnimator extends ValueAnimator {
        private ProgressBarAnimator() {
            setInterpolator(new LinearInterpolator());
            setFloatValues(0f, 1f);
            addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    float percent = (float) animation.getAnimatedValue();
                    onUpdate(percent);
                }
            });
        }

        void onUpdate(float percent) {
            if (mProgress != null) {
                mProgress.setProgress((int) (percent * 10000));
            }
            if (mDuration != null) {
                mDuration.setText(stringForTime((int) (percent * 10000)));
            }
        }
    }

    protected class OnTouchListener implements View.OnTouchListener {
        private final int TAP = 1;
        private final int HOLD = 2;
        private final int RELEASE = 3;

        private final long LONG_PRESS = ViewConfiguration.getLongPressTimeout();

        private final Context mContext;

        private long start;
        private final Handler mHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case TAP:
                        onTap();
                        break;
                    case HOLD:
                        onHold();
                        sendEmptyMessageDelayed(RELEASE, mCamera.getMaxVideoDuration());
                        break;
                    case RELEASE:
                        onRelease();
                        break;
                }
            }
        };

        protected OnTouchListener(Context context) {
            mContext = context;
        }

        protected Context getContext() {
            return mContext;
        }

        protected void onTap() {
            mCamera.takePicture(new File(getContext().getCacheDir(), PHOTO_DESTINATION));
            onTakePicture();
        }

        protected void onHold() {
            vibrate();
            mCamera.startRecording(new File(getContext().getCacheDir(), VIDEO_DESTINATION));
            if (mDuration != null) {
                mDuration.setVisibility(View.VISIBLE);
            }
            mAnimator.setDuration(mCamera.getMaxVideoDuration()).start();
            onRecordStart();
        }

        protected void onRelease() {
            mCamera.stopRecording();
            mAnimator.cancel();
            if (mProgress != null) {
                mProgress.setProgress(0);
            }
            if (mDuration != null) {
                mDuration.setVisibility(View.GONE);
            }
            onRecordStop();
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    start = System.currentTimeMillis();
                    mHandler.sendEmptyMessageDelayed(HOLD, LONG_PRESS);
                    v.setPressed(true);
                    break;
                case MotionEvent.ACTION_CANCEL:
                    clearHandler();
                    if (delta() > LONG_PRESS && delta() < mCamera.getMaxVideoDuration()) {
                        mHandler.sendEmptyMessage(RELEASE);
                    }
                    v.setPressed(false);
                    break;
                case MotionEvent.ACTION_UP:
                    clearHandler();
                    if (delta() < LONG_PRESS) {
                        mHandler.sendEmptyMessage(TAP);
                    } else if (delta() < mCamera.getMaxVideoDuration()) {
                        mHandler.sendEmptyMessage(RELEASE);
                    }
                    v.setPressed(false);
                    break;
            }
            return true;
        }

        private long delta() {
            return System.currentTimeMillis() - start;
        }

        private void vibrate() {
            Vibrator vibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator.hasVibrator() && hasPermissions(getContext(), Manifest.permission.VIBRATE)) {
                vibrator.vibrate(25);
            }
        }

        private void clearHandler() {
            mHandler.removeMessages(TAP);
            mHandler.removeMessages(HOLD);
            mHandler.removeMessages(RELEASE);
        }
    }
}
