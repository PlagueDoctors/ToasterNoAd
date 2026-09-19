# NoAd 四策略综合拦截技术方案

> 目录：`reference/`（技术方案，非最终代码）
> 前置阅读：`android-ad-blocking-research.md`（方案调研）、`IMPLEMENTATION_PLAN.md`（无障碍单策略方案）
> 编制日期：2026-09-19（**最后更新：2026-09-19，已合并 Shizuku 增强内容**）
> 状态：**评审中（阶段 A 已实施）**
> 定位：个人使用 / 开源，暂不纳入法律合规与商店上架约束
>
> ## 📌 文档定位
>
> 本文是 NoAd 的**唯一权威技术方案文档**。所有后续规划以此为准。
> 原先独立成篇的 `SHIZUKU_ENHANCEMENT.md` 内容已合并进本文 §6，
> 该文件保留为历史记录，**不再单独维护**。

---

## 0. 结论摘要（先看这里）

| 策略 | 拦截对象 | 生效层级 | 依赖 | 占用 VPN |
|---|---|---|---|---|
| **S1 无障碍模拟点击** | 开屏广告、弹窗广告 | UI 层 | `AccessibilityService` | ❌ |
| **S2 本地 DNS 过滤** | 网页广告、追踪域名 | 域名解析层 | `VpnService`（仅接管 DNS） | ✅ |
| **S3 全流量 VPN 过滤** | 网络请求层广告（含 App 内） | 网络层 | `VpnService`（接管全流量） | ✅ |
| **S4 应用级断网** | 广告载体 App 的全部联网 | 内核 UID 级 | Shizuku（shell 权限） | ❌ |

**核心约束与解法**：

> Android 同一时刻**只允许一个 VpnService 运行**，因此 **S2 与 S3 互斥**。
> **S4 不建立 TUN、不占用 VPN**，因此可与 S2/S3 叠加，也可在用户自己的 VPN 运行时继续工作。

**最关键的一条改进**：引入 S4 后，「检测到其他 VPN → 让位 → 网络拦截完全失效」
变为「检测到其他 VPN → 降级为 S4 → 应用级拦截仍然生效」。
这是一个**纯增益**的变化。

---

## 1. 方案目标

在 NoAd 内实现四种广告拦截策略，各司其职、互不冲突。

### 1.1 策略间的关系

四种策略**不是并列的替代选项**，而是覆盖不同问题域：

```
┌─ S1 无障碍 ──── UI 层：能"看见并点击"的广告（弹窗、开屏）
│                  无法拦截：网页内的横幅、信息流广告
│
├─ S2 DNS 过滤 ── 域名层：广告服务器域名解析失败
│                  无法拦截：直连 IP、DoH/DoT、已缓存的连接
│
├─ S3 VPN 过滤 ── 网络层：按域名/IP/规则丢弃或重写请求
│                  能力最强，但代价是独占系统 VPN
│
└─ S4 应用断网 ── UID 层：按包名切断整个应用的联网
                   不占用 VPN，可与 S2/S3/他人 VPN 共存
                   无法拦截：只拦广告、保留应用其余联网（做不到）
```

### 1.2 组合关系总览

```
                    ┌──────────────────────┐
                    │   统一控制中心        │
                    │  StrategyCoordinator │
                    └──────────┬───────────┘
                               │
        ┌──────────┬───────────┴───────────┬──────────┐
        ▼          ▼                       ▼          ▼
   ┌─────────┐ ┌───────────┐        ┌───────────┐ ┌──────────┐
   │   S1    │ │    S2     │        │    S3     │ │    S4    │
   │无障碍服务│ │ DNS 模式  │        │全流量模式 │ │应用断网  │
   └─────────┘ └───────────┘        └───────────┘ └──────────┘
        │            │                     │            │
   独立运行      ┌───┴─────────────────────┴───┐   独立运行
   与网络无关    │       互斥（只能选一个）      │   与 VPN 无关
        │       └───────────────┬─────────────┘        │
        │                       │                      │
        ▼                       ▼                      ▼
   UI 层弹窗             域名解析/网络层            UID 网络栈
```

**协同规则**：

1. **S1 与其余三策略完全独立**，可同时开启（无障碍服务不占用 VPN）
2. **S2 与 S3 互斥**，由 `NetworkFilterMode` 的单一选项控制
3. **S4 可与任意策略叠加**，它不占用 VPN，也不依赖 TUN
4. **四者共享同一套域名规则库**（S2/S3/S4 的规则判定）与拦截日志
5. **统一开关面板**：首页提供总开关，网络页提供模式选择

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

**关键澄清**：该约束**只作用于 VpnService**。
S4（Shizuku Chain-3）走的是 `NetworkPolicyManager` 的 UID 级防火墙，
**不创建 VPN 接口**，因此**完全不受此约束**。这是本文相对早期版本最重要的修订。

### 2.2 由此推导的架构决策

**决策一：S2 与 S3 是互斥模式，S4 是独立的可叠加能力**

```kotlin
enum class NetworkFilterMode(val label: String) {
    OFF("关闭"),                    // 不接管网络
    DNS_ONLY("DNS 过滤"),           // S2：只接管 DNS 查询
    FULL_TRAFFIC("全流量过滤"),      // S3：接管全部流量
    APP_FIREWALL("应用级断网"),      // S4：Shizuku Chain-3，不占 VPN
    HYBRID("混合模式"),              // S4 + S2 叠加
}
```

| 模式 | 占用 VPN | 需要 Shizuku | 可与他人 VPN 共存 |
|---|---|---|---|
| `OFF` | ❌ | ❌ | ✅ |
| `DNS_ONLY` | ✅ | ❌ | ❌（需让位） |
| `FULL_TRAFFIC` | ✅ | ❌ | ❌（需让位） |
| `APP_FIREWALL` | ❌ | ✅ | ✅ |
| `HYBRID` | ✅ | ✅ | ❌（需让位） |

> ⚠️ **`HYBRID` 的命名需要留意**：它表示「S4 + S2 叠加」，
> 因为 S2 占用 VPN，所以 `HYBRID` \*仍然\*要占用 VPN。
> 真正「两全其美」的组合是 `APP_FIREWALL` + 用户自己的 VPN。

**决策二：让位逻辑必须主动且优雅**

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
    settingsRepository.setVpnYielded(true)   // 标记"已让位"
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

**决策五（新增）：让位后可降级到 S4，而非彻底放弃**

