package com.xlythe.sample.camera;

import android.Manifest;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.xlythe.fragment.camera.CameraFragment;
import com.xlythe.view.camera.CameraView;

import java.io.File;

/**
 * Sweeps a panorama or a photosphere, and shows what was saved.
 *
 * <p>CameraView draws the guide over its own preview, so this screen is only the controls: which
 * kind of sweep, a button to start and finish it, and the picture once it is written.</p>
 */
public class PanoramaFragment extends CameraFragment implements CameraView.PanoramaListener {
    /** The widest the saved picture is decoded for showing, which is plenty for a phone screen. */
    private static final int PREVIEW_WIDTH = 2048;

    /** The camera, which draws the sweep's guide itself. */
    private CameraView mCameraView;

    /** Chooses between a panorama and a photosphere. */
    private MaterialButtonToggleGroup mModes;

    /** Starts a sweep, and finishes it early. */
    private MaterialButton mStart;

    /** Abandons a sweep. */
    private MaterialButton mCancel;

    /** What to do, and how far along the sweep is. */
    private TextView mStatus;

    /** Covers the camera with the saved picture. */
    private View mResult;

    /** The saved picture. */
    private ImageView mResultImage;

    /** The saved picture's size and where it went. */
    private TextView mResultText;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_panorama, container, false);
        mCameraView = view.findViewById(R.id.camera);
        mModes = view.findViewById(R.id.panorama_modes);
        mStart = view.findViewById(R.id.panorama_start);
        mCancel = view.findViewById(R.id.panorama_cancel);
        mStatus = view.findViewById(R.id.panorama_status);
        mResult = view.findViewById(R.id.panorama_result);
        mResultImage = view.findViewById(R.id.panorama_image);
        mResultText = view.findViewById(R.id.panorama_result_text);

        mModes.addOnButtonCheckedListener((group, id, checked) -> showHint());
        mStart.setOnClickListener(ignored -> {
            if (mCameraView.isCapturingPanorama()) {
                mCameraView.stopPanorama();
            } else {
                startSweep();
            }
        });
        mCancel.setOnClickListener(ignored -> {
            mCameraView.cancelPanorama();
            showSweeping(false);
            showHint();
        });
        view.findViewById(R.id.panorama_back).setOnClickListener(ignored ->
                mResult.setVisibility(View.GONE));
        showHint();
        return view;
    }

    @Override
    public void onCameraOpened() {
        super.onCameraOpened();
        mStart.setEnabled(true);
    }

    @Override
    public void onCameraClosed() {
        super.onCameraClosed();
        mStart.setEnabled(false);
    }

    private void startSweep() {
        // The button only shows once CameraFragment has the permission, but the camera is a
        // permission the user can revoke while the app is in the background.
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        File file = new File(requireContext().getCacheDir(),
                "panorama_" + System.currentTimeMillis() + ".jpg");
        CameraView.PanoramaMode mode = mModes.getCheckedButtonId() == R.id.panorama_mode_sphere
                ? CameraView.PanoramaMode.PHOTOSPHERE : CameraView.PanoramaMode.PANORAMA;
        if (!mCameraView.startPanorama(file, mode, this)) {
            mStatus.setText(R.string.panorama_unsupported);
            return;
        }
        showSweeping(true);
        mStatus.setText(getString(R.string.panorama_progress, 0));
    }

    @Override
    public void onPanoramaProgress(float progress) {
        mStatus.setText(getString(R.string.panorama_progress, Math.round(progress * 100)));
    }

    @Override
    public void onPanoramaCaptured(@NonNull File file) {
        showSweeping(false);
        showHint();
        showResult(file);
    }

    @Override
    public void onPanoramaFailed() {
        showSweeping(false);
        mStatus.setText(R.string.panorama_failed);
    }

    private void showHint() {
        mStatus.setText(mModes.getCheckedButtonId() == R.id.panorama_mode_sphere
                ? R.string.panorama_hint_sphere : R.string.panorama_hint_flat);
    }

    private void showSweeping(boolean sweeping) {
        mStart.setText(sweeping ? R.string.btn_done : R.string.btn_start);
        mCancel.setVisibility(sweeping ? View.VISIBLE : View.GONE);
        for (int i = 0; i < mModes.getChildCount(); i++) {
            mModes.getChildAt(i).setEnabled(!sweeping);
        }
        if (getActivity() != null) {
            // A sweep turns the phone every way, and the screen rotating with it would close the
            // camera halfway round.
            getActivity().setRequestedOrientation(sweeping
                    ? ActivityInfo.SCREEN_ORIENTATION_LOCKED
                    : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
    }

    private void showResult(@NonNull File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / options.inSampleSize > PREVIEW_WIDTH) {
            options.inSampleSize *= 2;
        }
        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
        if (bitmap == null) {
            mStatus.setText(R.string.panorama_failed);
            return;
        }
        mResultImage.setImageBitmap(bitmap);
        mResultText.setText(getString(R.string.panorama_result, bounds.outWidth, bounds.outHeight));
        mResult.setVisibility(View.VISIBLE);
    }
}
