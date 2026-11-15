"""Execute production permission intent handlers with Android fakes, without adb.

python -X utf8 tests/test_permission_settings_intents.py --java-home "C:/Program Files/Java/jdk-17"
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SUPPORT = {
    'dev/weather/oneplus/AndroidWeatherPages.java': '''package dev.weather.oneplus;
public class AndroidWeatherPages {public static final String WEATHER_VIDEO_FALLBACK="unused";
static String webUrl(String s){return null;}static boolean isWeatherVideo(String s){return false;}}''',
    'dev/weather/oneplus/WeatherWebActivity.java': '''package dev.weather.oneplus;
public class WeatherWebActivity {public static final String EXTRA_URL="url";}''',
    'dev/weather/oneplus/WeatherAppearanceActivity.java': 'package dev.weather.oneplus; public class WeatherAppearanceActivity {}',
    'dev/weather/oneplus/WeatherWidgetManagementActivity.java': 'package dev.weather.oneplus; public class WeatherWidgetManagementActivity {}',
    'dev/weather/oneplus/Codec.java': '''package dev.weather.oneplus;public class Codec {
static void write(java.io.ByteArrayOutputStream b,Object o){throw new AssertionError("unexpected codec");}}''',
}
FAKES = {
    'android/content/Context.java': 'package android.content;public class Context {}',
    'android/app/Activity.java': '''package android.app;import java.util.*;import android.content.*;
public class Activity extends Context {
public static final int RESULT_CANCELED=0,RESULT_OK=-1;
public String packageName;public Intent launched;public int requestCode;public boolean fail;
public Activity(String p){packageName=p;}public String getPackageName(){return packageName;}
public ComponentName getComponentName(){return new ComponentName(packageName,"WeatherActivity");}
public android.content.pm.PackageManager getPackageManager(){return null;}
public void startActivity(Intent i){if(fail)throw new SecurityException("denied");launched=i;requestCode=-1;}
public void startActivityForResult(Intent i,int c){if(fail)throw new SecurityException("denied");launched=i;requestCode=c;}
public void finish(){} }
''',
    'android/content/ComponentName.java': '''package android.content;public class ComponentName {
private final String p,c;public ComponentName(String p,String c){this.p=p;this.c=c;}
public String getPackageName(){return p;}public String getClassName(){return c;}}''',
    'android/net/Uri.java': '''package android.net;public class Uri {
private final String s;private Uri(String s){this.s=s;}public static Uri parse(String s){return new Uri(s);}
public static Uri fromParts(String scheme,String part,String fragment){return parse(scheme+":"+part+(fragment==null?"":"#"+fragment));}
public String getScheme(){int i=s.indexOf(':');return i<0?null:s.substring(0,i);}
public String getHost(){try{return java.net.URI.create(s).getHost();}catch(Exception e){return null;}}
public String toString(){return s;}}''',
    'android/os/Bundle.java': '''package android.os;import java.util.*;public class Bundle {
private final Map<String,Object> values=new LinkedHashMap<>();
public int[] getIntArray(String k){return (int[])values.get(k);}public void putIntArray(String k,int[] v){values.put(k,v);}
public int getInt(String k,int d){Object v=values.get(k);return v==null?d:(Integer)v;}
public void putInt(String k,int v){values.put(k,v);}public Object get(String k){return values.get(k);}
public void put(String k,Object v){values.put(k,v);}public Set<String> keySet(){return values.keySet();}}''',
    'android/content/Intent.java': '''package android.content;import java.util.*;import android.net.Uri;import android.os.Bundle;
public class Intent {
public static final String ACTION_VIEW="android.intent.action.VIEW",CATEGORY_BROWSABLE="android.intent.category.BROWSABLE";
public static final int FLAG_ACTIVITY_NEW_TASK=0x10000000;
private String action,pkg;private Uri data;private ComponentName component;private int flags;
private final Bundle extras=new Bundle();private final Set<String> categories=new LinkedHashSet<>();
public Intent(){}public Intent(String a){action=a;}public Intent(String a,Uri d){action=a;data=d;}
public Intent(Context c,Class<?> cls){component=new ComponentName("host",cls.getName());}
public Intent setAction(String s){action=s;return this;}public String getAction(){return action;}
public Intent setData(Uri d){data=d;return this;}public Uri getData(){return data;}
public String getDataString(){return data==null?null:data.toString();}
public Intent setPackage(String p){pkg=p;return this;}public String getPackage(){return pkg;}
public Intent setComponent(ComponentName c){component=c;return this;}public ComponentName getComponent(){return component;}
public Intent setFlags(int f){flags=f;return this;}public int getFlags(){return flags;}
public Intent addCategory(String c){categories.add(c);return this;}public Set<String> getCategories(){return categories;}
public Bundle getExtras(){return extras;}public String getStringExtra(String k){return (String)extras.get(k);}
public Intent putExtra(String k,String v){extras.put(k,v);return this;}
public Intent putExtra(String k,int v){extras.put(k,v);return this;}
public Intent putExtra(String k,double v){extras.put(k,v);return this;}
public Intent putExtra(String k,boolean v){extras.put(k,v);return this;}
public Intent putExtra(String k,int[] v){extras.put(k,v);return this;}
public Intent putExtra(String k,double[] v){extras.put(k,v);return this;}
public Intent putExtra(String k,boolean[] v){extras.put(k,v);return this;}
public Intent putExtra(String k,String[] v){extras.put(k,v);return this;}
}''',
    'android/os/Looper.java': 'package android.os;public class Looper {public static Looper getMainLooper(){return new Looper();}}',
    'android/os/Handler.java': '''package android.os;import java.util.*;public class Handler {
private static final List<Runnable> tasks=new ArrayList<>();public Handler(Looper l){}
public boolean post(Runnable r){tasks.add(r);return true;}
public static void flush(){while(!tasks.isEmpty())tasks.remove(0).run();}}''',
    'android/util/SparseArray.java': '''package android.util;import java.util.*;public class SparseArray<T> {
private final TreeMap<Integer,T> items=new TreeMap<>();public void put(int k,T v){items.put(k,v);}
public T get(int k){return items.get(k);}public void remove(int k){items.remove(k);}
public int size(){return items.size();}public int keyAt(int i){return new ArrayList<>(items.keySet()).get(i);}
public T valueAt(int i){return new ArrayList<>(items.values()).get(i);}}''',
    'android/util/Log.java': '''package android.util;public class Log {
public static int i(String t,String s){return 0;}public static int w(String t,String s){return 0;}
public static int w(String t,String s,Throwable e){return 0;}}''',
    'android/widget/Toast.java': '''package android.widget;public class Toast {
public static final int LENGTH_SHORT=0,LENGTH_LONG=1;
public static Toast makeText(android.content.Context c,CharSequence s,int d){return new Toast();}public void show(){}}''',
    'org/json/JSONObject.java': r'''package org.json;import java.util.*;public class JSONObject {
public static final Object NULL=new Object();private final Map<String,Object> map;
@SuppressWarnings("unchecked") public JSONObject(Map<?,?> m){map=(Map<String,Object>)m;}
@SuppressWarnings("unchecked") public JSONObject(String s){map=(Map<String,Object>)new Parser(s).parse();}
public Object opt(String k){return map.get(k);}public Iterator<String> keys(){return map.keySet().iterator();}
public String toString(){return map.toString();}
static class Parser {final String s;int p;Parser(String s){this.s=s;}
void space(){while(p<s.length()&&Character.isWhitespace(s.charAt(p)))p++;}
Object parse(){Object v=value();space();if(p!=s.length())throw new IllegalArgumentException("trailing JSON");return v;}
Object value(){space();char c=s.charAt(p);if(c=='{'){p++;Map<String,Object> m=new LinkedHashMap<>();space();
if(s.charAt(p)=='}'){p++;return m;}for(;;){space();String k=str();space();if(s.charAt(p++)!=':')throw new IllegalArgumentException();
m.put(k,value());space();char end=s.charAt(p++);if(end=='}')return m;if(end!=',')throw new IllegalArgumentException();}}
if(c=='"')return str();if(c=='['){p++;List<Object> a=new ArrayList<>();space();if(s.charAt(p)==']'){p++;return a;}
for(;;){a.add(value());space();char end=s.charAt(p++);if(end==']')return a;if(end!=',')throw new IllegalArgumentException();}}
int start=p;while(p<s.length()&&",}] \t\r\n".indexOf(s.charAt(p))<0)p++;String v=s.substring(start,p);
if(v.equals("null"))return NULL;if(v.equals("true"))return true;if(v.equals("false"))return false;return Integer.valueOf(v);}
String str(){if(s.charAt(p++)!='"')throw new IllegalArgumentException();StringBuilder b=new StringBuilder();
while(p<s.length()){char c=s.charAt(p++);if(c=='"')return b.toString();if(c=='\\')c=s.charAt(p++);b.append(c);}throw new IllegalArgumentException();}}
}''',
}

PROBE = r'''package dev.weather.oneplus;
import android.app.Activity;import android.content.*;import android.os.*;import android.provider.Settings;
import java.lang.reflect.Method;import java.util.*;
public class PermissionSettingsCheck {
static int checks,scenarios;static final String EDIT="miui.intent.action.APP_PERM_EDITOR",CH=AndroidConsent.CHANNEL;
static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
static Map<String,Object> args(String action,String target,int code){Map<String,Object> a=new LinkedHashMap<>();
a.put("action",action);a.put("data","package:com.miui.core");a.put("packageName","com.miui.securitycenter");
a.put("component",Collections.singletonMap("packageName","com.miui.securitycenter"));
a.put("extras",Collections.singletonMap("string_extra_map",Collections.singletonMap("extra_pkgname",target)));
a.put("request_code",code);a.put("flag",Intent.FLAG_ACTIVITY_NEW_TASK);return a;}
static void local(Intent i,String pkg){check(i!=null,"settings dispatched");
check(Settings.ACTION_APPLICATION_DETAILS_SETTINGS.equals(i.getAction()),"public Android application details action");
check(("package:"+pkg).equals(i.getDataString()),"current app, never System SDK");
check(i.getPackage()==null&&i.getComponent()==null,"no Xiaomi package or component leaks");
check(i.getStringExtra("extra_pkgname")==null,"no foreign package extra leaks");}
static class Fixture {final Activity a;final List<Map<?,?>> events=new ArrayList<>();final AndroidConsent consent;final AndroidIntentBridge bridge;
Fixture(String pkg){scenarios++;a=new Activity(pkg);AndroidConsent.EventSink sink=(c,m,e)->{
check(CH.equals(c)&&"onActivityResult".equals(m),"event channel and method");events.add((Map<?,?>)e);};
consent=new AndroidConsent(a,sink);bridge=new AndroidIntentBridge(a,sink,null);}
void dispatch(String method,Object a)throws Exception {Object result=consent.handleCall(CH,method,a);
if(result==AndroidConsent.UNHANDLED)result=bridge.handleCall(CH,method,a);
check(result==null,"handler accepts request");Handler.flush();}
void event(int index,int original,int result){check(events.size()==index+1,"one callback per request");Map<?,?> e=events.get(index);
check(e.get("request_code").equals(original),"original Dart request code");check(e.get("result_code").equals(result),"result passed without inventing grant");}}
static void consent(String pkg,String action,String target)throws Exception {Fixture f=new Fixture(pkg);
Object handled=f.consent.handleCall(CH,"startActivityForResult",args(action,target,81));
check(handled==null,"Consent must consume foreign SDK settings request");Handler.flush();local(f.a.launched,pkg);
check(f.events.isEmpty(),"opening settings never implies permission grant");
check(!f.consent.onActivityResult(123,Activity.RESULT_OK,null),"unrelated result ignored");
check(f.consent.onActivityResult(f.a.requestCode,Activity.RESULT_CANCELED,null),"settings refusal callback consumed");f.event(0,81,0);
f.consent.onActivityResult(f.a.requestCode,Activity.RESULT_OK,null);check(f.events.size()==1,"duplicate result ignored");}
static void adapt(String pkg,String action)throws Exception {Fixture f=new Fixture(pkg);Intent source=AndroidIntentBridge.parse(args(action,"com.miui.core",82));
source.setComponent(new ComponentName("com.miui.securitycenter","com.miui.permcenter.permissions.PermissionsEditorActivity"));
Method method=AndroidIntentBridge.class.getDeclaredMethod("adapt",Intent.class);method.setAccessible(true);
local((Intent)method.invoke(f.bridge,source),pkg);}
static void json(String pkg)throws Exception {Fixture f=new Fixture(pkg);
String value="{\"action\":\"miui.intent.action.APP_PERM_EDITOR\",\"data\":\"package:com.miui.core\","
+"\"packageName\":\"com.miui.securitycenter\",\"component\":{\"packageName\":\"com.miui.securitycenter\","
+"\"className\":\"com.miui.permcenter.permissions.PermissionsEditorActivity\"},"
+"\"extras\":{\"string_extra_map\":{\"extra_pkgname\":\"com.miui.core\"}}}";
check(f.consent.handleCall(CH,"startActivity",value)==AndroidConsent.UNHANDLED,"JSON bypasses Map consent handler");
f.dispatch("startActivity",value);local(f.a.launched,pkg);}
static void bridgeResult(String pkg,String action)throws Exception {Fixture f=new Fixture(pkg);f.dispatch("startActivityForResult",args(action,"com.miui.core",987));
local(f.a.launched,pkg);check((f.a.launched.getFlags()&Intent.FLAG_ACTIVITY_NEW_TASK)==0,"result activity has no NEW_TASK");
int code=f.a.requestCode;check(code!=987,"uses internal Android request code");Bundle state=new Bundle();f.bridge.saveState(state);
AndroidIntentBridge restored=new AndroidIntentBridge(f.a,(c,m,e)->f.events.add((Map<?,?>)e),state);
check(restored.onActivityResult(code,Activity.RESULT_CANCELED,null),"restored callback consumed");f.event(0,987,0);
check(!restored.onActivityResult(code,Activity.RESULT_OK,null),"duplicate bridge callback ignored");}
static void notification(String pkg)throws Exception {Fixture f=new Fixture(pkg);f.dispatch("startActivity",args(Settings.ACTION_APP_NOTIFICATION_SETTINGS,"com.miui.core",0));
check(Settings.ACTION_APP_NOTIFICATION_SETTINGS.equals(f.a.launched.getAction()),"notification settings action preserved");
check(pkg.equals(f.a.launched.getStringExtra(Settings.EXTRA_APP_PACKAGE)),"notification targets current app");
check(f.a.launched.getComponent()==null&&f.a.launched.getPackage()==null,"notification vendor target removed");}
static void failures(String pkg)throws Exception {Fixture f=new Fixture(pkg);f.a.fail=true;f.dispatch("startActivityForResult",args(EDIT,"com.miui.core",91));
check(f.a.launched==null,"denied settings launch does not dispatch foreign intent");f.event(0,91,0);
Fixture busy=new Fixture(pkg);busy.dispatch("startActivityForResult",args(EDIT,"com.miui.core",92));
busy.dispatch("startActivityForResult",args(EDIT,"com.miui.core",93));busy.event(0,93,0);
busy.consent.onActivityResult(busy.a.requestCode,Activity.RESULT_CANCELED,null);busy.event(1,92,0);
Fixture destroyed=new Fixture(pkg);destroyed.dispatch("startActivityForResult",args(EDIT,"com.miui.core",94));
destroyed.consent.onDestroy();destroyed.consent.onActivityResult(destroyed.a.requestCode,Activity.RESULT_OK,null);
check(destroyed.events.isEmpty(),"destroyed consent emits no callback");}
public static void main(String[] unused)throws Exception {for(String pkg:new String[]{"dev.weather.oneplus","dev.weather.test"}){
for(String action:new String[]{EDIT,Settings.ACTION_APPLICATION_DETAILS_SETTINGS}){
for(String target:new String[]{"com.miui.core","com.miui.weather2",pkg})consent(pkg,action,target);}
for(String action:new String[]{EDIT,Settings.ACTION_APPLICATION_DETAILS_SETTINGS,"android.intent.action.MANAGE_APP_PERMISSION","android.intent.action.MANAGE_APP_PERMISSIONS"})adapt(pkg,action);
for(String action:new String[]{"android.intent.action.MANAGE_APP_PERMISSION","android.intent.action.MANAGE_APP_PERMISSIONS"})bridgeResult(pkg,action);
json(pkg);notification(pkg);failures(pkg);}
System.out.println("PASS: "+scenarios+" production permission-settings scenarios, "+checks+" assertions");}}
'''


def write_sources(base, sources):
    paths = []
    for name, body in sources.items():
        path = base / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding='utf-8')
        paths.append(str(path))
    return paths


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, default=os.environ.get('JAVA_HOME', 'C:/Program Files/Java/jdk-17'))
    parser.add_argument('--android-jar', type=Path, default=Path(os.environ.get('LOCALAPPDATA', '')) / 'Android/Sdk/platforms/android-36/android.jar')
    args = parser.parse_args()
    suffix = '.exe' if os.name == 'nt' else ''
    javac, java = [str(args.java_home / 'bin' / (name + suffix)) for name in ('javac', 'java')]
    if not args.android_jar.is_file():
        parser.error('Provide --android-jar pointing to an installed Android platform jar')
    with tempfile.TemporaryDirectory(prefix='permission-settings-') as directory:
        temporary = Path(directory)
        classes = temporary / 'classes'
        command = [javac, '-J-Dfile.encoding=UTF-8', '--release', '8', '-encoding', 'UTF-8', '-d', str(classes)]
        production = [ROOT / 'port' / name for name in ('AndroidConsent.java', 'AndroidIntentBridge.java', 'AndroidPlatformServices.java')]
        # Compile complete production handlers against the SDK; only unrelated page
        # hosts are stand-ins. Runtime fakes cover APIs exercised by these checks.
        subprocess.run(command + ['-cp', str(args.android_jar)] + write_sources(temporary / 'support', SUPPORT) + list(map(str, production)), check=True)
        classpath = os.pathsep.join((str(classes), str(args.android_jar)))
        subprocess.run(command + ['-cp', classpath] + write_sources(temporary / 'fakes', FAKES) + write_sources(temporary / 'probe', {'dev/weather/oneplus/PermissionSettingsCheck.java': PROBE}), check=True)
        subprocess.run([java, '-cp', classpath, 'dev.weather.oneplus.PermissionSettingsCheck'], check=True)


if __name__ == '__main__':
    main()
