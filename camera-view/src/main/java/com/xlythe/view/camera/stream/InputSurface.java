package com.xlythe.view.camera.stream;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;

/**
 * Holds state associated with a Surface used for MediaCodec encoder input.
 * <p>
 * The constructor takes a Surface obtained from MediaCodec.createInputSurface(), and uses that
 * to create an EGL window surface.  Calls to eglSwapBuffers() cause a frame of data to be sent
 * to the video encoder.
 */
@RequiresApi(21)
@RestrictTo(RestrictTo.Scope.LIBRARY)
class InputSurface {
  private static final String TAG = "InputSurface";
  private static final int EGL_RECORDABLE_ANDROID = 0x3142;
  private static final int EGL_OPENGL_ES2_BIT = 4;

  private static final int TEST_Y = 120;                  // YUV values for colored rect
  private static final int TEST_U = 160;
  private static final int TEST_V = 200;
  private static final int TEST_R0 = 0;                   // RGB equivalent of {0,0,0}
  private static final int TEST_G0 = 136;
  private static final int TEST_B0 = 0;
  private static final int TEST_R1 = 236;                 // RGB equivalent of {120,160,200}
  private static final int TEST_G1 = 50;
  private static final int TEST_B1 = 186;

  private EGLDisplay mEGLDisplay;
  private EGLContext mEGLContext;
  private EGLSurface mEGLSurface;
  private Surface mSurface;

  private int mWidth = 0;
  private int mHeight = 0;
  private int mFrameRate = 0;

  private int mGenerateIndex = 0;

  /**
   * Creates an InputSurface from a Surface.
   */
  public InputSurface(Surface surface) {
    if (surface == null) {
      throw new NullPointerException();
    }
    mSurface = surface;
    eglSetup();
  }

  /**
   * Prepares EGL.  We want a GLES 2.0 context and a surface that supports recording.
   */
  private void eglSetup() {
    mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
    if (mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
      throw new RuntimeException("unable to get EGL14 display");
    }
    int[] version = new int[2];
    if (!EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1)) {
      mEGLDisplay = null;
      throw new RuntimeException("unable to initialize EGL14");
    }
    // Configure EGL for pbuffer and OpenGL ES 2.0.  We want enough RGB bits
    // to be able to tell if the frame is reasonable.
    int[] attribList = {
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
    };
    EGLConfig[] configs = new EGLConfig[1];
    int[] numConfigs = new int[1];
    if (!EGL14.eglChooseConfig(mEGLDisplay, attribList, 0, configs, 0, configs.length,
            numConfigs, 0)) {
      throw new RuntimeException("unable to find RGB888+recordable ES2 EGL config");
    }
    // Configure context for OpenGL ES 2.0.
    int[] attrib_list = {
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
    };
    mEGLContext = EGL14.eglCreateContext(mEGLDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
            attrib_list, 0);
    checkEglError("eglCreateContext");
    if (mEGLContext == null) {
      throw new RuntimeException("null context");
    }
    // Create a window surface, and attach it to the Surface we received.
    int[] surfaceAttribs = {
            EGL14.EGL_NONE
    };
    mEGLSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, configs[0], mSurface,
            surfaceAttribs, 0);
    checkEglError("eglCreateWindowSurface");
    if (mEGLSurface == null) {
      throw new RuntimeException("surface was null");
    }
  }

  public void setWidth(int width) {
    mWidth = width;
  }

  public int getWidth() {
    return mWidth;
  }

  public void setHeight(int height) {
    mHeight = height;
  }

  public int getHeight() {
    return mHeight;
  }

  public void setFrameRate(int frameRate) {
    mFrameRate = frameRate;
  }

  public int getFrameRate() {
    return mFrameRate;
  }

  public void draw() {
    makeCurrent();
    generateSurfaceFrame(mGenerateIndex);
    setPresentationTime(computePresentationTime(mGenerateIndex) * 1000);
    swapBuffers();
    mGenerateIndex++;
  }

  /**
   * Discard all resources held by this class, notably the EGL context.  Also releases the
   * Surface that was passed to our constructor.
   */
  public void release() {
    if (EGL14.eglGetCurrentContext().equals(mEGLContext)) {
      // Clear the current context and surface to ensure they are discarded immediately.
      EGL14.eglMakeCurrent(mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
              EGL14.EGL_NO_CONTEXT);
    }
    EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface);
    EGL14.eglDestroyContext(mEGLDisplay, mEGLContext);
    //EGL14.eglTerminate(mEGLDisplay);
    mSurface.release();
    // null everything out so future attempts to use this object will cause an NPE
    mEGLDisplay = null;
    mEGLContext = null;
    mEGLSurface = null;
    mSurface = null;
  }

  /**
   * Makes our EGL context and surface current.
   */
  public void makeCurrent() {
    if (!EGL14.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext)) {
      throw new RuntimeException("eglMakeCurrent failed");
    }
  }

  /**
   * Calls eglSwapBuffers.  Use this to "publish" the current frame.
   */
  public boolean swapBuffers() {
    return EGL14.eglSwapBuffers(mEGLDisplay, mEGLSurface);
  }

  /**
   * Returns the Surface that the MediaCodec receives buffers from.
   */
  public Surface getSurface() {
    return mSurface;
  }

  /**
   * Sends the presentation time stamp to EGL.  Time is expressed in nanoseconds.
   */
  public void setPresentationTime(long nsecs) {
    EGLExt.eglPresentationTimeANDROID(mEGLDisplay, mEGLSurface, nsecs);
  }

  /**
   * Checks for EGL errors.
   */
  private void checkEglError(String msg) {
    boolean failed = false;
    int error;
    while ((error = EGL14.eglGetError()) != EGL14.EGL_SUCCESS) {
      Log.e(TAG, msg + ": EGL error: 0x" + Integer.toHexString(error));
      failed = true;
    }
    if (failed) {
      throw new RuntimeException("EGL error encountered (see log)");
    }
  }

  /** Generates a frame of data using GL commands. */
  private void generateSurfaceFrame(int frameIndex) {
    frameIndex %= 8;
    int startX, startY;
    if (frameIndex < 4) {
      // (0,0) is bottom-left in GL
      startX = frameIndex * (getWidth() / 4);
      startY = getHeight() / 2;
    } else {
      startX = (7 - frameIndex) * (getWidth() / 4);
      startY = 0;
    }
    GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
    GLES20.glClearColor(TEST_R0 / 255.0f, TEST_G0 / 255.0f, TEST_B0 / 255.0f, 1.0f);
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
    GLES20.glScissor(startX, startY, getWidth() / 4, getHeight() / 2);
    GLES20.glClearColor(TEST_R1 / 255.0f, TEST_G1 / 255.0f, TEST_B1 / 255.0f, 1.0f);
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
  }

  /** Generates the presentation time for frame N, in microseconds. */
  private long computePresentationTime(int frameIndex) {
    return 132 + frameIndex * 1000000L / getFrameRate();
  }
}
