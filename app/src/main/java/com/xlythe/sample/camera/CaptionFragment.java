package com.xlythe.sample.camera;

import android.annotation.SuppressLint;
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
import com.xlythe.view.camera.Captions;
import com.xlythe.view.camera.caption.CaptionFormat;
import com.xlythe.view.camera.caption.TranscriptionListener;

import java.io.File;

/**
 * CaptionFragment records a few seconds, transcribes it, and shows what it heard.
 *
 * <p>Two calls: record as usual, then transcribe the file. The captions are written beside the
 * recording as a subtitle file, which is what makes them something a viewer can turn off - and
 * why nothing here draws text over the preview. Burning captions into the frames would mean
 * re-encoding every one to produce something nobody can switch off again.</p>
 *
 * <p>The cues are listed on screen because that is the only way to see, in a demo, that the
 * times line up with what was said. A real app would hand the sidecar to a player.</p>
 */
public class CaptionFragment extends CameraFragment {
    private CameraView mCameraView;
    private TextView mStatus;
    private TextView mCues;
    private Button mRecord;

    @Nullable
    private File mRecording;

    private boolean mRecordingNow;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_caption, container, false);
        mCameraView = view.findViewById(R.id.camera);
        mStatus = view.findViewById(R.id.caption_status);
        mCues = view.findViewById(R.id.caption_cues);
        mRecord = view.findViewById(R.id.caption_record);
        mRecord.setOnClickListener(ignored -> toggleRecording());
        return view;
    }

    @Override
    public void onCameraOpened() {
        if (!mCameraView.isTranscriptionSupported()) {
            // Android 13 is where the recogniser first took a file rather than the microphone,
            // and a recogniser still has to be installed to take it.
            mStatus.setText(R.string.caption_status_unsupported);
            mRecord.setEnabled(false);
            return;
        }
        mRecord.setEnabled(true);
        mStatus.setText(mCameraView.isWordTimingSupported()
                ? R.string.caption_status_ready_timed : R.string.caption_status_ready_windowed);
    }

    @SuppressLint("MissingPermission")
    private void toggleRecording() {
        if (mRecordingNow) {
            mCameraView.stopRecording();
            return;
        }
        mRecording = new File(requireContext().getCacheDir(),
                "to-caption-" + System.currentTimeMillis() + ".mp4");
        mCameraView.setOnVideoCapturedListener(new CameraView.OnVideoCapturedListener() {
            @Override public void onVideoConfirmation() {}

            @Override
            public void onVideoCaptured(File file) {
                mRecordingNow = false;
                mRecord.setText(R.string.caption_record);
                transcribe(file);
            }

            @Override
            public void onFailure() {
                mRecordingNow = false;
                mRecord.setText(R.string.caption_record);
                mStatus.setText(R.string.caption_status_record_failed);
            }
        });
        mCameraView.startRecording(mRecording);
        mRecordingNow = true;
        mRecord.setText(R.string.caption_stop);
        mStatus.setText(R.string.caption_status_recording);
        mCues.setText("");
    }

    private void transcribe(@NonNull File video) {
        mStatus.setText(R.string.caption_status_transcribing);
        mCameraView.transcribe(video, CaptionFormat.SUB_RIP,
                new TranscriptionListener() {
                    @Override
                    public void onTranscriptionProgress(float progress) {
                        mStatus.setText(getString(R.string.caption_status_progress,
                                Math.round(progress * 100)));
                    }

                    @Override
                    public void onTranscribed(@NonNull Captions captions, @NonNull File sidecar) {
                        mStatus.setText(getString(R.string.caption_status_done,
                                captions.getCues().size(), sidecar.getName()));
                        mCues.setText(describe(captions));
                    }

                    @Override
                    public void onTranscriptionFailed(@NonNull String reason) {
                        // Expected in a quiet room, which is why it is shown as the answer
                        // rather than as an error.
                        mStatus.setText(reason);
                        mCues.setText("");
                    }
                });
    }

    /** The cues with their times, so that a demo can show the timing is right. */
    private static CharSequence describe(@NonNull Captions captions) {
        StringBuilder text = new StringBuilder();
        for (Captions.Cue cue : captions.getCues()) {
            text.append(seconds(cue.getStartMs())).append(" - ")
                    .append(seconds(cue.getEndMs())).append('\n')
                    .append(cue.getText()).append("\n\n");
        }
        return text.toString().trim();
    }

    private static String seconds(long millis) {
        return String.format(java.util.Locale.US, "%d.%01ds", millis / 1000, millis % 1000 / 100);
    }
}
