package com.xlythe.sample.camera;

import static com.xlythe.sample.camera.MainActivity.TAG;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.xlythe.fragment.camera.CameraFragment;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.Exif;
import com.xlythe.view.camera.ZoomPreset;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.navigation.fragment.NavHostFragment;

/**
 * MainFragment demonstrates the standard usage of the CameraView library.
 * By extending CameraFragment, the library automatically manages camera lifecycle,
 * background thread operations, permissions, and UI binding to layout controls.
 */
public class MainFragment extends CameraFragment {
    /** How long the preview takes to come back after blinking for a picture. */
    private static final long SHUTTER_BLINK_MILLIS = 150;

    /** The CameraView the zoom presets drive, held while the view exists. */
    @Nullable
    private CameraView mCameraView;

    /** The row the zoom preset buttons go in, once the camera says what they are. */
    @Nullable
    private ViewGroup mZoomPresets;

    /** The exposure compensation row, shown only where the camera can compensate. */
    @Nullable
    private View mExposureControl;

    /** Picks the exposure compensation index; its progress is the index less the minimum. */
    @Nullable
    private SeekBar mExposureSlider;

    /** Shows what the picked index is worth, in EV. */
    @Nullable
    private TextView mExposureValue;

    /** Takes a DNG beside each JPEG, shown only where the camera can. */
    @Nullable
    private CheckBox mRaw;

    /** Opens the last picture filed in the viewer. */
    @Nullable
    private View mViewLast;

