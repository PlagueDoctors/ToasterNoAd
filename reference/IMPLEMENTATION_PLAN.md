# NoAd 实现方案（初步）

> 目录：`reference/`（设计方案，非最终代码）
> 前置阅读：同目录 `android-ad-blocking-research.md`
> 编制日期：2026-09-19
> 状态：**初步方案，待评审**

---

## 1. 现状与目标

### 1.1 当前状态

NoAd 目前是**纯 Compose UI 骨架**：

- 4 个页面（首页 / 应用管理 / 拦截日志 / 设置）已完成视觉层
- 4 个 `ViewModel` 均在 `init {}` 中硬编码假数据
- **无** Service、**无** Repository、**无** 持久化、**无** 权限声明
- 技术栈：Kotlin 2.2.10 / Compose BOM 2026.02.01 / minSdk 30 / AGP 9.3.2

即：**UI 层可复用，数据与能力层需从零构建**。

### 1.2 目标能力

1. **自动跳过开屏广告** —— 应用启动时的全屏广告
2. **自动关闭弹窗广告** —— 应用内的浮层/对话框广告
3. **规则可配置** —— 用户可为特定应用开关拦截、编写自定义规则
4. **拦截日志** —— 记录拦截历史供用户查看
5. **完全离线** —— 不联网、不上传任何数据

### 1.3 非目标（明确排除）

- ❌ 网页广告过滤（属 DNS/VPN 方案范畴）
- ❌ 广告内容屏蔽或篡改
- ❌ 需要 Root 的任何能力
- ❌ 云端规则同步（初期）

---

## 2. 架构设计

### 2.1 分层结构

在现有 feature-first 结构上补齐数据层与服务层：

```
com.toaster.noad/
├── MainActivity.kt
├── NoAdApplication.kt                    # 【新增】全局初始化
├── core/
│   ├── common/                           # 【新增】工具、常量、日志封装
│   ├── model/                            # 【新增】领域模型
│   │   ├── SkipRule.kt
│   │   ├── InterceptLog.kt
│   │   └── TargetApp.kt
│   ├── data/                             # 【新增】Repository
│   │   ├── rule/RuleRepository.kt
│   │   ├── log/LogRepository.kt
│   │   └── settings/SettingsRepository.kt
│   ├── database/                         # 【新增】Room
│   │   ├── NoAdDatabase.kt
│   │   ├── entity/  dao/
│   │   └── converter/
│   ├── engine/                           # 【新增】规则引擎（核心复杂度隔离）
│   │   ├── RuleEngine.kt
│   │   ├── NodeMatcher.kt
│   │   └── ActionExecutor.kt
│   ├── service/                          # 【新增】无障碍服务
│   │   ├── NoAdAccessibilityService.kt
│   │   └── event/EventProcessor.kt
│   ├── applist/                          # 【新增】已安装应用枚举
│   │   └── InstalledAppDataSource.kt
│   ├── designsystem/                     # 已有
│   └── navigation/                       # 已有
├── feature/
│   ├── home/      # 已有，接真实数据
│   ├── apps/      # 已有，接真实应用列表
│   ├── logs/      # 已有，接真实日志
│   ├── settings/  # 已有，接 DataStore
│   └── rules/     # 【新增】规则管理页
└── ui/theme/                             # 已有
```

**设计取舍说明**：

- **`core/engine/` 独立** —— 借鉴 GKD 把选择器引擎独立成模块的做法。规则匹配是本项目最复杂的部分，且与 Android 框架耦合小、**可单元测试**，隔离后测试成本最低。
- **`core/service/` 只做事件接收与转发** —— `AccessibilityService` 回调在主线程，必须轻量。它只负责把事件交给 `EventProcessor`，真正的匹配逻辑在 `engine` 中同步快速执行或转交后台。
- **`applist/` 单独抽出** —— `PackageManager` 查询在 Android 11+ 受包可见性限制，需集中处理权限与降级逻辑。

### 2.2 数据流

