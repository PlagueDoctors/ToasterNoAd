package com.toaster.noad.core.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * 无障碍服务设置跳转助手。
 *
 * ## 为什么不能直接跳到本服务的开关
 *
 * Android **没有**提供"打开某个无障碍服务开关"的公开 API。
 * 唯一可行的是跳到无障碍设置列表，由用户手动找到并开启。
 *
 * 不同 ROM 对该列表的入口 Activity 不一致（部分厂商改过类名），
 * 因此采用**多级降级**：
 *
 * 1. `ACTION_ACCESSIBILITY_SETTINGS`（标准入口，绝大多数设备可用）
 * 2. `ACTION_SETTINGS`（兜底，跳到设置首页由用户自行寻找）
 *
 * 直接 `startActivity` 时必须捕获 `ActivityNotFoundException` ——
 * 某些精简 ROM 会移除无障碍设置入口，
 * 未捕获会导致点击"去开启"时直接崩溃。
 */
object AccessibilitySettingsLauncher {

    /**
     * 打开系统无障碍设置页。
     *
     * @return true 表示成功跳转；false 表示所有入口都不可用，
     *         调用方应提示用户手动前往设置
     */
    fun openAccessibilitySettings(context: Context): Boolean {
        // 优先级 1：标准无障碍设置
        if (tryStart(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) {
            return true
        }

        // 优先级 2：应用详情页（用户可从"权限"路径找到无障碍）
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", context.packageName, null)
        }
        if (tryStart(context, appDetails)) {
            return true
        }

        // 优先级 3：设置首页
        return tryStart(context, Intent(Settings.ACTION_SETTINGS))
    }

    /**
     * 打开系统「应用详情」页，用于引导用户解除受限设置。
     *
     * Android 13+ 的「允许受限设置」入口位于应用详情页的右上角菜单中，
     * 因此这里跳应用详情页而非无障碍设置页。
     */
    fun openAppDetails(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", context.packageName, null)
        }
        return tryStart(context, intent)
    }

    private fun tryStart(context: Context, intent: Intent): Boolean {
        // 从非 Activity 上下文启动必须加 FLAG_ACTIVITY_NEW_TASK，
        // 否则会抛 AndroidRuntimeException。此处统一添加，
        // 因为调用方可能是 Activity 也可能是 Application。
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(intent)
            true
        } catch (e: android.content.ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            // 部分 ROM 对 settings 入口做了权限限制
            false
        }
    }

    /**
     * 本服务在系统设置中的完整组件名，供调试与日志展示。
     */
    fun serviceComponent(context: Context): ComponentName =
        ComponentName(context, NoAdAccessibilityService::class.java)
}
