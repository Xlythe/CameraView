package com.xlythe.view.camera.viewer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Opening pictures for the viewers: size, orientation and sphere metadata from real files, and
 * the copy made of a picture that cannot be decoded in pieces.
 *
 * <p>Robolectric's stand-in for the region decoder is the one before API 31. It reports the real
 * size of what it opens but decodes nothing, so the pixels, and what a decoder refuses, are
 * checked on a device.</p>
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = 30)
public class ImageSourcesTest {
    private Context mContext;
    private File mDirectory;

    @Before
    public void setUp() throws IOException {
        mContext = ApplicationProvider.getApplicationContext();
        mDirectory = Files.createTempDirectory("sources").toFile();
        File copies = new File(mContext.getCacheDir(), ImageSources.CACHE_DIRECTORY);
        File[] old = copies.listFiles();
        if (old != null) {
            for (File file : old) {
                assertTrue(file.delete());
            }
        }
    }

    /** A panorama from the sweep, turned a quarter: its size, its turn and its sphere. */
    @Test
    public void opensAPictureWithItsMetadata() throws IOException {
        File file = jpeg("pano.jpg", 3032, 532, GPanoTest.SWEEP_PANORAMA_XMP);
        orient(file, ExifInterface.ORIENTATION_ROTATE_90);

        ImageSources.Opened opened = ImageSources.open(mContext, ImageLocation.of(file));

        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, opened.orientation);
        assertNotNull(opened.gpano);
        assertTrue(opened.gpano.isFullCircle());
        assertEquals(3032, opened.source.getWidth());
        assertEquals(532, opened.source.getHeight());
        assertNotNull(opened.source.decode(new Rect(0, 0, 1024, 512), 2));