```
系统 UI 事件
    ↓ onAccessibilityEvent (主线程，必须快)
NoAdAccessibilityService
    ↓ 包名/事件类型快速过滤（最先执行，挡掉 99% 事件）
EventProcessor
    ↓ 匹配规则（RuleEngine）
    ├── 命中 → ActionExecutor 执行点击
    │              ↓
    │         写入 LogRepository（异步）
    └── 未命中 → 直接返回
```

**关键性能原则**：
1. **最快失败优先** —— 先用 `packageName` 判断是否在白名单，不在立即 return
2. **事件类型收敛** —— 只订阅 `TYPE_WINDOW_STATE_CHANGED` + `TYPE_WINDOW_CONTENT_CHANGED`
3. **主线程零阻塞** —— 匹配逻辑纯内存操作；日志写库走 `withContext(Dispatchers.IO)`
4. **事件节流** —— 配置 `notificationTimeout`，避免高频回调

---

## 3. 核心模块设计

### 3.1 规则模型

借鉴 SKIP 的「packageName + activityName 双限定」与 GKD 的选择器表达力：

```kotlin
data class SkipRule(
    val id: Long = 0,
    val packageName: String,           // 必填：目标应用
    val activityName: String? = null,  // 可选：限定 Activity，开屏广告的关键
    val name: String,                  // 规则名，如「京东读书开屏」
    val enabled: Boolean = true,
    val priority: Int = 0,             // 多规则命中时的优先级
    val matchers: List<NodeMatcher>,
    val action: SkipAction = SkipAction.Click,
    val description: String? = null,   // 备注，供排查
)

sealed interface NodeMatcher {
    data class ById(val id: String) : NodeMatcher
    data class ByText(val text: String, val regex: Boolean = false) : NodeMatcher
    data class ByDesc(val desc: String, val regex: Boolean = false) : NodeMatcher
    data class ByBounds(val rect: Rect) : NodeMatcher      // 需附带分辨率
    data object ByClassName(val className: String) : NodeMatcher
}

sealed interface SkipAction {
    data object Click : SkipAction                          // 首选：节点点击
    data class Tap(val x: Int, val y: Int) : SkipAction     // 降级：坐标手势
    data object Back : SkipAction                           // performGlobalAction
}
```

**匹配优先级**（借鉴 TouchHelper 的降级思路）：

```
ById  >  ByText/ByDesc  >  ByClassName  >  ByBounds
```

精度高的先试，全失败才用坐标兜底。

### 3.2 执行器（含降级回退）

```kotlin
class ActionExecutor(private val service: AccessibilityService) {

    suspend fun execute(node: AccessibilityNodeInfo?, action: SkipAction): Boolean =
        when (action) {
            is SkipAction.Click -> clickWithFallback(node)
            is SkipAction.Tap -> tapByGesture(action.x, action.y)
            SkipAction.Back -> service.performGlobalAction(GLOBAL_ACTION_BACK)
        }

    private fun clickWithFallback(node: AccessibilityNodeInfo?): Boolean {
        node ?: return false
        // 1) 节点自身可点击
        if (node.isClickable && node.performAction(ACTION_CLICK)) return true
        // 2) 向上找可点击祖先（广告按钮常被容器包裹）
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable && parent.performAction(ACTION_CLICK)) return true
            parent = parent.parent
        }
        // 3) 降级：坐标手势点击节点中心
        val rect = Rect().also { node.getBoundsInScreen(it) }
        return tapByGesture(rect.centerX(), rect.centerY())
    }
}
```

> **「向上找可点击祖先」这一步很关键**：实践中大量广告的跳过 `TextView` 自身
> `clickable=false`，真正可点击的是它的父容器。只点节点本身会大量失败。

### 3.3 无障碍服务

