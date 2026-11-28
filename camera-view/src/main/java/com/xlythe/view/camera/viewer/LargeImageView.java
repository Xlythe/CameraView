package com.xlythe.view.camera.viewer;

import static com.xlythe.view.camera.ICameraModule.TAG;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewParent;
import android.widget.OverScroller;

import androidx.annotation.AnyThread;
import androidx.annotation.IntDef;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;
import androidx.core.view.ViewCompat;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shows a picture of any size, and lets it be zoomed in on until its own pixels show: pinch or
 * double-tap to zoom, drag or fling to pan.
 *
 * <p>The picture is never decoded whole. A low-resolution copy no larger than the view shows at
 * once, and as the picture is zoomed, tiles of the part on screen are decoded at the detail the
 * screen can show and drawn over it. It is turned the way its Exif orientation says.</p>
 *
 * <h2>Memory</h2>
 * <p>The view holds at most five times its own pixel count in decoded pixels, at four bytes
 * each: the low-resolution copy, no larger than the view, and tiles for what is on screen, no
 * more than four times the view (and never less than 1 MP, for a small view). A 1080 x 2400 view
 * holds at most 13 MP, 52 MB, whether the picture is 12 MP or 200 MP. When the tiles for the
 * sharpest detail would not fit, which can happen just before the next level of detail is
 * reached, the tiles one level coarser are drawn instead.</p>
 *
 * <h2>RAW</h2>
 * <p>A DNG cannot be decoded a piece at a time. The first time one is shown it is decoded whole,
 * once, at full size up to 50 MP (a larger one at a half or a quarter), and written to the app's
 * cache as a JPEG that can be. That decode briefly holds up to 200 MB on top of the decoder's
 * own; afterwards the DNG is viewed like any other picture, and showing it again reuses the
 * copy. The four most recent copies are kept. The same is done for any other format that cannot
 * be decoded in pieces.</p>
 *
 * <p>Devices before Android 7.0 cannot decode DNG at all, and call
 * {@link ImageLoadListener#onImageFailed()} for one.</p>
 */
public class LargeImageView extends View {
    /** How many times the view's own pixel count the tiles may take. */
    @VisibleForTesting
    static final int TILE_BUDGET_VIEWS = 4;

    /** The least the tiles may take, so a small view can still show a corner in detail. */
    @VisibleForTesting
    static final long MIN_TILE_BUDGET_PIXELS = 4L * Tiles.TILE_SIZE * Tiles.TILE_SIZE;

    /** How much closer a double tap zooms in, from all the way out. */
    private static final float DOUBLE_TAP_ZOOM = 3f;

    /** How long a double tap's zoom takes. */
    private static final long DOUBLE_TAP_MILLIS = 250;

    /** Orders tiles coarsest first. */
    private static final Comparator<Tile> COARSEST_FIRST = new Comparator<Tile>() {
        @Override
        public int compare(Tile a, Tile b) {
            return b.sampleSize - a.sampleSize;
        }
    };

    /** Not started on a worker yet; can still be called off. */
    private static final int REQUEST_PENDING = 0;

    /** Being decoded on a worker. */
    private static final int REQUEST_RUNNING = 1;

    /** Called off before a worker reached it. */
    private static final int REQUEST_CANCELLED = 2;

    /** Where a tile on its way is. */
    @IntDef({REQUEST_PENDING, REQUEST_RUNNING, REQUEST_CANCELLED})
    @Retention(RetentionPolicy.SOURCE)
    private @interface RequestState {}

    /** Posts a worker's results back to the main thread. */
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /** Where the picture is placed in the view. */
    final Viewport mViewport = new Viewport();

    /** Tiles that have been decoded, least recently drawn first. */
    private final LinkedHashMap<Long, Tile> mTiles = new LinkedHashMap<>(16, 0.75f, true);

    /** Tiles being decoded. */
    private final Map<Long, Request> mRequests = new HashMap<>();

    /** The tiles the current view needs, reused from one frame to the next. */
    private final Set<Long> mNeeded = new LinkedHashSet<>();

    /** Draws the picture from its stored pixels into the view. */
    private final Matrix mDrawMatrix = new Matrix();

    /** Turns stored pixels upright, for the current picture. */
    private final Matrix mOrientationMatrix = new Matrix();

    /** Where a bitmap is drawn, in stored pixels. */
    private final RectF mDestination = new RectF();

    /** Smooths a bitmap drawn at a scale. */
    private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    /** The tiles in the order they are drawn, reused from one frame to the next. */
    private final List<Tile> mDrawOrder = new ArrayList<>();

    /** Drags, flings and double taps. */
    private final GestureDetector mGestures;

    /** Pinches. */
    private final ScaleGestureDetector mPinch;

    /** Carries a fling on after the finger lifts. */
    private final OverScroller mScroller;

    /** Decodes off the main thread: one thread, which goes away when there is nothing to do. */
    private Executor mExecutor = new ThreadPoolExecutor(
            0, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());

    /** Opens pictures. */
    @NonNull
    private ImageSources.Opener mOpener = ImageSources::open;

    /** Hears whether the picture showed. */
    @Nullable
    private ImageLoadListener mListener;

    /** The picture asked for, kept while detached so it can be shown again on return. */
    @Nullable
    private ImageLocation mLocation;

    /** Counts the pictures asked for, so a late answer about an earlier one is ignored. */
    private int mGeneration;

    /** Whether the current picture is being opened. */
    private boolean mOpening;

    /** The picture, once open. */
    @Nullable
    private ImageSources.Opened mOpened;

    /** How the picture's stored pixels turn upright, once open. */
    @Nullable
    private Orientation mOrientation;

    /** The low-resolution copy, once decoded. */
    @Nullable
    private Bitmap mBase;

    /** The sample size the copy was decoded at, or 0 before it is asked for. */
    private int mBaseSampleSize;

    /** Whether the current picture has been reported, one way or the other. */
    private boolean mReported;

    /** Decoded pixels held in tiles. */
    private long mTilePixels;

    /** Decoded pixels set aside for tiles being decoded, at the size they are expected to be. */
    private long mReservedPixels;

    /** The most decoded pixels held at once, for tests of the budget. */
    private long mPeakPixels;

    /** Where the last pinch event was centred across, so moving the fingers together pans. */
    private float mLastFocusX;

    /** And down. */
    private float mLastFocusY;

    /** A double tap's zoom, while it runs. */
    @Nullable
    private ValueAnimator mZoomAnimator;

    public LargeImageView(@NonNull Context context) {
        this(context, null);
    }

    public LargeImageView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LargeImageView(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mScroller = new OverScroller(context);
        mGestures = new GestureDetector(context, new GestureListener());
        mPinch = new ScaleGestureDetector(context, new PinchListener());
    }

    /** Shows a picture from a file, or nothing for null. */
    @MainThread
    public void setFile(@Nullable File file) {
        load(file == null ? null : ImageLocation.of(file));
    }

    /** Shows a picture from a Uri, such as one the camera filed in the gallery. */
    @MainThread
    public void setUri(@Nullable Uri uri) {
        load(uri == null ? null : ImageLocation.of(uri));
    }

    /** Hears whether each picture could be shown. */
    @MainThread
    public void setImageLoadListener(@Nullable ImageLoadListener listener) {
        mListener = listener;
    }

    @MainThread
    private void load(@Nullable ImageLocation location) {
        if (location == null ? mLocation == null : location.equals(mLocation)) {
            return;
        }
        release();
        mLocation = location;
        mReported = false;
        if (ViewCompat.isAttachedToWindow(this)) {
            open();
        }
        invalidate();
    }

    @VisibleForTesting
    void setExecutor(@NonNull Executor executor) {
        mExecutor = executor;
    }

    @VisibleForTesting
    void setOpener(@NonNull ImageSources.Opener opener) {
        mOpener = opener;
    }

    /** The decoded pixels held right now: the low-resolution copy, tiles and tiles on the way. */
    @VisibleForTesting
    long getHeldPixels() {
        return (mBase == null ? 0 : (long) mBase.getWidth() * mBase.getHeight())
                + mTilePixels + mReservedPixels;
    }

    /** The most decoded pixels held at once since the view was made. */
    @VisibleForTesting
    long getPeakPixels() {
        return mPeakPixels;
    }

    /** The most decoded pixels this view may hold, at its current size. */
    @VisibleForTesting
    long getBudgetPixels() {
        return (long) getWidth() * getHeight() + tileBudget();
    }

    /** How many tiles are decoded, for tests. */
    @VisibleForTesting
    int getTileCount() {
        return mTiles.size();
    }

    /** How many tiles are decoded at a sample size, for tests. */
    @VisibleForTesting
    int getTileCount(int sampleSize) {
        int count = 0;
        for (Tile tile : mTiles.values()) {
            if (tile.sampleSize == sampleSize) {
                count++;
            }
        }
        return count;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mLocation != null && mOpened == null && !mOpening) {
            open();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // Detached views hold no pixels; the picture is opened again if this one comes back.
        release();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        mViewport.setViewSize(width, height);
        place();
        requestBase();
    }

    /** Opens the picture on a worker. */
    @MainThread
    private void open() {
        final ImageLocation location = mLocation;
        if (location == null) {
            return;
        }
        final int generation = mGeneration;
        final Context context = getContext().getApplicationContext();
        final ImageSources.Opener opener = mOpener;
        mOpening = true;
        mExecutor.execute(new Runnable() {
            @WorkerThread
            @Override
            public void run() {
                ImageSources.Opened opened = null;
                try {
                    opened = opener.open(context, location);
                } catch (IOException | RuntimeException | OutOfMemoryError e) {
                    Log.w(TAG, "Unable to open " + location, e);
                }
                final ImageSources.Opened result = opened;
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        onOpened(generation, result);
                    }
                });
            }
        });
    }

    @MainThread
    private void onOpened(int generation, @Nullable ImageSources.Opened opened) {
        if (generation != mGeneration) {
            if (opened != null) {
                opened.source.close();
            }
            return;
        }
        mOpening = false;
        if (opened == null) {
            reportFailed();
            return;
        }
        mOpened = opened;
        RegionSource source = opened.source;
        mOrientation = Orientation.of(opened.orientation, source.getWidth(), source.getHeight());
        mOrientationMatrix.setValues(mOrientation.toMatrixValues());
        onImageOpened(opened.gpano);
        mViewport.setImage(mOrientation.getWidth(), mOrientation.getHeight(), wraps());
        place();
        requestBase();
    }

    /** Called when a picture opens, before it is placed, with its sphere metadata if any. */
    @MainThread
    void onImageOpened(@Nullable GPano gpano) {}

    /** Whether the picture repeats across, its right edge meeting its left. */
    @MainThread
    boolean wraps() {
        return false;
    }

    /** The smallest scale the picture may be shown at: here, the whole of it in the view. */
    @MainThread
    float minimumScale(@NonNull Viewport viewport) {
        return viewport.fitScale();
    }

    /** Which pixel of the upright picture to show in the middle at first, across. */
    @MainThread
    float initialCenterX(int imageWidth) {
        return imageWidth / 2f;
    }

    /** Zooms all the way out, once both the view and the picture have a size. */
    @MainThread
    private void place() {
        if (mOpened == null || !mViewport.isReady()) {
            return;
        }
        Orientation orientation = mOrientation;
        mViewport.reset(minimumScale(mViewport), initialCenterX(orientation.getWidth()),
                orientation.getHeight() / 2f);
        invalidate();
    }

    /** Decodes the low-resolution copy, once the view's size says how small it can be. */
    @MainThread
    private void requestBase() {
        final ImageSources.Opened opened = mOpened;
        if (opened == null || mBaseSampleSize != 0 || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        final RegionSource source = opened.source;
        final int sampleSize = Tiles.sampleSizeWithin(source.getWidth(), source.getHeight(),
                (long) getWidth() * getHeight());
        mBaseSampleSize = sampleSize;
        final int generation = mGeneration;
        mExecutor.execute(new Runnable() {
            @WorkerThread
            @Override
            public void run() {
                final Bitmap base = source.decode(
                        new Rect(0, 0, source.getWidth(), source.getHeight()), sampleSize);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        onBase(generation, base);
                    }
                });
            }
        });
    }

    @MainThread
    private void onBase(int generation, @Nullable Bitmap base) {
        if (generation != mGeneration) {
            if (base != null) {
                base.recycle();
            }
            return;
        }
        if (base == null) {
            reportFailed();
            return;
        }
        mBase = base;
        notePeak();
        invalidate();
        if (!mReported) {
            mReported = true;
            if (mListener != null) {
                mListener.onImageLoaded();
            }
        }
    }

    @MainThread
    private void reportFailed() {
        if (!mReported) {
            mReported = true;
            if (mListener != null) {
                mListener.onImageFailed();
            }
        }
    }

    /** Lets go of the picture's file and every pixel decoded from it. */
    @MainThread
    private void release() {
        mGeneration++;
        mOpening = false;
        for (Request request : mRequests.values()) {
            if (request.state.compareAndSet(REQUEST_PENDING, REQUEST_CANCELLED)) {
                mReservedPixels -= request.expectedPixels;
            }
            // A running decode keeps its reservation until it lands, and is then thrown away.
        }
        mRequests.clear();
        for (Tile tile : mTiles.values()) {
            tile.bitmap.recycle();
        }
        mTiles.clear();
        mTilePixels = 0;
        if (mBase != null) {
            mBase.recycle();
            mBase = null;
        }
        mBaseSampleSize = 0;
        if (mOpened != null) {
            mOpened.source.close();
            mOpened = null;
        }
        mOrientation = null;
        mScroller.forceFinished(true);
        if (mZoomAnimator != null) {
            mZoomAnimator.cancel();
        }
    }

    /** How many decoded pixels the tiles may take. */
    private long tileBudget() {
        return Math.max(TILE_BUDGET_VIEWS * (long) getWidth() * getHeight(),
                MIN_TILE_BUDGET_PIXELS);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        Bitmap base = mBase;
        ImageSources.Opened opened = mOpened;
        if (base == null || opened == null || !mViewport.isReady()) {
            return;
        }
        RegionSource source = opened.source;
        mDrawOrder.clear();
        mDrawOrder.addAll(mTiles.values());
        // Coarser tiles first, so finer ones land on top of them.
        Collections.sort(mDrawOrder, COARSEST_FIRST);
        for (int copy = 0; copy <= mViewport.lastCopy(); copy++) {
            mDrawMatrix.set(mOrientationMatrix);
            mDrawMatrix.postScale(mViewport.getScale(), mViewport.getScale());
            mDrawMatrix.postTranslate(mViewport.getX() + copy * mViewport.scaledWidth(),
                    mViewport.getY());
            int saved = canvas.save();
            canvas.concat(mDrawMatrix);
            mDestination.set(0, 0, source.getWidth(), source.getHeight());
            canvas.drawBitmap(base, null, mDestination, mPaint);
            for (Tile tile : mDrawOrder) {
                mDestination.set(tile.region);
                canvas.drawBitmap(tile.bitmap, null, mDestination, mPaint);
            }
            canvas.restoreToCount(saved);
        }
        mDrawOrder.clear();
        updateTiles();
    }

    /**
     * Works out which tiles the view needs at the detail it is zoomed to, and asks for those it
     * does not have, nearest the middle first, within the budget.
     */
    @MainThread
    private void updateTiles() {
        ImageSources.Opened opened = mOpened;
        Orientation orientation = mOrientation;
        if (opened == null || orientation == null || mBase == null) {
            return;
        }
        RegionSource source = opened.source;
        int width = source.getWidth();
        int height = source.getHeight();
        long budget = tileBudget();
        mNeeded.clear();
        int sampleSize = Tiles.sampleSize(mViewport.getScale());
        while (sampleSize < mBaseSampleSize) {
            long pixels = 0;
            for (int copy = 0; copy <= mViewport.lastCopy(); copy++) {
                double[] upright = mViewport.visible(copy);
                double[] stored = orientation.toStored(
                        upright[0], upright[1], upright[2], upright[3]);
                pixels += Tiles.covering(stored[0], stored[1], stored[2], stored[3],
                        sampleSize, width, height, mNeeded);
            }
            if (pixels <= budget) {
                break;
            }
            // Too many to hold at this detail: a level coarser instead.
            mNeeded.clear();
            sampleSize *= 2;
        }

        // Called off: what was asked for and is no longer on screen.
        Iterator<Map.Entry<Long, Request>> requests = mRequests.entrySet().iterator();
        while (requests.hasNext()) {
            Request request = requests.next().getValue();
            if (!mNeeded.contains(request.key)
                    && request.state.compareAndSet(REQUEST_PENDING, REQUEST_CANCELLED)) {
                mReservedPixels -= request.expectedPixels;
                requests.remove();
            }
        }

        List<Long> missing = new ArrayList<>();
        for (Long key : mNeeded) {
            if (mTiles.get(key) == null && !mRequests.containsKey(key)) {
                missing.add(key);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        sortByDistanceFromCentre(missing, width, height);
        for (Long key : missing) {
            long expected = Tiles.decodedPixels(key, width, height);
            if (!makeRoom(expected, budget)) {
                // The rest wait for tiles on their way to land.
                break;
            }
            request(key, source, expected);
        }
    }

    /** Puts the tiles nearest the middle of the view first, so the middle sharpens first. */
    @MainThread
    private void sortByDistanceFromCentre(@NonNull List<Long> keys, final int width,
            final int height) {
        Orientation orientation = mOrientation;
        double[] upright = mViewport.visible(0);
        double[] stored = orientation.toStored(upright[0], upright[1], upright[2], upright[3]);
        final double centreX = (stored[0] + stored[2]) / 2;
        final double centreY = (stored[1] + stored[3]) / 2;
        Collections.sort(keys, new Comparator<Long>() {
            @Override
            public int compare(Long a, Long b) {
                return Double.compare(distance(a), distance(b));
            }

            private double distance(long key) {
                Rect region = Tiles.region(key, width, height);
                double dx = region.exactCenterX() - centreX;
                double dy = region.exactCenterY() - centreY;
                return dx * dx + dy * dy;
            }
        });
    }

    /**
     * Throws out tiles the view does not need, least recently drawn first, until a new one fits.
     *
     * @return whether it fits
     */
    @MainThread
    private boolean makeRoom(long pixels, long budget) {
        Iterator<Map.Entry<Long, Tile>> tiles = mTiles.entrySet().iterator();
        while (mTilePixels + mReservedPixels + pixels > budget && tiles.hasNext()) {
            Map.Entry<Long, Tile> entry = tiles.next();
            if (mNeeded.contains(entry.getKey())) {
                continue;
            }
            Tile tile = entry.getValue();
            mTilePixels -= tile.pixels();
            tile.bitmap.recycle();
            tiles.remove();
        }
        return mTilePixels + mReservedPixels + pixels <= budget;
    }

    @MainThread
    private void request(final long key, @NonNull final RegionSource source, long expected) {
        final Request request = new Request(key, mGeneration, expected);
        mRequests.put(key, request);
        mReservedPixels += expected;
        notePeak();
        final Rect region = Tiles.region(key, source.getWidth(), source.getHeight());
        mExecutor.execute(new Runnable() {
            @WorkerThread
            @Override
            public void run() {
                if (!request.state.compareAndSet(REQUEST_PENDING, REQUEST_RUNNING)) {
                    return;
                }
                final Bitmap bitmap = source.decode(region, Tiles.sampleSizeOf(key));
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        onTile(request, region, bitmap);
                    }
                });
            }
        });
    }

    @MainThread
    private void onTile(@NonNull Request request, @NonNull Rect region, @Nullable Bitmap bitmap) {
        mReservedPixels -= request.expectedPixels;
        if (mRequests.get(request.key) == request) {
            mRequests.remove(request.key);
        }
        if (bitmap == null) {
            return;
        }
        if (request.generation != mGeneration || !mNeeded.contains(request.key)) {
            bitmap.recycle();
            return;
        }
        Tile tile = new Tile(region, Tiles.sampleSizeOf(request.key), bitmap);
        mTiles.put(request.key, tile);
        mTilePixels += tile.pixels();
        notePeak();
        invalidate();
    }

    private void notePeak() {
        mPeakPixels = Math.max(mPeakPixels, getHeldPixels());
    }

    @Override
    public void computeScroll() {
        super.computeScroll();
        if (mScroller.computeScrollOffset()) {
            mViewport.moveTo(-mScroller.getCurrX(), -mScroller.getCurrY());
            ViewCompat.postInvalidateOnAnimation(this);
        }
    }

    /** A single tap, which also reaches accessibility services as a click. */
    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        if (mOpened == null || !mViewport.isReady()) {
            return super.onTouchEvent(event);
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mScroller.forceFinished(true);
            if (mZoomAnimator != null) {
                mZoomAnimator.cancel();
            }
            // A picture that can be panned keeps the drag from a scrolling parent.
            ViewParent parent = getParent();
            if (parent != null && mViewport.canPan()) {
                parent.requestDisallowInterceptTouchEvent(true);
            }
        }
        boolean handled = mPinch.onTouchEvent(event);
        handled |= mGestures.onTouchEvent(event);
        return handled || super.onTouchEvent(event);
    }

    /** Zooms smoothly to a scale, keeping the pixel under a point where it is. */
    @MainThread
    private void animateZoom(float target, final float focusX, final float focusY) {
        if (mZoomAnimator != null) {
            mZoomAnimator.cancel();
        }
        final ValueAnimator animator = ValueAnimator.ofFloat(mViewport.getScale(), target);
        animator.setDuration(DOUBLE_TAP_MILLIS);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mViewport.scaleTo((Float) animation.getAnimatedValue(), focusX, focusY);
                invalidate();
            }
        });
        mZoomAnimator = animator;
        animator.start();
    }

    /** Drags, flings and double taps. */
    private final class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(@NonNull MotionEvent event) {
            return true;
        }

        @Override
        public boolean onScroll(@Nullable MotionEvent down, @NonNull MotionEvent event,
                float distanceX, float distanceY) {
            if (mPinch.isInProgress()) {
                return false;
            }
            mViewport.panBy(-distanceX, -distanceY);
            invalidate();
            return true;
        }

        @Override
        public boolean onFling(@Nullable MotionEvent down, @NonNull MotionEvent event,
                float velocityX, float velocityY) {
            boolean wraps = mViewport.wraps();
            mScroller.fling(Math.round(-mViewport.getX()), Math.round(-mViewport.getY()),
                    Math.round(-velocityX), Math.round(-velocityY),
                    wraps ? Integer.MIN_VALUE / 2 : 0,
                    wraps ? Integer.MAX_VALUE / 2 : Math.round(mViewport.panRangeX()),
                    0, Math.round(mViewport.panRangeY()));
            ViewCompat.postInvalidateOnAnimation(LargeImageView.this);
            return true;
        }

        @Override
        public boolean onSingleTapConfirmed(@NonNull MotionEvent event) {
            // A tap is the view's click, for an app that shows and hides its own controls.
            return performClick();
        }

        @Override
        public boolean onDoubleTap(@NonNull MotionEvent event) {
            float min = mViewport.getMinScale();
            float target = mViewport.getScale() < min * 1.05f
                    ? Math.min(mViewport.getMaxScale(), min * DOUBLE_TAP_ZOOM)
                    : min;
            animateZoom(target, event.getX(), event.getY());
            return true;
        }
    }

    /** Pinches, which zoom about the fingers and pan as the fingers move together. */
    private final class PinchListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
            mLastFocusX = detector.getFocusX();
            mLastFocusY = detector.getFocusY();
            return true;
        }

        @Override
        public boolean onScale(@NonNull ScaleGestureDetector detector) {
            mViewport.panBy(detector.getFocusX() - mLastFocusX,
                    detector.getFocusY() - mLastFocusY);
            mViewport.scaleBy(detector.getScaleFactor(), detector.getFocusX(),
                    detector.getFocusY());
            mLastFocusX = detector.getFocusX();
            mLastFocusY = detector.getFocusY();
            invalidate();
            return true;
        }
    }

    /** A decoded tile. */
    private static final class Tile {
        /** The stored pixels it covers. */
        final Rect region;

        /** How many stored pixels across each of its pixels stands for. */
        final int sampleSize;

        /** Its pixels. */
        final Bitmap bitmap;

        Tile(@NonNull Rect region, int sampleSize, @NonNull Bitmap bitmap) {
            this.region = region;
            this.sampleSize = sampleSize;
            this.bitmap = bitmap;
        }

        @AnyThread
        long pixels() {
            return (long) bitmap.getWidth() * bitmap.getHeight();
        }
    }

    /** A tile on its way. */
    private static final class Request {
        /** Which tile. */
        final long key;

        /** Which picture it is for. */
        final int generation;

        /** The pixels set aside for it. */
        final long expectedPixels;

        /**
         * A {@link RequestState}: pending, running or called off. A worker and the main thread
         * race for it.
         */
        final AtomicInteger state = new AtomicInteger(REQUEST_PENDING);

        Request(long key, int generation, long expectedPixels) {
            this.key = key;
            this.generation = generation;
            this.expectedPixels = expectedPixels;
        }
    }
}
