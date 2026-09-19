---
name: noad-dev-standards
description: NoAd 项目（Android/Kotlin/Jetpack Compose）的开发规范约束。当在本项目中编写、修改、审查、重构 Kotlin 或 Compose 代码，新增/调整 Gradle 依赖与构建配置，编写单元测试或仪器测试，修改 AndroidManifest 与权限，新增页面或 ViewModel，或评估代码是否符合项目标准时，必须加载本技能并遵循其中的规则。触发场景包括但不限于：新增 Composable、写 ViewModel、加依赖、改 build.gradle.kts、加权限、写测试、代码评审、命名与分层问题、资源与主题使用、协程写法。只要涉及本项目代码产出，都应先加载本技能。
agent_created: true
---

# NoAd 项目开发规范

## 用途

本技能是 `NoAd` 项目的强制开发规范。在为本项目产出或审查代码前加载，
确保改动符合项目既定的技术栈、架构分层与编码约定。

规范的完整版本位于项目内 `docs/CODING_STANDARDS.md`（当前 316 行），
本技能是其可执行摘要。

**当需要逐条核对某条规则的精确措辞时，读取 `docs/CODING_STANDARDS.md`。**
**当需要判断"某处该不该这么写"时，优先查该文件，而不是凭通用 Android 经验推断。**

## 执行流程

1. 动代码前，先确认改动落在哪一层（`core/` 还是 `feature/`），是否需要新增目录。
2. 检查是否触及下方「项目锁定事实」中的任一项；触及则先向用户确认，不得擅自修改。
3. 按下方规则编写改动。
4. 完成后对照「提交前自查」清单逐条核对。
5. 若发现既有代码与规范不符，**如实指出，不要静默沿用错误写法**。

## 项目锁定事实（不得擅自修改）

| 项 | 锁定值 |
|---|---|
| 包名 / namespace | `com.toaster.noad` |
| 模块结构 | 单模块 `:app` |
| `minSdk` | 30 |
| `compileSdk` / `targetSdk` | 37 |
| Java 兼容版本 | `VERSION_11` |
| 构建 DSL | Gradle Kotlin DSL（`.kts`） |
| 版本管理 | Gradle Version Catalog（`gradle/libs.versions.toml`） |
| UI 框架 | Jetpack Compose + Material 3，**不引入 XML 布局** |
| 动态取色 | **不启用** Material You |

未经用户确认，**不得**升级 AGP / Kotlin / Compose BOM / Gradle 版本，
也**不得**修改 SDK 版本、依赖版本或引入新的架构框架。

## 一、Kotlin 编码规则

### 必须

- 优先 `val`；`data class` 字段全为 `val`；对外暴露 `List`/`Set`，不暴露 `MutableList`
- 禁止 `!!`；来自 Java 的平台类型必须显式声明可空性
- 禁止通配符导入（`import foo.*`）
- 禁止空 `catch`；禁止用异常做常规控制流
- 禁止 `GlobalScope`，统一用 `viewModelScope` / `lifecycleScope` / 注入的作用域
- 禁止 `println`，日志用带统一 TAG 的封装
- 单表达式函数用 `=` 形式
- 未被实现的功能**禁止伪装成已实现**；空 lambda 必须加注释标明未实现，或移除该入口

### 应当

- 缩进 4 空格，UTF-8，无 Tab；单文件 ≤400 行
- 函数参数 ≤5 个，超出抽参数对象
- 布尔型或多同类型参数，调用处使用具名参数
- 仅本特性内部使用的 Composable 标 `private`
- 注释解释「为什么」，不复述代码已表达的「做什么」

### 命名

| 元素 | 规则 | 示例 |
|---|---|---|
| 包 | 全小写，无下划线/连字符 | `com.toaster.noad.feature.home` |
| 类 / 接口 | `PascalCase` | `HomeViewModel` |
| 函数 / 属性 | `camelCase` | `toggleProtection` |
| 常量 | `UPPER_SNAKE_CASE` | `MAX_RETRY_COUNT` |
| 布尔 | 以 `is`/`has`/`can`/`should` 开头 | `isLoading` |
| 扩展函数文件 | `<接收者>Ext.kt` | `ContextExt.kt` |
| 测试类 | 被测类 + `Test` | `HomeViewModelTest` |
| 测试方法 | `givenX_whenY_thenZ` | `givenQueryBlank_whenSearch_thenReturnAll` |

## 二、项目结构（feature-first）

新增代码必须落在下列结构中：

```
com.toaster.noad/
├── MainActivity.kt              # 仅入口绑定
├── NoAdApplication.kt           # 需要全局初始化时创建
├── core/
│   ├── common/                  # 工具类、常量、扩展函数
│   ├── designsystem/            # 跨特性复用 UI 组件
│   ├── model/                   # 领域模型
│   ├── data/                    # Repository 与数据源
│   ├── database/                # Room 数据库与 DAO
│   ├── service/                 # 无障碍服务、前台服务
│   └── navigation/              # 导航图与目的地定义
├── feature/<feature>/
│   ├── <Feature>Screen.kt       # Composable 入口
│   ├── <Feature>ViewModel.kt
│   ├── <Feature>UiState.kt      # 状态与跨层模型
│   └── components/              # 仅本特性使用的子组件
└── ui/theme/                    # Color / Type / Theme
```

**禁止**按类型建目录（`activities/`、`fragments/`、`adapters/` 等）。

## 三、架构分层

### 必须

- 依赖单向：UI → 数据层。数据层**不得**反向引用 UI 类型
- 每个特性遵循「有状态入口 + 无状态内容」两段式：
  - `XxxRoute()` 持有 ViewModel、收集状态、向下传状态与回调
  - `XxxScreen(uiState, callbacks)` 为纯 Composable，`@Preview` 针对它编写
- UI 状态用**单一 `data class`** 承载，经 `StateFlow<UiState>` 暴露，
  界面用 `collectAsStateWithLifecycle()` 收集
- 副作用用 `LaunchedEffect` / `DisposableEffect` / `rememberCoroutineScope`，
  **不得**在 Composable 顶层直接改状态或发起副作用
