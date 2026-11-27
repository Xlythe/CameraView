package com.xlythe.view.camera;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.media.MediaMetadataRetriever;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.RequiresApi;

import com.xlythe.view.camera.stream.AudioPlayer;
import com.xlythe.view.camera.stream.VideoPlayer;

public class VideoView extends FrameLayout implements TextureView.SurfaceTextureListener {
    private static final String TAG = VideoView.class.getSimpleName();
    private static final boolean DEBUG = false;

    // ---------- General ----------

    /** Delivers player callbacks and view mutations on the UI thread. */
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    // The view we draw the video on to
    private TextureView mTextureView;
    // Whether to play a file (or a uri, which plays the same way) or a stream
    private InputType mInputType = InputType.UNKNOWN;
    // If true, the texture view is ready to be drawn on
    private boolean mIsAvailable;
    /** True while an already-cleaned texture is being replaced with a fresh preview surface. */
    private boolean mIsReplacingTextureView;
    // If true, we should be playing
    private boolean mIsPlaying;
    // An optional listener for when videos are paused/played
    @Nullable private EventListener mEventListener;
    // If true, the video should be mirrored
    private boolean mIsMirrored = false;
    // The last known video width.
    private int mVideoWidth;
    // The last known video height.
    private int mVideoHeight;
    // The last known video orientation.
    private int mVideoOrientation;
    // The last known info about the video being mirrored.
    private boolean mVideoMirrored;

    // ---------- File ----------

    // Controls video playback
    @Nullable private MediaPlayer mMediaPlayer;
    // The file of the video to play
    @Nullable private File mFile;
    // The uri of the video to play, such as one MediaStore handed back. Set instead of mFile.
    @Nullable private Uri mUri;
    // An optional listener for when videos have reached the end
    @Nullable private MediaPlayer.OnCompletionListener mOnCompletionListener;
    // If true, the video should loop
    private boolean mIsLooping = false;
    /**
     * Reads container metadata off the main thread.
     *
     * <p>Parsing a container blocks for as long as it takes to read its headers, which is long
     * enough to drop frames and, on a large or damaged file, long enough to be an ANR.</p>
     */
    @Nullable private ExecutorService mPrepareExecutor;
    /**
     * Invalidates work from an earlier prepare, so metadata for a file that has since been
     * replaced cannot configure the player.
     */
    private int mPrepareGeneration;
    /** Held open until the player is prepared, because MediaPlayer reads from it until then. */
    @Nullable private FileInputStream mPreparingInputStream;

    // ---------- Stream ----------

