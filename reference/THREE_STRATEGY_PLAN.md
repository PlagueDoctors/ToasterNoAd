# NoAd 三策略综合拦截技术方案

> 目录：`reference/`（技术方案，非最终代码）
> 前置阅读：`android-ad-blocking-research.md`（方案调研）、`IMPLEMENTATION_PLAN.md`（无障碍单策略方案）
> 后续阅读：`SHIZUKU_ENHANCEMENT.md`（**可选增强：解决本文 S2/S3 互斥痛点**）
> 编制日期：2026-09-19
> 状态：**待评审**
> 定位：个人使用 / 开源，暂不纳入法律合规与商店上架约束

> 📌 **重要补充**：本文 §2 的「S2/S3 因单一 VPN 限制而互斥」，
> 在支持 **Shizuku** 的设备上可通过 **Chain-3 应用级断网（不占用 VPN）** 绕开。
> 详见 `SHIZUKU_ENHANCEMENT.md`，其中将该能力登记为 **S4 策略**。

---

## 1. 方案目标

在 NoAd 内**同时实现三种广告拦截策略**，各司其职、互不冲突：

| 策略 | 拦截对象 | 生效层级 | 依赖 |
|---|---|---|---|
| **S1 无障碍模拟点击** | 开屏广告、弹窗广告 | UI 层 | `AccessibilityService` |
| **S2 本地 DNS 过滤** | 网页广告、追踪域名 | 域名解析层 | `VpnService`（仅接管 DNS） |
| **S3 本地 VPN 流量过滤** | 网络请求层广告（含 App 内） | 网络层 | `VpnService`（接管全流量） |

### 1.1 策略间的关系

三种策略**不是并列的替代选项**，而是覆盖不同问题域：

```
┌─ S1 无障碍 ──── UI 层：能"看见并点击"的广告（弹窗、开屏）
│                  无法拦截：网页内的横幅、信息流广告
│
├─ S2 DNS 过滤 ── 域名层：广告服务器域名解析失败
│                  无法拦截：直连 IP、DoH/DoT、已缓存的连接
│
└─ S3 VPN 过滤 ── 网络层：按域名/IP/规则丢弃或重写请求
                   能力最强，但代价是独占系统 VPN
```

**关键认知**：S2 与 S3 **争夺同一个资源** —— Android 同一时刻**只允许一个 VpnService 运行**。因此二者必须设计为**互斥的两种工作模式**，而非同时启用。

---

## 2. 核心约束：Android 单一 VPN 限制

这是本方案最关键的技术前提，直接决定架构。

### 2.1 系统规则（官方明确）

> "There can be only one VPN connection running at the same time.
> The existing interface is deactivated when a new one is created."

**行为细节**：

| 场景 | 系统行为 |
|---|---|
| 应用 A 已建立 VPN，应用 B 调用 `establish()` | B **抢占**接口，A 的 TUN 被拆除 |
| A 被抢占 | A 收到 `onRevoke()` 回调 |
| `VpnService.prepare()` 返回值 | 已授权时返回 `null`；需授权时返回 Intent |
| 被抢占后 A 再调用其他方法 | **失败**，除非重新 `prepare()` 并获得授权 |
| 若 A、B 都设置了自动重连 | **无限抢占循环**（各自反复夺回接口） |

### 2.2 由此推导的架构决策

**决策一：S2 与 S3 是互斥模式，不是叠加开关**

```kotlin
enum class NetworkFilterMode {
    OFF,            // 不接管网络（仅 S1 生效）
    DNS_ONLY,       // S2：只接管 DNS 查询
    FULL_TRAFFIC,   // S3：接管全部流量
}
```

用户在同一时间只能在 `DNS_ONLY` 与 `FULL_TRAFFIC` 之间选一个。

**决策二：让位逻辑必须主动且优雅**

用户的需求是「当手机有其他 VPN 运行时应该让位」。实现要点：