- 数据访问必须经 Repository（接口 + 实现）才对 UI 可用
- 领域模型与持久化 Entity 分离；Entity **不得**暴露给 UI
- 偏好设置用 `DataStore`，不用 `SharedPreferences`

### 选项

- 注入对象少于 10 个时用手工构造 + `viewModelFactory`；
  超过约 10 个时再考虑 Hilt，且需先与用户确认

## 四、Composable 与 UI

### 必须

- Composable 名称用 `PascalCase`
- 参数顺序：必填 → `modifier: Modifier = Modifier` → 可选 → 尾随 lambda
- 每个可复用 Composable 配 `@Preview`
- `Modifier` 链按「外部到内部」书写：`fillMaxSize().padding(...)`
- 列表用 `LazyColumn`/`LazyRow` 并显式提供 `key`
- 所有用户可见文案走 `stringResource(R.string.xxx)`，**禁止硬编码中文字面量**
- 颜色用 `MaterialTheme.colorScheme.*`，**禁止**写死 `Color(0xFF...)`
- 字号遵循 `Type.kt` 的 `Typography`，**禁止**局部写死 `fontSize`
- 深浅色两套色板各自独立定义语义色，**不得**让一个色值同时服务两套方案

### 应当

- Composable 嵌套 ≤4 层，超出抽取子组件
- `@Preview` 尽量覆盖深浅两套主题

## 五、清单与权限

### 必须

- 权限按最小必要声明，每条附注释说明用途（隐私合规要求）
- 组件默认不导出；确需导出时显式写 `android:exported`
- 引入无障碍服务 / 前台服务时，声明 `<service>` 与 `BIND_ACCESSIBILITY_SERVICE`
  元数据、`foregroundServiceType`
- 需要读取应用列表时，按 targetSdk 要求处理：
  `QUERY_ALL_PACKAGES`、`PACKAGE_USAGE_STATS`、`FOREGROUND_SERVICE`、
  `POST_NOTIFICATIONS`（Android 13+ 需运行时请求）
- **禁止**声明未使用的组件或权限
- 优先单 `Activity` + Compose 导航，新增 `Activity` 前先确认是否真的需要

## 六、依赖与构建

### 必须

- 依赖与版本统一写入 `gradle/libs.versions.toml`，模块内通过 `libs.*` 引用；
  **禁止**在 `build.gradle.kts` 中写死版本字符串
- 依赖范围正确：`implementation` / `testImplementation` /
  `androidTestImplementation` / `debugImplementation`
- 新增构建逻辑必须与配置缓存兼容
  （项目已启用 `org.gradle.configuration-cache=true`）

### 注意

`release` 构建当前 `optimization.enable = false`，即未开启 R8 优化与混淆。
修改此项需先与用户确认，并同步补充 `app/src/main/keepRules/rules.keep`。

## 七、测试

### 必须

- 单元测试放 `src/test/`；仪器测试放 `src/androidTest/`
- 优先测**行为与契约**（公开行为、边界、失败路径），而非实现细节
- **禁止**无断言的测试；**禁止**靠删除或忽略测试来过门禁
- 测试必须确定性，不依赖真实时间、网络或设备状态

### 优先测试目标

`ViewModel` 状态变更逻辑、Repository 数据映射、纯函数工具。

### ⚠️ 资产文件必须有「契约测试」

纯解析器的测试**不能**发现文件本身写错。凡是 `assets/` 下的规则/配置数据，
必须额外写一个**直接读真实文件**的测试类（`*AssetTest`），断言：

- 数据格式（图案归一化、无重复、分类合法、每条有 `note`）
- **语义约束**（例如 CONTAINS 目标串长度下限、不含诱导性词汇、无坐标规则）
- **零假阳性**：对一份"正常界面"样本逐条断言不命中

`BuiltinRulesAssetTest`（域名）与 `BuiltinSkipRulesAssetTest`（UI 跳过）
都遵循这个模式。这是防止"测试全绿但错误数据直接进用户设备"的唯一手段。

### ⭐ 测试是发现设计缺陷的工具，不只是防回归

R6 一轮修复中，测试捕获了 **1 个安全漏洞 + 3 个设计缺陷**，
全部**不是**由实机发现的：

| 缺陷 | 捕获方式 |
|---|---|
| 通用规则泄漏到未纳管应用（越权点击） | `S1RuleCacheTest` 断言未纳管返回空 |
| 前缀匹配不足以防正文 | 反例 `跳过此步可在设置中重新开启` 进了测试树 |
| `关闭广告` 前缀仍假阳性 | `关闭广告推送通知` 未超长度上限 |
| `desc="关闭"` 缺 activity 限定 | `*AssetTest` 强制约束 |

**因此不要为了"让测试通过"而放宽断言** —— 上面每条断言放宽后，
对应的真实缺陷都会直接进用户设备。

写测试时请刻意构造**反例**：不只测"应该命中"，更要测"不应该命中"。

### ⚠️ 交付验证标准（用户明确界定）

**用户自己执行实机测试，不要求我运行仪器测试或产出完整 APK。**

- **必须**：`./gradlew testDebugUnitTest` 全绿
- **必须**：编译 0 error **0 warning**（项目基线是零告警，弃用 API 也要处理）
- **不要**：运行 `connectedDebugAndroidTest`
- **不要**：把 `assembleDebug` 当作交付条件

`src/androidTest/` 下的测试（如 `MigrationTest`）照常编写，但由用户执行。

新增测试如需要 mock，**不要引入 mockk / mockito** —— 项目测试栈只有
JUnit4 + `kotlinx-coroutines-test` + `org.json` + `room-testing`。
用**手写假实现**（如内存 `FakeSkipRuleDao`）。

## 八、质量工具与版本控制

### 应当

- 引入 Detekt + ktlint 以自动校验本规范（需先与用户确认）
- Android Lint 新增告警**不得**靠扩大 baseline 或全局 suppress 消除；
  确需抑制时必须就地注明原因
- 覆盖率报告（Kover / JaCoCo）作为反馈信号，
  **不得**当作测试有效性的替代品

### 必须

