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

## 十二、S1 无障碍（阶段 B 已实现）

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

### 禁止

- 在无障碍服务里做**保活**（前台服务、JobScheduler、AlarmManager 等）。
  它是系统级服务，本身优先级很高，额外保活只增加耗电与用户困惑。
- 用"找最近的节点"实现 `COORDINATE` 规则。坐标规则描述的是屏幕上一个点，
  强行就近匹配会把点击目标改到别处；应合成虚拟节点直接走手势。
- 请求 `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`。它会让服务接收所有窗口
  （含输入法、系统弹窗）的事件，徒增开销与误点风险。
- 在 `EventProcessor` 的回调里用 `runBlocking` 写库。回调声明为 `suspend`，
  由 `EventProcessor` 自行在 IO 作用域启动协程。

### 服务状态三字段不可合并

`AccessibilityState` 的 `serviceRunning` / `serviceEnabledInSettings` /
`appSwitchEnabled` 是三个独立事实。合并成一个布尔值会导致
「界面显示已开启，但拦截日志一条都没有，且用户不知道哪一环断了」。
判断是否真正生效用 `isEffectivelyActive`。

## 十三、已知待修正项（不要沿用其写法）

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
- **规则管理页尚未实现** — 仓储与 DAO 已就绪，UI 层未接
- **`NoAdAccessibilityService.DEBUG_LOG` 目前为 `true`** — 发布前必须关闭，
  否则 logcat 会持续输出每次事件的判定结果

> 已修复（勿回退）：首页策略状态的 `active` 曾写死 `false`，阶段 B 已接入
> `AccessibilityStateHolder` 的真实状态。

> 处理原则：修一项就删一项，不要在此长期堆积。若某条已确认不打算做，
> 也要先判定为「不做」并写清原因，而不是让它无限期挂在"待修正"里。

## 十四、官方参考

- Android 架构指南 — https://developer.android.com/topic/architecture
- Android 应用模块化 — https://developer.android.com/topic/modularization
- Kotlin 编码约定 — https://kotlinlang.org/docs/coding-conventions.html
- Compose 最佳实践 — https://developer.android.com/jetpack/compose/best-practices
- Compose 状态提升 — https://developer.android.com/jetpack/compose/state-hoisting
- Android 权限最佳实践 — https://developer.android.com/training/permissions/requesting
- 无障碍服务开发 — https://developer.android.com/guide/topics/ui/accessibility/service
