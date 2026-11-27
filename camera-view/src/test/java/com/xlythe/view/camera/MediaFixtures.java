package com.xlythe.view.camera;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Small files of each format the metadata layer reads, built byte by byte for the tests. */
final class MediaFixtures {
    private MediaFixtures() {}

    /** Writes bytes to a new temporary file. */
    static File file(byte[] bytes, String suffix) throws IOException {
        File file = File.createTempFile("media", suffix);
        file.deleteOnExit();
        Files.write(file.toPath(), bytes);
        return file;
    }

    static byte[] read(File file) throws IOException {
        return Files.readAllBytes(file.toPath());
    }

    static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] concat(byte[]... parts) {
        return Bytes.concat(parts);
    }

    // ---------- XMP ----------

    /** A packet with these attributes on one description, declaring the namespaces given. */
    static String xmp(String declarations, String attributes) {
        return "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\""
                + Xmp.NS_RDF + "\"><rdf:Description rdf:about=\"\" " + declarations + " "
                + attributes + "/></rdf:RDF></x:xmpmeta>";
    }

    /** A packet with a body of elements inside one description. */
    static String xmpElements(String declarations, String body) {
        return "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\""
                + Xmp.NS_RDF + "\"><rdf:Description rdf:about=\"\" " + declarations + ">"
                + body + "</rdf:Description></rdf:RDF></x:xmpmeta>";
    }

    // ---------- JPEG ----------

    /** A JPEG of segments then a tiny scan and an end, which decoders need not understand. */
    static byte[] jpeg(byte[]... segments) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);
        for (byte[] segment : segments) {
            out.write(segment, 0, segment.length);
        }
        out.write(0xFF);
        out.write(0xDA);
        out.write(0);
        out.write(2);
        out.write(1);
        out.write(2);
        out.write(0xFF);
        out.write(0xD9);
        return out.toByteArray();
    }

    static byte[] segment(int marker, byte[] identifier, byte[] payload) {
        return JpegContainer.segment(marker, identifier, payload);
    }

    static byte[] xmpSegment(String packet) {
        return segment(JpegContainer.APP1, JpegContainer.XMP_IDENTIFIER, utf8(packet));
    }

    /** An Exif segment holding a TIFF block. */
    static byte[] exifSegment(byte[] tiff) {
        return segment(JpegContainer.APP1, ExifImageContainer.EXIF_IDENTIFIER, tiff);
    }

    /** The TIFF block of a JPEG fixture whose private directory holds the given extra tags. */
    static byte[] tiff() {
        return ExifImageContainer.unwrap(ExifFixtures.exifJpeg(false));
    }

    // ---------- PNG ----------

    /** A one-pixel PNG with the given chunks before its image data. */
    static byte[] png(byte[]... chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(PngContainer.SIGNATURE, 0, 8);
        byte[] ihdr = new byte[13];
        ihdr[3] = 1;
        ihdr[7] = 1;
        ihdr[8] = 8;
        ihdr[9] = 2;
        write(out, PngContainer.chunk("IHDR", ihdr));
        for (byte[] chunk : chunks) {
            write(out, chunk);
        }
        write(out, PngContainer.chunk("IDAT", new byte[] {0x78, 0x01, 0x01, 0x04, 0x00,
                (byte) 0xFB, (byte) 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x04, 0x00, 0x01}));
        write(out, PngContainer.chunk("IEND", new byte[0]));
        return out.toByteArray();
    }

    // ---------- WebP ----------

    /** A simple lossy WebP of a given size, with a fake bitstream after its frame header. */
    static byte[] webp(int width, int height) {
        byte[] frame = new byte[30];
        frame[3] = (byte) 0x9D;
        frame[4] = 0x01;
        frame[5] = 0x2A;
        Bytes.put16le(frame, 6, width);
        Bytes.put16le(frame, 8, height);
        return riff("WEBP", riffChunk("VP8 ", frame));
    }

    /** A simple lossless WebP with alpha. */
    static byte[] webpLossless(int width, int height) {
        byte[] frame = new byte[20];
        frame[0] = 0x2F;
        long bits = (width - 1) | ((long) (height - 1) << 14) | (1L << 28);
        Bytes.put32le(frame, 1, bits);
        return riff("WEBP", riffChunk("VP8L", frame));
    }

    static byte[] riff(String form, byte[]... chunks) {
        byte[] body = concat(chunks);
        byte[] header = new byte[12];
        System.arraycopy(ascii("RIFF"), 0, header, 0, 4);
        Bytes.put32le(header, 4, body.length + 4);
        System.arraycopy(ascii(form), 0, header, 8, 4);
        return concat(header, body);
    }

    static byte[] riffChunk(String id, byte[] data) {
        return WavContainer.chunk(id, data);
    }

    // ---------- ISO boxes ----------

    static byte[] box(String type, byte[]... contents) {
        return IsoBoxes.box(type, contents);
    }

    /** A full box: version and flags, then contents. */
    static byte[] fullBox(String type, int version, int flags, byte[]... contents) {
        byte[] header = {(byte) version, (byte) (flags >> 16), (byte) (flags >> 8),
                (byte) flags};
        byte[][] all = new byte[contents.length + 1][];
        all[0] = header;
        System.arraycopy(contents, 0, all, 1, contents.length);
        return IsoBoxes.box(type, all);
    }

    static byte[] u16(int value) {
        byte[] bytes = new byte[2];
        Bytes.put16(bytes, 0, value);
        return bytes;
    }

    static byte[] u32(long value) {
        byte[] bytes = new byte[4];
        Bytes.put32(bytes, 0, value);
        return bytes;
    }

    static byte[] ftyp(String brand, String... compatible) {
        List<byte[]> parts = new ArrayList<>();
        parts.add(ascii(brand));
        parts.add(u32(0));
        for (String other : compatible) {
            parts.add(ascii(other));
        }
        return box("ftyp", parts.toArray(new byte[0][]));
    }

    /** A movie header, version 0, created at a time in seconds since 1904. */
    static byte[] mvhd(long created) {
        byte[] body = new byte[96];
        Bytes.put32(body, 0, created);
        Bytes.put32(body, 8, 1000);
        return fullBox("mvhd", 0, 0, body);
    }

    /** A track header, version 0, with a matrix's a, b, c and d, and a size. */
    static byte[] tkhd(int a, int b, int c, int d, int width, int height) {
        byte[] body = new byte[80];
        int matrix = 36;
        Bytes.put32(body, matrix, a);
        Bytes.put32(body, matrix + 4, b);
        Bytes.put32(body, matrix + 12, c);
        Bytes.put32(body, matrix + 16, d);
        Bytes.put32(body, matrix + 32, 0x40000000);
        Bytes.put32(body, 72, (long) width << 16);
        Bytes.put32(body, 76, (long) height << 16);
        return fullBox("tkhd", 0, 0, body);
    }

    static byte[] hdlr(String handler) {
        byte[] body = new byte[4 + 4 + 12 + 1];
        System.arraycopy(ascii(handler), 0, body, 4, 4);
        return fullBox("hdlr", 0, 0, body);
    }

    /** A video sample entry of a type, with child boxes after its fixed fields. */
    static byte[] visualEntry(String type, byte[]... children) {
        byte[] fixed = new byte[78];
        Bytes.put16(fixed, 6, 1);
        Bytes.put16(fixed, 24, 64);
        Bytes.put16(fixed, 26, 48);
        byte[][] all = new byte[children.length + 1][];
        all[0] = fixed;
        System.arraycopy(children, 0, all, 1, children.length);
        return box(type, all);
    }

    /** A video track whose only sample entry is this one, with a chunk offset table. */
    static byte[] videoTrack(byte[] tkhd, byte[] sampleEntry, long chunkOffset) {
        byte[] stsd = fullBox("stsd", 0, 0, u32(1), sampleEntry);
        byte[] stco = fullBox("stco", 0, 0, u32(1), u32(chunkOffset));
        byte[] stbl = box("stbl", stsd, stco);
        return box("trak", tkhd, box("mdia", hdlr("vide"), box("minf", stbl)));
    }

    static byte[] audioTrack() {
        byte[] stsd = fullBox("stsd", 0, 0, u32(0));
        return box("trak", tkhd(0x10000, 0, 0, 0x10000, 0, 0),
                box("mdia", hdlr("soun"), box("minf", box("stbl", stsd))));
    }

    /** An nclx colour box with a transfer. */
    static byte[] colr(int transfer) {
        byte[] body = new byte[11];
        System.arraycopy(ascii("nclx"), 0, body, 0, 4);
        Bytes.put16(body, 4, 9);
        Bytes.put16(body, 6, transfer);
        Bytes.put16(body, 8, 9);
        return box("colr", body);
    }

    /** An MP4 of these top-level boxes. */
    static byte[] mp4(byte[]... boxes) {
        return concat(boxes);
    }

    // ---------- Audio ----------

    /** An ID3v2 tag of frames, in a version, with padding. */
    static byte[] id3(int version, int padding, Id3Container.Frame... frames) {
        List<Id3Container.Frame> list = new ArrayList<>();
        for (Id3Container.Frame frame : frames) {
            list.add(frame);
        }
        return Id3Container.tag(version, list, padding);
    }

    static Id3Container.Frame textFrame(String id, String text) {
        return new Id3Container.Frame(id, 0, concat(new byte[] {3}, utf8(text)));
    }

    /** One MPEG audio frame header, for a file to look like an MP3 after its tag. */
    static byte[] mp3Frames() {
        byte[] frames = new byte[417];
        frames[0] = (byte) 0xFF;
        frames[1] = (byte) 0xFB;
        frames[2] = (byte) 0x90;
        frames[3] = 0x64;
        return frames;
    }

    /** A FLAC: its marker, stream information, these blocks, and fake frames. */
    static byte[] flac(byte[]... blocks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, ascii("fLaC"));
        byte[] info = new byte[34];
        write(out, flacBlock(0, info, blocks.length == 0));
        for (int i = 0; i < blocks.length; i++) {
            byte[] block = blocks[i].clone();
            if (i == blocks.length - 1) {
                block[0] |= (byte) 0x80;
            }
            write(out, block);
        }
        write(out, new byte[] {(byte) 0xFF, (byte) 0xF8, 0x69, 0x08, 0, 0, 0, 0});
        return out.toByteArray();
    }

    static byte[] flacBlock(int type, byte[] data, boolean last) {
        byte[] header = new byte[4];
        header[0] = (byte) ((last ? 0x80 : 0) | type);
        Bytes.put24(header, 1, data.length);
        return concat(header, data);
    }

    static byte[] vorbisComments(String... entries) {
        List<String> list = new ArrayList<>();
        for (String entry : entries) {
            list.add(entry);
        }
        return new VorbisComments("test vendor", list).toBytes();
    }

    /** An Ogg Vorbis file: identification, comment and setup headers, then audio pages. */
    static byte[] oggVorbis(String... comments) {
        byte[] identification = concat(ascii("\u0001vorbis"), new byte[23]);
        byte[] comment = concat(ascii("\u0003vorbis"), vorbisComments(comments),
                new byte[] {1});
        byte[] setup = concat(ascii("\u0005vorbis"), new byte[300]);
        return ogg(identification, comment, setup);
    }

    /** An Ogg Opus file: head and tags, then audio pages. */
    static byte[] oggOpus(String... comments) {
        byte[] head = concat(ascii("OpusHead"), new byte[11]);
        byte[] tags = concat(ascii("OpusTags"), vorbisComments(comments));
        return ogg(head, tags);
    }

    private static byte[] ogg(byte[]... headers) {
        List<byte[]> packets = new ArrayList<>();
        for (byte[] header : headers) {
            packets.add(header);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] page : OggContainer.headerPages(packets, 0x1234)) {
            write(out, page);
        }
        for (int sequence = 0; sequence < 3; sequence++) {
            List<byte[]> audio = new ArrayList<>();
            audio.add(new byte[] {1, 2, 3});
            audio.add(new byte[100 + sequence]);
            List<byte[]> pages = OggContainer.headerPages(audio, 0x1234);
            byte[] page = pages.get(1).clone();
            // Numbered after the headers, with a granule position, as audio is.
            int headerPages = OggContainer.headerPages(packets, 0x1234).size();
            Bytes.put32le(page, 18, headerPages + sequence);
            page[6] = (byte) (sequence + 1);
            Bytes.put32le(page, 22, 0);
            Bytes.put32le(page, 22, OggContainer.crc(page));
            write(out, page);
        }
        return out.toByteArray();
    }

    /** A WAV of one second of silence, with extra chunks after the audio. */
    static byte[] wav(byte[]... after) {
        byte[] format = new byte[16];
        Bytes.put16le(format, 0, 1);
        Bytes.put16le(format, 2, 1);
        Bytes.put32le(format, 4, 8000);
        Bytes.put32le(format, 8, 16000);
        Bytes.put16le(format, 12, 2);
        Bytes.put16le(format, 14, 16);
        byte[][] chunks = new byte[after.length + 2][];
        chunks[0] = riffChunk("fmt ", format);
        chunks[1] = riffChunk("data", new byte[64]);
        System.arraycopy(after, 0, chunks, 2, after.length);
        return riff("WAVE", chunks);
    }

    static byte[] infoList(String... pairs) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        write(data, ascii("INFO"));
        for (int i = 0; i < pairs.length; i += 2) {
            write(data, riffChunk(pairs[i], concat(utf8(pairs[i + 1]), new byte[1])));
        }
        return riffChunk("LIST", data.toByteArray());
    }

    private static void write(ByteArrayOutputStream out, byte[] bytes) {
        out.write(bytes, 0, bytes.length);
    }
}
