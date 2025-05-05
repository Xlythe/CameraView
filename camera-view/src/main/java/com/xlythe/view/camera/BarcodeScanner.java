package com.xlythe.view.camera;

import android.Manifest;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
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

                SettableFuture<Surface> providedSurface = SettableFuture.create();
                VideoRecorder.SurfaceProvider surfaceProvider = (width, height, orientation, flipped) -> {
                  mCameraOrientation = orientation;
                  try {
                    return providedSurface.get();
                  } catch (ExecutionException | InterruptedException e) {
                    throw new RuntimeException(e);
                  }
                };
                mCanvas.attachSurface(surfaceProvider);

                Surface surface = createSurface();
                try {
                  providedSurface.set(surface);

                  while (isAlive()) {
                    Canvas canvas = surface.lockCanvas(null);

                    surface.unlockCanvasAndPost(canvas);
                    Bitmap bitmap = Bitmap.createBitmap(canvas.getWidth(), canvas.getHeight(), Bitmap.Config.ARGB_8888);
                    Canvas bitmapCanvas = new Canvas(bitmap);

                    //processFrame(canvas.);
                  }

                } finally {
                  stopInternal();
                  mCanvas.detachSurface(surfaceProvider);
                  surface.release();
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
    try {
      mThread.join(300);
    } catch (InterruptedException e) {
      Log.e(TAG, "Interrupted while joining BarcodeScanner thread", e);
      Thread.currentThread().interrupt();
    }
  }

  private Surface createSurface() {
    int textureId = createTexture();
    SurfaceTexture surfaceTexture = createSurfaceTexture(textureId);
    return new Surface(surfaceTexture) {
      @Override
      public void release() {
        super.release();
        surfaceTexture.release();
        GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
      }
    };
  }

  private SurfaceTexture createSurfaceTexture(int textureId) {
    SurfaceTexture surfaceTexture = new SurfaceTexture(textureId);
    surfaceTexture.setDefaultBufferSize(100, 100); // TODO
    return surfaceTexture;
  }

  private int createTexture() {
    int[] textures = new int[1];
    GLES20.glGenTextures(1, textures, 0);
    int textureId = textures[0];

    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);

    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

    return textureId;
  }

  private void processFrame(Bitmap bitmap) {
    InputImage image = InputImage.fromBitmap(bitmap, mCameraOrientation);
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
}
