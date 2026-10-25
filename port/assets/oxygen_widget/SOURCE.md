# Oxygen widget art provenance

These assets were copied from the locally decoded net.oneplus.weather 16.40.4
APK (versionCode 16040004), using port/extract_oxygen_widget_assets.py.
source_manifest.json records each APK res/ path, byte count, and SHA-256.

The 30 small-card background WebPs originate in res/drawable-xxxhdpi, the 21
weather icon WebPs in res/drawable-xxhdpi, and the 2x2 picker preview in
res/drawable-xxxhdpi/app_widget_preview_pic.webp. Background art is rendered
over an app-side gradient; the preview is used only in the widget picker.
The current gradient colors are approximate fallback colors chosen for this
prototype, not extracted values from the original dynamic background palette.

The original assets and design are owned by their respective rights holders.
This is a local, source-attributed port prototype; redistribution terms have
not been determined.
