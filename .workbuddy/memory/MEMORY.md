# NoAd 项目长期记忆

## 项目定位

NoAd —— Android 广告拦截应用（自动关闭开屏广告/弹窗广告）。
包名 `com.toaster.noad`，单模块 `app`，纯 Jetpack Compose + Material 3。

## 锁定的技术栈（不得随意升级）

| 组件 | 版本 |
|---|---|
| AGP | 9.3.2 |
| Gradle | 9.5.0 |
| Kotlin | 2.2.10 |
| KSP | 2.2.10-2.0.2 |
| Compose BOM | 2026.02.01 |
| compileSdk / targetSdk | 37 |
| minSdk | 30 |
| JavaVersion | VERSION_11 |
| Room | 2.8.5 |
| DataStore | 1.2.1 |
| coroutines | 1.9.0 |

## 关键构建约束（踩坑记录）

1. **`android.disallowKotlinSourceSets=false` 必须保留在 `gradle.properties`。**
   AGP 9.x 的「内置 Kotlin 支持」默认禁止通过 `kotlin.sourceSets` 追加源码目录，
   而 KSP 会以该方式注册生成目录，缺少此开关会导致配置阶段直接失败。

2. **`ksp { arg("room.schemaLocation", ...) }` 必须同时放在 `defaultConfig` 与顶层 `ksp {}` 两处。**
   仅放一处时 schema 导出或 Kotlin 代码生成会缺失。

3. **Room 禁止使用 `fallbackToDestructiveMigration()`。**
   改表结构必须显式写 `Migration(n, n+1)`，schema 导出在 `app/schemas/`。

4. **`combine` 最多支持 5 个 Flow。** 超过需拆分为嵌套 combine 或预先合并。

5. **Kotlin 的 `Set` 不支持 `set[key]` 下标访问**（会报 "No 'get' operator method"），
   必须用 `contains()` + `map[key]`。

## 架构约定

- **分层**：`core/{model, database, data, engine, applist, designsystem, navigation}` +
  `feature/{home, apps, logs, settings, network}`（Screen + ViewModel 成对）
- **领域模型不依赖 Room 注解**，实体↔领域转换集中在 `core/database/Mappers.kt`
- **枚举在数据库中以 String 存储**（非序号），避免增删枚举导致存量数据错位
- **不使用 DI 框架**：`NoAdApplication.container`（`AppContainer`，全 `lazy`）手动持有依赖；
  ViewModel 通过 `NoAdViewModelFactory` 注入。ViewModel 超过约 15 个时应重新评估
- **Repository 只暴露 Flow**，写操作一律 `suspend`；S1 事件回调路径的写库必须异步

## 广告拦截策略规划（详见 reference/）

| 策略 | 层级 | 依赖 | 是否占用 VPN |
|---|---|---|---|
| S1 无障碍模拟点击 | UI 层 | AccessibilityService | 否 |
| S2 本地 DNS 过滤 | 域名解析层 | VpnService（仅接管 DNS） | 是 |
| S3 本地 VPN 全流量过滤 | 网络层 | VpnService（全流量） | 是 |
| S4 应用级断网 | 内核链 | Shizuku（Chain-3） | **否** |

**核心约束**：Android 同一时刻只允许一个 VpnService 运行 →
S2 与 S3 **互斥**；S1 与它们**可并存**；S4 不占 VPN，可与其他任意组合。

**让位规则**：检测到其他 VPN 时主动停止，绝不自动重连（否则形成无限抢占循环）。

**DNS-only 关键细节**：
- 只 `addRoute("10.0.0.1", 32)`，绝不 `addRoute("0.0.0.0", 0)`
- 上游 DNS 必须在 `establish()` **之前**读取（否则读到自己的虚拟 DNS）
- 转发 socket 必须 `protect()`（否则 DNS 回流死循环）
- 命中黑名单返回 `NXDOMAIN`，上游故障返回 `SERVFAIL`

**域名后缀匹配必须写 `domain == s || domain.endsWith(".$s")`**，
否则 `badexample.com` 会被 `example.com` 误匹配。白名单优先于黑名单。

## 文档索引

