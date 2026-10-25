"""Run production text input adapter against real Android IME primitives."""
from pathlib import Path
import argparse
import json
import subprocess
import time
from zipfile import ZipFile, ZIP_DEFLATED

ROOT=Path(__file__).resolve().parents[1]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk',type=Path,required=True)
    parser.add_argument('--java-home',type=Path,required=True)
    parser.add_argument('--serial',required=True)
    args=parser.parse_args()
    work=ROOT/'.build/textinput-probe';work.mkdir(exist_ok=True)
    classes=work/'classes';classes.mkdir(exist_ok=True)
    for stale in classes.rglob('*.class'):stale.unlink()
    dex=work/'dex';dex.mkdir(exist_ok=True)
    java=args.java_home/'bin';bt=args.sdk/'build-tools/35.0.0';android=args.sdk/'platforms/android-36/android.jar'
    adb=[str(args.sdk/'platform-tools/adb.exe'),'-s',args.serial]
    def run(command,**kwargs):return subprocess.run([str(v) for v in command],check=True,**kwargs)
    run([java/'javac.exe','--release','8','-encoding','UTF-8','-classpath',android,'-d',classes,
         ROOT/'port/AndroidTextInput.java',ROOT/'tests/TextInputRegressionProbe.java'])
    run([java/'java.exe','-cp',bt/'lib/d8.jar','com.android.tools.r8.D8','--min-api','28','--lib',android,'--output',dex,*classes.rglob('*.class')])
    manifest=work/'AndroidManifest.xml'
    manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.weather.textprobe">
<uses-sdk android:minSdkVersion="28" android:targetSdkVersion="36"/>
<application android:debuggable="true" android:label="Weather input regression test">
<activity android:name="dev.weather.oneplus.TextInputRegressionProbe" android:exported="true"/>
</application></manifest>''',encoding='utf-8')
    unsigned=work/'probe-unsigned.apk';aligned=work/'probe-aligned.apk';apk=work/'probe.apk'
    run([bt/'aapt2.exe','link','-I',android,'--manifest',manifest,'-o',unsigned])
    with ZipFile(unsigned,'a',compression=ZIP_DEFLATED) as archive:archive.write(dex/'classes.dex','classes.dex')
    run([bt/'zipalign.exe','-f','4',unsigned,aligned])
    key=work/'test-key.p12'
    if not key.exists():
        run([java/'keytool.exe','-genkeypair','-keystore',key,'-storetype','PKCS12','-alias','test',
             '-storepass','android','-keypass','android','-dname','CN=InputRegressionTest','-keyalg','RSA','-validity','30'])
    run([java/'java.exe','-jar',bt/'lib/apksigner.jar','sign','--ks',key,'--ks-pass','pass:android','--out',apk,aligned])
    present=subprocess.run(adb+['shell','pm','path','dev.weather.textprobe'],capture_output=True,text=True)
    if present.stdout.strip():raise RuntimeError('Probe already installed; inspect before replacing')
    run(adb+['install','--no-incremental',apk])
    try:
        run(adb+['shell','am','start','-W','-n','dev.weather.textprobe/dev.weather.oneplus.TextInputRegressionProbe'])
        for _ in range(20):
            time.sleep(.5)
            result=subprocess.run(adb+['shell','run-as','dev.weather.textprobe','cat','files/result.json'],capture_output=True,text=True)
            if result.returncode==0:break
        else:raise RuntimeError('No probe result; inspect TextInputProbe in logcat')
        data=json.loads(result.stdout);(work/'result.json').write_text(json.dumps(data,indent=2),encoding='utf-8')
        print(json.dumps(data),flush=True)
        assert data['passed'],data
    finally:run(adb+['uninstall','dev.weather.textprobe'])

if __name__=='__main__':main()
