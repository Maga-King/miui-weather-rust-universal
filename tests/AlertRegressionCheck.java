package dev.weather.oneplus;
import org.json.*;import java.time.*;import java.nio.file.*;import java.lang.reflect.*;
public class AlertRegressionCheck {
 static int checks;static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static long at(String s){return OffsetDateTime.parse(s).toInstant().toEpochMilli();}
 static void pref(CacheRegression.TestContext c,String ns,String k,boolean v)throws Exception{
  byte[] h=java.security.MessageDigest.getInstance("SHA-256").digest(ns.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  c.getSharedPreferences("prefs_"+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(h),0).edit().putString(k,v?"AQ==":"Ag==").commit();
 }
 static void daily(CacheRegression.TestContext c,boolean v)throws Exception{pref(c,"com.miui.weather2.warning_weather_day_and_night","warning_weather_day_and_night",v);}
 static void deliver(CacheRegression.TestContext c,JSONObject d,long now)throws Exception{
  Method m=WeatherAlerts.class.getDeclaredMethod("deliver",android.content.Context.class,WeatherDatabaseBridge.City.class,JSONObject.class,long.class);
  m.setAccessible(true);m.invoke(null,c,WeatherDatabaseBridge.selected,d,now);
 }
 static JSONObject warning(long now)throws Exception{return new JSONObject().put("title","Offline fixture warning").put("detail","Parser test only")
  .put("type","rain").put("level","yellow").put("pubTime",Instant.ofEpochMilli(now-1000).atOffset(ZoneOffset.UTC).toString());}
 static final class StopCase {
  final WeatherAlertJobService service=new WeatherAlertJobService();
  final android.app.job.JobParameters parameters;
  final java.util.concurrent.FutureTask<Void> future=new java.util.concurrent.FutureTask<>(()->null);
  StopCase(int reason)throws Exception {
   parameters=new android.app.job.JobParameters(WeatherAlerts.JOB_A,reason);
   Field active=WeatherAlertJobService.class.getDeclaredField("active");active.setAccessible(true);active.set(service,parameters);
   Field work=WeatherAlertJobService.class.getDeclaredField("work");work.setAccessible(true);work.set(service,future);
   Field running=WeatherAlerts.class.getDeclaredField("running");running.setAccessible(true);running.setInt(null,WeatherAlerts.JOB_A);
   daily(service,true);WeatherRefresh.real=true;WeatherRefresh.consent=true;
  }
  boolean stop()throws Exception {
   boolean retry=service.onStopJob(parameters);
   Field active=WeatherAlertJobService.class.getDeclaredField("active");active.setAccessible(true);
   Field running=WeatherAlerts.class.getDeclaredField("running");running.setAccessible(true);
   check(active.get(service)==null&&future.isCancelled()&&running.getInt(null)==0,"matching stop clears active/cancels work/releases running slot");
   check(!service.onStopJob(parameters),"duplicate stop callback after cleanup never retries");
   service.onDestroy();return retry;
  }
 }
 static void currentLocalHour(int target) {
  int utc=Instant.ofEpochMilli(System.currentTimeMillis()).atOffset(ZoneOffset.UTC).getHour();
  int offset=target-utc;if(offset>18)offset-=24;if(offset< -18)offset+=24;
  java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("GMT"+ZoneOffset.ofHours(offset).getId()));
  check(Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault()).getHour()==target,"controlled stop-test local hour");
 }
 static void stopCases()throws Exception {
  java.util.TimeZone original=java.util.TimeZone.getDefault();int api=android.os.Build.VERSION.SDK_INT;
  try {
   currentLocalHour(8);android.os.Build.VERSION.SDK_INT=36;
   StopCase stale=new StopCase(0);
   check(!stale.service.onStopJob(new android.app.job.JobParameters(WeatherAlerts.JOB_A)),"stale same-ID parameters do not retry");
   Field active=WeatherAlertJobService.class.getDeclaredField("active");active.setAccessible(true);
   Field running=WeatherAlerts.class.getDeclaredField("running");running.setAccessible(true);
   check(active.get(stale.service)==stale.parameters&&!stale.future.isCancelled()&&running.getInt(null)==WeatherAlerts.JOB_A,
    "stale parameters cannot cancel the current work or release its slot");stale.service.onDestroy();
   check(new StopCase(0).stop(),"real onStopJob retries interruption inside morning window");
   check(!new StopCase(android.app.job.JobParameters.STOP_REASON_CANCELLED_BY_APP).stop(),"app cancellation never retries");
   check(!new StopCase(android.app.job.JobParameters.STOP_REASON_USER).stop(),"user stop never retries");
   android.os.Build.VERSION.SDK_INT=30;StopCase legacy=new StopCase(android.app.job.JobParameters.STOP_REASON_USER);
   legacy.parameters.forbidStopReason=true;
   check(legacy.stop(),"API 30 uses window without calling unavailable getStopReason");
   android.os.Build.VERSION.SDK_INT=36;currentLocalHour(12);
   check(!new StopCase(0).stop(),"expired useful window stops without retry");
   currentLocalHour(20);check(new StopCase(0).stop(),"real onStopJob retries evening interruption");
   StopCase remote=new StopCase(0);WeatherAlerts.setRemoteDelivery(remote.service,"daily",true);
   check(!remote.stop(),"verified remote daily owner prevents local stop retry");
   StopCase revoked=new StopCase(0);WeatherRefresh.consent=false;
   check(!revoked.stop(),"withdrawn network consent prevents stop retry");
   StopCase disabled=new StopCase(0);daily(disabled.service,false);
   check(!disabled.stop(),"disabled daily preference prevents stop retry");
   StopCase otherSource=new StopCase(0);WeatherRefresh.real=false;
   check(!otherSource.stop(),"non-Xiaomi source prevents real reminder stop retry");
  }finally{java.util.TimeZone.setDefault(original);android.os.Build.VERSION.SDK_INT=api;WeatherRefresh.consent=true;WeatherRefresh.real=true;}
 }
 public static void main(String[]args)throws Exception{
  java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
  JSONObject raw=new JSONObject(new String(Files.readAllBytes(Paths.get(args[0])),java.nio.charset.StandardCharsets.UTF_8));
  long now=at("2026-10-18T19:00:00+08:00");LocalDate today=LocalDate.of(2026,10,18),tomorrow=today.plusDays(1);
  check(WeatherAlertData.forecast(raw,today,"C","zh_CN",now).contains("5–18°C"),"real today range");
  check(WeatherAlertData.forecast(raw,tomorrow,"C","zh_CN",now).contains("6–19°C"),"real tomorrow range");
  check(WeatherAlertData.forecast(raw,tomorrow,"F","en_US",now).contains("43–66°F"),"real tomorrow conversion");
  check(WeatherAlertData.forecast(raw,today.minusDays(1),"C","zh_CN",now)==null,"missing explicit forecast day rejected");
  check(WeatherAlertData.forecast(raw,today,"C","zh_CN",now+2*WeatherAlertData.DAY)==null,"stale daily rejected");
  check(WeatherAlertData.forecast(raw,today,"C","zh_CN",now-WeatherAlertData.DAY)==null,"future daily rejected");
  check(WeatherAlertData.forecast(raw,today,"K","zh_CN",now)==null,"unknown temperature unit rejected");
  JSONObject failed=new JSONObject(raw.toString());failed.getJSONObject("forecastDaily").put("status",1);
  check(WeatherAlertData.forecast(failed,today,"C","zh_CN",now)==null,"provider status honored");
  failed=new JSONObject(raw.toString());failed.getJSONObject("forecastDaily").getJSONObject("temperature").getJSONArray("value").getJSONObject(0).put("from","NaN");
  check(WeatherAlertData.forecast(failed,today,"C","zh_CN",now)==null,"invalid forecast number rejected");
  JSONObject alert=warning(now);WeatherAlertData.Event event=WeatherAlertData.alert(alert,now);
  check(event!=null&&event.title.equals("Offline fixture warning"),"confirmed warning schema");
  check(WeatherAlertData.alert(new JSONObject(alert.toString()).put("pubTime",""),now)==null,"unknown warning time rejected");
  check(WeatherAlertData.alert(alert,now+2*WeatherAlertData.DAY)==null,"old warning rejected");
  check(WeatherAlertData.alert(new JSONObject(alert.toString()).put("pubTime",Instant.ofEpochMilli(now+600000).atOffset(ZoneOffset.UTC).toString()),now)==null,"future warning rejected");
  check(event.identity().equals(WeatherAlertData.alert(new JSONObject(alert.toString()).put("detail","same event expanded"),now).identity()),"warning detail expansion deduplicates");
  check(event.identity().equals(WeatherAlertData.alert(new JSONObject(alert.toString()).put("pubTime",Instant.ofEpochMilli(event.when).atOffset(ZoneOffset.ofHours(8)).toString()),now).identity()),"equivalent UTC/local publication times deduplicate");
  ZoneId zone=ZoneId.of("Asia/Shanghai");
  check(WeatherAlerts.nextBoundary(at("2026-10-18T06:59:00+08:00"),zone)==at("2026-10-18T07:00:00+08:00"),"morning at seven");
  check(WeatherAlerts.nextBoundary(at("2026-10-18T07:00:00+08:00"),zone)==at("2026-10-18T19:00:00+08:00"),"finishing morning schedules evening");
  check(WeatherAlerts.nextBoundary(now,zone)==at("2026-10-19T07:00:00+08:00"),"evening rolls to tomorrow");
  check(Instant.ofEpochMilli(WeatherAlerts.nextBoundary(at("2026-10-31T19:00:00-04:00"),ZoneId.of("America/New_York"))).atZone(ZoneId.of("America/New_York")).getHour()==7,"DST preserves local seven");
  CacheRegression.TestContext c=new CacheRegression.TestContext();
  WeatherDatabaseBridge.selected=new WeatherDatabaseBridge.City("real-city","Real city","real-location","40","116",true,"zh_CN","C");
  check(!WeatherAlerts.dailyEnabled(c)&&WeatherAlerts.alertEnabled(c)&&WeatherAlerts.abruptEnabled(c),"original AOT boolean defaults");
  daily(c,true);check(WeatherAlerts.dailyEnabled(c),"original codec true");
  check(WeatherAlerts.retryDue(c,at("2026-10-28T07:30:00+08:00")),"interrupted morning slot retries");
  check(WeatherAlerts.retryDue(c,at("2026-10-28T19:30:00+08:00")),"interrupted evening slot retries");
  check(!WeatherAlerts.retryDue(c,at("2026-10-28T10:00:00+08:00")),"expired morning never retries");
  check(!WeatherAlerts.retryDue(c,at("2026-10-28T22:00:00+08:00")),"expired evening never retries");
  WeatherRefresh.consent=false;
  check(!WeatherAlerts.retryDue(c,at("2026-10-28T07:30:00+08:00")),"withdrawal disables retry");
  WeatherRefresh.consent=true;WeatherRefresh.real=false;
  check(!WeatherAlerts.retryDue(c,at("2026-10-28T07:30:00+08:00")),"experimental source cannot retry real alerts");
  WeatherRefresh.real=true;
  daily(c,false);check(!WeatherAlerts.retryDue(c,at("2026-10-28T07:30:00+08:00")),"disabled daily cannot retry");
  daily(c,true);
  pref(c,"com.miui.weather2.alert_weather_enable","alert_weather_enable",false);check(!WeatherAlerts.alertEnabled(c),"original codec false");
  check(WeatherAlerts.needsBackgroundWeather(c),"daily reminder enables shared background refresh");
  WeatherAlerts.onEnvironmentChanged(c);check(c.scheduler.jobs.size()==1,"one independent daily job");
  android.app.job.JobInfo pending=c.scheduler.jobs.values().iterator().next();int queued=c.scheduler.scheduled;
  WeatherAlerts.ensureScheduled(c);check(c.scheduler.jobs.values().iterator().next()==pending&&c.scheduler.scheduled==queued,"cold Application startup preserves launching daily job");
  WeatherRefresh.consent=false;WeatherAlerts.onEnvironmentChanged(c);check(c.scheduler.jobs.isEmpty(),"consent withdrawal cancels cache-only daily wakeups");
  WeatherRefresh.consent=true;WeatherAlerts.onEnvironmentChanged(c);check(c.scheduler.jobs.size()==1,"consent regrant rearms daily reminder");
  WeatherAlerts.onEnvironmentChanged(c);check(c.scheduler.jobs.size()==1,"repeated settings changes keep one job");
  int old=c.scheduler.jobs.keySet().iterator().next();WeatherAlerts.jobStarted(c,old);
  check(c.scheduler.jobs.size()==2&&c.scheduler.jobs.containsKey(old==WeatherAlerts.JOB_A?WeatherAlerts.JOB_B:WeatherAlerts.JOB_A),"successor uses different ID from finishing job");
  c.scheduler.jobs.remove(old);WeatherAlerts.jobEnded(old);check(c.scheduler.jobs.size()==1,"late finish preserves successor");
  deliver(c,raw,now);check(AndroidWeatherNotifications.count==1&&AndroidWeatherNotifications.last.title.contains("明天")&&AndroidWeatherNotifications.last.text.contains("6–19°C"),"evening uses real tomorrow");
  deliver(c,raw,now+1000);check(AndroidWeatherNotifications.count==1,"daily replay deduplicated");
  daily(c,false);WeatherAlerts.onEnvironmentChanged(c);check(c.scheduler.jobs.isEmpty(),"daily disabling cancels daily job");
  check(!WeatherAlerts.needsBackgroundWeather(c),"all local weather categories disabled needs no refresh");
  long actual=System.currentTimeMillis();alert=warning(actual);
  pref(c,"com.miui.weather2.alert_weather_enable","alert_weather_enable",true);
  AndroidWeatherNotifications.result=AndroidWeatherNotifications.PostResult.PERMISSION_DENIED;
  check(!WeatherAlerts.postServerAlert(c,"real-city",alert),"permission denied is not success");
  AndroidWeatherNotifications.result=AndroidWeatherNotifications.PostResult.QUIET_HOURS;
  check(!WeatherAlerts.postServerAlert(c,"real-city",alert),"quiet warning not consumed");
  AndroidWeatherNotifications.result=AndroidWeatherNotifications.PostResult.POSTED;
  check(WeatherAlerts.postServerAlert(c,"real-city",alert),"real warning can retry after grant/quiet");
  int count=AndroidWeatherNotifications.count;check(WeatherAlerts.postServerAlert(c,"real-city",alert)&&AndroidWeatherNotifications.count==count,"MiPush warning replay deduplicated");
  JSONObject response=new JSONObject().put("alerts",new JSONArray().put(alert));deliver(c,response,actual);
  check(AndroidWeatherNotifications.count==count,"HTTP and MiPush share warning identity");
  check(!WeatherAlerts.postServerAlert(c,"other-city",new JSONObject(alert.toString()).put("title","changed")),"wrong-city message rejected");
  WeatherRefresh.real=false;check(!WeatherAlerts.postServerAlert(c,"real-city",new JSONObject(alert.toString()).put("title","fake")),"test source cannot post real warning");WeatherRefresh.real=true;
  WeatherRefresh.consent=false;check(!WeatherAlerts.postServerEvent(c,"abrupt","real-city","id1","title","detail",actual),"withdrawn consent blocks remote event");WeatherRefresh.consent=true;
  check(WeatherAlerts.postServerEvent(c,"abrupt","real-city","id1","real server title","real server detail",0),"real abrupt event uses receipt time when issue absent");
  count=AndroidWeatherNotifications.count;WeatherAlerts.onMiPushContent(c,"id2",new JSONObject().put("messageType","4").put("messageDatas",new JSONArray().put(new JSONObject().put("data",new JSONObject().put("locationKey","wrong").put("title","wrong city").put("desc","detail")))).toString(),false);
  check(AndroidWeatherNotifications.count==count,"push location mismatch rejected");
  String push=new JSONObject().put("messageType","4").put("messageDatas",new JSONArray().put(new JSONObject().put("data",new JSONObject().put("locationKey","real-location").put("title","server abrupt").put("desc","server detail")))).toString();
  WeatherAlerts.onMiPushContent(c,"id3",push,true);check(AndroidWeatherNotifications.count==count,"click never reposts");
  WeatherAlerts.onMiPushContent(c,"id3",push,false);check(AndroidWeatherNotifications.count==count+1,"real pass-through event posts");
  WeatherAlerts.onMiPushContent(c,"id3",push,false);check(AndroidWeatherNotifications.count==count+1,"pass-through event id deduplicates");
  WeatherAlerts.setRemoteDelivery(c,"alert",true);check(!WeatherAlerts.needsBackgroundWeather(c),"verified remote owner disables local alert refresh");
  stopCases();
  System.out.println("PASS: "+checks+" real forecast/reminder/identity/preferences/scheduling assertions (offline only)");
 }
}
