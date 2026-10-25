"""Run before/after Xiaomi sky comparisons on Android without foreground navigation."""
from pathlib import Path
import argparse
import re
import shutil
import subprocess
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--asset-apk', type=Path, required=True)
    args = parser.parse_args()
    work = ROOT / '.build/widget-daynight/render'
    work.mkdir(parents=True, exist_ok=True)
    classes, dex = work / 'classes', work / 'dex'
    for directory in (classes, dex):
        directory.mkdir(exist_ok=True)
        for stale in directory.rglob('*.class' if directory == classes else '*.dex'):
            stale.unlink()
    def run(command, **kwargs):
        return subprocess.run(list(map(str, command)), check=True, **kwargs)
    sdk, java = args.sdk, args.java_home / 'bin'
    bt = sdk / 'build-tools/35.0.0'
    android = sdk / 'platforms/android-36/android.jar'
    adb = [sdk / 'platform-tools/adb.exe', '-s', args.serial]
    names = ('WeatherWidgetRenderer', 'WeatherCardWidgetRenderer')
    before = []
    for name in names:
        source = (args.baseline / f'{name}.java').read_text(encoding='utf-8')
        for original in names:
            source = re.sub(r'\b' + original + r'\b', 'Before' + original, source)
        target = work / f'Before{name}.java'
        target.write_text(source, encoding='utf-8')
        before.append(target)
    sources = [ROOT / 'tests/WidgetSkyRenderProbe.java', *before,
               *[ROOT / 'port' / f'{n}.java' for n in (*names, 'WeatherWidgetDayNight',
                  'WeatherWidgetFonts', 'WeatherWidgetData', 'WeatherOxygenWidgetRenderer')]]
    run([java / 'javac.exe', '--release', '8', '-encoding', 'UTF-8', '-cp', android,
         '-d', classes, *sources])
    run([java / 'java.exe', '-cp', bt / 'lib/d8.jar', 'com.android.tools.r8.D8', '--min-api', '28',
         '--lib', android, '--output', dex, *classes.rglob('*.class')])
    manifest = work / 'AndroidManifest.xml'
    manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.weather.daynightprobe">
<uses-sdk android:minSdkVersion="28" android:targetSdkVersion="36"/>
<application android:debuggable="true" android:label="Widget sky test"/>
<instrumentation android:name="dev.weather.oneplus.WidgetSkyRenderProbe" android:targetPackage="dev.weather.daynightprobe"/>
</manifest>''', encoding='utf-8')
    res = work / 'res'
    for language in ('values', 'values-zh'):
        destination = res / language
        destination.mkdir(parents=True, exist_ok=True)
        for source in (ROOT / 'port/res' / language).glob('weather_*strings.xml'):
            shutil.copy2(source, destination / source.name)
    drawable = res / 'drawable'
    drawable.mkdir(exist_ok=True)
    for source in (ROOT / 'port/res/drawable').glob('weather_oxygen_oos_*.xml'):
        shutil.copy2(source, drawable / source.name)
    run([bt / 'aapt2.exe', 'compile', '--dir', res, '-o', work / 'resources.zip'])
    run([bt / 'aapt2.exe', 'link', '-I', android, '--manifest', manifest,
         work / 'resources.zip', '-o', work / 'unsigned.apk'])
    with ZipFile(work / 'unsigned.apk', 'a', compression=ZIP_DEFLATED) as output:
        output.write(dex / 'classes.dex', 'classes.dex')
        with ZipFile(args.asset_apk) as original:
            for name in original.namelist():
                if name.startswith(('assets/weather_widget/', 'assets/weather_cards/', 'assets/oxygen_widget/')) or name == 'assets/fonts/MiSansVF.ttf':
                    output.writestr(name, original.read(name))
    key = work / 'test.p12'
    if not key.exists():
        run([java / 'keytool.exe', '-genkeypair', '-keystore', key, '-storetype', 'PKCS12',
             '-alias', 'test', '-storepass', 'android', '-keypass', 'android', '-dname', 'CN=WidgetSkyTest',
             '-keyalg', 'RSA', '-keysize', '2048', '-validity', '30'])
    run([bt / 'zipalign.exe', '-f', '4', work / 'unsigned.apk', work / 'aligned.apk'])
    run([java / 'java.exe', '-jar', bt / 'lib/apksigner.jar', 'sign', '--ks', key,
         '--ks-pass', 'pass:android', '--out', work / 'probe.apk', work / 'aligned.apk'])
    existing = subprocess.run(list(map(str, adb + ['shell', 'pm', 'path', 'dev.weather.daynightprobe'])),
                              capture_output=True, text=True)
    if existing.stdout.strip():
        raise RuntimeError('Temporary probe already exists; inspect before replacing')
    run(adb + ['install', '--no-incremental', work / 'probe.apk'])
    try:
        result = run(adb + ['shell', 'am', 'instrument', '-w',
                           'dev.weather.daynightprobe/dev.weather.oneplus.WidgetSkyRenderProbe'], capture_output=True, text=True)
        (work / 'result.txt').write_text(result.stdout + result.stderr, encoding='utf-8')
        print(result.stdout, flush=True)
        if 'PASS:' not in result.stdout or 'FAIL:' in result.stdout:
            raise RuntimeError('Device render comparisons failed')
    finally:
        run(adb + ['uninstall', 'dev.weather.daynightprobe'])


if __name__ == '__main__':
    main()
