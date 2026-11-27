package com.xlythe.view.camera.x;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.SurfaceTexture;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.camera.core.CameraEffect;
import androidx.camera.core.ProcessingException;
import androidx.camera.core.SurfaceOutput;
import androidx.camera.core.SurfaceProcessor;
import androidx.camera.core.SurfaceRequest;
import androidx.core.util.Consumer;

import com.xlythe.view.camera.ICameraModule;
import com.xlythe.view.camera.gl.CameraTextureProvider;
import com.xlythe.view.camera.gl.FrameRenderThread;
import com.xlythe.view.camera.gl.FrameRenderer;
import com.xlythe.view.camera.gl.GlMath;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * The GL core, offered to CameraX as the thing that sits between the camera and the outputs.
 *
 * <p>CameraX asks for one surface to write frames into and hands back one per output it wants
 * drawn, each with its own size and its own transform. That is exactly the shape of
 * {@link FrameRenderThread}, so there is very little here: the camera's
 * {@link SurfaceTexture} wrapped in the surface CameraX asked for, and each output registered with
 * the renderer.</p>
 *
 * <p>The rotation, the mirror and the crop all come from {@link SurfaceOutput#updateTransformMatrix},
 * never from anything worked out here. CameraX already knows the sensor orientation, the target
 * rotation, the view port and the mirror mode each use case was configured with - including the
 * front-camera mirroring this library's own settings drive - and it publishes the answer per
 * output. Recomputing any of it would be a second opinion that disagrees on some device.</p>
 */
final class FrameEffect extends CameraEffect {
    /** Log tag shared with the camera modules. */
    private static final String TAG = ICameraModule.TAG;
    /** Whether to log what the effect is doing. */
    private static final boolean DEBUG = ICameraModule.DEBUG;

    /**
     * The outputs CameraX draws through the effect: the preview and the recording.
     *
     * <p>The still is left out. CameraX would share one camera stream between all three, which
     * makes every photograph the preview's resolution, so a still is taken from the camera at its
     * own and drawn through the renderer afterwards, by {@link #drawStill}. Anything a caller
     * draws therefore lands in both files as well as on the screen, which is the point of putting
     * the frames through GL in the first place.</p>
     */
    static final int TARGETS = PREVIEW | VIDEO_CAPTURE;

    /** What CameraX hands its surfaces to. */
    private final Processor mProcessor;
    /** Who created the camera texture, or null for the render thread's own. */
    @Nullable private final CameraTextureProvider mTextureProvider;

    /**
     * Brings up the renderer and wraps it in an effect, or returns null if this device will not
     * give us a GL context - which the caller answers by binding without an effect.
     */
    @Nullable
    @MainThread
    static FrameEffect create(
            int targets,
            @NonNull Executor executor,
            @Nullable FrameRenderer renderer,
            @Nullable CameraTextureProvider textureProvider,
            @NonNull Consumer<Throwable> errorListener) {
        FrameRenderThread renderThread = new FrameRenderThread("CameraViewFrameRenderer");
        renderThread.setCameraTextureProvider(textureProvider);
        renderThread.setFrameRenderer(renderer);
        if (!renderThread.start()) {
            return null;
        }
        return new FrameEffect(targets, new Processor(renderThread, executor), textureProvider,
                executor, errorListener);
    }

    private FrameEffect(
            int targets,
            @NonNull Processor processor,
            @Nullable CameraTextureProvider textureProvider,
            @NonNull Executor executor,
            @NonNull Consumer<Throwable> errorListener) {
        // The only constructor CameraX leaves unrestricted: every overload naming an output
        // option or a transformation is RestrictTo(LIBRARY_GROUP), as are the constants. Both of
        // its defaults turn out to be the ones to want.
        //
        // One output shared by the preview and the recording, rather than one each. A still
        // target is not offered one either way: given its own output, the still aborts the
        // process in the platform's media code the moment the effect binds on a Pixel 7 Pro,
        // "RGBA override BLOB format buffer should have height == width", that output being
        // allocated as a BLOB the driver accepts only square. Hence drawStill.
        //
        // TRANSFORMATION_ARBITRARY rather than CAMERA_AND_SURFACE_ROTATION. The latter describes
        // the processor more exactly - it draws with the matrix CameraX hands each output, so
        // both rotations are already applied - but it changes nothing a caller can see here, and
        // it is measured rather than assumed: a capture drawn through the effect is the same
        // shape as one taken without it, once each file's own orientation is applied. The two
        // paths record that orientation differently, one in the pixels and one in the Exif tag,
        // which is the distinction FrameEffectDeviceTest holds.
        super(targets, executor, processor, errorListener);
        mProcessor = processor;
        mTextureProvider = textureProvider;
    }

    /**
     * Swaps what draws each frame, without a rebind.
     *
     * <p>The context, the thread and the texture all stay as they are, which is why this is worth
     * having: tearing the renderer down to change what it draws would drop frames and, with a
     * texture somebody else created, would not be possible at all.</p>
     */
    void setFrameRenderer(@Nullable FrameRenderer renderer) {
        mProcessor.mRenderThread.setFrameRenderer(renderer);
    }

    /**
     * Whether the camera is still rendering into this provider's texture.
     *
     * <p>The texture is created once, when the renderer starts, so a different provider is a
     * different effect rather than a setting on this one.</p>
     */
    boolean usesTextureFrom(@Nullable CameraTextureProvider provider) {
        return mTextureProvider == provider;
    }

    /**
     * How the camera's frames are shown, so that a renderer draws them the right way up.
     *
     * <p>Needed because the effect has one output for the preview and the recording, and CameraX
     * turns it for each of them after it is drawn. That output is held in whatever
     * orientation CameraX chose, and only how the frames end up shown says how to turn a
     * renderer's drawing onto it.</p>
     *
     * @param rotationDegrees how far the camera's buffer is turned clockwise to be shown
     * @param mirrored whether it is then shown mirrored left to right, as a front camera's is
     */
    void setShownOrientation(@GlMath.Rotation int rotationDegrees, boolean mirrored) {
        mProcessor.mRenderThread.setShownOrientation(rotationDegrees, mirrored);
    }

    /**
     * Draws a still the camera took through the renderer, at the still's own size, into the given
     * surface - as Camera2 draws one the camera writes into the renderer itself.
     *
     * <p>The frame goes into the renderer's still input as the camera's buffer, neither turned nor
     * mirrored, and is drawn with the matrix that shows it the right way up. So a renderer is
     * handed a still exactly as it is handed every preview frame, and draws on it what it draws
     * on them.</p>
     *
     * @param frame the camera's frame, cropped to what the preview showed
     * @param rotationDegrees how far the frame is turned clockwise to be shown
     * @param mirrored whether it is then shown mirrored left to right, as a front camera's is
     * @param into what to draw into, the size of the frame once turned
     * @param onDrawn called on the render thread once the still has been drawn
     * @return whether the still went in, which it cannot once the renderer has stopped
     */
    @WorkerThread
    boolean drawStill(@NonNull Bitmap frame, @GlMath.Rotation int rotationDegrees,
            boolean mirrored, @NonNull Surface into, @NonNull Runnable onDrawn) {
        FrameRenderThread renderThread = mProcessor.mRenderThread;
        Size size = new Size(frame.getWidth(), frame.getHeight());
        Surface input = renderThread.createStillInput(size);
        if (input == null) {
            return false;
        }
        boolean armed = renderThread.drawStillInto(into, shownSize(size, rotationDegrees),
                GlMath.ScaleType.FILL_CENTER,
                (surfaceTextureMatrix, matrix) -> System.arraycopy(
                        GlMath.uprightMatrix(surfaceTextureMatrix, rotationDegrees, mirrored), 0,
                        matrix, 0, matrix.length),
                onDrawn);
        if (!armed) {
            return false;
        }
        Canvas canvas;
        try {
            canvas = input.lockCanvas(null);
        } catch (IllegalArgumentException | IllegalStateException
                | Surface.OutOfResourcesException error) {
            Log.w(TAG, "The renderer's still input would not take the frame", error);
            renderThread.cancelStill();
            return false;
        }
        try {
            canvas.drawBitmap(frame, 0f, 0f, null);
        } finally {
            input.unlockCanvasAndPost(canvas);
        }
        return true;
    }

    /** The size of a frame of the given size once it is turned to be shown. */
    @NonNull
    static Size shownSize(@NonNull Size frame, @GlMath.Rotation int rotationDegrees) {
        return rotationDegrees % 180 == 0 ? frame : new Size(frame.getHeight(), frame.getWidth());
    }

    /** Stops drawing, closes every output and gives the GL context back. */
    @MainThread
    void release() {
        mProcessor.release();
    }

    /**
     * Everything CameraX calls, on the executor the effect was built with - the main thread, which
     * is where this module does all of its work. The render thread is told about outputs through
     * its own thread-safe methods.
     */
    private static final class Processor implements SurfaceProcessor {
        /** The thread that draws every output. */
        private final FrameRenderThread mRenderThread;
        /** Where CameraX reports that a surface it was given or asked for is done with. */
        private final Executor mExecutor;

        /** The outputs CameraX has given us and not yet asked for back. Main thread only. */
        private final Set<SurfaceOutput> mOutputs = new LinkedHashSet<>();

        /** Whether the effect has been released, after which new surfaces are refused. */
        private boolean mReleased;

        private Processor(@NonNull FrameRenderThread renderThread, @NonNull Executor executor) {
            mRenderThread = renderThread;
            mExecutor = executor;
        }

        @Override
        public void onInputSurface(@NonNull SurfaceRequest request) throws ProcessingException {
            SurfaceTexture texture = mRenderThread.getInputSurfaceTexture();
            if (mReleased || texture == null) {
                // Answered as well as reported: the request is a promise CameraX waits on, and
                // throwing alone would leave it waiting.
                request.willNotProvideSurface();
                throw new ProcessingException();
            }
            mRenderThread.setInputBufferSize(request.getResolution());
            // A new Surface per request over the one SurfaceTexture, which is how a rebind at a
            // different resolution is honoured. The previous one is released from the result
            // consumer rather than here, because that only runs once CameraX has stopped writing
            // to it.
            Surface surface = new Surface(texture);
            request.provideSurface(surface, mExecutor, result -> {
                surface.release();
                if (DEBUG) {
                    Log.d(TAG, "Frame input surface released with result " + result.getResultCode());
                }
            });
        }

        @Override
        public void onOutputSurface(@NonNull SurfaceOutput output) throws ProcessingException {
            if (mReleased) {
                output.close();
                throw new ProcessingException();
            }
            Surface surface = output.getSurface(mExecutor, event -> {
                // CameraX wants this output back, which happens when its use case is unbound.
                // Drawing has to stop before it is closed, or the next frame draws into a surface
                // that has been handed to somebody else.
                mRenderThread.removeOutput(output);
                mOutputs.remove(output);
                output.close();
            });
            mOutputs.add(output);
            Size size = output.getSize();
            // The frame is the size of the output, because the matrix above has already rotated
            // and cropped the camera's buffer to fill it. So the scale type decides nothing here -
            // which is the intent: where the frame sits is CameraX's answer, not ours.
            mRenderThread.addOutput(output, surface, size, size, GlMath.ScaleType.FILL_CENTER,
                    (surfaceTextureMatrix, into) ->
                            output.updateTransformMatrix(into, surfaceTextureMatrix));
        }

        @MainThread
        private void release() {
            if (mReleased) {
                return;
            }
            mReleased = true;
            for (SurfaceOutput output : mOutputs) {
                mRenderThread.removeOutput(output);
                output.close();
            }
            mOutputs.clear();
            // Last, because it blocks until the render thread has stopped, and the outputs above
            // are surfaces it was drawing into.
            mRenderThread.stop();
        }
    }
}
