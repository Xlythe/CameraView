package com.xlythe.view.camera;

import static com.xlythe.view.camera.MediaFixtures.xmp;
import static com.xlythe.view.camera.MediaFixtures.xmpElements;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Reading and editing an XMP packet by namespace and name. */
@RunWith(RobolectricTestRunner.class)
public class XmpTest {
    private static final String NS = "http://example.com/ns/";

    @Test
    public void readsAttributesElementsArraysAndResources() {
        Xmp xmp = Xmp.parse(xmpElements("xmlns:e=\"" + NS + "\" e:Attribute=\"a\"",
                "<e:Element> b </e:Element>"
                + "<e:Resource rdf:resource=\"http://example.com/\"/>"
                + "<e:Bag><rdf:Bag><rdf:li>one</rdf:li><rdf:li>two</rdf:li></rdf:Bag></e:Bag>"
                + "<e:Struct rdf:parseType=\"Resource\"><e:Field>f</e:Field></e:Struct>"));
        assertTrue(xmp.isWritable());
        assertEquals("a", xmp.get(NS, "Attribute"));
        assertEquals("b", xmp.get(NS, "Element"));
        assertEquals("http://example.com/", xmp.get(NS, "Resource"));
        assertEquals("one", xmp.get(NS, "Bag"));
        assertEquals(Arrays.asList("one", "two"), xmp.getArray(NS, "Bag"));
        assertEquals(Collections.singletonList("a"), xmp.getArray(NS, "Attribute"));
        assertTrue(xmp.getArray(NS, "Missing").isEmpty());
        assertNull(xmp.get(NS, "Struct"));
        assertNull(xmp.get(NS, "Missing"));
        assertEquals(Collections.singleton(NS), xmp.namespaces());
        assertFalse(xmp.isEmpty());
    }

    @Test
    public void editsKeepEverythingElse() {
        Xmp xmp = Xmp.parse(xmpElements("xmlns:e=\"" + NS + "\" xmlns:dc=\"" + Xmp.NS_DC
                + "\" e:Keep=\"kept\"", "<dc:subject><rdf:Bag><rdf:li>tag</rdf:li></rdf:Bag>"
                + "</dc:subject>"));
        xmp.set(NS, "New", "value with \"quotes\" & <brackets>\nand a line");
        xmp.setAlt(Xmp.NS_DC, "title", "Title");
        xmp.setSeq(Xmp.NS_DC, "creator", "Ada");
        xmp.set("http://other.example/", "Thing", "x");
        Xmp read = Xmp.parse(xmp.toBytes());
        assertEquals("kept", read.get(NS, "Keep"));
        assertEquals("value with \"quotes\" & <brackets>\nand a line", read.get(NS, "New"));
        assertEquals("Title", read.get(Xmp.NS_DC, "title"));
        assertEquals("Ada", read.get(Xmp.NS_DC, "creator"));
        assertEquals("tag", read.get(Xmp.NS_DC, "subject"));
        assertEquals("x", read.get("http://other.example/", "Thing"));
        String text = read.toString();
        assertTrue(text.startsWith("<?xpacket begin="));
        assertTrue(text.contains("xmlns:ns=\"http://other.example/\""));

        read.remove(NS, "New");
        read.removeNamespace(Xmp.NS_DC);
        assertNull(read.get(NS, "New"));
        assertNull(read.get(Xmp.NS_DC, "title"));
        assertNull(read.get(Xmp.NS_DC, "subject"));
        assertEquals("kept", read.get(NS, "Keep"));
    }

    @Test
    public void anEmptyPacketTakesProperties() {
        Xmp xmp = Xmp.empty();
        assertTrue(xmp.isEmpty());
        xmp.set(NS, "Name", "value");
        assertEquals("value", Xmp.parse(xmp.toBytes()).get(NS, "Name"));
        xmp.set(NS, "Name", null);
        assertTrue(xmp.isEmpty());
        // A bare RDF root gains the wrapper.
        Xmp bare = Xmp.parse("<rdf:RDF xmlns:rdf=\"" + Xmp.NS_RDF + "\"/>");
        bare.set(NS, "Name", "v");
        assertTrue(bare.toString().contains("x:xmpmeta"));
    }

    @Test
    public void aPrefixInUseForAnotherNamespaceIsNotReused() {
        Xmp xmp = Xmp.parse(xmp("xmlns:dc=\"http://not-dublin-core/\"", "dc:Other=\"o\""));
        xmp.set(Xmp.NS_DC, "format", "image/jpeg");
        Xmp read = Xmp.parse(xmp.toBytes());
        assertEquals("image/jpeg", read.get(Xmp.NS_DC, "format"));
        assertEquals("o", read.get("http://not-dublin-core/", "Other"));
    }

