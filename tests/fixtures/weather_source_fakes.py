"""Small source-session boundary fakes for existing offline Android oracles."""


SOURCE_STUBS = {
    'dev/weather/oneplus/WeatherSourceSession.java': '''package dev.weather.oneplus;
public final class WeatherSourceSession {
 public final String sourceId,databaseName;public final long epoch;
 public WeatherSourceSession(String id,long epoch){sourceId=id;this.epoch=epoch;databaseName=id.equals("xiaomi")?"weather.db":"openmet.db";}
 public boolean xiaomi(){return sourceId.equals("xiaomi");}
 public boolean same(WeatherSourceSession s){return s!=null&&sourceId.equals(s.sourceId)&&epoch==s.epoch;}
}''',
    'dev/weather/oneplus/WeatherSources.java': '''package dev.weather.oneplus;
public final class WeatherSources {
 public static final Object LOCK=new Object();
 public static WeatherSourceSession active=new WeatherSourceSession("xiaomi",0);public static boolean transitioning;
 public static WeatherSourceSession current(android.content.Context c){return active;}
 public static boolean isCurrent(android.content.Context c,WeatherSourceSession s){return !transitioning&&active.same(s);}
 public static java.io.File database(android.content.Context c,String source){return c.getDatabasePath(source.equals("xiaomi")?"weather.db":"openmet.db");}
 public static String scopedName(String name,WeatherSourceSession s){return s.xiaomi()?name:name+"_"+s.sourceId;}
}''',
}


def source_read_overload(body):
    """The fake storage remains pinned by its test; accept the production session API."""
    offset = body.rfind('}')
    return body[:offset] + '''
 static WeatherReadDatabase open(android.content.Context c,WeatherSourceSession session){return open(c);}
''' + body[offset:]


def source_raw_overload(body):
    offset = body.rfind('}')
    return body[:offset] + '''
 static org.json.JSONObject raw(android.content.Context c,WeatherDatabaseBridge.City city,WeatherSourceSession session){return raw(c,city);}
''' + body[offset:]


def source_city_identity(body):
    offset = body.rfind('}', 0, body.rfind('}'))
    return body[:offset] + '\n String cacheIdentity(){return "fixture-identity";}\n' + body[offset:]


ALERT_STATE_STUB = '''package dev.weather.oneplus;public class WeatherAlerts {
 static android.content.SharedPreferences state(android.content.Context c,WeatherSourceSession s){
  return c.getSharedPreferences(WeatherSources.scopedName("weather_real_alerts_v1",s),0);
 }
}'''
