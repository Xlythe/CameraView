package com.xlythe.view.camera.edit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.transformer.Composition;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Covers what the editor does around an export, with an exporter that reports whatever the test
 * asks it to. The export itself is media3's, and {@link TransformerExporter} says what of it a
 * unit test can reach.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
@OptIn(markerClass = UnstableApi.class)
public class VideoEditorTest {

    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    private final FakeExporter exporter = new FakeExporter();
    private final RecordingListener listener = new RecordingListener();

    private File input;
    private File output;

    @Before
    public void setup() throws IOException {
        input = folder.newFile("recording.mp4");
        output = new File(folder.getRoot(), "clip.mp4");
    }

    @Test
    public void startingAnEditorExportsToTheOutputFile() {
        VideoEditor editor = newEditor(new VideoEdits.Builder().setRotationDegrees(90).build());

        editor.start();

        assertEquals(1, exporter.startCount);
        assertNotNull(exporter.composition);
        assertEquals(output.getAbsolutePath(), exporter.outputPath);
        assertTrue(editor.isRunning());
    }

    @Test
    public void aFinishedExportReportsTheOutputOnTheMainThread() {
        VideoEditor editor = newEditor();
        editor.start();

        exporter.callback.onCompleted();

        // The result is posted rather than handed back on whichever thread the export ended on.
        assertTrue(listener.edited.isEmpty());
        ShadowLooper.shadowMainLooper().idle();
        assertEquals(1, listener.edited.size());
        assertSame(output, listener.edited.get(0));
        assertEquals(0, listener.failures);
        assertFalse(editor.isRunning());
    }

    @Test
    public void aFailedExportReportsAFailure() {
        VideoEditor editor = newEditor();
        editor.start();

        exporter.callback.onFailed(new IOException("no encoder"));

        assertEquals(0, listener.failures);
        ShadowLooper.shadowMainLooper().idle();
        assertEquals(1, listener.failures);
        assertTrue(listener.edited.isEmpty());
        assertFalse(editor.isRunning());
    }

    @Test
    public void anInputThatIsNotThereFailsWithoutAnExport() {
        VideoEditor editor = new VideoEditor(
                new File(folder.getRoot(), "never-recorded.mp4"),
                output,
                new VideoEdits.Builder().build(),
                exporter);
        editor.setOnVideoEditedListener(listener);

        editor.start();

        assertEquals(0, exporter.startCount);
        assertFalse(editor.isRunning());
        ShadowLooper.shadowMainLooper().idle();
        assertEquals(1, listener.failures);
    }

    @Test
    public void anEditorRunsOnce() {
        VideoEditor editor = newEditor();

        editor.start();
        editor.start();

        assertEquals(1, exporter.startCount);
    }

    @Test
    public void aCancelledExportReportsNothing() {
        VideoEditor editor = newEditor();
        editor.start();

        editor.cancel();
        assertEquals(1, exporter.cancelCount);
        assertFalse(editor.isRunning());

        // media3 can still finish, or fail, an export that was cancelled a moment earlier.
        exporter.callback.onCompleted();
        exporter.callback.onFailed(new IOException("cancelled"));
        ShadowLooper.shadowMainLooper().idle();

        assertTrue(listener.edited.isEmpty());
        assertEquals(0, listener.failures);
    }

    @Test
    public void cancellingAnEditorThatIsNotRunningIsHarmless() {
        VideoEditor editor = newEditor();

        editor.cancel();
        assertEquals(0, exporter.cancelCount);

        editor.start();
        exporter.callback.onCompleted();
        ShadowLooper.shadowMainLooper().idle();
        editor.cancel();
        assertEquals(0, exporter.cancelCount);
    }

    @Test
    public void aResultWithNothingListeningIsDropped() {
        VideoEditor finished = newEditor();
        finished.setOnVideoEditedListener(null);
        finished.start();
        exporter.callback.onCompleted();
        ShadowLooper.shadowMainLooper().idle();
        assertFalse(finished.isRunning());

        VideoEditor failed = newEditor();
        failed.setOnVideoEditedListener(null);
        failed.start();
        exporter.callback.onFailed(new IOException("no encoder"));
        ShadowLooper.shadowMainLooper().idle();
        assertFalse(failed.isRunning());
    }

    @Test
    public void thePublicConstructorBuildsAnEditorOverMedia3() {
        // The constructor the library hands out, with the real exporter behind it. Starting it
        // would need a codec, so this goes as far as building one and no further.
        VideoEditor editor = new VideoEditor(
                RuntimeEnvironment.getApplication(), input, output,
                new VideoEdits.Builder().setTimeRangeMs(0, 1_000).build());

        assertFalse(editor.isRunning());
        assertNull(editor.getOnVideoEditedListener());
    }

    @Test
    public void theListenerCanBeReadBack() {
        VideoEditor editor = newEditor();

        assertSame(listener, editor.getOnVideoEditedListener());
        editor.setOnVideoEditedListener(null);
        assertNull(editor.getOnVideoEditedListener());
    }

    private VideoEditor newEditor() {
        return newEditor(new VideoEdits.Builder().build());
    }

    private VideoEditor newEditor(VideoEdits edits) {
        VideoEditor editor = new VideoEditor(input, output, edits, exporter);
        editor.setOnVideoEditedListener(listener);
        return editor;
    }

    private static class FakeExporter implements VideoEditor.Exporter {
        @Nullable Composition composition;
        @Nullable String outputPath;
        @Nullable Callback callback;
        int startCount;
        int cancelCount;

        @Override
        public void setCallback(@NonNull Callback callback) {
            this.callback = callback;
        }

        @Override
        public void start(@NonNull Composition composition, @NonNull String outputPath) {
            this.composition = composition;
            this.outputPath = outputPath;
            startCount++;
        }

        @Override
        public void cancel() {
            cancelCount++;
        }
    }

    private static class RecordingListener implements VideoEditor.OnVideoEditedListener {
        final List<File> edited = new ArrayList<>();
        int failures;

        @Override
        public void onVideoEdited(File file) {
            edited.add(file);
        }

        @Override
        public void onFailure() {
            failures++;
        }
    }
}
