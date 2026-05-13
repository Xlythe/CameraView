package com.xlythe.sample.camera;

import static com.xlythe.sample.camera.MainActivity.TAG;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xlythe.fragment.camera.CameraFragment;
import com.xlythe.view.camera.Barcode;
import com.xlythe.view.camera.CameraView;

/**
 * QrCodeFragment demonstrates integrated barcode and QR code scanning using CameraView.
 * The library abstracts ML Kit barcode scanning directly into the camera lifecycle.
 */
public class QrCodeFragment extends CameraFragment {
    private CameraView mCameraView;
    private View mSuggestionCard;
    private TextView mSuggestionText;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_qr_code, container, false);
        mCameraView = view.findViewById(R.id.camera);
        mSuggestionCard = view.findViewById(R.id.suggestion_card);
        mSuggestionText = view.findViewById(R.id.suggestion_text);
        return view;
    }

    /**
     * Triggered when the camera successfully opens.
     * We activate the barcode scanner and pass a callback listener that receives detected Barcode lists.
     */
    @SuppressLint("MissingPermission")
    @Override
    public void onCameraOpened() {
        if (Build.VERSION.SDK_INT >= 19) {
            mCameraView.enterBarcodeScanner(barcodes -> {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (barcodes != null && !barcodes.isEmpty()) {
                            Barcode barcode = barcodes.get(0);
                            Barcode.UrlBookmark url = barcode.getUrl();
                            Barcode.WiFi wifi = barcode.getWifi();
                            Barcode.Phone phone = barcode.getPhone();
                            Barcode.Email email = barcode.getEmail();
                            Barcode.Sms sms = barcode.getSms();
                            Barcode.GeoPoint geo = barcode.getGeoPoint();
                            String text = barcode.getDisplayValue() != null ? barcode.getDisplayValue() : barcode.getRawValue();

                            mSuggestionCard.setVisibility(View.VISIBLE);
                            if (url != null && url.getUrl() != null) {
                                mSuggestionText.setText("Open URL: " + url.getUrl());
                                mSuggestionCard.setOnClickListener(v -> {
                                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url.getUrl()))); } catch (Exception ignored) {}
                                });
                            } else if (wifi != null && wifi.getSsid() != null) {
                                mSuggestionText.setText("Connect Wi-Fi: " + wifi.getSsid());
                                mSuggestionCard.setOnClickListener(v -> {
                                    if (getContext() != null) Toast.makeText(getContext(), "Connecting to Wi-Fi: " + wifi.getSsid(), Toast.LENGTH_SHORT).show();
                                });
                            } else if (phone != null && phone.getNumber() != null) {
                                mSuggestionText.setText("Call: " + phone.getNumber());
                                mSuggestionCard.setOnClickListener(v -> {
                                    try { startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.getNumber()))); } catch (Exception ignored) {}
                                });
                            } else if (email != null && email.getAddress() != null) {
                                mSuggestionText.setText("Email: " + email.getAddress());
                                mSuggestionCard.setOnClickListener(v -> {
                                    try { startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + email.getAddress()))); } catch (Exception ignored) {}
                                });
                            } else if (sms != null && sms.getPhoneNumber() != null) {
                                mSuggestionText.setText("SMS: " + sms.getPhoneNumber());
                                mSuggestionCard.setOnClickListener(v -> {
                                    try { startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + sms.getPhoneNumber()))); } catch (Exception ignored) {}
                                });
                            } else if (geo != null) {
                                mSuggestionText.setText("Location: " + geo.getLat() + ", " + geo.getLng());
                                mSuggestionCard.setOnClickListener(v -> {
                                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + geo.getLat() + "," + geo.getLng()))); } catch (Exception ignored) {}
                                });
                            } else if (text != null) {
                                mSuggestionText.setText("Search: " + text);
                                mSuggestionCard.setOnClickListener(v -> {
                                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(text)))); } catch (Exception ignored) {}
                                });
                            } else {
                                mSuggestionText.setText("Barcode detected");
                                mSuggestionCard.setOnClickListener(null);
                            }
                        } else {
                            mSuggestionCard.setVisibility(View.GONE);
                        }
                    });
                }
            }, Barcode.Format.ALL_FORMATS);
        }
    }

    /**
     * Triggered when the camera closes.
     * We cleanly exit the barcode scanner mode to unregister analyzer callbacks and free resources.
     */
    @Override
    public void onCameraClosed() {
        super.onCameraClosed();

        if (Build.VERSION.SDK_INT >= 19) {
            mCameraView.exitBarcodeScanner();
        }
    }
}
