package com.xlythe.view.camera.viewer;

import static com.xlythe.view.camera.ICameraModule.TAG;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.ColorInt;
import androidx.annotation.IntDef;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.Px;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.xlythe.view.camera.HdrColorMode;
import com.xlythe.view.camera.R;
import com.xlythe.view.camera.TransformMath;
import com.xlythe.view.camera.VideoView;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Shows any picture, video or sound file the way it is meant to be seen, like
 * {@link android.widget.VideoView} for everything. It works out what the file is, once and off
 * the main thread, from its contents rather than its name:
 *
 * <ul>
 *   <li>a picture of any size, a DNG included, to pinch and pan;</li>
 *   <li>a panorama to pan along, and a photo sphere to look around, told apart by how much of
 *       the sphere their photo sphere metadata covers, or a picture far wider than it is tall
 *       taken for a panorama when it has none;</li>
 *   <li>a video, which a tap plays and pauses;</li>
 *   <li>a motion photo, whose clip plays while it is held;</li>
 *   <li>a 360° video, to look around while it plays;</li>
 *   <li>sound, showing its cover picture if it has one, which a tap plays and pauses;</li>
 *   <li>an animated GIF, WebP or PNG, which plays as soon as it shows and a tap pauses.</li>
 * </ul>
 *
 * <p>A file it cannot show, it reports to {@link MediaListener#onMediaFailed()} and shows an
 * error placeholder in place of. That includes a video this device has no decoder for, which
 * would otherwise play its sound over nothing, and one whose decoder gives up while it plays.</p>
 *
 * <p>HDR pictures and videos put the window in HDR colour mode while they show, where the device
 * has one, and it goes back once nothing in the window shows HDR. A picture's gain map is drawn
 * in HDR; a video is drawn tone-mapped to standard range.</p>
 *
 * <h2>What each release shows</h2>
 * <ul>
 *   <li>JPEG, PNG, WebP, GIF and BMP pictures, animated GIFs and animated PNGs, on every
 *       release; before Android 9 an animated GIF plays through the library's own decoder, a
 *       frame at a time.</li>
 *   <li>HEIF and HEIC pictures, and animated WebP and HEIF sequences, from Android 9. Before it,
 *       an animated WebP shows as a still only where the platform decodes its first frame, which
 *       Android 5 does not.</li>
 *   <li>AVIF pictures, still and animated, from Android 12.</li>
 *   <li>DNG from Android 7.0. Before it, and on every release for a camera's other raw formats
 *       (CR2, CR3, NEF, ARW, RAF, ORF and RW2), the largest JPEG the camera embedded in the file
 *       is shown instead; some cameras embed one smaller than the photo, and a raw with none
 *       fails.</li>
 *   <li>Gain maps in HDR from Android 14. Before it, an Ultra HDR picture shows as an ordinary
 *       one.</li>
 *   <li>Videos and sound in whichever codecs the device decodes. From Android 4.1 a video's
 *       format, profile and level are checked against the device's decoders before it plays,
 *       and one none of them takes fails. Android 4.0 lists no decoders, so there a video fails
 *       when its decoder gives up on it. The software decoders of Android 5 and older take
 *       H.264's Baseline profile and not its Main or High, which most cameras record in; phones
 *       of those years often decode High in hardware. QuickTime movies that open without a file
 *       type, as older ones and some Live Photos do, play from a copy the platform recognises,
 *       kept in the app's cache.</li>
 *   <li>TIFF, JPEG XL and AVI files fail on every release so far.</li>
 * </ul>
 *
 * <h2>Look</h2>
 * <p>The only things it draws itself are a thin line along its bottom edge that shows how far a
 * video or recording has played, in the theme's {@code colorControlActivated}; an icon for
 * sound with no cover; and an icon of a struck-out picture for a file it cannot show. Both
 * icons are in {@code colorControlNormal}. Each can be restyled, replaced or turned off through
 * the {@code MediaView} attributes or the setters, as can tapping, holding and HDR. It draws no
 * text; give it a content description of your own.</p>
 */
public class MediaView extends FrameLayout {
    /** What a file is shown as. */
    @IntDef({UNKNOWN, PICTURE, PANORAMA, PHOTOSPHERE, VIDEO, MOTION_PHOTO, SPHERICAL_VIDEO,
            AUDIO, ANIMATION})
    @Retention(RetentionPolicy.SOURCE)
    public @interface MediaType {}

    /** Not known yet, or, to {@link #setMediaType}, whatever the file was found to be. */
    public static final int UNKNOWN = 0;
    /** A picture to zoom into. */
    public static final int PICTURE = 1;
    /** A panorama to pan along. */
    public static final int PANORAMA = 2;
    /** A photo sphere to look around from the inside. */
    public static final int PHOTOSPHERE = 3;
    /** A video. */
    public static final int VIDEO = 4;
    /** A still with a short clip that plays while it is held. */
    public static final int MOTION_PHOTO = 5;
    /** A 360° video to look around. */
    public static final int SPHERICAL_VIDEO = 6;
    /** Sound, shown as its cover picture. */
    public static final int AUDIO = 7;
    /** An animated picture, which loops. */
    public static final int ANIMATION = 8;

    /** The progress line's thickness when no style sets it, in dp. */
    private static final float DEFAULT_PROGRESS_DP = 2;

    /** How large the library's icons are drawn, as a fraction of the view's shorter side. */
    private static final float PLACEHOLDER_FRACTION = 0.25f;

    /** Posts the worker's results back to the main thread. */
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /** Works out what files are, and opens them to play, off the main thread. */
    private Executor mExecutor = new ThreadPoolExecutor(
            0, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());

    /**
     * Decodes animations, each on a thread of its own, so a decode a platform decoder stalls on
     * holds nothing else up.
     */
    private Executor mAnimationExecutor = Executors.newCachedThreadPool();

    /** Taps and holds. */
    private final GestureDetector mGestures;

    /** Draws the progress line. */
    private final Paint mProgressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** The progress line's thickness. */
    @Px
    private int mProgressHeight;

    /** Whether the progress line shows. */
    private boolean mProgressVisible = true;

    /** Whether a tap plays and pauses. */
    private boolean mTapToPlay = true;

    /** Whether holding a motion photo plays its clip. */
    private boolean mHoldToPlay = true;

    /** Whether HDR content puts the window in HDR colour mode. */
    private boolean mHdrEnabled = true;

    /** Whether turning the phone looks around a sphere. */
    private boolean mGyroscopeEnabled;

    /** What stands for sound with no cover, or null for nothing. */
    @Nullable
    private Drawable mAudioPlaceholder;

    /** What shows in place of a file that cannot be shown, or null for nothing. */
    @Nullable
    private Drawable mErrorPlaceholder;

    /** Whether the file failed, so the error placeholder shows. */
    private boolean mFailed;

    /** Hears whether each file showed. */
    @Nullable
    private MediaListener mListener;

    /** The file asked for. */
    @Nullable
    private ImageLocation mLocation;

    /** Counts the files asked for, so a late answer about an earlier one is ignored. */
    private int mGeneration;

    /** What the file was found to be, once known. */
    @Nullable
    private MediaDetector.Detection mDetection;

    /** What the file is to be shown as instead of what it was found to be, or UNKNOWN. */
    @MediaType
    private int mOverride = UNKNOWN;

    /** What the file is shown as now. */
    @MediaType
    private int mType = UNKNOWN;

    /** Whether the current file has been reported, one way or the other. */
    private boolean mReported;

    /** The view showing the file, or null. */
    @Nullable
    private View mContent;

    /** Plays sound, a motion photo's clip and a 360° video. */
    @Nullable
    private MediaPlayer mPlayer;

    /** Whether the player is ready. */
    private boolean mPlayerPrepared;

    /** Where a motion photo's clip is drawn while it plays. */
    @Nullable
    private TextureView mClipView;

    /** Whether a held motion photo is playing its clip. */
    private boolean mHolding;

    /** Whether the window is held in HDR colour mode. */
    @Nullable
    private Window mHdrWindow;

    /** Whether the view is attached. */
    private boolean mAttached;

    /** The playback position and length last read, for the progress line, in ms. */
    private int mPosition;

    /** The length of what plays, in ms, or 0 when unknown. */
    private int mDuration;

    /** Redraws the progress line while something plays. */
    private final Runnable mProgressStep = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (isPlaying()) {
                ViewCompat.postOnAnimation(MediaView.this, this);
            }
        }
    };

    public MediaView(@NonNull Context context) {
        this(context, null);
    }

    public MediaView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MediaView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setWillNotDraw(false);
        mGestures = new GestureDetector(context, new Gestures());
        mProgressHeight = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                DEFAULT_PROGRESS_DP, getResources().getDisplayMetrics()));
        mProgressPaint.setColor(themeColor(context, android.R.attr.colorControlActivated,
                themeColor(context, android.R.attr.colorAccent, 0xFFFFFFFF)));
        int iconColor = themeColor(context, android.R.attr.colorControlNormal, 0xB3FFFFFF);
        mAudioPlaceholder = new NoteDrawable(iconColor);
        mErrorPlaceholder = new ErrorDrawable(iconColor);
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.MediaView,
                    defStyleAttr, 0);
            try {
                mOverride = a.getInt(R.styleable.MediaView_mediaType, UNKNOWN);
                mTapToPlay = a.getBoolean(R.styleable.MediaView_tapToPlay, true);
                mHoldToPlay = a.getBoolean(R.styleable.MediaView_holdToPlay, true);
                mProgressVisible = a.getBoolean(R.styleable.MediaView_mediaProgressVisible,
                        true);
                mHdrEnabled = a.getBoolean(R.styleable.MediaView_hdrEnabled, true);
                mGyroscopeEnabled = a.getBoolean(R.styleable.MediaView_gyroscopeEnabled, false);
                mProgressHeight = a.getDimensionPixelSize(
                        R.styleable.MediaView_mediaProgressHeight, mProgressHeight);
                mProgressPaint.setColor(a.getColor(R.styleable.MediaView_mediaProgressColor,
                        mProgressPaint.getColor()));
                mAudioPlaceholder = placeholder(context, a, attrs,
                        R.styleable.MediaView_audioPlaceholder, R.attr.audioPlaceholder,
                        mAudioPlaceholder);
                mErrorPlaceholder = placeholder(context, a, attrs,
                        R.styleable.MediaView_errorPlaceholder, R.attr.errorPlaceholder,
                        mErrorPlaceholder);
            } finally {
                a.recycle();
            }
        }
    }

    /**
     * A placeholder set by an attribute: its drawable, null where it is {@code @null}, or the
     * library's own where it is not set. A layout's {@code @null} reads back from the typed
     * array as if it were not set at all, so the layout's own attributes are looked at too.
     */
    @Nullable
    private static Drawable placeholder(@NonNull Context context, @NonNull TypedArray array,
            @NonNull AttributeSet attrs, int index, int attribute, @Nullable Drawable library) {
        boolean set = array.hasValue(index);
        for (int i = 0; !set && i < attrs.getAttributeCount(); i++) {
            set = attrs.getAttributeNameResource(i) == attribute;
        }
        if (!set) {
            return library;
        }
        int id = array.getResourceId(index, 0);
        return id == 0 ? null : ContextCompat.getDrawable(context, id);
    }

    /** A colour from the theme, or the fallback if the theme has none. */
    @ColorInt
    private static int themeColor(@NonNull Context context, int attribute, @ColorInt int fallback) {
        // Through the theme's own attributes, which resolve a colour resource on every release.
        TypedArray array = context.obtainStyledAttributes(new int[] {attribute});
        try {
            return array.getColor(0, fallback);
        } catch (RuntimeException notAColor) {
            return fallback;
        } finally {
            array.recycle();
        }
    }

    // ---------- What to show ----------

    /** Shows a file, or nothing for null. */
    @MainThread
    public void setFile(@Nullable File file) {
        load(file == null ? null : ImageLocation.of(file));
    }

    /** Shows a file behind a Uri, such as one in the gallery. */
    @MainThread
    public void setUri(@Nullable Uri uri) {
        load(uri == null ? null : ImageLocation.of(uri));
    }

    /** Hears whether each file could be shown. */
    @MainThread
    public void setMediaListener(@Nullable MediaListener listener) {
        mListener = listener;
    }

    /**
     * What the file is shown as: what it was found to be, or what {@link #setMediaType} asked
     * for; {@link #UNKNOWN} while it is still being worked out.
     */
    @MainThread
    @MediaType
    public int getMediaType() {
        return mType;
    }

    /**
     * Shows the file as a kind of media regardless of what it was found to be, such as a picture
     * flat rather than as a sphere, or {@link #UNKNOWN} to go back to what it was found to be.
     * Showing a file as something it cannot be, such as a picture as a video, fails.
     */
    @MainThread
    public void setMediaType(@MediaType int type) {
        if (type == mOverride) {
            return;
        }
        mOverride = type;
        if (mDetection != null) {
            show(type == UNKNOWN ? mDetection.type : type);
        }
    }

    @MainThread
    private void load(@Nullable ImageLocation location) {
        if (location == null ? mLocation == null : location.equals(mLocation)) {
            return;
        }
        clearContent();
        mGeneration++;
        mLocation = location;
        mDetection = null;
        mType = UNKNOWN;
        mReported = false;
        mFailed = false;
        if (location == null) {
            return;
        }
        final int generation = mGeneration;
        final Context context = getContext().getApplicationContext();
        mExecutor.execute(() -> {
            MediaDetector.Detection detection;
            try {
                detection = MediaDetector.detect(context, location);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Unable to work out what " + location + " is", e);
                detection = null;
            }
            final MediaDetector.Detection result = detection;
            mHandler.post(() -> onDetected(generation, result));
        });
    }

    @MainThread
    private void onDetected(int generation, @Nullable MediaDetector.Detection detection) {
        if (generation != mGeneration) {
            return;
        }
        if (detection == null) {
            reportFailed();
            return;
        }
        mDetection = detection;
        show(mOverride == UNKNOWN ? detection.type : mOverride);
    }

    /** Shows the file as a kind of media, in place of whatever showed before. */
    @MainThread
    private void show(@MediaType int type) {
        ImageLocation location = mLocation;
        MediaDetector.Detection detection = mDetection;
        if (location == null || detection == null) {
            return;
        }
        clearContent();
        mType = type;
        mReported = false;
        mFailed = false;
        if ((type == VIDEO || type == SPHERICAL_VIDEO) && !detection.decodable) {
            // It would play its sound over nothing.
            reportFailed();
            return;
        }
        switch (type) {
            case PICTURE:
            case MOTION_PHOTO:
                showPicture(new LargeImageView(getContext()), location);
                break;
            case PANORAMA:
                showPicture(new PanoramaView(getContext()), location);
                break;
            case PHOTOSPHERE: {
                PhotosphereView sphere = new PhotosphereView(getContext());
                sphere.setGyroscopeEnabled(mGyroscopeEnabled);
                sphere.setImageLoadListener(imageListener());
                setContent(sphere);
                setLocation(sphere, location);
                break;
            }
            case VIDEO:
                showVideo(detection.playable != null ? detection.playable : location);
                break;
            case SPHERICAL_VIDEO:
                showSphericalVideo(detection.playable != null ? detection.playable : location,
                        detection);
                break;
            case AUDIO:
                showAudio(location, detection);
                break;
            case ANIMATION: {
                AnimationView animation = new AnimationView(getContext(), mAnimationExecutor);
                animation.setImageLoadListener(imageListener());
                setContent(animation);
                animation.setLocation(location);
                break;
            }
            default:
                reportFailed();
                return;
        }
        holdHdr(detection.hdr && type != AUDIO);
    }

    @MainThread
    private void showPicture(@NonNull LargeImageView view, @NonNull ImageLocation location) {
        view.setImageLoadListener(imageListener());
        setContent(view);
        if (location.isFile()) {
            view.setFile(location.file());
        } else {
            view.setUri(location.uri());
        }
    }

    private static void setLocation(@NonNull PhotosphereView view,
            @NonNull ImageLocation location) {
        if (location.isFile()) {
            view.setFile(location.file());
        } else {
            view.setUri(location.uri());
        }
    }

    @NonNull
    private ImageLoadListener imageListener() {
        final int generation = mGeneration;
        final int type = mType;
        return new ImageLoadListener() {
            @Override
            public void onImageLoaded() {
                if (generation == mGeneration && type == mType) {
                    reportLoaded();
                }
            }

            @Override
            public void onImageFailed() {
                if (generation == mGeneration && type == mType) {
                    reportFailed();
                }
            }
        };
    }

    @MainThread
    private void showVideo(@NonNull ImageLocation location) {
        VideoView video = new VideoView(getContext());
        video.setFitsInside(true);
        // Taps are this view's to decide, so the video does not play or pause on its own.
        video.setClickable(true);
        ViewCompat.setImportantForAccessibility(video,
                ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_NO);
        final int generation = mGeneration;
        video.setPrepareListener(new VideoView.PrepareListener() {
            @Override
            public void onPrepared() {
                if (generation == mGeneration) {
                    MediaPlayer player = video.getMediaPlayer();
                    mDuration = safeDuration(player);
                    reportLoaded();
                    invalidate();
                }
            }

            @Override
            public void onPrepareFailed() {
                if (generation == mGeneration) {
                    reportFailed();
                }
            }
        });
        video.setEventListener(new VideoView.EventListener() {
            @Override
            public void onPlay() {
                startProgress();
            }

            @Override
            public void onPause() {
                updateProgress();
            }
        });
        setContent(video);
        if (location.isFile()) {
            video.setFile(location.file());
        } else {
            video.setUri(location.uri());
        }
    }

    @MainThread
    private void showSphericalVideo(@NonNull ImageLocation location,
            @NonNull MediaDetector.Detection detection) {
        PhotosphereView sphere = new PhotosphereView(getContext());
        sphere.setGyroscopeEnabled(mGyroscopeEnabled);
        setContent(sphere);
        float[] eye = detection.eye;
        final int generation = mGeneration;
        sphere.showVideo(detection.sphere, eye[0], eye[1], eye[2], eye[3], surface -> {
            if (generation != mGeneration) {
                surface.release();
                return;
            }
            if (mPlayer != null) {
                mPlayer.setSurface(surface);
                surface.release();
                return;
            }
            preparePlayer(location, -1, -1, surface, true);
        });
    }

    @MainThread
    private void showAudio(@NonNull ImageLocation location,
            @NonNull MediaDetector.Detection detection) {
        ImageView image = new ImageView(getContext());
        Bitmap cover = null;
        if (detection.coverArt != null) {
            cover = BitmapFactory.decodeByteArray(detection.coverArt, 0,
                    detection.coverArt.length);
        }
        if (cover != null) {
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setImageBitmap(cover);
        } else {
            image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            image.setImageDrawable(mAudioPlaceholder);
        }
        setContent(image);
        preparePlayer(location, -1, -1, null, true);
    }

    /** Replaces what shows with a view filling this one. */
    @MainThread
    private void setContent(@NonNull View view) {
        mContent = view;
        addView(view, 0, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Takes away what shows, and stops anything playing. */
    @MainThread
    private void clearContent() {
        stopClip();
        releasePlayer();
        if (mContent != null) {
            if (mContent instanceof PhotosphereView) {
                ((PhotosphereView) mContent).clearVideo();
            } else if (mContent instanceof AnimationView) {
                ((AnimationView) mContent).release();
            }
            removeView(mContent);
            mContent = null;
        }
        mPosition = 0;
        mDuration = 0;
        holdHdr(false);
        invalidate();
    }

    // ---------- Reporting ----------

    @MainThread
    private void reportLoaded() {
        if (mReported) {
            return;
        }
        mReported = true;
        if (mListener != null) {
            mListener.onMediaLoaded(mType);
        }
    }

    /**
     * Shows the error placeholder in place of the file and reports it once, whether or not it
     * showed first: a video can show, then fail to play.
     */
    @MainThread
    private void reportFailed() {
        if (mFailed) {
            return;
        }
        mReported = true;
        mFailed = true;
        clearContent();
        invalidate();
        if (mListener != null) {
            mListener.onMediaFailed();
        }
    }

    // ---------- Playing ----------

    /**
     * Prepares the player.
     *
     * @param offset where in the file what plays starts, or -1 for all of it
     * @param length how long it is, or -1
     * @param surface where its frames go, or null for sound alone
     * @param report whether being ready, or failing, is the file's own outcome
     */
    @MainThread
    private void preparePlayer(@NonNull ImageLocation location, long offset, long length,
            @Nullable Surface surface, final boolean report) {
        releasePlayer();
        final int generation = mGeneration;
        final MediaPlayer player = new MediaPlayer();
        mPlayer = player;
        mPlayerPrepared = false;
        final Context context = getContext().getApplicationContext();
        // A provider's file can be a call to another process away, so it opens off this thread.
        mExecutor.execute(() -> {
            ParcelFileDescriptor descriptor = null;
            IOException failure = null;
            try {
                descriptor = location.openDescriptor(context);
            } catch (IOException e) {
                failure = e;
            }
            final ParcelFileDescriptor opened = descriptor;
            final IOException error = failure;
            mHandler.post(() -> {
                if (generation != mGeneration || player != mPlayer) {
                    closeQuietly(opened);
                    if (surface != null) {
                        surface.release();
                    }
                    return;
                }
                startPlayer(player, generation, location, opened, error, offset, length,
                        surface, report);
            });
        });
    }

    /**
     * Hands the player its file, once open, and prepares it.
     *
     * @param descriptor the open file, or null when it could not be opened
     * @param error why it could not be opened, or null
     */
    @MainThread
    private void startPlayer(@NonNull MediaPlayer player, int generation,
            @NonNull ImageLocation location, @Nullable ParcelFileDescriptor descriptor,
            @Nullable IOException error, long offset, long length, @Nullable Surface surface,
            final boolean report) {
        try {
            if (descriptor == null) {
                throw error != null ? error : new IOException("Nothing to read");
            }
            if (offset >= 0) {
                player.setDataSource(descriptor.getFileDescriptor(), offset, length);
            } else {
                player.setDataSource(descriptor.getFileDescriptor());
            }
            if (surface != null) {
                player.setSurface(surface);
            }
            if (Build.VERSION.SDK_INT >= 21) {
                player.setAudioAttributes(new AudioAttributes.Builder()
                        .setContentType(surface == null ? AudioAttributes.CONTENT_TYPE_MUSIC
                                : AudioAttributes.CONTENT_TYPE_MOVIE)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build());
            } else {
                player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            }
            final ParcelFileDescriptor held = descriptor;
            player.setOnPreparedListener(prepared -> {
                closeQuietly(held);
                if (generation != mGeneration || prepared != mPlayer) {
                    return;
                }
                mPlayerPrepared = true;
                mDuration = safeDuration(prepared);
                if (report) {
                    reportLoaded();
                }
                if (mHolding) {
                    prepared.start();
                    startProgress();
                }
                invalidate();
            });
            player.setOnErrorListener((failed, what, extra) -> {
                closeQuietly(held);
                Log.w(TAG, "Unable to play " + location + " (" + what + ", " + extra + ")");
                if (generation == mGeneration && failed == mPlayer) {
                    releasePlayer();
                    if (report) {
                        reportFailed();
                    } else {
                        // A motion photo's clip: its still shows again.
                        stopClip();
                    }
                }
                return true;
            });
            player.setOnCompletionListener(completed -> {
                if (generation == mGeneration && completed == mPlayer) {
                    mPosition = mDuration;
                    invalidate();
                    if (mType == MOTION_PHOTO) {
                        stopClip();
                    }
                }
            });
            player.prepareAsync();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Unable to play " + location, e);
            closeQuietly(descriptor);
            releasePlayer();
            if (report) {
                reportFailed();
            }
        } finally {
            if (surface != null) {
                surface.release();
            }
        }
    }

    private static void closeQuietly(@Nullable ParcelFileDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        try {
            descriptor.close();
        } catch (IOException e) {
            Log.w(TAG, "Unable to close a file", e);
        }
    }

    private static int safeDuration(@NonNull MediaPlayer player) {
        try {
            return Math.max(0, player.getDuration());
        } catch (IllegalStateException notPrepared) {
            return 0;
        }
    }

    @MainThread
    private void releasePlayer() {
        MediaPlayer player = mPlayer;
        mPlayer = null;
        mPlayerPrepared = false;
        if (player != null) {
            player.release();
        }
    }

    /** Whether a video or recording is playing. */
    @MainThread
    public boolean isPlaying() {
        if (mContent instanceof VideoView) {
            return ((VideoView) mContent).isPlaying();
        }
        if (mContent instanceof AnimationView) {
            return ((AnimationView) mContent).isPlaying();
        }
        try {
            return mPlayer != null && mPlayerPrepared && mPlayer.isPlaying();
        } catch (IllegalStateException released) {
            return false;
        }
    }

    /**
     * Plays a video, 360° video or recording from where it was paused.
     *
     * @return false if what shows does not play, or is not ready yet
     */
    @MainThread
    public boolean play() {
        if (mContent instanceof VideoView) {
            return ((VideoView) mContent).play();
        }
        if (mContent instanceof AnimationView) {
            return ((AnimationView) mContent).play();
        }
        if (mType == MOTION_PHOTO || mPlayer == null || !mPlayerPrepared) {
            return false;
        }
        if (mPosition >= mDuration && mDuration > 0) {
            mPlayer.seekTo(0);
        }
        mPlayer.start();
        startProgress();
        return true;
    }

    /**
     * Pauses a video, 360° video or recording.
     *
     * @return false if nothing is playing
     */
    @MainThread
    public boolean pause() {
        if (mContent instanceof VideoView) {
            return ((VideoView) mContent).pause();
        }
        if (mContent instanceof AnimationView) {
            return ((AnimationView) mContent).pause();
        }
        if (!isPlaying()) {
            return false;
        }
        mPlayer.pause();
        updateProgress();
        return true;
    }

    /** Plays a motion photo's clip over its still, for as long as it is held. */
    @MainThread
    private void startClip() {
        MediaDetector.Detection detection = mDetection;
        ImageLocation location = mLocation;
        if (detection == null || detection.motionPhoto == null || location == null
                || mHolding || !detection.decodable) {
            // A clip no decoder here takes would hold a blank over the still.
            return;
        }
        mHolding = true;
        final TextureView clip = new TextureView(getContext());
        mClipView = clip;
        final long offset = detection.motionPhoto.getVideoOffset();
        final long length = detection.motionPhoto.getVideoLength();
        final int generation = mGeneration;
        clip.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture texture, int width,
                    int height) {
                if (generation != mGeneration || clip != mClipView) {
                    return;
                }
                fitClip(clip, location, offset, length);
                preparePlayer(location, offset, length, new Surface(texture), false);
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture texture, int width,
                    int height) {}

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture texture) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture texture) {}
        });
        addView(clip, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Turns and fits the clip over the still, as the still is fitted. */
    @MainThread
    private void fitClip(@NonNull final TextureView clip, @NonNull final ImageLocation location,
            final long offset, final long length) {
        final Context context = getContext().getApplicationContext();
        mExecutor.execute(() -> {
            final int[] size = clipSize(context, location, offset, length);
            mHandler.post(() -> {
                if (size == null || clip != mClipView) {
                    return;
                }
                RectF whole = new RectF(0, 0, size[0], size[1]);
                clip.setTransform(TransformMath.asTextureTransform(TransformMath.centerInside(
                        whole, size[2], false, clip.getWidth(), clip.getHeight()), size[0],
                        size[1], clip.getWidth(), clip.getHeight()));
            });
        });
    }

    /** A clip's width, height and rotation, or null if they cannot be read. */
    @WorkerThread
    @Nullable
    private static int[] clipSize(@NonNull Context context, @NonNull ImageLocation location,
            long offset, long length) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try (ParcelFileDescriptor descriptor = location.openDescriptor(context)) {
            retriever.setDataSource(descriptor.getFileDescriptor(), offset, length);
            int width = parse(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int height = parse(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int rotation = Build.VERSION.SDK_INT >= 17 ? parse(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)) : 0;
            // The decoder turns the frames itself, so what arrives is already upright.
            boolean turned = rotation == 90 || rotation == 270;
            return width > 0 && height > 0 ? new int[] {turned ? height : width,
                    turned ? width : height, 0} : null;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Unable to read the clip of " + location, e);
            return null;
        } finally {
            try {
                retriever.release();
            } catch (IOException | RuntimeException ignored) {
                // Nothing more to let go of.
            }
        }
    }

    private static int parse(@Nullable String number) {
        try {
            return number == null ? 0 : Integer.parseInt(number.trim());
        } catch (NumberFormatException malformed) {
            return 0;
        }
    }

    /** Stops a motion photo's clip and shows its still again. */
    @MainThread
    private void stopClip() {
        if (!mHolding && mClipView == null) {
            return;
        }
        mHolding = false;
        if (mType == MOTION_PHOTO) {
            releasePlayer();
        }
        if (mClipView != null) {
            removeView(mClipView);
            mClipView = null;
        }
        mPosition = 0;
        mDuration = 0;
        invalidate();
    }

    // ---------- Gestures ----------

    @Override
    public boolean dispatchTouchEvent(@NonNull MotionEvent event) {
        mGestures.onTouchEvent(event);
        int action = event.getActionMasked();
        if (mHolding) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                stopClip();
            }
            // The still stays where it was while its clip plays.
            return true;
        }
        boolean handled = super.dispatchTouchEvent(event);
        return handled || action == MotionEvent.ACTION_DOWN;
    }

    /** A tap: plays or pauses, where tapping does. */
    @Override
    public boolean performClick() {
        boolean handled = super.performClick();
        if (!mTapToPlay || (mType != VIDEO && mType != SPHERICAL_VIDEO && mType != AUDIO
                && mType != ANIMATION)) {
            return handled;
        }
        if (isPlaying()) {
            pause();
        } else {
            play();
        }
        return true;
    }

    /** A hold, which accessibility services also send: plays a motion photo's clip once. */
    @Override
    public boolean performLongClick() {
        boolean handled = super.performLongClick();
        if (mType == MOTION_PHOTO && mHoldToPlay) {
            startClip();
            return true;
        }
        return handled;
    }

    /** Taps and holds anywhere on the view. */
    private final class Gestures extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(@NonNull MotionEvent event) {
            return true;
        }

        @Override
        public boolean onSingleTapConfirmed(@NonNull MotionEvent event) {
            return performClick();
        }

        @Override
        public void onLongPress(@NonNull MotionEvent event) {
            if (mType == MOTION_PHOTO && mHoldToPlay) {
                // What the still was doing with the finger stops there.
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                MediaView.super.dispatchTouchEvent(cancel);
                cancel.recycle();
                startClip();
            }
        }
    }

    // ---------- Progress ----------

    @MainThread
    private void startProgress() {
        removeCallbacks(mProgressStep);
        ViewCompat.postOnAnimation(this, mProgressStep);
    }

    @MainThread
    private void updateProgress() {
        int position = mPosition;
        int duration = mDuration;
        try {
            if (mContent instanceof VideoView) {
                MediaPlayer player = ((VideoView) mContent).getMediaPlayer();
                if (((VideoView) mContent).isPlaying() || duration > 0) {
                    position = player.getCurrentPosition();
                    duration = player.getDuration();
                }
            } else if (mPlayer != null && mPlayerPrepared) {
                position = mPlayer.getCurrentPosition();
                duration = mPlayer.getDuration();
            }
        } catch (IllegalStateException notReady) {
            return;
        }
        if (position != mPosition || duration != mDuration) {
            mPosition = position;
            mDuration = duration;
            invalidate();
        }
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        super.dispatchDraw(canvas);
        if (mFailed && mErrorPlaceholder != null) {
            drawCentered(canvas, mErrorPlaceholder);
        }
        if (!mProgressVisible || mDuration <= 0 || mProgressHeight <= 0) {
            return;
        }
        float fraction = Math.max(0, Math.min(1, mPosition / (float) mDuration));
        if (fraction <= 0) {
            return;
        }
        canvas.drawRect(0, getHeight() - mProgressHeight, getWidth() * fraction, getHeight(),
                mProgressPaint);
    }

    /**
     * Draws a drawable in the middle of the view: at its own size, shrunk to fit if it is
     * larger, or over the whole view if it has no size of its own, as the library's icons do.
     */
    @MainThread
    private void drawCentered(@NonNull Canvas canvas, @NonNull Drawable drawable) {
        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();
        if (width <= 0 || height <= 0) {
            drawable.setBounds(0, 0, getWidth(), getHeight());
        } else {
            float scale = Math.min(1, Math.min((float) getWidth() / width,
                    (float) getHeight() / height));
            int shownWidth = Math.round(width * scale);
            int shownHeight = Math.round(height * scale);
            int left = (getWidth() - shownWidth) / 2;
            int top = (getHeight() - shownHeight) / 2;
            drawable.setBounds(left, top, left + shownWidth, top + shownHeight);
        }
        drawable.draw(canvas);
    }

    /** How far what plays has got, as a fraction, for tests. */
    @VisibleForTesting
    float getProgress() {
        return mDuration <= 0 ? 0 : Math.max(0, Math.min(1, mPosition / (float) mDuration));
    }

    // ---------- HDR ----------

    /** Holds the window in HDR colour mode, or lets go of it. */
    @MainThread
    private void holdHdr(boolean hdr) {
        boolean want = hdr && mHdrEnabled && mAttached;
        if (want && mHdrWindow == null) {
            Window window = HdrColorMode.windowOf(this);
            if (window != null) {
                HdrColorMode.acquire(window);
                mHdrWindow = window;
            }
        } else if (!want && mHdrWindow != null) {
            HdrColorMode.release(mHdrWindow);
            mHdrWindow = null;
        }
    }

    /** Whether the view holds its window in HDR colour mode, for tests. */
    @VisibleForTesting
    boolean isHoldingHdr() {
        return mHdrWindow != null;
    }

    // ---------- Settings ----------

    /** Whether a tap plays and pauses a video, 360° video or recording. On by default. */
    @MainThread
    public void setTapToPlayEnabled(boolean enabled) {
        mTapToPlay = enabled;
    }

    @MainThread
    public boolean isTapToPlayEnabled() {
        return mTapToPlay;
    }

    /** Whether holding a motion photo plays its clip. On by default. */
    @MainThread
    public void setHoldToPlayEnabled(boolean enabled) {
        mHoldToPlay = enabled;
        if (!enabled) {
            stopClip();
        }
    }

    @MainThread
    public boolean isHoldToPlayEnabled() {
        return mHoldToPlay;
    }

    /** Whether the progress line shows along the bottom edge. On by default. */
    @MainThread
    public void setProgressVisible(boolean visible) {
        mProgressVisible = visible;
        invalidate();
    }

    @MainThread
    public boolean isProgressVisible() {
        return mProgressVisible;
    }

    /** Sets the progress line's colour, in place of the theme's colorControlActivated. */
    @MainThread
    public void setProgressColor(@ColorInt int color) {
        mProgressPaint.setColor(color);
        invalidate();
    }

    /** Sets the progress line's thickness. */
    @MainThread
    public void setProgressHeight(@Px int height) {
        mProgressHeight = height;
        invalidate();
    }

    /**
     * Sets what shows for sound with no cover picture, in place of the library's icon, or
     * nothing for null.
     */
    @MainThread
    public void setAudioPlaceholder(@Nullable Drawable placeholder) {
        mAudioPlaceholder = placeholder;
        if (mType == AUDIO && mContent instanceof ImageView && (mDetection == null
                || mDetection.coverArt == null)) {
            ((ImageView) mContent).setImageDrawable(placeholder);
        }
    }

    /**
     * Sets what shows in place of a file that cannot be shown, instead of the library's icon,
     * or nothing for null. It is drawn in the middle of the view, at its own size or smaller to
     * fit; one with no size of its own is given the whole view.
     */
    @MainThread
    public void setErrorPlaceholder(@Nullable Drawable placeholder) {
        mErrorPlaceholder = placeholder;
        invalidate();
    }

    /** What shows in place of a file that cannot be shown, or null for nothing. */
    @MainThread
    @Nullable
    public Drawable getErrorPlaceholder() {
        return mErrorPlaceholder;
    }

    /**
     * Whether HDR pictures and videos put the window in HDR colour mode while they show. On by
     * default.
     */
    @MainThread
    public void setHdrEnabled(boolean enabled) {
        mHdrEnabled = enabled;
        holdHdr(mDetection != null && mDetection.hdr && mType != AUDIO);
    }

    @MainThread
    public boolean isHdrEnabled() {
        return mHdrEnabled;
    }

    /**
     * Whether turning the phone looks around a photo sphere or 360° video. Off by default;
     * nothing happens on a phone without a rotation sensor.
     */
    @MainThread
    public void setGyroscopeEnabled(boolean enabled) {
        mGyroscopeEnabled = enabled;
        if (mContent instanceof PhotosphereView) {
            ((PhotosphereView) mContent).setGyroscopeEnabled(enabled);
        }
    }

    @MainThread
    public boolean isGyroscopeEnabled() {
        return mGyroscopeEnabled;
    }

    @VisibleForTesting
    void setExecutor(@NonNull Executor executor) {
        mExecutor = executor;
        mAnimationExecutor = executor;
    }

    /** What shows the file, for tests. */
    @VisibleForTesting
    @Nullable
    View getContentView() {
        return mContent;
    }

    // ---------- Lifecycle ----------

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mAttached = true;
        if (mDetection != null) {
            holdHdr(mDetection.hdr && mType != AUDIO);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        mAttached = false;
        stopClip();
        if (mType == AUDIO || mType == SPHERICAL_VIDEO) {
            // Playback stops with the view; it prepares again when shown again.
            pause();
        }
        holdHdr(false);
        removeCallbacks(mProgressStep);
        super.onDetachedFromWindow();
    }

    /** Whether the file could not be shown, so the error placeholder shows, for tests. */
    @VisibleForTesting
    boolean hasFailed() {
        return mFailed;
    }

    /** A note, the library's icon for sound with no cover picture. */
    private static final class NoteDrawable extends Drawable {
        /** Draws the note. */
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        /** The note's outline, in a 24-unit box. */
        private final Path mPath = new Path();

        /** The note, scaled to the bounds. */
        private final Path mScaled = new Path();

        NoteDrawable(@ColorInt int color) {
            mPaint.setColor(color);
            // A head and a stem with a flag, in a 24 unit box.
            mPath.addCircle(9, 17, 4, Path.Direction.CW);
            mPath.addRect(11, 4, 13, 17, Path.Direction.CW);
            mPath.moveTo(13, 4);
            mPath.lineTo(19, 6);
            mPath.lineTo(19, 9);
            mPath.lineTo(13, 7);
            mPath.close();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            float size = Math.min(bounds.width(), bounds.height()) * PLACEHOLDER_FRACTION;
            Matrix matrix = new Matrix();
            matrix.setScale(size / 24, size / 24);
            matrix.postTranslate(bounds.exactCenterX() - size / 2,
                    bounds.exactCenterY() - size / 2);
            mPath.transform(matrix, mScaled);
            canvas.drawPath(mScaled, mPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            mPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter filter) {
            mPaint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * A picture struck out, the library's icon for a file that cannot be shown: a frame with a
     * hill inside, and a line across it from corner to corner.
     */
    private static final class ErrorDrawable extends Drawable {
        /** Draws the icon. */
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        /** The frame, the hill and the line, in a 24-unit box. */
        private final Path mPath = new Path();

        /** The icon, scaled to the bounds. */
        private final Path mScaled = new Path();

        ErrorDrawable(@ColorInt int color) {
            mPaint.setColor(color);
            mPath.setFillType(Path.FillType.EVEN_ODD);
            // The frame: an outline two units thick.
            mPath.addRect(3, 5, 21, 19, Path.Direction.CW);
            mPath.addRect(5, 7, 19, 17, Path.Direction.CW);
            // A hill, inside the frame.
            mPath.moveTo(6, 16);
            mPath.lineTo(10, 11);
            mPath.lineTo(13, 14.5f);
            mPath.lineTo(15, 12.5f);
            mPath.lineTo(18, 16);
            mPath.close();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            float size = Math.min(bounds.width(), bounds.height()) * PLACEHOLDER_FRACTION;
            Matrix matrix = new Matrix();
            matrix.setScale(size / 24, size / 24);
            matrix.postTranslate(bounds.exactCenterX() - size / 2,
                    bounds.exactCenterY() - size / 2);
            mPath.transform(matrix, mScaled);
            canvas.drawPath(mScaled, mPaint);
            // The line across, from the top left corner to the bottom right.
            float stroke = mPaint.getStrokeWidth();
            Paint.Style style = mPaint.getStyle();
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(size / 12);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            float left = bounds.exactCenterX() - size / 2;
            float top = bounds.exactCenterY() - size / 2;
            canvas.drawLine(left + size * 3 / 24, top + size * 3 / 24, left + size * 21 / 24,
                    top + size * 21 / 24, mPaint);
            mPaint.setStrokeWidth(stroke);
            mPaint.setStyle(style);
        }

        @Override
        public void setAlpha(int alpha) {
            mPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter filter) {
            mPaint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