    /** The last picture filed, or null before one is. */
    @Nullable
    private Uri mLastPicture;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // The layout contains a CameraView and control buttons (capture, confirm, cancel).
        // CameraFragment automatically binds to these controls using their resource IDs.
        return inflater.inflate(R.layout.fragment_main, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mCameraView = view.findViewById(R.id.camera);
        mZoomPresets = view.findViewById(R.id.zoom_presets);
        mExposureControl = view.findViewById(R.id.exposure_control);
        mExposureSlider = view.findViewById(R.id.exposure_slider);
        mExposureValue = view.findViewById(R.id.exposure_value);
        mRaw = view.findViewById(R.id.raw);
        mViewLast = view.findViewById(R.id.view_last);
        mRaw.setOnCheckedChangeListener((button, checked) -> {
            CameraView cameraView = mCameraView;
            if (cameraView != null) {
                cameraView.setPictureFormat(checked
                        ? CameraView.PictureFormat.RAW_JPEG : CameraView.PictureFormat.JPEG);
            }
        });
        // The DNG of a RAW capture is filed last, so it is the one the viewer opens.
        mViewLast.setOnClickListener(v -> {
            if (mLastPicture != null) {
                NavHostFragment.findNavController(this).navigate(R.id.viewer_fragment,
                        ViewerFragment.arguments(mLastPicture));
            }
        });
        mViewLast.setEnabled(mLastPicture != null);
        // Heard for a pinch as well as for a tap on a preset, so the highlight follows the zoom
        // however it got there.
        mCameraView.setOnZoomChangedListener(this::highlightZoomPreset);
        mExposureSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                CameraView cameraView = mCameraView;
                if (!fromUser || cameraView == null) {
                    return;
                }
                int index = cameraView.getMinExposureCompensation() + progress;
                cameraView.setExposureCompensation(index);
                showExposureValue(index, cameraView.getExposureCompensationStep());
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    @Override
    public void onDestroyView() {
        mCameraView = null;
        mZoomPresets = null;
        mExposureControl = null;
        mExposureSlider = null;
        mExposureValue = null;
        mRaw = null;
        mViewLast = null;
        super.onDestroyView();
    }

    @Override
    public void onCameraOpened() {
        super.onCameraOpened();
        showZoomPresets();
        showExposureControl();
        showRaw();
    }

    @Override
    protected void onToggle() {
        super.onToggle();
        // The presets belong to the lens, and the other one may have different stops. So may its
        // exposure range and step.
        showZoomPresets();
        showExposureControl();
        showRaw();
    }

    /** Offers RAW where this lens can write it, and turns it off where it cannot. */
    @MainThread
    private void showRaw() {
        CameraView cameraView = mCameraView;
        if (cameraView == null || mRaw == null) {
            return;
        }
        boolean supported = cameraView.isPictureFormatSupported(CameraView.PictureFormat.RAW_JPEG);
        if (!supported) {
            mRaw.setChecked(false);
        }
        mRaw.setVisibility(supported ? View.VISIBLE : View.GONE);
    }

    /**
     * Sizes the exposure slider to the camera's range, or hides it where the camera cannot
     * compensate.
     */
    @MainThread
    private void showExposureControl() {
        CameraView cameraView = mCameraView;
        if (cameraView == null || mExposureControl == null || mExposureSlider == null) {
            return;
        }
        if (!cameraView.isExposureCompensationSupported()) {
            mExposureControl.setVisibility(View.GONE);
            return;
        }
        int min = cameraView.getMinExposureCompensation();
        int max = cameraView.getMaxExposureCompensation();
        int index = Math.max(min, Math.min(cameraView.getExposureCompensation(), max));
        mExposureSlider.setMax(max - min);
        mExposureSlider.setProgress(index - min);
        showExposureValue(index, cameraView.getExposureCompensationStep());
        mExposureControl.setVisibility(View.VISIBLE);
    }

    @MainThread
    private void showExposureValue(int index, float step) {
        if (mExposureValue != null) {
            mExposureValue.setText(formatExposure(requireContext(), index, step));
        }
    }

    /**
     * An exposure compensation index as the shift it makes in EV, such as "+0.7 EV". The index on
     * its own means something different on each camera, so the EV is what a user is shown.
     */
    @AnyThread
    @VisibleForTesting
    static String formatExposure(@NonNull Context context, int index, float step) {
        if (index == 0) {
            return context.getString(R.string.exposure_compensation_none);
        }
        return context.getString(R.string.exposure_compensation, index * step);
    }

    /** Fills the row with a button for each zoom preset the camera suggests. */
    private void showZoomPresets() {
        CameraView cameraView = mCameraView;
        if (cameraView == null || mZoomPresets == null) {
            return;
        }
        mZoomPresets.removeAllViews();
        List<ZoomPreset> presets = cameraView.getZoomPresets();
        if (presets.size() < 2) {
            // A single stop is no choice at all.
            return;
        }
        LayoutInflater inflater = getLayoutInflater();
        for (ZoomPreset preset : presets) {
            TextView button = (TextView) inflater.inflate(
                    R.layout.zoom_preset, mZoomPresets, false);
            button.setText(getString(R.string.zoom_preset, preset.getLabel()));
            button.setTag(preset.getRatio());
            button.setOnClickListener(
                    v -> cameraView.setZoomRatio(preset.getRatio(), true /* animate */));
            mZoomPresets.addView(button);
        }
        highlightZoomPreset(cameraView.getZoomRatio());
    }

    /**
     * Highlights the preset nearest the zoom. Nearest as a ratio, so 0.5x is as far below 1x as 2x
     * is above it.
     */
    private void highlightZoomPreset(float zoomRatio) {
        if (mZoomPresets == null) {
            return;
        }
        View nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (int i = 0; i < mZoomPresets.getChildCount(); i++) {
            View button = mZoomPresets.getChildAt(i);
            double distance = Math.abs(Math.log((Float) button.getTag() / zoomRatio));
            if (distance < nearestDistance) {
                nearest = button;
                nearestDistance = distance;
            }
        }
        for (int i = 0; i < mZoomPresets.getChildCount(); i++) {
            View button = mZoomPresets.getChildAt(i);
            button.setSelected(button == nearest);
        }
    }

    /**
     * Callback triggered as the picture is exposed. The library has already played the shutter
     * click; blinking the preview shows the moment too, as camera apps do.
     */
    @Override
    public void onShutter() {
        View camera = requireView().findViewById(R.id.camera);
        camera.animate().cancel();
        camera.setAlpha(0f);
        camera.animate().alpha(1f).setDuration(SHUTTER_BLINK_MILLIS);
    }

    /**
     * Callback triggered once the library has taken a picture and filed it in the gallery.
     */
    @Override
    public void onImageSaved(@NonNull Uri uri) {
        report("Picture saved to " + uri);
        mLastPicture = uri;
        if (mViewLast != null) {
            mViewLast.setEnabled(true);
        }

        // Print out metadata (EXIF data) about the picture using the library's Exif helper: its
        // title, photographer and copyright, the conditions it was taken in, where and when.
        try (InputStream input = requireContext().getContentResolver().openInputStream(uri)) {
            if (input != null) {
                Log.d(TAG, new Exif(input).toString());
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not read the metadata of " + uri, e);
        }
    }

    /**
     * Callback triggered once the library has recorded a video and filed it in the gallery.
     */
    @Override
    public void onVideoSaved(@NonNull Uri uri) {
        report("Video saved to " + uri);
    }

    /**
     * Callback triggered when video recording starts.
     */
    @Override
    protected void onRecordStart() {
        report("Recording");
    }

    /**
     * Callback triggered if camera initialization or capture fails.
     */
    @Override
    public void onFailure() {
       report("Failure");
    }

    private void report(String msg) {
        Log.d(TAG, msg);
        Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
    }

}
