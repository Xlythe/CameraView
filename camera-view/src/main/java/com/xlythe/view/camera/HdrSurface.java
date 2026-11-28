package com.xlythe.view.camera;

import android.graphics.Matrix;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Shows a stream that carries HDR - a camera preview, a video - as HDR.
 *
 * <p>Streams are normally drawn on a TextureView, which the view hierarchy composites into the
 * window like any other view: a 10-bit HLG or PQ stream drawn there is tone-mapped into the
 * window's range, so it shows as SDR. A SurfaceView hands its buffers to the display compositor
 * as they are, 10 bits and dataspace included, which is the path the platform shows HDR video
 * on. So while an HDR stream plays, it is drawn on a SurfaceView beneath the TextureView, and the
 * TextureView is hidden; when it stops, the TextureView comes back.</p>
 *
 * <p>The compositor positions a SurfaceView by the rectangle the view covers, so of the view's
 * transforms only scaling and translation reach it. A mirror, a rotation, or alpha do not, and a
 * stream that needs one of those stays on the TextureView, as SDR. A camera mirrors and turns its
 * own buffers, so a preview needs none of them.</p>
 *
 * <p>The window is held in HDR colour mode, through {@link HdrColorMode}, for as long as the
 * SurfaceView shows a stream: the SurfaceView does not need it, but whatever the window draws
 * beside the stream, such as an Ultra HDR picture, does.</p>
 *
 * <p>A user asks for the surface with {@link #acquire} and holds the {@link Lease} for as long as
 * anything may write into it. Streams can overlap - a camera rebind delivers the new request
 * before it has finished with the old one - so the SurfaceView is only hidden once every lease is
 * released, and only the newest lease positions it.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class HdrSurface {
    /** The first release whose displays say whether they show HDR, and whose windows go HDR. */
    private static final int MIN_SDK = 26;

    /**
     * How long the surface stays after the last stream lets go, for the next one to take it.
     * A camera builds its next session a few frames after closing the last, and switching the
     * window out of HDR and back in between would show.
     */
    private static final long SETTLE_DELAY_MILLIS = 300;

    /** The view the HDR surface stands in for. */
    public interface Host {
        /** The view the stream is drawn on as SDR, or null while there is none. */
        @MainThread
        @Nullable
        View getSdrView();
    }

    /** Hears from the surface on behalf of one stream. */
    public interface Listener {
        /**
         * The surface exists, at the size asked for, and can be written to. Called again with the
         * new surface after {@link #onSurfaceLost()}, once there is one.
         */
        @MainThread
        void onSurfaceReady(@NonNull Surface surface);

        /** The surface was destroyed, as happens when the window is hidden. */
        @MainThread
        void onSurfaceLost();
    }

    /** One stream's claim on the HDR surface. */
    public final class Lease {
        /** Told when the surface is ready, and when it is lost. */
        private final Listener mListener;
        /** The buffer width asked for, or 0 for whatever size the view has. */
        private final int mWidth;
        /** The buffer height asked for, or 0 for whatever size the view has. */
        private final int mHeight;
        /** Whether the listener holds the current surface. */
        private boolean mDelivered;

        private Lease(@NonNull Listener listener, int width, int height) {
            mListener = listener;
            mWidth = width;
            mHeight = height;
        }

        /**
         * Positions the stream in the view.
         *
         * <p>Only a scale and a translation reach the screen: they map the buffer, as the
         * SurfaceView stretches it over its own bounds, onto where it belongs. Only the newest
         * stream's transform is applied; an older one that is still closing no longer decides what
         * is on screen.</p>
         */
        @MainThread
        public void setTransform(@NonNull Matrix transform) {
            if (this != mCurrent || mSurfaceView == null) {
                return;
            }
            float[] values = new float[9];
            transform.getValues(values);
            mSurfaceView.setPivotX(0f);
            mSurfaceView.setPivotY(0f);
            mSurfaceView.setScaleX(values[Matrix.MSCALE_X]);
            mSurfaceView.setScaleY(values[Matrix.MSCALE_Y]);
            mSurfaceView.setTranslationX(values[Matrix.MTRANS_X]);
            mSurfaceView.setTranslationY(values[Matrix.MTRANS_Y]);
        }

        /**
         * Gives the surface up, once nothing writes into it any more or ever will. Safe to call
         * more than once.
         */
        @MainThread
        public void release() {
            if (!mLeases.remove(this)) {
                return;
            }
            if (this == mCurrent) {
                mCurrent = null;
            }
            mHandler.removeCallbacks(mSettle);
            mHandler.postDelayed(mSettle, SETTLE_DELAY_MILLIS);
        }
    }

    /** The view the SurfaceView is added to, beneath its other children. */
    private final ViewGroup mHost;
    /** Says which view the SurfaceView stands in for. */
    private final Host mSdrViews;
    /** Posts {@link #mSettle}. */
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    /** Every lease whose stream may still be writing into the surface. */
    private final Set<Lease> mLeases = new LinkedHashSet<>();
    /** Goes back to the SDR view once no stream holds the surface any more. */
    private final Runnable mSettle = this::settle;
    /** Hands the surface to the newest lease, if it is ready for it. */
    private final Runnable mDeliver = this::deliverIfReady;
    /** Follows the SurfaceView's surface as it is created, resized and destroyed. */
    private final SurfaceHolder.Callback mSurfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(@NonNull SurfaceHolder holder) {}

        @Override
        public void surfaceChanged(
                @NonNull SurfaceHolder holder, int format, int width, int height) {
            mSurfaceWidth = width;
            mSurfaceHeight = height;
            // Posted: showing a hidden SurfaceView creates its surface at once, inside acquire,
            // before the stream asking for it has been handed its lease.
            postDelivery();
        }

        @Override
        public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
            mSurfaceWidth = 0;
            mSurfaceHeight = 0;
            for (Lease lease : mLeases.toArray(new Lease[0])) {
                if (lease.mDelivered && mLeases.contains(lease)) {
                    lease.mDelivered = false;
                    lease.mListener.onSurfaceLost();
                }
            }
        }
    };

    /** The HDR surface, created the first time an HDR stream asks for it. */
    @Nullable
    private SurfaceView mSurfaceView;
    /** The newest stream's lease, or null while the newest stream is SDR or there is none. */
    @Nullable
    private Lease mCurrent;
    /** The buffer width the surface was last fixed at, or 0 while it follows the view. */
    private int mFixedWidth;
    /** The buffer height the surface was last fixed at, or 0 while it follows the view. */
    private int mFixedHeight;
    /** The surface's width as last reported, or 0 while there is no surface. */
    private int mSurfaceWidth;
    /** The surface's height as last reported, or 0 while there is no surface. */
    private int mSurfaceHeight;
    /** The window held in HDR colour mode, or null while none is. */
    @Nullable
    private Window mHdrWindow;
    /** Whether a stream has been handed the surface since the SDR view last showed. */
    private boolean mShowing;

    /**
     * @param host the view the SurfaceView is added to, beneath its other children
     * @param sdrViews says which of the host's views the SurfaceView stands in for
     */
    public HdrSurface(@NonNull ViewGroup host, @NonNull Host sdrViews) {
        mHost = host;
        mSdrViews = sdrViews;
    }

    /**
     * Whether an HDR stream would show as HDR here: a display that shows HDR, in a window that
     * can go HDR.
     *
     * <p>Asked before an HDR stream is sent here at all: on an SDR display it gains nothing, and
     * gives up the view transforms a TextureView keeps.</p>
     */
    @MainThread
    public boolean isAvailable() {
        if (Build.VERSION.SDK_INT < MIN_SDK || mHost.getWindowToken() == null) {
            return false;
        }
        Display display = mHost.getDisplay();
        return display != null && display.isHdr();
    }

    /**
     * Moves the stream onto the HDR surface.
     *
     * <p>The listener hears once the surface is that size, at the soonest on the next pass of the
     * main thread. The SDR view is hidden and the window switched to HDR at that point rather
     * than now, so the last SDR frame stays up until there is somewhere for the new stream to
     * go.</p>
     *
     * @param width the buffer width the writer needs, such as a camera's stream size, or 0 for
     *     a writer that scales to whatever size the surface has, such as a video decoder
     * @param height the buffer height, or 0 alongside a width of 0
     */
    @MainThread
    @NonNull
    public Lease acquire(int width, int height, @NonNull Listener listener) {
        mHandler.removeCallbacks(mSettle);
        Lease lease = new Lease(listener, width, height);
        mLeases.add(lease);
        mCurrent = lease;
        SurfaceView surfaceView = ensureSurfaceView();
        surfaceView.setVisibility(View.VISIBLE);
        if (width != mFixedWidth || height != mFixedHeight) {
            // A camera sizes its stream from the surface, so the surface has to be the stream's
            // size; the view then scales it to the screen. A decoder scales to any size.
            mFixedWidth = width;
            mFixedHeight = height;
            if (width > 0 && height > 0) {
                surfaceView.getHolder().setFixedSize(width, height);
            } else {
                surfaceView.getHolder().setSizeFromLayout();
            }
        }
        // Posted, so that a listener hears about the surface only once it holds its lease.
        postDelivery();
        return lease;
    }

    /**
     * Moves the stream back onto the SDR view, because the newest stream is SDR.
     *
     * <p>The SurfaceView stays until the streams still holding it let go, so nothing is left
     * writing into a destroyed surface.</p>
     */
    @MainThread
    public void showSdr() {
        mCurrent = null;
        showSdrView();
        releaseColorMode();
        if (mLeases.isEmpty()) {
            hideSurfaceView();
        }
    }

    /**
     * Forgets every stream and puts the views and the window back as they were. For a host that
     * has left the window, or a camera that has closed, neither of which is coming back for the
     * streams it had.
     */
    @MainThread
    public void reset() {
        mHandler.removeCallbacks(mSettle);
        mLeases.clear();
        mCurrent = null;
        showSdrView();
        releaseColorMode();
        hideSurfaceView();
    }

    /** Whether the HDR surface is on screen in place of the SDR view. */
    @MainThread
    public boolean isShowing() {
        return mShowing && mSurfaceView != null && mSurfaceView.getVisibility() == View.VISIBLE;
    }

    /** The HDR surface's view, or null before an HDR stream has asked for one. */
    @VisibleForTesting
    @Nullable
    public SurfaceView getSurfaceView() {
        return mSurfaceView;
    }

    /** Hands the surface to the newest lease on the next pass, if it is ready for it then. */
    @MainThread
    private void postDelivery() {
        mHandler.removeCallbacks(mDeliver);
        mHandler.post(mDeliver);
    }

    @MainThread
    private void deliverIfReady() {
        Lease lease = mCurrent;
        SurfaceView surfaceView = mSurfaceView;
        if (lease == null || lease.mDelivered || surfaceView == null || mSurfaceWidth <= 0
                || mSurfaceHeight <= 0) {
            return;
        }
        if (lease.mWidth > 0 && (mSurfaceWidth != lease.mWidth
                || mSurfaceHeight != lease.mHeight)) {
            return;
        }
        Surface surface = surfaceView.getHolder().getSurface();
        if (surface == null || !surface.isValid()) {
            return;
        }
        lease.mDelivered = true;
        mShowing = true;
        View sdrView = mSdrViews.getSdrView();
        if (sdrView != null) {
            sdrView.setVisibility(View.INVISIBLE);
        }
        acquireColorMode();
        lease.mListener.onSurfaceReady(surface);
    }

    @MainThread
    private void settle() {
        if (mCurrent == null && mLeases.isEmpty()) {
            showSdr();
        }
    }

    @MainThread
    @NonNull
    private SurfaceView ensureSurfaceView() {
        if (mSurfaceView == null) {
            mSurfaceView = new SurfaceView(mHost.getContext());
            mSurfaceView.getHolder().addCallback(mSurfaceCallback);
            // Beneath the SDR view, which is hidden rather than removed while the stream is HDR:
            // its owner may be holding on to it, as a camera holds on to its preview's texture.
            mHost.addView(mSurfaceView, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        return mSurfaceView;
    }

    @MainThread
    private void showSdrView() {
        mShowing = false;
        View sdrView = mSdrViews.getSdrView();
        if (sdrView != null) {
            sdrView.setVisibility(View.VISIBLE);
        }
    }

    @MainThread
    private void hideSurfaceView() {
        if (mSurfaceView == null) {
            return;
        }
        // Gone rather than invisible, which releases the surface and the buffers behind it.
        mSurfaceView.setVisibility(View.GONE);
        mSurfaceView.setScaleX(1f);
        mSurfaceView.setScaleY(1f);
        mSurfaceView.setTranslationX(0f);
        mSurfaceView.setTranslationY(0f);
    }

    @MainThread
    private void acquireColorMode() {
        if (mHdrWindow != null) {
            return;
        }
        Window window = HdrColorMode.windowOf(mHost);
        if (window == null) {
            return;
        }
        HdrColorMode.acquire(window);
        mHdrWindow = window;
    }

    @MainThread
    private void releaseColorMode() {
        Window window = mHdrWindow;
        if (window == null) {
            return;
        }
        mHdrWindow = null;
        HdrColorMode.release(window);
    }
}