见 §6.5。这是引入 S4 后对让位逻辑的改进。

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
├── NoAdApplication.kt                  # 入口 + AppContainer（手写 DI）
├── core/
│   ├── model/
│   │   ├── SkipRule.kt                 # S1 规则
│   │   ├── DomainRule.kt               # S2/S3/S4 域名规则
│   │   ├── ProtectionState.kt          # 统一保护状态 + NetworkFilterMode
│   │   ├── InterceptLog.kt             # 拦截日志
│   │   └── TargetApp.kt                # 纳管应用
│   ├── data/
│   │   ├── repository/RuleRepository.kt
│   │   ├── repository/DomainRuleRepository.kt   # 域名黑白名单 + 引擎缓存
│   │   ├── repository/LogRepository.kt
│   │   ├── repository/TargetAppRepository.kt
│   │   ├── rules/BuiltinRulesLoader.kt          # 【阶段A新增】内置规则加载
│   │   └── settings/SettingsRepository.kt       # DataStore
│   ├── database/                       # Room（Entity / DAO / Mappers）
│   ├── engine/                         # 【核心】策略引擎
│   │   ├── DomainRuleEngine.kt         # 【已有】S2/S3/S4 共享的域名匹配
│   │   ├── ui/                         # 【阶段B · 已完成】S1
│   │   │   ├── NodeSnapshot.kt         #   节点只读快照（可测性的前提）
│   │   │   ├── NodeRecycler.kt         #   回收兼容（API 33 起 recycle 弃用）
│   │   │   ├── UiTreeScanner.kt        #   BFS 遍历 + 回收
│   │   │   ├── UiMatcher.kt            #   规则匹配（纯逻辑，24 个单测）
│   │   │   ├── AntiMisclickGate.kt     #   防误点三重闸门（13 个单测）
│   │   │   └── ClickExecutor.kt        #   三级降级点击
│   │   ├── dns/                        # 【阶段D】S2
│   │   │   ├── DnsPacketParser.kt
│   │   │   ├── DnsInterceptor.kt
│   │   │   └── DnsUpstreamResolver.kt
│   │   └── net/                        # 【阶段E】S3
│   │       ├── PacketParser.kt
│   │       ├── TrafficFilter.kt
│   │       └── TcpUdpRelay.kt
│   ├── repository/
│   │   └── S1RuleCache.kt              # 【阶段B】事件热路径的内存规则快照
│   ├── shizuku/                        # 【阶段F】S4 特权通道（可选层）
│   │   ├── ShizukuCapabilityProbe.kt   # 能力探测（一切的前提）
│   │   ├── AppFirewallController.kt    # Chain-3 单应用断网
│   │   ├── PrivateDnsController.kt     # Private DNS 改写
│   │   ├── RestrictedSettingsController.kt  # 侧载限制解除
│   │   ├── AppManagerController.kt     # 组件停用 / 冻结
│   │   ├── AppOpsController.kt         # 权限级控制
│   │   └── ProcessController.kt        # 强制停止
│   ├── service/
│   │   ├── NoAdAccessibilityService.kt      # 【阶段B · 已完成】S1
│   │   ├── AccessibilityStateHolder.kt      # 【阶段B】服务真实状态（供 UI）
│   │   ├── AccessibilitySettingsLauncher.kt # 【阶段B】跳转系统设置
│   │   ├── ProtectionFlags.kt               # 【阶段B】开关内存镜像（供热路径）
│   │   ├── NoAdVpnService.kt                # 【阶段C】S2 + S3
│   │   ├── vpn/VpnArbitrator.kt             # 【阶段C】让位仲裁
│   │   └── event/EventProcessor.kt          # 【阶段B · 已完成】S1 事件流水线
│   ├── applist/InstalledAppDataSource.kt
│   ├── designsystem/  navigation/
├── feature/
│   ├── home/  apps/  logs/  settings/       # 【已有】
│   ├── rules/          # 【阶段B】S1 规则管理
│   └── network/        # 【已有骨架】网络过滤控制页
│       └── NetworkScreen.kt / NetworkViewModel.kt
└── ui/theme/
```

**分层约束**：`core/shizuku` 必须**只被 Service 层与 Repository 层引用**，
不允许 `feature/` 层直接调用 —— 保证 Shizuku 是可选能力而非耦合依赖。

### 3.2 拦截能力矩阵

| 广告类型 | S1 | S2 | S3 | S4 |
|---|---|---|---|---|
| 应用开屏广告 | ✅ 强 | ❌ | ⚠️ 部分 | ✅ 强（切断载体 App 联网） |
| 应用内弹窗广告 | ✅ 强 | ⚠️ 部分 | ✅ 中 | ✅ 强 |
| 网页横幅/插页 | ❌ | ✅ 强 | ✅ 强 | ⚠️ 部分（按浏览器整体） |
| 视频贴片广告 | ❌ | ⚠️ 部分 | ⚠️ 部分 | ⚠️ 部分 |
| 信息流原生广告 | ❌ | ❌ | ❌ | ❌ |
| 追踪/统计域名 | ❌ | ✅ 强 | ✅ 强 | ✅ 强 |

> **诚实说明**：信息流原生广告（广告内容与正文同服务器同域名）**四种策略都无法拦截**，
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

> 这四点必须在 UI 上说明（`NetworkScreen` 已有「能力说明」区块），
> 否则用户会认为"拦不住就是软件不行"。

### 4.5 替代路径：Private DNS 改写（需要 Shizuku）

见 §6.4。当用户使用现成 DNS 过滤服务（AdGuard DNS / NextDNS）时，
改写系统 Private DNS 可**零代码、不占 VPN** 地达成类似效果，
代价是只能用服务商规则、不能自定义。

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

## 6. S4：Shizuku 特权通道（应用级断网 + 增强能力）

> 本节由原 `SHIZUKU_ENHANCEMENT.md` 合并而来。
> **定位**：可选增强，非核心依赖。NoAd 在没有 Shizuku 的设备上必须功能完整。

### 6.1 Chain-3 单应用网络控制（★★★ 架构级价值）

#### 6.1.1 为什么这条最重要

回看 §2 的**最硬约束**：

> Android 同一时刻**只允许一个 VpnService 运行**。

**Chain-3 绕开了整个问题**：

```
通过 Shizuku 调用 connectivity 服务的 Chain-3 接口，
可以按包名阻止 / 允许某个应用联网。

关键特性：
    ✅ 不建立 TUN 设备
    ✅ 不占用系统 VPN
    ✅ 可以与其他 VPN 同时使用
    ✅ 拦截发生在内核网络栈层，应用层无法绕过
```

#### 6.1.2 实现原理

底层是 Android 的 **Chain-3（旧称 `mBlockedUids` / `NetworkPolicyManager`）** 机制，
原本用于「省流量 / 后台联网限制」场景。它按 **UID** 级别控制网络访问。

```kotlin
// 通过 Shizuku 执行（形式示意）
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
> 这正是 §6.2 能力探测必须存在的原因。

#### 6.1.3 能力边界（必须明确）

| 能做 | 不能做 |
|---|---|
| 按 App 整体切断联网 | ❌ 按域名切断（chain 是 UID 级） |
| 与其他 VPN **共存** | ❌ 只拦广告、保留应用其余联网 |
| 内核级生效，应用难绕过 | ❌ 对系统应用 / 白名单应用通常无效 |

