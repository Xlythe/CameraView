package com.xlythe.view.camera.caption;

import androidx.annotation.NonNull;

/**
 * Which subtitle format to write captions as.
 *
 * <p>Its own type rather than nested inside {@link SpeechTranscriber} because that class needs
 * Android 13 and this does not. An app has to name a format in order to ask for a
 * transcription, so a format that could only be named on Android 13 would make even the request
 * something callers had to guard.</p>
 */
public enum CaptionFormat {
    /** SubRip, the {@code .srt} file nearly every player reads. */
    SUB_RIP("srt"),

    /** WebVTT, the {@code .vtt} file a browser reads. */
    WEB_VTT("vtt");

    private final String mExtension;

    CaptionFormat(@NonNull String extension) {
        mExtension = extension;
    }

    /** The file extension, without the dot. */
    @NonNull
    public String getExtension() {
        return mExtension;
    }
}
