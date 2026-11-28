package com.xlythe.view.camera.edit;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.IntRange;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Stickers and text over a picture: placed, moved, scaled, turned, flipped, reordered and
 * removed, by touch or by the app. Stickers can be animated GIF, WebP or PNG.
 *
 * <p>Items are kept in the units of the picture beneath, of a size the app sets with
 * {@link #setCanvasSize}, drawn to fit the view. A touch that lands on no item, or on a
 * transparent part of one, passes through to the views beneath, such as a
 * {@link DrawingView}.</p>
 *
 * <p>An animated PNG animates on every release. An animated GIF or WebP animates from
 * Android 9, through the platform's own decoder, and shows its first frame, still, before
 * that.</p>
 *
 * <h2>Gestures</h2>
 * <ul>
 *   <li>Drag an item to move it; pinch and twist with two fingers to scale and turn it.</li>
 *   <li>The chosen item shows handles at its corners: drag the one at the bottom right to scale
 *   and turn, tap the one at the top left to flip it, and the one at the top right to remove it.
 *   They stay at those corners whichever way the item is flipped.</li>
 *   <li>Drag an item onto the app's delete zone, set with {@link #setDeleteZone}, to remove it.
 *   </li>
 *   <li>Drop a picture from another app, or from the app's own sticker grid with
 *   {@link #startDrag}, to add it where it lands; see {@link #setDropTargetEnabled}.</li>
 * </ul>
 *
 * <p>The handles and outline are tinted with the theme's {@code colorControlActivated}, and
 * draw no text.</p>
 */
public class OverlayView extends View {
    /** The tag this logs under. */
    private static final String TAG = "OverlayView";

    /** The most items unless the app says otherwise. */
    @VisibleForTesting
    static final int DEFAULT_MAX_OVERLAYS = 24;

    /** The longest side a sticker is decoded at unless the app says otherwise. */
    @VisibleForTesting
    static final int DEFAULT_MAX_DIMENSION = 1024;

    /** The most pixels an animated sticker's frames may take together. */
    private static final long MAX_ANIMATION_PIXELS = 24_000_000L;

    /** The least alpha a sticker's pixel needs to be touched. */
    private static final int HIT_ALPHA = 16;

    /** How large a handle is drawn, and how far beyond it a touch still reaches it, in dp. */
    private static final float HANDLE_DP = 12f;
    private static final float HANDLE_REACH_DP = 24f;

    /** The share of the canvas's shorter side a new item takes. */
    private static final float NEW_ITEM_SHARE = 0.35f;

    /** The text size of a new text item, as a share of the canvas's shorter side. */
    private static final float TEXT_SHARE = 0.08f;

    /** What a touch is doing. */
    private static final int MODE_NONE = 0;
    private static final int MODE_MOVE = 1;
    private static final int MODE_HANDLE = 2;
    private static final int MODE_PINCH = 3;

    /** Undo and redo over the list of items. */
    private final History<List<Overlay>> mHistory =
            new History<>(Collections.<Overlay>emptyList(), History.DEFAULT_CAPACITY);
    /** What each item's picture decoded to, by item. */
    private final Map<String, OverlayContent> mContents = new HashMap<>();
    /** Decodes pictures off the main thread, one at a time, on a thread that ends when idle. */
    private final Executor mExecutor = new ThreadPoolExecutor(
            0, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
    /** Posts decoded pictures back. */
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    /** The canvas's size, in the picture's units, or 0 to use the view's pixels. */
    private float mCanvasWidth;
    private float mCanvasHeight;
    /** Maps the picture's units to the view and back. */
    private final Matrix mCanvasToView = new Matrix();
    private final Matrix mViewToCanvas = new Matrix();
    /** The chosen item, or null. */
    @Nullable private String mSelected;
    /** Told about changes. */
    @Nullable private OnOverlayChangedListener mListener;
    /** The app's delete zone, or null. */
    @Nullable private View mDeleteZone;
    /** The limits. */
    private int mMaxOverlays = DEFAULT_MAX_OVERLAYS;
    private int mMaxDimension = DEFAULT_MAX_DIMENSION;
    /** When the animation clock started, in uptime milliseconds. */
    private final long mClockStart = SystemClock.uptimeMillis();
    /** Whether the handles are drawn on the chosen item. */
    private boolean mHandlesShown = true;
    /** Whether the view is in a window, so the platform's animations play. */
    private boolean mAttached;
    /** Lets the platform's animations redraw this view and time their frames. */
    private final Drawable.Callback mDrawableCallback = new Drawable.Callback() {
        @Override
        public void invalidateDrawable(@NonNull Drawable who) {
            invalidate();
        }

        @Override
        public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
            mHandler.postAtTime(what, who, when);
        }

        @Override
        public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
            mHandler.removeCallbacks(what, who);
        }
    };

    /** The gesture in progress. */
    private int mMode = MODE_NONE;
    /** Which handle is being dragged. */
    private int mHandle = -1;
    /** The item as it was when the gesture began. */
    @Nullable private Overlay mGestureStart;
    /** Where the gesture began, in the picture's units. */
    private float mDownX;
    private float mDownY;
    /** The two-finger span and angle when the pinch began. */
    private float mPinchSpan;
    private float mPinchAngle;
    /** Whether this gesture has changed anything yet, so the first change commits. */
    private boolean mGestureCommitted;
    /** Whether the dragged item is over the delete zone. */
    private boolean mOverDeleteZone;
    /** Spots taps and long presses. */
    private final GestureDetector mGestures;

    /** Draws. */
    private final Paint mBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint mOutline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHandleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlyph = new Paint(Paint.ANTI_ALIAS_FLAG);

    public OverlayView(@NonNull Context context) {
        this(context, null);
    }

    public OverlayView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        int accent = Build.VERSION.SDK_INT >= 21 ? DrawingView.themeColor(context,
                android.R.attr.colorControlActivated, 0xFF1E88E5) : 0xFF1E88E5;
        mOutline.setStyle(Paint.Style.STROKE);
        mOutline.setStrokeWidth(dp(1.5f));
        mOutline.setColor(accent);
        mHandleFill.setColor(accent);
        mGlyph.setColor(Color.WHITE);
        mGlyph.setStyle(Paint.Style.STROKE);
        mGlyph.setStrokeWidth(dp(1.75f));
        mGlyph.setStrokeCap(Paint.Cap.ROUND);
        mGlyph.setStrokeJoin(Paint.Join.ROUND);
        mGestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(@NonNull MotionEvent event) {
                onLongPressAt(event.getX(), event.getY());
            }
        });
    }

    // ---------- Items ----------

    /**
     * The size of the picture beneath, in units of the app's own, such as its pixels. Items are
     * kept in these units, drawn to fit the view. A size of 0 uses the view's own pixels.
     */
    @MainThread
    public void setCanvasSize(float width, float height) {
        mCanvasWidth = Math.max(0, width);
        mCanvasHeight = Math.max(0, height);
        layoutCanvas();
        invalidate();
    }

    /**
     * Adds a sticker, decoded off the main thread, to the top, and chooses it.
     *
     * @param mimeType the picture's MIME type, or null to tell it from its bytes
     * @param at where its middle goes, in the picture's units, or null for the middle
     * @return the item, as it is added; it takes its size once decoded
     */
    @MainThread
    @NonNull
    public Overlay addImage(@NonNull Uri uri, @Nullable String mimeType, @Nullable PointF at) {
        float side = Math.min(canvasWidth(), canvasHeight()) * NEW_ITEM_SHARE;
        Overlay overlay = Overlay.Builder.image(uri, mimeType)
                .setCenter(at == null ? canvasWidth() / 2f : at.x,
                        at == null ? canvasHeight() / 2f : at.y)
                .setSize(Math.max(1, side), Math.max(1, side))
                .build();
        add(overlay);
        load(overlay, true);
        return overlay;
    }

    /** Adds a line of text in a colour to the middle, on top, and chooses it. */
    @MainThread
    @NonNull
    public Overlay addText(@NonNull CharSequence text, int color) {
        float size = Math.min(canvasWidth(), canvasHeight()) * TEXT_SHARE;
        OverlayContent content = OverlayContent.text(text, color, size);
        Overlay overlay = Overlay.Builder.text(text)
                .setTextColor(color)
                .setCenter(canvasWidth() / 2f, canvasHeight() / 2f)
                .setSize(content.width(), content.height())
                .build();
        mContents.put(overlay.getId(), content);
        add(overlay);
        return overlay;
    }

    /** Puts an item in place of the one of the same name, such as with new text, as one step. */
    @MainThread
    public void updateOverlay(@NonNull Overlay overlay) {
        List<Overlay> items = new ArrayList<>(mHistory.current());
        int index = indexOf(items, overlay.getId());
        if (index < 0) {
            return;
        }
        Overlay old = items.get(index);
        if (overlay.getKind() == Overlay.KIND_TEXT && overlay.getText() != null) {
            float textSize = textSizeOf(old);
            OverlayContent content = OverlayContent.text(overlay.getText(),
                    overlay.getTextColor(), textSize);
            mContents.put(overlay.getId(), content);
            overlay = new Overlay.Builder(overlay).setSize(content.width(), content.height())
                    .build();
        }
        items.set(index, overlay);
        commit(items);
    }

    /** The items, bottom first, which cannot be changed. */
    @MainThread
    @NonNull
    public List<Overlay> getOverlays() {
        return mHistory.current();
    }

    /** Shows these items, bottom first, with nothing to undo, decoding their pictures. */
    @MainThread
    public void setOverlays(@NonNull List<Overlay> overlays) {
        mHistory.reset(Collections.unmodifiableList(new ArrayList<>(overlays)));
        for (Overlay overlay : overlays) {
            if (!mContents.containsKey(overlay.getId())) {
                if (overlay.getKind() == Overlay.KIND_IMAGE) {
                    load(overlay, false);
                } else if (overlay.getText() != null) {
                    mContents.put(overlay.getId(), OverlayContent.text(overlay.getText(),
                            overlay.getTextColor(), overlay.getHeight() * 0.8f));
                }
            }
        }
        select(null);
        changed();
    }

    /** Chooses an item, or none for null. */
    @MainThread
    public void select(@Nullable String id) {
        if (id != null && indexOf(mHistory.current(), id) < 0) {
            id = null;
        }
        if (id == null ? mSelected == null : id.equals(mSelected)) {
            return;
        }
        mSelected = id;
        if (mListener != null) {
            mListener.onSelectionChanged(id == null ? null : find(id));
        }
        invalidate();
    }

    /** The chosen item's name, or null. */
    @MainThread
    @Nullable
    public String getSelectedId() {
        return mSelected;
    }

    /** Removes an item, as a step that can be undone. */
    @MainThread
    public void remove(@NonNull String id) {
        List<Overlay> items = new ArrayList<>(mHistory.current());
        int index = indexOf(items, id);
        if (index < 0) {
            return;
        }
        items.remove(index);
        if (id.equals(mSelected)) {
            select(null);
        }
        commit(items);
    }

    /** Moves an item to a place in the order, 0 being the bottom. */
    @MainThread
    public void moveOverlay(@NonNull String id, @IntRange(from = 0) int toIndex) {
        List<Overlay> items = new ArrayList<>(mHistory.current());
        int index = indexOf(items, id);
        if (index < 0) {
            return;
        }
        int to = Math.max(0, Math.min(items.size() - 1, toIndex));
        if (to == index) {
            return;
        }
        items.add(to, items.remove(index));
        commit(items);
    }

    /** Moves an item to the top. */
    @MainThread
    public void bringToFront(@NonNull String id) {
        moveOverlay(id, mHistory.current().size() - 1);
    }

    /** Moves an item to the bottom. */
    @MainThread
    public void sendToBack(@NonNull String id) {
        moveOverlay(id, 0);
    }

    @MainThread
    public void undo() {
        if (mHistory.canUndo()) {
            mHistory.undo();
            changed();
        }
    }

    @MainThread
    public void redo() {
        if (mHistory.canRedo()) {
            mHistory.redo();
            changed();
        }
    }

    @MainThread
    public boolean canUndo() {
        return mHistory.canUndo();
    }

    @MainThread
    public boolean canRedo() {
        return mHistory.canRedo();
    }

    /** Hears items change and the choice change. */
    @MainThread
    public void setOnOverlayChangedListener(@Nullable OnOverlayChangedListener listener) {
        mListener = listener;
    }

    /**
     * The most items, how large a sticker's longest side is decoded, in pixels: 24 and 1024 by
     * default. Adding past the most is refused.
     */
    @MainThread
    public void setLimits(@IntRange(from = 1) int maxOverlays,
            @IntRange(from = 1) int maxDimension) {
        mMaxOverlays = Math.max(1, maxOverlays);
        mMaxDimension = Math.max(1, maxDimension);
    }

    /** Whether the chosen item shows its handles, as it does by default. */
    @MainThread
    public void setHandlesShown(boolean shown) {
        mHandlesShown = shown;
        invalidate();
    }

    // ---------- Drag and drop ----------

    /**
     * An app view that removes an item dragged onto it. It is set activated while an item is
     * over it, so its own background can show that, and is drawn by the app, never here.
     */
    @MainThread
    public void setDeleteZone(@Nullable View zone) {
        mDeleteZone = zone;
    }

    /**
     * Whether a picture dropped on the view, from another app or from {@link #startDrag}, is
     * added where it lands. Off by default.
     */
    @MainThread
    public void setDropTargetEnabled(boolean enabled) {
        setOnDragListener(enabled ? new DropListener() : null);
    }

    /**
     * Starts dragging a picture from a view, such as a cell of a sticker grid, so it can be
     * dropped on an overlay view here or in another app, which may read it.
     *
     * @return whether the drag started
     */
    @MainThread
    @SuppressWarnings("deprecation")
    public static boolean startDrag(@NonNull View source, @NonNull Uri uri,
            @NonNull String mimeType) {
        ClipData clip = new ClipData(new ClipDescription("", new String[] {mimeType}),
                new ClipData.Item(uri));
        View.DragShadowBuilder shadow = new View.DragShadowBuilder(source);
        if (Build.VERSION.SDK_INT >= 24) {
            return source.startDragAndDrop(clip, shadow, uri, View.DRAG_FLAG_GLOBAL
                    | View.DRAG_FLAG_GLOBAL_URI_READ
                    | View.DRAG_FLAG_GLOBAL_PERSISTABLE_URI_PERMISSION);
        }
        return source.startDrag(clip, shadow, uri, 0);
    }

    /** Takes a dropped picture. */
    private final class DropListener implements OnDragListener {
        @Override
        public boolean onDrag(@NonNull View view, @NonNull DragEvent event) {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED: {
                    ClipDescription description = event.getClipDescription();
                    return event.getLocalState() instanceof Uri || (description != null
                            && (description.hasMimeType("image/*")
                            || description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)));
                }
                case DragEvent.ACTION_DROP:
                    return onDrop(event);
                default:
                    return true;
            }
        }
    }

    private boolean onDrop(@NonNull DragEvent event) {
        Uri uri = event.getLocalState() instanceof Uri ? (Uri) event.getLocalState() : null;
        String mime = null;
        ClipData clip = event.getClipData();
        if (uri == null && clip != null && clip.getItemCount() > 0) {
            uri = clip.getItemAt(0).getUri();
            ClipDescription description = clip.getDescription();
            mime = description != null && description.getMimeTypeCount() > 0
                    ? description.getMimeType(0) : null;
        }
        if (uri == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 24 && !(event.getLocalState() instanceof Uri)) {
            Activity activity = activityOf(getContext());
            if (activity != null) {
                // Held until the activity ends, which is as long as the item can need it.
                activity.requestDragAndDropPermissions(event);
            }
        }
        float[] point = {event.getX(), event.getY()};
        mViewToCanvas.mapPoints(point);
        addImage(uri, mime != null && mime.startsWith("image/") ? mime : null,
                new PointF(point[0], point[1]));
        return true;
    }

    @Nullable
    private static Activity activityOf(@NonNull Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            current = ((ContextWrapper) current).getBaseContext();
        }
        return null;
    }

    // ---------- Export ----------

    /**
     * The items as a transparent picture of a size, stretched to fill it, at a moment: such as
     * the size of the picture they are over, to pass to {@link PictureEdits.Builder#setOverlay}.
     * Items not shown at that moment, by their start and end times, and items still decoding
     * are left out; each animation plays from when its item starts showing.
     */
    @MainThread
    @NonNull
    public Bitmap toBitmap(int width, int height, long timeMillis) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(width / canvasWidth(), height / canvasHeight());
        drawItems(canvas, timeMillis, false);
        return bitmap;
    }

    /** Whether every item's picture has been decoded. */
    @MainThread
    boolean isDecoded() {
        for (Overlay overlay : mHistory.current()) {
            if (!mContents.containsKey(overlay.getId())) {
                return false;
            }
        }
        return true;
    }

    /** Whether any item animates, such as to save the edit as an animation. */
    @MainThread
    public boolean isAnimated() {
        for (Overlay overlay : mHistory.current()) {
            OverlayContent content = mContents.get(overlay.getId());
            if (content != null && content.isAnimated()) {
                return true;
            }
        }
        return false;
    }

    // ---------- Drawing ----------

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mAttached = true;
        for (OverlayContent content : mContents.values()) {
            content.setCallback(mDrawableCallback);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        mAttached = false;
        for (OverlayContent content : mContents.values()) {
            content.setCallback(null);
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        layoutCanvas();
    }

    private void layoutCanvas() {
        mCanvasToView.reset();
        float width = getWidth();
        float height = getHeight();
        if (mCanvasWidth > 0 && mCanvasHeight > 0 && width > 0 && height > 0) {
            float scale = Math.min(width / mCanvasWidth, height / mCanvasHeight);
            mCanvasToView.setScale(scale, scale);
            mCanvasToView.postTranslate((width - mCanvasWidth * scale) / 2f,
                    (height - mCanvasHeight * scale) / 2f);
        }
        mCanvasToView.invert(mViewToCanvas);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        long now = SystemClock.uptimeMillis() - mClockStart;
        int saved = canvas.save();
        canvas.concat(mCanvasToView);
        drawItems(canvas, now, true);
        canvas.restoreToCount(saved);
        Overlay selected = mSelected == null ? null : find(mSelected);
        if (selected != null && mHandlesShown) {
            drawSelection(canvas, selected);
        }
        long wait = untilNextFrame(now);
        if (wait >= 0 && isShown()) {
            postInvalidateDelayed(wait);
        }
    }

    /** How long until any item's animation shows its next frame, or -1 if none will. */
    private long untilNextFrame(long timeMillis) {
        long soonest = -1;
        for (Overlay overlay : mHistory.current()) {
            OverlayContent content = mContents.get(overlay.getId());
            long wait = content == null ? -1 : content.untilNextFrame(timeMillis);
            if (wait >= 0 && (soonest < 0 || wait < soonest)) {
                soonest = wait;
            }
        }
        return soonest;
    }

    /**
     * Draws every item that has decoded, in order, at a moment. On screen every item shows on
     * one clock; in a picture of a moment of a video, only the items shown then, each animated
     * from when it starts showing.
     */
    private void drawItems(@NonNull Canvas canvas, long timeMillis, boolean screen) {
        for (Overlay overlay : mHistory.current()) {
            OverlayContent content = mContents.get(overlay.getId());
            if (content == null || (!screen && !overlay.isShownAt(timeMillis))) {
                continue;
            }
            int saved = canvas.save();
            canvas.concat(overlay.matrix());
            if (screen && mOverDeleteZone && overlay.getId().equals(mSelected)) {
                mBitmapPaint.setAlpha(128);
            }
            content.draw(canvas, overlay,
                    screen ? timeMillis : timeMillis - overlay.getStartMillis(), mBitmapPaint);
            mBitmapPaint.setAlpha(255);
            canvas.restoreToCount(saved);
        }
    }

    /** Draws the chosen item's outline and handles, in the view's pixels. */
    private void drawSelection(@NonNull Canvas canvas, @NonNull Overlay overlay) {
        float[] corners = corners(overlay);
        Path outline = new Path();
        outline.moveTo(corners[0], corners[1]);
        for (int i = 2; i < 8; i += 2) {
            outline.lineTo(corners[i], corners[i + 1]);
        }
        outline.close();
        canvas.drawPath(outline, mOutline);
        float radius = dp(HANDLE_DP);
        for (int handle = 0; handle < 3; handle++) {
            float x = corners[handleCorner(handle) * 2];
            float y = corners[handleCorner(handle) * 2 + 1];
            canvas.drawCircle(x, y, radius, mHandleFill);
            drawGlyph(canvas, handle, x, y, radius * 0.55f);
        }
    }

    /** The handles: flip at the top left, remove at the top right, scale and turn at the bottom right. */
    private static final int HANDLE_FLIP = 0;
    private static final int HANDLE_REMOVE = 1;
    private static final int HANDLE_TRANSFORM = 2;

    /** Which corner, as seen, a handle sits at: 0 top left, 1 top right, 2 bottom right. */
    private static int handleCorner(int handle) {
        return handle == HANDLE_FLIP ? 0 : handle == HANDLE_REMOVE ? 1 : 2;
    }

    /** Draws a handle's icon: two facing arrowheads, a cross, or a corner arrow. */
    private void drawGlyph(@NonNull Canvas canvas, int handle, float x, float y, float size) {
        Path path = new Path();
        if (handle == HANDLE_FLIP) {
            path.moveTo(x - size * 0.15f, y - size);
            path.lineTo(x - size, y);
            path.lineTo(x - size * 0.15f, y + size);
            path.close();
            path.moveTo(x + size * 0.15f, y - size);
            path.lineTo(x + size, y);
            path.lineTo(x + size * 0.15f, y + size);
            path.close();
        } else if (handle == HANDLE_REMOVE) {
            path.moveTo(x - size * 0.7f, y - size * 0.7f);
            path.lineTo(x + size * 0.7f, y + size * 0.7f);
            path.moveTo(x + size * 0.7f, y - size * 0.7f);
            path.lineTo(x - size * 0.7f, y + size * 0.7f);
        } else {
            path.moveTo(x - size * 0.7f, y - size * 0.7f);
            path.lineTo(x + size * 0.7f, y + size * 0.7f);
            path.moveTo(x + size * 0.7f, y);
            path.lineTo(x + size * 0.7f, y + size * 0.7f);
            path.lineTo(x, y + size * 0.7f);
        }
        canvas.drawPath(path, mGlyph);
    }

    /**
     * An item's corners in the view's pixels, as seen: top left, top right, bottom right,
     * bottom left, whichever way it is flipped, so the handles stay where a user expects them.
     */
    @NonNull
    private float[] corners(@NonNull Overlay overlay) {
        Overlay unflipped = new Overlay.Builder(overlay).setFlippedHorizontally(false)
                .setFlippedVertically(false).build();
        float[] corners = {0, 0, overlay.getWidth(), 0, overlay.getWidth(), overlay.getHeight(),
                0, overlay.getHeight()};
        Matrix matrix = unflipped.matrix();
        matrix.postConcat(mCanvasToView);
        matrix.mapPoints(corners);
        return corners;
    }

    // ---------- Touch ----------

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
        mGestures.onTouchEvent(event);
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                Overlay selected = mSelected == null ? null : find(mSelected);
                int handle = selected == null || !mHandlesShown ? -1 : handleAt(selected, x, y);
                if (handle >= 0) {
                    mMode = MODE_HANDLE;
                    mHandle = handle;
                    mGestureStart = selected;
                } else {
                    Overlay hit = itemAt(x, y);
                    if (hit == null) {
                        select(null);
                        mMode = MODE_NONE;
                        return false;
                    }
                    select(hit.getId());
                    mMode = MODE_MOVE;
                    mGestureStart = hit;
                }
                float[] point = toCanvas(x, y);
                mDownX = point[0];
                mDownY = point[1];
                mGestureCommitted = false;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (mSelected != null && event.getPointerCount() == 2) {
                    mMode = MODE_PINCH;
                    mGestureStart = find(mSelected);
                    mPinchSpan = span(event);
                    mPinchAngle = angle(event);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                Overlay start = mGestureStart;
                if (start == null) {
                    return mMode != MODE_NONE;
                }
                float[] point = toCanvas(x, y);
                if (mMode == MODE_MOVE) {
                    apply(new Overlay.Builder(start).setCenter(
                            start.getCenterX() + point[0] - mDownX,
                            start.getCenterY() + point[1] - mDownY).build());
                    updateDeleteZone(event);
                } else if (mMode == MODE_HANDLE && mHandle == HANDLE_TRANSFORM) {
                    apply(transformedByHandle(start, point[0], point[1]));
                } else if (mMode == MODE_PINCH && event.getPointerCount() >= 2) {
                    float scale = mPinchSpan > 0 ? span(event) / mPinchSpan : 1f;
                    float turn = angle(event) - mPinchAngle;
                    apply(new Overlay.Builder(start)
                            .setScale(clampScale(start, start.getScale() * scale))
                            .setRotationDegrees(start.getRotationDegrees() + turn).build());
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                if (mMode == MODE_PINCH) {
                    // The finger left carries on moving the item from where it is now.
                    int remaining = event.getActionIndex() == 0 ? 1 : 0;
                    float[] point = toCanvas(event.getX(remaining), event.getY(remaining));
                    mDownX = point[0];
                    mDownY = point[1];
                    mGestureStart = mSelected == null ? null : find(mSelected);
                    mMode = MODE_MOVE;
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mMode == MODE_HANDLE && event.getActionMasked() == MotionEvent.ACTION_UP
                        && mGestureStart != null && !mGestureCommitted) {
                    if (mHandle == HANDLE_FLIP) {
                        apply(new Overlay.Builder(mGestureStart).setFlippedHorizontally(
                                !mGestureStart.isFlippedHorizontally()).build());
                    } else if (mHandle == HANDLE_REMOVE) {
                        remove(mGestureStart.getId());
                    }
                }
                if (mOverDeleteZone && mSelected != null
                        && event.getActionMasked() == MotionEvent.ACTION_UP) {
                    String id = mSelected;
                    setDeleteZoneActive(false);
                    remove(id);
                }
                setDeleteZoneActive(false);
                if (mGestureCommitted && mListener != null) {
                    mListener.onOverlaysChanged(mHistory.current());
                }
                mMode = MODE_NONE;
                mGestureStart = null;
                mHandle = -1;
                return true;
            }
            default:
                return mMode != MODE_NONE;
        }
    }

    /** The item a scale-and-turn handle drag makes: scaled and turned about its middle. */
    @NonNull
    private Overlay transformedByHandle(@NonNull Overlay start, float x, float y) {
        float startDx = mDownX - start.getCenterX();
        float startDy = mDownY - start.getCenterY();
        float dx = x - start.getCenterX();
        float dy = y - start.getCenterY();
        float startLength = (float) Math.hypot(startDx, startDy);
        float length = (float) Math.hypot(dx, dy);
        float scale = startLength > 0 ? start.getScale() * length / startLength
                : start.getScale();
        float turn = (float) Math.toDegrees(Math.atan2(dy, dx) - Math.atan2(startDy, startDx));
        return new Overlay.Builder(start).setScale(clampScale(start, scale))
                .setRotationDegrees(start.getRotationDegrees() + turn).build();
    }

    /** Keeps an item from vanishing or swallowing the picture. */
    private float clampScale(@NonNull Overlay overlay, float scale) {
        float side = Math.max(overlay.getWidth(), overlay.getHeight());
        float smallest = dp(16) / Math.max(1e-3f, mCanvasToView.mapRadius(1f)) / side;
        float largest = 4f * Math.max(canvasWidth(), canvasHeight()) / side;
        return Math.max(smallest, Math.min(largest, scale));
    }

    /** Makes a gesture's change: the first commits, the rest amend, so the gesture is one step. */
    private void apply(@NonNull Overlay overlay) {
        List<Overlay> items = new ArrayList<>(mHistory.current());
        int index = indexOf(items, overlay.getId());
        if (index < 0) {
            return;
        }
        items.set(index, overlay);
        List<Overlay> next = Collections.unmodifiableList(items);
        if (mGestureCommitted) {
            mHistory.amend(next);
        } else {
            mHistory.commit(next);
            mGestureCommitted = true;
        }
        invalidate();
    }

    /** A long press on a sticker drags it out, to another app or another view. */
    private void onLongPressAt(float x, float y) {
        Overlay hit = itemAt(x, y);
        if (hit == null || hit.getUri() == null || mMode == MODE_PINCH || mGestureCommitted) {
            return;
        }
        String mime = hit.getMimeType() == null ? "image/*" : hit.getMimeType();
        startDrag(this, hit.getUri(), mime);
    }

    /** Which handle of an item a touch reaches, or -1. */
    private int handleAt(@NonNull Overlay overlay, float x, float y) {
        float[] corners = corners(overlay);
        float reach = dp(HANDLE_REACH_DP);
        for (int handle = 0; handle < 3; handle++) {
            float hx = corners[handleCorner(handle) * 2];
            float hy = corners[handleCorner(handle) * 2 + 1];
            if (Math.hypot(x - hx, y - hy) <= reach) {
                return handle;
            }
        }
        return -1;
    }

    /** The topmost item a touch lands on, where its picture is not transparent, or null. */
    @VisibleForTesting
    @Nullable
    Overlay itemAt(float x, float y) {
        float[] point = toCanvas(x, y);
        List<Overlay> items = mHistory.current();
        for (int i = items.size() - 1; i >= 0; i--) {
            Overlay overlay = items.get(i);
            Matrix inverse = new Matrix();
            if (!overlay.matrix().invert(inverse)) {
                continue;
            }
            float[] local = point.clone();
            inverse.mapPoints(local);
            if (local[0] < 0 || local[1] < 0 || local[0] >= overlay.getWidth()
                    || local[1] >= overlay.getHeight()) {
                continue;
            }
            OverlayContent content = mContents.get(overlay.getId());
            if (content == null || content.alphaAt(local[0] / overlay.getWidth(),
                    local[1] / overlay.getHeight(),
                    SystemClock.uptimeMillis() - mClockStart) >= HIT_ALPHA) {
                return overlay;
            }
        }
        return null;
    }

    private void updateDeleteZone(@NonNull MotionEvent event) {
        View zone = mDeleteZone;
        if (zone == null || !zone.isShown()) {
            return;
        }
        int[] location = new int[2];
        zone.getLocationOnScreen(location);
        Rect bounds = new Rect(location[0], location[1], location[0] + zone.getWidth(),
                location[1] + zone.getHeight());
        boolean over = bounds.contains((int) event.getRawX(), (int) event.getRawY());
        setDeleteZoneActive(over);
    }

    private void setDeleteZoneActive(boolean active) {
        if (mOverDeleteZone == active) {
            return;
        }
        mOverDeleteZone = active;
        if (mDeleteZone != null) {
            mDeleteZone.setActivated(active);
        }
        invalidate();
    }

    private static float span(@NonNull MotionEvent event) {
        return (float) Math.hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0));
    }

    private static float angle(@NonNull MotionEvent event) {
        return (float) Math.toDegrees(Math.atan2(event.getY(1) - event.getY(0),
                event.getX(1) - event.getX(0)));
    }

    // ---------- Loading ----------

    /** Decodes an item's picture off the main thread, sizing it to its picture if asked. */
    private void load(@NonNull Overlay overlay, boolean sizeToPicture) {
        Uri uri = overlay.getUri();
        if (uri == null) {
            return;
        }
        Context context = getContext().getApplicationContext();
        int maxDimension = mMaxDimension;
        mExecutor.execute(() -> {
            OverlayContent content = OverlayContent.decode(context, uri, overlay.getMimeType(),
                    maxDimension, MAX_ANIMATION_PIXELS);
            mHandler.post(() -> loaded(overlay.getId(), content, sizeToPicture));
        });
    }

    @MainThread
    private void loaded(@NonNull String id, @Nullable OverlayContent content,
            boolean sizeToPicture) {
        if (content != null) {
            // Kept even if the item was undone meanwhile, so a redo shows it.
            mContents.put(id, content);
            content.setCallback(mAttached ? mDrawableCallback : null);
        }
        Overlay overlay = find(id);
        if (overlay == null) {
            return;
        }
        if (content == null) {
            Log.w(TAG, "Unable to decode " + overlay.getUri() + "; the item is removed");
            List<Overlay> items = new ArrayList<>(mHistory.current());
            items.remove(indexOf(items, id));
            // Not a step: the item never showed.
            mHistory.amend(Collections.unmodifiableList(items));
            changed();
            return;
        }
        if (sizeToPicture) {
            float side = Math.max(overlay.getWidth(), overlay.getHeight());
            float aspect = content.width() / Math.max(1f, content.height());
            float width = aspect >= 1 ? side : side * aspect;
            float height = aspect >= 1 ? side / aspect : side;
            List<Overlay> items = new ArrayList<>(mHistory.current());
            items.set(indexOf(items, id), new Overlay.Builder(overlay).setSize(width, height)
                    .build());
            mHistory.amend(Collections.unmodifiableList(items));
        }
        changed();
    }

    // ---------- State ----------

    @Nullable
    @Override
    protected Parcelable onSaveInstanceState() {
        return new OverlayState(super.onSaveInstanceState(), mHistory.current(), mSelected);
    }

    @Override
    protected void onRestoreInstanceState(@Nullable Parcelable state) {
        if (!(state instanceof OverlayState)) {
            super.onRestoreInstanceState(state);
            return;
        }
        OverlayState saved = (OverlayState) state;
        super.onRestoreInstanceState(saved.getSuperState());
        setOverlays(saved.mOverlays);
        select(saved.mSelected);
    }

    /** The items and the choice, kept across a configuration change. */
    static final class OverlayState extends BaseSavedState {
        /** The items, bottom first. */
        @NonNull final List<Overlay> mOverlays;
        /** The chosen item's name, or null. */
        @Nullable final String mSelected;

        OverlayState(@Nullable Parcelable superState, @NonNull List<Overlay> overlays,
                @Nullable String selected) {
            super(superState);
            mOverlays = overlays;
            mSelected = selected;
        }

        OverlayState(@NonNull Parcel source) {
            super(source);
            List<Overlay> overlays = source.createTypedArrayList(Overlay.CREATOR);
            mOverlays = overlays == null ? Collections.<Overlay>emptyList() : overlays;
            mSelected = source.readString();
        }

        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeTypedList(mOverlays);
            out.writeString(mSelected);
        }

        public static final Creator<OverlayState> CREATOR = new Creator<OverlayState>() {
            @Override
            public OverlayState createFromParcel(Parcel source) {
                return new OverlayState(source);
            }

            @Override
            public OverlayState[] newArray(int size) {
                return new OverlayState[size];
            }
        };
    }

    // ---------- Helpers ----------

    private void add(@NonNull Overlay overlay) {
        List<Overlay> items = new ArrayList<>(mHistory.current());
        if (items.size() >= mMaxOverlays) {
            Log.w(TAG, "Refused an item past the most, " + mMaxOverlays);
            return;
        }
        items.add(overlay);
        commit(items);
        select(overlay.getId());
    }

    private void commit(@NonNull List<Overlay> items) {
        mHistory.commit(Collections.unmodifiableList(items));
        changed();
    }

    private void changed() {
        if (mSelected != null && find(mSelected) == null) {
            select(null);
        }
        invalidate();
        if (mListener != null) {
            mListener.onOverlaysChanged(mHistory.current());
        }
    }

    @Nullable
    private Overlay find(@NonNull String id) {
        for (Overlay overlay : mHistory.current()) {
            if (overlay.getId().equals(id)) {
                return overlay;
            }
        }
        return null;
    }

    private static int indexOf(@NonNull List<Overlay> items, @NonNull String id) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getId().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private float textSizeOf(@NonNull Overlay overlay) {
        OverlayContent content = mContents.get(overlay.getId());
        return content == null ? overlay.getHeight() * 0.8f : content.textSize();
    }

    @NonNull
    private float[] toCanvas(float x, float y) {
        float[] point = {x, y};
        mViewToCanvas.mapPoints(point);
        return point;
    }

    private float canvasWidth() {
        return mCanvasWidth > 0 ? mCanvasWidth : Math.max(1, getWidth());
    }

    private float canvasHeight() {
        return mCanvasHeight > 0 ? mCanvasHeight : Math.max(1, getHeight());
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    /** Hears items change. */
    public interface OnOverlayChangedListener {
        /** Called on the main thread with the items, bottom first, after any change. */
        void onOverlaysChanged(@NonNull List<Overlay> overlays);

        /** Called on the main thread when an item is chosen, or none for null. */
        void onSelectionChanged(@Nullable Overlay overlay);
    }
}
