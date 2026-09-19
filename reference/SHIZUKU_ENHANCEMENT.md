# NoAd 可选增强方案：Shizuku 特权通道

> 目录：`reference/`（技术方案，非最终代码）
> 前置阅读：`THREE_STRATEGY_PLAN.md`（三策略主体方案）
> 编制日期：2026-09-19
> 状态：**待评审**
> 定位：**可选增强**，非核心依赖。NoAd 在没有 Shizuku 的设备上必须功能完整。

---

## 0. 结论摘要（先看这里）

对「给 Shizuku 额外权限的手机能否做优化」的回答是：**能，而且能解决主体方案里两个最硬的痛点**。

| 优化点 | 解决的痛点 | 收益等级 |
|---|---|---|
| **Chain-3 单应用网络控制** | S2/S3 **互斥**、且都会占用系统 VPN | 🔴🔴 架构级 |
| **Private DNS 直接改写** | S2 需要自建 VpnService 才能过滤域名 | 🔴🔴 架构级 |
| **绕过 Android 13+ 侧载限制** | 无障碍服务无法开启 | 🔴 体验级 |
| **冻结 / 隐藏广告载体应用** | 开屏广告屡禁不止 | 🟡 能力级 |
| **AppOps 单权限拒绝** | 广告 SDK 索取敏感权限、保活 | 🟡 能力级 |
| **force-stop 广告进程** | 弹窗反复出现、无法点击 | 🟡 能力级 |

**最关键的一条**：`ShizuWall` 已验证「通过 Shizuku 调用 `cmd connectivity` 的 Chain-3 接口，可以在**不占用 VPN、不建立 TUN** 的前提下，按包名切断联网」。这意味着 —

> **在支持 Shizuku 的设备上，S2/S3 的「VPN 互斥」问题可以完全绕开。**

这是本文件的核心价值。下文详细展开。

---

## 1. Shizuku 是什么（能力边界）

### 1.1 原理

Shizuku 不破解系统、不绕过权限模型，而是：

```
用户用电脑执行一次 ADB 命令
        ↓
启动 Shizuku Server（以 shell 身份，UID 2000）
        ↓
App 启动时收到 Shizuku Server 的 Binder
        ↓
App 通过 ShizukuBinderWrapper 把系统 API 调用
转发给 Shizuku Server 执行
        ↓
Server 以 UID 2000 的身份调用系统服务
```

本质是**做一个中间人**：接收 App 请求 → 以更高 UID 转发给 system_server → 回传结果。

### 1.2 关键边界（必须写进文档，否则会做出错误设计）

| 边界 | 说明 | 对 NoAd 的影响 |
|---|---|---|
| **UID 2000 ≠ root** | shell 权限，非 UID 0 | 部分 root-only 操作不可用 |
| **重启后失效** | Shizuku Server 需重新启动 | 不能依赖它做持久化机制 |
| **需要用户装机** | 额外安装一个 App + 电脑操作一次 | 必须提供**无 Shizuku 降级路径** |
| **权限随版本漂移** | shell 权限各 Android 版本 / 厂商 ROM 不同 | 每项能力需运行时探测 + 优雅失败 |
| **Hidden API 限制** | 普通 App 进程内仍受限 | 重度调用应放进 `UserService` |
| **无法授权危险权限** | `pm grant` 无法授予 `dangerous` 级权限 | 不能用它给别的 App 开敏感权限 |
| **无法直接读其他 App 沙盒** | `/data/user/0/` 不可读 | 不能直接读别的 App 数据 |

> ⚠️ 特别注意最后两条：网上很多「Shizuku 万能论」的说法是错的。
> **Shizuku ≈ ADB shell 能力的子集**，不是 root 替代品。

### 1.3 与 NoAd 项目基线的兼容性

| 项目事实 | 与 Shizuku 的关系 |
|---|---|
| minSdk 30（Android 11） | ✅ Shizuku 支持范围（11+ 可本机无线调试激活，体验更好） |
| 非 root 定位 | ✅ 完全兼容，Shizuku 就是为非 root 设备设计的 |
| 纯 Compose | ✅ 仅需在 UI 增加"可选增强"入口 |
| 单模块 `app` | ⚠️ Shizuku 依赖建议放独立 `core/shizuku` 包，隔离可选性 |

