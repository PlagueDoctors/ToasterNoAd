package com.toaster.noad.core.database

import com.toaster.noad.core.database.entity.DomainRuleEntity
import com.toaster.noad.core.database.entity.InterceptLogEntity
import com.toaster.noad.core.database.entity.SkipRuleEntity
import com.toaster.noad.core.database.entity.TargetAppEntity
import com.toaster.noad.core.model.AdType
import com.toaster.noad.core.model.DomainCategory
import com.toaster.noad.core.model.DomainMatchType
import com.toaster.noad.core.model.DomainRule
import com.toaster.noad.core.model.InterceptLog
import com.toaster.noad.core.model.InterceptSource
import com.toaster.noad.core.model.MatchMode
import com.toaster.noad.core.model.SkipRule
import com.toaster.noad.core.model.TargetApp
import com.toaster.noad.core.model.TargetType

/**
 * 数据库实体 ↔ 领域模型 的映射。
 *
 * 设计意图：让领域层不依赖 Room 注解。
 * Repository 层负责在两个方向调用这些映射函数，UI 与引擎只接触领域模型。
 */

// ============ SkipRule ============

fun SkipRuleEntity.toDomain(): SkipRule = SkipRule(
    id = id,
    name = name,
    packageName = packageName,
    activityName = activityName,
    targetType = runCatching { TargetType.valueOf(targetType) }.getOrDefault(TargetType.TEXT),
    targetValue = targetValue,
    matchMode = runCatching { MatchMode.valueOf(matchMode) }.getOrDefault(MatchMode.EXACT),
    clickDelayMs = clickDelayMs,
    enabled = enabled,
    priority = priority,
)

fun SkipRule.toEntity(now: Long = System.currentTimeMillis()): SkipRuleEntity = SkipRuleEntity(
    id = id,
    name = name,
    packageName = packageName,
    activityName = activityName,
    targetType = targetType.name,
    targetValue = targetValue,
    matchMode = matchMode.name,
    clickDelayMs = clickDelayMs,
    enabled = enabled,
    priority = priority,
    updatedAt = now,
)

// ============ DomainRule ============

fun DomainRuleEntity.toDomain(): DomainRule = DomainRule(
    id = id,
    matchType = runCatching { DomainMatchType.valueOf(matchType) }
        .getOrDefault(DomainMatchType.SUFFIX),
    pattern = pattern,
    category = runCatching { DomainCategory.valueOf(category) }
        .getOrDefault(DomainCategory.AD),
    note = note,
    enabled = enabled,
)

fun DomainRule.toEntity(isWhitelist: Boolean, source: String): DomainRuleEntity =
    DomainRuleEntity(
        id = id,
        matchType = matchType.name,
        pattern = pattern,
        category = category.name,
        isWhitelist = isWhitelist,
        note = note,
        enabled = enabled,
        source = source,
    )

// ============ InterceptLog ============

fun InterceptLogEntity.toDomain(): InterceptLog = InterceptLog(
    id = id,
    packageName = packageName,
    appLabel = appLabel,
    adType = runCatching { AdType.valueOf(adType) }.getOrDefault(AdType.OTHER),
    source = runCatching { InterceptSource.valueOf(source) }
        .getOrDefault(InterceptSource.ACCESSIBILITY),
    ruleDetail = ruleDetail,
    timestamp = timestamp,
)

fun InterceptLog.toEntity(): InterceptLogEntity = InterceptLogEntity(
    id = id,
    packageName = packageName,
    appLabel = appLabel,
    adType = adType.name,
    source = source.name,
    ruleDetail = ruleDetail,
    timestamp = timestamp,
)

// ============ TargetApp ============

fun TargetAppEntity.toDomain(): TargetApp = TargetApp(
    packageName = packageName,
    label = label,
    accessibilityEnabled = accessibilityEnabled,
    networkFilterEnabled = networkFilterEnabled,
    appFirewallEnabled = appFirewallEnabled,
    sortOrder = sortOrder,
)

fun TargetApp.toEntity(now: Long = System.currentTimeMillis()): TargetAppEntity =
    TargetAppEntity(
        packageName = packageName,
        label = label,
        accessibilityEnabled = accessibilityEnabled,
        networkFilterEnabled = networkFilterEnabled,
        appFirewallEnabled = appFirewallEnabled,
        sortOrder = sortOrder,
        updatedAt = now,
    )
