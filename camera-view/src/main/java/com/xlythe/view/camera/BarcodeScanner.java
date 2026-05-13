package com.xlythe.view.camera;

import android.Manifest;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;

import com.google.android.gms.tasks.Tasks;
import com.google.common.util.concurrent.SettableFuture;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.common.InputImage;
import com.xlythe.view.camera.CameraView.BarcodeDetectorListener;
import com.xlythe.view.camera.stream.VideoRecorder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

import static android.os.Process.THREAD_PRIORITY_DISPLAY;
import static android.os.Process.THREAD_PRIORITY_VIDEO;
import static android.os.Process.setThreadPriority;

/**
 * When created, you must pass a {@link BarcodeDetectorListener}. Once {@link #start()} is called,
 * the listener will be called whenever a {@link Barcode} is discovered until {@link #stop()} is
 * called.
 */
@RequiresApi(18)
class BarcodeScanner {
  private static final String TAG = CameraView.class.getSimpleName();

  /** Draws on our surface. */
  private final VideoRecorder.Canvas mCanvas;

  /** Processes frames. */
  private final com.google.mlkit.vision.barcode.BarcodeScanner mScanner;

  /** Allows us to report events back to the caller. */
  private final BarcodeDetectorListener mListener;

  /** The rotation of the image. */
  private int mCameraOrientation;

  /**
   * If true, the background thread will continue to loop and process frames. Once false, the thread
   * will shut down.
   */
  private volatile boolean mIsAlive;

  /** The background thread processing frames for us. */
  private Thread mThread;

  /** A simple barcode scanner. */
  public BarcodeScanner(VideoRecorder.Canvas canvas, BarcodeDetectorListener listener, @Barcode.Format int format, @Barcode.Format int... formats) {
    this.mCanvas = canvas;
    this.mScanner = BarcodeScanning.getClient(new BarcodeScannerOptions.Builder()
            .setBarcodeFormats(format, formats)
            .build());
    this.mListener = listener;
  }

  /** @return True if actively scanning. False otherwise. */
  public boolean isScanning() {
    return mIsAlive;
  }

  /** Starts scanning. */
  @RequiresPermission(Manifest.permission.CAMERA)
  public void start() {
    if (isScanning()) {
      Log.w(TAG, "BarcodeScanner is already running");
      return;
    }

    mIsAlive = true;
    mThread =
            new Thread() {
              @RequiresPermission(Manifest.permission.CAMERA)
              @Override
              public void run() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                  setThreadPriority(THREAD_PRIORITY_VIDEO);
                } else {
                  setThreadPriority(THREAD_PRIORITY_DISPLAY);
                }

                SettableFuture<CameraMetadata> requestedSizeFuture = SettableFuture.create();
                SettableFuture<Surface> providedSurface = SettableFuture.create();
                VideoRecorder.SurfaceProvider surfaceProvider = (width, height, orientation, flipped) -> {
                  mCameraOrientation = orientation;
                  requestedSizeFuture.set(new CameraMetadata(width, height, orientation, flipped));
                  try {
                    return providedSurface.get();
                  } catch (ExecutionException | InterruptedException e) {
                    throw new RuntimeException(e);
                  }
                };
                mCanvas.attachSurface(surfaceProvider);

                ImageReader imageReader = null;
                try {
                  CameraMetadata metadata = Objects.requireNonNull(requestedSizeFuture.get());
                  imageReader = ImageReader.newInstance(metadata.getWidth(), metadata.getHeight(), ImageFormat.YUV_420_888, 2);
                  providedSurface.set(imageReader.getSurface());

                  while (isAlive()) {
                    Image image = imageReader.acquireLatestImage();
                    if (image != null) {
                      try {
                        processFrame(image);
                      } finally {
                        image.close();
                      }
                    } else {
                      try {
                        Thread.sleep(10);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                      }
                    }
                  }

                } catch (ExecutionException | InterruptedException e) {
                  Log.e(TAG, "Exception with barcode scanner stream", e);
                } finally {
                  stopInternal();
                  if (imageReader != null) {
                    mCanvas.detachSurface(surfaceProvider);
                    imageReader.close();
                  }
                }
              }
            };
    mThread.start();
  }

  private void stopInternal() {
    mIsAlive = false;
  }

  /** Stops scanning. */
  public void stop() {
    stopInternal();
    if (mThread != null) {
      try {
        mThread.join(300);
      } catch (InterruptedException e) {
        Log.e(TAG, "Interrupted while joining BarcodeScanner thread", e);
        Thread.currentThread().interrupt();
      }
    }
  }

  private void processFrame(Image mediaImage) {
    InputImage image = InputImage.fromMediaImage(mediaImage, mCameraOrientation);
    try {
      List<com.google.mlkit.vision.barcode.common.Barcode> barcodes = Tasks.await(mScanner.process(image));
      List<Barcode> list = new ArrayList<>(barcodes.size());
      for (com.google.mlkit.vision.barcode.common.Barcode barcode : barcodes) {
        list.add(new Barcode(barcode));
      }
      mListener.onBarcodeFound(list);
    } catch (ExecutionException | InterruptedException e) {
      Log.w(TAG, "Barcode processing failed", e);
    }
  }

  private static class CameraMetadata {
    final int width;
    final int height;
    final int orientation;
    final boolean flipped;

    CameraMetadata(int width, int height, int orientation, boolean flipped) {
      this.width = width;
      this.height = height;
      this.orientation = orientation;
      this.flipped = flipped;
    }

    int getWidth() {
      return width;
    }

    int getHeight() {
      return height;
    }

    int getOrientation() {
      return orientation;
    }

    boolean isFlipped() {
      return flipped;
    }
  }
}
