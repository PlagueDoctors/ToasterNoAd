package com.toaster.noad.core.service.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PackageOps] 的 JVM 单测。
 *
 * 命令与解析格式全部来自实机（NX789J / Android 15）只读探测：
 * `pm list packages -d` → `package:<pkg>`；
 * `dumpsys package` → `User 0: ... enabled=0 ...` + `enabledComponents:` 段。
 */
class PackageOpsTest {

    @Test
    fun givenDisableApp_whenBuildCommand_thenUserScopedDisableUser() {
        assertEquals(
            listOf("pm", "disable-user", "--user", "0", "com.example.app"),
            PackageOps.disableAppCommand("com.example.app"),
        )
    }

    @Test
    fun givenEnableApp_whenBuildCommand_thenUserScopedEnable() {
        assertEquals(
            listOf("pm", "enable", "--user", "0", "com.example.app"),
            PackageOps.enableAppCommand("com.example.app"),
        )
    }

    @Test
    fun givenDisableComponent_whenBuildCommand_thenSlashForm() {
        assertEquals(
            listOf("pm", "disable", "com.example.app/.SplashAdActivity"),
            PackageOps.disableComponentCommand("com.example.app", ".SplashAdActivity"),
        )
    }

    @Test
    fun givenRealDeviceOutput_whenParseDisabledPackages_thenExtracted() {
        // 实机 `pm list packages -d` 的输出格式
        val output = """
            package:cn.nubia.mifavor.miboard
            package:com.android.devicelockcontroller
            package:cn.nubia.browser
        """.trimIndent()

        assertEquals(
            setOf("cn.nubia.mifavor.miboard", "com.android.devicelockcontroller", "cn.nubia.browser"),
            PackageOps.parseDisabledPackages(output),
        )
    }

    @Test
    fun givenNullOutput_whenParseDisabledPackages_thenEmpty() {
        assertTrue(PackageOps.parseDisabledPackages(null).isEmpty())
    }

    @Test
    fun givenEnabledZero_whenParseAppEnabled_thenTrue() {
        // 实机输出：enabled=0 表示 DEFAULT（跟随 manifest），属正常可用
        val output = "    User 0: ceDataInode=1 installed=true hidden=false enabled=0 instant=false"
        assertEquals(true, PackageOps.parseAppEnabled(output))
    }

    @Test
    fun givenEnabledTwo_whenParseAppEnabled_thenFalse() {
        val output = "    User 0: installed=true enabled=2 instant=false"
        assertEquals(false, PackageOps.parseAppEnabled(output))
    }

    @Test
    fun givenEnabledThree_whenParseAppEnabled_thenFalse() {
        val output = "    User 0: installed=true enabled=3 instant=false"
        assertEquals(false, PackageOps.parseAppEnabled(output))
    }

    @Test
    fun givenBothUserRows_whenParseAppEnabled_thenUserZeroWins() {
        // 实机输出同时含 User 0 与 User 999（工作资料）—— 必须取 User 0
        val output = """
                User 0: installed=true hidden=false enabled=0 instant=false
                User 999: installed=false hidden=false enabled=2 instant=false
        """.trimIndent()
        assertEquals(true, PackageOps.parseAppEnabled(output))
    }

    @Test
    fun givenNoUserLine_whenParseAppEnabled_thenNull() {
        assertNull(PackageOps.parseAppEnabled("some unrelated output"))
        assertNull(PackageOps.parseAppEnabled(null))
    }

    @Test
    fun givenComponentInDump_whenParseComponentMentioned_thenTrue() {
        val dump = """
            User 0: installed=true enabled=0
              enabledComponents:
                com.example.app/.SplashAdActivity: 2
        """.trimIndent()
        assertTrue(
            PackageOps.parseComponentMentioned(dump, "com.example.app", ".SplashAdActivity"),
        )
    }

    @Test
    fun givenComponentAbsent_whenParseComponentMentioned_thenFalse() {
        val dump = "User 0: installed=true enabled=0\n  enabledComponents:"
        assertFalse(
            PackageOps.parseComponentMentioned(dump, "com.example.app", ".SplashAdActivity"),
        )
    }

    @Test
    fun givenSystemApp_whenCheckAllowed_thenForbidden() {
        // 硬约束：系统应用停用可能导致系统功能异常（方案 §6.6.3）
        assertFalse(PackageOps.isOperationAllowed(isSystemApp = true))
        assertTrue(PackageOps.isOperationAllowed(isSystemApp = false))
    }
}