---

## 2. 接入方式设计

### 2.1 依赖

```kotlin
// gradle/libs.versions.toml（新增）
[versions]
shizuku = "13.1.5"          // 以官方 release 为准

[libraries]
shizuku-api = { group = "dev.rikka.shizuku", name = "api", version.ref = "shizuku" }
shizuku-provider = { group = "dev.rikka.shizuku", name = "provider", version.ref = "shizuku" }
shizuku-aidl = { group = "dev.rikka.shizuku", name = "aidl", version.ref = "shizuku" }
```

```kotlin
// app/build.gradle.kts（新增，保持可选性）
dependencies {
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
```

> ⚠️ 引入前需确认与 AGP 9.3.2 / Kotlin 2.2.10 的兼容性，
> 并确认 `aidl` 插件在 AGP 9.x 下的配置方式。
> 这是**需要外部资料确认的版本敏感项**，实施时先验证再全量接入。

### 2.2 Manifest 声明

```xml
<provider
    android:name="rikka.shizuku.ShizukuProvider"
    android:authorities="${applicationId}.shizuku"
    android:enabled="true"
    android:exported="true"
    android:multiprocess="false"
    android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
```

### 2.3 生命周期接入

```kotlin
class NoAdApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        ShizukuProvider.enableMultiProcessSupport(false)
        Shizuku.addBinderReceivedListenerSticky { /* 标记可用 */ }
        Shizuku.addBinderDeadListener { /* 标记不可用，降级 */ }
    }
}
```

### 2.4 统一的可用性探测（关键：一切优化都必须过这道门）

```kotlin
/**
 * Shizuku 能力探测。所有依赖 Shizuku 的功能都必须先经过此门。
 * 设计原则：任何一项探测失败 = 该项优化静默降级，不影响其余功能。
 */
class ShizukuCapabilityProbe {

    data class Capabilities(
        val available: Boolean,             // Shizuku 服务是否可用
        val canControlPerAppNetwork: Boolean, // Chain-3 接口可用性
        val canWriteSecureSettings: Boolean,  // Private DNS 改写
        val canManagePackages: Boolean,       // 冻结 / 隐藏 / 停用
        val canForceStop: Boolean,            // 强停
        val canSetAppOps: Boolean,            // 权限级控制
    )

    fun probe(): Capabilities = runCatching {
        if (!isShizukuReady()) return Capabilities(false, false, false, false, false, false)

        Capabilities(
            available = true,
            canControlPerAppNetwork = probeChain3(),
            canWriteSecureSettings = probeSecureSettings(),
            canManagePackages = true,
            canForceStop = true,
            canSetAppOps = true,
        )
    }.getOrElse { Capabilities(false, false, false, false, false, false) }
}
```

> **设计原则**：能力探测必须是逐项的、运行时的、可失败的。
> 因为 shell 权限在不同 Android 版本 / 厂商 ROM 上差异很大，
> 静态假设「有 Shizuku 就有一切」会做出脆弱的实现。

---

## 3. 优化一：Chain-3 单应用网络控制（★★★ 最重要）

### 3.1 这条为什么最重要

回看主体方案的**最硬约束**：

> Android 同一时刻**只允许一个 VpnService 运行**。
> 因此 S2（DNS 过滤）与 S3（全流量过滤）必须设计为**互斥模式**。

这不只是设计选择，而是系统限制。用户在 NoAd 与其他 VPN 之间也只能**二选一**。

**Chain-3 绕开了整个问题**：

```
ShizuWall 的实测结论：
    通过 Shizuku 调用 connectivity 服务的 Chain-3 接口，
    可以按包名阻止 / 允许某个应用联网。

关键特性：
    ✅ 不建立 TUN 设备
    ✅ 不占用系统 VPN
    ✅ 可以与其他 VPN 同时使用
    ✅ 拦截发生在内核网络栈层，应用层无法绕过
```

### 3.2 实现原理

