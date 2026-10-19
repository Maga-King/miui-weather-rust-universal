package dev.weather.oneplus;

import android.content.Context;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;

/** Per-Activity density. Never mutates Application or system Resources. */
public final class WeatherDisplayDensity {
    public static final int MIN_OFFSET = -240;
    public static final int MAX_OFFSET = 240;
    private static final String PREFS = "weather_display_density";
    private WeatherDisplayDensity() {}

    public static int offset(Context context) {
        return clamp(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt("relative_offset", 0), MIN_OFFSET, MAX_OFFSET);
    }

    public static void saveOffset(Context context, int value) {
        boolean saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("relative_offset", clamp(value, MIN_OFFSET, MAX_OFFSET)).commit();
        if (!saved) throw new IllegalStateException("Could not save display density");
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Clamp pathological values without fixing DPI to the first resolution seen. */
    public static int effectiveDpi(int systemDpi, int offset) {
        if (offset == 0) return systemDpi;
        return (int)Math.max(120L, Math.min(960L,
                (long)systemDpi + clamp(offset, MIN_OFFSET, MAX_OFFSET)));
    }

    /** Start from unmodified Application resources on the current display, never this
     * Activity's overridden resources (which would accumulate offset on every call). */
    public static int systemDpi(Context context) {
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        Display display = null;
        if (Build.VERSION.SDK_INT >= 30) {
            try { display = context.getDisplay(); } catch (UnsupportedOperationException ignored) {}
        }
        if (display == null) {
            DisplayManager manager = (DisplayManager)app.getSystemService(Context.DISPLAY_SERVICE);
            if (manager != null) display = manager.getDisplay(Display.DEFAULT_DISPLAY);
        }
        Context source = display == null ? app : app.createDisplayContext(display);
        int dpi = source.getResources().getConfiguration().densityDpi;
        return dpi > 0 ? dpi : source.getResources().getDisplayMetrics().densityDpi;
    }

    /** Call only from attachBaseContext, passing the original base Context. */
    public static Context wrap(Context base) {
        int original = systemDpi(base);
        int target = effectiveDpi(original, offset(base));
        if (target == original) return base;
        // A sparse override inherits night mode, font scale, locale and window
        // bounds on later system updates. Copying the full Configuration would
        // pin all those values to the moment this Activity was created.
        Configuration config = new Configuration();
        config.densityDpi = target;
        return base.createConfigurationContext(config);
    }

    /** Compare against the density recorded at attach time, not incoming Configuration:
     * Android may already have updated its Resources before onConfigurationChanged. */
    public static boolean needsRecreation(Context context, int attachedDpi) {
        return attachedDpi != effectiveDpi(systemDpi(context), offset(context));
    }
}
