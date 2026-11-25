package com.xlythe.view.camera.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.media.CamcorderProfile;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.CameraView;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What ends up in the file, read back out of the file.
 *
 * <p>The other device tests ask whether a recording happened. These ask what was recorded: the
 * codec profile the encoder actually used, the resolution it was given, and the metadata a
 * player needs to show it the right way up. All of it is read from the muxed output with the
 * platform's own parsers, because the only claim worth making about quality is one the file
 * supports.</p>
 *
 * <p>Both backends, because an output difference between them is exactly the kind of thing
 * nobody goes looking for.</p>
 */
@RunWith(Parameterized.class)
public final class VideoQualityDeviceTest {
    private static final String TAG = "VideoQualityDeviceTest";

    /** Long enough for a camera to open, converge and deliver. */
    private static final long TIMEOUT_SECONDS = 30;

    /** How long to record for. */
    private static final long RECORD_MILLIS = 4_000;

    /** profile_idc values, from Annex A of the H.264 specification. */
    private static final int AVC_BASELINE = 66;
    private static final int AVC_MAIN = 77;
    private static final int AVC_HIGH = 100;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<CameraView.Backend> backends() {
        return Arrays.asList(CameraView.Backend.CAMERA2, CameraView.Backend.CAMERA_X);
    }

