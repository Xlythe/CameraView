package com.xlythe.view.camera;

import static com.xlythe.view.camera.MediaFixtures.box;
import static com.xlythe.view.camera.MediaFixtures.concat;
import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** How H.264 and MPEG-4 Part 2 streams say their transfer, when their container does not. */
@RunWith(RobolectricTestRunner.class)
public class AvcParametersTest {
    /** Writes bits most significant first, and Exp-Golomb codes. */
    static final class BitWriter {
        /** The bits written so far. */
        private final StringBuilder mBits = new StringBuilder();

        BitWriter bits(int value, int count) {
            for (int i = count - 1; i >= 0; i--) {
                mBits.append((value >> i) & 1);
            }
            return this;
        }

        BitWriter flag(boolean value) {
            return bits(value ? 1 : 0, 1);
        }

        BitWriter ue(int value) {
            int code = value + 1;
            int length = 32 - Integer.numberOfLeadingZeros(code);
            return bits(0, length - 1).bits(code, length);
        }

        BitWriter se(int value) {
            return ue(value > 0 ? 2 * value - 1 : -2 * value);
        }

        byte[] bytes() {
            // The stop bit, then zeros to a whole byte.
            StringBuilder padded = new StringBuilder(mBits).append('1');
            while (padded.length() % 8 != 0) {
                padded.append('0');
            }
            byte[] out = new byte[padded.length() / 8];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(padded.substring(i * 8, i * 8 + 8), 2);
            }
            return out;
        }
    }

    /** A baseline sequence parameter set whose video usability information says a transfer. */
    static byte[] baselineSps(int transfer) {
        BitWriter w = new BitWriter().bits(66, 8).bits(0, 8).bits(30, 8).ue(0);
        w.ue(0).ue(0).ue(0).ue(1).flag(false).ue(39).ue(29).flag(true).flag(true).flag(false);
        // The video usability information: an aspect ratio of its own, overscan, then colour.
        w.flag(true).flag(true).bits(255, 8).bits(4, 16).bits(3, 16);
        w.flag(true).flag(false);
        w.flag(true).bits(5, 3).flag(false).flag(true).bits(9, 8).bits(transfer, 8).bits(9, 8);
        return w.bytes();
    }

    /**
     * A high profile sequence parameter set, with scaling lists, the second picture order count
     * type, interlace and cropping, before the same colour.
     */
    static byte[] highSps(int transfer, boolean colour) {
        BitWriter w = new BitWriter().bits(100, 8).bits(0, 8).bits(51, 8).ue(0);
        w.ue(1).ue(2).ue(2).flag(false).flag(true);
        // The first list ends early when a delta brings it to zero; the second runs its length.
        w.flag(true).se(-8);
        w.flag(true);
        for (int i = 0; i < 16; i++) {
            w.se(1);
        }
        for (int i = 2; i < 8; i++) {
            w.flag(false);
        }
        w.ue(0).ue(1).flag(false).se(-1).se(2).ue(2).se(1).se(-1);
        w.ue(4).flag(false).ue(119).ue(33).flag(false).flag(true).flag(true);
        w.flag(true).ue(0).ue(0).ue(0).ue(4);
        w.flag(true).flag(false).flag(false);
        w.flag(colour);
        if (colour) {
            w.bits(5, 3).flag(true).flag(true).bits(1, 8).bits(transfer, 8).bits(1, 8);
        }
        return w.bytes();
    }

    /** An avcC holding a sequence parameter set, behind a picture parameter set's place. */
    static byte[] avcC(byte[] sps) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(1);
        out.write(sps[0]);
        out.write(0);
        out.write(sps[2]);
        out.write(0xFF);
        out.write(0xE1);
        out.write((sps.length + 1) >> 8);
        out.write(sps.length + 1);
        out.write(0x67);
        out.write(sps, 0, sps.length);
        out.write(1);
        out.write(0);
        out.write(2);
        out.write(0x68);
        out.write(0xCE);
        return out.toByteArray();
    }

    /** An esds whose MPEG-4 visual configuration opens with a visual object of a transfer. */
    static byte[] esds(int transfer, int esFlags) {
        BitWriter object = new BitWriter().flag(true).bits(2, 4).bits(1, 3).bits(1, 4)
                .flag(true).bits(5, 3).flag(false).flag(true).bits(6, 8).bits(transfer, 8)
                .bits(6, 8);
        byte[] visual = concat(new byte[] {0, 0, 1, (byte) 0xB0, 8}, new byte[] {0, 0, 1,
                (byte) 0xB5}, object.bytes());
        byte[] specific = concat(new byte[] {5, (byte) visual.length}, visual);
        byte[] config = concat(new byte[] {4, (byte) (13 + specific.length), 0x20, 0x11, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0}, specific);
        ByteArrayOutputStream es = new ByteArrayOutputStream();
        es.write(0);
        es.write(1);
        es.write(esFlags);
        if ((esFlags & 0x80) != 0) {
            es.write(0);
            es.write(2);
        }
        if ((esFlags & 0x40) != 0) {
            es.write(3);
            es.write('u');
            es.write('r');
            es.write('l');
        }
        if ((esFlags & 0x20) != 0) {
            es.write(0);
            es.write(3);
        }
        byte[] fields = es.toByteArray();
        byte[] descriptor = concat(new byte[] {3, (byte) 0x80, (byte) (fields.length
                + config.length)}, fields, config);
        return concat(new byte[4], descriptor);
    }

    @Test
    public void readsABaselineParameterSet() {
        assertEquals(18, AvcParameters.spsTransfer(baselineSps(18)));
        byte[] avcC = avcC(baselineSps(16));
        assertEquals(16, AvcParameters.transferCharacteristics(avcC, 0, avcC.length));
    }

    @Test
    public void readsAHighProfileParameterSet() {
        assertEquals(14, AvcParameters.spsTransfer(highSps(14, true)));
        assertEquals(-1, AvcParameters.spsTransfer(highSps(14, false)));
    }

    @Test
    public void saysNothingWithoutColour() {
        BitWriter w = new BitWriter().bits(66, 8).bits(0, 8).bits(30, 8).ue(0);
        w.ue(0).ue(2).ue(1).flag(false).ue(39).ue(29).flag(true).flag(true).flag(false);
        assertEquals("no video usability information", -1,
                AvcParameters.spsTransfer(w.flag(false).bytes()));
        BitWriter noSignal = new BitWriter().bits(66, 8).bits(0, 8).bits(30, 8).ue(0);
        noSignal.ue(0).ue(2).ue(1).flag(false).ue(39).ue(29).flag(true).flag(true).flag(false);
        noSignal.flag(true).flag(false).flag(false).flag(false);
        assertEquals("no signal type", -1, AvcParameters.spsTransfer(noSignal.bytes()));
    }

    @Test
    public void refusesWhatItCannotRead() {
        assertEquals(-1, AvcParameters.transferCharacteristics(new byte[3], 0, 3));
        byte[] avcC = avcC(baselineSps(16));
        // Cut short inside the parameter set, which runs past what is left.
        assertEquals(-1, AvcParameters.transferCharacteristics(avcC, 0, 12));
        // A parameter set too short to hold its own fields says nothing.
        byte[] truncated = avcC(new byte[] {66, 0, 30});
        assertEquals(-1, AvcParameters.transferCharacteristics(truncated, 0, truncated.length));
        // A picture parameter set first is passed over.
        byte[] other = avcC(baselineSps(18));
        other[8] = 0x68;
        assertEquals(-1, AvcParameters.transferCharacteristics(other, 0, other.length));
    }

    @Test
    public void readsAnMpeg4VisualObject() {
        for (int flags : new int[] {0, 0x80, 0x40, 0x20, 0xE0}) {
            byte[] esds = esds(6, flags);
            assertEquals("flags " + flags, 6, AvcParameters.esdsTransfer(esds, 0, esds.length));
        }
        assertEquals(-1, AvcParameters.esdsTransfer(new byte[8], 0, 8));
        byte[] cut = esds(6, 0);
        assertEquals(-1, AvcParameters.esdsTransfer(cut, 0, 12));
    }

    @Test
    public void aVisualObjectOfAnotherKindSaysNothing() {
        byte[] still = new BitWriter().flag(false).bits(3, 4).bytes();
        byte[] data = concat(new byte[] {0, 0, 1, (byte) 0xB5}, still);
        assertEquals(-1, AvcParameters.visualObjectTransfer(data, 0, data.length));
        byte[] noSignal = concat(new byte[] {0, 0, 1, (byte) 0xB5},
                new BitWriter().flag(false).bits(1, 4).flag(false).bytes());
        assertEquals(-1, AvcParameters.visualObjectTransfer(noSignal, 0, noSignal.length));
        byte[] noColour = concat(new byte[] {0, 0, 1, (byte) 0xB5},
                new BitWriter().flag(false).bits(1, 4).flag(true).bits(5, 3).flag(false)
                        .flag(false).bytes());
        assertEquals(-1, AvcParameters.visualObjectTransfer(noColour, 0, noColour.length));
        assertEquals(-1, AvcParameters.visualObjectTransfer(new byte[8], 0, 8));
    }

    @Test
    public void anMp4SaysItsStreamsTransfer() throws IOException {
        assertEquals(MediaTags.TRANSFER_HLG, MediaTags.read(MediaTagsBoxTest.movie(new byte[0],
                box("avcC", avcC(baselineSps(18))))).getVideoTransfer());
        assertEquals(MediaTags.TRANSFER_SDR, MediaTags.read(MediaTagsBoxTest.movie(new byte[0],
                MediaFixtures.fullBox("esds", 0, 0, concatTail(esds(6, 0))))).getVideoTransfer());
    }

    @Test
    public void everyStandardCurveButTwoIsStandardRange() {
        assertEquals(MediaTags.TRANSFER_UNKNOWN, Mp4Container.transferOf(-1));
        assertEquals(MediaTags.TRANSFER_UNKNOWN, Mp4Container.transferOf(0));
        assertEquals(MediaTags.TRANSFER_UNKNOWN, Mp4Container.transferOf(2));
        assertEquals(MediaTags.TRANSFER_UNKNOWN, Mp4Container.transferOf(3));
        assertEquals(MediaTags.TRANSFER_SDR, Mp4Container.transferOf(9));
        assertEquals(MediaTags.TRANSFER_SDR, Mp4Container.transferOf(17));
        assertEquals(MediaTags.TRANSFER_PQ, Mp4Container.transferOf(16));
        assertEquals(MediaTags.TRANSFER_HLG, Mp4Container.transferOf(18));
        assertEquals(MediaTags.TRANSFER_UNKNOWN, Mp4Container.transferOf(19));
    }

    @Test
    public void readsSignedCodes() {
        BitReader bits = new BitReader(new BitWriter().se(3).se(-2).se(0).bytes());
        assertEquals(3, bits.se());
        assertEquals(-2, bits.se());
        assertEquals(0, bits.se());
    }

    /** The esds fixture without the version and flags a full box adds itself. */
    private static byte[] concatTail(byte[] esds) {
        byte[] tail = new byte[esds.length - 4];
        System.arraycopy(esds, 4, tail, 0, tail.length);
        return tail;
    }
}
