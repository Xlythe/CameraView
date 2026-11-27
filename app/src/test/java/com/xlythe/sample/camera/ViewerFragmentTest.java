package com.xlythe.sample.camera;

import static org.junit.Assert.assertEquals;

import com.xlythe.view.camera.viewer.MediaView;

import org.junit.Test;

/** The viewer's choices, and what each is called. */
public class ViewerFragmentTest {
    /** Each choice shows the file as its own kind of media, and Auto as what it is found to be. */
    @Test
    public void eachChoiceIsAKindOfMedia() {
        int[] ids = {R.id.viewer_type_detect, R.id.viewer_type_picture, R.id.viewer_type_panorama,
                R.id.viewer_type_photosphere, R.id.viewer_type_video, R.id.viewer_type_motion,
                R.id.viewer_type_spherical, R.id.viewer_type_audio};
        int[] types = {MediaView.UNKNOWN, MediaView.PICTURE, MediaView.PANORAMA,
                MediaView.PHOTOSPHERE, MediaView.VIDEO, MediaView.MOTION_PHOTO,
                MediaView.SPHERICAL_VIDEO, MediaView.AUDIO};
        int[] labels = {R.string.viewer_mode_detect, R.string.viewer_mode_picture,
                R.string.viewer_mode_panorama, R.string.viewer_mode_photosphere,
                R.string.viewer_mode_video, R.string.viewer_mode_motion,
                R.string.viewer_mode_spherical, R.string.viewer_mode_audio};
        for (int i = 0; i < ids.length; i++) {
            assertEquals(types[i], ViewerFragment.typeOf(ids[i]));
            assertEquals(labels[i], ViewerFragment.labelOf(types[i]));
        }
    }
}
