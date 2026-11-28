package com.xlythe.view.camera.viewer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.test.core.app.ApplicationProvider;

import com.xlythe.view.camera.MediaTags;
import com.xlythe.view.camera.MediaTestFiles;
import com.xlythe.view.camera.VideoView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowMediaPlayer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.OutputStream;
import java.nio.file.Files;

/** What MediaView finds a file to be, and how it shows and plays it. */
@RunWith(RobolectricTestRunner.class)
// The region decoder Robolectric stands in for is the one before API 31.
@Config(sdk = 30)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class MediaViewTest {
    private Activity mActivity;
    private MediaView mView;
    private final Recorder mRecorder = new Recorder();

    /** Remembers what the view reported. */
    private static final class Recorder implements MediaListener {
        int loaded = -1;
        boolean failed;

        @Override
        public void onMediaLoaded(int type) {
            loaded = type;
        }

        @Override
        public void onMediaFailed() {
            failed = true;
        }
    }

    @Before
    public void setUp() {
        ShadowMediaPlayer.resetStaticState();
        ShadowMediaPlayer.setMediaInfoProvider(
                source -> new ShadowMediaPlayer.MediaInfo(4000, 0));
        mActivity = Robolectric.buildActivity(Activity.class).setup().get();
        mView = new MediaView(mActivity);
        mView.setExecutor(Runnable::run);
        mView.setMediaListener(mRecorder);
        FrameLayout root = new FrameLayout(mActivity);
        root.addView(mView, new ViewGroup.LayoutParams(400, 300));
        mActivity.setContentView(root);
        mView.layout(0, 0, 400, 300);
    }

    private static File file(byte[] bytes, String suffix) throws IOException {
        File file = File.createTempFile("media", suffix);
        file.deleteOnExit();
        Files.write(file.toPath(), bytes);
        return file;
    }

    /** A real JPEG of a size, with XMP in an APP1 segment straight after its start. */
    static byte[] jpeg(int width, int height, String xmp) {
        // Encoded by the JVM's own ImageIO, since the legacy graphics' encoder writes no real
        // JPEG; reached by name, since the Android classpath the tests compile against has none.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            Class<?> type = Class.forName("java.awt.image.BufferedImage");
            Object image = type.getConstructor(int.class, int.class, int.class)
                    .newInstance(width, height, 1);
            Class.forName("javax.imageio.ImageIO").getMethod("write",
                    Class.forName("java.awt.image.RenderedImage"), String.class,
                    OutputStream.class).invoke(null, image, "jpg", out);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        byte[] picture = out.toByteArray();
        if (xmp == null) {
            return picture;
        }
        byte[] identifier = "http://ns.adobe.com/xap/1.0/\u0000"
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] packet = xmp.getBytes(StandardCharsets.UTF_8);
        int length = 2 + identifier.length + packet.length;
        ByteArrayOutputStream withXmp = new ByteArrayOutputStream();
        withXmp.write(picture, 0, 2);
        withXmp.write(0xFF);
        withXmp.write(0xE1);
        withXmp.write(length >> 8);
        withXmp.write(length & 0xFF);
        withXmp.write(identifier, 0, identifier.length);
        withXmp.write(packet, 0, packet.length);
        withXmp.write(picture, 2, picture.length - 2);
        return withXmp.toByteArray();
    }

    private static String gpano(String attributes) {
        return "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/"
                + "1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\" xmlns:GPano=\""
                + "http://ns.google.com/photos/1.0/panorama/\" " + attributes
                + "/></rdf:RDF></x:xmpmeta>";
    }

    private void show(File file) {
        mView.setFile(file);
        ShadowLooper.idleMainLooper();
    }

    @Test
    public void aPictureOpensFlat() throws IOException {
        show(file(jpeg(64, 48, null), ".jpg"));
        assertEquals(MediaView.PICTURE, mView.getMediaType());
        assertTrue(mView.getContentView() instanceof LargeImageView);
        assertFalse(mView.getContentView() instanceof PanoramaView);
    }

    @Test
    public void aWidePictureWithoutMetadataIsAPanorama() throws IOException {
        show(file(jpeg(300, 100, null), ".jpg"));
        assertEquals(MediaView.PANORAMA, mView.getMediaType());
        assertTrue(mView.getContentView() instanceof PanoramaView);
    }

    @Test
    public void coverageDecidesSphereOrPanorama() throws IOException {
        // A whole sphere.
        show(file(jpeg(200, 100, gpano("GPano:ProjectionType=\"equirectangular\" "
                + "GPano:FullPanoWidthPixels=\"200\" GPano:FullPanoHeightPixels=\"100\"")),
                ".jpg"));
        assertEquals(MediaView.PHOTOSPHERE, mView.getMediaType());
        assertTrue(mView.getContentView() instanceof PhotosphereView);

        // A panorama mode's band, a third of the sphere's height, with the same metadata.
        show(file(jpeg(300, 50, gpano("GPano:ProjectionType=\"equirectangular\" "
                + "GPano:CroppedAreaImageWidthPixels=\"300\" "
                + "GPano:CroppedAreaImageHeightPixels=\"50\" GPano:FullPanoWidthPixels=\"300\" "
                + "GPano:FullPanoHeightPixels=\"150\" GPano:CroppedAreaTopPixels=\"50\"")),
                ".jpg"));
        assertEquals(MediaView.PANORAMA, mView.getMediaType());

        // Never a sphere without metadata, however close to 2:1.
        show(file(jpeg(200, 100, null), ".jpg"));
        assertEquals(MediaView.PICTURE, mView.getMediaType());
    }

    @Test
    public void theTypeCanBeChosenAndUnchosen() throws IOException {
        show(file(jpeg(64, 48, null), ".jpg"));
        mView.setMediaType(MediaView.PHOTOSPHERE);
        ShadowLooper.idleMainLooper();
        assertEquals(MediaView.PHOTOSPHERE, mView.getMediaType());
        assertTrue(mView.getContentView() instanceof PhotosphereView);
        mView.setMediaType(MediaView.PANORAMA);
        assertTrue(mView.getContentView() instanceof PanoramaView);
        mView.setMediaType(MediaView.UNKNOWN);
        assertEquals(MediaView.PICTURE, mView.getMediaType());
        mView.setMediaType(MediaView.UNKNOWN);
        assertEquals(MediaView.PICTURE, mView.getMediaType());
    }

    @Test
    public void aMissingFileFails() {
        show(new File("/nowhere/to/be/found.jpg"));
        assertTrue(mRecorder.failed);
        assertEquals(MediaView.UNKNOWN, mView.getMediaType());
        assertNull(mView.getContentView());
    }

    @Test
    public void nothingShowsForNull() throws IOException {
        show(file(jpeg(64, 48, null), ".jpg"));
        mView.setFile(null);
        assertNull(mView.getContentView());
        mView.setUri(null);
        assertEquals(MediaView.UNKNOWN, mView.getMediaType());
    }

    @Test
    public void aVideoGoesToAVideoView() throws IOException {
        File movie = file(MediaTestFiles.movie(), ".mp4");
        show(movie);
        assertEquals(MediaView.VIDEO, mView.getMediaType());
        assertTrue(mView.getContentView() instanceof VideoView);
        // Taps are the media view's, so the video view does not take them for its own.
        assertTrue(mView.getContentView().isClickable());
        assertFalse(mView.isPlaying());
    }

    @Test
    public void aSphericalVideoIsDrawnOnTheSphere() throws IOException {
        show(file(MediaTestFiles.sphericalMovie(), ".mp4"));
        assertEquals(MediaView.SPHERICAL_VIDEO, mView.getMediaType());
        PhotosphereView sphere = (PhotosphereView) mView.getContentView();
        assertTrue(sphere.getRenderer().hasVideo());
        mView.setFile(null);
        assertFalse(sphere.getRenderer().hasVideo());
    }

    @Test
    public void soundShowsItsCoverOrAnIconAndATapPlaysIt() throws IOException {
        File mp3 = file(MediaTestFiles.mp3WithCover(jpeg(20, 20, null)), ".mp3");
        show(mp3);
        assertEquals(MediaView.AUDIO, mView.getMediaType());
        ImageView image = (ImageView) mView.getContentView();
        assertNotNull(image.getDrawable());
        ShadowLooper.idleMainLooper();
        assertEquals(MediaView.AUDIO, mRecorder.loaded);

        assertTrue(mView.performClick());
        assertTrue(mView.isPlaying());
        mView.draw(new Canvas(Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)));
        assertTrue(mView.pause());
        assertFalse(mView.isPlaying());
        assertFalse(mView.pause());
        assertTrue(mView.play());

        mView.setTapToPlayEnabled(false);
        assertFalse(mView.isTapToPlayEnabled());
        mView.performClick();
        assertTrue(mView.isPlaying());

        // Without a cover, the icon, which can be replaced or taken away.
        show(file(MediaTestFiles.mp3WithCover(null), ".mp3"));
        image = (ImageView) mView.getContentView();
        assertNotNull(image.getDrawable());
        ColorDrawable replacement = new ColorDrawable(0xFF00FF00);
        mView.setAudioPlaceholder(replacement);
        assertEquals(replacement, image.getDrawable());
        mView.setAudioPlaceholder(null);
        assertNull(image.getDrawable());
    }

    @Test
    public void holdingAMotionPhotoPlaysItsClip() throws IOException {
        byte[] picture = jpeg(64, 48, null);
        byte[] video = MediaTestFiles.mp4Clip(500);
        String packet = MediaTestFiles.motionPhotoXmp(video.length);
        byte[] motion = MediaTestFiles.concat(jpeg(64, 48, packet), video);
        show(file(motion, ".jpg"));
        assertEquals(MediaView.MOTION_PHOTO, mView.getMediaType());
        assertTrue(mView.getContentView() instanceof LargeImageView);

        long now = SystemClock.uptimeMillis();
        assertTrue(mView.performLongClick());
        assertTrue(hasClip());
        // The clip stops when the finger lifts.
        mView.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 10, 10, 0));
        assertFalse(hasClip());

        mView.setHoldToPlayEnabled(false);
        assertFalse(mView.isHoldToPlayEnabled());
        mView.performLongClick();
        assertFalse(hasClip());
    }

    private boolean hasClip() {
        for (int i = 0; i < mView.getChildCount(); i++) {
            if (mView.getChildAt(i) instanceof TextureView
                    && !(mView.getChildAt(i).getParent() instanceof VideoView)) {
                return true;
            }
        }
        return false;
    }

    /** Before Android 14 a gain map is only extra bytes, so the picture holds nothing. */
    @Test
    public void aGainMapIsSdrBeforeAndroid14() throws IOException {
        show(file(MediaTestFiles.gainMapJpeg(), ".jpg"));
        assertEquals(MediaView.PICTURE, mView.getMediaType());
        assertFalse(mView.isHoldingHdr());
    }

    @Test
    @Config(sdk = 34)
    public void hdrHoldsTheWindowOnlyWhileItShows() throws IOException {
        int before = mActivity.getWindow().getColorMode();
        show(file(MediaTestFiles.gainMapJpeg(), ".jpg"));
        assertTrue(mView.isHoldingHdr());
        assertEquals(ActivityInfo.COLOR_MODE_HDR, mActivity.getWindow().getColorMode());

        mView.setHdrEnabled(false);
        assertFalse(mView.isHdrEnabled());
        assertEquals(before, mActivity.getWindow().getColorMode());
        mView.setHdrEnabled(true);
        assertTrue(mView.isHoldingHdr());

        // Off the screen, the window goes back.
        ((ViewGroup) mView.getParent()).removeView(mView);
        assertFalse(mView.isHoldingHdr());
        assertEquals(before, mActivity.getWindow().getColorMode());

        // An SDR picture holds nothing.
        MediaView other = new MediaView(mActivity);
        other.setExecutor(Runnable::run);
        mActivity.setContentView(other);
        other.setFile(file(jpeg(64, 48, null), ".jpg"));
        ShadowLooper.idleMainLooper();
        assertFalse(other.isHoldingHdr());
    }

    @Test
    public void settingsAreKept() {
        mView.setGyroscopeEnabled(true);
        assertTrue(mView.isGyroscopeEnabled());
        mView.setProgressVisible(false);
        assertFalse(mView.isProgressVisible());
        mView.setProgressColor(0xFFFF0000);
        mView.setProgressHeight(10);
        assertTrue(mView.isTapToPlayEnabled());
        assertTrue(mView.isHoldToPlayEnabled());
        assertEquals(0f, mView.getProgress(), 0f);
    }

    @Test
    public void readsItsAttributes() {
        Context context = ApplicationProvider.getApplicationContext();
        AttributeSet attributes = Robolectric.buildAttributeSet()
                .addAttribute(com.xlythe.view.camera.R.attr.mediaType, "panorama")
                .addAttribute(com.xlythe.view.camera.R.attr.tapToPlay, "false")
                .addAttribute(com.xlythe.view.camera.R.attr.holdToPlay, "false")
                .addAttribute(com.xlythe.view.camera.R.attr.mediaProgressVisible, "false")
                .addAttribute(com.xlythe.view.camera.R.attr.hdrEnabled, "false")
                .addAttribute(com.xlythe.view.camera.R.attr.gyroscopeEnabled, "true")
                .addAttribute(com.xlythe.view.camera.R.attr.mediaProgressColor, "#ff00ff00")
                .addAttribute(com.xlythe.view.camera.R.attr.mediaProgressHeight, "4dp")
                .addAttribute(com.xlythe.view.camera.R.attr.audioPlaceholder, "@null")
                .build();
        MediaView view = new MediaView(context, attributes);
        assertFalse(view.isTapToPlayEnabled());
        assertFalse(view.isHoldToPlayEnabled());
        assertFalse(view.isProgressVisible());
        assertFalse(view.isHdrEnabled());
        assertTrue(view.isGyroscopeEnabled());
    }

    @Test
    public void detectsWithoutAView() throws IOException {
        Context context = ApplicationProvider.getApplicationContext();
        MediaDetector.Detection picture = MediaDetector.detect(context,
                ImageLocation.of(file(jpeg(64, 48, null), ".jpg")));
        assertEquals(MediaView.PICTURE, picture.type);
        assertFalse(picture.hdr);
        MediaDetector.Detection sound = MediaDetector.detect(context,
                ImageLocation.of(file(MediaTestFiles.mp3WithCover(new byte[] {1, 2}),
                        ".mp3")));
        assertEquals(MediaView.AUDIO, sound.type);
        assertArrayEquals(new byte[] {1, 2}, sound.coverArt);
        try {
            MediaDetector.detect(context, ImageLocation.of(new File("/nowhere.mp4")));
            throw new AssertionError("Nothing to read is no media");
        } catch (IOException expected) {
            // As documented.
        }
    }

    @Test
    public void picksTheViewerFromCoverageAndShape() {
        assertEquals(MediaView.PICTURE, MediaDetector.pictureType(null, null, 0));
        assertEquals(MediaView.PANORAMA, MediaDetector.pictureType(null, new int[] {500, 100}, 0));
        // A tall picture turned on its side is wide.
        assertEquals(MediaView.PANORAMA, MediaDetector.pictureType(null, new int[] {100, 500},
                90));
        assertEquals(MediaView.PICTURE, MediaDetector.pictureType(null, new int[] {100, 500}, 0));
        assertArrayEquals(new float[] {0, 0, 0.5f, 1}, MediaDetector.eyeOf(
                MediaTags.SphericalVideo.STEREO_LEFT_RIGHT), 0f);
        assertArrayEquals(new float[] {0, 0, 1, 1}, MediaDetector.eyeOf(
                MediaTags.SphericalVideo.STEREO_MONO), 0f);
    }

    /** An animated picture plays as soon as it shows, and a tap pauses and plays it. */
    @Test
    public void anAnimationPlaysAndATapPausesIt() throws IOException {
        show(file(ApngDecoderTest.everyOperation(), ".png"));
        ShadowLooper.idleMainLooper();
        assertEquals(MediaView.ANIMATION, mView.getMediaType());
        assertEquals(MediaView.ANIMATION, mRecorder.loaded);
        assertTrue(mView.getContentView() instanceof AnimationView);
        assertTrue(mView.isPlaying());
        assertFalse("already playing", mView.play());

        assertTrue(mView.performClick());
        assertFalse(mView.isPlaying());
        assertFalse("already paused", mView.pause());
        assertTrue(mView.performClick());
        assertTrue(mView.isPlaying());
        Bitmap bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888);
        mView.draw(new Canvas(bitmap));

        // Shown as a still, an animation is just its first frame.
        mView.setMediaType(MediaView.PICTURE);
        assertTrue(mView.getContentView() instanceof LargeImageView);
    }

    /** An animation taken off the screen pauses, and plays on when it is put back. */
    @Test
    public void anAnimationPlaysOnWhenItsViewIsPutBack() throws IOException {
        show(file(ApngDecoderTest.everyOperation(), ".png"));
        ShadowLooper.idleMainLooper();
        assertTrue(mView.isPlaying());
        ViewGroup parent = (ViewGroup) mView.getParent();
        parent.removeView(mView);
        assertFalse(mView.isPlaying());
        parent.addView(mView);
        assertTrue(mView.isPlaying());
        assertTrue(mView.getContentView() instanceof AnimationView);
    }

    /** A picture asked to animate that does not, shows still. */
    @Test
    public void aStillShownAsAnAnimationStaysStill() throws IOException {
        mView.setMediaType(MediaView.ANIMATION);
        show(file(jpeg(64, 48, null), ".jpg"));
        ShadowLooper.idleMainLooper();
        assertTrue(mView.getContentView() instanceof AnimationView);
        assertEquals(MediaView.ANIMATION, mRecorder.loaded);
        assertFalse(mView.isPlaying());
        assertFalse(mView.play());
        Bitmap bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888);
        mView.draw(new Canvas(bitmap));
    }

    /** What cannot be decoded at all fails, as anything else would. */
    @Test
    public void anAnimationThatCannotBeReadFails() {
        mView.setMediaType(MediaView.ANIMATION);
        show(new File("/nowhere.gif"));
        ShadowLooper.idleMainLooper();
        assertTrue(mRecorder.failed);
    }

    @Test
    @Config(sdk = 27)
    public void beforeAndroid9OnlyGifAndPngAnimate() {
        assertTrue(MediaDetector.animatesHere(MediaTags.FORMAT_PNG));
        assertTrue(MediaDetector.animatesHere(MediaTags.FORMAT_GIF));
        assertFalse(MediaDetector.animatesHere(MediaTags.FORMAT_WEBP));
        assertFalse(MediaDetector.animatesHere(MediaTags.FORMAT_HEIF));
    }

    @Test
    public void fromAndroid9WebpAnimatesButAvifWaitsFor12() {
        assertTrue(MediaDetector.animatesHere(MediaTags.FORMAT_WEBP));
        assertFalse(MediaDetector.animatesHere(MediaTags.FORMAT_AVIF));
    }

    @Test
    @Config(sdk = 31)
    public void fromAndroid12AvifAnimates() {
        assertTrue(MediaDetector.animatesHere(MediaTags.FORMAT_AVIF));
    }

    /** A QuickTime movie Android would not open plays from a copy that it will. */
    @Test
    public void aQuickTimeMovieWithoutAFileTypePlaysFromACopy() throws IOException {
        Context context = ApplicationProvider.getApplicationContext();
        // A movie whose file type box gives way to the wide box an old QuickTime movie opens with.
        byte[] mp4 = MediaTestFiles.movie();
        int ftyp = ((mp4[0] & 0xFF) << 24) | ((mp4[1] & 0xFF) << 16) | ((mp4[2] & 0xFF) << 8)
                | (mp4[3] & 0xFF);
        byte[] rest = new byte[mp4.length - ftyp];
        System.arraycopy(mp4, ftyp, rest, 0, rest.length);
        File movie = file(MediaTestFiles.concat(new byte[] {0, 0, 0, 8, 'w', 'i', 'd', 'e'},
                rest), ".mov");
        MediaDetector.Detection detection = MediaDetector.detect(context,
                ImageLocation.of(movie));
        assertEquals(MediaView.VIDEO, detection.type);
        assertNotNull(detection.playable);
        assertTrue(detection.playable.file().getName().endsWith(".mov"));
        assertNull(MediaDetector.detect(context, ImageLocation.of(
                file(MediaTestFiles.movie(), ".mp4"))).playable);
    }

    /** Draws without anything to show, which draws nothing. */
    @Test
    public void drawsNothingWhenEmpty() {
        Bitmap bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888);
        mView.draw(new Canvas(bitmap));
        assertEquals(0, bitmap.getPixel(200, 299));
        assertEquals(View.VISIBLE, mView.getVisibility());
    }
}