```kotlin
class VpnArbitrator(private val context: Context) {

    /** 检测系统当前是否已有 VPN 在运行（包括本应用之外的其他 VPN） */
    fun isOtherVpnActive(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.allNetworks.any { network ->
            cm.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
            network.networkHandle != ourOwnNetworkHandle()   // 排除自己
        }
    }

    /** 启动前仲裁 */
    fun resolveStartDecision(userEnabled: Boolean): StartDecision = when {
        !userEnabled        -> StartDecision(false, "user_disabled")
        isOtherVpnActive()  -> StartDecision(false, "yield_to_other_vpn")
        else                -> StartDecision(true)
    }
}
```

**决策三：被抢占时立即停止，不争抢**

```kotlin
override fun onRevoke() {
    // 这是关键：被其他 VPN 抢占时，必须优雅退出，绝不自动重连
    logRepository.record(Event.VpnRevoked)
    stopVpn()                       // 关闭 TUN、停止线程、取消通知
    settingsRepository.setVpnYieldedByOther(true)   // 标记"已让位"
    super.onRevoke()
}
```

> ⚠️ **绝不能做的事**：在 `onRevoke()` 里自动 `establish()` 重新夺回。
> 这会与其他 VPN 形成**无限抢占循环**，表现为"VPN 反复掉线"，
> 是 Android 生态里最典型的负面案例。让位即让位，由用户决定何时恢复。

**决策四：让位后的恢复需用户显式触发**

被让位后，应用进入"已让位"状态并在 UI 明示。只有当：
- 用户手动点击恢复，**且**
- 检测到其他 VPN 已退出

才重新建立。不做任何后台自动恢复。

### 2.3 VPN 检测手段

| 手段 | 实现 | 可靠性 | 采用 |
|---|---|---|---|
| 网络能力检测 | `NetworkCapabilities.hasTransport(TRANSPORT_VPN)` | 高（系统级） | ✅ 主方案 |
| 网络接口嗅探 | 枚举 `tun*` / `ppp*` 接口名 | 中（可能被伪装） | ✅ 辅助兜底 |
| 自身状态跟踪 | 记录自己的网络 handle | 高 | ✅ 用于排除自身 |

```kotlin
// 辅助兜底
private fun hasTunInterface(): Boolean =
    runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .any { it.name.startsWith("tun") || it.name.startsWith("ppp") }
    }.getOrDefault(false)
```

---

## 3. 架构设计

### 3.1 整体分层

```
com.toaster.noad/
├── NoAdApplication.kt
├── core/
│   ├── model/
│   │   ├── SkipRule.kt              # S1 规则
│   │   ├── DomainRule.kt            # S2/S3 域名规则
│   │   ├── InterceptLog.kt
│   │   └── TargetApp.kt
│   ├── data/
│   │   ├── rule/RuleRepository.kt
│   │   ├── domain/DomainRuleRepository.kt   # 域名黑白名单
│   │   ├── log/LogRepository.kt
│   │   └── settings/SettingsRepository.kt
│   ├── database/                    # Room
│   ├── engine/                      # 【核心】三策略引擎
│   │   ├── ui/                      # S1
│   │   │   ├── UiMatcher.kt
│   │   │   └── ClickExecutor.kt
│   │   ├── dns/                     # S2
│   │   │   ├── DnsPacketParser.kt
│   │   │   ├── DnsInterceptor.kt
│   │   │   └── DnsUpstreamResolver.kt
│   │   ├── net/                     # S3
│   │   │   ├── PacketParser.kt
│   │   │   ├── TrafficFilter.kt
│   │   │   └── TcpUdpRelay.kt
│   │   └── DomainRuleEngine.kt      # S2/S3 共享的域名匹配
│   ├── service/
│   │   ├── NoAdAccessibilityService.kt      # S1
│   │   ├── NoAdVpnService.kt                # S2 + S3
│   │   ├── vpn/VpnArbitrator.kt             # 【关键】让位仲裁
│   │   └── event/EventProcessor.kt
│   ├── applist/InstalledAppDataSource.kt
│   ├── designsystem/  navigation/           # 已有
├── feature/
│   ├── home/  apps/  logs/  settings/       # 已有
│   ├── rules/          # 【新增】S1 规则管理
│   └── network/        # 【新增】网络过滤控制页（模式选择 + 状态）
└── ui/theme/
```

