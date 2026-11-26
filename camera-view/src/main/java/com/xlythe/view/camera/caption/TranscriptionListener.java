package com.xlythe.view.camera.caption;

import androidx.annotation.NonNull;

import com.xlythe.view.camera.Captions;

import java.io.File;

/**
 * Told how a transcription went. Called on the main thread.
 *
 * <p>Its own type rather than nested inside {@link SpeechTranscriber} for the same reason as
 * {@link CaptionFormat}: that class needs Android 13 and a listener does not, and an app should
 * be able to write the one place it handles captions without guarding the declaration.</p>
 */
public interface TranscriptionListener {
    /**
     * How far through the recording the transcription has got, 0 to 1.
     *
     * <p>Worth reporting because this is not quick: it decodes the audio and runs recognition
     * over all of it, which for a long recording is many seconds.</p>
     */
    default void onTranscriptionProgress(float progress) {}

    /**
     * The recording was transcribed and the captions written beside it.
     *
     * @param sidecar the subtitle file, which sits next to the recording
     */
    void onTranscribed(@NonNull Captions captions, @NonNull File sidecar);

    /**
     * Nothing could be transcribed, and this is why.
     *
     * <p>Worth showing rather than treating as a fault. A recording of a quiet room has nothing
     * in it to transcribe, and that is the commonest reason this is called.</p>
     */
    void onTranscriptionFailed(@NonNull String reason);
}
