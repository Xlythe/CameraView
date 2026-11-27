package com.xlythe.sample.camera;

import static com.xlythe.sample.camera.MainActivity.TAG;

import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
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
import androidx.core.os.BundleCompat;
import androidx.fragment.app.Fragment;

import com.xlythe.view.camera.viewer.ImageLoadListener;
import com.xlythe.view.camera.viewer.LargeImageView;
import com.xlythe.view.camera.viewer.PanoramaView;
import com.xlythe.view.camera.viewer.PhotosphereView;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * ViewerFragment shows a picture the camera took in whichever of the library's viewers suits it:
 * a DNG or any other picture zoomed down to its own pixels, a panorama panned along, or a photo
 * sphere looked around from the inside, optionally by turning the phone.
 *
 * <p>MainFragment opens it on the picture it just filed; the menu opens it empty, to pick a
 * picture from the gallery.</p>
 */
public class ViewerFragment extends Fragment {
    /** The argument naming the picture to show. */
    static final String ARG_URI = "uri";

    /** What a DNG is called when it is filed in the gallery. */
    private static final String DNG_TYPE = "image/x-adobe-dng";

    /** How a picture is shown. */
    enum Mode {
        /** Flat, to zoom into. */
        PICTURE,
        /** As a strip to pan along. */
        PANORAMA,
        /** From the inside of a sphere. */
        PHOTOSPHERE
    }

    /** Picks a picture from the gallery or the files app. */
    private final ActivityResultLauncher<String[]> mPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    show(uri);
                }
            });

    /** The picture being shown, or null before one is picked. */
    @Nullable
    private Uri mUri;

    /** The flat viewer, held while the view exists. */
    @Nullable
    private LargeImageView mPicture;

    /** The panorama viewer. */
    @Nullable
    private PanoramaView mPanorama;

    /** The photo sphere viewer. */
    @Nullable
    private PhotosphereView mPhotosphere;

    /** Picks how the picture is shown. */
    @Nullable
    private RadioGroup mModes;

    /** Turns the phone's rotation on and off for the photo sphere. */
    @Nullable
    private CheckBox mGyroscope;

    /** Spins until the picture shows. */
    @Nullable
    private ProgressBar mProgress;

    /** Says what to do, or that the picture cannot be shown. */
    @Nullable
    private TextView mMessage;

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
        return inflater.inflate(R.layout.fragment_viewer, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mPicture = view.findViewById(R.id.viewer_picture);
        mPanorama = view.findViewById(R.id.viewer_panorama);
        mPhotosphere = view.findViewById(R.id.viewer_photosphere);
        mModes = view.findViewById(R.id.viewer_mode);
        mGyroscope = view.findViewById(R.id.viewer_gyroscope);
        mProgress = view.findViewById(R.id.viewer_progress);
        mMessage = view.findViewById(R.id.viewer_message);

        ImageLoadListener listener = new ImageLoadListener() {
            @Override
            public void onImageLoaded() {
                setBusy(false, null);
            }

            @Override
            public void onImageFailed() {
                setBusy(false, getString(R.string.viewer_failed));
            }
        };
        mPicture.setImageLoadListener(listener);
        mPanorama.setImageLoadListener(listener);
        mPhotosphere.setImageLoadListener(listener);

        view.findViewById(R.id.viewer_open).setOnClickListener(
                v -> mPicker.launch(new String[] {"image/*"}));
        mModes.setOnCheckedChangeListener((group, id) -> display(modeOf(id)));
        mGyroscope.setOnCheckedChangeListener((button, checked) -> {
            if (mPhotosphere != null) {
                mPhotosphere.setGyroscopeEnabled(checked);
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
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable(ARG_URI, mUri);
    }

    @Override
    public void onDestroyView() {
        mPicture = null;
        mPanorama = null;
        mPhotosphere = null;
        mModes = null;
        mGyroscope = null;
        mProgress = null;
        mMessage = null;
        super.onDestroyView();
    }

    /** Shows a picture in the viewer that suits it. */
    @MainThread
    private void show(@NonNull Uri uri) {
        mUri = uri;
        Mode mode = guess(uri);
        int id = idOf(mode);
        if (mModes != null && mModes.getCheckedRadioButtonId() != id) {
            // Displays it, through the listener.
            mModes.check(id);
        } else {
            display(mode);
        }
    }

    /** Puts the picture in one viewer, and empties the others. */
    @MainThread
    private void display(@NonNull Mode mode) {
        if (mPicture == null || mPanorama == null || mPhotosphere == null || mGyroscope == null) {
            return;
        }
        Uri uri = mUri;
        mPicture.setUri(mode == Mode.PICTURE ? uri : null);
        mPanorama.setUri(mode == Mode.PANORAMA ? uri : null);
        mPhotosphere.setUri(mode == Mode.PHOTOSPHERE ? uri : null);
        mPicture.setVisibility(mode == Mode.PICTURE ? View.VISIBLE : View.GONE);
        mPanorama.setVisibility(mode == Mode.PANORAMA ? View.VISIBLE : View.GONE);
        mPhotosphere.setVisibility(mode == Mode.PHOTOSPHERE ? View.VISIBLE : View.GONE);
        mGyroscope.setVisibility(mode == Mode.PHOTOSPHERE && mPhotosphere.isGyroscopeAvailable()
                ? View.VISIBLE : View.GONE);
        if (uri != null) {
            setBusy(true, null);
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

    /** Which viewer suits a picture, from its type and shape. */
    @NonNull
    private Mode guess(@NonNull Uri uri) {
        String type = requireContext().getContentResolver().getType(uri);
        if (type == null && uri.getPath() != null
                && uri.getPath().toLowerCase(Locale.ROOT).endsWith(".dng")) {
            type = DNG_TYPE;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = requireContext().getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(input, null, bounds);
        } catch (IOException | SecurityException e) {
            Log.w(TAG, "Unable to read the size of " + uri, e);
        }
        return guess(type, bounds.outWidth, bounds.outHeight);
    }

    /**
     * Which viewer suits a picture: a photo sphere is twice as wide as it is tall, a panorama
     * wider still, and a DNG or anything else is a picture to zoom into. The camera's own
     * panoramas and photo spheres come out that shape; one from elsewhere can be switched.
     */
    @VisibleForTesting
    @NonNull
    static Mode guess(@Nullable String type, int width, int height) {
        if (DNG_TYPE.equals(type) || width <= 0 || height <= 0) {
            return Mode.PICTURE;
        }
        float aspect = (float) width / height;
        if (Math.abs(aspect - 2f) < 0.02f) {
            return Mode.PHOTOSPHERE;
        }
        return aspect > 2.5f ? Mode.PANORAMA : Mode.PICTURE;
    }

    private static int idOf(@NonNull Mode mode) {
        switch (mode) {
            case PANORAMA:
                return R.id.viewer_mode_panorama;
            case PHOTOSPHERE:
                return R.id.viewer_mode_photosphere;
            default:
                return R.id.viewer_mode_picture;
        }
    }

    @NonNull
    private static Mode modeOf(int id) {
        if (id == R.id.viewer_mode_panorama) {
            return Mode.PANORAMA;
        }
        if (id == R.id.viewer_mode_photosphere) {
            return Mode.PHOTOSPHERE;
        }
        return Mode.PICTURE;
    }
}