    @Test
    public void mergesStructuresAcrossPackets() {
        Xmp source = Xmp.parse(MediaTagsFixtures.motionPhotoXmp(100, 0, true));
        Xmp destination = Xmp.parse(xmp("xmlns:e=\"" + NS + "\"", "e:Mine=\"m\""));
        assertTrue(destination.merge(source, Collections.<String>emptySet()));
        Xmp read = Xmp.parse(destination.toBytes());
        List<Map<String, String>> items = read.getStructs(Xmp.NS_CONTAINER, "Directory");
        assertEquals(2, items.size());
        assertEquals("100", items.get(1).get(Xmp.NS_ITEM + "Length"));
        assertEquals("1", read.get(Xmp.NS_CAMERA, "MotionPhoto"));
        assertEquals("m", read.get(NS, "Mine"));

        Xmp filtered = Xmp.empty();
        filtered.merge(source, Collections.singleton(Xmp.NS_CONTAINER));
        assertTrue(filtered.getStructs(Xmp.NS_CONTAINER, "Directory").isEmpty());
        assertFalse(filtered.merge(Xmp.parse("not xml"), Collections.<String>emptySet()));
    }

    @Test
    public void aPacketThatIsNotXmlIsStillRead() {
        String broken = "<x:xmpmeta><rdf:RDF><rdf:Description xmlns:GPano=\"" + Xmp.NS_GPANO
                + "\" GPano:ProjectionType='equirectangular'><GPano:PoseHeadingDegrees>90"
                + "</GPano:PoseHeadingDegrees><GPano:Alt><rdf:Alt><rdf:li xml:lang='x-default'>"
                + "Hello &amp; bye</rdf:li></rdf:Alt></GPano:Alt>"
                + "<Container:Directory xmlns:Container=\"" + Xmp.NS_CONTAINER + "\" "
                + "xmlns:Item=\"" + Xmp.NS_ITEM + "\"><rdf:Seq><rdf:li><Container:Item "
                + "Item:Length=\"5\"/></rdf:li></rdf:Seq></Container:Directory>";
        Xmp xmp = Xmp.parse(broken);
        assertFalse(xmp.isWritable());
        assertEquals("equirectangular", xmp.get(Xmp.NS_GPANO, "ProjectionType"));
        assertEquals("90", xmp.get(Xmp.NS_GPANO, "PoseHeadingDegrees"));
        assertEquals("Hello & bye", xmp.get(Xmp.NS_GPANO, "Alt"));
        assertEquals("5", xmp.getStructs(Xmp.NS_CONTAINER, "Directory").get(0)
                .get(Xmp.NS_ITEM + "Length"));
        assertTrue(xmp.namespaces().contains(Xmp.NS_GPANO));
        assertEquals(broken, new String(xmp.toBytes(), StandardCharsets.UTF_8));
        assertEquals(broken, xmp.copy().toString());
        try {
            xmp.set(Xmp.NS_GPANO, "PoseHeadingDegrees", "1");
            throw new AssertionError("A packet that is not XML cannot be edited");
        } catch (IllegalStateException expected) {
            // As documented.
        }
    }

    @Test
    public void readsEveryUnicodeEncoding() {
        String packet = xmp("xmlns:e=\"" + NS + "\"", "e:Name=\"é\"");
        byte[] utf16be = ("﻿" + packet).getBytes(StandardCharsets.UTF_16BE);
        byte[] utf16le = ("﻿" + packet).getBytes(StandardCharsets.UTF_16LE);
        assertEquals("é", Xmp.parse(utf16be).get(NS, "Name"));
        assertEquals("é", Xmp.parse(utf16le).get(NS, "Name"));
        byte[] utf32 = new byte[4 + packet.length() * 4];
        utf32[2] = (byte) 0xFE;
        utf32[3] = (byte) 0xFF;
        for (int i = 0; i < packet.length(); i++) {
            Bytes.put32(utf32, 4 + i * 4, packet.charAt(i));
        }
        assertEquals("é", Xmp.parse(utf32).get(NS, "Name"));
        byte[] utf32le = new byte[4 + packet.length() * 4];
        utf32le[0] = (byte) 0xFF;
        utf32le[1] = (byte) 0xFE;
        for (int i = 0; i < packet.length(); i++) {
            Bytes.put32le(utf32le, 4 + i * 4, packet.charAt(i));
        }
        assertEquals("é", Xmp.parse(utf32le).get(NS, "Name"));
        // Leading garbage and trailing padding are ignored.
        assertEquals("é", Xmp.parse("junk" + packet + "\u0000\u0000  ").get(NS, "Name"));
    }

    @Test
    public void refusesADocumentType() {
        Xmp xmp = Xmp.parse("<!DOCTYPE x [<!ENTITY a \"aaaa\">]><x:xmpmeta xmlns:x=\"adobe:ns:"
                + "meta/\"><rdf:RDF xmlns:rdf=\"" + Xmp.NS_RDF + "\"/></x:xmpmeta>");
        assertFalse(xmp.isWritable());
    }
}
