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
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;

public class VideoView extends TextureView implements TextureView.SurfaceTextureListener {
    private static final String TAG = VideoView.class.getSimpleName();
    private static final boolean DEBUG = false;

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

    // If true, the video should be mirrored
    private boolean mIsMirrored = false;

    // Used in hiding the surface. This matrix contains the original transformation.
    @Nullable private Matrix mOriginalMatrix;

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
        setSurfaceTextureListener(this);

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

    public File getFile() {
        return mFile;
    }

    public void setFile(File file) {
        if (DEBUG) Log.d(TAG, "File set to " + file);
        this.mFile = file;
        if (mIsAvailable) {
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
        mIsPlaying = false;

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

        mMediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mediaPlayer) {
                mIsPlaying = false;
                if (mOnCompletionListener != null) {
                    mOnCompletionListener.onCompletion(mediaPlayer);
                }
            }
        });
    }

    public void setOnCompletionListener(MediaPlayer.OnCompletionListener listener) {
        mOnCompletionListener = listener;
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
            retriever.release();
            if (DEBUG) Log.d(TAG, String.format("Video metadata: width=%d, height=%d", width, height));

            transformPreview(width, height);

            Surface surface = new Surface(getSurfaceTexture());

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
        hideSurface();

        try {
            mMediaPlayer.setOnSeekCompleteListener(new MediaPlayer.OnSeekCompleteListener() {
                @Override
                public void onSeekComplete(MediaPlayer mediaPlayer) {
                    if (DEBUG) Log.d(TAG, "Seek completed");
                    mMediaPlayer.setOnSeekCompleteListener(null);
                    if (!mIsPlaying) {
                        mediaPlayer.pause();
                    }
                    showSurface();
                }
            });
            mMediaPlayer.start();
            mMediaPlayer.seekTo(1);
        } catch (IllegalStateException e) {
            if (DEBUG) e.printStackTrace();
        }
    }

    public boolean play() {
        if (DEBUG) Log.d(TAG, "play()");
        ensureMediaPlayer();

        try {
            mMediaPlayer.start();
            mIsPlaying = true;
            return true;
        } catch (IllegalStateException e) {
            if (DEBUG) e.printStackTrace();
        }
        return false;
    }

    public boolean pause() {
        if (DEBUG) Log.d(TAG, "pause()");
        ensureMediaPlayer();
        mIsPlaying = false;

        try {
            mMediaPlayer.pause();
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

    public void setShouldMirror(boolean mirror) {
        mIsMirrored = mirror;
    }

    public boolean isMirrored() {
        return mIsMirrored;
    }

    void transformPreview(int videoWidth, int videoHeight) {
        int viewWidth = getWidth();
        int viewHeight = getHeight();

        Matrix matrix = new Matrix();
        getTransform(matrix);

        int displayOrientation = getDisplayRotation();
        if (displayOrientation != Surface.ROTATION_90 && displayOrientation != Surface.ROTATION_270) {
            int temp = videoWidth;
            videoWidth = videoHeight;
            videoHeight = temp;
        }

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

        int translateX = (int) (viewWidth - newWidth) / 2;
        int translateY = (int) (viewHeight - newHeight) / 2;

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

        setTransform(matrix);
    }


    public int getDisplayRotation() {
        Display display;
        if (Build.VERSION.SDK_INT >= 17) {
            display = getDisplay();
        } else {
            display = ((WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        }
        return display.getRotation();
    }

    // Hides the Surface, until it's fully prepared.
    private void hideSurface() {
        if (mOriginalMatrix == null) {
            Matrix matrix = new Matrix();
            getTransform(matrix);
            mOriginalMatrix = new Matrix(matrix);
            matrix.postScale(0, 0);
            setTransform(matrix);
        }
    }

    // Translates the Surface back onto the screen, once it's ready to be shown.
    private void showSurface() {
        if (mOriginalMatrix != null) {
            setTransform(mOriginalMatrix);
            mOriginalMatrix = null;
        }
    }
}
