"""Run repository-owned reminder/identity oracle and actual JobService stop cases."""
import argparse
import os
import subprocess
import tempfile
from pathlib import Path

from fixtures.alerts_android_fakes import STUBS, add_toolchain_args, toolchain
from fixtures.weather_source_fakes import SOURCE_STUBS, source_read_overload

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    add_toolchain_args(parser)
    args = parser.parse_args()
    javac, java, classpath = toolchain(parser, args)
    stubs = STUBS.copy()
    stubs.update(SOURCE_STUBS)
    stubs['dev/weather/oneplus/WeatherReadDatabase.java']=source_read_overload(stubs['dev/weather/oneplus/WeatherReadDatabase.java'])
    stubs['dev/weather/oneplus/WeatherSourceRefreshSource.java']='package dev.weather.oneplus;public class WeatherSourceRefreshSource {}'
    stubs['dev/weather/oneplus/WeatherRefresh.java']=stubs['dev/weather/oneplus/WeatherRefresh.java'].replace(
        'static Object source(', 'static boolean alternate;static Object source(').replace(
        'return real?new XiaomiWeatherRefreshSource():new Object();',
        'return alternate?new WeatherSourceRefreshSource():real?new XiaomiWeatherRefreshSource():new Object();')
    # Reuse the real production City value object while replacing its database owner.
    city = (ROOT / 'port/WeatherDatabaseBridge.java').read_text(encoding='utf8').split(
        '    static final class City {', 1)[1].rsplit('\n}', 1)[0]
    stubs['dev/weather/oneplus/WeatherDatabaseBridge.java'] =         'package dev.weather.oneplus;public class WeatherDatabaseBridge {\n'         ' static City selected;static City firstCity(android.content.Context c){return selected;}\n'         ' static City firstCity(android.content.Context c,WeatherSourceSession s){return selected;}\n'         ' static final class City {' + city + '\n}'
    (ROOT / '.build').mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='alert-retry-', dir=ROOT / '.build') as tmp:
        scratch = Path(tmp)
        files = []
        for name, body in stubs.items():
            path = scratch / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(body, encoding='utf8')
            files.append(path)
        files.append(ROOT / 'tests/AlertRegressionCheck.java')
        alternate = scratch / 'dev/weather/oneplus/AlternateReminderCheck.java'
        alternate.write_text('''package dev.weather.oneplus;import org.json.*;
public class AlternateReminderCheck {
 static int checks;static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
 public static void main(String[] args)throws Exception {
  CacheRegression.TestContext c=new CacheRegression.TestContext();
  WeatherSourceSession x=WeatherSources.current(c);
  WeatherDatabaseBridge.City city=new WeatherDatabaseBridge.City("city","City","key","1","2",false,"en_US","C");
  WeatherDatabaseBridge.selected=city;
  byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest("com.miui.weather2.warning_weather_day_and_night".getBytes("UTF-8"));
  c.getSharedPreferences("prefs_"+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash),0)
   .edit().putString("warning_weather_day_and_night","AQ==").commit();
  WeatherSources.active=new WeatherSourceSession("open_meteo",1);WeatherRefresh.alternate=true;
  check(!WeatherAlerts.needsBackgroundWeather(c),"withdrawn alternate source cannot schedule refresh");
  WeatherAlerts.onWeatherResponse(c,city,new JSONObject().put("alerts",new JSONArray()),WeatherSources.active);
  check(WeatherAlerts.state(c).getString("snapshot",null)==null,"withdrawn alternate raw is not accepted");
  check(c.getSharedPreferences("weather_real_alerts_v1",0).getString("snapshot",null)==null,"Xiaomi reminder snapshot untouched");
  WeatherAlerts.setRemoteDelivery(c,"daily",true);
  check(!WeatherAlerts.state(c).getBoolean("remote_daily",false),"MiPush callback cannot claim alternate daily delivery");
  check(!WeatherAlerts.postServerEvent(c,"abrupt","city","event","title","detail",0),"alternate cannot post Xiaomi abrupt warning");
  WeatherAlerts.onWeatherResponse(c,city,new JSONObject().put("old",true),x);
  check(!new JSONObject(WeatherAlerts.state(c).getString("snapshot","{}")).has("old"),"old-session response rejected");
  WeatherSources.transitioning=true;
  check(!WeatherAlerts.needsBackgroundWeather(c),"transition stops reminder requests");
  System.out.println("PASS: "+checks+" withdrawn-source/session rejection assertions");
 }
}''',encoding='utf8')
        files.append(alternate)
        files.extend(ROOT / 'port' / name for name in (
            'WeatherAlerts.java', 'WeatherAlertData.java', 'WeatherAlertJobService.java',
            'AndroidWeatherPreferences.java', 'XiaomiWeatherParser.java', 'WeatherWidgetData.java'))
        subprocess.run([str(javac), '--release', '8', '-encoding', 'UTF-8', '-cp', classpath,
                        '-d', str(scratch), *map(str, files)], check=True)
        subprocess.run([str(java), '-cp', str(scratch) + os.pathsep + classpath,
                        'dev.weather.oneplus.AlertRegressionCheck',
                        str(ROOT / 'tests/fixtures/xiaomi-beijing-alerts.json')], check=True)
        subprocess.run([str(java), '-cp', str(scratch) + os.pathsep + classpath,
                        'dev.weather.oneplus.AlternateReminderCheck'], check=True)


if __name__ == '__main__':
    main()
