package com.toaster.noad.core.navigation

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.toaster.noad.NoAdApplication
import com.toaster.noad.feature.apps.AppsViewModel
import com.toaster.noad.feature.home.HomeViewModel
import com.toaster.noad.feature.logs.LogsViewModel
import com.toaster.noad.feature.network.NetworkViewModel
import com.toaster.noad.feature.rules.RulesViewModel
import com.toaster.noad.feature.settings.SettingsViewModel

/**
 * 全局 ViewModel 工厂。
 *
 * ## 为什么需要它
 *
 * 各 ViewModel 现在依赖 Repository，而 Repository 由 [NoAdApplication] 持有。
 * Compose 的 `viewModel()` 默认无法注入参数，因此需要显式提供工厂。
 *
 * ## 与 DI 框架的对比
 *
 * 手写工厂在此规模下更直观，且不引入注解处理。
 * 缺点是需要随 ViewModel 数量手工维护 —— 当 ViewModel 超过约 15 个时，
 * 应评估迁移到 DI 框架。
 */
object NoAdViewModelFactory {

    val Factory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            val container = applicationContainer()
            HomeViewModel(
                application = application(),
                targetAppRepository = container.targetAppRepository,
                logRepository = container.logRepository,
                settingsRepository = container.settingsRepository,
                sideloadRestrictionController = container.sideloadRestrictionController,
                accessibilityRecoveryController = container.accessibilityRecoveryController,
            )
        }
        initializer {
            val container = applicationContainer()
            AppsViewModel(
                targetAppRepository = container.targetAppRepository,
            )
        }
        initializer {
            LogsViewModel(logRepository = applicationContainer().logRepository)
        }
        initializer {
            val container = applicationContainer()
            SettingsViewModel(
                settingsRepository = container.settingsRepository,
                domainRuleRepository = container.domainRuleRepository,
                ruleRepository = container.ruleRepository,
            )
        }
        initializer {
            val container = applicationContainer()
            NetworkViewModel(
                settingsRepository = container.settingsRepository,
                domainRuleRepository = container.domainRuleRepository,
            )
        }
        initializer {
            val container = applicationContainer()
            RulesViewModel(
                ruleRepository = container.ruleRepository,
                targetAppRepository = container.targetAppRepository,
            )
        }
    }
}

/**
 * 从 [CreationExtras] 取出 Application。
 */
private fun CreationExtras.application(): NoAdApplication =
    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as NoAdApplication

/**
 * 从 [CreationExtras] 取出 Application 并返回依赖容器。
 */
private fun CreationExtras.applicationContainer() = application().container
