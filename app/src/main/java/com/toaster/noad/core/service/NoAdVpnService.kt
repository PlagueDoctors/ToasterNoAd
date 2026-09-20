package com.toaster.noad.core.service

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.toaster.noad.AppContainer
import com.toaster.noad.NoAdApplication
import com.toaster.noad.core.model.NetworkFilterMode
import com.toaster.noad.core.model.VpnYieldReason
import com.toaster.noad.core.vpn.DnsForwarder
import com.toaster.noad.core.vpn.DnsInterceptor
import com.toaster.noad.core.vpn.DnsPacketParser
import com.toaster.noad.core.vpn.Ip4UdpPacket
import com.toaster.noad.core.vpn.VpnStartReason
import com.toaster.noad.core.vpn.VpnStateHolder
import com.toaster.noad.core.vpn.VpnTunConfig
import kotlinx.coroutines.launch
import java.net.InetAddress

/**
 * S2 DNS 模式的 VpnService（阶段 D 接通包处理循环）。
 *
 * ## 数据通路（DNS_ONLY / HYBRID）
 *
 * ```
 * TUN 读包 → Ip4UdpPacket 解析（只认 IPv4/UDP）
 *   → 非 UDP:53 丢弃（S2 路由下本就不该出现，防御性忽略）
 *   → DnsPacketParser 提取域名
 *   → DnsInterceptor：DomainRuleEngine 白名单优先判定
 *       ├─ Blocked → 本地构造 NXDOMAIN（改写查询字节）→ 写回 TUN
 *       └─ Forward → DnsForwarder（protect socket）转发真实上游
 *             ├─ 有响应 → 写回 TUN
 *             └─ 全部上游失败 → 本地构造 SERVFAIL 写回 TUN
 * ```
 *
 * 与决策层的分工：字节翻译在 [Ip4UdpPacket]，判定在 [DnsInterceptor]，
 * 转发在 [DnsForwarder] —— 三者全部可在 JVM 上测试；本类只负责
 * 生命周期、线程与 TUN 读写，不承载任何可测逻辑。
 *
 * ## S3（FULL_TRAFFIC）边界
 *
 * 全流量包循环属**阶段 E**。收到 FULL_TRAFFIC 启动请求时直接拒绝：
 * 若允许它 establish，系统 DNS 会指向无人应答的虚拟地址，
 * 全设备解析超时 —— 这比「拒绝启动」糟糕得多。
 *
 * ## 生命周期
 *
 * - `establish` 成功前读上游 DNS（S2 铁律，§4.3①）；
 * - 包循环在独立线程阻塞读 TUN，[onDestroy] 关闭 TUN 使 read 抛出
 *   从而自然退出（不强制 stop 线程，避免与关闭时序竞态）；
 * - [onRevoke]（被其他 VPN 抢占）：标记让位、关闭资源、**绝不自动
 *   重连** —— 自动重连 = 与对方无限抢占循环（方案 §2.2 决策三）。
 */
class NoAdVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null

    @Volatile
    private var loopRunning = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 显式停止指令：不依赖 stopService 的隐式销毁 —— 对 VpnService，
        // 「stopService 回调是否及时 + fd 是否被系统额外持有」在部分
        // ROM 上不可靠，唯一确定的撤销方式是 close establish 返回的 pfd
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "explicit stop requested")
            closeTun()
            stopSelf()
            return START_NOT_STICKY
        }

        val container = (application as NoAdApplication).container

        val requestedMode = intent?.getStringExtra(EXTRA_MODE)
            ?.let { name -> runCatching { NetworkFilterMode.valueOf(name) }.getOrNull() }
            ?: NetworkFilterMode.OFF
        if (requestedMode == NetworkFilterMode.OFF) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 授权检查在仲裁之前：未授权时仲裁结果没有意义
        if (prepare(this) != null) {
            markYielded(container, VpnYieldReason.NOT_PREPARED)
            stopSelf()
            return START_NOT_STICKY
        }

        val decision = container.vpnArbitrator.resolveStartDecision(
            userEnabled = true,
            requestedMode = requestedMode,
        )
        if (!decision.start) {
            markYielded(container, VpnYieldReason.OTHER_VPN_ACTIVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // S3 全流量循环属阶段 E；S4 不建立 TUN（降级链路见下）
        if (decision.mode == NetworkFilterMode.FULL_TRAFFIC) {
            Log.w(TAG, "FULL_TRAFFIC loop lands in phase E; refusing to half-start")
            markYielded(container, VpnYieldReason.NOT_PREPARED)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!decision.mode.occupiesVpn) {
            if (decision.reason == VpnStartReason.DEGRADED_TO_APP_FIREWALL) {
                // ★ 方案 §6.9 降级链路：检测到其他 VPN → 让出 VPN，
                // 但对用户在「应用管理」勾选了 S4 断网的应用执行 Chain-3 断网
                // （不建立 TUN、不占 VPN，可与其他 VPN 共存）
                val targets = container.s1RuleCache.firewallPackages().toList()
                Log.i(TAG, "degraded to app firewall; targets=${targets.size}")
                container.applicationScope.launch {
                    val applied = container.appFirewallController.blockAll(targets)
                    Log.i(TAG, "app firewall applied to $applied/${targets.size}")
                }
            } else {
                Log.i(TAG, "arbitrator declined start (${decision.reason}); stopping")
            }
            stopSelf()
            return START_NOT_STICKY
        }

        // S2 铁律：上游 DNS 必须在 establish 之前读取
        val upstreams = readUpstreamDnsServers()
        if (upstreams.isEmpty()) {
            Log.w(TAG, "no IPv4 upstream DNS available; aborting")
            markYielded(container, VpnYieldReason.NOT_PREPARED)
            stopSelf()
            return START_NOT_STICKY
        }
        Log.d(TAG, "upstream DNS: $upstreams")

        val config = VpnTunConfig.forMode(decision.mode)
        val descriptor = establishWith(config)
        if (descriptor == null) {
            // 最常见原因：仲裁与系统调用之间有其他 VPN 抢先；结果仍是让位
            markYielded(container, VpnYieldReason.OTHER_VPN_ACTIVE)
            stopSelf()
            return START_NOT_STICKY
        }

        tun = descriptor
        loopRunning = true
        VpnStateHolder.onEstablished()
        startPacketLoop(container, descriptor, upstreams)
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        // 被其他 VPN 抢占：让位即让位，绝不自动重连（见类注释）
        val container = (application as? NoAdApplication)?.container
        if (container != null) {
            markYielded(container, VpnYieldReason.REVOKED)
        }
        closeTun()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        loopRunning = false
        closeTun()
        VpnStateHolder.onStopped()
        super.onDestroy()
    }

    /**
     * DNS 包处理循环。
     *
     * 阻塞读 TUN；[onDestroy] 关闭 TUN 后 read 抛 IO 异常自然退出。
     * 注意：同一个 [ParcelFileDescriptor] 的 input/output 视图共享
     * 底层描述符，关闭其中一个即关闭两个 —— 因此 finally 只关 input。
     */
    private fun startPacketLoop(
        container: AppContainer,
        descriptor: ParcelFileDescriptor,
        upstreams: List<InetAddress>,
    ) {
        val interceptor = DnsInterceptor(
            engineProvider = { container.domainRuleRepository.engine.value },
            parser = DnsPacketParser(),
        )
        val forwarder = DnsForwarder(protectSocket = { socket -> protect(socket) })
        val parser = DnsPacketParser()

        Thread {
            val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            val output = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
            val buffer = ByteArray(MAX_TUN_READ)
            var handled = 0L
            var hexDumped = 0
            try {
                while (loopRunning) {
                    val length = input.read(buffer)
                    if (length <= MIN_IP_UDP_SIZE) continue

                    val frame = Ip4UdpPacket.parseUdpDatagram(buffer, length)
                    if (frame == null) {
                        // 取证关键：dump 包头 20 字节。实机日志已证实全部包
                        // unparseable（症状=全设备解析瘫痪），hex 能直接区分
                        // 是版本/协议/偏移哪类不符（如 IPv6 混入、前导偏移）
                        if (hexDumped < 3) {
                            hexDumped++
                            val head = buffer.take(20).joinToString(" ") { "%02X".format(it) }
                            Log.d(TAG, "unparseable (len=$length) head: $head")
                        }
                        continue
                    }
                    if (frame.destinationPort != DNS_PORT) continue

                    val dnsPayload =
                        buffer.copyOfRange(frame.payloadOffset, frame.payloadOffset + frame.payloadLength)

                    val decision = interceptor.decide(dnsPayload)
                    if (handled < 3) {
                        val domain = (parser.parseQuery(dnsPayload) as? DnsPacketParser.ParseOutcome.Query)?.domain
                        Log.d(TAG, "dns#${handled + 1} len=$length domain=$domain -> ${decision::class.simpleName}")
                    }

                    val responsePayload = when (decision) {
                        is DnsInterceptor.Decision.Block -> decision.responsePayload
                        DnsInterceptor.Decision.Ignore -> continue
                        is DnsInterceptor.Decision.Forward ->
                            forwarder.forwardToAny(decision.queryPayload, upstreams)
                                // 上游全部失败 ≠ 域名不存在（§4.3④ 语义区分）
                                ?: run {
                                    Log.d(TAG, "all upstreams failed -> SERVFAIL")
                                    parser.buildErrorResponse(dnsPayload, RCODE_SERVFAIL)
                                }
                                ?: continue
                    }

                    val responsePacket =
                        Ip4UdpPacket.buildResponsePacket(buffer, length, responsePayload)
                    if (responsePacket == null) {
                        Log.d(TAG, "buildResponsePacket returned null")
                        continue
                    }
                    output.write(responsePacket)
                    handled++
                }
            } catch (e: Exception) {
                // TUN 被关闭（onDestroy/onRevoke）属正常退出；
                // 其他异常曾导致循环静默死亡（症状=全设备 DNS 无响应），必须留痕
                Log.w(TAG, "packet loop exited: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                runCatching { input.close() }
            }
        }.apply {
            name = "NoAdDnsLoop"
            isDaemon = true
            start()
        }
    }

    /**
     * 写入「已让位」标记（异步）。UI 据此展示让位原因并引导手动恢复；
     * 恢复必须显式触发（方案决策四），这里只落状态。
     */
    private fun markYielded(container: AppContainer, reason: VpnYieldReason) {
        Log.i(TAG, "vpn yielded: $reason")
        container.applicationScope.launch {
            runCatching { container.settingsRepository.setVpnYielded(true) }
        }
    }

    private fun establishWith(config: VpnTunConfig): ParcelFileDescriptor? = runCatching {
        Builder()
            .setSession(config.session)
            .addAddress(config.address, config.addressPrefix)
            .addDnsServer(config.dnsServer)
            .addRoute(config.route, config.routePrefix)
            .setMtu(config.mtu)
            .establish()
    }.onFailure {
        Log.w(TAG, "establish failed: ${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()

    /**
     * 读取真实上游 DNS（S2 铁律：只能在本方法于 establish 前调用时有效）。
     *
     * **只保留 IPv4**：`DatagramSocket`（IPv4 socket）connect IPv6 上游
     * 会抛异常且被转发层吞掉；若当前网络只下发 IPv6 DNS，不过滤会
     * 变成「全部上游失败 → 全部 SERVFAIL → 全设备解析瘫痪」。
     */
    private fun readUpstreamDnsServers(): List<InetAddress> = runCatching {
        val cm = getSystemService(ConnectivityManager::class.java)
            ?: return@runCatching emptyList()
        cm.allNetworks
            .mapNotNull { network -> cm.getLinkProperties(network) }
            .flatMap(LinkProperties::getDnsServers)
            .filterIsInstance<java.net.Inet4Address>()
            .distinct()
    }.getOrDefault(emptyList())

    private fun closeTun() {
        loopRunning = false
        // 幂等清零：onRevoke 与 onDestroy 都会走到这里，
        // 提前清零让首页状态卡在服务销毁前就反映「已停止」
        VpnStateHolder.onStopped()
        runCatching { tun?.close() }
        tun = null
    }

    companion object {
        private const val TAG = "NoAdVpn"

        private const val DNS_PORT = 53
        private const val RCODE_SERVFAIL = 2

        /** TUN 读缓冲：MTU 1500 + 冗余 */
        private const val MAX_TUN_READ = 32_767
        private const val MIN_IP_UDP_SIZE = 28

        /** 启动 intent 中的模式名（[NetworkFilterMode.name]） */
        const val EXTRA_MODE = "mode"

        /** 显式停止指令的 action（经 startService 送达，见 onStartCommand） */
        const val ACTION_STOP = "com.toaster.noad.vpn.STOP"

        /** 组装启动 intent 的唯一入口 */
        fun startIntent(context: Context, mode: NetworkFilterMode): Intent =
            Intent(context, NoAdVpnService::class.java).putExtra(EXTRA_MODE, mode.name)

        /**
         * 组装停止 intent 的唯一入口。
         *
         * 用 [startService] 携带 [ACTION_STOP] 而非 `stopService`：
         * 停止必须**确定执行且有日志**（close pfd 是撤销 TUN 的唯一
         * 可靠途径）；服务未在跑时的额外一次冷启动开销可忽略。
         */
        fun stopIntent(context: Context): Intent =
            Intent(context, NoAdVpnService::class.java).setAction(ACTION_STOP)
    }
}