```kotlin
class NoAdAccessibilityService : AccessibilityService() {

    private val processor by lazy { EventProcessor(...) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = TYPE_WINDOW_STATE_CHANGED or TYPE_WINDOW_CONTENT_CHANGED
            notificationTimeout = 100
            flags = flags or FLAG_REPORT_VIEW_IDS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (!processor.isTarget(pkg)) return          // 最快失败
        if (event.eventType != TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != TYPE_WINDOW_CONTENT_CHANGED) return

        // 同步快速匹配，命中则执行
        val hit = processor.tryMatch(event) ?: return
        processor.recordHit(hit)                       // 异步写库，不阻塞
    }

    override fun onInterrupt() = Unit
}
```

**注意**：`isTarget()` 必须走内存中的白名单 `Set`，不能查数据库。

### 3.4 应用列表枚举

Android 11（API 30）起有包可见性限制，`minSdk 30` 意味着必须处理：

**方案 A（推荐）**：声明 `QUERY_ALL_PACKAGES` 权限
**方案 B（合规友好）**：使用 `<queries>` 元素声明 intent 过滤器

> ⚠️ **Google Play 政策**：`QUERY_ALL_PACKAGES` 属于敏感权限，上架需说明必要性。
> 若最终目标是个人使用/开源，方案 A 更简单直接。

```kotlin
class InstalledAppDataSource(private val context: Context) {

    suspend fun loadLaunchableApps(): List<TargetApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .map { resolveToTargetApp(pm, it) }
            .distinctBy { it.packageName }
            .sortedBy { it.label }
            .toList()
    }
}
```

**关键点**：
- 必须过滤掉自身包名
- 图标加载需异步，且用 `LruCache` 缓存，否则列表滚动会卡顿
- 大列表（几百个应用）需分页或懒加载图标

### 3.5 持久化

**Room**（规则 + 日志）：

```kotlin
@Entity(tableName = "skip_rules")
data class SkipRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val activityName: String?,
    val name: String,
    val enabled: Boolean,
    val priority: Int,
    val matchersJson: String,        // 序列化存储，避免多表关联
    val actionJson: String,
)

@Entity(tableName = "intercept_logs", indices = [Index("timestamp")])
data class InterceptLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appLabel: String,
    val ruleName: String,
    val adType: String,
    val timestamp: Long,
)
```

**DataStore**（偏好）：拦截总开关、通知开关、自启动开关、深色主题。

**内置规则**：以 JSON 放 `src/main/assets/rules/builtin_rules.json`，首次启动导入数据库。

> 规则以 JSON 存 `assets` 而非硬编码在 Kotlin 中 —— 便于后续更新与社区贡献，
> 也符合 GKD「规则与代码分离」的思路。

---

## 4. 分阶段实施路线

按依赖顺序排列，每阶段可独立验证：

### 阶段一：数据基础（无 UI 依赖，可独立完成）

1. 引入 Room + DataStore 依赖（写入 `libs.versions.toml`）
2. 定义领域模型：`SkipRule`、`InterceptLog`、`TargetApp`
3. 建 Room 数据库、Entity、DAO
4. 实现 `RuleRepository`、`LogRepository`、`SettingsRepository` 接口与实现
5. **验证**：单元测试 Repository 的增删改查与映射

### 阶段二：规则引擎（纯逻辑，重点测试对象）

6. 实现 `NodeMatcher` 各匹配策略
7. 实现 `RuleEngine`：给定规则集 + 节点树 → 返回命中结果
8. 实现 `ActionExecutor` 的降级逻辑
9. **验证**：为核心匹配逻辑写单元测试（可脱离设备运行）

> 这一阶段产出**可完整单元测试**，是质量投入性价比最高的地方。

### 阶段三：无障碍服务接入

10. 写 `accessibility_service_config.xml`
11. 在 `AndroidManifest.xml` 声明服务与权限（含用途注释）
12. 实现 `NoAdAccessibilityService` + `EventProcessor`
13. 准备 5–10 条内置规则（选常见应用的开屏）
14. **验证**：真机开启服务，实测目标应用开屏是否被跳过

### 阶段四：UI 接线（复用现有界面）

