package com.xlythe.view.camera.v2;

import android.Manifest;
import android.annotation.TargetApi;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.ParcelFileDescriptor;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresPermission;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import com.xlythe.view.camera.CameraView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * In order to take videos, we create a MediaRecorder instance. When we start recording, we
 * tell the camera to draw to the MediaRecorder surface and then have the MediaRecorder save
 * itself to the given file.
 */
@TargetApi(21)
class Camera2VideoModule extends Camera2PictureModule {
    private VideoSurface mVideoSurface = new VideoSurface(this);

    Camera2VideoModule(CameraView view) {
        super(view);
    }

    @Override
    protected List<Camera2PreviewModule.CameraSurface> getCameraSurfaces() {
        List<Camera2PreviewModule.CameraSurface> list = super.getCameraSurfaces();
        list.add(mVideoSurface);
        return list;
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @Override
    public ParcelFileDescriptor startStreaming() {
        capture(State.STREAMING);
        return mVideoSurface.mRead;
    }

    @Override
    public void stopStreaming() {
        mVideoSurface.stopRecording();
        startPreview();
    }

    @Override
    public boolean isStreaming() {
        return mVideoSurface.mIsRecordingVideo;
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @Override
    public void startRecording(File file) {
        mVideoSurface.setFile(file);
        capture(State.RECORDING);
    }

    @Override
    public void stopRecording() {
        mVideoSurface.stopRecording();
        startPreview();
    }

    @Override
    public boolean isRecording() {
        return mVideoSurface.mIsRecordingVideo;
    }

    private void capture(final State state) {
        if (!mVideoSurface.mIsInitialized) {
            Log.w(TAG, "Cannot record. Failed to initialize.");
            return;
        }
        mVideoSurface.mAwaitingRecording = true;
        try {
            SurfaceTexture texture = getSurfaceTexture();
            texture.setDefaultBufferSize(mVideoSurface.getWidth(), mVideoSurface.getHeight());

            CaptureRequest.Builder request = getCameraDevice().createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            request.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            request.addTarget(getPreviewSurface().getSurface());
            request.addTarget(mVideoSurface.getSurface());

            setState(state);
            getCaptureSession().setRepeatingRequest(request.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    // Alright, our session is active. Start recording the Video surface.
                    if (mVideoSurface.mAwaitingRecording) {
                        mVideoSurface.mAwaitingRecording = false;
                        mVideoSurface.startRecording(getOnVideoCapturedListener());
                    }
                }
            }, getBackgroundHandler());
        } catch (CameraAccessException | IllegalStateException e) {
            // Crashes if the Camera is interacted with while still loading
            e.printStackTrace();
        }
    }

    private static final class VideoSurface extends CameraSurface {
        private Size chooseVideoSize(Size[] choices) {
            List<Size> availableSizes = new ArrayList<>(choices.length);
            for (Size size : choices) {
                if (getQuality() == CameraView.Quality.MEDIUM
                        && size.getWidth() > 720) {
                    continue;
                }
                if (getQuality() == CameraView.Quality.LOW
                        && size.getWidth() > 420) {
                    continue;
                }
                availableSizes.add(size);
            }

            if (availableSizes.isEmpty()) {
                Log.e(TAG, "Couldn't find any suitable video size");
                availableSizes.add(choices[0]);
            }

            return Collections.max(availableSizes, new CompareSizesByArea());
        }

        private boolean mIsRecordingVideo;
        private boolean mIsInitialized;
        private boolean mAwaitingRecording;

        private MediaRecorder mMediaRecorder;
        private ParcelFileDescriptor mWrite;
        private ParcelFileDescriptor mRead;

        @Nullable
        private CameraView.OnVideoCapturedListener mVideoListener;

        @Nullable
        private File mFile;

        private FileStreamThread mFileStreamThread;

        VideoSurface(Camera2VideoModule cameraView) {
            super(cameraView);
        }

