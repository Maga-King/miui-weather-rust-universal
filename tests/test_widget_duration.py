"""Compare the production Xiaomi sky phases with an extracted original MAML Var.

Uses the original expression as the oracle, including sub-minute boundaries.
No Android app is installed and no user weather data is read or changed.
"""
from pathlib import Path
import argparse
import ast
from datetime import datetime, timedelta, timezone
import os
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]

PROBE = '''package dev.weather.oneplus;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
public final class DurationProbe {
 public static void main(String[] args) throws Exception {
  BufferedReader lines=new BufferedReader(new InputStreamReader(System.in));
  String line, previous=""; WeatherWidgetData data=null;
  while((line=lines.readLine())!=null) {
   String[] p=line.split(","); String identity=p[0]+","+p[1]+","+p[2];
   if(!identity.equals(previous)) {
    data=new WeatherWidgetData(new JSONObject().put("cityId","fixture")
     .put("cityName","fixture").put("temperature","16").put("unit","C")
     .put("weatherType",1).put("sunriseMinute",Integer.parseInt(p[0]))
     .put("sunsetMinute",Integer.parseInt(p[1])).put("zoneId",p[2]));
    previous=identity;
   }
   System.out.println(WeatherWidgetRenderer.duration(data,Long.parseLong(p[3])));
  }
 }
}'''


def original_expression(manifest):
    variables = ET.parse(manifest).iter('Var')
    node = next(v for v in variables if v.get('name', '').startswith('current_duration_new_')
                and v.get('expression', '').startswith('ifelse('))
    suffix = node.get('name').removeprefix('current_duration_new')
    expression = re.sub(r'#(\w+)', r'\1', node.get('expression'))
    expression = expression.replace('}=', '>=').replace('{=', '<=')
    expression = expression.replace('}', '>').replace('{', '<').replace('**', '*')
    tree = ast.parse(expression, mode='eval')
    allowed = (ast.Expression, ast.Call, ast.Name, ast.Load, ast.Constant, ast.Compare,
               ast.BinOp, ast.Add, ast.Sub, ast.Mult, ast.Div, ast.GtE, ast.Gt, ast.Lt, ast.LtE)
    assert all(isinstance(n, allowed) for n in ast.walk(tree)), 'Unexpected MAML syntax'
    assert all(isinstance(n.func, ast.Name) and n.func.id == 'ifelse'
               for n in ast.walk(tree) if isinstance(n, ast.Call)), 'Unexpected MAML function'
    code = compile(tree, str(manifest), 'eval')

    def ifelse(*values):
        for index in range(0, len(values) - 1, 2):
            if values[index]:
                return values[index + 1]
        return values[-1]

    def evaluate(rise, setting, elapsed):
        r, s = rise * 60000, setting * 60000
        night = r - (s - 86400000)
        values = {'sunrise_time': r, 'sunset_time': s, 'H12': (s - r) / 12,
                  'H6': (s - r) / 6, 'h12': night / 12, 'h36': night / 36}
        scope = {name + suffix: value for name, value in values.items()}
        scope.update(time_sys=elapsed, ifelse=ifelse)
        return int(eval(code, {'__builtins__': {}}, scope))

    return evaluate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--maml-manifest', type=Path, required=True)
    parser.add_argument('--json-jar', type=Path, required=True)
    parser.add_argument('--android-jar', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, default=os.environ.get('JAVA_HOME'))
    args = parser.parse_args()
    if not args.java_home:
        parser.error('Set JAVA_HOME or pass --java-home')
    original = original_expression(args.maml_manifest)
    build = ROOT / '.build'
    build.mkdir(exist_ok=True)
    suffix = '.exe' if os.name == 'nt' else ''
    java = args.java_home / 'bin'
    with tempfile.TemporaryDirectory(prefix='widget-duration-test-', dir=build) as temp:
        work = Path(temp)
        probe = work / 'DurationProbe.java'
        probe.write_text(PROBE, encoding='utf-8')
        classes = work / 'classes'
        classes.mkdir()
        libraries = os.pathsep.join(map(str, (args.json_jar, args.android_jar)))
        sources = [ROOT / 'port' / f'{name}.java' for name in
                   ('WeatherWidgetRenderer', 'WeatherWidgetFonts', 'WeatherWidgetData', 'WeatherWidgetDayNight')]
        subprocess.run([str(java / ('javac' + suffix)), '-encoding', 'UTF-8', '-classpath',
                        libraries, '-d', str(classes), str(probe), *map(str, sources)], check=True)
        inputs, expected, cases = [], [], []
        fixtures = [(372, 1036, 8), (361, 1036, 8), (360, 1080, 0),
                    (240, 1320, -12), (510, 930, 14), (371, 1037, 8)]
        for rise, setting, offset in fixtures:
            midnight = datetime(2026, 10, 22, tzinfo=timezone(timedelta(hours=offset)))
            start = int(midnight.timestamp() * 1000)
            zone = f'GMT{offset:+03d}:00'
            samples = set(range(0, 86400000, 60000))
            # Find boundaries from the original expression, then examine each
            # integer millisecond around them. This does not copy port formulas.
            for minute in range(1440):
                low, high = minute * 60000, (minute + 1) * 60000
                if original(rise, setting, low) == original(rise, setting, high - 1):
                    continue
                initial = original(rise, setting, low)
                while low < high:
                    middle = (low + high) // 2
                    if original(rise, setting, middle) == initial:
                        low = middle + 1
                    else:
                        high = middle
                samples.update(t for t in range(low - 2, low + 3) if 0 <= t < 86400000)
            samples.update([19 * 3600000 + 12 * 60000 + 16000,
                            19 * 3600000 + 25 * 60000 + 19999,
                            19 * 3600000 + 25 * 60000 + 20000,
                            19 * 3600000 + 25 * 60000 + 20001])
            for elapsed in sorted(samples):
                inputs.append(f'{rise},{setting},{zone},{start + elapsed}')
                expected.append(original(rise, setting, elapsed))
                cases.append((rise, setting, zone, elapsed))
        result = subprocess.run([str(java / ('java' + suffix)), '-cp', str(classes) + os.pathsep
                                 + libraries, 'dev.weather.oneplus.DurationProbe'],
                                input='\n'.join(inputs) + '\n', capture_output=True, text=True, check=True)
        actual = list(map(int, result.stdout.split()))
        assert len(actual) == len(expected), result.stderr
        mismatches = [(cases[i], value, expected[i]) for i, value in enumerate(actual)
                      if value != expected[i]]
        assert not mismatches, f'Production differs from original MAML: {mismatches[:10]}'
        assert original(372, 1036, 19 * 3600000 + 12 * 60000 + 16000) == 15
        assert original(372, 1036, 19 * 3600000 + 25 * 60000 + 20000) == 0
        print(f'PASS: {len(cases)} original MAML comparisons; complete days, six sun/timezone '
              'fixtures and millisecond sky phase boundaries; icon policy is separate')


if __name__ == '__main__':
    main()