因此链条定位是：

> **Chain-3 解决的是「这个 App 的联网我全不要」，
> 而不是「这个 App 里的广告域名我不要」。**

#### 6.1.4 对策略架构的影响

引入 Chain-3 后，网络拦截从「二选一」变成「二选一 + 可叠加」：

```kotlin
enum class NetworkFilterMode {
    OFF,
    DNS_ONLY,              // S2：VpnService 只接管 DNS
    FULL_TRAFFIC,          // S3：VpnService 接管全流量
    APP_FIREWALL,          // 【新增】Chain-3 按应用断网（不占 VPN）
    HYBRID,                // 【新增】APP_FIREWALL + DNS_ONLY 叠加
}
```

**各组合的效果**：

| 组合 | 效果 | 是否占用 VPN |
|---|---|---|
| `APP_FIREWALL` 单独 | 彻底断掉广告 App 的网 | ❌ 不占用 ✅ |
| `DNS_ONLY` 单独 | 域名级过滤，性能好 | ✅ 占用 |
| `APP_FIREWALL + DNS_ONLY` | 双保险 | ⚠️ `HYBRID`，仍占 VPN |
| `APP_FIREWALL` + 用户自己的 VPN | **两全其美** | ✅ 用户 VPN 正常工作 |

> **最有价值的场景**：用户需要连公司 VPN 时，
> NoAd 用 Chain-3 提供按应用断网，
> **完全让出 VPN 通道给用户的 VPN** —— 这比「让位后什么都不做」强得多。

### 6.2 Shizuku 能力边界（必须写进文档，否则会做出错误设计）

#### 6.2.1 原理

```
用户用电脑执行一次 ADB 命令
        ↓
启动 Shizuku Server（以 shell 身份，UID 2000）
        ↓
App 启动时收到 Shizuku Server 的 Binder
        ↓
App 通过 ShizukuBinderWrapper 把系统 API 调用转发给 Shizuku Server 执行
        ↓
Server 以 UID 2000 的身份调用系统服务
```

本质是**做一个中间人**：接收 App 请求 → 以更高 UID 转发给 system_server → 回传结果。

#### 6.2.2 关键边界

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

#### 6.2.3 与 NoAd 项目基线的兼容性

| 项目事实 | 与 Shizuku 的关系 |
|---|---|
| minSdk 30（Android 11） | ✅ Shizuku 支持范围（11+ 可本机无线调试激活，体验更好） |
| 非 root 定位 | ✅ 完全兼容，Shizuku 就是为非 root 设备设计的 |
| 纯 Compose | ✅ 仅需在 UI 增加"可选增强"入口 |
| 单模块 `app` | ⚠️ Shizuku 依赖建议放独立 `core/shizuku` 包，隔离可选性 |

### 6.3 接入方式设计

#### 6.3.1 依赖

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

> ⚠️ **版本敏感项**：引入前需确认与 AGP 9.3.2 / Kotlin 2.2.10 的兼容性，
> 并确认 `aidl` 插件在 AGP 9.x（built-in Kotlin）下的配置方式。
> **这是需要外部资料确认的事项，实施时先验证再全量接入。**

#### 6.3.2 Manifest 声明

```xml
<provider
    android:name="rikka.shizuku.ShizukuProvider"
    android:authorities="${applicationId}.shizuku"
    android:enabled="true"
    android:exported="true"
    android:multiprocess="false"
    android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
```

#### 6.3.3 生命周期接入

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

#### 6.3.4 统一的可用性探测（一切优化都必须过这道门）

```kotlin
/**
 * Shizuku 能力探测。所有依赖 Shizuku 的功能都必须先经过此门。
 * 设计原则：任何一项探测失败 = 该项优化静默降级，不影响其余功能。
 */
class ShizukuCapabilityProbe {

    data class Capabilities(
        val available: Boolean,               // Shizuku 服务是否可用
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

### 6.4 增强能力二：Private DNS 直接改写（★★）

#### 6.4.1 核心价值

用户需求里明确写了「**DNS / Private DNS 过滤**」。§4 的 S2 是通过**自建 VpnService** 实现 DNS 接管，代价是占用 VPN。

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

#### 6.4.2 实现

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
            "private_dns_mode", "hostname"          // off / opportunistic / hostname
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

#### 6.4.3 与 S2 的关系：**竞争方案**

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

#### 6.4.4 重要限制

| 限制 | 说明 |
|---|---|
| 需要用户**手动安装并启动 Shizuku** | 仍属可选增强 |
| **Private DNS 是全局的** | 影响整机所有 App，不能按包区分 |
| 用户可能在系统设置里改回来 | 需监听设置变化，UI 同步状态 |
| 部分 ROM 有自己的 Private DNS 实现 | 需探测 `private_dns_mode` 是否存在 |
| **DoT 本身仍可被 App 绕过** | 若 App 自带 DoH，不走系统 DNS |

### 6.5 增强能力三：绕过 Android 13+ 无障碍侧载限制（★★）

#### 6.5.1 问题

NoAd 是个人 / 开源项目，几乎肯定是侧载安装 —— 因此这个问题**必然遇到**：

> 侧载安装的应用，其无障碍服务无法直接开启，
> 用户需要手动进入「应用信息 → 允许受限设置」，且部分 ROM 隐藏该入口。

#### 6.5.2 Shizuku 解法

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

#### 6.5.3 降级路径（必须有）

```
用户开启 S1
    ↓
检测是否被受限设置阻止
    ├─ 有 Shizuku → 尝试自动解除 → 成功：直接开启
    └─ 无 Shizuku / 失败 → 展示图文引导：
          「设置 → 应用 → NoAd → 右上角菜单 → 允许受限设置」
          + 提供 ROM 差异说明（MIUI / OneUI / HyperOS 入口不同）
