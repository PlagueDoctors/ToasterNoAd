# NoAd 开发规范（Kotlin / Jetpack Compose / Android）

> 适用范围：`NoAd` 项目全部 Kotlin 与 Android 相关改动
> 基线来源：Google Android 官方架构指南、Kotlin 官方编码约定、Jetpack Compose 官方最佳实践
> 强制性标记：**MUST**（必须）／**SHOULD**（应当，偏离需有理由）／**MAY**（可选）
> 版本：v1.0 · 最后更新 2026-09-19

---

## 0. 如何使用本规范

规范分为两部分：

| 部分 | 内容 | 对应章节 |
|---|---|---|
| **A. Kotlin 代码编写规范** | 语言级、命名、空安全、协程、异常 | 第 1–5 章 |
| **B. Android 应用结构规范** | 模块、分层、架构、依赖、清单、测试 | 第 6–12 章 |

当本规范与 `.trae/rules/rules.md` 存在冲突时，**以本文件为准**；本文件未覆盖的细节，回退到 `.trae/rules/rules.md`。
当本规范与 Google 官方指南存在冲突时，先记录冲突并说明理由，不得静默偏离。

**项目级不可协商的技术事实**（改动前必须知悉）：

| 项 | 锁定值 | 出处 |
|---|---|---|
| 包名 / namespace | `com.toaster.noad` | `app/build.gradle.kts` |
| `minSdk` | 30 | 同上 |
| `compileSdk` / `targetSdk` | 37 | 同上 |
| Java 兼容版本 | `VERSION_11` | 同上 |
| 构建 DSL | Gradle Kotlin DSL（`.kts`） | 全项目 |
| 版本管理 | Gradle Version Catalog（`gradle/libs.versions.toml`） | 同上 |
| UI 框架 | Jetpack Compose + Material 3（**不引入 XML 布局**） | 同上 |

---

# A. Kotlin 代码编写规范

## 1. 源文件组织

1.1 **MUST** 文件名与其中唯一的顶层声明保持一致。含多个顶层声明的文件，用能概括内容的名字命名。
1.2 **MUST** 文件编码 UTF-8，缩进 4 空格，不使用 Tab。
1.3 **MUST** 不引入通配符导入（`import foo.*`），IDE 自动整理时关闭该选项。
1.4 **SHOULD** 单个文件不超过 400 行；超过时按职责拆分。NoAd 现有最大文件 246 行，无需拆分的不要为拆而拆。
1.5 **MUST NOT** 在源文件中遗留被注释掉的代码块、`TODO` 无跟踪编号的占位实现（参见 §3.7）。

## 2. 命名

### 2.1 通用

| 元素 | 规则 | 正例 | 反例 |
|---|---|---|---|
| 包 | 全小写，不含下划线/连字符 | `com.toaster.noad.feature.home` | `com.toaster.noad.Feature` |
| 类 / 接口 | `PascalCase` | `HomeViewModel` | `home_view_model` |
| 函数 / 属性 | `camelCase` | `toggleProtection` | `ToggleProtection` |
| 常量（`const val` / 顶层 `val`） | `UPPER_SNAKE_CASE` | `MAX_RETRY_COUNT` | `maxRetryCount` |
| 类型参数 | 单大写字母或 `PascalCase` | `T`、`ResultT` | `t` |
| 局部变量 | `camelCase`，可用短名 | `it`、`app` | `a`、`tmp2` |

### 2.2 特殊规则

2.2.1 **MUST** 在名称中使用 4 个字母以上的连续大写作为缩写时，按普通单词处理：`HttpClient` 而非 `HTTPClient`；但 `IOStream` 这类既有约定可保留。
2.2.2 **MUST** 布尔属性以 `is` / `has` / `can` / `should` 开头，读起来是断言。
2.2.3 **MUST** 返回布尔值的函数用第三人称单数或 `is` 前缀：`isBlank()`、`equals()`。
2.2.4 **MUST** 扩展函数文件名用「接收者类型 + Ext」：`ContextExt.kt`、`FlowExt.kt`。
2.2.5 **MUST** 测试类命名为「被测类 + `Test`」；测试方法命名为 `givenX_whenY_thenZ`（与 `.trae/rules/rules.md` §二.5 一致）。
2.2.6 **SHOULD** 避免与 Kotlin 标准库名称撞名（如自定义 `let`、`apply`）。
2.2.7 **MAY** 在测试代码中，若单个测试方法名过长难以阅读，可使用反引号包裹的自然语言名。**MUST NOT** 在 `src/main/` 中使用反引号函数名。

