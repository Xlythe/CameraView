package com.xlythe.view.camera;

import static com.xlythe.view.camera.MediaFixtures.concat;
import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** How an HEVC sequence parameter set says its transfer, through every optional part of it. */
@RunWith(RobolectricTestRunner.class)
public class HevcParametersTest {
    /** What a parameter set holds beyond the bare minimum, for each test to choose. */
    static final class Sps {
        int subLayers;
        int chroma = 1;
        boolean conformance;
        boolean ordering = true;
        boolean scaling;
        boolean pcm;
        int shortTermSets;
        int longTerm;
        boolean vui = true;
        boolean aspect;
        boolean overscan;
        boolean signal = true;
        boolean colour = true;
        int transfer = 16;

        byte[] bytes() {
            AvcParametersTest.BitWriter w = new AvcParametersTest.BitWriter();
            w.bits(0, 4).bits(subLayers, 3).flag(true);
            // The profile, tier and level, then each sub-layer's.
            w.bits(0, 32).bits(0, 32).bits(0, 32);
            for (int i = 0; i < subLayers; i++) {
                w.flag(i % 2 == 0).flag(true);
            }
            if (subLayers > 0) {
                w.bits(0, 2 * (8 - subLayers));
            }
            for (int i = 0; i < subLayers; i++) {
                if (i % 2 == 0) {
                    w.bits(0, 32).bits(0, 32).bits(0, 24);
                }
                w.bits(0, 8);
            }
            w.ue(0).ue(chroma);
            if (chroma == 3) {
                w.flag(false);
            }
            w.ue(1920).ue(1080).flag(conformance);
            if (conformance) {
                w.ue(0).ue(0).ue(0).ue(4);
            }
            w.ue(2).ue(2).ue(4).flag(ordering);
            for (int i = ordering ? 0 : subLayers; i <= subLayers; i++) {
                w.ue(4).ue(2).ue(0);
            }
            for (int i = 0; i < 6; i++) {
                w.ue(1);
            }
            w.flag(scaling);
            if (scaling) {
                w.flag(true);
                for (int size = 0; size < 4; size++) {
                    for (int matrix = 0; matrix < 6; matrix += size == 3 ? 3 : 1) {
                        boolean explicit = (size + matrix) % 2 == 0;
                        w.flag(explicit);
                        if (!explicit) {
                            w.ue(0);
                        } else {
                            int coefficients = Math.min(64, 1 << (4 + (size << 1)));
                            if (size > 1) {
                                w.se(8);
                            }
                            for (int i = 0; i < coefficients; i++) {
                                w.se(1);
                            }
                        }
                    }
                }
            }
            w.flag(false).flag(true).flag(pcm);
            if (pcm) {
                w.bits(0x77, 8).ue(0).ue(1).flag(false);
            }
            w.ue(shortTermSets);
            for (int i = 0; i < shortTermSets; i++) {
                if (i == 0) {
                    // Two pictures before, one after.
                    w.ue(2).ue(1);
                    for (int p = 0; p < 3; p++) {
                        w.ue(p).flag(true);
                    }
                } else {
                    // Predicted from the one before: of its three, two used, one not at all.
                    w.flag(true).flag(false).ue(0);
                    w.flag(true).flag(true).flag(false).flag(false).flag(true);
                }
            }
            w.flag(longTerm > 0);
            if (longTerm > 0) {
                w.ue(longTerm);
                for (int i = 0; i < longTerm; i++) {
                    w.bits(0, 8).flag(true);
                }
            }
            w.flag(true).flag(true).flag(vui);
            if (vui) {
                w.flag(aspect);
                if (aspect) {
                    w.bits(255, 8).bits(4, 16).bits(3, 16);
                }
                w.flag(overscan);
                if (overscan) {
                    w.flag(true);
                }
                w.flag(signal);
                if (signal) {
                    w.bits(5, 3).flag(false).flag(colour);
                    if (colour) {
                        w.bits(9, 8).bits(transfer, 8).bits(9, 8);
                    }
                }
            }
            return w.bytes();
        }
    }

    /** An hvcC whose arrays hold a video parameter set, then a sequence parameter set. */
    static byte[] hvcC(byte[] sps) {
        byte[] header = new byte[22];
        header[0] = 1;
        byte[] vps = {0x40, 0x01, 0x0C, 0x01};
        byte[] nal = concat(new byte[] {0x42, 0x01}, sps);
        return concat(header, new byte[] {2},
                new byte[] {(byte) 0xA0, 0, 1, 0, (byte) vps.length}, vps,
                new byte[] {(byte) 0xA1, 0, 1, (byte) (nal.length >> 8), (byte) nal.length}, nal);
    }

    @Test
    public void readsTheSimplestSet() {
        Sps sps = new Sps();
        assertEquals(16, HevcParameters.spsTransfer(sps.bytes()));
        byte[] hvcC = hvcC(sps.bytes());
        assertEquals(16, HevcParameters.transferCharacteristics(hvcC, 0, hvcC.length));
    }

    @Test
    public void readsPastEveryOptionalPart() {
        Sps sps = new Sps();
        sps.subLayers = 3;
        sps.chroma = 3;
        sps.conformance = true;
        sps.ordering = false;
        sps.scaling = true;
        sps.pcm = true;
        sps.shortTermSets = 2;
        sps.longTerm = 2;
        sps.aspect = true;
        sps.overscan = true;
        sps.transfer = 18;
        assertEquals(18, HevcParameters.spsTransfer(sps.bytes()));
        byte[] hvcC = hvcC(sps.bytes());
        assertEquals(18, HevcParameters.transferCharacteristics(hvcC, 0, hvcC.length));
    }

    @Test
    public void saysNothingWithoutColour() {
        Sps noVui = new Sps();
        noVui.vui = false;
        assertEquals(-1, HevcParameters.spsTransfer(noVui.bytes()));
        Sps noSignal = new Sps();
        noSignal.signal = false;
        assertEquals(-1, HevcParameters.spsTransfer(noSignal.bytes()));
        Sps noColour = new Sps();
        noColour.colour = false;
        assertEquals(-1, HevcParameters.spsTransfer(noColour.bytes()));
        byte[] hvcC = hvcC(noColour.bytes());
        assertEquals(-1, HevcParameters.transferCharacteristics(hvcC, 0, hvcC.length));
    }

    @Test
    public void refusesWhatItCannotRead() {
        assertEquals(-1, HevcParameters.transferCharacteristics(new byte[22], 0, 22));
        byte[] hvcC = hvcC(new Sps().bytes());
        // Cut inside the sequence parameter set, whose length runs past what is left.
        assertEquals(-1, HevcParameters.transferCharacteristics(hvcC, 0, hvcC.length - 3));
        // A parameter set too short to hold its own fields.
        byte[] cut = hvcC(new byte[] {1, 2});
        assertEquals(-1, HevcParameters.transferCharacteristics(cut, 0, cut.length));
    }
}
