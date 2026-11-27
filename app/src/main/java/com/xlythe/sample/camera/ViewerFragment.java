package com.xlythe.sample.camera;

import static com.xlythe.sample.camera.MainActivity.TAG;

import android.content.Context;
import android.content.res.Resources;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import com.xlythe.view.camera.viewer.MediaListener;
import com.xlythe.view.camera.viewer.MediaView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ViewerFragment shows any picture, video or sound file in the library's MediaView, which works
 * out what the file is: a picture or DNG to zoom into, a panorama, a photo sphere, a video, a
 * motion photo, a 360° video, a recording or an animated picture. The choices along the bottom show it as something
 * else instead, and the line at the top says what MediaTags reads from it.
 *
 * <p>MainFragment opens it on the picture it just filed; the menu opens it empty, to pick a
 * file from the gallery.</p>
 */
public class ViewerFragment extends Fragment {
    /** The argument naming the file to show. */
    static final String ARG_URI = "uri";

    /** Picks a file from the gallery or the files app. */
    private final ActivityResultLauncher<String[]> mPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    show(uri);
                }
            });

    /** Reads the file's metadata off the main thread. */
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    /** Posts the metadata back. */
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /** The file being shown, or null before one is picked. */
    @Nullable
    private Uri mUri;

    /** Shows the file. */
    @Nullable
    private MediaView mMedia;

    /** Picks what the file is shown as. */
    @Nullable
    private RadioGroup mTypes;

    /** Turns the phone's rotation on and off for a sphere. */
    @Nullable
    private CheckBox mGyroscope;

    /** Spins until the file shows. */
    @Nullable
    private ProgressBar mProgress;

    /** Says what to do, or that the file cannot be shown. */
    @Nullable
    private TextView mMessage;

    /** Says what the file is and what its metadata says. */
    @Nullable
    private TextView mInfo;

    /** What the metadata says, once read. */
    @Nullable
    private String mSummary;

    /** The arguments that open this on a file. */
    @NonNull
    static Bundle arguments(@NonNull Uri uri) {
        Bundle arguments = new Bundle();
        arguments.putParcelable(ARG_URI, uri);
        return arguments;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_viewer, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mMedia = view.findViewById(R.id.viewer_media);
        mTypes = view.findViewById(R.id.viewer_type);
        mGyroscope = view.findViewById(R.id.viewer_gyroscope);
        mProgress = view.findViewById(R.id.viewer_progress);
        mMessage = view.findViewById(R.id.viewer_message);
        mInfo = view.findViewById(R.id.viewer_info);

        mMedia.setMediaListener(new MediaListener() {
            @Override
            public void onMediaLoaded(int type) {
                setBusy(false, null);
                updateInfo();
                if (mGyroscope != null) {
                    mGyroscope.setVisibility(type == MediaView.PHOTOSPHERE
                            || type == MediaView.SPHERICAL_VIDEO ? View.VISIBLE : View.GONE);
                }
                // Plays videos and recordings as soon as they are ready; a tap pauses them.
                if (mMedia != null && (type == MediaView.VIDEO
                        || type == MediaView.SPHERICAL_VIDEO || type == MediaView.AUDIO)) {
                    mMedia.play();
                }
            }

            @Override
            public void onMediaFailed() {
                setBusy(false, getString(R.string.viewer_failed));
            }
        });

        view.findViewById(R.id.viewer_open).setOnClickListener(
                v -> mPicker.launch(new String[] {"image/*", "video/*", "audio/*"}));
        mTypes.setOnCheckedChangeListener((group, id) -> {
            if (mMedia != null && mUri != null) {
                setBusy(true, null);
                mMedia.setMediaType(typeOf(id));
            }
        });
        mGyroscope.setOnCheckedChangeListener((button, checked) -> {
            if (mMedia != null) {
                mMedia.setGyroscopeEnabled(checked);
            }
        });

        Uri uri = getArguments() == null ? null
                : BundleCompat.getParcelable(getArguments(), ARG_URI, Uri.class);
        if (savedInstanceState != null) {
            Uri saved = BundleCompat.getParcelable(savedInstanceState, ARG_URI, Uri.class);
            if (saved != null) {
                uri = saved;
            }
        }
        if (uri != null) {
            show(uri);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mMedia != null && mMedia.isPlaying()) {
            mMedia.pause();
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable(ARG_URI, mUri);
    }

    @Override
    public void onDestroyView() {
        mMedia = null;
        mTypes = null;
        mGyroscope = null;
        mProgress = null;
        mMessage = null;
        mInfo = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        mExecutor.shutdownNow();
        super.onDestroy();
    }

    /** Shows a file, as whatever it is found to be. */
    @MainThread
    private void show(@NonNull Uri uri) {
        mUri = uri;
        mSummary = null;
        if (mTypes != null) {
            mTypes.check(R.id.viewer_type_detect);
        }
        if (mMedia != null) {
            mMedia.setMediaType(MediaView.UNKNOWN);
            mMedia.setUri(uri);
        }
        setBusy(true, null);
        updateInfo();
        Context context = requireContext().getApplicationContext();
        Resources resources = getResources();
        mExecutor.execute(() -> {
            String summary = read(context, resources, uri);
            mHandler.post(() -> {
                if (uri.equals(mUri)) {
                    mSummary = summary;
                    updateInfo();
                }
            });
        });
    }

    /** What a file's metadata says, as a line for the screen. */
    @WorkerThread
    @NonNull
    private static String read(@NonNull Context context, @NonNull Resources resources,
            @NonNull Uri uri) {
        try {
            return summary(resources, MediaTags.open(context, uri));
        } catch (IOException e) {
            Log.w(TAG, "Unable to read the metadata of " + uri, e);
            return resources.getString(R.string.viewer_no_metadata);
        }
    }

    /** What metadata says that is worth showing: when, where, what, and how it is special. */
    @VisibleForTesting
    @NonNull
    static String summary(@NonNull Resources resources, @NonNull MediaTags tags) {
        List<String> parts = new ArrayList<>();
        if (tags.getTitle() != null) {
            parts.add(tags.getTitle());
        }
        if (tags.getMake() != null || tags.getModel() != null) {
            parts.add(((tags.getMake() == null ? "" : tags.getMake() + " ")
                    + (tags.getModel() == null ? "" : tags.getModel())).trim());
        }
        if (tags.getCaptureTime() != -1) {
            parts.add(DateFormat.format("yyyy-MM-dd HH:mm", new Date(tags.getCaptureTime()))
                    .toString());
        }
        Location location = tags.getLocation();
        if (location != null) {
            parts.add(resources.getString(R.string.viewer_location, location.getLatitude(),
                    location.getLongitude()));
        }
        if (tags.getGainMap() != MediaTags.GAIN_MAP_NONE
                || tags.getVideoTransfer() == MediaTags.TRANSFER_HLG
                || tags.getVideoTransfer() == MediaTags.TRANSFER_PQ) {
            parts.add(resources.getString(R.string.viewer_hdr));
        }
        if (tags.getMotionPhoto() != null) {
            parts.add(resources.getString(R.string.viewer_motion_photo));
        }
        if (tags.isAnimated()) {
            parts.add(resources.getString(R.string.viewer_animated));
        }
        if (parts.isEmpty()) {
            return resources.getString(R.string.viewer_no_metadata);
        }
        return TextUtils.join(" · ", parts);
    }

    @MainThread
    private void updateInfo() {
        if (mInfo == null || mMedia == null) {
            return;
        }
        if (mUri == null) {
            mInfo.setVisibility(View.GONE);
            return;
        }
        String type = getString(labelOf(mMedia.getMediaType()));
        mInfo.setText(mSummary == null ? type
                : getString(R.string.viewer_info, type, mSummary));
        mInfo.setVisibility(View.VISIBLE);
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

    /** The media type a choice stands for. */
    @VisibleForTesting
    static int typeOf(int id) {
        if (id == R.id.viewer_type_picture) {
            return MediaView.PICTURE;
        } else if (id == R.id.viewer_type_panorama) {
            return MediaView.PANORAMA;
        } else if (id == R.id.viewer_type_photosphere) {
            return MediaView.PHOTOSPHERE;
        } else if (id == R.id.viewer_type_video) {
            return MediaView.VIDEO;
        } else if (id == R.id.viewer_type_motion) {
            return MediaView.MOTION_PHOTO;
        } else if (id == R.id.viewer_type_spherical) {
            return MediaView.SPHERICAL_VIDEO;
        } else if (id == R.id.viewer_type_audio) {
            return MediaView.AUDIO;
        } else if (id == R.id.viewer_type_animation) {
            return MediaView.ANIMATION;
        }
        return MediaView.UNKNOWN;
    }

    /** What a media type is called on screen. */
    @VisibleForTesting
    static int labelOf(int type) {
        switch (type) {
            case MediaView.PICTURE:
                return R.string.viewer_mode_picture;
            case MediaView.PANORAMA:
                return R.string.viewer_mode_panorama;
            case MediaView.PHOTOSPHERE:
                return R.string.viewer_mode_photosphere;
            case MediaView.VIDEO:
                return R.string.viewer_mode_video;
            case MediaView.MOTION_PHOTO:
                return R.string.viewer_mode_motion;
            case MediaView.SPHERICAL_VIDEO:
                return R.string.viewer_mode_spherical;
            case MediaView.AUDIO:
                return R.string.viewer_mode_audio;
            case MediaView.ANIMATION:
                return R.string.viewer_mode_animation;
            default:
                return R.string.viewer_mode_detect;
        }
    }
}