- `docs/CODING_STANDARDS.md` —— Kotlin + Android 编码规范（权威）
- `.trae/rules/coding-standards.md` —— 精简约束版（Trae 编辑器自动加载）
- `reference/android-ad-blocking-research.md` —— 现有方案调研
- `reference/IMPLEMENTATION_PLAN.md` —— 无障碍单策略初步方案
- **`reference/THREE_STRATEGY_PLAN.md` —— 四策略综合技术方案（唯一权威，1701 行）**
- `reference/SHIZUKU_ENHANCEMENT.md` —— 已归档，内容合并进主体方案 §6

> **后续所有规划以 `THREE_STRATEGY_PLAN.md` 为准**（用户明确要求）。

## 内置域名规则（已定稿 2026-09-19）

- **位置**：`app/src/main/assets/rules/builtin_domains.json`
- **规模**：60 条黑名单 + 6 条白名单（刻意精简，优先零误杀）
- **加载**：`core/data/rules/BuiltinRulesLoader.kt`，启动时 `NoAdApplication.initializeDomainRules()`
- **关键约束**：白名单**必须与黑名单同批导入**，否则误杀防护静默失效；
  `initializeDomainRules` 同时检查黑白名单是否缺失，不只查黑名单
- **测试**：`BuiltinRulesLoaderTest`（解析容错）+ `BuiltinRulesAssetTest`（对真实文件做契约测试）
  —— 后者是重点：只测解析器时文件本身写错不会被发现
- **选取标准**：只收纯粹广告/追踪域；排除业务/登录/支付/内容域；排除争议全家桶主域；
  每条必须有 `note`；规模精简

## 应用图标（已定稿 2026-09-19）

- **方案**：完全原创矢量，零许可风险（不引用任何图标库图案）
- **图形**：白色方框 + 45° 斜杠在正中心切断 = 「被拦截的广告位」
- **几何**：方框 26..82 环宽 7 圆角 10；斜杠宽 7；缺口半宽 5（缺口中心恰在 54,54）
- **文件**：`ic_launcher_background.xml`（渐变）、`ic_launcher_foreground.xml`、
  `ic_launcher_monochrome.xml`、`mipmap-anydpi/ic_launcher{,_round}.xml`、
  `mipmap-*dpi/ic_launcher{,_round}.png`
- **生成脚本**：`tools/gen_icon_paths.py`（矢量路径）、`tools/gen_icon_pngs.py`（纯 Python PNG 编码）

**图标三条硬约束（改图标前必读）**：
1. **单色兼容**：图形只依赖 alpha，缺口靠「背景透出」而非「填背景色」。
   用背景色填缺口会让单色模式出现实色补丁，图形反而被破坏。
2. **不用 `strokeWidth`**：中空方框用「外轮廓 + 内轮廓 + evenOdd」表达。
   部分启动器生成单色主题图标时会丢弃 stroke 只保留 fill。
3. **矢量与位图必须同步**：几何参数同时存在于 `ic_launcher_foreground.xml`
   与 `tools/gen_icon_pngs.py`，改其一必须同步另一。
- `ic_launcher_monochrome.xml` 必须与 `ic_launcher_foreground.xml` **完全一致**
- 旧的 `*.webp` 模板机器人图标已删除，不要恢复

## 质量基线

| 指标 | 当前值 |
|---|---|
| 单元测试 | **243 tests / 14 类 / 0 failures / 0 errors**（2026-09-19 R9 后） |
| 覆盖的测试类 | BuiltinSkipRulesLoaderTest(31)、UiMatcherTest(24)、BuiltinSkipRulesAssetTest(24)、SkipDiagnosticsTest(24)、AccessibilityStateHolderTest(21)、EventPreFilterTest(17)、RuleRepositoryTest(16)、BuiltinRulesLoaderTest(16)、S1RuleCacheTest(15)、DomainRuleEngineTest(15)、BuiltinRulesAssetTest(13)、AntiMisclickGateTest(13)、**KeepAlivePolicyTest(12)**、ExampleUnitTest(1) |
| 真机测试 | `MigrationTest`(6) —— 已编写，**由用户执行**，不纳入自动化交付验证 |
| 构建 | 编译告警 0（`app/src` 下无任何 `^w:`） |

### ⚠️ 交付验证标准（用户 2026-09-19 明确定界）

> "我不需要你做实机测试或者编译完整apk文件，所有实机测试由我做，
> 你对实机测试适应太差报错太多，写好代码编译无问题即可"

