# 小米天气 RUST 版本通用化

将小米天气 **18.0.0.27-R** 移植为普通 Android 单 APK：无需 Root，无需安装小米桌面、智能助理或额外的运行库 APK。

保留原版天气界面、天气背景动画、MiSans 字体、图标和预报资源；使用应用内兼容层衔接标准 Android 权限、定位、后台任务与通知。这里的「RUST 版本」指原版 HyperOS 天气发行版本，并非把整个天气应用重写为 Rust。

## 下载

到 [Releases](https://github.com/Maga-King/miui-weather-rust-universal/releases) 下载：

| APK | 包名 | 用途 |
| --- | --- | --- |
| `weather-oneplus.apk` | `dev.weather.oneplus` | 普通版，真实天气 |
| `weather-oneplus-experimental.apk` | `dev.weather.test` | 试验版，独立测试城市和天气控制 |

两版可同时安装，不会覆盖 `com.miui.weather2` 或它的数据。试验城市不是实际天气来源，不能用于判断真实预警或推送是否送达。

当前面向 **ARM64、Android 11 及以上**；已在一加 Android 17 和备用小米 HyperOS 4 设备上进行部分实测。其他机型尚未全面验证。

## 已实现与已知限制

- 原版主体界面、字体、图标、动态天气背景及预报入口。
- 标准 Android 定位、后台定位和通知权限对接。
- 标准桌面小组件，保持原有卡片设计；原版 MAML/PAG 动画尚未完整复现。
- 自动更新通常每 30 分钟一次。关闭「夜间自动更新」时 **23:00–次日 04:00** 暂停自动联网；开启后夜间也更新。App 内手动刷新不受这个限制。
- 从天气返回桌面及进程存活时解锁会触发小组件刷新检查。Android 没有适用于所有桌面的实时可见性回调，系统省电策略也可能推迟任务。
- 标准通知发送、取消、图标与本包点击目标已在主力机验证。早晚提醒读取真实今天／明天预报，正式预警读取天气接口数据并去重。
- 「夜间免打扰」独立控制 **23:00–次日 07:00** 的本地通知，不等于夜间自动更新。
- 小米推送 SDK 已嵌入，但首版实测仍停在底层账号注册阶段，**尚未取得有效注册回调，也未验证异常天气服务器推送送达**。本地通知测试通过不代表服务器推送通过。
- 原版降水／空气质量页面的全部交互尚未完成全面验收。

## 构建与最后一版一致性

本仓库以发布时已在手机上安装的最后一版为基准，**未进行字体删减或 APK 瘦身**。

`references/normal.json` 与 `references/test.json` 记录该版 APK SHA-256，以及每个有效 ZIP 条目的内容哈希。首个正式 Release 附件就是这两个原始成品。Actions 会真正重新编译宿主 Java、JNI 与资源，并与基准内容核对；它不会把下载现成 APK 改名当成编译。

原版运行库、AOT、字体、地图资源和第三方 DEX/JAR 单独放在固定的构建输入 Release。`inputs.lock.json` 锁定归档 SHA-256，归档内 `manifest.json` 逐文件校验。无需访问作者的电脑目录、手机或完整 ROM。

APK 的 ZIP 时间戳、签名容器等可能导致重新构建的文件总哈希不同；因此 **内容一致性和 APK 文件逐字节一致是两回事**。自动检查以 `references` 定义的有效条目为准，发布的原始 APK 则可直接验证完整文件 SHA-256。

### GitHub Actions

在 **Actions → Build Android packages → Run workflow** 构建两个版本，下载对应 Artifact。维护者可为 `v*` 标签发布构建产物。

官方仓库通过 Actions Secrets 保存签名配置，私钥不在源码或构建输入中：

- `RELEASE_KEYSTORE_B64`：PKCS#12 签名文件的 Base64。
- `RELEASE_KEYSTORE_PASSWORD`：签名文件密码。

Fork 不继承这些 Secrets。自行设置自己的签名后可以构建，但不同签名不能直接覆盖安装官方版本。

构建脚本及工作流列出了固定版本的 JDK、Android SDK、Build Tools 和 NDK。具体命令以 `python build.py --help` 为准。

## 来源与许可

本项目不是小米官方产品，与小米及一加无隶属关系。小米天气、HyperOS 运行库、字体、地图和相关美术资源保留原权利人的权利；仓库不将这些二进制资源声明为本项目原创或统一开源授权。

新增兼容层代码的许可见 `LICENSE`，第三方组件说明见 `THIRD_PARTY_NOTICES.md`。仓库不包含手机数据库、个人日志、截图、系统密码或签名私钥。
