package dev.weather.oneplus;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONObject;

/** Atomic local snapshot, independent of network-success bookkeeping. */
public final class WeatherWidgetCache {
    /** A retained snapshot is a successful cache operation, not a disk-write failure. */
    enum WriteResult { REPLACED, UNCHANGED, RETAINED_NEWER, FAILED }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("weather_widget_cache", Context.MODE_PRIVATE);
    }
    public static synchronized WeatherWidgetData read(Context context) {
        String json = preferences(context).getString("snapshot", null);
        if (json == null) return null;
        try { return new WeatherWidgetData(new JSONObject(json)); }
        catch (Exception error) { Log.w("WeatherWidget", "Invalid cached observation", error); return null; }
    }
    /**
     * Importing original weather.db never reports a network fetch. True means the
     * observation was handled successfully; an already newer snapshot may be retained.
     */
    public static boolean publishCached(Context context, WeatherWidgetData data) {
        return publish(context, data) != WriteResult.FAILED;
    }
    static WriteResult publish(Context context, WeatherWidgetData data) {
        if (data == null) return WriteResult.FAILED;
        synchronized (WeatherWidgetCache.class) {
        try {
            WeatherWidgetData previous = read(context);
            if (previous != null && previous.cityId.equals(data.cityId) && previous.unit.equals(data.unit)
                    && previous.observedAtMs > data.observedAtMs) return WriteResult.RETAINED_NEWER;
            // Zero means unknown, so it cannot displace a known newer observation.
            // Equal publication times may carry changed details such as minute rain.
            // City/unit changes are allowed, retaining the incoming source timestamp.
            String json = data.toJson().toString();
            if (previous != null && json.equals(previous.toJson().toString())) return WriteResult.UNCHANGED;
            if (!preferences(context).edit().putString("snapshot", json).commit()) return WriteResult.FAILED;
        } catch (Exception error) {
            Log.w("WeatherWidget", "Cannot save observation", error); return WriteResult.FAILED;
        }
        }
        // A renderer failure does not undo a successfully committed observation.
        try { WeatherWidgetProvider.updateAll(context); }
        catch (Exception error) { Log.w("WeatherWidget", "Cannot redraw saved observation", error); }
        return WriteResult.REPLACED;
    }
    /** Call only when the selected city is removed or the user clears weather data. */
    public static void clear(Context context) {
        synchronized (WeatherWidgetCache.class) {
            preferences(context).edit().remove("snapshot").commit();
        }
        WeatherWidgetProvider.updateAll(context);
    }
}