- 门禁 / baseline / 过滤策略的引入或修改，必须先经用户确认
- 提交信息格式：`<type>(<scope>): <描述>`，
  `type` ∈ `feat` / `fix` / `refactor` / `test` / `build` / `docs` / `style` / `chore`
- **禁止**提交 `local.properties`、`.gradle/`、`build/`、`.idea/` 中的本地配置

## 九、内置域名规则（assets JSON）

规则**不是** Kotlin 常量，位于 `app/src/main/assets/rules/builtin_domains.json`，
由 `core/data/rules/BuiltinRulesLoader.kt` 解析。

### 必须

- 修改规则文件后**必须**运行 `./gradlew testDebugUnitTest`；
  `BuiltinRulesAssetTest` 会对真实文件做契约测试
- 每条规则**必须**有 `note` 说明用途（否则无法审计与移除）
- 图案必须已归一化：小写、无尾点、无协议前缀、无通配符、无路径/端口
- **白名单必须与黑名单同批导入**。白名单不是"例外列表"，
  而是「因误杀风险而显式放行」的登记处；
  只导入黑名单会让这层防护静默失效
- 引入新域名前必须评估：该域名是否同时承载业务接口、登录、支付或内容分发？
  是则**不得**加入黑名单，或必须以白名单形式登记权衡

### 禁止

- 收录「全家桶」主域（如 `google.com`、`qq.com`、`alibaba.com` 本身）
- 为追求拦截率而扩大规模 —— 内置集合的价值是「零误杀覆盖高共识广告域」

## 十、应用图标

图标为**完全原创矢量**，零许可风险。相关文件：
`res/drawable/ic_launcher_{background,foreground,monochrome}.xml`、
`res/mipmap-anydpi/ic_launcher{,_round}.xml`、`res/mipmap-*dpi/ic_launcher{,_round}.png`。

### 必须

- **矢量与位图必须同步**：几何参数同时存在于 `ic_launcher_foreground.xml`
  与 `tools/gen_icon_pngs.py`。改其一必须同步另一，否则两种图标形态不一致
- `ic_launcher_monochrome.xml` 必须与 `ic_launcher_foreground.xml` **完全一致**
  （刻意不做"单色专属优化"，避免不一致）
- 添加图形时**只靠不透明度**传递信息，不依赖颜色差异（Android 13+ 主题化图标
  会取 alpha 形状重新着色）；缺口/镂空必须靠「背景透出」而非「填背景色」
- 中空图形用「外轮廓 + 内轮廓 + `fillType="evenOdd"`」表达，
  **不用 `android:strokeWidth`**（部分启动器生成单色图标时会丢弃 stroke）
- 改动后必须重新运行 `tools/gen_icon_pngs.py` 生成位图

### 禁止

- 恢复 `mipmap-*dpi/*.webp`（Android Studio 模板的绿色机器人图标）
- 直接引用第三方图标库的路径数据（Material Symbols / Lucide / Phosphor 均可商用
  但都需保留版权声明，图标常被拆出去单独使用，署名成本不划算）

## 十一、提交前自查

- [ ] 无 `!!`、无空 `catch`、无 `GlobalScope`、无 `println`
- [ ] 变量优先 `val`，集合对外只读
- [ ] 文案来自 `strings.xml`，颜色来自 `MaterialTheme.colorScheme`
- [ ] Composable 参数顺序正确，`Modifier` 链方向正确
- [ ] 仅内部使用的 Composable 标 `private`，列表有 `key`
- [ ] 可复用 Composable 有 `@Preview`
- [ ] 无硬编码版本号，依赖写在 `libs.versions.toml`
- [ ] 新增测试有真实断言且按 `givenX_whenY_thenZ` 命名
- [ ] 未实现的功能未被伪装成已实现
- [ ] 未擅自修改锁定的 SDK / 依赖 / 框架版本
- [ ] **编译 0 warning**（弃用 API 要处理，不能留 `^w:`）
- [ ] **改动 `assets/` 下数据后已跑 `*AssetTest`**
- [ ] **新增链路已确认「有入口」** —— 不只是零件正确，
      而是数据真的能进 DB、能被读到、能被消费
- [ ] **改表结构已补 `Migration(n, n+1)` + 迁移测试**，
      未使用 `fallbackToDestructiveMigration()`
- [ ] **枚举存库用显式小写字符串**（`persistedName`），未用 `enum.name`
- [ ] **`EventProcessor` 事件回调内无同步重活** —— 点击已投递后台，
      节点遍历前有廉价预筛；新增预筛条件时必须满足「只放宽不收紧」
- [ ] **未给无障碍服务加任何"保活"**（前台服务 / JobScheduler / AlarmManager 均无效）；
      涉及服务断开的改动已确认三态区分正确、且时钟可注入

## 十二、S1 无障碍（阶段 B：代码完成，待实机验证）

### ⚠️ 头号教训：零件全绿 ≠ 链路可用

阶段 B 曾出现**致命缺陷**：`EventProcessor` / `S1RuleCache` / `UiMatcher` /
`ClickExecutor` / `AntiMisclickGate` 全部实现正确且有单测覆盖，
但它们构成**一条没有入口的流水线** ——
`RuleRepository.add/addAll` 全项目零调用点、`assets/` 下无 S1 规则文件、
`feature/` 下无规则页。

结果：`skip_rule` 表恒空 → `S1RuleCache` 返回 `EMPTY` → 100% 事件被第 1 道闸丢弃
→ 用户报告「能开无障碍、能勾选应用，但**不跳过、无日志、统计恒 0**」。

**因此新增或修改这条链路时，必须验证「规则能进 DB 并被读到」，
而不只是验证「匹配逻辑正确」。**

### 第二道教训：规则正确 ≠ 规则写得对

链路修通、规则入库后（`skip_rule = 20`），`intercept_log` **仍为 0**。
真机抓包发现：规则的定位值**是凭常识推测的**，与真实节点完全不同 ——

```
真机 B 站开屏节点：
  rid  = tv.danmaku.bili:id/count_down
  text = "跳过 1"          ← 开屏按钮几乎都带倒计时
```

而当时写的三条 B 站规则全部落空：
`id/skip`（该 id 不存在）、`EXACT "跳过广告"`、`EXACT "跳过"`
（实际文本是「跳过 1」）。

