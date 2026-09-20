package com.toaster.noad.core.vpn

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VpnStateHolder] 的 JVM 单测。
 *
 * holder 是进程内单例（object），各用例必须自带完整的
 * 置位/清零往返，不依赖执行顺序。
 */
class VpnStateHolderTest {

    @Test
    fun givenInitialState_whenObserved_thenNotRunning() = runBlocking {
        // 单例可能被前一个用例置位：先清零到基准态
        VpnStateHolder.onStopped()
        assertFalse(VpnStateHolder.running.first())
    }

    @Test
    fun givenEstablished_whenObserved_thenRunning() = runBlocking {
        VpnStateHolder.onEstablished()
        assertTrue(VpnStateHolder.running.first())
        VpnStateHolder.onStopped()
    }

    @Test
    fun givenEstablished_whenStopped_thenBackToNotRunning() = runBlocking {
        VpnStateHolder.onEstablished()
        VpnStateHolder.onStopped()
        assertFalse(VpnStateHolder.running.first())
    }

    @Test
    fun givenRepeatedStops_whenObserved_thenStillConsistent() = runBlocking {
        // 服务 closeTun 与 onDestroy 都会调用清零：幂等是显式契约
        VpnStateHolder.onStopped()
        VpnStateHolder.onStopped()
        assertFalse(VpnStateHolder.running.first())
    }
}
