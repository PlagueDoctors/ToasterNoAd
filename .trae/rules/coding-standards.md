# NoAd 项目规范（Kotlin / Compose / Android）

> 完整规范见 `docs/CODING_STANDARDS.md`。本文件是精简约束版，供编码时自动遵守。
> 冲突时以 `docs/CODING_STANDARDS.md` 为准；本文件未覆盖的细节回退到 `rules.md`。
> 标记：**必须** / **应当** / **可选**

## 0. 项目锁定事实（不得擅改）

- 包名 `com.toaster.noad`，单模块 `:app`
- `minSdk 30` / `targetSdk 37` / `compileSdk 37` / Java 11
- Kotlin 2.2.10 · AGP 9.3.2 · Compose BOM 2026.02.01
- 纯 Jetpack Compose + Material 3，**不引入 XML 布局**
- 依赖版本统一放 `gradle/libs.versions.toml`，**禁止**在 `build.gradle.kts` 写死版本号
- **不启用** Material You 动态取色

## 一、Kotlin 编码

**必须**
- 优先 `val`；`data class` 字段全 `val`；对外暴露 `List`/`Set`，不用 `MutableList`
- 禁止 `!!`；平台类型显式声明可空性
- 禁止通配符导入；禁止空 `catch`；禁止 `GlobalScope`
- 禁止 `println`，日志用统一封装的 Logger 或带 TAG 的 `Log`
- 单表达式函数用 `=`
- 不用异常做控制流
- 未被实现的功能**禁止**伪装成已实现（空 lambda 必须加注释标明）

**应当**
- 缩进 4 空格，UTF-8，无 Tab；单文件 ≤400 行
- 函数参数 ≤5；布尔/多类型参数调用处用具名参数
- 仅本特性内部用的 Composable 标 `private`
- 注释解释「为什么」，不复述「做什么」

### 命名

| 元素 | 规则 |
|---|---|
| 包 | 全小写，无下划线/连字符 |
| 类/接口 | `PascalCase` |
| 函数/属性 | `camelCase` |
| 常量 | `UPPER_SNAKE_CASE` |
| 布尔 | 以 `is`/`has`/`can`/`should` 开头 |
| 扩展函数文件 | `<接收者>Ext.kt` |
| 测试类 | 被测类 + `Test` |
| 测试方法 | `givenX_whenY_thenZ` |

## 二、协程

**必须**
- 结构化并发：`viewModelScope` / `lifecycleScope` / 注入作用域，**禁止** `GlobalScope`
- 切线程用 `withContext(Dispatchers.IO)`，不新起协程
- 可取消长任务检查 `isActive`
- 不在 `Flow.collect` 中做阻塞操作

## 三、项目结构（feature-first）

**必须** 遵循下列包分层，新增页面照此落位：

```
com.toaster.noad/
├── MainActivity.kt              # 仅入口绑定
├── NoAdApplication.kt           # 需要全局初始化时创建
├── core/
│   ├── common/  designsystem/  model/  data/
│   ├── database/  service/  navigation/
├── feature/<feature>/
│   ├── <Feature>Screen.kt   <Feature>ViewModel.kt
│   └── <Feature>UiState.kt  components/
└── ui/theme/
```

**必须禁止** 按类型建目录（`activities/`、`adapters/` 等）。

## 四、架构分层（Google 官方三层）

**必须**
- 依赖单向：UI → 数据层，数据层不得反向引用 UI
- 每个特性遵循「有状态入口 + 无状态内容」：
  - `XxxRoute()` 持有 ViewModel、收集状态、向下传状态与回调
  - `XxxScreen(uiState, callbacks)` 纯 Composable，`@Preview` 针对它写
- 状态用单一 `data class` 承载，经 `StateFlow<UiState>` 暴露，界面用 `collectAsStateWithLifecycle()`
- 副作用用 `LaunchedEffect` / `DisposableEffect` / `rememberCoroutineScope`
- 数据访问经 Repository（接口 + 实现）才对 UI 可用
- 领域模型与持久化 Entity 分离；Entity 不得暴露给 UI
- 偏好用 DataStore，不用 `SharedPreferences`

**选项**
- 注入对象 <10 个时用手工构造 + `viewModelFactory`；超过再考虑 Hilt（需先确认）

