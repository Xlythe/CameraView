package com.xlythe.view.camera;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * An XMP packet, read and edited by namespace and property name.
 *
 * <p>Properties are found in both of the forms RDF allows, as attributes of an
 * {@code rdf:Description} and as elements inside one, and an array's value is its default
 * language entry or its first. Editing keeps everything else in the packet as it was, other
 * namespaces and structures included, and writes a new property as an attribute, or as the
 * array the XMP specification gives the Dublin Core properties.</p>
 *
 * <p>A packet that is not well-formed XML is still read, property by property, by pattern; it
 * cannot be edited, since writing it back could only lose what it holds.</p>
 */
final class Xmp {
    static final String NS_RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    static final String NS_META = "adobe:ns:meta/";
    static final String NS_XML = "http://www.w3.org/XML/1998/namespace";
    static final String NS_XMLNS = "http://www.w3.org/2000/xmlns/";
    static final String NS_DC = "http://purl.org/dc/elements/1.1/";
    static final String NS_XMP = "http://ns.adobe.com/xap/1.0/";
    static final String NS_EXIF = "http://ns.adobe.com/exif/1.0/";
    static final String NS_EXIF_EX = "http://cipa.jp/exif/1.0/";
    static final String NS_TIFF = "http://ns.adobe.com/tiff/1.0/";
    static final String NS_PHOTOSHOP = "http://ns.adobe.com/photoshop/1.0/";
    static final String NS_GPANO = "http://ns.google.com/photos/1.0/panorama/";
    static final String NS_CAMERA = "http://ns.google.com/photos/1.0/camera/";
    static final String NS_CONTAINER = "http://ns.google.com/photos/1.0/container/";
    static final String NS_ITEM = "http://ns.google.com/photos/1.0/container/item/";
    static final String NS_HDR_GAIN_MAP = "http://ns.adobe.com/hdr-gain-map/1.0/";
    static final String NS_APPLE_GAIN_MAP = "http://ns.apple.com/HDRGainMap/1.0/";
    static final String NS_APPLE_PIXEL_DATA = "http://ns.apple.com/pixeldatainfo/1.0/";
    static final String NS_XMP_NOTE = "http://ns.adobe.com/xmp/note/";
    static final String NS_SPHERICAL = "http://ns.google.com/videos/1.0/spherical/";

    /** The prefix each well-known namespace is written with, so a packet reads as people expect. */
    private static final Map<String, String> PREFIXES = new HashMap<>();

    static {
        PREFIXES.put(NS_RDF, "rdf");
        PREFIXES.put(NS_DC, "dc");
        PREFIXES.put(NS_XMP, "xmp");
        PREFIXES.put(NS_EXIF, "exif");
        PREFIXES.put(NS_EXIF_EX, "exifEX");
        PREFIXES.put(NS_TIFF, "tiff");
        PREFIXES.put(NS_PHOTOSHOP, "photoshop");
        PREFIXES.put(NS_GPANO, "GPano");
        PREFIXES.put(NS_CAMERA, "Camera");
        PREFIXES.put(NS_CONTAINER, "Container");
        PREFIXES.put(NS_ITEM, "Item");
        PREFIXES.put(NS_HDR_GAIN_MAP, "hdrgm");
        PREFIXES.put(NS_APPLE_GAIN_MAP, "HDRGainMap");
        PREFIXES.put(NS_APPLE_PIXEL_DATA, "apdi");
        PREFIXES.put(NS_XMP_NOTE, "xmpNote");
        PREFIXES.put(NS_SPHERICAL, "GSpherical");
    }

    /** What every packet this writes begins with: the wrapper XMP readers scan files for. */
    private static final String PACKET_BEGIN =
            "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n";

    /** And ends with. Writable, since nothing here relies on padding to edit in place. */
    private static final String PACKET_END = "\n<?xpacket end=\"w\"?>";

    /** A namespace declaration, for the reader that works on text. */
    private static final Pattern DECLARATION =
            Pattern.compile("xmlns:([\\w.-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");

