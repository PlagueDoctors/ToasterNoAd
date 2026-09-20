package com.toaster.noad.core.service

import com.toaster.noad.core.service.shizuku.AccessibilityRestoreOps
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SecureSettingsAccessibilityRestorer] 测试（R13）。
 *
 * 用内存 map 假体替代 `ContentResolver`（android.jar 是空壳，JVM 无法
 * 构造真实 resolver），通过主构造的读写注入函数驱动完整恢复序列。
 * 序列决策本身由 [AccessibilityRestoreOps.restore] 的 31 个测试锁定，
 * 这里锁定的是**解释器适配**这一层：
 *
 * - shell 命令 → ContentResolver 读写的翻译是否正确（按序位断言）；
 * - `getString` 返回 null（键不存在）被映射为 `"null"` 字面量 ——
 *   决策层对「空设置」与「首次恢复」必须走同一条路径，不能误判失败；
 * - 真实 IO 失败（SecurityException）落 `Failed`，且**绝不写入任何值**。
 *
 * 诚实边界：`Settings.Secure` 的真实读写与权限校验属 Android 平台侧，
 * 由用户实机验证；此处只锁定翻译层与失败传导。
 */
class SecureSettingsAccessibilityRestorerTest {

    private companion object {
        val FLAT = "com.toaster.noad/com.toaster.noad.core.service.NoAdAccessibilityService"
        const val ENABLED_KEY = "enabled_accessibility_services"
        const val MASTER_KEY = "accessibility_enabled"
    }

    /**
     * 内存版 Settings：记录全部读写（含顺序），可注入失败与异常。
     *
     * read 与 shell 的 `settings get` 对齐：键不存在返回 null。
     */
    private class FakeSettings {
        val map = mutableMapOf<String, String>()
        val reads = mutableListOf<String>()
        val writes = mutableListOf<Pair<String, String>>()
        var failWrites = false
        var throwSecurityOnRead = false
        var throwSecurityOnWrite = false

        fun read(key: String): String? {
            reads += key
            if (throwSecurityOnRead) throw SecurityException("WRITE_SECURE_SETTINGS revoked")
            return map[key]
        }

        fun write(key: String, value: String): Boolean {
            writes += key to value
            if (throwSecurityOnWrite) throw SecurityException("write rejected")
            if (failWrites) return false
            map[key] = value
            return true
        }
    }

    private fun restorer(settings: FakeSettings) = SecureSettingsAccessibilityRestorer(
        readSetting = settings::read,
        writeSetting = settings::write,
    )

    // ---------- 正常路径 ----------

    @Test
    fun givenEmptySettings_whenRestore_thenRestoredWithFlatAndMaster() = runBlocking {
        val settings = FakeSettings()

        val outcome = restorer(settings).restore(FLAT)

        assertEquals(AccessibilityRestoreOpsName.RESTORED, outcomeName(outcome))
        assertEquals(FLAT, settings.map[ENABLED_KEY])
        assertEquals("1", settings.map[MASTER_KEY])
        // 序位断言（R12 教训）：先写服务条目，再写总开关
        assertEquals(ENABLED_KEY, settings.writes[0].first)
        assertEquals(MASTER_KEY, settings.writes[1].first)
    }

    @Test
    fun givenOtherServicesOnly_whenRestore_thenOthersPreservedAndFlatAppended() = runBlocking {
        val settings = FakeSettings().apply { map[ENABLED_KEY] = "com.other/.Svc:com.tool/.T" }

        val outcome = restorer(settings).restore(FLAT)

        assertEquals(AccessibilityRestoreOpsName.RESTORED, outcomeName(outcome))
        assertEquals("com.other/.Svc:com.tool/.T:$FLAT", settings.map[ENABLED_KEY])
    }

