package com.xlythe.view.camera.edit;

import android.content.Context;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.annotation.RequiresApi;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;

/**
 * Runs an export with androidx.media3's {@link Transformer}.
 *
 * <p>The one thing here that no unit test reaches is {@link #start}, which hands the composition
 * to Transformer: that needs a hardware decoder, encoder and muxer, so it only runs on a device
 * and the device suite is what covers it. Everything around it - the Transformer this builds, and
 * the translation of its callbacks into the editor's - is exercised off one.</p>
 */
@RequiresApi(23)
@OptIn(markerClass = UnstableApi.class)
final class TransformerExporter implements VideoEditor.Exporter, Transformer.Listener {
    private static final String TAG = TransformerExporter.class.getSimpleName();

    private final Transformer mTransformer;

    @Nullable private Callback mCallback;

    TransformerExporter(Context context) {
        // A Transformer belongs to one looper: it has to be started and cancelled on that looper
        // and it reports back on it. Left alone it adopts whichever thread built it, so an editor
        // constructed on a background thread could never be started from the main one. Naming the
        // main looper makes that the thread an export is driven from, whoever built the editor.
        mTransformer = new Transformer.Builder(context)
                .setLooper(Looper.getMainLooper())
                .addListener(this)
                .build();
    }

    @Override
    public void setCallback(@NonNull Callback callback) {
        mCallback = callback;
    }

    @Override
    public void start(@NonNull Composition composition, @NonNull String outputPath) {
        mTransformer.start(composition, outputPath);
    }

    @Override
    public void cancel() {
        mTransformer.cancel();
    }

    @Override
    public void onCompleted(@NonNull Composition composition, @NonNull ExportResult result) {
        if (mCallback != null) {
            mCallback.onCompleted();
        }
    }

    @Override
    public void onError(@NonNull Composition composition, @NonNull ExportResult result,
                        @NonNull ExportException exception) {
        if (mCallback == null) {
            Log.w(TAG, "An export failed with nothing listening", exception);
            return;
        }
        mCallback.onFailed(exception);
    }
}