## 3. 语言特性

### 3.1 不可变性

3.1.1 **MUST** 优先 `val`，仅在确实需要重新赋值时用 `var`。
3.1.2 **MUST** 数据载体使用 `data class`，其字段全部为 `val`。
3.1.3 **MUST** 对外暴露只读集合类型（`List`、`Set`、`Map`），私有字段才用 `MutableList` 等。
3.1.4 **SHOULD** 状态变更通过 `copy()` 产生新实例，而非原地修改。NoAd 的 `ViewModel` 已遵循此模式，保持。

```kotlin
// 正确
_uiState.value = _uiState.value.copy(protectionEnabled = !_uiState.value.protectionEnabled)

// 错误：暴露可变集合，外部可绕过状态管理
val apps: MutableList<AppItem> = mutableListOf()
```

### 3.2 空安全

3.2.1 **MUST NOT** 使用 `!!`。用 `?.`、`?:`、`let`、`requireNotNull`（附带信息）替代。
3.2.2 **MUST** 平台类型（来自 Java 的返回值）必须显式声明可空性。
3.2.3 **SHOULD** 用 `?.let { }` 而非 `if (x != null) { x.foo() }`，但若分支逻辑超过两行，用显式 `if` 更清晰。

### 3.3 表达式与函数

3.3.1 **MUST** 单表达式函数用 `=` 形式：`fun isEnabled() = flag && ready`。
3.3.2 **MUST** 多分支判断优先 `when`；`when` 用作表达式时 **MUST** 穷尽或显式 `else`。对 `sealed class` / `enum` 的分支 **MUST NOT** 写 `else`（让编译器在新增分支时报错）。
3.3.3 **SHOULD** 函数参数不超过 5 个；超出时抽参数对象（`data class`）。
3.3.4 **MUST** 布尔型、多同类型参数调用处使用具名参数：
```kotlin
SettingsToggleRow(title = "拦截通知", checked = uiState.notification, onToggle = viewModel::toggleNotification)
```
3.3.5 **MUST** 函数职责单一，超过约 40 行考虑拆分。

### 3.4 可见性与作用域

3.4.1 **MUST** 默认使用最窄可见性；仅确需跨模块/包访问时才写 `internal` / `public`。
3.4.2 **MUST** 仅在所属特性内部使用的 Composable **MUST** 标 `private`。NoAd 的 `ProtectionToggleCard`、`ProtectedAppRow`、`AppRow`、`LogRow`、`SettingsGroupCard` 均已正确标 `private`，保持此约定。
3.4.3 **SHOULD** 模块内部 API 用 `internal` 而非 `public`。

### 3.5 扩展函数

3.5.1 **MUST** 扩展函数不覆盖已有成员函数语义，不放在无关类的内部。
3.5.2 **SHOULD** 扩展函数只在确实提升可读性时引入，避免变为全局工具袋。

### 3.6 注解与文档

3.6.1 **MUST** 公开 API（`public` / `internal`）在语义不自明时添加 KDoc。
3.6.2 **MUST** 注释说明「为什么」，不重复代码已表达的「做什么」。
3.6.3 **MUST** 中文注释使用全角标点，与既有代码风格一致。

### 3.7 禁止的写法

3.7.1 **MUST NOT** 提交空的或未使用的 lambda 回调而假装已实现。若某交互尚未实现，**MUST** 显式标注，例如：
```kotlin
// 尚未实现：日志页导航待接入
onClick = {},
```
或直接移除该按钮。当前 `HomeScreen.kt` 的 `onClick = {}` 属于待清理项。
3.7.2 **MUST NOT** 在源码中写入 `System.out.println`、未封装的 `Log.d`（见 §4.4）。
3.7.3 **MUST NOT** 硬编码 UI 文案与颜色（见 §7.5、§7.6）。

## 4. 协程与并发

4.1 **MUST** 协程启动使用结构化并发：`viewModelScope` / `lifecycleScope` / 注入的作用域。**MUST NOT** 使用裸 `GlobalScope`。
4.2 **MUST** 切换调度器使用 `withContext(Dispatchers.IO)`，而非创建新协程。
4.3 **MUST** 可取消的长任务定期检查 `isActive` 或使用挂起函数的天然取消点。
4.4 **MUST** 日志统一封装（后续建立 `core/common/Logger.kt`）；过渡期至少使用 `android.util.Log` 并带统一 TAG，禁止使用 `println`。
4.5 **SHOULD** 在测试中注入调度器，不硬编码 `Dispatchers.Main`（NoAd 尚未接入协程测试设施，接入时遵循此条）。
4.6 **MUST NOT** 在 `Flow` 的 `collect` 中执行阻塞操作。