底层是 Android 的 **Chain-3（旧称 `mBlockedUids` / `NetworkPolicyManager`）** 机制，
原本用于「省流量 / 后台联网限制」场景。它按 **UID** 级别控制网络访问。

```kotlin
// 通过 Shizuku 执行（形式示意，具体 API 以 ShizuWall 源码为准）
// 开启 chain3
cmd connectivity set-chain3-enabled true

// 禁止某包联网
cmd connectivity set-package-networking-enabled false com.example.adsapp

// 恢复
cmd connectivity set-package-networking-enabled true com.example.adsapp
```

对应的 **Java Binder 调用**路径（推荐，比 shell 命令可靠）：

```kotlin
// 通过 IConnectivityManager 的 setUidFirewallRule / setFirewallChainEnabled
val iConnectivityManager = IConnectivityManager.Stub.asInterface(
    ShizukuBinderWrapper(SystemServiceHelper.getSystemService("connectivity"))
)
```

> **注意**：不同 Android 版本的接口名有差异
> （`setFirewallUidRule` / `setUidFirewallRule` / `setFirewallChainEnabled`），
> **必须按 API level 分支处理**，并做运行时探测。
> 这正是 §2.4 能力探测必须存在的原因。

### 3.3 能力边界（必须明确）

| 能做 | 不能做 |
|---|---|
| 按 App 整体切断联网 | ❌ 按域名切断（做不到，chain 是 UID 级） |
| 与其他 VPN **共存** | ❌ 只拦广告、保留应用其余联网 |
| 内核级生效，应用难绕过 | ❌ 对系统应用 / 白名单应用通常无效 |

因此链条定位是：

> **Chain-3 解决的是「这个 App 的联网我全不要」，
> 而不是「这个 App 里的广告域名我不要」。**

### 3.4 对三策略架构的影响（重点）

引入 Chain-3 后，网络拦截从「二选一」变成「三选一 + 可叠加」：

```kotlin
enum class NetworkFilterMode {
    OFF,
    DNS_ONLY,              // S2：VpnService 只接管 DNS
    FULL_TRAFFIC,          // S3：VpnService 接管全流量
    APP_FIREWALL,          // 【新增】Chain-3 按应用断网（不占 VPN）
    HYBRID,                // 【新增】APP_FIREWALL + DNS_ONLY/S3 叠加
}
```

**新模式价值**：

| 组合 | 效果 | 是否占用 VPN |
|---|---|---|
| `APP_FIREWALL` 单独 | 彻底断掉广告 App 的网 | ❌ 不占用 ✅ |
| `DNS_ONLY` 单独 | 域名级过滤，性能好 | ✅ 占用 |
| `APP_FIREWALL + DNS_ONLY` | 双保险，且不影响用户用其他 VPN... | ⚠️ DNS_ONLY 仍占 VPN |
| `APP_FIREWALL` + 用户自己的 VPN | **两全其美** | ✅ 用户 VPN 正常工作 |

> **最有价值的场景**：用户需要连公司 VPN 时，
> NoAd 用 Chain-3 提供按应用断网，
> **完全让出 VPN 通道给用户的 VPN** — 这比「让位后什么都不做」强得多。

### 3.5 与「让位逻辑」的结合

主体方案的 `VpnArbitrator` 在检测到其他 VPN 时会让位。现在可以让位后**自动降级到 Chain-3**：

```kotlin
fun resolveStartDecision(userEnabled: Boolean): StartDecision = when {
    !userEnabled -> StartDecision(false, "user_disabled")

    // 有其他 VPN 时：如果有 Shizuku，降级到 Chain-3 而不是完全放弃
    isOtherVpnActive() && shizukuProbe.canControlPerAppNetwork ->
        StartDecision(true, mode = APP_FIREWALL, reason = "degraded_to_app_firewall")

    isOtherVpnActive() -> StartDecision(false, "yield_to_other_vpn")

    else -> StartDecision(true)
}
```

**这是一个纯增益的改进**：原本「让位 = 网络拦截全失效」，现在「让位 = 降级为应用级防火墙」。

---

## 4. 优化二：Private DNS 直接改写（★★）

### 4.1 核心价值

用户需求里明确写了「**DNS / Private DNS 过滤**」。主体方案的 S2 是通过**自建 VpnService** 来实现 DNS 接管，代价是占用 VPN。