        opened.source.close();
        assertNull(opened.source.decode(new Rect(0, 0, 10, 10), 1));
        // Closing twice is harmless.
        opened.source.close();
    }

    @Test
    public void aPlainPictureHasNoSphereAndIsUpright() throws IOException {
        File file = jpeg("plain.jpg", 640, 480, null);

        ImageSources.Opened opened = ImageSources.open(mContext, ImageLocation.of(file));

        assertEquals(ExifInterface.ORIENTATION_NORMAL, opened.orientation);
        assertNull(opened.gpano);
        opened.source.close();
    }

    /** An orientation Exif does not define is taken as upright. */
    @Test
    public void aNonsenseOrientationIsUpright() throws IOException {
        File file = jpeg("odd.jpg", 64, 48, null);
        ExifInterface exif = new ExifInterface(file.getPath());
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, "12");
        exif.saveAttributes();

        ImageSources.Opened opened = ImageSources.open(mContext, ImageLocation.of(file));

        assertEquals(ExifInterface.ORIENTATION_NORMAL, opened.orientation);
        opened.source.close();
    }

    @Test
    public void aMissingFileIsNotAPicture() {
        assertThrows(FileNotFoundException.class,
                () -> ImageSources.open(mContext, ImageLocation.of(new File(mDirectory, "none"))));
    }

    /** A picture behind a content Uri is read through its provider. */
    @Test
    public void opensAContentUri() throws IOException {
        File file = jpeg("shared.jpg", 800, 400, GPanoTest.SWEEP_PANORAMA_XMP);
        PictureProvider.sFile = file;
        Robolectric.buildContentProvider(PictureProvider.class).create("viewer.test");
        Uri uri = Uri.parse("content://viewer.test/shared.jpg");

        ImageLocation location = ImageLocation.of(uri);
        ImageSources.Opened opened = ImageSources.open(mContext, location);

        assertEquals(800, opened.source.getWidth());
        assertNotNull(opened.gpano);
        assertEquals(uri + "|" + file.length(), location.fingerprint(mContext));
        assertEquals(uri.toString(), location.toString());
        opened.source.close();

        // A Uri its provider will not open is named by the Uri alone.
        PictureProvider.sFile = new File(mDirectory, "gone.jpg");
        assertEquals(uri + "|-1", location.fingerprint(mContext));
    }

    @Test
    public void locationsCompareByWhatTheyName() {
        File file = new File(mDirectory, "a.jpg");
        assertEquals(ImageLocation.of(file), ImageLocation.of(Uri.fromFile(file)));
        assertEquals(ImageLocation.of(file).hashCode(),
                ImageLocation.of(Uri.fromFile(file)).hashCode());
        assertEquals(file.toString(), ImageLocation.of(file).toString());
        Uri uri = Uri.parse("content://viewer.test/a.jpg");
        assertEquals(ImageLocation.of(uri), ImageLocation.of(Uri.parse(uri.toString())));
        assertEquals(uri.hashCode(), ImageLocation.of(uri).hashCode());
        assertNotEquals(ImageLocation.of(uri), ImageLocation.of(file));
        assertNotEquals(ImageLocation.of(file), ImageLocation.of(uri));
        assertNotEquals(ImageLocation.of(file), "a.jpg");
    }

    /**
     * A picture that cannot be decoded in pieces is decoded whole once and copied to the cache,
     * and viewed in its own pixels through the copy. Asked for again, the copy is found.
     */
    @Test
    public void copiesWhatCannotBeDecodedInPieces() throws IOException {
        File file = jpeg("raw.jpg", 1200, 800, null);
        ImageLocation location = ImageLocation.of(file);

        RegionSource source = ImageSources.copy(mContext, location, 1200, 800,
                ImageSources.MAX_COPY_PIXELS);

        assertEquals(1200, source.getWidth());
        assertEquals(800, source.getHeight());
        File[] copies = copies();
        assertEquals(1, copies.length);
        assertEquals(new int[] {1200, 800}[0], bounds(copies[0])[0]);
        assertNotNull(source.decode(new Rect(600, 400, 1200, 800), 2));
        // Outside the picture there is nothing to decode.
        assertNull(source.decode(new Rect(1300, 0, 1400, 100), 1));
        source.close();

        long written = copies[0].lastModified();
        assertTrue(copies[0].setLastModified(written - 10_000));
        source = ImageSources.copy(mContext, location, 1200, 800, ImageSources.MAX_COPY_PIXELS);
        assertEquals(written - 10_000, copies()[0].lastModified());
        source.close();
    }

    /**
     * A picture over the limit is copied at the largest power-of-two fraction that fits, and a
     * piece asked for in more detail than the copy has comes back at the detail it does have.
     */
    @Test
    public void aCopyOverTheLimitIsShrunk() throws IOException {
        File file = jpeg("big.jpg", 1600, 1200, null);

        RegionSource source = ImageSources.copy(mContext, ImageLocation.of(file), 1600, 1200,
                1600L * 1200 / 4);

        assertEquals(1600, source.getWidth());
        int[] copy = bounds(copies()[0]);
        assertEquals(800, copy[0]);
        assertEquals(600, copy[1]);
        source.close();
    }

    /**
     * A copy at half size answers in the original's pixels: a piece is fetched from where it
     * falls in the copy, at the detail the copy has.
     */
    @Test
    public void aSmallerCopyAnswersInTheOriginalsPixels() {
        FakeRegionSource copy = new FakeRegionSource(800, 600, 0xFFFF0000);
        ScaledRegionSource source = new ScaledRegionSource(copy, 1600, 1200);

        assertEquals(1600, source.getWidth());
        assertEquals(1200, source.getHeight());
        Bitmap whole = source.decode(new Rect(0, 0, 1600, 1200), 1);
        assertEquals(800, whole.getWidth());
        assertEquals(new Rect(0, 0, 800, 600), copy.decodes().get(0).region);
        Bitmap quarter = source.decode(new Rect(0, 0, 1600, 1200), 4);
        assertEquals(400, quarter.getWidth());
        assertEquals(2, copy.decodes().get(1).sampleSize);
        // A corner pixel rounds out to the copy pixel it falls in.
        source.decode(new Rect(1599, 1199, 1600, 1200), 1);
        assertEquals(new Rect(799, 599, 800, 600), copy.decodes().get(2).region);
        assertNull(source.decode(new Rect(1700, 0, 1800, 100), 1));

        source.close();
        assertTrue(copy.isClosed());
    }

    /** Only the newest copies are kept, and a replaced picture is copied afresh. */
    @Test
    public void keepsOnlyTheNewestCopies() throws IOException {
        for (int i = 0; i < ImageSources.MAX_COPIES + 2; i++) {
            File file = jpeg("raw" + i + ".jpg", 100 + i, 100, null);
            File[] before = copies();
            for (File copy : before) {
                // Older than whatever comes next, whatever the clock's resolution.
                assertTrue(copy.setLastModified(copy.lastModified() - 60_000));
            }
            ImageSources.copy(mContext, ImageLocation.of(file), 100 + i, 100,
                    ImageSources.MAX_COPY_PIXELS).close();
        }
        assertEquals(ImageSources.MAX_COPIES, copies().length);

        File file = jpeg("raw0.jpg", 300, 300, null);
        ImageSources.copy(mContext, ImageLocation.of(file), 300, 300,
                ImageSources.MAX_COPY_PIXELS).close();
        boolean found = false;
        for (File copy : copies()) {
            found |= bounds(copy)[0] == 300;
        }
        assertTrue(found);
    }

    @Test
    public void aCopyOfNothingFails() {
        File file = new File(mDirectory, "missing.dng");
        assertThrows(IOException.class, () -> ImageSources.copy(mContext, ImageLocation.of(file),
                100, 100, ImageSources.MAX_COPY_PIXELS));
        assertFalse(copies().length > 0);
    }

    private File[] copies() {
        File[] copies = new File(mContext.getCacheDir(), ImageSources.CACHE_DIRECTORY).listFiles();
        return copies == null ? new File[0] : copies;
    }

    private static int[] bounds(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        return new int[] {options.outWidth, options.outHeight};
    }

    /** A real JPEG, with an XMP packet if one is given. */
    private File jpeg(String name, int width, int height, @Nullable String xmp)
            throws IOException {
        Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        image.eraseColor(0xFFFF8000);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        assertTrue(image.compress(Bitmap.CompressFormat.JPEG, 90, encoded));
        byte[] jpeg = encoded.toByteArray();
        File file = new File(mDirectory, name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(jpeg, 0, 2);
            if (xmp != null) {
                byte[] payload = ("http://ns.adobe.com/xap/1.0/\u0000" + xmp)
                        .getBytes(StandardCharsets.UTF_8);
                int length = payload.length + 2;
                out.write(new byte[] {(byte) 0xFF, (byte) 0xE1, (byte) (length >> 8),
                        (byte) length});
                out.write(payload);
            }
            out.write(jpeg, 2, jpeg.length - 2);
        }
        return file;
    }

    private static void orient(File file, int orientation) throws IOException {
        ExifInterface exif = new ExifInterface(file.getPath());
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(orientation));
        exif.saveAttributes();
    }

    /** Serves one file, as a gallery's provider would. */
    public static class PictureProvider extends ContentProvider {
        static File sFile;

        @Override
        public boolean onCreate() {
            return true;
        }

        @Nullable
        @Override
        public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode)
                throws FileNotFoundException {
            return ParcelFileDescriptor.open(sFile, ParcelFileDescriptor.MODE_READ_ONLY);
        }

        @Nullable
        @Override
        public Cursor query(@NonNull Uri uri, @Nullable String[] projection,
                @Nullable String selection, @Nullable String[] selectionArgs,
                @Nullable String sortOrder) {
            return null;
        }

        @Nullable
        @Override
        public String getType(@NonNull Uri uri) {
            return "image/jpeg";
        }

        @Nullable
        @Override
        public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
            return null;
        }

        @Override
        public int delete(@NonNull Uri uri, @Nullable String selection,
                @Nullable String[] selectionArgs) {
            return 0;
        }

        @Override
        public int update(@NonNull Uri uri, @Nullable ContentValues values,
                @Nullable String selection, @Nullable String[] selectionArgs) {
            return 0;
        }
    }
}
