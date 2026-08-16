package com.iblocker.android.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.iblocker.android.container
import com.iblocker.android.notify.ProtectionNotification
import com.iblocker.android.upstream.SocketProtector
import com.iblocker.android.upstream.UpstreamFactory
import com.iblocker.android.widget.WidgetRefresh
import com.iblocker.core.engine.DnsProxyEngine
import com.iblocker.core.log.BlockerStats
import com.iblocker.core.log.QueryLogRingWriter
import com.iblocker.core.log.StatsPersistence
import com.iblocker.core.rules.CompiledBlocklistView
import com.iblocker.core.shared.TunnelConstants
import com.iblocker.core.shared.TunnelRuntimeStats
import com.iblocker.core.shared.UpstreamConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * The on-device DNS filter.
 *
 * A split tunnel routes ONLY the fake resolver addresses (198.18.0.2 /
 * fd00::2) into the tun device, so all real traffic stays on the physical
 * interface — zero throughput cost, minimal battery cost. The service answers
 * blocked lookups itself and forwards the rest to the configured upstream
 * over a protected socket.
 *
 * This is the counterpart of the iOS build's NEPacketTunnelProvider, and like
 * it, it is a thin shell: settings + read loop + control actions. Every
 * decision lives in `core`, where it is unit-tested.
 */
class IBlockerVpnService : VpnService(), SocketProtector {

    private var tunnel: ParcelFileDescriptor? = null
    private var input: FileInputStream? = null
    private var output: FileOutputStream? = null
    private val writeLock = Mutex()

