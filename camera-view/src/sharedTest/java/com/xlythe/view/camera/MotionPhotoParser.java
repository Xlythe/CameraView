package com.xlythe.view.camera;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Reads a motion photo back, written apart from the writer so that a test checks the file against
 * the format rather than against the code that produced it.
 *
 * <p>It does what a gallery does: finds the XMP segment in the JPEG, reads the camera properties
 * and the container directory from it by namespace, and locates the video as the last
 * {@code Item:Length} bytes of the file. It also walks the JPEG to its end-of-image marker, so a
 * test can check the other way of finding the video - the primary image's length, plus its
 * padding, plus every item between - lands on the same byte.</p>
 *
 * <p>Shared by the unit tests and the device tests, which read files a real camera wrote.</p>
 */
public final class MotionPhotoParser {
    public static final String NAMESPACE_CAMERA = "http://ns.google.com/photos/1.0/camera/";
    public static final String NAMESPACE_CONTAINER = "http://ns.google.com/photos/1.0/container/";
    public static final String NAMESPACE_ITEM = "http://ns.google.com/photos/1.0/container/item/";
    private static final String NAMESPACE_RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String XMP_SIGNATURE = "http://ns.adobe.com/xap/1.0/\0";

    /** One entry of the container directory, as written. */
    public static final class Item {
        /** {@code Item:Mime}. */
        public final String mime;
        /** {@code Item:Semantic}. */
        public final String semantic;
        /** {@code Item:Length}, or null when absent. */
        public final Long length;
        /** {@code Item:Padding}, or null when absent. */
        public final Long padding;

        Item(String mime, String semantic, Long length, Long padding) {
            this.mime = mime;
            this.semantic = semantic;
            this.length = length;
            this.padding = padding;
        }
    }

    /** The XMP packet, as text. */
    public final String xmp;
    /** Every camera-namespace property, by local name. */
    public final Map<String, String> camera;
    /** The container directory, in order. */
    public final List<Item> items;
    /** Where the primary image's end-of-image marker ends. */
    public final int primaryImageEnd;
    /** Where the video starts, counted back from the end of the file. */
    public final long videoOffset;
    /** How long the video is, from its directory entry. */
    public final long videoLength;
    /** How many XMP segments the primary image has. A reader takes the first. */
    public final int xmpSegmentCount;

    private MotionPhotoParser(String xmp, Map<String, String> camera, List<Item> items,
            int primaryImageEnd, long videoOffset, long videoLength, int xmpSegmentCount) {
        this.xmp = xmp;
        this.camera = camera;
        this.items = items;
        this.primaryImageEnd = primaryImageEnd;
        this.videoOffset = videoOffset;
        this.videoLength = videoLength;
        this.xmpSegmentCount = xmpSegmentCount;
    }

    /** Reads a motion photo, failing loudly at anything that would make a gallery ignore it. */
    public static MotionPhotoParser parse(byte[] file) throws IOException {
        if (file.length < 4 || (file[0] & 0xFF) != 0xFF || (file[1] & 0xFF) != 0xD8) {
            throw new IOException("Not a JPEG");
        }
        String xmp = null;
        int xmpSegments = 0;
        int position = 2;
        while (true) {
            if (position + 4 > file.length) {
                throw new IOException("Ran off the end before the image data");
            }
            if ((file[position] & 0xFF) != 0xFF) {
                throw new IOException("Expected a marker at " + position);
            }
            int marker = file[position + 1] & 0xFF;
            if (marker == 0xFF) {
                position++;
                continue;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                position += 2;
                continue;
            }
            int length = ((file[position + 2] & 0xFF) << 8) | (file[position + 3] & 0xFF);
            int payload = position + 4;
            int end = position + 2 + length;
            if (marker == 0xE1 && startsWith(file, payload, XMP_SIGNATURE)) {
                xmpSegments++;
                if (xmp == null) {
                    int start = payload + XMP_SIGNATURE.length();
                    xmp = new String(file, start, end - start, StandardCharsets.UTF_8);
                }
            }
            position = end;
            if (marker == 0xDA) {
                break;
            }
        }
        int primaryEnd = endOfImage(file, position);
        if (xmp == null) {
            throw new IOException("No XMP");
        }

        Element root;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            String packet = xmp.substring(xmp.indexOf('<'), xmp.lastIndexOf('>') + 1);
            Document document = factory.newDocumentBuilder()
                    .parse(new InputSource(new StringReader(packet)));
            root = document.getDocumentElement();
        } catch (Exception error) {
            throw new IOException("Unreadable XMP", error);
        }

