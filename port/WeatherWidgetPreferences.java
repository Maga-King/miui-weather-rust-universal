package dev.weather.oneplus;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import org.json.JSONArray;
import org.json.JSONObject;

/** Per-instance choices. An explicit missing city never silently becomes another city. */
public final class WeatherWidgetPreferences {
    private WeatherWidgetPreferences() {}
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("weather_widget_instances_v1", Context.MODE_PRIVATE); }
    private static String key(int id, String field) { return "widget." + id + "." + field; }
    public static String cityId(Context c, int id) { return prefs(c).getString(key(id,"city"), ""); }
    public static String secondCityId(Context c, int id) { return prefs(c).getString(key(id,"second"), ""); }
    public static int panel(Context c, int id) { return Math.max(0,Math.min(3,prefs(c).getInt(key(id,"panel"),0))); }
    public static boolean sizeEditing(Context c,int id) { return prefs(c).getBoolean(key(id,"size_editing"),false); }
    public static int savedWidthPercent(Context c,int id) { return WeatherWidgetGeometry.clamp(prefs(c).getInt(key(id,"width_percent"),100)); }
    public static int savedHeightPercent(Context c,int id) { return WeatherWidgetGeometry.clamp(prefs(c).getInt(key(id,"height_percent"),100)); }
    public static int widthPercent(Context c,int id) { return sizeEditing(c,id)?savedWidthPercent(c,id):100; }
    public static int heightPercent(Context c,int id) { return sizeEditing(c,id)?savedHeightPercent(c,id):100; }
    public static boolean save(Context c,int id,String city,String second,int panel) {
        if(id==AppWidgetManager.INVALID_APPWIDGET_ID || panel<0 || panel>3 || city==null || second==null) return false;
        return prefs(c).edit().putString(key(id,"city"),city).putString(key(id,"second"),second)
                .putInt(key(id,"panel"),panel).commit();
    }
    public static boolean save(Context c,int id,String city,String second,int panel,
            boolean sizeEditing,int widthPercent,int heightPercent) {
        if(id==AppWidgetManager.INVALID_APPWIDGET_ID||panel<0||panel>3||city==null||second==null
                ||widthPercent<WeatherWidgetGeometry.MIN||widthPercent>WeatherWidgetGeometry.MAX
                ||heightPercent<WeatherWidgetGeometry.MIN||heightPercent>WeatherWidgetGeometry.MAX)return false;
        return prefs(c).edit().putString(key(id,"city"),city).putString(key(id,"second"),second)
                .putInt(key(id,"panel"),panel).putBoolean(key(id,"size_editing"),sizeEditing)
                .putInt(key(id,"width_percent"),widthPercent).putInt(key(id,"height_percent"),heightPercent).commit();
    }
    public static void delete(Context c,int id) {
        prefs(c).edit().remove(key(id,"city")).remove(key(id,"second")).remove(key(id,"panel"))
                .remove(key(id,"size_editing")).remove(key(id,"width_percent")).remove(key(id,"height_percent")).apply();
    }
    /** Includes real saved cities without weather, so missing observations remain distinguishable. */
    public static JSONArray cities(Context c) {
        JSONArray out=new JSONArray();
        WeatherSourceSession session=WeatherSources.current(c);
        if(!WeatherSources.isCurrent(c,session)||!WeatherSources.database(c,session.sourceId).isFile())return out;
        try(WeatherReadDatabase db=WeatherReadDatabase.open(c,session);
            Cursor rows=db.rawQuery("SELECT posID,name,flag FROM selectedcity ORDER BY position ASC,flag DESC",null)) {
            while(rows.moveToNext()) {
                String id=rows.isNull(0)?"":rows.getString(0), name=rows.isNull(1)?"":rows.getString(1);
                if(!id.isEmpty())out.put(new JSONObject().put("cityId",id).put("cityName",name).put("located",rows.getInt(2)==1));
            }
        }catch(Exception ignored){}
        return WeatherSources.isCurrent(c,session)?out:new JSONArray();
    }
    /** Port default for dual-city cards: actual located selection, otherwise first saved city. */
    public static String defaultCityId(Context c,boolean preferLocated) {
        JSONArray cities=cities(c);
        if(preferLocated)for(int i=0;i<cities.length();i++) {
            JSONObject city=cities.optJSONObject(i);
            if(city.optBoolean("located"))return city.optString("cityId");
        }
        return cities.length()==0?"":cities.optJSONObject(0).optString("cityId");
    }
    public static boolean dual(Context c,int id) {
        AppWidgetProviderInfo info=AppWidgetManager.getInstance(c).getAppWidgetInfo(id);
        return info!=null && info.provider.getClassName().endsWith("WeatherDualCityWidgetProvider");
    }
    public static WeatherWidgetData select(Context c,int id,WeatherWidgetData fallback) {
        String explicit=cityId(c,id);
        String wanted=explicit.isEmpty()?defaultCityId(c,dual(c,id)):explicit;
        return find(c,wanted,fallback);
    }
    static WeatherWidgetData select(Context c,int id,WeatherWidgetExtendedData.Snapshot snapshot) {
        String explicit=cityId(c,id);
        String wanted=explicit.isEmpty()?snapshot.defaultCity(dual(c,id)):explicit;
        return snapshot.city(wanted);
    }
    public static WeatherWidgetData second(Context c,int id,WeatherWidgetData fallback) {
        String wanted=secondCityId(c,id);
        return wanted.isEmpty()?null:find(c,wanted,fallback);
    }
    private static WeatherWidgetData find(Context c,String wanted,WeatherWidgetData fallback) {
        if(wanted.isEmpty())return null;
        JSONArray available=WeatherWidgetExtendedData.read(c,fallback).optJSONArray("cities");
        if(available!=null)for(int i=0;i<available.length();i++)try {
            JSONObject city=available.getJSONObject(i);
            if(wanted.equals(city.optString("cityId")))return new WeatherWidgetData(city);
        }catch(Exception ignored){}
        return null;
    }
}
