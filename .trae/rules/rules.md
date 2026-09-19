# NoAd Android 项目编码规则

> 适用项目：Kotlin + Jetpack Compose Android 应用
> 目标：保持代码简洁、易懂、可维护，遵循 Google 官方推荐架构与风格

> **规范优先级说明**
> 本项目规范的权威顺序为：
> 1. `docs/CODING_STANDARDS.md` —— 完整规范（Kotlin 编码规范 + Android 应用结构规范）
> 2. `.trae/rules/coding-standards.md` —— 精简约束版，供编码时自动遵守
> 3. 本文件 —— 项目早期的架构与风格说明，保留作为背景参考
>
> **当本文件与上述文件冲突时，以上述文件为准。**

---

## 一、项目目录与包层级

### 1. 顶层结构（Gradle 视角）
```
NoAd/
├── settings.gradle.kts          # 声明包含哪些模块
├── build.gradle.kts             # 根构建脚本（插件版本）
├── gradle/libs.versions.toml    # 统一版本目录
├── app/                         # 主应用模块
│   ├── build.gradle.kts         # 模块配置（SDK、依赖、签名）
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/                # 正式代码
│       ├── test/                # JVM 单元测试
│       └── androidTest/         # 设备端插桩测试
└── ...（可有多个模块：core、data、feature 等）
```

### 2. `app/src/main/` 内部分层
```
src/main/
├── AndroidManifest.xml          # 组件声明、权限、入口 Activity
├── java/com/toaster/noad/       # Kotlin 源码（包名 = namespace）
└── res/                         # 资源
    ├── values/                  # strings/colors/themes.xml
    ├── drawable/                # 矢量图/位图
    ├── mipmap-<density>/        # 启动图标（按密度）
    ├── xml/                      # backup/data extraction 规则
    └── layout/ font/ etc.       # 视图项目才有
```

### 3. 推荐包结构（feature-first，按特性分层）
```
com/toaster/noad/
├── MainActivity.kt              # 仅做入口绑定
├── NoAdApplication.kt           # Application 子类（如需）
├── core/                        # 跨特性共享
│   ├── common/                  # 工具类、常量、扩展函数
│   ├── data/                    # Repository 接口、数据源
│   ├── model/                   # 领域模型
│   ├── network/                 # Retrofit/OkHttp 客户端
│   ├── database/                # Room 数据库、DAO
│   ├── designsystem/            # 自有 UI 组件库
│   └── ui/theme/                # Color/Type/Theme
└── feature/                     # 每个业务特性一个目录
    ├── home/
    │   ├── HomeScreen.kt        # Composable 入口
    │   ├── HomeViewModel.kt     # 状态持有者
    │   ├── HomeUiState.kt       # UI 状态数据类
    │   └── components/          # 仅本特性使用的子组件
    └── settings/
```

### 4. 包命名公约
- 全小写、不以下划线开头、不含连字符。
- 示例：`com.toaster.noad.feature.home`，不写 `Feature.Home`。

---

## 二、Kotlin / Compose 编码公约

### 1. 通用 Kotlin
- **命名**：类/接口 `PascalCase`；函数/变量 `camelCase`；常量 `UPPER_SNAKE_CASE`；包名全小写。
- **不可变优先**：`val` > `var`；`data class` 字段不可变；对外暴露 `List` 而非 `MutableList`。
- **空安全**：避免 `!!`，用 `?.`、`?:`、`let { }`；非空断言只在 100% 确定时使用。
- **作用域**：尽可能 `private`；只暴露必要 API；内部实现用 `internal` 而非 `public`。
- **扩展函数**：放专属文件（`StringExt.kt`、`ContextExt.kt`），文件名 `类型+Ext`。
- **默认参数 > 重载**；命名参数用于布尔/多参场景提升可读性。
- **`when` 表达式** 优先于 `if/else` 链，需穷尽或加 `else`。
- **单表达式函数**用 `=`：`fun isOdd(n: Int) = n % 2 == 1`。

### 2. Jetpack Compose 特有
- **Composable 命名 `PascalCase`**：`Greeting(...)` 而非 `greeting()`，视为"类型"。
- **参数顺序**：必填在前，`modifier: Modifier = Modifier` 紧随其后，再是可选参数。
  - 正确：`fun Greeting(name: String, modifier: Modifier = Modifier, onAction: () -> Unit = {})`
- **State 上提**：Composable 无状态化（接收 state + lambda），状态由 `ViewModel` 通过 `StateFlow` 持有。
  - `ViewModel` 暴露 `StateFlow<UiState>`，Composable 用 `collectAsStateWithLifecycle()`。
- **副作用**：用 `LaunchedEffect` / `DisposableEffect` / `rememberCoroutineScope`；不在 Composable 顶层直接改状态。
- **Preview**：每个可复用 Composable 配 `@Preview`，并用 `@PreviewLightDark` 覆盖深浅色。
- **Modifier 链**：外部 → 内部顺序；`fillMaxSize().padding(...)` 先占满再留边。
- **主题取色**：用 `MaterialTheme.colorScheme.primary`，不要写死 `Color(0xFF...)`。
- **列表**：`LazyColumn` + `key = { it.id }`；避免 `RecyclerView` 思维。

### 3. 资源命名
- `strings.xml`：`feature_purpose`，如 `home_title_greeting`。
- `colors.xml`：尽量用主题色，资源中只放原始色板。
- 文件名全小写下划线：`ic_launcher_foreground.xml`。

### 4. Manifest & 权限
- 权限声明集中、加注释说明用途（隐私合规要求）。
- 组件默认不 `export`，必须 export 的显式写 `android:exported="true"`。

### 5. 测试
- 单元测试放 `src/test/`，命名 `类名+Test`。
- 测试方法用状态描述：`fun givenX_whenY_thenZ()`。
- Compose UI 测试放 `src/androidTest/`，用 `createComposeRule()`。

### 6. 版本管理
- 依赖统一放 `gradle/libs.versions.toml`，避免散落版本号。
- `compileSdk` 取最新稳定，`minSdk` 按业务最低需求设。

---

## 三、参考来源
- Google Android 架构指南：https://developer.android.com/topic/architecture
- Kotlin 编码规范：https://kotlinlang.org/docs/coding-conventions.html
- Jetpack Compose 最佳实践：https://developer.android.com/jetpack/compose/best-practices

---

## 四、本项目权威规范文件

| 文件 | 作用 |
|---|---|
| `docs/CODING_STANDARDS.md` | 完整规范：Kotlin 编码规范 + Android 应用结构规范（Google 官方 + 项目约束） |
| `.trae/rules/coding-standards.md` | 精简约束版，编码时自动遵守 |
| `.trae/rules/rules.md`（本文件） | 早期架构与风格说明，背景参考 |