### 3.2 三策略协同

```
                    ┌──────────────────────┐
                    │   统一控制中心        │
                    │  StrategyCoordinator │
                    └──────────┬───────────┘
                               │
        ┌──────────────────────┼──────────────────────┐
        ▼                      ▼                      ▼
   ┌─────────┐          ┌───────────┐          ┌───────────┐
   │   S1    │          │    S2     │          │    S3     │
   │无障碍服务│          │ DNS 模式  │          │全流量模式 │
   └─────────┘          └───────────┘          └───────────┘
        │                      │                      │
   独立运行               ┌────┴────┐            ┌────┴────┐
   与网络无关             │  互斥   │            │  互斥   │
        │                └─────────┘            └─────────┘
        │                      │                      │
        ▼                      ▼                      ▼
   UI 层弹窗             域名解析层              网络请求层
```

**协同规则**：

1. **S1 与 S2/S3 完全独立**，可同时开启（无障碍服务不占用 VPN）
2. **S2 与 S3 互斥**，由 `NetworkFilterMode` 单一开关控制
3. **三者共享同一套域名规则库**（S2/S3）与拦截日志
4. **统一开关面板**：首页提供总开关，网络页提供模式选择

### 3.3 拦截能力矩阵

| 广告类型 | S1 | S2 | S3 |
|---|---|---|---|
| 应用开屏广告 | ✅ 强 | ❌ | ⚠️ 部分 |
| 应用内弹窗广告 | ✅ 强 | ⚠️ 部分 | ✅ 中 |
| 网页横幅/插页 | ❌ | ✅ 强 | ✅ 强 |
| 视频贴片广告 | ❌ | ⚠️ 部分 | ⚠️ 部分 |
| 信息流原生广告 | ❌ | ❌ | ❌ |
| 追踪/统计域名 | ❌ | ✅ 强 | ✅ 强 |

> **诚实说明**：信息流原生广告（广告内容与正文同服务器同域名）**三种策略都无法拦截**，
> 这是技术边界，不应当作缺陷。

---

## 4. S2：本地 DNS 过滤设计

### 4.1 核心技术：只接管 DNS，不接管全流量

这是本方案**最优雅的部分**，也是与 S3 的本质区别。

**原理**：用 `VpnService.Builder` 只把**虚拟 DNS 服务器地址**加进路由表，而不加默认路由。

```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // 建立前先读取真实上游 DNS（此时系统 DNS 还没被改）
    val upstreams = readRealDnsServers()      // 见 4.3

    val builder = Builder()
        .setSession("NoAd DNS")
        .addAddress("10.0.0.1", 32)           // 虚拟网卡地址
        .addDnsServer("10.0.0.1")             // 告诉系统：DNS 服务器是 10.0.0.1
        .addRoute("10.0.0.1", 32)             // 【关键】只路由这一个地址
        // 注意：绝不调用 addRoute("0.0.0.0", 0)
```

**效果对比**：

| 配置 | DNS 查询 | 网页/下载/视频流量 | 性能影响 |
|---|---|---|---|
| `addRoute("0.0.0.0", 0)` | 进 TUN | **全部进 TUN** | 高 |
| `addRoute("10.0.0.1", 32)` | 进 TUN | **走原路径，完全不受影响** | 极低 |

> 这是 DNS-only 方案「轻量」的根本原因：影响面被收窄到只有 DNS 查询。

### 4.2 包处理循环

```
读取 TUN 数据包
    ↓
解析 IP 头 → 判断协议
    ├─ 非 UDP → 丢弃（不应出现）
    └─ UDP → 判断端口
              ├─ 非 53 → 丢弃
              └─ 53 → 解析 DNS 查询
                        ↓
                   提取域名 → 归一化
                        ↓
                  规则匹配（白名单优先）
                        ├─ 命中黑名单 → 本地构造 NXDOMAIN 响应
                        └─ 未命中 → 转发到真实上游 DNS
                                          ↓
                                    写回 TUN
```

### 4.3 关键实现细节

