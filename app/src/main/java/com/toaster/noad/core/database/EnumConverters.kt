package com.toaster.noad.core.database

import androidx.room.TypeConverter
import com.toaster.noad.core.model.AdType
import com.toaster.noad.core.model.DomainCategory
import com.toaster.noad.core.model.DomainMatchType
import com.toaster.noad.core.model.InterceptSource
import com.toaster.noad.core.model.MatchMode
import com.toaster.noad.core.model.NetworkFilterMode
import com.toaster.noad.core.model.TargetType

/**
 * 枚举 ↔ 字符串转换。
 *
 * 设计取舍：**存字符串而非序号**。
 * 理由：序号会随枚举项增删而错位，导致老数据被错误解读；
 * 字符串在数据库里可读、可直接用 SQL 查询、且新增枚举项不影响存量数据。
 */
class EnumConverters {

    @TypeConverter
    fun targetTypeToString(value: TargetType): String = value.name

    @TypeConverter
    fun stringToTargetType(value: String): TargetType =
        runCatching { TargetType.valueOf(value) }.getOrDefault(TargetType.TEXT)

    @TypeConverter
    fun matchModeToString(value: MatchMode): String = value.name

    @TypeConverter
    fun stringToMatchMode(value: String): MatchMode =
        runCatching { MatchMode.valueOf(value) }.getOrDefault(MatchMode.EXACT)

    @TypeConverter
    fun adTypeToString(value: AdType): String = value.name

    @TypeConverter
    fun stringToAdType(value: String): AdType =
        runCatching { AdType.valueOf(value) }.getOrDefault(AdType.OTHER)

    @TypeConverter
    fun interceptSourceToString(value: InterceptSource): String = value.name

    @TypeConverter
    fun stringToInterceptSource(value: String): InterceptSource =
        runCatching { InterceptSource.valueOf(value) }.getOrDefault(InterceptSource.ACCESSIBILITY)

    @TypeConverter
    fun domainMatchTypeToString(value: DomainMatchType): String = value.name

    @TypeConverter
    fun stringToDomainMatchType(value: String): DomainMatchType =
        runCatching { DomainMatchType.valueOf(value) }.getOrDefault(DomainMatchType.SUFFIX)

    @TypeConverter
    fun domainCategoryToString(value: DomainCategory): String = value.name

    @TypeConverter
    fun stringToDomainCategory(value: String): DomainCategory =
        runCatching { DomainCategory.valueOf(value) }.getOrDefault(DomainCategory.AD)

    @TypeConverter
    fun networkFilterModeToString(value: NetworkFilterMode): String = value.name

    @TypeConverter
    fun stringToNetworkFilterMode(value: String): NetworkFilterMode =
        runCatching { NetworkFilterMode.valueOf(value) }.getOrDefault(NetworkFilterMode.OFF)
}
