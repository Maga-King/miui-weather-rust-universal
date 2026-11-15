package dev.weather.oneplus;

import android.app.Activity;
import android.app.Application;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/** Standard Android bar styling; content owners still handle their own safe insets. */
public final class WeatherSystemBars {
    private WeatherSystemBars() {}

    static void transparentNavigation(Window window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setNavigationBarDividerColor(Color.TRANSPARENT);
        if(Build.VERSION.SDK_INT>=29)window.setNavigationBarContrastEnforced(false);
    }

    static void edgeToEdge(Window window) {
        transparentNavigation(window);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        window.setStatusBarColor(Color.TRANSPARENT);
        if(Build.VERSION.SDK_INT>=29)window.setStatusBarContrastEnforced(false);
        // Match WindowCompat: retain the legacy layout flags on Android 11+ too.
        View decor=window.getDecorView();
        int flags=decor.getSystemUiVisibility()|View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                |View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if(decor.getSystemUiVisibility()!=flags)decor.setSystemUiVisibility(flags);
        if(Build.VERSION.SDK_INT>=30)window.setDecorFitsSystemWindows(false);
    }

    static void iconAppearance(Window window,Boolean darkStatusIcons,Boolean darkNavigationIcons) {
        if(Build.VERSION.SDK_INT>=30) {
            WindowInsetsController controller=window.getInsetsController();
            if(controller==null)return;
            int mask=0,value=0;
            if(darkStatusIcons!=null){mask|=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
                if(darkStatusIcons)value|=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;}
            if(darkNavigationIcons!=null){mask|=WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                if(darkNavigationIcons)value|=WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;}
            if(mask!=0)controller.setSystemBarsAppearance(value,mask);
        } else {
            View decor=window.getDecorView();int flags=decor.getSystemUiVisibility();
            if(darkStatusIcons!=null)flags=darkStatusIcons?flags|View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR:flags&~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if(darkNavigationIcons!=null)flags=darkNavigationIcons?flags|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:flags&~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            if(decor.getSystemUiVisibility()!=flags)decor.setSystemUiVisibility(flags);
        }
    }

    /** MIUIX detail/settings pages retain their own content/action-bar inset handling. */
    static void install(Application application) {
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){
            private void apply(Activity activity) {
                String name=activity.getClass().getName();
                if(!(activity instanceof WeatherWidgetSettingsActivity)&&!name.matches(
                        "dev\\.weather\\.oneplus\\.Original(Rain|Aqi|Typhoon)(OS4)?Activity"))return;
                Window window=activity.getWindow();transparentNavigation(window);
                boolean dark=(activity.getResources().getConfiguration().uiMode
                        &Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
                iconAppearance(window,null,!dark);
            }
            public void onActivityCreated(Activity a,Bundle state){a.getWindow().getDecorView().post(()->{if(!a.isDestroyed())apply(a);});}
            public void onActivityResumed(Activity a){a.getWindow().getDecorView().post(()->{if(!a.isDestroyed())apply(a);});}
            public void onActivityStarted(Activity a){}
            public void onActivityPaused(Activity a){}
            public void onActivityStopped(Activity a){}
            public void onActivitySaveInstanceState(Activity a,Bundle state){}
            public void onActivityDestroyed(Activity a){}
        });
    }
}
