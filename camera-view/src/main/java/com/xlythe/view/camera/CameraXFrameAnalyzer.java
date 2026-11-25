package com.xlythe.view.camera;

import androidx.annotation.RestrictTo;
import androidx.camera.core.ImageAnalysis;

/**
 * Something CameraX hands frames to, which owns a model it has to give back.
 *
 * <p>There is one analysis slot. CameraX will bind an {@code ImageAnalysis} alongside a preview
 * and either a still or a recording, but not two of them, so whatever wants the frames has to
 * be the only thing that wants them - barcode scanning or segmentation, not both. Naming that
 * as one type keeps the binding logic from asking after each of them by name and quietly
 * forgetting the next one that arrives.</p>
 *
 * @hide
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public interface CameraXFrameAnalyzer extends ImageAnalysis.Analyzer, AutoCloseable {
    /** Releases the model. Narrowed from AutoCloseable, which throws and this does not. */
    @Override
    void close();
}