但 Android 本身就有 **Private DNS（DoT）** 能力，只是普通 App 无法修改这个系统设置 ——
因为它需要 `WRITE_SECURE_SETTINGS` 权限（`signature|privileged` 级）。

**Shizuku 可以授予这个权限**，这正是市面上多个 Private DNS 切换工具的工作原理：

```bash
# 传统方式（电脑 ADB）
adb shell pm grant <package> android.permission.WRITE_SECURE_SETTINGS

# 有 Shizuku 后（无需电脑）
Shizuku 内自动授予该权限
```

> 已验证的应用：`PrivateDNSAndroid`、`Private DNS Quick Setting`（GPL-3.0）
> 均明确说明「需要 Shizuku 或 ADB 授予 `WRITE_SECURE_SETTINGS`」。

### 4.2 实现

```kotlin
/** 通过 Shizuku 授予自身 WRITE_SECURE_SETTINGS，然后改写 Private DNS */
class PrivateDnsController(private val context: Context) {

    /**
     * 设置 Private DNS 为「指定主机名」模式。
     * @param hostname 例如 "dns.adguard-dns.com" 或自建 DoT 服务
     */
    fun setPrivateDnsHostname(hostname: String): Boolean = runCatching {
        val resolver = context.contentResolver
        Settings.Global.putString(
            resolver,
            "private_dns_mode", "hostname"          // 三种模式：off / opportunistic / hostname
        )
        Settings.Global.putString(resolver, "private_dns_specifier", hostname)
        true
    }.getOrElse { false }

    fun setPrivateDnsOff(): Boolean = runCatching {
        Settings.Global.putString(context.contentResolver, "private_dns_mode", "off")
        true
    }.getOrElse { false }
}
```

### 4.3 与 S2 的关系：**竞争方案**

| 方案 | 实现方式 | 占用 VPN | 加密 | 篡改能力 | 复杂度 |
|---|---|---|---|---|---|
| **S2（自建 VpnService DNS）** | 自己接管 DNS | ✅ 占用 | 仅上游可 DoT | ✅ 可自定义规则 | 高 |
| **Private DNS 改写** | 改系统设置指向 DoT | ❌ **不占用** | ✅ 强制 DoT 加密 | ❌ 只能选服务商 | **极低** |

**这是一个真正的替代选择**：

- 想用**现成 DNS 过滤服务**（AdGuard DNS / NextDNS 等）→ 用 Private DNS 改写，**零代码**，不占 VPN
- 想用**自定义域名规则**（NoAd 自己的规则库）→ 必须用 S2 自建 VpnService

> **建议**：两者都保留，由用户在 UI 选择。
> 把 Private DNS 定位为「**懒人模式 / 兼容模式**」：
> 对不想开 VPN 权限、或需要与其他 VPN 共存的用户，是极其廉价的高价值选项。

### 4.4 重要限制

| 限制 | 说明 |
|---|---|
| 需要用户**手动安装并启动 Shizuku** | 仍属可选增强 |
| **Private DNS 是全局的** | 影响整机所有 App，不能按包区分 |
| 用户可能在系统设置里改回来 | 需监听设置变化，UI 同步状态 |
| 部分 ROM 有自己的 Private DNS 实现 | 需探测 `private_dns_mode` 是否存在 |
| **DoT 本身仍可被 App 绕过** | 若 App 自带 DoH，不走系统 DNS |

---

## 5. 优化三：绕过 Android 13+ 无障碍侧载限制（★★）

### 5.1 问题

主体方案里已记录这个风险：

> **Android 13+ 侧载限制**：侧载安装的应用，其无障碍服务无法直接开启，
> 用户需要手动进入「应用信息 → 允许受限设置」，且部分 ROM 隐藏该入口。

**NoAd 是个人 / 开源项目，几乎肯定是侧载安装** —— 因此这个问题**必然遇到**。

### 5.2 Shizuku 解法

`ACCESS_RESTRICTED_SETTINGS` 是一个 AppOps 操作，Shizuku 可以直接设置：