**① 上游 DNS 必须在建立 TUN 前读取**

```kotlin
private fun readRealDnsServers(): List<InetAddress> {
    // 必须在 establish() 之前调用
    // 否则此时读到的会是自己的虚拟 DNS 10.0.0.1 → 死循环
    val cm = getSystemService(ConnectivityManager::class.java)
    return cm.getLinkProperties(cm.activeNetwork)
        ?.dnsServers
        .orEmpty()
        .ifEmpty { listOf(InetAddress.getByName("223.5.5.5")) }   // 兜底
}
```

**② 转发前必须调用 `protect()`**

```kotlin
val socket = DatagramSocket()
protect(socket)      // 【关键】否则 DNS 请求会回流进自己的 TUN → 循环
socket.send(packet)
```

漏掉 `protect()` 的症状：DNS 超时、页面打不开、CPU 飙升、日志刷屏 ——
且排查时容易误判为"上游 DNS 不稳定"。

**③ 响应包必须重算 IP 头**

命中黑名单时不能只改 DNS payload，写回 TUN 的是**完整 IP 包**，必须处理：

- 翻转源/目的 IP
- 翻转源/目的端口
- 设置新的包长度
- **重算 IPv4 头校验和**
- 设置 DNS 响应标志与错误码

**④ 错误码语义区分**

| 场景 | 返回 | 含义 |
|---|---|---|
| 命中黑名单 | `NXDOMAIN` | 域名不存在（应用会快速放弃） |
| 上游不可达 | `SERVFAIL` | 解析服务临时故障 |

不能混用，否则影响排查判断。

### 4.4 DNS 层的能力边界

**必须明确告知用户这些限制**：

1. **直连 IP 不经过 DNS** → 无法拦截
2. **应用自建 DNS 或使用 DoH/DoT** → 绕过系统 DNS，无法拦截
3. **已缓存的 DNS** → 规则更新不会立即对已建连接生效
4. **不解析 HTTPS SNI、不检查 HTTP Host** → 看不到应用层内容

> 这四点必须在 UI 上说明，否则用户会认为"拦不住就是软件不行"。

---

## 5. S3：本地 VPN 全流量过滤设计

### 5.1 定位与代价

S3 能力最强（可看到实际 IP 请求、可丢弃连接、可做域名+IP 双层过滤），但代价明确：

| 代价 | 说明 |
|---|---|
| **独占系统 VPN** | 与其他 VPN 应用互斥，必须让位 |
| **全流量经过 TUN** | 性能开销明显高于 S2 |
| **需要完整 TCP/UDP 转发** | 实现复杂度远高于 DNS-only |
| **常驻通知** | 系统强制要求显示 VPN 通知 |
| **电量消耗** | 实测通常增加 3%–5% |

### 5.2 实现要点

**路由配置**（与 S2 的区别）：

```kotlin
val builder = Builder()
    .setSession("NoAd")
    .addAddress("10.0.0.2", 32)
    .addDnsServer("10.0.0.2")
    .addRoute("0.0.0.0", 0)              // 【关键】接管全部流量
    .setBlocking(false)
    .setMtu(1500)
```

**包处理循环**：需完整实现 IP 包的解析与转发

```
读取 TUN 包 → 解析 IP 头
    ├─ TCP → 提取目标 IP:Port
    ├─ UDP → 提取目标 IP:Port（含 53 端口走 DNS 拦截逻辑）
    └─ 其他 → 丢弃
         ↓
    规则判定
    ├─ 命中黑名单 → 直接丢弃（客户端表现为连接超时/重置）
    └─ 未命中 → 通过 protect() 保护的 socket 转发到真实目标
         ↓
    收到响应 → 构造 IP 包 → 写回 TUN
```

**推荐实现路径**：

S3 的完整 TCP/UDP 转发是一个**有相当工程量的网络栈实现**。建议：

- **方案 A（推荐）**：基于成熟开源库实现，如 `tun2socks` 的 Kotlin/JNI 封装
- **方案 B**：自行实现，需处理 TCP 三次握手、序列号、窗口、分片等
- **方案 C（务实）**：**S3 只做"域名级丢包"** —— 解析 DNS 获取域名，命中黑名单则丢弃对应 IP 的后续连接。这避免了实现完整 TCP 栈，但需维护"IP → 域名"映射表

