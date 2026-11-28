package com.xlythe.view.camera.gl;

import android.graphics.SurfaceTexture;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;

import com.xlythe.view.camera.ICameraModule;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The thread that owns the GL context, the camera's {@link SurfaceTexture} and the outputs.
 *
 * <p>One camera stream comes in and any number of surfaces go out: the preview, an encoder, a
 * still. Every frame is drawn once per output, each with its own size and its own transform,
 * because a preview and a recording rarely want the same shape of the same frame.</p>
 *
 * <p>The context belongs to this thread and nothing may touch it from anywhere else, which is why
 * the camera's frames arrive here rather than on the main thread: {@link SurfaceTexture} is given
 * this thread's handler, so a frame is consumed and drawn on the thread that can.</p>
 *
 * <p>A renderer draws every output the right way up as it is shown. An output held some other way
 * round - a recording in the sensor's orientation, a preview in the camera buffer's shape - is
 * drawn through a {@link ShownCanvas} and turned onto it, so that nothing a renderer draws needs
 * to know which way the sensor faces or whether the lens is mirrored.</p>
 *
 * <p>This is the driver. It is as thin as a thing that owns a thread can be, and every decision it
 * makes is in {@link GlMath}, because nothing here can run without a GPU.</p>
 */
@RequiresApi(21)
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class FrameRenderThread {
    /** Log tag shared with the camera modules. */
    private static final String TAG = ICameraModule.TAG;

    /**
     * How long to wait for the context to come up, or to go away, before giving up on it.
     *
     * <p>Under the five seconds an unresponsive main thread costs an app, because this is waited
     * on from there: a camera being configured is a main-thread operation in every backend here.
     * Two is far longer than bringing an EGL context up takes even on a cold driver, so reaching
     * it means the thread is wedged - which is reported as GL being unavailable rather than held
     * on to until the system kills the app.</p>
     */
    private static final long START_TIMEOUT_SECONDS = 2;

    /**
     * The longest a still waits for the mask made nearest to it before it is drawn with the
     * nearest there is. A model that has started returns one within a few frames; one still
     * starting may take many seconds, and the picture is not held for it.
     */
    private static final long STILL_MASK_WAIT_MILLIS = 1_500;

    /** How often a waiting still looks for its mask. */
    private static final long STILL_MASK_POLL_MILLIS = 15;

    /** How the matrix a frame is drawn with is arrived at, which is the backend's business. */
    public interface TextureTransform {
        /**
         * @param surfaceTextureMatrix what {@link SurfaceTexture#getTransformMatrix} reported for
         *     this frame
         * @param into receives the 4x4 column-major matrix to draw with. Copy the argument in to
         *     draw the camera's own idea of the frame.
         */
        void onTextureTransform(@NonNull float[] surfaceTextureMatrix, @NonNull float[] into);
    }

    /**
     * How the camera's frames are shown: turned clockwise from the buffer the camera writes,
     * then mirrored left to right or not.
     */
    private static final class Shown {
        /** Before anybody has said: the buffer as it is. */
        private static final Shown AS_WRITTEN = new Shown(0, false);

        /** How far the camera's buffer is turned clockwise to be shown. */
        @GlMath.Rotation private final int mRotation;

        /** Whether it is then shown mirrored left to right, as a front camera's is. */
        private final boolean mMirrored;

        private Shown(@GlMath.Rotation int rotation, boolean mirrored) {
            mRotation = rotation;
            mMirrored = mirrored;
        }
    }

    /** One surface being drawn into, and everything known about how to draw into it. */
    private static final class Output {
        /** The surface drawn into. */
        private final Surface mSurface;
        /** The surface's size in pixels. */
        private final Size mSize;
        /** The size of the frame once this output's transform is applied. */
        private final Size mFrameSize;
        /** How the frame is fitted to the surface. */
        private final GlMath.ScaleType mScaleType;
        /** Turns the camera texture's matrix into the one this output samples with. */
        private final TextureTransform mTransform;
        /**
         * Whether this surface feeds an encoder, which takes its presentation times on the
         * monotonic clock. See {@link GlMath#isOnRealtimeClock}.
         */
        private final boolean mEncoder;
        /**
         * Whether the camera's timestamps are on the clock that counts sleep, and have to be
         * moved before an encoder sees them. Null until the first frame decides it, and then
         * kept, because two frames that disagreed would go backwards in time.
         */
        @Nullable private Boolean mRealtimeClock;
        /**
         * How this output's frames are shown, for one that has to stay as it was when it began -
         * a recording is tagged once - or null to follow {@link #setShownOrientation}.
         */
        @Nullable private final Shown mShown;
        /** The EGL surface wrapping {@link #mSurface}, made on the first frame, or null. */
        @Nullable private EGLSurface mEglSurface;
        /** Set once this output has failed, so a broken surface is not retried every frame. */
        private boolean mFailed;
        /**
         * Where a renderer draws for this output when the output is not the right way up as
         * shown. Made on the first frame that needs it.
         */
        @Nullable private ShownCanvas mCanvas;
        /**
         * Set once this output could not be drawn through a canvas, which then has the renderer
         * draw into it directly - turned, but drawn - rather than not at all.
         */
        private boolean mCanvasFailed;

        private Output(@NonNull Surface surface, @NonNull Size size, @NonNull Size frameSize,
                @NonNull GlMath.ScaleType scaleType, @NonNull TextureTransform transform,
                boolean encoder, @Nullable Shown shown) {
            mSurface = surface;
            mSize = size;
            mFrameSize = frameSize;
            mScaleType = scaleType;
            mTransform = transform;
            mEncoder = encoder;
            mShown = shown;
        }
    }

    /** One still waiting for its frame, and where to draw it when it arrives. */
    private static final class Still {
        /** The surface the still is drawn into. */
        private final Surface mSurface;
        /** The surface's size in pixels. */
        private final Size mSize;
        /** How the frame is fitted to the surface. */
        private final GlMath.ScaleType mScaleType;
        /** Turns the still texture's matrix into the one the still is sampled with. */
        private final TextureTransform mTransform;
        /** Runs on the render thread once the still has been drawn. */
        private final Runnable mOnDrawn;
        /**
         * When the camera took the still, in nanoseconds on its clock, or 0 for the time the
         * frame landed on the still input with.
         */
        private final long mTimestampNanos;

        private Still(@NonNull Surface surface, @NonNull Size size,
                @NonNull GlMath.ScaleType scaleType, @NonNull TextureTransform transform,
                @NonNull Runnable onDrawn, long timestampNanos) {
            mSurface = surface;
            mSize = size;
            mScaleType = scaleType;
            mTransform = transform;
            mOnDrawn = onDrawn;
            mTimestampNanos = timestampNanos;
        }
    }

    /** The render thread's name, which a stack trace shows. */
    private final String mName;

    /**
     * Outputs by the key the caller added them under, in the order they were added.
     *
     * <p>Only ever read and written on the render thread, which is what makes a plain map safe
     * here: adding, removing and drawing are all posted to it.</p>
     */
    private final Map<Object, Output> mOutputs = new LinkedHashMap<>();

    /** The transform the camera's SurfaceTexture reported for the frame being drawn. */
    private final float[] mSurfaceTextureMatrix = GlMath.identity();
    /** The transform the current output samples the frame with, handed to the renderer. */
    private final float[] mTextureMatrix = GlMath.identity();

    /**
     * The size the camera writes frames at, which is what says whether a picture is shown wide
     * or tall. Null until the backend says, which leaves every output drawn as it is held.
     */
    @Nullable private volatile Size mInputSize;

    /** How the camera's frames are shown, set by the backend as the display turns. */
    @NonNull private volatile Shown mShown = Shown.AS_WRITTEN;

    /** What turns a {@link ShownCanvas} onto its output. Made with the context. */
    @Nullable private Texture2dProgram mCanvasProgram;

    /** The render thread, or null while stopped. */
    @Nullable private HandlerThread mThread;
    /** Posts to the render thread, or null while stopped. */
    @Nullable private Handler mHandler;
    /** The EGL context, or null while stopped. */
    @Nullable private EglCore mEgl;
    /** Draws the camera's external texture, or null while stopped. */
    @Nullable private OesTextureProgram mProgram;
    /** What the camera writes preview frames into, or null while stopped. */
    @Nullable private SurfaceTexture mSurfaceTexture;
    /** The frame handed to the renderer, reused for every frame and output. */
    @Nullable private GlFrame mFrame;

    /**
     * A second input, for one frame at a time at a size of its own.
     *
     * <p>Here because a still is not a preview frame. A camera gives its full resolution only to
     * a capture request aimed at a high-resolution target, so an effect that drew the preview
     * stream into the photograph would quietly turn a twelve megapixel camera into a two
     * megapixel one. This is that target: the camera writes one full-size frame here, it goes
     * through the same renderer as every other frame, and the result is what gets saved.</p>
     *
     * <p>Null until {@link #createStillInput} is called, which is only when an effect is
     * running - a texture and a buffer the size of a photograph are not worth holding
     * otherwise.</p>
     */
    @Nullable private SurfaceTexture mStillTexture;
    /** The surface the camera writes a still into, wrapping {@link #mStillTexture}, or null. */
    @Nullable private Surface mStillSurface;
    /** The external texture behind {@link #mStillTexture}, or 0. */
    private int mStillTextureName;

    /** What to do with the next frame to land on the still input, or null for nothing. */
    @Nullable private Still mPendingStill;
    /** The still that has landed but waits on its mask, or null. Render thread only. */
    @Nullable private MaskedStill mMaskedStill;
    /** What draws each frame, or null to draw the camera frame unchanged. */
    @Nullable private volatile FrameRenderer mRenderer;
    /** Who creates the camera texture, or null for one of this thread's own. */
    @Nullable private CameraTextureProvider mTextureProvider;
    /** The external texture the camera writes preview frames into, or 0. */
    private int mTextureName;
    /** Whether the thread is running, with its context up. */
    private volatile boolean mRunning;

    public FrameRenderThread(@NonNull String name) {
        mName = name;
    }

    /**
     * Who creates the texture the camera renders into. Must be set before {@link #start}, because
     * that is when the texture is made and a camera cannot be moved to another one afterwards.
     */
    public void setCameraTextureProvider(@Nullable CameraTextureProvider provider) {
        if (mRunning) {
            throw new IllegalStateException("The camera texture is created when the renderer starts");
        }
        mTextureProvider = provider;
    }

    /** What draws each frame, or null to draw the camera frame and nothing else. */
    public void setFrameRenderer(@Nullable FrameRenderer renderer) {
        mRenderer = renderer;
    }

    /**
     * Brings up the thread and the context.
     *
     * <p>Returns rather than throws, because a device whose driver will not give us a context is
     * a device the camera still has to work on: the caller's answer to false is to use the path
     * that does not go through GL.</p>
     *
     * @return whether the context, the shader and the texture all came up
     */
    public boolean start() {
        if (mRunning) {
            return true;
        }
        HandlerThread thread = new HandlerThread(mName);
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        mHandler = handler;
        CountDownLatch ready = new CountDownLatch(1);
        AtomicBoolean started = new AtomicBoolean();
        handler.post(() -> {
            started.set(initialize());
            ready.countDown();
        });
        try {
            if (!ready.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "Timed out starting the frame renderer");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Interrupted starting the frame renderer", error);
        }
        if (!started.get()) {
            thread.quitSafely();
            mHandler = null;
            return false;
        }
        mThread = thread;
        mRunning = true;
        return true;
    }

    /**
     * The size the camera writes frames at, which is passed on to the input texture.
     *
     * <p>Kept as well as passed on, because a SurfaceTexture cannot be asked for it back and a
     * renderer drawing in the shape a picture is shown needs it.</p>
     */
    public void setInputBufferSize(@NonNull Size size) {
        mInputSize = size;
        SurfaceTexture texture = mSurfaceTexture;
        if (texture != null) {
            texture.setDefaultBufferSize(size.getWidth(), size.getHeight());
        }
    }

    /**
     * How the camera's frames are shown, which is what a renderer's drawing is turned to match.
     *
     * <p>The same for every output, because the preview, the recording and the still all show
     * what the person framed. Called again whenever the display turns.</p>
     *
     * @param rotationDegrees how far the camera's buffer is turned clockwise to be shown
     * @param mirrored whether it is then shown mirrored left to right, as a front camera's is
     */
    public void setShownOrientation(@GlMath.Rotation int rotationDegrees, boolean mirrored) {
        mShown = new Shown(rotationDegrees, mirrored);
    }

    /** The texture the camera writes into. Null until {@link #start} has succeeded. */
    @Nullable
    public SurfaceTexture getInputSurfaceTexture() {
        return mSurfaceTexture;
    }

    /**
     * The surface a camera writes a full-size still into, created on first use.
     *
     * <p>Blocks until the render thread has made it, because the caller is building a capture
     * session around it and cannot be given it later. Returns null if the thread is not running
     * or will not make one.</p>
     *
     * @param size the size the camera will write, which is the size the still comes out
     */
    @Nullable
    public Surface createStillInput(@NonNull Size size) {
        Handler handler = mHandler;
        if (!mRunning || handler == null) {
            return null;
        }
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Surface> created = new AtomicReference<>();
        handler.post(() -> {
            try {
                created.set(makeStillInput(size));
            } catch (RuntimeException | LinkageError error) {
                Log.w(TAG, "This device will not render stills through GL", error);
            } finally {
                ready.countDown();
            }
        });
        try {
            if (!ready.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "Timed out making a still input");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        return created.get();
    }

    /** Runs on the render thread. Replaces any previous still input, since its size may differ. */
    @Nullable
    private Surface makeStillInput(@NonNull Size size) {
        EglCore egl = mEgl;
        OesTextureProgram program = mProgram;
        if (egl == null || program == null) {
            return null;
        }
        egl.makeCurrent();
        releaseStillInput();
        mStillTextureName = program.createExternalTexture();
        SurfaceTexture texture = new SurfaceTexture(mStillTextureName);
        texture.setDefaultBufferSize(size.getWidth(), size.getHeight());
        texture.setOnFrameAvailableListener(this::onStillAvailable, mHandler);
        mStillTexture = texture;
        mStillSurface = new Surface(texture);
        return mStillSurface;
    }

    /**
     * Draws the next frame to arrive on the still input into a surface, once.
     *
     * <p>Armed rather than drawn, because the frame has not been taken yet: the caller asks for
     * this and then tells the camera to capture, and the drawing happens when the frame
     * lands.</p>
     *
     * @param onDrawn called on the render thread once the frame has been drawn and presented
     * @return whether the request was armed
     */
    public boolean drawStillInto(@NonNull Surface surface, @NonNull Size size,
            @NonNull GlMath.ScaleType scaleType, @NonNull TextureTransform transform,
            @NonNull Runnable onDrawn) {
        return drawStillInto(surface, size, scaleType, transform, onDrawn, 0);
    }

    /**
     * {@link #drawStillInto}, for a still the camera took at a known time but that reaches the
     * still input later, drawn there from a decoded picture.
     *
     * @param timestampNanos when the camera took it, on the clock its preview frames carry
     */
    public boolean drawStillInto(@NonNull Surface surface, @NonNull Size size,
            @NonNull GlMath.ScaleType scaleType, @NonNull TextureTransform transform,
            @NonNull Runnable onDrawn, long timestampNanos) {
        Handler handler = mHandler;
        if (!mRunning || handler == null) {
            return false;
        }
        handler.post(() -> mPendingStill =
                new Still(surface, size, scaleType, transform, onDrawn, timestampNanos));
        return true;
    }

    /** Stops waiting for a still, for a capture that failed before the camera wrote anything. */
    public void cancelStill() {
        post(() -> mPendingStill = null);
    }

    /** Runs on the render thread: a full-size frame has arrived for a still. */
    private void onStillAvailable(@NonNull SurfaceTexture texture) {
        EglCore egl = mEgl;
        Still still = mPendingStill;
        if (!mRunning || egl == null) {
            return;
        }
        try {
            // A still waiting on its mask is drawn now, from the frame still in the texture,
            // before the next one takes its place.
            drawMaskedStillNow();
            egl.makeCurrent();
            texture.updateTexImage();
            if (still == null) {
                // A frame nobody asked for, which happens when a capture is abandoned. It still
                // has to be consumed or the texture stays full and the next one never arrives.
                return;
            }
            mPendingStill = null;
            float[] stillMatrix = new float[16];
            texture.getTransformMatrix(stillMatrix);
            long timestampNanos = still.mTimestampNanos != 0
                    ? still.mTimestampNanos : texture.getTimestamp();
            drawWhenMasked(new MaskedStill(still, stillMatrix, timestampNanos,
                    SystemClock.uptimeMillis() + STILL_MASK_WAIT_MILLIS), false /* now */);
        } catch (RuntimeException | LinkageError error) {
            Log.w(TAG, "Dropped a still frame", error);
            mPendingStill = null;
        }
    }

    /** A still that has landed on the still input and waits for the mask nearest to it. */
    private final class MaskedStill implements Runnable {
        /** Where it is drawn. */
        final Still mStill;
        /** The still texture's transform as the frame landed. */
        final float[] mMatrix;
        /** When the camera took it. */
        final long mTimestampNanos;
        /** When it is drawn with whatever mask there is, in uptime milliseconds. */
        final long mDeadline;

        MaskedStill(@NonNull Still still, @NonNull float[] matrix, long timestampNanos,
                long deadline) {
            mStill = still;
            mMatrix = matrix;
            mTimestampNanos = timestampNanos;
            mDeadline = deadline;
        }

        @Override
        public void run() {
            mMaskedStill = null;
            drawWhenMasked(this, false /* now */);
        }
    }

    /**
     * Draws a still once the renderer has the mask made nearest to it, or once
     * {@link #STILL_MASK_WAIT_MILLIS} have passed. A mask arrives some time after its frame, so
     * a still drawn on landing would take whichever mask came last: one made before the press,
     * or none at all while the model starts. The preview goes on drawing meanwhile.
     *
     * @param now draw it with whatever mask there is, as when another still or a stop needs the
     *     texture
     */
    private void drawWhenMasked(@NonNull MaskedStill waiting, boolean now) {
        Handler handler = mHandler;
        FrameRenderer renderer = mRenderer;
        if (!now && handler != null && renderer instanceof BackgroundReplacer
                && !((BackgroundReplacer) renderer).hasMaskFor(waiting.mTimestampNanos)
                && SystemClock.uptimeMillis() < waiting.mDeadline) {
            mMaskedStill = waiting;
            handler.postDelayed(waiting, STILL_MASK_POLL_MILLIS);
            return;
        }
        if (mEgl == null) {
            waiting.mStill.mOnDrawn.run();
            return;
        }
        try {
            System.arraycopy(waiting.mMatrix, 0, mSurfaceTextureMatrix, 0,
                    waiting.mMatrix.length);
            drawStill(waiting.mStill, waiting.mTimestampNanos);
        } catch (RuntimeException | LinkageError error) {
            Log.w(TAG, "Dropped a still frame", error);
        }
    }

    /** Draws the still waiting on its mask, if one is and its mask or its deadline has come. */
    private void drawMaskedStillIfReady() {
        MaskedStill waiting = mMaskedStill;
        FrameRenderer renderer = mRenderer;
        if (waiting == null || (renderer instanceof BackgroundReplacer
                && !((BackgroundReplacer) renderer).hasMaskFor(waiting.mTimestampNanos)
                && SystemClock.uptimeMillis() < waiting.mDeadline)) {
            return;
        }
        drawMaskedStillNow();
    }

    /** Draws the still waiting on its mask, if one is, with whatever mask is nearest now. */
    private void drawMaskedStillNow() {
        MaskedStill waiting = mMaskedStill;
        if (waiting == null) {
            return;
        }
        mMaskedStill = null;
        Handler handler = mHandler;
        if (handler != null) {
            handler.removeCallbacks(waiting);
        }
        drawWhenMasked(waiting, true /* now */);
    }

    /** Draws the still input into its one output, through the renderer like any other frame. */
    private void drawStill(@NonNull Still still, long timestampNanos) {
        EglCore egl = mEgl;
        GlFrame frame = mFrame;
        if (egl == null || frame == null) {
            return;
        }
        EGLSurface eglSurface = null;
        try {
            eglSurface = egl.createWindowSurface(still.mSurface);
            egl.makeCurrent(eglSurface);
            still.mTransform.onTextureTransform(mSurfaceTextureMatrix, mTextureMatrix);
            GLES20.glViewport(0, 0, still.mSize.getWidth(), still.mSize.getHeight());
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            frame.set(mStillTextureName, mTextureMatrix, still.mSize.getWidth(),
                    still.mSize.getHeight(), timestampNanos,
                    GlMath.viewport(still.mScaleType, still.mSize, still.mSize));
            FrameRenderer renderer = mRenderer;
            if (renderer == null || !renderer.onDrawFrame(frame)) {
                frame.drawCameraFrame();
            }
            egl.setPresentationTime(eglSurface, timestampNanos);
            egl.swapBuffers(eglSurface);
        } finally {
            if (eglSurface != null) {
                egl.releaseSurface(eglSurface);
            }
            // Told after the surface is released, so a caller that reads the result cannot race
            // the GL driver still holding it.
            still.mOnDrawn.run();
        }
    }

    private void releaseStillInput() {
        // Drawn from the texture about to go, rather than lost with it.
        drawMaskedStillNow();
        mPendingStill = null;
        if (mStillSurface != null) {
            mStillSurface.release();
            mStillSurface = null;
        }
        if (mStillTexture != null) {
            mStillTexture.setOnFrameAvailableListener(null);
            mStillTexture.release();
            mStillTexture = null;
        }
        if (mStillTextureName != 0 && mProgram != null) {
            mProgram.deleteTexture(mStillTextureName);
        }
        mStillTextureName = 0;
    }

    /**
     * Adds a surface to draw every frame into, replacing any output under the same key.
     *
     * @param key identifies this output for {@link #removeOutput}; the caller's own object
     * @param frameSize the size the frame has once {@code transform} has been applied to it, which
     *     is what decides where {@code scaleType} places it. A backend whose transform already
     *     fits the frame to the output passes the output's own size.
     */
    public void addOutput(@NonNull Object key, @NonNull Surface surface, @NonNull Size size,
            @NonNull Size frameSize, @NonNull GlMath.ScaleType scaleType,
            @NonNull TextureTransform transform) {
        putOutput(key, new Output(surface, size, frameSize, scaleType, transform, false, null));
    }

    /**
     * Adds an encoder's input surface to draw every frame into, filling it.
     *
     * <p>Separate from {@link #addOutput} because an encoder reads the presentation time of every
     * frame as when it happened, against its audio, so the time has to be on the encoder's
     * clock.</p>
     *
     * @param key identifies this output for {@link #removeOutput}; the caller's own object
     * @param rotationDegrees how far the camera's buffer is turned clockwise to show this
     *     recording, fixed for its length because the file is tagged once
     * @param mirrored whether the recording is then shown mirrored left to right
     */
    public void addEncoderOutput(@NonNull Object key, @NonNull Surface surface,
            @NonNull Size size, @NonNull TextureTransform transform,
            @GlMath.Rotation int rotationDegrees, boolean mirrored) {
        putOutput(key, new Output(surface, size, size, GlMath.ScaleType.FILL_CENTER, transform,
                true, new Shown(rotationDegrees, mirrored)));
    }

    private void putOutput(@NonNull Object key, @NonNull Output output) {
        post(() -> {
            releaseOutput(mOutputs.remove(key));
            mOutputs.put(key, output);
        });
    }

    /** Stops drawing into the surface added under this key and lets go of it. */
    public void removeOutput(@NonNull Object key) {
        post(() -> releaseOutput(mOutputs.remove(key)));
    }

    /**
     * Shuts the thread down and gives the context, the texture and every output back.
     *
     * <p>Blocks until the GL thread has finished, because what comes next is usually the camera
     * releasing the surfaces this thread is drawing into.</p>
     */
    public void stop() {
        HandlerThread thread = mThread;
        Handler handler = mHandler;
        if (thread == null || handler == null) {
            return;
        }
        mRunning = false;
        CountDownLatch stopped = new CountDownLatch(1);
        handler.post(() -> {
            try {
                releaseGl();
            } finally {
                stopped.countDown();
            }
        });
        try {
            if (!stopped.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "Timed out stopping the frame renderer");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Interrupted stopping the frame renderer", error);
        }
        thread.quitSafely();
        mThread = null;
        mHandler = null;
    }

    private void post(@NonNull Runnable work) {
        Handler handler = mHandler;
        if (handler == null) {
            Log.w(TAG, "The frame renderer is not running");
            return;
        }
        handler.post(work);
    }

    /** Runs on the render thread. Everything it creates is released by {@link #releaseGl}. */
    private boolean initialize() {
        try {
            EglCore egl = new EglCore();
            mEgl = egl;
            // A GLES call without a current context does nothing and reports nothing, so the
            // context is made current against the offscreen surface before the program and the
            // texture are created - there is no output surface to make current against yet.
            egl.makeCurrent();
            OesTextureProgram program = new OesTextureProgram();
            mProgram = program;
            mCanvasProgram = new Texture2dProgram();
            mFrame = new GlFrame(program);
            CameraTextureProvider provider = mTextureProvider;
            mTextureName = provider == null
                    ? program.createExternalTexture() : provider.onCreateCameraTexture();
            SurfaceTexture texture = new SurfaceTexture(mTextureName);
            texture.setOnFrameAvailableListener(this::onFrameAvailable, mHandler);
            mSurfaceTexture = texture;
            FrameRenderer renderer = mRenderer;
            if (renderer != null) {
                renderer.onRendererStarted();
            }
            return true;
        } catch (RuntimeException | LinkageError error) {
            Log.e(TAG, "This device will not render camera frames through GL", error);
            releaseGl();
            return false;
        }
    }

    /** Runs on the render thread, because that is the handler the SurfaceTexture was given. */
    private void onFrameAvailable(@NonNull SurfaceTexture texture) {
        EglCore egl = mEgl;
        if (!mRunning || egl == null) {
            return;
        }
        try {
            // A still whose mask has come in goes ahead of the preview, rather than behind every
            // frame queued since it began to wait.
            drawMaskedStillIfReady();
            // Consuming a frame is a GL operation on the texture, so it needs a current context
            // even though nothing is being drawn yet.
            egl.makeCurrent();
            CameraTextureProvider provider = mTextureProvider;
            boolean advanced = provider != null && provider.onCameraFrameAvailable(texture);
            if (!advanced) {
                texture.updateTexImage();
            }
            texture.getTransformMatrix(mSurfaceTextureMatrix);
            long timestampNanos = texture.getTimestamp();
            for (Output output : mOutputs.values()) {
                drawInto(output, timestampNanos);
            }
        } catch (RuntimeException | LinkageError error) {
            // One bad frame is not worth tearing the camera down for: the next one usually works,
            // and a surface that has genuinely gone is caught per output below.
            Log.w(TAG, "Dropped a camera frame", error);
        }
    }

    private void drawInto(@NonNull Output output, long timestampNanos) {
        EglCore egl = mEgl;
        GlFrame frame = mFrame;
        if (egl == null || frame == null || output.mFailed) {
            return;
        }
        try {
            if (output.mEglSurface == null) {
                output.mEglSurface = egl.createWindowSurface(output.mSurface);
            }
            egl.makeCurrent(output.mEglSurface);
            output.mTransform.onTextureTransform(mSurfaceTextureMatrix, mTextureMatrix);
            FrameRenderer renderer = mRenderer;
            if (renderer == null || !drawThroughCanvas(output, renderer, frame, timestampNanos)) {
                // Cleared because a frame that does not cover the output - and under FIT_CENTER
                // none of them do - would otherwise show whatever the last frame left behind.
                GLES20.glViewport(0, 0, output.mSize.getWidth(), output.mSize.getHeight());
                GLES20.glClearColor(0f, 0f, 0f, 1f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                frame.set(mTextureName, mTextureMatrix, output.mSize.getWidth(),
                        output.mSize.getHeight(), timestampNanos,
                        GlMath.viewport(output.mScaleType, output.mFrameSize, output.mSize));
                if (renderer == null || !renderer.onDrawFrame(frame)) {
                    frame.drawCameraFrame();
                }
            }
            egl.setPresentationTime(output.mEglSurface, presentationTime(output, timestampNanos));
            egl.swapBuffers(output.mEglSurface);
        } catch (RuntimeException | LinkageError error) {
            // This output is gone, which happens when a use case is unbound a frame before the
            // renderer hears about it. The others keep drawing.
            Log.w(TAG, "Giving up on a frame output", error);
            output.mFailed = true;
            releaseOutput(output);
        }
    }

    /**
     * Has the renderer draw the picture the right way up as it is shown, and turns that onto the
     * output, for an output that holds the picture some other way round.
     *
     * <p>Not used without a renderer: the camera frame alone is drawn straight into the output
     * with the output's own matrix, which is the same picture for one pass instead of two.</p>
     *
     * @return false, having drawn nothing, when the output is already the right way up and the
     *     shape it is shown, so the renderer draws into it directly
     */
    private boolean drawThroughCanvas(@NonNull Output output, @NonNull FrameRenderer renderer,
            @NonNull GlFrame frame, long timestampNanos) {
        Size inputSize = mInputSize;
        Texture2dProgram canvasProgram = mCanvasProgram;
        if (inputSize == null || canvasProgram == null || output.mCanvasFailed) {
            return false;
        }
        Shown shown = output.mShown != null ? output.mShown : mShown;
        float[] asShown;
        float[] cameraAsShown;
        Size shownSize;
        try {
            float[] toBuffer = GlMath.outputToBuffer(mTextureMatrix, mSurfaceTextureMatrix);
            asShown = GlMath.outputAsShown(toBuffer, shown.mRotation, shown.mMirrored);
            shownSize = GlMath.shownSize(toBuffer, inputSize, output.mSize, shown.mRotation);
            cameraAsShown = GlMath.multiply(mTextureMatrix, GlMath.invertAffine(asShown));
        } catch (IllegalArgumentException error) {
            // A matrix that flattens the frame says nothing about how it is turned.
            Log.w(TAG, "Drawing straight into an output whose turn cannot be read", error);
            output.mCanvasFailed = true;
            return false;
        }
        if (GlMath.isIdentity(asShown) && shownSize.equals(output.mSize)) {
            return false;
        }
        ShownCanvas canvas = output.mCanvas;
        if (canvas == null) {
            canvas = new ShownCanvas();
            output.mCanvas = canvas;
        }
        try {
            canvas.bind(shownSize);
        } catch (IllegalStateException error) {
            Log.w(TAG, "Drawing straight into an output that is held turned", error);
            output.mCanvasFailed = true;
            canvas.release();
            output.mCanvas = null;
            return false;
        }
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        int width = shownSize.getWidth();
        int height = shownSize.getHeight();
        frame.set(mTextureName, cameraAsShown, width, height, timestampNanos,
                new int[] {0, 0, width, height});
        if (!renderer.onDrawFrame(frame)) {
            frame.drawCameraFrame();
        }
        canvas.unbind();
        // Whatever the renderer left switched on would otherwise clip or blend the turn.
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        canvasProgram.draw(canvas.getTexture(), asShown,
                new int[] {0, 0, output.mSize.getWidth(), output.mSize.getHeight()});
        return true;
    }

    /** When an output is told a frame happened, which for an encoder is on its own clock. */
    private static long presentationTime(@NonNull Output output, long timestampNanos) {
        if (!output.mEncoder) {
            return timestampNanos;
        }
        long monotonicNanos = System.nanoTime();
        long realtimeNanos = SystemClock.elapsedRealtimeNanos();
        if (output.mRealtimeClock == null) {
            output.mRealtimeClock =
                    GlMath.isOnRealtimeClock(timestampNanos, monotonicNanos, realtimeNanos);
        }
        return output.mRealtimeClock
                ? timestampNanos - (realtimeNanos - monotonicNanos)
                : timestampNanos;
    }

    private void releaseOutput(@Nullable Output output) {
        EglCore egl = mEgl;
        if (output == null) {
            return;
        }
        if (output.mCanvas != null) {
            output.mCanvas.release();
            output.mCanvas = null;
        }
        if (output.mEglSurface == null) {
            return;
        }
        if (egl != null) {
            egl.releaseSurface(output.mEglSurface);
        }
        output.mEglSurface = null;
    }

    /**
     * Runs on the render thread, and has to cope with being called part way through
     * {@link #initialize} - which is the whole reason every field it touches is nullable.
     */
    private void releaseGl() {
        mRunning = false;
        // Drawn while the renderer is still up, rather than lost or drawn without it.
        drawMaskedStillNow();
        FrameRenderer renderer = mRenderer;
        if (renderer != null && mProgram != null) {
            try {
                renderer.onRendererStopped();
            } catch (RuntimeException error) {
                Log.w(TAG, "A frame renderer threw on the way out", error);
            }
        }
        for (Output output : mOutputs.values()) {
            releaseOutput(output);
        }
        mOutputs.clear();
        releaseStillInput();
        if (mSurfaceTexture != null) {
            mSurfaceTexture.setOnFrameAvailableListener(null);
            mSurfaceTexture.release();
            mSurfaceTexture = null;
        }
        if (mTextureName != 0) {
            CameraTextureProvider provider = mTextureProvider;
            if (provider != null) {
                provider.onReleaseCameraTexture(mTextureName);
            } else if (mProgram != null) {
                mProgram.deleteTexture(mTextureName);
            }
            mTextureName = 0;
        }
        if (mCanvasProgram != null) {
            mCanvasProgram.release();
            mCanvasProgram = null;
        }
        if (mProgram != null) {
            mProgram.release();
            mProgram = null;
        }
        mFrame = null;
        if (mEgl != null) {
            mEgl.release();
            mEgl = null;
        }
    }
}
