"""Exercise production permissions with Android's real permission dialogs in a disposable APK."""
from pathlib import Path
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from zipfile import ZipFile,ZIP_DEFLATED

ROOT=Path(__file__).resolve().parents[1]
PACKAGE='dev.weather.permissionprobe'

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk',type=Path,required=True)
    parser.add_argument('--java-home',type=Path,required=True)
    parser.add_argument('--serial',required=True)
    args=parser.parse_args()
    work=ROOT/'.build/android12-13-fix/permission-probe';work.mkdir(parents=True,exist_ok=True)
    classes=work/'classes';classes.mkdir(exist_ok=True)
    dex=work/'dex';dex.mkdir(exist_ok=True)
    java=args.java_home/'bin';bt=args.sdk/'build-tools/35.0.0';android=args.sdk/'platforms/android-36/android.jar'
    adb=[str(args.sdk/'platform-tools/adb.exe'),'-s',args.serial]
    def run(command,**kwargs):return subprocess.run([str(x) for x in command],check=True,**kwargs)
    def shell(*command):return run(adb+['shell',*command],capture_output=True,text=True,encoding='utf-8').stdout.strip()
    run([java/'javac.exe','--release','8','-encoding','UTF-8','-classpath',android,'-d',classes,
        ROOT/'port/AndroidPermissions.java',ROOT/'port/AndroidLocation.java',ROOT/'tests/PermissionRegressionProbe.java'])
    run([java/'java.exe','-cp',bt/'lib/d8.jar','com.android.tools.r8.D8','--min-api','28','--lib',android,'--output',dex,*classes.rglob('*.class')])
    manifest=work/'AndroidManifest.xml'
    manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.weather.permissionprobe">
