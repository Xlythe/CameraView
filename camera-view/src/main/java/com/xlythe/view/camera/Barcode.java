package com.xlythe.view.camera;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public class Barcode {
  @IntDef({
          Format.UNKNOWN,
          Format.ALL_FORMATS,
          Format.CODE_128,
          Format.CODE_39,
          Format.CODE_93,
          Format.CODABAR,
          Format.DATA_MATRIX,
          Format.EAN_13,
          Format.EAN_8,
          Format.ITF,
          Format.QR_CODE,
          Format.UPC_A,
          Format.UPC_E,
          Format.PDF417,
          Format.AZTEC
  })
  public @interface Format {
    int UNKNOWN = com.google.mlkit.vision.barcode.common.Barcode.FORMAT_UNKNOWN;
    int ALL_FORMATS = com.google.mlkit.vision.barcode.common.Barcode.FORMAT_ALL_FORMATS;
    int CODE_128 = com.google.mlkit.vision.barcode.common.Barcode.FORMAT_CODE_128;
    int CODE_39 = com.google.mlkit.vision.barcode.common.Barcode.FORMAT_CODE_39;
    int CODE_93 = com.google.mlkit.vision.barcode.common.Barcode.FORMAT_CODE_93;
    int CODABAR = com.google.mlkit.vision.barcode.common.Barcode.FORMAT_CODABAR;
    int DATA_MATRIX = com.google.mlkit.vision.barcode.common.Barcode.FORMAT_DATA_MATRIX;
    int EAN_13 = com.google.mlkit.vision.barcode.common.Barcode.FORMAT_EAN_13;
    int EAN_8 = com.google.mlkit.vision.barcode.common.Barcode.FORMAT_EAN_8;
    int ITF = com.google.mlkit.vision.barcode.common.Barcode.FORMAT_ITF;
    int QR_CODE = com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE;
    int UPC_A = com.google.mlkit.vision.barcode.common.Barcode.FORMAT_UPC_A;
    int UPC_E = com.google.mlkit.vision.barcode.common.Barcode.FORMAT_UPC_E;
    int PDF417 = com.google.mlkit.vision.barcode.common.Barcode.FORMAT_PDF417;
    int AZTEC = com.google.mlkit.vision.barcode.common.Barcode.FORMAT_AZTEC;
  }

  @IntDef({
          Type.UNKNOWN,
          Type.CONTACT_INFO,
          Type.EMAIL,
          Type.ISBN,
          Type.PHONE,
          Type.PRODUCT,
          Type.SMS,
          Type.TEXT,
          Type.URL,
          Type.WIFI,
          Type.GEO,
          Type.CALENDAR_EVENT,
          Type.DRIVER_LICENSE
  })
  public @interface Type {
    int UNKNOWN = com.google.mlkit.vision.barcode.common.Barcode.TYPE_UNKNOWN;
    int CONTACT_INFO = com.google.mlkit.vision.barcode.common.Barcode.TYPE_CONTACT_INFO;
    int EMAIL = com.google.mlkit.vision.barcode.common.Barcode.TYPE_EMAIL;
    int ISBN = com.google.mlkit.vision.barcode.common.Barcode.TYPE_ISBN;
    int PHONE = com.google.mlkit.vision.barcode.common.Barcode.TYPE_PHONE;
    int PRODUCT = com.google.mlkit.vision.barcode.common.Barcode.TYPE_PRODUCT;
    int SMS = com.google.mlkit.vision.barcode.common.Barcode.TYPE_SMS;
    int TEXT = com.google.mlkit.vision.barcode.common.Barcode.TYPE_TEXT;
    int URL = com.google.mlkit.vision.barcode.common.Barcode.TYPE_URL;
    int WIFI = com.google.mlkit.vision.barcode.common.Barcode.TYPE_WIFI;
    int GEO = com.google.mlkit.vision.barcode.common.Barcode.TYPE_GEO;
    int CALENDAR_EVENT = com.google.mlkit.vision.barcode.common.Barcode.TYPE_CALENDAR_EVENT;
    int DRIVER_LICENSE = com.google.mlkit.vision.barcode.common.Barcode.TYPE_DRIVER_LICENSE;
  }

  public static class WiFi {
    @IntDef({
            EncryptionType.OPEN,
            EncryptionType.WPA,
            EncryptionType.WEP
    })
    public @interface EncryptionType {
      int OPEN = com.google.mlkit.vision.barcode.common.Barcode.WiFi.TYPE_OPEN;
      int WPA = com.google.mlkit.vision.barcode.common.Barcode.WiFi.TYPE_WPA;
      int WEP = com.google.mlkit.vision.barcode.common.Barcode.WiFi.TYPE_WEP;
    }

    private final com.google.mlkit.vision.barcode.common.Barcode.WiFi mWifi;

    private WiFi(com.google.mlkit.vision.barcode.common.Barcode.WiFi wifi) {
      this.mWifi = wifi;
    }

    @EncryptionType
    public int getEncryptionType() {
      return mWifi.getEncryptionType();
    }

    @Nullable
    public String getPassword() {
      return mWifi.getPassword();
    }

    @Nullable
    public String getSsid() {
      return mWifi.getSsid();
    }
  }

  public static class UrlBookmark {
    private final com.google.mlkit.vision.barcode.common.Barcode.UrlBookmark mUrlBookmark;

    private UrlBookmark(com.google.mlkit.vision.barcode.common.Barcode.UrlBookmark urlBookmark) {
      this.mUrlBookmark = urlBookmark;
    }

    @Nullable
    public String getTitle() {
      return mUrlBookmark.getTitle();
    }

    @Nullable
    public String getUrl() {
      return mUrlBookmark.getUrl();
    }
  }

  public static class Sms {
    private final com.google.mlkit.vision.barcode.common.Barcode.Sms mSms;

    private Sms(com.google.mlkit.vision.barcode.common.Barcode.Sms sms) {
      this.mSms = sms;
    }

    @Nullable
    public String getMessage() {
      return mSms.getMessage();
    }

    @Nullable
    public String getPhoneNumber() {
      return mSms.getPhoneNumber();
    }
  }

  public static class GeoPoint {
    private final com.google.mlkit.vision.barcode.common.Barcode.GeoPoint mGeoPoint;

    private GeoPoint(com.google.mlkit.vision.barcode.common.Barcode.GeoPoint geoPoint) {
      this.mGeoPoint = geoPoint;
    }

    public double getLat() {
      return mGeoPoint.getLat();
    }

    public double getLng() {
      return mGeoPoint.getLng();
    }
  }

  public static class ContactInfo {
    private final com.google.mlkit.vision.barcode.common.Barcode.ContactInfo mContactInfo;

    private ContactInfo(com.google.mlkit.vision.barcode.common.Barcode.ContactInfo contactInfo) {
      this.mContactInfo = contactInfo;
    }

    @Nullable
    public PersonName getName() {
      return new PersonName(mContactInfo.getName());
    }

    @Nullable
    public String getOrganization() {
      return mContactInfo.getOrganization();
    }

    @Nullable
    public String getTitle() {
      return mContactInfo.getTitle();
    }

    @NonNull
    public List<Address> getAddresses() {
      List<Address> list = new ArrayList<>(mContactInfo.getAddresses().size());
      for (com.google.mlkit.vision.barcode.common.Barcode.Address address : mContactInfo.getAddresses()) {
        list.add(new Address(address));
      }
      return list;
    }

    @NonNull
    public List<Email> getEmails() {
      List<Email> list = new ArrayList<>(mContactInfo.getAddresses().size());
      for (com.google.mlkit.vision.barcode.common.Barcode.Email email : mContactInfo.getEmails()) {
        list.add(new Email(email));
      }
      return list;
    }

    @NonNull
    public List<Phone> getPhones() {
      List<Phone> list = new ArrayList<>(mContactInfo.getAddresses().size());
      for (com.google.mlkit.vision.barcode.common.Barcode.Phone phone : mContactInfo.getPhones()) {
        list.add(new Phone(phone));
      }
      return list;
    }

    @NonNull
    public List<String> getUrls() {
      return mContactInfo.getUrls();
    }
  }

  public static class Email {
    @IntDef({
            Type.UNKNOWN,
            Type.WORK,
            Type.HOME
    })
    public @interface Type {
      int UNKNOWN = com.google.mlkit.vision.barcode.common.Barcode.Email.TYPE_UNKNOWN;
      int WORK = com.google.mlkit.vision.barcode.common.Barcode.Email.TYPE_WORK;
      int HOME = com.google.mlkit.vision.barcode.common.Barcode.Email.TYPE_HOME;
    }

    private final com.google.mlkit.vision.barcode.common.Barcode.Email mEmail;

    private Email(com.google.mlkit.vision.barcode.common.Barcode.Email email) {
      this.mEmail = email;
    }

    @Type
    public int getType() {
      return mEmail.getType();
    }

    @Nullable
    public String getAddress() {
      return mEmail.getAddress();
    }

    @Nullable
    public String getBody() {
      return mEmail.getBody();
    }

    @Nullable
    public String getSubject() {
      return mEmail.getSubject();
    }
  }

  public static class Phone {
    @IntDef({
            Type.UNKNOWN,
            Type.WORK,
            Type.HOME,
            Type.FAX,
            Type.MOBILE
    })
    public @interface Type {
      int UNKNOWN = com.google.mlkit.vision.barcode.common.Barcode.Phone.TYPE_UNKNOWN;
      int WORK = com.google.mlkit.vision.barcode.common.Barcode.Phone.TYPE_WORK;
      int HOME = com.google.mlkit.vision.barcode.common.Barcode.Phone.TYPE_HOME;
      int FAX = com.google.mlkit.vision.barcode.common.Barcode.Phone.TYPE_FAX;
      int MOBILE = com.google.mlkit.vision.barcode.common.Barcode.Phone.TYPE_MOBILE;
    }

    private final com.google.mlkit.vision.barcode.common.Barcode.Phone mPhone;

    private Phone(com.google.mlkit.vision.barcode.common.Barcode.Phone phone) {
      this.mPhone = phone;
    }

    @Type
    public int getType() {
      return mPhone.getType();
    }

    @Nullable
    public String getNumber() {
      return mPhone.getNumber();
    }
  }

  public static class PersonName {
    private final com.google.mlkit.vision.barcode.common.Barcode.PersonName mPersonName;

    private PersonName(com.google.mlkit.vision.barcode.common.Barcode.PersonName personName) {
      this.mPersonName = personName;
    }

    @Nullable
    public String getFirst() {
      return mPersonName.getFirst();
    }

    @Nullable
    public String getFormattedName() {
      return mPersonName.getFormattedName();
    }

    @Nullable
    public String getLast() {
      return mPersonName.getLast();
    }

    @Nullable
    public String getMiddle() {
      return mPersonName.getMiddle();
    }

    @Nullable
    public String getPrefix() {
      return mPersonName.getPrefix();
    }

    @Nullable
    public String getPronunciation() {
      return mPersonName.getPronunciation();
    }

    @Nullable
    public String getSuffix() {
      return mPersonName.getSuffix();
    }
  }

  public static class DriverLicense {
    private final com.google.mlkit.vision.barcode.common.Barcode.DriverLicense mDriverLicense;

    private DriverLicense(com.google.mlkit.vision.barcode.common.Barcode.DriverLicense driverLicense) {
      this.mDriverLicense = driverLicense;
    }

    @Nullable
    public String getFirstName() {
      return mDriverLicense.getFirstName();
    }

    @Nullable
    public String getMiddleName() {
      return mDriverLicense.getMiddleName();
    }

    @Nullable
    public String getLastName() {
      return mDriverLicense.getLastName();
    }

    @Nullable
    public String getAddressStreet() {
      return mDriverLicense.getAddressStreet();
    }

    @Nullable
    public String getAddressCity() {
      return mDriverLicense.getAddressCity();
    }

    @Nullable
    public String getAddressState() {
      return mDriverLicense.getAddressState();
    }

    @Nullable
    public String getAddressZip() {
      return mDriverLicense.getAddressZip();
    }

    @Nullable
    public String getBirthDate() {
      return mDriverLicense.getBirthDate();
    }

    @Nullable
    public String getGender() {
      return mDriverLicense.getGender();
    }

    @Nullable
    public String getDocumentType() {
      return mDriverLicense.getDocumentType();
    }

    @Nullable
    public String getLicenseNumber() {
      return mDriverLicense.getLicenseNumber();
    }

    @Nullable
    public String getIssueDate() {
      return mDriverLicense.getIssueDate();
    }

    @Nullable
    public String getExpiryDate() {
      return mDriverLicense.getExpiryDate();
    }

    @Nullable
    public String getIssuingCountry() {
      return mDriverLicense.getIssuingCountry();
    }
  }

  public static class CalendarEvent {
    private final com.google.mlkit.vision.barcode.common.Barcode.CalendarEvent mCalendarEvent;

    private CalendarEvent(com.google.mlkit.vision.barcode.common.Barcode.CalendarEvent calendarEvent) {
      this.mCalendarEvent = calendarEvent;
    }

    @Nullable
    public CalendarDateTime getStart() {
      return new CalendarDateTime(mCalendarEvent.getStart());
    }

    @Nullable
    public CalendarDateTime getEnd() {
      return new CalendarDateTime(mCalendarEvent.getEnd());
    }

    @Nullable
    public String getSummary() {
      return mCalendarEvent.getSummary();
    }

    @Nullable
    public String getDescription() {
      return mCalendarEvent.getDescription();
    }

    @Nullable
    public String getLocation() {
      return mCalendarEvent.getLocation();
    }

    @Nullable
    public String getOrganizer() {
      return mCalendarEvent.getOrganizer();
    }

    @Nullable
    public String getStatus() {
      return mCalendarEvent.getStatus();
    }
  }

  public static class CalendarDateTime {
    private final com.google.mlkit.vision.barcode.common.Barcode.CalendarDateTime mCalendarDateTime;

    private CalendarDateTime(com.google.mlkit.vision.barcode.common.Barcode.CalendarDateTime calendarDateTime) {
      this.mCalendarDateTime = calendarDateTime;
    }

    public int getSeconds() {
      return mCalendarDateTime.getSeconds();
    }

    public int getMinutes() {
      return mCalendarDateTime.getMinutes();
    }

    public int getHours() {
      return mCalendarDateTime.getHours();
    }

    public int getDay() {
      return mCalendarDateTime.getDay();
    }

    public int getMonth() {
      return mCalendarDateTime.getMonth();
    }

    public int getYear() {
      return mCalendarDateTime.getYear();
    }

    public boolean isUtc() {
      return mCalendarDateTime.isUtc();
    }

    @Nullable
    public String getRawValue() {
      return mCalendarDateTime.getRawValue();
    }
  }

  public static class Address {
    @IntDef({
            Type.UNKNOWN,
            Type.WORK,
            Type.HOME
    })
    public @interface Type {
      int UNKNOWN = com.google.mlkit.vision.barcode.common.Barcode.Phone.TYPE_UNKNOWN;
      int WORK = com.google.mlkit.vision.barcode.common.Barcode.Phone.TYPE_WORK;
      int HOME = com.google.mlkit.vision.barcode.common.Barcode.Phone.TYPE_HOME;
    }

    private final com.google.mlkit.vision.barcode.common.Barcode.Address mAddress;

    private Address(com.google.mlkit.vision.barcode.common.Barcode.Address address) {
      this.mAddress = address;
    }

    @Type
    public int getType() {
      return mAddress.getType();
    }

    @NonNull
    public String[] getAddressLines() {
      return mAddress.getAddressLines();
    }
  }

  private final com.google.mlkit.vision.barcode.common.Barcode mBarcode;

  Barcode(com.google.mlkit.vision.barcode.common.Barcode barcode) {
    this.mBarcode = barcode;
  }

  @Format
  public int getFormat() {
    return mBarcode.getFormat();
  }

  @Type
  public int getType() {
    return mBarcode.getValueType();
  }

  @Nullable
  public CalendarEvent getCalendarEvent() {
    return new CalendarEvent(mBarcode.getCalendarEvent());
  }

  @Nullable
  public ContactInfo getContactInfo() {
    return new ContactInfo(mBarcode.getContactInfo());
  }

  @Nullable
  public DriverLicense getDriverLicense() {
    return new DriverLicense(mBarcode.getDriverLicense());
  }

  @Nullable
  public Email getEmail() {
    return new Email(mBarcode.getEmail());
  }

  @Nullable
  public GeoPoint getGeoPoint() {
    return new GeoPoint(mBarcode.getGeoPoint());
  }

  @Nullable
  public Phone getPhone() {
    return new Phone(mBarcode.getPhone());
  }

  @Nullable
  public Sms getSms() {
    return new Sms(mBarcode.getSms());
  }

  @Nullable
  public UrlBookmark getUrl() {
    return new UrlBookmark(mBarcode.getUrl());
  }

  @Nullable
  public WiFi getWifi() {
    return new WiFi(mBarcode.getWifi());
  }

  @Nullable
  public String getDisplayValue() {
    return mBarcode.getDisplayValue();
  }

  @Nullable
  public String getRawValue() {
    return mBarcode.getRawValue();
  }
}
