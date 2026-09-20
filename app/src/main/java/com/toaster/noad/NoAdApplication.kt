package com.toaster.noad

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import com.toaster.noad.core.applist.InstalledAppDataSource
import com.toaster.noad.core.data.repository.DomainRuleRepository
import com.toaster.noad.core.data.repository.LogRepository
import com.toaster.noad.core.data.repository.RuleRepository
import com.toaster.noad.core.data.repository.TargetAppRepository
import com.toaster.noad.core.data.rules.BuiltinRulesLoader
import com.toaster.noad.core.data.rules.BuiltinSkipRulesLoader
import com.toaster.noad.core.data.settings.SettingsRepository
import com.toaster.noad.core.database.NoAdDatabase
import com.toaster.noad.core.database.entity.DomainRuleEntity
import com.toaster.noad.core.service.AccessibilityAutoRestorer
import com.toaster.noad.core.service.AccessibilityRecoveryController
import com.toaster.noad.core.service.AccessibilityStateHolder
import com.toaster.noad.core.service.AccessibilityWatchdog
import com.toaster.noad.core.service.AppFirewallController
import com.toaster.noad.core.service.AppOpsController
import com.toaster.noad.core.service.AutoRestorePolicy
import com.toaster.noad.core.service.PackageController
import com.toaster.noad.core.service.PrivateDnsController
import com.toaster.noad.core.service.ProcessController
import com.toaster.noad.core.service.ProtectionFlags
import com.toaster.noad.core.service.SecureSettingsAccessibilityRestorer
import com.toaster.noad.core.service.SideloadRestrictionController
import com.toaster.noad.core.service.keepalive.KeepAliveRuntime
import com.toaster.noad.core.service.keepalive.KeepAliveService
import com.toaster.noad.core.vpn.VpnArbitrator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.NetworkInterface
import java.util.Collections
import com.toaster.noad.core.service.shizuku.AccessibilityAuthorizationRestorer
import com.toaster.noad.core.service.shizuku.RestrictedSettingsFixer
import com.toaster.noad.core.service.shizuku.ShizukuAvailabilityHolder
import com.toaster.noad.core.service.shizuku.ShizukuCapabilityHolder
import com.toaster.noad.core.service.shizuku.ShizukuCapabilityProbe
import com.toaster.noad.core.service.shizuku.ShizukuShellClient
import com.toaster.noad.core.service.shizuku.ShizukuState
import com.toaster.noad.core.repository.S1RuleCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

/**
 * 手动依赖容器。
 *
 * ## 为什么不用 DI 框架
 *
 * 项目当前为单模块、依赖图浅（无跨模块注入需求）。
 * 引入 Hilt/Koin 会带来额外注解处理、构建复杂度与学习成本，
 * 收益在当前规模下不明显。因此采用**手动容器**：
 *
 * - 所有依赖在此集中构造，生命周期与进程一致
 * - 全部 `lazy`，未被使用的依赖不会被创建（例如不打开日志页就不查库）
 * - 若后续模块增多需要拆分，再评估引入 DI 框架
 *
 * ## 线程安全
 *
 * `lazy` 默认使用 `SYNCHRONIZED` 模式，因此各属性是线程安全的一次性初始化。
 */
class AppContainer(private val context: Context) {

    val database: NoAdDatabase by lazy { NoAdDatabase.getInstance(context) }

    val installedAppDataSource: InstalledAppDataSource by lazy {
        InstalledAppDataSource(context)
    }

    val ruleRepository: RuleRepository by lazy {
        RuleRepository(database.skipRuleDao())
    }

    val domainRuleRepository: DomainRuleRepository by lazy {
        DomainRuleRepository(database.domainRuleDao())
    }

    val logRepository: LogRepository by lazy {
        LogRepository(database.interceptLogDao())
    }

    val targetAppRepository: TargetAppRepository by lazy {
        TargetAppRepository(
            dao = database.targetAppDao(),
            installedAppDataSource = installedAppDataSource,
        )
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(context)
    }

    /**
     * Shizuku UserService 传输层（F2）。
     * 独立成容器项：状态机（探测）与执行器（解除）共享同一个连接。
     */
    val shizukuShellClient: ShizukuShellClient by lazy {
        ShizukuShellClient(context)
    }