**唯一验收标准**：`./gradlew testDebugUnitTest` 全绿 + `compileDebugKotlin` 0 error 0 warning。
**禁止**：运行 `connectedDebugAndroidTest`；把 `assembleDebug` 作为交付条件。

### ⚠️ 序列化依赖冲突修复（不可回退）

`room-testing` 的 `room-migration` 需要 kotlinx-serialization **1.8.1**，
但 AGP 的 "consistent resolution" 会把主配置的 **1.7.3** 以 `strictly`
传播到 `debugAndroidTestRuntimeClasspath`，与 Kotlin 2.2.10 生成代码 ABI 不兼容。

现象极具误导性：`AbstractMethodError: GeneratedSerializer.typeParametersSerializers()`
（报错发生在测试框架内部的描述符哈希计算，**与真实原因完全无关**）。

**必须保留** `app/build.gradle.kts` 中的：
```kotlin
configurations.configureEach {
    resolutionStrategy { force(libs.kotlinx.serialization.json.get().toString()) }
}
```
以及 `libs.versions.toml` 中 `serialization = "1.8.1"`。

## S1 无障碍（阶段 B：代码完成，待实机验证）

**新增文件**（`core/engine/ui/`、`core/service/`、`core/repository/`）：

| 文件 | 职责 |
|---|---|
| `engine/ui/NodeSnapshot.kt` | 节点只读快照 —— **可测性的前提** |
| `engine/ui/NodeRecycler.kt` | `recycle()` 版本兼容（API 33 起弃用） |
| `engine/ui/UiTreeScanner.kt` | BFS 遍历 + 回收，上限 2000 节点防 ANR |
| `engine/ui/UiMatcher.kt` | 规则匹配（纯逻辑，无 Android 依赖） |
| `engine/ui/AntiMisclickGate.kt` | 防误点三重闸门 |
| `engine/ui/ClickExecutor.kt` | 三级降级点击 |
| `repository/S1RuleCache.kt` | 事件热路径的内存规则快照 |
| `service/NoAdAccessibilityService.kt` | 服务主体 |
| `service/AccessibilityStateHolder.kt` | 服务真实状态（StateFlow，供 UI） |
| `service/ProtectionFlags.kt` | 开关内存镜像（AtomicBoolean，供热路径） |
| `service/AccessibilitySettingsLauncher.kt` | 跳转系统设置（多级降级） |
| `service/event/EventProcessor.kt` | 事件流水线（四道闸门） |

**必须遵守的设计约束**：

1. **`AccessibilityNodeInfo` 绝不进入匹配逻辑**。它无法在 JVM 单测中构造、
   必须成对 `recycle()`、且只在事件回调期间有效。所有匹配操作 `NodeSnapshot`。
2. **`recycle()` 不能删**。API 33 弃用但 `minSdk=30`，API 30–32 不回收是真实内存泄漏。
   统一走 `NodeRecycler`，不要 `@Suppress` 后在调用点直接删。
3. **事件回调（主线程）禁止查库**。包名判断走 `S1RuleCache` 内存快照，
   开关判断走 `ProtectionFlags` 内存镜像。
4. **一次事件只点一个节点**。`matchBest` 返回单个最佳匹配。
5. **点击三级降级**：节点自身 → 向上找可点击祖先（≤5 层）→ 坐标手势。
   `canPerformGestures="true"` 已在服务配置中声明。
6. **窗口切换必须 `gate.reset()`**，否则新界面被上一界面残留冷却影响，
   表现为难以复现的"偶发失效"。

**S1 生效需要两层授权**（UI 文案必须区分，否则用户困惑）：
系统设置授权服务 **AND** 应用内开关打开。详见 `AccessibilityState.isEffectivelyActive`。

**默认不纳管任何应用** —— 用户未在「应用管理」勾选前，S1 不会拦截任何应用。
排查"没效果"时先确认这一点。

## ⭐ skip_rule 规则链路（2026-09-19 修复，改动前必读）

### 曾经的致命缺陷：一条没有入口的流水线

`EventProcessor` / `S1RuleCache` / `UiMatcher` / `ClickExecutor` / `AntiMisclickGate`
全部实现正确且有单测覆盖，但 `RuleRepository.add/addAll` **全项目零调用点**、
assets 下**无 S1 规则文件**、`feature/` 下**无规则页** ——
结果 `skip_rule` 表恒空 → `S1RuleCache` 返回 `EMPTY` → 100% 事件被第 1 道闸丢弃。

