package com.xlythe.view.camera;

/**
 * Reaches the library's photo mode test switch from device tests in other packages, which is the
 * only reason it is public.
 */
public final class PhotoModeTesting {
    private PhotoModeTesting() {}

    /**
     * Has background blur go through the library's own blur on every camera, the ones with a blur
     * of their own included, until called again with false.
     */
    public static void forceBlurFallback(boolean forced) {
        ICameraModule.setBlurFallbackForced(forced);
    }
}