## 5. 错误处理

5.1 **MUST** 不使用异常做常规控制流。
5.2 **MUST** 捕获异常时明确处理或转换为业务错误，**MUST NOT** 空 `catch` 吞掉异常。
5.3 **SHOULD** 使用 `Result<T>` 或自定义密封结果类型表达可失败操作，而非抛异常穿透 UI 层。
5.4 **MUST** 对 Android 系统 API 的调用（`PackageManager`、`ActivityManager` 等）处理其声明的受检异常与权限异常。

---

# B. Android 应用结构规范

## 6. 模块与目录结构

### 6.1 模块划分

6.1.1 **MUST** 当前保持单模块 `:app`。**MUST NOT** 在没有明确收益时提前拆模块。
6.1.2 **MAY** 在下列信号出现时拆出模块：构建时间显著增长、需要独立复用 `core`、需要按特性做动态交付。
6.1.3 拆分时 **MUST** 采用 Google 官方推荐的分层模块化：`:app`（壳）→ `:feature:*`（特性）→ `:core:*`（`data` / `domain` / `ui` / `common` / `designsystem` / `database` / `network`）。依赖方向 **MUST** 单向：`app → feature → core`，**MUST NOT** 出现 `core → feature` 反向依赖。

### 6.2 包结构（feature-first）

6.2.1 **MUST** 遵循现有 feature-first 布局，新增页面也照此落位：

```
com.toaster.noad/
├── MainActivity.kt              # 仅入口绑定
├── NoAdApplication.kt           # Application 子类（引入全局初始化时创建）
├── core/
│   ├── common/                  # 工具类、常量、扩展函数
│   ├── designsystem/            # 跨特性复用 UI 组件
│   ├── model/                   # 领域模型
│   ├── data/                    # Repository 与数据源
│   ├── database/                # Room 数据库与 DAO
│   ├── service/                 # 无障碍服务、前台服务
│   └── navigation/              # 导航图与目的地定义
├── feature/
│   └── <feature>/
│       ├── <Feature>Screen.kt   # Composable 入口
│       ├── <Feature>ViewModel.kt
│       ├── <Feature>UiState.kt  # 状态与跨层模型
│       └── components/          # 仅本特性使用的子组件
└── ui/theme/                    # Color / Type / Theme
```

6.2.2 **MUST NOT** 按「类型」建层（禁止 `activities/`、`fragments/`、`adapters/` 这类目录）。
6.2.3 **MUST** 包名全小写，不使用下划线或连字符。

## 7. 架构分层

### 7.1 总体

7.1.1 **MUST** 采用 Google 官方推荐的「UI 层 + 数据层（+ 可选领域层）」三层结构。
7.1.2 **MUST** 依赖方向单向：UI → 数据层。数据层 **MUST NOT** 反向引用 UI 类型。
7.1.3 **MUST** UI 层只消费不可变状态对象，**MUST NOT** 让数据层直接产出 `Compose` 相关类型。

### 7.2 UI 层

7.2.1 **MUST** 每个特性遵循「有状态入口 + 无状态内容」两段式：
- `<Feature>Route()`：持有 `ViewModel`，收集状态，向下传状态与回调；
- `<Feature>Screen(uiState, callbacks...)`：纯函数式 Composable，`@Preview` 针对它编写。

7.2.2 **MUST** 状态由 `ViewModel` 通过 `StateFlow<UiState>` 暴露，界面用 `collectAsStateWithLifecycle()` 收集。
7.2.3 **MUST NOT** 在 Composable 内直接修改状态（除传入的回调）；**MUST NOT** 把 `ViewModel` 向下传递超过一层。
7.2.4 **MUST** 副作用使用 `LaunchedEffect` / `DisposableEffect` / `rememberCoroutineScope`，**MUST NOT** 在 Composable 顶层直接发起。
7.2.5 **MUST** UI 状态用单一 `data class` 承载，避免多个独立 `StateFlow` 造成的状态不一致。

### 7.3 数据层

7.3.1 **MUST** 数据访问通过 Repository：`Repository` 接口 + 实现，对 UI 暴露挂起函数或 `Flow`。
7.3.2 **MUST** 一个数据源一个职责；Room 管持久化、DataStore 管偏好、`PackageManager` 管应用信息，不混用。
7.3.3 **MUST** 领域模型与持久化实体（Entity）分离；**MUST NOT** 把 `@Entity` 直接暴露给 UI。
7.3.4 **SHOULD** 偏好设置使用 `DataStore`（Preferences DataStore），**MUST NOT** 使用已废弃的 `SharedPreferences` 承载新功能。

