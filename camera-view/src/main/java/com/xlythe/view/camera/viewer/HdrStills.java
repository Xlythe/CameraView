package com.xlythe.view.camera.viewer;

import static com.xlythe.view.camera.ICameraModule.TAG;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Pictures whose pixels are HDR themselves, with no gain map: BT.2020 with an HLG or PQ transfer,
 * as a 10-bit HEIC or AVIF, or a PNG with a cICP chunk, can be.
 *
 * <p>Android names those colour spaces from Android 14, and a bitmap in one draws in HDR in a
 * window in HDR colour mode. The platform decodes a HEIC or AVIF into one itself. A PNG's cICP
 * chunk it reads for its primaries but not its transfer, so the decoded bitmap is given the
 * colour space the chunk names. Before Android 14 the decoder converts such a picture to SDR as
 * it decodes it.</p>
 */
final class HdrStills {
    /** The PNG signature, which a file must start with for its chunks to be read. */
    private static final long PNG_SIGNATURE = 0x89504E470D0A1A0AL;

    /** The chunk type cICP, which names a picture's colour primaries and transfer. */
    private static final int CICP = 0x63494350;

    /** The chunk type IDAT, after which no cICP chunk may come. */
    private static final int IDAT = 0x49444154;

    /** The most chunks read looking for cICP, so a damaged file cannot keep the reader going. */
    private static final int MAX_CHUNKS = 64;

    /** H.273's code for BT.2020 primaries. */
    private static final int PRIMARIES_BT2020 = 9;

    /** H.273's code for the PQ transfer, SMPTE ST 2084. */
    private static final int TRANSFER_PQ = 16;

    /** H.273's code for the HLG transfer, ARIB STD-B67. */
    private static final int TRANSFER_HLG = 18;

    private HdrStills() {}

    /** Whether the platform decodes a picture, whose bounds these are, into an HDR colour space. */
    static boolean isHdr(@NonNull BitmapFactory.Options bounds) {
        return Build.VERSION.SDK_INT >= 34 && isHdr(bounds.outColorSpace);
    }

    @RequiresApi(34)
    static boolean isHdr(@Nullable ColorSpace colorSpace) {
        return colorSpace != null
                && (colorSpace.getId() == ColorSpace.get(ColorSpace.Named.BT2020_HLG).getId()
                || colorSpace.getId() == ColorSpace.get(ColorSpace.Named.BT2020_PQ).getId());
    }

    /**
     * The HDR colour space a picture's own header names where the platform's decoder does not
     * apply it, or null: a PNG whose cICP chunk names BT.2020 with PQ or HLG.
     *
     * @param input the picture from its first byte, read only as far as its pixels
     */
    @WorkerThread
    @Nullable
    static ColorSpace declaredHdrColorSpace(@NonNull InputStream input) {
        if (Build.VERSION.SDK_INT < 34) {
            return null;
        }
        try {
            int[] cicp = readPngCicp(input);
            return cicp == null ? null : namedHdr(cicp[0], cicp[1]);
        } catch (IOException e) {
            Log.d(TAG, "Unable to read a picture's colour chunk", e);
            return null;
        }
    }

    /** The named HDR colour space for H.273 codes, or null for any other pair. */
    @RequiresApi(34)
    @Nullable
    static ColorSpace namedHdr(int primaries, int transfer) {
        if (primaries != PRIMARIES_BT2020) {
            return null;
        }
        if (transfer == TRANSFER_PQ) {
            return ColorSpace.get(ColorSpace.Named.BT2020_PQ);
        }
        if (transfer == TRANSFER_HLG) {
            return ColorSpace.get(ColorSpace.Named.BT2020_HLG);
        }
        return null;
    }

    /**
     * A PNG's cICP chunk as its primaries and transfer codes, or null for a file that is not a
     * PNG or has none before its pixels.
     */
    @VisibleForTesting
    @WorkerThread
    @Nullable
    static int[] readPngCicp(@NonNull InputStream stream) throws IOException {
        DataInputStream input = new DataInputStream(stream);
        if (input.readLong() != PNG_SIGNATURE) {
            return null;
        }
        for (int i = 0; i < MAX_CHUNKS; i++) {
            long length = input.readInt() & 0xFFFFFFFFL;
            int type = input.readInt();
            if (type == IDAT) {
                return null;
            }
            if (type == CICP && length >= 2) {
                int primaries = input.readUnsignedByte();
                int transfer = input.readUnsignedByte();
                return new int[] {primaries, transfer};
            }
            // The chunk's data and its CRC.
            long remaining = length + 4;
            while (remaining > 0) {
                long skipped = input.skip(remaining);
                if (skipped <= 0) {
                    if (input.read() < 0) {
                        return null;
                    }
                    skipped = 1;
                }
                remaining -= skipped;
            }
        }
        return null;
    }

    /**
     * The config to decode a picture into: 10 bits a channel for an HDR picture stored with 10,
     * which costs no more memory than 8, and otherwise 8. A picture in an HDR colour space keeps
     * it either way.
     */
    @NonNull
    static Bitmap.Config config(@NonNull BitmapFactory.Options bounds) {
        if (Build.VERSION.SDK_INT >= 34 && isHdr(bounds.outColorSpace)
                && bounds.outConfig == Bitmap.Config.RGBA_1010102) {
            return Bitmap.Config.RGBA_1010102;
        }
        return Bitmap.Config.ARGB_8888;
    }

    /**
     * Gives a decoded bitmap the HDR colour space its picture names, where the decoder did not.
     * The pixels are the picture's own coded values either way; this says how to read them.
     */
    @RequiresApi(34)
    static void applyColorSpace(@NonNull Bitmap bitmap, @NonNull ColorSpace colorSpace) {
        try {
            bitmap.setColorSpace(colorSpace);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Unable to give a picture the colour space " + colorSpace.getName(), e);
        }
    }
}
