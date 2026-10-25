"""Exercise production widget snapshots/parsers on JVM with counting Android fakes.
Requires JDK 17 and --json-jar pointing to the standard org.json library.
"""
from pathlib import Path
import argparse
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
 'android/content/Context.java': '''package android.content;
public class Context {
 public static java.io.File database;
 public java.io.File getDatabasePath(String name){return database;}
 public android.content.res.Resources getResources(){return new android.content.res.Resources();}
 public SharedPreferences getSharedPreferences(String name,int mode){return new SharedPreferences();}
 public static final int MODE_PRIVATE=0;
}''',
 'android/content/SharedPreferences.java': '''package android.content;
public class SharedPreferences { public String getString(String k,String d){return d;} }''',
 'android/content/res/Resources.java': '''package android.content.res;
public class Resources { public Configuration getConfiguration(){return new Configuration();} }''',
 'android/content/res/Configuration.java': '''package android.content.res;
public class Configuration { public android.os.LocaleList getLocales(){return new android.os.LocaleList();} }''',
 'android/os/LocaleList.java': '''package android.os;
public class LocaleList { public java.util.Locale get(int i){return java.util.Locale.US;} }''',
 'android/util/Log.java': '''package android.util;
public class Log {public static int w(String tag,String message){return 0;}}''',
 'android/database/Cursor.java': '''package android.database;
public class Cursor implements AutoCloseable {
 private final String[][] rows; private int at=-1;
 public Cursor(String[][] rows){this.rows=rows;}
 public boolean moveToNext(){return ++at<rows.length;}
 public boolean moveToFirst(){at=0;return rows.length>0;}
 public boolean isNull(int i){return rows[at][i]==null;}
 public String getString(int i){return rows[at][i];}
 public int getInt(int i){return Integer.parseInt(rows[at][i]);}
 public void close(){}
}''',
 'android/database/sqlite/SQLiteDatabase.java': '''package android.database.sqlite;
import android.database.Cursor; import java.util.*; import org.json.JSONObject;
public class SQLiteDatabase implements AutoCloseable {
 public static final int OPEN_READONLY=1,NO_LOCALIZED_COLLATORS=2;
 public static int opens,queries; public static String[][] cities=new String[0][];
 public static final Map<String,JSONObject> weather=new HashMap<>();
 public static SQLiteDatabase openDatabase(String path,Object factory,int flags){opens++;return new SQLiteDatabase();}
 public Cursor rawQuery(String sql,String[] args){queries++;
  if(sql.startsWith("SELECT posID"))return new Cursor(cities);
  if(sql.startsWith("SELECT data1")){JSONObject raw=weather.get(args[0]);
   return new Cursor(raw==null?new String[0][]:new String[][]{{raw.toString(),null}});}
  throw new AssertionError("Unexpected query: "+sql);
 }
 public void close(){}
}''',
 'dev/weather/oneplus/WeatherReadDatabase.java': '''package dev.weather.oneplus;
public class WeatherReadDatabase implements AutoCloseable {
 private final android.database.sqlite.SQLiteDatabase db;
 private WeatherReadDatabase(android.content.Context c){db=android.database.sqlite.SQLiteDatabase.openDatabase(c.getDatabasePath("weather.db").getAbsolutePath(),null,1);}
 static WeatherReadDatabase open(android.content.Context c){return new WeatherReadDatabase(c);}
 android.database.Cursor rawQuery(String sql,String[] args){return db.rawQuery(sql,args);}
 public void close(){db.close();}
}''',
 'dev/weather/oneplus/WeatherDatabaseBridge.java': '''package dev.weather.oneplus;
public class WeatherDatabaseBridge {
 static String unit="C"; static String temperatureUnit(android.content.Context context){return unit;}
 static final class City { final String id;
  City(String id,String name,String extra,String lat,String lon,boolean located,String locale,String unit){this.id=id;}
 }
}''',
 'dev/weather/oneplus/WeatherWidgetCityCache.java': '''package dev.weather.oneplus;
public class WeatherWidgetCityCache {
 static final java.util.Map<String,org.json.JSONObject> data=new java.util.HashMap<>();
 static int reads;
 static org.json.JSONObject raw(android.content.Context c,WeatherDatabaseBridge.City city){reads++;return data.get(city.id);}
}''',
 'dev/weather/oneplus/SnapshotTest.java': r'''package dev.weather.oneplus;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.json.*;
public class SnapshotTest {
 static int assertions;
 static void check(boolean ok,String why){assertions++;if(!ok)throw new AssertionError(why);}
 static JSONObject weather(int temperature,int humidity,int hoursLater){
  String time=OffsetDateTime.now(ZoneOffset.UTC).plusHours(hoursLater).withNano(0).toString();
  String forecast=OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).withNano(0).toString();
  return new JSONObject().put("current",new JSONObject().put("pubTime",time).put("weather",0)
   .put("temperature",new JSONObject().put("unit","C").put("value",""+temperature))
   .put("humidity",new JSONObject().put("unit","%").put("value",""+humidity)))
   .put("forecastHourly",new JSONObject()
    .put("temperature",new JSONObject().put("pubTime",forecast).put("unit","C").put("value",new JSONArray("[20,21,22]")))
    .put("weather",new JSONObject().put("pubTime",forecast).put("value",new JSONArray("[0,1,2]"))));
 }
 public static void main(String[] args)throws Exception {
  Context context=new Context();Context.database=new java.io.File(args[0]);
  SQLiteDatabase.cities=new String[][]{
   {"a","City A","key-a","1","2","0"},
   {"b","City B","key-b","3","4","1"},
   {"empty","No data","key-c","5","6","0"}};
  SQLiteDatabase.weather.put("a",weather(20,50,0));
  SQLiteDatabase.weather.put("b",weather(30,70,0));
  WeatherWidgetExtendedData.Snapshot first=WeatherWidgetExtendedData.snapshot(context,null);
  check(SQLiteDatabase.opens==1,"one database open for all cities");
  check(SQLiteDatabase.queries==4,"one selected-city scan plus one raw query per saved city");
  check(WeatherWidgetCityCache.reads==3,"identity-resolved city cache read once per city");
  check(first.defaultCity(false).equals("a"),"homepage ordering");
  check(first.defaultCity(true).equals("b"),"located default");
  check(first.city("missing")==null&&first.city("empty")==null,"missing city does not fall back");
  check(first.city("a").temperature.equals("20")&&first.city("b").temperature.equals("30"),"per-city values");
  JSONObject a=first.details(first.city("a"));
  check(a.getString("humidity").equals("50%"),"A details");
  check(a.getJSONArray("hourly").length()==3,"real hourly data");
  check(a.getString("uvIndex").isEmpty(),"missing UV does not fabricate zero");
  JSONObject uvRaw=weather(20,50,0);
  for(String invalid:new String[]{"","null","NaN","Infinity","-1","unknown"}) {
   uvRaw.getJSONObject("current").put("uvIndex",invalid);
   check(WeatherWidgetExtendedData.parse(uvRaw,"C","zh_CN",System.currentTimeMillis()).getString("uvIndex").isEmpty(),"invalid UV ignored: "+invalid);
  }
  for(String valid:new String[]{"0","3","7.5"}) {
   uvRaw.getJSONObject("current").put("uvIndex",valid);
   check(WeatherWidgetExtendedData.parse(uvRaw,"C","zh_CN",System.currentTimeMillis()).getString("uvIndex").equals(valid),"real UV preserved: "+valid);
  }
  check(first.details(null).getString("uvIndex").isEmpty(),"missing city cannot show UV");
  a.put("panel",3).put("secondCityId","b");
  for(int i=0;i<30;i++){
   JSONObject same=first.details(first.city("a"));
   check(!same.has("panel")&&!same.has("secondCityId"),"instance options stay isolated");
   check(same.getJSONArray("hourly")==a.getJSONArray("hourly"),"forecasts parsed once per city per batch");
  }
  check(SQLiteDatabase.queries==4&&SQLiteDatabase.opens==1,"thirty widgets cause no extra storage reads");
  check(first.details(first.city("b")).getString("humidity").equals("70%"),"no cross-city details");
  check(first.details(null).getJSONArray("hourly").length()==0,"no invented missing forecast");
  SQLiteDatabase.weather.put("a",weather(22,55,1));
  WeatherWidgetExtendedData.Snapshot next=WeatherWidgetExtendedData.snapshot(context,null);
  check(next.city("a").temperature.equals("22"),"next refresh re-reads changed data");
  check(first.city("a").temperature.equals("20"),"active batch remains coherent");
  check(next.details(next.city("a")).getString("humidity").equals("55%"),"next batch has fresh details");
  WeatherWidgetData newer=XiaomiWeatherParser.parse(weather(25,65,2),"a","City A","C","en_US");
  WeatherWidgetExtendedData.Snapshot fallback=WeatherWidgetExtendedData.snapshot(context,newer);
  check(fallback.city("a").temperature.equals("25"),"newer selected observation preserved");
  check(fallback.city("b").temperature.equals("30"),"fallback never contaminates another city");
  WeatherWidgetCityCache.data.put("b",weather(32,80,3));
  WeatherWidgetExtendedData.Snapshot network=WeatherWidgetExtendedData.snapshot(context,null);
  check(network.city("b").temperature.equals("32"),"newer per-city background data wins");
  check(network.details(network.city("b")).getString("humidity").equals("80%"),"observation and details use same raw data");
  WeatherDatabaseBridge.unit="F";
  WeatherWidgetExtendedData.Snapshot fahrenheit=WeatherWidgetExtendedData.snapshot(context,newer);
  check(fahrenheit.city("a").temperature.equals("72"),"display unit conversion preserved");
  check(fahrenheit.details(fahrenheit.city("a")).getJSONArray("hourly").getJSONObject(0).getString("temp").equals("68"),"hourly unit conversion preserved");
  check(!fahrenheit.city("a").unit.equals(newer.unit),"fallback with different units cannot replace data");
  Context.database=new java.io.File(args[0]+".missing");
  int before=SQLiteDatabase.opens;
  WeatherWidgetExtendedData.Snapshot absent=WeatherWidgetExtendedData.snapshot(context,newer);
  check(absent.cities.length()==0&&absent.city("a")==null,"deleted database cannot expose stale saved cities");
  check(SQLiteDatabase.opens==before,"missing database is not opened or created");
  System.out.println("PASS: "+assertions+" assertions; 30 same-city widgets share one city scan, with per-batch freshness and isolated options");
 }
}''',
}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--json-jar', type=Path, required=True)
    args = parser.parse_args()
    java = Path(os.environ['JAVA_HOME']) / 'bin'
    build = ROOT / '.build'
    build.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='widget-snapshot-test-', dir=build) as temp:
        work = Path(temp)
        files = []
        for name, source in SOURCES.items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source, encoding='utf-8')
            files.append(str(path))
        for name in ('WeatherWidgetExtendedData', 'WeatherWidgetData', 'XiaomiWeatherParser'):
            files.append(str(ROOT / 'port' / f'{name}.java'))
        classes = work / 'classes'
        subprocess.run([str(java / 'javac'), '-encoding', 'UTF-8', '-cp', str(args.json_jar),
                        '-d', str(classes), *files], check=True)
        database = work / 'weather.db'
        database.touch()
        subprocess.run([str(java / 'java'), '-cp', os.pathsep.join((str(classes), str(args.json_jar))),
                        'dev.weather.oneplus.SnapshotTest', str(database)], check=True)

if __name__ == '__main__':
    main()
