#if canImport(Network)
import Foundation
import Network
import IBlockerKit

/// Plain DNS-over-UDP:53 upstream.
///
/// One long-lived connected socket serves every client, so transaction IDs
/// are rewritten to a private sequence and demultiplexed on receive. The
/// address must be an IP literal — hostname resolution inside the tunnel
/// would recurse.
public actor UDPUpstream: DNSUpstream {
    private let host: NWEndpoint.Host
    private let port: NWEndpoint.Port
    private let timeoutNanoseconds: UInt64
    private var connection: NWConnection?
    private var nextID: UInt16 = 0
    private var pending: [UInt16: CheckedContinuation<Data, Error>] = [:]

    public init(address: String, port: UInt16 = 53, timeout: TimeInterval = 3) {
        self.host = NWEndpoint.Host(address)
        self.port = NWEndpoint.Port(rawValue: port) ?? 53
        self.timeoutNanoseconds = UInt64(timeout * 1_000_000_000)
    }

    public func resolve(_ query: Data) async throws -> Data {
        var lastError: Error = UpstreamError.timeout
        for _ in 0..<2 {
            do {
                return try await attempt(query)
            } catch {
                lastError = error
            }
        }
        throw lastError
    }

    private func attempt(_ query: Data) async throws -> Data {
        guard query.count >= 12 else { throw UpstreamError.badResponse }
        let connection = ensureConnection()

        var bytes = [UInt8](query)
        let originalID = UInt16(bytes[0]) << 8 | UInt16(bytes[1])

        nextID &+= 1
        var attempts = 0
        while pending[nextID] != nil && attempts < 0x10000 {
            nextID &+= 1
            attempts += 1
        }
        let ourID = nextID
        bytes[0] = UInt8(ourID >> 8)
        bytes[1] = UInt8(ourID & 0xFF)

        let response: Data = try await withCheckedThrowingContinuation { continuation in
            pending[ourID] = continuation
            connection.send(content: Data(bytes), completion: .contentProcessed { [weak self] error in
                if let error {
                    Task {
                        await self?.fail(id: ourID, error: UpstreamError.connectionFailed(String(describing: error)))
                    }
                }
            })
            Task { [weak self, timeoutNanoseconds] in
                try? await Task.sleep(nanoseconds: timeoutNanoseconds)
                await self?.fail(id: ourID, error: UpstreamError.timeout)
            }
        }

        var out = [UInt8](response)
        guard out.count >= 12 else { throw UpstreamError.badResponse }
        out[0] = UInt8(originalID >> 8)
        out[1] = UInt8(originalID & 0xFF)
        return Data(out)
    }

    private func ensureConnection() -> NWConnection {
        if let existing = connection {
            return existing
        }
        let conn = NWConnection(host: host, port: port, using: .udp)
        conn.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed(let error):
                Task { await self?.connectionBroke(String(describing: error)) }
            case .cancelled:
                Task { await self?.connectionBroke("cancelled") }
            default:
                break
            }
        }
        receiveLoop(conn)
        conn.start(queue: .global(qos: .userInitiated))
        connection = conn
        return conn
    }

    private nonisolated func receiveLoop(_ conn: NWConnection) {
        conn.receiveMessage { [weak self] data, _, _, error in
            guard let self else { return }
            if let data, !data.isEmpty {
                Task { await self.complete(data) }
            }
            if error == nil {
                self.receiveLoop(conn)
            }
        }
    }

    private func complete(_ data: Data) {
        guard data.count >= 2 else { return }
        let id = UInt16(data[0]) << 8 | UInt16(data[1])
        if let continuation = pending.removeValue(forKey: id) {
            continuation.resume(returning: data)
        }
    }

    private func fail(id: UInt16, error: Error) {
        if let continuation = pending.removeValue(forKey: id) {
            continuation.resume(throwing: error)
        }
    }

    private func connectionBroke(_ reason: String) {
        connection = nil
        let waiting = pending
        pending.removeAll()
        for continuation in waiting.values {
            continuation.resume(throwing: UpstreamError.connectionFailed(reason))
        }
    }
}
#endif
