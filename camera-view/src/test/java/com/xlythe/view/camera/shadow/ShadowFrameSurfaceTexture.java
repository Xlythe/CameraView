package com.xlythe.view.camera.shadow;

import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.annotation.Resetter;
import org.robolectric.shadow.api.Shadow;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Lets a test hand a {@link SurfaceTexture} a camera frame.
 *
 * <p>A real SurfaceTexture tells its listener a frame has landed when a producer - the camera -
 * queues a buffer into it. Under test nothing produces, so the listener never fires and nothing
 * downstream of it runs: not the render thread's frame loop, not a still being drawn. This keeps
 * the listener and the handler it was given, and {@link #deliverFrame} calls it there the way the
 * framework would, then waits for the handler to finish with it.</p>
 *
 * <p>The frame itself is what {@link #setFrameTimestamp} and {@link #setFrameTransform} say it is,
 * for every texture, since a test delivers one frame at a time. Consuming it with
 * {@code updateTexImage} is counted, because a frame consumed twice or not at all is a stalled
 * preview on a device.</p>
 */
@Implements(SurfaceTexture.class)
public class ShadowFrameSurfaceTexture {
    /** How long a delivered frame is given to be drawn before the test gives up on it. */
    private static final long DELIVERY_TIMEOUT_SECONDS = 5;

    /** Guards every static field and the instance state read across threads. */
    private static final Object sLock = new Object();

    /** Every texture with a listener, oldest first. */
    private static final List<SurfaceTexture> sListening = new ArrayList<>();

    /** What {@code getTimestamp} reports for the frame being consumed. */
    private static long sTimestampNanos;

    /** What {@code getTransformMatrix} reports for the frame being consumed. */
    @NonNull private static float[] sTransform = identity();

    /** The texture this shadows. */
    @RealObject private SurfaceTexture mRealTexture;

    /** The listener frames are reported to, or null for none. */
    @Nullable private SurfaceTexture.OnFrameAvailableListener mListener;

    /** Where the listener is called, or null for straight away. */
    @Nullable private Handler mHandler;

    /** What {@code setDefaultBufferSize} was last told, or null if it never was. */
    @Nullable private Size mBufferSize;

    /** How often this texture's frame has been consumed. */
    private int mUpdates;

    /** Whether this texture has been released. */
    private boolean mReleased;

    /** The timestamp every frame reports from now on. */
    public static void setFrameTimestamp(long timestampNanos) {
        synchronized (sLock) {
            sTimestampNanos = timestampNanos;
        }
    }

    /** The transform every frame reports from now on, as {@code getTransformMatrix} gives it. */
    public static void setFrameTransform(@NonNull float[] transform) {
        synchronized (sLock) {
            sTransform = transform.clone();
        }
    }

    /** Every texture something is listening to, oldest first. */
    @NonNull
    public static List<SurfaceTexture> listeningTextures() {
        synchronized (sLock) {
            return new ArrayList<>(sListening);
        }
    }

    /** How often a texture's frame has been consumed with {@code updateTexImage}. */
    public static int updatesOf(@NonNull SurfaceTexture texture) {
        ShadowFrameSurfaceTexture shadow = Shadow.extract(texture);
        synchronized (sLock) {
            return shadow.mUpdates;
        }
    }

    /** The buffer size a texture was last given, or null if it never was. */
    @Nullable
    public static Size bufferSizeOf(@NonNull SurfaceTexture texture) {
        ShadowFrameSurfaceTexture shadow = Shadow.extract(texture);
        synchronized (sLock) {
            return shadow.mBufferSize;
        }
    }

    public static boolean isReleased(@NonNull SurfaceTexture texture) {
        ShadowFrameSurfaceTexture shadow = Shadow.extract(texture);
        synchronized (sLock) {
            return shadow.mReleased;
        }
    }

    /**
     * Tells a texture's listener a frame has landed, on the handler it asked for, and returns
     * once the listener has finished with it.
     *
     * @throws IllegalStateException if nothing is listening, which is a test that has lost track
     *     of which texture the camera writes into
     */
    public static void deliverFrame(@NonNull SurfaceTexture texture) {
        ShadowFrameSurfaceTexture shadow = Shadow.extract(texture);
        SurfaceTexture.OnFrameAvailableListener listener;
        Handler handler;
        synchronized (sLock) {
            listener = shadow.mListener;
            handler = shadow.mHandler;
        }
        if (listener == null) {
            throw new IllegalStateException("Nothing is listening for frames on this texture");
        }
        if (handler == null) {
            listener.onFrameAvailable(texture);
            return;
        }
        CountDownLatch handled = new CountDownLatch(1);
        handler.post(() -> {
            try {
                listener.onFrameAvailable(texture);
            } finally {
                handled.countDown();
            }
        });
        await(handled);
    }

    /**
     * Waits until everything already posted to a handler has run, which is how a test knows
     * work it handed a render thread has been done.
     */
    public static void flush(@NonNull Handler handler) {
        CountDownLatch flushed = new CountDownLatch(1);
        handler.post(flushed::countDown);
        await(flushed);
    }

    /** The handler a texture's listener is called on, or null for none. */
    @Nullable
    public static Handler handlerOf(@NonNull SurfaceTexture texture) {
        ShadowFrameSurfaceTexture shadow = Shadow.extract(texture);
        synchronized (sLock) {
            return shadow.mHandler;
        }
    }

    @Resetter
    public static void reset() {
        synchronized (sLock) {
            sListening.clear();
            sTimestampNanos = 0;
            sTransform = identity();
        }
    }

    @Implementation
    protected void setOnFrameAvailableListener(
            @Nullable SurfaceTexture.OnFrameAvailableListener listener, @Nullable Handler handler) {
        synchronized (sLock) {
            mListener = listener;
            mHandler = listener == null ? null : handler;
            sListening.remove(mRealTexture);
            if (listener != null) {
                sListening.add(mRealTexture);
            }
        }
    }

    @Implementation
    protected void setDefaultBufferSize(int width, int height) {
        synchronized (sLock) {
            mBufferSize = new Size(width, height);
        }
    }

    @Implementation
    protected void updateTexImage() {
        synchronized (sLock) {
            mUpdates++;
        }
    }

    @Implementation
    protected long getTimestamp() {
        synchronized (sLock) {
            return sTimestampNanos;
        }
    }

    @Implementation
    protected void getTransformMatrix(float[] matrix) {
        synchronized (sLock) {
            System.arraycopy(sTransform, 0, matrix, 0, sTransform.length);
        }
    }

    @Implementation
    protected void release() {
        synchronized (sLock) {
            mReleased = true;
            mListener = null;
            mHandler = null;
            sListening.remove(mRealTexture);
        }
    }

    private static void await(@NonNull CountDownLatch latch) {
        try {
            if (!latch.await(DELIVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("The render thread did not get to the frame");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    @NonNull
    private static float[] identity() {
        return new float[] {
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1,
        };
    }
}
