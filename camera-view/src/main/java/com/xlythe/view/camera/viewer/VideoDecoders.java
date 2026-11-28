package com.xlythe.view.camera.viewer;

import static com.xlythe.view.camera.ICameraModule.TAG;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecInfo.CodecProfileLevel;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Whether this device has a decoder for a file's video, worked out from its tracks before it
 * plays, so a video no decoder here can draw fails rather than playing its sound over nothing.
 * A decoder counts when it takes the track's format and lists the track's profile, or a profile
 * that contains it, at the track's level or higher.
 *
 * <p>Android lists its decoders from 4.1. Before that, and for a file whose tracks cannot be
 * read, a video counts as decodable and the player has the last word.</p>
 */
final class VideoDecoders {
    /** A profile Android names no constant for, such as HEVC's range extensions, so none lists. */
    @VisibleForTesting
    static final int UNLISTED_PROFILE = -1;

    /** The MediaFormat key of a track's profile, as a {@link CodecProfileLevel} profile. */
    private static final String KEY_PROFILE = "profile";

    /** The MediaFormat key of a track's level, as a {@link CodecProfileLevel} level. */
    private static final String KEY_LEVEL = "level";

    /** The MediaFormat key of a track's first codec-specific data. */
    private static final String KEY_CSD = "csd-0";

    private static final String AVC = "video/avc";
    private static final String HEVC = "video/hevc";
    private static final String VP9 = "video/x-vnd.on2.vp9";
    private static final String AV1 = "video/av01";

    // CodecProfileLevel's values, named here since several are newer than the core build's
    // minimum and the class's fields are compile-time constants anyway.
    private static final int AVC_BASELINE = 0x01;
    private static final int AVC_MAIN = 0x02;
    private static final int AVC_EXTENDED = 0x04;
    private static final int AVC_HIGH = 0x08;
    private static final int AVC_HIGH10 = 0x10;
    private static final int AVC_HIGH422 = 0x20;
    private static final int AVC_HIGH444 = 0x40;
    private static final int AVC_CONSTRAINED_BASELINE = 0x10000;
    private static final int AVC_CONSTRAINED_HIGH = 0x80000;
    private static final int HEVC_MAIN = 0x01;
    private static final int HEVC_MAIN10 = 0x02;
    private static final int HEVC_MAIN_STILL = 0x04;
    private static final int HEVC_MAIN10_HDR10 = 0x1000;
    private static final int HEVC_MAIN10_HDR10_PLUS = 0x2000;
    private static final int VP9_PROFILE2 = 0x04;
    private static final int VP9_PROFILE3 = 0x08;
    private static final int VP9_PROFILE2_HDR = 0x1000;
    private static final int VP9_PROFILE3_HDR = 0x2000;
    private static final int VP9_PROFILE2_HDR10_PLUS = 0x4000;
    private static final int VP9_PROFILE3_HDR10_PLUS = 0x8000;
    private static final int AV1_MAIN10 = 0x02;
    private static final int AV1_MAIN10_HDR10 = 0x1000;
    private static final int AV1_MAIN10_HDR10_PLUS = 0x2000;

    /** AVC's level_idc values, in CodecProfileLevel's order from Level1. */
    private static final int[] AVC_LEVEL_IDC = {10, -1, 11, 12, 13, 20, 21, 22, 30, 31, 32, 40,
            41, 42, 50, 51, 52, 60, 61, 62};

    /** HEVC's general_level_idc values, 30 times the level, in CodecProfileLevel's order. */
    private static final int[] HEVC_LEVEL_IDC = {30, 60, 63, 90, 93, 120, 123, 150, 153, 156, 180,
            183, 186};

    private VideoDecoders() {}

    /**
     * Whether a decoder here takes the file's video, or part of a file's; true for a file with
     * no video, and wherever it cannot be told.
     *
     * @param offset where the part starts, or -1 for the whole file
     * @param length how long the part is, or -1
     */
    @WorkerThread
    static boolean canDecode(@NonNull Context context, @NonNull ImageLocation location,
            long offset, long length) {
        if (Build.VERSION.SDK_INT < 16) {
            return true;
        }
        return canDecodeTracks(context, location, offset, length);
    }