    /**
     * Shizuku 可用性状态机（F1，方案 §6.3.3 / §6.3.4）。
     *
     * 事件由 NoAdApplication 在 onCreate 注册的三个监听驱动；
     * 刷新作用域用 [applicationScope] —— 状态的存活期应等于进程，
     * 与任何界面无关。
     */
    val shizukuAvailabilityHolder: ShizukuAvailabilityHolder by lazy {
        ShizukuAvailabilityHolder(
            context = context,
            scope = applicationScope,
            shell = shizukuShellClient,
        )
    }

    /** 侧载「受限设置」解除执行器（F2，方案 §6.5.2） */
    val restrictedSettingsFixer: RestrictedSettingsFixer by lazy {
        RestrictedSettingsFixer(shell = shizukuShellClient)
    }

    /**
     * 侧载限制解除的 feature 层门面（F2，方案 §6.5.3 + §3 分层约束）。
     * feature 只消费本类，不接触 core/shizuku 的类型。
     */
    val sideloadRestrictionController: SideloadRestrictionController by lazy {
        SideloadRestrictionController(
            context = context,
            shizukuHolder = shizukuAvailabilityHolder,
            fixer = restrictedSettingsFixer,
            scope = applicationScope,
        )
    }

    /**
     * 无障碍授权恢复门面（R12，R13 升级双通道）。
     *
     * ROM「一键清理」按 force-stop 语义撤销无障碍授权后，
     * 用户点一下即可恢复授权记录（read-merge-write 保护他人条目），
     * 不必再跑系统设置。通道优先级：adb 高级授权（WRITE_SECURE_SETTINGS，
     * 一次授权终身有效）> Shizuku（R12）> 手动引导。
     * 与受限解除（F2）是两个关注点，各自独立成门面。
     */
    val accessibilityRecoveryController: AccessibilityRecoveryController by lazy {
        AccessibilityRecoveryController(
            context = context,
            shizukuHolder = shizukuAvailabilityHolder,
            restorer = AccessibilityAuthorizationRestorer(shell = shizukuShellClient),
            secureSettingsRestorer = SecureSettingsAccessibilityRestorer(context.contentResolver),
            hasSecureWritePermission = {
                context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                    PackageManager.PERMISSION_GRANTED
            },
        )
    }

    /**
     * 无障碍授权自动恢复器（R14）：启动/回前台自检时静默恢复丢失的授权。
     *
     * 只绑 WRITE_SECURE_SETTINGS 通道（不自动走 Shizuku，理由见其 KDoc），
     * 节流策略为进程内实例 —— 进程重启即清零，重新打开应用可立即再试一次。
     */
    val accessibilityAutoRestorer: AccessibilityAutoRestorer by lazy {
        AccessibilityAutoRestorer(
            policy = AutoRestorePolicy(),
            hasSecureWritePermission =
                accessibilityRecoveryController::canRestoreWithoutShizuku,
            secureRestore = accessibilityRecoveryController::restoreViaSecureChannel,
        )
    }

    /**
     * Shizuku 逐项能力探测发布者（F1；F3–F7 的统一能力闸门）。
     *
     * 逐项、运行时、可失败：某台设备缺 Chain-3 时只让「应用级断网」
     * 降级，不影响 Private DNS / 包管理 / AppOps / 强停。
     */
    val shizukuCapabilityHolder: ShizukuCapabilityHolder by lazy {
        ShizukuCapabilityHolder(
            probe = ShizukuCapabilityProbe(
                exec = shizukuShellClient::exec,
                isReady = ::isShizukuReady,
                selfPackageName = context.packageName,
            ),
            scope = applicationScope,
        )
    }

    /** S4 应用级断网（F3，Chain-3） */
    val appFirewallController: AppFirewallController by lazy {
        AppFirewallController(
            exec = shizukuShellClient::exec,
            isReady = ::isShizukuReady,
            selfPackageName = context.packageName,
        )
    }

    /** Private DNS 改写（F4；不预判就绪 —— 通道不可用时其自身返回 ChannelUnavailable） */
    val privateDnsController: PrivateDnsController by lazy {
        PrivateDnsController(exec = shizukuShellClient::exec)
    }

    /** 应用 / 组件停用（F5；门面内部强制拒绝系统应用） */
    val packageController: PackageController by lazy {
        PackageController(exec = shizukuShellClient::exec, isReady = ::isShizukuReady)
    }

    /** AppOps 精细化控制（F6；全字符串操作名，绝无数值 opCode） */
    val appOpsController: AppOpsController by lazy {
        AppOpsController(exec = shizukuShellClient::exec, isReady = ::isShizukuReady)
    }

