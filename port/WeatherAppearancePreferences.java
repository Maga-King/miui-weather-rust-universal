package dev.weather.oneplus;

import android.content.Context;
import android.content.SharedPreferences;

/** App-local appearance settings shared by the screen, compatibility bridge and renderer. */
public final class WeatherAppearancePreferences {
    private static final String FILE = "weather_appearance_v1";
    private static final String HOME_CARD_BLUR = "home_card_blur";

    private WeatherAppearancePreferences() {}

    public static boolean homeCardBlurEnabled(Context context) {
        return preferences(context).getBoolean(HOME_CARD_BLUR, true);
    }



    public static void setHomeCardBlurEnabled(Context context, boolean enabled) {
        save(context, HOME_CARD_BLUR, enabled);
    }



    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static void save(Context context, String key, boolean enabled) {
        if (!preferences(context).edit().putBoolean(key, enabled).commit())
            throw new IllegalStateException("Could not save appearance option: " + key);
    }
}
