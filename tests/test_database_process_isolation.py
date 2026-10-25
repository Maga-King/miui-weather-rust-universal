"""Check the real two-SQLite locking behavior on disposable Android databases."""
from pathlib import Path
import argparse
import json
import re
import subprocess
import uuid
import xml.etree.ElementTree as ET
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", type=Path, required=True)
    args = parser.parse_args()
    for edition, package in (("normal", "dev.weather.oneplus"), ("test", "dev.weather.test")):
        manifest = ET.parse(ROOT / "manifests" / (edition + ".xml")).getroot()
        provider = next(p for p in manifest.find("application").findall("provider")
                        if p.get(ANDROID + "name") == "dev.weather.oneplus.WeatherDatabaseProvider")
        assert provider.get(ANDROID + "process") == ":weather_db"
        assert provider.get(ANDROID + "exported") == "false"
        assert provider.get(ANDROID + "authorities") == package + ".weatherdb"
    for path in (ROOT / "port").glob("*.java"):
        if path.name not in ("WeatherDatabaseProvider.java", "TestCityDatabaseSeed.java"):
            assert not re.search(r"SQLiteDatabase\s*\.\s*open", path.read_text(encoding="utf-8")), path

    work = ROOT / ".build/database-isolation-check"
    work.mkdir(parents=True, exist_ok=True)
    with ZipFile(args.apk) as archive:
        (work / "libsqlite3.so").write_bytes(archive.read("lib/arm64-v8a/libsqlite3.so"))
    tc = args.sdk / "ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64"
    executable = work / "probe"
    subprocess.run([str(tc / "bin/clang.exe"), "--target=aarch64-linux-android28",
                    "--sysroot=" + str(tc / "sysroot"), str(ROOT / "tests/sqlite_lock_probe.c"),
                    "-ldl", "-o", str(executable)], check=True)
    adb = [str(args.sdk / "platform-tools/adb.exe"), "-s", args.serial]
    remote = "/data/local/tmp/weather-lock-probe-" + uuid.uuid4().hex[:12]
    created = [remote, remote + ".so"]
    results = []
    try:
        for local, target in ((executable, remote), (work / "libsqlite3.so", remote + ".so")):
            subprocess.run(adb + ["push", str(local), target], check=True, capture_output=True)
        subprocess.run(adb + ["shell", "chmod", "700", remote], check=True)
        for mode, expected in (("lock-same", 9), ("lock-separate", 0), ("separate", 0)):
            database = remote + "-" + mode + ".db"
            created.extend(database + suffix for suffix in ("", "-wal", "-shm", "-journal"))
            result = subprocess.run(adb + ["shell", remote, remote + ".so", mode, database],
                                    capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30)
            results.append(dict(mode=mode, exit=result.returncode, output=result.stdout + result.stderr))
            assert result.returncode == expected, results[-1]
            print("PASS:", mode, "expected exit", expected)
    finally:
        (work / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
        assert all(p.startswith("/data/local/tmp/weather-lock-probe-") for p in created)
        subprocess.run(adb + ["shell", "rm", "-f", *created], check=False, capture_output=True)
    print("PASS: private provider declarations, no main-process database readers, and real WAL lock isolation")


if __name__ == "__main__":
    main()