## 五、Composable 约定

**必须**
- 名称 `PascalCase`
- 参数顺序：必填 → `modifier: Modifier = Modifier` → 可选 → 尾随 lambda
- 每个可复用 Composable 配 `@Preview`
- `Modifier` 链「外部到内部」：`fillMaxSize().padding(...)`
- 列表用 `LazyColumn`/`LazyRow` 并显式给 `key`
- 文案一律走 `stringResource(R.string.xxx)`，**禁止**硬编码中文
- 颜色用 `MaterialTheme.colorScheme.*`，**禁止**写死 `Color(0xFF...)`
- 字号遵循 `Type.kt` 的 `Typography`，**禁止**局部写死 `fontSize`

**应当**
- 嵌套 ≤4 层，超出抽子组件
- `@Preview` 覆盖深浅两套主题

**现有待修正项**（不要沿用其写法）：
- `HomeScreen.kt` 的 `onClick = {}` 空实现
- `StatCard.kt` 写死 `fontSize = 11.sp`
- `Theme.kt` 中 `SlateOutline` 被深浅两套色板共用
- 界面层硬编码中文而非使用已定义好的 `strings.xml`

## 六、清单与权限

**必须**
- 权限按最小必要声明，每条附注释说明用途
- 组件默认不导出；确需导出显式写 `android:exported`
- 引入无障碍/前台服务时声明 `<service>` 与 `BIND_ACCESSIBILITY_SERVICE` 元数据、`foregroundServiceType`
- 读取应用列表按 targetSdk 处理：`QUERY_ALL_PACKAGES`、`PACKAGE_USAGE_STATS`、`FOREGROUND_SERVICE`、`POST_NOTIFICATIONS`（Android 13+ 运行时权限）
- 禁止声明未使用的组件或权限
- 优先单 Activity + Compose 导航

## 七、依赖与构建

**必须**
- 依赖与版本集中在 `libs.versions.toml`
- 依赖范围正确：`implementation` / `testImplementation` / `androidTestImplementation` / `debugImplementation`
- 未经确认不得升级 AGP / Kotlin / Compose BOM / Gradle
- 未经确认不得改 `compileSdk` / `minSdk` / `targetSdk`
- 新增构建逻辑必须兼容配置缓存

**现状提醒**：`release.optimization.enable = false`，发布构建未开启 R8 优化与混淆。

## 八、测试

**必须**
- 单元测试 → `src/test/`；仪器测试 → `src/androidTest/`
- 优先测行为与契约（公开行为、边界、失败路径），非实现细节
- 禁止无断言的测试；禁止靠删除/忽略测试来过门禁
- 测试必须确定性，不依赖真实时间/网络/设备状态

**优先测试目标**：ViewModel 状态变更、Repository 映射、纯函数工具

## 九、质量工具与版本控制

**应当**
- 引入 Detekt + ktlint 对齐本规范
- Android Lint 新增告警**不得**靠扩大 baseline 或全局 suppress 消除；确需抑制须就地注明原因
- 引入 Kover/JaCoCo 覆盖率报告，但覆盖率不等于测试有效性

**必须**
- 门禁 / baseline / 过滤策略的引入或修改须经确认
- 提交信息：`<type>(<scope>): <描述>`；type ∈ feat|fix|refactor|test|build|docs|style|chore
- 禁止提交 `local.properties`、`.gradle/`、`build/`、`.idea/` 本地配置

> 现状：项目无 `.git`，建议开始实质开发前先初始化仓库以获得变更基线。

## 十、提交前自查

- [ ] 无 `!!`、无空 `catch`、无 `GlobalScope`、无 `println`
- [ ] 变量优先 `val`，集合对外只读
- [ ] 文案来自 `strings.xml`，颜色来自 `MaterialTheme.colorScheme`
- [ ] Composable 参数顺序正确，`Modifier` 链方向正确
- [ ] 仅内部使用的 Composable 标 `private`，列表有 `key`
- [ ] 可复用 Composable 有 `@Preview`
- [ ] 无硬编码版本号
- [ ] 新增测试有真实断言且按规范命名
- [ ] 未实现的功能未被伪装成已实现