```kotlin
/**
 * 解除「受限设置」限制，允许无障碍服务开启。
 * 仅在 Android 13+ 且无障碍服务被限制时需要。
 */
fun allowRestrictedSettings(packageName: String): Boolean = runCatching {
    val appOps = IAppOpsService.Stub.asInterface(
        ShizukuBinderWrapper(SystemServiceHelper.getSystemService("appops"))
    )
    val opCode = 119  // OP_ACCESS_RESTRICTED_SETTINGS，数值随版本可能变化
    appOps.setMode(opCode, Process.myUid(), packageName, AppOpsManager.MODE_ALLOWED)
    true
}.getOrElse { false }
```

> ⚠️ `OP_ACCESS_RESTRICTED_SETTINGS` 的**数值在不同 Android 版本不一样**，
> 必须按 API level 查表，或使用 `AppOpsManager.strOpToOp("android:access_restricted_settings")`。
> **这是易错点，必须运行时探测 + 失败降级到图文引导。**

### 5.3 降级路径（必须有）

```
用户开启 S1
    ↓
检测是否被受限设置阻止
    ├─ 有 Shizuku → 尝试自动解除 → 成功：直接开启
    └─ 无 Shizuku / 失败 → 展示图文引导：
          「设置 → 应用 → NoAd → 右上角菜单 → 允许受限设置」
          + 提供 ROM 差异说明（MIUI / OneUI / HyperOS 入口不同）
```

---

## 6. 优化四：应用冻结 / 隐藏 / 停用（★★）

### 6.1 能力

通过 `IPackageManager` 可以做到普通 App 做不到的事：

```kotlin
// 停用应用（等效于「冻结」，App 完全无法运行）
fun disablePackage(pkg: String): Boolean = runCatching {
    val pm = IPackageManager.Stub.asInterface(
        ShizukuBinderWrapper(SystemServiceHelper.getSystemService("package"))
    )
    pm.setApplicationEnabledSetting(
        pkg,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
        0,
        Process.myUid(),
        /* callingPackage = */ myPackageName
    )
    true
}.getOrElse { false }

// 从桌面隐藏（但仍可运行）
fun hidePackage(pkg: String, hidden: Boolean): Boolean = runCatching {
    val pm = IPackageManager.Stub.asInterface(...)
    pm.setApplicationHiddenSettingAsUser(pkg, hidden, UserHandle.myUserId())
    true
}.getOrElse { false }

// 停用组件（例如只停掉广告 Activity / Service）
fun disableComponent(pkg: String, component: String): Boolean = runCatching {
    val pm = IPackageManager.Stub.asInterface(...)
    pm.setComponentEnabledSetting(
        ComponentName(pkg, component),
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP,
        UserHandle.myUserId()
    )
    true
}.getOrElse { false }
```

### 6.2 在 NoAd 里怎么用（要有边界）

这是一个**能力很强但容易越界**的功能。必须明确 NoAd 的定位是**拦广告**，不是**应用管理器**。

| 用途 | 是否属于 NoAd 职责 | 建议 |
|---|---|---|
| 停用**开屏广告载体组件** | ✅ 是 | 允许，但需用户逐个确认 |
| 冻结**用户明确指定的**广告 App | ✅ 是 | 允许，UI 上明确标注风险 |
| 批量停用系统应用 | ❌ 否 | **不提供**（属于冻结类工具职责） |
| 静默安装 / 卸载 | ❌ 否 | **不提供** |

**单组件停用的实际价值**：
很多 App 的开屏广告由**独立 Activity** 承载（如 `com.xxx.SplashAdActivity`）。
S1 无障碍只能「等它出现再点掉」，而停用该组件**从根上不会出现**。

> **推荐定位**：作为 S1 的**强化选项** ——
> 「跳过开屏广告」的升级版叫「禁用开屏广告组件」，
> 由用户在规则页对已识别的高频广告组件手动启用。
> **不做全自动批量操作**，避免误伤。

### 6.3 风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 误停用导致应用异常 | 部分 App 停用组件后崩溃 | 提供一键恢复 + 操作日志 |
| 停用系统应用风险高 | 可能导致系统功能异常 | **禁止对系统应用操作**（`FLAG_SYSTEM` 过滤） |
| 用户忘记恢复 | 长期不可用 | 规则页集中展示 + 明显提示 |

