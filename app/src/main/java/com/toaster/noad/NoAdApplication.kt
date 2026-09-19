package com.toaster.noad

import android.app.Application
import android.content.Context
import com.toaster.noad.core.applist.InstalledAppDataSource
import com.toaster.noad.core.data.repository.DomainRuleRepository
import com.toaster.noad.core.data.repository.LogRepository
import com.toaster.noad.core.data.repository.RuleRepository
import com.toaster.noad.core.data.repository.TargetAppRepository
import com.toaster.noad.core.data.settings.SettingsRepository
import com.toaster.noad.core.database.NoAdDatabase
import com.toaster.noad.core.database.entity.DomainRuleEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

        applicationScope.launch {
            initializeDomainRules()
            pruneUninstalledTargetApps()
            trimLogOverflow()
        }
    }

    /** 加载并编译域名规则到内存引擎；首次启动时导入内置规则 */
    private suspend fun initializeDomainRules() {
        runCatching {
            val repo = container.domainRuleRepository
            if (!repo.hasBuiltinRules()) {
                repo.importBuiltin(BuiltinDomainRules.rules)
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
 * 内置域名规则占位。
 *
 * ## 为什么是占位而非大规模列表
 *
 * 内置规则规模属于待决策项（见 `THREE_STRATEGY_PLAN.md` Q3）。
 * 大规模导入会带来误杀风险与维护成本，需要先用实测数据支撑。
 *
 * 当前只放最小可用集合：仅覆盖业界公认的、几乎不可能误杀的广告与追踪域名，
 * 目的是**让 S2/S3 的过滤链路可端到端验证**，而非追求拦截率。
 *
 * 后续应替换为经过筛选的公开列表（如 AdAway hosts 源转换），
 * 并配合白名单机制控制误杀。
 */
object BuiltinDomainRules {

    val rules: List<com.toaster.noad.core.model.DomainRule> = listOf(
        // 占位示例：这些是广为人知的广告/追踪域名，仅用于验证过滤链路连通性。
        // 注意：不是完整列表，不应作为实际拦截能力的依据。
        rule("doubleclick.net"),
        rule("googlesyndication.com"),
        rule("googleadservices.com"),
        rule("adservice.google.com"),
    )

    private fun rule(pattern: String) = com.toaster.noad.core.model.DomainRule(
        matchType = com.toaster.noad.core.model.DomainMatchType.SUFFIX,
        pattern = pattern,
        category = com.toaster.noad.core.model.DomainCategory.AD,
        note = "builtin",
        enabled = true,
    )

    /** 供 UI 展示的规则来源标识 */
    const val SOURCE = DomainRuleEntity.SOURCE_BUILTIN
}
