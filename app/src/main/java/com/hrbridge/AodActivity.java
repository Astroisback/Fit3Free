package com.hrbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * AMOLED always-on display: pitch black screen showing live BPM + clock.
 *
 * Brightness tracks the ambient light sensor the way a real AOD does, so it
 * stays barely visible in a dark room and readable in daylight. Content drifts
 * slowly to avoid burn-in. Tap anywhere to exit.
 */
public class AodActivity extends AppCompatActivity implements SensorEventListener {

    private static final String TAG = "AodActivity";

    private static final long TICK_MS = 1000L;
    private static final long SHIFT_MS = 60_000L;
    private static final int SHIFT_PX = 24;

    /** Re-read battery current every N clock ticks (10s). */
    private static final int BATTERY_TICKS = 10;
    private int tickCount = 0;

    /** Ambient light (lux) mapped to window brightness, interpolated in log space. */
    private static final float[] LUX_POINTS = {0f, 3f, 10f, 50f, 200f, 800f, 3000f, 10000f};
    private static final float[] BRIGHTNESS_POINTS = {
            0.060f, 0.120f, 0.220f, 0.400f, 0.600f, 0.800f, 0.920f, 1.000f};

    /** Fallback when the device has no light sensor. */
    private static final float DEFAULT_BRIGHTNESS = 0.35f;

    /** Brightness mode, persisted so the screen opens the way you left it. */
    private static final String KEY_DIM = "aod_dim_mode";
    /** Follow whatever the system brightness is (default). */
    private static final float FOLLOW_SYSTEM = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;

    /** Smoothing factor for the lux EMA: lower = calmer, less flicker. */
    private static final float LUX_SMOOTHING = 0.12f;
    /** Don't touch the window unless the target moved by at least this much. */
    private static final float BRIGHTNESS_EPSILON = 0.004f;

    private TextView clockText, dateText, bpmText, bpmLabel, hintText, batteryText;
    private View root;

    private BatteryManager batteryManager;

    private SensorManager sensorManager;
    private Sensor lightSensor;
    private float smoothedLux = -1f;
    /** Last brightness written to the window. -1 = nothing applied yet. */
    private float appliedBrightness = -1f;

    /**
     * false (default) = leave screen brightness alone, so the AOD matches the
     * rest of the phone. true = auto-dim from the light sensor for night use.
     */
    private boolean dimMode = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private final SimpleDateFormat timeFmt = new SimpleDateFormat("h:mm", Locale.getDefault());
    private final SimpleDateFormat ampmFmt = new SimpleDateFormat("a", Locale.getDefault());
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEE, d MMM", Locale.getDefault());

