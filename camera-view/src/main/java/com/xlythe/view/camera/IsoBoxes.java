package com.xlythe.view.camera;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Boxes, the building blocks of MP4, QuickTime, 3GP, M4A, HEIF and AVIF: a size, a type, and
 * either data or more boxes. A size of 1 means a 64-bit size follows the type, and a size of 0
 * means the box runs to the end of whatever holds it.
 */
final class IsoBoxes {
    /** A box's place, in a file or in an array. */
    static final class Box {
        /** Its type, such as "moov". */
        @NonNull
        final String type;

        /** Where it starts. */
        final long offset;

        /** How long its header is: 8, 16 with a 64-bit size, plus 16 for a uuid type. */
        final int header;

        /** Its whole size, header included. */
        final long size;

        /** Whether its size field said it runs to the end of what holds it. */
        final boolean toEnd;

        Box(@NonNull String type, long offset, int header, long size, boolean toEnd) {
            this.type = type;
            this.offset = offset;
            this.header = header;
            this.size = size;
            this.toEnd = toEnd;
        }

        /** Where its contents start. */
        long data() {
            return offset + header;
        }

        /** One past its end. */
        long end() {
            return offset + size;
        }

        /** How many bytes of contents it has. */
        long dataSize() {
            return size - header;
        }
    }

    private IsoBoxes() {}

    /**
     * The boxes in a range of a file, until they stop making sense.
     *
     * @return the boxes, which stop short of the range's end if one does not fit
     */
    @WorkerThread
    @NonNull
    static List<Box> children(@NonNull ByteSource source, long start, long end)
            throws IOException {
        List<Box> boxes = new ArrayList<>();
        long at = start;
        byte[] header = new byte[16];
        while (at + 8 <= end) {
            int read = source.read(at, header, 0, (int) Math.min(16, end - at));
            Box box = parse(header, 0, read, at, end - at);
            if (box == null) {
                break;
            }
            boxes.add(box);
            at = box.end();
        }
        return boxes;
    }

    /** The boxes in a range of an array. */
    @NonNull
    static List<Box> children(@NonNull byte[] data, int start, int end) {
        List<Box> boxes = new ArrayList<>();
        long at = start;
        while (at + 8 <= end) {
            Box box = parse(data, (int) at, (int) Math.min(16, end - at), at, end - at);
            if (box == null) {
                break;
            }
            boxes.add(box);
            at = box.end();
        }
        return boxes;
    }

    /** The boxes inside a box held in an array, after a number of bytes of its own. */
    @NonNull
    static List<Box> children(@NonNull byte[] data, @NonNull Box parent, int skip) {
        return children(data, (int) parent.data() + skip, (int) parent.end());
    }

    /**
     * Reads a box header.
     *
     * @param available how many header bytes were read
     * @param remaining how far it is to the end of what holds the box
     * @return the box, or null if it does not fit
     */
    @Nullable
    private static Box parse(@NonNull byte[] data, int at, int available, long offset,
            long remaining) {
        if (available < 8) {
            return null;
        }
        long size = Bytes.u32(data, at);
        String type = Bytes.fourCc(data, at + 4);
        int header = 8;
        boolean toEnd = false;
        if (size == 1) {
            if (available < 16) {
                return null;
            }
            size = Bytes.u64(data, at + 8);
            header = 16;
        } else if (size == 0) {
            size = remaining;
            toEnd = true;
        }
        if (size < header || size > remaining) {
            return null;
        }
        if ("uuid".equals(type)) {
            header += 16;
            if (size < header) {
                return null;
            }
        }
        return new Box(type, offset, header, size, toEnd);
    }

    /** The first box of a type in a list, or null. */
    @Nullable
    static Box find(@NonNull List<Box> boxes, @NonNull String type) {
        for (Box box : boxes) {
            if (type.equals(box.type)) {
                return box;
            }
        }
        return null;
    }

    /** A box found by following a path of types down from a box in an array, or null. */
    @Nullable
    static Box path(@NonNull byte[] data, @NonNull Box from, @NonNull String... types) {
        Box box = from;
        for (String type : types) {
            box = find(children(data, box, "meta".equals(box.type) ? metaSkip(data, box) : 0),
                    type);
            if (box == null) {
                return null;
            }
        }
        return box;
    }

    /**
     * How many bytes of its own a meta box has before its children: four for ISO's, which has a
     * version and flags, and none for QuickTime's, whose first four bytes are a child's size.
     */
    static int metaSkip(@NonNull byte[] data, @NonNull Box meta) {
        int at = (int) meta.data();
        if (meta.dataSize() < 8 || Bytes.u32(data, at) == 0) {
            return meta.dataSize() >= 4 ? 4 : 0;
        }
        long firstSize = Bytes.u32(data, at);
        return firstSize >= 8 && firstSize <= meta.dataSize() ? 0 : 4;
    }

    /** A box's contents as an array. */
    @NonNull
    static byte[] data(@NonNull byte[] data, @NonNull Box box) {
        byte[] contents = new byte[(int) box.dataSize()];
        System.arraycopy(data, (int) box.data(), contents, 0, contents.length);
        return contents;
    }

    /** The 16 bytes of a uuid box's extended type. */
    @NonNull
    static byte[] uuid(@NonNull byte[] data, @NonNull Box box) {
        byte[] uuid = new byte[16];
        System.arraycopy(data, (int) box.data() - 16, uuid, 0, 16);
        return uuid;
    }

    /** A box made of a type and contents. */
    @NonNull
    static byte[] box(@NonNull String type, @NonNull byte[]... contents) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] part : contents) {
            body.write(part, 0, part.length);
        }
        byte[] inner = body.toByteArray();
        byte[] box = new byte[8 + inner.length];
        Bytes.put32(box, 0, box.length);
        System.arraycopy(Bytes.fourCc(type), 0, box, 4, 4);
        System.arraycopy(inner, 0, box, 8, inner.length);
        return box;
    }

    /** A box made of a type and a list of children. */
    @NonNull
    static byte[] box(@NonNull String type, @NonNull List<byte[]> children) {
        return box(type, children.toArray(new byte[0][]));
    }

    /** A uuid box with an extended type and contents. */
    @NonNull
    static byte[] uuidBox(@NonNull byte[] uuid, @NonNull byte[] contents) {
        return box("uuid", uuid, contents);
    }

    /** A free box of a size, at least eight bytes, which readers skip. */
    @NonNull
    static byte[] free(int size) {
        byte[] free = new byte[size];
        Bytes.put32(free, 0, size);
        System.arraycopy(Bytes.fourCc("free"), 0, free, 4, 4);
        return free;
    }

    /** A box's bytes, header and all, from an array. */
    @NonNull
    static byte[] bytes(@NonNull byte[] data, @NonNull Box box) {
        byte[] bytes = new byte[(int) box.size];
        System.arraycopy(data, (int) box.offset, bytes, 0, bytes.length);
        return bytes;
    }
}