    /** 强制停止（F7；内建按包冷却，防破坏性重复触发） */
    val processController: ProcessController by lazy {
        ProcessController(exec = shizukuShellClient::exec, isReady = ::isShizukuReady)
    }

    /** Shizuku 是否已就绪（Ready = binder 存活 + 已授权 + 探测完成） */
    private fun isShizukuReady(): Boolean =
        shizukuAvailabilityHolder.state.value is ShizukuState.Ready

    /**
     * VPN 让位仲裁（阶段 C）：任何 VPN 动作前的唯一决策入口。
     *
     * `canControlPerAppNetwork` 接**真实能力**（Chain-3 探测结果）：
     * 满足时「让位 → 降级为应用级断网」，否则让位即失效（方案 §6.9）。
     */
    val vpnArbitrator: VpnArbitrator by lazy {
        VpnArbitrator(
            isOtherVpnActive = { detectOtherVpnActive() },
            canControlPerAppNetwork = {
                shizukuCapabilityHolder.capabilities.value.canControlPerAppNetwork
            },
        )
    }

    /**
     * 「检测到其他 VPN」的判定绝不能把自己算进去，否则 S2 运行期间
     * 每次仲裁都会误判让位。运行事实由 [VpnStateHolder.running] 单一
     * 持有（服务维护），此处只读 —— 系统 VPN 单实例，自己在跑时
     * 其他 VPN 必然不存在，直接短路。
     */
    private fun detectOtherVpnActive(): Boolean {
        if (com.toaster.noad.core.vpn.VpnStateHolder.running.value) return false
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return hasTunInterfaceFallback()
        return runCatching {
            cm.allNetworks.any { network ->
                cm.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }.getOrDefault(hasTunInterfaceFallback())
    }

    private fun hasTunInterfaceFallback(): Boolean = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching false
        Collections.list(interfaces).any {
            it.name.startsWith("tun") || it.name.startsWith("ppp")
        }
    }.getOrDefault(false)

    /**
     * S1 规则内存缓存（进程内单例）。
     *
     * 必须由容器持有而非在服务内构造：无障碍服务可能被系统
     * 反复连接/解绑，每次都新建缓存会导致
     * 「订阅 Flow 重建快照」的开销重复发生，且两个实例间状态不一致。
     *
     * 用 [applicationScope] 作为订阅作用域：缓存的存活周期
     * 应等于进程生命周期，而非某个服务的连接周期。
     */
    val s1RuleCache: S1RuleCache by lazy {
        S1RuleCache(
            targetAppRepository = targetAppRepository,
            ruleRepository = ruleRepository,
            scope = applicationScope,
        )
    }

    /**
     * 应用级协程作用域。
     *
     * 供 [s1RuleCache] 等需要跨服务生命周期的组件订阅数据流。
     */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

/**
 * NoAd 应用入口。
 *
 * 职责：
 * 1. 持有全局依赖容器 [container]
 * 2. 执行应用级一次性初始化（编译域名引擎、清理失效数据、导入内置规则）
 *
 * 注意：初始化任务放在 [applicationScope] 中异步执行，**不阻塞冷启动**。
 * 冷启动路径上任何磁盘 IO 都会直接影响用户感知的启动耗时。
 */
class NoAdApplication : Application() {

