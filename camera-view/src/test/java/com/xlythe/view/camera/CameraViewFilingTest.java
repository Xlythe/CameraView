package com.xlythe.view.camera;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.ShadowPausedAsyncTask;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * Where a capture ends up: takePicture() and startRecording() file theirs where the gallery looks
 * and report a uri, and a capture given a File stays in that file.
 *
 * <p>No camera writes anything here. {@link RecordingCameraView} keeps the file each capture was
 * handed, and the test writes it and reports it the way a backend would.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
@LooperMode(LooperMode.Mode.PAUSED)
public class CameraViewFilingTest {
    private static final byte[] CONTENTS = {1, 2, 3, 4, 5, 6, 7, 8};

    /** What the DNG of a RAW_JPEG picture holds, so a test can tell it from the JPEG. */
    private static final byte[] DNG_CONTENTS = {9, 8, 7, 6};

    /** Keeps the file each capture is handed, in place of a camera that would write it. */
    public static class RecordingCameraView extends CameraView {
        public final List<File> pictures = new ArrayList<>();
        public final List<File> videos = new ArrayList<>();

        /** The format this view says it writes, or null for whatever its camera says. */
        @Nullable public PictureFormat format;

        public RecordingCameraView(Context context) {
            super(context);
        }

        /** No camera is open to accept RAW, so a test says what the camera would write. */
        @Override
        public PictureFormat getPictureFormat() {
            return format != null ? format : super.getPictureFormat();
        }

        @Override
        public void takePicture(File file) {
            pictures.add(file);
        }

        @Override
        public void startRecording(File file) {
            videos.add(file);
        }
    }

    /** Everything a listener heard. */
    private static final class Heard
            implements CameraView.OnImageCapturedListener, CameraView.OnVideoCapturedListener {
        final List<File> captured = new ArrayList<>();
        final List<Uri> saved = new ArrayList<>();
        /** How many rows had been published as each uri was heard, for a test that asks. */
        final List<Integer> publishedWhenSaved = new ArrayList<>();
        /** Counts the published rows, or null to not count them. */
        @Nullable IntSupplier published;
        int confirmations;
        int failures;

        @Override public void onImageConfirmation() { confirmations++; }
        @Override public void onImageCaptured(File file) { captured.add(file); }
        @Override public void onImageSaved(@NonNull Uri uri) {
            saved.add(uri);
            if (published != null) {
                publishedWhenSaved.add(published.getAsInt());
            }
        }
        @Override public void onVideoConfirmation() { confirmations++; }
        @Override public void onVideoCaptured(File file) { captured.add(file); }
        @Override public void onVideoSaved(@NonNull Uri uri) { saved.add(uri); }
        @Override public void onFailure() { failures++; }
    }

