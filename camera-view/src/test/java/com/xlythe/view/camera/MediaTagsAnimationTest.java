package com.xlythe.view.camera;

import static com.xlythe.view.camera.MediaFixtures.ascii;
import static com.xlythe.view.camera.MediaFixtures.box;
import static com.xlythe.view.camera.MediaFixtures.concat;
import static com.xlythe.view.camera.MediaFixtures.u32;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.CRC32;

/** Whether a picture animates: GIF frames, an APNG's control chunk, WebP frames, a sequence. */
@RunWith(RobolectricTestRunner.class)
public class MediaTagsAnimationTest {
    /** A GIF of some frames, with a global and a local colour table and an extension. */
    static byte[] gif(int frames) {
        byte[] header = concat(ascii("GIF89a"), new byte[] {2, 0, 2, 0, (byte) 0x80, 0, 0},
                new byte[6]);
        byte[] loop = concat(new byte[] {0x21, (byte) 0xFF, 11}, ascii("NETSCAPE2.0"),
                new byte[] {3, 1, 0, 0, 0});
        byte[] body = loop;
        for (int i = 0; i < frames; i++) {
            byte[] control = {0x21, (byte) 0xF9, 4, 0, 10, 0, 0, 0};
            byte[] image = concat(new byte[] {0x2C, 0, 0, 0, 0, 2, 0, 2, 0,
                    (byte) (i % 2 == 0 ? 0x80 : 0)}, i % 2 == 0 ? new byte[6] : new byte[0],
                    new byte[] {2, 2, 0x4C, 0x01, 0});
            body = concat(body, control, image);
        }
        return concat(header, body, new byte[] {0x3B});
    }

    /** A PNG chunk with its CRC. */
    static byte[] chunk(String type, byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(ascii(type));
        crc.update(data);
        return concat(u32(data.length), ascii(type), data, u32(crc.getValue()));
    }

    static byte[] png(byte[]... chunks) {
        byte[] header = chunk("IHDR", concat(u32(1), u32(1), new byte[] {8, 6, 0, 0, 0}));
        return concat(PngContainer.SIGNATURE, header, concat(chunks),
                chunk("IDAT", new byte[] {0x78, 0x01}), chunk("IEND", new byte[0]));
    }

    static byte[] webp(byte[]... chunks) {
        byte[] body = concat(ascii("WEBP"), concat(chunks));
        byte[] size = new byte[4];
        Bytes.put32le(size, 0, body.length);
        return concat(ascii("RIFF"), size, body);
    }

    static byte[] riffChunk(String type, byte[] data) {
        byte[] size = new byte[4];
        Bytes.put32le(size, 0, data.length);
        return concat(ascii(type), size, data, data.length % 2 == 1 ? new byte[1] : new byte[0]);
    }

    @Test
    public void countsAGifsFrames() throws IOException {
        assertEquals(3, GifContainer.frames(ByteSource.of(gif(3))));
        assertEquals(1, GifContainer.frames(ByteSource.of(gif(1))));
        MediaTags animated = MediaTags.read(gif(2));
        assertEquals(MediaTags.FORMAT_GIF, animated.getFormat());
        assertEquals(MediaTags.KIND_IMAGE, animated.getKind());
        assertTrue(animated.isAnimated());
        assertFalse(MediaTags.read(gif(1)).isAnimated());
    }

    @Test
    public void aBrokenGifCountsWhatItCanRead() throws IOException {
        byte[] whole = gif(3);
        // Cut inside the second frame's data: the first frame still counts.
        byte[] cut = new byte[whole.length - 12];
        System.arraycopy(whole, 0, cut, 0, cut.length);
        assertTrue(GifContainer.frames(ByteSource.of(cut)) >= 2);
        assertEquals(0, GifContainer.frames(ByteSource.of(ascii("GIF89a"))));
        // An image descriptor cut short.
        byte[] descriptor = concat(ascii("GIF89a"), new byte[7], new byte[] {0x2C, 0, 0});
        assertEquals(0, GifContainer.frames(ByteSource.of(descriptor)));
        // Something no reader understands ends the count.
        byte[] stray = concat(ascii("GIF89a"), new byte[7], new byte[] {0x55});
        assertEquals(0, GifContainer.frames(ByteSource.of(stray)));
        // Sub-blocks that run off the end.
        byte[] runaway = concat(ascii("GIF89a"), new byte[7], new byte[] {0x21, (byte) 0xF9,
                50, 1, 2});
        assertEquals(0, GifContainer.frames(ByteSource.of(runaway)));
    }

    @Test
    public void aGifKeepsNothing() throws IOException {
        File file = File.createTempFile("animation", ".gif");
        file.deleteOnExit();
        Files.write(file.toPath(), gif(2));
        MediaTags tags = MediaTags.open(file);
        assertTrue(tags.isReadOnly());
        assertFalse(tags.setTitle("Nothing"));
        assertFalse(tags.setXmp(Xmp.NS_DC, "source", "Nowhere"));
        tags.save();
    }

    @Test
    public void anApngSaysSoInItsControlChunk() throws IOException {
        byte[] control = chunk("acTL", concat(u32(4), u32(0)));
        assertTrue(MediaTags.read(png(control)).isAnimated());
        byte[] one = chunk("acTL", concat(u32(1), u32(0)));
        assertFalse("one frame does not animate", MediaTags.read(png(one)).isAnimated());
        assertFalse(MediaTags.read(png()).isAnimated());
    }

    @Test
    public void anAnimatedWebpHasFrames() throws IOException {
        byte[] header = riffChunk("VP8X", new byte[] {0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        byte[] anim = riffChunk("ANIM", new byte[6]);
        byte[] frame = riffChunk("ANMF", new byte[16]);
        assertTrue(MediaTags.read(webp(header, anim, frame, frame)).isAnimated());
        assertFalse(MediaTags.read(webp(header, anim, frame)).isAnimated());
    }

    @Test
    public void aSequenceIsAnimated() throws IOException {
        byte[] still = MediaTagsBoxTest.heif(0, null, null, new byte[0]);
        assertFalse(MediaTags.read(still).isAnimated());
        byte[] sequence = concat(still, box("moov", box("mvhd", new byte[100])));
        assertTrue(MediaTags.read(sequence).isAnimated());
    }

    @Test
    public void otherFormatsDoNotAnimate() throws IOException {
        assertFalse(MediaTags.read(MediaTestFiles.movie()).isAnimated());
        assertFalse(MediaTags.read(MediaTestFiles.mp3WithCover(null)).isAnimated());
    }
}
