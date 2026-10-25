"""Capture a bounded Perfetto trace of process-cold launch and early swipes."""
import argparse
import subprocess
import time
from pathlib import Path

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--package', default='dev.weather.test')
a = p.parse_args()
a.output.parent.mkdir(parents=True, exist_ok=True)
adb = [str(Path.home()/'AppData/Local/Android/Sdk/platform-tools/adb.exe'), '-s', a.serial]
remote = '/data/misc/perfetto-traces/weather-startup-audit-' + str(time.time_ns()) + '.pftrace'
config = '''buffers { size_kb: 65536 fill_policy: RING_BUFFER }
data_sources { config { name: "linux.ftrace" ftrace_config {
  ftrace_events: "sched/sched_switch"
  ftrace_events: "sched/sched_waking"
  ftrace_events: "power/cpu_frequency"
  atrace_categories: "gfx"
  atrace_categories: "view"
  atrace_categories: "am"
  atrace_categories: "wm"
  atrace_categories: "dalvik"
  atrace_categories: "input"
  atrace_apps: "%s"
} } }
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
data_sources { config { name: "linux.process_stats" process_stats_config {
  scan_all_processes_on_start: true
} } }
duration_ms: 12000
write_into_file: true
''' % a.package
subprocess.run(adb+['shell','am','force-stop',a.package],check=True,capture_output=True)
recorder = subprocess.Popen(adb+['shell','perfetto','--txt','-c','-','-o',remote],
    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
recorder.stdin.write(config.encode())
recorder.stdin.close()
time.sleep(0.6)
activity = 'TestWeatherActivity' if a.package == 'dev.weather.test' else 'WeatherActivity'
start = time.monotonic()
launch = subprocess.run(adb+['shell','am','start','-W','-n',a.package+'/dev.weather.oneplus.'+activity],
    capture_output=True,text=True,timeout=30)
print(launch.stdout,flush=True)
time.sleep(max(0,3.5-(time.monotonic()-start)))
for i in range(4):
    y1,y2 = (1950,900) if i%2==0 else (900,1950)
    subprocess.run(adb+['shell','input','swipe','540',str(y1),'540',str(y2),'600'],check=True)
    time.sleep(0.5)
recorder.wait(timeout=25)
print(recorder.stdout.read().decode('utf-8','replace'),flush=True)
if recorder.returncode:
    raise SystemExit(recorder.returncode)
subprocess.run(adb+['pull',remote,str(a.output)],check=True)
# Only remove this script's uniquely named temporary trace after a successful pull.
subprocess.run(adb+['shell','rm',remote],check=True)
print('Trace saved:',a.output,flush=True)
