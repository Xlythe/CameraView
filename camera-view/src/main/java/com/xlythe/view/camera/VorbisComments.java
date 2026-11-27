package com.xlythe.view.camera;

import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Vorbis comments, the tags of FLAC, Ogg Vorbis and Opus: a vendor string, then any number of
 * "NAME=value" entries whose names ignore case. Shared by the formats that keep them, together
 * with the field mapping: TITLE, ARTIST, DESCRIPTION, COPYRIGHT, DATE and ENCODER, and the
 * cover as a FLAC picture.
 */
final class VorbisComments {
    /** The picture type of a front cover, as FLAC and ID3 number them. */
    static final int FRONT_COVER = 3;

    /** The entry a cover picture is kept in, in Ogg. */
    static final String PICTURE = "METADATA_BLOCK_PICTURE";

    /** The vendor string. */
    @NonNull
    private final String mVendor;

    /** The entries, in order, each "NAME=value". */
    @NonNull
    private final List<String> mEntries;

    VorbisComments(@NonNull String vendor, @NonNull List<String> entries) {
        mVendor = vendor;
        mEntries = entries;
    }

    /** No comments, with this library as the vendor. */
    @NonNull
    static VorbisComments empty() {
        return new VorbisComments("CameraView", new ArrayList<String>());
    }

    /**
     * Reads comments from a block, starting at an offset.
     *
     * @return the comments, or null if they do not fit the block
     */
    @Nullable
    static VorbisComments parse(@NonNull byte[] data, int at) {
        if (at + 8 > data.length) {
            return null;
        }
        long vendorLength = Bytes.u32le(data, at);
        if (vendorLength > data.length - at - 8) {
            return null;
        }
        String vendor = new String(data, at + 4, (int) vendorLength, Bytes.UTF_8);
        int position = at + 4 + (int) vendorLength;
        long count = Bytes.u32le(data, position);
        position += 4;
        List<String> entries = new ArrayList<>();
        for (long i = 0; i < count; i++) {
            if (position + 4 > data.length) {
                return null;
            }
            long length = Bytes.u32le(data, position);
            if (length > data.length - position - 4) {
                return null;
            }
            entries.add(new String(data, position + 4, (int) length, Bytes.UTF_8));
            position += 4 + (int) length;
        }
        return new VorbisComments(vendor, entries);
    }

