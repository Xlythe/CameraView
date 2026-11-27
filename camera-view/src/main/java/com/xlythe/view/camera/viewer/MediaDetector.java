package com.xlythe.view.camera.viewer;

import static com.xlythe.view.camera.ICameraModule.TAG;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import com.xlythe.view.camera.MediaTags;

import java.io.IOException;
import java.io.InputStream;

/**
 * Works out what a file is, for {@link MediaView}: a picture, a panorama or a photo sphere, a
 * video, a motion photo or a 360° video, or sound; and whether it is HDR.
 */
final class MediaDetector {
    /**
     * How much of the sphere's height, in degrees, a picture with photo sphere metadata must
     * cover to open as a sphere. Below it, it is a panorama that pans flat, wrapping round if it
     * goes all the way. Panorama modes write the same metadata as photo spheres do, so the
     * coverage decides, never the metadata being there.
     */
    @VisibleForTesting
    static final double SPHERE_VERTICAL_DEGREES = 90;

    /** How much wider than tall a picture with no metadata must be to open as a panorama. */
    @VisibleForTesting
    static final double PANORAMA_ASPECT = 2.5;

    /** What a file was found to be. */
    static final class Detection {
        /** One of {@link MediaView}'s media types. */
        @MediaView.MediaType
        final int type;

        /** Whether it is HDR: a picture with a gain map, or a video in HLG or PQ. */
        final boolean hdr;

        /** Where a motion photo keeps its clip, or null. */
        @Nullable
        final MediaTags.MotionPhoto motionPhoto;

        /** The part of the sphere a 360° video covers, or null for any other file. */
        @Nullable
        final GPano sphere;

        /** The part of each frame one eye sees, as {left, top, right, bottom} fractions. */
        @NonNull
        final float[] eye;

        /** The picture that stands for a recording, encoded, or null. */
        @Nullable
        final byte[] coverArt;

        Detection(@MediaView.MediaType int type, boolean hdr,
                @Nullable MediaTags.MotionPhoto motionPhoto, @Nullable GPano sphere,
                @NonNull float[] eye, @Nullable byte[] coverArt) {
            this.type = type;
            this.hdr = hdr;
            this.motionPhoto = motionPhoto;
            this.sphere = sphere;
            this.eye = eye;
            this.coverArt = coverArt;
        }
    }

    private MediaDetector() {}

    /**
     * Works out what a file is.
     *
     * @throws IOException if it cannot be read, or is no media this device knows
     */
    @WorkerThread
    @NonNull
    static Detection detect(@NonNull Context context, @NonNull ImageLocation location)
            throws IOException {
        MediaTags tags = null;
        try {
            tags = location.openTags(context);
        } catch (IOException e) {
            Log.w(TAG, "Unable to read the metadata of " + location, e);
        }
        int kind = tags == null ? MediaTags.KIND_UNKNOWN : tags.getKind();
        int[] bounds = kind == MediaTags.KIND_IMAGE || kind == MediaTags.KIND_UNKNOWN
                ? bounds(context, location) : null;
        if (kind == MediaTags.KIND_UNKNOWN) {
            kind = bounds != null ? MediaTags.KIND_IMAGE : sniff(context, location);
        }
        float[] wholeFrame = {0, 0, 1, 1};
        switch (kind) {
            case MediaTags.KIND_IMAGE: {
                // Bitmaps carry a gain map from Android 14; before it one is only extra bytes.
                boolean hdr = Build.VERSION.SDK_INT >= 34 && tags != null
                        && tags.getGainMap() != MediaTags.GAIN_MAP_NONE;
                MediaTags.MotionPhoto motion = tags == null ? null : tags.getMotionPhoto();
                if (motion != null) {
                    return new Detection(MediaView.MOTION_PHOTO, hdr, motion, null, wholeFrame,
                            null);
                }
                if (tags != null && tags.isAnimated() && animatesHere(tags.getFormat())) {
                    return new Detection(MediaView.ANIMATION, false, null, null, wholeFrame,
                            null);
                }
                int rotation = tags == null ? 0 : tags.getRotation();
                return new Detection(pictureType(tags == null ? null : tags.getPanorama(),
                        bounds, rotation), hdr, null, null, wholeFrame, null);
            }
            case MediaTags.KIND_VIDEO: {
                boolean hdr = tags != null && isHdr(tags.getVideoTransfer());
                if (!hdr && tags != null
                        && tags.getVideoTransfer() == MediaTags.TRANSFER_UNKNOWN) {
                    hdr = isHdrStream(context, location);
                }
                MediaTags.SphericalVideo spherical =
                        tags == null ? null : tags.getSphericalVideo();
                if (spherical != null && spherical.getProjection()
                        == MediaTags.SphericalVideo.PROJECTION_EQUIRECTANGULAR) {
                    return new Detection(MediaView.SPHERICAL_VIDEO, hdr, null, sphereOf(spherical),
                            eyeOf(spherical.getStereoMode()), null);
                }
                return new Detection(MediaView.VIDEO, hdr, null, null, wholeFrame, null);
            }
            case MediaTags.KIND_AUDIO:
                return new Detection(MediaView.AUDIO, false, null, null, wholeFrame,
                        tags == null ? null : tags.getCoverArt());
            default:
                throw new IOException(location + " is no media this device knows");
        }
    }