    /** A MediaStore that refuses every row, as a full or unmounted volume does. */
    public static class RefusingMediaProvider extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Nullable @Override public Cursor query(@NonNull Uri uri, @Nullable String[] projection,
                @Nullable String selection, @Nullable String[] selectionArgs,
                @Nullable String sortOrder) { return null; }
        @Nullable @Override public String getType(@NonNull Uri uri) { return null; }
        @Nullable @Override public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
            return null;
        }
        @Override public int delete(@NonNull Uri uri, @Nullable String selection,
                @Nullable String[] selectionArgs) { return 0; }
        @Override public int update(@NonNull Uri uri, @Nullable ContentValues values,
                @Nullable String selection, @Nullable String[] selectionArgs) { return 0; }
    }

    private Application context;
    private RecordingCameraView cameraView;
    private Heard heard;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        CaptureDelivery.allowDngInMediaStore();
        // The confirmation preview decodes the picture on AsyncTask's pool. Inline, the decode is
        // finished, and the file closed, before anything tries to delete it.
        ShadowPausedAsyncTask.overrideExecutor(Runnable::run);
        // The video confirmation starts its player before there is a surface to prepare it on,
        // which a real MediaPlayer refuses with an exception VideoView expects.
        ShadowMediaPlayer.setCreateListener((player, shadow) -> shadow.setInvalidStateBehavior(
                ShadowMediaPlayer.InvalidStateBehavior.EMULATE));
        cameraView = new RecordingCameraView(context);
        cameraView.setId(1);
        cameraView.layout(0, 0, 1080, 1920);
        heard = new Heard();
        cameraView.setOnImageCapturedListener(heard);
        cameraView.setOnVideoCapturedListener(heard);
    }

    @Test
    public void aPictureWithoutAFileIsFiledInPictures() throws Exception {
        cameraView.takePicture();
        File capture = only(cameraView.pictures);
        assertEquals("written to the library's own cache directory",
                CameraView.captureDirectory(context), capture.getParentFile());

        write(capture);
        cameraView.showImageConfirmation(capture);
        awaitReport();

        assertEquals(1, heard.saved.size());
        assertTrue(heard.saved.get(0).toString().startsWith(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString()));
        assertEquals("a filed picture is not also reported as a file", 0, heard.captured.size());
        ContentValues inserted = onlyInsert();
        assertEquals(capture.getName(), inserted.getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertEquals(Environment.DIRECTORY_PICTURES + "/CameraView",
                inserted.getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
        assertFalse("the cache copy goes once it is filed", capture.exists());
    }

    @Test
    public void aRecordingWithoutAFileIsFiledInMovies() throws Exception {
        cameraView.startRecording();
        File capture = only(cameraView.videos);
        assertEquals(CameraView.captureDirectory(context), capture.getParentFile());
        assertTrue(capture.getName().endsWith(".mp4"));

        write(capture);
        cameraView.showVideoConfirmation(capture);
        awaitReport();

        assertEquals(1, heard.saved.size());
        assertTrue(heard.saved.get(0).toString().startsWith(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString()));
        assertEquals(0, heard.captured.size());
        assertEquals(Environment.DIRECTORY_MOVIES + "/CameraView",
                onlyInsert().getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
        assertFalse(capture.exists());
    }

    /** Before API 29 the picture is copied into the public Pictures directory instead. */
    @Test
    @Config(sdk = 28)
    public void beforeApi29APictureWithoutAFileIsCopiedIntoPictures() throws Exception {
        cameraView.takePicture();
        File capture = only(cameraView.pictures);
        write(capture);

        cameraView.showImageConfirmation(capture);
        awaitReport();

        assertEquals(1, heard.saved.size());
        File copied = new File(heard.saved.get(0).getPath());
        assertEquals(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                copied.getParentFile());
        assertTrue(copied.exists());
        assertFalse(capture.exists());
    }

    @Test
    public void aCaptureGivenAFileNeverTouchesMediaStore() throws Exception {
        File picture = write(new File(context.getCacheDir(), "chosen.jpg"));
        File video = write(new File(context.getCacheDir(), "chosen.mp4"));

        cameraView.takePicture(picture);
        cameraView.showImageConfirmation(picture);
        cameraView.startRecording(video);
        cameraView.showVideoConfirmation(video);
        settle();

        assertEquals(List.of(picture, video), heard.captured);
        assertEquals(0, heard.saved.size());
        assertTrue(resolver().getInsertStatements().isEmpty());
        assertTrue("the caller's file is left where it is", picture.exists());
        assertTrue(video.exists());
    }

    @Test
    public void aConfirmedPictureIsFiled() throws Exception {
        cameraView.setImageConfirmationEnabled(true);
        cameraView.takePicture();
        File capture = write(only(cameraView.pictures));

        cameraView.showImageConfirmation(capture);
        settle();
        assertEquals(1, heard.confirmations);
        assertTrue("nothing is filed before it is confirmed",
                resolver().getInsertStatements().isEmpty());
        assertTrue(cameraView.isImageConfirmationPending());

        cameraView.confirmPicture();
        awaitReport();

        assertEquals(1, heard.saved.size());
        assertEquals(0, heard.captured.size());
        assertFalse(capture.exists());
    }

    @Test
    public void aRejectedPictureIsDeletedAndNeverFiled() throws Exception {
        cameraView.setImageConfirmationEnabled(true);
        cameraView.takePicture();
        File capture = write(only(cameraView.pictures));
        cameraView.showImageConfirmation(capture);

        cameraView.rejectPicture();
        settle();

        assertEquals(0, heard.saved.size());
        assertEquals(0, heard.failures);
        assertTrue(resolver().getInsertStatements().isEmpty());
        assertFalse(capture.exists());
    }

    @Test
    public void aConfirmedRecordingIsFiled() throws Exception {
        cameraView.setVideoConfirmationEnabled(true);
        cameraView.startRecording();
        File capture = write(only(cameraView.videos));

        cameraView.showVideoConfirmation(capture);
        settle();
        assertEquals(1, heard.confirmations);
        assertTrue(resolver().getInsertStatements().isEmpty());

        cameraView.confirmVideo();
        awaitReport();

        assertEquals(1, heard.saved.size());
        assertTrue(heard.saved.get(0).toString().startsWith(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString()));
        assertFalse(capture.exists());
    }

    @Test
    public void aRejectedRecordingIsDeletedAndNeverFiled() throws Exception {
        cameraView.setVideoConfirmationEnabled(true);
        cameraView.startRecording();
        File capture = write(only(cameraView.videos));
        cameraView.showVideoConfirmation(capture);

        cameraView.rejectVideo();
        settle();

        assertEquals(0, heard.saved.size());
        assertTrue(resolver().getInsertStatements().isEmpty());
        assertFalse(capture.exists());
    }

    /** A picture waiting to be confirmed across a configuration change is still filed after it. */
    @Test
    public void aPendingPictureIsStillFiledAfterStateIsRestored() throws Exception {
        cameraView.setImageConfirmationEnabled(true);
        cameraView.takePicture();
        File capture = write(only(cameraView.pictures));
        cameraView.showImageConfirmation(capture);

        SparseArray<Parcelable> state = new SparseArray<>();
        cameraView.saveHierarchyState(state);
        CameraView restored = new CameraView(context);
        restored.setId(cameraView.getId());
        restored.layout(0, 0, 1080, 1920);
        restored.restoreHierarchyState(state);
        Heard afterRestore = new Heard();
        restored.setOnImageCapturedListener(afterRestore);
        heard = afterRestore;

        restored.confirmPicture();
        awaitReport();

        assertEquals(1, afterRestore.saved.size());
        assertEquals(0, afterRestore.captured.size());
        assertFalse(capture.exists());
    }

    /** A capture that never reached disc cannot be filed, and says so. */
    @Test
    @Config(sdk = {28, 37})
    public void aCaptureThatCannotBeFiledIsAFailure() throws Exception {
        cameraView.takePicture();
        File capture = only(cameraView.pictures);

        cameraView.showImageConfirmation(capture);
        awaitReport();

        assertEquals(1, heard.failures);
        assertEquals(0, heard.saved.size());
        assertEquals(0, heard.captured.size());
    }

    /** A MediaStore that will not take the row is a failure too, and the cache copy still goes. */
    @Test
    @Config(sdk = 37)
    public void aCaptureMediaStoreRefusesIsAFailure() throws Exception {
        Robolectric.buildContentProvider(RefusingMediaProvider.class).create(MediaStore.AUTHORITY);
        cameraView.startRecording();
        File capture = write(only(cameraView.videos));

        cameraView.showVideoConfirmation(capture);
        awaitReport();

        assertEquals(1, heard.failures);
        assertEquals(0, heard.saved.size());
        assertFalse(capture.exists());
    }

    /** Only RAW sits in the cache as a DNG. RAW_JPEG's JPEG is the file it is reported by. */
    @Test
    public void theCacheFileFollowsThePictureFormat() {
        for (CameraView.PictureFormat format : CameraView.PictureFormat.values()) {
            cameraView.format = format;
            cameraView.takePicture();
            File capture = cameraView.pictures.get(cameraView.pictures.size() - 1);
            assertEquals(format.name(),
                    format == CameraView.PictureFormat.RAW ? ".dng" : ".jpg",
                    capture.getName().substring(capture.getName().lastIndexOf('.')));
            assertEquals(CameraView.captureDirectory(context), capture.getParentFile());
        }
    }

    @Test
    @Config(sdk = {29, 37})
    public void aRawPictureWithoutAFileIsFiledAsADng() throws Exception {
        cameraView.format = CameraView.PictureFormat.RAW;
        cameraView.takePicture();
        File capture = write(only(cameraView.pictures), DNG_CONTENTS);

        cameraView.showImageConfirmation(capture, List.of(capture));
        awaitReport();

        assertEquals(1, heard.saved.size());
        assertEquals(0, heard.captured.size());
        ContentValues inserted = onlyInsert();
        assertEquals(capture.getName(), inserted.getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertTrue(capture.getName().endsWith(".dng"));
        assertEquals("image/x-adobe-dng", inserted.getAsString(MediaStore.MediaColumns.MIME_TYPE));
        assertFalse(capture.exists());
    }

    /**
     * RAW_JPEG files both under one name and reports each, the JPEG first, once both are filed.
     */
    @Test
    @Config(sdk = {29, 37})
    public void aRawJpegPictureFilesBothTheJpegFirst() throws Exception {
        cameraView.format = CameraView.PictureFormat.RAW_JPEG;
        cameraView.takePicture();
        File capture = write(only(cameraView.pictures), CONTENTS);
        File dng = write(DngWriter.dngBeside(capture), DNG_CONTENTS);
        heard.published = () -> resolver().getUpdateStatements().size();

        cameraView.showImageConfirmation(capture, List.of(capture, dng));
        awaitReport();

        assertEquals(2, heard.saved.size());
        assertEquals("neither is heard before both are filed", List.of(2, 2),
                heard.publishedWhenSaved);
        assertArrayEquals("the JPEG is heard first", CONTENTS, read(heard.saved.get(0)));
        assertArrayEquals(DNG_CONTENTS, read(heard.saved.get(1)));
        List<ShadowContentResolver.InsertStatement> inserts = resolver().getInsertStatements();
        assertEquals(2, inserts.size());
        String jpegName = inserts.get(0).getContentValues()
                .getAsString(MediaStore.MediaColumns.DISPLAY_NAME);
        String dngName = inserts.get(1).getContentValues()
                .getAsString(MediaStore.MediaColumns.DISPLAY_NAME);
        assertEquals(capture.getName(), jpegName);
        assertEquals("under the same name", baseName(jpegName) + ".dng", dngName);
        assertEquals("image/jpeg", inserts.get(0).getContentValues()
                .getAsString(MediaStore.MediaColumns.MIME_TYPE));
        assertEquals("image/x-adobe-dng", inserts.get(1).getContentValues()
                .getAsString(MediaStore.MediaColumns.MIME_TYPE));
        assertEquals(0, heard.captured.size());
        assertFalse(capture.exists());
        assertFalse(dng.exists());
    }

    /** A DNG that never reached disc fails the picture, and takes the filed JPEG with it. */
    @Test
    @Config(sdk = 37)
    public void aRawJpegPictureMissingItsDngFilesNeither() throws Exception {
        cameraView.format = CameraView.PictureFormat.RAW_JPEG;
        cameraView.takePicture();
        File capture = write(only(cameraView.pictures), CONTENTS);
        File dng = DngWriter.dngBeside(capture);

        cameraView.showImageConfirmation(capture, List.of(capture, dng));
        awaitReport();

        assertEquals(1, heard.failures);
        assertEquals(0, heard.saved.size());
        assertEquals(2, resolver().getInsertStatements().size());
        assertEquals("no row is left behind", 2, resolver().getDeletedUris().size());
        assertTrue(resolver().getUpdateStatements().isEmpty());
        assertFalse("the cache copy goes either way", capture.exists());
    }

    /** A MediaStore that takes neither half is a failure, and both cache copies still go. */
    @Test
    @Config(sdk = 37)
    public void aRawJpegPictureMediaStoreRefusesLosesBothCacheCopies() throws Exception {
        Robolectric.buildContentProvider(RefusingMediaProvider.class).create(MediaStore.AUTHORITY);
        cameraView.format = CameraView.PictureFormat.RAW_JPEG;
        cameraView.takePicture();
        File capture = write(only(cameraView.pictures), CONTENTS);
        File dng = write(DngWriter.dngBeside(capture), DNG_CONTENTS);

        cameraView.showImageConfirmation(capture, List.of(capture, dng));
        awaitReport();

        assertEquals(1, heard.failures);
        assertEquals(0, heard.saved.size());
        assertFalse(capture.exists());
        assertFalse(dng.exists());
    }

    @Test
    @Config(sdk = 28)
    public void beforeApi29ARawJpegPictureIsCopiedAsBoth() throws Exception {
        cameraView.format = CameraView.PictureFormat.RAW_JPEG;
        cameraView.takePicture();
        File capture = write(only(cameraView.pictures), CONTENTS);
        File dng = write(DngWriter.dngBeside(capture), DNG_CONTENTS);

        cameraView.showImageConfirmation(capture, List.of(capture, dng));
        awaitReport();

        File pictures =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        assertEquals(2, heard.saved.size());
        assertEquals(new File(pictures, capture.getName()),
                new File(heard.saved.get(0).getPath()));
        assertEquals(new File(pictures, dng.getName()), new File(heard.saved.get(1).getPath()));
        assertFalse(capture.exists());
        assertFalse(dng.exists());
    }

    @Test
    public void aConfirmedRawJpegPictureFilesBoth() throws Exception {
        cameraView.setImageConfirmationEnabled(true);
        cameraView.format = CameraView.PictureFormat.RAW_JPEG;
        cameraView.takePicture();
        File capture = write(only(cameraView.pictures), CONTENTS);
        File dng = write(DngWriter.dngBeside(capture), DNG_CONTENTS);

        cameraView.showImageConfirmation(capture, List.of(capture, dng));
        settle();
        assertEquals(1, heard.confirmations);
        assertTrue(resolver().getInsertStatements().isEmpty());

        cameraView.confirmPicture();
        awaitReport();

        assertEquals(2, heard.saved.size());
        assertEquals(2, resolver().getInsertStatements().size());
        assertFalse(capture.exists());
        assertFalse(dng.exists());
    }

    @Test
    public void aRejectedRawJpegPictureDeletesBoth() throws Exception {
        cameraView.setImageConfirmationEnabled(true);
        cameraView.format = CameraView.PictureFormat.RAW_JPEG;
        cameraView.takePicture();
        File capture = write(only(cameraView.pictures), CONTENTS);
        File dng = write(DngWriter.dngBeside(capture), DNG_CONTENTS);
        cameraView.showImageConfirmation(capture, List.of(capture, dng));

        cameraView.rejectPicture();
        settle();

        assertEquals(0, heard.saved.size());
        assertEquals(0, heard.failures);
        assertTrue(resolver().getInsertStatements().isEmpty());
        assertFalse(capture.exists());
        assertFalse("the DNG goes with the JPEG", dng.exists());
        assertFalse(cameraView.isImageConfirmationPending());
    }

    /** Both halves of a pending RAW_JPEG picture survive a configuration change. */
    @Test
    public void aPendingRawJpegPictureIsFiledAsBothAfterStateIsRestored() throws Exception {
        cameraView.setImageConfirmationEnabled(true);
        cameraView.format = CameraView.PictureFormat.RAW_JPEG;
        cameraView.takePicture();
        File capture = write(only(cameraView.pictures), CONTENTS);
        File dng = write(DngWriter.dngBeside(capture), DNG_CONTENTS);
        cameraView.showImageConfirmation(capture, List.of(capture, dng));

        SparseArray<Parcelable> state = new SparseArray<>();
        cameraView.saveHierarchyState(state);
        CameraView restored = new CameraView(context);
        restored.setId(cameraView.getId());
        restored.layout(0, 0, 1080, 1920);
        restored.restoreHierarchyState(state);
        Heard afterRestore = new Heard();
        restored.setOnImageCapturedListener(afterRestore);
        heard = afterRestore;

        restored.confirmPicture();
        awaitReport();

        assertEquals(2, afterRestore.saved.size());
        assertFalse(capture.exists());
        assertFalse(dng.exists());
    }

    /**
     * Given a file, RAW_JPEG is reported by the JPEG the caller named, with the DNG left beside
     * it, and rejecting it deletes both.
     */
    @Test
    public void aRawJpegPictureGivenAFileIsReportedByItsJpeg() throws Exception {
        cameraView.format = CameraView.PictureFormat.RAW_JPEG;
        File picture = write(new File(context.getCacheDir(), "chosen.jpg"), CONTENTS);
        File dng = write(DngWriter.dngBeside(picture), DNG_CONTENTS);

        cameraView.takePicture(picture);
        cameraView.showImageConfirmation(picture, List.of(picture, dng));
        settle();

        assertEquals(List.of(picture), heard.captured);
        assertTrue(resolver().getInsertStatements().isEmpty());
        assertTrue(picture.exists());
        assertTrue(dng.exists());

        cameraView.setImageConfirmationEnabled(true);
        cameraView.showImageConfirmation(picture, List.of(picture, dng));
        cameraView.rejectPicture();
        assertFalse(picture.exists());
        assertFalse(dng.exists());
    }

    private static String baseName(String name) {
        return name.substring(0, name.lastIndexOf('.'));
    }

    private byte[] read(Uri uri) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            assertTrue("nothing at " + uri, input != null);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[64];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static File only(List<File> files) {
        assertEquals(1, files.size());
        return files.get(0);
    }

    private static File write(File file) throws IOException {
        return write(file, CONTENTS);
    }

    private static File write(File file, byte[] contents) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(contents);
        }
        return file;
    }

    private ShadowContentResolver resolver() {
        return Shadows.shadowOf(context.getContentResolver());
    }

    private ContentValues onlyInsert() {
        List<ShadowContentResolver.InsertStatement> inserts = resolver().getInsertStatements();
        assertEquals(1, inserts.size());
        return inserts.get(0).getContentValues();
    }

    /** Runs the main looper until filing, which happens on a thread of its own, has reported. */
    private void awaitReport() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (heard.saved.isEmpty() && heard.failures == 0
                && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper();
            Thread.sleep(10);
        }
        assertTrue("the capture was never reported", !heard.saved.isEmpty() || heard.failures > 0);
    }

    /** Gives anything that was going to be filed long enough to have been. */
    private static void settle() throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            ShadowLooper.idleMainLooper();
            Thread.sleep(10);
        }
    }
}
