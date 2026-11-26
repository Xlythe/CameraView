package com.xlythe.view.camera.caption;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * One line of live captions: what is being said now, and what it means.
 *
 * <p>Live rather than finished, which is the difference from {@link com.xlythe.view.camera.Captions}
 * and the reason this is its own type. A caption for a recording is settled before anybody reads
 * it; a caption for a conversation arrives while the sentence is still being spoken, changes as
 * more of it is heard, and is replaced rather than appended. So the same line is reported several
 * times and {@link #isFinal} says when it has stopped changing.</p>
 *
 * <p>The translation arrives after the text it translates, because translating takes a moment
 * longer than hearing. A line is reported as soon as it is heard, with no translation, and
 * reported again once there is one - so a reader sees the words appear and then the meaning,
 * rather than waiting for both.</p>
 */
public final class LiveCaption {
    private final String mText;
    @Nullable private final String mTranslation;
    private final boolean mFinal;
    private final long mStartMs;
    private final long mEndMs;

    LiveCaption(@NonNull String text, @Nullable String translation, boolean settled,
            long startMs, long endMs) {
        mText = text;
        mTranslation = translation;
        mFinal = settled;
        mStartMs = startMs;
        mEndMs = endMs;
    }

    /** What was said, in the language it was said in. */
    @NonNull
    public String getText() {
        return mText;
    }

    /**
     * What it means in the language asked for, or null if there is not one yet.
     *
     * <p>Null covers three cases that a caller handles the same way - by showing the text and
     * waiting: no translation was asked for, one was asked for and has not arrived, and one was
     * asked for and could not be made.</p>
     */
    @Nullable
    public String getTranslation() {
        return mTranslation;
    }

    /**
     * Whether this line has stopped changing.
     *
     * <p>False while the sentence is still being spoken, and the same line will be reported
     * again with more of it. True once the recogniser has settled on it, which is the moment to
     * keep it if a transcript is being built.</p>
     */
    public boolean isFinal() {
        return mFinal;
    }

    /** When this line started, in milliseconds since captioning began. */
    public long getStartMs() {
        return mStartMs;
    }

    /** When it ended, or where it has got to so far while it is not final. */
    public long getEndMs() {
        return mEndMs;
    }

    /** The same line with a translation attached, which is how one arrives. */
    @NonNull
    LiveCaption withTranslation(@Nullable String translation) {
        return new LiveCaption(mText, translation, mFinal, mStartMs, mEndMs);
    }

    @NonNull
    @Override
    public String toString() {
        return "LiveCaption{" + (mFinal ? "final" : "partial") + " " + mStartMs + "-" + mEndMs
                + " " + mText + (mTranslation == null ? "" : " -> " + mTranslation) + "}";
    }
}
