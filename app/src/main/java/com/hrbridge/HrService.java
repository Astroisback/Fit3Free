package com.hrbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Heart rate bridge service.
 *
 * Always on:
 *   GATT CLIENT  -> connects to the watch, subscribes to HR notifications,
 *                   publishes BPM to the ongoing notification + in-app UI.
 *
 * Optional (user toggles, off by default):
 *   BLE BROADCAST -> phone also acts as a virtual Heart Rate Monitor
 *                    (GATT server + LE advertising). This is the "BLE spam".
 *   WIFI FALLBACK -> HTTP POST of every BPM sample to the PC.
 */
public class HrService extends Service {

    private static final String TAG = "HrService";
    private static final String CHANNEL_ID = "hr_bridge_silent_lockscreen";
    private static final int NOTIF_ID = 1;

    public static final String PREFS = "hr_bridge";
    public static final String KEY_BLE = "ble_broadcast";
    public static final String KEY_WIFI = "wifi_fallback";
    private static final String KEY_SHOULD_RUN = "should_run";
    private static final String KEY_MAC = "last_mac";
    private static final String KEY_URL = "last_url";

    public static final String ACTION_UPDATE = "com.hrbridge.BPM_UPDATE";

    /** True while the bridge is actively streaming. Read by the UI. */
    public static volatile boolean isRunning = false;
    /** Last BPM seen, so a freshly opened screen has something to show. */
    public static volatile int lastBpm = 0;

    // Standard Bluetooth SIG UUIDs
    private static final UUID HR_SERVICE_UUID =
            UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    private static final UUID HR_MEASUREMENT_UUID =
            UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    private static final UUID BODY_SENSOR_LOC_UUID =
            UUID.fromString("00002a38-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // --- Client side (reading from the watch) ---
    private BluetoothGatt gatt;

    // --- Server side (optional broadcast to PC) ---
    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattCharacteristic serverHrChar;
    private final Set<BluetoothDevice> subscribedDevices = new HashSet<>();

    // --- General ---
    private String pcUrl;
    private boolean bleBroadcast = false;
    private boolean wifiFallback = false;
    private volatile boolean running = false;
    private PowerManager.WakeLock wakeLock;
    private BluetoothManager btManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        btManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        // Process was restarted by the system (sticky). Only resume if the
        // user never pressed STOP, otherwise die quietly.
        if (intent == null || intent.getAction() == null) {
            if (!prefs.getBoolean(KEY_SHOULD_RUN, false)) {
                stopEverything();
                return START_NOT_STICKY;
            }
            return start(prefs.getString(KEY_MAC, null),
                    prefs.getString(KEY_URL, ""),
                    prefs.getBoolean(KEY_BLE, false),
                    prefs.getBoolean(KEY_WIFI, false));
        }

        if ("STOP".equals(intent.getAction())) {
            prefs.edit().putBoolean(KEY_SHOULD_RUN, false).apply();
            stopEverything();
            return START_NOT_STICKY;
        }

        // Toggle changed while streaming: reconfigure in place, no reconnect.
        if ("APPLY".equals(intent.getAction())) {
            if (!running) {
                stopEverything();
                return START_NOT_STICKY;
            }
            boolean ble = intent.getBooleanExtra("ble", bleBroadcast);
            boolean wifi = intent.getBooleanExtra("wifi", wifiFallback);
            String url = intent.getStringExtra("url");
            prefs.edit()
                    .putBoolean(KEY_BLE, ble)
                    .putBoolean(KEY_WIFI, wifi)
                    .putString(KEY_URL, url != null ? url : pcUrl)
                    .apply();
            applyConfig(url, ble, wifi);
            return START_STICKY;
        }

        String mac = intent.getStringExtra("mac");
        String url = intent.getStringExtra("url");
        boolean ble = intent.getBooleanExtra("ble", false);
        boolean wifi = intent.getBooleanExtra("wifi", false);

        prefs.edit()
                .putBoolean(KEY_SHOULD_RUN, true)
                .putString(KEY_MAC, mac)
                .putString(KEY_URL, url)
                .putBoolean(KEY_BLE, ble)
                .putBoolean(KEY_WIFI, wifi)
                .apply();

        return start(mac, url, ble, wifi);
    }

