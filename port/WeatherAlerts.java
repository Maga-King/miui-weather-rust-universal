package dev.weather.oneplus;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.json.JSONArray;
import org.json.JSONObject;

/** Ordinary Android reminders from real provider data; no exact alarm or foreground service. */
public final class WeatherAlerts {
    static final int JOB_A=0x574520,JOB_B=0x574521;
    private static final String STORE="weather_real_alerts_v1";
    private static int running;
    private WeatherAlerts() {}
    private static SharedPreferences state(Context c){return c.getSharedPreferences(STORE,Context.MODE_PRIVATE);}
    public static boolean dailyEnabled(Context c){return AndroidWeatherPreferences.readBoolean(c,
            "com.miui.weather2.warning_weather_day_and_night","warning_weather_day_and_night",false);}
    public static boolean alertEnabled(Context c){return AndroidWeatherPreferences.readBoolean(c,
            "com.miui.weather2.alert_weather_enable","alert_weather_enable",true);}
    public static boolean abruptEnabled(Context c){return AndroidWeatherPreferences.readBoolean(c,
            "com.miui.weather2.information","abrupt_weather_warning_key",true);}
    /** Share WeatherRefresh's single automatic chain when reminders need real background data. */
    public static boolean needsBackgroundWeather(Context c){
        return realSource(c)&&((dailyEnabled(c)&&!remote(c,"daily"))||(alertEnabled(c)&&!remote(c,"alert")));
    }

