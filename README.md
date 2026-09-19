# NoAd

> 一个 Android 广告拦截应用，自动跳过应用开屏广告与应用内弹窗广告。
>
> ⚠️ **项目开发中** —— 当前完成度较低，本 README 仅作初步介绍，全部功能完成后会补充完整文档。

---

## 这是什么

手机上大量应用在启动时展示开屏广告、在使用中弹出插屏广告。手动点击「跳过」既打断操作，
部分广告的关闭按钮还做得极小、延迟出现，甚至伪装成其他按钮。

NoAd 的目标是**自动完成这些点击**，让广告出现即消失。此外还规划了网络层的域名过滤能力，
用于拦截网页横幅与追踪域名。

本项目**仅个人自用与开源研究**，不以上架应用商店为目标。

## 目前能做什么

| 能力 | 状态 |
|---|---|
| **S1 无障碍自动点击**（开屏/弹窗广告） | ✅ 已实现，可实机使用 |
| S2 本地 DNS 过滤（不接管全流量） | 🚧 未开始 |
| S3 全流量 VPN 过滤 | 🚧 未开始 |
| S4 Shizuku 应用级断网 | 🚧 未开始 |
| 内置域名规则库（60 黑 + 6 白） | ✅ 已实现 |
| 拦截日志 / 统计 / 应用管理 | ✅ 基础可用 |

> **S1 已可用但需要你手动配置**，见下方「如何使用」。S2/S3/S4 目前只是界面上的占位，
> 会如实显示为「未接入」，不会伪装成已生效。

## 四种策略的分工

它们**不是可替换的同类选项**，而是覆盖不同层面的问题：

```
S1 无障碍点击 ── UI 层：能"看见并点击"的广告（开屏、弹窗）
S2 DNS 过滤  ── 域名层：让广告服务器域名解析失败
S3 VPN 过滤  ── 网络层：按规则丢弃/重写请求（能力最强，代价独占 VPN）
S4 应用断网  ── UID 层：按包名切断整个应用联网（不占 VPN，需 Shizuku）
```

**关键约束**：Android 同一时刻只允许一个 VpnService 运行。
因此 S2 与 S3 **互斥**；S1 与它们可并存；S4 不占 VPN，可与任意组合叠加。

**诚实说明**：信息流原生广告（广告内容与正文同服务器同域名）**四种策略都拦不住**，
这是技术边界而非缺陷。

## 如何使用

### 安装

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 开启 S1（两层授权，缺一不可）

1. **系统层**：打开 NoAd → 首页「无障碍拦截」卡片 → 「去开启」→
   在系统无障碍设置中找到「NoAd 广告拦截」并打开
2. **应用层**：在应用内打开「无障碍拦截」开关
3. **纳管应用**：进入「应用管理」页勾选需要拦截的应用

> ⚠️ **默认不纳管任何应用**，未勾选前不会拦截任何东西。
> 如果发现「没效果」，先检查这里。

> ⚠️ **Android 13+ 侧载限制**：从浏览器等渠道安装 APK 时，无障碍列表里可能
> **看不到** NoAd。需要到「应用信息 → 右上角菜单 → 允许受限设置」放行后重试。

### 排查拦截是否生效

```bash
adb logcat -s NoAdAccessibility
```

可看到每次事件的判定结果。应用内「拦截日志」页也应出现「无障碍」来源的记录。

## 构建环境

| 组件 | 版本 |
|---|---|
| AGP | 9.3.2 |
| Gradle | 9.5.0 |
| Kotlin | 2.2.10 |
| Compose BOM | 2026.02.01 |
| compileSdk / targetSdk | 37 |
| minSdk | 30（Android 11） |

```bash
./gradlew testDebugUnitTest    # 单元测试
./gradlew assembleDebug        # 构建 APK
```

## 技术栈

- **UI**：Jetpack Compose + Material 3
- **架构**：feature-first + MVVM，手写依赖容器（未引入 DI 框架）
- **持久化**：Room（规则 / 日志 / 纳管应用）+ DataStore（设置项）

## 项目结构

```
app/src/main/java/com/toaster/noad/
├── core/
│   ├── model/         领域模型
│   ├── database/      Room（Entity / DAO / Mappers）
│   ├── data/          Repository 层
│   ├── engine/        策略引擎
│   │   ├── DomainRuleEngine.kt   域名匹配（S2/S3/S4 共享）
│   │   └── ui/                   S1 无障碍匹配与点击
│   ├── service/       无障碍服务、状态发布
│   ├── applist/       已安装应用枚举
│   └── navigation/    导航与 ViewModel 工厂
└── feature/           home / apps / logs / network / settings
```

## 文档

- **[`reference/THREE_STRATEGY_PLAN.md`](reference/THREE_STRATEGY_PLAN.md)** ——
  综合技术方案（**唯一权威**，后续规划均以此为准）
- `reference/android-ad-blocking-research.md` —— 现有方案调研
- `reference/SHIZUKU_ENHANCEMENT.md` —— 已归档，内容并入主方案

## 已知限制

- S1 的拦截成功率**高度依赖具体机型与目标应用**，无法保证通用
- 部分应用的广告关闭按钮延迟出现或伪装成其他控件，需要针对性编写规则
- Android 13+ 侧载安装需手动放行「受限设置」
- 不支持信息流原生广告

## 许可

待补充。
