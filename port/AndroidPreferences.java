package dev.weather.oneplus;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Persists the values used by the original Dart shared-preferences plugin. */
final class AndroidPreferences {
    static final Object UNHANDLED=new Object();
    private final Context context;
    private final Map<String,SharedPreferences> opened=new HashMap<>();
    AndroidPreferences(Context context){this.context=context;}
    Object handleCall(String channel,String method,Object args)throws Exception{
        args=plain(args);
        if(channel.equals("hyperos/shared_preferences")&&method.equals("open")){
            if(!(args instanceof List)||((List<?>)args).size()!=1||!(((List<?>)args).get(0) instanceof String))
                throw new IllegalArgumentException("Expected one preferences filename");
            String name=(String)((List<?>)args).get(0);
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
            String id="prefs/"+Base64.encodeToString(hash,Base64.NO_WRAP|Base64.URL_SAFE|Base64.NO_PADDING);
            opened.put(id,context.getSharedPreferences(id.replace('/','_'),Context.MODE_PRIVATE));
            return id;
        }
        SharedPreferences file=opened.get(channel);
        if(file==null)return UNHANDLED;
        if(method.equals("reload")||method.equals("getAll")){
            Map<String,Object> result=new LinkedHashMap<>();
            for(Map.Entry<String,?> entry:file.getAll().entrySet()){
                if(entry.getValue() instanceof String){
                    byte[] bytes=Base64.decode((String)entry.getValue(),Base64.NO_WRAP);
                    result.put(entry.getKey(),Codec.read(Codec.buffer(bytes)));
                }
            }
            return result;
        }
        if(!(args instanceof List))return UNHANDLED;
        List<?> list=(List<?>)args;
        boolean nightBefore=WeatherRefresh.nightUpdateAllowed(context);
        int alertsBefore=alertSettings();
        boolean backgroundBefore=WeatherRefresh.backgroundNeeded(context);
        SharedPreferences.Editor edit=file.edit();
        if(method.equals("put")&&list.size()==2&&list.get(0) instanceof String){
            put(edit,(String)list.get(0),list.get(1));
        }else if(method.equals("putAll")&&list.size()==1&&list.get(0) instanceof Map){
            for(Map.Entry<?,?> entry:((Map<?,?>)list.get(0)).entrySet()){
                if(!(entry.getKey() instanceof String))throw new IllegalArgumentException("Preference key must be a string");
                put(edit,(String)entry.getKey(),entry.getValue());
            }
        }else return UNHANDLED;
        if(!edit.commit())throw new IllegalStateException("Could not save preferences");
        boolean nightAfter=WeatherRefresh.nightUpdateAllowed(context);
        int alertChanges=alertsBefore^alertSettings();
        try{
            if((alertChanges&8)!=0)AndroidMiPushBridge.onEnvironmentChanged(context);
            if((alertChanges&7)!=0)WeatherAlerts.onEnvironmentChanged(context);
            if(nightBefore!=nightAfter||(alertChanges&7)!=0){
                boolean backgroundAfter=WeatherRefresh.backgroundNeeded(context);
                if(backgroundBefore&&!backgroundAfter)WeatherRefresh.cancelAutomatic(context);
                else if(backgroundAfter||nightBefore!=nightAfter){
                    WeatherRefresh.reschedule(context);
                    WeatherRefresh.ensureScheduled(context);
                    if(backgroundAfter)WeatherRefresh.requestAutomatic(context);
                }
                if(nightBefore!=nightAfter)Log.i("WeatherRefresh","Night automatic updates enabled="+nightAfter);
            }
        }catch(Exception error){
            Log.w("WeatherRefresh","Saved weather preference; scheduler will retry on next event",error);
        }
        return null;
    }
    private int alertSettings(){
        return (WeatherAlerts.dailyEnabled(context)?1:0)|(WeatherAlerts.alertEnabled(context)?2:0)
                |(WeatherAlerts.abruptEnabled(context)?4:0)
                |(AndroidWeatherPreferences.readBoolean(context,"com.miui.weather2.information","night_not_disturb_setting",false)?8:0);
    }
    private static void put(SharedPreferences.Editor edit,String key,Object value){
        if(value==null)edit.remove(key);
        else edit.putString(key,Base64.encodeToString(Codec.encode(value,false),Base64.NO_WRAP));
    }
    private static Object plain(Object value)throws JSONException{
        if(value==JSONObject.NULL)return null;
        if(value instanceof JSONArray){List<Object> list=new ArrayList<>();JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++)list.add(plain(a.get(i)));return list;}
        if(value instanceof JSONObject){Map<String,Object> map=new LinkedHashMap<>();JSONObject o=(JSONObject)value;Iterator<String> keys=o.keys();while(keys.hasNext()){String key=keys.next();map.put(key,plain(o.get(key)));}return map;}
        return value;
    }
}
