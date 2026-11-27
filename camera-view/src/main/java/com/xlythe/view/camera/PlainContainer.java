package com.xlythe.view.camera;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;

/** A format with no metadata this reads or writes, known only for what it holds. */
final class PlainContainer extends MediaContainer {
    /** What the file is. */
    @MediaTags.Format
    private final int mFormat;

    /** What it holds. */
    @MediaTags.Kind
    private final int mKind;

    /** An empty packet, which can never be written. */
    private final Xmp mXmp = Xmp.empty();

    PlainContainer(@MediaTags.Format int format) {
        this(format, kindOf(format));
    }

    PlainContainer(@MediaTags.Format int format, @MediaTags.Kind int kind) {
        mFormat = format;
        mKind = kind;
    }

    @Override
    int format() {
        return mFormat;
    }

    @Override
    int kind() {
        return mKind;
    }

    /** What a format holds, when nothing else in the file says. */
    @MediaTags.Kind
    private static int kindOf(@MediaTags.Format int format) {
        switch (format) {
            case MediaTags.FORMAT_GIF:
                return MediaTags.KIND_IMAGE;
            case MediaTags.FORMAT_MATROSKA:
                return MediaTags.KIND_VIDEO;
            case MediaTags.FORMAT_AMR:
            case MediaTags.FORMAT_AAC:
            case MediaTags.FORMAT_MP3:
                return MediaTags.KIND_AUDIO;
            default:
                return MediaTags.KIND_UNKNOWN;
        }
    }

    @Override
    boolean keeps(@Field int field) {
        return false;
    }

    @NonNull
    @Override
    Xmp xmp() {
        return mXmp;
    }

    @Override
    int maxXmpBytes() {
        return NO_XMP;
    }

    @Override
    void save(@NonNull File file) throws IOException {
        throw new IOException("This format keeps no metadata");
    }
}
