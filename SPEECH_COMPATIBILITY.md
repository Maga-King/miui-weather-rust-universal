# 天气播报兼容实现

原版主界面的大温度点击通过 `weather_method_channel` 调用 `tts_init`、
`tts_start_speak` 和 `tts_stop_speak`。开始请求的正文是
`tts_speak_text`，由原版天气页面按当前城市生成。
原先兼容宿主没有实现这三个方法，调用返回未实现，因而没有声音。

`AndroidWeatherSpeech` 现在将这些请求接到 Android `TextToSpeech`，
并经 `weather_tts_channel` 回传原版认识的 `on_start`、`on_done`、
`on_error`。回调沿用请求的 JSON／StandardMethodCodec，初始化完成前不返回成功。

- 使用设备的标准 TTS 引擎；中文播报需要引擎支持中文语音数据。
- 不需要 Root、小米语音系统服务或录音权限；不会采集麦克风音频。
- 两版 Manifest 都声明 TTS 服务查询，以满足 Android 11 起的包可见性要求。
- 直接使用原版传入的播报正文，不拼接组件缓存、不替换当前城市。
- 开始播放由实际引擎回调确认；无引擎、缺语音、超时、音频焦点被拒绝等情况明确返回错误。
- 取消、切后台和销毁会停止播报；切后台释放语音服务。
- 旧的语音完成／音频焦点回调不能结束后续新播报。

本地回归：`python tests/test_weather_speech.py`，需要 `JAVA_HOME` 指向 JDK。
测试编译原始生产桥接代码，用确定性的 Android 替身验证异步初始化、取消、
超时、重试、语言缺失、音频焦点及两种回调编码。它不代替实机发声验收。

2026-10-21：已检测到连接手机提供 `com.oplus.ttsaccessibilityengine`。
无安装的 shell 合成探针被系统终止，不能据此判断 TTS 成败；本次尚未覆盖安装
天气应用，因此真实点击后的播报与界面状态仍待安装验收。
