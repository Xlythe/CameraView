package com.xlythe.view.camera;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.Collection;

/**
 * Thrown by {@link Exif#save()} when the file has no room for some of its tags. Everything else
 * is saved; {@link #getTags()} names the tags that were left out.
 */
public final class ExifNoRoomException extends IOException {
    /** The numbers of the tags left out, in ascending order. */
    @NonNull private final int[] mTags;

    /** @param tags the numbers of the tags left out, in ascending order */
    ExifNoRoomException(@NonNull Collection<Integer> tags) {
        super(message(tags));
        mTags = new int[tags.size()];
        int i = 0;
        for (int tag : tags) {
            mTags[i++] = tag;
        }
    }

    /** The tags left out, by number, in ascending order. */
    @NonNull
    public int[] getTags() {
        return mTags.clone();
    }

    @NonNull
    private static String message(@NonNull Collection<Integer> tags) {
        StringBuilder message = new StringBuilder("No room in the Exif block for ");
        String separator = "";
        for (int tag : tags) {
            message.append(separator).append(ExifTags.describe(tag));
            separator = ", ";
        }
        return message.toString();
    }
}
