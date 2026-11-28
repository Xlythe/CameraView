package com.xlythe.view.camera.v2;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.Location;
import android.os.AsyncTask;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.UiThread;

import com.xlythe.view.camera.DrawnPictures;
import com.xlythe.view.camera.PendingPicture;
import com.xlythe.view.camera.ar.ArTracker;
import com.xlythe.view.camera.gl.FrameRenderThread;
import com.xlythe.view.camera.gl.GlMath;

import java.io.File;
import java.util.List;

import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * The one capture session a camera shared with ARCore runs.
 *
 * <p>ARCore's surfaces and nothing else. The camera writes into them, ARCore copies each frame
 * into the renderer's texture, and the renderer draws the preview from there - so there is no
 * preview surface of the camera's own, and no still output either: a still is the next frame the
 * renderer draws, at the size ARCore tracks at, with whatever the app draws over it.</p>
 *
 * <p>The repeating request is made once, from {@code TEMPLATE_RECORD} as ARCore requires, and
 * then belongs to ARCore. A request of this session's own while ARCore is resumed would interfere
 * with tracking, so settings that would reissue it are not offered while AR runs.</p>
 */
@RequiresApi(24)
final class ArCaptureSession extends SessionImpl {
    /** The module, which owns the ARCore session. */
    private final ArCameraModule mModule;

    /** The ARCore session the surfaces belong to. */
    private final ArTracker mArSession;

    /** The surface the renderer draws the preview into, or null before initialize. */
    @Nullable
    private Surface mPreviewOutput;

    /** The picture being drawn, or null while none is. */
    @Nullable
    private DrawnFrame mPicture;

    ArCaptureSession(@NonNull ArCameraModule module, @NonNull ArTracker arSession) {
        super(module);
        mModule = module;
        mArSession = arSession;
    }

    /**
     * Points the renderer at the view and at ARCore's frames.
     *
     * <p>The preview is drawn at the size ARCore tracks at and transformed onto the view as a
     * camera's own preview is, with the frame drawn through the matrix a camera would have
     * reported. So the view transform that orients a plain preview orients this one.</p>
     */
    @MainThread
    @Override
    public void initialize(@NonNull StreamConfigurationMap map) throws CameraAccessException {
        FrameRenderThread effect = mModule.getEffect();
        SurfaceTexture preview = mModule.getSurfaceTexture();
        if (effect == null || preview == null) {
            throw new IllegalStateException("AR needs GL and a preview to draw into");
        }
        Size size = mArSession.getTextureSize();
        preview.setDefaultBufferSize(size.getWidth(), size.getHeight());
        mPreviewOutput = new Surface(preview);
        effect.setInputBufferSize(size);
        effect.addOutput(this, mPreviewOutput, size, size, GlMath.ScaleType.FILL_CENTER,
                (surfaceTextureMatrix, into) ->
                        System.arraycopy(surfaceTextureMatrix, 0, into, 0, into.length));
        transformPreview(size.getWidth(), size.getHeight());
    }

    @Override
    public void onLayoutChanged() throws CameraAccessException {
        Size size = mArSession.getTextureSize();
        transformPreview(size.getWidth(), size.getHeight());
    }

    @NonNull
    @Override
    public List<Surface> getSurfaces() {
        List<Surface> surfaces = super.getSurfaces();
        surfaces.addAll(mArSession.getSurfaces());
        return surfaces;
    }

    /**
     * Starts the camera writing into ARCore's surfaces. ARCore resumes once the session is
     * active, and from then on the requests are its own.
     */
    @Override
    public void onAvailable(@NonNull CameraDevice device, @NonNull CameraCaptureSession session)
            throws CameraAccessException {
        if (mArSession.isResumed()) {
            return;
        }
        CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        for (Surface surface : mArSession.getSurfaces()) {
            builder.addTarget(surface);
        }
        session.setRepeatingRequest(builder.build(), null, getHandler());
    }

    /** Nothing to reissue: ARCore owns the repeating request while it tracks. */
    @Override
    public void onInvalidate(@NonNull CameraDevice device, @NonNull CameraCaptureSession session) {}

