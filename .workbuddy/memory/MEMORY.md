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
| 单元测试 | 82 tests / 0 failures（clean build 验证） |
| 覆盖的测试类 | UiMatcherTest(24)、BuiltinRulesLoaderTest(16)、DomainRuleEngineTest(15)、BuiltinRulesAssetTest(13)、AntiMisclickGateTest(13)、ExampleUnitTest(1) |
| 构建 | `assembleDebug` 通过，APK ~66MB，编译告警 0 |

## S1 无障碍（阶段 B 已完成 2026-09-19）

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
