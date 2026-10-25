"""Compile the Java/native host around the frozen, hash-verified Xiaomi runtime.

The inputs archive contains runtime dependencies, never a ready-to-copy output APK.
Java, native compatibility/host code, DEX linkage, manifest and resources are rebuilt.
Only Python's standard library is required. See BUILD_INPUTS.md for the interface.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import sys
import urllib.request
import zipfile
from port.patch_dpi_settings import patch as patch_dpi_settings
from port.patch_widget_settings import patch as patch_widget_settings
from port.patch_weather_locale import patch as patch_weather_locale
from port.patch_mgl_rendering import patch as patch_mgl_rendering

ROOT = Path(__file__).resolve().parent
WORK = ROOT / ".build"
SDK_VERSION = "36"
BUILD_TOOLS = "35.0.0"
NDK_VERSION = "29.0.14206865"
EDITIONS = {
    "normal": ("dev.weather.oneplus", "weather-oneplus.apk"),
    "test": ("dev.weather.test", "weather-oneplus-experimental.apk"),
}
MIPUSH_SHA256 = "bce8d29a119c0a85fd0e56e577ec291ddfb8edda1c0f901f3de467b7ca578644"
WEBKIT_VERSION = "1.18.0"
WEBKIT_SHA256 = "f15a0bd7d51f4bb8dffdd3a2cf5829f9a7de9ad4ba2682fa85d619032050581f"


def webkit_jar() -> Path:
    directory = WORK / "dependencies"
    directory.mkdir(parents=True, exist_ok=True)
    archive = directory / f"webkit-{WEBKIT_VERSION}.aar"
    if not archive.exists() or digest(archive.read_bytes()) != WEBKIT_SHA256:
        url = f"https://dl.google.com/dl/android/maven2/androidx/webkit/webkit/{WEBKIT_VERSION}/webkit-{WEBKIT_VERSION}.aar"
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read()
        if digest(data) != WEBKIT_SHA256:
            raise ValueError("AndroidX WebKit artifact SHA-256 mismatch")
        archive.write_bytes(data)
    jar = directory / f"webkit-{WEBKIT_VERSION}.jar"
    with zipfile.ZipFile(archive) as source:
        data = source.read("classes.jar")
    if not jar.exists() or jar.read_bytes() != data:
        jar.write_bytes(data)
    return jar


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def run(*args: object) -> None:
    command = [str(arg) for arg in args]
    print("+", subprocess.list2cmdline(command), flush=True)
    subprocess.run(command, check=True)


def required(path: Path) -> Path:
    if not path.is_file():
        raise FileNotFoundError(f"Required build input is missing: {path}")
    return path


def safe_name(name: str) -> PurePosixPath:
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or "\\" in name or ":" in name:
        raise ValueError(f"Invalid archive path: {name}")
    return path


def clean(path: Path) -> None:
    if not path.resolve().is_relative_to(WORK.resolve()):
        raise ValueError("Build cleanup must stay inside .build")
    if path.exists():
        shutil.rmtree(path)
    path.mkdir(parents=True)


def toolchain() -> dict[str, Path]:
    sdk_value = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    java_value = os.environ.get("JAVA_HOME")
    if not sdk_value or not java_value:
        raise RuntimeError("Set ANDROID_HOME (or ANDROID_SDK_ROOT) and JAVA_HOME before building")
    if os.name != "nt":
        raise RuntimeError("This build currently targets Windows; use the Windows Actions workflow")
    sdk, java = Path(sdk_value), Path(java_value) / "bin"
    bt = sdk / "build-tools" / BUILD_TOOLS
    ndk = sdk / "ndk" / NDK_VERSION / "toolchains/llvm/prebuilt/windows-x86_64"
    return {
        "java": required(java / "java.exe"),
        "javac": required(java / "javac.exe"),
        "android": required(sdk / "platforms" / f"android-{SDK_VERSION}" / "android.jar"),
        "d8": required(bt / "lib/d8.jar"),
        "aapt2": required(bt / "aapt2.exe"),
        "zipalign": required(bt / "zipalign.exe"),
        "apksigner": required(bt / "lib/apksigner.jar"),
        "clang": required(ndk / "bin/clang.exe"),
        "sysroot": ndk / "sysroot",
    }


def fetch_inputs(lock_path: Path, destination: Path) -> Path:
    lock = json.loads(required(lock_path).read_text(encoding="utf-8"))
    expected = lock["sha256"].lower()
    if len(expected) != 64 or not lock["url"].startswith("https://"):
        raise ValueError("inputs.lock.json must pin an HTTPS archive URL and SHA-256")
    archive = WORK / "downloads" / "inputs.zip"
    archive.parent.mkdir(parents=True, exist_ok=True)
    if not archive.is_file() or digest(archive.read_bytes()) != expected:
        print("Downloading pinned runtime inputs", flush=True)
        request = urllib.request.Request(lock["url"], headers={"User-Agent": "WeatherPortBuild/1"})
        with urllib.request.urlopen(request, timeout=120) as response, archive.open("wb") as output:
            shutil.copyfileobj(response, output)
    if digest(archive.read_bytes()) != expected:
        raise ValueError("Runtime input archive SHA-256 mismatch")
    # A cached extraction is checked file-by-file by verify_inputs below as well.
    stamp = destination / ".archive-sha256"
    if stamp.is_file() and stamp.read_text(encoding="ascii").strip() == expected:
        return destination
    if not destination.resolve().is_relative_to(WORK.resolve()):
        raise ValueError("Downloaded inputs must be extracted below .build")
    clean(destination)
    with zipfile.ZipFile(archive) as source:
        for entry in source.infolist():
            name = safe_name(entry.filename)
            if entry.is_dir():
                continue
            if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError("Input archives may not contain symbolic links")
            target = destination.joinpath(*name.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            with source.open(entry) as data, target.open("wb") as output:
                shutil.copyfileobj(data, output)
    stamp.write_text(expected + "\n", encoding="ascii")
    return destination


def verify_inputs(inputs: Path) -> dict:
    manifest = json.loads(required(inputs / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("schema") != 1 or not isinstance(manifest.get("files"), dict):
        raise ValueError("Expected runtime inputs manifest schema 1")
    declared = manifest["files"]
    for name, record in declared.items():
        path = inputs.joinpath(*safe_name(name).parts)
        expected = record["sha256"] if isinstance(record, dict) else record
        if digest(required(path).read_bytes()) != expected:
            raise ValueError(f"Runtime input SHA-256 mismatch: {name}")
    actual = {p.relative_to(inputs).as_posix() for p in inputs.rglob("*") if p.is_file()
              and p.relative_to(inputs).as_posix() not in {"manifest.json", ".archive-sha256"}}
    if actual != set(declared):
        raise ValueError(f"Untracked/missing runtime inputs: {sorted(actual ^ set(declared))[:12]}")
    jar = inputs / "mipush/MiPush_SDK_Client_6_0_1-C.jar"
    if digest(required(jar).read_bytes()) != MIPUSH_SHA256:
        raise ValueError("Unexpected MiPush SDK version")
    print(f"Verified {len(declared)} frozen runtime inputs", flush=True)
    return manifest


def compile_native(tc: dict[str, Path]) -> dict[str, Path]:
    xdl = ROOT / "third_party/xdl"
    xdl_sources = sorted(xdl.glob("*.c"))
    required(xdl / "include/xdl.h")
    if not xdl_sources:
        raise ValueError("Missing vendored xDL C sources")
    output = WORK / "native"
    clean(output)
    flags = [tc["clang"], "--target=aarch64-linux-android28", "--sysroot=" + str(tc["sysroot"]),
             "-std=c17", "-D_GNU_SOURCE", "-shared", "-fPIC", "-O2", "-Wl,-z,max-page-size=16384",
             "-I" + str(xdl / "include"), "-I" + str(xdl)]
    # Preserve the final reference's __FILE__ strings, independent of checkout location.
    original = r"C:\Users\a1510\weather-port-analysis"
    flags.extend("-ffile-prefix-map=" + str(source) + "=" + target for source, target in (
        (ROOT / "port", original + r"\port"),
        (xdl, original + r"\research\xDL\xdl\src\main\cpp"),
        (ROOT / "native", original + r"\probe")))
    host, compat = output / "libweatherhost.so", output / "libweathercompat.so"
    run(*flags, required(ROOT / "port/host.c"), *xdl_sources,
        "-llog", "-ldl", "-landroid", "-lmediandk", "-ljnigraphics", "-o", host)
    run(*flags, required(ROOT / "native/shim.c"), required(ROOT / "native/shim.S"), *xdl_sources,
        "-llog", "-ldl", "-Wl,-z,global", "-Wl,-soname,libweathercompat.so", "-o", compat)
    return {host.name: host, compat.name: compat}


def compile_java(tc: dict[str, Path], inputs: Path) -> tuple[Path, Path, Path]:
    splash = WORK / "splash-classes"
    host = WORK / "host-classes"
    module = WORK / "module-classes"
    stubs = ROOT / "weather-pages-module/compile-stubs"
    stub_classes = WORK / "module-stub-classes"
    for path in (splash, host, module, stub_classes):
        clean(path)
    splash_jar = required(inputs / "splash/splash-runtime-isolated.jar")
    mipush = required(inputs / "mipush/MiPush_SDK_Client_6_0_1-C.jar")
    splash_sources = sorted((ROOT / "splash/src").rglob("*.java"))
    host_sources = sorted((ROOT / "port").glob("*.java"))
    module_sources = sorted((ROOT / "weather-pages-module/src").rglob("*.java"))
    if not splash_sources or not host_sources or not module_sources:
        raise ValueError("Host, splash and module Java source directories must all be present")
    run(tc["javac"], "--release", "8", "-encoding", "UTF-8", "-classpath",
        os.pathsep.join(map(str, [tc["android"], splash_jar])), "-d", splash, *splash_sources)
    # Real original activities remain in the supplied pages DEX; these classes only aid javac.
    if not list(stubs.rglob("*.java")):
        raise ValueError("Missing module compile-only Java stubs")
    run(tc["javac"], "--release", "8", "-encoding", "UTF-8", "-classpath", tc["android"],
        "-d", stub_classes, *sorted(stubs.rglob("*.java")))
    run(tc["javac"], "--release", "8", "-encoding", "UTF-8", "-classpath",
        os.pathsep.join(map(str, [tc["android"], stub_classes])), "-d", module, *module_sources)
    run(tc["javac"], "--release", "8", "-encoding", "UTF-8", "-classpath",
        os.pathsep.join(map(str, [tc["android"], splash, splash_jar, mipush, stub_classes, webkit_jar()])), "-d", host, *host_sources)
    return host, splash, module


def unsigned_payload(apk: Path) -> dict[str, str]:
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate output APK entries")
        return {name: digest(archive.read(name)) for name in sorted(names)
                if not name.endswith("/") and not is_signature(name)}


def is_signature(name: str) -> bool:
    upper = name.upper()
    return upper == "META-INF/MANIFEST.MF" or (upper.startswith("META-INF/")
            and upper.endswith((".SF", ".RSA", ".DSA", ".EC")))


def prune_aliased_font_assets(entries: dict[str, bytes], font_manifest: list[dict]) -> None:
    """Remove static MiSans weights only after their families point at bundled VFs."""
    aliased = {
        *(f"assets/flutter_assets/fonts/MiSans-{weight}.ttf" for weight in (250, 305, 330, 380, 450, 520)),
        *(f"assets/flutter_assets/fonts/MiSansTibetan-{weight}.ttf" for weight in (250, 305, 330, 380, 450, 520)),
    }
    used = {"assets/flutter_assets/" + font["asset"]
            for family in font_manifest for font in family["fonts"]}
    variables = {"assets/fonts/MiSansVF.ttf", "assets/fonts/MiSansTibetanVF.ttf"}
    if aliased & used or not aliased <= entries.keys() or not variables <= entries.keys():
        raise ValueError("Static MiSans font alias/asset state changed")
    for name in aliased:
        del entries[name]


def compare_payload(edition: str, actual: dict[str, str], strict: bool) -> dict:
    reference = ROOT / "references" / (edition + ".json")
    if not reference.is_file():
        if strict:
            raise FileNotFoundError(f"Missing final APK payload reference: {reference}")
        return {"reference_present": False}
    data = json.loads(reference.read_text(encoding="utf-8"))
    expected = data.get("payload_sha256", data.get("entries"))
    if not isinstance(expected, dict):
        raise ValueError("Reference must contain payload_sha256 entry/digest mapping")
    expected = {name: value["sha256"] if isinstance(value, dict) else value
                for name, value in expected.items() if not name.endswith("/") and not is_signature(name)}
    missing = sorted(set(expected) - set(actual))
    extra = sorted(set(actual) - set(expected))
    changed = sorted(name for name in set(expected) & set(actual) if expected[name] != actual[name])
    report = {"reference_present": True, "reference_apk_sha256": data.get("apk_sha256"),
              "matched": not (missing or extra or changed), "missing": missing, "extra": extra,
              "changed": changed, "matched_entries": len(set(expected) & set(actual)) - len(changed)}
    print(f"{edition}: payload comparison: {report['matched_entries']} equal, "
          f"{len(changed)} changed, {len(missing)} missing, {len(extra)} extra", flush=True)
    return report


def keystore(args: argparse.Namespace) -> Path | None:
    value = args.keystore or os.environ.get("WEATHER_KEYSTORE_PATH")
    if value:
        return required(Path(value))
    encoded = os.environ.get("WEATHER_KEYSTORE_B64")
    if encoded:
        path = WORK / "signing/ci.p12"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(base64.b64decode(encoded, validate=True))
        return path
    if args.require_signing:
        raise RuntimeError("Release signing requires a keystore secret")
    return None


def build_edition(edition: str, tc: dict[str, Path], inputs: Path,
                  classes: tuple[Path, Path, Path], native: dict[str, Path],
                  key: Path | None, args: argparse.Namespace) -> dict:
    package, filename = EDITIONS[edition]
    stage = WORK / edition
    clean(stage)
    host, splash, module = classes
    host_classes = sorted(host.rglob("*.class"))
    if edition == "normal":
        host_classes = [path for path in host_classes if not path.name.startswith("Test")]
    run(tc["java"], "-cp", tc["d8"], "com.android.tools.r8.D8", "--lib", tc["android"],
        "--min-api", "28", "--output", stage, *host_classes, *sorted(splash.rglob("*.class")),
        inputs / "splash/splash-runtime-isolated.jar", inputs / "mipush/MiPush_SDK_Client_6_0_1-C.jar",
        webkit_jar(),
        required(inputs / "pages/original-weather-pages.dex"), *sorted(module.rglob("*.class")))
    dex=stage / "classes.dex"
    dex.write_bytes(patch_weather_locale(dex.read_bytes()))
    manifest = required(ROOT / "manifests" / (edition + ".xml"))
    import xml.etree.ElementTree as ET
    if ET.parse(manifest).getroot().get("package") != package:
        raise ValueError(f"Unexpected {edition} manifest package")
    run(tc["aapt2"], "link", "-I", tc["android"], "--package-id", "0x7e", "--allow-reserved-package-id",
        "--manifest", manifest, WORK / "widget-res.zip", "-o", stage / "resources.apk")
    entries: dict[str, bytes] = {}
    with zipfile.ZipFile(stage / "resources.apk") as archive:
        entries.update((name, archive.read(name)) for name in archive.namelist() if not name.endswith("/"))
    for path in sorted(stage.glob("classes*.dex")):
        entries[path.name] = path.read_bytes()
    runtime = inputs / "runtime"
    for path in sorted(runtime.rglob("*")):
        if path.is_file():
            name = path.relative_to(runtime).as_posix()
            if not name.startswith(("lib/arm64-v8a/", "assets/")):
                raise ValueError(f"Unexpected runtime entry: {name}")
            entries[name] = path.read_bytes()
    for name, path in native.items():
        entries["lib/arm64-v8a/" + name] = path.read_bytes()
    renderer = "lib/arm64-v8a/libhyper_opengl.so"
    entries[renderer] = patch_mgl_rendering(entries[renderer])
    entries["lib/arm64-v8a/libapp.so"] = patch_widget_settings(patch_dpi_settings(
        required(inputs / "variants" / edition / "libapp.so").read_bytes(), tc["clang"]), tc["clang"])
    # Preserve ALL frozen asset bytes. Only explicitly supplied edition overlays differ.
    for directory in (ROOT / "port/assets", inputs / "variants" / edition / "assets"):
        if directory.is_dir():
            for path in sorted(directory.rglob("*")):
                if path.is_file():
                    name = path.relative_to(directory).as_posix()
                    if edition == "normal" and name.startswith("test-city/"):
                        continue
                    entries["assets/" + name] = path.read_bytes()
    # Text fonts can fall back to Android without unregistering icon/symbol faces.
    font_manifest=json.loads(entries["assets/flutter_assets/FontManifest.json"])
    system_fonts=[family for family in font_manifest if family["family"].startswith("packages/hyperos/")]
    entries["assets/flutter_assets/FontSystem__.json"]=json.dumps(system_fonts,separators=(",",":"),ensure_ascii=False).encode("utf-8")
    # The in-app engine aliases these assets to the bundled variable fonts.
    # No duplicated font data, changes to symbol fonts or widget font assets.
    for family in font_manifest:
        if family["family"] == "sans-serif" or family["family"] == "sans-serif-medium" or family["family"].startswith("mipro-"):
            for font in family["fonts"]:
                font["asset"]="fonts/WeatherMiSansVF.ttf"
        elif family["family"] == "sans-serif-tibt":
            for font in family["fonts"]:
                font["asset"]="fonts/WeatherMiSansTibetanVF.ttf"
    entries["assets/flutter_assets/FontManifest.json"]=json.dumps(font_manifest,separators=(",",":"),ensure_ascii=False).encode("utf-8")
    # Static Latin and Tibetan MiSans weights are superseded by variable fonts.
    # Keep HyperOS icon/symbol, widget, and system font assets.
    prune_aliased_font_assets(entries, font_manifest)
    unsigned = stage / "unsigned.apk"
    with zipfile.ZipFile(unsigned, "w") as output:
        for name, data in entries.items():
            info = zipfile.ZipInfo(name, date_time=(2000, 1, 1, 0, 0, 0))
            # AssetManagerFontStyleSet opens the same variable font for several
            # named weights. Compressed assets inflated the 20 MB MiSans buffer
            # repeatedly on Flutter's layout thread (1.6-1.8 s on the OS4 probe).
            # Stored/aligned font bytes can be mapped directly by both Flutter
            # and Android; keep every glyph/weight and all existing alias paths.
            mapped_asset = name == "resources.arsc" or (
                name.startswith("assets/") and name.lower().endswith((".ttf", ".otf")))
            info.compress_type = zipfile.ZIP_STORED if mapped_asset else zipfile.ZIP_DEFLATED
            output.writestr(info, data)
    aligned = stage / "aligned.apk"
    run(tc["zipalign"], "-f", "-P", "16", "4", unsigned, aligned)
    args.output.mkdir(parents=True, exist_ok=True)
    final = args.output / (filename if key else filename.replace(".apk", "-unsigned.apk"))
    if key:
        if not os.environ.get("WEATHER_KEYSTORE_PASSWORD"):
            raise RuntimeError("Set WEATHER_KEYSTORE_PASSWORD for APK signing")
        signing: list[object] = [tc["java"], "-jar", tc["apksigner"], "sign", "--ks", key,
                                 "--ks-pass", "env:WEATHER_KEYSTORE_PASSWORD"]
        if os.environ.get("WEATHER_KEY_ALIAS"):
            signing.extend(["--ks-key-alias", os.environ["WEATHER_KEY_ALIAS"]])
        if os.environ.get("WEATHER_KEY_PASSWORD"):
            signing.extend(["--key-pass", "env:WEATHER_KEY_PASSWORD"])
        run(*signing, "--out", final, aligned)
        run(tc["java"], "-jar", tc["apksigner"], "verify", "--verbose", final)
    else:
        shutil.copyfile(aligned, final)
    payload = unsigned_payload(final)
    comparison = compare_payload(edition, payload, args.strict_payload)
    report = {"edition": edition, "package": package, "signed": key is not None,
              "apk": final.name, "bytes": final.stat().st_size, "apk_sha256": digest(final.read_bytes()),
              "payload_sha256": payload, "reference_comparison": comparison,
              "toolchain": {"java": "17", "android": SDK_VERSION, "build_tools": BUILD_TOOLS, "ndk": NDK_VERSION}}
    final.with_suffix(".json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Built {final.name}: {report['bytes']} bytes, sha256 {report['apk_sha256']}", flush=True)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--variant", choices=["all", *EDITIONS], default="all")
    parser.add_argument("--inputs", type=Path, default=ROOT / "inputs")
    parser.add_argument("--fetch-inputs", action="store_true")
    parser.add_argument("--inputs-lock", type=Path, default=ROOT / "inputs.lock.json")
    parser.add_argument("--output", type=Path, default=ROOT / "dist")
    parser.add_argument("--keystore", type=Path)
    parser.add_argument("--require-signing", action="store_true")
    parser.add_argument("--strict-payload", action="store_true", help="Fail on any decompressed payload difference from the frozen reference")
    args = parser.parse_args()
    WORK.mkdir(exist_ok=True)
    if args.fetch_inputs:
        args.inputs = fetch_inputs(args.inputs_lock, WORK / "inputs")
    verify_inputs(args.inputs)
    tc = toolchain()
    key = keystore(args)
    try:
        native = compile_native(tc)
        classes = compile_java(tc, args.inputs)
        run(tc["aapt2"], "compile", "--dir", ROOT / "port/res", "-o", WORK / "widget-res.zip")
        editions = list(EDITIONS) if args.variant == "all" else [args.variant]
        reports = [build_edition(name, tc, args.inputs, classes, native, key, args) for name in editions]
        (args.output / "SHA256SUMS.txt").write_text("".join(
            f"{report['apk_sha256']}  {report['apk']}\n" for report in reports), encoding="ascii")
        if args.strict_payload and any(not report["reference_comparison"].get("matched", False) for report in reports):
            raise RuntimeError("Rebuilt APK payload differs from the final reference; see dist/*.json")
        return 0
    finally:
        if os.environ.get("WEATHER_KEYSTORE_B64") and not args.keystore and not os.environ.get("WEATHER_KEYSTORE_PATH"):
            (WORK / "signing/ci.p12").unlink(missing_ok=True)


if __name__ == "__main__":
    sys.exit(main())
