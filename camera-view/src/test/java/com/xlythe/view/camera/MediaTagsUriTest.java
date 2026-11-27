package com.xlythe.view.camera;

import static com.xlythe.view.camera.MediaFixtures.exifSegment;
import static com.xlythe.view.camera.MediaFixtures.jpeg;
import static com.xlythe.view.camera.MediaFixtures.tiff;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Content Uris: written where the app may write them, through a copy that goes back over the
 * Uri on saving, and read only everywhere else.
 */
@RunWith(RobolectricTestRunner.class)
public class MediaTagsUriTest {
    /** The authority files are served from, writably. */
    private static final String WRITABLE = "com.xlythe.test.writable";

    /** The authority files are served from, for reading only. */
    private static final String READ_ONLY = "com.xlythe.test.readonly";

    /** The file the providers serve. */
    static File sFile;

    /** Serves the one file, opened in whatever mode is asked. */
    public static class FileProvider extends ContentProvider {
        @Override
        public boolean onCreate() {
            return true;
        }

        @Nullable
        @Override
        public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode)
                throws FileNotFoundException {
            return ParcelFileDescriptor.open(sFile, ParcelFileDescriptor.parseMode(mode));
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

    /** Serves the file to read, and refuses to open it to write, as for another app's photo. */
    public static class ReadOnlyProvider extends FileProvider {
        @Nullable
        @Override
        public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode)
                throws FileNotFoundException {
            if (mode.contains("w")) {
                throw new SecurityException("Not the app's to write");
            }
            return super.openFile(uri, mode);
        }
    }

    private Context mContext;

    @Before
    public void setUp() throws IOException {
        mContext = ApplicationProvider.getApplicationContext();
        sFile = File.createTempFile("served", ".jpg");
        sFile.deleteOnExit();
        Files.write(sFile.toPath(), jpeg(exifSegment(tiff())));
        Robolectric.buildContentProvider(FileProvider.class).create(WRITABLE);
        Robolectric.buildContentProvider(ReadOnlyProvider.class).create(READ_ONLY);
    }

    private static Uri uri(String authority) {
        return Uri.parse("content://" + authority + "/photo/1");
    }

    @Test
    public void aWritableUriIsEditedAndWrittenBack() throws IOException {
        MediaTags tags = MediaTags.open(mContext, uri(WRITABLE));
        assertFalse(tags.isReadOnly());
        assertTrue(tags.setTitle("Harbour"));
        assertTrue(tags.setXmp(Xmp.NS_DC, "source", "Camera"));
        tags.save();
        assertEquals("Harbour", tags.getTitle());

        MediaTags again = MediaTags.open(sFile);
        assertEquals("Harbour", again.getTitle());
        assertEquals("Camera", again.getXmp(Xmp.NS_DC, "source"));
        // The copy the edit was made in is gone.
        File[] copies = new File(mContext.getCacheDir(), "cameraview-tags").listFiles();
        assertEquals(0, copies == null ? 0 : copies.length);

        // A second edit copies afresh, and the file gets shorter as well as longer.
        assertTrue(tags.setTitle(null));
        tags.save();
        assertNull(MediaTags.open(sFile).getTitle());
    }

    @Test
    public void aUriNobodyEditedSavesNothing() throws IOException {
        long modified = sFile.lastModified();
        byte[] before = Files.readAllBytes(sFile.toPath());
        MediaTags tags = MediaTags.open(mContext, uri(WRITABLE));
        tags.save();
        assertEquals(modified, sFile.lastModified());
        assertArrayEquals(before, Files.readAllBytes(sFile.toPath()));
    }

    @Test
    public void aUriTheAppMayNotWriteIsReadOnly() throws IOException {
        MediaTags tags = MediaTags.open(mContext, uri(READ_ONLY));
        assertTrue(tags.isReadOnly());
        assertFalse(tags.setTitle("Harbour"));
        assertFalse(tags.setCaptureTime(0));
        assertFalse(tags.setOrientation(90, false));
        assertFalse(tags.setCoverArt(new byte[] {1}));
        assertFalse(tags.setXmp(Xmp.NS_DC, "source", "Camera"));
        tags.save();
        assertNull(MediaTags.open(sFile).getTitle());
    }

    @Test
    public void aFormatThisNeverWritesIsReadOnlyBehindAnyUri() throws IOException {
        Files.write(sFile.toPath(), MatroskaContainerTest.sphericalWebm());
        MediaTags tags = MediaTags.open(mContext, uri(WRITABLE));
        assertTrue(tags.isReadOnly());
        assertFalse(tags.setTitle("Lake"));
    }

    @Test
    public void copiesLeftBehindAreSweptAway() throws IOException {
        File directory = new File(mContext.getCacheDir(), "cameraview-tags");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File stale = new File(directory, "edit-old.tmp");
        assertTrue(stale.createNewFile());
        assertTrue(stale.setLastModified(System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000));
        File fresh = new File(directory, "edit-new.tmp");
        assertTrue(fresh.createNewFile());

        MediaTags tags = MediaTags.open(mContext, uri(WRITABLE));
        assertTrue(tags.setTitle("Begins an edit"));
        assertFalse(stale.exists());
        assertTrue(fresh.exists());
    }

    @Test
    public void copyingIntoAUriWritesItBack() throws IOException {
        File source = File.createTempFile("source", ".jpg");
        source.deleteOnExit();
        Files.write(source.toPath(), jpeg(exifSegment(tiff())));
        MediaTags from = MediaTags.open(source);
        assertTrue(from.setAuthor("Ines"));
        from.save();

        MediaTags to = MediaTags.open(mContext, uri(WRITABLE));
        assertTrue(MediaTags.open(source).copyTo(to));
        to.save();
        assertEquals("Ines", MediaTags.open(sFile).getAuthor());
        assertFalse(MediaTags.open(source).copyTo(MediaTags.open(mContext, uri(READ_ONLY))));
    }
}
