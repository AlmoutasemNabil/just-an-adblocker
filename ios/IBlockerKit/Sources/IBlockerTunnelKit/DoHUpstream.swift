import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import IBlockerKit

/// DNS-over-HTTPS upstream (RFC 8484 POST).
///
/// The default upstream. HTTP/2/3 multiplexing gives per-query concurrency
/// for free and, because the exchange is TCP-based on our side, upstream
/// truncation never happens.
///
/// IMPORTANT: inside the packet tunnel, the endpoint URL must use an IP
/// literal (https://1.1.1.1/dns-query). A hostname would be resolved through
/// the system resolver — which points at the tunnel itself.
public final class DoHUpstream: DNSUpstream, @unchecked Sendable {
    private let url: URL
    private let session: URLSession

    public init(url: URL, timeout: TimeInterval = 4) {
        self.url = url
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = timeout
        configuration.timeoutIntervalForResource = timeout * 2
        configuration.httpAdditionalHeaders = [
            "Accept": "application/dns-message",
            "Content-Type": "application/dns-message",
        ]
        self.session = URLSession(configuration: configuration)
    }

    public convenience init?(config: UpstreamConfig) {
        guard config.kind == .doh, let raw = config.dohURL, let url = URL(string: raw) else {
            return nil
        }
        self.init(url: url)
    }

    public func resolve(_ query: Data) async throws -> Data {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = query
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200, data.count >= 12 else {
            throw UpstreamError.badResponse
        }
        return data
    }
}