    private int start(String mac, String url, boolean ble, boolean wifi) {
        if (mac == null) {
            stopEverything();
            return START_NOT_STICKY;
        }

        pcUrl = url;
        bleBroadcast = ble;
        wifiFallback = wifi;
        running = true;
        isRunning = true;

        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HRBridge::BLE");
            wakeLock.acquire();
        }

        startForeground(NOTIF_ID, buildNotification("Connecting..."));

        // Optional: act as a virtual HR monitor for other BLE clients.
        if (bleBroadcast) {
            startGattServer();
            startAdvertising();
        } else {
            Log.i(TAG, "BLE broadcast disabled by user");
        }

        connectToDevice(mac);

        return START_STICKY;
    }

    /**
     * Live reconfiguration. The watch connection is untouched, so switching the
     * extras on or off never interrupts the BPM stream.
     */
    private void applyConfig(String url, boolean ble, boolean wifi) {
        if (url != null && !url.isEmpty()) pcUrl = url;
        wifiFallback = wifi;

        if (ble && !bleBroadcast) {
            bleBroadcast = true;
            startGattServer();
            startAdvertising();
            broadcast("BLE broadcast on");
        } else if (!ble && bleBroadcast) {
            bleBroadcast = false;
            stopBleBroadcast();
            broadcast("BLE broadcast off");
        } else {
            broadcast(wifi ? "WiFi fallback on" : "WiFi fallback off");
        }

        Log.i(TAG, "Applied config: ble=" + bleBroadcast + " wifi=" + wifiFallback);
    }

    /** Tears down only the advertiser + GATT server, leaving the watch link alive. */
    @SuppressWarnings("MissingPermission")
    private void stopBleBroadcast() {
        if (advertiser != null) {
            try { advertiser.stopAdvertising(advertiseCallback); } catch (Exception ignored) {}
            advertiser = null;
        }
        if (gattServer != null) {
            subscribedDevices.clear();
            try { gattServer.clearServices(); } catch (Exception ignored) {}
            try { gattServer.close(); } catch (Exception ignored) {}
            gattServer = null;
            serverHrChar = null;
        }
    }

    /** Full teardown: stops streaming, drops the notification, kills the service. */
    private void stopEverything() {
        // If we were launched via startForegroundService we must satisfy the
        // startForeground() contract before dying, or the system kills us hard.
        if (!isRunning) {
            try { startForeground(NOTIF_ID, buildNotification("Stopping...")); } catch (Exception ignored) {}
        }
        shutdown();
        cancelNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    // =====================================================================
    //  GATT SERVER — phone acts as a virtual Heart Rate Monitor (optional)
    // =====================================================================

    @SuppressWarnings("MissingPermission")
    private void startGattServer() {
        gattServer = btManager.openGattServer(this, serverCallback);
        if (gattServer == null) {
            Log.e(TAG, "Failed to open GATT server");
            broadcast("GATT server failed");
            return;
        }

        BluetoothGattService hrService = new BluetoothGattService(
                HR_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        serverHrChar = new BluetoothGattCharacteristic(
                HR_MEASUREMENT_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                0);

        BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        serverHrChar.addDescriptor(cccd);

        BluetoothGattCharacteristic bodySensorLoc = new BluetoothGattCharacteristic(
                BODY_SENSOR_LOC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        bodySensorLoc.setValue(new byte[]{2}); // 2 = Wrist

        hrService.addCharacteristic(serverHrChar);
        hrService.addCharacteristic(bodySensorLoc);

        gattServer.addService(hrService);
        Log.i(TAG, "GATT Server started with HR service");
        broadcast("BLE Server ready");
    }

    private final BluetoothGattServerCallback serverCallback = new BluetoothGattServerCallback() {
        @Override
        @SuppressWarnings("MissingPermission")
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (!running) return;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "PC connected: " + device.getAddress());
                broadcast("PC connected via BLE!");
            } else {
                Log.i(TAG, "PC disconnected: " + device.getAddress());
                subscribedDevices.remove(device);
                broadcast("PC disconnected");
            }
        }

        @Override
        @SuppressWarnings("MissingPermission")
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                              BluetoothGattDescriptor descriptor,
                                              boolean preparedWrite, boolean responseNeeded,
                                              int offset, byte[] value) {
            if (CCCD_UUID.equals(descriptor.getUuid())) {
                if (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    Log.i(TAG, "PC subscribed to HR notifications: " + device.getAddress());
                    subscribedDevices.add(device);
                    broadcast("PC subscribed to HR!");
                } else {
                    subscribedDevices.remove(device);
                }
            }
            BluetoothGattServer server = gattServer;
            if (responseNeeded && server != null) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
            }
        }

        @Override
        @SuppressWarnings("MissingPermission")
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
                                                 int offset, BluetoothGattCharacteristic characteristic) {
            BluetoothGattServer server = gattServer;
            if (server == null) return;
            if (BODY_SENSOR_LOC_UUID.equals(characteristic.getUuid())) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, new byte[]{2});
            } else {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
            }
        }

        @Override
        @SuppressWarnings("MissingPermission")
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId,
                                             int offset, BluetoothGattDescriptor descriptor) {
            BluetoothGattServer server = gattServer;
            if (server == null) return;
            if (CCCD_UUID.equals(descriptor.getUuid())) {
                byte[] val = subscribedDevices.contains(device)
                        ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, val);
            }
        }
    };

    // =====================================================================
    //  BLE ADVERTISER (optional)
    // =====================================================================

    @SuppressWarnings("MissingPermission")
    private void startAdvertising() {
        BluetoothAdapter adapter = btManager.getAdapter();
        advertiser = adapter != null ? adapter.getBluetoothLeAdvertiser() : null;

        if (advertiser == null) {
            Log.e(TAG, "BLE advertising not supported");
            broadcast("BLE advertising not supported");
            return;
        }

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(new ParcelUuid(HR_SERVICE_UUID))
                .build();

        advertiser.startAdvertising(settings, data, advertiseCallback);
        Log.i(TAG, "Started BLE advertising as Heart Rate Monitor");
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.i(TAG, "Advertising started successfully");
            broadcast("Broadcasting as HR Monitor");
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.e(TAG, "Advertising failed: " + errorCode);
            broadcast("Advertising failed (code " + errorCode + ")");
        }
    };

    @SuppressWarnings("MissingPermission")
    private void notifySubscribers(int bpm) {
        if (!running || !bleBroadcast) return;
        BluetoothGattServer server = gattServer;
        if (server == null || serverHrChar == null) return;

        // Standard HR Measurement payload: [flags, bpm], flags=0 -> 8-bit BPM
        serverHrChar.setValue(new byte[]{0x00, (byte) (bpm & 0xFF)});

        for (BluetoothDevice device : subscribedDevices) {
            try {
                server.notifyCharacteristicChanged(device, serverHrChar, false);
            } catch (Exception e) {
                Log.w(TAG, "Failed to notify " + device.getAddress(), e);
            }
        }
    }

    // =====================================================================
    //  GATT CLIENT — reading HR from the watch
    // =====================================================================

    @SuppressWarnings("MissingPermission")
    private void connectToDevice(String mac) {
        try {
            BluetoothAdapter adapter = btManager.getAdapter();
            BluetoothDevice device = adapter.getRemoteDevice(mac);

            broadcast("Connecting to " + mac + "...");
            Log.i(TAG, "Connecting to " + mac);

            gatt = device.connectGatt(this, false, gattCallback,
                    BluetoothDevice.TRANSPORT_LE);

        } catch (Exception e) {
            Log.e(TAG, "Failed to connect", e);
            broadcast("Error: " + e.getMessage());
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        @SuppressWarnings("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (!running) return;

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "GATT connected to watch, discovering services...");
                broadcast("Connected to watch!");
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "GATT disconnected (status=" + status + ")");
                broadcast("Watch disconnected");

                // Retry off the callback thread, and re-check `running` when
                // the retry actually fires so STOP always wins.
                handler.postDelayed(() -> {
                    if (running && gatt != null) {
                        broadcast("Reconnecting...");
                        try {
                            gatt.connect();
                        } catch (Exception ignored) {}
                    }
                }, 3000);
            }
        }

        @Override
        @SuppressWarnings("MissingPermission")
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (!running) return;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                broadcast("Service discovery failed");
                return;
            }

            BluetoothGattService hrService = g.getService(HR_SERVICE_UUID);
            if (hrService == null) {
                broadcast("HR service not found on watch");
                return;
            }

            BluetoothGattCharacteristic hrChar =
                    hrService.getCharacteristic(HR_MEASUREMENT_UUID);
            if (hrChar == null) {
                broadcast("HR characteristic not found");
                return;
            }

            boolean ok = g.setCharacteristicNotification(hrChar, true);
            Log.i(TAG, "setCharacteristicNotification: " + ok);

            BluetoothGattDescriptor cccd = hrChar.getDescriptor(CCCD_UUID);
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else {
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(cccd);
                }
                broadcast("Subscribed to watch HR!");
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g,
                                            BluetoothGattCharacteristic c) {
            handleHrData(c.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g,
                                            BluetoothGattCharacteristic c,
                                            byte[] value) {
            handleHrData(value);
        }
    };

    // =====================================================================
    //  HR DATA HANDLER
    // =====================================================================

    private void handleHrData(byte[] data) {
        // Late callbacks after STOP must not resurrect the notification.
        if (!running) return;
        if (data == null || data.length < 2) return;

        int flags = data[0] & 0xFF;
        int bpm;
        if ((flags & 0x01) == 0) {
            bpm = data[1] & 0xFF;
        } else {
            if (data.length < 3) return;
            bpm = (data[1] & 0xFF) | ((data[2] & 0xFF) << 8);
        }

        Log.i(TAG, "Heart Rate: " + bpm + " BPM");
        lastBpm = bpm;

        updateNotification(bpm + " BPM");
        notifySubscribers(bpm);

        Intent i = new Intent(ACTION_UPDATE);
        i.setPackage(getPackageName());
        i.putExtra("bpm", bpm);
        i.putExtra("status", "Streaming: " + bpm + " BPM");
        sendBroadcast(i);

        if (wifiFallback && pcUrl != null && !pcUrl.isEmpty()) {
            postBpmAsync(bpm);
        }
    }

    private void postBpmAsync(int bpm) {
        final String target = pcUrl;
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(target);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "text/plain");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(String.valueOf(bpm).getBytes());
                    os.flush();
                }

                conn.getResponseCode();
            } catch (Exception e) {
                Log.w(TAG, "HTTP POST failed: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    // =====================================================================
    //  LIFECYCLE
    // =====================================================================

    @SuppressWarnings("MissingPermission")
    private void shutdown() {
        // Flip the flags first so any in-flight callback becomes a no-op.
        running = false;
        isRunning = false;
        lastBpm = 0;
        handler.removeCallbacksAndMessages(null);

        stopBleBroadcast();

        BluetoothGatt g = gatt;
        gatt = null;
        if (g != null) {
            try { g.disconnect(); } catch (Exception ignored) {}
            try { g.close(); } catch (Exception ignored) {}
        }

        if (wakeLock != null) {
            if (wakeLock.isHeld()) wakeLock.release();
            wakeLock = null;
        }

        broadcast("Stopped");
    }

    private void broadcast(String status) {
        Intent i = new Intent(ACTION_UPDATE);
        i.setPackage(getPackageName());
        i.putExtra("bpm", 0);
        i.putExtra("status", status);
        sendBroadcast(i);
    }

    // ---- Notification helpers ----

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Fit3Free", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Live heart rate from your band");
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            channel.setShowBadge(true);
            channel.setSound(null, null);
            channel.enableVibration(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        // Tapping the notification opens the AMOLED clock/HR screen.
        Intent openAod = new Intent(this, AodActivity.class);
        openAod.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, openAod, piFlags);

        Intent stop = new Intent(this, HrService.class).setAction("STOP");
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, piFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Fit3Free")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }

    private void updateNotification(String text) {
        if (!running) return;
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.notify(NOTIF_ID, buildNotification(text));
        } catch (Exception ignored) {}
    }

    private void cancelNotification() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.cancel(NOTIF_ID);
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        shutdown();
        cancelNotification();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
