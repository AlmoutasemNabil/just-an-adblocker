import Foundation

/// A resolver the engine forwards non-blocked queries to.
/// Takes and returns raw DNS messages so EDNS options pass through untouched.
public protocol DNSUpstream: Sendable {
    func resolve(_ query: Data) async throws -> Data
}

public enum UpstreamError: Error, Equatable {
    case timeout
    case badResponse
    case connectionFailed(String)
}
