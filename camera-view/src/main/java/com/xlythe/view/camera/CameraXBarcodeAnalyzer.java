package com.xlythe.view.camera;

import android.media.Image;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.common.InputImage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** CameraX ImageAnalysis adapter for CameraView's ML Kit barcode callback. */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class CameraXBarcodeAnalyzer implements CameraXFrameAnalyzer {
    /** Log tag shared with CameraView. */
    private static final String TAG = CameraView.class.getSimpleName();
    /** ML Kit scanner processing CameraX image proxies. */
    private final com.google.mlkit.vision.barcode.BarcodeScanner mScanner;
    /** CameraView callback receiving mapped barcode values. */
    private final CameraView.BarcodeDetectorListener mListener;
    /** Executor serializing listener and image completion callbacks. */
    private final Executor mCallbackExecutor;
    /** Ensures no callbacks are delivered after the analyzer closes. */
    private final AtomicBoolean mClosed = new AtomicBoolean();

    public CameraXBarcodeAnalyzer(
            @NonNull CameraView.BarcodeDetectorListener listener,
            @NonNull Executor callbackExecutor,
            @Barcode.Format int format,
            @Barcode.Format int... formats) {
        mListener = listener;
        mCallbackExecutor = callbackExecutor;
        mScanner = BarcodeScanning.getClient(new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(format, formats)
                .build());
    }

    @ExperimentalGetImage
    @Override public void analyze(@NonNull ImageProxy imageProxy) {
        if (mClosed.get()) {
            imageProxy.close();
            return;
        }
        Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            imageProxy.close();
            return;
        }
        InputImage image = InputImage.fromMediaImage(
                mediaImage, imageProxy.getImageInfo().getRotationDegrees());
        try {
            mScanner.process(image)
                    .addOnSuccessListener(mCallbackExecutor, barcodes -> {
                        if (mClosed.get()) return;
                        List<Barcode> mapped = new ArrayList<>(barcodes.size());
                        for (com.google.mlkit.vision.barcode.common.Barcode barcode : barcodes) {
                            mapped.add(new Barcode(barcode));
                        }
                        mListener.onBarcodeFound(mapped);
                    })
                    .addOnFailureListener(mCallbackExecutor,
                            error -> Log.w(TAG, "CameraX barcode analysis failed", error))
                    .addOnCompleteListener(mCallbackExecutor, ignored -> imageProxy.close());
        } catch (RuntimeException error) {
            imageProxy.close();
            Log.w(TAG, "CameraX barcode analysis could not start", error);
        }
    }

    @Override public void close() {
        if (mClosed.compareAndSet(false, true)) mScanner.close();
    }
}