    @Test
    fun givenSelfAlreadyPresent_whenRestore_thenAlreadyPresentAndOnlyMasterWritten() =
        runBlocking {
            val settings = FakeSettings().apply { map[ENABLED_KEY] = ":$FLAT" }

            val outcome = restorer(settings).restore(FLAT)

            assertEquals(AccessibilityRestoreOpsName.ALREADY_PRESENT, outcomeName(outcome))
            // 序列只写总开关；服务条目保持原样（含前导分隔符）
            assertEquals(1, settings.writes.size)
            assertEquals(MASTER_KEY, settings.writes[0].first)
            assertEquals(":$FLAT", settings.map[ENABLED_KEY])
        }

    @Test
    fun givenCaseVariantPresent_whenRestore_thenEntryUntouched() = runBlocking {
        // 大小写变体 = 已在列表（containsService 大小写不敏感）；
        // 序列 therefore 不写服务条目，原书写形态原样保留（R12 零改写契约）
        val settings = FakeSettings().apply { map[ENABLED_KEY] = FLAT.uppercase() }

        val outcome = restorer(settings).restore(FLAT)

        assertEquals(AccessibilityRestoreOpsName.ALREADY_PRESENT, outcomeName(outcome))
        assertEquals(FLAT.uppercase(), settings.map[ENABLED_KEY])
        assertEquals(MASTER_KEY, settings.writes.single().first)
    }

    // ---------- 失败传导 ----------

    @Test
    fun givenSilentWriteRejection_whenRestore_thenFailed() = runBlocking {
        val settings = FakeSettings().apply { failWrites = true }

        val outcome = restorer(settings).restore(FLAT)

        // put 声称成功但值未落盘 → 回读验证捕获 → Failed，不崩溃
        assertEquals(AccessibilityRestoreOpsName.FAILED, outcomeName(outcome))
    }

    @Test
    fun givenReadSecurityException_whenRestore_thenFailedAndNothingWritten() = runBlocking {
        val settings = FakeSettings().apply { throwSecurityOnRead = true }

        val outcome = restorer(settings).restore(FLAT)

        assertEquals(AccessibilityRestoreOpsName.FAILED, outcomeName(outcome))
        // 权限已失：一个字节都不能写（read-merge-write 的第一步就终止）
        assertTrue(settings.writes.isEmpty())
    }

    @Test
    fun givenWriteSecurityException_whenRestore_thenFailed() = runBlocking {
        val settings = FakeSettings().apply { throwSecurityOnWrite = true }

        val outcome = restorer(settings).restore(FLAT)

        assertEquals(AccessibilityRestoreOpsName.FAILED, outcomeName(outcome))
    }

    // ---------- 翻译层 ----------

    @Test
    fun givenExecUnavailableOnFirstRead_whenRestore_thenFailsWithoutAnyWrite() = runBlocking {
        // 解释器对未知/非法命令形态返回 null（等同通道不可用）：
        // 序列必须在第一次 read 就终止，绝不带着坏输入继续写。
        // 这是解释器「未知命令 → null」防御的下游行为契约。
        val executed = mutableListOf<List<String>>()
        val outcome = AccessibilityRestoreOps.restore(
            exec = { command ->
                executed += command
                null
            },
            flat = FLAT,
        )

        assertEquals(AccessibilityRestoreOpsName.FAILED, outcomeName(outcome))
        // 只有第一次 read 被发出；write 命令从未进入序列
        assertEquals(1, executed.size)
        assertEquals(4, executed[0].size)
    }

    /** 便于按名断言终态（sealed 层级只在本包可见） */
    private object AccessibilityRestoreOpsName {
        const val RESTORED = "Restored"
        const val ALREADY_PRESENT = "AlreadyPresent"
        const val FAILED = "Failed"
    }

    private fun outcomeName(outcome: AccessibilityRestoreOps.RestoreOutcome): String =
        when (outcome) {
            is AccessibilityRestoreOps.RestoreOutcome.Restored -> "Restored"
            is AccessibilityRestoreOps.RestoreOutcome.AlreadyPresent -> "AlreadyPresent"
            is AccessibilityRestoreOps.RestoreOutcome.Failed -> "Failed"
        }
}
