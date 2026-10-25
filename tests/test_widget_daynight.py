"""Exercise Xiaomi icon day/night independently of the retained sky phases."""
from pathlib import Path
import argparse
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PROBE = '''package dev.weather.oneplus;
import java.time.*;
import java.util.TimeZone;
import org.json.JSONObject;
public final class DayNightProbe {
 static int assertions;
 static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
 static WeatherWidgetData data(int rise,int setting,String zone,String unit)throws Exception{
  return new WeatherWidgetData(new JSONObject().put("cityId","fixture").put("cityName","fixture")
   .put("temperature","16").put("unit",unit).put("weatherType",1)
   .put("sunriseMinute",rise).put("sunsetMinute",setting).put("zoneId",zone)
   .put("observedAtMs",1));
 }
 public static void main(String[] args)throws Exception {
  TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
  int[][] suns={{372,1036},{361,1036},{240,1320},{510,930},{0,1439},{-1,-1},{-1,1036},
               {372,-1},{0,0},{1036,372},{1440,1080},{360,1440}};
  int[] offsets={-12,-5,0,8,14};
  for(int[] sun:suns)for(int offset:offsets)for(String unit:new String[]{"C","F"}){
   String zone=String.format(java.util.Locale.ROOT,"GMT%+03d:00",offset);
   WeatherWidgetData d=data(sun[0],sun[1],zone,unit);
   int r=d.sunriseMinute,s=d.sunsetMinute;
   if(r<0||s<=r){r=360;s=1080;}
   long midnight=LocalDate.of(2026,10,22).atStartOfDay().toInstant(ZoneOffset.ofHours(offset)).toEpochMilli();
   for(int minute=0;minute<1440;minute++){
    // Oracle uses the stated interval, separately from Calendar in production.
    boolean expected=!(minute>=r&&minute<s);
    check(WeatherWidgetDayNight.night(d,midnight+minute*60000L)==expected,
          "Unexpected day/night "+zone+" "+sun[0]+"/"+sun[1]+" minute "+minute);
   }
   check(WeatherWidgetDayNight.night(d,midnight+r*60000L-1),"Before sunrise must be night");
   check(!WeatherWidgetDayNight.night(d,midnight+r*60000L),"Sunrise must be day");
   check(!WeatherWidgetDayNight.night(d,midnight+s*60000L-1),"Before sunset must be day");
   check(WeatherWidgetDayNight.night(d,midnight+s*60000L),"Sunset must be night");
  }
  long night=OffsetDateTime.parse("2026-10-22T23:00:00+08:00").toInstant().toEpochMilli();
  long day=OffsetDateTime.parse("2026-10-22T12:00:00+08:00").toInstant().toEpochMilli();
  for(String zone:new String[]{"","invalid/city-zone"}){
   WeatherWidgetData d=data(-1,-1,zone,"C");
   check(WeatherWidgetDayNight.night(d,night),"Unknown zone must use local night");
   check(!WeatherWidgetDayNight.night(d,day),"Unknown zone must use local day");
  }
  check(WeatherWidgetDayNight.night(null,night),"Missing observation uses local night appearance");
  check(!WeatherWidgetDayNight.night(null,day),"Missing observation uses local day appearance");
  long simultaneous=Instant.parse("2026-10-22T10:00:00Z").toEpochMilli();
  check(WeatherWidgetDayNight.night(data(360,1080,"GMT+08:00","C"),simultaneous),"City A sunset");
  check(!WeatherWidgetDayNight.night(data(360,1080,"GMT+00:00","C"),simultaneous),"City B still daytime");
  WeatherWidgetData d=data(372,1036,"Asia/Shanghai","C");String saved=d.toJson().toString();
  WeatherWidgetDayNight.night(d,night);
  check(saved.equals(d.toJson().toString()),"Icon decision must not mutate observations");
  check(WeatherWidgetDayNight.night(d,OffsetDateTime.parse("2026-10-22T19:12:16+08:00").toInstant().toEpochMilli()),
        "Reported evening now selects night icon");
  System.out.println("PASS: "+assertions+" icon checks; complete days, five offsets, units, sun boundaries, missing/invalid data and per-city isolation");
 }
}'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--json-jar', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, default=os.environ.get('JAVA_HOME'))
    args = parser.parse_args()
    if not args.java_home:
        parser.error('Set JAVA_HOME or pass --java-home')
    build = ROOT / '.build'
    build.mkdir(exist_ok=True)
    suffix = '.exe' if os.name == 'nt' else ''
    java = args.java_home / 'bin'
    with tempfile.TemporaryDirectory(prefix='widget-daynight-test-', dir=build) as temp:
        work = Path(temp)
        probe = work / 'DayNightProbe.java'
        probe.write_text(PROBE, encoding='utf-8')
        classes = work / 'classes'
        classes.mkdir()
        subprocess.run([str(java / ('javac' + suffix)), '-encoding', 'UTF-8', '-cp', str(args.json_jar),
                        '-d', str(classes), str(probe), str(ROOT / 'port/WeatherWidgetData.java'),
                        str(ROOT / 'port/WeatherWidgetDayNight.java')], check=True)
        subprocess.run([str(java / ('java' + suffix)), '-cp', str(classes) + os.pathsep + str(args.json_jar),
                        'dev.weather.oneplus.DayNightProbe'], check=True)


if __name__ == '__main__':
    main()
