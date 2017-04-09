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
        play();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {}

    private void play() {
        Surface surface = new Surface(getSurfaceTexture());

        try {
            mMediaPlayer.setDataSource(new FileInputStream(mFile).getFD());
            mMediaPlayer.setSurface(surface);
            mMediaPlayer.prepare();
            mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mMediaPlayer.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
