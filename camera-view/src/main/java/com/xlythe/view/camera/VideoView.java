package com.xlythe.view.camera;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Objects;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.xlythe.view.camera.stream.AudioPlayer;
import com.xlythe.view.camera.stream.VideoPlayer;

public class VideoView extends FrameLayout implements TextureView.SurfaceTextureListener {
    private static final String TAG = VideoView.class.getSimpleName();
    private static final boolean DEBUG = false;

    // ---------- General ----------

    // The view we draw the video on to
    private TextureView mTextureView;
    // Whether to play a file or a stream
    private InputType mInputType = InputType.UNKNOWN;
    // If true, the texture view is ready to be drawn on
    private boolean mIsAvailable;
    // If true, we should be playing
    private boolean mIsPlaying;
    // An optional listener for when videos are paused/played
    @Nullable private EventListener mEventListener;
    // If true, the video should be mirrored
    private boolean mIsMirrored = false;

    // ---------- File ----------

    // Controls video playback
    @Nullable private MediaPlayer mMediaPlayer;
    // The file of the video to play
    @Nullable private File mFile;
    // An optional listener for when videos have reached the end
    @Nullable private MediaPlayer.OnCompletionListener mOnCompletionListener;
    // If true, the video should loop
    private boolean mIsLooping = false;

    // ---------- Stream ----------

    // The stream to play
    @Nullable private VideoStream mVideoStream;
    // Plays the audio half of the VideoStream
    @Nullable private AudioPlayer mAudioPlayer;
    // Plays the video half of the VideoStream
    @Nullable private VideoPlayer mVideoPlayer;

    public VideoView(Context context) {
        this(context, null);
    }

