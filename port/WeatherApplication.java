package dev.weather.oneplus;

import android.app.Application;

/** Registers best-effort unlock updates whenever Android starts the ordinary app process. */
public final class WeatherApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        WeatherFonts.install(this);
        WeatherRenderPreferences.install(this);
        if (getPackageName().equals(Application.getProcessName())) {
            WeatherWidgetEvents.register(this);
            WeatherAlerts.ensureScheduled(this);
            WeatherRefresh.ensureScheduled(this);
        }
    }
}