```

### 6.6 增强能力四：应用冻结 / 组件停用（★★）

#### 6.6.1 能力

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

#### 6.6.2 在 NoAd 里怎么用（要有边界）

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

#### 6.6.3 风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 误停用导致应用异常 | 部分 App 停用组件后崩溃 | 提供一键恢复 + 操作日志 |
| 停用系统应用风险高 | 可能导致系统功能异常 | **禁止对系统应用操作**（`FLAG_SYSTEM` 过滤） |
| 用户忘记恢复 | 长期不可用 | 规则页集中展示 + 明显提示 |

### 6.7 增强能力五：AppOps 精细化权限控制（★）

#### 6.7.1 用途

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

#### 6.7.2 对 NoAd 相关的操作

| Op | 用途 | 与广告的关系 |
|---|---|---|
| `RUN_IN_BACKGROUND` | 禁止后台运行 | 阻止广告 SDK 后台拉取素材、保活 |
| `SYSTEM_ALERT_WINDOW` | 悬浮窗 | 阻止「悬浮广告」与「摇一摇跳转」 |
| `GET_DEVICE_ID` / `READ_PHONE_STATE` | 设备标识 | 削弱跨应用广告追踪 |
| `REQUEST_INSTALL_PACKAGES` | 安装其他 App | 阻止广告诱导下载安装 |

#### 6.7.3 重要限制（必须诚实说明）

> **⚠️ 网上大量说法是错误的**：
> `AppOps` 的「拒绝」**不等于**系统权限拒绝。
> 它的实际行为是「**返回空数据而非真实拒绝**」——
> App 仍然认为自己拿到了权限，只是拿到的是空白值。
>
> **好处**：不会触发「不给权限就不让用」。
> **限制**：对某些强校验场景无效。

并且：**Shizuku 无法授予危险权限**（`pm grant` 对 `dangerous` 级权限无效），
只能**拒绝**，不能**帮助 App 获得权限**。这个方向是单向的。

### 6.8 增强能力六：强制停止进程（★）

#### 6.8.1 用途

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

#### 6.8.2 定位与限制

| 项 | 说明 |
|---|---|
| **定位** | S1 的**最后手段**，触发条件必须非常严格 |
| **触发条件建议** | ① 用户已在规则中显式启用 ② 匹配到"自杀式广告"特征 ③ 冷却时间内不重复 |
| **副作用** | 用户正在使用的应用被强停 → **体验破坏** |
| **必须** | 强停后写入日志 + UI 提示「已重置 XX 应用」 |

> ⚠️ **不建议默认开启**。这是最容易引起用户反感的操作。
> 建议设计为「**高风险广告 App 列表**」+ 用户逐个授权，而不是全局开关。

### 6.9 与 `VpnArbitrator` 的整合（对 §2 决策五的展开）

```
用户开启网络拦截
    ↓
VpnArbitrator 检测其他 VPN
    ├─ 无其他 VPN
    │     └─ 按用户选择：DNS_ONLY / FULL_TRAFFIC / APP_FIREWALL / HYBRID
    │
    └─ 有其他 VPN
          ├─ 有 Shizuku → 降级为 APP_FIREWALL（S4）
          │                让出 VPN，但保留应用级拦截 ✅
          └─ 无 Shizuku → 让位，网络拦截不可用 ⚠️
                            UI 明示 + 提示可安装 Shizuku 增强
```

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

**这是一个纯增益的改进**：原本「让位 = 网络拦截全失效」，
现在「让位 = 降级为应用级防火墙」。

### 6.10 更新后的策略矩阵

| 能力 | 无 Shizuku | 有 Shizuku | 是否占用 VPN |
|---|---|---|---|
| S1 无障碍点击 | ✅ | ✅ + 自动解除侧载限制 | ❌ |
| S1 组件停用（根治开屏） | ❌ | ✅ | ❌ |
| S1 强制停止（兜底） | ❌ | ✅ | ❌ |
| S2 DNS 过滤 | ✅（自建 VPN） | ✅ + **Private DNS 替代方案** | ✅ / ❌ |
| S3 全流量过滤 | ✅（自建 VPN） | ✅ | ✅ |
| **S4 应用级断网（Chain-3）** | ❌ | ✅ | ❌ **不占用** |
| 让位后降级 | ❌ 完全失效 | ✅ **降级到 S4** | ❌ |

---

## 7. 三策略统一控制

### 7.1 状态模型

```kotlin
data class ProtectionState(
    val userEnabled: Boolean = false,              // 总开关（用户意图）
    val accessibility: AccessibilityState,         // S1 服务状态
    val networkFilterMode: NetworkFilterMode,      // S2/S3/S4 模式
    val networkFilterActive: Boolean = false,      // 网络过滤是否实际生效
    val yieldReason: VpnYieldReason = VpnYieldReason.NONE,  // 让位原因
    val shizukuAvailable: Boolean = false,         // Shizuku 增强是否可用
)
```

`NetworkFilterMode` 自带两个派生属性，供 UI 决定提示文案与选项可用性：

```kotlin
val NetworkFilterMode.occupiesVpn: Boolean
    get() = this == DNS_ONLY || this == FULL_TRAFFIC || this == HYBRID

val NetworkFilterMode.requiresShizuku: Boolean
    get() = this == APP_FIREWALL || this == HYBRID
```

> 这是把「约束知识」放在枚举上而非散落在 UI 里：
> 新增模式时只需在枚举上声明，各页面自动获得正确的提示。

### 7.2 UI 设计

**首页**：总保护开关 + 策略状态卡片（S1/S2/S3/S4 各自启停）+ 今日拦截统计。

**网络过滤页**（`feature/network`，已实现骨架）：

```
┌─────────────────────────────────────┐
│  过滤模式                            │
│                                     │
│  ○ 关闭                              │
│    仅使用无障碍拦截弹窗广告            │
│                                     │
│  ◉ DNS 过滤                          │
│    只接管域名解析，性能影响极小         │
│    ⚠ 会占用系统 VPN（与其他 VPN 互斥） │
│                                     │
│  ○ 全流量过滤                        │
│    接管全部流量，拦截能力最强           │
│    ⚠ 会占用系统 VPN                    │
│                                     │
│  ○ 应用级断网                        │
│    彻底切断指定应用的联网               │
│    ⚠ 需要安装并启动 Shizuku            │
│                                     │
├─────────────────────────────────────┤
│  ⓘ 检测到其他 VPN 正在运行            │
│     网络过滤已自动让位                 │
│     [了解详情]                        │
└─────────────────────────────────────┘
```

**让位提示的文案要求**（用户能理解、不困惑）：
- 明确说明「已让位给其他 VPN」而非静默失效
- 说明恢复条件：「其他 VPN 关闭后可手动恢复」
- 不提供「强制夺回」按钮（避免抢占循环）
- **有 Shizuku 时**：额外说明「已降级为应用级断网，拦截仍在生效」

### 7.3 开关联动规则

| 用户操作 | 系统响应 |
|---|---|
| 开启 S1 | 引导至无障碍设置；Android 13+ 侧载需「允许受限设置」 |
| 开启 S2 | 检查其他 VPN → 有则提示让位；无则请求 VPN 授权 |
| 开启 S3 | 同 S2，但额外确认「将占用 VPN」 |
| 开启 S4 | 检查 Shizuku 可用性 → 不可用则引导安装 |
| S2 ↔ S3 切换 | 停止当前 TUN → 重建（需重新走授权检查） |
| 检测到其他 VPN 启动 | 主动停止 TUN；**有 Shizuku 则降级为 S4**，否则进入让位状态 |
| 其他 VPN 退出 | **不自动恢复**，等待用户手动开启 |

---

## 8. 规则体系

### 8.1 两套规则

| 规则类型 | 服务策略 | 内容 |
|---|---|---|
| **UI 跳过规则** | S1 | 节点选择器（id/text/desc/坐标） |
| **域名过滤规则** | S2 + S3 + S4（日志归类） | 域名黑白名单（精确/后缀） |

### 8.2 域名规则模型

```kotlin
enum class DomainMatchType { EXACT, SUFFIX }

