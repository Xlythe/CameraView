package com.xlythe.sample.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which viewer the Compose sample opens a capture in. */
class ViewerModeTest {
    /** The camera's photo spheres are 2:1, its panoramas wider, and a DNG is always flat. */
    @Test
    fun picksTheViewerByShape() {
        assertEquals(ViewerMode.PHOTOSPHERE, guessViewerMode("image/jpeg", 3016, 1508))
        assertEquals(ViewerMode.PANORAMA, guessViewerMode("image/jpeg", 3032, 532))
        assertEquals(ViewerMode.PICTURE, guessViewerMode("image/jpeg", 4000, 3000))
        assertEquals(ViewerMode.PICTURE, guessViewerMode("image/x-adobe-dng", 4000, 2000))
        assertEquals(ViewerMode.PICTURE, guessViewerMode(null, -1, -1))
    }
}
