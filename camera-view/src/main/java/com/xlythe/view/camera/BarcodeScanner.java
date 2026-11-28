package com.xlythe.view.camera;

import android.Manifest;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;
import androidx.annotation.WorkerThread;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.common.InputImage;
import com.xlythe.view.camera.CameraView.BarcodeDetectorListener;
import com.xlythe.view.camera.stream.VideoRecorder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * When created, you must pass a {@link BarcodeDetectorListener}. Once {@link #start()} is called,
 * the listener will be called whenever a {@link Barcode} is discovered until {@link #stop()} is
 * called.
 *
 * <p>This is an ML Kit analyzer on top of {@link FrameStream}, which owns the camera frames; the
 * same stream is public, so any other detector can be run the same way.</p>
 */
@RequiresApi(21)
class BarcodeScanner {
  private static final String TAG = CameraView.class.getSimpleName();

  /** Loaded to learn whether ML Kit is on the classpath, which an app on the core build adds. */
  private static final String ML_KIT_CLASS = "com.google.mlkit.vision.barcode.BarcodeScanning";

  /** How long a scan may take before it is logged as slow. It is waited for regardless. */
  private static final long SLOW_SCAN_MILLIS = 10_000;

  /** The camera's frames. */
  private final FrameStream mFrames;

  /** Processes frames. */
  private final com.google.mlkit.vision.barcode.BarcodeScanner mScanner;

  /** Allows us to report events back to the caller. */
  private final BarcodeDetectorListener mListener;

  /** Ensures the ML Kit scanner is closed exactly once. */
  private final AtomicBoolean mStopped = new AtomicBoolean();

  /** A simple barcode scanner. */
  BarcodeScanner(VideoRecorder.Canvas canvas, @Nullable CameraView cameraView,
          BarcodeDetectorListener listener, @Barcode.Format int format,
          @Barcode.Format int... formats) {
    this.mScanner = BarcodeScanning.getClient(new BarcodeScannerOptions.Builder()
            .setBarcodeFormats(format, formats)
            .build());
    this.mListener = listener;
    this.mFrames = new FrameStream(
            canvas, cameraView, new FrameStream.Params(), this::processFrame);
  }

  /**
   * Whether ML Kit's barcode scanning can be loaded.
   *
   * <p>Checked by name and without initializing the class, so a missing dependency is a false here
   * rather than a NoClassDefFoundError out of the constructor.</p>
   */
  static boolean isAvailable() {
    try {
      Class.forName(ML_KIT_CLASS, false, BarcodeScanner.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException | LinkageError error) {
      return false;
    }
  }

  /** @return True if actively scanning. False otherwise. */
  public boolean isScanning() {
    return mFrames.isAnalyzing();
  }

  /** Starts scanning. */
  @RequiresPermission(Manifest.permission.CAMERA)
  public void start() {
    if (mStopped.get()) {
      Log.w(TAG, "BarcodeScanner cannot be restarted");
      return;
    }
    mFrames.start();
  }

  /** Stops scanning. */
  public void stop() {
    mFrames.close();
    if (mStopped.compareAndSet(false, true)) {
      mScanner.close();
    }
  }

  @WorkerThread
  private void processFrame(FrameStream.Frame frame) {
    if (mStopped.get()) {
      return;
    }
    InputImage image = InputImage.fromMediaImage(
            frame.getImage(), frame.getRotationDegrees());
    List<com.google.mlkit.vision.barcode.common.Barcode> barcodes;
    try {
      barcodes = awaitScan(mScanner.process(image), SLOW_SCAN_MILLIS);
    } catch (ExecutionException | CancellationException e) {
      Log.w(TAG, "Barcode processing failed", e);
      return;
    }
    if (mStopped.get()) {
      return;
    }
    List<Barcode> list = new ArrayList<>(barcodes.size());
    for (com.google.mlkit.vision.barcode.common.Barcode barcode : barcodes) {
      list.add(new Barcode(barcode));
    }
    mListener.onBarcodeFound(list);
  }

  /**
   * Waits for ML Kit to finish with a frame, however long it takes and whatever interrupts the
   * wait.
   *
   * <p>The frame and its reader are closed as soon as the frame's analyzer returns, while ML Kit
   * reads the frame's buffer from native code until its task completes. Returning on an interrupt
   * or a timeout frees that buffer under it, and the read crashes the process. So an interrupt,
   * which is how a stopping scanner asks the frame thread to finish, is held until the task is
   * done and then restored, and a slow scan is logged and waited out.</p>
   *
   * @throws ExecutionException if the scan failed
   * @throws CancellationException if the scan was cancelled
   */
  @WorkerThread
  static <T> T awaitScan(Task<T> scan, long slowMillis) throws ExecutionException {
    boolean interrupted = false;
    try {
      while (true) {
        try {
          return Tasks.await(scan, slowMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          interrupted = true;
        } catch (TimeoutException e) {
          Log.w(TAG, "A barcode scan has taken over " + slowMillis + " ms; still waiting for it");
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
