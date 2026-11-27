package com.xlythe.view.camera.legacy;

import android.Manifest;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.location.Location;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.Face;
import com.xlythe.view.camera.ICameraModule;
import com.xlythe.view.camera.LocationProvider;
import com.xlythe.view.camera.PermissionChecker;
import com.xlythe.view.camera.TransformMath;
import com.xlythe.view.camera.stream.VideoRecorder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.collection.ArrayMap;

@RestrictTo(RestrictTo.Scope.LIBRARY)
public class LegacyCameraModule extends ICameraModule {
    /** No camera chosen yet, which is not the same as camera zero. */
    private static final int INVALID_CAMERA_ID = -1;

    /** The zoom scale CameraView publishes, matching the Camera2 and CameraX backends. */
    private static final int MAX_ZOOM_LEVEL = 100;

    /**
     * How old a location may be and still describe where a picture was taken.
     *
     * <p>Two hours, because what is wanted is the place rather than the moment, and a fix from
     * earlier in the same outing names the place correctly. Starting a live scan when the camera
     * opens would be more accurate and would cost a radio running for as long as the camera is
     * on, for a field most viewers read to the nearest town.</p>
     */
    private static final long STALE_LOCATION_MILLIS = 2 * 60 * 60 * 1000;

    /**
     * How long to wait for the GPS to answer, which is deliberately almost no time at all.
     *
     * <p>The point is to take whatever fix is already there, not to hold a capture while the
     * radio warms up. A picture is never delayed for its metadata.</p>
     */
    private static final long GPS_TIMEOUT_MILLIS = 10;

    /** Which of the device's cameras is selected, as an index into Camera.getNumberOfCameras. */
    private int mActiveCamera = INVALID_CAMERA_ID;

    /** The open camera, or null when none is open - including after a failed open. */
    private Camera mCamera;

    /** The preview size chosen for the open camera, or null when no camera is open. */
    @Nullable private Camera.Size mPreviewSize;

    /** The recorder for a recording in progress, or null when nothing is being recorded. */
    private MediaRecorder mVideoRecorder;

    /** Where the recording in progress is being written. */
    private File mVideoFile;

    /** The surfaces handed out for streaming, each with the holder that feeds it. */
    private final Map<VideoRecorder.SurfaceProvider, LegacySurfaceHolder> mSurfaceProviders = new ArrayMap<>();

    public LegacyCameraModule(CameraView view) {
        super(view);
    }

    @Override
    public void open() {
        Log.d(TAG, "onOpen() activeCamera="+getActiveCamera());
        try {
            mCamera = Camera.open(getActiveCamera());
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to open camera", e);
            return;
        }

        try {
            mCamera.setPreviewTexture(getSurfaceTexture());

            Camera.Parameters parameters = mCamera.getParameters();
            int cameraOrientation = getRelativeCameraOrientation();
            mCamera.setDisplayOrientation(cameraOrientation);
            mPreviewSize = chooseOptimalPreviewSize(mCamera.getParameters().getSupportedPreviewSizes(), getWidth(), getHeight());
            parameters.setPreviewSize(getPreviewWidth(), getPreviewHeight());
            parameters.setPictureFormat(ImageFormat.JPEG);
            parameters.setJpegQuality(getPictureQuality());
            mCamera.setParameters(parameters);
            transformPreview(getWidth(), getHeight(), getPreviewWidth(), getPreviewHeight(), cameraOrientation);

            mCamera.startPreview();
        } catch (IOException | RuntimeException e) {
            // A camera opened but never started is worse than no camera: close() would release
            // it, but takePicture() and startRecording() would both act on one configured by
            // halves. Put the module back where a failed Camera.open leaves it. RuntimeException
            // is caught with it because setParameters and startPreview throw that, not IOException,
            // on a camera that will not take what it was given.
            Log.e(TAG, "Failed to properly initialize camera", e);
            try {
                mCamera.release();
            } catch (RuntimeException released) {
                Log.w(TAG, "Camera would not release after a failed open", released);
            }
            mCamera = null;
            mPreviewSize = null;
        }
    }

