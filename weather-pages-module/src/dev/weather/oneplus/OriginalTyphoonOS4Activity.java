package dev.weather.oneplus;
import android.content.Context;
import android.os.Bundle;

/** OS4 owns its sheet/window layout; do not substitute the legacy page. */
public final class OriginalTyphoonOS4Activity extends com.miui.weather2map.ActivityTyphoonOS4 {
    @Override protected void attachBaseContext(Context context) {
        WeatherPagesRuntime.install(context);
        super.attachBaseContext(context);
        WeatherPagesRuntime.install(this);
    }
    @Override protected void onCreate(Bundle state) {
        WeatherPagesRuntime.prepareIntent(this);
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);
        }
    }
}