enum class DomainCategory(val label: String) {
    AD("广告"), TRACKER("追踪"), ANALYTICS("统计"), CUSTOM("自定义"),
}

data class DomainRule(
    val id: Long = 0L,
    val matchType: DomainMatchType,
    val pattern: String,                    // 已归一化：小写、无尾点、无协议前缀
    val category: DomainCategory = DomainCategory.AD,
    val note: String? = null,
    val enabled: Boolean = true,
)

data class DomainPolicy(
    val blacklist: List<DomainRule> = emptyList(),
    val whitelist: List<DomainRule> = emptyList(),
)
```

### 8.3 匹配顺序（白名单必须优先）

```
域名归一化（小写、去尾点、去端口、去协议）
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

// 正确：逐级剥离子域，逐级精确比较
var cursor = domain
while (true) {
    if (suffixes.contains(cursor)) return cursor
    val dot = cursor.indexOf('.')
    if (dot < 0) return null
    cursor = cursor.substring(dot + 1)
}
```

> 这个写法比 `endsWith(".$suffix")` 更快（无需字符串拼接），
> 且天然避免了 `badexample.com` 误匹配。已在 `DomainRuleEngine` 中实现并有单测覆盖。

### 8.4 内置规则方案（✅ 已定，2026-09-19）

**决策**：规则数据放 `app/src/main/assets/rules/builtin_domains.json`，
由 `BuiltinRulesLoader` 解析并在首次启动时导入 Room。

**规模**：**60 条黑名单 + 6 条白名单**（刻意精简，见下方判定标准）。

**为什么放 assets 而不是 Kotlin 常量**：

| 维度 | Kotlin 常量 | assets JSON（已采纳） |
|---|---|---|
| 规则增长 | 膨胀 dex，需重新编译 | 只影响 assets 大小 |
| 可审阅性 | 需读代码 | 纯数据，可 diff |
| 更新方式 | 改代码重发版 | 可替换文件或后续做增量更新 |
| 人工编辑风险 | 编译期发现语法错误 | 运行期需容错（已有跳过机制） |

**选取标准**（写入 JSON 的 `meta.criteria`，作为后续扩充的硬约束）：

1. 仅收录**纯粹用于**广告投放、广告交易、广告监测、统计上报的域名
2. 排除任何同时承载业务接口、登录、支付、内容分发的域名
3. 排除存在争议的「全家桶」主域（如 `google.com`、`qq.com` 本身）
4. 每条规则必须有 `note` 说明用途，便于后续审计与移除
5. 规模保持精简，**优先保证零误杀，而非追逐拦截率**

**白名单的用途**：文件中登记的白名单条目是**显式声明的风险权衡**。
例如友盟（`umeng.com`）同时承载崩溃上报与分享回调，
把它放进黑名单可能破坏某些 App 的分享功能 ——
因此文件里以白名单形式登记该权衡，并在 `note` 中写明理由。
**若只导入黑名单而不导入白名单，这层防护会静默失效。**

**质量保障**（两个测试类，共 29 个断言）：

| 测试类 | 职责 |
|---|---|
| `BuiltinRulesLoaderTest` | 解析器逻辑：坏数据单条跳过不整体丢弃、分类容错、空值处理 |
| `BuiltinRulesAssetTest` | **对真实文件做契约测试**：图案格式、无重复、白名单确实生效、业务域名确实不被拦、规模约束 |

> `BuiltinRulesAssetTest` 是这里最关键的设计。
> 如果只测解析器，文件本身写错了（误把业务域名加进黑名单）不会有任何测试失败，
> 而错误会直接进入用户设备。该测试直接读取真实文件并断言语义约束。

**后续扩展**：支持用户在规则页导入更大规模列表
（如 AdAway hosts 源转换结果），内置集合保持精简。

### 8.5 规则来源

| 来源 | 说明 | 当前状态 |
|---|---|---|
| 内置规则 | `assets/rules/builtin_domains.json` | ✅ 已实现 |
| 本地导入 | 支持导入 JSON | ⏳ 待实现（Repository 已有 `importBlacklist` / `importWhitelist`） |
| 用户自定义 | 应用内增删改 | ⏳ 待实现 |

---

## 9. 性能与功耗设计

| 策略 | 主线程风险 | 应对 |
|---|---|---|
| S1 | `onAccessibilityEvent` 在主线程 | 最快失败 + 异步写库 + 节流 |
| S2 | 包处理在独立线程 | 只处理 UDP 53，影响面小 |
| S3 | 全流量包处理 | 独立线程 + 缓冲区复用 + MTU 调优 |
| S4 | Binder IPC 调用 | 低频操作，按需调用 + 缓存探测结果 |

**功耗实测预期**：

- S1 单独运行：影响极小
- S1 + S2：DNS 查询量小，影响低（优于纯 S3）
- S1 + S4：接近无额外开销（无 TUN、无包处理）
- S1 + S3：预计增加 3%–5% 电量（与 AdGuard 同量级）

**优化要点**：

1. S2/S3 包处理线程使用**固定缓冲池**，避免频繁分配
2. DNS 响应加**短 TTL 缓存**，减少重复查询
3. 上游 DNS 设置超时与熔断，避免拖住主循环
4. 规则匹配使用 **HashSet 预编译**（`DomainRuleEngine` 已实现，且引擎不可变因而无锁）
5. S1 的规则按包名预分组，避免每事件全量遍历

---

## 10. 实施路线

### 阶段 A：基础设施（四策略共享）—— ✅ **已完成（2026-09-19）**

1. Room + DataStore 落库
2. 领域模型：`SkipRule`、`DomainRule`、`InterceptLog`、`TargetApp`、`ProtectionState`
3. Repository 层全部实现
4. `InstalledAppDataSource`

**实际落地内容与计划的差异**：

| 项 | 计划 | 实际 |
|---|---|---|
| 新增依赖 | Room + DataStore | 另需 **KSP**（Room 注解处理），版本 `2.2.10-2.0.2` |
| 依赖注入 | 未说明 | 采用**手写 `AppContainer`**，未引入 DI 框架 |
| 构建调整 | 未说明 | 需 `android.disallowKotlinSourceSets=false`（AGP 9.x 内置 Kotlin 限制） |
| 额外产出 | 未说明 | `DomainRuleEngine`（域名匹配）+ `NetworkScreen` 骨架 |
| 策略枚举 | OFF / DNS_ONLY / FULL_TRAFFIC | 扩展为 + `APP_FIREWALL` / `HYBRID`（S4） |
| 内置规则 | 仅占位 4 条 | **已定稿为 assets JSON，60 + 6 条，含契约测试** |
| 应用图标 | 未提及 | 替换模板机器人为**原创矢量图标**（含 monochrome 层与位图） |

**验证证据**：

```
./gradlew testDebugUnitTest   → BUILD SUCCESSFUL
  45 tests, 0 failures, 0 skipped
  ├── DomainRuleEngineTest        15
  ├── BuiltinRulesLoaderTest      16
  ├── BuiltinRulesAssetTest       13
  └── ExampleUnitTest              1