        Map<String, String> camera = new LinkedHashMap<>();
        collectCamera(root, camera);

        List<Item> items = new ArrayList<>();
        Element directory = find(root, NAMESPACE_CONTAINER, "Directory");
        if (directory != null) {
            Element sequence = find(directory, NAMESPACE_RDF, "Seq");
            if (sequence != null) {
                for (Node li = sequence.getFirstChild(); li != null; li = li.getNextSibling()) {
                    if (!(li instanceof Element) || !"li".equals(li.getLocalName())) {
                        continue;
                    }
                    Element item = find(li, NAMESPACE_CONTAINER, "Item");
                    Element holder = item != null ? item : (Element) li;
                    items.add(new Item(
                            attribute(holder, "Mime"),
                            attribute(holder, "Semantic"),
                            number(attribute(holder, "Length")),
                            number(attribute(holder, "Padding"))));
                }
            }
        }

        long videoLength = 0;
        for (Item item : items) {
            if ("MotionPhoto".equals(item.semantic) && item.length != null) {
                videoLength = item.length;
            }
        }
        return new MotionPhotoParser(xmp, Collections.unmodifiableMap(camera),
                Collections.unmodifiableList(items), primaryEnd, file.length - videoLength,
                videoLength, xmpSegments);
    }

    /**
     * Where the video starts when worked out forwards: the primary image, its padding, and every
     * secondary item ahead of the video. The format says this and {@link #videoOffset} agree.
     */
    public long videoOffsetFromTheFront() {
        long offset = primaryImageEnd;
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (i == 0) {
                offset += item.padding == null ? 0 : item.padding;
                continue;
            }
            if ("MotionPhoto".equals(item.semantic)) {
                return offset;
            }
            offset += item.length == null ? 0 : item.length;
        }
        return -1;
    }

    /** The video's bytes. */
    public byte[] video(byte[] file) {
        byte[] video = new byte[(int) videoLength];
        System.arraycopy(file, (int) videoOffset, video, 0, video.length);
        return video;
    }

    /** Scans entropy-coded data for the end-of-image marker, skipping stuffed and restart bytes. */
    private static int endOfImage(byte[] file, int position) throws IOException {
        for (int i = position; i + 1 < file.length; i++) {
            if ((file[i] & 0xFF) != 0xFF) {
                continue;
            }
            int next = file[i + 1] & 0xFF;
            if (next == 0xD9) {
                return i + 2;
            }
        }
        throw new IOException("No end of image");
    }

    private static void collectCamera(Node node, Map<String, String> camera) {
        if (node instanceof Element) {
            Element element = (Element) node;
            for (int i = 0; i < element.getAttributes().getLength(); i++) {
                Node attribute = element.getAttributes().item(i);
                if (NAMESPACE_CAMERA.equals(attribute.getNamespaceURI())) {
                    camera.put(attribute.getLocalName(), attribute.getNodeValue());
                }
            }
            if (NAMESPACE_CAMERA.equals(element.getNamespaceURI())) {
                camera.put(element.getLocalName(), element.getTextContent().trim());
            }
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            collectCamera(child, camera);
        }
    }

    private static Element find(Node node, String namespace, String localName) {
        if (node instanceof Element && namespace.equals(node.getNamespaceURI())
                && localName.equals(node.getLocalName())) {
            return (Element) node;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            Element found = find(child, namespace, localName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String attribute(Element element, String localName) {
        return element.hasAttributeNS(NAMESPACE_ITEM, localName)
                ? element.getAttributeNS(NAMESPACE_ITEM, localName) : null;
    }

    private static Long number(String value) {
        return value == null ? null : Long.parseLong(value.trim());
    }

    private static boolean startsWith(byte[] data, int start, String prefix) {
        byte[] expected = prefix.getBytes(StandardCharsets.US_ASCII);
        if (start + expected.length > data.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (data[start + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
