package com.xlythe.sample.camera

import com.xlythe.view.camera.viewer.MediaView
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Compose media demo's choices. */
class ViewerModeTest {
    /** Each choice names its own kind of media, and Auto stands for what a file is found to be. */
    @Test
    fun eachChoiceIsAKindOfMedia() {
        for (mode in ViewerMode.entries) {
            assertEquals(mode, ViewerMode.of(mode.type))
        }
        assertEquals(MediaView.UNKNOWN, ViewerMode.AUTO.type)
        assertEquals(ViewerMode.AUTO, ViewerMode.of(99))
    }
}