---

## 7. 优化五：AppOps 精细化权限控制（★）

### 7.1 用途

阻止广告 SDK 的越界行为。这是 `App Ops` 这类工具的核心能力。

```kotlin
fun setAppOpMode(pkg: String, op: String, mode: Int): Boolean = runCatching {
    val appOps = IAppOpsService.Stub.asInterface(
        ShizukuBinderWrapper(SystemServiceHelper.getSystemService("appops"))
    )
    val opCode = AppOpsManager.strOpToOp(op)   // 用字符串转码，避免硬编码数值
    appOps.setMode(opCode, Process.myUid(), pkg, mode)
    true
}.getOrElse { false }
```

### 7.2 对 NoAd 相关的操作

| Op | 用途 | 与广告的关系 |
|---|---|---|
| `RUN_IN_BACKGROUND` | 禁止后台运行 | 阻止广告 SDK 后台拉取素材、保活 |
| `SYSTEM_ALERT_WINDOW` | 悬浮窗 | 阻止「悬浮广告」与「摇一摇跳转」 |
| `GET_DEVICE_ID` / `READ_PHONE_STATE` | 设备标识 | 削弱跨应用广告追踪 |
| `REQUEST_INSTALL_PACKAGES` | 安装其他 App | 阻止广告诱导下载安装 |

### 7.3 重要限制（必须诚实说明）

> **⚠️ 网上大量说法是错误的**：
> `AppOps` 的「拒绝」**不等于**系统权限拒绝。
> 它的实际行为是「**返回空数据而非真实拒绝**」——
> App 仍然认为自己拿到了权限，只是拿到的是空白值。
>
> **好处**：不会触发「不给权限就不让用」。
> **限制**：对某些强校验场景无效。

并且：**Shizuku 无法授予危险权限**（`pm grant` 对 `dangerous` 级权限无效），
只能**拒绝**，不能**帮助 App 获得权限**。这个方向是单向的。

---

## 8. 优化六：强制停止进程（★）

### 8.1 用途

补充 S1 的短板：当广告弹窗的关闭按钮：
- 是恶意的（假按钮、跳转下载）
- 是坐标随机 / 无法点击
- 是穿透式的（覆盖整屏）

此时「点掉它」不如「**直接把 App 停掉**」。

```kotlin
fun forceStopPackage(pkg: String): Boolean = runCatching {
    val am = IActivityManager.Stub.asInterface(
        ShizukuBinderWrapper(SystemServiceHelper.getSystemService("activity"))
    )
    am.forceStopPackage(pkg, UserHandle.myUserId())
    true
}.getOrElse { false }
```

### 8.2 定位与限制

| 项 | 说明 |
|---|---|
| **定位** | S1 的**最后手段**，触发条件必须非常严格 |
| **触发条件建议** | ① 用户已在规则中显式启用 ② 匹配到"自杀式广告"特征 ③ 冷却时间内不重复 |
| **副作用** | 用户正在使用的应用被强停 → **体验破坏** |
| **必须** | 强停后写入日志 + UI 提示「已重置 XX 应用」 |

> ⚠️ **不建议默认开启**。这是最容易引起用户反感的操作。
> 建议设计为「**高风险广告 App 列表**」+ 用户逐个授权，
> 而不是全局开关。

---

## 9. 架构影响汇总

### 9.1 新增模块

```
com.toaster.noad/
├── core/
│   └── shizuku/                        # 【新增】Shizuku 特权通道（可选层）
│       ├── ShizukuCapabilityProbe.kt   # 能力探测（一切的前提）
│       ├── ShizukuBinderHolder.kt      # Binder 获取与缓存
│       ├── AppFirewallController.kt    # Chain-3 单应用断网
│       ├── PrivateDnsController.kt     # Private DNS 改写
│       ├── AppManagerController.kt     # 冻结 / 隐藏 / 组件停用
│       ├── AppOpsController.kt         # 权限级控制
│       ├── ProcessController.kt        # 强制停止
│       └── RestrictedSettingsController.kt  # 侧载限制解除
```

