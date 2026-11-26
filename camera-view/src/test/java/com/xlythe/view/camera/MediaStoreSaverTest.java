package com.xlythe.view.camera;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Where a capture is filed so the gallery shows it. */
@RunWith(RobolectricTestRunner.class)
public class MediaStoreSaverTest {
    private static final byte[] CONTENTS = {1, 2, 3, 4, 5, 6, 7, 8};

    private Application context;
    private File capture;

    @Before
    public void setUp() throws IOException {
        context = RuntimeEnvironment.getApplication();
        capture = new File(context.getCacheDir(), "2026-11-26 10.15.00.jpg");
        try (FileOutputStream output = new FileOutputStream(capture)) {
            output.write(CONTENTS);
        }
    }

    /** From API 29 through MediaStore: a pending row, the bytes, then the row published. */
    @Test
    @Config(sdk = {29, 37})
    public void aPictureIsInsertedPendingAndThenPublished() throws Exception {
        ShadowContentResolver resolver = Shadows.shadowOf(context.getContentResolver());

        Uri uri = save(false /* isVideo */);

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
        assertTrue("the capture itself is left for the caller", capture.exists());
    }

    @Test
    @Config(sdk = 37)
    public void aVideoGoesToMovies() throws Exception {
        ShadowContentResolver resolver = Shadows.shadowOf(context.getContentResolver());

        Uri uri = save(true /* isVideo */);

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

        assertNull(save(false /* isVideo */));
        assertEquals(1, resolver.getInsertStatements().size());
        assertEquals(1, resolver.getDeletedUris().size());
    }

    /** Before API 29 the copy goes straight into the public directory. */
    @Test
    @Config(sdk = 28)
    public void beforeScopedStorageACopyLandsInThePublicDirectory() throws Exception {
        Uri uri = save(false /* isVideo */);

        assertNotNull(uri);
        File copied = new File(uri.getPath());
        assertEquals(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                copied.getParentFile());
        assertArrayEquals(CONTENTS, Files.readAllBytes(copied.toPath()));
        assertTrue(capture.exists());
    }

    /** A timestamped name keeps its shape, without the colons some filesystems refuse. */
    @Test
    @Config(sdk = 37)
    public void aNameLosesTheCharactersFilesystemsReject() {
        assertEquals("2026-11-26 10-15-00.jpg",
                MediaStoreSaver.displayName(new File("2026-11-26 10:15:00.jpg")));
        assertEquals("a-b-c.mp4", MediaStoreSaver.displayName(new File("a?b*c.mp4")));
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

    /** Saves the capture and waits for the answer, which arrives on the main thread. */
    private Uri save(boolean isVideo) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Uri> result = new AtomicReference<>();
        MediaStoreSaver.save(context, capture, isVideo, uri -> {
            result.set(uri);
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
