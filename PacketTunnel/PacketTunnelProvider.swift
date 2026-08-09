import NetworkExtension
import os
import IBlockerKit
import IBlockerTunnelKit

/// The on-device DNS filter. A split tunnel routes ONLY the fake resolver
/// addresses (198.18.0.2 / fd00::2) into the utun, so all real traffic stays
/// on the physical interface; the provider answers blocked lookups itself
/// and forwards the rest to the configured upstream.
class PacketTunnelProvider: NEPacketTunnelProvider {

    private static let log = Logger(subsystem: "com.iblocker.tunnel", category: "provider")

    private var engine: DNSProxyEngine?
    private var paths: AppGroupPaths?
    private var maintenanceTask: Task<Void, Never>?

    override func startTunnel(options: [String: NSObject]?) async throws {
        guard let groupID = AppGroupPaths.groupID(from: Bundle.main),
              let paths = AppGroupPaths(groupID: groupID) else {
            Self.log.error("missing AppGroupID / container — check entitlements")
            throw NEVPNError(.configurationInvalid)
        }
        self.paths = paths
        try? paths.ensureDirectories()

        let settings = SharedSettings(groupID: groupID)
        let upstreamConfig = settings?.upstreamConfig ?? .default
        guard let upstream = UpstreamFactory.make(upstreamConfig) else {
            Self.log.error("invalid upstream config")
            throw NEVPNError(.configurationInvalid)
        }

        let matcher = Self.currentMatcher(paths: paths)
        let engine = DNSProxyEngine(
            matcher: matcher,
            upstream: upstream,
            logWriter: try? QueryLogRingWriter(url: paths.queryLogURL),
            statsURL: paths.statsURL,
            configuration: .init(logEnabled: settings?.queryLogEnabled ?? true)
        )
        self.engine = engine

        let networkSettings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "127.0.0.1")
        let relayStrategy = settings?.relayStrategy ?? .blockDomains

        let ipv4 = NEIPv4Settings(
            addresses: [TunnelConstants.tunnelIPv4],
            subnetMasks: [TunnelConstants.tunnelIPv4SubnetMask]
        )
        let dnsRoute4 = NEIPv4Route(destinationAddress: TunnelConstants.dnsIPv4, subnetMask: "255.255.255.255")
        if relayStrategy == .autoSuspend {
            // Claim the default route so iOS treats this as a full VPN and
            // suspends Private Relay / tracker relaying itself — but exclude
            // both halves of the address space with more-specific routes, so
            // real traffic never enters the tunnel. Only the /32 DNS route
            // (more specific than the /1 exclusions) still comes to us.
            ipv4.includedRoutes = [NEIPv4Route.default(), dnsRoute4]
            ipv4.excludedRoutes = [
                NEIPv4Route(destinationAddress: "0.0.0.0", subnetMask: "128.0.0.0"),
                NEIPv4Route(destinationAddress: "128.0.0.0", subnetMask: "128.0.0.0"),
            ]
        } else {
            ipv4.includedRoutes = [dnsRoute4]
        }
        networkSettings.ipv4Settings = ipv4

        let ipv6 = NEIPv6Settings(
            addresses: [TunnelConstants.tunnelIPv6],
            networkPrefixLengths: [NSNumber(value: TunnelConstants.tunnelIPv6PrefixLength)]
        )
        let dnsRoute6 = NEIPv6Route(destinationAddress: TunnelConstants.dnsIPv6, networkPrefixLength: 128)
        if relayStrategy == .autoSuspend {
            ipv6.includedRoutes = [NEIPv6Route.default(), dnsRoute6]
            ipv6.excludedRoutes = [
                NEIPv6Route(destinationAddress: "::", networkPrefixLength: 1),
                NEIPv6Route(destinationAddress: "8000::", networkPrefixLength: 1),
            ]
        } else {
            ipv6.includedRoutes = [dnsRoute6]
        }
        networkSettings.ipv6Settings = ipv6

        let dns = NEDNSSettings(servers: [TunnelConstants.dnsIPv4, TunnelConstants.dnsIPv6])
        dns.matchDomains = [""]  // make the tunnel the default resolver for everything
        networkSettings.dnsSettings = dns
        networkSettings.mtu = NSNumber(value: TunnelConstants.mtu)

        try await setTunnelNetworkSettings(networkSettings)

        Self.log.info("tunnel up — \(matcher.blockedEntryCount) rules, upstream \(upstreamConfig.displayName, privacy: .public), available memory \(os_proc_available_memory())")

        startPacketLoop()
        startMaintenance(paths: paths)
    }

    override func stopTunnel(with reason: NEProviderStopReason) async {
        Self.log.info("tunnel stopping: \(String(describing: reason), privacy: .public)")
        maintenanceTask?.cancel()
        if let engine {
            await engine.flush()
        }
        engine = nil
    }

    override func handleAppMessage(_ messageData: Data) async -> Data? {
        guard let engine,
              let request = try? TunnelIPCCoder.decodeRequest(messageData) else { return nil }

        let response: TunnelResponse
        switch request {
        case .ping:
            response = .ok

        case .reloadRules:
            if let paths {
                await engine.reload(matcher: Self.currentMatcher(paths: paths))
                response = .ok
            } else {
                response = .failure("no app group container")
            }

        case .getStats:
            await engine.flush()
            response = .stats(await engine.statsSnapshot(memoryBytes: UInt64(os_proc_available_memory())))

        case .setUpstream(let config):
            if let upstream = UpstreamFactory.make(config) {
                await engine.setUpstream(upstream)
                response = .ok
            } else {
                response = .failure("invalid upstream config")
            }
        }
        return try? TunnelIPCCoder.encode(response)
    }

    /// The matcher is the on-disk blobs PLUS the in-memory seed fallback for
    /// every bundled source the user has left enabled — so the guaranteed
    /// floor (in-app Google ads, Apple tracker-relay bypass) holds even when
    /// the compiled blob is missing or stale.
    private static func currentMatcher(paths: AppGroupPaths) -> DomainMatcher {
        let state = FilterListState.load(from: paths.filterStateURL)
        return paths.loadMatcher(builtInBlockHashes: SeedRules.fallbackHashes(state: state))
    }

    // MARK: - Packet loop

    private func startPacketLoop() {
        packetFlow.readPackets { [weak self] packets, _ in
            guard let self else { return }
            for packet in packets {
                Task { [weak self] in
                    guard let self, let engine = self.engine else { return }
                    if let reply = await engine.handlePacket(packet) {
                        let family = (([UInt8](reply).first ?? 0) >> 4) == 6 ? AF_INET6 : AF_INET
                        self.packetFlow.writePackets([reply], withProtocols: [NSNumber(value: family)])
                    }
                }
            }
            self.startPacketLoop()
        }
    }

    // MARK: - Maintenance

    /// Flushes the log/stats every 2 s and picks up recompiled blocklists
    /// even if the app's reload IPC never arrives (e.g. app was killed).
    private func startMaintenance(paths: AppGroupPaths) {
        maintenanceTask = Task { [weak self] in
            var tick: UInt64 = 0
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                guard let self, let engine = self.engine else { return }
                await engine.flush()
                tick += 1
                if tick % 5 == 0 {
                    let current = await engine.blocklistGeneration
                    if let onDisk = try? CompiledBlocklistView(contentsOf: paths.blocklistURL),
                       onDisk.generation != current {
                        Self.log.info("blocklist generation changed → reloading rules")
                        await engine.reload(matcher: Self.currentMatcher(paths: paths))
                    }
                }
            }
        }
    }
}
