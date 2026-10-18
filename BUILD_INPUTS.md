# Frozen build input interface

The portable build recompiles the host and compatibility native libraries, every host/module/splash Java source, DEX linkage, resources and manifests. Original Xiaomi/AMap runtime code remains a binary dependency. Fonts and every other frozen asset retain their final APK bytes. No whole ROM, original Weather APK, XmsCore APK, probe APK, device data or signing key is needed in the repository.

## Source tree

```text
build.py
port/*.java
port/host.c
port/res/**                         # Last prepared XML/image resources; do not regenerate fonts
port/assets/**                      # Optional owned asset overlays, including test-city schema
native/shim.c
native/shim.S
third_party/xdl/*.c
third_party/xdl/*.h
third_party/xdl/include/xdl.h
third_party/xdl/LICENSE
weather-pages-module/src/**/*.java
weather-pages-module/compile-stubs/**/*.java
splash/src/androidx/core/splashscreen/R.java
splash/src/dev/weather/oneplus/AndroidStartupSplash.java
manifests/normal.xml                 # Last merged, readable build-stage manifest
manifests/test.xml
references/normal.json
references/test.json
inputs.lock.json
```

Keep xDL at commit `1e0b6254165a2ddcbd32f77a371700c69155acf8`. The generated native `shim.c`/`shim.S` are build source inputs; rebuilding them needs no private symbol-analysis reports. The splash Java files are the last generated source, rather than recompiling proprietary Java definitions.

## Runtime archive

The archive root must directly contain these paths (no extra enclosing `inputs/` folder):

```text
manifest.json
runtime/lib/arm64-v8a/*.so           # Final normal APK libraries, full set; two owned .so files are rebuilt
runtime/assets/**                   # All final normal APK assets, byte-for-byte
variants/normal/libapp.so            # Final normal APK AOT, already relocated and patched
variants/test/libapp.so              # Final test APK AOT, already relocated and patched
variants/test/assets/**              # Any additional/different test-edition assets
pages/original-weather-pages.dex     # Existing adapted upstream module DEX, before merging with host
splash/splash-runtime-isolated.jar   # Existing isolated AndroidX/Kotlin dependency
mipush/MiPush_SDK_Client_6_0_1-C.jar
```

`runtime/lib` may include the old `libweatherhost.so` and `libweathercompat.so` as baseline evidence; both are always replaced with newly compiled outputs. It may also include normal `libapp.so`; the selected edition AOT always overrides it. The current normal APK contains 167 native libraries; no dependency reduction is performed.

`manifest.json` verifies every actual input file. Additional metadata fields are permitted:

```json
{"schema":1,"files":{"runtime/assets/example.bin":"64-character-sha256","pages/original-weather-pages.dex":{"sha256":"64-character-sha256","bytes":7056444}}}
```

Use the existing premerge pages DEX (SHA-256 `e84cd2266339bb63e847c227f673ae66417d339c1b0ea58926147e4a4831d3bb`). Its 7,038 classes are all present in the final merged APK, but extracting/repartitioning the merged DEX is unnecessary when the premerge module is available. The MiPush JAR must match SHA-256 `bce8d29a119c0a85fd0e56e577ec291ddfb8edda1c0f901f3de467b7ca578644`.

The large archive belongs in a pinned GitHub Release asset. The repository's `inputs.lock.json` contains its permanent HTTPS URL and SHA-256:

```json
{"url":"https://github.com/OWNER/REPO/releases/download/build-inputs-v1/inputs.zip","sha256":"64-character-archive-sha256"}
```

For each edition, `references/EDITION.json` records decompressed entries from the final reference APK:

```json
{"apk_sha256":"reference-whole-file-sha256","payload_sha256":{"classes.dex":"entry-sha256","assets/example.bin":"entry-sha256"}}
```

All payload entries are compared, including manifests, DEX, resources, native libraries and assets. ZIP timestamps, compression and APK signing material are excluded. Comparisons are emitted in `dist/*.json`; `--strict-payload` fails on any difference. This measures the rebuilt payload, not copied reference APK containers.

## Local and Actions builds

Use Windows, Python 3.12, JDK 17, Android SDK platform 36, Build Tools 35.0.0 and NDK 29.0.14206865. Set `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) and `JAVA_HOME`; the build does not access the original workstation. Compiler prefix maps preserve the reference libraries' historical `__FILE__` strings so a new checkout produces identical native bytes. Python requires no third-party packages.

```powershell
python build.py --inputs C:\path\to\extracted-inputs --variant all --strict-payload
python build.py --fetch-inputs --variant all --strict-payload
```

Without signing secrets, the build emits `*-unsigned.apk`. For signed builds, set `WEATHER_KEYSTORE_PATH` or `WEATHER_KEYSTORE_B64` plus `WEATHER_KEYSTORE_PASSWORD`; optional `WEATHER_KEY_ALIAS` and `WEATHER_KEY_PASSWORD` are supported. `--require-signing` refuses unsigned release output. Signing passwords are passed to apksigner through environment references and never printed as command-line values. Decoded CI key material is deleted after the build. Store the existing private key in GitHub Actions secrets to preserve installed-app update compatibility; never commit it.

The workflow builds on version tags (`v*`) and manual dispatch. Configure GitHub repository secrets `RELEASE_KEYSTORE_B64` and `RELEASE_KEYSTORE_PASSWORD`; optional `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` map to the corresponding build environment variables. Tags or a manual `release_tag` require signing and publish the compiled APKs, per-entry comparison reports and SHA256SUMS. A successful strict build is required before publication.

## Public repository exclusions

Exclude `probe/probe-keystore.p12`, all other private signing keys, SDK registration state (`regSec`, registration IDs), app databases/preferences, city/location snapshots, account/network dumps, logcat, jobs/device reports, screenshots, full ROM/framework packages, `.git` from downloaded dependencies and build/cache outputs. The original embedded MiPush application ID/token are client configuration already carried by the upstream app; they are not a private release-signing key or a server credential. Keep upstream licenses and identify the binary runtime separately from the repository's own source license.
