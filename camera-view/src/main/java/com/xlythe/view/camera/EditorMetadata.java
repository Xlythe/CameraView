package com.xlythe.view.camera;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;
import androidx.annotation.WorkerThread;

/**
 * What the picture editor needs of {@link MediaTags} beyond its public fields: describing a
 * motion photo's clip it appends or cuts off, and a fresh Exif thumbnail.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class EditorMetadata {
    private EditorMetadata() {}

    /**
     * Says the picture carries a motion photo's clip of the given length at its very end.
     *
     * @param presentationTimestampUs the time in the clip the still shows, or -1
     * @return false if the file cannot say so
     */
    @WorkerThread
    public static boolean describeMotionPhoto(@NonNull MediaTags tags, long videoLength,
            long presentationTimestampUs) {
        return tags.describeMotionPhoto(videoLength, presentationTimestampUs);
    }

    /**
     * Takes out what says the picture carries a motion photo's clip.
     *
     * @return false if the file says it has one and that could not be changed
     */
    @WorkerThread
    public static boolean removeMotionPhoto(@NonNull MediaTags tags) {
        return tags.removeMotionPhotoDescription();
    }

    /** Whether the file's Exif holds a thumbnail. */
    @AnyThread
    public static boolean hasThumbnail(@NonNull MediaTags tags) {
        return tags.hasExifThumbnail();
    }

    /**
     * Has the next save write a thumbnail of the picture into its Exif.
     *
     * @return false if the format keeps no Exif or it cannot be written
     */
    @WorkerThread
    public static boolean attachThumbnail(@NonNull MediaTags tags) {
        return tags.attachExifThumbnail();
    }
}
