package com.xlythe.sample.camera;

import static com.xlythe.sample.camera.MainActivity.TAG;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
import androidx.core.os.BundleCompat;
import androidx.fragment.app.Fragment;

import com.xlythe.view.camera.MediaTags;
import com.xlythe.view.camera.edit.PictureEditor;
import com.xlythe.view.camera.edit.PictureEdits;
import com.xlythe.view.camera.gl.ColorMatrix;
import com.xlythe.view.camera.viewer.MediaListener;
import com.xlythe.view.camera.viewer.MediaView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * EditorFragment edits a picture with the library's PictureEditor: turns, flips, straightens,
 * crops and filters it, marks it with an overlay, and writes it in any format the device can,
 * keeping its metadata. The result is shown in a MediaView, with what MediaTags reads from it.
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

    /** How wide the mark's overlay is drawn; it is stretched over the picture. */
    private static final int OVERLAY_WIDTH = 512;

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
    @Nullable private CheckBox mMark;
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
        mMark = view.findViewById(R.id.editor_mark);
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
        mMark = null;
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
        if (mMedia != null) {
            mMedia.setUri(uri);
        }
        if (mSave != null) {
            mSave.setEnabled(true);
        }
        setBusy(true, null);
        Context context = requireContext().getApplicationContext();
        mExecutor.execute(() -> {
            int[] size = uprightSize(context, uri);
            String summary = describe(context, uri);
            mHandler.post(() -> {
                if (uri.equals(mUri)) {
                    mUprightWidth = size[0];
                    mUprightHeight = size[1];
                    setInfo(summary);
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
        PictureEditor editor = new PictureEditor(requireContext(), mUri, output, edits());
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
        setBusy(true, null);
        editor.start();
    }

    /** The edits the choices make. */
    @MainThread
    @NonNull
    private PictureEdits edits() {
        PictureEdits.Builder builder = new PictureEdits.Builder()
                .setRotationDegrees(mTurn)
                .setFlippedHorizontally(isChecked(mFlipHorizontal))
                .setFlippedVertically(isChecked(mFlipVertical))
                .setStraightenDegrees(isChecked(mStraighten) ? STRAIGHTEN_DEGREES : 0)
                .setCrop(isChecked(mCrop) ? CROP : null)
                .setColorMatrix(isChecked(mFilter) ? ColorMatrix.grayscale() : null)
                .setOverlayFiltered(isChecked(mMarkFiltered));
        if (isChecked(mMark) && mUprightWidth > 0 && mUprightHeight > 0) {
            builder.setOverlay(mark(mUprightWidth, mUprightHeight));
        }
        return builder.build();
    }

    /**
     * A red ring and bar in the top left of the picture as seen, drawn smaller than the
     * picture with its proportions, since the editor stretches an overlay over it.
     */
    @VisibleForTesting
    @NonNull
    static Bitmap mark(int uprightWidth, int uprightHeight) {
        int width = OVERLAY_WIDTH;
        int height = Math.max(1, Math.round(OVERLAY_WIDTH * uprightHeight / (float) uprightWidth));
        Bitmap overlay = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(overlay);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.RED);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width / 40f);
        float radius = Math.min(width, height) / 6f;
        canvas.drawCircle(width / 4f, height / 4f, radius, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(width / 4f - radius, height / 4f + radius * 1.4f, width / 4f + radius,
                height / 4f + radius * 1.7f, paint);
        return overlay;
    }

    @MainThread
    private void showEdited(@NonNull File file) {
        if (mMedia != null) {
            mMedia.setFile(file);
        }
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