**教训：零件全绿 ≠ 链路可用。** 新增/修改这条链路时，
必须验证「规则能进 DB」而不只是「匹配逻辑正确」。

### 完整链路

```
assets/rules/builtin_skip_rules.json  (15 应用组 / 21 条 / version 2，含通用层 "*")
  → BuiltinSkipRulesLoader.load(context)   [逐条容错，坏条目只跳过自己]
  → RuleRepository.mergeBuiltin(rules)     [跨来源去重 / 只增不改不删]
  → skip_rule 表 (source = 'builtin')
  → RuleRepository.observeEnabledRules() → S1RuleCache（AtomicReference 内存快照）
        ↑ 通用规则单独存放，查询时附加到已纳管应用（见「匹配规则设计」）
  → EventProcessor 四道闸 → UiMatcher → ClickExecutor → SkipDiagnostics
```

启动入口：`NoAdApplication.applicationScope.launch { initializeSkipRules() }`
（`needsBuiltinImport()` 为 true 时才导入）。

### 三条必须遵守的约定

**1. `skip_rule.source` 必须存 `SkipRuleSource.persistedName`（小写字符串）**

```kotlin
enum class SkipRuleSource(val persistedName: String) {
    BUILTIN("builtin"), IMPORTED("imported"), USER("user");
    companion object {
        const val NAME_BUILTIN = "builtin"; /* ... */
        fun fromName(raw: String?): SkipRuleSource = ...   // 未知值退回 USER
    }
}
```

**绝不能用 `enum.name`** —— 会得到 `"BUILTIN"`/`"USER"`，
而 `Migrations` 的 `DEFAULT 'user'` 与 `SkipRuleEntity.SOURCE_*` 都是小写，
导致 `WHERE source = 'user'` **查不到新数据**，现象是「规则莫名消失」。

**约束**：enum 构造参数**不能引用 companion 常量**
（`Companion object of enum class ... is uninitialized here`），必须写字面量。

**2. `RuleRepository.mergeBuiltin` 的四条契约（均有单测锁定）**

- **幂等**：重复启动/重复导入不产生重复项
- **只增不删**：不删除任何存量规则
- **不改**：用户对规则的改名/停用/删除不被覆盖
- **跨来源去重**：`allExistingKeys()` 查 `dao.loadAll()` 全表；
  用户已建同定位值规则时**阻止内置插入**

`SkipRuleDao.insertAll` 必须是 `OnConflictStrategy.IGNORE`。
用 `REPLACE` 会**重置用户停用状态**并**改变行 id**。

业务键 `SkipRuleKey` = `packageName + activityName + targetType + targetValue.lowercase()`，
**不含** `name` / `priority`（`UiMatcher` 匹配本身 `ignoreCase`）。

**3. CONTAINS 误点防护放「规则编写层」，**不要**放 `UiMatcher`**

曾尝试在 `matchesContains` 加 8 字符候选长度护栏，
破坏了既有测试 `given contains rule when node text contains target then matched`
（用 12 字正文断言应命中）→ **已完全撤回**。
理由：护栏放匹配器会破坏规则语义，规则页预览会与运行时不一致。

误点防护改由两层承担：
- **规则编写约束**（`BuiltinSkipRulesAssetTest` 断言）：
  CONTAINS 目标串 ≥ 4 字符；不含诱导性词汇
  （立即/领取/查看/下载/打开/安装/购买/下单/抽奖/红包/优惠/开通/授权/同意/允许）
- **`AntiMisclickGate` 冷却层**：规则 3s / 节点 5s / 全局 400ms

### 失败排查：`SkipReason` 对照表

| 值 | 含义 | 用户该做什么 |
|---|---|---|
| `APP_NOT_MANAGED` | 应用未纳管 | 去应用管理页勾选 |
| `NO_RULE_FOR_PACKAGE` | 该应用无规则 | 等规则库扩充或自建规则 |
| `ACTIVITY_MISMATCH` | 界面不匹配 | 规则 activity 限定过窄 |
| `IRRELEVANT_EVENT` | 事件类型无关 | 正常 |
| `NO_ROOT_NODE` / `EMPTY_NODE_TREE` | 取不到节点树 | ROM 限制 |
| `NO_NODE_MATCH` | 未命中任何节点 | 规则定位值需更新 |

