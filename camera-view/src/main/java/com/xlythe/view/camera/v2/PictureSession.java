package com.xlythe.view.camera.v2;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.Location;
import android.media.Image;
import android.os.AsyncTask;
import android.os.Build;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import com.xlythe.view.camera.Burst;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.DngWriter;
import com.xlythe.view.camera.Exif;
import com.xlythe.view.camera.ExifCaptureParameters;
import com.xlythe.view.camera.ExifNoRoomException;
import com.xlythe.view.camera.PendingPicture;
import com.xlythe.view.camera.RawCapturePairer;
import com.xlythe.view.camera.gl.FrameRenderThread;
import com.xlythe.view.camera.gl.GlMath;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;
import androidx.exifinterface.media.ExifInterface;

import static com.xlythe.view.camera.ICameraModule.DEBUG;
import static com.xlythe.view.camera.ICameraModule.TAG;

@RequiresApi(21)
class PictureSession extends PreviewSession {
    /**
     * Supports {@link ImageFormat#JPEG} and {@link ImageFormat#YUV_420_888}. You can support
     * larger sizes with YUV_420_888, at the cost of speed.
     */
    private static final int IMAGE_FORMAT_DEFAULT = ImageFormat.JPEG;
    private static final int IMAGE_FORMAT_LOW = IMAGE_FORMAT_DEFAULT;
    private static final int IMAGE_FORMAT_MEDIUM = IMAGE_FORMAT_DEFAULT;
    private static final int IMAGE_FORMAT_HIGH = IMAGE_FORMAT_DEFAULT;

    /**
     * Note: Historically, we used YUV_420_888 for MAX. JPEG does not play well with other surfaces
     * at high resolutions. However, we found a solution where JPEG does work (up to its max
     * supported resolution); limit the the preview to 1080p. The preview has other reasons for
     * being restricted to 1080p (it crashes the Nexus 5X for one), but this adds yet another reason
     * we mustn't let the preview go any higher.
     */
    private static final int IMAGE_FORMAT_MAX = IMAGE_FORMAT_DEFAULT;

    /** How long a flash picture waits on metering before it is taken regardless. */
    @VisibleForTesting
    static final long METERING_TIMEOUT_MILLIS = 3_000;

    /** Frames after the trigger in which metering has to start, for a camera that skips it. */
    private static final int METERING_START_FRAMES = 3;

    /**
     * How many burst pictures of the camera's own frames are asked for at once: enough that the
     * next is always queued while the last is encoded, and few enough that little is still to
     * come once the burst is stopped. A drawn burst asks for one at a time, since the renderer
     * draws one still at a time and each drawing is a full-size bitmap until it is written.
     */
    @VisibleForTesting
    static final int BURST_PICTURES_IN_FLIGHT = 3;

    /** The JPEG (or JPEG_R) output, which every format but RAW writes to. */
    private final PictureSurface mPictureSurface;

    /** The RAW_SENSOR output, configured only for a RAW format on a camera that has one. */
    private final RawPictureSurface mRawSurface;

    /** A flash picture waiting on auto-exposure to meter, or null. */
    @Nullable
    private volatile Metering mMetering;

    /**
     * The renderer input the camera writes a still into, or null when nothing is drawing.
     *
     * <p>Made once in {@link #initialize} and held, because the session is configured around it
     * and the request has to target the same one.</p>
     */
    @Nullable
    private Surface mEffectStillInput;

    /**
     * The drawn picture on its way to disk, or null when none is.
     *
     * <p>Holds the reader the renderer draws into, because nothing else does. The renderer keeps
     * only the reader's surface, and an ImageReader that nothing references is collected and its
     * surface released - after which the picture has nowhere to be drawn, and the capture never
     * reports. Volatile because a picture can be asked for from any thread and is read back on
     * the camera's handler.</p>
     */
    @Nullable
    private volatile DrawnPicture mDrawnPicture;

    /** The burst being taken, or null when none is. */
    @Nullable
    private BurstCapture mBurstCapture;

    PictureSession(Camera2Module camera2Module) {
        super(camera2Module);
        mPictureSurface = new PictureSurface(camera2Module, getPreviewSurface());
        mRawSurface = new RawPictureSurface(camera2Module);
    }

    /** Whether a format writes a DNG, with or without a JPEG beside it. */
    static boolean writesRaw(@NonNull CameraView.PictureFormat format) {
        return format == CameraView.PictureFormat.RAW
                || format == CameraView.PictureFormat.RAW_JPEG;
    }

    @Override
    public void initialize(@NonNull StreamConfigurationMap map) throws CameraAccessException {
        super.initialize(map);
        // Both the still size and anything a subclass adds are chosen from the preview's, so the
        // hook goes between the two: a recording session sizes its video here and the still is
        // then capped to it.
        onPreviewInitialized(map);
        if (hasStillCapture()) {
            // Asked of the camera again rather than trusted from the request, because a lens
            // change can leave a format accepted for the back camera in front of a front camera
            // that cannot write it. A drawn picture is a JPEG, so no RAW output is configured
            // while frames are drawn.
            CameraView.PictureFormat format = getPictureFormat();
            boolean drawing = getModule().getEffect() != null;
            boolean raw = !drawing && allowsRaw() && writesRaw(format)
                    && isPictureFormatSupported(format);
            // One request reads all of its outputs in one pixel mode, and the RAW output is
            // sized for the binned one, so a session that writes a DNG keeps its JPEG binned. A
            // drawn picture keeps it binned too, since the renderer reads a binned stream.
            mPictureSurface.initialize(map, maxPictureSize(), allowsUltraHdr(),
                    allowsMaximumResolution() && !raw && !drawing);
            // Once the still has a size, and once only: asking the renderer for an input makes
            // one, so asking twice leaves the session configured around a surface that nothing
            // is drawing into - which is a capture that configures and then never arrives.
            mEffectStillInput = createEffectStillInput();
            if (raw) {
                mRawSurface.initialize(map);
            }
        }
    }

    /**
     * Where the camera writes a still while an effect is running, or null when none is.
     *
     * <p>A second input to the renderer rather than the preview stream it is already drawing,
     * because a camera gives its full resolution only to a request aimed at a high-resolution
     * target. Taking the photograph off the preview would quietly turn a twelve megapixel
     * camera into a two megapixel one.</p>
     */
    @Nullable
    private Surface createEffectStillInput() {
        FrameRenderThread effect = getModule().getEffect();
        if (effect == null || !mPictureSurface.hasSize()) {
            return null;
        }
        return effect.createStillInput(
                new Size(mPictureSurface.getWidth(), mPictureSurface.getHeight()));
    }

    /** Lets a subclass add its own outputs once the preview has a size and before the still does. */
    protected void onPreviewInitialized(@NonNull StreamConfigurationMap map)
            throws CameraAccessException {}

    /**
     * Whether this session carries a still-capture output at all.
     *
     * <p>A session that cannot take a picture does not pay for the reader or the stream slot.</p>
     */
    protected boolean hasStillCapture() {
        return true;
    }

    /**
     * The largest still this session may ask for, or null for the largest the camera offers.
     *
     * <p>Only a recording session needs this: the combination of a preview, a recording and a
     * still is guaranteed by Camera2 at the recording size, not at the sensor's maximum.</p>
     */
    @Nullable
    protected Size maxPictureSize() {
        return null;
    }

    /**
     * Whether the still may be Ultra HDR. Camera2 verifies JPEG_R beside a preview alone, so a
     * session that also feeds an encoder keeps its still as JPEG.
     */
    protected boolean allowsUltraHdr() {
        return true;
    }

    /**
     * Whether the session may carry a RAW output. A camera with the RAW capability guarantees a
     * preview, a full-size JPEG and a full-size RAW together, and nothing with an encoder in it.
     */
    protected boolean allowsRaw() {
        return true;
    }

    /**
     * Whether a MAX still may read the sensor out unbinned, on a camera that offers it. The
     * readout stalls the repeating frames while it happens, which a recording cannot afford.
     */
    protected boolean allowsMaximumResolution() {
        return true;
    }