**分层约束**：`core/shizuku` 必须**只被 Service 层和 Repository 层引用**，
不允许 `feature/` 层直接调用 —— 保证 Shizuku 是可选能力而非耦合依赖。

### 9.2 更新后的策略矩阵

| 能力 | 无 Shizuku | 有 Shizuku | 是否占用 VPN |
|---|---|---|---|
| S1 无障碍点击 | ✅ | ✅ + 自动解除侧载限制 | ❌ |
| S1 组件停用（根治开屏） | ❌ | ✅ | ❌ |
| S1 强制停止（兜底） | ❌ | ✅ | ❌ |
| S2 DNS 过滤 | ✅（自建 VPN） | ✅ + **Private DNS 替代方案** | ✅ / ❌ |
| S3 全流量过滤 | ✅（自建 VPN） | ✅ | ✅ |
| **S4 应用级断网（Chain-3）** | ❌ | ✅ | ❌ **不占用** |
| 让位后降级 | ❌ 完全失效 | ✅ **降级到 S4** | ❌ |

> 注意引入了 **S4** 这个概念（Chain-3 应用级防火墙）。
> 它不完全属于原三策略框架，建议在主体方案里作为**第四策略**登记。

### 9.3 与 `VpnArbitrator` 的整合

```
用户开启网络拦截
    ↓
VpnArbitrator 检测其他 VPN
    ├─ 无其他 VPN
    │     └─ 按用户选择：DNS_ONLY / FULL_TRAFFIC
    │
    └─ 有其他 VPN
          ├─ 有 Shizuku → 降级为 APP_FIREWALL（S4）
          │                让出 VPN，但保留应用级拦截 ✅
          └─ 无 Shizuku → 让位，网络拦截不可用 ⚠️
                            UI 明示 + 提示可安装 Shizuku 增强
```

---

## 10. 设计原则（必须遵守）

### 10.1 Shizuku 是**可选增强**，不是前提

```kotlin
// ❌ 错误设计：把 Shizuku 当必需依赖
class HomeViewModel {
    val isProtected = shizuku.isAvailable() && accessibility.isRunning()
}

// ✅ 正确设计：Shizuku 只影响"增强项"，不影响核心功能
class HomeViewModel {
    val protectionState = combine(
        accessibilityServiceState,   // 核心，不依赖 Shizuku
        networkFilterState,          // 核心，不依赖 Shizuku
        shizukuEnhancementState,     // 增强，可选
    ) { base, shizuku ->
        ProtectionState(
            core = base,
            enhancements = shizuku,   // 单独展示
        )
    }
}
```

**验收标准**：在一台**没有安装 Shizuku** 的设备上，
NoAd 的**所有核心功能必须完整可用**，只是没有增强项。

### 10.2 每一项能力都要独立降级

不能出现「Shaizuku 挂了一个功能导致全盘失效」。每一项都要：

```
探测能力 → 可用则启用 → 不可用则该项静默降级 → UI 如实标注
```

### 10.3 UI 必须如实标注状态

```
┌─────────────────────────────────────┐
│  增强能力（需要 Shizuku）             │
│                                     │
│  ✅ 应用级断网                        │
│     Shizuku 已就绪                   │
│                                     │
│  ⚠️ 禁用开屏广告组件                  │
│     需要重新启动 Shizuku              │
│     [如何操作]                       │
│                                     │
│  ⚪ 强制停止广告进程                  │
│     未启用（未安装 Shizuku）           │
│     [了解如何安装]                    │
└─────────────────────────────────────┘
```

**禁止**：装了 Shizuku 就静默启用一堆高风险功能。所有增强项**默认关闭，由用户显式开启**。

### 10.4 UserService 优先于 newProcess

官方已明确 `newProcess` 将被移除：

> "Prepare to remove `Shizuku#newProcess`, developers should have to use UserService instead."
> "`newProcess` uses texts to communicate, which is not efficient and unreliable."

因此：

| 场景 | 推荐方式 |
|---|---|
| 单次 Binder 调用（如设置一个 AppOp） | `ShizukuBinderWrapper` 直接调用 |
| 需长驻状态、重复 Binder 调用 | **`UserService`** |
| 执行 shell 命令 | 避免，优先用 Binder API |

