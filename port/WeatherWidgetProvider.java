package dev.weather.oneplus;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.widget.RemoteViews;

/** Ordinary Android AppWidget. The original card's only tap target opens Weather. */
public final class WeatherWidgetProvider extends AppWidgetProvider {
    static boolean hasWidgets(Context context) { return ids(context).length != 0; }
    private static int[] ids(Context context) {
        return AppWidgetManager.getInstance(context).getAppWidgetIds(new ComponentName(context, WeatherWidgetProvider.class));
    }
    @Override public void onEnabled(Context context) {
        WeatherRefresh.ensureScheduled(context); WeatherRefresh.requestAutomatic(context);
    }
    @Override public void onDisabled(Context context) {
        if(WeatherAlerts.needsBackgroundWeather(context))WeatherRefresh.ensureScheduled(context);
        else WeatherRefresh.cancel(context);
    }
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        update(context, manager, widgetIds);
        WeatherRefresh.ensureScheduled(context); WeatherRefresh.requestAutomatic(context);
    }
    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        update(context, manager, new int[] {id});
        WeatherRefresh.ensureScheduled(context); WeatherRefresh.requestAutomatic(context);
    }
    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if(Intent.ACTION_BOOT_COMPLETED.equals(action)||Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                ||Intent.ACTION_TIME_CHANGED.equals(action)||Intent.ACTION_TIMEZONE_CHANGED.equals(action))
            WeatherAlerts.onEnvironmentChanged(context);
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            updateAll(context); WeatherRefresh.reschedule(context); WeatherRefresh.requestAutomatic(context);
        } else if (Intent.ACTION_TIME_CHANGED.equals(action) || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
            updateAll(context); WeatherRefresh.reschedule(context); WeatherRefresh.requestAutomatic(context);
        }
    }
    public static void updateAll(Context context) { update(context, AppWidgetManager.getInstance(context), ids(context)); }
    private static void update(Context context, AppWidgetManager manager, int[] ids) {
        if (ids.length == 0) return;
        int layout = resource(context, "layout", "weather_widget");
        int image = resource(context, "id", "weather_widget_image");
        WeatherWidgetData data = null;
        try {
            WeatherRefreshSource source = WeatherRefresh.source(context);
            if (source != null) data = source.localWidget(context);
        } catch (Exception error) {
            Log.w("WeatherWidget", "Cannot read local widget data", error);
        }
        if (data == null) data = WeatherWidgetCache.read(context);
        WeatherWidgetRenderer renderer = new WeatherWidgetRenderer(context);
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launch == null) launch = new Intent(context, WeatherActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean landscape = context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        for (int id : ids) {
            Bundle options = manager.getAppWidgetOptions(id);
            int width = options.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 150);
            int height = options.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 150);
            float ratio = Math.max(100, width) / (float) Math.max(100, height);
            Bitmap bitmap = renderer.render(data, System.currentTimeMillis(), ratio >= 1 ? 440 : Math.round(440 * ratio),
                ratio >= 1 ? Math.round(440 / ratio) : 440);
            RemoteViews views = new RemoteViews(context.getPackageName(), layout);
            views.setImageViewBitmap(image, bitmap); views.setOnClickPendingIntent(image, open);
            if (data != null) views.setContentDescription(image, data.cityName + " " + data.temperature + "° " + data.description);
            manager.updateAppWidget(id, views);
            // Do not recycle a bitmap until RemoteViews has safely marshalled it in all host implementations.
        }
    }
    private static int resource(Context context, String type, String name) {
        int id = context.getResources().getIdentifier(name, type, context.getPackageName());
        if (id == 0) throw new IllegalStateException("Missing widget resource " + type + "/" + name);
        return id;
    }
}
