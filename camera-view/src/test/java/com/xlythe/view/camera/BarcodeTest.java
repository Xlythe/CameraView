package com.xlythe.view.camera;

import android.graphics.Point;
import android.graphics.Rect;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class BarcodeTest {

    private com.google.mlkit.vision.barcode.common.Barcode mockMlKitBarcode;
    private Barcode barcode;

    @Before
    public void setup() {
        mockMlKitBarcode = Mockito.mock(com.google.mlkit.vision.barcode.common.Barcode.class);
        barcode = new Barcode(mockMlKitBarcode);
    }

    @Test
    public void testBasicProperties() {
        when(mockMlKitBarcode.getFormat()).thenReturn(Barcode.Format.QR_CODE);
        when(mockMlKitBarcode.getValueType()).thenReturn(Barcode.Type.URL);
        when(mockMlKitBarcode.getDisplayValue()).thenReturn("https://example.com");
        when(mockMlKitBarcode.getRawValue()).thenReturn("raw_url");

        assertEquals(Barcode.Format.QR_CODE, barcode.getFormat());
        assertEquals(Barcode.Type.URL, barcode.getType());
        assertEquals("https://example.com", barcode.getDisplayValue());
        assertEquals("raw_url", barcode.getRawValue());
    }

    @Test
    public void testCalendarEvent() {
        com.google.mlkit.vision.barcode.common.Barcode.CalendarEvent mockEvent =
                Mockito.mock(com.google.mlkit.vision.barcode.common.Barcode.CalendarEvent.class);
        com.google.mlkit.vision.barcode.common.Barcode.CalendarDateTime mockStart =
                Mockito.mock(com.google.mlkit.vision.barcode.common.Barcode.CalendarDateTime.class);
        com.google.mlkit.vision.barcode.common.Barcode.CalendarDateTime mockEnd =
                Mockito.mock(com.google.mlkit.vision.barcode.common.Barcode.CalendarDateTime.class);

        when(mockStart.getYear()).thenReturn(2026);
        when(mockStart.getMonth()).thenReturn(5);
        when(mockStart.getDay()).thenReturn(13);
        when(mockStart.getHours()).thenReturn(14);
        when(mockStart.getMinutes()).thenReturn(30);
        when(mockStart.getSeconds()).thenReturn(0);
        when(mockStart.isUtc()).thenReturn(true);
        when(mockStart.getRawValue()).thenReturn("20260513T143000Z");

        when(mockEvent.getStart()).thenReturn(mockStart);
        when(mockEvent.getEnd()).thenReturn(mockEnd);
        when(mockEvent.getSummary()).thenReturn("Meeting");
        when(mockEvent.getDescription()).thenReturn("Discuss project");
        when(mockEvent.getLocation()).thenReturn("Room 1");
        when(mockEvent.getOrganizer()).thenReturn("boss@example.com");
        when(mockEvent.getStatus()).thenReturn("CONFIRMED");

        when(mockMlKitBarcode.getCalendarEvent()).thenReturn(mockEvent);

        Barcode.CalendarEvent event = barcode.getCalendarEvent();
        assertNotNull(event);
        assertEquals("Meeting", event.getSummary());
        assertEquals("Discuss project", event.getDescription());
        assertEquals("Room 1", event.getLocation());
        assertEquals("boss@example.com", event.getOrganizer());
        assertEquals("CONFIRMED", event.getStatus());

        assertNotNull(event.getStart());
        assertEquals(2026, event.getStart().getYear());
        assertEquals(5, event.getStart().getMonth());
        assertEquals(13, event.getStart().getDay());
        assertEquals(14, event.getStart().getHours());
        assertEquals(30, event.getStart().getMinutes());
        assertEquals(0, event.getStart().getSeconds());
        assertEquals(true, event.getStart().isUtc());
        assertEquals("20260513T143000Z", event.getStart().getRawValue());

        assertNotNull(event.getEnd());
    }

    @Test
    public void testContactInfo() {
        com.google.mlkit.vision.barcode.common.Barcode.ContactInfo mockContact =
                Mockito.mock(com.google.mlkit.vision.barcode.common.Barcode.ContactInfo.class);
        com.google.mlkit.vision.barcode.common.Barcode.PersonName mockName =
                Mockito.mock(com.google.mlkit.vision.barcode.common.Barcode.PersonName.class);

        when(mockName.getFirst()).thenReturn("John");
        when(mockName.getLast()).thenReturn("Doe");
        when(mockName.getFormattedName()).thenReturn("John Doe");

        when(mockContact.getName()).thenReturn(mockName);
        when(mockContact.getOrganization()).thenReturn("Google");
        when(mockContact.getTitle()).thenReturn("Engineer");
        com.google.mlkit.vision.barcode.common.Barcode.Address mockAddress =
                Mockito.mock(com.google.mlkit.vision.barcode.common.Barcode.Address.class);
        com.google.mlkit.vision.barcode.common.Barcode.Email mockContactEmail =
                Mockito.mock(com.google.mlkit.vision.barcode.common.Barcode.Email.class);
        com.google.mlkit.vision.barcode.common.Barcode.Phone mockContactPhone =
                Mockito.mock(com.google.mlkit.vision.barcode.common.Barcode.Phone.class);
        when(mockAddress.getAddressLines()).thenReturn(new String[] {"1600 Amphitheatre Pkwy"});
        when(mockContactEmail.getAddress()).thenReturn("john@google.com");
        when(mockContactPhone.getNumber()).thenReturn("+16502530000");

        // Deliberately different lengths. A wrapper that walked or sized the wrong collection
        // would still pass if every list here were the same length, or empty.
        when(mockContact.getAddresses()).thenReturn(Collections.singletonList(mockAddress));
        when(mockContact.getEmails()).thenReturn(
                Arrays.asList(mockContactEmail, mockContactEmail));
        when(mockContact.getPhones()).thenReturn(
                Arrays.asList(mockContactPhone, mockContactPhone, mockContactPhone));
        when(mockContact.getUrls()).thenReturn(Arrays.asList("https://google.com"));

        when(mockMlKitBarcode.getContactInfo()).thenReturn(mockContact);

        Barcode.ContactInfo contact = barcode.getContactInfo();
        assertNotNull(contact);
        assertEquals("Google", contact.getOrganization());
        assertEquals("Engineer", contact.getTitle());
        assertEquals(Collections.singletonList("https://google.com"), contact.getUrls());

        assertNotNull(contact.getName());
        assertEquals("John", contact.getName().getFirst());
        assertEquals("Doe", contact.getName().getLast());
        assertEquals("John Doe", contact.getName().getFormattedName());

        assertEquals(1, contact.getAddresses().size());
        assertEquals("1600 Amphitheatre Pkwy",
                contact.getAddresses().get(0).getAddressLines()[0]);
        assertEquals(2, contact.getEmails().size());
        assertEquals("john@google.com", contact.getEmails().get(0).getAddress());
        assertEquals(3, contact.getPhones().size());
        assertEquals("+16502530000", contact.getPhones().get(0).getNumber());
    }

    @Test
    public void testDriverLicense() {
        com.google.mlkit.vision.barcode.common.Barcode.DriverLicense mockLicense =
                Mockito.mock(com.google.mlkit.vision.barcode.common.Barcode.DriverLicense.class);

        when(mockLicense.getFirstName()).thenReturn("Jane");
        when(mockLicense.getMiddleName()).thenReturn("Q");
        when(mockLicense.getLastName()).thenReturn("Smith");
        when(mockLicense.getAddressStreet()).thenReturn("1 Main Street");
        when(mockLicense.getAddressCity()).thenReturn("Mountain View");
        when(mockLicense.getAddressState()).thenReturn("CA");
        when(mockLicense.getAddressZip()).thenReturn("94043");
        when(mockLicense.getBirthDate()).thenReturn("19800101");
        when(mockLicense.getGender()).thenReturn("2");
        when(mockLicense.getDocumentType()).thenReturn("DL");
        when(mockLicense.getLicenseNumber()).thenReturn("DL123456");
        when(mockLicense.getIssueDate()).thenReturn("20200101");
        when(mockLicense.getExpiryDate()).thenReturn("20300101");
        when(mockLicense.getIssuingCountry()).thenReturn("US");

        when(mockMlKitBarcode.getDriverLicense()).thenReturn(mockLicense);

        Barcode.DriverLicense license = barcode.getDriverLicense();
        assertNotNull(license);
        assertEquals("Jane", license.getFirstName());
        assertEquals("Q", license.getMiddleName());
        assertEquals("Smith", license.getLastName());
        assertEquals("1 Main Street", license.getAddressStreet());
        assertEquals("Mountain View", license.getAddressCity());
        assertEquals("CA", license.getAddressState());
        assertEquals("94043", license.getAddressZip());
        assertEquals("19800101", license.getBirthDate());
        assertEquals("2", license.getGender());
        assertEquals("DL", license.getDocumentType());
        assertEquals("DL123456", license.getLicenseNumber());
        assertEquals("20200101", license.getIssueDate());
        assertEquals("20300101", license.getExpiryDate());
        assertEquals("US", license.getIssuingCountry());
    }

    /** Nothing scanned means nothing wrapped, rather than a wrapper around null. */
    @Test
    public void absentStructuredValuesAreNull() {
        when(mockMlKitBarcode.getContactInfo()).thenReturn(null);
        when(mockMlKitBarcode.getDriverLicense()).thenReturn(null);
        when(mockMlKitBarcode.getCalendarEvent()).thenReturn(null);
        when(mockMlKitBarcode.getEmail()).thenReturn(null);
        when(mockMlKitBarcode.getPhone()).thenReturn(null);
        when(mockMlKitBarcode.getSms()).thenReturn(null);
        when(mockMlKitBarcode.getUrl()).thenReturn(null);
        when(mockMlKitBarcode.getWifi()).thenReturn(null);
        when(mockMlKitBarcode.getGeoPoint()).thenReturn(null);

        assertNull(barcode.getContactInfo());
        assertNull(barcode.getDriverLicense());
        assertNull(barcode.getCalendarEvent());
        assertNull(barcode.getEmail());
        assertNull(barcode.getPhone());
        assertNull(barcode.getSms());
        assertNull(barcode.getUrl());
        assertNull(barcode.getWifi());
        assertNull(barcode.getGeoPoint());
    }

    @Test
    public void testEmail() {
        com.google.mlkit.vision.barcode.common.Barcode.Email mockEmail =
                Mockito.mock(com.google.mlkit.vision.barcode.common.Barcode.Email.class);

        when(mockEmail.getAddress()).thenReturn("test@example.com");
        when(mockEmail.getSubject()).thenReturn("Hello");
        when(mockEmail.getBody()).thenReturn("World");
        when(mockEmail.getType()).thenReturn(Barcode.Email.Type.WORK);

        when(mockMlKitBarcode.getEmail()).thenReturn(mockEmail);

        Barcode.Email email = barcode.getEmail();
        assertNotNull(email);
        assertEquals("test@example.com", email.getAddress());
        assertEquals("Hello", email.getSubject());
        assertEquals("World", email.getBody());
        assertEquals(Barcode.Email.Type.WORK, email.getType());
    }

    @Test
    public void testGeoPoint() {
        com.google.mlkit.vision.barcode.common.Barcode.GeoPoint mockPoint =
                Mockito.mock(com.google.mlkit.vision.barcode.common.Barcode.GeoPoint.class);

        when(mockPoint.getLat()).thenReturn(37.4220);
        when(mockPoint.getLng()).thenReturn(-122.0841);

        when(mockMlKitBarcode.getGeoPoint()).thenReturn(mockPoint);

        Barcode.GeoPoint point = barcode.getGeoPoint();
        assertNotNull(point);
        assertEquals(37.4220, point.getLat(), 0.0001);
        assertEquals(-122.0841, point.getLng(), 0.0001);
    }

    @Test
    public void testPhone() {
        com.google.mlkit.vision.barcode.common.Barcode.Phone mockPhone =
                Mockito.mock(com.google.mlkit.vision.barcode.common.Barcode.Phone.class);

        when(mockPhone.getNumber()).thenReturn("1234567890");
        when(mockPhone.getType()).thenReturn(Barcode.Phone.Type.MOBILE);

        when(mockMlKitBarcode.getPhone()).thenReturn(mockPhone);

        Barcode.Phone phone = barcode.getPhone();
        assertNotNull(phone);
        assertEquals("1234567890", phone.getNumber());
        assertEquals(Barcode.Phone.Type.MOBILE, phone.getType());
    }

    @Test
    public void testSms() {
        com.google.mlkit.vision.barcode.common.Barcode.Sms mockSms =
                Mockito.mock(com.google.mlkit.vision.barcode.common.Barcode.Sms.class);

        when(mockSms.getPhoneNumber()).thenReturn("9876543210");
        when(mockSms.getMessage()).thenReturn("Hi there");

        when(mockMlKitBarcode.getSms()).thenReturn(mockSms);

        Barcode.Sms sms = barcode.getSms();
        assertNotNull(sms);
        assertEquals("9876543210", sms.getPhoneNumber());
        assertEquals("Hi there", sms.getMessage());
    }

    @Test
    public void testUrl() {
        com.google.mlkit.vision.barcode.common.Barcode.UrlBookmark mockUrl =
                Mockito.mock(com.google.mlkit.vision.barcode.common.Barcode.UrlBookmark.class);

        when(mockUrl.getTitle()).thenReturn("Google");
        when(mockUrl.getUrl()).thenReturn("https://google.com");

        when(mockMlKitBarcode.getUrl()).thenReturn(mockUrl);

        Barcode.UrlBookmark url = barcode.getUrl();
        assertNotNull(url);
        assertEquals("Google", url.getTitle());
        assertEquals("https://google.com", url.getUrl());
    }

    @Test
    public void testWiFi() {
        com.google.mlkit.vision.barcode.common.Barcode.WiFi mockWifi =
                Mockito.mock(com.google.mlkit.vision.barcode.common.Barcode.WiFi.class);

        when(mockWifi.getSsid()).thenReturn("GuestNetwork");
        when(mockWifi.getPassword()).thenReturn("secret123");
        when(mockWifi.getEncryptionType()).thenReturn(Barcode.WiFi.EncryptionType.WPA);

        when(mockMlKitBarcode.getWifi()).thenReturn(mockWifi);

        Barcode.WiFi wifi = barcode.getWifi();
        assertNotNull(wifi);
        assertEquals("GuestNetwork", wifi.getSsid());
        assertEquals("secret123", wifi.getPassword());
        assertEquals(Barcode.WiFi.EncryptionType.WPA, wifi.getEncryptionType());
    }
}