    @Override
    public void close() {
        Log.d(TAG, "onClose() activeCamera="+getActiveCamera());
        if (isRecording()) {
            // The recorder holds the camera, so releasing the camera under a running recording
            // leaves an encoder writing to a file nothing will ever finish, and isRecording()
            // answering yes for a camera that no longer exists.
            stopRecording();
        }
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
            mPreviewSize = null;
        }
    }

    @Override
    public void onLayoutChanged() {
        if (mPreviewSize == null) {
            return;
        }

        int cameraOrientation = getRelativeCameraOrientation();
        transformPreview(getWidth(), getHeight(), getPreviewWidth(), getPreviewHeight(), cameraOrientation);
    }

    private void transformPreview(int viewWidth, int viewHeight, int previewWidth, int previewHeight, int cameraOrientation) {
        if (DEBUG) {
            Log.d(TAG, String.format("Configuring SurfaceView matrix: "
                            + "viewWidth=%s, viewHeight=%s, previewWidth=%s, previewHeight=%s, cameraOrientation=%s",
                    viewWidth, viewHeight, previewWidth, previewHeight, cameraOrientation));
        }

        Matrix matrix = new Matrix();

        // Because the camera already rotates the preview for us (@see Camera.setDisplayOrientation(int)},
        // we need to flip the width/height to the dimensions we'll actually be given.
        if (cameraOrientation == 90 || cameraOrientation == 270) {
            int temp = previewWidth;
            previewWidth = previewHeight;
            previewHeight = temp;
        }

        // We want to maintain aspect ratio, but we also want both sides to be >= the view's width and height.
        // Otherwise, there will be blank space around our preview.
        double aspectRatio = (double) previewHeight / (double) previewWidth;
        int newWidth, newHeight;
        if (getHeight() > viewWidth * aspectRatio) {
            newWidth = (int) (viewHeight / aspectRatio);
            newHeight = viewHeight;
        } else {
            newWidth = viewWidth;
            newHeight = (int) (viewWidth * aspectRatio);
        }

        // We scale the image up so that it definitely fits (or overflows) our bounds
        matrix.setScale((float) newWidth / (float) viewWidth, (float) newHeight / (float) viewHeight);

        // And then we reposition it so that it's centered
        matrix.postTranslate((viewWidth - newWidth) / 2f, (viewHeight - newHeight) / 2f);

        // And once we're done, we apply our changes.
        setTransform(matrix);
    }

    @Override
    public void takePicture(File file) {
        if (mCamera == null) {
            // Nothing is holding a caller to the camera's lifecycle - a button is pressed when it
            // is pressed - so this is a state an app reaches rather than a programming error, and
            // the answer is the failure callback it is already listening to.
            Log.w(TAG, "Cannot take a picture before the camera is open");
            onImageFailed();
            return;
        }
        mCamera.takePicture(null, null, new LegacyPictureListener(
                file, getRelativeCameraOrientation(false /* isPreview */), isUsingFrontFacingCamera(), this));
    }

    @Override
    public void startRecording(File file) {
        if (mCamera == null) {
            Log.w(TAG, "Cannot record before the camera is open");
            onVideoFailed();
            return;
        }
        if (Build.VERSION.SDK_INT < 16) {
            // Before API 16, MediaRecorder sets the camera's preview to a surface of its own, and
            // it cannot take one made from our SurfaceTexture: the camera loses its preview and
            // mediaserver aborts the recording.
            Log.w(TAG, "Recording needs API 16");
            onVideoFailed();
            return;
        }
        mVideoFile = file;
        mVideoRecorder = new MediaRecorder();

        mCamera.unlock();
        mVideoRecorder.setCamera(mCamera);

        // CAMCORDER rather than MIC: on a phone with several microphones it selects the
        // one facing the same way as the camera, and it applies the camcorder tuning
        // instead of the voice-call tuning. The voice tuning is automatic gain control and
        // noise suppression built for speech, which audibly pumps on music and on room
        // ambience - exactly the material a camera records.
        mVideoRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        mVideoRecorder.setVideoSource(MediaRecorder.VideoSource.DEFAULT);
        // Every profile below is fetched for the camera actually in use. The single-argument
        // overload returns the first back-facing camera's profile whatever camera is open, so on
        // the front camera it handed MediaRecorder a size and encoder configuration belonging to
        // a different sensor - which is one way to get a recording full of green lines.
        switch (getQuality()) {
            case MAX:
                if (CamcorderProfile.hasProfile(mActiveCamera, CamcorderProfile.QUALITY_HIGH)) {
                    mVideoRecorder.setProfile(CamcorderProfile.get(mActiveCamera, CamcorderProfile.QUALITY_HIGH));
                    break;
                }
                // Fall-through
            case HIGH:
                if (CamcorderProfile.hasProfile(mActiveCamera, CamcorderProfile.QUALITY_1080P)) {
                    mVideoRecorder.setProfile(CamcorderProfile.get(mActiveCamera, CamcorderProfile.QUALITY_1080P));
                    break;
                }
                // Fall-through
            case MEDIUM:
                if (CamcorderProfile.hasProfile(mActiveCamera, CamcorderProfile.QUALITY_720P)) {
                    mVideoRecorder.setProfile(CamcorderProfile.get(mActiveCamera, CamcorderProfile.QUALITY_720P));
                    break;
                }
                // Fall-through
            case LOW:
                if (CamcorderProfile.hasProfile(mActiveCamera, CamcorderProfile.QUALITY_480P)) {
                    mVideoRecorder.setProfile(CamcorderProfile.get(mActiveCamera, CamcorderProfile.QUALITY_480P));
                    break;
                }
                // Fall-through
            default:
                mVideoRecorder.setProfile(CamcorderProfile.get(mActiveCamera, CamcorderProfile.QUALITY_LOW));
                break;
        }
        mVideoRecorder.setOutputFile(file.getAbsolutePath());
        mVideoRecorder.setMaxDuration((int) getMaxVideoDuration());
        mVideoRecorder.setMaxFileSize(getMaxVideoSize());
        mVideoRecorder.setOrientationHint(getRelativeCameraOrientation(false /* isPreview */));
        mVideoRecorder.setOnInfoListener((mr, what, extra) -> {
            switch (what) {
                case MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED:
                    Log.w(TAG, "Max duration for recording reached");
                    finishLimitedRecording();
                    break;
                case MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED:
                    Log.w(TAG, "Max filesize for recording reached");
                    finishLimitedRecording();
                    break;
            }
        });

        Location location = getLocation(getContext());
        if (location != null) {
            mVideoRecorder.setLocation((float) location.getLatitude(), (float) location.getLongitude());
        }

        try {
            mVideoRecorder.prepare();
            mVideoRecorder.start();
        } catch (IOException | RuntimeException e) {
            e.printStackTrace();
            mVideoRecorder = null;
            onVideoFailed();
        }
    }

    /**
     * Delivers a recording MediaRecorder ended on its own at a duration or size limit.
     *
     * <p>Capturing has already stopped by this point, but the file is only finalized by stop(),
     * so finish it the way an explicit stopRecording() would. Posted because the callback arrives
     * on MediaRecorder's own thread.</p>
     */
    private void finishLimitedRecording() {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (isRecording()) {
                stopRecording();
            }
        });
    }

    @Override
    public void stopRecording() {
        if (mVideoRecorder != null) {
            try {
                mVideoRecorder.stop();
                showVideoConfirmation(mVideoFile);
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to stop video recorder. This can happen if a video is stopped too quickly. :(", e);
                onVideoFailed();
            }
            mVideoRecorder = null;
        } else {
            onVideoFailed();
        }
    }

    @Override
    public boolean isRecording() {
        return mVideoRecorder != null;
    }

    @Override
    public void focus(Rect focus, Rect metering) {
        if (DEBUG) {
            Log.d(TAG, String.format("Focus: focus=%s, metering=%s", focus, metering));
        }
        if (mCamera != null) {
            mCamera.cancelAutoFocus();

            Camera.Parameters parameters = mCamera.getParameters();
            if (!parameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                Log.w(TAG, "Focus not available on this camera");
                return;
            }

            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);

            if (parameters.getMaxNumFocusAreas() > 0) {
                parameters.setFocusAreas(Collections.singletonList(new Camera.Area(focus, 1000)));
            }

            if (parameters.getMaxNumMeteringAreas() > 0) {
                parameters.setMeteringAreas(Collections.singletonList(new Camera.Area(metering, 1000)));
            }

            mCamera.setParameters(parameters);
            mCamera.autoFocus((success, camera) -> {
                if (DEBUG) {
                    Log.d(TAG, "AutoFocus: " + success);
                }
                onFocusCompleted(success);
            });
            onFocusRequested(focus);
        } else {
            Log.w(TAG, "Attempted to set focus but no camera found");
        }
    }

    @Override
    public boolean isFocusSupported() {
        if (mCamera == null) {
            return false;
        }
        List<String> modes = mCamera.getParameters().getSupportedFocusModes();
        return modes != null && modes.contains(Camera.Parameters.FOCUS_MODE_AUTO);
    }

    @Override
    public void setZoomLevel(int zoomLevel) {
        if (mCamera == null) {
            Log.w(TAG, "Attempted to set zoom level to " + zoomLevel + " but no camera found");
            return;
        }
        Camera.Parameters parameters = mCamera.getParameters();
        if (!parameters.isZoomSupported()) {
            Log.w(TAG, "Zoom not supported");
            return;
        }
        // The level is a position in the camera's range, as it is on the other backends, while
        // this API takes an index into the camera's own zoom table. Passing the level straight
        // through meant the scale differed per device and an out-of-range value reached the
        // camera unchecked.
        parameters.setZoom(toZoomIndex(zoomLevel, parameters.getMaxZoom()));
        mCamera.setParameters(parameters);
    }

    /** Maps a 0..MAX_ZOOM_LEVEL position onto an index into the camera's zoom table. */
    static int toZoomIndex(int zoomLevel, int maxZoomIndex) {
        int clamped = Math.max(0, Math.min(zoomLevel, MAX_ZOOM_LEVEL));
        if (maxZoomIndex <= 0) {
            return 0;
        }
        return Math.round((float) clamped * maxZoomIndex / MAX_ZOOM_LEVEL);
    }

    /** Maps an index into the camera's zoom table back onto a 0..MAX_ZOOM_LEVEL position. */
    static int toZoomLevel(int zoomIndex, int maxZoomIndex) {
        if (maxZoomIndex <= 0) {
            return 0;
        }
        int clamped = Math.max(0, Math.min(zoomIndex, maxZoomIndex));
        return Math.round((float) clamped * MAX_ZOOM_LEVEL / maxZoomIndex);
    }

    @Override
    public int getZoomLevel() {
        if (mCamera == null) {
            return 0;
        }
        Camera.Parameters parameters = mCamera.getParameters();
        return toZoomLevel(parameters.getZoom(), parameters.getMaxZoom());
    }

    @Override
    public int getMaxZoomLevel() {
        // Fixed, so one zoom control works across every backend.
        return MAX_ZOOM_LEVEL;
    }

    @Override
    public boolean isZoomSupported() {
        return mCamera != null && mCamera.getParameters().isZoomSupported();
    }

    @Override
    public boolean isFaceDetectionSupported() {
        return mCamera != null
                && mCamera.getParameters().getMaxNumDetectedFaces() > 0;
    }

    @Override
    protected boolean setFaceDetectionEnabled(boolean enabled) {
        if (mCamera == null) {
            return !enabled;
        }
        if (!enabled) {
            try {
                mCamera.setFaceDetectionListener(null);
                mCamera.stopFaceDetection();
            } catch (RuntimeException error) {
                // Throws if detection was never started, which is not a failure to stop.
                if (DEBUG) Log.d(TAG, "Face detection was not running", error);
            }
            return true;
        }
        if (!isFaceDetectionSupported()) {
            return false;
        }
        mCamera.setFaceDetectionListener((faces, camera) -> onFacesDetected(
                translate(faces), faceToViewTransform()));
        try {
            mCamera.startFaceDetection();
        } catch (RuntimeException error) {
            Log.w(TAG, "Camera refused to start face detection", error);
            mCamera.setFaceDetectionListener(null);
            return false;
        }
        return true;
    }

    @NonNull
    private static List<Face> translate(@Nullable Camera.Face[] faces) {
        if (faces == null) {
            return Collections.emptyList();
        }
        List<Face> translated = new ArrayList<>(faces.length);
        for (Camera.Face face : faces) {
            if (face == null || face.rect == null) {
                continue;
            }
            translated.add(Face.create(face.rect, face.score, face.id,
                    face.leftEye, face.rightEye, face.mouth));
        }
        return translated;
    }

    /**
     * Maps face bounds onto the view.
     *
     * <p>The old API reports faces in a fixed square from -1000 to 1000 that covers the field of
     * view as it is currently displayed, so the rotation the preview needed has already been
     * applied and only the scale into the view is left.</p>
     */
    @NonNull
    private Matrix faceToViewTransform() {
        return TransformMath.centerCrop(new RectF(-1000f, -1000f, 1000f, 1000f),
                0 /* already in display orientation */,
                false /* and already mirrored if the lens is */,
                getView().getWidth(), getView().getHeight());
    }

    @Override
    public void toggleCamera() {
        boolean shouldOpen = getView().isOpen();
        close();
        mActiveCamera = (mActiveCamera + 1) % Camera.getNumberOfCameras();
        if (shouldOpen) {
            open();
        }
    }

    @Override
    public void setLensFacing(CameraView.LensFacing lensFacing) {
        boolean shouldOpen = getView().isOpen();
        close();

        // Search for the properly facing camera
        int numberOfCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numberOfCameras; i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(i, info);
            if ((info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT && lensFacing.equals(CameraView.LensFacing.FRONT))
                    || (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK && lensFacing.equals(CameraView.LensFacing.BACK))) {
                mActiveCamera = i;
                break;
            }
        }

        if (shouldOpen) {
            open();
        }
    }

    @Override
    public boolean hasFrontFacingCamera() {
        // Search for the front facing camera
        int numberOfCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numberOfCameras; i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isUsingFrontFacingCamera() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(getActiveCamera(), info);
        return info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT;
    }

    @Override
    protected int getRelativeCameraOrientation() {
        return getRelativeCameraOrientation(true /* isPreview */);
    }

    /**
     * The angle of view, which this API reports itself.
     *
     * <p>Camera.Parameters gives the angle for the lens wide open, with no account of zoom, so
     * the zoom this module has applied is taken off here to match what the preview is showing.
     * </p>
     */
    @Override
    public float getHorizontalFieldOfView() {
        return fieldOfView(true /* horizontal */);
    }

    @Override
    public float getVerticalFieldOfView() {
        return fieldOfView(false /* horizontal */);
    }

    private float fieldOfView(boolean horizontal) {
        Camera camera = mCamera;
        if (camera == null) {
            return 0f;
        }
        try {
            Camera.Parameters parameters = camera.getParameters();
            float full = horizontal
                    ? parameters.getHorizontalViewAngle() : parameters.getVerticalViewAngle();
            if (!parameters.isZoomSupported()) {
                return full;
            }
            List<Integer> ratios = parameters.getZoomRatios();
            int index = parameters.getZoom();
            if (ratios == null || index < 0 || index >= ratios.size()) {
                return full;
            }
            // getZoomRatios is in hundredths, so 100 is no zoom at all.
            return TransformMath.zoomedFieldOfViewDegrees(full, ratios.get(index) / 100f);
        } catch (RuntimeException error) {
            if (DEBUG) Log.d(TAG, "Could not work out the field of view", error);
            return 0f;
        }
    }

    /**
     * False: this backend paints frames onto a surface rather than handing them over.
     *
     * <p>An analyzer reads YUV planes from an ImageReader surface. The old Camera API delivers
     * preview bytes to a callback, which {@link #attachSurface} draws through a Canvas, and a
     * surface that only accepts pixels cannot be read back.</p>
     */
    @Override
    public boolean isFrameAnalysisSupported() {
        return false;
    }

    @RequiresApi(18)
    @Override
    protected void attachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
        if (mCamera != null && mPreviewSize != null) {
            LegacySurfaceHolder surfaceHolder = new LegacySurfaceHolder(getContext(), surfaceProvider, mPreviewSize.width, mPreviewSize.height, getSensorOrientation(), isUsingFrontFacingCamera());
            mSurfaceProviders.put(surfaceProvider, surfaceHolder);
            mCamera.setPreviewCallback((data, camera) -> {
                Canvas canvas = surfaceHolder.lockCanvas();
                canvas.drawBitmap(toBitmap(data, camera), new Matrix(), new Paint());
                surfaceHolder.unlockCanvasAndPost(canvas);
            });
        }
    }

    @RequiresApi(18)
    @Override
    protected void detachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
        LegacySurfaceHolder surfaceHolder = mSurfaceProviders.remove(surfaceProvider);
        if (surfaceHolder != null) {
            surfaceHolder.close();
            mCamera.setPreviewCallback(null);
        }
    }

    private Bitmap toBitmap(byte[] data, Camera camera) {
        Camera.Parameters parameters = camera.getParameters();
        int width = parameters.getPreviewSize().width;
        int height = parameters.getPreviewSize().height;

        YuvImage yuv = new YuvImage(data, parameters.getPreviewFormat(), width, height, null);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuv.compressToJpeg(new Rect(0, 0, width, height), 50, out);

        byte[] bytes = out.toByteArray();
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    private int getRelativeCameraOrientation(boolean isPreview) {
        return getRelativeImageOrientation(getDisplayRotation(), getSensorOrientation(), isUsingFrontFacingCamera(), isPreview);
    }

    private int getSensorOrientation() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(getActiveCamera(), info);
        return info.orientation;
    }

    private int getActiveCamera() {
        if (mActiveCamera != INVALID_CAMERA_ID) {
            return mActiveCamera;
        }

        int numberOfCameras = Camera.getNumberOfCameras();
        if (numberOfCameras == 0) {
            return INVALID_CAMERA_ID;
        }

        for (int i = 0; i < numberOfCameras; i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                mActiveCamera = i;
                return mActiveCamera;
            }
        }

        mActiveCamera = 0;
        return mActiveCamera;
    }

    private int getPreviewWidth() {
        if (mPreviewSize == null) {
            return 0;
        }

        return mPreviewSize.width;
    }

    private int getPreviewHeight() {
        if (mPreviewSize == null) {
            return 0;
        }

        return mPreviewSize.height;
    }

    private static Camera.Size chooseOptimalPreviewSize(List<Camera.Size> choices, int width, int height) {
        if (DEBUG) {
            Log.d(TAG, String.format("Initializing PreviewSurface with width=%s and height=%s", width, height));
        }
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Camera.Size> bigEnough = new ArrayList<>();
        for (Camera.Size option : choices) {
            if (option.width >= width && option.height >= height) {
                bigEnough.add(option);
            }
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices.get(0);
        }
    }

    private static class CompareSizesByArea implements Comparator<Camera.Size> {
        @Override
        public int compare(Camera.Size lhs, Camera.Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.width * lhs.height -
                    (long) rhs.width * rhs.height);
        }
    }

    @SuppressWarnings({"MissingPermission"})
    @Nullable
    static Location getLocation(Context context) {
        if (PermissionChecker.hasPermissions(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
            // Our GPS timeout is purposefully low. We're not intending to wait until GPS is acquired
            // but we want a last known location for the next time a picture is taken.
            return LocationProvider.getGPSLocation(context, STALE_LOCATION_MILLIS, GPS_TIMEOUT_MILLIS);
        }
        return null;
    }
}
