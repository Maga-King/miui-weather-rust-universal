package dev.weather.oneplus;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Atomic, identity-checked widget snapshots; never publishes the global primary cache or alerts. */
public final class WeatherWidgetCityCache {
    private WeatherWidgetCityCache() {}
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("weather_widget_cities_v1",Context.MODE_PRIVATE);
    }
    private static String key(WeatherDatabaseBridge.City city) throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest((city.id+"\n"+city.unit).getBytes(StandardCharsets.UTF_8));
        StringBuilder value=new StringBuilder();
        for(byte b:digest)value.append(String.format(Locale.ROOT,"%02x",b&255));
        return value.toString();
    }
    private static String identity(WeatherDatabaseBridge.City city) {
        return new JSONArray(Arrays.asList(city.id,city.name,city.locationKey,city.latitude,
                city.longitude,city.located,city.locale,city.unit)).toString();
    }
    private static JSONObject entry(Context context,WeatherDatabaseBridge.City city) throws Exception {
        if(city==null||!city.valid())return null;
        String encoded=prefs(context).getString(key(city),null);
        if(encoded==null)return null;
        JSONObject data=new JSONObject(encoded);
        return identity(city).equals(data.optString("identity"))?data:null;
    }
    public static WeatherWidgetData read(Context context,String cityId) {
        try {
            WeatherDatabaseBridge.City city=WeatherDatabaseBridge.cityById(context,cityId);
            JSONObject data=entry(context,city);
            if(data==null)return null;
            WeatherWidgetData observation=new WeatherWidgetData(data.getJSONObject("observation"));
            return city.id.equals(observation.cityId)&&city.unit.equals(observation.unit)?observation:null;
        } catch(Exception error){return null;}
    }
    public static JSONObject raw(Context context,String cityId) {
        try {
            JSONObject data=entry(context,WeatherDatabaseBridge.cityById(context,cityId));
            return data==null?null:data.getJSONObject("raw");
        } catch(Exception error){return null;}
    }
    static boolean fresh(Context context,WeatherDatabaseBridge.City city,long now) {
        try {
            JSONObject data=entry(context,city);
            long fetched=data==null?0:data.optLong("fetchedAtMs");
            return fetched>0&&fetched<=now&&now-fetched<WeatherRefresh.intervalMs(context);
        } catch(Exception error){return false;}
    }
    static synchronized boolean publish(Context context,WeatherDatabaseBridge.City city,
            WeatherRefreshSource.Result result) {
        if(!WeatherRefresh.networkAllowed(context)||city==null||!result.networkVerified
                ||result.observation==null||result.rawResponse==null)return false;
        try {
            if(!city.sameSelection(WeatherDatabaseBridge.cityById(context,city.id))
                    ||!city.id.equals(result.observation.cityId)||!city.unit.equals(result.observation.unit))return false;
            JSONObject old=entry(context,city);
            if(old!=null&&new WeatherWidgetData(old.getJSONObject("observation")).observedAtMs
                    >result.observation.observedAtMs) {
                // A valid revalidation still refreshes TTL, retaining the newer atomic snapshot.
                old.put("fetchedAtMs",System.currentTimeMillis());
                return prefs(context).edit().putString(key(city),old.toString()).commit();
            }
            JSONObject data=new JSONObject().put("identity",identity(city))
                    .put("observation",result.observation.toJson()).put("raw",result.rawResponse)
                    .put("fetchedAtMs",System.currentTimeMillis());
            return prefs(context).edit().putString(key(city),data.toString()).commit();
        } catch(Exception error){Log.w("WeatherWidget","Cannot cache city: "+error.getClass().getSimpleName());return false;}
    }

    /** Only IDs belonging to actually placed widgets; deleted or invalid choices are not substituted. */
    static List<WeatherDatabaseBridge.City> requestedCities(Context context) {
        List<WeatherDatabaseBridge.City> selected=WeatherDatabaseBridge.selectedCities(context);
        String first=selected.isEmpty()?"":selected.get(0).id,located=first;
        for(WeatherDatabaseBridge.City city:selected)if(city.located){located=city.id;break;}
        Set<String> wanted=new LinkedHashSet<>();
        AppWidgetManager manager=AppWidgetManager.getInstance(context);
        Class<?>[] providers={WeatherWidgetProvider.class,WeatherDualCityWidgetProvider.class,
                WeatherHourlyWidgetProvider.class,WeatherSmallWidgetProvider.class,WeatherCustomWidgetProvider.class,
                WeatherDailyWidgetProvider.class,WeatherCompleteWidgetProvider.class,WeatherSunWidgetProvider.class};
        for(Class<?> provider:providers)for(int id:manager.getAppWidgetIds(new ComponentName(context,provider))) {
            boolean dual=provider==WeatherDualCityWidgetProvider.class;
            String city=WeatherWidgetPreferences.cityId(context,id);
            wanted.add(city.isEmpty()?(dual?located:first):city);
            if(dual)wanted.add(WeatherWidgetPreferences.secondCityId(context,id));
        }
        List<WeatherDatabaseBridge.City> result=new ArrayList<>();
        Set<String> added=new HashSet<>();
        for(WeatherDatabaseBridge.City city:selected)
            if(city.valid()&&wanted.contains(city.id)&&added.add(city.id))result.add(city);
        return result;
    }

    /** Same Job's worker, cancellation and deadline. Returns whether a successor should retry. */
    static boolean refreshWidgets(Context context,XiaomiWeatherRefreshSource source,
            WeatherDatabaseBridge.City primary,WeatherRefreshSource.Result primaryResult,
            CancellationSignal cancellation,long deadlineElapsed) {
        boolean retry=false;
        cancellation.throwIfCanceled();
        if(primaryResult!=null&&primaryResult.networkVerified&&primaryResult.rawResponse!=null)
            retry=!publish(context,primary,primaryResult);
        List<WeatherDatabaseBridge.City> requested;
        try{requested=requestedCities(context);}
        catch(Exception error){Log.w("WeatherWidget","Cannot resolve widget cities: "+error.getClass().getSimpleName());return true;}
        // Oldest/missing first, so a tight Job budget cannot starve a later city indefinitely.
        requested.sort((a,b)->Long.compare(attemptedAt(context,a),attemptedAt(context,b)));
        for(WeatherDatabaseBridge.City city:requested) {
            cancellation.throwIfCanceled();
            if(!WeatherRefresh.networkAllowed(context))break;
            if(primary!=null&&city.sameSelection(primary))continue; // Primary already attempted once.
            if(fresh(context,city,System.currentTimeMillis()))continue;
            if(deadlineElapsed-SystemClock.elapsedRealtime()<1000){retry=true;break;}
            try {
                prefs(context).edit().putString("attempt_"+key(city),
                        Long.toString(System.currentTimeMillis())).commit();
                WeatherRefreshSource.Result result=source.fetchCity(context,city,cancellation,deadlineElapsed);
                cancellation.throwIfCanceled();
                if(result.networkVerified)retry|=!publish(context,city,result);
                else retry|=result.retryable;
            } catch(Exception error) {
                cancellation.throwIfCanceled();
                retry=true;
                Log.w("WeatherWidget","City refresh failed: "+error.getClass().getSimpleName());
            }
        }
        return retry;
    }
    private static long attemptedAt(Context context,WeatherDatabaseBridge.City city) {
        try {
            long attempted=Long.parseLong(prefs(context).getString("attempt_"+key(city),"0"));
            return attempted>0&&attempted<=System.currentTimeMillis()?attempted:0;
        }
        catch(Exception error){return 0;}
    }
}