    /**
     * Whether an animated picture of a format plays on this release: an animated PNG and GIF
     * always, the rest from Android 9, and AVIF from Android 12, where it decodes at all.
     */
    @VisibleForTesting
    static boolean animatesHere(@MediaTags.Format int format) {
        if (format == MediaTags.FORMAT_PNG || format == MediaTags.FORMAT_GIF) {
            return true;
        }
        if (format == MediaTags.FORMAT_AVIF) {
            return Build.VERSION.SDK_INT >= 31;
        }
        return Build.VERSION.SDK_INT >= 28;
    }

    /**
     * Which viewer suits a picture: a sphere or a panorama by how much of the sphere its photo
     * sphere metadata says it covers, or a panorama by its shape when it has none, and
     * otherwise a picture to zoom into.
     *
     * @param bounds the stored picture's width and height, or null if they are unknown
     * @param rotation how far the picture turns to stand upright
     */
    @VisibleForTesting
    @MediaView.MediaType
    static int pictureType(@Nullable MediaTags.Panorama panorama, @Nullable int[] bounds,
            int rotation) {
        if (bounds == null) {
            return MediaView.PICTURE;
        }
        GPano gpano = GPano.from(panorama, bounds[0], bounds[1]);
        if (gpano != null) {
            return gpano.height * 180 >= SPHERE_VERTICAL_DEGREES - 1e-6
                    ? MediaView.PHOTOSPHERE : MediaView.PANORAMA;
        }
        boolean turned = rotation == 90 || rotation == 270;
        double width = turned ? bounds[1] : bounds[0];
        double height = turned ? bounds[0] : bounds[1];
        return height > 0 && width / height >= PANORAMA_ASPECT
                ? MediaView.PANORAMA : MediaView.PICTURE;
    }

    /** Where a 360° video sits on the sphere, from its crop and pose. */
    @VisibleForTesting
    @NonNull
    static GPano sphereOf(@NonNull MediaTags.SphericalVideo spherical) {
        double width = 1 - spherical.getCropLeft() - spherical.getCropRight();
        double height = 1 - spherical.getCropTop() - spherical.getCropBottom();
        if (!(width > 0) || !(height > 0)) {
            return new GPano(0, 0, 1, 1, 0, 0, 0, Float.NaN, Float.NaN, Float.NaN);
        }
        return new GPano(spherical.getCropLeft(), spherical.getCropTop(), width, height,
                spherical.getPoseYawDegrees(), spherical.getPosePitchDegrees(),
                spherical.getPoseRollDegrees(), Float.NaN, Float.NaN, Float.NaN);
    }

    /** The part of each frame the left eye sees, which a mono video gives whole. */
    @VisibleForTesting
    @NonNull
    static float[] eyeOf(@MediaTags.SphericalVideo.StereoMode int stereo) {
        switch (stereo) {
            case MediaTags.SphericalVideo.STEREO_TOP_BOTTOM:
                return new float[] {0, 0, 1, 0.5f};
            case MediaTags.SphericalVideo.STEREO_LEFT_RIGHT:
                return new float[] {0, 0, 0.5f, 1};
            default:
                return new float[] {0, 0, 1, 1};
        }
    }

    private static boolean isHdr(@MediaTags.Transfer int transfer) {
        return transfer == MediaTags.TRANSFER_HLG || transfer == MediaTags.TRANSFER_PQ;
    }

    /** The stored picture's size, from its header alone, or null if it will not decode. */
    @WorkerThread
    @Nullable
    private static int[] bounds(@NonNull Context context, @NonNull ImageLocation location) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream input = location.open(context)) {
            BitmapFactory.decodeStream(input, null, options);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return options.outWidth > 0 && options.outHeight > 0
                ? new int[] {options.outWidth, options.outHeight} : null;
    }

    /** What the platform makes of a file nothing else recognised: video, sound or neither. */
    @WorkerThread
    @MediaTags.Kind
    private static int sniff(@NonNull Context context, @NonNull ImageLocation location) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try (ParcelFileDescriptor descriptor = location.openDescriptor(context)) {
            retriever.setDataSource(descriptor.getFileDescriptor());
            if ("yes".equals(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))) {
                return MediaTags.KIND_VIDEO;
            }
            if ("yes".equals(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))) {
                return MediaTags.KIND_AUDIO;
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "The platform cannot read " + location, e);
        } finally {
            try {
                retriever.release();
            } catch (IOException | RuntimeException ignored) {
                // Nothing more to let go of.
            }
        }
        return MediaTags.KIND_UNKNOWN;
    }

    /**
     * Whether the platform finds a video's stream HDR, for a file whose container says
     * nothing. The platform names the transfer from Android 7.0.
     */
    @WorkerThread
    private static boolean isHdrStream(@NonNull Context context,
            @NonNull ImageLocation location) {
        if (Build.VERSION.SDK_INT < 24) {
            return false;
        }
        MediaExtractor extractor = new MediaExtractor();
        try (ParcelFileDescriptor descriptor = location.openDescriptor(context)) {
            extractor.setDataSource(descriptor.getFileDescriptor());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                    int transfer = format.getInteger(MediaFormat.KEY_COLOR_TRANSFER);
                    if (transfer == MediaFormat.COLOR_TRANSFER_ST2084
                            || transfer == MediaFormat.COLOR_TRANSFER_HLG) {
                        return true;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "The platform cannot read the streams of " + location, e);
        } finally {
            extractor.release();
        }
        return false;
    }
}