> **建议采用方案 C 起步**：先实现 DNS 解析 + IP 黑名单丢弃，
> 覆盖「广告域名」这一主要场景，复杂度远低于完整透明代理。
> 若后续确有需要，再升级到方案 A。

### 5.3 与 S2 的代码复用

S2 与 S3 有大量共享逻辑，应抽取公共模块：

| 共享模块 | 用途 |
|---|---|
| `DomainRuleEngine` | 域名匹配（白名单优先 + 精确/后缀） |
| `DnsPacketParser` | DNS 包解析与构造 |
| `DnsInterceptor` | DNS 拦截判定 |
| `VpnArbitrator` | 让位仲裁 |
| `DomainRuleRepository` | 规则数据源 |

**复用策略**：把 DNS 处理抽为独立模块，S2 = 「只有 DNS 处理」，S3 = 「DNS 处理 + 全流量转发」。

```
VpnService 基类（TUN 建立、生命周期、让位）
    ├── DnsOnlyVpnService   → 只挂 DNS 处理
    └── FullTrafficVpnService → 挂 DNS + 流量转发
```

> 但注意：**Android 只允许一个 VpnService 实例运行**。
> 因此更实际的做法是**一个 Service + 模式分支**，而非两个 Service 类：

```kotlin
class NoAdVpnService : VpnService() {
    private var mode: NetworkFilterMode = OFF

    override fun onStartCommand(...): Int {
        mode = intent.getMode()
        val builder = buildBuilder(mode)     // 按模式决定路由配置
        // 后续包处理循环中按 mode 决定是否做流量转发
    }
}
```

---

## 6. 三策略统一控制

### 6.1 状态模型

```kotlin
data class ProtectionState(
    val accessibilityEnabled: Boolean,      // S1 服务运行中
    val networkFilterMode: NetworkFilterMode, // S2/S3 模式
    val vpnYielded: Boolean,                // 是否因其他 VPN 让位
    val vpnEstablished: Boolean,            // TUN 是否已建立
)

enum class NetworkFilterMode { OFF, DNS_ONLY, FULL_TRAFFIC }
```

### 6.2 UI 设计

**首页**（已有页面，数据接真实源）：
- 总保护开关
- **三策略状态卡片**：分别显示 S1 / S2 / S3 的启停状态
- 今日拦截统计（三策略汇总，可按来源分列）

**网络过滤页**（新增 `feature/network`）：
```
┌─────────────────────────────────────┐
│  网络过滤模式                        │
│                                     │
│  ○ 关闭                             │
│    仅使用无障碍拦截弹窗              │
│                                     │
│  ◉ DNS 过滤（推荐）                  │
│    只接管域名解析，性能影响极小       │
│    拦截网页广告与追踪域名             │
│                                     │
│  ○ 全流量过滤                        │
│    接管全部流量，拦截能力最强         │
│    ⚠ 将占用系统 VPN，与其他 VPN 冲突  │
│                                     │
├─────────────────────────────────────┤
│  ⓘ 检测到其他 VPN 正在运行           │
│     网络过滤已自动让位                │
│     [了解详情]                       │
└─────────────────────────────────────┘
```

**让位提示的文案要求**（用户能理解、不困惑）：
- 明确说明「已让位给 XXX」而非静默失效
- 说明恢复条件：「其他 VPN 关闭后可手动恢复」
- 不提供「强制夺回」按钮（避免抢占循环）

### 6.3 开关联动规则

| 用户操作 | 系统响应 |
|---|---|
| 开启 S1 | 引导至无障碍设置；Android 13+ 侧载需「允许受限设置」 |
| 开启 S2 | 检查其他 VPN → 有则提示让位；无则请求 VPN 授权 |
| 开启 S3 | 同 S2，但额外确认「将占用 VPN」 |
| S2 ↔ S3 切换 | 停止当前 TUN → 重建（需重新走授权检查） |
| 检测到其他 VPN 启动 | 主动停止 TUN，进入让位状态，UI 明示 |
| 其他 VPN 退出 | **不自动恢复**，等待用户手动开启 |