**铁律：规则必须来自实证，不能来自常识。**
"某应用大概有个叫 skip 的按钮"这类推断，**一次都不该出现在规则文件里**。

### ⚠️ 第三条教训：性能修复的头号风险是「静默漏拦」（R7）

用户报告**「启动任何应用时存在一个半秒左右的明显延迟」** ——
注意这条反馈的性质：**不是拦截失效，而是拦截生效后引入的副作用**。
排查方向与前面两条完全不同：要找「谁在主线程干了重活」。

#### 主线程路径的三个成本源（改 `EventProcessor` 必读）

`onAccessibilityEvent` **默认在主线程回调**，任何同步重活都直接吃掉帧预算。

| 成本源 | 机制 | 量级 |
|---|---|---|
| 点击同步执行 | `ClickExecutor.execute` 含 `findByIndex` 全树 BFS + `ACTION_CLICK` IPC，失败回落 `dispatchGesture`（手势播放约 40ms） | 50–300ms |
| 每个事件遍历整树 | 启动时内容变化事件数十次 × `UiTreeScanner.scan()`，500 节点 = 数百次 `getChild` IPC | 累计数百 ms |
| `notificationTimeout` | 事件被系统合并 | 最多 100ms |

#### ⭐ 核心铁律：预筛只放宽、不收紧

在遍历节点树前用事件自带文本做一次**无 IPC**的廉价预筛是主要优化手段，
但预筛**一旦比权威实现 `UiMatcher` 严格**，就会产生
「事件被预筛丢弃、永远走不到匹配」的**静默漏拦** ——
**现象与「规则写错」完全一致，极难排查。**

具体规则（全部有测试守护，不要"优化"掉）：

- 规则集为空 → 放行
- **任何 `VIEW_ID` / `COORDINATE` 型规则 → 整体放行**。
  `AccessibilityEvent` **没有** `viewIdResourceName` 属性
  （那是 `AccessibilityNodeInfo` 才有的），从事件侧根本无法判断 viewId 规则是否命中
- **任何 `REGEX` 型规则 → 整体放行**（正则 ≠ 纯字符串前缀匹配）
- 事件无任何文本候选 → 放行（**信息不足时绝不能替权威实现做否决**）
- **禁止自实现 activity 预筛** —— 它是纯字符串比较、零 IPC 成本，
  交给权威实现 `filterByActivity` 即可；自己实现一遍必然引入语义偏差

守护测试：`EventPreFilterTest.givenRealWorldSkipTexts_whenMatcherHits_thenPreFilterAlwaysPasses`
枚举真实文案，逐条断言「`UiMatcher` 命中 ⇒ 预筛必然放行」。

> 这条铁律的代价是真实发生过的：我确实在 `EventPreFilter` 里自实现了 activity
> 预筛且比权威实现更严格，**测试连挂两次才暴露**。
> 放任不管的话，用户会看到「某些应用又拦不住了」，
> 而排查方向会错误地指向规则文件。

#### 点击必须投递到后台线程

`performAction` / `getChild` **是 IPC 调用，不受「必须主线程」约束**。

```kotlin
val scope = handoffScope ?: return ProcessOutcome.Deferred(...)
scope.launch(handoffContext) { performClick(...) }   // Dispatchers.Default
return ProcessOutcome.ClickScheduled(...)
```

- 无 scope 时返回 `Deferred` **显式暴露**，不要静默丢弃
- **`ClickScheduled` 不计入 `totalClicks`** —— 投递 ≠ 点击成功，
  统计口径不能被乐观化
- 降频职责交给预筛后，`EVENT_TIMEOUT_MS` 应置 `0`（把及时性换回来）

#### 耗时诊断（用户实机自检通道）

`SkipDiagnosticsState`：`lastCostMs` / `maxCostMs` / `slowEventCount`，
常量 `FRAME_BUDGET_MS = 16L`、`SLOW_EVENT_THRESHOLD_MS = 100L`，派生 `hasFrameDrop`。
规则页诊断卡片显示「单次处理耗时：最近 Nms · 峰值 Mms」。

**排查启动卡顿时，先看这行数字**：长期高于 16ms 即说明重活又回到了主线程路径。

#### 顺手项：Room 启用 WAL

`NoAdDatabase.build()` 必须带 `.setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)`。
默认 `AUTOMATIC` 在部分 OEM ROM 上落到 TRUNCATE 模式，每次写事务重建 journal
并 fsync；拦截日志恰好是「启动时高频小写入」，这笔开销正叠在冷启动路径上。

### ⚠️ 第四条教训：无障碍是「授权模型」，不存在「保活」（R8）

用户报告**"无障碍权限在息屏/切换应用后系统自动又关闭了，想办法让它常驻"**。

**这个请求的前提是错的，必须先纠正**：

无障碍**不存在"常驻"这个状态**，因此没有"让它常驻"的实现方式。
用户在设置里勾选后，系统把这一条写进
`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`，之后**由系统自行决定**
何时连接、何时解绑。**没有任何 API 能让应用要求系统"保持连接"。**

关键推论：**服务被解绑时，设置里的授权记录依然存在** ——
这正是它能被系统自动重连的原因。

| 用户感知 | 实际状态 | 结论 |
|---|---|---|
| "权限被自动关了" | 授权还在，只是服务实例被解绑 | **不需要重新授权** |
| 真正要解决的 | 断开未被及时发现与呈现 | 做**自愈检测**，不是保活 |

#### 🔴 禁止的做法（附理由，不要重新"优化"回来）

| 方案 | 为什么禁止 |
|---|---|
| 前台服务 / `AlarmManager` / `JobScheduler` 给无障碍保活 | **完全无效**。它们影响进程优先级，而服务绑定由系统的无障碍管理器决定。纯粹白耗电 |
| `adb` / Shizuku 写 `Settings.Secure` | 需用户每 12 小时重新授权调试；属系统性写操作，影响面超出本应用 |
| 隐藏断开事实、只显示"已开启" | 违背 UI 诚实性原则（三字段分离的初衷就是"不掩盖断开"） |