    @Rule
    public final GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            // Granted so that the location a recording should carry is actually available to
            // write. Without it the location test can only skip, which proves nothing.
            Manifest.permission.ACCESS_FINE_LOCATION
    );

    private final CameraView.Backend backend;

    /** Whether this test put a mock provider in place that has to be taken back out. */
    private boolean mInjectedLocation;

    public VideoQualityDeviceTest(CameraView.Backend backend) {
        this.backend = backend;
    }

    @Before
    public void chooseTheBackend() {
        DeviceState.assumeATestableCamera();
        CameraView.setDefaultBackend(backend);
    }

    @After
    public void restoreTheDefault() {
        CameraView.setDefaultBackend(CameraView.Backend.AUTO);
    }

    /**
     * The recording declares a profile its own encoder publishes.
     *
     * <p>The library does not ask for a profile - MediaRecorder takes whatever its encoder
     * defaults to, and on the hardware this was written against that default is already High.
     * What is worth holding is the agreement between the two: a file whose sequence parameter
     * set declares a profile no encoder on the device publishes will not decode everywhere, and
     * nothing else in the suite would notice.</p>
     *
     * <p>The value is logged either way, because which profile a device defaults to is the
     * thing worth knowing when a recording looks worse than it should.</p>
     */
    @Test
    public void aRecordingDeclaresAProfileItsEncoderPublishes() throws Exception {
        MediaFormat format = videoTrack(record());
        String mime = format.getString(MediaFormat.KEY_MIME);
        Assume.assumeTrue("recorded " + mime + ", so profile_idc does not apply",
                MediaFormat.MIMETYPE_VIDEO_AVC.equals(mime));

        int actual = profileIdcOf(format);
        Set<Integer> published = publishedProfileIdcs();
        Log.i(TAG, backend + " recorded profile_idc=" + actual + " (" + nameOf(actual)
                + "); encoders publish " + published);

        assertTrue(backend + " recorded profile_idc=" + actual
                        + ", which no encoder on this device publishes " + published,
                published.contains(actual));
    }

    /** A MAX recording is close to the largest size the device publishes a profile for. */
    @Test
    public void aMaxQualityRecordingIsAsLargeAsTheDeviceOffers() throws Exception {
        MediaFormat format = videoTrack(record());
        int width = format.getInteger(MediaFormat.KEY_WIDTH);
        int height = format.getInteger(MediaFormat.KEY_HEIGHT);
        long pixels = (long) width * height;

        CamcorderProfile best = highestProfile();
        Assume.assumeTrue("no camcorder profile to compare against", best != null);
        long offered = (long) best.videoFrameWidth * best.videoFrameHeight;
        Log.i(TAG, backend + " recorded " + width + "x" + height + ", device offers "
                + best.videoFrameWidth + "x" + best.videoFrameHeight);

        // Within a quarter of the pixels the device offers, rather than equal to them: the
        // recording has to fit the camera's aspect ratio and the preview it shares a session
        // with, so the nearest supported size is not always the largest one.
        assertTrue(backend + " recorded " + width + "x" + height + " at MAX on a device offering "
                        + best.videoFrameWidth + "x" + best.videoFrameHeight,
                pixels * 4 >= offered);
    }

    /**
     * A recording says which way up it was taken.
     *
     * <p>Without it a player shows a portrait video on its side, and there is nothing in the
     * frames themselves to recover it from.</p>
     */
    @Test
    public void aRecordingCarriesItsOrientation() throws Exception {
        MediaMetadataRetriever reader = new MediaMetadataRetriever();
        try {
            reader.setDataSource(record().getAbsolutePath());
            String rotation = reader.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            Log.i(TAG, backend + " recorded rotation " + rotation);
            assertNotNull(backend + " recorded no orientation at all", rotation);
            int degrees = Integer.parseInt(rotation);
            assertTrue(backend + " recorded a rotation of " + degrees,
                    degrees == 0 || degrees == 90 || degrees == 180 || degrees == 270);
        } finally {
            reader.release();
        }
    }

    /**
     * A recording carries where it was taken, on whichever backend wrote it.
     *
     * <p>Camera2 has always written it and CameraX did not, which made the same recording come
     * out differently depending on a backend the caller may never have chosen.</p>
     */
    @Test
    public void aRecordingCarriesItsLocation() throws Exception {
        Assume.assumeTrue("location is not granted, so there is nothing to write",
                ApplicationProvider.getApplicationContext().checkSelfPermission(
                        Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED);
        Location expected = injectTestLocation();

        MediaMetadataRetriever reader = new MediaMetadataRetriever();
        try {
            reader.setDataSource(record().getAbsolutePath());
            String location =
                    reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION);
            Log.i(TAG, backend + " recorded location " + location);
            assertNotNull(backend + " recorded no location at all", location);

            // ISO 6709, which is how the MP4 location box reads back: two signed decimals and a
            // solidus. The tolerance is the four decimal places MediaRecorder writes.
            Matcher coordinates =
                    Pattern.compile("([+-]\\d+\\.\\d+)([+-]\\d+\\.\\d+)").matcher(location);
            assertTrue(backend + " wrote an unreadable location: " + location,
                    coordinates.find());
            assertEquals(backend + " recorded the wrong latitude",
                    expected.getLatitude(), Double.parseDouble(coordinates.group(1)), 0.001);
            assertEquals(backend + " recorded the wrong longitude",
                    expected.getLongitude(), Double.parseDouble(coordinates.group(2)), 0.001);
        } finally {
            reader.release();
        }
    }

    /**
     * Pushes a known location onto the provider the library reads.
     *
     * <p>Waiting for a real fix makes the test a coin toss: a device indoors has none, and the
     * test could only skip and prove nothing. A mock provider is the same code path with a
     * location the test knows, so the value written to the file can be checked rather than
     * merely observed to exist.</p>
     */
    private Location injectTestLocation() {
        LocationManager manager = (LocationManager) ApplicationProvider.getApplicationContext()
                .getSystemService(Context.LOCATION_SERVICE);
        Location where = new Location(LocationManager.GPS_PROVIDER);
        where.setLatitude(37.4219983);
        where.setLongitude(-122.084);
        where.setAltitude(30);
        where.setAccuracy(1f);
        where.setTime(System.currentTimeMillis());
        where.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        try {
            manager.addTestProvider(LocationManager.GPS_PROVIDER,
                    false /* requiresNetwork */, false /* requiresSatellite */,
                    false /* requiresCell */, false /* hasMonetaryCost */,
                    true /* supportsAltitude */, true /* supportsSpeed */,
                    true /* supportsBearing */,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, where);
            mInjectedLocation = true;
        } catch (SecurityException | IllegalArgumentException denied) {
            // Mocking locations is a privilege, not a permission, so it cannot be granted from
            // inside the test. Said out loud rather than skipped silently.
            Assume.assumeNoException("this device will not accept a mock location; grant it with"
                    + " 'adb shell appops set com.xlythe.view.camera.test"
                    + " android:mock_location allow'", denied);
        }
        return where;
    }

    @After
    public void removeTestLocation() {
        if (!mInjectedLocation) {
            return;
        }
        mInjectedLocation = false;
        LocationManager manager = (LocationManager) ApplicationProvider.getApplicationContext()
                .getSystemService(Context.LOCATION_SERVICE);
        try {
            manager.removeTestProvider(LocationManager.GPS_PROVIDER);
        } catch (SecurityException | IllegalArgumentException ignored) {
            // Nothing to put back if it was never taken.
        }
    }

    /** Records a few seconds on this backend and returns the finished file. */
    private File record() throws Exception {
        File file = File.createTempFile("quality", ".mp4",
                ApplicationProvider.getApplicationContext().getCacheDir());
        file.deleteOnExit();
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<File> recorded = new AtomicReference<>();

        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            CountDownLatch opened = new CountDownLatch(1);
            AtomicReference<CameraView> held = new AtomicReference<>();
            scenario.onActivity(activity -> {
                CameraView camera = activity.getCameraView();
                held.set(camera);
                camera.setQuality(CameraView.Quality.MAX);
                camera.setOnCameraStateChangedListener(
                        new CameraView.OnCameraStateChangedListener() {
                            @Override public void onCameraOpened() {
                                opened.countDown();
                            }

                            @Override public void onCameraClosed() {}
                        });
                if (camera.isOpen()) {
                    opened.countDown();
                } else {
                    camera.open();
                }
            });
            assertTrue("the camera never opened on " + backend + " "
                            + DeviceState.whyTheCameraMightNotOpen(),
                    opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            CameraView camera = held.get();
            assertNotNull(camera);
            // The library's default is AUTO, so in a dim room every capture in this suite fires
            // the flash - a dozen times over a full run, which is unpleasant for whoever is
            // holding the phone and makes the captures depend on the light in the room. Nothing
            // here asserts on exposure; the tests that mean to exercise the flash set the mode
            // they want themselves.
            DeviceState.turnTheFlashOff(camera);
            try {
                camera.setOnVideoCapturedListener(new CameraView.OnVideoCapturedListener() {
                    @Override public void onVideoConfirmation() {}

                    @Override public void onVideoCaptured(File saved) {
                        recorded.set(saved);
                        finished.countDown();
                    }

                    @Override public void onFailure() {
                        finished.countDown();
                    }
                });
                camera.startRecording(file);
                Thread.sleep(RECORD_MILLIS);
                camera.stopRecording();
                assertTrue("the recording never reported on " + backend,
                        finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            } finally {
                scenario.onActivity(activity -> activity.getCameraView().close());
            }
        }

        Assume.assumeTrue(backend + " produced no video on this device",
                recorded.get() != null && recorded.get().length() > 0);
        return recorded.get();
    }

    /** The video track's format, as the platform's own demuxer reads it back. */
    private static MediaFormat videoTrack(File file) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    return format;
                }
            }
        } finally {
            extractor.release();
        }
        throw new AssertionError("the recording has no video track");
    }

    /**
     * The profile the file itself declares.
     *
     * <p>Read out of the sequence parameter set rather than asked of the format, because the
     * profile a decoder obeys is the one in the bitstream. csd-0 holds the SPS in Annex B form:
     * a start code, a NAL header whose low five bits are 7, and then profile_idc.</p>
     */
    private static int profileIdcOf(MediaFormat format) {
        ByteBuffer csd = format.getByteBuffer("csd-0");
        assertNotNull("the recording published no sequence parameter set", csd);
        byte[] bytes = new byte[csd.remaining()];
        csd.duplicate().get(bytes);
        for (int i = 0; i + 4 < bytes.length; i++) {
            if (bytes[i] != 0 || bytes[i + 1] != 0) {
                continue;
            }
            int nal;
            if (bytes[i + 2] == 1) {
                nal = i + 3;
            } else if (bytes[i + 2] == 0 && bytes[i + 3] == 1) {
                nal = i + 4;
            } else {
                continue;
            }
            if (nal + 1 < bytes.length && (bytes[nal] & 0x1f) == 7) {
                return bytes[nal + 1] & 0xff;
            }
        }
        throw new AssertionError("no sequence parameter set in csd-0");
    }

    /** Every H.264 profile_idc the encoders on this device publish. */
    private static Set<Integer> publishedProfileIdcs() {
        Set<Integer> published = new TreeSet<>();
        for (MediaCodecInfo info
                : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) {
                continue;
            }
            MediaCodecInfo.CodecCapabilities capabilities;
            try {
                capabilities = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
            } catch (IllegalArgumentException unsupported) {
                continue;
            }
            if (capabilities == null || capabilities.profileLevels == null) {
                continue;
            }
            for (MediaCodecInfo.CodecProfileLevel level : capabilities.profileLevels) {
                int idc = profileIdcOf(level.profile);
                if (idc != 0) {
                    published.add(idc);
                }
            }
        }
        return published;
    }

    /** What a profile_idc is called, for a log line someone has to read. */
    private static String nameOf(int profileIdc) {
        switch (profileIdc) {
            case AVC_HIGH:
                return "High";
            case AVC_MAIN:
                return "Main";
            case AVC_BASELINE:
                return "Baseline";
            default:
                return "profile_idc " + profileIdc;
        }
    }

    /** The H.264 profile_idc a MediaCodec profile constant stands for, or 0 for the rest. */
    private static int profileIdcOf(int codecProfile) {
        switch (codecProfile) {
            case MediaCodecInfo.CodecProfileLevel.AVCProfileHigh:
                return AVC_HIGH;
            case MediaCodecInfo.CodecProfileLevel.AVCProfileMain:
                return AVC_MAIN;
            case MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline:
            case MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline:
                return AVC_BASELINE;
            default:
                // Extended, and the high-bit-depth and 4:2:2 profiles, which the library does
                // not choose between and a recording at 8-bit 4:2:0 cannot use.
                return 0;
        }
    }

    /** The largest recording profile the device publishes for its first camera. */
    private static CamcorderProfile highestProfile() {
        for (int quality : new int[] {CamcorderProfile.QUALITY_2160P,
                CamcorderProfile.QUALITY_1080P, CamcorderProfile.QUALITY_720P,
                CamcorderProfile.QUALITY_480P}) {
            if (CamcorderProfile.hasProfile(0, quality)) {
                return CamcorderProfile.get(0, quality);
            }
        }
        return null;
    }
}