### 7.4 依赖注入

7.4.1 **MAY** 在依赖关系简单时使用手工构造 + 工厂；`ViewModel` 用 `viewModelFactory` 提供依赖。
7.4.2 **SHOULD** 当注入图超过约 10 个对象时引入 Hilt，并在此之前先与维护者确认。
7.4.3 **MUST NOT** 用单例 `object` 作为隐藏的服务定位器。

### 7.5 资源

7.5.1 **MUST** 所有用户可见文案放 `res/values/strings.xml`，通过 `stringResource(R.string.xxx)` 引用。**MUST NOT** 在 Composable 中硬编码中文字面量。
> 当前 `strings.xml` 已定义完整中文文案，但界面代码全部硬编码字面量；后续改动 **MUST** 逐步改用资源引用。
7.5.2 **MUST** 资源命名：`strings.xml` 用 `feature_purpose`（如 `home_today_blocked`）；文件名全小写下划线。
7.5.3 **MUST** 带占位符的字符串保留格式化参数，如 `home_compare_yesterday` → `%1$d%%`。

### 7.6 主题与样式

7.6.1 **MUST** 颜色经 `MaterialTheme.colorScheme.*` 获取。**MUST NOT** 在 Composable 内写死 `Color(0xFF...)`。
7.6.2 **MUST** 字号遵循 `Type.kt` 的 `Typography`。**MUST NOT** 局部写死 `fontSize = 11.sp` 绕过排版体系（`StatCard.kt` 属待修正项）。
7.6.3 **MUST** 深浅色两套色板各自独立定义语义色，**MUST NOT** 让一个色值同时服务两套方案（`SlateOutline` 复用属待修正项）。
7.6.4 **MUST NOT** 启用 Material You 动态取色，保持品牌色稳定（现有 `Theme.kt` 决策，继续遵守）。

### 7.7 Composable 编写约定

7.7.1 **MUST** Composable 名称用 `PascalCase`（视作类型）。
7.7.2 **MUST** 参数顺序：必填参数 → `modifier: Modifier = Modifier` → 可选参数 → 尾随 lambda。
7.7.3 **MUST** 每个可复用 Composable 提供 `@Preview`；`SHOULD` 用 `@PreviewLightDark` 或显式 `darkTheme = true/false` 覆盖两套主题（现有代码统一 `darkTheme = true`，扩展时补齐浅色）。
7.7.4 **MUST** `Modifier` 链按「外部到内部」顺序书写：`fillMaxSize().padding(...)`。
7.7.5 **MUST** 列表使用 `LazyColumn`/`LazyRow` 并显式提供 `key`。
7.7.6 **SHOULD** Composable 嵌套不超过 4 层，超出时抽取子组件。

## 8. Android 组件与清单

8.1 **MUST** 权限按最小必要原则声明，每条权限 **MUST** 附注释说明用途（隐私合规要求）。
8.2 **MUST** 组件默认不导出；确需导出时显式写 `android:exported`。
8.3 **MUST** 引入无障碍服务 / 前台服务时，在 `AndroidManifest.xml` 声明对应 `<service>` 与 `BIND_ACCESSIBILITY_SERVICE` 元数据、`foregroundServiceType`。
8.4 **MUST** 需要读取应用列表时，按 targetSdk 要求处理以下权限的声明、用途说明与运行时请求：
`android.permission.QUERY_ALL_PACKAGES`、`android.permission.PACKAGE_USAGE_STATS`、`android.permission.FOREGROUND_SERVICE`、`android.permission.POST_NOTIFICATIONS`（Android 13+ 运行时权限）。
8.5 **MUST** `Application` 子类（如需全局初始化）在清单 `android:name` 注册。
8.6 **MUST** 新增 `Activity` 前先确认是否真的需要——优先单 `Activity` + Compose 导航。
8.7 **MUST NOT** 在 `AndroidManifest.xml` 中声明未使用的组件或权限。

## 9. 依赖与构建