    /**
     * Draws the next frame into a picture and writes it, through the renderer like the preview.
     *
     * <p>Upright and mirrored as it is shown, and tagged as such, as every drawn picture is.</p>
     */
    @MainThread
    void takePicture(@NonNull File file) {
        FrameRenderThread effect = mModule.getEffect();
        if (effect == null) {
            onImageFailed();
            return;
        }
        DrawnFrame previous = mPicture;
        if (previous != null) {
            Log.w(TAG, "Giving up an AR picture for the one asked for after it");
            previous.abandon();
        }
        PendingPicture picture = new PendingPicture(file, new PendingPicture.Callback() {
            @Override
            public void onSaved(@NonNull File saved) {
                showImageConfirmation(saved);
            }

            @Override
            public void onFailed() {
                onImageFailed();
            }
        });
        int orientation = getRelativeCameraOrientation();
        Size size = mArSession.getTextureSize();
        boolean quarterTurned = (orientation / 90) % 2 != 0;
        int width = quarterTurned ? size.getHeight() : size.getWidth();
        int height = quarterTurned ? size.getWidth() : size.getHeight();
        SafeCloseImageReader reader =
                new SafeCloseImageReader(width, height, PixelFormat.RGBA_8888, 2 /* maxImages */);
        DrawnFrame drawn = new DrawnFrame(effect, reader, file, picture);
        boolean armed = effect.drawNextFrameInto(reader.getSurface(), new Size(width, height),
                GlMath.ScaleType.FILL_CENTER,
                (surfaceTextureMatrix, into) -> System.arraycopy(
                        GlMath.uprightMatrix(surfaceTextureMatrix, orientation, false),
                        0, into, 0, into.length),
                () -> getHandler().post(this::onShutter));
        if (!armed) {
            reader.close();
            picture.fail();
            return;
        }
        mPicture = drawn;
        reader.setOnImageAvailableListener(drawn::onImageAvailable, getHandler());
    }

    @Override
    public void close() {
        super.close();
        DrawnFrame picture = mPicture;
        if (picture != null) {
            picture.abandon();
        }
        FrameRenderThread effect = mModule.getEffect();
        if (effect != null) {
            effect.removeOutput(this);
        }
        if (mPreviewOutput != null) {
            mPreviewOutput.release();
            mPreviewOutput = null;
        }
    }

    /** A picture on its way from the renderer to disk. */
    private final class DrawnFrame {
        /** The renderer drawing it, told to stop if the picture is given up. */
        private final FrameRenderThread mEffect;

        /** What the renderer draws into, closed once the drawing is read back. */
        private final SafeCloseImageReader mReader;

        /** Where the JPEG goes. */
        private final File mFile;

        /** The shutter press, told once the JPEG is written or lost. */
        private final PendingPicture mPending;

        /** Whether the picture has gone to its writer or been given up. */
        private boolean mFinished;

        DrawnFrame(@NonNull FrameRenderThread effect, @NonNull SafeCloseImageReader reader,
                @NonNull File file, @NonNull PendingPicture pending) {
            mEffect = effect;
            mReader = reader;
            mFile = file;
            mPending = pending;
        }

        @MainThread
        void onImageAvailable(@NonNull SafeCloseImageReader reader) {
            SafeCloseImageReader.Frame frame;
            try {
                frame = reader.acquireLatestImage();
            } catch (IllegalStateException error) {
                Log.e(TAG, "Failed to acquire an AR picture", error);
                abandon();
                return;
            }
            if (frame == null) {
                return;
            }
            Bitmap bitmap;
            try {
                bitmap = DrawnPictures.toBitmap(frame.getImage());
            } catch (RuntimeException | OutOfMemoryError error) {
                Log.e(TAG, "Failed to read back an AR picture", error);
                abandon();
                return;
            } finally {
                frame.close();
                reader.close();
            }
            if (mFinished) {
                bitmap.recycle();
                return;
            }
            mFinished = true;
            if (mPicture == this) {
                mPicture = null;
            }
            File file = mFile;
            PendingPicture pending = mPending;
            Location location = mModule.getLocation();
            new PictureSession.DrawnImageSaver(getContext(), location, bitmap, file,
                    getPictureQuality(), exifCaptureParameters()) {
                @UiThread
                @Override
                protected void onPostExecute(Boolean saved) {
                    pending.onWritten(file, Boolean.TRUE.equals(saved));
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }

        /** Reports the picture lost, and lets go of what it was holding. */
        void abandon() {
            if (mFinished) {
                return;
            }
            mFinished = true;
            if (mPicture == this) {
                mPicture = null;
            }
            mEffect.cancelStill();
            mReader.close();
            mPending.onWritten(mFile, false);
        }
    }
}
