package com.xlythe.fragment.camera;

import android.Manifest;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Vibrator;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.LinearInterpolator;
import android.widget.CompoundButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.PermissionChecker;
import com.xlythe.view.camera.R;

import java.util.Arrays;
import java.util.Formatter;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

public abstract class CameraFragment extends Fragment implements CameraView.OnImageCapturedListener, CameraView.OnVideoCapturedListener, CameraView.OnCameraStateChangedListener {
    private static final String[] REQUIRED_PERMISSIONS;
    private static final String[] OPTIONAL_PERMISSIONS;

    static {
        if (Build.VERSION.SDK_INT >= 34) {
            // In U+, READ_MEDIA_IMAGES and READ_MEDIA_VIDEO no longer give access to the file system and the user must manually select files from a system window.
            // As this experience is terrible, support for reading previews of images taken is removed. An image that was taken immediately can still be previewed.
            REQUIRED_PERMISSIONS = new String[] {
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
            };
            OPTIONAL_PERMISSIONS = new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.VIBRATE,
            };
        } else if (Build.VERSION.SDK_INT >= 33) {
            // In T+, READ_EXTERNAL_STORAGE is removed and replaced with READ_MEDIA_IMAGES and READ_MEDIA_VIDEO
            REQUIRED_PERMISSIONS = new String[] {
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
            };
            OPTIONAL_PERMISSIONS = new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.VIBRATE,
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
            };
        } else if (Build.VERSION.SDK_INT >= 19) {
            // In KitKat+, WRITE_EXTERNAL_STORAGE is optional
            REQUIRED_PERMISSIONS = new String[] {
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
            };
            OPTIONAL_PERMISSIONS = new String[] {
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.VIBRATE
            };
        } else {
            REQUIRED_PERMISSIONS = new String[] {
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            };
            OPTIONAL_PERMISSIONS = new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.VIBRATE
            };
        }
    }

    /** Package-private so a test can deliver a result under the same code the prompt asks with. */
    static final int REQUEST_CODE_PERMISSIONS = 10;

    private View mCameraHolder;

    private View mPermissionPrompt;

    private CameraView mCamera;

    private View mPermissionRequest;

    @Nullable
    private View mCapture;

    @Nullable
    private View mConfirm;

    @Nullable
    private TextView mDuration;

    @Nullable
    private ProgressBar mProgress;

    @Nullable
    private CompoundButton mToggle;

    @Nullable
    private View mCancel;

    private final ProgressBarAnimator mAnimator = new ProgressBarAnimator();

    /** Held so invalidate() can set the toggle's state without the listener reading it as a tap. */
    private final CompoundButton.OnCheckedChangeListener mToggleListener = (button, checked) -> {
        mCamera.toggleCamera();
        onToggle();
    };

    private DisplayManager.DisplayListener mDisplayListener;

    private final BroadcastReceiver mStateChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            invalidate();
        }
    };

    @SuppressWarnings({"MissingPermission"})
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (PermissionChecker.hasPermissions(getContext(), REQUIRED_PERMISSIONS)) {
                showCamera();
            } else {
                showPermissionPrompt();
            }
        }
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        if (Build.VERSION.SDK_INT >= 17) {
            DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            mDisplayListener = new DisplayManager.DisplayListener() {
                int lastKnownWidth = -1;
                int lastKnownHeight = -1;
                int lastKnownRotation = -1;

                @Override
                public void onDisplayAdded(int displayId) {}

                @Override
                public void onDisplayRemoved(int displayId) {}

                @SuppressLint("WrongConstant")
                @SuppressWarnings({"MissingPermission"})
                @Override
                public void onDisplayChanged(int displayId) {
                    if (getDisplayId() != displayId) {
                        return;
                    }

                    Display display = displayManager.getDisplay(displayId);
                    if (lastKnownWidth == display.getWidth()
                            && lastKnownHeight == display.getHeight()
                            && lastKnownRotation == display.getRotation()) {
                        return;
                    }

                    lastKnownWidth = display.getWidth();
                    lastKnownHeight = display.getHeight();
                    lastKnownRotation = display.getRotation();

                    if (PermissionChecker.hasPermissions(getContext(), REQUIRED_PERMISSIONS)) {
                        if (mCamera.isOpen()) {
                            mCamera.close();
                            mCamera.open();
                        }
                    }
                }
            };
            displayManager.registerDisplayListener(mDisplayListener, new Handler());
        }

        ContextCompat.registerReceiver(context, mStateChangedReceiver, new IntentFilter(CameraView.ACTION_CAMERA_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private int getDisplayId() {
        if (Build.VERSION.SDK_INT < 17) {
            return -1;
        }

        View view = getView();
        if (view == null) {
            return -1;
        }

        return view.getDisplay().getDisplayId();
    }

    @Override
    public void onDetach() {
        requireContext().unregisterReceiver(mStateChangedReceiver);
        // Must match the version gate onAttach registers under, or the listener outlives us.
        if (Build.VERSION.SDK_INT >= 17) {
            DisplayManager displayManager = (DisplayManager) requireContext().getSystemService(Context.DISPLAY_SERVICE);
            displayManager.unregisterDisplayListener(mDisplayListener);
        }
        super.onDetach();
    }

    @SuppressWarnings({"MissingPermission"})
    @Override
    public void onStart() {
        super.onStart();
        if (PermissionChecker.hasPermissions(getContext(), REQUIRED_PERMISSIONS)) {
            showCamera();
        } else {
            showPermissionPrompt();
        }
    }

    @Override
    public void onStop() {
        // CameraView dispatches onCameraClosed() itself, and only when it really was open.
        if (mCamera != null) {
            mCamera.close();
        }
        super.onStop();
    }

    public void setEnabled(boolean enabled) {
        if (mCapture != null) {
            mCapture.setEnabled(enabled);
        } else {
            mCamera.setEnabled(enabled);
        }
    }

    public boolean isEnabled() {
        if (mCapture != null) {
            return mCapture.isEnabled();
        } else {
            return mCamera.isEnabled();
        }
    }

    public void setQuality(CameraView.Quality quality) {
        mCamera.setQuality(quality);
    }

    public CameraView.Quality getQuality() {
        return mCamera.getQuality();
    }

    public void setMaxVideoDuration(long duration) {
        mCamera.setMaxVideoDuration(duration);
    }

    public long getMaxVideoDuration() {
        return mCamera.getMaxVideoDuration();
    }

    public void setMaxVideoSize(long size) {
        mCamera.setMaxVideoSize(size);
    }

    public long getMaxVideoSize() {
        return mCamera.getMaxVideoSize();
    }

    protected void onTakePicture() {}

    protected void onRecordStart() {}

    protected void onRecordStop() {}

    @Override
    public void onCameraOpened() {}

    @Override
    public void onCameraClosed() {}

    @RequiresPermission(Manifest.permission.CAMERA)
    private void showCamera() {
        // A permission result can land after onDestroyView, once the Views are gone.
        if (mCamera == null) {
            return;
        }
        mCameraHolder.setVisibility(View.VISIBLE);
        mPermissionPrompt.setVisibility(View.GONE);
        mCamera.open();
    }

    private void showPermissionPrompt() {
        if (mCameraHolder == null) {
            return;
        }
        mCameraHolder.setVisibility(View.GONE);
        mPermissionPrompt.setVisibility(View.VISIBLE);
    }

    private static String stringForTime(long timeMs) {
        long totalSeconds = Math.max(0, timeMs) / 1000;

        long seconds = totalSeconds % 60;
        long minutes = (totalSeconds / 60) % 60;
        long hours = totalSeconds / 3600;

        try (Formatter formatter = new Formatter(Locale.getDefault())) {
            if (hours > 0) {
                return formatter.format("%d:%02d:%02d", hours, minutes, seconds).toString();
            } else {
                return formatter.format("%02d:%02d", minutes, seconds).toString();
            }
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mCameraHolder = view.findViewById(R.id.layout_camera);
        mPermissionPrompt = view.findViewById(R.id.layout_permissions);
        mPermissionRequest = view.findViewById(R.id.request_permissions);
        mCamera = view.findViewById(R.id.camera);

        // Check the required Views before anything reads through them, so a layout that is
        // missing one reports which id it needs instead of throwing from findViewById.
        if (mCameraHolder == null) {
            throw new IllegalStateException("No View found with id R.id.layout_camera");
        }

        if (mCamera == null) {
            throw new IllegalStateException("No CameraView found with id R.id.camera");
        }

        if (mPermissionPrompt == null) {
            throw new IllegalStateException("No View found with id R.id.layout_permissions");
        }

        if (mPermissionRequest == null) {
            throw new IllegalStateException("No View found with id R.id.request_permissions");
        }

        mToggle = view.findViewById(R.id.toggle);
        mCapture = mCameraHolder.findViewById(R.id.capture);
        mProgress = view.findViewById(R.id.progress);
        mDuration = view.findViewById(R.id.duration);
        mCancel = view.findViewById(R.id.cancel);
        mConfirm = view.findViewById(R.id.confirm);

        invalidate();
    }

    private void invalidate() {
        // The state receiver is registered from onAttach and unregistered in onDetach, so it can
        // fire before onViewCreated has resolved the Views and after onDestroyView has released
        // them. Both windows used to dereference a null CameraView.
        if (mCamera == null) {
            return;
        }

        mCamera.setOnImageCapturedListener(this);
        mCamera.setOnVideoCapturedListener(this);
        mCamera.setOnCameraStateChangedListener(this);

        if (mCapture != null) {
            mCapture.setOnTouchListener(new OnTouchListener(getContext()));
        }

        if (mConfirm != null) {
            mConfirm.setOnClickListener(v -> mCamera.confirmPicture());
            mCamera.setImageConfirmationEnabled(true);
            mCamera.setVideoConfirmationEnabled(true);
        } else {
            mCamera.setImageConfirmationEnabled(false);
            mCamera.setVideoConfirmationEnabled(false);
        }

        mPermissionRequest.setOnClickListener(v -> requestPermissions(concat(REQUIRED_PERMISSIONS, OPTIONAL_PERMISSIONS), REQUEST_CODE_PERMISSIONS));

        if (mToggle != null) {
            mToggle.setVisibility(mCamera.hasFrontFacingCamera() ? View.VISIBLE : View.GONE);
            // Detach the listener first: invalidate() also runs after the camera reports itself
            // ready, by which point the listener is attached and setChecked would read as a tap
            // and toggle the lens the user never asked to change.
            mToggle.setOnCheckedChangeListener(null);
            mToggle.setChecked(mCamera.isUsingFrontFacingCamera());
            mToggle.setOnCheckedChangeListener(mToggleListener);
        }

        if (mProgress != null) {
            mProgress.setMax(10000);
        }

        if (mDuration != null) {
            mDuration.setVisibility(View.GONE);
        }

        if (mCancel != null) {
            mCancel.setVisibility(View.GONE);
        }

        if (mConfirm != null) {
            mConfirm.setVisibility(View.GONE);
        }
    }

    @Override
    public void onDestroyView() {
        // Drop the Views with the hierarchy that owned them, so anything still arriving, such as
        // the camera state broadcast, does not reach a destroyed CameraView.
        mCameraHolder = null;
        mPermissionPrompt = null;
        mPermissionRequest = null;
        mCamera = null;
        mToggle = null;
        mCapture = null;
        mProgress = null;
        mDuration = null;
        mCancel = null;
        mConfirm = null;
        super.onDestroyView();
    }

    @Override
    public void onViewStateRestored(@Nullable Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);

        // Set listeners here, or else restoring state will trigger them.
        if (mToggle != null) {
            mToggle.setOnCheckedChangeListener(mToggleListener);
        }
    }

    protected void onToggle() {

    }

    @Override
    public void onFailure() {}

    /**
     * Puts the capture button back and takes the confirmation buttons away.
     *
     * <p>The same thing happens after confirming a picture and after confirming a video, and it
     * has to happen whether or not the thing being confirmed was still there - so it is one
     * method rather than two copies and a third for the case that goes wrong.</p>
     */
    private void restoreCaptureButtons() {
        if (mConfirm != null) {
            mConfirm.setVisibility(View.GONE);
        }
        if (mCapture != null) {
            mCapture.setVisibility(View.VISIBLE);
        }
        if (mCancel != null) {
            mCancel.setVisibility(View.GONE);
        }
        if (mToggle != null) {
            mToggle.setVisibility(mCamera.hasFrontFacingCamera() ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onImageConfirmation() {
        View.OnClickListener listener = view -> {
            // The buttons outlive what they are confirming: a camera that closes, or state being
            // restored, clears the pending picture while they are still on screen, and both of
            // these throw when there is nothing pending. From a click handler that is a crash,
            // so the buttons are put back either way and the picture is only acted on if it is
            // still there.
            if (mCamera.isImageConfirmationPending()) {
                if (view == mCancel) {
                    mCamera.rejectPicture();
                } else {
                    mCamera.confirmPicture();
                }
            }

            restoreCaptureButtons();
        };
        if (mCancel != null) {
            mCancel.setVisibility(View.VISIBLE);
            mCancel.setOnClickListener(listener);
        }
        if (mToggle != null) {
            mToggle.setVisibility(View.GONE);
        }
        if (mCapture != null) {
            mCapture.setVisibility(View.GONE);
        }
        if (mConfirm != null) {
            mConfirm.setVisibility(View.VISIBLE);
            mConfirm.setOnClickListener(listener);
        }
    }

    @Override
    public void onVideoConfirmation() {
        View.OnClickListener listener = v -> {
            if (!mCamera.isVideoConfirmationPending()) {
                // As above: the buttons outlive what they are confirming.
                restoreCaptureButtons();
                return;
            }
            if (v == mCancel) {
                mCamera.rejectVideo();
            } else {
                mCamera.confirmVideo();
            }

            restoreCaptureButtons();
        };
        if (mCancel != null) {
            mCancel.setVisibility(View.VISIBLE);
            mCancel.setOnClickListener(listener);
        }
        if (mToggle != null) {
            mToggle.setVisibility(View.GONE);
        }
        if (mCapture != null) {
            mCapture.setVisibility(View.GONE);
        }
        if (mConfirm != null) {
            mConfirm.setVisibility(View.VISIBLE);
            mConfirm.setOnClickListener(listener);
        }
    }

    private class ProgressBarAnimator extends ValueAnimator {
        private ProgressBarAnimator() {
            setInterpolator(new LinearInterpolator());
            setFloatValues(0f, 1f);
            addUpdateListener(animation -> {
                float percent = (float) animation.getAnimatedValue();
                onUpdate(percent);
            });
        }

        void onUpdate(float percent) {
            if (mProgress != null) {
                mProgress.setProgress((int) (percent * 10000));
            }
            if (mDuration != null) {
                // The animator runs for the whole maximum duration, so the fraction elapsed is
                // what turns into a wall-clock time. Scaling by the progress bar's own range
                // instead counted a fixed ten seconds for every recording.
                mDuration.setText(stringForTime((long) (percent * mCamera.getMaxVideoDuration())));
            }
        }
    }

    private class OnTouchListener implements View.OnTouchListener {
        private final int TAP = 1;
        private final int HOLD = 2;
        private final int RELEASE = 3;

        private final long LONG_PRESS = ViewConfiguration.getLongPressTimeout();

        private final Context mContext;

        private long mDownEventTimestamp;
        private Rect mViewBoundsRect;
        @SuppressLint("HandlerLeak")
        private final Handler mHandler = new Handler() {
            // Reached only from the capture button, which showCamera gates on the fragment's
            // REQUIRED_PERMISSIONS, so the microphone has been granted by the time a hold starts.
            @SuppressWarnings("MissingPermission")
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case TAP:
                        onTap();
                        break;
                    case HOLD:
                        onHold();
                        if (mCamera.getMaxVideoDuration() > 0) {
                            sendEmptyMessageDelayed(RELEASE, mCamera.getMaxVideoDuration());
                        }
                        break;
                    case RELEASE:
                        onRelease();
                        break;
                }
            }
        };

        OnTouchListener(Context context) {
            mContext = context;
        }

        Context getContext() {
            return mContext;
        }

        void onTap() {
            mCamera.takePicture();
            onTakePicture();
        }

        // CameraFragment records on long press, so the microphone really is required here, and
        // it stays in REQUIRED_PERMISSIONS for that reason.
        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        void onHold() {
            vibrate();
            mCamera.startRecording();
            if (mCamera.isRecording()) {
                boolean hasDurationLimit = mCamera.getMaxVideoDuration() > 0;
                if (mDuration != null) {
                    // Without a limit there is no animator to drive the label, so showing it
                    // would only leave the previous recording's time on screen.
                    mDuration.setVisibility(hasDurationLimit ? View.VISIBLE : View.GONE);
                    mDuration.setText(stringForTime(0));
                }
                if (hasDurationLimit) {
                    mAnimator.setDuration(mCamera.getMaxVideoDuration()).start();
                }
                onRecordStart();
            }
        }

        void onRelease() {
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

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    mDownEventTimestamp = System.currentTimeMillis();
                    mViewBoundsRect = new Rect(v.getLeft(), v.getTop(), v.getRight(), v.getBottom());
                    mHandler.sendEmptyMessageDelayed(HOLD, LONG_PRESS);
                    v.setPressed(true);
                    break;
                case MotionEvent.ACTION_MOVE:
                    // If the user moves their finger off the button, trigger RELEASE
                    if (mViewBoundsRect.contains(v.getLeft() + (int) event.getX(), v.getTop() + (int) event.getY())) {
                        break;
                    }
                    // Fall-through
                case MotionEvent.ACTION_CANCEL:
                    clearHandler();
                    if (delta() > LONG_PRESS && (mCamera.getMaxVideoDuration() <= 0 || delta() < mCamera.getMaxVideoDuration())) {
                        mHandler.sendEmptyMessage(RELEASE);
                    }
                    v.setPressed(false);
                    break;
                case MotionEvent.ACTION_UP:
                    clearHandler();
                    if (delta() < LONG_PRESS) {
                        mHandler.sendEmptyMessage(TAP);
                    } else if ((mCamera.getMaxVideoDuration() <= 0 || delta() < mCamera.getMaxVideoDuration())) {
                        mHandler.sendEmptyMessage(RELEASE);
                    }
                    v.setPressed(false);
                    break;
            }
            return true;
        }

        private long delta() {
            return System.currentTimeMillis() - mDownEventTimestamp;
        }

        @SuppressWarnings({"MissingPermission"})
        private void vibrate() {
            Vibrator vibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (PermissionChecker.hasPermissions(getContext(), Manifest.permission.VIBRATE) && vibrator.hasVibrator()) {
                vibrator.vibrate(25);
            }
        }

        private void clearHandler() {
            mHandler.removeMessages(TAP);
            mHandler.removeMessages(HOLD);
            mHandler.removeMessages(RELEASE);
        }
    }

    private static <T> T[] concat(T[] first, T[] second) {
        T[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
