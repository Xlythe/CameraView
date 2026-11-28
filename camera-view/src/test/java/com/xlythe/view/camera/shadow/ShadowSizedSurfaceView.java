package com.xlythe.view.camera.shadow;

import static org.robolectric.util.reflector.Reflector.reflector;

import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import androidx.annotation.Nullable;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.annotation.Resetter;
import org.robolectric.shadows.ShadowSurfaceView;
import org.robolectric.util.reflector.Direct;
import org.robolectric.util.reflector.ForType;

/**
 * Gives a {@link SurfaceView} a surface the way a device does: once it has been given a size, on
 * a later pass of the main looper, and taken away again when the test says the window was hidden.
 * Hiding the view destroys its surface and showing it again creates one, both at once, inside the
 * call to setVisibility, as SurfaceView itself does.
 *
 * <p>Robolectric's own shadow hands out a holder whose surface is always null and which never
 * reports one, so nothing that waits for a SurfaceView's surface can be reached.</p>
 */
@Implements(SurfaceView.class)
public class ShadowSizedSurfaceView extends ShadowSurfaceView {
    /** The holder this view hands out, which owns the surface. */
    private final SizedHolder mHolder = new SizedHolder();

    /** The view this shadows. */
    @RealObject
    private SurfaceView mRealView;

    @Implementation
    @Override
    protected SurfaceHolder getHolder() {
        return mHolder;
    }

    @Implementation
    protected void setVisibility(int visibility) {
        reflector(SurfaceViewReflector.class, mRealView).setVisibility(visibility);
        if (visibility == View.VISIBLE) {
            // A view not laid out yet gets its surface on its first traversal instead.
            if (mHolder.mSurface == null && mHolder.mWidth > 0 && mHolder.mHeight > 0
                    && mRealView.getWidth() > 0) {
                mHolder.report();
            }
        } else {
            mHolder.destroy();
        }
    }

    /** Reaches SurfaceView's own setVisibility. */
    @ForType(SurfaceView.class)
    private interface SurfaceViewReflector {
        @Direct
        void setVisibility(int visibility);
    }

    /** Destroys the surface, as hiding the window the view is in does. */
    public void destroySurface() {
        mHolder.destroy();
    }

    /** Creates the surface again at the size last asked for, as showing the window again does. */
    public void recreateSurface() {
        mHolder.report();
    }

    /** Whether surfaces are reported at all; off, a SurfaceView never gets one. */
    private static boolean sReportsSurfaces = true;

    /** Lets surfaces be reported, or not, for a test of a window that never shows. */
    public static void setReportsSurfaces(boolean reports) {
        sReportsSurfaces = reports;
    }

    /** Reports surfaces again, as a device does. Call it before each test. */
    @Resetter
    public static void reset() {
        sReportsSurfaces = true;
    }

    /** The width a surface sized from the layout reports. */
    public static final int LAYOUT_WIDTH = 1080;
    /** The height a surface sized from the layout reports. */
    public static final int LAYOUT_HEIGHT = 1920;

    /** A holder that sizes its surface when asked and reports it a looper pass later. */
    private static final class SizedHolder extends FakeSurfaceHolder {
        /** Reports a requested size on a later pass, as the view's next traversal would. */
        private final Handler mHandler = new Handler(Looper.getMainLooper());
        /** The surface, or null while there is none. */
        @Nullable private Surface mSurface;
        /** The size last asked for with setFixedSize. */
        private int mWidth;
        /** The size last asked for with setFixedSize. */
        private int mHeight;

        /**
         * A view's first listener hears of a surface the size of the layout, as a SurfaceView
         * added to a window gets one on its next traversal without being asked.
         */
        @Override
        public void addCallback(SurfaceHolder.Callback callback) {
            super.addCallback(callback);
            if (mWidth == 0 && mHeight == 0) {
                mWidth = LAYOUT_WIDTH;
                mHeight = LAYOUT_HEIGHT;
                mHandler.post(this::report);
            }
        }

        @Override
        public void setFixedSize(int width, int height) {
            mWidth = width;
            mHeight = height;
            mHandler.post(this::report);
        }

        /** Sizes the surface to the view, which a test sees as {@link #LAYOUT_WIDTH} by height. */
        @Override
        public void setSizeFromLayout() {
            mWidth = LAYOUT_WIDTH;
            mHeight = LAYOUT_HEIGHT;
            mHandler.post(this::report);
        }

        @Override
        public Surface getSurface() {
            return mSurface;
        }

        void report() {
            if (!sReportsSurfaces) {
                return;
            }
            if (mSurface == null) {
                mSurface = new Surface(new SurfaceTexture(0));
                for (SurfaceHolder.Callback callback : getCallbacks()) {
                    callback.surfaceCreated(this);
                }
            }
            for (SurfaceHolder.Callback callback : getCallbacks()) {
                callback.surfaceChanged(this, PixelFormat.RGBA_8888, mWidth, mHeight);
            }
        }

        void destroy() {
            if (mSurface == null) {
                return;
            }
            for (SurfaceHolder.Callback callback : getCallbacks()) {
                callback.surfaceDestroyed(this);
            }
            mSurface.release();
            mSurface = null;
        }
    }
}