    /**
     * 应用级协程作用域。
     *
     * 使用 [SupervisorJob]：单个子任务失败不会取消整个作用域，
     * 避免一个初始化任务异常导致其余初始化全部中断。
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // 立即同步保护开关到内存镜像。
        // 必须在初始化任务之前：无障碍服务可能在应用进程刚启动时
        // 就被系统唤起（例如开机后用户直接打开某个应用），
        // 此时若镜像尚未同步，事件回调会因读到默认 false 而静默失效。
        ProtectionFlags.syncFrom(
            scope = applicationScope,
            protectionEnabledFlow = container.settingsRepository.protectionEnabled,
            accessibilityEnabledFlow = container.settingsRepository.accessibilityEnabled,
        )

        // 同步应用内 S1 开关到 UI 状态持有者。
        // 与 ProtectionFlags 分开的原因：前者服务于无障碍事件热路径（无锁布尔），
        // 后者服务于 UI（StateFlow，需要携带完整状态）。
        container.settingsRepository.accessibilityEnabled
            .onEach(AccessibilityStateHolder::setAppSwitchEnabled)
            .launchIn(applicationScope)

        // 后台保活的单一接线点：设置变化 → 启停前台服务 + 同步内存镜像。
        //
        // 放在 Application（而非 ViewModel）的原因：
        // - 保活的存活周期等于进程，与任何界面无关
        // - 心跳闹钟 / 开机接收器拉起进程时，UI 根本不存在，
        //   只有这里的观察者能在进程启动后立即恢复服务
        // - ViewModel 只负责写设置（单一数据流方向，避免双写竞态）
        //
        // 首次发射还承担「进程被无障碍重连拉起后恢复保活」的职责；
        // 若此刻应用在后台，startForegroundService 可能被系统拒绝 ——
        // ensureStarted 内部吞掉该异常，链条由心跳闹钟兜底。
        container.settingsRepository.keepAliveEnabled
            .onEach { enabled ->
                KeepAliveRuntime.update(enabled)
                if (enabled) {
                    KeepAliveService.ensureStarted(this)
                } else {
                    KeepAliveService.ensureStopped(this)
                }
            }
            .launchIn(applicationScope)

        // 启动无障碍服务的自愈监听。
        //
        // 必须在 onCreate 中同步启动（不能塞进下面的异步初始化块）：
        // 息屏/解锁广播可能在初始化任务跑完之前就到达，
        // 晚注册会漏掉那一次恢复时机。
        //
        // 注意 start() 内部只做注册（廉价），真正的状态核对在协程里异步执行，
        // 因此不会拖慢冷启动。
        accessibilityWatchdog.start()

        // Shizuku 绑定生命周期接线（F1，方案 §6.3.3）。
        //
        // - enableMultiProcessSupport(false)：单进程应用
        //   （与 manifest 中 provider 的 multiprocess="false" 对应）
        // - sticky 监听在注册时若 binder 已存活会**立即在当前线程回调**，
        //   因此回调体只做轻量的协程启动；pingBinder / 授权核对 / 能力探测
        //   全部在 IO 协程内完成，不拖慢冷启动主线程
        // - dead 监听是 §11.2「Shizuku 重启后失效」风险的应对：
        //   状态归位 → UI 自动回退手动引导，绝不静默假装可用
        // - 权限结果监听刻意不读 grantResult：由状态机重新核对真实权限，
        //   避免出现「监听说的已授权」与「实际权限」两套真相
        ShizukuProvider.enableMultiProcessSupport(false)
        container.shizukuAvailabilityHolder.let { holder ->
            Shizuku.addBinderReceivedListenerSticky(holder::onBinderReceived)
            Shizuku.addBinderDeadListener(holder::onBinderDead)
            Shizuku.addRequestPermissionResultListener { _, _ ->
                holder.onPermissionResult()
            }
        }

        applicationScope.launch {
            initializeDomainRules()
            initializeSkipRules()
            pruneUninstalledTargetApps()
            trimLogOverflow()
        }
    }

    /**
     * 无障碍自愈监听器。
     *
     * 用 `applicationScope` 作为刷新作用域：核对的存活周期应等于进程，
     * 而不是某个界面的生命周期。
     */
    private val accessibilityWatchdog by lazy {
        AccessibilityWatchdog(appContext = this, scope = applicationScope)
    }

    /**
     * 加载并编译域名规则到内存引擎。
     *
     * ## 导入策略
     *
     * 内置规则来自 assets，解析与写库都失败时**不阻塞启动**（外层 runCatching）。
     * 判定「需要导入」的条件同时检查黑名单与白名单：
     * 只检查黑名单会让「白名单丢失但黑名单存在」的中间态永远不被修复。
     *
     * ## 幂等性
     *
     * `dao.insertAll` 使用 `OnConflictStrategy.IGNORE` 且
     * `(pattern, is_whitelist)` 有唯一索引，因此重复启动不会产生重复规则。
     */
    private suspend fun initializeDomainRules() {
        runCatching {
            val repo = container.domainRuleRepository

            val blacklistMissing = !repo.hasBuiltinRules()
            val whitelistMissing = repo.builtinWhitelistCount() == 0

            if (blacklistMissing || whitelistMissing) {
                // 读取 + 解析都放在 IO 线程（本函数运行于 Dispatchers.IO）
                val loaded = BuiltinRulesLoader.load(this)
                if (loaded.totalCount > 0) {
                    repo.importBuiltin(
                        blacklist = loaded.blacklist,
                        whitelist = loaded.whitelist,
                    )
                }
            }

            repo.rebuildEngine()
        }
    }