./gradlew assembleDebug       → BUILD SUCCESSFUL in 49s
  APK 内确认包含：
    assets/rules/builtin_domains.json          (8114 B)
    res/drawable/ic_launcher_foreground.xml    (1272 B)
    res/drawable/ic_launcher_monochrome.xml    (1272 B)
    res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher{,_round}.png
    旧的 *.webp 模板机器人图标已移除
```

### 阶段 B：S1 无障碍（独立，优先验证）—— ✅ **已完成（2026-09-19）**

5. `NoAdAccessibilityService` + `EventProcessor`
6. `UiMatcher` + `ClickExecutor`（含降级点击）
7. UI 接线：首页/应用管理/日志/设置

**实际落地内容与计划的差异**：

| 项 | 计划 | 实际 |
|---|---|---|
| 节点抽象 | 未说明 | 新增 **`NodeSnapshot`**：`AccessibilityNodeInfo` 无法在 JVM 单测中构造，必须抽象后才可测 |
| 误点防护 | 未提及 | 新增 **`AntiMisclickGate`**（三重闸门），S1 是唯一会代替用户操作界面的策略 |
| 节点回收 | 计划提到需 `recycle()` | 新增 **`NodeRecycler`**：`recycle()` 自 **API 33 起弃用**，须按版本分支而非直接删除 |
| 服务状态 | 未说明 | 新增 **`AccessibilityStateHolder`** + **`ProtectionFlags`**：前者供 UI，后者供事件热路径 |
| 规则缓存 | 未说明 | 新增 **`S1RuleCache`**：事件回调在主线程，禁止查库 |
| 首页状态 | 计划"接入真实状态" | `active` 不再写死 `false`，改为三态（运行中/待开启/未接入） |
| 引导 UI | 未说明 | 新增引导卡片，区分"缺授权"与"缺应用内开关" |

**关键设计决策（本阶段确立）**：

1. **`NodeSnapshot` 抽象是单测的前提**。`AccessibilityNodeInfo` 无法构造、
   必须成对 `recycle()`、且只在事件回调期间有效。不抽象则匹配逻辑
   （即"该不该点这个节点"）完全无法测试 —— 而 S1 恰恰是误点风险最高的策略。

2. **误点防护的成本是不对称的**。漏点 = 广告没关掉；误点 = 可能触发付费/订阅。
   因此 `AntiMisclickGate` 采用三重限制且偏保守：
   - 同一规则冷却 3s（开屏广告不连续出现）
   - 同一节点冷却 5s（点掉后节点应消失，仍在说明点击无效）
   - 全局节流 400ms（略大于系统最小可感知点击间隔）
   - **窗口切换时重置**（否则新界面会被上一界面的残留冷却影响，表现为"偶发失效"）

3. **`recycle()` 不能直接删**。API 33 起框架接管节点生命周期，
   但 `minSdk = 30` 意味着 API 30–32 上不回收是**真实原生内存泄漏**。
   用 `NodeRecycler` 集中做版本分支，同时保留"为什么"的说明 ——
   直接 `@Suppress` 会让后来者误以为可以删掉回收调用。

4. **一次性只点一个节点**。`matchBest` 返回全局最佳匹配而非全部命中，
   连续点击多个节点会显著提高误点概率。

5. **点击三级降级**：节点自身 → 向上找可点击祖先（最多 5 层）→ 坐标手势。
   "向上找祖先"是关键：大量广告的跳过 `TextView` 自身 `clickable=false`，
   真正响应点击的是父容器。缺这一步会表现为"规则配置正确但就是点不掉"。

**验证证据**：

```
./gradlew clean testDebugUnitTest assembleDebug  → BUILD SUCCESSFUL in 12s
  82 tests, 0 failures, 0 skipped
  ├── UiMatcherTest               24  【阶段B新增】
  ├── BuiltinRulesLoaderTest      16
  ├── DomainRuleEngineTest        15
  ├── BuiltinRulesAssetTest       13
  ├── AntiMisclickGateTest        13  【阶段B新增】
  └── ExampleUnitTest              1
  APK: app/build/outputs/apk/debug/app-debug.apk (66 MB)
  编译告警: 0
```

APK 内容校验：

```
assets/rules/builtin_domains.json          （内置域名规则）
res/xml/accessibility_service_config.xml   （无障碍服务配置）
AndroidManifest 中 /core/service/NoAdAccessibilityService 已注册，
  permission=BIND_ACCESSIBILITY_SERVICE，exported=true，
  meta-data 指向 @xml/accessibility_service_config
