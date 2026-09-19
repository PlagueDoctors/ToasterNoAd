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
- `reference/THREE_STRATEGY_PLAN.md` —— 三策略综合技术方案
- `reference/SHIZUKU_ENHANCEMENT.md` —— Shizuku 可选增强方案

## 注意事项

- 项目原本**非 Git 仓库**（无 `.git`），无版本控制基线
- `.trae/` 是 Trae 编辑器的目录，**WorkBuddy 不会自动加载**其中的规则；
  需 WorkBuddy 遵守的规范放在 `.workbuddy/skills/noad-dev-standards/SKILL.md`
