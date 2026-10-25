#!/usr/bin/env python3
"""Report APK size and native-library compatibility evidence without installing it."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from zipfile import ZIP_DEFLATED, ZipFile


ANDROID = "http://schemas.android.com/apk/res/android"
OLD_STATIC_WEIGHTS = {
    f"assets/flutter_assets/fonts/{family}-{weight}.ttf"
    for family in ("MiSans", "MiSansTibetan")
    for weight in (250, 305, 330, 380, 450, 520)
}


def readelf(readelf_path: Path, *args: str) -> str:
    return subprocess.run([str(readelf_path), *args], check=True,
                          capture_output=True, text=True).stdout


def read_manifest(archive: ZipFile, apk: Path, aapt: Path | None) -> ET.Element:
    data = archive.read("AndroidManifest.xml")
    try:
        return ET.fromstring(data)
    except ET.ParseError:
        if aapt is None:
            raise ValueError("Compiled AndroidManifest.xml requires --aapt from Android Build Tools")
    dump = subprocess.run([str(aapt), "dump", "xmltree", str(apk), "AndroidManifest.xml"],
                          check=True, capture_output=True, text=True, encoding="utf-8").stdout
    stack = []
    root = None
    for line in dump.splitlines():
        element = re.match(r"( *)E: ([^ ]+)", line)
        if element:
            indent = len(element[1])
            while stack and stack[-1][0] >= indent:
                stack.pop()
            node = ET.Element(element[2])
            if stack:
                stack[-1][1].append(node)
            else:
                root = node
            stack.append((indent, node))
            continue
        attr = re.match(r" *A: ([^ (=]+)(?:\([^)]*\))?=(.*)", line)
        if not attr or not stack:
            continue
        key, raw = attr[1], attr[2]
        if key.startswith("android:"):
            key = "{" + ANDROID + "}" + key[8:]
        quoted = re.match(r'"([^"]*)"', raw)
        typed = re.match(r"\(type (0x[0-9a-fA-F]+)\)(0x[0-9a-fA-F]+)", raw)
        if quoted:
            value = quoted[1]
        elif typed:
            value = ("true" if int(typed[2], 16) else "false") if int(typed[1], 16) == 0x12 else str(int(typed[2], 16))
        else:
            value = raw
        stack[-1][1].set(key, value)
    if root is None or root.tag != "manifest" or root.find("application") is None:
        raise ValueError("aapt did not return a complete APK manifest")
    return root


def audit(apk: Path, readelf_path: Path, android_api_libs: Path, aapt: Path | None = None) -> dict:
    with ZipFile(apk) as archive:
        infos = [entry for entry in archive.infolist() if not entry.is_dir()]
        names = {entry.filename for entry in infos}
        if len(names) != len(infos):
            raise ValueError("APK contains duplicate entries")

        manifest = read_manifest(archive, apk, aapt)
        app = manifest.find("application")
        native_extraction = app is not None and app.get(f"{{{ANDROID}}}extractNativeLibs") == "true"
        uses_libraries = [
            {"name": item.get(f"{{{ANDROID}}}name"),
             "required": item.get(f"{{{ANDROID}}}required", "true")}
            for tag in ("uses-library", "uses-native-library")
            for item in manifest.findall(tag)
            + ([] if app is None else app.findall(tag))
        ]
        fonts = json.loads(archive.read("assets/flutter_assets/FontManifest.json"))
        font_assets = sorted({"assets/flutter_assets/" + font["asset"]
                              for family in fonts for font in family["fonts"]})
        engine_font_aliases = {
            "assets/flutter_assets/fonts/WeatherMiSansVF.ttf": "assets/fonts/MiSansVF.ttf",
            "assets/flutter_assets/fonts/WeatherMiSansTibetanVF.ttf": "assets/fonts/MiSansTibetanVF.ttf",
        }
        missing_fonts = [name for name in font_assets
                         if name not in names and engine_font_aliases.get(name) not in names]
        native_entries = [entry for entry in infos
                          if entry.filename.startswith("lib/") and entry.filename.endswith(".so")]
        compressed_native = [entry.filename for entry in native_entries
                             if entry.compress_type == ZIP_DEFLATED]
        uncompressed_native = [entry.filename for entry in native_entries
                               if entry.compress_type != ZIP_DEFLATED]
        bundled_libs = {Path(entry.filename).name for entry in native_entries}

        dependencies: dict[str, list[str]] = {}
        undefined_symbols: dict[str, list[str]] = {}
        with tempfile.TemporaryDirectory(prefix="weather-apk-audit-") as temp:
            temp_path = Path(temp)
            for entry in native_entries:
                leaf = Path(entry.filename).name
                extracted = temp_path / leaf
                extracted.write_bytes(archive.read(entry.filename))
                dynamic = readelf(readelf_path, "-d", str(extracted))
                dependencies[leaf] = re.findall(r"Shared library: \[(.*?)\]", dynamic)
                symbols = readelf(readelf_path, "--dyn-syms", "--wide", str(extracted))
                undefined_symbols[leaf] = [
                    line.split()[-1] for line in symbols.splitlines()
                    if re.search(r"\bUND\b", line) and line.split()
                ]

            api_libs = {path.name for path in android_api_libs.glob("*.so")}
            missing_system_libraries = sorted({needed for deps in dependencies.values()
                                               for needed in deps
                                               if needed not in bundled_libs and needed not in api_libs})

            roots = ("libapp.so", "libhyper_os_flutter.so", "libhyper_opengl.so",
                     "libweatherhost.so", "libweathercompat.so", "libweather_app.so")
            reachable: dict[str, list[str]] = {}
            for root in roots:
                if root not in dependencies:
                    continue
                seen = {root}
                pending = [root]
                while pending:
                    current = pending.pop()
                    for needed in dependencies[current]:
                        if needed in bundled_libs and needed not in seen:
                            seen.add(needed)
                            pending.append(needed)
                reachable[root] = sorted(seen)

            selected = {root: {"dependencies": dependencies[root],
                               "unresolved_android13_libraries": sorted(
                                   needed for needed in dependencies[root]
                                   if needed not in bundled_libs and needed not in api_libs)}
                        for root in roots if root in dependencies}

        groups: dict[str, list[int]] = {}
        for entry in infos:
            name = entry.filename
            if name.startswith("lib/"):
                group = "native"
            elif name.endswith(".dex"):
                group = "dex"
            elif name.startswith("assets/flutter_assets/"):
                group = "flutter_assets"
            elif name.startswith("assets/"):
                group = "other_assets"
            elif name.startswith("res/") or name == "resources.arsc":
                group = "resources"
            else:
                group = "metadata"
            item = groups.setdefault(group, [0, 0, 0])
            item[0] += 1
            item[1] += entry.file_size
            item[2] += entry.compress_size

        return {
            "apk": str(apk),
            "package": manifest.get("package"),
            "bytes": apk.stat().st_size,
            "groups": groups,
            "native_entry_count": len(native_entries),
            "native_compression": {"extractNativeLibs": native_extraction,
                                   "deflated_count": len(compressed_native),
                                   "uncompressed_count": len(uncompressed_native),
                                   "unexpected_uncompressed": uncompressed_native},
            "uses_library_declarations": uses_libraries,
            "old_static_weight_entries_remaining": sorted(OLD_STATIC_WEIGHTS & names),
            "missing_font_manifest_assets": missing_fonts,
            "api33_system_library_count": len(api_libs),
            "unresolved_needed_libraries": missing_system_libraries,
            "load_roots": selected,
            "reachable_bundled_libraries": reachable,
        }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--readelf", type=Path, required=True)
    parser.add_argument("--aapt", type=Path, help="Android Build Tools aapt executable for compiled manifests")
    parser.add_argument("--android-api-libs", type=Path, required=True,
                        help="NDK sysroot/usr/lib/aarch64-linux-android/33")
    parser.add_argument("--json", type=Path)
    parser.add_argument("--check", action="store_true",
                        help="fail for stale fonts, missing declared fonts, or unexpected native compression")
    args = parser.parse_args()
    report = audit(args.apk, args.readelf, args.android_api_libs, args.aapt)
    output = json.dumps(report, indent=2, ensure_ascii=False) + "\n"
    if args.json:
        args.json.write_text(output, encoding="utf-8")
    print(output, end="")
    if args.check:
        compression = report["native_compression"]
        if not compression["extractNativeLibs"]:
            raise SystemExit("APK must keep extractNativeLibs=true for compressed native libraries")
        if compression["unexpected_uncompressed"]:
            raise SystemExit("Unexpected uncompressed native entries")
        if report["old_static_weight_entries_remaining"]:
            raise SystemExit("Superseded static MiSans weights remain in the APK")
        if report["missing_font_manifest_assets"]:
            raise SystemExit("FontManifest references missing APK assets")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
