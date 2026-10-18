# Third-party materials

The build is a compatibility port, not a source release of Xiaomi Weather.

| Component | Provenance / terms |
| --- | --- |
| Xiaomi Weather 18.0.0.27-R AOT, HyperOS libraries, MiSans / symbol fonts, artwork | Supplied original application and ROM resources; rights remain with their respective owners. Stored in the pinned binary input archive, not covered by this repository's MIT license. |
| Original rain / AQI pages and map libraries | Derived from the supplied XmsCore weather module; original third-party rights remain applicable. |
| xDL | https://github.com/hexhacking/xDL ; included license at `third_party/xdl/LICENSE`. |
| AndroidX core-splashscreen 1.0.1 | Google Maven; Apache-2.0, license at `third_party/androidx/LICENSE-2.0.txt`. |
| Kotlin standard library 1.6.21 | JetBrains; Apache-2.0. Its complete runtime is namespace-relocated for splash compatibility. |
| Jar Jar Links 1.14.1 | Build-time namespace relocation, Apache-2.0. |
| MiPush SDK 6_0_1-C | Xiaomi SDK, obtained as the unmodified jar from `com.umeng.umsdk:xiaomi-push:6.0.1` on Maven Central. It does not require the Umeng runtime. Xiaomi SDK terms apply. |

Binary inputs are checksum-pinned. Do not replace them with arbitrary newer
libraries and assume identical behavior. MiPush registration/delivery remains
unverified in the initial release.
