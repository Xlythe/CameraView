package com.xlythe.sample.camera;

import static com.xlythe.sample.camera.MainActivity.TAG;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;
import androidx.appcompat.app.AlertDialog;
import androidx.core.os.BundleCompat;
import androidx.fragment.app.Fragment;

import com.xlythe.view.camera.MediaTags;
import com.xlythe.view.camera.edit.BrushPanelView;
import com.xlythe.view.camera.edit.Drawing;
import com.xlythe.view.camera.edit.DrawingView;
import com.xlythe.view.camera.edit.EditHistory;
import com.xlythe.view.camera.edit.Overlay;
import com.xlythe.view.camera.edit.OverlayView;
import com.xlythe.view.camera.edit.PictureEditor;
import com.xlythe.view.camera.edit.PictureEdits;
import com.xlythe.view.camera.gl.ColorMatrix;
import com.xlythe.view.camera.viewer.MediaListener;
import com.xlythe.view.camera.viewer.MediaView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * EditorFragment edits a picture with the library's pieces: a DrawingView to draw on it with a
 * pen or a finger and a BrushPanelView to pick the brush, then PictureEditor to turn, flip,
 * straighten, crop and filter it with the drawing on it, and write it in any format the device
 * can, keeping its metadata. The result is shown in a MediaView, with what MediaTags reads.
 */
public class EditorFragment extends Fragment {
    /** The argument naming the picture to edit. */
    static final String ARG_URI = "uri";

    /** The saved instance state's key for the edited picture. */
    private static final String STATE_EDITED = "edited";

    /** The saved instance state's key for the quarter turn. */
    private static final String STATE_TURN = "turn";

    /** How far the straighten choice levels the picture, in degrees. */
    private static final float STRAIGHTEN_DEGREES = 8f;

    /** What the crop choice keeps: the middle of the picture. */
    private static final RectF CROP = new RectF(0.2f, 0.2f, 0.8f, 0.8f);

    /** The pixels the small choice limits the result to. */
    private static final long SMALL_PIXELS = 2_000_000L;

    /** The quality lossy formats are written at. */
    private static final int QUALITY = 90;

    /** The longest side the drawing is drawn at to go over the picture. */
    private static final int MAX_OVERLAY_SIDE = 2048;

    /** The longest side the picture is shown at while it is edited. */
    private static final int MAX_PREVIEW_SIDE = 1600;

