"""Render production Oxygen widgets in a temporary ordinary-UID Android test APK."""
from pathlib import Path
import argparse
import subprocess
import tarfile
import shutil
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--font-apk", type=Path, required=True, help="Existing weather APK providing the unchanged MiSans font")
    args = parser.parse_args()
    work = ROOT / ".build/oxygen-widget-preview"
    classes, dex = work / "classes", work / "dex"
    classes.mkdir(parents=True, exist_ok=True)
    for stale in classes.rglob("*.class"):
        stale.unlink()
    dex.mkdir(exist_ok=True)
    bt, java = args.sdk / "build-tools/35.0.0", args.java_home / "bin"
    android = args.sdk / "platforms/android-36/android.jar"
    adb = [str(args.sdk / "platform-tools/adb.exe"), "-s", args.serial]
    sources = [ROOT / p for p in ("tests/OxygenWidgetRenderProbe.java", "port/WeatherOxygenWidgetRenderer.java",
                                  "port/WeatherWidgetFonts.java", "port/WeatherWidgetData.java")]
    def run(command, **kwargs):
        return subprocess.run([str(x) for x in command], check=True, **kwargs)
    run([java / "javac.exe", "--release", "8", "-encoding", "UTF-8", "-classpath", android,
         "-d", classes, *sources])
    run([java / "java.exe", "-cp", bt / "lib/d8.jar", "com.android.tools.r8.D8", "--min-api", "28",
         "--lib", android, "--output", dex, *classes.rglob("*.class")])
    manifest = work / "AndroidManifest.xml"
    manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.weather.widgetprobe">
<uses-sdk android:minSdkVersion="28" android:targetSdkVersion="36"/>
<queries><package android:name="dev.weather.oneplus"/></queries>
<application android:debuggable="true" android:label="Weather widget render test" android:theme="@android:style/Theme.Material.Light.NoActionBar">
<activity android:name="dev.weather.oneplus.OxygenWidgetRenderProbe" android:exported="true"/>
</application></manifest>''', encoding="utf-8")
    res=work/"res/drawable";res.mkdir(parents=True,exist_ok=True)
    for source in (ROOT/"port/res/drawable").glob("weather_oxygen_oos_*.xml"):
        shutil.copy2(source,res/source.name)
    run([bt/"aapt2.exe","compile","--dir",work/"res","-o",work/"probe-res.zip"])
    run([bt / "aapt2.exe", "link", "-I", android, "--manifest", manifest,work/"probe-res.zip", "-o", work / "probe-unsigned.apk"])
    with ZipFile(work / "probe-unsigned.apk", "a", compression=ZIP_DEFLATED) as archive:
        archive.write(dex / "classes.dex", "classes.dex")
        for source in (ROOT/"port/assets/oxygen_widget").rglob("*"):
            if source.is_file():archive.write(source,"assets/"+source.relative_to(ROOT/"port/assets").as_posix())
        with ZipFile(args.font_apk) as original:
            archive.writestr("assets/fonts/MiSansVF.ttf",original.read("assets/fonts/MiSansVF.ttf"))
    run([bt / "zipalign.exe", "-f", "4", work / "probe-unsigned.apk", work / "probe-aligned.apk"])
    # Disposable test identity only. The weather APK's production signing key is not used.
    key = work / "test-keystore.p12"
    if not key.exists():
        run([java / "keytool.exe", "-genkeypair", "-keystore", key, "-storetype", "PKCS12",
             "-alias", "widgetprobe", "-storepass", "android", "-keypass", "android", "-dname",
             "CN=WeatherWidgetRenderTest", "-keyalg", "RSA", "-keysize", "2048", "-validity", "30"])
    run([java / "java.exe", "-jar", bt / "lib/apksigner.jar", "sign", "--ks", key, "--ks-pass", "pass:android",
         "--out", work / "probe.apk", work / "probe-aligned.apk"])
    check = subprocess.run(adb + ["shell", "pm", "path", "dev.weather.widgetprobe"], capture_output=True, text=True)
    if check.stdout.strip():
        raise SystemExit("A widget probe package is already installed; inspect it before replacing it")
    run(adb + ["install", "--no-incremental", str(work / "probe.apk")])
    try:
        sizes = {i: (150, 150) for i in range(8, 25)}
        sizes.update({10: (340, 150), 11: (340, 340), 12: (340, 340), 13: (150, 60), 15: (150, 60), 22: (150, 60)})
        sizes.update({i: (64, 64) for i in range(17, 22)})
        specs = ",".join(f"{i}:{w*3}:{h*3}" for i, (w, h) in sizes.items()) + ",16:450:180"
        # Measured zero-padding 1x1 ColorOS host, plus a compact 56dp host.
        specs += "".join(f",{i}:246:270,{i}:168:168" for i in range(17, 22))
        run(adb + ["shell", "am", "start", "-W", "-n", "dev.weather.widgetprobe/dev.weather.oneplus.OxygenWidgetRenderProbe", "--es", "targets", specs])
        import time
        for _ in range(30):
            time.sleep(1)
            result = subprocess.run(adb + ["shell", "run-as", "dev.weather.widgetprobe", "cat", "files/renders/result.txt"], capture_output=True, text=True)
            if result.returncode == 0:
                print(result.stdout, flush=True)
                break
        else:
            raise RuntimeError("Renderer did not finish; inspect OxygenWidgetProbe in logcat")
        with (work / "renders.tar").open("wb") as stream:
            run(adb + ["exec-out", "run-as", "dev.weather.widgetprobe", "tar", "-cf", "-", "files/renders"], stdout=stream)
        with tarfile.open(work / "renders.tar") as archive:
            archive.extractall(work / "rendered", filter="data")
    finally:
        run(adb + ["uninstall", "dev.weather.widgetprobe"])
    print("Ordinary-UID render checks complete; temporary test app removed")


if __name__ == "__main__":
    main()
