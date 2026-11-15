"""Verify Oxygen sky source provenance, original periods, and per-city isolation on the JVM."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]

PROBE = '''package dev.weather.oneplus;
import java.time.*;
import java.util.TimeZone;
import org.json.JSONObject;
public final class OxygenBackgroundLogicProbe {
 static int assertions;
 static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
 static WeatherWidgetData data(int type,int rise,int setting,String zone)throws Exception {
  return new WeatherWidgetData(new JSONObject().put("cityId",zone).put("cityName",zone)
   .put("temperature","26").put("unit","C").put("weatherType",type)
   .put("sunriseMinute",rise).put("sunsetMinute",setting).put("zoneId",zone));
 }
 public static void main(String[] args)throws Exception {
  TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
  int[][] suns={{372,1036},{240,1320},{510,930},{0,1439},{0,0},{1036,372},{360,420},{-1,-1},{372,-1}};
  for(int[] sun:suns)for(int offset:new int[]{-12,-5,0,8,14}){
   String zone=String.format(java.util.Locale.ROOT,"GMT%+03d:00",offset);
   WeatherWidgetData data=data(0,sun[0],sun[1],zone);
   int r=data.sunriseMinute,s=data.sunsetMinute;if(r<0||s<0){r=360;s=1080;}
   long midnight=LocalDate.of(2026,10,25).atStartOfDay().toInstant(ZoneOffset.ofHours(offset)).toEpochMilli();
   for(int minute=0;minute<1440;minute++){
    int expected=OriginalOxygenPeriod.getPeriod(minute,r,s);
    check(WeatherOxygenBackground.period(data,midnight+minute*60000L)==expected,
      "Period mismatch "+zone+" "+r+"/"+s+" @ "+minute);
   }
  }
  long simultaneous=Instant.parse("2026-10-25T10:00:00Z").toEpochMilli();
  WeatherWidgetData a=data(0,360,1080,"GMT+08:00"),b=data(1,360,1080,"GMT+00:00");
  check(WeatherOxygenBackground.period(a,simultaneous)==5,"City A evening");
  check(WeatherOxygenBackground.period(b,simultaneous)==1,"City B noon");
  String aBefore=a.toJson().toString(),bBefore=b.toJson().toString();
  WeatherOxygenBackground.period(a,simultaneous);WeatherOxygenBackground.period(b,simultaneous);
  check(aBefore.equals(a.toJson().toString())&&bBefore.equals(b.toJson().toString()),"No observation mutation");
  JSONObject details=new JSONObject().put("humidity","70%").put("windPower",2).put("pm25",50).put("countryCode","CN");
  check(WeatherOxygenBackground.unusual(b,details,0),"Verified golden sunrise conditions");
  check(WeatherOxygenBackground.unusual(b,details,2),"Verified sunset glow conditions");
  check(!WeatherOxygenBackground.unusual(a,details,0),"Unusual effects remain cloudy-only");
  check(!WeatherOxygenBackground.unusual(b,details,1),"Noon cannot become golden sunrise");
  details.remove("windPower");details.put("wind","2 km/h");
  check(!WeatherOxygenBackground.unusual(b,details,0),"Wind speed cannot substitute for wind force");
  details.put("windPower",2);details.remove("pm25");details.put("aqi",50);
  check(!WeatherOxygenBackground.unusual(b,details,0),"AQI cannot substitute for PM2.5");
  check(WeatherOxygenBackground.cardSize(10)==1&&WeatherOxygenBackground.cardSize(11)==2
    &&WeatherOxygenBackground.cardSize(12)==3&&WeatherOxygenBackground.cardSize(8)==0,"Original bean sizes");
  System.out.println("PASS: "+assertions+" original period / weather / per-city checks");
 }
}'''


def extract_method(source, name):
    match = re.search(r'(?:private|public) static [^;{]+\b' + re.escape(name) + r'\([^)]*\) \{', source)
    if not match:
        raise RuntimeError(f'Missing original method {name}')
    start = match.start()
    opening = source.index('{', start)
    depth, end = 1, opening + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end].replace('private static', 'public static')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--analysis', type=Path, required=True)
    parser.add_argument('--coloros-analysis', type=Path, required=True)
    parser.add_argument('--json-jar', type=Path, required=True)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    args = parser.parse_args()
    assets = ROOT / 'port/assets/oxygen_widget'
    payload = json.loads((assets / 'background_rules.json').read_text(encoding='utf-8'))
    for relative, expected in payload['sources'].items():
        assert hashlib.sha256((args.analysis / relative).read_bytes()).hexdigest() == expected, relative
    inventory = json.loads((assets / 'source_manifest.json').read_text(encoding='utf-8'))
    for entry in inventory['resources']:
        target = ROOT / 'port' / entry['destination']
        assert target.stat().st_size == entry['bytes'], target
        assert hashlib.sha256(target.read_bytes()).hexdigest() == entry['sha256'], target
        original = args.analysis / 'decoded' / entry['source']
        assert target.read_bytes() == original.read_bytes(), target
    arrays = ET.parse(args.analysis / 'decoded/res/values/arrays.xml').getroot()
    # ColorOS and Oxygen same original color resources, independently decoded APKs.
    other = ET.parse(args.coloros_analysis / 'res/values/arrays.xml').getroot()
    other_arrays = {node.attrib.get('name'): [(v.text or '').lower() for v in node] for node in other}
    for node in arrays:
        if node.attrib.get('name', '').endswith('_colors'):
            assert [v.text.lower() for v in node] == other_arrays[node.attrib['name']], node.attrib['name']
    rules = payload['rules']
    assert len(rules) == 364
    for key, rule in rules.items():
        assert len(rule['colors']) == len(rule['offsets']) >= 4, key
        assert rule['offsets'][0] == 0 and rule['offsets'][-1] == 1, key
        assert rule['offsets'] == sorted(rule['offsets']), key
        assert set(rule['images']) == {'0', '1', '2', '3'}, key
        for image in rule['images'].values():
            assert (assets / 'backgrounds' / image['asset']).is_file(), key
    assert rules['0:1:false']['colors'] == [-7683601, -10643233, -11894066, -12882497, -14658632]
    assert rules['1:0:true']['offsets'] == [0, .1959, .3637, .5781, 1]
    assert rules['1:259:false']['images']['1']['alpha'] == 128
    assert rules['2:259:false']['images']['2']['alpha'] == 153
    assert rules['0:1:false']['images']['0']['mode'] == 'DST_OVER'
    assert rules['0:1:false']['images']['1']['mode'] == 'SCREEN'
    print(f'PASS: {len(rules)} original palettes / offsets, {len(rules)*4} source image rules, {len(inventory["resources"])} source resource hashes')
    period_source = args.coloros_analysis.parent / 'jadx-weather/sources/com/oplus/weather/main/utils/Period.java'
    source = period_source.read_text(encoding='utf-8')
    original = '\n'.join(extract_method(source, name) for name in
        ('between', 'getPeriod', 'getPeriodMorningTime', 'getPeriodNoonTime', 'getPeriodNightfallTime'))
    oracle = 'package dev.weather.oneplus; final class OriginalOxygenPeriod {\n' + original + '\n}'
    build = ROOT / '.build/oxygen-background-audit'
    build.mkdir(parents=True, exist_ok=True)
    java = args.java_home / 'bin'
    android = args.sdk / 'platforms/android-36/android.jar'
    classpath = os.pathsep.join(map(str, (args.json_jar, android)))
    with tempfile.TemporaryDirectory(prefix='logic-', dir=build) as tmp:
        work = Path(tmp)
        (work / 'OriginalOxygenPeriod.java').write_text(oracle, encoding='utf-8')
        (work / 'OxygenBackgroundLogicProbe.java').write_text(PROBE, encoding='utf-8')
        sources = [work / 'OriginalOxygenPeriod.java', work / 'OxygenBackgroundLogicProbe.java',
                   ROOT / 'port/WeatherOxygenBackground.java', ROOT / 'port/WeatherWidgetData.java']
        subprocess.run([str(java / 'javac.exe'), '--release', '8', '-encoding', 'UTF-8', '-cp', classpath,
                        '-d', str(work), *map(str, sources)], check=True)
        subprocess.run([str(java / 'java.exe'), '-cp', str(work) + os.pathsep + classpath,
                        'dev.weather.oneplus.OxygenBackgroundLogicProbe'], check=True)


if __name__ == '__main__':
    main()
