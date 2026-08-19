package com.hrbridge;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;

/**
 * Battery readout shared by the main screen and the always-on display.
 *
 * Produces a single line such as:
 *   ⚡ 64%  ·  charging · AC  ·  27.4 W  ·  38m to full
 *   🔋 82%  ·  on battery  ·  2.1 W  ·  9h 12m left
 *
 * Watts are P = V * I. Both readings prefer the framework and fall back to the
 * kernel's power_supply nodes, because several OEMs return 0 from
 * BATTERY_PROPERTY_CURRENT_NOW while still exposing a working current_now file.
 */
final class BatteryInfo {

    /** Charging green, low-battery amber, and the normal neutral grey. */
    static final int COLOR_CHARGING = 0xFF5CE68A;
    static final int COLOR_WARN     = 0xFFFFA23A;
    static final int COLOR_NEUTRAL  = 0xFFB8B8B8;

    /** Formatted line, ready to drop into a TextView. */
    final String text;
    /** Colour matching the current state. */
    final int color;

    private BatteryInfo(String text, int color) {
        this.text = text;
        this.color = color;
    }

    /**
     * Reads the sticky ACTION_BATTERY_CHANGED broadcast and formats it.
     * Never returns null and never yields an empty string: if something can't
     * be read, the line says so rather than silently rendering blank.
     */
    static BatteryInfo read(Context context) {
        Intent battery = null;
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            // ACTION_BATTERY_CHANGED is a protected system broadcast, so on
            // Android 14+ even a null-receiver peek needs the export flag.
            battery = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    ? context.registerReceiver(null, f, Context.RECEIVER_EXPORTED)
                    : context.registerReceiver(null, f);
        } catch (Exception ignored) {
            // Fall through to the unavailable branch below.
        }
        return from(context, battery);
    }

    /** Formats an already-received battery intent. */
    static BatteryInfo from(Context context, Intent battery) {
        if (battery == null) {
            return new BatteryInfo("battery unavailable", COLOR_WARN);
        }

        BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);

        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int voltageMv = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);

        int pct = (level >= 0 && scale > 0) ? Math.round(level * 100f / scale) : -1;

        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL
                || plugged != 0;
        boolean full = status == BatteryManager.BATTERY_STATUS_FULL
                || (charging && pct >= 100);

        StringBuilder sb = new StringBuilder();
        sb.append(charging ? "⚡ " : "🔋 ");
        sb.append(pct >= 0 ? pct + "%" : "--%");
        sb.append("  ·  ").append(statusLabel(status, plugged, full));

        double watts = watts(bm, voltageMv);
        if (watts > 0) {
            sb.append("  ·  ").append(String.format(Locale.US, "%.1f W", watts));
        } else if (charging) {
            // Wattage is the headline number while charging, so be explicit
            // when the device refuses to report it.
            sb.append("  ·  W n/a");
        }

        String remaining = timeEstimate(bm, charging, full, pct);
        if (remaining != null) {
            sb.append("  ·  ").append(remaining);
        }

        int color = charging
                ? COLOR_CHARGING
                : (pct >= 0 && pct <= 15 ? COLOR_WARN : COLOR_NEUTRAL);

        return new BatteryInfo(sb.toString(), color);
    }

    /** Human-readable charge state, including how it's plugged in. */
    private static String statusLabel(int status, int plugged, boolean full) {
        if (full) return "full";

        String source = null;
        if (plugged == BatteryManager.BATTERY_PLUGGED_AC) source = "AC";
        else if (plugged == BatteryManager.BATTERY_PLUGGED_USB) source = "USB";
        else if (plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) source = "wireless";

        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING:
                return source != null ? "charging · " + source : "charging";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                return plugged != 0 ? "plugged · not charging" : "not charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING:
                return "discharging";
            default:
                if (plugged != 0) return source != null ? "charging · " + source : "charging";
                return "on battery";
        }
    }

    /** Charge/discharge power in watts, or -1 if current isn't reported. */
    private static double watts(BatteryManager bm, int voltageMvExtra) {
        double volts = volts(voltageMvExtra);
        if (volts <= 0) return -1;

        double mA = currentMilliAmps(bm);
        if (mA <= 0) return -1;

        double w = (mA / 1000.0) * volts;
        // Nothing phone-shaped charges above ~250 W, so a larger result means
        // the units were off by a factor of 1000.
        while (w > 250) w /= 1000.0;
        return w >= 0.05 ? w : -1;
    }

    /** Battery voltage in volts, from sysfs if possible, else the extra (mV). */
    private static double volts(int voltageMvExtra) {
        long raw = readLong("/sys/class/power_supply/battery/voltage_now");
        if (raw > 0) {
            double v = raw / 1_000_000.0;      // normally µV
            if (v < 1.5) v = raw / 1000.0;     // some kernels publish mV
            if (v >= 2.0 && v <= 20.0) return v;
        }
        if (voltageMvExtra > 0) {
            double v = voltageMvExtra / 1000.0;
            if (v >= 2.0 && v <= 20.0) return v;
        }
        return -1;
    }

    /** Absolute current draw in mA: framework first, then kernel nodes. */
    private static double currentMilliAmps(BatteryManager bm) {
        if (bm != null) {
            int uA = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (uA != 0 && uA != Integer.MIN_VALUE) {
                double mA = Math.abs(uA) / 1000.0;
                // Some vendors already report mA here; under 1 mA is implausible.
                if (mA < 1.0) mA = Math.abs(uA);
                if (mA >= 1.0 && mA <= 30_000) return mA;
            }
        }

        for (String path : new String[]{
                "/sys/class/power_supply/battery/current_now",
                "/sys/class/power_supply/bms/current_now"}) {
            long raw = readLong(path);
            if (raw == Long.MIN_VALUE || raw == 0) continue;
            double mA = Math.abs(raw) / 1000.0;
            if (mA < 1.0) mA = Math.abs(raw);
            if (mA >= 1.0 && mA <= 30_000) return mA;
        }
        return -1;
    }

    /**
     * Time to full while charging, or time to empty while discharging.
     * Prefers the OS estimate on API 28+, then falls back to charge / current.
     */
    private static String timeEstimate(BatteryManager bm, boolean charging,
                                       boolean full, int pct) {
        if (full) return "full";
        if (bm == null || pct < 0) return null;

        if (charging && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                long ms = bm.computeChargeTimeRemaining();
                if (ms > 0) return formatDuration(ms / 1000L) + " to full";
            } catch (Exception ignored) {}
        }

        double mA = currentMilliAmps(bm);
        if (mA <= 0) return null;

        // charge_counter is the charge currently in the pack, in µAh.
        int counterUah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        if (counterUah <= 0) return null;

        double presentMah = counterUah / 1000.0;
        double neededMah = charging
                ? presentMah * (100.0 - pct) / Math.max(1, pct)  // charge still missing
                : presentMah;                                    // charge left to burn

        double hours = neededMah / mA;
        if (hours <= 0 || hours > 72) return null;

        return formatDuration((long) (hours * 3600)) + (charging ? " to full" : " left");
    }

    private static String formatDuration(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        if (h > 0) return m > 0 ? h + "h " + m + "m" : h + "h";
        return Math.max(1, m) + "m";
    }

    /** Reads a single long from a sysfs node. Long.MIN_VALUE if unreadable. */
    private static long readLong(String path) {
        BufferedReader r = null;
        try {
            File f = new File(path);
            if (!f.canRead()) return Long.MIN_VALUE;
            r = new BufferedReader(new FileReader(f), 64);
            String line = r.readLine();
            if (line == null) return Long.MIN_VALUE;
            return Long.parseLong(line.trim());
        } catch (Exception e) {
            return Long.MIN_VALUE;
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) {}
        }
    }
}
