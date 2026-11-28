package com.xlythe.view.camera.stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.media.MediaFormat;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The bytes a receiver sends to say what it plays.
 *
 * <p>They cross between devices running different versions, so the properties that matter are
 * that each release lists what it really decodes, that anything which is not capabilities reads
 * as a receiver that said nothing, and that a later version's additions are ignored rather than
 * turning the whole list down.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class PlaybackCapabilitiesTest {
    private static final AudioCodec.Query EVERYTHING = (mimeType, encoder) -> true;

    @Before
    public void setUp() {
        FakeAudioCodecs.reset();
    }

    @After
    public void tearDown() {
        FakeAudioCodecs.reset();
    }

    @Test
    public void eachReleaseListsWhatItDecodes() {
        assertArrayEquals("API 14: no MediaCodec",
                new int[] {AudioCodec.PCM},
                PlaybackCapabilities.parse(PlaybackCapabilities.forDevice(14, EVERYTHING)));
        assertArrayEquals("API 16: AAC",
                new int[] {AudioCodec.AAC, AudioCodec.PCM},
                PlaybackCapabilities.parse(PlaybackCapabilities.forDevice(16, EVERYTHING)));
        assertArrayEquals("API 21: Opus decodes from here, though it only encodes from 29",
                new int[] {AudioCodec.OPUS, AudioCodec.AAC, AudioCodec.PCM},
                PlaybackCapabilities.parse(PlaybackCapabilities.forDevice(21, EVERYTHING)));
    }

    /** Only decoders count: a device that can encode a format but not play it does not list it. */
    @Test
    public void thisDeviceListsItsDecoders() {
        FakeAudioCodecs.addEncoder(MediaFormat.MIMETYPE_AUDIO_OPUS);
        FakeAudioCodecs.addDecoder(MediaFormat.MIMETYPE_AUDIO_AAC);

        assertArrayEquals(new int[] {AudioCodec.AAC, AudioCodec.PCM},
                PlaybackCapabilities.parse(PlaybackCapabilities.forThisDevice()));
    }

    /** Anything that is not capabilities is a receiver that said nothing, and gets PCM. */
    @Test
    public void bytesThatAreNotCapabilitiesSayNothing() {
        assertNull(PlaybackCapabilities.parse(null));
        assertNull(PlaybackCapabilities.parse(new byte[0]));
        assertNull("too short", PlaybackCapabilities.parse(new byte[] {'X', 'C', 'A', 'P', 1}));
        assertNull("another app's message",
                PlaybackCapabilities.parse(new byte[] {'H', 'E', 'L', 'L', 'O', 1, 0}));
        assertNull("version 0 was never written",
                PlaybackCapabilities.parse(new byte[] {'X', 'C', 'A', 'P', 0, 0}));
        assertNull("a count longer than what follows",
                PlaybackCapabilities.parse(new byte[] {'X', 'C', 'A', 'P', 1, 3, 1}));
    }

    /** An empty list is still a receiver that reads the newer header. */
    @Test
    public void anEmptyListIsNotSilence() {
        int[] codecs = PlaybackCapabilities.parse(new byte[] {'X', 'C', 'A', 'P', 1, 0});

        assertNotNull(codecs);
        assertArrayEquals(new int[0], codecs);
    }

    /** A later version's formats and fields are skipped, and what this one knows is kept. */
    @Test
    public void aLaterVersionIsReadAsFarAsItIsUnderstood() {
        byte[] later = {'X', 'C', 'A', 'P', 2, 3, 9, AudioCodec.AAC, (byte) 200, 42, 42, 42};

        assertArrayEquals(new int[] {AudioCodec.AAC}, PlaybackCapabilities.parse(later));
    }
}