日志形如 `事件跳过: <REASON>（<中文说明>）`（同值去重，不刷屏）。
设备 ROM 可能把应用日志置 Silent，此时用 DB / `adb exec-out` 观测。

### ⭐⭐ 匹配规则设计（R6，2026-09-19，写规则前必读）

> **第一轮修复让规则进了库，第二轮发现规则本身是错的。**
> `skip_rule = 20` 已入库，但 `intercept_log` 仍为 0 —— 卡在匹配。
> 真机抓包证明：规则的定位值是**凭常识推测**的，与真实节点不符。

**铁律：规则必须来自实证，不能来自常识。**

真机 B 站开屏节点（唯一可靠的证据形态）：
```
rid  = tv.danmaku.bili:id/count_down
text = "跳过 1"          ← 开屏按钮几乎都带倒计时
clickable = true
```

#### 三层规则结构

| 层 | 包名 | 定位方式 |
|---|---|---|
| **通用层** | `"*"`（`GLOBAL_RULE_PACKAGE`） | SDK viewId 后缀 + 文本前缀 |
| **专属 viewId** | 具体包名 | 精确 viewId / 后缀 |
| **专属文本兜底** | 具体包名 | `TEXT PREFIX` |

#### 定位方式选择

- **SDK 统一 id → `VIEW_ID` + 后缀 `*`**（收益最高，一条覆盖所有接入方）
  - 穿山甲 `*tt_splash_skip_btn`、快手 `*ksad_splash_circle_skip_view`
  - 按钮 id 的**包名前缀随宿主而异**（`com.byted.pangle:id/` 或
    `com.cainiao.wireless:id/`），后缀才稳定
- **带倒计时按钮 → `TEXT` + `PREFIX`**
  - `EXACT` 因文本动态化**必然落空**（这是曾经完全失效的直接原因）
  - `CONTAINS` 会命中正文
- **`DESCRIPTION` + `EXACT`/`CONTAINS` → 必须有 `activityName`**
  - `desc="关闭"` 这类通用词否则会乱点（测试强制）

#### 护栏常量（改动需同步测试）

| 常量 | 值 | 说明 |
|---|---|---|
| `MAX_PREFIX_CANDIDATE_LENGTH` | 10 | 匹配时候选串长度上限，对应 GKD `[text.length<10]` |
| `MAX_PREFIX_TARGET_LENGTH`（测试用） | 6 | 规则里 PREFIX 目标串上限 |
| `MIN_VIEW_ID_SUFFIX_LENGTH`（测试用） | 8 | 后缀过短易误命中 |

> ⚠️ **前缀匹配单独用并不安全**。曾以为"正文不会以『跳过』开头"，
> 但 `跳过此步可在设置中重新开启` 正是反例 —— 因此必须配长度上限。

#### 已明确排除的做法

1. `TEXT` + `EXACT` 匹配开屏文案（必然漏拦）
2. `关闭广告` 前缀规则（`关闭广告推送通知` 仅 8 字，仍假阳性）—— 已全删
3. 微信 `com.tencent.mm`（无开屏广告；朋友圈是信息流）
4. **凭推测填 `activityName`** —— 不可靠的限定比不限定危害更大（静默失效）

#### 🔴 通用规则的唯一安全契约

**通用规则同样受"应用必须被纳管"约束。**

`S1RuleCache.rulesForPackage` 曾经写成：
```kotlin
if (global.isEmpty()) return own ?: emptyList()
if (own.isNullOrEmpty()) return global   // ← 缺陷！
```
未纳管应用既不在 `byPackage`、又因此分支拿到通用规则 →
**在用户从未授权的应用上执行点击**。

**修正：先判 `packageName !in snapshot.managedPackages` 直接返回空。**
由 `S1RuleCacheTest` 钉死，改动此方法务必跑该测试类。

#### `S1RuleCache` 构造签名（为可测性做过重构）

主构造函数接受**两个 Flow**（`targetAppsFlow` / `enabledRulesFlow`），
次构造函数接受两个仓库。原因：`TargetAppRepository` 需要 `Context`，
项目测试栈无 Robolectric，原签名会让快照逻辑无法在 JVM 上验证。