15. `HomeViewModel` 接真实统计数据（今日拦截数、受保护应用数）
16. `AppsViewModel` 接 `InstalledAppDataSource`，替换硬编码列表
17. `AppsScreen` 补应用图标加载与缓存
18. `LogsViewModel` 接 `LogRepository`，日志倒序分页
19. `SettingsViewModel` 接 DataStore
20. `HomeScreen` 的「查看日志」按钮接入导航
21. **验证**：四个页面数据全部来自真实数据源

### 阶段五：服务状态与引导

22. 首页显示无障碍服务**真实开启状态**（`AccessibilityManager` 查询）
23. 服务未开启时，提供跳转系统设置的引导（`Settings.ACTION_ACCESSIBILITY_SETTINGS`）
24. **Android 13+ 侧载场景**需额外引导「允许受限设置」
25. 可选：前台服务保活 + 通知
26. **验证**：全新安装流程完整可走通

### 阶段六：规范化收尾

27. 清理规范中标注的 4 处待修正项（空 lambda、写死 fontSize 等）
28. 补齐 `strings.xml` 资源引用
29. 引入 Detekt + ktlint
30. 初始化 Git 仓库并提交

---

## 5. 关键技术风险

| 风险 | 影响 | 应对 |
|---|---|---|
| **Google Play 无法上架** | 高 | 定位为个人/开源工具，不以上架为目标 |
| **Android 13+ 侧载限制** | 高 | 必须提供「允许受限设置」的图文引导 |
| **各厂商 ROM 保活策略** | 中 | 引导用户加入后台白名单；可选前台服务 |
| **规则维护成本** | 中 | 支持导入导出；规则与代码分离 |
| **主线程卡顿** | 中 | 事件快速失败 + 异步写库 + 节流 |
| **误点风险** | 中 | 双限定作用域（包名+Activity）+ 高精度匹配优先 |
| **`QUERY_ALL_PACKAGES` 政策** | 中 | 备选 `<queries>` 方案 |
| **节点内存泄漏** | 低 | `AccessibilityNodeInfo` 需要 `recycle()` |

---

## 6. 需要你决策的问题

以下问题会影响实现方向，需要你确认：

### Q1. 分发方式
- **A. 仅个人使用** → 可直接用 `QUERY_ALL_PACKAGES`，无需考虑审核
- **B. 开源发布**（GitHub）→ 需注意 GKD 的 GPL 协议传染性，建议独立实现
- **C. 尝试上架商店** → 需重新设计权限策略，且成功率低

### Q2. 规则来源
- **A. 仅内置少量规则** → 实现简单，覆盖有限
- **B. 内置 + 支持导入本地 JSON** → 平衡
- **C. 内置 + 支持远程订阅链接** → 能力最强，但破坏「完全离线」定位

### Q3. 拦截范围
- **A. 仅开屏广告**（限定 `activityName`）→ 误点风险最低
- **B. 开屏 + 应用内弹窗** → 覆盖更全，需更谨慎的规则设计

### Q4. 保活策略
- **A. 仅依赖无障碍服务自身**（系统会保活）→ 最简
- **B. 增加前台服务 + 常驻通知** → 更稳，但用户可见通知

### Q5. 是否先初始化 Git
当前项目无版本控制。在开始阶段一之前初始化仓库，可以获得清晰的变更基线。

---

## 7. 附：最小可行实现（MVP）建议

若希望**尽快看到可用效果**，建议先做一条最短路径：

1. `AndroidManifest` 声明无障碍服务
2. 一个最小 `AccessibilityService`：监听 `TYPE_WINDOW_STATE_CHANGED`，遍历根节点找 `text contains "跳过"` 的节点 → 点击（含向上找可点击祖先）
3. 硬编码 3–5 个测试应用的白名单
4. 首页显示服务开关状态

**这样约 150 行代码即可验证核心链路是否可行**，再决定是否投入完整架构。

> 建议先做 MVP 验证，再按阶段一～六推进。原因是：
> 无障碍拦截的**真实成功率高度依赖具体机型与目标应用**，
> 在投入完整架构前用最小成本验证可行性，可以避免方向性浪费。