    /** Set true only after verified remote subscription/delivery, never merely a connected SDK. */
    public static void setRemoteDelivery(Context c,String category,boolean active){
        if(!category.equals("daily")&&!category.equals("alert")&&!category.equals("abrupt"))throw new IllegalArgumentException("category");
        state(c).edit().putBoolean("remote_"+category,active).apply();
        onEnvironmentChanged(c);
    }
    private static boolean remote(Context c,String category){return state(c).getBoolean("remote_"+category,false);}
    private static boolean realSource(Context c) {
        try {return WeatherRefresh.source(c) instanceof XiaomiWeatherRefreshSource;}
        catch(Exception ignored){return false;}
    }
    private static boolean selected(Context c,WeatherDatabaseBridge.City city){
        return city!=null&&city.valid()&&city.sameSelection(WeatherDatabaseBridge.firstCity(c));
    }
    /** Root calls only after successful real HTTP and final selection/cancellation checks. */
    public static void onWeatherResponse(Context c,WeatherDatabaseBridge.City city,JSONObject response) {
        try {
            if(!realSource(c)||!WeatherRefresh.networkAllowed(c)||!selected(c,city)||response==null)return;
            // Persist the real response independently of widget state, including tomorrow and alerts.
            state(c).edit().putString("snapshot_city",selection(city))
                    .putString("snapshot",response.toString()).commit();
            deliver(c,city,response,System.currentTimeMillis());
            ensureScheduled(c,false);
        }catch(Exception error){Log.w("WeatherAlerts","Real response deferred: "+error.getClass().getSimpleName());}
    }
    /** Startup, settings, city, clock/timezone and boot hooks; no permission UI. */
    public static void onEnvironmentChanged(Context c) {
        try {ensureScheduled(c,true);}catch(Exception e){Log.w("WeatherAlerts","Scheduling deferred: "+e.getClass().getSimpleName());}
    }
    /** Process startup must preserve the very job that may be launching this process. */
    public static void ensureScheduled(Context c) {
        try {ensureScheduled(c,false);}catch(Exception e){Log.w("WeatherAlerts","Scheduling deferred: "+e.getClass().getSimpleName());}
    }
    /** Executes off the main thread. Missing/stale real data produces no notification. */
    public static void runDue(Context c) {
        try {
            if(Thread.currentThread().isInterrupted()||!realSource(c)||!WeatherRefresh.networkAllowed(c))return;
            WeatherDatabaseBridge.City city=WeatherDatabaseBridge.firstCity(c);
            if(city==null||!city.valid())return;
            JSONObject response=null;
            if(selection(city).equals(state(c).getString("snapshot_city",""))) {
                String raw=state(c).getString("snapshot",null);if(raw!=null)response=new JSONObject(raw);
            }
            if(response==null && c.getDatabasePath("weather.db").isFile()) {
                try(WeatherReadDatabase db=WeatherReadDatabase.open(c);
                    Cursor row=db.rawQuery("SELECT data1 FROM raw WHERE city_id=? LIMIT 1",new String[]{city.id})) {
                    if(row.moveToFirst()&&!row.isNull(0))response=new JSONObject(row.getString(0));
                }
            }
            if(response!=null&&selected(c,city)&&!Thread.currentThread().isInterrupted())deliver(c,city,response,System.currentTimeMillis());
        }catch(Exception e){Log.w("WeatherAlerts","Cached reminder deferred: "+e.getClass().getSimpleName());}
    }
    private static void deliver(Context c,WeatherDatabaseBridge.City city,JSONObject response,long now) {
        if(!selected(c,city)||Thread.currentThread().isInterrupted())return;
        if(alertEnabled(c)&&!remote(c,"alert"))for(WeatherAlertData.Event e:WeatherAlertData.alerts(response,now))
            post(c,"alert",city.id,e.identity(),e.title,e.text,e.when);
        if(!dailyEnabled(c)||remote(c,"daily"))return;
        ZonedDateTime local=Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault());int hour=local.getHour();
        // Android may defer a 7/19 job in Doze. Deliver only within that useful morning/evening window.
        boolean morning=hour>=7&&hour<10,evening=hour>=19&&hour<22;
        if(!morning&&!evening)return;
        LocalDate target=morning?local.toLocalDate():local.toLocalDate().plusDays(1);
        String text=WeatherAlertData.forecast(response,target,city.unit,city.locale,now);if(text==null)return;
        boolean zh=city.locale.startsWith("zh");
        String title=city.name+" · "+(morning?(zh?"今天天气":"Today's weather"):(zh?"明天天气":"Tomorrow's weather"));
        post(c,"daily",city.id,local.toLocalDate()+"/"+(morning?"07":"19"),title,text,now);
    }
    /** Shared alert identity makes HTTP discovery and MiPush receipt update one notification. */
    public static boolean postServerAlert(Context c,String cityId,JSONObject alert) {
        WeatherAlertData.Event e=WeatherAlertData.alert(alert,System.currentTimeMillis());
        return e!=null&&alertEnabled(c)&&post(c,"alert",cityId,e.identity(),e.title,e.text,e.when);
    }
    /** Called for a real SDK pass-through callback; a notification click never re-posts it. */
    public static void onMiPushContent(Context c,String messageId,String content,boolean clicked) {
        if(clicked||content==null||content.length()>2*1024*1024)return;
        try {
            JSONObject envelope=new JSONObject(content);
            WeatherDatabaseBridge.City city=WeatherDatabaseBridge.firstCity(c);
            if(city==null||!city.valid())return;
            String type=envelope.optString("messageType","");JSONArray parts=envelope.optJSONArray("messageDatas");
            if(parts==null)return;
            JSONObject action=object(envelope.opt("messageAction"));
            for(int i=0;i<parts.length();i++) {
                JSONObject part=parts.optJSONObject(i);if(part==null)continue;
                JSONObject data=object(part.opt("data"));if(data==null)continue;
                String location=data.optString("locationKey","");
                if(location.isEmpty()&&action!=null)location=action.optString("locationKey","");
                if(!location.equals(city.locationKey))continue;
                if(type.equals("4")||type.equals("5")) {
                    // Original S1.b uses the first record's real title/desc and locationKey.
                    if(i==0)postServerEvent(c,"abrupt",city.id,messageId,data.optString("title",""),
                            data.optString("desc",""),WeatherAlertData.time(data.optString("pubTime","")));
                }else {
                    JSONArray alerts=data.optJSONArray("alerts");
                    if(alerts!=null)for(int j=0;j<alerts.length();j++)postServerAlert(c,city.id,alerts.optJSONObject(j));
                }
            }
        }catch(Exception e){Log.w("WeatherAlerts","Push event ignored: "+e.getClass().getSimpleName());}
    }
    private static JSONObject object(Object value){
        if(value instanceof JSONObject)return (JSONObject)value;
        if(value instanceof String)try{return new JSONObject((String)value);}catch(Exception ignored){}
        return null;
    }
    /** For an actual structured server event. No locally inferred abnormal-weather thresholds. */
    public static boolean postServerEvent(Context c,String category,String cityId,String eventId,
            String title,String text,long publishedAtMs) {
        if(!category.equals("abrupt")&&!category.equals("alert"))return false;
        if(category.equals("abrupt")?!abruptEnabled(c):!alertEnabled(c))return false;
        long now=System.currentTimeMillis();
        // Original abrupt messages do not always carry issue time. Zero means actual receipt time.
        if(publishedAtMs==0)publishedAtMs=now;
        if(eventId==null||eventId.trim().isEmpty()||publishedAtMs<=0||publishedAtMs>now+300_000||now-publishedAtMs>WeatherAlertData.DAY)return false;
        return post(c,category,cityId,eventId,title,text,publishedAtMs);
    }
    private static synchronized boolean post(Context c,String category,String cityId,String identity,String title,String text,long when) {
        if(title==null||title.trim().isEmpty()||text==null||text.trim().isEmpty()||Thread.currentThread().isInterrupted())return false;
        try {
            if(!realSource(c)||!WeatherRefresh.networkAllowed(c))return false;
            WeatherDatabaseBridge.City current=WeatherDatabaseBridge.firstCity(c);
            if(current==null||!current.valid()||!current.id.equals(cityId))return false;
            String key="sent_"+hash(category+"\n"+cityId+"\n"+identity);
            SharedPreferences prefs=state(c);if(prefs.contains(key))return true;
            int id=0x57000000|(key.hashCode()&0xffffff);
            AndroidWeatherNotifications.PostResult result=AndroidWeatherNotifications.postLocal(c,
                    new AndroidWeatherNotifications.Request(id,AndroidWeatherNotifications.DEFAULT_CHANNEL,title,text,when,null));
            if(result!=AndroidWeatherNotifications.PostResult.POSTED)return false;
            SharedPreferences.Editor edit=prefs.edit();long now=System.currentTimeMillis();
            // Bound the ledger without forgetting warnings still eligible for replay.
            for(java.util.Map.Entry<String,?> entry:prefs.getAll().entrySet())if(entry.getKey().startsWith("sent_")
                    &&entry.getValue() instanceof Long&&now-(Long)entry.getValue()>7*WeatherAlertData.DAY)edit.remove(entry.getKey());
            edit.putLong(key,now).commit();return true;
        }catch(Exception e){Log.w("WeatherAlerts","Notification deferred: "+e.getClass().getSimpleName());return false;}
    }
    private static String selection(WeatherDatabaseBridge.City city){return city.id+"\n"+city.locationKey+"\n"+city.latitude+"\n"+city.longitude+"\n"+city.located;}
    private static String hash(String value)throws Exception{
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
    static long nextBoundary(long now,ZoneId zone){
        ZonedDateTime local=Instant.ofEpochMilli(now).atZone(zone);
        for(int day=0;day<=1;day++)for(int hour:new int[]{7,19}){
            long time=local.toLocalDate().plusDays(day).atTime(hour,0).atZone(zone).toInstant().toEpochMilli();
            if(time>now)return time;
        }throw new IllegalStateException();
    }
    private static synchronized void ensureScheduled(Context c,boolean replace) {
        JobScheduler jobs=c.getSystemService(JobScheduler.class);if(jobs==null)return;
        boolean enabled=WeatherRefresh.networkAllowed(c)&&realSource(c)&&dailyEnabled(c)&&!remote(c,"daily");
        for(int id:new int[]{JOB_A,JOB_B})if(id!=running && (!enabled||replace))jobs.cancel(id);
        if(!enabled)return;
        for(int id:new int[]{JOB_A,JOB_B})if(id!=running&&jobs.getPendingJob(id)!=null)return;
        int id=running==JOB_A?JOB_B:JOB_A;long now=System.currentTimeMillis();
        jobs.schedule(new JobInfo.Builder(id,new ComponentName(c,WeatherAlertJobService.class))
                .setMinimumLatency(Math.max(1,nextBoundary(now,ZoneId.systemDefault())-now)).setPersisted(true).build());
    }
    static synchronized void jobStarted(Context c,int id){running=id;ensureScheduled(c,false);}
    static synchronized void jobEnded(int id){if(running==id)running=0;}
}