#### 排障入口：`SkipDiagnostics`

部分 ROM 抑制应用日志（实测 NX789J 的 `log.tag.NoAdAccessibility` 为 Silent，
无 root 无法改），因此引擎判定结果写入**内存 `StateFlow`**，
在规则页顶部「匹配诊断」卡片展示。

- 用**内存而非 DB**：诊断非审计数据，不值得付一次 `Migration(2,3)`
- 用 `MutableStateFlow` 而非 `AtomicReference`：UI 需感知变化
- 字段名是 **`availableRuleCount`**（不是 `ruleCount`），
  展示模型 `DiagnosticsItem.ruleCount` 由它赋值

### 两套规则彼此独立（不要混淆）

| | 域名规则（S2/S3） | UI 跳过规则（S1） |
|---|---|---|
| 资产 | `assets/rules/builtin_domains.json` | `assets/rules/builtin_skip_rules.json` |
| 加载器 | `BuiltinRulesLoader` | `BuiltinSkipRulesLoader` |
| 表 | `domain_rule` | `skip_rule` |
| 来源列 | `source`（AD/ANALYTICS/…） | `source`（builtin/imported/user） |

## ⭐ R7 性能契约（2026-09-19，改动 `EventProcessor` / `NoAdDatabase` 前必读）

用户报告**「启动任何应用时存在一个半秒左右的明显延迟」**。
注意反馈性质：**不是拦截失效，而是拦截生效后引入的副作用**，
排查方向是「谁在主线程干了重活」。

### 主线程三个成本源

| # | 来源 | 量级 |
|---|---|---|
| 1 | `onAccessibilityEvent` **默认在主线程回调**，而 `ClickExecutor.execute` 含全树 BFS + `ACTION_CLICK` IPC，失败回落 `dispatchGesture`（约 40ms） | 50–300ms |
| 2 | 启动时内容变化事件数十次 × `UiTreeScanner.scan()`（500 节点 = 数百次 `getChild` IPC） | 累计数百 ms |
| 3 | `notificationTimeout` 事件合并 | 最多 100ms |

三者叠加正好是用户感知的「半秒」。

### 🔴 铁律：预筛只放宽、不收紧

遍历节点树前用事件自带文本做**无 IPC 预筛**（`EventPreFilter`）是主要优化手段，
但预筛**一旦比权威实现 `UiMatcher` 严格**，就产生
「事件被预筛丢弃、永远走不到匹配」的**静默漏拦** ——
**现象与「规则写错」完全一致，极难排查。**

规则（全部有测试守护，不要"优化"掉）：

- 规则集为空 / 事件无文本候选 → 放行（**信息不足绝不能替权威实现做否决**）
- **任何 `VIEW_ID` / `COORDINATE` 型规则 → 整体放行**。
  `AccessibilityEvent` **没有** `viewIdResourceName` 属性（那是
  `AccessibilityNodeInfo` 才有的），从事件侧根本无法判断 viewId 规则
- **任何 `REGEX` 型规则 → 整体放行**（正则 ≠ 纯字符串前缀匹配）
- **禁止自实现 activity 预筛** —— 纯字符串比较零 IPC，交给
  `filterByActivity` 即可；自己实现必然引入语义偏差

守护测试：`EventPreFilterTest.givenRealWorldSkipTexts_whenMatcherHits_thenPreFilterAlwaysPasses`。

> 这条铁律的代价真实发生过：我确实在 `EventPreFilter` 里自实现了 activity 预筛
> 且比权威实现更严格，**测试连挂两次才暴露**。放任不管的话，用户会看到
> 「某些应用又拦不住了」，而排查方向会错误地指向规则文件。

### 其它 R7 硬约束

- **点击必须投递后台**：`performAction` / `getChild` 是 IPC，
  **不受「必须主线程」约束**。投递到 `Dispatchers.Default`，
  返回 `ProcessOutcome.ClickScheduled`；无 scope 返回 `Deferred` 显式暴露。
