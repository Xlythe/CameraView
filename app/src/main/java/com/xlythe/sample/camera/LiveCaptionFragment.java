package com.xlythe.sample.camera;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xlythe.fragment.camera.CameraFragment;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.caption.LiveCaption;
import com.xlythe.view.camera.caption.LiveCaptionListener;
import com.xlythe.view.camera.caption.LiveCaptions;

import java.util.Locale;

/**
 * LiveCaptionFragment captions what you say while the camera is running, and translates it.
 *
 * <p>The call case: the camera is streaming and the words underneath it arrive as they are
 * spoken, so the person at the other end can read along. The captioner has nothing to do with
 * the camera - it listens to the microphone - which is why it is its own object here rather than
 * something on the CameraView: a real call needs a second one for the audio coming the other
 * way.</p>
 *
 * <p>The line on screen is replaced, not appended to. The same line arrives several times - as
 * more of the sentence is heard, and again when its translation turns up - and appending instead
 * is what makes the first few words of every sentence double.</p>
 */
public class LiveCaptionFragment extends CameraFragment {
    /** What this demo translates into. English, since the sample is written in it. */
    private static final Locale TRANSLATE_TO = Locale.ENGLISH;

    private CameraView mCameraView;
    private TextView mCaption;
    private TextView mTranslation;
    private TextView mStatus;
    private Button mStartStop;

    @Nullable
    private LiveCaptions mLiveCaptions;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_live_caption, container, false);
        mCameraView = view.findViewById(R.id.camera);
        mCaption = view.findViewById(R.id.live_caption);
        mTranslation = view.findViewById(R.id.live_translation);
        mStatus = view.findViewById(R.id.live_status);
        mStartStop = view.findViewById(R.id.live_start_stop);
        mStartStop.setOnClickListener(ignored -> toggle());
        return view;
    }

    @Override
    public void onCameraOpened() {
        if (!LiveCaptions.isSupported(requireContext())) {
            mStatus.setText(R.string.live_status_unsupported);
            mStartStop.setEnabled(false);
            return;
        }
        mStartStop.setEnabled(true);
        mStatus.setText(R.string.live_status_ready);
    }

    @Override
    public void onCameraClosed() {
        super.onCameraClosed();
        stop();
    }

    @Override
    public void onDestroyView() {
        stop();
        super.onDestroyView();
    }

    private void toggle() {
        if (mLiveCaptions != null) {
            stop();
        } else {
            start();
        }
    }

    @SuppressLint("MissingPermission")
    private void start() {
        LiveCaptions live = new LiveCaptions(requireContext());
        mLiveCaptions = live;
        mCaption.setText("");
        mTranslation.setText("");
        mStatus.setText(R.string.live_status_listening);
        mStartStop.setText(R.string.live_stop);
        // The device's own language is what is being spoken here; a call would use whatever the
        // other person picked.
        live.start(Locale.getDefault(), TRANSLATE_TO, new LiveCaptionListener() {
            @Override
            public void onLiveCaption(@NonNull LiveCaption caption) {
                // Replaced, not appended: this is the same line arriving again with more of it.
                mCaption.setText(caption.getText());
                mTranslation.setText(caption.getTranslation() == null
                        ? "" : caption.getTranslation());
                mStatus.setText(caption.isFinal()
                        ? R.string.live_status_settled : R.string.live_status_hearing);
            }

            @Override
            public void onLiveCaptionFailed(@NonNull String reason) {
                mStatus.setText(reason);
                stop();
            }

            @Override
            public void onTranslationAvailable(boolean available) {
                if (available) {
                    return;
                }
                // A dead end unless somebody is told where to fix it: the service downloads
                // languages on demand and will not fetch one because an app asked.
                mStatus.setText(R.string.live_status_no_translation);
                offerTranslationSettings();
            }
        });
    }

    private void stop() {
        LiveCaptions live = mLiveCaptions;
        mLiveCaptions = null;
        if (live == null) {
            return;
        }
        live.stop();
        mStartStop.setText(R.string.live_start);
        if (isAdded()) {
            mStatus.setText(R.string.live_status_ready);
        }
    }

    /** Offers the screen where translation languages are downloaded, if there is one. */
    private void offerTranslationSettings() {
        PendingIntent settings = LiveCaptions.getTranslationSettingsIntent(requireContext());
        if (settings == null) {
            return;
        }
        mStatus.setOnClickListener(ignored -> {
            try {
                settings.send();
            } catch (PendingIntent.CanceledException cancelled) {
                mStatus.setOnClickListener(null);
            }
        });
    }

    /**
     * Whether this device can translate what it hears, asked off the main thread.
     *
     * <p>Not used to decide whether to caption - the words are worth showing either way - but it
     * is what lets the button say what to expect before anybody presses it. The platform warns
     * the question takes seconds, which is why it is not asked on the main thread.</p>
     */
    @Override
    public void onResume() {
        super.onResume();
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            boolean translates = LiveCaptions.isTranslationSupported(
                    requireContext().getApplicationContext(), Locale.getDefault(), TRANSLATE_TO);
            View view = getView();
            if (view != null) {
                view.post(() -> {
                    if (mLiveCaptions == null && isAdded()) {
                        mStatus.setText(translates
                                ? R.string.live_status_ready : R.string.live_status_ready_no_translation);
                    }
                });
            }
        });
    }
}