---

## 11. 实施优先级建议

| 阶段 | 内容 | 价值 | 复杂度 |
|---|---|---|---|
| **P0** | `ShizukuCapabilityProbe` + Binder 基础设施 | 一切的前提 | 中 |
| **P0** | 侧载限制解除（§5） | **必遇问题**，体验提升巨大 | 低 |
| **P1** | **Chain-3 应用级断网（§3）** | **架构级价值**，解决 VPN 互斥 | 高 |
| **P1** | Private DNS 改写（§4） | 零成本高价值 | 低 |
| **P2** | 组件停用（§6） | 根治开屏广告 | 中 |
| **P2** | AppOps 权限控制（§7） | 削弱广告 SDK | 中 |
| **P3** | 强制停止（§8） | 兜底能力，风险高 | 低 |

> **建议**：P0 两项先做，立刻可见价值；
> Chain-3 是真正的架构升级，值得投入。

---

## 12. 风险清单（Shizuku 专属）

| 风险 | 等级 | 应对 |
|---|---|---|
| **Shizuku 重启后失效，功能静默消失** | 🔴 高 | Binder 死亡监听 + UI 状态同步 + 引导重启 |
| **不同 Android 版本接口差异（Chain-3 / AppOps opcode）** | 🔴 高 | 运行时探测 + 按 API level 分支 + 优雅降级 |
| **厂商 ROM 削减 shell 权限** | 🔴 高 | 逐项探测，不假设能力存在 |
| **误停用组件导致 App 崩溃** | 🟡 中 | 一键恢复 + 操作日志 + 禁止操作系统应用 |
| **强停导致用户正在用的 App 被杀** | 🟡 中 | 默认关闭 + 高风险列表 + 冷却时间 |
| **用户无法激活 Shizuku（无电脑 / 不会操作）** | 🟡 中 | 详尽图文引导 + Android 11+ 无线调试方案 |
| **Shizuku 依赖引入影响构建** | 🟡 中 | 实施前先验证 AGP 9.3.2 兼容性 |
| **用户误以为 Shizuku 是 root** | 🟢 低 | UI 明确说明能力与边界 |

---

## 13. 待决策

| # | 问题 | 影响 |
|---|---|---|
| **SQ1** | 是否引入 Shizuku 依赖？ | 决定整个增强层是否存在 |
| **SQ2** | Chain-3 是否作为正式「S4 策略」纳入主体方案？ | 影响架构文档与实现路线 |
| **SQ3** | Private DNS 与 S2 自建 DNS 如何共存？（二选一 / 都提供） | 影响网络页 UI 复杂度 |
| **SQ4** | 组件停用功能是否做？（能力强，但越接近「应用管理器」） | 影响项目定位边界 |
| **SQ5** | 强制停止是否提供？（风险最高） | 影响用户信任 |
| **SQ6** | 增强项默认全部关闭，还是提供「一键启用推荐项」？ | 影响易用性 vs 安全性权衡 |

---

## 14. 附：关键结论速查

| 问题 | 结论 |
|---|---|
| Shizuku 是 root 吗？ | ❌ 不是，是 shell（UID 2000） |
| 重启后还在吗？ | ❌ 不在，需重新启动 Shizuku |
| 能授权危险权限吗？ | ❌ 不能，只能拒绝不能授予 |
| 能解决 VPN 互斥吗？ | ✅ **能，Chain-3 不占 VPN** |
| 能改 Private DNS 吗？ | ✅ 能，需 `WRITE_SECURE_SETTINGS` |
| 能绕过 Android 13+ 侧载限制吗？ | ✅ 能，改 `ACCESS_RESTRICTED_SETTINGS` |
| 能停用应用组件吗？ | ✅ 能，`setComponentEnabledSetting` |
| 能读其他 App 数据吗？ | ❌ 不能，沙盒不可越 |
| 没有 Shizuku 能用 NoAd 吗？ | ✅ **必须能，这是硬约束** |
| 推荐 API 方式 | `UserService` > `ShizukuBinderWrapper` > shell 命令 |