- **`ClickScheduled` 不计入 `totalClicks`** —— 投递 ≠ 点击成功，统计口径不可乐观化。
- `EVENT_TIMEOUT_MS` 置 `0`（降频职责已交给预筛，把及时性换回来）。
- `NoAdDatabase.build()` 必须带 `.setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)`。
  默认 `AUTOMATIC` 在部分 OEM ROM 落到 TRUNCATE 模式，每次写事务重建 journal
  并 fsync；拦截日志恰是「启动时高频小写入」，正好叠在冷启动路径上。
- `PRE_FILTERED` 与 `NO_NODE_MATCH` **必须区分**：前者"连树都没读"，
  后者"读了树但没命中"。合并会让"预筛过严"伪装成"规则不对"。

### 耗时自检通道

`SkipDiagnosticsState`：`lastCostMs` / `maxCostMs` / `slowEventCount`；
常量 `FRAME_BUDGET_MS = 16L`、`SLOW_EVENT_THRESHOLD_MS = 100L`；派生 `hasFrameDrop`。
规则页诊断卡片显示「单次处理耗时：最近 Nms · 峰值 Mms」。
**排查启动卡顿先看这行数字**：长期高于 16ms 说明重活又回到主线程路径。

## ⭐ R8 无障碍生命周期的硬约束（2026-09-19，改动 `AccessibilityStateHolder` / `AccessibilityWatchdog` 前必读）

### 🔴 无障碍是「授权模型」，不存在「常驻」，也没有「保活」

用户曾要求"让无障碍权限常驻"。**这个前提是错的，必须纠正方向**：