    /** Picks a picture from the gallery or the files app. */
    private final ActivityResultLauncher<String[]> mPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    open(uri);
                }
            });

    /** Reads sizes, metadata and which formats can be written, off the main thread. */
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    /** Posts what the executor finds back. */
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /** The picture to edit, or null before one is picked. */
    @Nullable private Uri mUri;
    /** The last edited picture, or null. */
    @Nullable private File mEdited;
    /** The picture's width as seen, once read. */
    private int mUprightWidth;
    /** The picture's height as seen, once read. */
    private int mUprightHeight;
    /** The quarter turn to make, in degrees. */
    private int mTurn;
    /** The edit in progress, or null. */
    @Nullable private PictureEditor mEditor;

    @Nullable private MediaView mMedia;
    @Nullable private ImageView mPreview;
    @Nullable private DrawingView mDrawing;
    @Nullable private OverlayView mOverlays;
    @Nullable private View mDeleteZone;
    @Nullable private Button mUndo;
    @Nullable private Button mRedo;
    @Nullable private BrushPanelView mBrushes;
    @Nullable private CheckBox mErase;
    @Nullable private CheckBox mShowResult;
    @Nullable private TextView mInfo;
    @Nullable private TextView mMessage;
    @Nullable private ProgressBar mProgress;
    @Nullable private Button mTurnButton;
    @Nullable private Button mSave;
    @Nullable private CheckBox mFlipHorizontal;
    @Nullable private CheckBox mFlipVertical;
    @Nullable private CheckBox mStraighten;
    @Nullable private CheckBox mCrop;
    @Nullable private CheckBox mFilter;
    @Nullable private CheckBox mMarkFiltered;
    @Nullable private CheckBox mSmall;
    @Nullable private RadioGroup mFormat;
    @Nullable private RadioGroup mClip;

    /** The arguments that open this on a picture. */
    @NonNull
    static Bundle arguments(@NonNull Uri uri) {
        Bundle arguments = new Bundle();
        arguments.putParcelable(ARG_URI, uri);
        return arguments;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_editor, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mMedia = view.findViewById(R.id.editor_media);
        mPreview = view.findViewById(R.id.editor_preview);
        mDrawing = view.findViewById(R.id.editor_drawing);
        mOverlays = view.findViewById(R.id.editor_overlays);
        mDeleteZone = view.findViewById(R.id.editor_delete_zone);
        mUndo = view.findViewById(R.id.editor_undo);
        mRedo = view.findViewById(R.id.editor_redo);
        mBrushes = view.findViewById(R.id.editor_brushes);
        mErase = view.findViewById(R.id.editor_erase);
        mShowResult = view.findViewById(R.id.editor_show_result);
        mInfo = view.findViewById(R.id.editor_info);
        mMessage = view.findViewById(R.id.editor_message);
        mProgress = view.findViewById(R.id.editor_progress);
        mTurnButton = view.findViewById(R.id.editor_turn);
        mSave = view.findViewById(R.id.editor_save);
        mFlipHorizontal = view.findViewById(R.id.editor_flip_horizontal);
        mFlipVertical = view.findViewById(R.id.editor_flip_vertical);
        mStraighten = view.findViewById(R.id.editor_straighten);
        mCrop = view.findViewById(R.id.editor_crop);
        mFilter = view.findViewById(R.id.editor_filter);
        mMarkFiltered = view.findViewById(R.id.editor_mark_filtered);
        mSmall = view.findViewById(R.id.editor_small);
        mFormat = view.findViewById(R.id.editor_format);
        mClip = view.findViewById(R.id.editor_clip);

        mMedia.setMediaListener(new MediaListener() {
            @Override
            public void onMediaLoaded(int type) {
                setBusy(false, null);
            }

            @Override
            public void onMediaFailed() {
                setBusy(false, getString(R.string.viewer_failed));
            }
        });
        view.findViewById(R.id.editor_open).setOnClickListener(
                v -> mPicker.launch(new String[] {"image/*"}));
        mTurnButton.setOnClickListener(v -> {
            mTurn = (mTurn + 90) % 360;
            updateTurn();
        });
        mSave.setOnClickListener(v -> save());
        mBrushes.setDrawingView(mDrawing);
        mErase.setOnCheckedChangeListener((box, checked) -> mDrawing.setTool(
                checked ? DrawingView.TOOL_ERASE : DrawingView.TOOL_DRAW));
        // One undo across the drawing and the stickers, in the order things were done.
        EditHistory history = new EditHistory();
        history.attach(mDrawing);
        history.attach(mOverlays);
        history.setOnHistoryChangedListener(() -> {
            if (mUndo != null && mRedo != null) {
                mUndo.setEnabled(history.canUndo());
                mRedo.setEnabled(history.canRedo());
            }
        });
        mUndo.setOnClickListener(v -> history.undo());
        mRedo.setOnClickListener(v -> history.redo());
        setUpOverlays(view);
        mShowResult.setOnCheckedChangeListener((box, checked) -> showResult(checked));

        // HEIF and AVIF need encoders not every device has.
        View heif = view.findViewById(R.id.editor_format_heif);
        View avif = view.findViewById(R.id.editor_format_avif);
        heif.setEnabled(false);
        avif.setEnabled(false);
        mExecutor.execute(() -> {
            boolean writesHeif = PictureEditor.isOutputFormatSupported(PictureEditor.FORMAT_HEIF);
            boolean writesAvif = PictureEditor.isOutputFormatSupported(PictureEditor.FORMAT_AVIF);
            mHandler.post(() -> {
                heif.setEnabled(writesHeif);
                avif.setEnabled(writesAvif);
            });
        });

        Uri uri = getArguments() == null ? null
                : BundleCompat.getParcelable(getArguments(), ARG_URI, Uri.class);
        if (savedInstanceState != null) {
            Uri saved = BundleCompat.getParcelable(savedInstanceState, ARG_URI, Uri.class);
            if (saved != null) {
                uri = saved;
            }
            mTurn = savedInstanceState.getInt(STATE_TURN);
            String edited = savedInstanceState.getString(STATE_EDITED);
            mEdited = edited == null ? null : new File(edited);
        }
        updateTurn();
        if (uri != null) {
            open(uri);
        }
        if (mEdited != null && mEdited.isFile()) {
            showEdited(mEdited);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable(ARG_URI, mUri);
        outState.putInt(STATE_TURN, mTurn);
        outState.putString(STATE_EDITED, mEdited == null ? null : mEdited.getPath());
    }

    @Override
    public void onDestroyView() {
        if (mEditor != null && mEditor.isRunning()) {
            mEditor.cancel();
        }
        mEditor = null;
        mMedia = null;
        mPreview = null;
        mDrawing = null;
        mOverlays = null;
        mDeleteZone = null;
        mUndo = null;
        mRedo = null;
        mBrushes = null;
        mErase = null;
        mShowResult = null;
        mInfo = null;
        mMessage = null;
        mProgress = null;
        mTurnButton = null;
        mSave = null;
        mFlipHorizontal = null;
        mFlipVertical = null;
        mStraighten = null;
        mCrop = null;
        mFilter = null;
        mMarkFiltered = null;
        mSmall = null;
        mFormat = null;
        mClip = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        mExecutor.shutdownNow();
        super.onDestroy();
    }

    /** Opens a picture to edit, and reads its size as seen for the mark's overlay. */
    @MainThread
    private void open(@NonNull Uri uri) {
        mUri = uri;
        mUprightWidth = 0;
        mUprightHeight = 0;
        showResult(false);
        if (mShowResult != null) {
            mShowResult.setChecked(false);
            mShowResult.setEnabled(false);
        }
        if (mSave != null) {
            mSave.setEnabled(true);
        }
        setBusy(true, null);
        Context context = requireContext().getApplicationContext();
        mExecutor.execute(() -> {
            int[] size = uprightSize(context, uri);
            String summary = describe(context, uri);
            Bitmap preview = preview(context, uri);
            mHandler.post(() -> {
                if (uri.equals(mUri)) {
                    mUprightWidth = size[0];
                    mUprightHeight = size[1];
                    setInfo(summary);
                    setBusy(false, preview == null ? getString(R.string.viewer_failed) : null);
                    if (mPreview != null) {
                        mPreview.setImageBitmap(preview);
                    }
                    if (mDrawing != null) {
                        // The drawing is kept in the picture's own pixels.
                        mDrawing.setDrawingSize(size[0], size[1]);
                    }
                    if (mOverlays != null) {
                        // So are the stickers.
                        mOverlays.setCanvasSize(size[0], size[1]);
                        mOverlays.setOverlays(Collections.<Overlay>emptyList());
                    }
                }
            });
        });
    }

    /** Edits the picture as the choices say, and shows the result. */
    @MainThread
    private void save() {
        if (mUri == null) {
            return;
        }
        if (mEditor != null && mEditor.isRunning()) {
            mEditor.cancel();
        }
        int format = formatOf(mFormat == null ? View.NO_ID : mFormat.getCheckedRadioButtonId());
        File directory = requireContext().getExternalFilesDir(null);
        File output = new File(directory == null ? requireContext().getFilesDir() : directory,
                "edited-" + System.currentTimeMillis() + extensionOf(format));
        Drawing drawing = mDrawing == null ? null : mDrawing.getDrawing();
        PictureEdits edits = edits();
        if (mOverlays != null && (!mOverlays.getOverlays().isEmpty()
                || (drawing != null && !drawing.isEmpty()))) {
            // The ink and the stickers over it, as one picture to lay over the one edited.
            int[] size = overlaySize(mUprightWidth, mUprightHeight);
            edits = new PictureEdits.Builder(edits)
                    .setOverlay(mOverlays.toBitmap(size[0], size[1], 0, drawing)).build();
        }
        setBusy(true, null);
        startEditor(mUri, output, format, edits);
    }

    /** Writes the edited picture. */
    @MainThread
    private void startEditor(@NonNull Uri source, @NonNull File output, int format,
            @NonNull PictureEdits edits) {
        PictureEditor editor = new PictureEditor(requireContext(), source, output, edits);
        editor.setOutputFormat(format);
        editor.setQuality(QUALITY);
        editor.setMaxOutputPixels(mSmall != null && mSmall.isChecked() ? SMALL_PIXELS : 0);
        editor.setMotionPhotoPolicy(
                clipPolicyOf(mClip == null ? View.NO_ID : mClip.getCheckedRadioButtonId()));
        editor.setOnPictureEditedListener(new PictureEditor.OnPictureEditedListener() {
            @Override
            public void onPictureEdited(@NonNull File file) {
                Log.d(TAG, "Edited into " + file);
                File previous = mEdited;
                mEdited = file;
                showEdited(file);
                if (previous != null && !previous.equals(file) && !previous.delete()) {
                    Log.w(TAG, "Could not delete " + previous);
                }
            }

            @Override
            public void onFailure() {
                setBusy(false, getString(R.string.editor_failed));
            }
        });
        mEditor = editor;
        editor.start();
    }

    /** The edits the choices make. */
    @MainThread
    @NonNull
    private PictureEdits edits() {
        return new PictureEdits.Builder()
                .setRotationDegrees(mTurn)
                .setFlippedHorizontally(isChecked(mFlipHorizontal))
                .setFlippedVertically(isChecked(mFlipVertical))
                .setStraightenDegrees(isChecked(mStraighten) ? STRAIGHTEN_DEGREES : 0)
                .setCrop(isChecked(mCrop) ? CROP : null)
                .setColorMatrix(isChecked(mFilter) ? ColorMatrix.grayscale() : null)
                .setOverlayFiltered(isChecked(mMarkFiltered))
                .build();
    }

    /** The size to draw what goes over the picture at: its own, no larger than it needs. */
    @NonNull
    private static int[] overlaySize(int width, int height) {
        float scale = Math.min(1f, MAX_OVERLAY_SIDE / (float) Math.max(1, Math.max(width,
                height)));
        return new int[] {Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale))};
    }

    /** Wires the sticker row, text and the delete zone to the overlay view. */
    @MainThread
    private void setUpOverlays(@NonNull View root) {
        OverlayView overlays = mOverlays;
        if (overlays == null) {
            return;
        }
        setUpSticker(root.findViewById(R.id.editor_sticker_heart), rawUri(R.raw.sticker_heart),
                "image/png");
        setUpSticker(root.findViewById(R.id.editor_sticker_star), rawUri(R.raw.sticker_star),
                "image/gif");
        overlays.setDropTargetEnabled(true);
        overlays.setDeleteZone(mDeleteZone);
        overlays.setOnOverlayChangedListener(new OverlayView.OnOverlayChangedListener() {
            @Override
            public void onOverlaysChanged(@NonNull List<Overlay> items) {}

            @Override
            public void onSelectionChanged(@Nullable Overlay overlay) {
                if (mDeleteZone != null) {
                    mDeleteZone.setVisibility(overlay != null ? View.VISIBLE : View.GONE);
                }
            }
        });
        root.findViewById(R.id.editor_text).setOnClickListener(v -> editText());
    }

    /** A sticker in the row: a tap adds it, and a long press drags it onto the picture. */
    @MainThread
    private void setUpSticker(@NonNull ImageView view, @NonNull Uri uri,
            @NonNull String mimeType) {
        view.setImageURI(uri);
        view.setOnClickListener(v -> {
            if (mOverlays != null) {
                mOverlays.addImage(uri, mimeType, null);
            }
        });
        view.setOnLongClickListener(v -> OverlayView.startDrag(v, uri, mimeType));
    }

    /** Asks for text to add, or to put in place of the chosen text. */
    @MainThread
    private void editText() {
        OverlayView overlays = mOverlays;
        if (overlays == null) {
            return;
        }
        Overlay chosen = null;
        for (Overlay overlay : overlays.getOverlays()) {
            if (overlay.getId().equals(overlays.getSelectedId())
                    && overlay.getKind() == Overlay.KIND_TEXT) {
                chosen = overlay;
            }
        }
        Overlay editing = chosen;
        EditText input = new EditText(requireContext());
        input.setHint(R.string.editor_text_hint);
        input.setSingleLine(true);
        if (editing != null && editing.getText() != null) {
            input.setText(editing.getText());
        }
        new AlertDialog.Builder(requireContext())
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String text = input.getText().toString().trim();
                    if (text.isEmpty() || mOverlays == null) {
                        return;
                    }
                    if (editing != null) {
                        mOverlays.updateOverlay(new Overlay.Builder(editing).setText(text)
                                .build());
                    } else {
                        mOverlays.addText(text, Color.WHITE);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** A resource of this app's as a URI the overlay view can read. */
    @NonNull
    private Uri rawUri(int id) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority(requireContext().getPackageName())
                .appendPath(getResources().getResourceTypeName(id))
                .appendPath(getResources().getResourceEntryName(id))
                .build();
    }

    /** The picture, upright, small enough to show while it is edited, or null. */
    @WorkerThread
    @Nullable
    private static Bitmap preview(@NonNull Context context, @NonNull Uri uri) {
        File file = new File(context.getCacheDir(), "editor-preview.png");
        PictureEditor editor = new PictureEditor(context, uri, file,
                new PictureEdits.Builder().build());
        CountDownLatch done = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            editor.setOutputFormat(PictureEditor.FORMAT_PNG);
            editor.setMaxOutputPixels((long) MAX_PREVIEW_SIDE * MAX_PREVIEW_SIDE);
            editor.setOnPictureEditedListener(new PictureEditor.OnPictureEditedListener() {
                @Override
                public void onPictureEdited(@NonNull File edited) {
                    done.countDown();
                }

                @Override
                public void onFailure() {
                    done.countDown();
                }
            });
            editor.start();
        });
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return BitmapFactory.decodeFile(file.getPath());
    }

    @MainThread
    private void showEdited(@NonNull File file) {
        if (mMedia != null) {
            mMedia.setFile(file);
        }
        if (mShowResult != null) {
            mShowResult.setEnabled(true);
            mShowResult.setChecked(true);
        }
        showResult(true);
        Context context = requireContext().getApplicationContext();
        mExecutor.execute(() -> {
            String summary = describe(context, Uri.fromFile(file));
            mHandler.post(() -> {
                if (file.equals(mEdited)) {
                    setInfo(summary);
                }
            });
        });
    }

    /** What a picture is: its format, size and metadata, as a line for the screen. */
    @WorkerThread
    @NonNull
    private String describe(@NonNull Context context, @NonNull Uri uri) {
        try {
            MediaTags tags = MediaTags.open(context, uri);
            int[] size = uprightSize(context, uri);
            return getString(R.string.editor_info, formatName(tags.getFormat()), size[0], size[1],
                    ViewerFragment.summary(context.getResources(), tags));
        } catch (IOException | IllegalStateException e) {
            Log.w(TAG, "Could not read " + uri, e);
            return "";
        }
    }

    /** A picture's width and height as seen, or zeros if it cannot be read. */
    @WorkerThread
    @NonNull
    private static int[] uprightSize(@NonNull Context context, @NonNull Uri uri) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(input, null, bounds);
            int rotation = MediaTags.open(context, uri).getRotation();
            return rotation % 180 == 0 ? new int[] {bounds.outWidth, bounds.outHeight}
                    : new int[] {bounds.outHeight, bounds.outWidth};
        } catch (IOException e) {
            Log.w(TAG, "Could not read the size of " + uri, e);
            return new int[] {0, 0};
        }
    }

    /** Shows the saved picture, or the one being edited with its drawing. */
    @MainThread
    private void showResult(boolean result) {
        if (mMedia != null) {
            mMedia.setVisibility(result ? View.VISIBLE : View.GONE);
        }
        if (mDrawing != null) {
            mDrawing.setVisibility(result ? View.GONE : View.VISIBLE);
        }
        if (mOverlays != null) {
            mOverlays.setVisibility(result ? View.GONE : View.VISIBLE);
        }
        if (mPreview != null) {
            mPreview.setVisibility(result ? View.GONE : View.VISIBLE);
        }
    }

    @MainThread
    private void updateTurn() {
        if (mTurnButton != null) {
            mTurnButton.setText(getString(R.string.editor_turn_degrees, mTurn));
        }
    }

    @MainThread
    private void setInfo(@NonNull String text) {
        if (mInfo != null) {
            mInfo.setText(text);
            mInfo.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    @MainThread
    private void setBusy(boolean busy, @Nullable String message) {
        if (mProgress != null) {
            mProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        }
        if (mMessage != null) {
            mMessage.setText(message);
            mMessage.setVisibility(message == null ? View.GONE : View.VISIBLE);
        }
    }

    private static boolean isChecked(@Nullable CheckBox box) {
        return box != null && box.isChecked();
    }

    /** The format a choice stands for. */
    @VisibleForTesting
    static int formatOf(int id) {
        if (id == R.id.editor_format_jpeg) {
            return PictureEditor.FORMAT_JPEG;
        } else if (id == R.id.editor_format_png) {
            return PictureEditor.FORMAT_PNG;
        } else if (id == R.id.editor_format_webp) {
            return PictureEditor.FORMAT_WEBP;
        } else if (id == R.id.editor_format_heif) {
            return PictureEditor.FORMAT_HEIF;
        } else if (id == R.id.editor_format_avif) {
            return PictureEditor.FORMAT_AVIF;
        }
        return PictureEditor.FORMAT_SAME;
    }

    /** What a choice does with a motion photo's clip. */
    @VisibleForTesting
    static int clipPolicyOf(int id) {
        if (id == R.id.editor_clip_keep) {
            return PictureEditor.MOTION_PHOTO_KEEP;
        } else if (id == R.id.editor_clip_drop) {
            return PictureEditor.MOTION_PHOTO_DROP;
        }
        return PictureEditor.MOTION_PHOTO_AUTO;
    }

    /** The file extension for a format, or none where it is not yet known. */
    @VisibleForTesting
    @NonNull
    static String extensionOf(int format) {
        switch (format) {
            case PictureEditor.FORMAT_JPEG:
                return ".jpg";
            case PictureEditor.FORMAT_PNG:
                return ".png";
            case PictureEditor.FORMAT_WEBP:
                return ".webp";
            case PictureEditor.FORMAT_HEIF:
                return ".heic";
            case PictureEditor.FORMAT_AVIF:
                return ".avif";
            default:
                return "";
        }
    }

    /** What a format is called. */
    @VisibleForTesting
    @NonNull
    static String formatName(int format) {
        switch (format) {
            case MediaTags.FORMAT_JPEG:
                return "JPEG";
            case MediaTags.FORMAT_PNG:
                return "PNG";
            case MediaTags.FORMAT_WEBP:
                return "WebP";
            case MediaTags.FORMAT_HEIF:
                return "HEIF";
            case MediaTags.FORMAT_AVIF:
                return "AVIF";
            case MediaTags.FORMAT_DNG:
                return "DNG";
            case MediaTags.FORMAT_GIF:
                return "GIF";
            default:
                return "?";
        }
    }
}