        @Override
        void initialize(StreamConfigurationMap map) {
            super.initialize(chooseVideoSize(map.getOutputSizes(MediaRecorder.class)));
            try {
                ParcelFileDescriptor[] descriptors = ParcelFileDescriptor.createPipe();
                mRead = descriptors[0];
                mWrite = descriptors[1];

                mMediaRecorder = new MediaRecorder();
                mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                switch (mCameraView.getQuality()) {
                    case HIGH:
                        mMediaRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH));
                        break;
                    case MEDIUM:
                        mMediaRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_720P));
                        break;
                    case LOW:
                        mMediaRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_LOW));
                        break;
                }

                // TODO This won't work. You're required to have a seekable output file, which a stream is not.
                // Time to try and find a low level video API...
                mMediaRecorder.setOutputFile(mWrite.getFileDescriptor());

                mMediaRecorder.setMaxDuration(mCameraView.getMaxVideoDuration());
                mMediaRecorder.setMaxFileSize(mCameraView.getMaxVideoSize());
                mMediaRecorder.setOnInfoListener(new MediaRecorder.OnInfoListener() {
                    @Override
                    public void onInfo(MediaRecorder mr, int what, int extra) {
                        switch (what) {
                            case MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED:
                                Log.w(TAG, "Max duration for recording reached");
                                break;
                            case MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED:
                                Log.w(TAG, "Max filesize for recording reached");
                                break;
                        }
                    }
                });
                mMediaRecorder.setVideoSize(getWidth(), getHeight());
                mMediaRecorder.setOrientationHint(ORIENTATIONS.get(mCameraView.getDisplayRotation()));
                mMediaRecorder.prepare();
                mIsInitialized = true;
            } catch (IOException e) {
                Log.e(TAG, "Failed to initialize", e);
            }
        }

        void setFile(File file) {
            mFile = file;
            if (mFileStreamThread != null) {
                mFileStreamThread.mIsAlive = false;
            }
            try {
                mFileStreamThread = new FileStreamThread(mRead, file);
                mFileStreamThread.start();
            } catch (IOException e) {
                Log.e(TAG, "Failed to create FileStreamThread", e);
            }
        }

        @Override
        Surface getSurface() {
            return mMediaRecorder.getSurface();
        }

        boolean isRecording() {
            return mIsRecordingVideo;
        }

        void startRecording(CameraView.OnVideoCapturedListener listener) {
            if (mMediaRecorder == null) {
                Log.w(TAG, "Cannot record. Failed to initialize.");
                return;
            }

            mVideoListener = listener;
            try {
                mMediaRecorder.start();
                mIsRecordingVideo = true;
            } catch (IllegalStateException e) {
                Log.e(TAG, "Unable to start recording", e);
            } catch (RuntimeException e) {
                // MediaRecorder can crash with 'start failed.'
                Log.e(TAG, "Let me guess. 'start failed.'?", e);
                mIsInitialized = false;
            }
        }

        void stopRecording() {
            if (mIsRecordingVideo) {
                mIsRecordingVideo = false;
                try {
                    mMediaRecorder.stop();
                    mMediaRecorder.reset();
                } catch (RuntimeException e) {
                    // MediaRecorder can crash with 'stop failed.'
                    Log.e(TAG, "Let me guess. 'stop failed.'?", e);
                }
                if (mFileStreamThread != null) {
                    mFileStreamThread.mIsAlive = false;
                    try {
                        mFileStreamThread.join();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                if (mVideoListener != null) {
                    mVideoListener.onVideoCaptured(mFile);
                }
            }
        }

        @Override
        void close() {
            if (mMediaRecorder != null) {
                mMediaRecorder.release();
                mMediaRecorder = null;
            }
            if (mWrite != null) {
                try {
                    mWrite.close();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to close input stream");
                }
                mWrite = null;
            }
            if (mRead != null) {
                try {
                    mRead.close();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to close output stream");
                }
                mRead = null;
            }
            if (mFileStreamThread != null) {
                mFileStreamThread.mIsAlive = false;
                mFileStreamThread = null;
            }
        }
    }

    /**
     * Streams from a file descriptor into an output file
     */
    private static class FileStreamThread extends Thread {
        private final InputStream mInputStream;
        private final OutputStream mOutputStream;

        private volatile boolean mIsAlive;

        FileStreamThread(ParcelFileDescriptor input, File destination) throws IOException {
            mInputStream = new ParcelFileDescriptor.AutoCloseInputStream(input);
            destination.delete();
            destination.createNewFile();
            mOutputStream = new FileOutputStream(destination);
        }

        @Override
        public void run() {
            byte[] buffer = new byte[1024];
            try {
                while (mIsAlive) {
                    int len = mInputStream.read(buffer);
                    mOutputStream.write(buffer, 0, len);
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to transfer buffer", e);
            }

            mIsAlive = false;
        }
    }
}
