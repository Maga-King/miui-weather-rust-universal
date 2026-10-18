package dev.weather.oneplus;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;

/** Declare in test APK only, android:process=":restart", exported=false. */
public final class TestCityRestartActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        AndroidStartupSplash splash = AndroidStartupSplash.install(this, null);
        super.onCreate(state);
        if (!"dev.weather.test".equals(getPackageName())) { finish(); return; }
        setContentView(new FrameLayout(this));
        splash.onFirstFrame();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            startActivity(new Intent(this, TestWeatherActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finishAndRemoveTask();
        }, 700);
    }
}