#### 正确的做法：自愈检测 + 如实呈现

**`AccessibilityState` 的三态必须严格区分**（写错会让用户白跑设置）：

```kotlin
// ⭐ 已授权但当前未连接 —— 最需要自愈的一态
val isDisconnectedButAuthorized: Boolean
    get() = !serviceRunning && serviceEnabledInSettings
```

| 状态 | UI 文案 | 用户该做 |
|---|---|---|
| `isEffectivelyActive` | 运行中 | 什么都不做 |
| `isDisconnectedButAuthorized` | 已授权，服务暂时断开 | **什么都不做**（等自愈） |
| `isNotAuthorized` | 未开启 | 去系统设置 |

`AccessibilityWatchdog`（进程级，`Application.onCreate` 同步启动）监听：

- `ACTION_SCREEN_ON` / `ACTION_USER_PRESENT` / `ACTION_BOOT_COMPLETED`
- `ACCESSIBILITY_STATE_CHANGED`（API 31+）

**刻意不注册** `ACTION_SCREEN_OFF`：息屏瞬间状态无意义。
`start()` 只做注册，核对在协程里（不拖慢冷启动）。
Android 14+ 必须传 `RECEIVER_NOT_EXPORTED`，否则抛 `SecurityException`。

前台侧由 `HomeScreen` 的 `ON_RESUME` 覆盖（从设置页返回时屏幕既没亮也没解锁，
广播不会发）。**两层缺一不可。**

#### 两条实现约束（都被测试捕获过）

**① 首因优先**：`onUnbind` 与 `onDestroy` 会因同一次断开先后触发，
前者信息更具体。因此**不接受覆盖**：

```kotlin
disconnectReason = current.disconnectReason ?: reason   // ← 不是 reason ?: current
```

写成 `reason ?: current` 等价于"有值就覆盖"，`onDestroy` 会盖掉 `onUnbind`。
断开时刻同理只在**首次**写入，否则「已断开多久」永远显示「刚刚」。

**② 时钟必须可注入**：直接用 `SystemClock.elapsedRealtime()` 会让
**整个状态机在 JVM 上无法测试**（`android.jar` 是空壳，方法体全是 `throw`，
报 `Method elapsedRealtime not mocked`）。而状态机决定了 UI 说"去设置"还是
"等一等"，是最不能靠猜的部分：

```kotlin
internal var clock: () -> Long = { SystemClock.elapsedRealtime() }
```

测试注入计数器后，「已断开多久」的边界（刚断开 / 59 秒 / 1 分 / 59 分 / 1 小时）
才可验证。

#### 关于「设置读取」的诚实边界

`refreshFromSystemSettings` 依赖 `ContentResolver`，项目**无 Robolectric**，
JVM 上拿不到。因此提供 `internal fun seedSettingsFlagForTest(enabled: Boolean)`，
用于验证**由该字段驱动的三态判定**。

**不覆盖**设置读取路径本身——那是 Android 平台行为，由用户实机验证。
这个边界必须在测试类 KDoc 里如实标注，**不假装覆盖了 `Settings.Secure` 读取**。

### 完整规则链路（改动前先读）

```
assets/rules/builtin_skip_rules.json   (15 应用组 / 21 条 / version 2，含通用层 "*")
  → BuiltinSkipRulesLoader.load(context)   逐条容错，坏条目只跳过自己
  → RuleRepository.mergeBuiltin(rules)     跨来源去重 / 只增不改不删
  → skip_rule 表 (source = 'builtin')
  → RuleRepository.observeEnabledRules() → S1RuleCache（AtomicReference 内存快照）
        ↑ 通用规则单独存放，查询时附加到已纳管应用
  → EventProcessor 四道闸 → UiMatcher → ClickExecutor → SkipDiagnostics
```

启动入口为 `NoAdApplication.initializeSkipRules()`，仅在
`RuleRepository.needsBuiltinImport()` 为 true 时导入。

### ⭐ 匹配规则设计（写规则前必读）

#### 三层结构

| 层 | 包名 | 定位方式 |
|---|---|---|
| **通用层** | `"*"`（`GLOBAL_RULE_PACKAGE`） | SDK viewId 后缀 + 文本前缀 |
| **专属 viewId** | 具体包名 | 精确 viewId / 后缀 |
| **专属文本兜底** | 具体包名 | `TEXT PREFIX` |

#### 定位方式选择（有明确判据，不要凭感觉）

- **SDK 统一 id → `VIEW_ID` + 后缀 `*`**（收益最高）
  - 穿山甲 `*tt_splash_skip_btn`、快手 `*ksad_splash_circle_skip_view`
  - 按钮 id 的**包名前缀随宿主而异**（`com.byted.pangle:id/` 或
    `com.cainiao.wireless:id/`），只有后缀稳定
- **带倒计时按钮 → `TEXT` + `MatchMode.PREFIX`**
  - `EXACT` 因文本动态化**必然落空** —— 这是 S1 曾完全失效的直接原因
  - `CONTAINS` 会命中正文
- **`DESCRIPTION` + `EXACT`/`CONTAINS` → 必须同时给 `activityName`**
  - `desc="关闭"` / `desc="返回"` 这类通用词否则会乱点

#### 护栏常量（改动需同步测试）

| 常量 | 值 | 位置 |
|---|---|---|
| `MAX_PREFIX_CANDIDATE_LENGTH` | 10 | `UiMatcher`，对应 GKD `[text.length<10]` |
| `MAX_PREFIX_TARGET_LENGTH` | 6 | `BuiltinSkipRulesAssetTest` 断言规则 |
| `MIN_VIEW_ID_SUFFIX_LENGTH` | 8 | `BuiltinSkipRulesAssetTest` 断言规则 |

> ⚠️ **前缀匹配单独用并不安全**。曾以为"正文不会以『跳过』开头"，
> 但 `跳过此步可在设置中重新开启` **正是反例** —— 因此必须配长度上限。
> 这条推理错误已被写成测试用例，不要重新"优化"掉长度上限。

#### 禁止的规则写法

