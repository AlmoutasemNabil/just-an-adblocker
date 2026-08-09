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
                    guard !result.body.isEmpty else { throw URLError(.zeroByteResource) }
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
                    throw URLError(.badServerResponse)
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
        if let urlError = error as? URLError {
            return urlError.localizedDescription
        }
        return String(describing: error)
    }
}
