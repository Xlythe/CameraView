package com.xlythe.view.camera;

import androidx.annotation.MainThread;
import androidx.annotation.RestrictTo;
import androidx.camera.core.ImageAnalysis;

/**
 * Something CameraX hands frames to, which owns a model it has to give back.
 *
 * <p>There is one analysis slot. CameraX binds an {@code ImageAnalysis} alongside a preview and
 * a still, and gives it up to a recording, so whatever wants the frames has to be the only thing
 * that wants them - barcode scanning or segmentation, not both. Naming that as one type keeps
 * the binding logic from asking after each of them by name and quietly forgetting the next one
 * that arrives.</p>
 *
 * @hide
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public interface CameraXFrameAnalyzer extends ImageAnalysis.Analyzer, AutoCloseable {
    /**
     * Whether the frames from the next bind come from a lens shown mirrored, as a front camera
     * is. Told before every bind, since a lens switch rebinds with the same analyzer.
     */
    @MainThread
    default void setMirrored(boolean mirrored) {}

    /** Releases the model. Narrowed from AutoCloseable, which throws and this does not. */
    @Override
    void close();
}