    /** The packet as a tree, or null when it is not well-formed and can only be read as text. */
    @Nullable
    private final Document mDocument;

    /** The packet's text, kept for reading one that is not well-formed. */
    @NonNull
    private final String mText;

    private Xmp(@Nullable Document document, @NonNull String text) {
        mDocument = document;
        mText = text;
    }

    /** A packet with nothing in it, ready to take properties. */
    @NonNull
    static Xmp empty() {
        return parse("<x:xmpmeta xmlns:x=\"" + NS_META + "\"><rdf:RDF xmlns:rdf=\""
                + NS_RDF + "\"/></x:xmpmeta>");
    }

    /** Reads a packet from its bytes, in whichever Unicode encoding they are. */
    @NonNull
    static Xmp parse(@NonNull byte[] packet) {
        return parse(decode(packet));
    }

    /** Reads a packet. Never fails: one that is not XML is read as text. */
    @NonNull
    static Xmp parse(@NonNull String packet) {
        String text = packet;
        int start = text.indexOf('<');
        if (start > 0) {
            text = text.substring(start);
        }
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == 0
                || Character.isWhitespace(text.charAt(end - 1)))) {
            end--;
        }
        text = text.substring(0, end);
        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setExpandEntityReferences(false);
            disallowDoctype(factory);
            DocumentBuilder builder = factory.newDocumentBuilder();
            document = builder.parse(new InputSource(new StringReader(text)));
            if (rdf(document) == null) {
                document = null;
            }
        } catch (Exception | StackOverflowError malformed) {
            // Read as text below.
            document = null;
        }
        return new Xmp(document, text);
    }

    /** XMP never needs a document type, and one is how entity expansion attacks begin. */
    private static void disallowDoctype(@NonNull DocumentBuilderFactory factory) {
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception unsupported) {
            // The platform's own parser expands no external entities and declines the feature.
        }
    }

    /** The packet as text: UTF-16 or UTF-32 by their byte order marks, and UTF-8 otherwise. */
    @NonNull
    private static String decode(@NonNull byte[] packet) {
        if (packet.length >= 4 && packet[0] == 0 && packet[1] == 0
                && (packet[2] & 0xFF) == 0xFE && (packet[3] & 0xFF) == 0xFF) {
            return utf32(packet, true);
        }
        if (packet.length >= 4 && (packet[0] & 0xFF) == 0xFF && (packet[1] & 0xFF) == 0xFE
                && packet[2] == 0 && packet[3] == 0) {
            return utf32(packet, false);
        }
        if (packet.length >= 2 && (packet[0] & 0xFF) == 0xFE && (packet[1] & 0xFF) == 0xFF) {
            return new String(packet, 2, packet.length - 2, Bytes.UTF_16BE);
        }
        if (packet.length >= 2 && (packet[0] & 0xFF) == 0xFF && (packet[1] & 0xFF) == 0xFE) {
            return new String(packet, 2, packet.length - 2, Bytes.UTF_16LE);
        }
        return new String(packet, Bytes.UTF_8);
    }

    @NonNull
    private static String utf32(@NonNull byte[] packet, boolean bigEndian) {
        StringBuilder text = new StringBuilder();
        for (int at = 4; at + 4 <= packet.length; at += 4) {
            long codePoint = bigEndian ? Bytes.u32(packet, at) : Bytes.u32le(packet, at);
            if (codePoint <= Character.MAX_CODE_POINT) {
                text.appendCodePoint((int) codePoint);
            }
        }
        return text.toString();
    }

    /** Whether the packet can be edited, which takes one that is well-formed. */
    boolean isWritable() {
        return mDocument != null;
    }

    /** Whether the packet holds no properties. */
    boolean isEmpty() {
        return namespaces().isEmpty();
    }

    /** Every namespace a property is in. */
    @NonNull
    Set<String> namespaces() {
        Set<String> namespaces = new LinkedHashSet<>();
        if (mDocument == null) {
            Matcher matcher = DECLARATION.matcher(mText);
            while (matcher.find()) {
                String uri = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
                if (!NS_RDF.equals(uri) && !NS_META.equals(uri)
                        && mText.contains(matcher.group(1) + ":")) {
                    namespaces.add(uri);
                }
            }
            return namespaces;
        }
        for (Element description : descriptions()) {
            for (Node property : properties(description)) {
                namespaces.add(property.getNamespaceURI());
            }
        }
        return namespaces;
    }

    /**
     * A property's value: a simple one's text, or an array's default language entry or first
     * entry.
     *
     * @return the value, or null when it is absent or is a structure rather than a value
     */
    @Nullable
    String get(@NonNull String namespace, @NonNull String name) {
        if (mDocument == null) {
            return textValue(namespace, name);
        }
        for (Element description : descriptions()) {
            Attr attribute = description.getAttributeNodeNS(namespace, name);
            if (attribute != null) {
                return attribute.getValue();
            }
            for (Element child : children(description)) {
                if (namespace.equals(child.getNamespaceURI())
                        && name.equals(child.getLocalName())) {
                    return valueOf(child);
                }
            }
        }
        return null;
    }

    /** An array's entries in order, or a simple property as a single entry, or none. */
    @NonNull
    List<String> getArray(@NonNull String namespace, @NonNull String name) {
        List<String> values = new ArrayList<>();
        Element property = mDocument == null ? null : propertyElement(namespace, name);
        Element array = property == null ? null : array(property);
        if (array != null) {
            for (Element item : children(array)) {
                if (isRdf(item, "li")) {
                    values.add(item.getTextContent().trim());
                }
            }
            return values;
        }
        String value = get(namespace, name);
        if (value != null) {
            values.add(value);
        }
        return values;
    }

    /**
     * The entries of an array of structures, such as the directory of a motion photo's
     * container: each entry's fields, at any depth, keyed by namespace followed by name.
     */
    @NonNull
    List<Map<String, String>> getStructs(@NonNull String namespace, @NonNull String name) {
        if (mDocument == null) {
            return textStructs(namespace, name);
        }
        List<Map<String, String>> structs = new ArrayList<>();
        Element property = propertyElement(namespace, name);
        Element array = property == null ? null : array(property);
        if (array == null) {
            return structs;
        }
        for (Element item : children(array)) {
            if (isRdf(item, "li")) {
                Map<String, String> fields = new LinkedHashMap<>();
                gather(item, fields);
                structs.add(fields);
            }
        }
        return structs;
    }

    /** Sets a simple property, or removes it when null. */
    void set(@NonNull String namespace, @NonNull String name, @Nullable String value) {
        Element description = prepare(namespace, name, value);
        if (description != null) {
            description.setAttributeNS(namespace, prefixFor(namespace, description) + ":" + name,
                    value);
        }
    }

    /** Sets a property as a language alternative with only a default entry, or removes it. */
    void setAlt(@NonNull String namespace, @NonNull String name, @Nullable String value) {
        setArray(namespace, name, value, "Alt");
    }

    /** Sets a property as an ordered array of one entry, or removes it. */
    void setSeq(@NonNull String namespace, @NonNull String name, @Nullable String value) {
        setArray(namespace, name, value, "Seq");
    }

    private void setArray(@NonNull String namespace, @NonNull String name,
            @Nullable String value, @NonNull String kind) {
        Element description = prepare(namespace, name, value);
        if (description == null) {
            return;
        }
        Document document = description.getOwnerDocument();
        String rdf = prefixFor(NS_RDF, description);
        Element property = document.createElementNS(namespace,
                prefixFor(namespace, description) + ":" + name);
        Element array = document.createElementNS(NS_RDF, rdf + ":" + kind);
        Element item = document.createElementNS(NS_RDF, rdf + ":li");
        if ("Alt".equals(kind)) {
            item.setAttributeNS(NS_XML, "xml:lang", "x-default");
        }
        item.appendChild(document.createTextNode(value));
        array.appendChild(item);
        property.appendChild(array);
        description.appendChild(property);
    }

    /**
     * Takes a property out wherever it is, and finds the description a new value goes in.
     *
     * @return the description, or null when the value is null and there is nothing to add
     */
    @Nullable
    private Element prepare(@NonNull String namespace, @NonNull String name,
            @Nullable String value) {
        remove(namespace, name);
        if (value == null) {
            return null;
        }
        List<Element> descriptions = descriptions();
        if (!descriptions.isEmpty()) {
            return descriptions.get(0);
        }
        Element rdf = rdf(document());
        String prefix = rdf.getPrefix() == null ? "rdf" : rdf.getPrefix();
        Element description = document().createElementNS(NS_RDF, prefix + ":Description");
        description.setAttributeNS(NS_RDF, prefix + ":about", "");
        rdf.appendChild(description);
        return description;
    }

    /** Removes a property, in either form, from every description. */
    void remove(@NonNull String namespace, @NonNull String name) {
        document();
        for (Element description : descriptions()) {
            description.removeAttributeNS(namespace, name);
            for (Element child : children(description)) {
                if (namespace.equals(child.getNamespaceURI())
                        && name.equals(child.getLocalName())) {
                    description.removeChild(child);
                }
            }
        }
    }

    /** Removes every property in a namespace. */
    void removeNamespace(@NonNull String namespace) {
        document();
        for (Element description : descriptions()) {
            for (Node property : properties(description)) {
                if (namespace.equals(property.getNamespaceURI())) {
                    if (property instanceof Attr) {
                        description.removeAttributeNode((Attr) property);
                    } else {
                        description.removeChild(property);
                    }
                }
            }
        }
    }

    /**
     * Copies every property of another packet into this one, in place of any of the same name
     * here, leaving out those in the given namespaces.
     *
     * @return false, with nothing copied, if the other packet could only be read as text
     */
    boolean merge(@NonNull Xmp other, @NonNull Set<String> excluded) {
        document();
        if (other.mDocument == null) {
            return false;
        }
        for (Element source : other.descriptions()) {
            for (Node property : properties(source)) {
                String namespace = property.getNamespaceURI();
                if (excluded.contains(namespace)) {
                    continue;
                }
                String name = property.getLocalName();
                if (property instanceof Attr) {
                    set(namespace, name, ((Attr) property).getValue());
                    continue;
                }
                Element description = prepare(namespace, name, "");
                if (description != null) {
                    Element copy = (Element) document().importNode(property, true);
                    redeclare(copy, description);
                    description.appendChild(copy);
                }
            }
        }
        return true;
    }

    /** Gives an imported element, and all in it, prefixes this packet declares. */
    private void redeclare(@NonNull Element element, @NonNull Element description) {
        String namespace = element.getNamespaceURI();
        if (namespace != null) {
            element.setPrefix(prefixFor(namespace, description));
        }
        NamedNodeMap attributes = element.getAttributes();
        List<Attr> declarations = new ArrayList<>();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attribute = (Attr) attributes.item(i);
            String attributeNamespace = attribute.getNamespaceURI();
            if (NS_XMLNS.equals(attributeNamespace)) {
                declarations.add(attribute);
            } else if (attributeNamespace != null && !NS_XML.equals(attributeNamespace)) {
                attribute.setPrefix(prefixFor(attributeNamespace, description));
            }
        }
        for (Attr declaration : declarations) {
            element.removeAttributeNode(declaration);
        }
        for (Element child : children(element)) {
            redeclare(child, description);
        }
    }

    /** The packet, as a file stores it. */
    @NonNull
    byte[] toBytes() {
        if (mDocument == null) {
            return mText.getBytes(Bytes.UTF_8);
        }
        StringBuilder out = new StringBuilder(PACKET_BEGIN);
        Element root = mDocument.getDocumentElement();
        if (isRdf(root, "RDF")) {
            out.append("<x:xmpmeta xmlns:x=\"").append(NS_META).append("\">");
            write(root, out);
            out.append("</x:xmpmeta>");
        } else {
            write(root, out);
        }
        out.append(PACKET_END);
        return out.toString().getBytes(Bytes.UTF_8);
    }

    /** A copy that can be edited without changing this one. */
    @NonNull
    Xmp copy() {
        return mDocument == null ? new Xmp(null, mText) : parse(toBytes());
    }

    @NonNull
    @Override
    public String toString() {
        return new String(toBytes(), Bytes.UTF_8);
    }

    // ---------- The tree ----------

    /** The tree, for an edit, which a packet that is not well-formed cannot take. */
    @NonNull
    private Document document() {
        if (mDocument == null) {
            throw new IllegalStateException("A packet that is not well-formed cannot be edited");
        }
        return mDocument;
    }

    @Nullable
    private static Element rdf(@NonNull Document document) {
        NodeList list = document.getElementsByTagNameNS(NS_RDF, "RDF");
        return list.getLength() == 0 ? null : (Element) list.item(0);
    }

    @NonNull
    private List<Element> descriptions() {
        List<Element> descriptions = new ArrayList<>();
        Element rdf = mDocument == null ? null : rdf(mDocument);
        if (rdf == null) {
            return descriptions;
        }
        for (Element child : children(rdf)) {
            if (isRdf(child, "Description")) {
                descriptions.add(child);
            }
        }
        return descriptions;
    }

    /** A description's properties: its attributes that are not RDF's own, and its elements. */
    @NonNull
    private static List<Node> properties(@NonNull Element description) {
        List<Node> properties = new ArrayList<>();
        NamedNodeMap attributes = description.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attribute = (Attr) attributes.item(i);
            if (isProperty(attribute)) {
                properties.add(attribute);
            }
        }
        for (Element child : children(description)) {
            if (child.getNamespaceURI() != null) {
                properties.add(child);
            }
        }
        return properties;
    }

    /** Whether an attribute is a property rather than a declaration or RDF's own. */
    private static boolean isProperty(@NonNull Attr attribute) {
        String namespace = attribute.getNamespaceURI();
        return namespace != null && !NS_XMLNS.equals(namespace) && !NS_RDF.equals(namespace)
                && !NS_XML.equals(namespace);
    }

    @NonNull
    private static List<Element> children(@NonNull Element parent) {
        List<Element> children = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null;
                child = child.getNextSibling()) {
            if (child instanceof Element) {
                children.add((Element) child);
            }
        }
        return children;
    }

    private static boolean isRdf(@NonNull Element element, @NonNull String name) {
        return NS_RDF.equals(element.getNamespaceURI()) && name.equals(element.getLocalName());
    }

    @Nullable
    private Element propertyElement(@NonNull String namespace, @NonNull String name) {
        for (Element description : descriptions()) {
            for (Element child : children(description)) {
                if (namespace.equals(child.getNamespaceURI())
                        && name.equals(child.getLocalName())) {
                    return child;
                }
            }
        }
        return null;
    }

    /** The Alt, Seq or Bag a property element holds, or null if it holds no array. */
    @Nullable
    private static Element array(@NonNull Element property) {
        for (Element child : children(property)) {
            if (isRdf(child, "Alt") || isRdf(child, "Seq") || isRdf(child, "Bag")) {
                return child;
            }
        }
        return null;
    }

    /** A property element's value, as {@link #get} describes it. */
    @Nullable
    private static String valueOf(@NonNull Element property) {
        String resource = property.getAttributeNS(NS_RDF, "resource");
        if (!resource.isEmpty()) {
            return resource;
        }
        Element array = array(property);
        if (array != null) {
            String first = null;
            for (Element item : children(array)) {
                if (!isRdf(item, "li")) {
                    continue;
                }
                String text = item.getTextContent().trim();
                if ("x-default".equalsIgnoreCase(item.getAttributeNS(NS_XML, "lang"))) {
                    return text;
                }
                if (first == null) {
                    first = text;
                }
            }
            return first;
        }
        if (!children(property).isEmpty()
                || "Resource".equals(property.getAttributeNS(NS_RDF, "parseType"))) {
            return null;
        }
        return property.getTextContent().trim();
    }

    /** Collects every field under an element, attributes and leaf elements both. */
    private static void gather(@NonNull Element element, @NonNull Map<String, String> fields) {
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attribute = (Attr) attributes.item(i);
            if (isProperty(attribute)) {
                fields.put(attribute.getNamespaceURI() + attribute.getLocalName(),
                        attribute.getValue());
            }
        }
        for (Element child : children(element)) {
            String namespace = child.getNamespaceURI();
            if (children(child).isEmpty() && namespace != null && !NS_RDF.equals(namespace)
                    && !child.getTextContent().trim().isEmpty()) {
                fields.put(namespace + child.getLocalName(), child.getTextContent().trim());
            }
            gather(child, fields);
        }
    }

    /**
     * The prefix a namespace is declared with, declaring it on the description if it is not yet,
     * with its usual prefix unless another namespace already has that.
     */
    @NonNull
    private String prefixFor(@NonNull String namespace, @NonNull Element description) {
        Map<String, String> declared = new HashMap<>();
        Set<String> used = new HashSet<>();
        collectDeclarations(document().getDocumentElement(), declared, used);
        String prefix = declared.get(namespace);
        if (prefix != null) {
            return prefix;
        }
        String base = PREFIXES.containsKey(namespace) ? PREFIXES.get(namespace) : "ns";
        prefix = base;
        for (int i = 1; used.contains(prefix); i++) {
            prefix = base + i;
        }
        description.setAttributeNS(NS_XMLNS, "xmlns:" + prefix, namespace);
        return prefix;
    }

    private static void collectDeclarations(@NonNull Element element,
            @NonNull Map<String, String> declared, @NonNull Set<String> used) {
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attribute = (Attr) attributes.item(i);
            if (NS_XMLNS.equals(attribute.getNamespaceURI())
                    && !"xmlns".equals(attribute.getName())) {
                String prefix = attribute.getLocalName();
                used.add(prefix);
                if (!declared.containsKey(attribute.getValue())) {
                    declared.put(attribute.getValue(), prefix);
                }
            }
        }
        if (element.getPrefix() != null) {
            used.add(element.getPrefix());
        }
        for (Element child : children(element)) {
            collectDeclarations(child, declared, used);
        }
    }

    /** Writes a node and everything in it. */
    private static void write(@NonNull Node node, @NonNull StringBuilder out) {
        switch (node.getNodeType()) {
            case Node.ELEMENT_NODE: {
                out.append('<').append(node.getNodeName());
                NamedNodeMap attributes = node.getAttributes();
                for (int i = 0; i < attributes.getLength(); i++) {
                    Node attribute = attributes.item(i);
                    out.append(' ').append(attribute.getNodeName()).append("=\"");
                    escape(attribute.getNodeValue(), out, true);
                    out.append('"');
                }
                if (node.getFirstChild() == null) {
                    out.append("/>");
                    return;
                }
                out.append('>');
                for (Node child = node.getFirstChild(); child != null;
                        child = child.getNextSibling()) {
                    write(child, out);
                }
                out.append("</").append(node.getNodeName()).append('>');
                return;
            }
            case Node.TEXT_NODE:
            case Node.CDATA_SECTION_NODE:
                escape(node.getNodeValue(), out, false);
                return;
            case Node.COMMENT_NODE:
                out.append("<!--").append(node.getNodeValue()).append("-->");
                return;
            default:
                // Processing instructions inside the packet carry no properties.
        }
    }

    private static void escape(@Nullable String text, @NonNull StringBuilder out,
            boolean attribute) {
        if (text == null) {
            return;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&':
                    out.append("&amp;");
                    break;
                case '<':
                    out.append("&lt;");
                    break;
                case '>':
                    out.append("&gt;");
                    break;
                case '"':
                    out.append(attribute ? "&quot;" : "\"");
                    break;
                case '\n':
                    out.append(attribute ? "&#xA;" : "\n");
                    break;
                case '\r':
                    out.append("&#xD;");
                    break;
                case '\t':
                    out.append(attribute ? "&#x9;" : "\t");
                    break;
                default:
                    out.append(c);
            }
        }
    }

    // ---------- Reading a packet that is not well-formed ----------

    /** Each prefix the text binds, to its namespace. */
    @NonNull
    private Map<String, String> textDeclarations() {
        Map<String, String> prefixes = new LinkedHashMap<>();
        Matcher declaration = DECLARATION.matcher(mText);
        while (declaration.find()) {
            prefixes.put(declaration.group(1), declaration.group(2) != null
                    ? declaration.group(2) : declaration.group(3));
        }
        return prefixes;
    }

    /** The prefixes the text binds to a namespace; usually one. */
    @NonNull
    private List<String> textPrefixes(@NonNull String namespace) {
        List<String> prefixes = new ArrayList<>();
        for (Map.Entry<String, String> declaration : textDeclarations().entrySet()) {
            if (namespace.equals(declaration.getValue())) {
                prefixes.add(declaration.getKey());
            }
        }
        return prefixes;
    }

    /** The element form of a property in the text, or null. */
    @Nullable
    private String textElement(@NonNull String prefix, @NonNull String name) {
        String qualified = Pattern.quote(prefix + ":" + name);
        Matcher element = Pattern.compile("<" + qualified + "(?:\\s[^>]*)?>(.*?)</"
                + qualified + "\\s*>", Pattern.DOTALL).matcher(mText);
        return element.find() ? element.group(1) : null;
    }

    @Nullable
    private String textValue(@NonNull String namespace, @NonNull String name) {
        for (String prefix : textPrefixes(namespace)) {
            Matcher attribute = Pattern.compile("[\\s\"']" + Pattern.quote(prefix + ":" + name)
                    + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')").matcher(mText);
            if (attribute.find()) {
                return unescape(attribute.group(1) != null ? attribute.group(1)
                        : attribute.group(2));
            }
            String inner = textElement(prefix, name);
            if (inner != null) {
                Matcher item = Pattern.compile("<rdf:li(?:\\s[^>]*)?>([^<]*)</rdf:li\\s*>")
                        .matcher(inner);
                if (item.find()) {
                    return unescape(item.group(1).trim());
                }
                return inner.contains("<") ? null : unescape(inner.trim());
            }
        }
        return null;
    }

    @NonNull
    private List<Map<String, String>> textStructs(@NonNull String namespace,
            @NonNull String name) {
        List<Map<String, String>> structs = new ArrayList<>();
        Map<String, String> prefixes = textDeclarations();
        for (String prefix : textPrefixes(namespace)) {
            String inner = textElement(prefix, name);
            if (inner == null) {
                continue;
            }
            Matcher item = Pattern.compile("<rdf:li\\b(.*?)(?:</rdf:li\\s*>|$)",
                    Pattern.DOTALL).matcher(inner);
            while (item.find()) {
                Map<String, String> fields = new LinkedHashMap<>();
                Matcher field = Pattern.compile(
                        "([\\w.-]+):([\\w.-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
                        .matcher(item.group(1));
                while (field.find()) {
                    String uri = prefixes.get(field.group(1));
                    if (uri != null && !NS_RDF.equals(uri)) {
                        fields.put(uri + field.group(2), unescape(field.group(3) != null
                                ? field.group(3) : field.group(4)));
                    }
                }
                structs.add(fields);
            }
            return structs;
        }
        return structs;
    }

    @NonNull
    private static String unescape(@NonNull String value) {
        return value.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&apos;", "'").replace("&#xA;", "\n").replace("&#xD;", "\r")
                .replace("&#x9;", "\t").replace("&amp;", "&");
    }
}
