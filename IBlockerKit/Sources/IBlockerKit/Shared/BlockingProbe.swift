#if canImport(Darwin)
import Foundation
import Darwin

/// Resolves a hostname through the SYSTEM resolver (getaddrinfo), which on
/// device goes through the tunnel — so the outcome measures exactly what any
/// other app's ad SDK would experience.
public enum BlockingProbe {

    public enum Outcome: Equatable, Sendable {
        /// Every returned address is a blackhole (0.0.0.0 / ::) — an ad SDK
        /// gets nowhere. This is what the tunnel synthesizes for blocked names.
        case blocked
        /// Real addresses came back.
        case resolved([String])
        /// Lookup failed outright (NXDOMAIN, no resolver, …). For an ad
        /// domain this also means ads cannot load.
        case unreachable(String)
    }

    public static func probe(host: String) async -> Outcome {
        let hostCopy = host
        return await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                continuation.resume(returning: probeSync(host: hostCopy))
            }
        }
    }

    static func probeSync(host: String) -> Outcome {
        var hints = addrinfo()
        hints.ai_family = AF_UNSPEC
        hints.ai_socktype = SOCK_STREAM

        var list: UnsafeMutablePointer<addrinfo>?
        let status = getaddrinfo(host, nil, &hints, &list)
        guard status == 0 else {
            return .unreachable(String(cString: gai_strerror(status)))
        }
        defer { freeaddrinfo(list) }

        var addresses: [String] = []
        var blackholeOnly = true
        var node = list
        while let current = node {
            if let sockaddrPointer = current.pointee.ai_addr {
                var buffer = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                if getnameinfo(sockaddrPointer, current.pointee.ai_addrlen,
                               &buffer, socklen_t(buffer.count),
                               nil, 0, NI_NUMERICHOST) == 0 {
                    let ip = String(cString: buffer)
                    addresses.append(ip)
                    if ip != "0.0.0.0" && ip != "::" {
                        blackholeOnly = false
                    }
                }
            }
            node = current.pointee.ai_next
        }

        guard !addresses.isEmpty else { return .unreachable("no addresses") }
        return blackholeOnly ? .blocked : .resolved(addresses)
    }
}
#endif
