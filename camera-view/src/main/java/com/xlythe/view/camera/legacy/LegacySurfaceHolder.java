package com.xlythe.view.camera.legacy;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Build;
import android.os.PowerManager;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.collection.ArraySet;

import com.xlythe.view.camera.stream.VideoRecorder;

import java.util.Set;

@RequiresApi(18)
public class LegacySurfaceHolder implements SurfaceHolder {
  private final Context mContext;
  private final VideoRecorder.SurfaceProvider mSurfaceProvider;
  private final Set<Callback> mCallbacks = new ArraySet<>();
  private final int mCameraOrientation;

  @Nullable private Surface mSurface;
  @Nullable private Canvas mCanvas;
  private int mWidth;
  private int mHeight;

  @Nullable private PowerManager.WakeLock mWakeLock;

  LegacySurfaceHolder(
          Context context,
          VideoRecorder.SurfaceProvider surfaceProvider,
          int defaultWidth,
          int defaultHeight,
          int cameraOrientation) {
    this.mContext = context;
    this.mSurfaceProvider = surfaceProvider;
    this.mWidth = defaultWidth;
    this.mHeight = defaultHeight;
    this.mCameraOrientation = cameraOrientation;
  }

  @Override
  public void addCallback(Callback callback) {
    mCallbacks.add(callback);
  }

  @Override
  public void removeCallback(Callback callback) {
    mCallbacks.remove(callback);
  }

  @Override
  public boolean isCreating() {
    return mSurface == null;
  }

  @Override
  public void setType(int type) {
    // ignored
  }

  @Override
  public void setFixedSize(int width, int height) {
    this.mWidth = width;
    this.mHeight = height;
  }

  @Override
  public void setSizeFromLayout() {
    // ignored
  }

  @Override
  public void setFormat(int format) {
    // ignored
  }

  @SuppressLint("WakelockTimeout")
  @Override
  public void setKeepScreenOn(boolean screenOn) {
    // For screen off, just release our wakelock.
    if (!screenOn) {
      if (mWakeLock != null) {
        mWakeLock.release();
        mWakeLock = null;
      }
      return;
    }

    // For screen on, create a wakelock if necessary.
    if (mWakeLock != null) {
      return;
    }

    PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
    mWakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "legacy-camera:");
    mWakeLock.acquire();
  }

  @Override
  public Canvas lockCanvas() {
    if (mCanvas != null) {
      return mCanvas;
    }

    mCanvas = getSurface().lockCanvas(new Rect(0, 0, mWidth, mHeight));
    return mCanvas;
  }

  @RequiresApi(api = Build.VERSION_CODES.M)
  @Override
  public Canvas lockHardwareCanvas() {
    if (mCanvas != null) {
      return mCanvas;
    }

    mCanvas = getSurface().lockHardwareCanvas();
    return mCanvas;
  }

  @Override
  public Canvas lockCanvas(Rect dirty) {
    if (mCanvas != null) {
      return mCanvas;
    }

    mCanvas = getSurface().lockCanvas(dirty);
    return mCanvas;
  }

  @Override
  public void unlockCanvasAndPost(Canvas canvas) {
    getSurface().unlockCanvasAndPost(canvas);
    mCanvas = null;
  }

  @Override
  public Rect getSurfaceFrame() {
    return new Rect(0, 0, mWidth, mHeight);
  }

  @Override
  public Surface getSurface() {
    if (mSurface == null) {
      mSurface = mSurfaceProvider.getSurface(mWidth, mHeight, mCameraOrientation);
      for (Callback callback : mCallbacks) {
        callback.surfaceCreated(this);
      }
    }
    return mSurface;
  }

  public void close() {
    setKeepScreenOn(false);
    if (mSurface != null) {
      mSurface.release();
      mSurface = null;

      for (Callback callback : mCallbacks) {
        callback.surfaceDestroyed(this);
      }
    }
  }
}