    /** The comments as a block: vendor, count, entries. */
    @NonNull
    byte[] toBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeString(out, mVendor);
        byte[] count = new byte[4];
        Bytes.put32le(count, 0, mEntries.size());
        out.write(count, 0, 4);
        for (String entry : mEntries) {
            writeString(out, entry);
        }
        return out.toByteArray();
    }

    private static void writeString(@NonNull ByteArrayOutputStream out, @NonNull String text) {
        byte[] bytes = text.getBytes(Bytes.UTF_8);
        byte[] length = new byte[4];
        Bytes.put32le(length, 0, bytes.length);
        out.write(length, 0, 4);
        out.write(bytes, 0, bytes.length);
    }

    /** The first value of a name, or null. */
    @Nullable
    String get(@NonNull String name) {
        String prefix = name.toUpperCase(Locale.US) + "=";
        for (String entry : mEntries) {
            if (entry.length() >= prefix.length()
                    && entry.substring(0, prefix.length()).toUpperCase(Locale.US).equals(prefix)) {
                String value = entry.substring(prefix.length());
                return value.trim().isEmpty() ? null : value;
            }
        }
        return null;
    }

    /** Sets a name to one value in place of all it had, or removes it for null. */
    void set(@NonNull String name, @Nullable String value) {
        String prefix = name.toUpperCase(Locale.US) + "=";
        int index = -1;
        for (int i = mEntries.size() - 1; i >= 0; i--) {
            String entry = mEntries.get(i);
            if (entry.length() >= prefix.length()
                    && entry.substring(0, prefix.length()).toUpperCase(Locale.US).equals(prefix)) {
                mEntries.remove(i);
                index = i;
            }
        }
        if (value != null) {
            String entry = name.toUpperCase(Locale.US) + "=" + value;
            if (index >= 0) {
                mEntries.add(index, entry);
            } else {
                mEntries.add(entry);
            }
        }
    }

    /** The name a field is kept under, or null. */
    @Nullable
    static String nameOf(@MediaContainer.Field int field) {
        switch (field) {
            case MediaContainer.TITLE:
                return "TITLE";
            case MediaContainer.AUTHOR:
                return "ARTIST";
            case MediaContainer.DESCRIPTION:
                return "DESCRIPTION";
            case MediaContainer.COPYRIGHT:
                return "COPYRIGHT";
            case MediaContainer.SOFTWARE:
                return "ENCODER";
            default:
                return null;
        }
    }

    /** A field's value, with the usual alternatives for a description. */
    @Nullable
    String field(@MediaContainer.Field int field) {
        String name = nameOf(field);
        if (name == null) {
            return null;
        }
        String value = get(name);
        if (value == null && field == MediaContainer.DESCRIPTION) {
            value = get("COMMENT");
        }
        return value;
    }

    /** The recording date, as DATE holds it in ISO 8601, or -1. */
    long date() {
        return MediaTags.parseIsoTime(get("DATE"));
    }

    /** Sets the recording date in ISO 8601, with its offset, or removes it for -1. */
    void setDate(long millis) {
        set("DATE", millis < 0 ? null : MediaTags.formatIsoTime(millis, TimeZone.getDefault()));
    }

    /** The cover picture kept as an Ogg entry, or null. */
    @Nullable
    byte[] picture() {
        String encoded = get(PICTURE);
        if (encoded != null) {
            try {
                byte[] block = Base64.decode(encoded, Base64.DEFAULT);
                byte[] image = pictureData(block);
                if (image != null) {
                    return image;
                }
            } catch (IllegalArgumentException malformed) {
                // Fall back to the older entry.
            }
        }
        String legacy = get("COVERART");
        if (legacy != null) {
            try {
                return Base64.decode(legacy, Base64.DEFAULT);
            } catch (IllegalArgumentException malformed) {
                return null;
            }
        }
        return null;
    }

    /** Sets the cover as an Ogg entry, or removes it for null. */
    void setPicture(@Nullable byte[] block) {
        set("COVERART", null);
        set(PICTURE, block == null ? null : Base64.encodeToString(block, Base64.NO_WRAP));
    }

    /** The image in a FLAC picture block, or null if it is malformed. */
    @VisibleForTesting
    @Nullable
    static byte[] pictureData(@NonNull byte[] block) {
        if (block.length < 32) {
            return null;
        }
        long mimeLength = Bytes.u32(block, 4);
        int at = 8 + (int) Math.min(mimeLength, block.length);
        if (at + 4 > block.length) {
            return null;
        }
        long descriptionLength = Bytes.u32(block, at);
        at += 4 + (int) Math.min(descriptionLength, block.length);
        at += 16;
        if (at + 4 > block.length) {
            return null;
        }
        long length = Bytes.u32(block, at);
        if (length > block.length - at - 4) {
            return null;
        }
        byte[] image = new byte[(int) length];
        System.arraycopy(block, at + 4, image, 0, image.length);
        return image;
    }

    /** The type of a FLAC picture block. */
    static int pictureType(@NonNull byte[] block) {
        return block.length >= 4 ? (int) Bytes.u32(block, 0) : -1;
    }

    /**
     * A FLAC picture block for a front cover, or null if it is not a JPEG or PNG. The size is
     * left as zero, which the format allows for unknown.
     */
    @Nullable
    static byte[] pictureBlock(@NonNull byte[] image) {
        String mime = Id3Container.mimeOf(image);
        if (mime == null) {
            return null;
        }
        byte[] mimeBytes = mime.getBytes(Bytes.LATIN_1);
        byte[] block = new byte[32 + mimeBytes.length + image.length];
        Bytes.put32(block, 0, FRONT_COVER);
        Bytes.put32(block, 4, mimeBytes.length);
        System.arraycopy(mimeBytes, 0, block, 8, mimeBytes.length);
        int at = 8 + mimeBytes.length;
        // An empty description, then width, height, depth and colours unknown.
        at += 4 + 16;
        Bytes.put32(block, at, image.length);
        System.arraycopy(image, 0, block, at + 4, image.length);
        return block;
    }
}