    @NonNull
    @Override
    public List<Surface> getSurfaces() {
        List<Surface> surfaces = super.getSurfaces();
        if (!hasStillCapture()) {
            return surfaces;
        }
        Surface stillInput = mEffectStillInput;
        if (stillInput != null) {
            // The renderer's own input, at the size the camera would have written the JPEG.
            // The reader is left out: a frame drawn by GL cannot land in a JPEG reader, so the
            // photograph is encoded from what the renderer produced instead.
            surfaces.add(stillInput);
            return surfaces;
        }
        surfaces.add(mPictureSurface.getSurface());
        if (mRawSurface.isInitialized()) {
            surfaces.add(mRawSurface.getSurface());
        }
        return surfaces;
    }

    @Override
    public void close() {
        super.close();
        BurstCapture burst = mBurstCapture;
        if (burst != null) {
            burst.mBurst.end();
        }
        Metering metering = mMetering;
        if (metering != null) {
            mMetering = null;
            getHandler().removeCallbacks(metering);
        }
        mPictureSurface.close();
        mRawSurface.close();
        abandonDrawnPicture();
    }

    /** Whether a flash picture may run auto-exposure's metering, with its pre-flash, first. */
    protected boolean metersForFlash() {
        return true;
    }

    /**
     * Whether the flash fires for the next picture: always when it is on, and for auto flash
     * when the preview's exposure has said it needs to.
     */
    @VisibleForTesting
    static boolean flashWillFire(
            boolean hasFlash, @NonNull CameraView.Flash flash, @Nullable Integer aeState) {
        if (!hasFlash) {
            return false;
        }
        switch (flash) {
            case ON:
                return true;
            case AUTO:
                return aeState != null && aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED;
            default:
                return false;
        }
    }

    @Override
    void onPreviewCaptured(@NonNull CaptureResult result) {
        super.onPreviewCaptured(result);
        Metering metering = mMetering;
        if (metering != null && metering.mTriggered) {
            metering.onResult(result);
        }
    }

    void takePicture(@NonNull File file, @NonNull CameraDevice device, @NonNull CameraCaptureSession session) {
        if (!hasStillCapture() || mBurstCapture != null) {
            Log.w(TAG, "This camera cannot take a picture in its current configuration");
            onImageFailed();
            return;
        }
        if (mMetering != null) {
            Log.w(TAG, "Ignoring a picture asked for while another meters for its flash");
            onImageFailed();
            return;
        }
        CameraView.PictureFormat format = getPictureFormat();
        boolean raw = writesRaw(format);
        FrameRenderThread effect = getModule().getEffect();
        if (raw && (effect != null || !mRawSurface.isInitialized())) {
            // Reached while recording, while frames are drawn, and on a camera that cannot write
            // RAW. A renderer draws a JPEG and nothing else, and a caller who asked for a DNG and
            // quietly got a JPEG would have no way to tell, so this refuses.
            Log.w(TAG, "This session cannot capture " + format);
            onImageFailed();
            return;
        }
        boolean jpeg = format != CameraView.PictureFormat.RAW;
        // RAW writes only the DNG, so it takes the file it was given whatever it is called.
        File dng = jpeg ? DngWriter.dngBeside(file) : file;
        List<File> files = new ArrayList<>(2);
        if (jpeg) {
            files.add(file);
        }
        if (raw) {
            files.add(dng);
        }
        PendingPicture picture = new PendingPicture(file, files, new PendingPicture.Callback() {
            @Override
            public void onSaved(@NonNull File saved) {
                showImageConfirmation(saved, files);
            }

            @Override
            public void onFailed() {
                onImageFailed();
            }
        });
        Targets targets;
        if (effect != null) {
            DrawnPicture drawn = prepareDrawnPicture(effect, file, picture,
                    exifCaptureParameters());
            if (drawn == null) {
                return;
            }
            targets = new Targets(drawn);
        } else {
            if (jpeg) {
                mPictureSurface.initializePicture(file, exifCaptureParameters(), picture);
            }
            if (raw) {
                mRawSurface.initializePicture(dng, picture);
            }
            targets = new Targets(jpeg, raw);
        }
        // The torch takes the flash strategy's place on every request, so nothing fires to meter
        // for.
        if (!isTorchLit() && flashWillFire(hasFlash(), getFlash(), getAeState())) {
            if (metersForFlash()) {
                meterForFlash(device, session, targets);
            } else {
                captureStill(device, session, targets, false /* fromBuffer */, false /* metered */,
                        false /* burst */);
            }
        } else {
            captureStill(device, session, targets, true /* fromBuffer */, false /* metered */,
                    false /* burst */);
        }
    }

    /** Which outputs one shutter press writes to, carried across a wait on metering. */
    private static final class Targets {
        /** Whether the press writes a JPEG. */
        final boolean mJpeg;

        /** Whether the press writes a DNG. */
        final boolean mRaw;

        /** The drawn picture the press writes instead of either, or null for a camera frame. */
        @Nullable
        final DrawnPicture mDrawn;

        Targets(boolean jpeg, boolean raw) {
            mJpeg = jpeg;
            mRaw = raw;
            mDrawn = null;
        }

        Targets(@NonNull DrawnPicture drawn) {
            mJpeg = false;
            mRaw = false;
            mDrawn = drawn;
        }
    }

