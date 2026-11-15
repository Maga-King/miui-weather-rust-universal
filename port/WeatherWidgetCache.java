package dev.weather.oneplus;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONObject;

/** Atomic local snapshot, independent of network-success bookkeeping. */
public final class WeatherWidgetCache {
    /** A retained snapshot is a successful cache operation, not a disk-write failure. */
    enum WriteResult { REPLACED, UNCHANGED, RETAINED_NEWER, FAILED }

    private static SharedPreferences preferences(Context context,WeatherSourceSession session) {
        return context.getSharedPreferences(WeatherSources.scopedName("weather_widget_cache",session), Context.MODE_PRIVATE);
    }
    public static WeatherWidgetData read(Context context) {
        return read(context,WeatherSources.current(context));
    }
    static WeatherWidgetData read(Context context,WeatherSourceSession session) {
        synchronized(WeatherSources.LOCK) {
        if(!WeatherSources.isCurrent(context,session))return null;
        SharedPreferences prefs=preferences(context,session);
        String source=prefs.getString("source_id",null);
        if(source==null?!session.xiaomi():!session.sourceId.equals(source))return null;
        String json = prefs.getString("snapshot", null);
        if (json == null) return null;
        try {
            WeatherWidgetData data=new WeatherWidgetData(new JSONObject(json));
            String identity=prefs.getString("snapshot_identity",null);
            if(identity!=null) {
                WeatherDatabaseBridge.City city=WeatherDatabaseBridge.cityById(context,data.cityId,session);
                if(city==null||!identity.equals(city.cacheIdentity()))return null;
            } else if(!session.xiaomi())return null;
            return WeatherSources.isCurrent(context,session)?data:null;
        }
        catch (Exception error) { Log.w("WeatherWidget", "Invalid cached observation", error); return null; }
        }
    }
    /**
     * Importing original weather.db never reports a network fetch. True means the
     * observation was handled successfully; an already newer snapshot may be retained.
     */
    public static boolean publishCached(Context context, WeatherWidgetData data) {
        return publish(context, data) != WriteResult.FAILED;
    }
    static boolean publishCached(Context context,WeatherWidgetData data,WeatherSourceSession session) {
        return publish(context,data,session)!=WriteResult.FAILED;
    }
    static WriteResult publish(Context context, WeatherWidgetData data) {
        WeatherSourceSession session=WeatherSources.current(context);
        // Source-less legacy callers can only publish original Xiaomi data.
        return session.xiaomi()?publish(context,data,session):WriteResult.FAILED;
    }
    static WriteResult publish(Context context,WeatherWidgetData data,WeatherSourceSession session) {
        if (data == null) return WriteResult.FAILED;
        synchronized (WeatherSources.LOCK) {
        if(!WeatherSources.isCurrent(context,session))return WriteResult.FAILED;
        try {
            WeatherDatabaseBridge.City city=WeatherDatabaseBridge.cityById(context,data.cityId,session);
            if(city!=null&&!city.unit.equals(data.unit))return WriteResult.FAILED;
            if(city==null&&!session.xiaomi())return WriteResult.FAILED;
            String identity=city==null?null:city.cacheIdentity();
            WeatherWidgetData previous = read(context,session);
            if (previous != null && previous.cityId.equals(data.cityId) && previous.unit.equals(data.unit)
                    && previous.observedAtMs > data.observedAtMs) return WriteResult.RETAINED_NEWER;
            // Zero means unknown, so it cannot displace a known newer observation.
            // Equal publication times may carry changed details such as minute rain.
            // City/unit changes are allowed, retaining the incoming source timestamp.
            String json = data.toJson().toString();
            String savedIdentity=preferences(context,session).getString("snapshot_identity",null);
            if (previous != null && json.equals(previous.toJson().toString())
                    &&(identity==null?savedIdentity==null:identity.equals(savedIdentity))) return WriteResult.UNCHANGED;
            if (!preferences(context,session).edit().putString("snapshot", json)
                    .putString("source_id",session.sourceId).putString("snapshot_identity",identity)
                    .commit()) return WriteResult.FAILED;
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
        clear(context,WeatherSources.current(context));
    }
    static void clear(Context context,WeatherSourceSession session) {
        synchronized (WeatherSources.LOCK) {
            if(!WeatherSources.isCurrent(context,session))return;
            preferences(context,session).edit().remove("snapshot").remove("source_id")
                    .remove("snapshot_identity").commit();
        }
        WeatherWidgetProvider.updateAll(context);
    }
}
