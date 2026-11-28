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
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaFormat;
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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowMediaCodecList;
import org.robolectric.shadows.ShadowMediaExtractor;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.util.DataSource;

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
        ShadowMediaCodecList.reset();
        ShadowMediaExtractor.reset();
        ShadowMediaPlayer.resetStaticState();
        // Robolectric's extractor knows a file by a name; every file here is the one source.
        // Set after the player's reset, which clears it.
        DataSource.setFileDescriptorTransform((descriptor, offset) -> SOURCE);
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

    @After
    public void tearDown() {
        ShadowMediaCodecList.reset();
        ShadowMediaExtractor.reset();
        DataSource.reset();
    }

    /** What Robolectric's extractor knows every file here as. */
    private static final String SOURCE = "media-view-test";

    /** Gives every file a video track of a format, which no decoder here takes. */
    private static void videoTrackWithoutADecoder(String mime) {
        ShadowMediaExtractor.addTrack(DataSource.toDataSource(SOURCE),
                MediaFormat.createVideoFormat(mime, 64, 48), new byte[0]);
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
        assertTrue(mView.hasFailed());
    }

    /**
     * A video no decoder here takes fails before it plays, rather than playing its sound over
     * nothing, and shows the error placeholder.
     */
    @Test
    @Config(sdk = 30)
    public void aVideoNoDecoderTakesFails() throws IOException {
        videoTrackWithoutADecoder("video/av01");
        show(file(MediaTestFiles.movie(), ".mp4"));
        assertTrue(mRecorder.failed);
        assertEquals(-1, mRecorder.loaded);
        assertEquals(MediaView.VIDEO, mView.getMediaType());
        assertNull(mView.getContentView());
        assertTrue(mView.hasFailed());
        assertFalse(mView.play());

        // A 360° video too.
        mRecorder.failed = false;
        show(file(MediaTestFiles.sphericalMovie(), ".mp4"));
        assertTrue(mRecorder.failed);
        assertNull(mView.getContentView());
    }

    /**
     * What showed and then cannot play is reported failed and shows it, as a video does when
     * Android 4.0's software decoder gives up on H.264 High with (1, UNKNOWN_ERROR).
     */
    @Test
    public void whatShowedThenCannotPlayShowsItFailed() throws IOException {
        ShadowMediaPlayer[] created = new ShadowMediaPlayer[1];
        ShadowMediaPlayer.setCreateListener((player, shadow) -> created[0] = shadow);
        show(file(MediaTestFiles.mp3WithCover(null), ".mp3"));
        ShadowLooper.idleMainLooper();
        assertEquals(MediaView.AUDIO, mRecorder.loaded);
        assertTrue(mView.performClick());
        assertTrue(mView.isPlaying());
        assertFalse(mView.hasFailed());

        created[0].invokeErrorListener(1, Integer.MIN_VALUE);
        assertTrue(mRecorder.failed);
        assertTrue(mView.hasFailed());
        assertFalse(mView.isPlaying());
        assertNull(mView.getContentView());
        assertEquals(MediaView.AUDIO, mView.getMediaType());

        // Reported once; a new file starts afresh.
        mRecorder.failed = false;
        created[0].invokeErrorListener(1, Integer.MIN_VALUE);
        assertFalse(mRecorder.failed);
        show(file(jpeg(64, 48, null), ".jpg"));
        assertFalse(mView.hasFailed());
    }

    /** A motion photo whose clip no decoder here takes stays on its still when held. */
    @Test
    @Config(sdk = 30)
    public void aMotionPhotoWhoseClipCannotPlayStaysStill() throws IOException {
        videoTrackWithoutADecoder("video/hevc");
        byte[] video = MediaTestFiles.mp4Clip(500);
        String packet = MediaTestFiles.motionPhotoXmp(video.length);
        show(file(MediaTestFiles.concat(jpeg(64, 48, packet), video), ".jpg"));
        assertEquals(MediaView.MOTION_PHOTO, mView.getMediaType());
        assertFalse(mRecorder.failed);
        mView.performLongClick();
        assertFalse(hasClip());
    }

    /** The error placeholder is drawn in the middle, at its own size; it can be taken away. */
    @Test
    public void theErrorPlaceholderCanBeReplacedOrTakenAway() {
        assertNotNull(mView.getErrorPlaceholder());
        Recording replacement = new Recording(40, 20);
        mView.setErrorPlaceholder(replacement);
        assertEquals(replacement, mView.getErrorPlaceholder());
        show(new File("/nowhere/to/be/found.jpg"));
        mView.draw(new Canvas(Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)));
        assertEquals(new Rect(180, 140, 220, 160), replacement.drawnAt);

        // Larger than the view, it is shrunk to fit, keeping its shape.
        Recording large = new Recording(800, 300);
        mView.setErrorPlaceholder(large);
        mView.draw(new Canvas(Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)));
        assertEquals(new Rect(0, 75, 400, 225), large.drawnAt);

        mView.setErrorPlaceholder(null);
        assertNull(mView.getErrorPlaceholder());
        mView.draw(new Canvas(Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)));
    }

    /** The library's own error icon: in colorControlNormal, in the middle, nowhere else. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void theLibrarysErrorIconIsDrawnInTheMiddle() {
        show(new File("/nowhere/to/be/found.jpg"));
        Bitmap bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888);
        mView.draw(new Canvas(bitmap));
        // The icon is a quarter of the shorter side, 75 px, around the middle: its frame's left
        // edge, and the line across it, are drawn; the corners of the view are not.
        int drawn = 0;
        for (int x = 160; x < 240; x++) {
            for (int y = 110; y < 190; y++) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 0) {
                    drawn++;
                }
            }
        }
        assertTrue("the icon should be drawn, " + drawn + " pixels were", drawn > 300);
        assertTrue(Color.alpha(bitmap.getPixel(200, 150)) > 0);
        assertEquals(0, bitmap.getPixel(5, 5));
        assertEquals(0, bitmap.getPixel(395, 295));
        assertEquals(0, bitmap.getPixel(100, 150));
    }

    /** Draws nothing, and remembers where it was asked to. */
    private static final class Recording extends Drawable {
        private final int mWidth;
        private final int mHeight;
        Rect drawnAt;

        Recording(int width, int height) {
            mWidth = width;
            mHeight = height;
        }

        @Override
        public void draw(Canvas canvas) {
            drawnAt = new Rect(getBounds());
        }

        @Override
        public int getIntrinsicWidth() {
            return mWidth;
        }

        @Override
        public int getIntrinsicHeight() {
            return mHeight;
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(ColorFilter filter) {}

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
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
                .addAttribute(com.xlythe.view.camera.R.attr.errorPlaceholder, "@null")
                .build();
        MediaView view = new MediaView(context, attributes);
        // @null takes the library's icons away; unset, they stay.
        assertNull(view.getErrorPlaceholder());
        assertNotNull(new MediaView(context, Robolectric.buildAttributeSet().build())
                .getErrorPlaceholder());
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

    /** A MIDI file is sound, though the platform's retriever may say nothing of it. */
    @Test
    public void aMidiFileIsSound() throws IOException {
        // A header chunk for one track at 96 ticks a beat, and a track that only ends.
        byte[] midi = {'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 0, 0, 1, 0, 96,
                'M', 'T', 'r', 'k', 0, 0, 0, 4, 0, (byte) 0xFF, 0x2F, 0};
        assertEquals(MediaView.AUDIO, MediaDetector.detect(ApplicationProvider
                .getApplicationContext(), ImageLocation.of(file(midi, ".mid"))).type);
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
