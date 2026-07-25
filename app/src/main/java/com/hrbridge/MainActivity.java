package com.hrbridge;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final int PERM_REQUEST = 100;

    private Spinner deviceSpinner;
    private EditText ipEdit;
    private TextView bpmText;
    private TextView statusText;
    private Button startBtn, stopBtn, aodBtn;
    // AppCompat inflates <Switch> as SwitchCompat, so hold the common base type.
    private CompoundButton bleSwitch, wifiSwitch;

    private BluetoothAdapter btAdapter;
    private SharedPreferences prefs;
    private final List<BluetoothDevice> deviceList = new ArrayList<>();

    /* Receives live BPM broadcasts from HrService */
    private final BroadcastReceiver bpmReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (HrService.ACTION_UPDATE.equals(intent.getAction())) {
                int bpm = intent.getIntExtra("bpm", 0);
                String status = intent.getStringExtra("status");
                if (bpm > 0) {
                    bpmText.setText(String.valueOf(bpm));
                }
                if (status != null) {
                    statusText.setText(status);
                }
                if ("Stopped".equals(status)) {
                    bpmText.setText("--");
                }
                syncButtons();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(HrService.PREFS, MODE_PRIVATE);

        deviceSpinner = findViewById(R.id.deviceSpinner);
        ipEdit = findViewById(R.id.ipEdit);
        bpmText = findViewById(R.id.bpmText);
        statusText = findViewById(R.id.statusText);
        startBtn = findViewById(R.id.startBtn);
        stopBtn = findViewById(R.id.stopBtn);
        aodBtn = findViewById(R.id.aodBtn);
        bleSwitch = findViewById(R.id.bleSwitch);
        wifiSwitch = findViewById(R.id.wifiSwitch);

        // No baked-in address; the field remembers whatever you last used.
        ipEdit.setText(prefs.getString("last_url", ""));

        // Both extras default to off: notification-only mode.
        bleSwitch.setChecked(prefs.getBoolean(HrService.KEY_BLE, false));
        wifiSwitch.setChecked(prefs.getBoolean(HrService.KEY_WIFI, false));

        CompoundButton.OnCheckedChangeListener saver = (v, checked) -> {
            prefs.edit()
                    .putBoolean(HrService.KEY_BLE, bleSwitch.isChecked())
                    .putBoolean(HrService.KEY_WIFI, wifiSwitch.isChecked())
                    .apply();
            applyToRunningService();
        };
        bleSwitch.setOnCheckedChangeListener(saver);
        wifiSwitch.setOnCheckedChangeListener(saver);

        // A changed PC address also applies live, once you leave the field.
        ipEdit.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) return;
            String url = ipEdit.getText().toString().trim();
            if (!url.equals(prefs.getString("last_url", ""))) {
                prefs.edit().putString("last_url", url).apply();
                applyToRunningService();
            }
        });

        startBtn.setOnClickListener(v -> startBridge());
        stopBtn.setOnClickListener(v -> stopBridge());

        // Tapping the BPM number (or the button) opens the AMOLED always-on screen.
        bpmText.setOnClickListener(v -> openAod());
        aodBtn.setOnClickListener(v -> openAod());

        requestPermissions();
        requestBatteryExemption();
    }

    private void openAod() {
        startActivity(new Intent(this, AodActivity.class));
    }

    /**
     * Push the current toggle state into a live service. It reconfigures in
     * place, so nothing needs a manual restart and the BPM stream never drops.
     */
    private void applyToRunningService() {
        if (!HrService.isRunning) return;
        Intent intent = new Intent(this, HrService.class);
        intent.setAction("APPLY");
        intent.putExtra("ble", bleSwitch.isChecked());
        intent.putExtra("wifi", wifiSwitch.isChecked());
        intent.putExtra("url", ipEdit.getText().toString().trim());
        // Service is already foreground, so a plain startService is enough and
        // avoids the startForegroundService() contract.
        startService(intent);
        Toast.makeText(this, "Applied", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(HrService.ACTION_UPDATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bpmReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(bpmReceiver, filter);
        }
        if (HrService.lastBpm > 0) {
            bpmText.setText(String.valueOf(HrService.lastBpm));
        }
        syncButtons();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(bpmReceiver); } catch (Exception ignored) {}
    }

    /** Buttons always mirror the real service state, never a guess. */
    private void syncButtons() {
        boolean running = HrService.isRunning;
        startBtn.setEnabled(!running);
        stopBtn.setEnabled(running);
        if (!running && "Ready".contentEquals(statusText.getText())) {
            bpmText.setText("--");
        }
    }

    private void requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception ignored) {}
    }

    private void requestPermissions() {
        List<String> perms = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, "android.permission.BLUETOOTH_ADVERTISE")
                    != PackageManager.PERMISSION_GRANTED) {
                perms.add("android.permission.BLUETOOTH_ADVERTISE");
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (!perms.isEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), PERM_REQUEST);
        } else {
            loadBondedDevices();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERM_REQUEST) {
            loadBondedDevices();
        }
    }

    @SuppressWarnings("MissingPermission")
    private void loadBondedDevices() {
        BluetoothManager btManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        btAdapter = btManager.getAdapter();

        if (btAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show();
            return;
        }

        deviceList.clear();
        List<String> names = new ArrayList<>();

        try {
            Set<BluetoothDevice> bonded = btAdapter.getBondedDevices();
            for (BluetoothDevice d : bonded) {
                deviceList.add(d);
                String name = d.getName();
                if (name == null || name.isEmpty()) name = "Unknown";
                names.add(name + " (" + d.getAddress() + ")");
            }
        } catch (SecurityException e) {
            Toast.makeText(this, "Bluetooth permission denied", Toast.LENGTH_SHORT).show();
            return;
        }

        if (names.isEmpty()) {
            names.add("No bonded devices found");
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        deviceSpinner.setAdapter(adapter);

        // Re-select whatever was used last time. Nothing is hardcoded, so the
        // first run just shows your bonded devices and waits for a pick.
        String lastMac = prefs.getString("last_mac", "");
        if (!lastMac.isEmpty()) {
            for (int i = 0; i < deviceList.size(); i++) {
                if (deviceList.get(i).getAddress().equalsIgnoreCase(lastMac)) {
                    deviceSpinner.setSelection(i);
                    break;
                }
            }
        }
    }

    private void startBridge() {
        int pos = deviceSpinner.getSelectedItemPosition();
        if (pos < 0 || pos >= deviceList.size()) {
            Toast.makeText(this, "Select a device first", Toast.LENGTH_SHORT).show();
            return;
        }

        BluetoothDevice device = deviceList.get(pos);
        String url = ipEdit.getText().toString().trim();

        Intent intent = new Intent(this, HrService.class);
        intent.setAction("START");
        intent.putExtra("mac", device.getAddress());
        intent.putExtra("url", url);
        intent.putExtra("ble", bleSwitch.isChecked());
        intent.putExtra("wifi", wifiSwitch.isChecked());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        statusText.setText("Starting...");
        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);
    }

    private void stopBridge() {
        Intent intent = new Intent(this, HrService.class);
        intent.setAction("STOP");
        // While the service is live it's already foreground, so this is the
        // reliable delivery path on O+. If it isn't running there's nothing to
        // promote, and a plain startService avoids the FGS timeout contract.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && HrService.isRunning) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        bpmText.setText("--");
        statusText.setText("Stopped");
        startBtn.setEnabled(true);
        stopBtn.setEnabled(false);
    }
}
