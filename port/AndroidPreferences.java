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
    // Dart and its open preference channels survive Activity recreation.
    // Keep only namespace strings for that same process lifetime, never Activities.
    private static final Map<String,String> namespaces=new java.util.concurrent.ConcurrentHashMap<>();
    private static final String DISPLAY_NAMESPACE="com.miui.weather2.unit";
    private static final String DISPLAY_KEY="densityRatio";
    private static final String DISPLAY_METADATA="display_logical_density_dpi";
    AndroidPreferences(Context context){this.context=context.getApplicationContext();}
    Object handleCall(String channel,String method,Object args)throws Exception{
        args=plain(args);
        if(channel.equals("hyperos/shared_preferences")&&method.equals("open")){
            if(!(args instanceof List)||((List<?>)args).size()!=1||!(((List<?>)args).get(0) instanceof String))
                throw new IllegalArgumentException("Expected one preferences filename");
            String name=(String)((List<?>)args).get(0);
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
            String id="prefs/"+Base64.encodeToString(hash,Base64.NO_WRAP|Base64.URL_SAFE|Base64.NO_PADDING);
            opened.put(id,context.getSharedPreferences(id.replace('/','_'),Context.MODE_PRIVATE));
            namespaces.put(id,name);
            return id;
        }
        SharedPreferences file=opened.get(channel);
        if(file==null&&namespaces.containsKey(channel)){
            file=context.getSharedPreferences(channel.replace('/','_'),Context.MODE_PRIVATE);
            opened.put(channel,file);
        }
        if(file==null)return UNHANDLED;
        boolean displayPreferences=DISPLAY_NAMESPACE.equals(namespaces.get(channel));
        if(method.equals("reload")||method.equals("getAll")){
            Map<String,Object> result=new LinkedHashMap<>();
            for(Map.Entry<String,?> entry:file.getAll().entrySet()){
                if(entry.getValue() instanceof String){
                    byte[] bytes=Base64.decode((String)entry.getValue(),Base64.NO_WRAP);
                    result.put(entry.getKey(),Codec.read(Codec.buffer(bytes)));
                }
            }
            if(displayPreferences){
                int current=WeatherDisplayDensity.offset(context);
                result.put(DISPLAY_KEY,current);
                result.put(DISPLAY_METADATA,displayMetadata(current));
            }
            return result;
        }
        if(!(args instanceof List))return UNHANDLED;
        List<?> list=(List<?>)args;
        boolean nightBefore=WeatherRefresh.nightUpdateAllowed(context);
        int alertsBefore=alertSettings();
        boolean backgroundBefore=WeatherRefresh.backgroundNeeded(context);
        SharedPreferences.Editor edit=file.edit();
        Integer densityOffset=null;
        if(method.equals("put")&&list.size()==2&&list.get(0) instanceof String){
            String key=(String)list.get(0);
            Object value=list.get(1);
            if(displayPreferences&&DISPLAY_KEY.equals(key)){densityOffset=displayOffset(value);value=densityOffset;}
            put(edit,key,value);
        }else if(method.equals("putAll")&&list.size()==1&&list.get(0) instanceof Map){
            for(Map.Entry<?,?> entry:((Map<?,?>)list.get(0)).entrySet()){
                if(!(entry.getKey() instanceof String))throw new IllegalArgumentException("Preference key must be a string");
                String key=(String)entry.getKey();
                Object value=entry.getValue();
                if(displayPreferences&&DISPLAY_KEY.equals(key)){densityOffset=displayOffset(value);value=densityOffset;}
                put(edit,key,value);
            }
        }else return UNHANDLED;
        if(!edit.commit())throw new IllegalStateException("Could not save preferences");
        if(densityOffset!=null)WeatherDisplayDensity.saveOffset(context,densityOffset);
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
    private static int displayOffset(Object value){
        if(value==null)return 0;
        if(!(value instanceof Integer)&&!(value instanceof Long))
            throw new IllegalArgumentException("DPI offset must be an integer");
        long number=((Number)value).longValue();
        return (int)Math.max(WeatherDisplayDensity.MIN_OFFSET,Math.min(WeatherDisplayDensity.MAX_OFFSET,number));
    }
    private List<Object> displayMetadata(int current){
        SortedSet<Integer> offsets=new TreeSet<>();
        for(int offset=WeatherDisplayDensity.MIN_OFFSET;offset<=WeatherDisplayDensity.MAX_OFFSET;offset+=20)offsets.add(offset);
        offsets.add(current);
        Map<Integer,String> choices=new LinkedHashMap<>();
        for(int offset:offsets){
            String label=offset==0?"跟随系统":(offset>0?"系统 DPI + "+offset:"系统 DPI − "+(-offset));
            choices.put(offset,label);
        }
        return Arrays.asList(1,"显示大小",current,choices);
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
