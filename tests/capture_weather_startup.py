"""Record cold-launch handoff without clearing app data or global logs."""
import argparse
import json
import shutil
import subprocess
import time
from pathlib import Path
from PIL import Image, ImageDraw

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True)
p.add_argument('--package', default='dev.weather.oneplus')
p.add_argument('--route')
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
a.output.mkdir(parents=True, exist_ok=True)
adb = [str(Path.home()/'AppData/Local/Android/Sdk/platform-tools/adb.exe'), '-s', a.serial]
activity = 'TestWeatherActivity' if a.package == 'dev.weather.test' else 'WeatherActivity'
remote = '/sdcard/weather-startup-' + str(time.time_ns()) + '.mp4'
subprocess.run(adb+['shell','am','force-stop',a.package],check=True,capture_output=True)
time.sleep(.3)
with (a.output/'startup.log').open('wb') as log:
    logger = subprocess.Popen(adb+['logcat','-v','threadtime','-T','1',
        'WeatherStartup:I','WeatherHost:I','WeatherChannel:I','WeatherMGL:I',
        'WeatherFonts:I','AndroidRuntime:E','flutter:I','*:S'],stdout=log,stderr=log)
    recorder = subprocess.Popen(adb+['shell','screenrecord','--time-limit','6',
        '--size','720x1600',remote],stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    try:
        time.sleep(.35)
        command = adb+['shell','am','start','-W','-n',a.package+'/dev.weather.oneplus.'+activity]
        if a.route:
            command += ['--es','open_route',a.route]
        launch = subprocess.run(command,capture_output=True,text=True,timeout=30)
        pid = subprocess.check_output(adb+['shell','pidof',a.package],text=True).strip().split()[0]
        (a.output/'launch.txt').write_text(launch.stdout+launch.stderr,encoding='utf-8')
        recorder.wait(timeout=20)
        if recorder.returncode:
            raise RuntimeError(recorder.stdout.read().decode('utf-8','replace'))
    finally:
        logger.terminate()
        logger.wait(timeout=10)
        if recorder.poll() is None:
            recorder.terminate()
            recorder.wait(timeout=10)
subprocess.run(adb+['pull',remote,str(a.output/'startup.mp4')],check=True,capture_output=True)
subprocess.run(adb+['shell','rm',remote],check=True,capture_output=True)
lines = (a.output/'startup.log').read_text(encoding='utf-8',errors='replace').splitlines()
own = [x for x in lines if len(x.split())>3 and x.split()[2]==pid]
(a.output/'own.log').write_text('\n'.join(own),encoding='utf-8')
summary = [x for x in own if 'WeatherStartup:' in x or 'FATAL EXCEPTION' in x]
print('\n'.join(summary),flush=True)
subprocess.run([shutil.which('ffmpeg'),'-hide_banner','-loglevel','error','-y',
    '-i',str(a.output/'startup.mp4'),'-vf','fps=10,scale=180:-1','-frames:v','60',
    str(a.output/'frame-%02d.png')],check=True)
frames = sorted(a.output.glob('frame-*.png'))
sheet = Image.new('RGB',(180*10,420*((len(frames)+9)//10)),'#ddd')
draw = ImageDraw.Draw(sheet)
for i,path in enumerate(frames):
    x,y = (i%10)*180,(i//10)*420
    draw.text((x+3,y+3),f'{i/10:.1f}s',fill='black')
    with Image.open(path) as im:
        sheet.paste(im,(x,y+20))
sheet.save(a.output/'contact.png')
print('Recorded:',a.output,flush=True)
