package dev.weather.oneplus;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
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
    public static boolean save(Context c,int id,String city,String second,int panel) {
        if(id==AppWidgetManager.INVALID_APPWIDGET_ID || panel<0 || panel>3 || city==null || second==null) return false;
        return prefs(c).edit().putString(key(id,"city"),city).putString(key(id,"second"),second)
                .putInt(key(id,"panel"),panel).commit();
    }
    public static void delete(Context c,int id) {
        prefs(c).edit().remove(key(id,"city")).remove(key(id,"second")).remove(key(id,"panel")).apply();
    }
    /** Includes real saved cities without weather, so missing observations remain distinguishable. */
    public static JSONArray cities(Context c) {
        JSONArray out=new JSONArray();
        if(!c.getDatabasePath("weather.db").isFile())return out;
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(c.getDatabasePath("weather.db").getAbsolutePath(),null,
                    SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            Cursor rows=db.rawQuery("SELECT posID,name,flag FROM selectedcity ORDER BY position ASC,flag DESC",null)) {
            while(rows.moveToNext()) {
                String id=rows.isNull(0)?"":rows.getString(0), name=rows.isNull(1)?"":rows.getString(1);
                if(!id.isEmpty())out.put(new JSONObject().put("cityId",id).put("cityName",name).put("located",rows.getInt(2)==1));
            }
        }catch(Exception ignored){}
        return out;
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
