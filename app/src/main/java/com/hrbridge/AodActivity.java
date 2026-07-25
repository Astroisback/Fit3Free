package com.hrbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
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

    /** Ambient light (lux) mapped to window brightness, interpolated in log space. */
    private static final float[] LUX_POINTS = {0f, 3f, 10f, 50f, 200f, 800f, 3000f, 10000f};
    private static final float[] BRIGHTNESS_POINTS = {
            0.010f, 0.030f, 0.060f, 0.120f, 0.250f, 0.450f, 0.700f, 1.000f};

    /** Fallback when the device has no light sensor. */
    private static final float DEFAULT_BRIGHTNESS = 0.02f;

    /** Smoothing factor for the lux EMA: lower = calmer, less flicker. */
    private static final float LUX_SMOOTHING = 0.12f;
    /** Don't touch the window unless the target moved by at least this much. */
    private static final float BRIGHTNESS_EPSILON = 0.004f;

    private TextView clockText, dateText, bpmText, bpmLabel, hintText;
    private View root;

    private SensorManager sensorManager;
    private Sensor lightSensor;
    private float smoothedLux = -1f;
    private float appliedBrightness = -1f;

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

        // Start dim; the first sensor reading corrects it within a frame or two.
        setBrightness(DEFAULT_BRIGHTNESS);
        if (lightSensor == null) {
            Log.i(TAG, "No ambient light sensor, holding fixed brightness");
        }

        hideSystemBars();

        root.setOnClickListener(v -> finish());

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
    //  Ambient light -> brightness
    // =====================================================================

    @Override
    public void onSensorChanged(SensorEvent event) {
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
        float b = Math.max(0.005f, Math.min(1f, target));
        if (appliedBrightness >= 0f && Math.abs(b - appliedBrightness) < BRIGHTNESS_EPSILON) {
            return;
        }
        appliedBrightness = b;

        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = b;
        getWindow().setAttributes(lp);

        // In a dark room the panel can only go so low, so pull the content
        // alpha down too. That's what actually makes it comfortable at night.
        if (root != null) {
            float alpha = Math.max(0.55f, Math.min(1f, 0.55f + b * 0.9f));
            root.setAlpha(alpha);
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
        if (lightSensor != null) {
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
        handler.post(tick);
        handler.postDelayed(shift, SHIFT_MS);
        hideSystemBars();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(bpmReceiver); } catch (Exception ignored) {}
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
