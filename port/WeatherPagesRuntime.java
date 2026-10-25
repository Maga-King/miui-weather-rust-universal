package dev.weather.oneplus;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.util.Log;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.WeakHashMap;

/** Hosts the original XmsCore weather screens and their unmodified 0x7f resources. */
public final class WeatherPagesRuntime {
    private static ResourcesLoader loader;
    private static final WeakHashMap<Resources, Boolean> loaded = new WeakHashMap<>();
    private WeatherPagesRuntime() { }

    public static synchronized void install(Context context) {
        if (Build.VERSION.SDK_INT < 30) throw new UnsupportedOperationException("Original weather pages require Android 11");
        android.os.Trace.beginSection("WeatherPages.install");
        try {
            if (loader == null) {
                // The host resource package must be 0x7e; retain the original module IDs.
                int id = context.getResources().getIdentifier("weather_widget_name", "string", context.getPackageName());
                if ((id >>> 24) != 0x7e) throw new IllegalStateException("Host resource package must use 0x7e");
                ResourcesLoader prepared = new ResourcesLoader();
                // Reuse immutable resources across process restarts, invalidate on APK update.
                // AtomicFile prevents a killed copy from becoming the next process's input.
                for (int attempt=0; attempt<2; attempt++) {
                    File archive=resourceArchive(context,attempt!=0);
                    try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY)) {
                        prepared.addProvider(ResourcesProvider.loadFromApk(fd));
                        break;
                    } catch (java.io.IOException error) { if(attempt!=0)throw error; }
                }
                loader=prepared;
            }
            add(context.getApplicationContext().getResources());
            add(context.getResources());
        } catch (Exception error) { throw new IllegalStateException("Cannot load original weather pages", error); }
        finally { android.os.Trace.endSection(); }
    }

    private static File resourceArchive(Context context,boolean force) throws Exception {
        File archive=new File(context.getCodeCacheDir(),"original-weather-pages-v1.apk");
        android.content.SharedPreferences cache=context.getSharedPreferences("weather_page_resources",Context.MODE_PRIVATE);
        String source=context.getApplicationInfo().sourceDir;
        if(!force&&archive.isFile()&&archive.length()>0&&source.equals(cache.getString("source",null))
                &&archive.length()==cache.getLong("length",-1))return archive;
        android.os.Trace.beginSection("WeatherPages.copyResources");
        android.util.AtomicFile atomic=new android.util.AtomicFile(archive);
        FileOutputStream out=null;
        try(InputStream in=context.getAssets().open("weather_pages/resources.apk")){
            out=atomic.startWrite();byte[] buffer=new byte[65536];int count;
            while((count=in.read(buffer))!=-1)out.write(buffer,0,count);
            atomic.finishWrite(out);out=null;
            if(!cache.edit().putString("source",source).putLong("length",archive.length()).commit())
                Log.w("WeatherPages","Resource cache stamp could not be saved");
            return archive;
        }finally{if(out!=null)atomic.failWrite(out);android.os.Trace.endSection();}
    }

    private static void add(Resources resources) {
        if (loaded.containsKey(resources)) return;
        resources.addLoaders(loader);
        loaded.put(resources, Boolean.TRUE);
    }

    public static void open(android.app.Activity activity, String method, Object arguments) throws Exception {
        JSONObject values;
        if (arguments instanceof JSONObject) values = (JSONObject) arguments;
        else if (arguments instanceof Map) values = new JSONObject((Map<?, ?>) arguments);
        else if (arguments instanceof String) values = new JSONObject((String) arguments);
        else throw new IllegalArgumentException("Expected original page arguments object");
        Object city = values.opt("cityData");
        if (city == null || city == JSONObject.NULL) throw new IllegalArgumentException("Missing original cityData");
        install(activity);
        String json;
        if (city instanceof JSONObject || city instanceof String) json = city.toString();
        else if (city instanceof Map) json = new JSONObject((Map<?, ?>) city).toString();
        else throw new IllegalArgumentException("Invalid original cityData object");
        String target;
        if ("start_rain_map_activity".equals(method))
            target = "OriginalRainActivity";
        else if ("start_rain_map_activity_os4".equals(method)) target = "OriginalRainOS4Activity";
        else if ("start_aqi_map_activity".equals(method))
            target = "OriginalAqiActivity";
        else if ("start_aqi_map_activity_os4".equals(method)) target = "OriginalAqiOS4Activity";
        else if ("start_typhoon_map_activity".equals(method)) target = "OriginalTyphoonActivity";
        else if ("start_typhoon_map_activity_os4".equals(method)) target = "OriginalTyphoonOS4Activity";
        else throw new IllegalArgumentException("Unknown original weather page: " + method);
        Intent intent = new Intent();
        intent.setClassName(activity, "dev.weather.oneplus." + target);
        // Original CityData's Parcel reader/writer formats differ in this ROM.
        // Carry the JSON across Binder, then populate the original local object.
        intent.putExtra("weather_city_json", json);
        Object weather = values.opt("weatherType");
        int weatherType = 0;
        try { weatherType = Integer.parseInt(String.valueOf(weather)); } catch (NumberFormatException ignored) { }
        intent.putExtra("weather_type", weatherType);
        intent.putExtra("is_night", values.optBoolean("isNight", false));
        // OS4's original activity consumes snake_case extras; Dart uses camelCase.
        // Leave its own defaults intact when the optional arguments are absent.
        if (values.has("darkMode")) putMode(intent, "dark_mode", values.opt("darkMode"));
        if (values.has("layoutMode")) putMode(intent, "layout_mode", values.opt("layoutMode"));
        Object index = values.opt("cityIndex");
        intent.putExtra("city_index", index instanceof Number ? ((Number) index).intValue() : 0);
        Object from = values.opt("from");
        intent.putExtra("from", from == null || from == JSONObject.NULL ? "" : String.valueOf(from));
        activity.startActivity(intent);
        Log.i("WeatherPages", "Started " + target);
    }

    private static void putMode(Intent intent, String key, Object value) {
        if (value instanceof Boolean) intent.putExtra(key, (Boolean) value);
        else if (value instanceof Number) intent.putExtra(key, ((Number) value).intValue());
        else if (value instanceof String) intent.putExtra(key, (String) value);
    }

    public static void prepareIntent(android.app.Activity activity) {
        try {
            String json = activity.getIntent().getStringExtra("weather_city_json");
            if (json == null) throw new IllegalArgumentException("Missing original city JSON");
            Class<?> cityClass = Class.forName("com.miui.weather2map.structures.CityData");
            Class<?> gsonClass = Class.forName("com.google.gson.Gson");
            Object gson = gsonClass.getConstructor().newInstance();
            Parcelable data = (Parcelable) gsonClass.getMethod("fromJson", String.class, Class.class).invoke(gson, json, cityClass);
            activity.getIntent().putExtra("city_data", data);
        } catch (Exception error) { throw new IllegalStateException("Cannot decode original city data", error); }
    }
}
