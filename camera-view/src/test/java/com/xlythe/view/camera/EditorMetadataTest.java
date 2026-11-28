package com.xlythe.view.camera;

import static com.xlythe.view.camera.MediaFixtures.concat;
import static com.xlythe.view.camera.MediaFixtures.file;
import static com.xlythe.view.camera.MediaFixtures.jpeg;
import static com.xlythe.view.camera.MediaFixtures.xmpSegment;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** What the picture editor writes about a motion photo's clip and a thumbnail. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public class EditorMetadataTest {
    @Test
    public void aClipAppendedToAPlainPictureIsDescribedInGooglesForm() throws IOException {
        byte[] clip = MediaTestFiles.mp4Clip(64);
        File file = file(concat(jpeg(), clip), ".jpg");
        MediaTags tags = MediaTags.open(file);
        assertNull(tags.getMotionPhoto());

        assertTrue(EditorMetadata.describeMotionPhoto(tags, clip.length, 5000));
        tags.save();

        MediaTags saved = MediaTags.open(file);
        MediaTags.MotionPhoto motion = saved.getMotionPhoto();
        assertNotNull(motion);
        assertEquals(MediaTags.MotionPhoto.FORMAT_MOTION_PHOTO, motion.getFormat());
        assertEquals(clip.length, motion.getVideoLength());
        assertEquals(file.length() - clip.length, motion.getVideoOffset());
        assertEquals(5000, motion.getPresentationTimestampUs());
        List<Map<String, String>> items = directory(file);
        assertEquals(2, items.size());
        assertEquals("Primary", items.get(0).get(Xmp.NS_ITEM + "Semantic"));
        assertEquals("image/jpeg", items.get(0).get(Xmp.NS_ITEM + "Mime"));
        assertEquals("MotionPhoto", items.get(1).get(Xmp.NS_ITEM + "Semantic"));
    }

    @Test
    public void aClipGoesAfterTheGainMapTheDirectoryAlreadyLists() throws IOException {
        String packet = MediaFixtures.xmpElements("xmlns:Container=\"" + Xmp.NS_CONTAINER
                + "\" xmlns:Item=\"" + Xmp.NS_ITEM + "\" xmlns:Camera=\"" + Xmp.NS_CAMERA
                + "\" Camera:MicroVideo=\"1\" Camera:MicroVideoOffset=\"99\"",
                "<Container:Directory><rdf:Seq>"
                + "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"image/jpeg\" "
                + "Item:Semantic=\"Primary\"/></rdf:li>"
                + "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"image/jpeg\" "
                + "Item:Semantic=\"GainMap\" Item:Length=\"1000\"/></rdf:li>"
                + "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"video/mp4\" "
                + "Item:Semantic=\"MotionPhoto\" Item:Length=\"7\"/></rdf:li>"
                + "</rdf:Seq></Container:Directory>");
        File file = file(jpeg(xmpSegment(packet)), ".jpg");
        MediaTags tags = MediaTags.open(file);
        assertTrue(EditorMetadata.describeMotionPhoto(tags, 300, -1));
        tags.save();

        List<Map<String, String>> items = directory(file);
        assertEquals(3, items.size());
        assertEquals("GainMap", items.get(1).get(Xmp.NS_ITEM + "Semantic"));
        assertEquals("1000", items.get(1).get(Xmp.NS_ITEM + "Length"));
        assertEquals("300", items.get(2).get(Xmp.NS_ITEM + "Length"));
        MediaTags saved = MediaTags.open(file);
        assertNull("the older form is gone", saved.getXmp(Xmp.NS_CAMERA, "MicroVideo"));
        assertNull(saved.getXmp(Xmp.NS_CAMERA, "MotionPhotoPresentationTimestampUs"));
        assertEquals("1", saved.getXmp(Xmp.NS_CAMERA, "MotionPhoto"));

        // Cut off again, the gain map stays listed.
        MediaTags cut = MediaTags.open(file);
        assertTrue(EditorMetadata.removeMotionPhoto(cut));
        cut.save();
        List<Map<String, String>> left = directory(file);
        assertEquals(2, left.size());
        assertEquals("GainMap", left.get(1).get(Xmp.NS_ITEM + "Semantic"));
        assertNull(MediaTags.open(file).getXmp(Xmp.NS_CAMERA, "MotionPhoto"));
    }

    @Test
    public void aDirectoryOfOnlyThePrimaryPictureIsRemoved() throws IOException {
        byte[] clip = MediaTestFiles.mp4Clip(64);
        File file = file(concat(jpeg(xmpSegment(MediaTestFiles.motionPhotoXmp(clip.length))),
                clip), ".jpg");
        MediaTags tags = MediaTags.open(file);
        assertNotNull(tags.getMotionPhoto());
        assertTrue(EditorMetadata.removeMotionPhoto(tags));
        tags.save();
        assertTrue(directory(file).isEmpty());
        assertNull(MediaTags.open(file).getXmp(Xmp.NS_CAMERA, "MotionPhoto"));
    }

    @Test
    public void aPictureThatNeverHadAClipNeedsNothingRemoved() throws IOException {
        MediaTags tags = MediaTags.read(jpeg());
        assertTrue(EditorMetadata.removeMotionPhoto(tags));
        assertFalse("what can only be read cannot say it has one",
                EditorMetadata.describeMotionPhoto(tags, 10, -1));
    }

    @Test
    public void aThumbnailIsAskedForAndFound() throws IOException {
        File file = file(ExifFixtures.exifJpeg(false), ".jpg");
        MediaTags tags = MediaTags.open(file);
        assertFalse(EditorMetadata.hasThumbnail(tags));
        assertTrue(EditorMetadata.attachThumbnail(tags));
        assertFalse(EditorMetadata.attachThumbnail(MediaTags.read(jpeg())));
        assertFalse(EditorMetadata.hasThumbnail(MediaTags.read(MediaFixtures.png())));
    }

    private static List<Map<String, String>> directory(File file) throws IOException {
        byte[] bytes = MediaFixtures.read(file);
        int start = Bytes.indexOf(bytes, "<x:xmpmeta".getBytes(Bytes.UTF_8), 0);
        if (start < 0) {
            // No packet at all says nothing either.
            return new ArrayList<>();
        }
        int end = Bytes.indexOf(bytes, "</x:xmpmeta>".getBytes(Bytes.UTF_8), start);
        return Xmp.parse(new String(bytes, start, end + 12 - start, Bytes.UTF_8))
                .getStructs(Xmp.NS_CONTAINER, "Directory");
    }
}
