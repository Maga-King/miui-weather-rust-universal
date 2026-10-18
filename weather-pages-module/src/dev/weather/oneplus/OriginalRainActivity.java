package dev.weather.oneplus;
import android.content.Context;
import android.os.Bundle;
public final class OriginalRainActivity extends com.miui.weather2map.ActivityMinuteRain {
    @Override protected void attachBaseContext(Context context) {
        WeatherPagesRuntime.install(context);
        super.attachBaseContext(context);
        WeatherPagesRuntime.install(this);
    }
    @Override protected void onCreate(Bundle state) {
        WeatherPagesRuntime.prepareIntent(this);
        setTheme(0x7f130225);
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);
        }
    }
}
