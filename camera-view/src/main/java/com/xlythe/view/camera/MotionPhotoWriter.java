package com.xlythe.view.camera;

import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * Writes Google's Motion Photo format, version 1: a JPEG with a short MP4 appended to it and
 * described in the JPEG's XMP. {@link MediaTags#getMotionPhoto()} reads it back.
 *
 * <p>Two sets of XMP properties do the describing. The camera ones say the file is a motion photo
 * and which frame of the video the still was taken from. The container ones list the items the
 * file holds, in the order they are stored, with the length of every item after the primary image.
 * That length is how a reader finds the video without parsing the JPEG: it is the last
 * {@code Item:Length} bytes of the file.</p>
 *
 * <p>A JPEG that already carries XMP keeps it. An Ultra HDR picture lists its gain map in the same
 * container directory, and the format requires the gain map to stay ahead of the video, which
 * appending to both the file and the directory preserves.</p>
 *
 * <p>The video can also carry a metadata track that repeats the presentation timestamp and scores
 * frames for a model choosing the best one. Nothing here scores frames, so the descriptor
 * {@link #metadataTrackSample} builds says so with a model version of zero.</p>
 *
 * @see <a href="https://developer.android.com/media/platform/motion-photo-format">Motion Photo
 *     format 1.0</a>
 */
final class MotionPhotoWriter {
    /** The camera namespace, which says how to present the still and the video. */
    static final String NAMESPACE_CAMERA = "http://ns.google.com/photos/1.0/camera/";
    /** The container namespace, which holds the directory of items. */
    static final String NAMESPACE_CONTAINER = "http://ns.google.com/photos/1.0/container/";
    /** The item namespace, which describes each entry in the directory. */
    static final String NAMESPACE_ITEM = "http://ns.google.com/photos/1.0/container/item/";
    /** RDF, which every XMP packet is written in. */
    static final String NAMESPACE_RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    /** The XMP envelope around the RDF. */
    static final String NAMESPACE_XMP_META = "adobe:ns:meta/";
    /** Where namespace declarations live, as far as DOM is concerned. */
    private static final String NAMESPACE_XMLNS = "http://www.w3.org/2000/xmlns/";

    /**
     * The prefixes written. The format names {@code Camera} as the default for its camera
     * namespace, but every motion photo a phone has ever written uses {@code GCamera}, and a
     * reader that matches on text rather than on namespaces only knows that one.
     */
    static final String PREFIX_CAMERA = "GCamera";
    static final String PREFIX_CONTAINER = "Container";
    static final String PREFIX_ITEM = "Item";
    static final String PREFIX_RDF = "rdf";
    static final String PREFIX_XMP_META = "x";

    /** The version of the format this writes. */
    static final int VERSION = 1;

    /** {@code Item:Mime} for the primary image. */
    static final String MIME_JPEG = "image/jpeg";
    /** {@code Item:Mime} for the video. */
    static final String MIME_MP4 = "video/mp4";
    /** {@code Item:Semantic} for the image a viewer shows. */
    static final String SEMANTIC_PRIMARY = "Primary";
    /** {@code Item:Semantic} for the video. */
    static final String SEMANTIC_MOTION_PHOTO = "MotionPhoto";

    /** The MIME type the format gives the video's optional metadata track. */
    static final String METADATA_TRACK_MIME = "application/motionphoto-image-meta";


    /** The identifier that opens a JPEG's XMP segment, terminator included. */
    @VisibleForTesting
    static final byte[] XMP_SIGNATURE =
            "http://ns.adobe.com/xap/1.0/\0".getBytes(Bytes.UTF_8);
    /** The identifier that opens a JPEG's Exif segment. */
    private static final byte[] EXIF_SIGNATURE = {'E', 'x', 'i', 'f', 0, 0};
    /** The identifier that opens the Multi-Picture Format segment an Ultra HDR JPEG carries. */
    private static final byte[] MPF_SIGNATURE = {'M', 'P', 'F', 0};

    /**
     * The XMP packet wrapper, which marks where the packet starts and ends. The begin attribute is
     * a byte order mark, so a reader scanning for the packet learns its encoding from it.
     */
    private static final String XPACKET_BEGIN = "<?xpacket begin=\""
            + (char) 0xFEFF + "\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>";
    private static final String XPACKET_END = "<?xpacket end=\"w\"?>";

    /** The largest a JPEG segment can be, length field included. */
    private static final int MAX_SEGMENT_LENGTH = 0xFFFF;

    /** Start of image. */
    private static final int MARKER_SOI = 0xD8;
    /** Start of scan, after which comes entropy-coded data rather than segments. */
    private static final int MARKER_SOS = 0xDA;
    /** End of image. */
    private static final int MARKER_EOI = 0xD9;
    /** The JFIF segment. */
    private static final int MARKER_APP0 = 0xE0;
    /** The Exif and XMP segments. */
    private static final int MARKER_APP1 = 0xE1;
    /** The ICC profile and Multi-Picture Format segments. */
    private static final int MARKER_APP2 = 0xE2;

    /** The descriptor tags the metadata track uses, from the "User Private" range. */
    private static final int TAG_METADATA = 0xC0;
    private static final int TAG_SCORE = 0xC1;
    private static final int TAG_TRACK_SCORE = 0xC2;
    private static final int TAG_FRAME_SCORE = 0xC3;
    private static final int TAG_FLAGS = 0xC4;
    private static final int TAG_TRACK_FLAGS = 0xC5;

    /**
     * The camera properties an older version defined. The format deletes them and tells readers
     * to ignore them, and an offset left over from one would point at the wrong bytes once the
     * file changes, so they go.
     */
    private static final List<String> RETIRED_CAMERA_PROPERTIES = Arrays.asList(
            "MicroVideo", "MicroVideoVersion", "MicroVideoOffset",
            "MicroVideoPresentationTimestampUs");

    /** The camera properties this writes, removed first wherever an earlier writer put them. */
    private static final List<String> CAMERA_PROPERTIES = Arrays.asList(
            "MotionPhoto", "MotionPhotoVersion", "MotionPhotoPresentationTimestampUs");

    private MotionPhotoWriter() {}

    /**
     * Turns a JPEG on disk into a motion photo, by describing the video in its XMP and appending
     * the video to it.
     *
     * <p>Written to a sibling file and renamed over the original, so a failure part way through
     * leaves the still as it was rather than a picture with half a video.</p>
     *
     * @param presentationTimestampUs the video frame the still corresponds to, or -1 if unknown
     */
    @WorkerThread
    static void write(@NonNull File jpeg, @NonNull File video, long presentationTimestampUs)
            throws IOException {
        byte[] picture = readFully(jpeg);
        byte[] described = withMotionPhotoXmp(picture, video.length(), presentationTimestampUs);
        File temporary = new File(jpeg.getParentFile(), jpeg.getName() + ".motion");
        try {
            try (OutputStream output = new FileOutputStream(temporary);
                 InputStream input = new FileInputStream(video)) {
                output.write(described);
                copy(input, output);
            }
            if (!temporary.renameTo(jpeg)) {
                // A filesystem that will not rename over an existing file. Android's do, so this
                // is the slower path a desktop takes, and it is no worse than writing in place.
                try (OutputStream output = new FileOutputStream(jpeg);
                     InputStream input = new FileInputStream(temporary)) {
                    copy(input, output);
                }
            }
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                Log.w(TAG, "Could not delete " + temporary);
            }
        }
    }

    /**
     * The JPEG with XMP declaring a motion photo whose video is {@code videoLength} bytes long and
     * will be appended after everything already in the file.
     *
     * <p>An XMP segment already in the JPEG is merged into rather than replaced. A new one goes
     * after the Exif segment, which the Exif and XMP specifications both want first, and before
     * any Multi-Picture Format segment: that one locates the gain map relative to itself, so
     * nothing it does not already account for may change size between the two.</p>
     *
     * @throws IOException if the bytes are not a JPEG, or one this cannot describe without
     *     breaking what else it holds
     */
    @AnyThread
    @NonNull
    static byte[] withMotionPhotoXmp(@NonNull byte[] jpeg, long videoLength,
            long presentationTimestampUs) throws IOException {
        if (videoLength <= 0) {
            throw new IOException("A motion photo needs a video");
        }
        if (jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != MARKER_SOI) {
            throw new IOException("Not a JPEG");
        }

        int insertAt = 2;
        int xmpStart = -1;
        int xmpEnd = -1;
        int mpfStart = -1;
        int position = 2;
        while (position + 4 <= jpeg.length) {
            if ((jpeg[position] & 0xFF) != 0xFF) {
                throw new IOException("Malformed JPEG segment at " + position);
            }
            int marker = jpeg[position + 1] & 0xFF;
            if (marker == 0xFF) {
                // A fill byte, which may pad any marker.
                position++;
                continue;
            }
            if (marker == MARKER_SOS || marker == MARKER_EOI) {
                break;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                // Markers that stand alone, with no length after them.
                position += 2;
                continue;
            }
            int length = ((jpeg[position + 2] & 0xFF) << 8) | (jpeg[position + 3] & 0xFF);
            int end = position + 2 + length;
            if (length < 2 || end > jpeg.length) {
                throw new IOException("Truncated JPEG segment at " + position);
            }
            int payload = position + 4;
            if (marker == MARKER_APP0 && insertAt == position) {
                insertAt = end;
            } else if (marker == MARKER_APP1 && startsWith(jpeg, payload, end, EXIF_SIGNATURE)) {
                if (mpfStart < 0) {
                    insertAt = end;
                }
            } else if (marker == MARKER_APP1 && startsWith(jpeg, payload, end, XMP_SIGNATURE)
                    && xmpStart < 0) {
                xmpStart = position;
                xmpEnd = end;
            } else if (marker == MARKER_APP2 && startsWith(jpeg, payload, end, MPF_SIGNATURE)
                    && mpfStart < 0) {
                mpfStart = position;
            }
            position = end;
        }

        String existing = null;
        if (xmpStart >= 0) {
            if (mpfStart >= 0 && mpfStart < xmpStart) {
                // Rewriting the XMP would move the gain map without moving the segment that says
                // where it is.
                throw new IOException("The XMP follows the Multi-Picture Format segment");
            }
            int packet = xmpStart + 4 + XMP_SIGNATURE.length;
            existing = new String(jpeg, packet, xmpEnd - packet, Bytes.UTF_8);
        }

        byte[] segment = xmpSegment(buildXmp(existing, videoLength, presentationTimestampUs));
        ByteArrayOutputStream output = new ByteArrayOutputStream(jpeg.length + segment.length);
        if (xmpStart >= 0) {
            output.write(jpeg, 0, xmpStart);
            output.write(segment);
            output.write(jpeg, xmpEnd, jpeg.length - xmpEnd);
        } else {
            output.write(jpeg, 0, insertAt);
            output.write(segment);
            output.write(jpeg, insertAt, jpeg.length - insertAt);
        }
        return output.toByteArray();
    }

    /** An APP1 segment carrying an XMP packet. */
    @NonNull
    private static byte[] xmpSegment(@NonNull String packet) throws IOException {
        byte[] body = packet.getBytes(Bytes.UTF_8);
        int length = 2 + XMP_SIGNATURE.length + body.length;
        if (length > MAX_SEGMENT_LENGTH) {
            // Extended XMP exists for this, but nothing this writes comes close to needing it.
            throw new IOException("The XMP is too large for one JPEG segment");
        }
        ByteArrayOutputStream segment = new ByteArrayOutputStream(length + 2);
        segment.write(0xFF);
        segment.write(MARKER_APP1);
        segment.write(length >> 8);
        segment.write(length & 0xFF);
        segment.write(XMP_SIGNATURE);
        segment.write(body);
        return segment.toByteArray();
    }

    /**
     * An XMP packet describing the motion photo, merged into the one the JPEG already had.
     *
     * <p>Everything the existing packet says stays, other than motion photo properties a previous
     * writer left, which are replaced, and the retired MicroVideo ones, which are dropped. The
     * directory keeps its items in order, gains a primary image at the front if it had none, and
     * gains the video at the end, where the format requires it to be.</p>
     */
    @VisibleForTesting
    @NonNull
    static String buildXmp(@Nullable String existing, long videoLength,
            long presentationTimestampUs) throws IOException {
        Document document = existing == null ? newDocument() : parse(existing);
        Element rdf = firstElement(document.getDocumentElement(), NAMESPACE_RDF, "RDF");
        if (rdf == null) {
            throw new IOException("The XMP has no RDF");
        }

        List<Element> descriptions = childElements(rdf, NAMESPACE_RDF, "Description");
        for (Element description : descriptions) {
            removeProperties(description, RETIRED_CAMERA_PROPERTIES);
            removeProperties(description, CAMERA_PROPERTIES);
        }
        Element description;
        if (descriptions.isEmpty()) {
            description = document.createElementNS(NAMESPACE_RDF, PREFIX_RDF + ":Description");
            description.setAttributeNS(NAMESPACE_RDF, PREFIX_RDF + ":about", "");
            rdf.appendChild(description);
        } else {
            description = descriptions.get(0);
        }

        declare(description, PREFIX_CAMERA, NAMESPACE_CAMERA);
        declare(description, PREFIX_CONTAINER, NAMESPACE_CONTAINER);
        declare(description, PREFIX_ITEM, NAMESPACE_ITEM);
        description.setAttributeNS(NAMESPACE_CAMERA, PREFIX_CAMERA + ":MotionPhoto", "1");
        description.setAttributeNS(NAMESPACE_CAMERA, PREFIX_CAMERA + ":MotionPhotoVersion",
                String.valueOf(VERSION));
        description.setAttributeNS(NAMESPACE_CAMERA,
                PREFIX_CAMERA + ":MotionPhotoPresentationTimestampUs",
                String.valueOf(presentationTimestampUs));

        Element directory = firstElement(rdf, NAMESPACE_CONTAINER, "Directory");
        if (directory == null) {
            directory = document.createElementNS(
                    NAMESPACE_CONTAINER, PREFIX_CONTAINER + ":Directory");
            description.appendChild(directory);
        }
        Element sequence = firstElement(directory, NAMESPACE_RDF, "Seq");
        if (sequence == null) {
            sequence = document.createElementNS(NAMESPACE_RDF, PREFIX_RDF + ":Seq");
            directory.appendChild(sequence);
        }

        boolean hasPrimary = false;
        for (Element item : childElements(sequence, NAMESPACE_RDF, "li")) {
            String semantic = semanticOf(item);
            if (SEMANTIC_MOTION_PHOTO.equals(semantic)) {
                sequence.removeChild(item);
            } else if (SEMANTIC_PRIMARY.equals(semantic)) {
                hasPrimary = true;
            }
        }
        if (!hasPrimary) {
            // Length 0 and padding 0: the primary image's length is found by parsing it, and
            // nothing separates it from the item after it.
            Element primary = item(document, MIME_JPEG, SEMANTIC_PRIMARY, 0, 0L);
            sequence.insertBefore(primary, sequence.getFirstChild());
        }
        sequence.appendChild(item(document, MIME_MP4, SEMANTIC_MOTION_PHOTO, videoLength, null));

        return XPACKET_BEGIN + serialize(document.getDocumentElement()) + XPACKET_END;
    }

    /**
     * One entry in the directory, written the way every motion photo writer writes it: a list item
     * whose resource is a {@code Container:Item} carrying the item properties as attributes.
     *
     * @param padding the bytes between this item and the next, which only the first item may say
     */
    @NonNull
    private static Element item(@NonNull Document document, @NonNull String mime,
            @NonNull String semantic, long length, @Nullable Long padding) {
        Element listItem = document.createElementNS(NAMESPACE_RDF, PREFIX_RDF + ":li");
        listItem.setAttributeNS(NAMESPACE_RDF, PREFIX_RDF + ":parseType", "Resource");
        Element item = document.createElementNS(NAMESPACE_CONTAINER, PREFIX_CONTAINER + ":Item");
        item.setAttributeNS(NAMESPACE_ITEM, PREFIX_ITEM + ":Mime", mime);
        item.setAttributeNS(NAMESPACE_ITEM, PREFIX_ITEM + ":Semantic", semantic);
        item.setAttributeNS(NAMESPACE_ITEM, PREFIX_ITEM + ":Length", String.valueOf(length));
        if (padding != null) {
            item.setAttributeNS(NAMESPACE_ITEM, PREFIX_ITEM + ":Padding", String.valueOf(padding));
        }
        listItem.appendChild(item);
        return listItem;
    }

    /**
     * What a directory entry is for. RDF allows a property to be an attribute or an element, and
     * on the list item itself or on the resource inside it, so all four places are looked in.
     */
    @Nullable
    private static String semanticOf(@NonNull Element listItem) {
        if (listItem.hasAttributeNS(NAMESPACE_ITEM, "Semantic")) {
            return listItem.getAttributeNS(NAMESPACE_ITEM, "Semantic");
        }
        Element semantic = firstElement(listItem, NAMESPACE_ITEM, "Semantic");
        if (semantic != null) {
            return semantic.getTextContent().trim();
        }
        Element item = firstElement(listItem, NAMESPACE_CONTAINER, "Item");
        if (item != null && item.hasAttributeNS(NAMESPACE_ITEM, "Semantic")) {
            return item.getAttributeNS(NAMESPACE_ITEM, "Semantic");
        }
        return null;
    }

    /** Removes camera properties, whether written as attributes or as child elements. */
    private static void removeProperties(@NonNull Element description,
            @NonNull List<String> names) {
        for (String name : names) {
            if (description.hasAttributeNS(NAMESPACE_CAMERA, name)) {
                description.removeAttributeNS(NAMESPACE_CAMERA, name);
            }
            for (Element child : childElements(description, NAMESPACE_CAMERA, name)) {
                description.removeChild(child);
            }
        }
    }

    /** Binds a prefix on an element, unless it is already bound there. */
    private static void declare(@NonNull Element element, @NonNull String prefix,
            @NonNull String namespace) {
        if (!element.hasAttributeNS(NAMESPACE_XMLNS, prefix)) {
            element.setAttributeNS(NAMESPACE_XMLNS, "xmlns:" + prefix, namespace);
        }
    }

    /** An empty packet: the envelope, the RDF and nothing in it yet. */
    @NonNull
    private static Document newDocument() throws IOException {
        Document document = newBuilder().newDocument();
        Element meta = document.createElementNS(NAMESPACE_XMP_META, PREFIX_XMP_META + ":xmpmeta");
        declare(meta, PREFIX_XMP_META, NAMESPACE_XMP_META);
        document.appendChild(meta);
        Element rdf = document.createElementNS(NAMESPACE_RDF, PREFIX_RDF + ":RDF");
        declare(rdf, PREFIX_RDF, NAMESPACE_RDF);
        meta.appendChild(rdf);
        return document;
    }

    @NonNull
    private static Document parse(@NonNull String packet) throws IOException {
        try {
            return newBuilder().parse(new InputSource(new StringReader(packet.trim())));
        } catch (SAXException | RuntimeException error) {
            throw new IOException("The existing XMP could not be read", error);
        }
    }

    @NonNull
    private static DocumentBuilder newBuilder() throws IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        try {
            return factory.newDocumentBuilder();
        } catch (ParserConfigurationException error) {
            throw new IOException("No XML parser", error);
        }
    }

    @NonNull
    private static String serialize(@NonNull Element element) throws IOException {
        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(element), new StreamResult(writer));
            return writer.toString();
        } catch (TransformerException | RuntimeException error) {
            throw new IOException("The XMP could not be written", error);
        }
    }

    /** The first element with a name, searching depth first below a node. */
    @Nullable
    private static Element firstElement(@Nullable Node parent, @NonNull String namespace,
            @NonNull String localName) {
        if (parent == null) {
            return null;
        }
        if (parent instanceof Element && isNamed(parent, namespace, localName)) {
            return (Element) parent;
        }
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            Element found = firstElement(child, namespace, localName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The direct children of an element with a name. */
    @NonNull
    private static List<Element> childElements(@NonNull Element parent, @NonNull String namespace,
            @NonNull String localName) {
        List<Element> children = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element && isNamed(child, namespace, localName)) {
                children.add((Element) child);
            }
        }
        return children;
    }

    private static boolean isNamed(@NonNull Node node, @NonNull String namespace,
            @NonNull String localName) {
        return namespace.equals(node.getNamespaceURI()) && localName.equals(node.getLocalName());
    }

    /**
     * The one sample of the video's metadata track: no frames scored, the still's frame and its
     * timestamp, and neither track stabilized.
     *
     * <p>The layout is ISO/IEC 14496-1's descriptor syntax - a tag byte, a length in seven-bit
     * groups, then the fields big-endian - nested as the format lays it out.</p>
     *
     * @param presentationTimestampUs the same value the XMP carries, which the format requires
     */
    @AnyThread
    @NonNull
    static byte[] metadataTrackSample(long presentationTimestampUs) {
        // No model scored anything, which version 0 says; the score beside it is then unused.
        byte[] frameScore = descriptor(TAG_FRAME_SCORE, ByteBuffer.allocate(12)
                .putFloat(0f)
                .putLong(presentationTimestampUs)
                .array());
        byte[] trackScore = descriptor(TAG_TRACK_SCORE, ByteBuffer.allocate(4).putInt(0).array());
        byte[] score = descriptor(TAG_SCORE, concat(
                ByteBuffer.allocate(4).putInt(0 /* modelVersion */).array(),
                frameScore,
                trackScore));
        // isStabilized is the top bit and the rest of the byte is padding. The frames are the
        // camera's as it delivered them, which is never stabilized by this library.
        byte[] trackFlags = descriptor(TAG_TRACK_FLAGS, new byte[] {0});
        byte[] flags = descriptor(TAG_FLAGS, concat(trackFlags /* low */, trackFlags /* high */));
        return descriptor(TAG_METADATA, concat(score, flags));
    }

    /** A tag, the payload's size in the expandable encoding, and the payload. */
    @VisibleForTesting
    @NonNull
    static byte[] descriptor(int tag, @NonNull byte[] payload) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(payload.length + 5);
        output.write(tag);
        int size = payload.length;
        int groups = 1;
        while ((size >>> (7 * groups)) != 0) {
            groups++;
        }
        for (int group = groups - 1; group >= 0; group--) {
            int bits = (size >>> (7 * group)) & 0x7F;
            output.write(group == 0 ? bits : bits | 0x80);
        }
        output.write(payload, 0, payload.length);
        return output.toByteArray();
    }

    @NonNull
    private static byte[] concat(@NonNull byte[]... parts) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            output.write(part, 0, part.length);
        }
        return output.toByteArray();
    }

    private static boolean startsWith(@NonNull byte[] data, int start, int end,
            @NonNull byte[] prefix) {
        if (end - start < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[start + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    @WorkerThread
    @NonNull
    private static byte[] readFully(@NonNull File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) file.length());
            copy(input, output);
            return output.toByteArray();
        }
    }

    @WorkerThread
    private static void copy(@NonNull InputStream input, @NonNull OutputStream output)
            throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }
}