```

**实机调试前必须知道的三件事**（写给实施者）：

1. **S1 生效需要两层授权，缺一不可**：
   系统设置里授权服务 **且** 应用内开关打开。
   首页引导卡片会区分提示当前缺哪一层。

2. **Android 13+ 侧载限制**：从浏览器等渠道安装时，
   无障碍列表里可能**看不到** NoAd。需要在「应用信息 → 右上角菜单 →
   允许受限设置」中放行。本阶段**只做文案引导**，
   用 Shizuku 自动解除属于阶段 F。

3. **验证拦截是否真的生效**：
   - `adb logcat -s NoAdAccessibility` 可看每次事件的判定结果
     （`DEBUG_LOG` 目前为 `true`，发布前应关闭）
   - 应用内「拦截日志」页应出现 `ACCESSIBILITY` 来源的记录
   - 若日志为空：先确认纳管了目标应用（**默认不纳管任何应用**），
     且该应用在「应用管理」页的开关已打开


### 阶段 C：网络层公共模块
8. `VpnArbitrator`（让位仲裁）—— **最高优先**
9. `DnsPacketParser`（`DomainRuleEngine` 已完成）
10. `NoAdVpnService` 骨架 + 模式分发

### 阶段 D：S2 DNS 模式
11. DNS-only 路由配置 + 包处理循环
12. 上游 DNS 读取 + `protect()` 转发
13. 构造 NXDOMAIN/SERVFAIL 响应
14. 网络过滤页 UI 接线真实状态

### 阶段 E：S3 全流量模式
15. 全路由配置 + 包处理循环
16. 先做「域名级丢包」（方案 C）
17. 视需要升级到完整转发（方案 A）

### 阶段 F：S4 Shizuku 增强

> 插入位置：**建议在阶段 C 之前先做 F1/F2**（详见 §12 优先级表），
> 因为侧载限制解除是「必然遇到」的问题，且成本极低。

18. **F1**：`ShizukuCapabilityProbe` + Binder 基础设施 + 依赖接入验证
19. **F2**：侧载限制解除（§6.5）
20. **F3**：Chain-3 应用级断网 `AppFirewallController`（§6.1）
21. **F4**：Private DNS 改写（§6.4）
22. **F5**：组件停用（§6.6）
23. **F6**：AppOps 权限控制（§6.7）
24. **F7**：强制停止（§6.8）
25. 让位后降级到 S4 的链路打通（§6.9）

### 阶段 G：统一控制与收尾
26. `ProtectionState` 统一状态接线（Service 层 → UI）
27. 让位 UI 与恢复流程
28. 「增强能力」设置页（如实标注每项状态）
29. 引入 Detekt + ktlint，Git 提交规范化

---

## 11. 风险清单

### 11.1 通用风险

| 风险 | 等级 | 应对 |
|---|---|---|
| **与其他 VPN 抢占循环** | 🔴 高 | `onRevoke` 中绝不自动重连；主动让位 |
| **S3 完整 TCP 栈实现难度** | 🔴 高 | 先用方案 C（域名级丢包） |
| **DNS 循环（漏 protect）** | 🔴 高 | 代码审查必查；单测覆盖 |
| **Android 13+ 侧载限制** | 🟡 中 | 图文引导 + Shizuku 自动解除（S4 路径） |
| **厂商 ROM 保活** | 🟡 中 | 引导后台白名单 + 前台服务 |
| **S2/S3 模式切换抖动** | 🟡 中 | 重建 TUN 时给 UI 明确过渡状态 |
| **上游 DNS 不可用** | 🟡 中 | fallback DNS + 超时熔断 |
| **S1 误点** | 🟡 中 | 包名+Activity 双限定 + 高精度匹配优先 |
| **`AccessibilityNodeInfo` 泄漏** | 🟢 低 | 及时 `recycle()` |

### 11.2 Shizuku 专属风险

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

## 12. Shizuku 增强实施优先级

| 阶段 | 内容 | 价值 | 复杂度 |
|---|---|---|---|
| **P0** | `ShizukuCapabilityProbe` + Binder 基础设施 | 一切的前提 | 中 |
| **P0** | 侧载限制解除（§6.5） | **必遇问题**，体验提升巨大 | 低 |
| **P1** | **Chain-3 应用级断网（§6.1）** | **架构级价值**，解决 VPN 互斥 | 高 |
| **P1** | Private DNS 改写（§6.4） | 零成本高价值 | 低 |
| **P2** | 组件停用（§6.6） | 根治开屏广告 | 中 |
| **P2** | AppOps 权限控制（§6.7） | 削弱广告 SDK | 中 |
| **P3** | 强制停止（§6.8） | 兜底能力，风险高 | 低 |

> **建议**：P0 两项先做，立刻可见价值（且侧载限制是必然遇到的问题）；
> Chain-3 是真正的架构升级，值得投入。

---

## 13. 设计原则（必须遵守）

### 13.1 Shizuku 是**可选增强**，不是前提

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
    ) { base, shizuku -> ProtectionState(core = base, enhancements = shizuku) }
}
```

**验收标准**：在一台**没有安装 Shizuku** 的设备上，
NoAd 的**所有核心功能必须完整可用**，只是没有增强项。

### 13.2 每一项能力都要独立降级

不能出现「Shizuku 挂了一个功能导致全盘失效」。每一项都要：

```
探测能力 → 可用则启用 → 不可用则该项静默降级 → UI 如实标注
```

### 13.3 UI 必须如实标注状态

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

**禁止**：装了 Shizuku 就静默启用一堆高风险功能。
所有增强项**默认关闭，由用户显式开启**。

### 13.4 UserService 优先于 newProcess

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

## 14. 已完成 / 待决策

### 已确认

- ✅ 四策略综合实现（S1/S2/S3/S4）
- ✅ S2/S3 互斥，非同时启用（技术必然）
- ✅ S4 可与 S2/S3 或他人 VPN 叠加（不占用 VPN）
- ✅ 有其他 VPN 时主动让位，且不争抢；有 Shizuku 时降级到 S4
- ✅ 定位个人 / 开源，暂不纳入法律与上架约束
- ✅ **内置域名规则方案**：assets JSON，60 + 6 条，含契约测试（原 Q3）
- ✅ **应用图标方案**：原创矢量，含 monochrome 层与位图（见 §15）

### 待决策

| # | 问题 | 影响 | 建议 |
|---|---|---|---|
| Q1 | **默认模式**选 `DNS_ONLY` 还是 `FULL_TRAFFIC`？ | 首次使用体验与默认权限申请 | 建议 `DNS_ONLY`（性能好、影响面小） |
| Q2 | **S3 实现深度**：先做域名级丢包，还是直接上完整转发？ | 工作量差异很大 | 建议先做方案 C |
| Q4 | **是否保留「仅 S1」快速模式**？ | 低配设备体验 | 建议保留（`OFF` 模式即是） |
| Q5 | **是否引入前台服务保活**？ | 常驻通知与稳定性 | 建议引入，由设置开关控制 |
| **SQ1** | 是否引入 Shizuku 依赖？ | 决定整个增强层是否存在 | 建议引入，但严格保持可选 |
| **SQ2** | Chain-3 是否作为正式 S4 策略纳入方案？ | 影响架构文档与实现路线 | **✅ 已采纳**（本文已将其登记为 S4） |
| **SQ3** | Private DNS 与 S2 自建 DNS 如何共存？ | 网络页 UI 复杂度 | 建议都提供，Private DNS 定位为懒人模式 |
| **SQ4** | 组件停用功能是否做？ | 项目定位边界（是否偏向应用管理器） | 建议做，但只针对用户显式确认的组件 |
| **SQ5** | 强制停止是否提供？ | 用户信任 | 建议默认关闭，逐个授权 |
| **SQ6** | 增强项默认全部关闭，还是提供「一键启用推荐项」？ | 易用性 vs 安全性 | 建议默认全关 + 逐项引导 |

> 原 Q3（内置域名规则规模）已由 §8.4 的决策关闭。

---

## 15. 应用图标（✅ 已定，2026-09-19）

### 15.1 结论

| 项 | 决策 |
|---|---|
| 方案 | **完全原创**的矢量图形 |
| 许可风险 | **零**（无第三方素材，无署名义务） |
| 图形语义 | 被斜杠切断的方框 = 「被拦截的广告位」 |
| 配色 | 与应用色板同源（Emerald 家族） |
| 交付物 | 自适应图标 XML（3 层）+ 各密度 PNG 位图 + 生成脚本 |

