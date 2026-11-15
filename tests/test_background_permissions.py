"""Run the production permission queue against deterministic Android fakes; no device.

python -X utf8 tests/test_background_permissions.py --java-home "C:/Program Files/Java/jdk-17"
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
    'android/Manifest.java': '''package android; public class Manifest { public static class permission {
 public static final String ACCESS_FINE_LOCATION="android.permission.ACCESS_FINE_LOCATION",
 ACCESS_COARSE_LOCATION="android.permission.ACCESS_COARSE_LOCATION",
 ACCESS_BACKGROUND_LOCATION="android.permission.ACCESS_BACKGROUND_LOCATION",
 POST_NOTIFICATIONS="android.permission.POST_NOTIFICATIONS"; }}''',
    'android/os/Build.java': '''package android.os; public class Build {
 public static class VERSION { public static int SDK_INT=36; }
 public static class VERSION_CODES {public static final int M=23,Q=29,R=30,S=31,TIRAMISU=33;} }''',
    'android/os/Looper.java': '''package android.os; public class Looper {
 public static Looper getMainLooper(){return new Looper();} }''',
    'android/os/SystemClock.java': '''package android.os; public class SystemClock {
 public static long uptimeMillis(){return Handler.now;} public static long elapsedRealtime(){return Handler.now;} }''',
    'android/os/Handler.java': '''package android.os; import java.util.*; public class Handler {
 public static long now,sequence; private static final List<Task> tasks=new ArrayList<>();
 private static class Task {Runnable run;long due,order;Task(Runnable r,long d){run=r;due=d;order=sequence++;}}
 public Handler(Looper l){} public boolean post(Runnable r){return postDelayed(r,0);}
 public boolean postDelayed(Runnable r,long delay){tasks.add(new Task(r,now+Math.max(0,delay)));return true;}
 public void removeCallbacks(Runnable r){tasks.removeIf(t->t.run==r);}
 public void removeCallbacksAndMessages(Object token){tasks.clear();}
 public static void reset(){tasks.clear();now=0;sequence=0;}
 public static void flush(){advance(0);}
 public static void advance(long millis){long target=now+millis;int count=0;for(;;){
  Task first=null;for(Task t:tasks)if(t.due<=target&&(first==null||t.due<first.due||
    (t.due==first.due&&t.order<first.order)))first=t;
  if(first==null)break;if(++count>10000)throw new AssertionError("timer loop");
  tasks.remove(first);now=first.due;first.run.run();}now=target;}
}''',
    'android/content/Context.java': 'package android.content; public class Context {}',
    'android/content/Intent.java': '''package android.content; public class Intent {
 public final String action;public final android.net.Uri uri;
 public Intent(String a,android.net.Uri u){action=a;uri=u;} }''',
    'android/content/pm/PackageInfo.java': '''package android.content.pm; public class PackageInfo {
 public String[] requestedPermissions; }''',
    'android/content/pm/PermissionInfo.java': '''package android.content.pm; public class PermissionInfo {
 public static final int PROTECTION_MASK_BASE=15,PROTECTION_DANGEROUS=1;public int protectionLevel=1; }''',
    'android/content/pm/PackageManager.java': '''package android.content.pm; import java.util.*;
 public class PackageManager {public static final int PERMISSION_GRANTED=0,PERMISSION_DENIED=-1,GET_PERMISSIONS=4096;
 public final Set<String> declared=new LinkedHashSet<>(Arrays.asList(
 android.Manifest.permission.ACCESS_FINE_LOCATION,android.Manifest.permission.ACCESS_COARSE_LOCATION,
 android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,android.Manifest.permission.POST_NOTIFICATIONS,
 "android.permission.CAMERA"));public final Set<String> granted=new HashSet<>();
 public PackageInfo getPackageInfo(String name,int flags)throws NameNotFoundException{
  PackageInfo p=new PackageInfo();p.requestedPermissions=declared.toArray(new String[0]);return p;}
 public PermissionInfo getPermissionInfo(String name,int flags)throws NameNotFoundException{
  if(!declared.contains(name))throw new NameNotFoundException();return new PermissionInfo();}
 public int checkPermission(String name,String pkg){return granted.contains(name)?0:-1;}
 public CharSequence getBackgroundPermissionOptionLabel(){return "Always allow";}
 public static class NameNotFoundException extends Exception {}
}''',
    'android/app/Activity.java': '''package android.app; import java.util.*;
 public class Activity extends android.content.Context {
 public final android.content.pm.PackageManager pm=new android.content.pm.PackageManager();
 public final List<Call> calls=new ArrayList<>();public int settings;public boolean focus=true,finishing,destroyed;
 public static class Call {public final String[] names;public final int code;
  Call(String[] n,int c){names=n.clone();code=c;}}
 public android.content.pm.PackageManager getPackageManager(){return pm;}
 public String getPackageName(){return "dev.weather.oneplus";}
 public int checkSelfPermission(String p){return pm.checkPermission(p,getPackageName());}
 public <T>T getSystemService(Class<T> type){return type.cast(new NotificationManager());}
 public boolean shouldShowRequestPermissionRationale(String p){return false;}
 public boolean isFinishing(){return finishing;} public boolean isDestroyed(){return destroyed;}
 public boolean hasWindowFocus(){return focus;}
 public void requestPermissions(String[] p,int code){calls.add(new Call(p,code));}
 public void startActivity(android.content.Intent i){settings++;}
 public void startActivityForResult(android.content.Intent i,int code){settings++;}
}''',
    'android/app/NotificationManager.java': '''package android.app; public class NotificationManager {
 public boolean areNotificationsEnabled(){return true;} }''',
    'android/net/Uri.java': '''package android.net; public class Uri {
 public static Uri fromParts(String s,String p,String f){return new Uri();} }''',
    'android/provider/Settings.java': '''package android.provider; public class Settings {
 public static final String ACTION_APPLICATION_DETAILS_SETTINGS="android.settings.APPLICATION_DETAILS_SETTINGS"; }''',
    'android/widget/Toast.java': '''package android.widget; import java.util.*; public class Toast {
 public static final int LENGTH_SHORT=0,LENGTH_LONG=1; public static final List<String> shown=new ArrayList<>();
 private final String text;private Toast(CharSequence t){text=t.toString();}
 public static Toast makeText(android.content.Context c,CharSequence t,int duration){return new Toast(t);}
 public void show(){shown.add(text);} }''',
    'android/util/Log.java': '''package android.util; public class Log {
 public static int i(String t,String s){return 0;} public static int w(String t,String s){return 0;}
 public static int w(String t,String s,Throwable e){return 0;} public static int e(String t,String s,Throwable e){return 0;} }''',
    'org/json/JSONArray.java': '''package org.json; public class JSONArray {
 private final java.util.List<?> values;public JSONArray(java.util.List<?> v){values=v;}
 public int length(){return values.size();}public Object opt(int i){return values.get(i);} }''',
    'org/json/JSONObject.java': '''package org.json; public class JSONObject {
 private final java.util.Map<?,?> values;public JSONObject(java.util.Map<?,?> v){values=v;}
 public Object opt(String k){return values.get(k);} public String toString(){return values.toString();} }''',
}

PROBE = r'''package dev.weather.oneplus;
import android.Manifest;import android.app.Activity;import android.os.*;import android.widget.Toast;
import java.util.*;
public class BackgroundPermissionCheck {
 static final String FG=Manifest.permission.ACCESS_COARSE_LOCATION,FINE=Manifest.permission.ACCESS_FINE_LOCATION,
  BG=Manifest.permission.ACCESS_BACKGROUND_LOCATION,CAMERA="android.permission.CAMERA";
 static final String TIP="如果没出现后台定位授权，请在系统设置开启";
 static int checks,scenarios;
 static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label+" sdk="+Build.VERSION.SDK_INT);}
 static class Fixture {
  final Activity a=new Activity();final List<Map<?,?>> events=new ArrayList<>();
  final Map<Integer,List<String>> requests=new HashMap<>();final AndroidPermissions p;
  Fixture(int sdk,boolean foreground){scenarios++;Handler.reset();Toast.shown.clear();Build.VERSION.SDK_INT=sdk;
   if(foreground)a.pm.granted.add(FG);
   p=new AndroidPermissions(a,(channel,method,args)->{
    check(channel.equals(AndroidPermissions.CHANNEL),"correct event channel");
    check(method.equals("on_request_permission_result"),"correct result method");events.add((Map<?,?>)args);
   });p.onResume();Handler.flush();}
  void request(int code,String... names){Map<String,Object> args=new LinkedHashMap<>();
   args.put("permissions",Arrays.asList(names));args.put("request_code",code);requests.put(code,Arrays.asList(names));
   check(Boolean.TRUE.equals(p.handleCall(AndroidPermissions.CHANNEL,"request_permissions",args)),"accepted");
   Handler.flush();check(a.settings==0,"no automatic settings or explanation");}
  Activity.Call call(){return a.calls.get(a.calls.size()-1);}
  void pause(){a.focus=false;p.onWindowFocusChanged(false);p.onPause();Handler.flush();}
  void resume(){a.focus=true;p.onResume();p.onWindowFocusChanged(true);Handler.flush();}
  void focus(boolean f){a.focus=f;p.onWindowFocusChanged(f);Handler.flush();}
  void result(Activity.Call c,int... values){check(p.onRequestPermissionsResult(c.code,c.names,values),"callback consumed");Handler.flush();}
  void event(int index,int code,int... values){check(events.size()>index,"result emitted");Map<?,?> e=events.get(index);
   check(e.get("request_code").equals(code),"original Dart code preserved");
   check(e.get("permissions").equals(requests.get(code)),"original permission list preserved");
   List<?> actual=(List<?>)e.get("grant_results");check(actual.size()==values.length,"grant count");
   for(int i=0;i<values.length;i++)check(actual.get(i).equals(values[i]),"grant state "+i);}
  void tip(int count){check(Toast.shown.size()==count,"toast count expected "+count+" actual "+Toast.shown);
   if(count>0)check(Toast.shown.get(0).equals(TIP),"exact fallback message");}
 }
 static void backgroundOnly(Activity.Call c){check(Arrays.equals(c.names,new String[]{BG}),"background-only system request");
  check(c.code>=0x5800&&c.code<=0x6fff,"background request code in reserved range");}
 static void sdkFlow(int sdk){
  Fixture f=new Fixture(sdk,false);f.request(11,FINE,FG,BG);check(f.a.calls.size()==1,"foreground first");
  Activity.Call fg=f.call();check(!Arrays.asList(fg.names).contains(BG),"never combine foreground and background");
  check(Arrays.asList(fg.names).contains(FG)&&Arrays.asList(fg.names).contains(FINE),"precision pair");
  f.pause();f.a.pm.granted.add(FG);f.result(fg,-1,0);f.resume();
  check(f.p.hasForegroundLocation(),"approximate location is enough");
  if(sdk<29){check(f.a.calls.size()==1,"pre29 no background request");f.event(0,11,-1,0,0);f.tip(0);return;}
  check(f.a.calls.size()==2,"background system request after foreground grant");Activity.Call bg=f.call();backgroundOnly(bg);
  f.pause();Handler.advance(5000);check(f.events.isEmpty(),"system UI not timed out");
  f.a.pm.granted.add(BG);f.result(bg,0);f.resume();f.event(0,11,-1,0,0);f.tip(0);
 }
 static void deny(int sdk){Fixture f=new Fixture(sdk,true);f.request(12,BG);Activity.Call bg=f.call();backgroundOnly(bg);
  f.pause();Handler.advance(50);f.result(bg,-1);f.resume();f.event(0,12,-1);f.tip(0);
  Handler.advance(5000);check(f.events.size()==1,"deny only completes once");f.tip(0);}
 static void focusOnlyDeny(){Fixture f=new Fixture(36,true);f.request(13,BG);Activity.Call bg=f.call();
  f.focus(false);Handler.advance(100);f.result(bg,-1);f.focus(true);f.event(0,13,-1);f.tip(0);}
 static void immediateGrant(){Fixture f=new Fixture(36,true);f.request(14,BG);Activity.Call bg=f.call();
  f.a.pm.granted.add(BG);f.result(bg,0);f.event(0,14,0);f.tip(0);Handler.advance(5000);
  check(f.events.size()==1,"grant callback cancels watchdog");f.tip(0);}
 static void grantCheckedAgain(){Fixture f=new Fixture(36,true);f.request(15,BG);Activity.Call bg=f.call();
  f.pause();f.result(bg,0);f.resume();f.event(0,15,-1);f.tip(0);}
 static void noUi(){Fixture f=new Fixture(36,true);f.request(20,BG);backgroundOnly(f.call());
  f.request(21,CAMERA);check(f.a.calls.size()==1,"queue waits for background");
  Handler.advance(2999);check(f.events.isEmpty(),"does not finish before 3 seconds");f.tip(0);
  Handler.advance(1);f.event(0,20,-1);f.tip(1);check(f.a.calls.size()==2,"queued work continues after timeout");
  Activity.Call next=f.call();check(Arrays.equals(next.names,new String[]{CAMERA}),"next foreground request");
  f.a.pm.granted.add(CAMERA);f.result(next,0);f.event(1,21,0);}
 static void immediateNoUiDenial(int sdk){Fixture f=new Fixture(sdk,true);f.request(22,BG);Activity.Call bg=f.call();
  Handler.advance(100);f.result(bg,-1);check(f.events.isEmpty(),"noUI denial remains pending until original deadline");
  Handler.advance(2899);check(f.events.isEmpty(),"denial timer does not run early");Handler.advance(1);
  f.event(0,22,-1);f.tip(1);}
 static void pauseProtects(){Fixture f=new Fixture(33,true);f.request(23,BG);f.pause();Handler.advance(6000);
  check(f.events.isEmpty(),"paused UI protects pending request");f.tip(0);f.resume();Handler.advance(3000);
  f.event(0,23,-1);f.tip(0);}
 static void focusProtects(){Fixture f=new Fixture(36,true);f.request(24,BG);f.focus(false);Handler.advance(6000);
  check(f.events.isEmpty(),"system window protects without pause");f.tip(0);f.focus(true);Handler.advance(3000);
  f.event(0,24,-1);f.tip(0);}
 static void resumeGrant(){Fixture f=new Fixture(29,true);f.request(25,BG);f.pause();Handler.advance(6000);
  f.a.pm.granted.add(BG);f.resume();Handler.advance(3000);f.event(0,25,0);f.tip(0);}
 static void lateForeground(){Fixture f=new Fixture(36,true);f.request(30,BG);Activity.Call old=f.call();
  f.request(31,CAMERA);Handler.advance(3000);Activity.Call next=f.call();
  f.result(old,-1);check(f.events.size()==1,"late background cannot finish new foreground");
  f.a.pm.granted.add(CAMERA);f.result(next,0);f.event(1,31,0);}
 static void lateBackground(){Fixture f=new Fixture(36,true);f.request(32,BG);Activity.Call old=f.call();
  Handler.advance(3000);f.request(33,BG);Activity.Call next=f.call();backgroundOnly(next);
  check(old.code!=next.code,"consecutive background requests have different system codes");
  f.result(old,-1);check(f.events.size()==1,"late background cannot finish new background");
  f.pause();f.a.pm.granted.add(BG);f.result(next,0);f.resume();f.event(1,33,0);f.tip(1);}
 static void cancelled(){Fixture f=new Fixture(36,true);f.request(40,BG);Activity.Call bg=f.call();
  check(f.p.onRequestPermissionsResult(bg.code,new String[0],new int[0]),"empty cancellation consumed");
  Handler.flush();f.event(0,40,-1);f.tip(0);Handler.advance(3000);
  check(f.events.size()==1,"cancel callback completes once");f.tip(0);
  check(f.a.calls.size()==1,"cancel not retried");}
 static void foregroundDenied(){Fixture f=new Fixture(36,false);f.request(41,BG);Activity.Call fg=f.call();
  f.pause();f.result(fg,-1,-1);f.resume();f.event(0,41,-1);check(f.a.calls.size()==1,"no background without foreground");f.tip(0);}
 static void destroyed(){Fixture f=new Fixture(36,true);f.request(42,BG);Activity.Call bg=f.call();f.request(43,CAMERA);
  f.p.onDestroy();f.a.destroyed=true;Handler.advance(10000);f.p.onRequestPermissionsResult(bg.code,bg.names,new int[]{-1});
  Handler.flush();check(f.events.isEmpty(),"destroyed bridge never emits");f.tip(0);
  check(f.a.calls.size()==1,"destroy cancels queued requests");}
 static void passiveAndGranted(){Fixture f=new Fixture(36,true);
  check(f.p.checkPermission(BG)==-1,"passive denied check");check(f.a.calls.isEmpty(),"check never requests");
  f.a.pm.granted.add(BG);f.request(44,BG);f.event(0,44,0);check(f.a.calls.isEmpty(),"already granted no request");f.tip(0);}
 public static void main(String[] args){for(int sdk:new int[]{28,29,30,33,36})sdkFlow(sdk);
  for(int sdk:new int[]{29,30,33,36})deny(sdk);
  focusOnlyDeny();immediateGrant();grantCheckedAgain();noUi();
  for(int sdk:new int[]{29,30,33,36})immediateNoUiDenial(sdk);
  pauseProtects();focusProtects();resumeGrant();lateForeground();lateBackground();
  cancelled();foregroundDenied();destroyed();passiveAndGranted();
  System.out.println("PASS: "+scenarios+" background permission scenarios, "+checks+" assertions");}
}'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, default=os.environ.get('JAVA_HOME'))
    args = parser.parse_args()
    if not args.java_home:
        parser.error('Provide --java-home or JAVA_HOME')
    java_bin = args.java_home / 'bin'
    suffix = '.exe' if os.name == 'nt' else ''
    with tempfile.TemporaryDirectory(prefix='background-permissions-') as directory:
        temporary = Path(directory)
        sources = []
        for name, body in SOURCES.items():
            path = temporary / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(body, encoding='utf-8')
            sources.append(path)
        probe = temporary / 'dev/weather/oneplus/BackgroundPermissionCheck.java'
        probe.parent.mkdir(parents=True, exist_ok=True)
        probe.write_text(PROBE, encoding='utf-8')
        sources.extend([probe, ROOT / 'port/AndroidPermissions.java'])
        subprocess.run([str(java_bin / ('javac' + suffix)), '-J-Dfile.encoding=UTF-8', '--release', '8', '-encoding', 'UTF-8',
                        '-d', str(temporary / 'classes'), *map(str, sources)], check=True)
        subprocess.run([str(java_bin / ('java' + suffix)), '-cp', str(temporary / 'classes'),
                        'dev.weather.oneplus.BackgroundPermissionCheck'], check=True)


if __name__ == '__main__':
    main()
