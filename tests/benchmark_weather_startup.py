"""Measure process-cold startup without clearing app data or device logs.

First Flutter frame is not full readiness; retain the animation/route logs too.
"""
import argparse
import json
import re
import statistics
import subprocess
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default=str(Path.home() / 'AppData/Local/Android/Sdk/platform-tools/adb.exe'))
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', default='dev.weather.test')
    parser.add_argument('--runs', type=int, default=3)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    adb = [args.adb, '-s', args.serial]
    activity = 'TestWeatherActivity' if args.package == 'dev.weather.test' else 'WeatherActivity'
    component = args.package + '/dev.weather.oneplus.' + activity
    records = []
    for n in range(1, args.runs + 1):
        subprocess.run(adb + ['shell', 'am', 'force-stop', args.package], check=True, capture_output=True)
        time.sleep(0.8)
        log_path = args.output / f'run-{n}.log'
        with log_path.open('wb') as log:
            logger = subprocess.Popen(adb + ['logcat', '-v', 'threadtime', '-T', '1',
                'WeatherStartup:I', 'WeatherHost:I', 'WeatherMGL:I', 'WeatherChannel:I',
                'WeatherPortProbe:I', 'WeatherWidget:I', 'WeatherFonts:I',
                'Choreographer:I', 'AndroidRuntime:E', 'flutter:I', '*:S'], stdout=log, stderr=log)
            try:
                start = time.monotonic()
                launch = subprocess.run(adb + ['shell', 'am', 'start', '-W', '-n', component],
                    capture_output=True, text=True, timeout=30)
                pid = subprocess.check_output(adb + ['shell', 'pidof', args.package], text=True).strip().split()[0]
                (args.output / f'run-{n}-launch.txt').write_text(launch.stdout + launch.stderr, encoding='utf-8')
                time.sleep(max(0, 5.0 - (time.monotonic() - start)))
                if n == 1:
                    (args.output / 'home-at-5s.png').write_bytes(subprocess.check_output(adb + ['exec-out', 'screencap', '-p']))
                subprocess.run(adb + ['shell', 'input', 'swipe', '540', '1950', '540', '900', '600'], check=True)
                time.sleep(1)
                subprocess.run(adb + ['shell', 'input', 'swipe', '540', '900', '540', '1950', '600'], check=True)
                time.sleep(1)
            finally:
                logger.terminate()
                logger.wait(timeout=10)
        lines = log_path.read_text(encoding='utf-8', errors='replace').splitlines()
        own = [x for x in lines if pid and len(x.split()) > 3 and x.split()[2] == pid]
        match = re.search(r'TotalTime: (\d+)', launch.stdout)
        record = {'run': n, 'pid': pid, 'launch': launch.returncode,
                  'am_total_ms': int(match[1]) if match else None}
        for x in own:
            m = re.search(r'WeatherStartup: ([\w-]+) total=(\d+)ms', x)
            if m:
                record.setdefault(m[1] + '_ms', int(m[2]))
        record['fatal_or_missing'] = [x for x in own if any(s in x for s in ['FATAL EXCEPTION', 'UNIMPLEMENTED', 'MissingPluginException'])]
        records.append(record)
        print(json.dumps(record, ensure_ascii=False), flush=True)
    result = {'package': args.package, 'serial': args.serial, 'runs': records}
    for key in ['am_total_ms', 'runtime-ready_ms', 'engine-returned_ms', 'first-frame-callback_ms']:
        values = [r[key] for r in records if isinstance(r.get(key), int)]
        if values:
            result['median_' + key] = statistics.median(values)
    (args.output / 'summary.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