9.1 **MUST** 所有依赖与版本集中在 `gradle/libs.versions.toml`，模块内通过 `libs.*` 引用。**MUST NOT** 在 `build.gradle.kts` 中写死版本字符串。
9.2 **MUST** 依赖范围正确：仅 `src/main` 需要的用 `implementation`；仅测试需要的用 `testImplementation` / `androidTestImplementation`；仅调试需要的用 `debugImplementation`。
9.3 **MUST** 新增依赖前先确认标准库或既有依赖能否满足，避免重复引入同功能库。
9.4 **MUST NOT** 未经确认升级 AGP / Kotlin / Compose BOM / Gradle 版本。
9.5 **MUST NOT** 修改 `compileSdk`、`minSdk`、`targetSdk` 而不说明影响与理由。
9.6 **SHOULD** 保持 `release` 的 R8 优化与混淆配置，启用时同步补充 `app/src/main/keepRules/rules.keep` 规则。
> 现状：`release.optimization.enable = false`，即当前发布构建未开启优化与混淆。
9.7 **MUST** 启用 `org.gradle.configuration-cache` 的项目，新增构建逻辑必须与配置缓存兼容（不使用 `Project` 在配置期的可变状态）。

## 10. 测试

10.1 **MUST** 单元测试放 `src/test/`，仪器测试放 `src/androidTest/`。
10.2 **MUST** 测试命名遵循 §2.2.5。
10.3 **MUST** 优先测试**行为与契约**（公开行为、边界、失败路径），而非实现细节。
10.4 **MUST NOT** 为覆盖率而写无断言的测试；`MUST NOT` 通过删除/忽略测试来让门禁通过。
10.5 **SHOULD** `ViewModel` 状态变更逻辑、Repository 数据映射、纯函数工具是单元测试的优先目标。
10.6 **SHOULD** Compose UI 测试使用 `createComposeRule()`。
10.7 **MUST** 测试必须确定性，禁止依赖真实时间、网络、设备状态（时间通过注入时钟获取）。
> 现状：仅存在两个 Android Studio 模板测试，对业务代码零覆盖。

## 11. 代码质量工具（建议补齐）

11.1 **SHOULD** 引入 Detekt 作为 Kotlin 静态分析工具，并对齐本规范 §1–§5。
11.2 **SHOULD** 引入 ktlint 保证格式一致性。
11.3 **SHOULD** Android Lint 保持启用，新增告警 **MUST NOT** 通过扩大 baseline 或全局 suppress 来消除；确需抑制时 **MUST** 就地标注原因。
11.4 **SHOULD** 引入 Kover 或 JaCoCo 生成覆盖率报告作为反馈信号；覆盖率 **MUST NOT** 被当作测试有效性的替代品。
11.5 **MUST** 任何门禁、baseline、过滤策略的引入或修改 **MUST** 经确认，不得自行放宽。

## 12. 版本控制

12.1 **MUST** 使用 Git 管理；**推荐** 在开始实质开发前初始化仓库（当前项目无 `.git`，缺少变更基线）。
12.2 **MUST** 提交信息格式：`<type>(<scope>): <描述>`，`type` ∈ `feat` / `fix` / `refactor` / `test` / `build` / `docs` / `style` / `chore`。
12.3 **MUST NOT** 提交 `local.properties`、`.gradle/`、`build/`、`.idea/` 中的本地配置。
12.4 **MUST** 每次提交保持单一意图，不混合无关改动。

---

## 附录 A：审查清单

提交前自查：

- [ ] 无 `!!`、无空 `catch`、无 `GlobalScope`
- [ ] 变量优先 `val`，集合对外只读
- [ ] 文案来自 `strings.xml`，颜色来自 `MaterialTheme.colorScheme`
- [ ] Composable 参数顺序符合 §7.7.2，`Modifier` 链方向正确
- [ ] 独立 Composable 标 `private`，列表有 `key`
- [ ] 每个可复用 Composable 有 `@Preview`
- [ ] 无硬编码版本号，依赖写在 `libs.versions.toml`
- [ ] 新增测试有真实断言，命名 `givenX_whenY_thenZ`
- [ ] 未实现的功能未被伪装成已实现（空 lambda 需显式标注）

## 附录 B：官方参考

- Android 架构指南 — https://developer.android.com/topic/architecture
- Android 应用模块化指南 — https://developer.android.com/topic/modularization
- Kotlin 编码约定 — https://kotlinlang.org/docs/coding-conventions.html
- Kotlin 协程最佳实践 — https://developer.android.com/kotlin/coroutines/coroutines-best-practices
- Compose 最佳实践 — https://developer.android.com/jetpack/compose/best-practices
- Compose 状态提升 — https://developer.android.com/jetpack/compose/state-hoisting
- Android 权限最佳实践 — https://developer.android.com/training/permissions/requesting
- 无障碍服务开发 — https://developer.android.com/guide/topics/ui/accessibility/service
- R8 与 Keep 规则 — https://developer.android.com/build/shrink-code
