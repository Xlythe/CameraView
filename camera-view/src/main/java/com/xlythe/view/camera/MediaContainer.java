package com.xlythe.view.camera;

import android.location.Location;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * One file format's own metadata, behind the fields {@link MediaTags} offers for every format.
 *
 * <p>Each field is either one the format keeps natively, which {@link #keeps} says, or one it
 * does not, which {@link MediaTags} keeps in the XMP packet instead where the format has one.
 * A format reads and stages changes in memory, and writes them all at {@link #save}.</p>
 */
abstract class MediaContainer {
    /** The fields a format may keep natively. */
    @IntDef({TITLE, DESCRIPTION, AUTHOR, COPYRIGHT, MAKE, MODEL, SOFTWARE, CAPTURE_TIME, LOCATION,
            HEADING, ORIENTATION, AMBIENT, COVER_ART})
    @Retention(RetentionPolicy.SOURCE)
    @interface Field {}

    static final int TITLE = 1;
    static final int DESCRIPTION = 2;
    static final int AUTHOR = 3;
    static final int COPYRIGHT = 4;
    static final int MAKE = 5;
    static final int MODEL = 6;
    static final int SOFTWARE = 7;
    static final int CAPTURE_TIME = 8;
    static final int LOCATION = 9;
    static final int HEADING = 10;
    static final int ORIENTATION = 11;
    static final int AMBIENT = 12;
    static final int COVER_ART = 13;

    /** Where a format keeps no XMP packet at all. */
    static final int NO_XMP = 0;

    /** Whether staged changes are waiting for {@link #save}. */
    private boolean mDirty;

    /** What the file is, as one of {@link MediaTags}'s formats. */
    @MediaTags.Format
    abstract int format();

    /** Whether the file holds pictures, video or sound, as one of {@link MediaTags}'s kinds. */
    @MediaTags.Kind
    abstract int kind();

    /** Whether the format keeps a field natively, where it can also be written. */
    abstract boolean keeps(@Field int field);

    /**
     * Whether the format has a native place for a field, whether or not it can be written here.
     * A field with a native place that cannot be written is not put in XMP instead, since
     * readers would go on finding the old value where the format keeps it.
     */
    boolean hasNativeHome(@Field int field) {
        return keeps(field);
    }

    /** A text field as the format keeps it, or null. */
    @Nullable
    String text(@Field int field) {
        return null;
    }

    /**
     * Stages a text field, or its removal. Only called for a field the format keeps.
     *
     * @return false, with the field removed, if the format's own form cannot hold this text
     */
    boolean setText(@Field int field, @Nullable String value) throws IOException {
        throw new UnsupportedOperationException();
    }

    /** When the content was captured, in milliseconds since the epoch, or -1. */
    long captureTime() {
        return -1;
    }

    /** Stages the capture time, or its removal for -1. */
    void setCaptureTime(long millis) throws IOException {
        throw new UnsupportedOperationException();
    }

    /** Where the content was captured, or null. */
    @Nullable
    Location location() {
        return null;
    }

    /** Stages the location, or its removal. */
    void setLocation(@Nullable Location location) throws IOException {
        throw new UnsupportedOperationException();
    }

    /** Which way the camera faced, in degrees clockwise from north, or NaN. */
    float heading() {
        return Float.NaN;
    }

    /** Stages the heading, or its removal for NaN. */
    void setHeading(float degrees) throws IOException {
        throw new UnsupportedOperationException();
    }

    /** How the stored content turns upright, as an Exif orientation, or 0 when it does not say. */
    int orientation() {
        return 0;
    }

    /**
     * Stages an orientation, as an Exif orientation.
     *
     * @return false, with nothing staged, if the format cannot hold this one
     */
    boolean setOrientation(int orientation) throws IOException {
        throw new UnsupportedOperationException();
    }

    /** The conditions the content was captured in, or null. */
    @Nullable
    AmbientConditions ambient() {
        return null;
    }

    /** Stages the conditions, or their removal. */
    void setAmbient(@Nullable AmbientConditions conditions) throws IOException {
        throw new UnsupportedOperationException();
    }

    /** The encoded picture that stands for the content, or null. */
    @Nullable
    byte[] coverArt() {
        return null;
    }

    /**
     * Stages a cover picture, or its removal.
     *
     * @return false, with nothing staged, if the format cannot hold a picture of this kind
     */
    boolean setCoverArt(@Nullable byte[] image) throws IOException {
        throw new UnsupportedOperationException();
    }

    /** Whether the file's Exif can be written. */
    boolean canWriteExif() {
        return false;
    }

    /** The file's Exif, if the format keeps Exif, or null. */
    @Nullable
    Exif exif() {
        return null;
    }

    /**
     * The file's Exif, ready to change, or null if the format cannot write it. Changes made
     * through it are written at {@link #save}.
     */
    @Nullable
    Exif writableExif() throws IOException {
        return null;
    }

    /** The QuickTime metadata, for a format that keeps it, or null. */
    @Nullable
    String videoTag(@NonNull VideoMetadata.Tag tag) {
        return null;
    }

    /**
     * Stages a QuickTime field, or its removal.
     *
     * @return false if the format does not keep QuickTime metadata
     */
    boolean setVideoTag(@NonNull VideoMetadata.Tag tag, @Nullable String value) {
        return false;
    }

    /** The file's XMP packet, or an empty one. */
    @NonNull
    abstract Xmp xmp();

    /**
     * A second packet that continues the first, as a large JPEG's extended XMP does, or null.
     * Read only: it is kept as it is.
     */
    @Nullable
    Xmp extendedXmp() {
        return null;
    }

    /**
     * The most bytes a packet may take, or {@link #NO_XMP} when the format keeps none, so the
     * packet can never be written.
     */
    abstract int maxXmpBytes();

    /** Says the packet {@link #xmp()} returned has changed, to be written at {@link #save}. */
    void xmpChanged() {
        markDirty();
    }

    /** Describes a motion photo's clip, if the file carries one, from what its packet says. */
    @Nullable
    MediaTags.MotionPhoto motionPhoto() {
        return null;
    }

    /** Whether the picture is animated: more than one frame, shown in turn. */
    boolean animated() {
        return false;
    }

    /** Which kind of gain map the file carries, as one of {@link MediaTags}'s gain maps. */
    @MediaTags.GainMap
    int gainMap() {
        return MediaTags.GAIN_MAP_NONE;
    }

    /** How the video's brightness is encoded, as one of {@link MediaTags}'s transfers. */
    @MediaTags.Transfer
    int videoTransfer() {
        return MediaTags.TRANSFER_UNKNOWN;
    }

    /** The spherical video metadata, or null. */
    @Nullable
    MediaTags.SphericalVideo sphericalVideo() {
        return null;
    }

    /** The video's display size, upright, or null if it is not a video or does not say. */
    @Nullable
    int[] videoSize() {
        return null;
    }

    /** Settings a camera recorded with a still, or a recording's capture frame rate, or null. */
    @Nullable
    MediaTags.CaptureSettings captureSettings() {
        Exif exif = exif();
        return exif == null ? null : MediaTags.CaptureSettings.of(exif);
    }

    /**
     * The identifier Apple gives both halves of a Live Photo, or null. A still keeps it in
     * Apple's maker note.
     */
    @Nullable
    String livePhotoId() {
        Exif exif = exif();
        return exif == null ? null : appleContentIdentifier(exif.getTagBytes(TAG_MAKER_NOTE));
    }

    /** The Exif tag of a camera maker's own notes. */
    private static final int TAG_MAKER_NOTE = 0x927C;

    /** The tag of Apple's maker note that holds a Live Photo's identifier. */
    private static final int APPLE_CONTENT_IDENTIFIER = 0x0011;

    /** The content identifier in an Apple maker note, or null if it is not one or has none. */
    @Nullable
    static String appleContentIdentifier(@Nullable byte[] note) {
        if (note == null || note.length < 16 || !Bytes.startsWith(note, 0, "Apple iOS")) {
            return null;
        }
        boolean little = note[12] == 'I';
        int count = little ? Bytes.u16le(note, 14) : Bytes.u16(note, 14);
        for (int i = 0; i < count && 16 + i * 12 + 12 <= note.length; i++) {
            int entry = 16 + i * 12;
            int tag = little ? Bytes.u16le(note, entry) : Bytes.u16(note, entry);
            int type = little ? Bytes.u16le(note, entry + 2) : Bytes.u16(note, entry + 2);
            long length = little ? Bytes.u32le(note, entry + 4) : Bytes.u32(note, entry + 4);
            if (tag != APPLE_CONTENT_IDENTIFIER || type != 2 || length > note.length) {
                continue;
            }
            long at = length <= 4 ? entry + 8
                    : little ? Bytes.u32le(note, entry + 8) : Bytes.u32(note, entry + 8);
            if (at + length > note.length) {
                return null;
            }
            String id = new String(note, (int) at, Bytes.terminator(note, (int) at,
                    (int) (at + length)) - (int) at, Bytes.LATIN_1).trim();
            return id.isEmpty() ? null : id;
        }
        return null;
    }

    /** Whether there are changes for {@link #save} to write. */
    boolean isDirty() {
        return mDirty;
    }

    /** Says there are changes for {@link #save} to write. */
    void markDirty() {
        mDirty = true;
    }

    /** Writes every staged change into the file, which is the one this was read from. */
    @WorkerThread
    abstract void save(@NonNull File file) throws IOException;

    /** After a save, the changes are written. */
    void saved() {
        mDirty = false;
    }

    /** Lets go of anything held for writing that was never saved. */
    void release() {}
}
