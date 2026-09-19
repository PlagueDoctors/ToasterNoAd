# NoAd 项目长期记忆

## 定位与栈
NoAd —— Android 广告拦截（自动关开屏/弹窗广告），com.toaster.noad，单模块 app，Compose+M3。
锁定栈（禁随意升级）：AGP 9.3.2 / Gradle 9.5.0 / Kotlin 2.2.10 / KSP 2.2.10-2.0.2 / Compose BOM 2026.02.01 / compileSdk=targetSdk 37 / minSdk 30 / Java 11 / Room 2.8.5 / DataStore 1.2.1 / coroutines 1.9.0。

## 交付标准（用户定界，全程有效）
唯一验收 = `./gradlew testDebugUnitTest` 全绿 + `compileDebugKotlin` 0 error 0 warning。
禁止实机测试与 assembleDebug 交付（用户自测）。

## 构建/架构硬约束
1. gradle.properties 必须留 `android.disallowKotlinSourceSets=false`（KSP 靠它注册生成目录）。
2. schemaLocation 同放 defaultConfig 与顶层 ksp{}；禁 fallbackToDestructiveMigration，改表写显式 Migration。
3. 序列化强制 1.8.1：resolutionStrategy.force + toml serialization=1.8.1 不可回退（防误导性 AbstractMethodError）。
4. 构造参数资源注解写 @param:StringRes（KT-73255，0 warning 门禁）。
5. combine 最多 5 个 Flow；Kotlin Set 无下标访问。
6. 领域模型不带 Room 注解；枚举入库 String；Repository 只暴露 Flow、写 suspend；无 DI（AppContainer 全 lazy + ViewModelFactory）。
7. 事件回调（主线程）禁查库：走 S1RuleCache/ProtectionFlags 内存镜像。

## 四策略
S1 无障碍点击（无 VPN）；S2 DNS；S3 全流量 VPN；S4 Shizuku 断网（不占 VPN）。S2⊥S3 互斥；S4 任意组合；见其他 VPN 主动停绝不重连。
- S2：只 addRoute 10.0.0.1/32；establish 前读上游 DNS；socket 必须 protect()；NXDOMAIN/SERVFAIL 区分；后缀匹配 `domain==s||domain.endsWith(".$s")`；白名单优先。
- S1 铁律：规则来自实证非常识；开屏文本 EXACT 必落空（用 PREFIX≤6 字）；通用规则同样受纳管约束（先判 managedPackages）；预筛只放宽不收紧（VIEW_ID/REGEX 整体放行）；点击投后台（ClickScheduled 不计总数）；NodeSnapshot 隔离、recycle 不能删、窗口切换 gate.reset()。

## skip_rule 链路（零件全绿≠链路可用）
builtin_skip_rules.json → Loader → mergeBuiltin（幂等/只增不删/IGNORE）→ S1RuleCache → 四闸。source 存 persistedName 小写；CONTAINS 防护放规则层（≥4 字、禁诱导词）。

## R8 授权模型（无保活，靠自愈）
三态 isEffectivelyActive/isDisconnectedButAuthorized（勿扰）/isNotAuthorized；禁保活、禁隐藏断开；Watchdog 同步启动+前台 ON_RESUME 缺一不可（14+ RECEIVER_NOT_EXPORTED；不注册 SCREEN_OFF）；首因优先；时钟可注入。

## R9/R10 保活与隐身
恢复链：粘性重启（false 必停，null=乐观恢复）→心跳（失败必重排、onDestroy 不取消、显式 Intent）→BOOT/更新广播（门控=keepAlive&&autostart）。FGS specialUse+低渠道；13+ 通知权限拒绝不阻断；force-stop 不可自启。R10：excludeFromRecents；通知动态=纯层 Content 类；无横幅=低渠道+同 ID；计数=intercept_log COUNT 单一源。

## Shizuku（R11 F1/F2、R12 恢复授权，细节见 skill §十三）
传输层 = UserService + 自有极简 AIDL(exec) + cmd appops 字符串名（newProcess 将被移除；框架 AIDL 事务码漂移；禁 op 数值）。getVersion()≤0 未就绪（无 getServerVersion）。成功判定=get 回读验证（包级→--uid）；门面 SideloadRestrictionController（feature 零引用）；成功调 markRestrictedSettingCleared()（进程内，重启归保守）；ShellClient/Fixer/Ops/Restorer 不能 internal（AppContainer 公开持有）。诚实边界：Ready 只带 canSetAppOps；失败回退手动引导。
R12 恢复：一键清理按 force-stop 撤销授权（不可阻止）。read-merge-write：get→合并（已存在零改写含大小写）→put→回读验证；+accessibility_enabled=1。R8 禁止表只禁周期后台写（显式恢复不在此列）。门面 RecoveryController 平行于 SideloadRestrictionController。
🔴 同一文件多个 Edit 不得进同一并行批次（随机覆盖静默丢失）；每文件每轮一个。

## 定稿与环境
- 域名规则 60 黑+6 白 builtin_domains.json，黑白必须同批导入。
- 图标：只依赖 alpha；不用 strokeWidth；矢量与 gen_icon_pngs.py 同步；monochrome=foreground；webp 勿恢复。
- 质量：307 tests / 18 类（R12 后），0 警告。
- 环境：Bash 先 export PATH；网络代理可达 Maven Central；校验看 merged_manifest。
- 文档：docs/CODING_STANDARDS.md（权威）；THREE_STRATEGY_PLAN.md（唯一方案，R11/R12 已录）；skill noad-dev-standards（§十三）。
