package com.xlythe.view.camera;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.TextureView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class VideoView extends TextureView implements TextureView.SurfaceTextureListener {
    private final File mFile;
    private final MediaPlayer mMediaPlayer = new MediaPlayer();

    public VideoView(Context context, File file) {
        super(context);
        mFile = file;
        setSurfaceTextureListener(this);
    }

    public MediaPlayer getMediaPlayer() {
        return mMediaPlayer;
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        prepare();
        play();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        mMediaPlayer.release();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {}

    protected void prepare() {
        Surface surface = new Surface(getSurfaceTexture());

        try {
            mMediaPlayer.setDataSource(new FileInputStream(mFile).getFD());
            mMediaPlayer.setSurface(surface);
            mMediaPlayer.prepare();
            mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        } catch (IllegalStateException | IOException e) {
            e.printStackTrace();
        }
    }

    protected boolean play() {
        try {
            mMediaPlayer.start();
            return true;
        } catch (IllegalStateException e) {
            e.printStackTrace();
        }
        return false;
    }

    protected boolean pause() {
        try {
            mMediaPlayer.pause();
            return true;
        } catch (IllegalStateException e) {
            e.printStackTrace();
        }
        return false;
    }

    protected boolean isPlaying() {
        return mMediaPlayer.isPlaying();
    }
}