### 15.2 为什么选「原创」而不是引用宽松许可的图标库

考察过三个许可最宽松的主流图标库，均可商用：

| 图标库 | 许可 | 商用 | 署名义务 |
|---|---|---|---|
| Material Symbols | Apache-2.0 | ✅ | 需保留版权与许可声明 |
| Lucide | ISC | ✅ | 需保留版权与许可声明 |
| Phosphor | MIT | ✅ | 需保留版权与许可声明 |

**三者都可用，但都需要保留声明。** 对于一个启动器图标而言，
把第三方许可声明放进「关于」页属于不必要的合规负担 ——
尤其应用图标通常还会被拆出来单独用于 README、商店页、分享图等场景，
每处都要考虑署名。

因此采用**原创绘制**：图形简单（方框 + 斜杠），自己画比引入依赖更干净。

> **补充澄清**：项目内使用 Material Icons（Compose 的 `Icons.Rounded.*`）
> 本身是 Apache-2.0 的正常依赖使用，与「用什么图案做应用图标」是两个独立问题。
> 前者只需在「关于」页保留声明，后者已通过原创规避。

### 15.3 设计说明

```
┌─────────────────────────┐
│  ╲                      │   背景：深青 → 翡翠的对角渐变
│   ╲   ┌───────┐         │         + 右上柔光 + 左下冷光补充
│    ╲  │       │         │
│     ╲ │   ╱   │         │   前景：白色方框（描边 7，圆角 10）
│      ╲│  ╱    │         │         被 45° 斜杠（宽 7）在正中心切断
│      ╱│ ╱     │         │
│     ╱ │╱      │         │   缺口：半宽 5，落在方框内部
│    ╱  └───────┘         │         形成「已切断」而非「禁止」
│   ╱                     │
└─────────────────────────┘
```

**为什么不用盾牌 / 禁止符号**：

- **盾牌**：安全类最多见的符号，语义也不精确 ——
  盾牌暗示抵御外部入侵，而广告拦截是在已有流量中做过滤
- **禁止符号（圆圈加斜杠）**：语义准确但极度通用，
  缩到 48dp 时与大量「禁用」类应用无法区分

「被切断的方框」在极小尺寸下仍保留独特轮廓：方形 + 斜线。

**比例决策**：方框描边与斜杠**等宽（都是 7）**。
早期版本用 9 宽的斜杠，视觉上斜杠压过方框，读起来像
「一个被划掉的禁止符号」而非「被拦截的广告位」。等宽后形成结构关系而非覆盖关系。

### 15.4 交付物清单

| 文件 | 内容 |
|---|---|
| `res/drawable/ic_launcher_background.xml` | 渐变背景（含两处径向柔光） |
| `res/drawable/ic_launcher_foreground.xml` | 方框 + 斜杠（纯白，evenOdd 挖空） |
| `res/drawable/ic_launcher_monochrome.xml` | Android 13+ 主题化图标图层 |
| `res/mipmap-anydpi/ic_launcher.xml` | 自适应图标定义（方形） |
| `res/mipmap-anydpi/ic_launcher_round.xml` | 自适应图标定义（圆形） |
| `res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png` | 各密度位图（方形） |
| `res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_round.png` | 各密度位图（圆形） |
| `tools/gen_icon_paths.py` | 矢量路径生成（保证斜杠两段严格共线） |
| `tools/gen_icon_pngs.py` | 位图渲染（纯 Python PNG 编码，无第三方依赖） |

**关键实现约束**（已在文件注释中详述）：

1. **单色兼容**：图形只依赖 alpha，缺口靠「背景透出」而非「填背景色」。
   若用背景色填充缺口，单色模式下会变成实色补丁，图形反而被破坏。
2. **不用 `strokeWidth`**：方框的中空用「外轮廓 + 内轮廓挖空 + evenOdd」表达。
   部分启动器生成单色主题图标时会丢弃 stroke 只保留 fill。
3. **矢量与位图必须同步**：几何参数同时存在于
   `ic_launcher_foreground.xml` 与 `tools/gen_icon_pngs.py`，
   修改其一必须同步另一，否则两种图标会出现形态差异。
4. **旧的 `*.webp` 模板机器人图标已删除** ——
   否则会出现「应用内是新图标、桌面还是机器人」的割裂。

---

## 16. 附：关键技术决策速查

| 决策 | 结论 | 理由 |
|---|---|---|
| S1 与其余策略可否同时开 | ✅ 可以 | 无障碍不占用 VPN |
| S2 与 S3 可否同时开 | ❌ 不可以 | 系统只允许一个 VpnService |
| S4 可否与 S2/S3 同时开 | ✅ 可以 | Chain-3 不建立 TUN |
| S4 可否与用户自己的 VPN 同开 | ✅ 可以 | 同上，这是 S4 的核心价值 |
| 有其他 VPN 时 | 让位；有 Shizuku 则降级 S4 | 避免抢占循环，且不白白丧失拦截 |
| S2 路由配置 | 只 `addRoute("10.0.0.1", 32)` | 影响面收窄，性能好 |
| S3 路由配置 | `addRoute("0.0.0.0", 0)` | 需要接管全部流量 |
| 上游 DNS 读取时机 | `establish()` **之前** | 否则读到自己的虚拟 DNS |
| 转发 socket | 必须 `protect()` | 否则死循环 |
| 黑名单命中返回 | `NXDOMAIN` | 应用快速放弃 |
| 上游故障返回 | `SERVFAIL` | 语义区分，便于排查 |
| 白名单优先级 | 高于黑名单 | 避免误伤登录等关键域名 |
| 后缀匹配写法 | 逐级剥离子域 + HashSet 精确比较 | 防 `badexample.com` 误匹配，且无字符串拼接 |
| 内置规则存放位置 | `assets/rules/*.json` | 数据与代码分离，可审阅可替换 |
| 内置规则规模 | 62 黑 + 6 白，精简优先 | 先保证零误杀，规模由用户按需导入 |
| 域名规则引擎 | 不可变 + HashSet 预编译 | 供多线程无锁并发读 |
| Shizuku 是 root 吗 | ❌ 不是，是 shell（UID 2000） | 不能授权危险权限，不能读他人沙盒 |
| Shizuku 重启后还在吗 | ❌ 不在，需重新启动 | 必须提供无 Shizuku 降级路径 |
| 能解决 VPN 互斥吗 | ✅ **能，Chain-3 不占 VPN** | 本方案最重要的架构改进 |
| 推荐 API 方式 | `UserService` > `ShizukuBinderWrapper` > shell | 官方已宣布移除 `newProcess` |
| 应用图标方案 | 原创矢量，零许可风险 | 避免第三方署名义务 |
| 图标单色兼容做法 | 只依赖 alpha，缺口靠背景透出 | 用背景色填缺口会破坏单色渲染 |
