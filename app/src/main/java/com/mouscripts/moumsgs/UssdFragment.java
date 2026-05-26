package com.mouscripts.moumsgs;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

public class UssdFragment extends Fragment {

    private Spinner simSpinner;
    private TextInputEditText ussdCodeEditText;
    private MaterialButton dialButton, cancelButton;
    private TextView statusTextView, responseTextView;

    private List<SimInfo> simList = new ArrayList<>();
    private int selectedSubId = -1;
    private int selectedSlotIndex = 0;
    private boolean isSessionActive = false;

    private static class SimInfo {
        int subId;
        String displayName;
        String number;
        int slotIndex;

        SimInfo(int subId, String displayName, String number, int slotIndex) {
            this.subId = subId;
            this.displayName = displayName;
            this.number = number;
            this.slotIndex = slotIndex;
        }

        @Override
        public String toString() {
            return displayName + (number != null && !number.isEmpty() ? " (" + number + ")" : "");
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_ussd, container, false);

        simSpinner = view.findViewById(R.id.portSpinner);
        ussdCodeEditText = view.findViewById(R.id.ussdCodeEditText);
        dialButton = view.findViewById(R.id.dialButton);
        cancelButton = view.findViewById(R.id.cancelButton);
        statusTextView = view.findViewById(R.id.ussdStatusTextView);
        responseTextView = view.findViewById(R.id.ussdResponseTextView);

        simSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                if (position >= 0 && position < simList.size()) {
                    SimInfo info = simList.get(position);
                    selectedSubId = info.subId;
                    selectedSlotIndex = info.slotIndex;
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                selectedSubId = -1;
                selectedSlotIndex = 0;
            }
        });

        dialButton.setOnClickListener(v -> dialUssd());
        cancelButton.setOnClickListener(v -> cancelUssd());

        detectSims();

        return view;
    }

    private void detectSims() {
        boolean hasPhoneState = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
        boolean hasPhoneNumbers = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_PHONE_NUMBERS)
                == PackageManager.PERMISSION_GRANTED;

        if (!hasPhoneState) {
            statusTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.error));
            statusTextView.setText("Phone state permission not granted");
            return;
        }

        try {
            List<SimInfo> list = new ArrayList<>();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                SubscriptionManager subManager = (SubscriptionManager)
                        requireContext().getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                if (subManager != null) {
                    List<SubscriptionInfo> subs = subManager.getActiveSubscriptionInfoList();
                    if (subs != null && !subs.isEmpty()) {
                        for (SubscriptionInfo sub : subs) {
                            String name = sub.getDisplayName() != null ? sub.getDisplayName().toString() : "SIM " + (sub.getSimSlotIndex() + 1);
                            String number = "";
                            if (hasPhoneNumbers && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                                try { number = sub.getNumber() != null ? sub.getNumber() : ""; } catch (Exception ignored) {}
                            }
                            list.add(new SimInfo(sub.getSubscriptionId(), name, number, sub.getSimSlotIndex()));
                        }
                    }
                }
            }

            if (list.isEmpty()) {
                TelephonyManager tm = (TelephonyManager) requireContext().getSystemService(Context.TELEPHONY_SERVICE);
                String number = "";
                if (hasPhoneNumbers && tm != null) {
                    try { number = tm.getLine1Number() != null ? tm.getLine1Number() : ""; } catch (Exception ignored) {}
                }
                list.add(new SimInfo(-1, "SIM 1", number, 0));
            }

            simList = list;

            if (list.isEmpty()) {
                statusTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.error));
                statusTextView.setText(R.string.no_modems);
            } else {
                ArrayAdapter<SimInfo> adapter = new ArrayAdapter<>(
                        requireContext(), android.R.layout.simple_spinner_item, list);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                simSpinner.setAdapter(adapter);
                selectedSubId = list.get(0).subId;
                selectedSlotIndex = list.get(0).slotIndex;
                statusTextView.setText("");
            }
        } catch (Exception e) {
            Log.e("UssdFragment", "Error detecting SIMs", e);
            statusTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.error));
            statusTextView.setText("Error detecting SIMs: " + e.getMessage());
        }
    }

    private void dialUssd() {
        String rawCode = ussdCodeEditText.getText().toString().trim();
        if (rawCode.isEmpty()) {
            statusTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.error));
            statusTextView.setText(R.string.ussd_hint);
            return;
        }
        if (simList.isEmpty()) {
            statusTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.error));
            statusTextView.setText(R.string.no_modems);
            return;
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CALL_PHONE}, 200);
            return;
        }

        String code = normalizeUssdCode(rawCode);

        dialButton.setEnabled(false);
        statusTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary));
        statusTextView.setText(R.string.ussd_sending);
        responseTextView.setVisibility(View.GONE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dialUssdApi26(code);
        } else {
            dialUssdLegacy(code);
        }
    }

    private String normalizeUssdCode(String code) {
        String normalized = code.trim();
        if (!normalized.startsWith("*") && !normalized.startsWith("#")) {
            normalized = "*" + normalized;
        }
        if (!normalized.endsWith("#")) {
            normalized = normalized + "#";
        }
        return normalized;
    }

    private void dialUssdApi26(String code) {
        TelephonyManager tm = getTelephonyManager();
        if (tm == null) {
            statusTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.error));
            statusTextView.setText("Telephony service not available");
            resetUi();
            return;
        }

        try {
            isSessionActive = true;
            cancelButton.setEnabled(true);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                tm.sendUssdRequest(code, new TelephonyManager.UssdResponseCallback() {
                    @Override
                    public void onReceiveUssdResponse(TelephonyManager telephonyManager,
                                                       String request, CharSequence response) {
                        new Handler(Looper.getMainLooper()).post(() -> {
                            if (!isAdded() || getContext() == null) return;
                            responseTextView.setText(response);
                            responseTextView.setVisibility(View.VISIBLE);
                            statusTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.success));
                            statusTextView.setText("Response received");
                            resetUi();
                        });
                    }

                    @Override
                    public void onReceiveUssdResponseFailed(TelephonyManager telephonyManager,
                                                            String request, int failureCode) {
                        new Handler(Looper.getMainLooper()).post(() -> {
                            if (!isAdded() || getContext() == null) return;
                            statusTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.error));
                            String msg;
                            if (failureCode == TelephonyManager.USSD_RETURN_FAILURE) {
                                msg = "Network rejected request or invalid code";
                            } else if (failureCode == -1) {
                                msg = "Unknown error (possible SIM conflict)";
                            } else {
                                msg = "USSD failed. Code: " + failureCode;
                            }
                            statusTextView.setText(msg);
                            resetUi();
                        });
                    }
                }, new Handler(Looper.getMainLooper()));
            }

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!isAdded()) return;
                if (isSessionActive) {
                    statusTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary));
                    statusTextView.setText("Waiting for network response...");
                }
            }, 2000);
        } catch (SecurityException e) {
            Log.e("UssdFragment", "CALL_PHONE permission not granted", e);
            statusTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.error));
            statusTextView.setText("Call permission required");
            resetUi();
        } catch (Exception e) {
            Log.e("UssdFragment", "Error dialing USSD", e);
            statusTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.error));
            statusTextView.setText("Error: " + e.getMessage());
            resetUi();
        }
    }

    @SuppressWarnings("deprecation")
    private void dialUssdLegacy(String code) {
        try {
            String dialCode = Uri.encode(code);
            Intent intent = new Intent(Intent.ACTION_CALL);
            intent.setData(Uri.parse("tel:" + dialCode));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            isSessionActive = true;
            statusTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary));
            statusTextView.setText("Dialing code...");
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (isAdded() && getContext() != null) resetUi();
            }, 5000);
        } catch (SecurityException e) {
            if (!isAdded()) return;
            statusTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.error));
            statusTextView.setText("Call permission required");
            resetUi();
        }
    }

    private void cancelUssd() {
        isSessionActive = false;
        statusTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary));
        statusTextView.setText(R.string.ussd_cancelled);
        resetUi();
    }

    private TelephonyManager getTelephonyManager() {
        TelephonyManager tm = null;
        try {
            tm = (TelephonyManager) requireContext().getSystemService(Context.TELEPHONY_SERVICE);
        } catch (Exception e) {
            Log.e("UssdFragment", "Error getting TelephonyManager", e);
            return null;
        }
        if (tm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && selectedSubId != -1) {
            try {
                tm = tm.createForSubscriptionId(selectedSubId);
            } catch (Exception e) {
                Log.e("UssdFragment", "Error creating sub-specific TM", e);
            }
        }
        return tm;
    }

    private void resetUi() {
        isSessionActive = false;
        dialButton.setEnabled(true);
        cancelButton.setEnabled(false);
    }
}
