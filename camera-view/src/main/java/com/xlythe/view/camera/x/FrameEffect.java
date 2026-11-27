package com.xlythe.view.camera.x;

import android.graphics.SurfaceTexture;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import com.xlythe.view.camera.gl.TimeLapse;

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
    private static final String TAG = ICameraModule.TAG;
    private static final boolean DEBUG = ICameraModule.DEBUG;

    /**
     * Every output a frame can reach: the preview, the recording and the still.
     *
     * <p>CameraX accepts only certain combinations - a target set has to be one use case, or the
     * preview and the recording, or all three - so this is the one value that covers the whole
     * pipeline. Anything a caller draws therefore lands in the file as well as on the screen,
     * which is the point of putting the frames through GL in the first place.</p>
     */
    static final int ALL_TARGETS = PREVIEW | VIDEO_CAPTURE | IMAGE_CAPTURE;

    private final Processor mProcessor;
    @Nullable private final CameraTextureProvider mTextureProvider;

    /**
     * What draws a time-lapse recording on this effect's thread, from the recording's own stream,
     * while one is being made. See {@link #startTimeLapse}.
     */
    @Nullable private Processor mTimeLapseProcessor;

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
        return new FrameEffect(targets, new Processor(renderThread, executor, null),
                textureProvider, executor, errorListener);
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
        // One output shared by every target, rather than one each. One each would give the still
        // its own resolution instead of the preview's, which is the better picture, but it
        // aborts the process in the platform's media code the moment the effect binds on a
        // Pixel 7 Pro: "RGBA override BLOB format buffer should have height == width", that
        // still output being allocated as a BLOB the driver accepts only square.
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
     * <p>Needed because the effect has one output for the preview, the recording and the still,
     * and CameraX turns it for each of them after it is drawn. That output is held in whatever
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
     * This pipeline offered for the preview alone, for a bind whose recording is drawn from a
     * stream of its own by {@link #startTimeLapse}.
     *
     * <p>The same processor, so the same thread, texture and renderer: only what CameraX routes
     * through it changes. The still is left out, because CameraX accepts the preview and the
     * recording apart only as one effect each, and a still with an effect of its own is the
     * output that aborts the process on a Pixel 7 Pro.</p>
     */
    @NonNull
    @MainThread
    CameraEffect forPreview() {
        return new TargetedEffect(PREVIEW, getExecutor(), mProcessor, getErrorListener());
    }

    /**
     * An effect for the recording alone that draws it as a time-lapse, on this effect's thread and
     * through its renderer.
     *
     * <p>A separate effect because CameraX shares one output between every use case an effect
     * covers. Keeping one frame in many there would keep one frame in many on the preview too;
     * as an effect of its own the recording has a stream of its own, and the preview keeps every
     * frame. It still comes to this thread, because the renderer is the caller's and runs on one
     * thread only.</p>
     *
     * <p>Replaces any time-lapse already started here.</p>
     */
    @NonNull
    @MainThread
    CameraEffect startTimeLapse(@NonNull TimeLapse timeLapse) {
        stopTimeLapse();
        Processor processor = new Processor(mProcessor.mRenderThread, getExecutor(), timeLapse);
        mTimeLapseProcessor = processor;
        return new TargetedEffect(VIDEO_CAPTURE, getExecutor(), processor, getErrorListener());
    }

    /** Closes what {@link #startTimeLapse} drew into and lets its stream go. */
    @MainThread
    void stopTimeLapse() {
        Processor processor = mTimeLapseProcessor;
        mTimeLapseProcessor = null;
        if (processor != null) {
            processor.release();
        }
    }

    /** Stops drawing, closes every output and gives the GL context back. */
    @MainThread
    void release() {
        stopTimeLapse();
        mProcessor.release();
        // Last, because it blocks until the render thread has stopped, and the outputs above
        // are surfaces it was drawing into.
        mProcessor.mRenderThread.stop();
    }

    /** Another set of targets for a processor this effect already has. */
    private static final class TargetedEffect extends CameraEffect {
        private TargetedEffect(int targets, @NonNull Executor executor,
                @NonNull SurfaceProcessor processor, @NonNull Consumer<Throwable> errorListener) {
            super(targets, executor, processor, errorListener);
        }
    }

    /**
     * Everything CameraX calls, on the executor the effect was built with - the main thread, which
     * is where this module does all of its work. The render thread is told about outputs through
     * its own thread-safe methods.
     */
    private static final class Processor implements SurfaceProcessor {
        private final FrameRenderThread mRenderThread;
        private final Executor mExecutor;

        /**
         * Which frames the outputs keep, for a time-lapse drawn from a stream of its own, which is
         * an input of the render thread's keyed by this processor. Null for the main pipeline,
         * which draws every frame of the camera's main input.
         */
        @Nullable private final TimeLapse mTimeLapse;

        /** The outputs CameraX has given us and not yet asked for back. Main thread only. */
        private final Set<SurfaceOutput> mOutputs = new LinkedHashSet<>();

        private boolean mReleased;

        private Processor(@NonNull FrameRenderThread renderThread, @NonNull Executor executor,
                @Nullable TimeLapse timeLapse) {
            mRenderThread = renderThread;
            mExecutor = executor;
            mTimeLapse = timeLapse;
        }

        @Override
        public void onInputSurface(@NonNull SurfaceRequest request) throws ProcessingException {
            SurfaceTexture texture = mReleased ? null : mTimeLapse == null
                    ? mRenderThread.getInputSurfaceTexture()
                    : mRenderThread.addInput(this, request.getResolution());
            if (texture == null) {
                // Answered as well as reported: the request is a promise CameraX waits on, and
                // throwing alone would leave it waiting.
                request.willNotProvideSurface();
                throw new ProcessingException();
            }
            if (mTimeLapse == null) {
                mRenderThread.setInputBufferSize(request.getResolution());
            }
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
            FrameRenderThread.TextureTransform transform = (surfaceTextureMatrix, into) ->
                    output.updateTransformMatrix(into, surfaceTextureMatrix);
            if (mTimeLapse != null) {
                mRenderThread.addTimeLapseOutput(output, this, surface, size, transform,
                        mTimeLapse);
                return;
            }
            // The frame is the size of the output, because the matrix above has already rotated
            // and cropped the camera's buffer to fill it. So the scale type decides nothing here -
            // which is the intent: where the frame sits is CameraX's answer, not ours.
            mRenderThread.addOutput(output, surface, size, size, GlMath.ScaleType.FILL_CENTER,
                    transform);
        }

        /** Closes every output, and lets a time-lapse's stream go. The thread is the effect's. */
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
            if (mTimeLapse != null) {
                mRenderThread.removeInput(this);
            }
        }
    }
}
