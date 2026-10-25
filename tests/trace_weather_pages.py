"""Trace a real weather-page tap in the existing foreground app; no data reset."""
import argparse
import subprocess
import time
from pathlib import Path

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial',required=True)
p.add_argument('--package',default='dev.weather.oneplus')
p.add_argument('--x',type=int,required=True)
p.add_argument('--y',type=int,required=True)
p.add_argument('--output',type=Path,required=True)
a=p.parse_args()
a.output.mkdir(parents=True,exist_ok=True)
adb=[str(Path.home()/'AppData/Local/Android/Sdk/platform-tools/adb.exe'),'-s',a.serial]
remote='/data/misc/perfetto-traces/weather-page-'+str(time.time_ns())+'.pftrace'
config='''buffers { size_kb: 65536 fill_policy: RING_BUFFER }
data_sources { config { name: "linux.ftrace" ftrace_config {
 ftrace_events: "sched/sched_switch" ftrace_events: "sched/sched_waking"
 atrace_categories: "gfx" atrace_categories: "view" atrace_categories: "am"
 atrace_categories: "wm" atrace_categories: "dalvik" atrace_categories: "webview"
 atrace_apps: "%s"
} } }
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
data_sources { config { name: "linux.process_stats" process_stats_config { scan_all_processes_on_start: true } } }
duration_ms: 9000
write_into_file: true
''' % a.package
with (a.output/'logcat.txt').open('wb') as f:
 logger=subprocess.Popen(adb+['logcat','-v','threadtime','-T','1'],stdout=f,stderr=f)
 recorder=subprocess.Popen(adb+['shell','perfetto','--txt','-c','-','-o',remote],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
 try:
  recorder.stdin.write(config.encode());recorder.stdin.close();time.sleep(.7)
  subprocess.run(adb+['shell','input','tap',str(a.x),str(a.y)],check=True)
  recorder.wait(timeout=20)
  print(recorder.stdout.read().decode('utf-8','replace'))
  if recorder.returncode:raise RuntimeError('Perfetto failed')
  (a.output/'page.png').write_bytes(subprocess.check_output(adb+['exec-out','screencap','-p']))
 finally:
  logger.terminate();logger.wait(timeout=10)
  if recorder.poll() is None:recorder.terminate();recorder.wait(timeout=10)
subprocess.run(adb+['pull',remote,str(a.output/'page.pftrace')],check=True,capture_output=True)
subprocess.run(adb+['shell','rm',remote],check=True,capture_output=True)
print('Saved',a.output)
