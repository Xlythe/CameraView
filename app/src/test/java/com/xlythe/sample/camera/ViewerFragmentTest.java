package com.xlythe.sample.camera;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Which viewer the sample opens a capture in. */
public class ViewerFragmentTest {
    /** The camera's photo spheres are 2:1, its panoramas wider, and a DNG is always flat. */
    @Test
    public void picksTheViewerByShape() {
        assertEquals(ViewerFragment.Mode.PHOTOSPHERE, ViewerFragment.guess("image/jpeg", 3016, 1508));
        assertEquals(ViewerFragment.Mode.PHOTOSPHERE, ViewerFragment.guess(null, 4096, 2047));
        assertEquals(ViewerFragment.Mode.PANORAMA, ViewerFragment.guess("image/jpeg", 3032, 532));
        assertEquals(ViewerFragment.Mode.PICTURE, ViewerFragment.guess("image/jpeg", 4000, 3000));
        assertEquals(ViewerFragment.Mode.PICTURE, ViewerFragment.guess("image/jpeg", 2400, 1000));
        assertEquals(ViewerFragment.Mode.PICTURE,
                ViewerFragment.guess("image/x-adobe-dng", 4000, 2000));
        assertEquals(ViewerFragment.Mode.PICTURE, ViewerFragment.guess(null, -1, -1));
    }
}
