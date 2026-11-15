# Third-party materials

The build is a compatibility port, not a source release of Xiaomi Weather.

| Component | Provenance / terms |
| --- | --- |
| Xiaomi Weather 18.0.0.27-R AOT, HyperOS libraries, MiSans / symbol fonts, artwork | Supplied original application and ROM resources; rights remain with their respective owners. Stored in the pinned binary input archive, not covered by this repository's MIT license. |
| Original rain / AQI pages and map libraries | Derived from the supplied XmsCore weather module; original third-party rights remain applicable. |
| OnePlus Weather 16.40.4 widget artwork | Selected static assets from the user-supplied `net.oneplus.weather` APK, used by the additional standard Android widget prototype. Original rights remain with their owners; not covered by the compatibility code's MIT license. No Oplus system service or card-engine binary is bundled. |
| xDL | https://github.com/hexhacking/xDL ; included license at `third_party/xdl/LICENSE`. |
| AndroidX core-splashscreen 1.0.1 | Google Maven; Apache-2.0, license at `third_party/androidx/LICENSE-2.0.txt`. |
| Kotlin standard library 1.6.21 | JetBrains; Apache-2.0. Its complete runtime is namespace-relocated for splash compatibility. |
| Jar Jar Links 1.14.1 | Build-time namespace relocation, Apache-2.0. |
| MiPush SDK 6_0_1-C | Xiaomi SDK, obtained as the unmodified jar from `com.umeng.umsdk:xiaomi-push:6.0.1` on Maven Central. It does not require the Umeng runtime. Xiaomi SDK terms apply. |
| Sherpa ONNX 2.0.1 ARM64 CPU JNI and Kotlin API | Official [static ONNX Runtime AAR](https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-static-link-onnxruntime-2.0.1.aar), checksum pinned by `build.py`; Apache-2.0. Only ARM64 JNI and classes are bundled. License at `third_party/neural_speech/SHERPA-LICENSE`. |
| ONNX Runtime 1.28.2 statically linked in Sherpa JNI | Version identified by pinned Sherpa `v2.0.1/cmake/onnxruntime-android-aarch64-static.cmake`. Microsoft, MIT; license at `third_party/neural_speech/ONNXRUNTIME-LICENSE`, third-party inventory in `third_party/neural_speech/ONNXRUNTIME-NOTICES`. Notices list upstream build possibilities, not a claim every listed feature is linked. |
| ONNX Runtime Android 1.22.0 direct Java/JNI engine | Microsoft, MIT; official Maven artifact `com.microsoft.onnxruntime:onnxruntime-android:1.22.0`. Direct ARM64 Java/JNI dependency for the optional GPT-SoVITS path. Version-specific complete license and upstream inventory at `third_party/neural_speech/ONNXRUNTIME-1.22.0-LICENSE` and `third_party/neural_speech/ONNXRUNTIME-1.22.0-NOTICES`. The full upstream inventory is preserved without asserting that every listed backend is in the Android build. |
| Kotlin standard library 2.2.0 | JetBrains, Apache-2.0; license at `third_party/neural_speech/KOTLIN-LICENSE`. Kotlin names and new Sherpa/host references are namespace-relocated to avoid the original R8-obfuscated Kotlin definitions; native Sherpa class names stay intact. Separate from the splash runtime. |
| Apache Commons Compress 1.21 | Apache Software Foundation, Apache-2.0. Model import uses BZip2/TAR decoding with exact archive hashes and bounded extraction. |
| Optional Kokoro v1.1 Chinese int8 speech model and voices | [hexgrad/Kokoro-82M-v1.1-zh](https://huggingface.co/hexgrad/Kokoro-82M-v1.1-zh), Apache-2.0; ONNX conversion archive distributed by Sherpa. Model weights are user-selected downloads, absent from the APK. 103 voice names follow ONNX metadata. No character voice or emotion claims. |
| Optional model archive espeak-ng-data | Included unchanged in the official Kokoro archive, outside the APK. eSpeak NG upstream GNU GPL v3 or later; license at `third_party/neural_speech/ESPEAK-COPYING`. Sherpa 2.0.1 no longer uses eSpeak NG; synthesis uses the supplied phoneme lexicons. This is not a bundled eSpeak native engine. |
| Native Sherpa support code | Upstream Sherpa transitively includes supporting code such as OpenFst/Kaldi components (Apache-2.0), Eigen (MPL-2.0) and ONNX/protobuf support. Keep upstream notices and terms; primary Apache license at `third_party/neural_speech/APACHE-LICENSE`, Eigen MPL at `third_party/neural_speech/EIGEN-COPYING.MPL2`. The static library is not described as entirely Apache-2.0. |
| Genie-TTS Chinese frontend and shared pronunciation resources | [High-Logic/Genie-TTS](https://github.com/High-Logic/Genie-TTS), source revision `d347fd0f8683e9a362b69f59fa0a4799ddb5e828`, and [High-Logic/Genie](https://huggingface.co/High-Logic/Genie) shared data; MIT. Copyright (c) 2025 High_Logic; complete license at `third_party/neural_speech/GENIE-TTS-LICENSE`. Chinese normalization/sandhi copies retain their separate Paddle attribution below. |
| GPT-SoVITS upstream inference/export logic and phoneme resources | [RVC-Boss/GPT-SoVITS](https://github.com/RVC-Boss/GPT-SoVITS), Copyright (c) 2024 RVC-Boss; MIT. Complete license at `third_party/neural_speech/GPT-SOVITS-LICENSE`. This software license does not cover third-party character weights or reference recordings. |
| g2pM 0.1.2.5 pronunciation model and character/class tables | [kakaobrain/g2pM](https://github.com/kakaobrain/g2pM), Apache-2.0; complete license at `third_party/neural_speech/G2PM-LICENSE`. The APK's small `g2pm.onnx` is a conversion of the original pronunciation BiLSTM and has no character voice or reference recording. CEDICT-derived pronunciation data is separately attributed below. |
| Jieba 0.42.1 dictionary and POS/word HMM tables | [fxsjy/jieba](https://github.com/fxsjy/jieba), Copyright (c) 2013 Sun Junyi; MIT. Complete license at `third_party/neural_speech/JIEBA-LICENSE`. Original probabilities/frequencies are serialized in the Chinese lexicon and used by the Java segmentation port. |
| pypinyin 0.55.0 and pinyin-data | [mozillazg/python-pinyin](https://github.com/mozillazg/python-pinyin) and [mozillazg/pinyin-data](https://github.com/mozillazg/pinyin-data), MIT. Exported character tones and pronunciation conversion retain licenses at `third_party/neural_speech/PYPINYIN-LICENSE` and `third_party/neural_speech/PINYIN-DATA-LICENSE`. |
| Paddle Chinese normalization and tone sandhi | PaddlePaddle Authors, Copyright (c) 2020 and 2021; Apache-2.0. The Java port adapts the Genie copies for weather numbers, units and phoneme alignment. Complete license at `third_party/neural_speech/APACHE-LICENSE`, copyright attribution at `third_party/neural_speech/PADDLE-FRONTEND-NOTICE`. |
| Chinese RoBERTa vocabulary | [hfl/chinese-roberta-wwm-ext-large](https://huggingface.co/hfl/chinese-roberta-wwm-ext-large), Apache-2.0 model-card terms; shared Genie tokenizer vocabulary. Complete license at `third_party/neural_speech/APACHE-LICENSE`, source/authors at `third_party/neural_speech/HFL-BERT-NOTICE`. Only the vocabulary is bundled; the large RoBERTa model is external. |
| CEDICT-derived pronunciation subset | The Chinese lexicon includes g2pM's `digest_cedict.pkl` pronunciation lists. CC-CEDICT contributors and original CEDICT author Paul Denisowski are credited; the applicable upstream Attribution-ShareAlike data terms remain separate from the repository's MIT license. The packaged g2pM snapshot/license version is unverified; historical 3.0 and current 4.0 legal texts and exact scope are preserved in `third_party/neural_speech/TIBAO-FRONTEND-NOTICES.md` and adjacent `CC-CEDICT-BY-SA-*` files. |
| Optional external Tribbie GPT-SoVITS V2ProPlus weights/reference material | Source collection declares CC-BY-NC 4.0 and additionally prohibits secondary redistribution. Participants 红血球AE3803 and 白菜工厂1145号员工; character voice rights remain with miHoYo/COGNOSPHERE. `port/assets/tibao_models/manifest.json` is only an import hash allowlist. Character weights, converted derivatives, original/user recordings and reference tensors are excluded from public source and Release attachments. Source conditions at `third_party/neural_speech/TIBAO-PRIVATE-MODEL-TERMS.md`. |

Binary inputs are checksum-pinned. Do not replace them with arbitrary newer
libraries and assume identical behavior. MiPush registration/delivery remains
unverified in the initial release.

Speech dependency source links and model installation/verification details are recorded in
`SPEECH_COMPATIBILITY.md`. Licenses for the optional data are not the repository's MIT
license. Before redistributing native dependency binaries, preserve their upstream
third-party notices as well as the primary component licenses.

Chinese frontend asset provenance, converted-data scope and separate data licenses
are described in `third_party/neural_speech/TIBAO-FRONTEND-NOTICES.md`. A dependency
license or a public model download does not grant rights to redistribute a private
user recording or the restricted character-model collection.