    // The stream to play
    @Nullable private VideoStream mVideoStream;
    // The latest prepared stream. Used to avoid a race condition where the texture is destroyed and cleans up the wrong stream.
    @Nullable private VideoStream mPreparedVideoStream;
    // Plays the audio half of the VideoStream
    @Nullable private AudioPlayer mAudioPlayer;
    // Plays the video half of the VideoStream
    @Nullable private VideoPlayer mVideoPlayer;
    /** True after the prepared stream audio channel reaches end-of-stream. */
    private boolean mAudioStreamEnded;
    /** True after the prepared stream video channel reaches end-of-stream. */
    private boolean mVideoStreamEnded;

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

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed) {
            if (mVideoWidth == 0 || mVideoHeight == 0) {
                // Ignore layout changed event. Video isn't prepared yet.
                return;
            }

            switch (mInputType) {
                case FILE:
                    transformPreview(mVideoWidth, mVideoHeight);
                    break;
                case STREAM:
                    transformPreview(mVideoWidth, mVideoHeight, mVideoOrientation, mVideoMirrored);
                    break;
                case UNKNOWN:
                    // ignored
                    break;
            }
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
        if (Objects.equals(file, mFile) && mUri == null && mInputType == InputType.FILE) {
            return;
        }

        if (DEBUG) Log.d(TAG, "File set to " + file);
        if (mInputType == InputType.STREAM) {
            releaseStreamPlayback(true);
        } else {
            releaseMediaPlayer();
        }
        mFile = file;
        mUri = null;
        mVideoStream = null;
        mInputType = InputType.FILE;
        createTextureView();
        if (file != null && mIsAvailable) {
            prepare();
        }
    }

    /**
     * Plays a video by uri rather than by file, such as the content uri
     * {@link CameraView.OnVideoCapturedListener#onVideoSaved(Uri)} reports.
     */
    public void setUri(@Nullable Uri uri) {
        if (Objects.equals(uri, mUri) && mFile == null && mInputType == InputType.FILE) {
            return;
        }

        if (DEBUG) Log.d(TAG, "Uri set to " + uri);
        if (mInputType == InputType.STREAM) {
            releaseStreamPlayback(true);
        } else {
            releaseMediaPlayer();
        }
        mUri = uri;
        mFile = null;
        mVideoStream = null;
        mInputType = InputType.FILE;
        createTextureView();
        if (uri != null && mIsAvailable) {
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
        Objects.requireNonNull(videoStream, "VideoStream cannot be null");
        if (videoStream == mVideoStream && mInputType == InputType.STREAM) {
            return;
        }

        if (DEBUG) Log.d(TAG, "Stream set");
        if (mInputType == InputType.STREAM) {
            releaseStreamPlayback(true);
        } else {
            releaseMediaPlayer();
        }
        mVideoStream = videoStream;
        mFile = null;
        mUri = null;
        mInputType = InputType.STREAM;
        createTextureView();
        if (mIsAvailable) {
            prepare();
        }
        if (DEBUG) Log.d(TAG, "Stream ready");
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        if (DEBUG) Log.d(TAG, "Texture available");
        mIsAvailable = true;
        if (mFile != null || mUri != null || mVideoStream != null) {
            if (DEBUG) Log.d(TAG, "Preparing file or stream");
            prepare();
        }
        if (DEBUG) Log.d(TAG, "onSurfaceTextureAvailable: " + mFile);
        if (DEBUG) Log.d(TAG, "onSurfaceTextureAvailable: " + mVideoStream);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {}

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        if (DEBUG) Log.d(TAG, "Texture destroyed");
        mIsAvailable = false;
        if (mIsReplacingTextureView) {
            return true;
        }
        setPlayingState(false);

        switch (mInputType) {
            case FILE:
                releaseMediaPlayer();
                break;
            case STREAM:
                releaseStreamPlayback(true);
                break;
            case UNKNOWN:
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
                if (DEBUG) Log.d(TAG, "Oops");
                throw new IllegalStateException("Must set a file to stream before preparing");
            case FILE:
                if (DEBUG) Log.d(TAG, "Preparing file");
                prepareFile();
                break;
            case STREAM:
                if (DEBUG) Log.d(TAG, "Preparing stream");
                prepareStream();
                break;
        }
    }

    protected void prepareFile() {
        if (DEBUG) Log.d(TAG, "Preparing video");
        File file = mFile;
        Uri uri = mUri;
        SurfaceTexture surfaceTexture = mTextureView == null ? null : mTextureView.getSurfaceTexture();
        if ((file == null && uri == null) || surfaceTexture == null) {
            Log.w(TAG, "Cannot prepare video without a file and surface");
            return;
        }

        // Reading the container and preparing the decoder both block for as long as the file
        // takes to parse, so neither belongs on the thread drawing the UI. Read the metadata on a
        // worker, then configure the player and let it prepare asynchronously.
        int generation = ++mPrepareGeneration;
        prepareExecutor().execute(() -> {
            VideoMetadata metadata = readMetadata(file, uri);
            mMainHandler.post(() -> {
                if (generation != mPrepareGeneration) {
                    if (DEBUG) Log.d(TAG, "Discarding metadata for a replaced video");
                    return;
                }
                if (metadata == null) {
                    setPlayingState(false);
                    return;
                }
                startPreparing(file, uri, surfaceTexture, metadata, generation);
            });
        });
    }

    /** Container dimensions, already corrected for the rotation recorded in the file. */
    private static final class VideoMetadata {
        final int width;
        final int height;

        VideoMetadata(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    /** Reads the file's metadata, or the uri's when there is no file. */
    @Nullable
    private VideoMetadata readMetadata(@Nullable File file, @Nullable Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try (FileInputStream inputStream = file == null ? null : new FileInputStream(file)) {
            if (inputStream != null) {
                retriever.setDataSource(inputStream.getFD());
            } else {
                retriever.setDataSource(getContext(), uri);
            }
            int width = extractAsInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            int height = extractAsInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            int rotation = extractAsInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (DEBUG) Log.d(TAG, String.format(
                    "Video metadata: width=%d, height=%d, rotation=%d", width, height, rotation));
            return rotation == 90 || rotation == 270
                    ? new VideoMetadata(height, width)
                    : new VideoMetadata(width, height);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Failed to read video metadata from " + (file == null ? uri : file), e);
            return null;
        } finally {
            try {
                retriever.release();
            } catch (IOException e) {
                Log.w(TAG, "Failed to release video metadata retriever", e);
            }
        }
    }

    /** Prepares the player on the file, or on the uri when there is no file. */
    @UiThread
    private void startPreparing(@Nullable File file, @Nullable Uri uri,
            SurfaceTexture surfaceTexture, VideoMetadata metadata, int generation) {
        Object source = file == null ? uri : file;
        ensureMediaPlayer();
        transformPreview(metadata.width, metadata.height);

        closePreparingInputStream();
        Surface surface = new Surface(surfaceTexture);
        try {
            try {
                Objects.requireNonNull(mMediaPlayer).stop();
            } catch (IllegalStateException ignored) {
                // The newly created player has not entered the started state yet.
            }
            mMediaPlayer.reset();

            if (file != null) {
                // MediaPlayer reads from the descriptor until it finishes preparing, so unlike
                // the synchronous path this stream cannot be closed when the call returns.
                FileInputStream inputStream = new FileInputStream(file);
                mPreparingInputStream = inputStream;
                mMediaPlayer.setDataSource(inputStream.getFD());
            } else {
                mMediaPlayer.setDataSource(getContext(), uri);
            }
            mMediaPlayer.setSurface(surface);
            mMediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build());
            mMediaPlayer.setOnPreparedListener(mediaPlayer -> {
                mediaPlayer.setOnPreparedListener(null);
                closePreparingInputStream();
                if (generation != mPrepareGeneration) {
                    return;
                }
                seekToFirstFrame();
                if (mIsPlaying) {
                    playFile();
                }
            });
            mMediaPlayer.setOnErrorListener((mediaPlayer, what, extra) -> {
                Log.e(TAG, "Failed to prepare video " + source
                        + " (what=" + what + ", extra=" + extra + ")");
                closePreparingInputStream();
                setPlayingState(false);
                return true;
            });
            mMediaPlayer.prepareAsync();
        } catch (IllegalStateException | IOException e) {
            Log.e(TAG, "Failed to prepare video " + source, e);
            closePreparingInputStream();
            setPlayingState(false);
        } finally {
            surface.release();
        }
    }

    private void closePreparingInputStream() {
        FileInputStream inputStream = mPreparingInputStream;
        mPreparingInputStream = null;
        if (inputStream == null) {
            return;
        }
        try {
            inputStream.close();
        } catch (IOException e) {
            Log.w(TAG, "Failed to close the video input stream", e);
        }
    }

    private ExecutorService prepareExecutor() {
        if (mPrepareExecutor == null) {
            mPrepareExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "VideoView-Prepare");
                thread.setDaemon(true);
                return thread;
            });
        }
        return mPrepareExecutor;
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

        mAudioStreamEnded = !videoStream.hasAudio();
        mVideoStreamEnded = !videoStream.hasVideo();
        if (videoStream.hasAudio()) {
            mAudioPlayer = new AudioPlayer(videoStream.getAudioInputStream());
            mAudioPlayer.setStreamEndListener(() -> mMainHandler.post(() ->
                    onStreamEnded(videoStream, true)));
        }

        if (videoStream.hasVideo()) {
            @SuppressLint("Recycle") Surface surface = new Surface(mTextureView.getSurfaceTexture());
            mVideoPlayer = new VideoPlayer(surface, videoStream.getVideoInputStream());
            mVideoPlayer.setStreamEndListener(() -> mMainHandler.post(() ->
                    onStreamEnded(videoStream, false)));
            mVideoPlayer.setOnMetadataAvailableListener((width, height, orientation, flipped) ->
                    mMainHandler.post(() -> {
                        if (mPreparedVideoStream == videoStream) {
                            transformPreview(width, height, orientation, flipped);
                        }
                    }));
        }

        mPreparedVideoStream = videoStream;
        if (mIsPlaying) {
            playStream();
        }
    }

    private void onStreamEnded(VideoStream videoStream, boolean audio) {
        if (mPreparedVideoStream != videoStream) {
            videoStream.close();
            return;
        }
        if (audio) {
            mAudioStreamEnded = true;
        } else {
            mVideoStreamEnded = true;
        }
        if (mAudioStreamEnded && mVideoStreamEnded) {
            setPlayingState(false);
            releaseStreamPlayback(true);
        }
    }

    private void releaseMediaPlayer() {
        // Invalidate any prepare still in flight, so its metadata cannot configure a player that
        // has gone away, and drop the descriptor it was holding open.
        mPrepareGeneration++;
        closePreparingInputStream();

        MediaPlayer mediaPlayer = mMediaPlayer;
        mMediaPlayer = null;
        if (mediaPlayer != null) {
            mediaPlayer.release();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        ExecutorService executor = mPrepareExecutor;
        mPrepareExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
        }

        // A view that is gone must not still be decoding. On a device the SurfaceTexture is
        // destroyed on the way out and that callback releases everything, but it is a callback
        // from the platform about a texture, not a statement about this view: a detach that does
        // not destroy the texture would leave a decoder running and audio playing from a view
        // nobody can see. Releasing here says it directly. The file is kept, so reattaching
        // prepares again.
        setPlayingState(false);
        switch (mInputType) {
            case FILE:
                releaseMediaPlayer();
                break;
            case STREAM:
                releaseStreamPlayback(true);
                break;
            case UNKNOWN:
                break;
        }
        super.onDetachedFromWindow();
    }

    @RequiresApi(18)
    private void releaseStreamPlayback(boolean closeStream) {
        AudioPlayer audioPlayer = mAudioPlayer;
        VideoPlayer videoPlayer = mVideoPlayer;
        VideoStream preparedStream = mPreparedVideoStream;
        VideoStream currentStream = mVideoStream;
        mAudioPlayer = null;
        mVideoPlayer = null;
        mPreparedVideoStream = null;
        if (audioPlayer != null) {
            audioPlayer.stop();
        }
        if (videoPlayer != null) {
            videoPlayer.stop();
        }
        if (closeStream) {
            if (preparedStream != null) {
                preparedStream.close();
            }
            if (currentStream != null && currentStream != preparedStream) {
                currentStream.close();
            }
            mVideoStream = null;
        }
        setPlayingState(false);
    }

    private int extractAsInt(MediaMetadataRetriever retriever, int key) {
        String metadata = retriever.extractMetadata(key);
        if (metadata == null) {
            return 0;
        }
        try {
            return Integer.parseInt(metadata);
        } catch (NumberFormatException e) {
            Log.w(TAG, "Invalid numeric video metadata: " + metadata, e);
            return 0;
        }
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
        float clampedVolume = Math.max(0f, Math.min(volume, 1f));
        Objects.requireNonNull(mMediaPlayer).setVolume(clampedVolume, clampedVolume);
    }

    private void createTextureView() {
        // Destroy the TextureView we used for the previous round of video activity. This is
        // because the TextureView will continue to show a bitmap of the old view until the video
        // is able to draw to it again. We'd rather clear the TextureView, but since there's no such
        // way, we destroy it instead.
        if (mTextureView != null) {
            mIsReplacingTextureView = true;
            try {
                removeView(mTextureView);
            } finally {
                mIsReplacingTextureView = false;
            }
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
        if (mInputType == InputType.STREAM) {
            return super.onTouchEvent(event);
        }

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

    void transformPreview(int videoWidth, int videoHeight) {
        transformPreview(videoWidth, videoHeight, 0 /* orientation */, false /* flipped */);
    }

    /**
     * Fits the video to the view, upright and filling it.
     *
     * <p>The same centre-crop every camera preview in this library goes through, rather than a
     * second copy of the arithmetic: this method used to hand-roll the scale, the translation and
     * the rotation, down to a one-pixel correction for the difference between 90 and 270 degrees,
     * and it got a different answer from the previews it sat next to.</p>
     *
     * @param cameraOrientation how far the video has to be rotated to be upright
     * @param flipped whether the video was recorded mirrored, as a front camera records it
     */
    void transformPreview(
            int videoWidth, int videoHeight, int cameraOrientation, boolean flipped) {
        mVideoWidth = videoWidth;
        mVideoHeight = videoHeight;
        mVideoOrientation = cameraOrientation;
        mVideoMirrored = flipped;

        int viewWidth = getWidth();
        int viewHeight = getHeight();
        if (videoWidth <= 0 || videoHeight <= 0 || viewWidth <= 0 || viewHeight <= 0
                || mTextureView == null) {
            return;
        }

        // Asking for a mirrored view of a mirrored recording shows it the way round it was
        // taken, so the two cancel.
        boolean mirrored = flipped != isMirrored();
        Size frame = new Size(videoWidth, videoHeight);
        mTextureView.setTransform(TransformMath.asTextureTransform(
                TransformMath.centerCrop(
                        new RectF(0f, 0f, videoWidth, videoHeight),
                        TransformMath.within360(cameraOrientation),
                        mirrored, viewWidth, viewHeight),
                frame, viewWidth, viewHeight));
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
