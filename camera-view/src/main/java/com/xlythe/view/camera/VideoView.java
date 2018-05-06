package com.xlythe.view.camera;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Build;
import android.support.annotation.CheckResult;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;

public class VideoView extends FrameLayout implements TextureView.SurfaceTextureListener {
    private static final String TAG = VideoView.class.getSimpleName();
    private static final boolean DEBUG = false;

    // The view we draw the video on to
    private TextureView mTextureView;

    // Controls video playback
    private MediaPlayer mMediaPlayer;

    // The file of the video to play
    private File mFile;

    // If true, the texture view is ready to be drawn on
    private boolean mIsAvailable;

    // If true, we should be playing
    private boolean mIsPlaying;

    // An optional listener for when videos have reached the end
    @Nullable private MediaPlayer.OnCompletionListener mOnCompletionListener;

    // An optional listener for when videos are paused/played
    @Nullable private EventListener mEventListener;

    // If true, the video should be mirrored
    private boolean mIsMirrored = false;

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
        if (file == mFile || (file != null && file.equals(mFile))) {
            return;
        }

        if (DEBUG) Log.d(TAG, "File set to " + file);
        this.mFile = file;
        createTextureView();
        if (file != null && mIsAvailable) {
            prepare();
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        if (DEBUG) Log.d(TAG, "Texture available");
        mIsAvailable = true;
        if (mFile != null) {
            prepare();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        if (DEBUG) Log.d(TAG, "Texture destroyed");
        mIsAvailable = false;
        setPlayingState(false);

        ensureMediaPlayer();
        mMediaPlayer.release();
        mMediaPlayer = null;

        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {}

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
                mMediaPlayer.stop();
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

    private int extractAsInt(MediaMetadataRetriever retriever, int key) {
        String metadata = retriever.extractMetadata(key);
        if (metadata == null) {
            return 0;
        }
        return Integer.valueOf(metadata);
    }

    public void seekToFirstFrame() {
        if (DEBUG) Log.d(TAG, "seekToFirstFrame()");
        ensureMediaPlayer();

        try {
            mMediaPlayer.setOnSeekCompleteListener(mediaPlayer -> {
                if (DEBUG) Log.d(TAG, "Seek completed");
                mMediaPlayer.setOnSeekCompleteListener(null);
                if (!mIsPlaying) {
                    mediaPlayer.pause();
                }
            });
            mMediaPlayer.start();
            mMediaPlayer.seekTo(1);
        } catch (IllegalStateException e) {
            if (DEBUG) e.printStackTrace();
        }
    }

    @CheckResult
    public boolean play() {
        if (DEBUG) Log.d(TAG, "play()");
        ensureMediaPlayer();

        try {
            mMediaPlayer.start();
            setPlayingState(true);
            return true;
        } catch (IllegalStateException e) {
            if (DEBUG) e.printStackTrace();
        }
        return false;
    }

    @CheckResult
    public boolean pause() {
        if (DEBUG) Log.d(TAG, "pause()");
        ensureMediaPlayer();

        try {
            mMediaPlayer.pause();
            setPlayingState(false);
            return true;
        } catch (IllegalStateException e) {
            if (DEBUG) e.printStackTrace();
        }

        return false;
    }

    public boolean isPlaying() {
        ensureMediaPlayer();
        try {
            return mMediaPlayer.isPlaying();
        } catch (IllegalStateException e) {
            if (DEBUG) e.printStackTrace();
        }
        return false;
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

    public void setVolume(float volume) {
        ensureMediaPlayer();
        mMediaPlayer.setVolume(volume, volume);
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

    public interface EventListener {
        void onPlay();
        void onPause();
    }
}
