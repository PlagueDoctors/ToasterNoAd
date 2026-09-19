package com.toaster.noad.core.service

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「受限设置解除覆盖」测试（R11 / 阶段 F2）。
 *
 * ## 测的是什么
 *
 * [AccessibilityStateHolder.markRestrictedSettingCleared] 是 F2 解除
 * 成功后撤下受限提示的唯一入口。受限提示一旦误显示/误残留，
 * 用户就会以为解除失败而放弃 —— 这条判定必须钉死。
 *
 * ## 测试边界（诚实标注）
 *
 * `refreshFromSystemSettings` 依赖 `ContentResolver`（无 Robolectric
 * 拿不到），因此「cleared 标志参与 refresh 判定」的路径本类**不覆盖**，
 * 由用户实机验证；这里只覆盖标志自身的置位/撤显/不被 resurrect 行为。
 */
class AccessibilityStateHolderRestrictedTest {

    @Before
    fun setUp() {
        // resetForTest 会把时钟还原为 SystemClock，
        // 因此注入必须在 reset 之后（onDisconnected 会读时钟）
        AccessibilityStateHolder.resetForTest()
        AccessibilityStateHolder.clock = { 1_000L }
    }

    @After
    fun tearDown() {
        // holder 是全局单例，测试之间必须复位，否则互相污染
        AccessibilityStateHolder.resetForTest()
    }

    @Test
    fun givenRestrictedShown_whenMarkCleared_thenHintRemoved() {
        AccessibilityStateHolder.seedRestrictedFlagForTest(true)
        assertTrue(
            "前置：受限提示应处于展示态",
            AccessibilityStateHolder.state.value.restrictedBySideload,
        )

        AccessibilityStateHolder.markRestrictedSettingCleared()

        assertFalse(
            "解除成功后受限提示必须立即撤下",
            AccessibilityStateHolder.state.value.restrictedBySideload,
        )
    }

    @Test
    fun givenNotRestricted_whenMarkCleared_thenNoSideEffect() {
        assertFalse(AccessibilityStateHolder.state.value.restrictedBySideload)

        AccessibilityStateHolder.markRestrictedSettingCleared()

        assertFalse(
            "未展示受限提示时解除不应产生任何可见变化",
            AccessibilityStateHolder.state.value.restrictedBySideload,
        )
    }

    @Test
    fun givenCleared_whenOtherStateUpdates_thenHintNotResurrected() {
        AccessibilityStateHolder.seedRestrictedFlagForTest(true)
        AccessibilityStateHolder.markRestrictedSettingCleared()

        // 解除后其余状态更新走 copy：任何字段变更都不得把受限提示带回来
        AccessibilityStateHolder.setAppSwitchEnabled(true)
        AccessibilityStateHolder.onDisconnected()

        assertFalse(
            "受限提示一旦撤下，不应被其他状态更新复活",
            AccessibilityStateHolder.state.value.restrictedBySideload,
        )
    }
}
