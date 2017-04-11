package com.xlythe.view.camera;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class VideoView extends TextureView implements TextureView.SurfaceTextureListener {
    private static final String TAG = "VideoView";
    private static final boolean DEBUG = true;

    private final MediaPlayer mMediaPlayer = new MediaPlayer();

    private File mFile;

    // If true, the texture view is ready to be drawn on
    private boolean mIsAvailable;

    // If true, we should be playing
    private boolean mIsPlaying;

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
        mMediaPlayer.release();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {}

    protected void prepare() {
        if (DEBUG) Log.d(TAG, "Preparing video");
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

        try {
            mMediaPlayer.setDataSource(new FileInputStream(mFile).getFD());
            mMediaPlayer.setSurface(surface);
            mMediaPlayer.prepare();
            mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        } catch (IllegalStateException | IOException e) {
            e.printStackTrace();
        }

        seekToFirstFrame();
    }

    public void seekToFirstFrame() {
        if (DEBUG) Log.d(TAG, "seekToFirstFrame()");
        try {
            mMediaPlayer.setOnSeekCompleteListener(new MediaPlayer.OnSeekCompleteListener() {
                @Override
                public void onSeekComplete(MediaPlayer mediaPlayer) {
                    if (DEBUG) Log.d(TAG, "Seek completed");
                    mMediaPlayer.setOnSeekCompleteListener(null);
                    if (!mIsPlaying) {
                        mediaPlayer.pause();
                    }
                }
            });
            mMediaPlayer.start();
            mMediaPlayer.seekTo(mMediaPlayer.getCurrentPosition() + 1);
        } catch (IllegalStateException e) {
            if (DEBUG) e.printStackTrace();
        }
    }

    public boolean play() {
        if (DEBUG) Log.d(TAG, "play()");
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
        try {
            mMediaPlayer.pause();
            return true;
        } catch (IllegalStateException e) {
            if (DEBUG) e.printStackTrace();
        }
        return false;
    }

    public boolean isPlaying() {
        try {
            return mMediaPlayer.isPlaying();
        } catch (IllegalStateException e) {
            if (DEBUG) e.printStackTrace();
        }
        return false;
    }
}