用户在设置里勾选 → 系统写入 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`
→ 之后**由系统自行决定**何时连接/解绑。**没有任何 API 能让应用要求系统保持连接。**

官方 `AccessibilityServiceInfo` 对 flags 的原文：
> This field represents a set of flags used for **configuring** an AccessibilityService.

**关键推论：服务被解绑时，授权记录依然存在** —— 这正是它能自动重连的原因。
所以"断开"的正确应对是**自愈检测**，不是保活。

### 禁止的做法（不要重新"优化"回来）

| 方案 | 为什么禁止 |
|---|---|
| 前台服务 / `AlarmManager` / `JobScheduler` 给无障碍保活 | **完全无效**。影响的是进程优先级，而服务绑定由系统的无障碍管理器决定 |
| `adb` / Shizuku 写 `Settings.Secure` | 需用户每 12 小时重新授权调试；系统性写操作，影响面超出本应用 |
| 隐藏断开事实、只显示"已开启" | 违背 UI 诚实性原则（三字段分离的初衷就是"不掩盖断开"） |

### `AccessibilityState` 三态必须严格区分（写错会让用户白跑设置）

| 状态 | UI 文案 | 用户该做 |
|---|---|---|
| `isEffectivelyActive` | 运行中 | 什么都不做 |
| `isDisconnectedButAuthorized`（⭐ 可自愈） | 已授权，服务暂时断开 | **什么都不做** |
| `isNotAuthorized` | 未开启 | 去系统设置 |

`isDisconnectedButAuthorized = !serviceRunning && serviceEnabledInSettings`

### `AccessibilityWatchdog` 触发时机（进程级，Application.onCreate 同步启动）

`SCREEN_ON` / `USER_PRESENT` / `BOOT_COMPLETED` / `ACCESSIBILITY_STATE_CHANGED`(API 31+)
—— 都是系统广播，不受 Android 8+ 后台限制。

- **必须同步启动**，不能塞进异步初始化块（广播可能早于初始化完成）
- Android 14+ 必须传 `Context.RECEIVER_NOT_EXPORTED`，否则 `SecurityException`
- **刻意不注册 `SCREEN_OFF`**：息屏瞬间状态无意义
- 前台侧由 `HomeScreen` 的 `ON_RESUME` 覆盖（从设置页返回时屏幕没亮没解锁、广播不发）
  —— **两层缺一不可**

### 两条实现约束（都被测试捕获）

**① 首因优先**：`onUnbind` 与 `onDestroy` 因同一次断开先后触发，前者信息更具体。
```kotlin
disconnectReason = current.disconnectReason ?: reason   // ← 不是 reason ?: current
```
断开时刻同理只在**首次**写入，否则「已断开多久」永远显示「刚刚」。
唯一例外是 `markUserDisabledInSettings()`（授权记录消失 = 用户意图，比"系统解绑"更强）。

**② 时钟必须可注入**：直接用 `SystemClock.elapsedRealtime()` 会让状态机在 JVM 上
**完全无法测试**（`android.jar` 是空壳，报 `Method elapsedRealtime not mocked`）。
```kotlin
internal var clock: () -> Long = { SystemClock.elapsedRealtime() }
```
测试注入计数器 → 「已断开多久」的边界才可验证。

### 测试边界（必须诚实标注）

`refreshFromSystemSettings` 依赖 `ContentResolver`，**无 Robolectric** 时 JVM 拿不到。
提供 `internal fun seedSettingsFlagForTest(enabled: Boolean)` 验证**字段驱动的三态判定**；
**不覆盖**设置读取路径本身（Android 平台行为，由用户实机验证）。

## ⭐ R9 后台保活的硬约束（2026-09-19，改动 `core/service/keepalive/` 前必读）

### 两层失效勿混淆（R8 vs R9）

R8 管**无障碍连接层**（系统解绑→重连，授权模型内自愈）；
R9 管**进程存活层**（进程被杀→拉起，`core/service/keepalive/` 五件套）。
R8 文档「前台服务保活对无障碍无效」的结论**依然成立**——R9 保活
不是为了让无障碍绑定不断开，而是防进程死亡这种更彻底的失效。

### 三层恢复链与铁律

粘性重启（秒级，系统豁免）→ 心跳闹钟（≤15 分钟，**进程死亡不清除
已排定闹钟**）→ BOOT/更新广播（官方 FGS 豁免）。铁律：

1. **心跳失败也必须重排**（`TRY_START_AND_RESCHEDULE` 字面契约）——
   一次性闹钟不重排即断链。**不要"优化"成失败就取消**。
2. **用户明确 false 必须停止**（粘性重启唯一硬约束，防「关不掉」bug）；
   镜像 null = 乐观恢复（误恢复由观察者流毫秒级纠正）。
3. **接收器读 DataStore 权威值**，不读内存镜像（BOOT 路径镜像必为 null）。
4. **刻意不在 Service.onDestroy 取消闹钟**（心跳要比服务活得久）。
5. 心跳 PendingIntent 用**显式 Intent**，不进 manifest filter。
6. FGS 类型 `specialUse`（Android 15 BOOT 白名单内），通知渠道
   `IMPORTANCE_LOW`；13+ 通知权限拒绝**不阻断**保活。

### 双开关门控（改设置页前必读）

- 「后台保活」（默认 true）管被杀自恢复；「开机自启动」（默认 false）
  只管重启后恢复；BOOT 门控 = `keepAlive && autostart`；
  **MY_PACKAGE_REPLACED 只看保活开关**（更新是用户主动行为）。
- 自启开关副标题只能写「设备重启后恢复后台保活」，
  **不能写「恢复拦截」**（无障碍重启后由系统自动重连）。
- 电池优化豁免是官方 FGS 后台启动豁免项，入口在设置页
  （PowerManager 查询 + ON_RESUME 刷新，模式同 HomeScreen）。

### 诚实边界

**force-stop 后任何应用都无法自启**（Android 安全设计），
恢复链只覆盖系统回收场景。UI 与文档均不得夸大。

## 已验证的环境限制

- 沙箱内**无网络**，`pip install` 不可用 →
  需要图像处理时用纯 Python（标准库 `zlib` + `struct` 手写 PNG 编码），
  参考 `tools/gen_icon_pngs.py` 的实现
- Bash 工具每次调用前需 `export PATH="/usr/bin:/bin:/mingw64/bin:/c/Windows/System32:$PATH"`
  （否则报 `dirname: command not found`；错误只出现在 stderr，命令仍会执行）
- Python 建议用 `C:/Users/PlagueDoctor/.workbuddy/binaries/python/versions/3.13.12/python.exe`
  （有 `zlib`/`struct`，无 `PIL`）
- 校验 APK 内清单**不能**在二进制 `AndroidManifest.xml` 里搜字符串
  （AAPT2 会编码进资源池），应查看
  `app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml`

## 注意事项

- 项目**已是 Git 仓库**（有 `.git`，含 `0.1`、`first commit` 两次提交）
- `.trae/` 是 Trae 编辑器的目录，**WorkBuddy 不会自动加载**其中的规则；
  需 WorkBuddy 遵守的规范放在 `.workbuddy/skills/noad-dev-standards/SKILL.md`
