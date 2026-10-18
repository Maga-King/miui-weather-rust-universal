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
        try {
            if (loader == null) {
                // The host resource package must be 0x7e; retain the original module IDs.
                int id = context.getResources().getIdentifier("weather_widget_name", "string", context.getPackageName());
                if ((id >>> 24) != 0x7e) throw new IllegalStateException("Host resource package must use 0x7e");
                File archive = new File(context.getCodeCacheDir(), "original-weather-pages-v1.apk");
                try (InputStream in = context.getAssets().open("weather_pages/resources.apk");
                     FileOutputStream out = new FileOutputStream(archive)) {
                    byte[] buffer = new byte[65536]; int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                }
                loader = new ResourcesLoader();
                try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY)) {
                    loader.addProvider(ResourcesProvider.loadFromApk(fd));
                }
            }
            add(context.getApplicationContext().getResources());
            add(context.getResources());
        } catch (Exception error) { throw new IllegalStateException("Cannot load original weather pages", error); }
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
        boolean rain = method.startsWith("start_rain_map_activity");
        Intent intent = new Intent();
        intent.setClassName(activity, "dev.weather.oneplus." + (rain ? "OriginalRainActivity" : "OriginalAqiActivity"));
        // Original CityData's Parcel reader/writer formats differ in this ROM.
        // Carry the JSON across Binder, then populate the original local object.
        intent.putExtra("weather_city_json", json);
        Object weather = values.opt("weatherType");
        int weatherType = 0;
        try { weatherType = Integer.parseInt(String.valueOf(weather)); } catch (NumberFormatException ignored) { }
        intent.putExtra("weather_type", weatherType);
        intent.putExtra("is_night", values.optBoolean("isNight", false));
        Object index = values.opt("cityIndex");
        intent.putExtra("city_index", index instanceof Number ? ((Number) index).intValue() : 0);
        Object from = values.opt("from");
        intent.putExtra("from", from == null || from == JSONObject.NULL ? "" : String.valueOf(from));
        activity.startActivity(intent);
        Log.i("WeatherPages", "Started original " + (rain ? "rain" : "AQI") + " activity");
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
