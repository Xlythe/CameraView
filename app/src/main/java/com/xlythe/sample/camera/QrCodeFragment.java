package com.xlythe.sample.camera;

import static com.xlythe.sample.camera.MainActivity.TAG;

import android.annotation.SuppressLint;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xlythe.fragment.camera.CameraFragment;
import com.xlythe.view.camera.Barcode;
import com.xlythe.view.camera.CameraView;

public class QrCodeFragment extends CameraFragment {
    private CameraView mCameraView;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_qr_code, container, false);
        mCameraView = view.findViewById(R.id.camera);
        return view;
    }

    @SuppressLint("MissingPermission")
    @Override
    public void onCameraOpened() {
        if (Build.VERSION.SDK_INT >= 19) {
            mCameraView.enterBarcodeScanner(barcodes -> Log.d(TAG, "Found barcode of length " + barcodes.size()), Barcode.Format.QR_CODE);
        }
    }

    @Override
    public void onCameraClosed() {
        super.onCameraClosed();

        if (Build.VERSION.SDK_INT >= 19) {
            mCameraView.exitBarcodeScanner();
        }
    }
}
