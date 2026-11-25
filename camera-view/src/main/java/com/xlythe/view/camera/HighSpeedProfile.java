package com.xlythe.view.camera;

import android.util.Range;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One frame size and frame rate a camera will record high-speed video at.
 *
 * <p>The two travel together because a camera does not offer a frame rate on its own terms: the
 * same phone commonly records 240fps at 720p and only 120fps at 1080p, and a session asked for a
 * combination the camera does not publish fails to configure rather than falling back. So a
 * caller picks one of these rather than naming a number.</p>
 */
public final class HighSpeedProfile {
    @NonNull
    private final Size mSize;

    @NonNull
    private final Range<Integer> mFrameRateRange;

    public HighSpeedProfile(@NonNull Size size, @NonNull Range<Integer> frameRateRange) {
        if (size == null) throw new NullPointerException("size cannot be null");
        if (frameRateRange == null) {
            throw new NullPointerException("frameRateRange cannot be null");
        }
        mSize = size;
        mFrameRateRange = frameRateRange;
    }

    @NonNull
    public Size getSize() {
        return mSize;
    }

    /**
     * The range exactly as the camera published it, which is what a capture request has to carry.
     *
     * <p>A request naming anything else is refused by the camera, so this is handed back rather
     * than rebuilt from {@link #getFrameRate()}.</p>
     */
    @NonNull
    public Range<Integer> getFrameRateRange() {
        return mFrameRateRange;
    }

    /** How many frames a second this records, which is the number worth showing someone. */
    public int getFrameRate() {
        return mFrameRateRange.getUpper();
    }

    /**
     * Whether the camera holds this rate rather than being free to drop below it.
     *
     * <p>Worth knowing because a recording that varies its frame rate plays back at the wrong
     * speed: the file says how many frames a second to show, and a camera that quietly halved
     * its rate in a dim room supplied half as many.</p>
     */
    public boolean isFixedFrameRate() {
        return mFrameRateRange.getLower().equals(mFrameRateRange.getUpper());
    }

    /** How many times slower than life this plays back at, given a playback rate. */
    public int getSlowdown(int playbackFrameRate) {
        if (playbackFrameRate <= 0) {
            return 1;
        }
        return Math.max(1, getFrameRate() / playbackFrameRate);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof HighSpeedProfile)) {
            return false;
        }
        HighSpeedProfile that = (HighSpeedProfile) other;
        return mSize.equals(that.mSize) && mFrameRateRange.equals(that.mFrameRateRange);
    }

    @Override
    public int hashCode() {
        return 31 * mSize.hashCode() + mFrameRateRange.hashCode();
    }

    @NonNull
    @Override
    public String toString() {
        return mSize + "@" + mFrameRateRange + "fps";
    }

    /**
     * The choices one published high-speed size amounts to, one per frame rate.
     *
     * <p>A camera publishes several ranges per size and most of them describe the same recording:
     * a device offering 240fps lists both [30, 240], which lets it drop the rate when the light
     * goes, and [240, 240], which does not. Both end at 240, so offering both would be offering
     * the same choice twice, and the fixed one is the one a slow-motion recording wants - a
     * recording whose rate sagged is written as if every frame were taken on time, so it plays
     * back faster than it was shot. The variable range is kept only where a rate has no fixed
     * form, since a camera that publishes nothing else is still a camera that can record.</p>
     *
     * @param ranges the ranges the camera published for this size, which may be null or empty
     */
    @NonNull
    public static List<HighSpeedProfile> forSize(
            @NonNull Size size, @Nullable Range<Integer>[] ranges) {
        if (ranges == null) {
            return Collections.emptyList();
        }
        Map<Integer, HighSpeedProfile> byFrameRate = new LinkedHashMap<>();
        for (Range<Integer> range : ranges) {
            if (range == null || range.getUpper() == null || range.getUpper() <= 0) {
                continue;
            }
            HighSpeedProfile candidate = new HighSpeedProfile(size, range);
            HighSpeedProfile existing = byFrameRate.get(candidate.getFrameRate());
            if (existing == null || (!existing.isFixedFrameRate() && candidate.isFixedFrameRate())) {
                byFrameRate.put(candidate.getFrameRate(), candidate);
            }
        }
        return new ArrayList<>(byFrameRate.values());
    }

    /**
     * The same choices in a stable order: slowest first, and smallest first at each rate.
     *
     * <p>A camera publishes its sizes and ranges in whatever order it likes, and two cameras on
     * the same phone do not agree. An app showing these in a menu would show them in a different
     * order per lens.</p>
     */
    @NonNull
    public static List<HighSpeedProfile> ordered(@NonNull List<HighSpeedProfile> profiles) {
        List<HighSpeedProfile> ordered = new ArrayList<>(profiles);
        Collections.sort(ordered, (left, right) -> {
            if (left.getFrameRate() != right.getFrameRate()) {
                return Integer.compare(left.getFrameRate(), right.getFrameRate());
            }
            long leftPixels = (long) left.mSize.getWidth() * left.mSize.getHeight();
            long rightPixels = (long) right.mSize.getWidth() * right.mSize.getHeight();
            return Long.compare(leftPixels, rightPixels);
        });
        return ordered;
    }

    /**
     * The published choice for a size and a frame rate, or null when the camera has none.
     *
     * <p>Null rather than the nearest match: a session configured for a combination the camera
     * did not publish fails, and a caller who asked for 240fps and silently got 120 has a file
     * that plays back at half the speed they were expecting.</p>
     *
     * @param size the frame size wanted, or null for the largest this camera records at that rate
     */
    @Nullable
    public static HighSpeedProfile match(@NonNull List<HighSpeedProfile> published,
            @Nullable Size size, int frameRate) {
        HighSpeedProfile best = null;
        for (HighSpeedProfile profile : published) {
            if (profile.getFrameRate() != frameRate) {
                continue;
            }
            if (size != null) {
                if (profile.mSize.equals(size)) {
                    return profile;
                }
                continue;
            }
            if (best == null || pixels(profile) > pixels(best)) {
                best = profile;
            }
        }
        return best;
    }

    /**
     * The fastest choice this camera publishes, at the largest size that reaches it.
     *
     * <p>What a caller who only wants "as slow as this phone goes" is asking for.</p>
     */
    @Nullable
    public static HighSpeedProfile fastest(@NonNull List<HighSpeedProfile> published) {
        HighSpeedProfile best = null;
        for (HighSpeedProfile profile : published) {
            if (best == null
                    || profile.getFrameRate() > best.getFrameRate()
                    || (profile.getFrameRate() == best.getFrameRate()
                    && pixels(profile) > pixels(best))) {
                best = profile;
            }
        }
        return best;
    }

    private static long pixels(@NonNull HighSpeedProfile profile) {
        return (long) profile.mSize.getWidth() * profile.mSize.getHeight();
    }
}
