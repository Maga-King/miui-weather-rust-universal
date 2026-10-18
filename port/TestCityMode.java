package dev.weather.oneplus;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import org.json.JSONException;
import org.json.JSONObject;

/** Private opt-in configuration only. Does not open, copy, replace or delete any database. */
public final class TestCityMode {
    private static final String PREFS = "local_test_city_mode_v1";
    private TestCityMode() {}
    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    private static void checkPackage(Context context) {
        if (!"dev.weather.test".equals(context.getPackageName()))
            throw new SecurityException("Test city is restricted to the experimental package");
    }
    public static boolean enabled(Context context) {
        checkPackage(context);
        return preferences(context).getBoolean("enabled", false);
    }
    /** Caller must restart the Dart engine when entering/leaving the separate DB namespace. */
    public static synchronized long enable(Context context, JSONObject profile) throws JSONException {
        checkPackage(context);
        JSONObject valid = TestCityFixtures.validate(profile);
        SharedPreferences prefs = preferences(context);
        long generation = prefs.getLong("generation", 0) + 1;
        if (!prefs.edit().putString("profile", valid.toString()).putBoolean("enabled", true)
                .putLong("generation", generation).commit()) throw new IllegalStateException("Cannot save test configuration");
        return generation;
    }
    public static synchronized long disable(Context context) {
        checkPackage(context);
        SharedPreferences prefs = preferences(context);
        long generation = prefs.getLong("generation", 0) + 1;
        if (!prefs.edit().putBoolean("enabled", false).putLong("generation", generation).commit())
            throw new IllegalStateException("Cannot exit test mode");
        return generation;
    }
    public static long generation(Context context) { checkPackage(context); return preferences(context).getLong("generation", 0); }
    public static boolean sameSession(Context context, long generation, boolean wasEnabled) {
        return generation(context) == generation && enabled(context) == wasEnabled;
    }
    /** Invalid enabled state fails closed; it must never trigger real-network fallback. */
    public static JSONObject profile(Context context) throws JSONException {
        if (!enabled(context)) throw new IllegalStateException("Test mode is disabled");
        return TestCityFixtures.validate(new JSONObject(preferences(context).getString("profile", "{}")));
    }
    /** Pass this root to Dart path setup before the test engine starts. */
    public static File isolatedRoot(Context context) {
        checkPackage(context);
        return context.getFilesDir().getParentFile();
    }
    public static File isolatedDatabase(Context context) {
        return new File(new File(isolatedRoot(context), "databases"), "weather.db");
    }
    /** Read at the widget boundary, bypassing the real widget cache without modifying it. */
    public static WeatherWidgetData widget(Context context, String unit, String locale, long nowMs) throws JSONException {
        JSONObject response = TestCityFixtures.weather(profile(context), nowMs);
        WeatherWidgetData parsed = XiaomiWeatherParser.parse(response, TestCityFixtures.CITY_ID,
                TestCityFixtures.CITY_NAME, unit, locale);
        JSONObject labeled = parsed.toJson();
        labeled.put("description", TestCityFixtures.SOURCE + " · " + parsed.description);
        return new WeatherWidgetData(labeled);
    }
}
