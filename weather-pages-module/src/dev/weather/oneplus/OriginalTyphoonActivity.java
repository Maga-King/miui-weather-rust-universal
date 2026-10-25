package dev.weather.oneplus;
import android.content.Context;
import android.os.Bundle;

/** Full-screen typhoon page selected by the original legacy method. */
public final class OriginalTyphoonActivity extends com.miui.weather2map.ActivityTyphoon {
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