    /**
     * 导入内置 S1 跳过规则。
     *
     * ## 为什么必须有这一步
     *
     * S1 引擎（`EventProcessor` → `S1RuleCache` → `UiMatcher`）在代码层面
     * 是完整的，但 `skip_rule` 表原本没有任何写入来源。结果是引擎的
     * 第一道闸门（按包名取规则）永远拿到空列表，**所有无障碍事件
     * 在第一步就被丢弃** —— 表现为"授权成功、应用已纳管，
     * 但既不跳过也不产生日志、统计恒为 0"。
     *
     * 本方法补上缺失的写入通道。
     *
     * ## 幂等性
     *
     * [RuleRepository.mergeBuiltin] 按业务键（包名 + 定位方式 + 定位值 +
     * Activity）合并，只插入缺失项，因此重复启动不会产生重复规则；
     * 同时它**不会覆盖**用户对内置规则的停用或删除操作，
     * 避免每次启动都把用户改过的状态抹掉。
     *
     * ## 与域名规则导入的差异
     *
     * 域名规则表有 `(pattern, is_whitelist)` 唯一索引，可以交给数据库
     * 用 `INSERT OR IGNORE` 兜底查重；`skip_rule` 没有唯一索引
     * （同一应用的多条规则本就可以指向同一个定位值，只是优先级不同），
     * 因此查重必须在 Repository 层显式完成。
     */
    private suspend fun initializeSkipRules() {
        runCatching {
            val repo = container.ruleRepository
            if (!repo.needsBuiltinImport()) return@runCatching

            val loaded = BuiltinSkipRulesLoader.load(this)
            if (loaded.totalCount == 0) return@runCatching

            repo.mergeBuiltin(loaded.rules)
        }
    }

    /** 清理已卸载应用残留的纳管记录 */
    private suspend fun pruneUninstalledTargetApps() {
        runCatching { container.targetAppRepository.pruneUninstalled() }
    }

    /** 裁剪日志至容量上限，避免表无限增长 */
    private suspend fun trimLogOverflow() {
        runCatching {
            val settings = container.settingsRepository.settings
            // 此处只读取一次快照，避免长驻订阅
            val capacity = SettingsRepository.DEFAULT_LOG_CAPACITY
            container.logRepository.trim(capacity)
            container.logRepository.deleteOlderThanDays(
                SettingsRepository.DEFAULT_RETENTION_DAYS,
            )
            settings // 保留引用以便后续扩展为动态读取
        }
    }

    companion object {
        /**
         * 从任意 Context 获取依赖容器。
         *
         * 这是手写容器的常见做法；若容器在 [onCreate] 之外被访问会抛
         * [UninitializedPropertyAccessException] 而非返回 null，
         * 便于尽早暴露错误用法。
         */
        fun containerOf(context: Context): AppContainer =
            (context.applicationContext as NoAdApplication).container
    }
}

/**
 * 内置域名规则入口。
 *
 * ## 已定方案（2026-09-19）
 *
 * 规则**不再硬编码为 Kotlin 常量**，改为放在
 * `app/src/main/assets/rules/builtin_domains.json`，由 [BuiltinRulesLoader] 解析。
 *
 * 决策依据：
 *
 * 1. **规模**：起步集约 60 条黑名单 + 6 条白名单。刻意保持精简 ——
 *    内置规则的价值是「零误杀地覆盖高共识广告域」，而不是追求拦截率。
 *    大规模列表（数万条）应由用户按需在规则页导入，避免默认配置就产生误杀。
 * 2. **白名单必须与黑名单同批导入**：文件中登记的若干白名单条目是为了
 *    显式放行「有误杀风险」的域名（如友盟同时承载崩溃上报）。
 *    若只导入黑名单，这层防护会静默失效。
 * 3. **数据与代码分离**：规则会持续演进，放 assets 便于审阅、diff 与替换，
 *    且不随规则增长而膨胀 dex。
 *
 * 规则的选取标准与审计要求见 JSON 文件内的 `meta.criteria` / `meta.auditNote`。
 */
object BuiltinDomainRules {

    /** 供 UI 展示的规则来源标识 */
    const val SOURCE = DomainRuleEntity.SOURCE_BUILTIN
}