    /**
     * Sends the still request.
     *
     * @param fromBuffer whether the picture may be a frame the camera already has, taken as the
     *     request was made rather than after it arrived. Never for a flash picture, whose frame
     *     has to be lit by the flash.
     * @param metered whether a precapture sequence ran for this picture, to be ended after it
     * @param burst whether this is a picture of a burst, which neither fires the flash nor plays
     *     the click
     */
    private void captureStill(@NonNull CameraDevice device, @NonNull CameraCaptureSession session,
            @NonNull Targets targets, boolean fromBuffer, boolean metered, boolean burst) {
        try {
            CaptureRequest.Builder builder = newStillRequest(device, fromBuffer);
            if (burst) {
                applyBurstAeMode(builder);
            } else {
                applyStillAeMode(builder);
            }
            if (targets.mJpeg) {
                builder.addTarget(mPictureSurface.getSurface());
            }
            if (targets.mRaw) {
                builder.addTarget(mRawSurface.getSurface());
            }
            DrawnPicture drawn = targets.mDrawn;
            if (drawn != null) {
                builder.addTarget(drawn.mInput);
            }
            // A DNG is written from the capture result as much as from the frame, so only a RAW
            // capture hands the result on.
            CameraCaptureSession.CaptureCallback rawCallback =
                    targets.mRaw ? mRawSurface.captureCallback() : null;
            session.capture(builder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureStarted(@NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request, long timestamp, long frameNumber) {
                    if (burst) {
                        onBurstShutter();
                    } else {
                        onShutter();
                    }
                }

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    if (metered) {
                        endMetering(device, session);
                    }
                    if (rawCallback != null) {
                        rawCallback.onCaptureCompleted(session, request, result);
                    }
                    if (drawn != null) {
                        drawn.onCaptureCompleted(result);
                    }
                }

                @Override
                public void onCaptureFailed(@NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
                    if (rawCallback != null) {
                        rawCallback.onCaptureFailed(session, request, failure);
                    }
                    if (drawn != null) {
                        drawn.onCaptureFailed(failure);
                    }
                }

                @Override
                public void onCaptureBufferLost(@NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request, @NonNull Surface target,
                        long frameNumber) {
                    if (drawn != null && target == drawn.mInput) {
                        Log.e(TAG, "The camera lost the frame a picture was to be drawn from");
                        drawn.abandon();
                    }
                }
            }, getHandler());
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            // Crashes if the Camera is interacted with while still loading
            Log.e(TAG, "Failed to create capture request", e);
            mPictureSurface.abandonPicture();
            mRawSurface.abandonPicture();
            abandonDrawnPicture();
        }
    }

    /**
     * A still request carrying everything a picture shares with every other: focus, exposure
     * compensation, metering, zoom, stabilization, quality, and the sensor mode the still output
     * was configured for. The flash and the targets are left to the caller.
     *
     * @param fromBuffer whether the picture may be a frame the camera already has. See
     *     {@link #captureStill}.
     */
    @NonNull
    private CaptureRequest.Builder newStillRequest(@NonNull CameraDevice device,
            boolean fromBuffer) throws CameraAccessException {
        CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        applyExposureCompensation(builder);
        MeteringRectangle metering = mMeteringRectangle;
        Rect cropRegion = mCropRegion;
        if (Build.VERSION.SDK_INT >= 31 && mPictureSurface.isMaximumResolution()) {
            builder.set(CaptureRequest.SENSOR_PIXEL_MODE,
                    CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
            metering = toMaximumResolution(metering);
            cropRegion = toMaximumResolution(cropRegion);
        }
        if (metering != null) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{metering});
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{metering});
        }
        applyZoom(builder, cropRegion);
        applyStabilization(builder);
        builder.set(CaptureRequest.JPEG_QUALITY, (byte) getPictureQuality());
        if (Build.VERSION.SDK_INT >= 26 && isZeroShutterLagSupported()) {
            // Set either way, since some still templates turn it on themselves. A RAW frame
            // taken from the buffer carries that frame's timestamp, and so does its result,
            // so the two still pair. An unbinned still never comes from the buffer, whose
            // frames are binned.
            builder.set(CaptureRequest.CONTROL_ENABLE_ZSL,
                    fromBuffer && !mPictureSurface.isMaximumResolution());
        }
        return builder;
    }

    /**
     * The auto-exposure mode for a picture of a burst: the preview's, without the flash. A flash
     * cannot recharge between pictures, and metering for it would hold each one up. A lit torch
     * stays lit.
     */
    private void applyBurstAeMode(@NonNull CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
        applyAeMode(builder, false /* withFlashStrategy */);
    }

    /**
     * How a picture of a burst was taken, for its Exif: as any picture is, except that the flash
     * never fires for one.
     */
    @NonNull
    private ExifCaptureParameters burstExifParameters() {
        return exifCaptureParameters().setFlash(CameraView.Flash.OFF, hasFlash());
    }

    /**
     * Takes a burst. Each picture is the one {@link #takePicture} would take with the same
     * settings, written and tagged the same way, except that the flash stays off and the click
     * plays only where it is required.
     *
     * <p>The camera's own frames are asked for a few at a time, another as each arrives. A
     * repeating request would be simpler to start, but its frames cannot be told apart from the
     * ones still in flight once it stops, while each of these carries its own file. While frames
     * are drawn, each picture is drawn and written as a single one is before the next is asked
     * for.</p>
     */
    void startBurst(@NonNull Burst burst, @NonNull CameraDevice device,
            @NonNull CameraCaptureSession session) {
        if (!hasStillCapture() || mBurstCapture != null || mMetering != null
                || mDrawnPicture != null || mPictureSurface.hasPicture()) {
            Log.w(TAG, "This camera cannot take a burst in its current configuration");
            burst.end();
            return;
        }
        FrameRenderThread effect = getModule().getEffect();
        BurstCapture capture = new BurstCapture(burst, device, session, effect);
        mBurstCapture = capture;
        burst.whenFinished(() -> {
            if (mBurstCapture == capture) {
                mBurstCapture = null;
            }
        });
        if (effect == null) {
            mPictureSurface.startBurst(burst, capture::requestNext);
        }
        int inFlight = effect == null ? BURST_PICTURES_IN_FLIGHT : 1;
        for (int i = 0; i < inFlight; i++) {
            capture.requestNext();
        }
    }

    /** A burst on its way, and the camera and session its pictures are asked of. */
    private final class BurstCapture {
        /** Hands out each picture's file, and hears how each turned out. */
        final Burst mBurst;

        /** The camera each request is made for. */
        private final CameraDevice mDevice;

        /** The session each request goes to. */
        private final CameraCaptureSession mSession;

        /** The renderer each picture is drawn through, or null for the camera's own frames. */
        @Nullable
        private final FrameRenderThread mEffect;

        /** Hears of pictures of the camera's own frames that no frame will arrive for. */
        private final CameraCaptureSession.CaptureCallback mFrameCallback =
                new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureStarted(@NonNull CameraCaptureSession session,
                            @NonNull CaptureRequest request, long timestamp, long frameNumber) {
                        onBurstShutter();
                    }

                    @Override
                    public void onCaptureFailed(@NonNull CameraCaptureSession session,
                            @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
                        if (!failure.wasImageCaptured()) {
                            onPictureLost(request);
                        }
                    }

                    @Override
                    public void onCaptureBufferLost(@NonNull CameraCaptureSession session,
                            @NonNull CaptureRequest request, @NonNull Surface target,
                            long frameNumber) {
                        if (mPictureSurface.isSurface(target)) {
                            onPictureLost(request);
                        }
                    }
                };

        BurstCapture(@NonNull Burst burst, @NonNull CameraDevice device,
                @NonNull CameraCaptureSession session, @Nullable FrameRenderThread effect) {
            mBurst = burst;
            mDevice = device;
            mSession = session;
            mEffect = effect;
        }

        /** Asks for the burst's next picture, unless it has all it is going to take. */
        @MainThread
        void requestNext() {
            if (mBurstCapture != this) {
                return;
            }
            File file = mBurst.nextFile();
            if (file == null) {
                return;
            }
            if (mEffect == null) {
                requestFrame(file);
            } else {
                requestDrawing(mEffect, file);
            }
        }

        /**
         * Asks for one of the camera's own frames. The preview is a target too, because the
         * camera serves queued requests ahead of the repeating one and the preview would
         * otherwise freeze for the whole burst. An unbinned still is the exception: the preview
         * cannot be read out in that mode, and the readout holds it up anyway.
         */
        private void requestFrame(@NonNull File file) {
            mPictureSurface.expectBurstPicture(file, burstExifParameters());
            try {
                CaptureRequest.Builder builder = newStillRequest(mDevice, false /* fromBuffer */);
                applyBurstAeMode(builder);
                builder.addTarget(mPictureSurface.getSurface());
                if (!mPictureSurface.isMaximumResolution()) {
                    builder.addTarget(getPreviewSurface().getSurface());
                }
                builder.setTag(file);
                mSession.capture(builder.build(), mFrameCallback, getHandler());
            } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
                Log.e(TAG, "Failed to request a burst picture", e);
                if (mPictureSurface.forgetBurstPicture(file)) {
                    mBurst.onPictureFailed(file);
                }
            }
        }

        /**
         * Asks for a picture drawn as a single one would be, and for the next once it is written.
         * Waiting for the write rather than the drawing keeps one full-size bitmap in memory, not
         * a queue of them, and the pictures in order.
         */
        private void requestDrawing(@NonNull FrameRenderThread effect, @NonNull File file) {
            PendingPicture picture = new PendingPicture(file, new PendingPicture.Callback() {
                @Override
                public void onSaved(@NonNull File saved) {
                    mBurst.onPictureTaken(saved);
                    requestNext();
                }

                @Override
                public void onFailed() {
                    mBurst.onPictureFailed(file);
                }
            });
            DrawnPicture drawn = prepareDrawnPicture(effect, file, picture, burstExifParameters());
            if (drawn == null) {
                return;
            }
            captureStill(mDevice, mSession, new Targets(drawn), false /* fromBuffer */,
                    false /* metered */, true /* burst */);
        }

        /** A picture of the camera's own frames that the camera dropped. */
        private void onPictureLost(@NonNull CaptureRequest request) {
            Object tag = request.getTag();
            if (!(tag instanceof File)) {
                return;
            }
            File file = (File) tag;
            if (mPictureSurface.forgetBurstPicture(file)) {
                Log.w(TAG, "The camera dropped a burst picture");
                mBurst.onPictureFailed(file);
            }
        }
    }

    /**
     * Starts auto-exposure's precapture sequence, which meters the scene with a pre-flash, and
     * takes the picture once it finishes. Without it the flash fires on an exposure chosen for
     * the dark.
     */
    private void meterForFlash(@NonNull CameraDevice device, @NonNull CameraCaptureSession session,
            @NonNull Targets targets) {
        Metering metering = new Metering(device, session, targets);
        try {
            CaptureRequest.Builder builder = newPreviewRequest(device);
            // In the still's auto-exposure mode rather than the preview's, so that the pre-flash
            // fires where low-light boost has the preview without one.
            applyStillAeMode(builder);
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            mMetering = metering;
            getHandler().postDelayed(metering, METERING_TIMEOUT_MILLIS);
            session.capture(builder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    metering.mTriggered = true;
                    metering.onResult(result);
                }

                @Override
                public void onCaptureFailed(@NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
                    Log.w(TAG, "Metering for the flash failed; taking the picture without it");
                    metering.finish();
                }
            }, getHandler());
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.e(TAG, "Failed to meter for the flash", e);
            mMetering = null;
            getHandler().removeCallbacks(metering);
            mPictureSurface.abandonPicture();
            mRawSurface.abandonPicture();
            abandonDrawnPicture();
        }
    }

    /**
     * Ends a precapture sequence once its picture is taken. A camera may hold the exposure it
     * metered for the flash until told otherwise, which would leave the preview stuck on it.
     */
    private void endMetering(@NonNull CameraDevice device, @NonNull CameraCaptureSession session) {
        if (Build.VERSION.SDK_INT < 23) {
            return;
        }
        try {
            CaptureRequest.Builder builder = newPreviewRequest(device);
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL);
            session.capture(builder.build(), null /* callback */, getHandler());
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.w(TAG, "Failed to end metering for the flash", e);
        }
    }

    /**
     * A flash picture waiting on auto-exposure's precapture sequence.
     *
     * <p>Runs as the timeout too: a camera that never finishes metering still takes the
     * picture, late, rather than never.</p>
     */
    private final class Metering implements Runnable {
        /** The camera the picture is taken on once metering finishes. */
        private final CameraDevice mDevice;

        /** The session the picture is taken in once metering finishes. */
        private final CameraCaptureSession mSession;

        /** The outputs the waiting picture writes to. */
        private final Targets mTargets;

        /** Whether the trigger's own frame is back. Results before it predate the sequence. */
        volatile boolean mTriggered;

        /** Whether auto-exposure has been seen metering. */
        private boolean mStarted;

        /** Frames since the trigger in which metering has not started. */
        private int mFramesWaiting;

        Metering(@NonNull CameraDevice device, @NonNull CameraCaptureSession session,
                @NonNull Targets targets) {
            mDevice = device;
            mSession = session;
            mTargets = targets;
        }

        void onResult(@NonNull CaptureResult result) {
            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
            if (aeState == null) {
                // A camera that does not report its exposure cannot be waited on.
                finish();
            } else if (aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                mStarted = true;
            } else if (mStarted || ++mFramesWaiting >= METERING_START_FRAMES) {
                finish();
            }
        }

        @Override
        public void run() {
            if (mMetering == this) {
                Log.w(TAG, "Metering for the flash timed out; taking the picture anyway");
                finish();
            }
        }

        void finish() {
            if (mMetering != this) {
                return;
            }
            mMetering = null;
            getHandler().removeCallbacks(this);
            captureStill(mDevice, mSession, mTargets, false /* fromBuffer */, true /* metered */,
                    false /* burst */);
        }
    }

    /**
     * Arms the renderer to draw the next still, and returns the picture it will be, or null
     * having failed {@code picture} if it cannot.
     *
     * <p>The camera writes one full-size frame into the renderer, the renderer draws it the way
     * it draws every other frame, and what comes out is read back and encoded. So a filter, a
     * mask or a replaced background reaches the photograph and not only the preview.</p>
     *
     * <p>Encoded here rather than by the camera: the JPEG comes from a bitmap this writes, so it
     * does not go through the camera's own encoder, and every tag the camera would have written
     * is filled in from the frame's capture result instead. The resolution is the camera's.</p>
     *
     * <p>Drawn the right way up and mirrored as it is shown, rather than in the sensor's
     * orientation with an Exif tag to turn it as the camera's own JPEG is. A renderer then draws
     * the picture exactly as it draws the preview, and a caption it puts at the top is at the
     * top of the file.</p>
     *
     * @param exifParameters how the picture is being taken, which the file is tagged with
     */
    @Nullable
    private DrawnPicture prepareDrawnPicture(@NonNull FrameRenderThread effect,
            @NonNull File file, @NonNull PendingPicture picture,
            @NonNull ExifCaptureParameters exifParameters) {
        Surface input = mEffectStillInput;
        if (input == null) {
            Log.w(TAG, "The renderer has no still input, so there is nothing to draw into");
            picture.fail();
            return null;
        }
        DrawnPicture previous = mDrawnPicture;
        if (previous != null) {
            // The renderer draws one still at a time, so the newer press takes its place rather
            // than both waiting on a frame only one of them can have.
            Log.w(TAG, "Giving up a drawn picture for the one asked for after it");
            previous.abandon();
        }
        int orientation = getRelativeCameraOrientation();
        boolean mirrored = getModule().isUsingFrontFacingCamera();
        boolean quarterTurned = (orientation / 90) % 2 != 0;
        int width = quarterTurned ? mPictureSurface.getHeight() : mPictureSurface.getWidth();
        int height = quarterTurned ? mPictureSurface.getWidth() : mPictureSurface.getHeight();
        SafeCloseImageReader reader =
                new SafeCloseImageReader(width, height, PixelFormat.RGBA_8888, 2 /* maxImages */);
        boolean armed = effect.drawStillInto(reader.getSurface(), new Size(width, height),
                GlMath.ScaleType.FILL_CENTER,
                (surfaceTextureMatrix, into) -> System.arraycopy(
                        GlMath.uprightMatrix(surfaceTextureMatrix, orientation, mirrored),
                        0, into, 0, into.length),
                () -> {});
        if (!armed) {
            reader.close();
            picture.fail();
            return null;
        }
        DrawnPicture drawn = new DrawnPicture(
                effect, input, reader, file, exifParameters, picture);
        mDrawnPicture = drawn;
        reader.setOnImageAvailableListener(drawn::onImageAvailable, getHandler());
        return drawn;
    }

    /** Gives up the drawn picture on its way, if there is one. */
    private void abandonDrawnPicture() {
        DrawnPicture drawn = mDrawnPicture;
        if (drawn != null) {
            drawn.abandon();
        }
    }

    /**
     * A drawn picture on its way to disk.
     *
     * <p>Written once two things are back, which arrive in either order: the drawing, read back
     * from the reader, and the camera's capture result for the frame it was drawn from, which is
     * where the file's exposure, sensitivity, aperture and focus distance come from.</p>
     */
    private final class DrawnPicture {
        /** The renderer drawing the picture, told to stop waiting if the picture is given up. */
        private final FrameRenderThread mEffect;

        /** The renderer's input the camera writes the frame into. */
        final Surface mInput;

        /** The reader the renderer draws into, closed once the drawing is read back. */
        private final SafeCloseImageReader mReader;

        /** Where the JPEG goes. */
        private final File mFile;

        /** How the picture was taken, described when it was asked for. */
        private final ExifCaptureParameters mExifParameters;

        /** The shutter press, told once the JPEG is written or lost. */
        private final PendingPicture mPicture;

        /** The drawing, once read back, or null until then. */
        @Nullable
        private Bitmap mBitmap;

        /** Whether the camera has reported the frame, with or without its capture result. */
        private boolean mCaptured;

        /** The frame's capture result, or null until it arrives or if the camera lost it. */
        @Nullable
        private TotalCaptureResult mResult;

        /** Whether the picture has gone to its writer or been given up. */
        private boolean mFinished;

        DrawnPicture(@NonNull FrameRenderThread effect, @NonNull Surface input,
                @NonNull SafeCloseImageReader reader, @NonNull File file,
                @NonNull ExifCaptureParameters exifParameters, @NonNull PendingPicture picture) {
            mEffect = effect;
            mInput = input;
            mReader = reader;
            mFile = file;
            mExifParameters = exifParameters;
            mPicture = picture;
        }

        @MainThread
        void onImageAvailable(@NonNull SafeCloseImageReader reader) {
            SafeCloseImageReader.Frame frame;
            try {
                frame = reader.acquireLatestImage();
            } catch (IllegalStateException error) {
                Log.e(TAG, "Failed to acquire a drawn picture", error);
                abandon();
                return;
            }
            if (frame == null) {
                return;
            }
            Bitmap bitmap;
            try {
                bitmap = toBitmap(frame.getImage());
            } catch (RuntimeException | OutOfMemoryError error) {
                Log.e(TAG, "Failed to read back a drawn picture", error);
                abandon();
                return;
            } finally {
                frame.close();
                // One frame is all that was asked for, and the reader holds native buffers.
                reader.close();
            }
            if (mFinished) {
                bitmap.recycle();
                return;
            }
            mBitmap = bitmap;
            saveWhenReady();
        }

        @MainThread
        void onCaptureCompleted(@NonNull TotalCaptureResult result) {
            mResult = result;
            mCaptured = true;
            saveWhenReady();
        }

        @MainThread
        void onCaptureFailed(@NonNull CaptureFailure failure) {
            if (failure.wasImageCaptured()) {
                // The frame is on its way without its result, so the file goes without what only
                // the result could have said.
                mCaptured = true;
                saveWhenReady();
                return;
            }
            Log.e(TAG, "The camera dropped a drawn picture, reason " + failure.getReason());
            abandon();
        }

        /** Hands the picture to its writer once both the drawing and the camera's report are in. */
        @MainThread
        private void saveWhenReady() {
            Bitmap bitmap = mBitmap;
            if (mFinished || bitmap == null || !mCaptured) {
                return;
            }
            mFinished = true;
            mBitmap = null;
            if (mDrawnPicture == this) {
                mDrawnPicture = null;
            }
            TotalCaptureResult result = mResult;
            if (result != null) {
                mExifParameters.setCaptureResult(result);
            }
            File file = mFile;
            PendingPicture picture = mPicture;
            // Read now rather than on the writer's thread, because the camera can close before
            // the writer runs and the fix it was following goes with it.
            Location location = getModule().getLocation();
            new DrawnImageSaver(getContext(), location, bitmap, file, getPictureQuality(),
                    mExifParameters) {
                @UiThread
                @Override
                protected void onPostExecute(Boolean saved) {
                    picture.onWritten(file, Boolean.TRUE.equals(saved));
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }

        /** Reports the picture lost, and lets go of what it was holding. */
        void abandon() {
            if (mFinished) {
                return;
            }
            mFinished = true;
            if (mDrawnPicture == this) {
                mDrawnPicture = null;
            }
            mEffect.cancelStill();
            mReader.close();
            Bitmap bitmap = mBitmap;
            mBitmap = null;
            if (bitmap != null) {
                bitmap.recycle();
            }
            mPicture.onWritten(mFile, false);
        }
    }

    /** The drawn frame as a bitmap, which is RGBA and so needs no conversion beyond the stride. */
    @NonNull
    private static Bitmap toBitmap(@NonNull Image drawn) {
        Image.Plane plane = drawn.getPlanes()[0];
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        // The reader pads each row out to its stride, so the bitmap is made wide enough to hold
        // the padding and then cropped - reading it as if it were packed shears the picture.
        int padded = rowStride / pixelStride;
        Bitmap full = Bitmap.createBitmap(padded, drawn.getHeight(), Bitmap.Config.ARGB_8888);
        full.copyPixelsFromBuffer(plane.getBuffer());
        if (padded == drawn.getWidth()) {
            return full;
        }
        Bitmap cropped = Bitmap.createBitmap(full, 0, 0, drawn.getWidth(), drawn.getHeight());
        full.recycle();
        return cropped;
    }

    /**
     * Writes a drawn picture, and tags it as {@link ImageSaver} tags a camera frame.
     *
     * <p>Separate from {@link ImageSaver} because what it is given is already pixels, already the
     * right way up and mirrored as shown, rather than a camera frame. So the orientation it
     * records is normal, where a camera frame's records the turn and the mirror a viewer still
     * has to apply.</p>
     */
    private static class DrawnImageSaver extends AsyncTask<Void, Void, Boolean> {
        /** The application, whose name and version the picture records as its software. */
        private final Context mContext;

        /** Where the picture was taken, or null to leave it untagged. */
        @Nullable
        private final Location mLocation;

        /** The drawn picture, recycled once written. */
        private final Bitmap mBitmap;

        /** Where the JPEG goes. */
        private final File mFile;

        /** The JPEG quality, from 0 to 100. */
        private final int mQuality;

        /** How the picture was taken, including what the camera reported for its frame. */
        private final ExifCaptureParameters mExifParameters;

        DrawnImageSaver(@NonNull Context context, @Nullable Location location,
                @NonNull Bitmap bitmap, @NonNull File file, int quality,
                @NonNull ExifCaptureParameters exifParameters) {
            mContext = context.getApplicationContext();
            mLocation = location;
            mBitmap = bitmap;
            mFile = file;
            mQuality = quality;
            mExifParameters = exifParameters;
        }

        /** @return true only when the picture reached disk, so a failure is never reported as one. */
        @WorkerThread
        @Override
        protected Boolean doInBackground(Void... params) {
            try (FileOutputStream output = new FileOutputStream(mFile)) {
                if (!mBitmap.compress(Bitmap.CompressFormat.JPEG, mQuality, output)) {
                    Log.e(TAG, "Failed to compress a drawn picture");
                    return false;
                }
            } catch (IOException | RuntimeException error) {
                Log.e(TAG, "Failed to write a drawn picture", error);
                if (mFile.exists() && !mFile.delete()) {
                    Log.w(TAG, "Failed to clean up a partially written picture");
                }
                return false;
            } finally {
                mBitmap.recycle();
            }
            try {
                Exif exif = new Exif(mFile);
                exif.attachTimestamp();
                exif.attachCaptureDevice(mContext);
                exif.attachThumbnail();
                mExifParameters.applyTo(exif);
                exif.setAttribute(ExifInterface.TAG_ORIENTATION,
                        Integer.toString(ExifInterface.ORIENTATION_NORMAL));
                if (mLocation != null) {
                    exif.attachLocation(mLocation);
                }
                exif.save();
            } catch (IOException error) {
                Log.e(TAG, "Failed to update EXIF on a drawn picture", error);
            }
            return true;
        }
    }

    /**
     * A metering region moved onto the unbinned sensor, or null when it cannot be: a request in
     * maximum resolution mode reads its regions against the larger array.
     */
    @RequiresApi(31)
    @Nullable
    private MeteringRectangle toMaximumResolution(@Nullable MeteringRectangle metering) {
        if (metering == null) {
            return null;
        }
        Rect region = toMaximumResolution(metering.getRect());
        return region == null ? null
                : new MeteringRectangle(region, metering.getMeteringWeight());
    }

    /**
     * Package-private, and so is the conversion below it, so that the planes-to-NV21 arithmetic
     * can be asserted directly. It is the kind of code that fails by tinting or striping an
     * image rather than by throwing, which no end-to-end assertion notices.
     */
    static class ImageSaver extends AsyncTask<Void, Void, Boolean> {
        // The application, whose name and version the picture records as its software
        private final Context mContext;

        // Where the picture was taken, or null to leave it untagged
        @Nullable
        private final Location mLocation;

        // The frame that was captured, closed as soon as its bytes are copied out, or null when
        // they were copied out before the saver was made
        @Nullable
        private final SafeCloseImageReader.Frame mFrame;

        // The encoded picture, when it was copied out of its frame up front, or null
        @Nullable
        private final byte[] mBytes;

        // The orientation of the image
        private final int mOrientation;

        // If true, the picture taken is reversed and needs to be flipped.
        // Typical with front facing cameras.
        private final boolean mIsReversed;

        // The file to save the image to
        private final File mFile;

        // How the picture was taken, for the tags the camera's JPEG leaves out
        private final ExifCaptureParameters mExifParameters;

        ImageSaver(Context context, @Nullable Location location,
                SafeCloseImageReader.Frame frame, int orientation, boolean reversed, File file,
                ExifCaptureParameters exifParameters) {
            mContext = context.getApplicationContext();
            mLocation = location;
            mFrame = frame;
            mBytes = null;
            mOrientation = orientation;
            mIsReversed = reversed;
            mFile = file;
            mExifParameters = exifParameters;
        }

        /**
         * Saves a picture already copied out of its frame. A burst copies each one out as it
         * arrives, so the reader never runs out of frames to hand the camera while saves queue.
         */
        ImageSaver(Context context, @Nullable Location location, @NonNull byte[] bytes,
                int orientation, boolean reversed, File file,
                ExifCaptureParameters exifParameters) {
            mContext = context.getApplicationContext();
            mLocation = location;
            mFrame = null;
            mBytes = bytes;
            mOrientation = orientation;
            mIsReversed = reversed;
            mFile = file;
            mExifParameters = exifParameters;
        }

        /** @return true only when the picture reached disk, so a failure is never reported as one. */
        @WorkerThread
        @Override
        protected Boolean doInBackground(Void... params) {
            // Finally, we save the file to disk
            byte[] bytes = mBytes;
            SafeCloseImageReader.Frame frame = mFrame;
            if (frame != null) {
                try {
                    bytes = toByteArray(frame.getImage());
                } finally {
                    frame.close();
                }
            }
            if (bytes == null) {
                Log.e(TAG, "Failed to get bytes from image");
                return false;
            }
            try (FileOutputStream output = new FileOutputStream(mFile)) {
                output.write(bytes);
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Failed to write the file", e);
                // Leave no truncated file behind for a caller that is told the capture failed.
                if (mFile.exists() && !mFile.delete()) {
                    Log.w(TAG, "Failed to clean up a partially written picture");
                }
                return false;
            }

            try {
                Exif exif = new Exif(mFile);
                exif.attachTimestamp();
                exif.attachCaptureDevice(mContext);
                exif.attachThumbnail();
                mExifParameters.applyTo(exif);
                exif.rotate(mOrientation);
                if (mIsReversed) {
                    exif.flipHorizontally();
                }
                if (mLocation != null) {
                    exif.attachLocation(mLocation);
                }
                exif.save();
            } catch (ExifNoRoomException e) {
                // The picture keeps everything else, and is never failed over its metadata.
                Log.w(TAG, "Saved the picture without some of its Exif tags", e);
            } catch (IOException e) {
                Log.e(TAG, "Failed to update EXIF", e);
            }

            return true;
        }

        @Nullable
        private static byte[] toByteArray(Image image) {
            byte[] data = null;
            if (image.getFormat() == ImageFormat.JPEG
                    || (Build.VERSION.SDK_INT >= 34 && image.getFormat() == ImageFormat.JPEG_R)) {
                Image.Plane[] planes = image.getPlanes();
                ByteBuffer buffer = planes[0].getBuffer();
                data = new byte[buffer.capacity()];
                buffer.get(data);
                return data;
            } else if (image.getFormat() == ImageFormat.YUV_420_888) {
                data = NV21toJPEG(
                        YUV_420_888toNV21(image),
                        image.getWidth(), image.getHeight());
            } else {
                Log.w(TAG, "Unrecognized image format: " + image.getFormat());
            }
            return data;
        }

        static byte[] YUV_420_888toNV21(Image image) {
            Image.Plane yPlane = image.getPlanes()[0];
            Image.Plane uPlane = image.getPlanes()[1];
            Image.Plane vPlane = image.getPlanes()[2];

            ByteBuffer yBuffer = yPlane.getBuffer();
            ByteBuffer uBuffer = uPlane.getBuffer();
            ByteBuffer vBuffer = vPlane.getBuffer();

            int ySize = yBuffer.remaining();
            int uSize = uBuffer.remaining();
            int vSize = vBuffer.remaining();

            if (DEBUG) {
                Log.d(TAG, String.format("Image{width=%d, height=%d}",
                        image.getWidth(), image.getHeight()));
                Log.d(TAG, String.format("yPlane{size=%d, pixelStride=%d, rowStride=%d}",
                        ySize, yPlane.getPixelStride(), yPlane.getRowStride()));
                Log.d(TAG, String.format("uPlane{size=%d, pixelStride=%d, rowStride=%d}",
                        uSize, uPlane.getPixelStride(), uPlane.getRowStride()));
                Log.d(TAG, String.format("vPlane{size=%d, pixelStride=%d, rowStride=%d}",
                        vSize, vPlane.getPixelStride(), vPlane.getRowStride()));
            }

            int position = 0;
            byte[] nv21 = new byte[ySize + (image.getWidth() * image.getHeight() / 2)];

            // Add the full y buffer to the array. If rowStride > 1, some padding may be skipped.
            for (int row = 0; row < image.getHeight(); row++) {
                yBuffer.get(nv21, position, image.getWidth());
                position += image.getWidth();
                yBuffer.position(Math.min(ySize, yBuffer.position() - image.getWidth() + yPlane.getRowStride()));
            }

            int chromaHeight = image.getHeight() / 2;
            int chromaWidth = image.getWidth() / 2;
            int chromaGap = uPlane.getRowStride() - (chromaWidth * uPlane.getPixelStride());

            if (DEBUG) {
                Log.d(TAG, String.format("chromaHeight=%d", chromaHeight));
                Log.d(TAG, String.format("chromaWidth=%d", chromaWidth));
                Log.d(TAG, String.format("chromaGap=%d", chromaGap));
            }

            // Interleave the u and v frames, filling up the rest of the buffer
            for (int row = 0; row < chromaHeight; row++) {
                for (int col = 0; col < chromaWidth; col++) {
                    vBuffer.get(nv21, position++, 1);
                    uBuffer.get(nv21, position++, 1);
                    vBuffer.position(Math.min(vSize, vBuffer.position() - 1 + vPlane.getPixelStride()));
                    uBuffer.position(Math.min(uSize, uBuffer.position() - 1 + uPlane.getPixelStride()));
                }
                vBuffer.position(Math.min(vSize, vBuffer.position() + chromaGap));
                uBuffer.position(Math.min(uSize, uBuffer.position() + chromaGap));
            }

            if (DEBUG) {
                Log.d(TAG, String.format("nv21{size=%d, position=%d}", nv21.length, position));
            }

            return nv21;
        }

        private static byte[] NV21toJPEG(byte[] nv21, int width, int height) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            yuv.compressToJpeg(new Rect(0, 0, width, height), 100, out);
            return out.toByteArray();
        }
    }

    private static final class PictureSurface extends CameraSurface {
        /**
         * Frames the reader holds at once. A burst gives each back as soon as it is copied, so a
         * second is only there to cover a moment where the main thread is late to collect one.
         */
        private static final int MAX_IMAGES = 2;

        private List<Size> getSizes(StreamConfigurationMap map) {
            Size[] sizes = map.getOutputSizes(mImageFormat);

            // Special case for high resolution images (assuming, of course, quality was set to high)
            if (Build.VERSION.SDK_INT >= 23) {
                Size[] highResSizes = map.getHighResolutionOutputSizes(mImageFormat);
                if (highResSizes != null) {
                    sizes = concat(sizes, highResSizes);
                }
            }

            return Arrays.asList(sizes);
        }

        /**
         * The JPEG sizes a MAX still gains from reading the sensor out unbinned. Empty for any
         * other quality or format: the guaranteed unbinned combination is a JPEG beside a
         * preview, so an Ultra HDR still stays binned.
         */
        private List<Size> getMaximumResolutionSizes(List<Size> binned) {
            List<Size> unbinned = new ArrayList<>();
            if (Build.VERSION.SDK_INT < 31 || getQuality() != CameraView.Quality.MAX
                    || mImageFormat != ImageFormat.JPEG) {
                return unbinned;
            }
            StreamConfigurationMap map = mCameraView.getMaximumResolutionMap();
            if (map == null) {
                return unbinned;
            }
            for (Size[] group : new Size[][] {
                    map.getOutputSizes(ImageFormat.JPEG),
                    map.getHighResolutionOutputSizes(ImageFormat.JPEG)}) {
                if (group == null) {
                    continue;
                }
                for (Size size : group) {
                    // A size both maps list is configured for the binned mode, so it is never a
                    // reason to switch.
                    if (!binned.contains(size) && !unbinned.contains(size)) {
                        unbinned.add(size);
                    }
                }
            }
            return unbinned;
        }

        private final SafeCloseImageReader.OnImageAvailableListener mOnImageAvailableListener =
                new SafeCloseImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(@NonNull SafeCloseImageReader reader) {
                Burst burst = mBurst;
                if (burst != null) {
                    onBurstImageAvailable(reader, burst);
                    return;
                }
                try {
                    if (mFile != null) {
                        SafeCloseImageReader.Frame frame = reader.acquireLatestImage();
                        if (frame == null) {
                            Log.w(TAG, "OnImageAvailable called but no image available");
                            abandonPicture();
                            return;
                        }
                        final File file = mFile;
                        final PendingPicture picture = mPicture;
                        new ImageSaver(
                                mCameraView.getContext(),
                                getLocation(),
                                frame,
                                mCameraView.getRelativeCameraOrientation(),
                                isUsingFrontFacingCamera(),
                                mFile,
                                mExifParameters) {
                            @UiThread
                            @Override
                            protected void onPostExecute(Boolean saved) {
                                picture.onWritten(file, Boolean.TRUE.equals(saved));
                            }
                        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                        mFile = null;
                        mPicture = null;
                    } else {
                        Log.w(TAG, "OnImageAvailable called but no file to write to");
                        onImageFailed();
                    }
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Failed to save image", e);
                    abandonPicture();
                }
            }
        };

        private final CameraSurface mPreviewSurface;
        private SafeCloseImageReader mImageReader;
        private File mFile;

        /** The shutter press {@link #mFile} belongs to, told when it is written or lost. */
        @Nullable
        private PendingPicture mPicture;

        /** How the picture for {@link #mFile} is being taken, described when it was asked for. */
        private ExifCaptureParameters mExifParameters = new ExifCaptureParameters();

        /** The burst whose frames this reader collects, or null when none is running. */
        @Nullable
        private Burst mBurst;

        /** The burst pictures the camera still owes, in the order they were asked for. */
        private final Deque<OwedPicture> mOwedPictures = new ArrayDeque<>();

        /** Asks the camera for another burst picture, run as each one arrives. */
        @Nullable
        private Runnable mOnBurstFrame;

        /** What {@link #mImageReader} was made for: JPEG, or JPEG_R for Ultra HDR. */
        private int mImageFormat = IMAGE_FORMAT_DEFAULT;

        /**
         * Whether {@link #mImageReader} has a size only the unbinned sensor offers, so a capture
         * into it has to ask for maximum resolution mode. See {@link PictureSession#takePicture}.
         */
        private boolean mMaximumResolution;

        PictureSurface(Camera2Module camera2Module, CameraSurface previewSurface) {
            super(camera2Module);
            mPreviewSurface = previewSurface;
        }

        @Override
        void initialize(StreamConfigurationMap map) {
            initialize(map, null /* maxSize */, true /* ultraHdrAllowed */,
                    true /* maximumResolutionAllowed */);
        }

        void initialize(StreamConfigurationMap map, @Nullable Size maxSize,
                boolean ultraHdrAllowed, boolean maximumResolutionAllowed) {
            if (DEBUG) Log.d(TAG, "Initializing PictureSession");
            mImageFormat = chooseImageFormat(ultraHdrAllowed);
            List<Size> sizes = getSizes(map);
            List<Size> unbinned = maximumResolutionAllowed
                    ? getMaximumResolutionSizes(sizes) : new ArrayList<>();
            List<Size> choices = new ArrayList<>(sizes);
            choices.addAll(unbinned);
            super.initialize(chooseSize(within(choices, maxSize), mPreviewSurface.mSize));
            mMaximumResolution = unbinned.contains(mSize);
            if (mImageReader != null) {
                // Initialized again - a session can be, when the camera is reconfigured - and an
                // ImageReader holds native buffers the collector cannot reclaim, so the one being
                // replaced has to be handed back rather than dropped.
                mImageReader.close();
            }
            // A frame left the camera for every one replaced, so a burst running on the old reader
            // will never see the rest of its pictures.
            abandonBurstPictures();
            mImageReader = new SafeCloseImageReader(
                    getWidth(), getHeight(), mImageFormat, MAX_IMAGES);
            mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mCameraView.getHandler());
        }

        /**
         * JPEG_R where Ultra HDR is asked for and this camera writes it. Asked again each time,
         * because a lens or colour space that changed since the request may not support it.
         */
        private int chooseImageFormat(boolean ultraHdrAllowed) {
            if (ultraHdrAllowed && Build.VERSION.SDK_INT >= 34
                    && mCameraView.getPictureFormat() == CameraView.PictureFormat.ULTRA_HDR
                    && mCameraView.isPictureFormatSupported(CameraView.PictureFormat.ULTRA_HDR)) {
                return ImageFormat.JPEG_R;
            }
            return getImageFormat(getQuality());
        }

        void initializePicture(File file, ExifCaptureParameters exifParameters,
                @NonNull PendingPicture picture) {
            mFile = file;
            mExifParameters = exifParameters;
            mPicture = picture;
            if (mFile.exists()) {
                Log.w(TAG, "File already exists. Deleting.");
                if (!mFile.delete()) {
                    Log.w(TAG, "Failed to delete existing file.");
                }
            }
        }

        /** Reports the outstanding picture lost, if there is one. */
        void abandonPicture() {
            File file = mFile;
            PendingPicture picture = mPicture;
            mFile = null;
            mPicture = null;
            if (file != null && picture != null) {
                picture.onWritten(file, false);
            }
        }

        /** Whether a single picture is waiting on its frame. */
        boolean hasPicture() {
            return mFile != null;
        }

        /** Collects the frames of a burst until it finishes, asking for more as each arrives. */
        void startBurst(@NonNull Burst burst, @NonNull Runnable onFrame) {
            mBurst = burst;
            mOnBurstFrame = onFrame;
            burst.whenFinished(() -> {
                if (mBurst == burst) {
                    mBurst = null;
                    mOnBurstFrame = null;
                    mOwedPictures.clear();
                }
            });
        }

        /**
         * Notes where the next frame of the burst goes and how it was asked for. Frames arrive in
         * the order they were asked for.
         */
        void expectBurstPicture(@NonNull File file, @NonNull ExifCaptureParameters exifParameters) {
            mOwedPictures.add(new OwedPicture(file, exifParameters));
        }

        /** Forgets a burst picture no frame will arrive for, if it was still owed. */
        boolean forgetBurstPicture(@NonNull File file) {
            Iterator<OwedPicture> owed = mOwedPictures.iterator();
            while (owed.hasNext()) {
                if (owed.next().mFile.equals(file)) {
                    owed.remove();
                    return true;
                }
            }
            return false;
        }

        /** Whether a surface is this reader's, for the camera's word on a lost frame. */
        boolean isSurface(@NonNull Surface surface) {
            return mImageReader != null && mImageReader.getSurface() == surface;
        }

        /**
         * Copies a burst frame out and gives it back to the reader at once, then saves it in the
         * order it was taken. Asking for the next picture before saving keeps the camera busy.
         */
        private void onBurstImageAvailable(@NonNull SafeCloseImageReader reader,
                @NonNull Burst burst) {
            SafeCloseImageReader.Frame frame;
            try {
                frame = reader.acquireNextImage();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Failed to read a burst frame", e);
                return;
            }
            if (frame == null) {
                return;
            }
            byte[] bytes;
            try {
                bytes = ImageSaver.toByteArray(frame.getImage());
            } finally {
                frame.close();
            }
            OwedPicture owed = mOwedPictures.poll();
            if (owed == null) {
                Log.w(TAG, "Dropped a frame the burst did not ask for");
                return;
            }
            File file = owed.mFile;
            if (bytes == null) {
                burst.onPictureFailed(file);
                return;
            }
            Runnable onFrame = mOnBurstFrame;
            if (onFrame != null) {
                onFrame.run();
            }
            new ImageSaver(
                    mCameraView.getContext(),
                    getLocation(),
                    bytes,
                    mCameraView.getRelativeCameraOrientation(),
                    isUsingFrontFacingCamera(),
                    file,
                    owed.mExifParameters) {
                @UiThread
                @Override
                protected void onPostExecute(Boolean saved) {
                    if (Boolean.TRUE.equals(saved)) {
                        burst.onPictureTaken(file);
                    } else {
                        burst.onPictureFailed(file);
                    }
                }
            }.executeOnExecutor(burst.getExecutor());
        }

        /** Fails the burst pictures still owed by a reader that is going away. */
        private void abandonBurstPictures() {
            Burst burst = mBurst;
            if (burst == null) {
                return;
            }
            burst.end();
            OwedPicture owed;
            while ((owed = mOwedPictures.poll()) != null) {
                burst.onPictureFailed(owed.mFile);
            }
        }

        /** A burst picture the camera still owes. */
        private static final class OwedPicture {
            /** Where its frame is written. */
            final File mFile;

            /** How it was taken, described when it was asked for. */
            final ExifCaptureParameters mExifParameters;

            OwedPicture(@NonNull File file, @NonNull ExifCaptureParameters exifParameters) {
                mFile = file;
                mExifParameters = exifParameters;
            }
        }

        boolean isMaximumResolution() {
            return mMaximumResolution;
        }

        @Override
        Surface getSurface() {
            return mImageReader.getSurface();
        }

        @Override
        void close() {
            if (mImageReader != null) {
                mImageReader.close();
                mImageReader = null;
            }
            abandonBurstPictures();
            abandonPicture();
        }
    }

    /**
     * The RAW_SENSOR output a DNG is written from.
     *
     * <p>Beside the JPEG reader rather than instead of it, because RAW_JPEG needs both at once and
     * a camera with the RAW capability guarantees them together. The capture request decides
     * which of them a given shutter press writes.</p>
     */
    private static final class RawPictureSurface extends CameraSurface {
        /**
         * How many frames the reader holds, and how many frames and results may wait to be paired.
         *
         * <p>The same number for both, because a frame waiting for its result is a buffer the
         * reader has handed out: one buffer would let a single unpaired frame starve the next
         * capture, and a larger bound would promise frames the reader cannot produce.</p>
         */
        private static final int MAX_PENDING_CAPTURES = 2;

        /** Matches each frame to its capture result, which arrive on different callbacks. */
        private final RawCapturePairer<TotalCaptureResult> mPairer =
                new RawCapturePairer<>(MAX_PENDING_CAPTURES);

        /** The RAW_SENSOR reader, or null for a session that writes no DNG. */
        @Nullable
        private SafeCloseImageReader mImageReader;

        /** Where the next DNG goes, or null when no capture is outstanding. */
        @Nullable
        private File mFile;

        /** The shutter press {@link #mFile} belongs to, told when it is written or lost. */
        @Nullable
        private PendingPicture mPicture;

        RawPictureSurface(Camera2Module camera2Module) {
            super(camera2Module);
        }

        @MainThread
        private void onImageAvailable(@NonNull SafeCloseImageReader reader) {
            SafeCloseImageReader.Frame frame;
            try {
                // acquireNextImage rather than acquireLatestImage: a frame is claimed by the
                // result with its timestamp, so dropping older frames could drop the one a
                // waiting result is about to pair with.
                frame = reader.acquireNextImage();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Failed to acquire a raw frame", e);
                return;
            }
            if (frame == null) {
                Log.w(TAG, "OnImageAvailable called but no raw frame available");
                return;
            }
            RawCapturePairer.Pairing<TotalCaptureResult> pairing = mPairer.offerFrame(frame);
            if (pairing != null) {
                write(pairing);
            }
        }

        @Override
        void initialize(StreamConfigurationMap map) {
            if (DEBUG) Log.d(TAG, "Initializing the RAW surface");
            Size size = DngWriter.sensorSize(mCameraView.getCameraCharacteristics());
            if (size == null) {
                Log.w(TAG, "Camera offers no RAW size DngCreator will accept");
                return;
            }
            super.initialize(size);
            mPairer.clear();
            if (mImageReader != null) {
                mImageReader.close();
            }
            mImageReader = new SafeCloseImageReader(getWidth(), getHeight(),
                    ImageFormat.RAW_SENSOR, MAX_PENDING_CAPTURES);
            mImageReader.setOnImageAvailableListener(
                    this::onImageAvailable, mCameraView.getHandler());
        }

        boolean isInitialized() {
            return mImageReader != null;
        }

        void initializePicture(@NonNull File file, @NonNull PendingPicture picture) {
            mFile = file;
            mPicture = picture;
            if (mFile.exists()) {
                Log.w(TAG, "File already exists. Deleting.");
                if (!mFile.delete()) {
                    Log.w(TAG, "Failed to delete existing file.");
                }
            }
        }

        /** Reports the outstanding DNG lost, if there is one. */
        void abandonPicture() {
            File file = mFile;
            PendingPicture picture = mPicture;
            mFile = null;
            mPicture = null;
            if (file != null && picture != null) {
                picture.onWritten(file, false);
            }
        }

        /** Feeds capture results to the pairer, since they are the other half of every DNG. */
        @NonNull
        CameraCaptureSession.CaptureCallback captureCallback() {
            return new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
                    if (timestamp == null) {
                        Log.w(TAG, "A capture result arrived with no sensor timestamp");
                        return;
                    }
                    RawCapturePairer.Pairing<TotalCaptureResult> pairing =
                            mPairer.offerMetadata(result, timestamp);
                    if (pairing != null) {
                        write(pairing);
                    }
                }

                @Override
                public void onCaptureFailed(@NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
                    Log.e(TAG, "The camera dropped a RAW capture, reason " + failure.getReason());
                    // Nothing is coming for this capture, so what is waiting would only be
                    // paired with the next one.
                    mPairer.clear();
                    abandonPicture();
                }
            };
        }

        /** Hands a paired frame to the writer, or closes it if there is nowhere to write it. */
        @MainThread
        private void write(@NonNull RawCapturePairer.Pairing<TotalCaptureResult> pairing) {
            File file = mFile;
            PendingPicture picture = mPicture;
            CameraCharacteristics characteristics = mCameraView.getCameraCharacteristics();
            if (file == null || picture == null || characteristics == null) {
                Log.w(TAG, "A raw frame arrived with nowhere to write it");
                pairing.frame.close();
                abandonPicture();
                return;
            }
            mFile = null;
            mPicture = null;
            // Read now, as a JPEG's location is, because the camera can close before the writer
            // runs and the fix it was following goes with it.
            Location location = getLocation();
            new DngWriter(characteristics, pairing,
                    DngWriter.exifOrientation(mCameraView.getRelativeCameraOrientation(),
                            isUsingFrontFacingCamera()),
                    file) {
                @WorkerThread
                @Nullable
                @Override
                protected Location getLocation() {
                    return location;
                }

                @UiThread
                @Override
                protected void onPostExecute(Boolean saved) {
                    picture.onWritten(file, Boolean.TRUE.equals(saved));
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }

        @Override
        Surface getSurface() {
            return mImageReader.getSurface();
        }

        @Override
        void close() {
            mPairer.clear();
            if (mImageReader != null) {
                mImageReader.close();
                mImageReader = null;
            }
            abandonPicture();
        }
    }

    private static int getImageFormat(CameraView.Quality quality) {
        switch (quality) {
            case LOW:
                return IMAGE_FORMAT_LOW;
            case MEDIUM:
                return IMAGE_FORMAT_MEDIUM;
            case HIGH:
                return IMAGE_FORMAT_HIGH;
            case MAX:
                return IMAGE_FORMAT_MAX;
            default:
                return IMAGE_FORMAT_DEFAULT;
        }
    }
}
