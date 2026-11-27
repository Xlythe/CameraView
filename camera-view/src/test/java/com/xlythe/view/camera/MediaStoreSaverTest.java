package com.xlythe.view.camera;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowLooper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Where a capture is filed so the gallery shows it. */
@RunWith(RobolectricTestRunner.class)
public class MediaStoreSaverTest {
    private static final byte[] CONTENTS = {1, 2, 3, 4, 5, 6, 7, 8};

    /** A different payload for the DNG, so a test can tell which file landed where. */
    private static final byte[] DNG_CONTENTS = {9, 8, 7, 6};

    private Application context;
    private File capture;

    /** The DNG a RAW_JPEG picture writes beside {@link #capture}, not written until a test does. */
    private File dng;

    @Before
    public void setUp() throws IOException {
        context = RuntimeEnvironment.getApplication();
        CaptureDelivery.allowDngInMediaStore();
        capture = new File(context.getCacheDir(), "2026-11-26-10-15-00-000.jpg");
        try (FileOutputStream output = new FileOutputStream(capture)) {
            output.write(CONTENTS);
        }
        dng = new File(context.getCacheDir(), "2026-11-26-10-15-00-000.dng");
    }

    /** From API 29 through MediaStore: a pending row, the bytes, then the row published. */
    @Test
    @Config(sdk = {29, 37})
    public void aPictureIsInsertedPendingAndThenPublished() throws Exception {
        ShadowContentResolver resolver = Shadows.shadowOf(context.getContentResolver());

        Uri uri = saveCapture(false /* isVideo */);

        assertNotNull(uri);
        assertTrue(uri.toString().startsWith(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString()));
        ContentValues inserted = resolver.getInsertStatements().get(0).getContentValues();
        assertEquals(capture.getName(), inserted.getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertEquals("image/jpeg", inserted.getAsString(MediaStore.MediaColumns.MIME_TYPE));
        assertEquals(Environment.DIRECTORY_PICTURES + "/CameraView",
                inserted.getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
        assertEquals(1, (int) inserted.getAsInteger(MediaStore.MediaColumns.IS_PENDING));

        try (InputStream copied = context.getContentResolver().openInputStream(uri)) {
            assertNotNull(copied);
            assertArrayEquals(CONTENTS, readAll(copied));
        }
        List<ShadowContentResolver.UpdateStatement> updates = resolver.getUpdateStatements();
        assertEquals(1, updates.size());
        assertEquals(0, (int) updates.get(0).getContentValues()
                .getAsInteger(MediaStore.MediaColumns.IS_PENDING));
        assertFalse("the cache file goes once it is filed", capture.exists());
    }

    @Test
    @Config(sdk = 37)
    public void aVideoGoesToMovies() throws Exception {
        ShadowContentResolver resolver = Shadows.shadowOf(context.getContentResolver());

        Uri uri = saveCapture(true /* isVideo */);

        assertNotNull(uri);
        assertTrue(uri.toString().startsWith(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString()));
        ContentValues inserted = resolver.getInsertStatements().get(0).getContentValues();
        assertEquals("video/mp4", inserted.getAsString(MediaStore.MediaColumns.MIME_TYPE));
        assertEquals(Environment.DIRECTORY_MOVIES + "/CameraView",
                inserted.getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
    }

    /** A copy that fails takes its pending row with it, or the row would stay invisible. */
    @Test
    @Config(sdk = 37)
    public void aFailedCopyLeavesNoRowBehind() throws Exception {
        ShadowContentResolver resolver = Shadows.shadowOf(context.getContentResolver());
        assertTrue(capture.delete());

        assertNull(saveCapture(false /* isVideo */));
        assertEquals(1, resolver.getInsertStatements().size());
        assertEquals(1, resolver.getDeletedUris().size());
    }

    /** Before API 29 the copy goes straight into the public directory. */
    @Test
    @Config(sdk = 28)
    public void beforeScopedStorageACopyLandsInThePublicDirectory() throws Exception {
        Uri uri = saveCapture(false /* isVideo */);

        assertNotNull(uri);
        File copied = new File(uri.getPath());
        assertEquals(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                copied.getParentFile());
        assertArrayEquals(CONTENTS, Files.readAllBytes(copied.toPath()));
        assertFalse(capture.exists());
    }

    /** A capture MediaStore will not take is lost either way, so its cache file still goes. */
    @Test
    @Config(sdk = 37)
    public void aRefusedCaptureStillLosesItsCacheFile() throws Exception {
        Robolectric.buildContentProvider(CameraViewFilingTest.RefusingMediaProvider.class)
                .create(MediaStore.AUTHORITY);

        assertNull(saveCapture(false /* isVideo */));
        assertFalse(capture.exists());
    }

    /** A DNG is filed as one, under its own name, so the gallery never calls it a JPEG. */
    @Test
    @Config(sdk = {29, 37})
    public void aDngIsFiledAsADng() throws Exception {
        ShadowContentResolver resolver = Shadows.shadowOf(context.getContentResolver());
        write(dng, DNG_CONTENTS);

        List<Uri> uris = save(Collections.singletonList(dng), false /* isVideo */);

        assertNotNull(uris);
        assertEquals(1, uris.size());
        ContentValues inserted = resolver.getInsertStatements().get(0).getContentValues();
        assertEquals(dng.getName(), inserted.getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertEquals("image/x-adobe-dng",
                inserted.getAsString(MediaStore.MediaColumns.MIME_TYPE));
        assertEquals(Environment.DIRECTORY_PICTURES + "/CameraView",
                inserted.getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
        assertFalse(dng.exists());
    }

    @Test
    public void theTypeFollowsTheExtension() {
        assertEquals(MediaStoreSaver.MIME_TYPE_JPEG,
                MediaStoreSaver.mimeType(new File("IMG_1.jpg"), false /* isVideo */));
        assertEquals(MediaStoreSaver.MIME_TYPE_DNG,
                MediaStoreSaver.mimeType(new File("IMG_1.dng"), false /* isVideo */));
        assertEquals(MediaStoreSaver.MIME_TYPE_DNG,
                MediaStoreSaver.mimeType(new File("IMG_1.DNG"), false /* isVideo */));
        assertEquals("video/mp4", MediaStoreSaver.mimeType(new File("VID_1.mp4"), true));
    }

    /**
     * Two files of one capture are filed in the order given, each under its own name and type,
     * and reported together.
     */
    @Test
    @Config(sdk = {29, 37})
    public void twoFilesAreFiledInOrder() throws Exception {
        ShadowContentResolver resolver = Shadows.shadowOf(context.getContentResolver());
        write(dng, DNG_CONTENTS);

        List<Uri> uris = save(Arrays.asList(capture, dng), false /* isVideo */);

        assertNotNull(uris);
        assertEquals(2, uris.size());
        List<ShadowContentResolver.InsertStatement> inserts = resolver.getInsertStatements();
        assertEquals(2, inserts.size());
        assertEquals(capture.getName(), inserts.get(0).getContentValues()
                .getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertEquals("image/jpeg", inserts.get(0).getContentValues()
                .getAsString(MediaStore.MediaColumns.MIME_TYPE));
        assertEquals(dng.getName(), inserts.get(1).getContentValues()
                .getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertEquals("image/x-adobe-dng", inserts.get(1).getContentValues()
                .getAsString(MediaStore.MediaColumns.MIME_TYPE));
        try (InputStream first = context.getContentResolver().openInputStream(uris.get(0));
             InputStream second = context.getContentResolver().openInputStream(uris.get(1))) {
            assertNotNull(first);
            assertNotNull(second);
            assertArrayEquals("the JPEG is reported first", CONTENTS, readAll(first));
            assertArrayEquals(DNG_CONTENTS, readAll(second));
        }
        assertEquals("both rows are published", 2, resolver.getUpdateStatements().size());
        assertFalse(capture.exists());
        assertFalse(dng.exists());
    }

    /**
     * A capture that loses its second file is filed as neither: the first row, already copied,
     * goes with the second, and neither is ever published.
     */
    @Test
    @Config(sdk = 37)
    public void aCaptureMissingItsSecondFileLeavesNoRowBehind() throws Exception {
        ShadowContentResolver resolver = Shadows.shadowOf(context.getContentResolver());

        assertNull(save(Arrays.asList(capture, dng), false /* isVideo */));

        assertEquals(2, resolver.getInsertStatements().size());
        assertEquals(2, resolver.getDeletedUris().size());
        assertTrue("nothing was published", resolver.getUpdateStatements().isEmpty());
        assertFalse("the cache copy goes either way", capture.exists());
    }

    /** Before API 29 both files are copied into the public directory, in order. */
    @Test
    @Config(sdk = 28)
    public void beforeScopedStorageTwoFilesAreCopiedInOrder() throws Exception {
        write(dng, DNG_CONTENTS);

        List<Uri> uris = save(Arrays.asList(capture, dng), false /* isVideo */);

        assertNotNull(uris);
        assertEquals(2, uris.size());
        File pictures =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        assertEquals(new File(pictures, capture.getName()), new File(uris.get(0).getPath()));
        assertEquals(new File(pictures, dng.getName()), new File(uris.get(1).getPath()));
        assertArrayEquals(DNG_CONTENTS,
                Files.readAllBytes(new File(uris.get(1).getPath()).toPath()));
        assertFalse(capture.exists());
        assertFalse(dng.exists());
    }

    /** And a capture missing its second file there leaves no copy of the first behind. */
    @Test
    @Config(sdk = 28)
    public void beforeScopedStorageACaptureMissingItsSecondFileLeavesNoCopy() throws Exception {
        assertNull(save(Arrays.asList(capture, dng), false /* isVideo */));

        File pictures =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        assertFalse(new File(pictures, capture.getName()).exists());
        assertFalse(new File(pictures, dng.getName()).exists());
        assertFalse(capture.exists());
    }

    private static void write(File file, byte[] contents) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(contents);
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[64];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    /** Saves the capture alone and waits for where it went, or null. */
    private Uri saveCapture(boolean isVideo) throws InterruptedException {
        List<Uri> uris = save(Collections.singletonList(capture), isVideo);
        if (uris == null) {
            return null;
        }
        assertEquals(1, uris.size());
        return uris.get(0);
    }

    /** Saves the files together and waits for the answer, which arrives on the main thread. */
    private List<Uri> save(List<File> files, boolean isVideo) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<List<Uri>> result = new AtomicReference<>();
        MediaStoreSaver.save(context, files, isVideo, uris -> {
            result.set(uris);
            done.countDown();
        });
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while (done.getCount() > 0 && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper();
            done.await(10, TimeUnit.MILLISECONDS);
        }
        assertEquals("the save never reported", 0, done.getCount());
        return result.get();
    }
}
