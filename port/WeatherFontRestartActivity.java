package dev.weather.oneplus;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;

/** Internal, short-lived helper process to recreate Flutter's font registry. */
public final class WeatherFontRestartActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        AndroidStartupSplash splash=AndroidStartupSplash.install(this,null);
        super.onCreate(state);setContentView(new FrameLayout(this));splash.onFirstFrame();
        new Handler(Looper.getMainLooper()).postDelayed(()->{
            Intent launch=getPackageManager().getLaunchIntentForPackage(getPackageName());
            if(launch!=null)startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("open_route","/settings"));
            finishAndRemoveTask();
        },650);
    }
}
