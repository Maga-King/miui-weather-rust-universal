package dev.weather.oneplus;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.SizeF;
import android.widget.RemoteViews;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ordinary Android AppWidget. The original card's only tap target opens Weather. */
public class WeatherWidgetProvider extends AppWidgetProvider {
    private static final Class<?>[] PROVIDERS={WeatherWidgetProvider.class,WeatherDualCityWidgetProvider.class,
        WeatherHourlyWidgetProvider.class,WeatherSmallWidgetProvider.class,WeatherCustomWidgetProvider.class,
        WeatherDailyWidgetProvider.class,WeatherCompleteWidgetProvider.class,WeatherSunWidgetProvider.class};
    private static final Map<Integer,String> DRAWN=new LinkedHashMap<Integer,String>(32,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer,String> entry) { return size()>128; }
    };
    protected int style() {return WeatherCardWidgetRenderer.BASE;}
    static boolean hasWidgets(Context context) {
        for(Class<?> provider:PROVIDERS)if(ids(context,provider).length!=0)return true;
        return false;
    }
    private static int[] ids(Context context,Class<?> provider) {
        return AppWidgetManager.getInstance(context).getAppWidgetIds(new ComponentName(context, provider));
    }
    @Override public void onEnabled(Context context) {
        WeatherRefresh.ensureScheduled(context); WeatherRefresh.requestAutomatic(context);
    }
    @Override public void onDeleted(Context context, int[] ids) {
        for(int id:ids) {
            WeatherWidgetPreferences.delete(context,id);
            synchronized(WeatherWidgetProvider.class){DRAWN.remove(id);}
        }
    }
    @Override public void onDisabled(Context context) {
        if(hasWidgets(context)||WeatherAlerts.needsBackgroundWeather(context))WeatherRefresh.ensureScheduled(context);
        else WeatherRefresh.cancel(context);
    }
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        update(context, manager, widgetIds,style());
        WeatherRefresh.ensureScheduled(context); WeatherRefresh.requestAutomatic(context);
    }
    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        update(context, manager, new int[] {id},style());
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
    public static synchronized void updateAll(Context context) {
        context=context.getApplicationContext();
        AppWidgetManager manager=AppWidgetManager.getInstance(context);
        for(int style=0;style<PROVIDERS.length;style++)update(context,manager,ids(context,PROVIDERS[style]),style);
    }
    private static synchronized void update(Context context, AppWidgetManager manager, int[] ids,int style) {
        if (ids.length == 0) return;
        context=context.getApplicationContext();
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
        WeatherWidgetRenderer renderer = null;
        WeatherCardWidgetRenderer cards=null;
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launch == null) launch = new Intent(context, WeatherActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long now=System.currentTimeMillis();
        for (int id : ids) {
            WeatherWidgetData instance=WeatherWidgetPreferences.select(context,id,data);
            org.json.JSONObject details=null;
            if(style!=0)try {
                details=WeatherWidgetExtendedData.read(context,instance);
                details.put("panel",WeatherWidgetPreferences.panel(context,id));
                details.put("secondCityId",WeatherWidgetPreferences.secondCityId(context,id));
            } catch(Exception error){Log.w("WeatherWidget","Cannot read extended weather cache",error);}
            Bundle options = manager.getAppWidgetOptions(id);
            List<WeatherWidgetSizing.Size> sizes=WeatherWidgetSizing.resolve(context,options,style);
            String signature=signature(context,style,instance,details,sizes,now);
            if(signature.equals(DRAWN.get(id)))continue;
            if(style==0&&renderer==null)renderer=new WeatherWidgetRenderer(context);
            if(style!=0&&cards==null)cards=new WeatherCardWidgetRenderer(context);
            Map<SizeF,RemoteViews> variants=new LinkedHashMap<>();
            List<Bitmap> rendered=new ArrayList<>();
            try {
                for(WeatherWidgetSizing.Size size:sizes) {
                    Bitmap bitmap=style==0?renderer.render(instance,now,size.width,size.height)
                        :cards.render(style,instance,details,now,size.width,size.height);
                    rendered.add(bitmap);
                    RemoteViews views=new RemoteViews(context.getPackageName(),layout);
                    views.setImageViewBitmap(image,bitmap);views.setOnClickPendingIntent(image,open);
                    if(instance!=null)views.setContentDescription(image,instance.cityName+" "+instance.temperature+"\u00b0 "+instance.description);
                    variants.put(size.dp,views);
                }
                RemoteViews views=variants.values().iterator().next();
                if(Build.VERSION.SDK_INT>=31&&variants.size()>1)views=new RemoteViews(variants);
                manager.updateAppWidget(id,views);
                DRAWN.put(id,signature);
            } finally {
                // The synchronous Binder call has transferred ashmem copies to the
                // widget service. No renderer or app-side cache retains these frames.
                for(Bitmap bitmap:rendered)bitmap.recycle();
            }
        }
    }
    private static String signature(Context context,int style,WeatherWidgetData data,
            org.json.JSONObject details,List<WeatherWidgetSizing.Size> sizes,long now) {
        try {
            String state=style+"|"+(now/60000)+"|"+context.getResources().getConfiguration()
                +"|"+sizes+"|"+(data==null?"":data.toJson())+"|"+details;
            return android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256")
                .digest(state.getBytes(java.nio.charset.StandardCharsets.UTF_8)),android.util.Base64.NO_WRAP);
        }catch(Exception error){return Long.toString(System.nanoTime());}
    }
    private static int resource(Context context, String type, String name) {
        int id = context.getResources().getIdentifier(name, type, context.getPackageName());
        if (id == 0) throw new IllegalStateException("Missing widget resource " + type + "/" + name);
        return id;
    }
}
