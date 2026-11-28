package com.xlythe.view.camera;

import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * Turns a JPEG into Google's Motion Photo, version 1: the clip appended to the picture and
 * described in its XMP, which {@link MediaTags#getMotionPhoto()} reads back.
 *
 * <p>The description is {@link MediaTags}' own, the one the picture editor writes when it keeps a
 * clip: the camera namespace's flags, and a container directory listing the primary picture and
 * then the clip, whose length from the end of the file is how a reader finds it. A packet already
 * in the JPEG is merged into, so an Ultra HDR picture keeps its gain map's metadata and the gain
 * map stays where its locator says.</p>
 *
 * <p>The clip can also carry a metadata track that repeats the presentation timestamp and scores
 * frames for a model choosing the best one. Nothing here scores frames, so the descriptor
 * {@link #metadataTrackSample} builds says so with a model version of zero.</p>
 *
 * @see <a href="https://developer.android.com/media/platform/motion-photo-format">Motion Photo
 *     format 1.0</a>
 */
final class MotionPhotoWriter {
    /** The MIME type the format gives the video's optional metadata track. */
    static final String METADATA_TRACK_MIME = "application/motionphoto-image-meta";

    /** The descriptor tags the metadata track uses, from the "User Private" range. */
    private static final int TAG_METADATA = 0xC0;
    private static final int TAG_SCORE = 0xC1;
    private static final int TAG_TRACK_SCORE = 0xC2;
    private static final int TAG_FRAME_SCORE = 0xC3;
    private static final int TAG_FLAGS = 0xC4;
    private static final int TAG_TRACK_FLAGS = 0xC5;

    private MotionPhotoWriter() {}

    /**
     * Turns a JPEG on disk into a motion photo, by describing the video in its XMP and appending
     * the video to it.
     *
     * <p>Written to a sibling file and renamed over the original, so a failure part way through
     * leaves the still as it was rather than a picture with half a video.</p>
     *
     * @param presentationTimestampUs the video frame the still corresponds to, or -1 if unknown
     * @throws IOException if there is no video, the file is not a JPEG, or its XMP cannot say so
     */
    @WorkerThread
    static void write(@NonNull File jpeg, @NonNull File video, long presentationTimestampUs)
            throws IOException {
        long videoLength = video.length();
        if (videoLength <= 0) {
            throw new IOException("A motion photo needs a video");
        }
        File temporary = new File(jpeg.getParentFile(), jpeg.getName() + ".motion");
        try {
            copy(jpeg, temporary, false);
            MediaTags tags = MediaTags.open(temporary);
            if (tags.getFormat() != MediaTags.FORMAT_JPEG) {
                throw new IOException("Not a JPEG");
            }
            if (!tags.describeMotionPhoto(videoLength, presentationTimestampUs)) {
                throw new IOException("The picture's XMP cannot describe a motion photo");
            }
            tags.save();
            copy(video, temporary, true);
            if (!temporary.renameTo(jpeg)) {
                // A filesystem that will not rename over an existing file. Android's do, so this
                // is the slower path a desktop takes, and it is no worse than writing in place.
                copy(temporary, jpeg, false);
            }
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                Log.w(TAG, "Could not delete " + temporary);
            }
        }
    }

    /**
     * The one sample of the video's metadata track: no frames scored, the still's frame and its
     * timestamp, and neither track stabilized.
     *
     * <p>The layout is ISO/IEC 14496-1's descriptor syntax - a tag byte, a length in seven-bit
     * groups, then the fields big-endian - nested as the format lays it out.</p>
     *
     * @param presentationTimestampUs the same value the XMP carries, which the format requires
     */
    @AnyThread
    @NonNull
    static byte[] metadataTrackSample(long presentationTimestampUs) {
        // No model scored anything, which version 0 says; the score beside it is then unused.
        byte[] frameScore = descriptor(TAG_FRAME_SCORE, ByteBuffer.allocate(12)
                .putFloat(0f)
                .putLong(presentationTimestampUs)
                .array());
        byte[] trackScore = descriptor(TAG_TRACK_SCORE, ByteBuffer.allocate(4).putInt(0).array());
        byte[] score = descriptor(TAG_SCORE, concat(
                ByteBuffer.allocate(4).putInt(0 /* modelVersion */).array(),
                frameScore,
                trackScore));
        // isStabilized is the top bit and the rest of the byte is padding. The frames are the
        // camera's as it delivered them, which is never stabilized by this library.
        byte[] trackFlags = descriptor(TAG_TRACK_FLAGS, new byte[] {0});
        byte[] flags = descriptor(TAG_FLAGS, concat(trackFlags /* low */, trackFlags /* high */));
        return descriptor(TAG_METADATA, concat(score, flags));
    }

    /** A tag, the payload's size in the expandable encoding, and the payload. */
    @VisibleForTesting
    @NonNull
    static byte[] descriptor(int tag, @NonNull byte[] payload) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(payload.length + 5);
        output.write(tag);
        int size = payload.length;
        int groups = 1;
        while ((size >>> (7 * groups)) != 0) {
            groups++;
        }
        for (int group = groups - 1; group >= 0; group--) {
            int bits = (size >>> (7 * group)) & 0x7F;
            output.write(group == 0 ? bits : bits | 0x80);
        }
        output.write(payload, 0, payload.length);
        return output.toByteArray();
    }

    @NonNull
    private static byte[] concat(@NonNull byte[]... parts) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            output.write(part, 0, part.length);
        }
        return output.toByteArray();
    }

    /** Copies a file into another, after what it holds already or in its place. */
    @WorkerThread
    private static void copy(@NonNull File from, @NonNull File to, boolean append)
            throws IOException {
        try (InputStream input = new FileInputStream(from);
             OutputStream output = new FileOutputStream(to, append)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }
}
