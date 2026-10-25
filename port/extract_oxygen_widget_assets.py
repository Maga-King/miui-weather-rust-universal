#!/usr/bin/env python3
"""Extract Oxygen 16.40.4 standard and OOS widget art used by our renderer.

Usage: python port/extract_oxygen_widget_assets.py PATH_TO_APKTOOL_DECODED
This script does not modify the source APK or install anything.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path

BACKGROUNDS = (
    "weather_quick_card_sunday_dawn_small_16_bg.webp",
    "weather_quick_card_sunday_morning_small_16_bg.webp",
    "weather_quick_card_sunday_noon_small_16_bg.webp",
    "weather_quick_card_sunday_af_small_16_bg.webp",
    "weather_quick_card_sunday_nightfall_small_16_bg.webp",
    "weather_quick_card_sunday_night_small_16_bg.webp",
    "weather_quick_card_cloudy_morning_small_16_bg.webp",
    "weather_quick_card_cloudy_noon_small_16_bg.webp",
    "weather_quick_card_cloudy_af_small_16_bg.webp",
    "weather_quick_card_cloudy_nightfall_small_16_bg.webp",
    "weather_quick_card_cloudy_night_small_16_bg.webp",
    "weather_quick_card_overcast_morning_small_16_bg.webp",
    "weather_quick_card_overcast_noon_small_16_bg.webp",
    "weather_quick_card_overcast_nightfall_small_16_bg.webp",
    "weather_quick_card_overcast_night_small_16_bg.webp",
    "weather_quick_card_fog_noon_night_small_16_bg.webp",
    "weather_quick_card_fog_night_small_16_bg.webp",
    "weather_quick_card_heavy_rain_noon_small_16_bg.webp",
    "weather_quick_card_thunderstorm_noon_small_16_bg.webp",
    "weather_quick_card_thundershower_small_16_bg.webp",
    "weather_quick_card_middle_rain_noon_small_16_bg.webp",
    "weather_quick_card_rain_noon_small_16_bg.webp",
    "weather_quick_card_sleeting_small_16_bg.webp",
    "weather_quick_card_heavy_snow_noon_small_16_bg.webp",
    "weather_quick_card_light_snow_noon_small_16_bg.webp",
    "weather_quick_card_middle_snow_noon_small_16_bg.webp",
    "weather_quick_card_sand_dust_small_16_bg.webp",
    "weather_quick_card_hail_noon_small_16_bg.webp",
    "weather_quick_card_smog_small_16_bg.webp",
    "weather_quick_card_smog_night_small_16_bg.webp",
)
ICONS = (
    "card_sunny.webp", "card_sunny_night.webp", "card_cloudy.webp",
    "card_cloudy_night.webp", "card_overcast.webp", "card_fog.webp",
    "card_storm.webp", "card_thunder_storm.webp", "card_thunder_shower.webp",
    "card_heavy_rain.webp", "card_middle_rain.webp", "card_light_rain.webp",
    "card_sleet.webp", "card_heavy_snow.webp", "card_light_snow.webp",
    "card_middle_snow.webp", "card_sand_dust.webp", "card_blowing_dust.webp",
    "card_hail.webp", "card_smog.webp", "card_freezing_rain.webp",
)
OOS_BACKGROUNDS = tuple("weather_quick_card_sunny_" + name + "_2x2.webp"
                        for name in ("daytime", "evening", "night", "dark_daytime", "dark_evening"))


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("decoded", type=Path, help="apktool decoded Oxygen Weather 16.40.4 directory")
    args = parser.parse_args()
    source = args.decoded.resolve()
    metadata = (source / "apktool.yml").read_text(encoding="utf-8")
    manifest = (source / "AndroidManifest.xml").read_text(encoding="utf-8")
    if "versionName: 16.40.4" not in metadata or 'package="net.oneplus.weather"' not in manifest:
        raise SystemExit("Expected net.oneplus.weather 16.40.4 apktool decode")
    port = Path(__file__).resolve().parent
    output = port / "assets" / "oxygen_widget"
    inventory = []
    groups = (
        ("backgrounds", "drawable-xxxhdpi", BACKGROUNDS),
        ("icons", "drawable-xxhdpi", ICONS),
        ("oos_backgrounds", "drawable-xxxhdpi", OOS_BACKGROUNDS),
    )
    for group, source_dir, names in groups:
        target_dir = output / group
        target_dir.mkdir(parents=True, exist_ok=True)
        for name in names:
            original = source / "res" / source_dir / name
            if not original.is_file():
                raise SystemExit(f"Missing expected APK resource: {original}")
            target = target_dir / name
            shutil.copyfile(original, target)
            inventory.append({"source": str(original.relative_to(source)).replace("\\", "/"),
                              "destination": str(target.relative_to(port)).replace("\\", "/"),
                              "bytes": target.stat().st_size, "sha256": sha256(target)})
    # Picker previews are generated from synthetic weather, not copied over here.
    for suffix in ("degree_dark", "degree_light", "icon_arrow_dark_up", "icon_arrow_light_up"):
        original = source / "res/drawable" / ("weather_quick_card_" + suffix + ".xml")
        target = port / "res/drawable" / ("weather_oxygen_oos_" + suffix + ".xml")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(original, target)
        inventory.append({"source": original.relative_to(source).as_posix(),
                          "destination": target.relative_to(port).as_posix(),
                          "bytes": target.stat().st_size, "sha256": sha256(target)})
    (output / "source_manifest.json").write_text(
        json.dumps({"package": "net.oneplus.weather", "version": "16.40.4",
                    "resource_count": len(inventory), "resources": inventory},
                   ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Copied {len(inventory)} resources, {sum(i['bytes'] for i in inventory):,} bytes")


if __name__ == "__main__":
    main()
