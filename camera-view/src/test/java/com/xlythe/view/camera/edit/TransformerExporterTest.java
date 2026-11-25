package com.xlythe.view.camera.edit;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * Covers the media3-backed exporter as far as a unit test can: the Transformer it builds and the
 * translation of Transformer's callbacks. Starting an export is not covered here, and cannot be -
 * see the class under test.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
@OptIn(markerClass = UnstableApi.class)
public class TransformerExporterTest {

    private final RecordingCallback callback = new RecordingCallback();

    private Composition composition;
    private TransformerExporter exporter;

    @Before
    public void setup() {
        Context context = RuntimeEnvironment.getApplication();
        composition = VideoEditor.toComposition(
                new File("/recordings/clip.mp4"), new VideoEdits.Builder().build());
        exporter = new TransformerExporter(context);
    }

    @Test
    public void anExportIsDrivenFromTheMainLooper() {
        // Transformer refuses a call from any thread but the one its looper belongs to, and a
        // cancel with nothing to cancel does nothing else, so this returning on the test's own
        // main thread is what says the exporter pinned Transformer to the main looper rather
        // than leaving it on whichever thread built it.
        exporter.cancel();
    }

    @Test
    public void aCompletedExportIsPassedOn() {
        exporter.setCallback(callback);

        exporter.onCompleted(composition, new ExportResult.Builder().build());

        assertEquals(1, callback.completions);
        assertEquals(0, callback.failures);
    }

    @Test
    public void aFailedExportIsPassedOnWithItsCause() {
        exporter.setCallback(callback);
        ExportException exception = ExportException.createForUnexpected(new RuntimeException());

        exporter.onError(composition, new ExportResult.Builder().build(), exception);

        assertEquals(1, callback.failures);
        assertSame(exception, callback.lastFailure);
    }

    @Test
    public void callbacksWithNobodyListeningAreDropped() {
        exporter.onCompleted(composition, new ExportResult.Builder().build());
        exporter.onError(composition, new ExportResult.Builder().build(),
                ExportException.createForUnexpected(new RuntimeException()));

        assertEquals(0, callback.completions);
        assertEquals(0, callback.failures);
    }

    private static class RecordingCallback implements VideoEditor.Exporter.Callback {
        int completions;
        int failures;
        @Nullable Exception lastFailure;

        @Override
        public void onCompleted() {
            completions++;
        }

        @Override
        public void onFailed(@NonNull Exception exception) {
            failures++;
            lastFailure = exception;
        }
    }
}