1. `TEXT` + `EXACT` 匹配开屏文案（文本本质动态，必然漏拦）
2. `关闭广告` 前缀规则 —— `关闭广告推送通知` 仅 8 字，前缀+长度两重
   约束仍不足以防假阳性，**已全部删除**
3. 微信 `com.tencent.mm` —— 无开屏广告；朋友圈是信息流广告；
   `activityName` 不可靠。收录它只有风险
4. **凭推测填 `activityName`** —— 不可靠的限定比不限定危害更大
   （限定错了是**静默失效**，而不限定至少还能匹配）

#### 🔴 通用规则的安全契约（改动 `S1RuleCache` 必读）

**通用规则同样受"应用必须被纳管"约束。**

`rulesForPackage` 曾经写成：
```kotlin
if (global.isEmpty()) return own ?: emptyList()
if (own.isNullOrEmpty()) return global   // ← 缺陷！
```
未纳管应用既不在 `byPackage`（`own` 为 null），又因通用规则存在
而入围第二个分支 → **拿到了本不该生效的通用规则** →
**在用户从未授权的应用上执行点击**。

**修正：函数开头先判 `packageName !in snapshot.managedPackages` 返回空。**
由 `S1RuleCacheTest` 钉死，改动此方法务必跑该测试类。

#### `S1RuleCache` 的构造签名（为可测性做过重构）

主构造函数接受**两个 Flow**（`targetAppsFlow` / `enabledRulesFlow`），
次构造函数接受两个仓库。

不要"简化"回只接受仓库：`TargetAppRepository` 需要 `Context`，
而项目测试栈**无 Robolectric**，原签名会让快照构建逻辑
（包含上面那条核心安全契约）**永远无法在 JVM 上验证**。

#### 排障入口：`SkipDiagnostics`（不是可选功能）

部分 ROM 抑制应用日志（实测 NX789J 的 `log.tag.NoAdAccessibility`
被设为 Silent，无 root 无法更改）。因此引擎判定结果写入**内存 `StateFlow`**，
在规则页顶部「匹配诊断」卡片展示。

- 用**内存而非 DB**：诊断非审计数据，不值得付一次 `Migration(2,3)`
- 用 `MutableStateFlow` 而非 `AtomicReference`：UI 需感知变化
- 字段名是 **`availableRuleCount`**；展示模型 `DiagnosticsItem.ruleCount`
  由它赋值（曾误写为 `ruleCount` 导致编译失败）
- `lastReason` 与 `lastOutcome` **互斥**，UI 靠此不变式二选一

### 失败静默治理

`EventProcessor` 的每道闸门返回带原因的 `ProcessOutcome.Ignored(SkipReason)`，
日志形如 `事件跳过: <REASON>（<中文说明>）`（`recordSkipReason` 做同值去重防刷屏）。
`process()` 外层包装统一调用 `SkipDiagnostics.record(...)`，
**任何返回路径都会被覆盖**（不要在各 return 点单独记录，漏一条就查不出原因）。

| `SkipReason` | 含义 | 用户该做什么 |
|---|---|---|
| `APP_NOT_MANAGED` | 应用未纳管 | 去应用管理页勾选 |
| `NO_RULE_FOR_PACKAGE` | 该应用无规则 | 等规则库扩充或自建规则 |
| `ACTIVITY_MISMATCH` | 界面不匹配 | 规则 activity 限定过窄 |
| `IRRELEVANT_EVENT` | 事件类型无关 | 正常 |
| `PRE_FILTERED` | 事件文本与所有规则都不沾边，未遍历节点 | 正常降频；疑似漏拦时才关注 |
| `NO_ROOT_NODE` / `EMPTY_NODE_TREE` | 取不到节点树 | ROM 限制 |
| `NO_NODE_MATCH` | 未命中任何节点 | 规则定位值需更新 |

> `PRE_FILTERED` 与 `NO_NODE_MATCH` **必须区分**：前者是"连树都没读"，
> 后者是"读了树但没命中"。合并成一个原因会让"预筛过严"这一故障
> 伪装成"规则不对"，正是 R7 要防的那类误判。

**新增闸门时必须给出 `SkipReason`**，不得返回无原因的忽略 ——
否则用户只能看到"没反应"，无法定位卡在哪一环。

### 规则来源的编码约定（极易出错）

**`skip_rule.source` 必须存 `SkipRuleSource.persistedName`（小写字符串），
绝不能用 `enum.name`。**

- `SkipRuleEntity.SOURCE_*` 与 `Migrations` 的 `DEFAULT 'user'` 都是**小写**
- 用 `enum.name` 会写入 `"BUILTIN"`/`"USER"`，导致 `WHERE source = 'user'`
  **查不到新数据**，现象是「规则莫名消失」
- enum 构造参数**不能引用 companion 常量**（`Companion object of enum class
  ... is uninitialized here`），必须写字面量：`BUILTIN("builtin")`
- `fromName()` 的未知值**退回 `USER`**（最需要保护的一类）

### `mergeBuiltin` 的四条契约（均有单测锁定）

1. **幂等** — 重复启动/重复导入不产生重复项
2. **只增不删** — 不删除任何存量规则
3. **不改** — 用户对规则的改名 / 停用 / 删除不被覆盖
4. **跨来源去重** — `allExistingKeys()` 查 `dao.loadAll()` 全表；
   用户已建同定位值规则时**阻止内置插入**

`SkipRuleDao.insertAll` 必须是 `OnConflictStrategy.IGNORE`。
用 `REPLACE` 会**重置用户停用状态**并**改变行 id**。

业务键 = `packageName + activityName + targetType + targetValue.lowercase()`，
**不含** `name` / `priority`。

`Migrations` 的 `DEFAULT 'user'` 不可改成 `'builtin'` ——
v1 没有任何内置导入路径，存量行必然是用户手工产生的，
默认 `'builtin'` 会让它们在首次导入的替换步骤中被删除。

### ⚠️ CONTAINS 误点防护放「规则编写层」，不要放 `UiMatcher`

曾尝试在 `UiMatcher.matchesContains` 加 8 字符候选长度护栏，
破坏了既有测试（用 12 字正文断言 CONTAINS 应命中）→ **已完全撤回**。
理由：护栏放匹配器会破坏规则语义，**规则页预览会与运行时不一致**。

