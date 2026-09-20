# NoAd 项目长期记忆

## 定位与栈
NoAd —— Android 广告拦截（自动关广告），com.toaster.noad，单模块 app，Compose+M3。
栈：AGP 9.3.2/Gradle 9.5.0/Kotlin 2.2.10/KSP 2.2.10-2.0.2/Compose BOM 2026.02.01/compileSdk=targetSdk 37/minSdk 30/Java 11/Room 2.8.5/DataStore 1.2.1/coroutines 1.9.0。
> 细节（构建注意项、时钟注入等待）以 skill `noad-dev-standards` §十三 与 plan 为准，
> 本文件只留最易忘的硬约束。

## 交付标准（用户定界）
唯一验收 = 单测全绿 + compileDebugKotlin 0 error 0 warning。
禁止实机测试与打包交付；实机验证一律由用户 Android Studio 自建（已两次违规，勿再犯）。

## 最易忘的硬约束
1. combine 最多 5 个 Flow；Set 无下标；Ksp 目录：`android.disallowKotlinSourceSets=false` 必须留。
2. 改表写显式 Migration（禁 destructive）；序列化强制 1.8.1（防 AbstractMethodError）。
3. 主线程热路径禁查库禁 IPC：走内存镜像；时钟/日志一律注入（Android 方法在 JVM 单测抛 not mocked）。
4. 抽象：Ops（纯逻辑）= 唯一命令序列权威；Controller = 就绪检查+下发+回读验证。
5. 🔴 DNS 虚拟地址绝不能=TUN 地址（内核 local 表劫持）；🔴 cmd appops 只用字符串 op 名。

## 四策略
S1 无障碍点击；S2 DNS；S3 全流量 VPN；S4 Shizuku 断网（不占 VPN）。S2⊥S3 互斥；S4 任意组合；见他 VPN 停绝不重连。
- S2：只 addRoute DNS 虚拟地址；establish 前读上游 DNS（仅 IPv4）；socket 必须 protect()。
- S1 铁律：EXACT 必落空（PREFIX≤6 字）；通用规则受纳管约束；预筛只放宽；点击投后台。
- S3 入口仍关（TCP 转发需 tun2socks 级实现）；E 数据层（A 记录解析/IpDomainMap/TrafficFilter，无映射一律放行）已就绪。

## 恢复链（R8–R14）
R8 三态（无保活靠自愈，Watchdog 同步启动+前台 ON_RESUME 缺一）。
R12 一键清理=force-stop 撤销授权：读改写→回读验证。R13 adb WRITE_SECURE_SETTINGS 终身（卸载重装失），通道序 secure>Shizuku。
R14 开应用自检静默恢复（30s/10 次节流；锚=开关开+用户打开）。
Shizuku：getVersion()≤0 未就绪；ShellClient/Ops/Controller 勿 internal（容器持有）。

## 阶段 F（1.4-s4phase，已交付能力层）
F1 能力探测逐项可失败→Holder；F3 Chain-3 断网（`<pkg>:allow` 回读）；F4 Private DNS（mode+specifier 成对、拒 URL）；
F5 包/组件停用（拒系统应用）；F6 AppOps 字符串 op；F7 强停（10s/包冷却）；降级链路：让位→appFirewallEnabled 应用断网。
**未接线**：F3–F7 的 UI 入口。

## 流程惯例
🔴 同一文件多个 Edit 不得进同一并行批次；版本号随交付递增；实机取证优先于猜测（adb logcat -d / ip rule / 只读命令探测输出格式）。
质量基线：479 tests/38 类，0 警告。
命令行构建：登记 `installations.paths=AS 的 jbr`（daemon 需 JDK 25）+ 关 auto-download。
