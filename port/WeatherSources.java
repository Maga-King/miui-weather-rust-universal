package dev.weather.oneplus;

import android.content.Context;
import java.io.File;

/** Legacy Xiaomi cache boundary. Alternate-source routing has been withdrawn. */
public final class WeatherSources {
    public static final Object LOCK=new Object();
    private static final WeatherSourceSession ORIGINAL=new WeatherSourceSession("xiaomi","weather.db",0,"");
    private WeatherSources(){}
    public static WeatherSourceSession current(Context context){return ORIGINAL;}
    public static boolean isCurrent(Context context,WeatherSourceSession session){return ORIGINAL.same(session);}
    public static File database(Context context){return context.getDatabasePath("weather.db");}
    public static File database(Context context,String sourceId){
        if(!"xiaomi".equals(sourceId))throw new IllegalArgumentException("Alternate weather sources are unavailable");
        return database(context);
    }
    public static String scopedName(Context context,String legacy){return legacy;}
    public static String scopedName(String legacy,WeatherSourceSession session){
        if(!ORIGINAL.same(session))throw new IllegalArgumentException("Alternate weather sources are unavailable");
        return legacy;
    }
    /** Restore users who selected an unfinished source in the short-lived test build. */
    static void restoreOriginal(Context context){
        android.content.SharedPreferences settings=context.getSharedPreferences("weather_sources_v1",Context.MODE_PRIVATE);
        if("xiaomi".equals(settings.getString("source","xiaomi")))return;
        synchronized(LOCK){
            if(!settings.edit().putString("source","xiaomi").putLong("epoch",0).remove("local_token").commit())
                throw new IllegalStateException("Could not restore Xiaomi weather");
            WeatherRefresh.cancel(context);
            WeatherRefresh.state(context).edit().remove("last_success_ms").remove("success_city_id")
                .remove("success_unit").remove("success_observed_at_ms").remove("next_attempt_ms")
                .remove("consecutive_failures").remove("last_error").remove("scheduled_interval_minutes")
                .remove("next_periodic_ms").commit();
        }
    }
}