    @RequiresApi(16)
    @WorkerThread
    private static boolean canDecodeTracks(@NonNull Context context,
            @NonNull ImageLocation location, long offset, long length) {
        MediaExtractor extractor = new MediaExtractor();
        ParcelFileDescriptor descriptor = null;
        try {
            descriptor = location.openDescriptor(context);
            if (offset >= 0) {
                extractor.setDataSource(descriptor.getFileDescriptor(), offset, length);
            } else {
                extractor.setDataSource(descriptor.getFileDescriptor());
            }
            boolean video = false;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) {
                    continue;
                }
                video = true;
                int[] profileLevel = profileLevel(mime, format);
                if (hasDecoder(mime, profileLevel)) {
                    return true;
                }
                Log.w(TAG, "No decoder here for " + describe(mime, profileLevel) + " in "
                        + location);
            }
            return !video;
        } catch (IOException | RuntimeException e) {
            Log.d(TAG, "Unable to read the tracks of " + location, e);
            return true;
        } finally {
            extractor.release();
            if (descriptor != null) {
                try {
                    descriptor.close();
                } catch (IOException e) {
                    Log.w(TAG, "Unable to close " + location, e);
                }
            }
        }
    }

    /** A track's format, profile and level, for the log. */
    @NonNull
    private static String describe(@NonNull String mime, @Nullable int[] profileLevel) {
        return profileLevel == null ? mime : String.format(Locale.US,
                "%s profile 0x%x level 0x%x", mime, profileLevel[0], profileLevel[1]);
    }

    /**
     * An AVC, HEVC, VP9 or AV1 track's profile and level, as {@link CodecProfileLevel} values,
     * the level 0 when unknown; or null when the profile is not known, or the format is another,
     * whose decoders are taken at their word. From the track's own keys where the platform reads
     * them, or from an AVC or HEVC track's parameter sets.
     */
    @AnyThread
    @Nullable
    @VisibleForTesting
    static int[] profileLevel(@NonNull String mime, @NonNull MediaFormat format) {
        if (!AVC.equalsIgnoreCase(mime) && !HEVC.equalsIgnoreCase(mime)
                && !VP9.equalsIgnoreCase(mime) && !AV1.equalsIgnoreCase(mime)) {
            return null;
        }
        if (format.containsKey(KEY_PROFILE)) {
            int level = format.containsKey(KEY_LEVEL) ? format.getInteger(KEY_LEVEL) : 0;
            return new int[] {format.getInteger(KEY_PROFILE), level};
        }
        ByteBuffer csd = format.getByteBuffer(KEY_CSD);
        if (csd == null) {
            return null;
        }
        byte[] bytes = new byte[csd.remaining()];
        csd.duplicate().get(bytes);
        if (AVC.equalsIgnoreCase(mime)) {
            return avcProfileLevel(bytes);
        }
        if (HEVC.equalsIgnoreCase(mime)) {
            return hevcProfileLevel(bytes);
        }
        return null;
    }

    /** An AVC sequence parameter set's profile and level, or null if there is none. */
    @AnyThread
    @Nullable
    @VisibleForTesting
    static int[] avcProfileLevel(@NonNull byte[] csd) {
        byte[] sps = nal(csd, 7, true);
        if (sps == null || sps.length < 4) {
            return null;
        }
        int profileIdc = sps[1] & 0xFF;
        int constraints = sps[2] & 0xFF;
        int levelIdc = sps[3] & 0xFF;
        int profile;
        switch (profileIdc) {
            case 66:
                // constraint_set1_flag: the part of Baseline that Main and High decoders take.
                profile = (constraints & 0x40) != 0 ? AVC_CONSTRAINED_BASELINE : AVC_BASELINE;
                break;
            case 77:
                profile = AVC_MAIN;
                break;
            case 88:
                profile = AVC_EXTENDED;
                break;
            case 100:
                // constraint_set4 and 5 together: High without B frames.
                profile = (constraints & 0x0C) == 0x0C ? AVC_CONSTRAINED_HIGH : AVC_HIGH;
                break;
            case 110:
                profile = AVC_HIGH10;
                break;
            case 122:
                profile = AVC_HIGH422;
                break;
            case 244:
                profile = AVC_HIGH444;
                break;
            default:
                profile = UNLISTED_PROFILE;
                break;
        }
        int level = 0;
        boolean level1b = levelIdc == 9 || (levelIdc == 11 && (constraints & 0x10) != 0
                && (profileIdc == 66 || profileIdc == 77 || profileIdc == 88));
        if (level1b) {
            level = 0x02;
        } else {
            for (int i = 0; i < AVC_LEVEL_IDC.length; i++) {
                if (AVC_LEVEL_IDC[i] == levelIdc) {
                    level = 1 << i;
                    break;
                }
            }
        }
        return new int[] {profile, level};
    }

    /** An HEVC sequence parameter set's profile and level, or null if there is none. */
    @AnyThread
    @Nullable
    @VisibleForTesting
    static int[] hevcProfileLevel(@NonNull byte[] csd) {
        byte[] sps = nal(csd, 33, false);
        // Two header bytes, one of layer counts, then profile_tier_level: its profile byte,
        // four of compatibility flags, six of constraint flags and the level.
        if (sps == null || sps.length < 15) {
            return null;
        }
        int profileIdc = sps[3] & 0x1F;
        boolean highTier = (sps[3] & 0x20) != 0;
        int levelIdc = sps[14] & 0xFF;
        int profile;
        switch (profileIdc) {
            case 1:
                profile = HEVC_MAIN;
                break;
            case 2:
                profile = HEVC_MAIN10;
                break;
            case 3:
                profile = HEVC_MAIN_STILL;
                break;
            default:
                profile = UNLISTED_PROFILE;
                break;
        }
        int level = 0;
        for (int i = 0; i < HEVC_LEVEL_IDC.length; i++) {
            if (HEVC_LEVEL_IDC[i] == levelIdc) {
                // Each level is a main tier bit followed by its high tier bit.
                level = 1 << (2 * i + (highTier ? 1 : 0));
                break;
            }
        }
        return new int[] {profile, level};
    }

    /**
     * The first NAL unit of a type in codec-specific data, which holds units after start codes
     * or one unit without; its emulation prevention bytes taken out.
     *
     * @param avc whether the units have AVC's one-byte header, rather than HEVC's two
     */
    @Nullable
    private static byte[] nal(@NonNull byte[] data, int type, boolean avc) {
        int start = startCode(data, 0);
        if (start < 0) {
            return data.length > 0 && typeOf(data[0], avc) == type
                    ? unescape(data, 0, data.length) : null;
        }
        while (start >= 0 && start < data.length) {
            int end = nextStartCode(data, start);
            if (typeOf(data[start], avc) == type) {
                return unescape(data, start, end);
            }
            start = startCode(data, end);
        }
        return null;
    }

    private static int typeOf(byte header, boolean avc) {
        return avc ? header & 0x1F : (header >> 1) & 0x3F;
    }

    /** Where the unit after the start code at or after an offset begins, or -1. */
    private static int startCode(@NonNull byte[] data, int from) {
        for (int i = from; i + 2 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                return i + 3;
            }
        }
        return -1;
    }

    /** Where the unit starting at an offset ends: at the next start code, or the end. */
    private static int nextStartCode(@NonNull byte[] data, int from) {
        for (int i = from; i + 2 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && (data[i + 2] == 1
                    || (data[i + 2] == 0 && i + 3 < data.length && data[i + 3] == 1))) {
                return i;
            }
        }
        return data.length;
    }

    /** A unit's bytes with each 0x03 that follows two zero bytes taken out. */
    @NonNull
    private static byte[] unescape(@NonNull byte[] data, int start, int end) {
        byte[] out = new byte[end - start];
        int length = 0;
        int zeros = 0;
        for (int i = start; i < end; i++) {
            if (zeros >= 2 && data[i] == 3) {
                zeros = 0;
                continue;
            }
            zeros = data[i] == 0 ? zeros + 1 : 0;
            out[length++] = data[i];
        }
        byte[] trimmed = new byte[length];
        System.arraycopy(out, 0, trimmed, 0, length);
        return trimmed;
    }

    /** Whether a decoder here takes a format, at a profile and level when they are known. */
    @RequiresApi(16)
    @WorkerThread
    private static boolean hasDecoder(@NonNull String mime, @Nullable int[] profileLevel) {
        for (MediaCodecInfo info : decoders()) {
            for (String type : info.getSupportedTypes()) {
                if (!type.equalsIgnoreCase(mime)) {
                    continue;
                }
                MediaCodecInfo.CodecCapabilities capabilities;
                try {
                    capabilities = info.getCapabilitiesForType(type);
                } catch (RuntimeException e) {
                    Log.d(TAG, "Unable to read what " + info.getName() + " takes", e);
                    return true;
                }
                if (profileLevel == null || supports(mime, profileLevel[0], profileLevel[1],
                        capabilities.profileLevels)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Every decoder this device lists. */
    @RequiresApi(16)
    @WorkerThread
    @NonNull
    @SuppressWarnings("deprecation")
    private static MediaCodecInfo[] decoders() {
        MediaCodecInfo[] all;
        if (Build.VERSION.SDK_INT >= 21) {
            all = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
        } else {
            all = new MediaCodecInfo[MediaCodecList.getCodecCount()];
            for (int i = 0; i < all.length; i++) {
                all[i] = MediaCodecList.getCodecInfoAt(i);
            }
        }
        int count = 0;
        for (MediaCodecInfo info : all) {
            if (!info.isEncoder()) {
                all[count++] = info;
            }
        }
        MediaCodecInfo[] decoders = new MediaCodecInfo[count];
        System.arraycopy(all, 0, decoders, 0, count);
        return decoders;
    }

    /**
     * Whether a decoder listing these profiles and levels takes a track of a profile and level.
     * A decoder that lists none is taken at its word that it takes the format at all.
     */
    @AnyThread
    @VisibleForTesting
    static boolean supports(@NonNull String mime, int profile, int level,
            @Nullable CodecProfileLevel[] listed) {
        if (listed == null || listed.length == 0) {
            return true;
        }
        for (CodecProfileLevel entry : listed) {
            if (contains(mime, entry.profile, profile) && (level <= 0 || entry.level >= level)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a decoder of one profile takes a track of another, which it contains. */
    @AnyThread
    @VisibleForTesting
    static boolean contains(@NonNull String mime, int decoder, int track) {
        if (track == UNLISTED_PROFILE) {
            return false;
        }
        if (decoder == track) {
            return true;
        }
        if (AVC.equalsIgnoreCase(mime)) {
            switch (track) {
                case AVC_BASELINE:
                case AVC_CONSTRAINED_BASELINE:
                case AVC_MAIN:
                    // Main is taken on a Baseline decoder's word too: Android 5's software
                    // decoder lists Baseline alone, and plays Main.
                    return decoder == AVC_BASELINE || decoder == AVC_CONSTRAINED_BASELINE
                            || decoder == AVC_MAIN || decoder == AVC_HIGH
                            || decoder == AVC_CONSTRAINED_HIGH || decoder == AVC_HIGH10
                            || decoder == AVC_HIGH422 || decoder == AVC_HIGH444;
                case AVC_HIGH:
                case AVC_CONSTRAINED_HIGH:
                    return decoder == AVC_HIGH || decoder == AVC_HIGH10
                            || decoder == AVC_HIGH422 || decoder == AVC_HIGH444;
                case AVC_HIGH10:
                    return decoder == AVC_HIGH422 || decoder == AVC_HIGH444;
                case AVC_HIGH422:
                    return decoder == AVC_HIGH444;
                default:
                    return false;
            }
        }
        if (HEVC.equalsIgnoreCase(mime)) {
            boolean main10 = decoder == HEVC_MAIN10 || decoder == HEVC_MAIN10_HDR10
                    || decoder == HEVC_MAIN10_HDR10_PLUS;
            switch (track) {
                case HEVC_MAIN:
                case HEVC_MAIN_STILL:
                    return decoder == HEVC_MAIN || main10;
                case HEVC_MAIN10:
                case HEVC_MAIN10_HDR10:
                case HEVC_MAIN10_HDR10_PLUS:
                    return main10;
                default:
                    return false;
            }
        }
        if (VP9.equalsIgnoreCase(mime)) {
            switch (track) {
                case VP9_PROFILE2:
                case VP9_PROFILE2_HDR:
                case VP9_PROFILE2_HDR10_PLUS:
                    return decoder == VP9_PROFILE2 || decoder == VP9_PROFILE2_HDR
                            || decoder == VP9_PROFILE2_HDR10_PLUS;
                case VP9_PROFILE3:
                case VP9_PROFILE3_HDR:
                case VP9_PROFILE3_HDR10_PLUS:
                    return decoder == VP9_PROFILE3 || decoder == VP9_PROFILE3_HDR
                            || decoder == VP9_PROFILE3_HDR10_PLUS;
                default:
                    return false;
            }
        }
        if (AV1.equalsIgnoreCase(mime)) {
            switch (track) {
                case AV1_MAIN10:
                case AV1_MAIN10_HDR10:
                case AV1_MAIN10_HDR10_PLUS:
                    return decoder == AV1_MAIN10 || decoder == AV1_MAIN10_HDR10
                            || decoder == AV1_MAIN10_HDR10_PLUS;
                default:
                    return false;
            }
        }
        return false;
    }
}
