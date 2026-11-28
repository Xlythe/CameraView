package com.xlythe.sample.camera;

import android.content.res.Resources;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.os.BundleCompat;
import androidx.fragment.app.Fragment;

import com.xlythe.view.camera.MediaTags;
import com.xlythe.view.camera.viewer.MediaListener;
import com.xlythe.view.camera.viewer.MediaView;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * ViewerFragment shows any picture, video or sound file in the library's MediaView, which works
 * out what the file is: a picture or DNG to zoom into, a panorama, a photo sphere, a video, a
 * motion photo, a 360° video, a recording or an animated picture.
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

    /** The file being shown, or null before one is picked. */
    @Nullable
    private Uri mUri;

    /** Shows the file. */
    @Nullable
    private MediaView mMedia;

    /** Turns the phone's rotation on and off for a sphere. */
    @Nullable
    private CheckBox mGyroscope;

    /** Spins until the file shows. */
    @Nullable
    private ProgressBar mProgress;

    /** Says what to do, or that the file cannot be shown. */
    @Nullable
    private TextView mMessage;

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
        mGyroscope = view.findViewById(R.id.viewer_gyroscope);
        mProgress = view.findViewById(R.id.viewer_progress);
        mMessage = view.findViewById(R.id.viewer_message);

        mMedia.setMediaListener(new MediaListener() {
            @Override
            public void onMediaLoaded(int type) {
                setBusy(false, null);
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
                // MediaView shows that it failed itself.
                setBusy(false, null);
            }
        });

        view.findViewById(R.id.viewer_open).setOnClickListener(
                v -> mPicker.launch(new String[] {"image/*", "video/*", "audio/*"}));
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
        mGyroscope = null;
        mProgress = null;
        mMessage = null;
        super.onDestroyView();
    }

    /** Shows a file, as whatever it is found to be. */
    @MainThread
    private void show(@NonNull Uri uri) {
        mUri = uri;
        if (mMedia != null) {
            mMedia.setUri(uri);
        }
        setBusy(true, null);
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
    private void setBusy(boolean busy, @Nullable String message) {
        if (mProgress != null) {
            mProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        }
        if (mMessage != null) {
            mMessage.setText(message);
            mMessage.setVisibility(message == null ? View.GONE : View.VISIBLE);
        }
    }
}
