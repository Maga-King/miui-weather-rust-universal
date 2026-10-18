package dev.weather.oneplus;
import android.app.Activity;
import androidx.core.splashscreen.SplashScreen;

/** Real AndroidX splash, without logo, animation, or a minimum display duration. */
public final class AndroidStartupSplash {
    private final Activity activity;
    private volatile boolean firstFrame;
    private AndroidStartupSplash(Activity activity) { this.activity = activity; }
    /** Call before super.onCreate; callback restores host system-bar settings. */
    public static AndroidStartupSplash install(Activity activity, Runnable restoreSystemBars) {
        androidx.core.splashscreen.R.bind(activity);
        AndroidStartupSplash state = new AndroidStartupSplash(activity);
        SplashScreen splash = SplashScreen.installSplashScreen(activity);
        splash.setKeepOnScreenCondition(() -> !state.firstFrame);
        splash.setOnExitAnimationListener(view -> {
            view.remove();
            if (restoreSystemBars != null) restoreSystemBars.run();
        });
        return state;
    }
    /** Call on actual engine first frame, or on a startup failure to expose errors. */
    public void onFirstFrame() {
        firstFrame = true;
        activity.getWindow().getDecorView().postInvalidateOnAnimation();
    }
}
