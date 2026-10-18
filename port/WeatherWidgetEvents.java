package dev.weather.oneplus;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

/** USER_PRESENT is an unlock hint, not a widget-visibility or launcher callback. */
public final class WeatherWidgetEvents {
    private WeatherWidgetEvents() {}
    public static void register(Context application) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!Intent.ACTION_USER_PRESENT.equals(intent.getAction()) || !WeatherWidgetProvider.hasWidgets(context)) return;
                PendingResult result = goAsync();
                new Thread(() -> {
                    try {
                        WeatherWidgetProvider.updateAll(context);
                        WeatherRefresh.ensureScheduled(context);
                        WeatherRefresh.requestAutomatic(context);
                    } catch (Exception error) { Log.w("WeatherWidget", "Unlock update failed", error); }
                    finally { result.finish(); }
                }, "WeatherWidgetUnlock").start();
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) application.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else application.registerReceiver(receiver, filter);
    }
}