    private var engine: DnsProxyEngine? = null
    private var logWriter: QueryLogRingWriter? = null
    private var scope: CoroutineScope? = null
    private var readThread: Thread? = null
    private var maintenance: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                // Interface-level settings (app exclusions) only apply to a
                // freshly established tun device.
                teardown(stopService = false)
                startTunnel()
                return START_STICKY
            }
            ACTION_RELOAD_RULES -> {
                val engine = engine
                scope?.launch { engine?.reload(container.paths.currentMatcher()) }
                return START_STICKY
            }
            ACTION_SET_UPSTREAM -> {
                applyUpstreamFromSettings()
                return START_STICKY
            }
            ACTION_SYNC_PAUSE -> {
                val until = container.settings.reload().activePauseUntil()
                val engine = engine
                scope?.launch {
                    engine?.setPaused(until)
                    publishStats()
                }
                return START_STICKY
            }
            else -> startTunnel()
        }
        return START_STICKY
    }

    override fun onRevoke() {
        // Another VPN took the slot, or the user revoked consent in Settings.
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    // MARK: - Lifecycle

    private fun startTunnel() {
        if (tunnel != null) return
        _state.value = TunnelState.CONNECTING

        val container = container
        val settings = container.settings.reload()
        val paths = container.paths
        paths.ensureDirectories()

        val upstream = UpstreamFactory.make(settings.upstream, this)
            ?: UpstreamFactory.make(UpstreamConfig.DEFAULT, this)!!

        val descriptor = try {
            buildTunnel(settings.excludedPackages)
        } catch (error: Exception) {
            Log.e(TAG, "could not establish the tun interface", error)
            null
        }
        if (descriptor == null) {
            _state.value = TunnelState.FAILED
            container.settings.update { it.copy(protectionActive = false) }
            stopSelf()
            return
        }

        tunnel = descriptor
        input = FileInputStream(descriptor.fileDescriptor)
        output = FileOutputStream(descriptor.fileDescriptor)

        val writer = QueryLogRingWriter.openOrNull(paths.queryLogFile)
        logWriter = writer

        val matcher = paths.currentMatcher()
        val engine = DnsProxyEngine(
            matcher = matcher,
            upstream = upstream,
            logWriter = writer,
            statsFile = paths.statsFile,
            configuration = DnsProxyEngine.Configuration(logEnabled = settings.queryLogEnabled),
        )
        this.engine = engine

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        this.scope = scope
        scope.launch { engine.setPaused(settings.activePauseUntil()) }

        startForeground(
            ProtectionNotification.ID,
            ProtectionNotification.build(this, blockedToday(), settings.activePauseUntil()),
        )

        container.settings.update { it.copy(protectionActive = true) }
        _state.value = TunnelState.CONNECTED
        WidgetRefresh.refreshAll(this)

        Log.i(TAG, "tunnel up — ${matcher.blockedEntryCount} rules, upstream ${settings.upstream.displayName}")

        startPacketLoop(engine, scope)
        startMaintenance(scope)
    }

    private fun stopTunnel() = teardown(stopService = true)

    private fun teardown(stopService: Boolean) {
        if (tunnel == null && scope == null) {
            _state.value = TunnelState.DISCONNECTED
            if (stopService) stopSelf()
            return
        }
        _state.value = TunnelState.DISCONNECTING

        maintenance?.cancel()
        maintenance = null
        readThread?.interrupt()
        readThread = null

        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { tunnel?.close() }
        input = null
        output = null
        tunnel = null

        val engine = engine
        this.engine = null
        if (engine != null) {
            runBlocking { runCatching { engine.close() } }
        }
        logWriter = null

        scope?.cancel()
        scope = null

        _runtimeStats.value = null
        _state.value = TunnelState.DISCONNECTED

        if (stopService) {
            container.settings.update { it.copy(protectionActive = false) }
            WidgetRefresh.refreshAll(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Only the two synthetic resolver addresses are routed in, and they are
     * also the DNS servers handed to the system — so every app's lookups
     * arrive here while nothing else ever enters the interface.
     */
    private fun buildTunnel(excludedPackages: List<String>): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(TunnelConstants.SESSION_NAME)
            .setMtu(TunnelConstants.MTU)
            .addAddress(TunnelConstants.TUNNEL_IPV4, TunnelConstants.TUNNEL_IPV4_PREFIX_LENGTH)
            .addRoute(TunnelConstants.DNS_IPV4, 32)
            .addDnsServer(TunnelConstants.DNS_IPV4)

        // IPv6 is best-effort: some devices/ROMs refuse a v6 address on a tun
        // interface, and v4-only filtering is still complete filtering.
        runCatching {
            builder.addAddress(TunnelConstants.TUNNEL_IPV6, TunnelConstants.TUNNEL_IPV6_PREFIX_LENGTH)
                .addRoute(TunnelConstants.DNS_IPV6, 128)
                .addDnsServer(TunnelConstants.DNS_IPV6)
        }.onFailure { Log.w(TAG, "IPv6 unavailable on this device — filtering IPv4 only") }

        // This app deliberately stays *inside* the tunnel: the Blocking Test
        // resolves through the system resolver and must experience exactly
        // what another app's ad SDK would. Recursion is prevented the right
        // way instead — upstream endpoints are IP literals (no lookup) on
        // protected sockets. Only the user's excluded apps opt out.
        for (packageName in excludedPackages) {
            runCatching { builder.addDisallowedApplication(packageName) }
                .onFailure { Log.w(TAG, "excluded app $packageName is not installed") }
        }

        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this,
                0,
                packageManager.getLaunchIntentForPackage(packageName) ?: Intent(),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )

        return builder.establish()
    }

    // MARK: - Packet loop

    private fun startPacketLoop(engine: DnsProxyEngine, scope: CoroutineScope) {
        readThread = thread(name = "iblocker-tun-read", isDaemon = true) {
            val stream = input ?: return@thread
            val buffer = ByteArray(TunnelConstants.MTU)
            while (!Thread.currentThread().isInterrupted && scope.isActive) {
                val length = try {
                    stream.read(buffer)
                } catch (_: Exception) {
                    break
                }
                if (length <= 0) continue
                val packet = buffer.copyOf(length)
                scope.launch {
                    val reply = try {
                        engine.handlePacket(packet)
                    } catch (error: Exception) {
                        Log.w(TAG, "packet dropped: ${error.message}")
                        null
                    }
                    if (reply != null) writeToTunnel(reply)
                }
            }
        }
    }

    private suspend fun writeToTunnel(packet: ByteArray) {
        writeLock.withLock {
            try {
                output?.write(packet)
            } catch (error: Exception) {
                Log.w(TAG, "tun write failed: ${error.message}")
            }
        }
    }

    // MARK: - Maintenance

    /**
     * Flushes the log/stats every 2 s, picks up a pause set by the tile,
     * widget or a shortcut, and reloads recompiled blocklists even when the
     * UI never sent an explicit reload.
     */
    private fun startMaintenance(scope: CoroutineScope) {
        maintenance = scope.launch {
            var tick = 0L
            while (isActive) {
                delay(2_000)
                val engine = engine ?: return@launch
                engine.flush()

                val settings = container.settings.reload()
                engine.setPaused(settings.activePauseUntil())

                tick += 1
                if (tick % 5 == 0L) {
                    val current = engine.blocklistGeneration()
                    val onDisk = CompiledBlocklistView.openOrNull(container.paths.blocklistFile)
                    if (onDisk != null && onDisk.generation != current) {
                        Log.i(TAG, "blocklist generation changed → reloading rules")
                        engine.reload(container.paths.currentMatcher())
                    }
                }
                if (tick % 3 == 0L) {
                    publishStats()
                    ProtectionNotification.update(
                        this@IBlockerVpnService,
                        blockedToday(),
                        settings.activePauseUntil(),
                    )
                    if (tick % 15 == 0L) WidgetRefresh.refreshAll(this@IBlockerVpnService)
                }
            }
        }
    }

    private suspend fun publishStats() {
        _runtimeStats.value = engine?.statsSnapshot()
    }

    private fun blockedToday(): Long =
        StatsPersistence.load(container.paths.statsFile).counters(BlockerStats.dayKey()).blocked

    private fun applyUpstreamFromSettings() {
        val config = container.settings.reload().upstream
        val upstream = UpstreamFactory.make(config, this) ?: return
        val engine = engine ?: return
        scope?.launch { engine.setUpstream(upstream) }
    }

    // MARK: - SocketProtector

    override fun protect(socket: java.net.Socket): Boolean = super.protect(socket)

    override fun protect(socket: java.net.DatagramSocket): Boolean = super.protect(socket)

    companion object {
        private const val TAG = "IBlockerVpn"

        const val ACTION_START = "com.iblocker.android.vpn.START"
        const val ACTION_STOP = "com.iblocker.android.vpn.STOP"
        const val ACTION_RELOAD_RULES = "com.iblocker.android.vpn.RELOAD_RULES"
        const val ACTION_SET_UPSTREAM = "com.iblocker.android.vpn.SET_UPSTREAM"
        const val ACTION_SYNC_PAUSE = "com.iblocker.android.vpn.SYNC_PAUSE"
        const val ACTION_RESTART = "com.iblocker.android.vpn.RESTART"

        private val _state = MutableStateFlow(TunnelState.DISCONNECTED)
        val state: StateFlow<TunnelState> = _state.asStateFlow()

        private val _runtimeStats = MutableStateFlow<TunnelRuntimeStats?>(null)
        val runtimeStats: StateFlow<TunnelRuntimeStats?> = _runtimeStats.asStateFlow()

        fun intent(context: Context, action: String): Intent =
            Intent(context, IBlockerVpnService::class.java).setAction(action)
    }
}
