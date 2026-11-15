# 天气播报兼容实现

原版大温度数字通过 `weather_method_channel` 调用 `tts_init`、`tts_start_speak` 和 `tts_stop_speak`。桥接到标准 Android `TextToSpeech`，保留原版当前城市正文、回调和音频焦点。无需 Root、小米语音系统服务或录音权限。取消、切后台和销毁会停止播报；旧回调不能结束后续新播报。

## 当前可选本地语音

系统 TTS 是默认选项。设置中的“天气播报”可切换到 Kokoro 中文模型，或导入专门准备的缇宝 GPT-SoVITS V2ProPlus Android ZIP，保留预设 13、19、20。模型不嵌入 APK，未导入时不假报可用。

Kokoro 使用官方 [int8 中文多语言包](https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_1.tar.bz2)，精确大小 147,052,523 字节，SHA-256 `259c9c8bc906d6078e4e0570421885dabb68b539c2f970fcbb890a58eeaf3e9c`。缇宝只提供标准 Android 文件选择器导入，原始 PyTorch 包不能直接使用。角色权重、转换后的参考张量及私人参考音频不随公开 Release 提供。

导入采用哈希清单、条目和体积限制、临时目录及原子提交。模型保存在应用私有 no-backup 目录；合成时才在独立 `:weather_speech` 进程加载。查询声音不加载大模型；取消和闲置后释放资源。

## 延迟限制

缇宝已在备用 Android 手机上导入并实际发声，用户反馈首次试听约等 50 秒。当前会加载模型并生成完整首句后再开始播放，本地首段超时为 90 秒。这不是实时流式播报，切换或释放模型后仍可能再次长时间等待；本次发布暂不继续性能优化。模型体积约 1.17 GB，另外存储。

当前中文前端保留原始 g2pM、分词及实际 RoBERTa 特征；28 个天气、城市、单位案例的音素／token 与原实现一致，一组 `[61,1024]` float16 BERT 特征最大绝对差为 0。该结果不表示音质或所有手机的性能已验收。

## 回归与打包

- `tests/test_weather_speech.py`：132 项断言，覆盖三种后端、回调、90 秒超时、取消及音频焦点。
- `tests/test_neural_speech.py`：96 项安装器和服务断言，采用确定性合成替身，不代表实机合成速度。
- `tests/test_neural_kotlin_isolation.py`：Kotlin 命名隔离、JNI 类型化回调和可重复 jar。

原天气网页 DEX 的 Kotlin ABI 经 R8 改写，新语音依赖隔离到 `dev.weather.speech.kotlin`，Sherpa JNI 类名保持不变。直接 ONNX Runtime 固定 1.22.0，Sherpa 固定 2.0.1，构建时校验下载哈希。许可证与数据来源见 [第三方声明](THIRD_PARTY_NOTICES.md)；相关声明同时写入 APK。
