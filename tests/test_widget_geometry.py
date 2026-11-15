"""Check launcher-size rendering transforms and per-instance persistence with Android fakes."""
import argparse,subprocess,tempfile,os
from pathlib import Path
from fixtures.alerts_android_fakes import add_toolchain_args, toolchain
from fixtures.weather_source_fakes import SOURCE_STUBS, source_read_overload

ROOT=Path(__file__).resolve().parents[1]
PROBE='''package dev.weather.oneplus;
import android.content.Context;import android.graphics.Canvas;import org.json.JSONObject;
public class GeometryCheck {
 static int n;static void check(boolean ok,String why){n++;if(!ok)throw new AssertionError(why);}
 public static void main(String[] args) {
  Canvas canvas=new Canvas();WeatherWidgetGeometry.apply(canvas,360,180,100,100);
  check(canvas.calls==0,"disabled/default is an exact no-op");
  WeatherWidgetGeometry.apply(canvas,360,180,75,50);
  check(canvas.x==45&&canvas.y==45&&canvas.sx==.75f&&canvas.sy==.5f,"shrink is centered in launcher bounds");
  WeatherWidgetGeometry.apply(canvas,360,180,150,125);
  check(canvas.x==-90&&canvas.y==-22.5f&&canvas.sx==1.5f&&canvas.sy==1.25f,"enlargement remains centered");
  WeatherWidgetGeometry.apply(canvas,360,180,-5,Integer.MAX_VALUE);
  check(canvas.sx==.5f&&canvas.sy==1.5f,"invalid persisted values are bounded");
  Context c=new Context();
  check(!WeatherWidgetPreferences.sizeEditing(c,1)&&WeatherWidgetPreferences.widthPercent(c,1)==100&&WeatherWidgetPreferences.heightPercent(c,1)==100,"existing instances default unchanged");
  check(WeatherWidgetPreferences.save(c,1,"a","b",2,true,75,125),"save choices atomically");
  check(WeatherWidgetPreferences.widthPercent(c,1)==75&&WeatherWidgetPreferences.heightPercent(c,1)==125,"enabled instance uses saved geometry");
  check(WeatherWidgetPreferences.widthPercent(c,2)==100,"another widget is independent");
  check(WeatherWidgetPreferences.cityId(c,1).equals("a")&&WeatherWidgetPreferences.secondCityId(c,1).equals("b")&&WeatherWidgetPreferences.panel(c,1)==2,"geometry keeps city/content options");
  check(WeatherWidgetPreferences.save(c,1,"a","b",2,false,75,125),"disable geometry");
  check(WeatherWidgetPreferences.widthPercent(c,1)==100&&WeatherWidgetPreferences.savedWidthPercent(c,1)==75,"off uses default while preserving tuning");
  check(!WeatherWidgetPreferences.save(c,1,"changed","b",2,true,0,100)&&WeatherWidgetPreferences.cityId(c,1).equals("a"),"invalid geometry cannot partially overwrite city");
  WeatherWidgetPreferences.delete(c,1);
  check(!WeatherWidgetPreferences.sizeEditing(c,1)&&WeatherWidgetPreferences.savedWidthPercent(c,1)==100&&WeatherWidgetPreferences.cityId(c,1).isEmpty(),"deleted ID cannot inherit old sizing");
  System.out.println("PASS: "+n+" geometry/default/instance/persistence checks");
 }
}'''
def main():
 ap=argparse.ArgumentParser(description=__doc__);add_toolchain_args(ap);args=ap.parse_args()
 javac,java,cp=toolchain(ap,args)
 sources={}
 sources['android/content/Context.java']='''package android.content;public class Context {
 public static final int MODE_PRIVATE=0;private java.util.Map<String,dev.weather.oneplus.CacheRegression.Prefs> prefs=new java.util.HashMap<>();
 public android.content.SharedPreferences getSharedPreferences(String n,int m){return prefs.computeIfAbsent(n,k->new dev.weather.oneplus.CacheRegression.Prefs());}
 public java.io.File getDatabasePath(String n){return new java.io.File("absent");}
}'''
 sources['dev/weather/oneplus/CacheRegression.java']='''package dev.weather.oneplus;
import android.content.SharedPreferences;import java.util.*;public class CacheRegression {public static class Prefs implements SharedPreferences {
 final Map<String,Object> values=new HashMap<>();
 public Map<String,?> getAll(){return values;}public boolean contains(String k){return values.containsKey(k);}
 public String getString(String k,String d){return (String)values.getOrDefault(k,d);}public int getInt(String k,int d){return (Integer)values.getOrDefault(k,d);}
 public long getLong(String k,long d){return (Long)values.getOrDefault(k,d);}public float getFloat(String k,float d){return (Float)values.getOrDefault(k,d);}
 public boolean getBoolean(String k,boolean d){return (Boolean)values.getOrDefault(k,d);}public Set<String> getStringSet(String k,Set<String>d){return d;}
 public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){}public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){}
 public Editor edit(){return new Edit();}class Edit implements Editor {
 Map<String,Object> put=new HashMap<>();Set<String> remove=new HashSet<>();
 public Editor putString(String k,String v){put.put(k,v);return this;}public Editor putInt(String k,int v){put.put(k,v);return this;}
 public Editor putLong(String k,long v){put.put(k,v);return this;}public Editor putFloat(String k,float v){put.put(k,v);return this;}
 public Editor putBoolean(String k,boolean v){put.put(k,v);return this;}public Editor putStringSet(String k,Set<String>v){put.put(k,v);return this;}
 public Editor remove(String k){remove.add(k);return this;}public Editor clear(){values.clear();return this;}
 public boolean commit(){for(String k:remove)values.remove(k);values.putAll(put);return true;}public void apply(){commit();}
 }} }'''
 sources['android/graphics/Canvas.java']='''package android.graphics;public class Canvas {
 public int calls;public float x,y,sx,sy;public void translate(float a,float b){calls++;x=a;y=b;}public void scale(float a,float b){calls++;sx=a;sy=b;}
}'''
 sources['android/content/ComponentName.java']='package android.content;public class ComponentName {public String getClassName(){return "";}}'
 sources['android/appwidget/AppWidgetManager.java']='''package android.appwidget;public class AppWidgetManager {
 public static final int INVALID_APPWIDGET_ID=0;public static AppWidgetManager getInstance(android.content.Context c){return new AppWidgetManager();}
 public AppWidgetProviderInfo getAppWidgetInfo(int id){return null;}
}'''
 sources['android/appwidget/AppWidgetProviderInfo.java']='package android.appwidget;public class AppWidgetProviderInfo {public android.content.ComponentName provider;}'
 sources['android/database/Cursor.java']='''package android.database;public interface Cursor extends AutoCloseable {
 boolean moveToNext();boolean isNull(int i);String getString(int i);int getInt(int i);void close();}'''
 sources['dev/weather/oneplus/WeatherReadDatabase.java']='''package dev.weather.oneplus;public class WeatherReadDatabase implements AutoCloseable {
 public static WeatherReadDatabase open(android.content.Context c){throw new IllegalStateException();}
 public android.database.Cursor rawQuery(String s,String[]a){return null;}public void close(){}}'''
 sources['dev/weather/oneplus/WeatherWidgetData.java']='package dev.weather.oneplus;public class WeatherWidgetData {public WeatherWidgetData(org.json.JSONObject o){}}'
 sources['dev/weather/oneplus/WeatherWidgetExtendedData.java']='''package dev.weather.oneplus;public class WeatherWidgetExtendedData {
 public static org.json.JSONObject read(android.content.Context c,WeatherWidgetData d){return new org.json.JSONObject();}
 public static class Snapshot {public String defaultCity(boolean located){return "";}public WeatherWidgetData city(String id){return null;}}}'''
 needed=['android/content/Context.java','android/content/ComponentName.java','android/appwidget/AppWidgetManager.java','android/appwidget/AppWidgetProviderInfo.java','android/database/Cursor.java','android/graphics/Canvas.java','dev/weather/oneplus/CacheRegression.java','dev/weather/oneplus/WeatherReadDatabase.java','dev/weather/oneplus/WeatherWidgetData.java','dev/weather/oneplus/WeatherWidgetExtendedData.java']
 sources.update(SOURCE_STUBS)
 sources['dev/weather/oneplus/WeatherReadDatabase.java']=source_read_overload(sources['dev/weather/oneplus/WeatherReadDatabase.java'])
 needed.extend(SOURCE_STUBS)
 (ROOT/'.build').mkdir(exist_ok=True)
 with tempfile.TemporaryDirectory(prefix='geometry-',dir=ROOT/'.build') as tmp:
  tmp=Path(tmp);files=[]
  for name in needed:
   p=tmp/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(sources[name],encoding='utf8');files.append(p)
  p=tmp/'dev/weather/oneplus/GeometryCheck.java';p.write_text(PROBE,encoding='utf8');files.append(p)
  files.extend(ROOT/'port'/n for n in ['WeatherWidgetGeometry.java','WeatherWidgetPreferences.java'])
  subprocess.run([str(javac),'--release','8','-encoding','UTF-8','-cp',cp,'-d',str(tmp),*map(str,files)],check=True)
  subprocess.run([str(java),'-cp',str(tmp)+os.pathsep+cp,'dev.weather.oneplus.GeometryCheck'],check=True)
if __name__=='__main__':main()