    /** The activity is recreated on rotation; only teach the tap gesture once. */
    private static boolean hintShown = false;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Date now = new Date();
            clockText.setText(timeFmt.format(now));
            dateText.setText(dateFmt.format(now) + "  ·  " + ampmFmt.format(now));
            // Current/watts change continuously without firing a broadcast, so
            // re-read the sticky intent on a slower cadence than the clock.
            if (++tickCount % BATTERY_TICKS == 0) refreshBattery();
            handler.postDelayed(this, TICK_MS);
        }
    };

    /** Burn-in protection: nudge the whole layout a few pixels every minute. */
    private final Runnable shift = new Runnable() {
        @Override
        public void run() {
            root.setTranslationX(random.nextInt(SHIFT_PX * 2 + 1) - SHIFT_PX);
            root.setTranslationY(random.nextInt(SHIFT_PX * 2 + 1) - SHIFT_PX);
            handler.postDelayed(this, SHIFT_MS);
        }
    };

    private final BroadcastReceiver bpmReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int bpm = intent.getIntExtra("bpm", 0);
            if (bpm > 0) {
                bpmText.setText(String.valueOf(bpm));
            } else if (!HrService.isRunning) {
                bpmText.setText("--");
            }
        }
    };

    /**
     * ACTION_BATTERY_CHANGED is sticky, so registering also gives us the
     * current state immediately without waiting for a level change.
     */
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateBattery(intent);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_aod);

        root = findViewById(R.id.aodRoot);
        clockText = findViewById(R.id.clockText);
        dateText = findViewById(R.id.dateText);
        bpmText = findViewById(R.id.aodBpmText);
        bpmLabel = findViewById(R.id.aodBpmLabel);
        hintText = findViewById(R.id.aodHint);
        batteryText = findViewById(R.id.batteryText);

        batteryManager = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);

        // Keep the screen alive and show over the lockscreen.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        }

        // Default is to inherit the system brightness. Previously this screen
        // always forced its own very low override, which is why the AOD looked
        // far darker than the rest of the phone and never matched it.
        dimMode = getSharedPreferences(HrService.PREFS, MODE_PRIVATE)
                .getBoolean(KEY_DIM, false);
        applyBrightnessMode();

        if (dimMode && lightSensor == null) {
            Log.i(TAG, "No ambient light sensor, holding fixed brightness");
        }

        hideSystemBars();

        root.setOnClickListener(v -> finish());

        // Long-press toggles auto-dim, so night use is opt-in rather than forced.
        root.setOnLongClickListener(v -> {
            dimMode = !dimMode;
            getSharedPreferences(HrService.PREFS, MODE_PRIVATE)
                    .edit().putBoolean(KEY_DIM, dimMode).apply();
            applyBrightnessMode();
            updateSensorListener();
            hintText.setAlpha(1f);
            hintText.setText(dimMode ? "Auto-dim on" : "Matches system brightness");
            handler.postDelayed(
                    () -> hintText.animate().alpha(0f).setDuration(1200).start(), 2000);
            return true;
        });

        int bpm = HrService.lastBpm;
        bpmText.setText(bpm > 0 ? String.valueOf(bpm) : "--");
        if (!HrService.isRunning) {
            bpmLabel.setText("BPM · bridge stopped");
        }

        // Fade the hint out after a few seconds so the screen goes truly minimal.
        if (hintShown) {
            hintText.setAlpha(0f);
        } else {
            hintShown = true;
            handler.postDelayed(() -> hintText.animate().alpha(0f).setDuration(1200).start(), 4000);
        }
    }

    // =====================================================================
    //  Battery: level, watts, time remaining
    // =====================================================================

    /**
     * Builds the battery line from a sticky ACTION_BATTERY_CHANGED intent.
     *
     * Watts are derived from the framework's instantaneous current and voltage
     * (P = V * I). Current is reported in µA and voltage in mV, and the sign of
     * the current is vendor-dependent, so magnitude is used and the direction is
     * taken from the charging status instead.
     */
    private void updateBattery(Intent battery) {
        if (batteryText == null || battery == null) return;

        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int voltageMv = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);

        if (level < 0 || scale <= 0) {
            batteryText.setText("");
            return;
        }

        int pct = Math.round(level * 100f / scale);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        boolean full = status == BatteryManager.BATTERY_STATUS_FULL || pct >= 100;

        StringBuilder sb = new StringBuilder();
        sb.append(charging ? "⚡ " : "🔋 ").append(pct).append('%');

        double watts = instantaneousWatts(voltageMv);
        if (watts >= 0.05) {
            sb.append("  ·  ").append(String.format(Locale.US, "%.1f W", watts));
        }

        String remaining = timeEstimate(charging, full, pct);
        if (remaining != null) {
            sb.append("  ·  ").append(remaining);
        }

        batteryText.setText(sb.toString());
        // Green while charging, amber when low, neutral otherwise.
        batteryText.setTextColor(charging ? 0xFF8AC08A : (pct <= 15 ? 0xFFD98A3A : 0xFF9A9A9A));
    }

    /**
     * Re-reads the sticky battery intent without needing a broadcast. A null
     * receiver is the documented way to peek at a sticky value; it needs the
     * export flag on Android 14+ just like a real registration.
     */
    private void refreshBattery() {
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent sticky = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    ? registerReceiver(null, f, Context.RECEIVER_EXPORTED)
                    : registerReceiver(null, f);
            updateBattery(sticky);
        } catch (Exception e) {
            Log.w(TAG, "Battery refresh failed: " + e.getMessage());
        }
    }

    /** P = V * I, from the framework's instantaneous current reading. */
    private double instantaneousWatts(int voltageMv) {
        if (batteryManager == null || voltageMv <= 0) return -1;
        int currentUa = batteryManager.getIntProperty(
                BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        if (currentUa == 0 || currentUa == Integer.MIN_VALUE) return -1;

        double amps = Math.abs(currentUa) / 1_000_000.0;
        double volts = voltageMv / 1000.0;
        double w = amps * volts;
        // Some vendors report current in mA rather than µA; scale back if the
        // result is physically implausible for a phone.
        if (w > 250) w /= 1000.0;
        return w;
    }

    /**
     * Time to full while charging, or time to empty while discharging.
     * Prefers the OS estimate on Android 9+, then falls back to
     * capacity / current, which is what most devices can actually support.
     */
    private String timeEstimate(boolean charging, boolean full, int pct) {
        if (full) return "full";

        if (charging && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && batteryManager != null) {
            long ms = batteryManager.computeChargeTimeRemaining();
            if (ms > 0) return formatDuration(ms / 1000L) + " to full";
        }

        if (batteryManager == null) return null;
        int currentUa = batteryManager.getIntProperty(
                BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        if (currentUa == 0 || currentUa == Integer.MIN_VALUE) return null;

        double currentMa = Math.abs(currentUa) / 1000.0;
        if (currentMa < 1) return null;

        // charge_counter is the charge currently in the pack, in µAh.
        int counterUah = batteryManager.getIntProperty(
                BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        if (counterUah <= 0) return null;

        double presentMah = counterUah / 1000.0;
        double neededMah = charging
                ? presentMah * (100.0 - pct) / Math.max(1, pct)  // charge still missing
                : presentMah;                                     // charge left to burn

        double hours = neededMah / currentMa;
        if (hours <= 0 || hours > 72) return null;

        return formatDuration((long) (hours * 3600)) + (charging ? " to full" : " left");
    }

    private static String formatDuration(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        if (h > 0) return m > 0 ? h + "h " + m + "m" : h + "h";
        return Math.max(1, m) + "m";
    }

    // =====================================================================
    //  Ambient light -> brightness
    // =====================================================================

    /** Applies the current mode: inherit system brightness, or start dimming. */
    private void applyBrightnessMode() {
        if (dimMode) {
            setBrightness(lightSensor != null && smoothedLux >= 0f
                    ? luxToBrightness(smoothedLux)
                    : DEFAULT_BRIGHTNESS);
        } else {
            // Hand brightness back to the system and clear the content fade.
            appliedBrightness = FOLLOW_SYSTEM;
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = FOLLOW_SYSTEM;
            getWindow().setAttributes(lp);
            if (root != null) root.setAlpha(1f);
        }
    }

    /** The light sensor is only worth listening to while auto-dim is on. */
    private void updateSensorListener() {
        if (sensorManager == null || lightSensor == null) return;
        if (dimMode) {
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        } else {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!dimMode) return;
        if (event.sensor.getType() != Sensor.TYPE_LIGHT) return;

        float lux = Math.max(0f, event.values[0]);
        // Exponential moving average so a passing shadow doesn't strobe the screen.
        smoothedLux = (smoothedLux < 0f)
                ? lux
                : smoothedLux + LUX_SMOOTHING * (lux - smoothedLux);

        setBrightness(luxToBrightness(smoothedLux));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Nothing to do.
    }

    /** Piecewise interpolation over the lux table, log-spaced to match perception. */
    private static float luxToBrightness(float lux) {
        if (lux <= LUX_POINTS[0]) return BRIGHTNESS_POINTS[0];
        int last = LUX_POINTS.length - 1;
        if (lux >= LUX_POINTS[last]) return BRIGHTNESS_POINTS[last];

        for (int i = 0; i < last; i++) {
            if (lux <= LUX_POINTS[i + 1]) {
                float loLux = (float) Math.log10(1 + LUX_POINTS[i]);
                float hiLux = (float) Math.log10(1 + LUX_POINTS[i + 1]);
                float t = (((float) Math.log10(1 + lux)) - loLux) / (hiLux - loLux);
                return BRIGHTNESS_POINTS[i]
                        + t * (BRIGHTNESS_POINTS[i + 1] - BRIGHTNESS_POINTS[i]);
            }
        }
        return BRIGHTNESS_POINTS[last];
    }

    private void setBrightness(float target) {
        if (!dimMode) return;

        float b = Math.max(0.04f, Math.min(1f, target));
        if (appliedBrightness >= 0f && Math.abs(b - appliedBrightness) < BRIGHTNESS_EPSILON) {
            return;
        }
        appliedBrightness = b;

        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = b;
        getWindow().setAttributes(lp);

        // Only fade content in genuine darkness. The old curve faded at every
        // level, which stacked on top of the dim override and made the text
        // washed out even in daylight.
        if (root != null) {
            root.setAlpha(b < 0.10f ? 0.80f : 1f);
        }
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
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
        // Sticky broadcast: this returns the current battery state right away.
        // ACTION_BATTERY_CHANGED is a protected system broadcast, so on
        // Android 14+ it must be registered RECEIVER_EXPORTED. Registering it
        // without an export flag throws SecurityException and kills the screen.
        IntentFilter batteryFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryReceiver, batteryFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(batteryReceiver, batteryFilter);
        }

        applyBrightnessMode();
        updateSensorListener();
        handler.post(tick);
        handler.postDelayed(shift, SHIFT_MS);
        hideSystemBars();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(bpmReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) {}
        if (lightSensor != null) {
            sensorManager.unregisterListener(this);
        }
        handler.removeCallbacks(tick);
        handler.removeCallbacks(shift);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
