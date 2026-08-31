import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public struct FetchResult: Sendable {
    public var statusCode: Int
    public var body: Data
    public var etag: String?
    public var lastModified: String?

    public init(statusCode: Int, body: Data, etag: String? = nil, lastModified: String? = nil) {
        self.statusCode = statusCode
        self.body = body
        self.etag = etag
        self.lastModified = lastModified
    }
}

public struct UpdateSummary: Sendable, Equatable {
    public var updatedSourceIDs: [String] = []
    public var unchangedSourceIDs: [String] = []
    public var failedSourceIDs: [String: String] = [:]

    public var anyChanged: Bool { !updatedSourceIDs.isEmpty }

    public init() {}
}

/// Failures we raise ourselves. Phrased for the list row a user actually
/// reads, not for a log — `URLError`'s own text degrades to
/// "The operation couldn't be completed. (NSURLErrorDomain error -1011.)"
/// when the error is synthesized rather than produced by URLSession.
public enum FilterListUpdateError: LocalizedError, Equatable {
    case httpStatus(Int)
    case emptyBody

    public var errorDescription: String? {
        switch self {
        case .emptyBody:
            return "Server returned an empty file"
        case .httpStatus(let code):
            switch code {
            case 404: return "Not found (404) — the list may have moved"
            case 403: return "Access denied (403)"
            case 429: return "Rate limited — try again later"
            case 500...599: return "Server error (\(code))"
            default: return "Unexpected response (\(code))"
            }
        }
    }
}

/// Downloads raw list texts into the App Group cache with conditional GETs.
/// The network layer is injected as a closure so tests never touch the wire.
public struct FilterListUpdater: Sendable {
    public typealias Fetch = @Sendable (URLRequest) async throws -> FetchResult

    private let fetch: Fetch
    private let paths: AppGroupPaths

    public init(paths: AppGroupPaths, fetch: @escaping Fetch) {
        self.paths = paths
        self.fetch = fetch
    }

    #if canImport(Darwin) || canImport(FoundationNetworking)
    /// Production fetcher backed by URLSession.
    public init(paths: AppGroupPaths, session: URLSession = .shared) {
        self.init(paths: paths) { request in
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                throw URLError(.badServerResponse)
            }
            return FetchResult(
                statusCode: http.statusCode,
                body: data,
                etag: http.value(forHTTPHeaderField: "ETag"),
                lastModified: http.value(forHTTPHeaderField: "Last-Modified")
            )
        }
    }
    #endif

    /// Fetches every enabled source, updating `state.metadata` and the
    /// cached list files. Does not recompile; call BlocklistCompiler after.
    public func update(state: inout FilterListState, force: Bool = false) async -> UpdateSummary {
        var summary = UpdateSummary()
        try? paths.ensureDirectories()

        for source in state.sources where source.enabled {
            // Bundled rulesets ship in the binary — nothing to fetch.
            if SeedRules.bundledText(for: source.id) != nil { continue }
            // A hidden source must not download either: its failures would be
            // recorded where no one can see or act on them.
            if FeatureFlags.isHidden(sourceID: source.id) { continue }

            var metadata = state.metadata[source.id] ?? FilterListMetadata()
            var request = URLRequest(url: source.url)
            request.timeoutInterval = 60
            let hasCachedFile = FileManager.default.fileExists(atPath: paths.cachedListURL(sourceID: source.id).path)
            if !force && hasCachedFile {
                if let etag = metadata.etag {
                    request.setValue(etag, forHTTPHeaderField: "If-None-Match")
                }
                if let lastModified = metadata.lastModified {
                    request.setValue(lastModified, forHTTPHeaderField: "If-Modified-Since")
                }
            }

            do {
                let result = try await fetch(request)
                switch result.statusCode {
                case 200:
                    guard !result.body.isEmpty else { throw FilterListUpdateError.emptyBody }
                    try result.body.write(to: paths.cachedListURL(sourceID: source.id), options: .atomic)
                    metadata.etag = result.etag
                    metadata.lastModified = result.lastModified
                    metadata.lastFetched = Date()
                    metadata.lastError = nil
                    summary.updatedSourceIDs.append(source.id)
                case 304:
                    metadata.lastFetched = Date()
                    metadata.lastError = nil
                    summary.unchangedSourceIDs.append(source.id)
                default:
                    throw FilterListUpdateError.httpStatus(result.statusCode)
                }
            } catch {
                metadata.lastError = shortDescription(of: error)
                summary.failedSourceIDs[source.id] = metadata.lastError
            }
            state.metadata[source.id] = metadata
        }

        return summary
    }

    private func shortDescription(of error: Error) -> String {
        if let updateError = error as? FilterListUpdateError {
            return updateError.errorDescription ?? "Update failed"
        }
        if let urlError = error as? URLError {
            switch urlError.code {
            case .notConnectedToInternet: return "No internet connection"
            case .timedOut: return "Timed out"
            case .cannotFindHost, .cannotConnectToHost, .dnsLookupFailed:
                return "Can't reach the server"
            case .networkConnectionLost: return "Connection lost"
            case .secureConnectionFailed, .serverCertificateUntrusted:
                return "Secure connection failed"
            case .dataNotAllowed: return "Cellular data not allowed"
            case .zeroByteResource: return "Server returned an empty file"
            case .badServerResponse: return "Unexpected response from the server"
            default: return "Download failed (\(urlError.code.rawValue))"
            }
        }
        return error.localizedDescription
    }
}