误点防护改由两层承担：

- **规则编写约束**（`BuiltinSkipRulesAssetTest` 断言）：
  CONTAINS 目标串 ≥ 4 字符；不含诱导性词汇
  （立即 / 领取 / 查看 / 下载 / 打开 / 安装 / 购买 / 下单 / 抽奖 /
  红包 / 优惠 / 开通 / 授权 / 同意 / 允许）
- **`AntiMisclickGate` 冷却层**：规则 3s / 节点 5s / 全局 400ms

### 必须

- **匹配逻辑只操作 `NodeSnapshot`，绝不直接持有 `AccessibilityNodeInfo`**。
  后者无法在 JVM 单测中构造、必须成对 `recycle()`、且只在事件回调期间有效。
  违反此条会让"该不该点这个节点"完全无法测试 —— 而 S1 是唯一
  代替用户操作界面的策略，误点可能触发付费。
- **节点回收统一走 `NodeRecycler.recycle()`**。`recycle()` 自 API 33 起弃用
  （框架接管生命周期），但 `minSdk = 30`，API 30–32 上不回收是**真实内存泄漏**。
  不要在调用点 `@Suppress("DEPRECATION")` 后直接删掉回收调用。
- **事件回调（主线程）内禁止任何数据库访问**。包名判断走 `S1RuleCache`
  内存快照，开关判断走 `ProtectionFlags` 内存镜像。
- **一次事件只点一个节点**（`UiMatcher.matchBest` 返回单个最佳匹配）。
  连续点击多个节点会显著提高误点概率。
- **窗口切换时必须 `EventProcessor.onWindowChanged()`**（内部 `gate.reset()`）。
  否则新界面会被上一界面的残留冷却影响，表现为极难复现的"偶发失效"。
- **点击必须三级降级**：节点自身 `ACTION_CLICK` → 向上找可点击祖先（≤5 层）
  → 坐标手势。大量广告的跳过 `TextView` 自身 `clickable=false`，
  真正响应点击的是父容器；缺"找祖先"会表现为"规则正确但点不掉"。
- **新定位方式必须给置信度**，并保持 `VIEW_ID > TEXT > DESCRIPTION > COORDINATE`。
- **UI 必须区分两层授权**：系统设置授权服务 + 应用内开关。
  写文案时不能只说"开启无障碍"，要说明当前缺哪一层。
- **两个加载器共用资产读取**走 `BuiltinRuleAssets.readAssetText()`，
  不要各自实现一遍 `assets.open(...)`。

### 禁止

- 在无障碍服务里做**保活**（前台服务、JobScheduler、AlarmManager 等）。
  它是系统级服务，本身优先级很高，额外保活只增加耗电与用户困惑。
- 用"找最近的节点"实现 `COORDINATE` 规则。坐标规则描述的是屏幕上一个点，
  强行就近匹配会把点击目标改到别处；应合成虚拟节点直接走手势。
- 请求 `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`。它会让服务接收所有窗口
  （含输入法、系统弹窗）的事件，徒增开销与误点风险。
- 在 `EventProcessor` 的回调里用 `runBlocking` 写库。回调声明为 `suspend`，
  由 `EventProcessor` 自行在 IO 作用域启动协程。
- 在规则 JSON 里写 `COORDINATE` 规则（屏幕尺寸差异导致坐标不可移植，
  资产测试已断言内置规则不含坐标规则）。

### 服务状态三字段不可合并

`AccessibilityState` 的 `serviceRunning` / `serviceEnabledInSettings` /
`appSwitchEnabled` 是三个独立事实。合并成一个布尔值会导致
「界面显示已开启，但拦截日志一条都没有，且用户不知道哪一环断了」。
判断是否真正生效用 `isEffectivelyActive`。

### 两套规则彼此独立（不要混淆）

| | 域名规则（S2/S3） | UI 跳过规则（S1） |
|---|---|---|
| 资产 | `assets/rules/builtin_domains.json` | `assets/rules/builtin_skip_rules.json` |
| 加载器 | `BuiltinRulesLoader` | `BuiltinSkipRulesLoader` |
| 表 | `domain_rule` | `skip_rule` |
| 资产契约测试 | `BuiltinRulesAssetTest` | `BuiltinSkipRulesAssetTest` |
| 解析器测试 | `BuiltinRulesLoaderTest` | `BuiltinSkipRulesLoaderTest` |


## 十三、Shizuku 通道与工具层教训（R11：F1+F2；R12：授权恢复）

### 传输层定稿（不要重开讨论）

- **UserService + 自有极简 AIDL（单方法 `exec(in String[])`）+ `cmd appops` 字符串命令**。
  官方已宣布移除 `newProcess`；框架 AIDL 方法事务码按声明序分配会静默漂移；
  op 数值跨版本漂移 —— `cmd appops` 按字符串名解析同时规避两者。
  **禁止硬编码 op 数值（如 119）**，只用常量 `ACCESS_RESTRICTED_SETTINGS`
- `ShellCommandUserService`：**限时等待再读输出**（先读会在命令不退出时永久阻塞）；
  绝不抛异常，失败以文本标记并入输出由解析层按 UNKNOWN 降级
- `ShizukuShellClient`：`bindUserService` + `CompletableDeferred`（8s 超时）；
  服务器死亡 `reset()` 归位重绑
- 状态机 `ShizukuState`（**包级声明**，门面要 import）：
  `Unavailable / NeedsPermission / Probing / Ready(canSetAppOps)`；
  **能力探测诚实原则：`Ready` 只携带已实现且实打验证过的能力项**
  （F2 仅 `canSetAppOps`，用只读 `cmd appops get` 实测），不做「常量 false 冒充已探测」
- Shizuku API 速记：服务器版本查询是 `Shizuku.getVersion()`
  （**没有** `getServerVersion`）；aidl 开关在 AGP 9.x 用
  `buildFeatures { aidl = true }`

### 可见性与分层（违反即编译错）

