package com.xlythe.view.camera.shadow;

import android.media.CamcorderProfile;
import android.media.EncoderProfiles;
import android.os.Build;

import androidx.annotation.Nullable;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowCamcorderProfile;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * Answers {@link CamcorderProfile#getAll} from the profiles the test registered.
 *
 * <p>{@code getAll} is the API 31 replacement for the {@link CamcorderProfile} fields: it reports
 * the same recording configuration as an {@link EncoderProfiles}, which separates the video and
 * audio tracks and can describe more than one of each. Robolectric's shadow only covers the old
 * fields, so {@code getAll} reaches the unimplemented native method and returns null, and callers
 * that prefer the newer API get a NullPointerException rather than a missing-profile answer.</p>
 *
 * <p>CameraX is such a caller. {@code DefaultEncoderProfilesProvider} reads {@code getAll} on
 * API 31 and above, and {@code ProfileAwareVideoEncoderInfo} walks every camera and quality
 * through it to work out which sizes an encoder really supports, dereferencing the result without
 * a null check. Translating the registered profile is enough for both.</p>
 *
 * <p>The translation is lossy in one direction only: a {@link CamcorderProfile} cannot describe
 * the codec profile, chroma subsampling, bit depth or HDR format, so the video track is reported
 * as 8-bit 4:2:0 SDR with an unknown codec profile, which is what a device that predates those
 * fields reports.</p>
 */
@Implements(CamcorderProfile.class)
public class ShadowListingCamcorderProfile extends ShadowCamcorderProfile {
    /** What {@link EncoderProfiles} reports when a device does not name a codec profile. */
    private static final int PROFILE_UNKNOWN = -1;

    private static final int BIT_DEPTH_8 = 8;

    @Implementation(minSdk = Build.VERSION_CODES.S)
    @Nullable
    protected static EncoderProfiles getAll(String cameraId, int quality) {
        int camera;
        try {
            camera = Integer.parseInt(cameraId);
        } catch (NumberFormatException error) {
            return null;
        }
        if (!hasProfile(camera, quality)) {
            return null;
        }
        CamcorderProfile profile = get(camera, quality);
        if (profile == null) {
            return null;
        }
        return ReflectionHelpers.callConstructor(EncoderProfiles.class,
                ClassParameter.from(int.class, profile.duration),
                ClassParameter.from(int.class, profile.fileFormat),
                ClassParameter.from(EncoderProfiles.VideoProfile[].class,
                        new EncoderProfiles.VideoProfile[] {videoProfile(profile)}),
                ClassParameter.from(EncoderProfiles.AudioProfile[].class,
                        new EncoderProfiles.AudioProfile[] {audioProfile(profile)}));
    }

    /**
     * The video track, described as richly as the platform of the day allows.
     *
     * <p>The chroma, bit depth and HDR arguments arrived in API 33; before that the constructor
     * stops at the codec profile, so the richer call is tried first and the older one stands in.
     * </p>
     */
    private static EncoderProfiles.VideoProfile videoProfile(CamcorderProfile profile) {
        ClassParameter<?>[] core = {
                ClassParameter.from(int.class, profile.videoCodec),
                ClassParameter.from(int.class, profile.videoFrameWidth),
                ClassParameter.from(int.class, profile.videoFrameHeight),
                ClassParameter.from(int.class, profile.videoFrameRate),
                ClassParameter.from(int.class, profile.videoBitRate),
                ClassParameter.from(int.class, PROFILE_UNKNOWN),
        };
        try {
            return ReflectionHelpers.callConstructor(EncoderProfiles.VideoProfile.class,
                    append(core,
                            ClassParameter.from(int.class, EncoderProfiles.VideoProfile.YUV_420),
                            ClassParameter.from(int.class, BIT_DEPTH_8),
                            ClassParameter.from(int.class,
                                    EncoderProfiles.VideoProfile.HDR_NONE)));
        } catch (RuntimeException error) {
            return ReflectionHelpers.callConstructor(
                    EncoderProfiles.VideoProfile.class, core);
        }
    }

    private static EncoderProfiles.AudioProfile audioProfile(CamcorderProfile profile) {
        return ReflectionHelpers.callConstructor(EncoderProfiles.AudioProfile.class,
                ClassParameter.from(int.class, profile.audioCodec),
                ClassParameter.from(int.class, profile.audioChannels),
                ClassParameter.from(int.class, profile.audioSampleRate),
                ClassParameter.from(int.class, profile.audioBitRate),
                ClassParameter.from(int.class, PROFILE_UNKNOWN));
    }

    private static ClassParameter<?>[] append(ClassParameter<?>[] head, ClassParameter<?>... tail) {
        ClassParameter<?>[] all = new ClassParameter<?>[head.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }
}