<uses-sdk android:minSdkVersion="28" android:targetSdkVersion="36"/>
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/>
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION"/>
<application android:debuggable="true" android:label="Weather permission probe">
<activity android:name="dev.weather.oneplus.PermissionRegressionProbe" android:exported="true"/>
</application></manifest>''',encoding='utf-8')
    unsigned=work/'unsigned.apk';aligned=work/'aligned.apk';apk=work/'probe.apk'
    run([bt/'aapt2.exe','link','-I',android,'--manifest',manifest,'-o',unsigned])
    with ZipFile(unsigned,'a',compression=ZIP_DEFLATED) as archive:archive.write(dex/'classes.dex','classes.dex')
    run([bt/'zipalign.exe','-f','4',unsigned,aligned])
    key=work/'test-key.p12'
    if not key.exists():run([java/'keytool.exe','-genkeypair','-keystore',key,'-storetype','PKCS12','-alias','test',
        '-storepass','android','-keypass','android','-dname','CN=PermissionRegressionTest','-keyalg','RSA','-validity','30'],capture_output=True)
    run([java/'java.exe','-jar',bt/'lib/apksigner.jar','sign','--ks',key,'--ks-pass','pass:android','--out',apk,aligned])
    if subprocess.run(adb+['shell','pm','path',PACKAGE],capture_output=True).returncode==0:raise RuntimeError('Probe package already exists')
    run(adb+['install','--no-incremental',apk],capture_output=True)
    def dump():
        shell('uiautomator','dump','/data/local/tmp/weather-permissionprobe-ui.xml')
        xml=shell('cat','/data/local/tmp/weather-permissionprobe-ui.xml')
        (work/'last-ui.xml').write_text(xml,encoding='utf-8')
        return ET.fromstring(xml)
    def tap_node(predicate):
        for _ in range(4):
            for node in dump().iter('node'):
                if predicate(node):
                    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds','')))
                    shell('input','tap',str((x1+x2)//2),str((y1+y2)//2));return
            time.sleep(.25)
        raise AssertionError('Expected permission UI was not found')
    def start(mode,pregrant=False):
        print('Testing',mode,'pregrant='+str(pregrant),flush=True)
        shell('pm','clear',PACKAGE)
        time.sleep(.5)
        for permission in ('ACCESS_BACKGROUND_LOCATION','ACCESS_FINE_LOCATION','ACCESS_COARSE_LOCATION'):
            shell('pm','revoke',PACKAGE,'android.permission.'+permission)
            shell('pm','clear-permission-flags',PACKAGE,'android.permission.'+permission,'user-set','user-fixed')
        if pregrant:
            shell('pm','grant',PACKAGE,'android.permission.ACCESS_COARSE_LOCATION')
            shell('pm','grant',PACKAGE,'android.permission.ACCESS_FINE_LOCATION')
        (work/'prelaunch-permissions.txt').write_text(shell('dumpsys','package',PACKAGE),encoding='utf-8')
        shell('am','start','-W','-n',PACKAGE+'/dev.weather.oneplus.PermissionRegressionProbe','--es','scenario',mode)
    def result():
        for _ in range(130):
            value=json.loads(shell('run-as',PACKAGE,'cat','files/result.json'))
            if value['events']:
                assert value['accepted'] and len(value['events'])==1,value
                return value
            time.sleep(.15)
        raise AssertionError('Permission completion was lost')
    reports={}
    try:
        for mode in ('common','legacy'):
            start(mode)
            tap_node(lambda n:n.get('resource-id','').endswith('permission_allow_foreground_only_button'))
            value=result();assert value['fine']==0 and value['coarse']==0,value
            event=value['events'][0];assert event['stringResult']==(mode=='legacy'),event
            assert event['arguments']['request_code']==(0 if mode=='legacy' else 1005),event
            assert event['arguments']['permissions']==['android.permission.ACCESS_FINE_LOCATION'],event
            assert event['arguments']['grant_results']==[0],event
            reports[mode]=value
        start('common')
        tap_node(lambda n:n.get('resource-id','').endswith('permission_deny_button'))
        value=result();assert value['fine']==-1 and value['coarse']==-1,value
        assert value['events'][0]['arguments']['grant_results']==[-1],value
        reports['denied']=value
        start('background',True)
        tap_node(lambda n:n.get('resource-id')=='android:id/button2')
        value=result();assert value['background']==-1 and value['fine']==0,value
        assert value['events'][0]['arguments']['grant_results']==[-1],value
        reports['background_cancel']=value
        for grant in (False,True):
            start('background',True)
            tap_node(lambda n:n.get('resource-id')=='android:id/button1')
            assert 'com.android.settings' in shell('dumpsys','activity','activities')
            if grant:shell('pm','grant',PACKAGE,'android.permission.ACCESS_BACKGROUND_LOCATION')
            shell('input','keyevent','KEYCODE_BACK')
            value=result();assert value['background']==(0 if grant else -1),value
            assert value['events'][0]['arguments']['grant_results']==[0 if grant else -1],value
            reports['background_settings_'+str(grant)]=value
        for grant in (False,True):
            start('backend')
            tap_node(lambda n:n.get('resource-id','').endswith(
                'permission_allow_foreground_only_button' if grant else 'permission_deny_button'))
            value=result();event=value['events'][0]
            assert event['kind']=='location',value
            if grant:
                assert value['coarse']==0 and event.get('errorCode') not in ('PERMISSION_DENIED','APP_NOT_FOREGROUND','CANCELLED'),value
            else:assert value['coarse']==-1 and event.get('errorCode')=='PERMISSION_DENIED',value
            reports['backend_'+str(grant)]=value
        (work/'result.json').write_text(json.dumps(reports,ensure_ascii=False,indent=2),encoding='utf-8')
        print('PASS: real foreground system dialogs (common + legacy + direct location); denial; background Settings cancel/return/real grant; lifecycle and exact callback codec shapes')
    finally:
        diagnostic=subprocess.run(adb+['shell','run-as',PACKAGE,'cat','files/result.json'],capture_output=True,text=True,encoding='utf-8')
        (work/'last-state.txt').write_text(diagnostic.stdout+diagnostic.stderr,encoding='utf-8')
        run(adb+['uninstall',PACKAGE],capture_output=True)
        shell('rm','-f','/data/local/tmp/weather-permissionprobe-ui.xml')

if __name__=='__main__':main()