---

## 7. 规则体系

### 7.1 两套规则

| 规则类型 | 服务策略 | 内容 |
|---|---|---|
| **UI 跳过规则** | S1 | 节点选择器（id/text/desc/坐标） |
| **域名过滤规则** | S2 + S3 | 域名黑白名单（精确/后缀） |

### 7.2 域名规则模型（S2/S3 共享）

```kotlin
enum class MatchType { EXACT, SUFFIX }

data class DomainRule(
    val type: MatchType,
    val pattern: String,
    val category: DomainCategory,   // AD / TRACKER / ANALYTICS
)

data class DomainPolicy(
    val blacklist: List<DomainRule>,
    val whitelist: List<DomainRule> = emptyList(),
) {
    companion object {
        val EMPTY = DomainPolicy(emptyList(), emptyList())
    }
}
```

### 7.3 匹配顺序（白名单必须优先）

```
域名归一化（小写、去尾点）
    ↓
1. 白名单精确匹配
2. 白名单后缀匹配
3. 黑名单精确匹配
4. 黑名单后缀匹配
    ↓
默认放行
```

**为什么白名单优先**：若黑名单有 `example.com`、白名单有 `login.example.com`，
黑名单优先会误伤关键登录域名。

**后缀匹配的正确实现**：

```kotlin
// 错误：badexample.com 会被误匹配到 example.com
domain.endsWith(suffix)

// 正确
domain == suffix || domain.endsWith(".$suffix")
```

### 7.4 规则来源

| 来源 | 说明 |
|---|---|
| 内置规则 | 放 `assets/rules/`，首次启动导入 Room |
| 本地导入 | 支持导入 JSON |
| 用户自定义 | 应用内增删改 |

内置域名规则可参考公开的广告域名列表（如 AdAway 的 hosts 源格式转换）。

---

## 8. 性能与功耗设计

| 策略 | 主线程风险 | 应对 |
|---|---|---|
| S1 | `onAccessibilityEvent` 在主线程 | 最快失败 + 异步写库 + 节流 |
| S2 | 包处理在独立线程 | 只处理 UDP 53，影响面小 |
| S3 | 全流量包处理 | 独立线程 + 缓冲区复用 + MTU 调优 |

**功耗实测预期**：

- S1 单独运行：影响极小
- S1 + S2：DNS 查询量小，影响低（优于纯 S3）
- S1 + S3：预计增加 3%–5% 电量（与 AdGuard 同量级）

**优化要点**：

1. S2/S3 包处理线程使用**固定缓冲池**，避免频繁分配
2. DNS 响应加**短 TTL 缓存**，减少重复查询
3. 上游 DNS 设置超时与熔断，避免拖住主循环
4. 规则匹配使用 **HashSet 预编译**，不做线性扫描
5. S1 的规则按包名预分组，避免每事件全量遍历

---

## 9. 实施路线

在原有六阶段基础上扩展为**三策略并行推进**：

### 阶段 A：基础设施（三策略共享）
1. Room + DataStore 落库
2. 领域模型：`SkipRule`、`DomainRule`、`InterceptLog`、`TargetApp`
3. Repository 层全部实现
4. `InstalledAppDataSource`

### 阶段 B：S1 无障碍（独立，优先验证）
5. `NoAdAccessibilityService` + `EventProcessor`
6. `UiMatcher` + `ClickExecutor`（含降级点击）
7. UI 接线：首页/应用管理/日志/设置

### 阶段 C：网络层公共模块
8. `VpnArbitrator`（让位仲裁）—— **最高优先**
9. `DomainRuleEngine` + `DnsPacketParser`
10. `NoAdVpnService` 骨架 + 模式分发

### 阶段 D：S2 DNS 模式
11. DNS-only 路由配置 + 包处理循环
12. 上游 DNS 读取 + `protect()` 转发
13. 构造 NXDOMAIN/SERVFAIL 响应
14. 网络过滤页 UI