    public VideoView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public VideoView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context, attrs);
    }

    @TargetApi(21)
    public VideoView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context, attrs);
    }

    private void init(Context context, @Nullable AttributeSet attrs) {
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.VideoView, 0, 0);
            if (a.hasValue(R.styleable.VideoView_filePath)) {
                setFile(new File(a.getString(R.styleable.VideoView_filePath)));
            }
            a.recycle();
        }
    }

    public MediaPlayer getMediaPlayer() {
        ensureMediaPlayer();
        return mMediaPlayer;
    }

    @Nullable
    public File getFile() {
        return mFile;
    }

    public void setFile(@Nullable File file) {
        if (Objects.equals(file, mFile) && mInputType.equals(InputType.FILE)) {
            return;
        }

        if (DEBUG) Log.d(TAG, "File set to " + file);
        this.mFile = file;
        if (mVideoStream != null) {
            if (Build.VERSION.SDK_INT <= 18) {
                throw new RuntimeException("API not available");
            }
            mVideoStream.close();
            mVideoStream = null;
        }
        this.mInputType = InputType.FILE;
        createTextureView();
        if (file != null && mIsAvailable) {
            prepare();
        }
    }

    @RequiresApi(18)
    public boolean hasStream() {
        return mVideoStream != null;
    }

    @Nullable
    @RequiresApi(18)
    public VideoStream getStream() {
        return mVideoStream;
    }

    @RequiresApi(18)
    public void setStream(VideoStream videoStream) {
        if (Objects.equals(videoStream, mVideoStream) && mInputType.equals(InputType.STREAM)) {
            return;
        }

        if (DEBUG) Log.d(TAG, "Stream set");
        this.mVideoStream = videoStream;
        this.mFile = null;
        this.mInputType = InputType.STREAM;
        createTextureView();
        if (videoStream != null && mIsAvailable) {
            prepare();
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        if (DEBUG) Log.d(TAG, "Texture available");
        mIsAvailable = true;
        if (mFile != null || mVideoStream != null) {
            prepare();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {}

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        if (DEBUG) Log.d(TAG, "Texture destroyed");
        mIsAvailable = false;
        setPlayingState(false);

        switch (mInputType) {
            case FILE:
                ensureMediaPlayer();
                if (mMediaPlayer != null) {
                    mMediaPlayer.release();
                    mMediaPlayer = null;
                }
                break;
            case STREAM:
                if (mVideoStream != null) {
                    if (Build.VERSION.SDK_INT < 18) {
                        throw new RuntimeException("API not available");
                    }
                    mVideoStream.close();
                    mVideoStream = null;
                }
                break;
        }

        return true;
    }

    /**
     * MediaPlayer is null after being destroyed. Call this before any calls to MediaPlayer to
     * ensure it exists.
     */
    private void ensureMediaPlayer() {
        if (mMediaPlayer == null) {
            mMediaPlayer = new MediaPlayer();
        }

        mMediaPlayer.setOnCompletionListener(mediaPlayer -> {
            setPlayingState(false);
            if (mOnCompletionListener != null) {
                mOnCompletionListener.onCompletion(mediaPlayer);
            }
        });
    }

    public void setOnCompletionListener(MediaPlayer.OnCompletionListener listener) {
        mOnCompletionListener = listener;
    }

    public void setEventListener(EventListener listener) {
        mEventListener = listener;
    }

    protected void prepare() {
        switch (mInputType) {
            case UNKNOWN:
                throw new IllegalStateException("Must set a file ot stream before preparing");
            case FILE:
                prepareFile();
                break;
            case STREAM:
                prepareStream();
                break;
        }
    }

    protected void prepareFile() {
        if (DEBUG) Log.d(TAG, "Preparing video");
        ensureMediaPlayer();

        try {
            FileDescriptor fileDescriptor = new FileInputStream(mFile).getFD();

            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(fileDescriptor);
            int width = extractAsInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            int height = extractAsInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            int rotation = Build.VERSION.SDK_INT < 17 ? 0 : extractAsInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            retriever.release();
            if (DEBUG) Log.d(TAG, String.format("Video metadata: width=%d, height=%d, rotation=%d", width, height, rotation));

            if (rotation == 90 || rotation == 270) {
                int temp = width;
                width = height;
                height = temp;
            }
            transformPreview(width, height);

            Surface surface = new Surface(mTextureView.getSurfaceTexture());

            try {
                Objects.requireNonNull(mMediaPlayer).stop();
            } catch (IllegalStateException e) {
                if (DEBUG) e.printStackTrace();
            }

            try {
                mMediaPlayer.reset();
            } catch (IllegalStateException e) {
                if (DEBUG) e.printStackTrace();
            }

            mMediaPlayer.setDataSource(fileDescriptor);
            mMediaPlayer.setSurface(surface);
            mMediaPlayer.prepare();
            mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        } catch (IllegalStateException | IOException e) {
            e.printStackTrace();
        }

        seekToFirstFrame();
    }

    protected void prepareStream() {
        if (DEBUG) Log.d(TAG, "Preparing stream");
        VideoStream videoStream = mVideoStream;
        if (videoStream == null) {
            throw new IllegalStateException("Cannot prepare a null stream");
        }
        if (Build.VERSION.SDK_INT < 18) {
            throw new RuntimeException("API not available");
        }

        if (videoStream.hasAudio()) {
            mAudioPlayer = new AudioPlayer(videoStream.getAudioInputStream());
            mAudioPlayer.setStreamEndListener(() -> new Handler(Looper.getMainLooper()).post(() -> {
                setPlayingState(false);
                videoStream.close();
            }));
        }

        if (videoStream.hasVideo()) {
            @SuppressLint("Recycle") Surface surface = new Surface(mTextureView.getSurfaceTexture());
            mVideoPlayer = new VideoPlayer(surface, videoStream.getVideoInputStream());
            mVideoPlayer.setStreamEndListener(() -> new Handler(Looper.getMainLooper()).post(() -> {
                setPlayingState(false);
                videoStream.close();
            }));
            mVideoPlayer.setOnMetadataAvailableListener((width, height, orientation, flipped) -> new Handler(Looper.getMainLooper()).post(() -> transformPreview(width, height, orientation, flipped)));
        }

        if (mIsPlaying) {
            playStream();
        }
    }

    private int extractAsInt(MediaMetadataRetriever retriever, int key) {
        String metadata = retriever.extractMetadata(key);
        if (metadata == null) {
            return 0;
        }
        return Integer.parseInt(metadata);
    }

    public void seekToFirstFrame() {
        if (DEBUG) Log.d(TAG, "seekToFirstFrame()");
        ensureMediaPlayer();

        try {
            Objects.requireNonNull(mMediaPlayer).setOnSeekCompleteListener(mediaPlayer -> {
                if (DEBUG) Log.d(TAG, "Seek completed");
                mMediaPlayer.setOnSeekCompleteListener(null);
                if (!mIsPlaying) {
                    mediaPlayer.pause();
                }
            });
            mMediaPlayer.start();
            mMediaPlayer.seekTo(1);
            mMediaPlayer.setLooping(isLooping());
        } catch (IllegalStateException e) {
            if (DEBUG) e.printStackTrace();
        }
    }

    public boolean play() {
        if (DEBUG) Log.d(TAG, "play()");

        switch (mInputType) {
            case FILE:
                return playFile();
            case STREAM:
                return playStream();
            default:
                return false;
        }
    }

    private boolean playFile() {
        ensureMediaPlayer();

        try {
            Objects.requireNonNull(mMediaPlayer).start();
            mMediaPlayer.setLooping(isLooping());
            setPlayingState(true);
            return true;
        } catch (IllegalStateException e) {
            if (DEBUG) e.printStackTrace();
        }
        return false;
    }

    private boolean playStream() {
        if (DEBUG) Log.d(TAG, "Playing stream");
        if (mAudioPlayer != null) {
            if (DEBUG) Log.d(TAG, "Playing audio stream");
            mAudioPlayer.start();
        }
        if (mVideoPlayer != null) {
            if (DEBUG) Log.d(TAG, "Playing video stream");
            if (Build.VERSION.SDK_INT <= 18) {
                throw new RuntimeException("API not available");
            }
            mVideoPlayer.start();
        }
        setPlayingState(true);
        return true;
    }

    public boolean pause() {
        if (DEBUG) Log.d(TAG, "pause()");

        switch (mInputType) {
            case FILE:
                return pauseFile();
            case STREAM:
                return pauseStream();
            default:
                return false;
        }
    }

    private boolean pauseFile() {
        ensureMediaPlayer();

        try {
            Objects.requireNonNull(mMediaPlayer).pause();
            setPlayingState(false);
            return true;
        } catch (IllegalStateException e) {
            if (DEBUG) e.printStackTrace();
        }

        return false;
    }

    private boolean pauseStream() {
        if (mAudioPlayer != null) {
            mAudioPlayer.stop();
        }
        if (mVideoPlayer != null) {
            if (Build.VERSION.SDK_INT <= 18) {
                throw new RuntimeException("API not available");
            }
            mVideoPlayer.stop();
        }
        setPlayingState(false);
        return true;
    }

    public boolean isPlaying() {
        return mIsPlaying;
    }

    private void setPlayingState(boolean state) {
        if (mIsPlaying != state) {
            mIsPlaying = state;
            if (mEventListener != null) {
                if (mIsPlaying) {
                    mEventListener.onPlay();
                } else {
                    mEventListener.onPause();
                }
            }
        }
    }

    public void setShouldMirror(boolean mirror) {
        mIsMirrored = mirror;
    }

    public boolean isMirrored() {
        return mIsMirrored;
    }

    public void setShouldLoop(boolean loop) {
        mIsLooping = loop;
    }

    public boolean isLooping() {
        return mIsLooping;
    }

    public void setVolume(float volume) {
        ensureMediaPlayer();
        Objects.requireNonNull(mMediaPlayer).setVolume(volume, volume);
    }

    void transformPreview(int videoWidth, int videoHeight) {
        int viewWidth = getWidth();
        int viewHeight = getHeight();

        Matrix matrix = new Matrix();

        float aspectRatio = (float) videoHeight / (float) videoWidth;
        int newWidth, newHeight;
        if (viewHeight > viewWidth * aspectRatio) {
            newWidth = (int) (viewHeight / aspectRatio);
            newHeight = viewHeight;
        } else {
            newWidth = viewWidth;
            newHeight = (int) (viewWidth * aspectRatio);
        }

        float scaleX = (float) newWidth / (float) viewWidth;
        float scaleY = (float) newHeight / (float) viewHeight;

        int translateX = (viewWidth - newWidth) / 2;
        int translateY = (viewHeight - newHeight) / 2;

        matrix.setScale(scaleX, scaleY);
        matrix.postTranslate(translateX, translateY);

        if (isMirrored()) {
            matrix.postScale(-1, 1);
            matrix.postTranslate(viewWidth, 0);
        }

        if (DEBUG) {
            Log.d(TAG, String.format("Result: viewAspectRatio=%s, videoAspectRatio=%s, "
                            + "viewWidth=%s, viewHeight=%s, videoWidth=%s, videoHeight=%s, "
                            + "newWidth=%s, newHeight=%s, scaleX=%s, scaleY=%s, translateX=%s, "
                            + "translateY=%s",
                    ((float) viewHeight / (float) viewWidth), aspectRatio, viewWidth, viewHeight,
                    videoWidth, videoHeight, newWidth, newHeight, scaleX, scaleY, translateX, translateY));
        }

        mTextureView.setTransform(matrix);
    }

    private void createTextureView() {
        // Destroy the TextureView we used for the previous round of video activity. This is
        // because the TextureView will continue to show a bitmap of the old view until the video
        // is able to draw to it again. We'd rather clear the TextureView, but since there's no such
        // way, we destroy it instead.
        if (mTextureView != null) {
            removeView(mTextureView);
        }
        mIsAvailable = false;
        addView(mTextureView = new TextureView(getContext()), 0);
        mTextureView.setSurfaceTextureListener(this);
    }

    private void onTap() {
        if (isPlaying()) {
            pause();
        } else {
            play();
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!super.onTouchEvent(event)) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    setPressed(true);
                    break;
                case MotionEvent.ACTION_CANCEL:
                    setPressed(false);
                    break;
                case MotionEvent.ACTION_UP:
                    onTap();
                    setPressed(false);
                    break;
            }
        }
        return true;
    }

    private void transformPreview(int previewWidth, int previewHeight, int cameraOrientation, boolean flipped) {
        int viewWidth = getWidth();
        int viewHeight = getHeight();
        int displayOrientation = getDisplayRotation();

        // Camera2 rotates the preview to always face in portrait mode, even if the phone is
        // currently in landscape. This is great for portrait mode, because there's less work to be done.
        // It's less great for landscape, because we have to undo it. Without any matrix modifications,
        // the preview will be smushed into the aspect ratio of the view.
        Matrix matrix = new Matrix();

        // Camera2 reverses the preview width/height.
        if (cameraOrientation != 0 && cameraOrientation != 180) {
            int temp = previewWidth;
            previewWidth = previewHeight;
            previewHeight = temp;
        }

        // We want to find the aspect ratio of the preview. Our goal is to stretch the image in
        // our SurfaceView to match this ratio, so that the image doesn't looked smushed.
        // This means the edges of the preview will be cut off.
        float aspectRatio = (float) previewHeight / (float) previewWidth;
        int newWidth, newHeight;
        if (viewHeight > viewWidth * aspectRatio) {
            newWidth = (int) Math.ceil(viewHeight / aspectRatio);
            newHeight = viewHeight;
        } else {
            newWidth = viewWidth;
            newHeight = (int) Math.ceil(viewWidth * aspectRatio);
        }

        // For portrait, we've already been mostly stretched. For landscape, our image is rotated 90 degrees.
        // Think of it as a sideways squished photo. We want to first streeeetch the height of the photo
        // until it matches the aspect ratio we originally expected. Now we're no longer stretched
        // (although we're wildly off screen, with only the far left sliver of the photo still
        // visible on the screen, and our picture is still sideways).
        float scaleX = (float) newWidth / (float) viewWidth;
        float scaleY = (float) newHeight / (float) viewHeight;

        // However, we've actually stretched too much. The height of the picture is currently the
        // width of our screen. When we rotate the picture, it'll be too large and we'll end up
        // cropping a lot of the picture. That's what this step is for. We scale down the image so
        // that the height of the photo (currently the width of the phone) becomes the height we
        // want (the height of the phone, or slightly bigger, depending on aspect ratio).
        float scale = 1f;
        if (cameraOrientation == 90 || cameraOrientation == 270) {
            boolean cropHeight = viewWidth > newHeight * viewHeight / newWidth;
            if (cropHeight) {
                // If we're cropping the top/bottom, then we want the widths to be exact
                scale = (float) viewWidth / newHeight;
            } else {
                // If we're cropping the left/right, then we want the heights to be exact
                scale = (float) viewHeight / newWidth;
            }
            newWidth = (int) Math.ceil(newWidth * scale);
            newHeight = (int) Math.ceil(newHeight * scale);
            scaleX *= scale;
            scaleY *= scale;
        }

        // Because we scaled the preview beyond the bounds of the view, we need to crop some of it.
        // By translating the photo over, we'll move it into the center.
        int translateX = (int) Math.ceil((viewWidth - newWidth) / 2d);
        int translateY = (int) Math.ceil((viewHeight - newHeight) / 2d);

        // Due to the direction of rotation (90 vs 270), a 1 pixel offset can either put us
        // exactly where we want to be, or it can put us 1px lower than we wanted. This is error
        // correction for that.
        if (cameraOrientation == 270) {
            translateX = (int) Math.floor((viewWidth - newWidth) / 2d);
            translateY = (int) Math.floor((viewHeight - newHeight) / 2d);
        }

        // Finally, with our photo scaled and centered, we apply a rotation.
        int rotation = cameraOrientation;

        matrix.setScale(scaleX, scaleY);
        matrix.postTranslate(translateX, translateY);
        matrix.postRotate(rotation, (int) Math.ceil(viewWidth / 2d), (int) Math.ceil(viewHeight / 2d));
        if (flipped) {
            matrix.postScale(-1, 1, (int) Math.ceil(viewWidth / 2d), (int) Math.ceil(viewHeight / 2d));
        }

        if (DEBUG) {
            Log.d(TAG, String.format("transformPreview: displayOrientation=%s, cameraOrientation=%s, "
                            + "viewWidth=%s, viewHeight=%s, viewAspectRatio=%s, previewWidth=%s, previewHeight=%s, previewAspectRatio=%s, "
                            + "newWidth=%s, newHeight=%s, scaleX=%s, scaleY=%s, scale=%s, "
                            + "translateX=%s, translateY=%s, rotation=%s",
                    displayOrientation, cameraOrientation, viewWidth, viewHeight,
                    ((float) viewHeight / (float) viewWidth), previewWidth, previewHeight, aspectRatio,
                    newWidth, newHeight, scaleX, scaleY, scale, translateX, translateY, rotation));
        }

        mTextureView.setTransform(matrix);
    }

    /**
     * @return One of 0, 90, 180, 270.
     */
    protected int getDisplayRotation() {
        Display display;
        if (Build.VERSION.SDK_INT >= 17) {
            display = getDisplay();
        } else {
            display = ((WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        }

        // Null when the View is detached. If we were in the middle of a background operation,
        // better to not NPE. When the background operation finishes, it'll realize that the camera
        // was closed.
        if (display == null) {
            return 0;
        }

        int displayRotation = display.getRotation();
        switch (displayRotation) {
            case Surface.ROTATION_0:
                displayRotation = 0;
                break;
            case Surface.ROTATION_90:
                displayRotation = 90;
                break;
            case Surface.ROTATION_180:
                displayRotation = 180;
                break;
            case Surface.ROTATION_270:
                displayRotation = 270;
                break;
        }
        return displayRotation;
    }

    public interface EventListener {
        void onPlay();
        void onPause();
    }

    private enum InputType {
        UNKNOWN, FILE, STREAM
    }
}