- `AppContainer` 公开属性持有 `ShizukuShellClient` / `RestrictedSettingsFixer`，
  因此这两个类**不能标 `internal`**（Kotlin 规则：public 签名暴露 internal 类型直接报错，
  门面构造器同理）—— 由此 `RestrictedSettingsOps` 也必须公开（fixer 公开方法返回它的嵌套类型）
- feature 层只 import `SideloadRestrictionController`（core/service 门面），
  零 `core/service/shizuku` 引用（§3 分层约束）；未装 Shizuku 时一切功能完整
- 受限判定闭环：解除成功调 `AccessibilityStateHolder.markRestrictedSettingCleared()`
  做进程内覆盖（`&& !restrictedSettingCleared`），**刻意不持久化** ——
  appop 真实状态由系统持有，重启回保守判定：限制仍在提示卡重现、可再解除
- 成功判定 = **回读验证**（set 不报错 ≠ 生效）；变体链 包级 → `--uid`；
  成功后直接打开系统无障碍设置（§6.5.3）

### 🔴 工具层教训：同一文件的多个编辑绝不能放进同一并行批次

R11 曾向同一文件在同一消息批次发多个 Edit，结果**随机互相覆盖**（部分编辑
静默丢失：toml 版本声明、imports、字段、函数体各有殃及），下游表现为
各种看似无关的编译错误，排查成本远高于省下的轮次。

**铁律：每个文件每轮最多一个 Edit；只有不同文件才可并行。**
修复受影响文件前必须先 read/grep 确认真实状态，不能凭"上次编辑成功"的回执推断。

### R12：授权恢复（restoreAuthorization）的契约

- **read-merge-write 是铁律**：`enabled_accessibility_services` 是全局共享设置，
  绝不整体覆盖（会抹掉其他应用的无障碍条目）。顺序：
  `settings get` → `parseEntries`（容忍 `"null"` 字面量/空白/脏分隔符）→
  `mergeEnabledServices`（**已存在条目零改写**，含大小写变体——不规范化、不追加）→
  `settings put` → `settings get` **回读验证**（set 不报错 ≠ 生效，
  SELinux/ROM 静默拒绝是真实失败形态）→ 另写 `accessibility_enabled=1`
- **R8 禁止表的边界**：禁止的是「周期性后台自动写授权记录」；
  用户显式点击的一次性恢复不在此列。后台静默周期写依然禁止
- **可测性模式**：命令执行收敛为注入函数
  `restore(exec: suspend (List<String>) -> String?, flat)`——生产传 `shell::exec`，
  测试传记录调用顺序的假 exec；决策层零 Android 依赖（对照 S1RuleCache 收 Flow）
- **门面拆分**：`AccessibilityRecoveryController`（恢复授权）与
  `SideloadRestrictionController`（解除受限）平行独立——两个关注点不共名；
  Restorer 同样不能 internal（AppContainer 公开属性持有）
- **诊断尾行语义**：`diagnosticTail("null")` 返回字符串 `"null"`，空串才返回 null
- 🔴 命令断言按**序位**：read 与 write 命令第 4 参都是键名，
  `.first { it[3] == key }` 会永远先匹配到 read —— 用 `commands[1]` 按调用顺序断言
- 🔴 `--rerun-tasks` 与配置缓存不兼容（秒退，告警校验无效）。强制重编译姿势：
  `find app/src/main/java -name "*.kt" -exec touch {} +` 后 compile + `grep "^w: "`

## 十四、已知待修正项（不要沿用其写法）

以下是当前代码库里客观存在的"临时/占位/违规"实现，**新增代码不要模仿**，改动相关
文件时顺手修正。

**违反本规范的写法**（不得以「与现有代码保持一致」为由复制）：

| 位置 | 问题 |
|---|---|
| `core/designsystem/StatCard.kt` | 写死 `fontSize = 11.sp` |
| `ui/theme/Theme.kt` | `SlateOutline` 被深浅两套色板共用 |
| 各 `Screen.kt` | 界面仍有硬编码中文，未全部迁到 `strings.xml` |

**尚未接通的真实数据**（当前为占位实现）：

- **应用列表的图标仍是占位方块** — 尚未接入 `PackageManager` 加载真实图标
- **无「昨日拦截」区间查询** — 只有总量计数，没有时间区间聚合
- **S2/S3/S4 的策略状态** — 首页已如实显示为「未接入」，待阶段 C–F 接线

**工程质量缺口**：

- **Detekt / ktlint 尚未引入** — 静态分析门禁缺失，目前只有单元测试
- **`NoAdAccessibilityService.DEBUG_LOG` 目前为 `true`** — 发布前必须关闭，
  否则 logcat 会持续输出每次事件的判定结果

> 已修复（勿回退）：
> - 首页策略状态的 `active` 曾写死 `false`，阶段 B 已接入
>   `AccessibilityStateHolder` 的真实状态。
> - **跳过规则管理页曾缺失**，导致 `skip_rule` 表恒空、S1 完全失效。
>   已于 2026-09-19 补齐（`feature/rules/`）+ 内置规则资产 + 启动导入。
> - **`RuleRepository.add/addAll` 曾零调用点**。新增任何"规则来源"时，
>   必须确认它真的有写入路径，光有仓储方法不算完成。
> - **`skip_rule` 的 `source` 列曾用 `enum.name` 存储**（大写），
>   与迁移的 `DEFAULT 'user'` 不一致，会导致「规则莫名消失」。
>   必须用 `SkipRuleSource.persistedName`（小写）。

> 处理原则：修一项就删一项，不要在此长期堆积。若某条已确认不打算做，
> 也要先判定为「不做」并写清原因，而不是让它无限期挂在"待修正"里。

## 十五、官方参考

- Android 架构指南 — https://developer.android.com/topic/architecture
- Android 应用模块化 — https://developer.android.com/topic/modularization
- Kotlin 编码约定 — https://kotlinlang.org/docs/coding-conventions.html
- Compose 最佳实践 — https://developer.android.com/jetpack/compose/best-practices
- Compose 状态提升 — https://developer.android.com/jetpack/compose/state-hoisting
- Android 权限最佳实践 — https://developer.android.com/training/permissions/requesting
- 无障碍服务开发 — https://developer.android.com/guide/topics/ui/accessibility/service
