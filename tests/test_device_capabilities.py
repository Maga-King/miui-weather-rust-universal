"""Check capability types on the actual Flutter wire format and ROM profiles."""
from pathlib import Path
import argparse
import subprocess
import tempfile
from test_android_location import SOURCES

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    args = parser.parse_args()
    fakes = {name: SOURCES[name] for name in (
        'android/content/SharedPreferences.java', 'org/json/JSONObject.java')}
    fakes['android/content/Context.java'] = '''package android.content;
public class Context {public static final int MODE_PRIVATE=0;
 public SharedPreferences getSharedPreferences(String name,int mode){return new SharedPreferences(){
  public boolean getBoolean(String key,boolean fallback){return fallback;}
  public Editor edit(){throw new UnsupportedOperationException();}};}}'''
    fakes['android/os/Build.java'] = '''package android.os;public class Build {
 public static String DEVICE="test-device",MODEL="test-model",HARDWARE="test-hardware",
 MANUFACTURER="real-maker",BRAND="real-brand",PRODUCT="real-product",TYPE="user";
 public static class VERSION {public static int SDK_INT=37;
 public static String INCREMENTAL="test-incremental",RELEASE="17";}}'''
    fakes['org/json/JSONArray.java'] = '''package org.json;
public class JSONArray {public Object opt(int index){return null;}}'''
    fakes['dev/weather/oneplus/DeviceCapabilitiesCheck.java'] = '''package dev.weather.oneplus;
import java.util.*;import android.os.Build;
public class DeviceCapabilitiesCheck {
 static int checks;
 static void check(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
 static Map<?,?> wire(Object map){return (Map<?,?>)Codec.read(Codec.buffer(Codec.encode(map,false)));}
 public static void main(String[] args){
  for(int sdk:new int[]{29,31,33,34,36,37}) for(String device:new String[]{"marble","goku","lhasa","sweet","OP5D0DL1"}) {
   Build.VERSION.SDK_INT=sdk;
   Build.DEVICE=device;Build.HARDWARE="mt6789";
   Map<?,?> profile=wire(AndroidDeviceProfile.systemProperties());
   check(profile.size()==17,"all original returned keys");
   for(String key:new String[]{"DEVICE","MODEL","VERSION.INCREMENTAL","VERSION.RELEASE",
      "HARDWARE","CHARACTERISTICS","MIUI_VERSION","CUSTOMIZED_REGION","OAID"})
    check(profile.get(key) instanceof String,"string type: "+key);
   for(String key:new String[]{"SDK_INT","MULTI_DISPLAY_TYPE","MULTI_DISPLAY_TYPE_OLD"})
    check(profile.get(key) instanceof Integer,"integer type: "+key);
   for(String key:new String[]{"SUPPORTS_BACKGROUND_BLUR","IS_MIUI","IS_INTERNATIONAL_BUILD","IS_ALPHA_BUILD","IS_DEVELOPMENT_VERSION"})
    check(profile.get(key) instanceof Boolean,"boolean type: "+key);
   check(profile.get("SDK_INT").equals(sdk),"actual API level");
   check(profile.get("IS_MIUI").equals(true),"requested Xiaomi profile");
   check(profile.get("SUPPORTS_BACKGROUND_BLUR").equals(true),"app blur capability");
   check(profile.get("MULTI_DISPLAY_TYPE").equals(0),"normal phone default");
   check(profile.get("IS_INTERNATIONAL_BUILD").equals(false),"preserve domestic data path");
   check(profile.get("OAID").equals(""),"no fabricated advertising ID");
   check(profile.get("DEVICE").equals("xuanyuan"),"avoid original device downgrades");
   check(profile.get("MODEL").equals("25010PN30C"),"China flagship model");
   check(profile.get("HARDWARE").equals("qcom"),"avoid low hardware override");
   check(profile.get("CHARACTERISTICS").equals("nosdcard"),"non-tablet profile");
   check(profile.get("MULTI_DISPLAY_TYPE_OLD").equals(0),"non-fold legacy profile");
   check(profile.get("CUSTOMIZED_REGION").equals("CN"),"requested China region");
  }
  Map<?,?> identity=wire(AndroidDeviceProfile.handleCall(null,"com.xiaomi.hyperos/device_info","getDeviceInfo",null));
  check(identity.size()==5,"identity field count");
  for(Map.Entry<?,?> e:identity.entrySet())check(e.getKey() instanceof String&&e.getValue() instanceof String,"identity string cast");
  check(!identity.containsKey("SUPPORTS_BACKGROUND_BLUR"),"separate capability channel");
  check(identity.get("device").equals("xuanyuan"),"consistent identity channels");
  check(identity.get("model").equals("25010PN30C"),"consistent model channels");
  for(String key:new String[]{"ro.miui.region","ro.miui.customized.region"}) {
   Map<String,Object> arguments=new HashMap<>();arguments.put("key",key);
   check(AndroidDeviceProfile.handleCall(null,"com.android.os.system.properties.method.channel","getString",arguments).equals("CN"),"China individual property");
  }
  Map<?,?> levels=wire(AndroidDeviceProfile.handleCall(null,"com.miui.performance.devicelevelutils.method.channel","getDeviceLevel",null));
  for(Object level:levels.values())check(level.equals(3),"highest raw tier");
  check(AndroidDeviceProfile.handleCall(null,"weather_method_channel","get_device_com_level",null).equals(6),"highest weather tier");
  Map<?,?> glass=wire(AndroidDeviceProfile.handleCall(null,"com.xiaomi.hyperos/hyper_material","getGlassCapability",null));
  check(glass.get("isSupportGlass").equals(true)&&glass.get("isSupportMaterial").equals(true),"glass and material");
  check(AndroidDeviceProfile.handleCall(null,"unknown","getAll",null)==AndroidDeviceProfile.UNHANDLED,"unknown route unchanged");
  System.out.println("PASS: "+checks+" capability checks including Flutter codec and Android 10-17 profiles");
 }
}'''
    with tempfile.TemporaryDirectory(prefix='weather-capabilities-') as folder:
        work = Path(folder)
        sources = []
        for name, contents in fakes.items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(contents, encoding='utf-8')
            sources.append(path)
        sources.extend(ROOT / 'port' / name for name in (
            'AndroidDeviceProfile.java', 'WeatherAppearancePreferences.java', 'Codec.java'))
        subprocess.run([str(args.java_home / 'bin/javac.exe'), '--release', '8',
            '-encoding', 'UTF-8', '-d', str(work / 'classes'), *map(str,sources)], check=True)
        subprocess.run([str(args.java_home / 'bin/java.exe'), '-cp', str(work / 'classes'),
            'dev.weather.oneplus.DeviceCapabilitiesCheck'], check=True)

if __name__ == '__main__':
    main()
