package com.toaster.noad

import android.app.Application
import android.content.Context
import com.toaster.noad.core.applist.InstalledAppDataSource
import com.toaster.noad.core.data.repository.DomainRuleRepository
import com.toaster.noad.core.data.repository.LogRepository
import com.toaster.noad.core.data.repository.RuleRepository
import com.toaster.noad.core.data.repository.TargetAppRepository
import com.toaster.noad.core.data.rules.BuiltinRulesLoader
import com.toaster.noad.core.data.settings.SettingsRepository
import com.toaster.noad.core.database.NoAdDatabase
import com.toaster.noad.core.database.entity.DomainRuleEntity
import com.toaster.noad.core.service.AccessibilityStateHolder
import com.toaster.noad.core.service.ProtectionFlags
import com.toaster.noad.core.repository.S1RuleCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

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

        applicationScope.launch {
            initializeDomainRules()
            pruneUninstalledTargetApps()
            trimLogOverflow()
        }
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
