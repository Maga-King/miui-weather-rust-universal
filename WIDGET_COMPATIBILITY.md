# 桌面小组件与各系统材质兼容

2026-10-19 核查。目标保持普通单 APK、无 Root；当前桌面输出使用标准 Android AppWidget。下列资料均已取得官方正文，OPPO 另检查了公开 SDK 和官方样例。此表区分公开文档与实机验证，不代表已适配所有系统。

| 系统 | 官方第三方机制 | 接入边界 | 桌面实时壁纸玻璃 |
| --- | --- | --- | --- |
| Android 标准 | AppWidget / RemoteViews；响应式尺寸、主题色、圆角等 | 普通 APK 可承载；实际尺寸由桌面返回 | 未取得标准 RemoteViews 背景采样协议；窗口模糊 API 作用于应用自己的窗口 |
| ColorOS / OPlus | 标准组件之外，另有 AppCardWidgetProvider + SmartEngine 插件卡，可进入桌面及负一屏 | 同 APK 可承载，但正式环境需包名、签名、授权码校验与平台运营配置；不等同标准 AppWidget | 公开插件卡文档、1.2.3-external SDK 和样例未发现壁纸模糊入口。流体云高斯背景属于通知模板 |
| OriginOS | 原子组件基于标准 AppWidget / RemoteViews，增加 vivo metadata | 原子组件体系需商店上架、接入申请、审核；普通标准组件无需因此添加 OEM 标记 | 已公开宿主圆角、深浅色规范；未找到第三方实时壁纸模糊协议 |
| One UI | 标准组件；另有三星 Flex Window 盖屏组件扩展 | 已验证的扩展面向 Galaxy Z Flip5 盖屏；不可推导为 Home 桌面协议 | 公开 One UI 设计文档描述模糊视觉，但没有提供 Home AppWidget 背景模糊 API |

“未找到公开接口”不等于断言厂商不存在内部能力或合作协议。`SemBlurInfo`、`semSetBlurInfo` 的公开版本与访问条件未获官方材料确认。历史 Galaxy Edge SDK 面向侧边面板，官方已于 2023-12-05 停止支持，不能用作本项目桌面方案。

## 已验证设备与实现策略

一加当前连接设备的 Android 17 框架存在 `View.setBackdropRenderEffect`，但未标注 `@RemotableViewMethod`，RemoteViews 参数协议也没有 `RenderEffect`。普通 UID、不豁免隐藏 API 的测试中，相关反射/RemoteViews 调用失败；普通 alpha 控制组成功。这个结论仅针对已检查固件，不能扩展成所有厂商或未来版本的结论。

进一步检查用户提供的 OPPO／一加桌面 `com.android.launcher` 17.3.12（170030012）：标准 AppWidget 的背景模糊路径按组件 Provider 类名匹配桌面内置名单，该版本名单只有自带搜索组件；随后才查找标记为 `OplusBlurBg` 的子视图。仅添加这个 tag 不会使普通天气组件通过前置检查，未找到通用的 metadata／options 开关。另一条 `CardBlurHandler.onRequestBlur(View, …)` 是专有卡片的宿主内回调，不是标准 RemoteViews 接口。这里观察到的是类名检查，不能据此声称一定要求系统签名；复用搜索组件类名还会进入搜索专用处理，未采用或验证这种办法。本轮仅记录调查结果，应用内玻璃与标准桌面组件实现没有改动。

共享天气数据、城市选择和刷新调度；标准组件保留为所有桌面的基本路径。尺寸使用桌面提供的 `SizeF` 与系统 density，横条保持原设计比例。新增厂商适配必须有明确接口、版本/宿主能力检查、接入条件和对应设备验证；不得只按品牌名开启材质，或把需要授权的 metadata 当成通用玻璃开关。

无材质能力时使用原模板的非材质背景。半透明底板、壁纸主题取色、静态壁纸图片模糊均不等同实时背景玻璃。现有版本没有为此申请全部文件访问、录屏或无障碍权限。

原 MAML 双城模板已在普通应用 UID 下以正确比例离屏绘出；小横条的原引擎路径尚未通过视觉验证。当前发布代码仍使用标准组件绘制路径，不将研究验证当成已集成。

## 官方资料

- [Android RemoteViews](https://developer.android.google.cn/reference/android/widget/RemoteViews)：受支持的视图及远程修改方法。
- [Android 灵活组件布局](https://developer.android.google.cn/develop/ui/views/appwidgets/layouts)：不同桌面尺寸与响应式布局。
- [Android 窗口模糊](https://source.android.google.cn/docs/core/display/window-blurs)：自有窗口的背景/后方模糊与设备能力。
- [WallpaperManager](https://developer.android.google.cn/reference/android/app/WallpaperManager)：现代 Android 对壁纸像素读取的限制。
- [OPPO 插件卡开发](https://open.oppomobile.com/documentation/page/info?id=11733)及[官方样例](https://github.com/OPPO-OpenPlatform/cardwidgetsupport_android_demo)：Provider、SmartEngine、鉴权和桌面/负一屏通道。
- [OPPO 订阅卡片](https://open.oppomobile.com/documentation/page/info?id=12673)：ColorOS 14.0.1 / API 2.0.1 起的 desktop 入口。
- [OPPO 流体云模板](https://open.oppomobile.com/documentation/page/info?id=12658)：高斯背景描述属于 notification 场景。
- [vivo 技术规范](https://dev.vivo.com.cn/documentCenter/doc/845)、[接入指导](https://dev.vivo.com.cn/documentCenter/doc/850)、[视觉规范](https://dev.vivo.com.cn/documentCenter/doc/835)：Android 小组件基础、审核流程和宿主圆角。
- [三星 One UI 视觉深度](https://developer.samsung.com/one-ui/structure/visual-depth.html)：设计规范，不是桌面模糊接口。
- [三星 Flex Window 组件](https://developer.samsung.com/codelab/galaxy-z/widget-flex-window.html)：盖屏专属 metadata 与适用范围。
- [三星 Galaxy Edge](https://developer.samsung.com/galaxy-edge/overview.html)：SDK 停止支持公告。

官方正文、访问状态、下载哈希、设备诊断保存在本地研究目录，未把手机框架、个人截图、天气缓存或日志加入仓库。
