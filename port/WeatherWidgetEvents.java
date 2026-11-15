package dev.weather.oneplus;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;
import java.util.concurrent.atomic.AtomicBoolean;

/** USER_PRESENT is an unlock hint, not a widget-visibility or launcher callback. */
public final class WeatherWidgetEvents {
    private static final AtomicBoolean redrawPending = new AtomicBoolean();
    private WeatherWidgetEvents() {}
    public static void register(Context application) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String action=intent.getAction();
                if (!WeatherWidgetProvider.hasWidgets(context)) return;
                boolean unlock=Intent.ACTION_USER_PRESENT.equals(action);
                if(unlock){
                    // Do not lose the unlock network hint if a cached repaint
                    // from SCREEN_ON or TIME_TICK is already in progress.
                    WeatherRefresh.ensureScheduled(context);
                    WeatherRefresh.requestAutomatic(context);
                }
                if(!unlock&&Intent.ACTION_TIME_TICK.equals(action)) {
                    android.os.PowerManager power=context.getSystemService(android.os.PowerManager.class);
                    if(power!=null&&!power.isInteractive())return;
                }
                if(!redrawPending.compareAndSet(false,true))return;
                PendingResult result = goAsync();
                new Thread(() -> {
                    try {
                        WeatherWidgetProvider.updateAll(context);
                    } catch (Exception error) { Log.w("WeatherWidget", "Widget appearance update failed", error); }
                    finally { redrawPending.set(false);result.finish(); }
                }, "WeatherWidgetAppearance").start();
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_PRESENT);
        // These repaint only the cached sky. The chosen network interval and
        // 23:00-04:00 policy still belong to WeatherRefresh.
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        filter.addAction(Intent.ACTION_TIME_TICK);
        if (Build.VERSION.SDK_INT >= 33) application.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else application.registerReceiver(receiver, filter);
    }
}
