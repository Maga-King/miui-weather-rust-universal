"""Read production background diagnostics against synthetic system state; no device changes."""
import argparse, os, subprocess, tempfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
STUBS={
'android/content/SharedPreferences.java':'''package android.content;public interface SharedPreferences {
 boolean getBoolean(String k,boolean f);long getLong(String k,long f);String getString(String k,String f);
}''',
'android/content/Context.java':'''package android.content;public class Context {
 public static final int MODE_PRIVATE=0;public final java.util.Map<Class<?>,Object> services=new java.util.HashMap<>();
 public final java.util.Map<String,Prefs> prefs=new java.util.HashMap<>();
 public <T>T getSystemService(Class<T> type){return type.cast(services.get(type));}
 public String getPackageName(){return "dev.weather.oneplus";}
 public Prefs getSharedPreferences(String n,int m){return prefs.computeIfAbsent(n,k->new Prefs());}
 public static class Prefs implements SharedPreferences {
  public java.util.Map<String,Object> data=new java.util.HashMap<>();
  public boolean getBoolean(String k,boolean f){return data.containsKey(k)?(Boolean)data.get(k):f;}
  public long getLong(String k,long f){return data.containsKey(k)?(Long)data.get(k):f;}
  public String getString(String k,String f){return data.containsKey(k)?(String)data.get(k):f;}
 }
}''',
'android/content/Intent.java':'''package android.content;public class Intent {
 public String action;public android.net.Uri uri;public java.util.Map<String,Object> extras=new java.util.HashMap<>();
 public Intent(String a){action=a;}public Intent(String a,android.net.Uri u){action=a;uri=u;}
 public Intent putExtra(String k,String v){extras.put(k,v);return this;}
}''',
'android/net/Uri.java':'''package android.net;public class Uri {
 public String scheme,part;public static Uri fromParts(String s,String p,String f){Uri u=new Uri();u.scheme=s;u.part=p;return u;}
}''',
'android/provider/Settings.java':'''package android.provider;public class Settings {
 public static final String ACTION_APPLICATION_DETAILS_SETTINGS="details",ACTION_APP_NOTIFICATION_SETTINGS="notifications",
 ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS="battery",EXTRA_APP_PACKAGE="package";
}''',
'android/content/ComponentName.java':'''package android.content;public class ComponentName {
 private final String p,c;public ComponentName(String p,String c){this.p=p;this.c=c;}
 public String getPackageName(){return p;}public String getClassName(){return c;}
}''',
'android/app/ActivityManager.java':'''package android.app;public class ActivityManager {
 public boolean restricted;public boolean isBackgroundRestricted(){return restricted;}
}''',
'android/app/NotificationManager.java':'''package android.app;public class NotificationManager {
 public static final int IMPORTANCE_NONE=0;public NotificationChannel channel;public String requested;
 public NotificationChannel getNotificationChannel(String n){requested=n;return channel;}
}''',
'android/app/NotificationChannel.java':'''package android.app;public class NotificationChannel {
 private final int importance;public NotificationChannel(int i){importance=i;}public int getImportance(){return importance;}
}''',
'android/app/usage/UsageStatsManager.java':'''package android.app.usage;public class UsageStatsManager {
 public static final int STANDBY_BUCKET_ACTIVE=10,STANDBY_BUCKET_WORKING_SET=20,STANDBY_BUCKET_FREQUENT=30,
 STANDBY_BUCKET_RARE=40,STANDBY_BUCKET_RESTRICTED=45;public int bucket=40;
 public int getAppStandbyBucket(){return bucket;}
}''',
'android/os/PowerManager.java':'''package android.os;public class PowerManager {
 public boolean ignore,saver;public String queried;
 public boolean isIgnoringBatteryOptimizations(String p){queried=p;return ignore;}
 public boolean isPowerSaveMode(){return saver;}
}''',
'android/os/PersistableBundle.java':'''package android.os;public class PersistableBundle {
 public long due;public long getLong(String k,long f){return "due_ms".equals(k)?due:f;}
}''',
'android/app/job/JobInfo.java':'''package android.app.job;public class JobInfo {
 public android.content.ComponentName service;public android.os.PersistableBundle extras=new android.os.PersistableBundle();
 public JobInfo(String p,String c,long due){service=new android.content.ComponentName(p,c);extras.due=due;}
 public android.content.ComponentName getService(){return service;}public android.os.PersistableBundle getExtras(){return extras;}
}''',
'android/app/job/JobScheduler.java':'''package android.app.job;public class JobScheduler {
 public java.util.List<JobInfo> pending=new java.util.ArrayList<>();public boolean fail;
 public java.util.List<JobInfo> getAllPendingJobs(){if(fail)throw new SecurityException();return pending;}
}''',
'dev/weather/oneplus/WeatherRefresh.java':'''package dev.weather.oneplus;public class WeatherRefresh {
 static final java.util.concurrent.atomic.AtomicBoolean IN_FLIGHT=new java.util.concurrent.atomic.AtomicBoolean();
 static boolean consent=true,needed=true,night;
 static boolean networkAllowed(android.content.Context c){return consent;}
 static boolean backgroundNeeded(android.content.Context c){return needed;}
 static boolean nightUpdateAllowed(android.content.Context c){return night;}
 static android.content.SharedPreferences state(android.content.Context c){return c.getSharedPreferences("weather_refresh_state",0);}
}''',
'dev/weather/oneplus/WeatherAlerts.java':'''package dev.weather.oneplus;public class WeatherAlerts {
 static boolean daily=true,alert=true,abrupt=true;
 static boolean dailyEnabled(android.content.Context c){return daily;}static boolean alertEnabled(android.content.Context c){return alert;}
 static boolean abruptEnabled(android.content.Context c){return abrupt;}
 static android.content.SharedPreferences state(android.content.Context c){return c.getSharedPreferences("weather_real_alerts_v1",0);}
}''',
'dev/weather/oneplus/WeatherRefreshJobService.java':'package dev.weather.oneplus;public class WeatherRefreshJobService {}',
'dev/weather/oneplus/WeatherAlertJobService.java':'package dev.weather.oneplus;public class WeatherAlertJobService {}',
'dev/weather/oneplus/AndroidWeatherNotifications.java':'''package dev.weather.oneplus;public class AndroidWeatherNotifications {
 static final String DEFAULT_CHANNEL="weather_alert_channel_id";static boolean allowed=true,fail,quiet;
 static boolean notificationsAllowed(android.content.Context c){if(fail)throw new SecurityException();return allowed;}
 static boolean quietHoursEnabled(android.content.Context c){return quiet;}
}''',
}
PROBE='''package dev.weather.oneplus;
import android.app.*;import android.app.job.*;import android.content.*;import android.os.*;import android.app.usage.*;
public class BackgroundStatusCheck {
 static int checks;static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
 public static void main(String[] args) {
  Context c=new Context();PowerManager power=new PowerManager();power.ignore=true;power.saver=true;
  ActivityManager activity=new ActivityManager();activity.restricted=true;UsageStatsManager usage=new UsageStatsManager();
  NotificationManager notifications=new NotificationManager();notifications.channel=new NotificationChannel(0);
  JobScheduler jobs=new JobScheduler();
  c.services.put(PowerManager.class,power);c.services.put(ActivityManager.class,activity);c.services.put(UsageStatsManager.class,usage);
  c.services.put(NotificationManager.class,notifications);c.services.put(JobScheduler.class,jobs);
  jobs.pending.add(new JobInfo(c.getPackageName(),WeatherRefreshJobService.class.getName(),2000));
  jobs.pending.add(new JobInfo(c.getPackageName(),WeatherRefreshJobService.class.getName(),1000));
  jobs.pending.add(new JobInfo(c.getPackageName(),WeatherAlertJobService.class.getName(),0));
  jobs.pending.add(new JobInfo("other.package",WeatherRefreshJobService.class.getName(),1));
  jobs.pending.add(new JobInfo(c.getPackageName(),"unrelated.sdk.Job",1));
  c.getSharedPreferences("weather_refresh_state",0).data.put("last_success_ms",900L);
  c.getSharedPreferences("weather_real_alerts_v1",0).data.put("last_background_check_ms",800L);
  c.getSharedPreferences("weather_mipush_bridge_v1",0).data.put("verified",true);
  c.getSharedPreferences("weather_mipush_bridge_v1",0).data.put("last_pass_through_at",700L);
  c.getSharedPreferences("weather_mipush_bridge_v1",0).data.put("last_sdk_notification_at",750L);
  WeatherBackgroundStatus s=WeatherBackgroundStatus.read(c);
  check(s.refreshJobs==2&&s.reminderJobs==1&&s.nextRefreshDue==1000,"own jobs only and earliest planned time");
  check(s.batteryExempt&&s.powerSave&&s.backgroundRestricted,"real system state");
  check(c.getPackageName().equals(power.queried),"power query targets own package");
  check(s.notificationsAllowed&&s.alertChannelBlocked,"app permission is distinct from disabled channel");
  check(notifications.requested.equals(AndroidWeatherNotifications.DEFAULT_CHANNEL),"real alert channel query");
  check(s.sdkRegistered&&!s.remoteAlert&&!s.remoteDaily&&!s.remoteAbrupt,"SDK registration is not verified remote weather delivery");
  check(s.lastSuccess==900&&s.lastReminderCheck==800&&s.lastPushReceipt==750,"real timestamps preserved");
  check(s.standbyBucket==40&&WeatherBackgroundStatus.standbyLabel(40,false).equals("Rare"),"standby state");
  check(WeatherBackgroundStatus.standbyLabel(45,true).equals("受限制"),"restricted mapping");
  check(WeatherBackgroundStatus.standbyLabel(999,false).equals("Unavailable"),"unknown bucket not called active");
  Intent details=WeatherBackgroundStatus.applicationSettings("dev.weather.test");
  check(details.action.equals("details")&&details.uri.part.equals("dev.weather.test"),"app-details targets caller package");
  Intent notify=WeatherBackgroundStatus.notificationSettings("dev.weather.test");
  check(notify.action.equals("notifications")&&notify.extras.get("package").equals("dev.weather.test"),"notification settings targets caller package");
  check(WeatherBackgroundStatus.batterySettings().action.equals("battery"),"public battery settings");
  check(WeatherBackgroundStatus.refreshError("source_timeout",true).contains("超时"),"human-readable timeout");
  check(!WeatherBackgroundStatus.refreshError("anything_private",true).contains("anything_private"),"unknown internal code not leaked to UI");
  AndroidWeatherNotifications.fail=true;jobs.fail=true;c.services.remove(PowerManager.class);
  s=WeatherBackgroundStatus.read(c);
  check(s.notificationsAllowed==null&&!s.jobsAvailable&&s.batteryExempt==null,"unavailable state is not permission denial or grant");
  check(jobs.pending.size()==5,"diagnostics never mutate jobs");
  System.out.println("PASS: "+checks+" background diagnostics/settings assertions");
 }
}'''
def main():
    ap=argparse.ArgumentParser();ap.add_argument('--java-home',type=Path,default=os.environ.get('JAVA_HOME'));args=ap.parse_args()
    if not args.java_home:ap.error('Provide --java-home or JAVA_HOME')
    java=args.java_home/'bin';suffix='.exe' if os.name=='nt' else ''
    with tempfile.TemporaryDirectory(prefix='background-status-',dir=ROOT/'.build') as tmp:
        tmp=Path(tmp);sources=[]
        for name,body in STUBS.items():
            path=tmp/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(body,encoding='utf8');sources.append(path)
        probe=tmp/'dev/weather/oneplus/BackgroundStatusCheck.java';probe.write_text(PROBE,encoding='utf8');sources.append(probe)
        sources.append(ROOT/'port/WeatherBackgroundStatus.java')
        subprocess.run([str(java/('javac'+suffix)),'--release','8','-encoding','UTF-8','-d',str(tmp/'classes'),*map(str,sources)],check=True)
        subprocess.run([str(java/('java'+suffix)),'-cp',str(tmp/'classes'),'dev.weather.oneplus.BackgroundStatusCheck'],check=True)
if __name__=='__main__':main()
