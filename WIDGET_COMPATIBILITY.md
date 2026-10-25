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

## 2026-10-21 本地天气包核查

用户提供的一加天气 `net.oneplus.weather` 16.40.4 同时包含标准
`TodayWeatherWidgetProvider` 和 Oplus QuickCard／Seedling 私有卡片。包内声明
不同尺寸/内容模板；标准款使用
144dp 的 2×2 RemoteViews，背景来自应用内天气图片与渐变，并非桌面壁纸采样。
本项目现已注册 17 个独立的一加风格标准 Android AppWidget 入口：13 款桌面模板，
加上 4 款原本声明为 secondarylockscreen 的样式转桌面适配。原有 8 款小米组件保留，
普通版和试验版各有 25 个入口。全部使用现有天气数据、逐实例城市设置和统一刷新调度；
未引入 Oplus 卡引擎或常驻服务。私有宿主交互、动效和个别专属数据不可原样复用。
背景图片和天气图标取自原包；当前渐变底色是适配层的近似色，尚未复刻原版
动态色表。底部显示真实降水描述或天气／高低温摘要，不模拟原版建议轮播，
因此这是静态标准组件适配，不能视为原厂引擎或像素级复刻。
已提取的 60 份原资源包括 30 张小卡背景、5 张 OOS 原背景、21 枚天气图标和 4 个 OOS 矢量符号；
它们与原 APK 的哈希一致。其余模板未完整搬入各自 UPK/RPK 专用画材。
桌面选择器的 17 张独立预览由生产绘制器和明确的示例数据生成，不含用户天气信息。

| style | 入口 | 默认桌面格数 | 来源边界 |
| --- | --- | --- | --- |
| 8 | 今日 | 2×2 | 原标准组件 |
| 9 | 快览 | 2×2 | QuickCard 蓝天分支；城市在上，温度/天气图标居中上半区，天气与温度范围贴底 |
| 10 | 逐小时 | 4×2 | QuickCard |
| 11 | 综合预报 | 4×4 | QuickCard |
| 12 | 全景天气 | 4×4 | 原 n2four 可变宽，适配默认取 4×4 |
| 13 / 14 | 简洁横条 / 简洁方卡 | 2×1 / 2×2 | QuickCard Seedling |
| 15 | 通透横条 | 2×1 | UPK 桌面模板；当前为静态底色，没有实时壁纸模糊 |
| 16 | 自适应天气 | 2×2，可缩为 2×1 | UPK 桌面模板，两种画布分别布局 |
| 17–20 | 温度 / 天气实况 / 紫外线 / 日出日落 | 1×1 | 原锁屏模板转为桌面入口 |
| 21–23 | 迷你天气 / 信息横条 / 今日详情 | 1×1 / 2×1 / 2×2 | RPK 模板适配 |
| 24 | 橙色快览 | 2×2 | 独立 OOS 分支，原橙白日夜/深浅背景 |

从连接设备提取的 `com.coloros.weather2` 则采用 RPK／AppCard／Seedling 卡片入口。
所查 `WeatherSeedling.upk` 模板包含 `backdropFilter: blur(15px)`，
但其执行依赖专有卡片宿主；模板声明本身不能证明实际呈现了实时壁纸玻璃。
桌面侧模糊回调及标准组件的名单检查仍构成边界，不能通过复制天气包的
资源或 metadata 就赋予本项目所有桌面同样的能力。

组件逐实例城市设置从完整城市快照中按城市 ID 取值；同一刷新批次不是共用同一
个城市的数据。每轮重新读取底层数据库/raw 缓存，缺失城市不会替换成别的城市。

紫外线组件读取所选城市的真实 `current.uvIndex`；0 是合法值，缺失/非法值显示 `--`，
不伪造指数或等级。缺失城市不回退到其他城市。每实例城市仍从天气设置的
“管理小组件”进入 MIUIX 编辑页，所有模板共享原有刷新间隔和夜间开关。

2026-10-21：普通版及试验版已用原签名覆盖安装到 PJZ110（Android 17），
系统 AppWidget 服务分别识别到全部 25 个入口。普通 UID 测试 APK 完成 59 个场景/118 次
实际绘制，包括 17 款、空数据、横条/方卡切换及复用绘制器与独立绘制器逐像素一致性检查。
另含 OOS 日间/傍晚/夜间与系统深浅色的原背景分支检查。
数据快照测试通过 95 个断言，资源连通性检查确认每个入口都有配置页和独立预览。
已修复重复绘制遗留的画笔透明度/描边、横条画布及数处文字重叠。
这些结果不等于已在所有桌面逐一摆放 16 款，也不代表其他 Android 版本均已实测。

同机桌面摆放后发现，1×1 宿主给出 246×270px、ImageView 零内边距，
相邻两个宿主直接相接；此前 64×64 设计画布被放大为 246px 方块，导致两张底板粘连。
现仅对 style 17–21 限制可见底板至 60dp（与小横条高度一致），
小尺寸宿主至少留出 5% 的两侧空白，内容保持等比居中、圆角由 20 改为 14 设计单位。
当前设备可见底板为 180×180px，两侧各有 33px 空白；宿主尺寸和点击区域不改变。
实测尺寸和更小宿主已纳入绘制检查；其他 12 个非 1×1 尺寸样本与改动前逐像素一致。

快览现有两个独立入口：style 9 保留蓝天分支，style 24 对应 `weather_quick_card_small_oos.xml`。
蓝天版按原布局把城市放在上方、温度放在左侧上半区、天气图标放在右上，天气描述及高低温范围靠底显示。
橙色版按 OOS XML 将温度置于左上、城市紧随其下、图标放在右上，底部显示描述与高低温箭头；
使用 5 张 `sunny_*_2x2.webp` 原图及原矢量符号，24dp 设计圆角、cover 缩放右对齐。
两款都是静态标准 Android 组件，均由独立 Provider、系统预览和逐实例设置注册；OOS 背景按时段和深浅色变化，天气图标来自真实天气。

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