### 阶段 E：S3 全流量模式
15. 全路由配置 + 包处理循环
16. 先做「域名级丢包」（方案 C）
17. 视需要升级到完整转发（方案 A）

### 阶段 F：统一控制与收尾
18. `ProtectionState` 统一状态
19. 让位 UI 与恢复流程
20. 清理规范标注的待修正项
21. 引入 Detekt + ktlint，初始化 Git

---

## 10. 风险清单

| 风险 | 等级 | 应对 |
|---|---|---|
| **与其他 VPN 抢占循环** | 🔴 高 | `onRevoke` 中绝不自动重连；主动让位 |
| **S3 完整 TCP 栈实现难度** | 🔴 高 | 先用方案 C（域名级丢包） |
| **DNS 循环（漏 protect）** | 🔴 高 | 代码审查必查；单测覆盖 |
| **Android 13+ 侧载限制** | 🟡 中 | 图文明示「允许受限设置」引导 |
| **厂商 ROM 保活** | 🟡 中 | 引导后台白名单 + 前台服务 |
| **S2/S3 模式切换抖动** | 🟡 中 | 重建 TUN 时给 UI 明确过渡状态 |
| **上游 DNS 不可用** | 🟡 中 | fallback DNS + 超时熔断 |
| **S1 误点** | 🟡 中 | 包名+Activity 双限定 + 高精度匹配优先 |
| **`AccessibilityNodeInfo` 泄漏** | 🟢 低 | 及时 `recycle()` |

---

## 11. 已完成 / 待决策

### 已确认（本次需求明确）
- ✅ 三策略综合实现
- ✅ S2/S3 互斥，非同时启用（技术必然）
- ✅ 有其他 VPN 时主动让位，且不争抢
- ✅ 定位个人 / 开源，暂不纳入法律与上架约束

### 待决策

| # | 问题 | 影响 |
|---|---|---|
| Q1 | **默认模式**选 DNS_ONLY 还是 FULL_TRAFFIC？ | 影响首次使用体验与默认权限申请 |
| Q2 | **S3 实现深度**：先做域名级丢包，还是直接上完整转发？ | 影响工作量（差异很大） |
| Q3 | **内置域名规则规模**：少量精选 vs 大规模导入？ | 影响误杀率与维护成本 |
| Q4 | **是否保留 S2/S3 之外的「仅 S1」快速模式**？ | 影响低配设备体验 |
| Q5 | **是否引入前台服务保活**？ | 影响常驻通知与稳定性 |

> **新增决策见 `SHIZUKU_ENHANCEMENT.md` §13**（SQ1–SQ6）。
> 其中 **SQ2「Chain-3 是否作为正式 S4 策略纳入本文」** 会影响本节架构描述，
> 若采纳需回头更新 §2 的「S2/S3 互斥」表述为「S2/S3 互斥，但 S4 可叠加」。

---

## 12. 附：关键技术决策速查

| 决策 | 结论 | 理由 |
|---|---|---|
| S1 与 S2/S3 可否同时开 | ✅ 可以 | 无障碍不占用 VPN |
| S2 与 S3 可否同时开 | ❌ 不可以 | 系统只允许一个 VpnService |
| 有其他 VPN 时 | 主动让位，不自动重连 | 避免抢占循环 |
| S2 路由配置 | 只 `addRoute("10.0.0.1", 32)` | 影响面收窄，性能好 |
| S3 路由配置 | `addRoute("0.0.0.0", 0)` | 需要接管全部流量 |
| 上游 DNS 读取时机 | `establish()` **之前** | 否则读到自己的虚拟 DNS |
| 转发 socket | 必须 `protect()` | 否则死循环 |
| 黑名单命中返回 | `NXDOMAIN` | 应用快速放弃 |
| 上游故障返回 | `SERVFAIL` | 语义区分，便于排查 |
| 白名单优先级 | 高于黑名单 | 避免误伤登录等关键域名 |
| 后缀匹配写法 | `domain == s \|\| domain.endsWith(".$s")` | 防 `badexample.com` 误匹配 |
