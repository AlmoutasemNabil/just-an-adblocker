import XCTest
@testable import IBlockerKit

final class FilterListUpdaterTests: XCTestCase {

    private func makeState(sourceID: String = "test") -> FilterListState {
        var state = FilterListState()
        state.sources = [
            FilterListSource(id: sourceID, name: "Test", url: URL(string: "https://lists.example.com/test.txt")!,
                             enabled: true, isBuiltIn: false),
        ]
        return state
    }

    func testDownloadCacheAndConditionalGet() async throws {
        let paths = AppGroupPaths(containerURL: try makeTempDirectory())
        var state = makeState()

        let listBody = Data("0.0.0.0 ads.example.com\n||tracker.net^\n".utf8)
        let updater = FilterListUpdater(paths: paths) { request in
            if request.value(forHTTPHeaderField: "If-None-Match") == "v1" {
                return FetchResult(statusCode: 304, body: Data())
            }
            return FetchResult(statusCode: 200, body: listBody, etag: "v1", lastModified: "Mon, 01 Jan 2026 00:00:00 GMT")
        }

        let first = await updater.update(state: &state)
        XCTAssertEqual(first.updatedSourceIDs, ["test"])
        XCTAssertTrue(first.failedSourceIDs.isEmpty)
        XCTAssertEqual(state.metadata["test"]?.etag, "v1")
        XCTAssertEqual(try Data(contentsOf: paths.cachedListURL(sourceID: "test")), listBody)

        let second = await updater.update(state: &state)
        XCTAssertEqual(second.unchangedSourceIDs, ["test"])
        XCTAssertTrue(second.updatedSourceIDs.isEmpty)

        // force ignores the cache validators
        let third = await updater.update(state: &state, force: true)
        XCTAssertEqual(third.updatedSourceIDs, ["test"])

        // Compile from the cache.
        let stats = try BlocklistCompiler.compile(state: &state, paths: paths)
        XCTAssertEqual(stats.blockedEntryCount, 2)
        XCTAssertEqual(state.metadata["test"]?.entryCount, 2)
    }

    func testFailureIsRecordedAndDoesNotThrow() async throws {
        let paths = AppGroupPaths(containerURL: try makeTempDirectory())
        var state = makeState()

        let updater = FilterListUpdater(paths: paths) { _ in
            FetchResult(statusCode: 503, body: Data())
        }
        let summary = await updater.update(state: &state)
        XCTAssertEqual(summary.updatedSourceIDs, [])
        XCTAssertNotNil(summary.failedSourceIDs["test"])
        XCTAssertNotNil(state.metadata["test"]?.lastError)
    }

    func testDisabledSourcesAreNotFetched() async throws {
        let paths = AppGroupPaths(containerURL: try makeTempDirectory())
        var state = makeState()
        state.sources[0].enabled = false

        let updater = FilterListUpdater(paths: paths) { _ in
            XCTFail("disabled source must not be fetched")
            return FetchResult(statusCode: 200, body: Data())
        }
        let summary = await updater.update(state: &state)
        XCTAssertTrue(summary.updatedSourceIDs.isEmpty)
    }

    func testStatePersistenceRoundTripAndBuiltInMerge() throws {
        let url = try makeTempDirectory().appendingPathComponent("sources.json")
        var state = FilterListState()
        state.userAllowlist = ["keep.example.com"]
        state.generation = 9
        try state.save(to: url)

        let loaded = FilterListState.load(from: url)
        XCTAssertEqual(loaded.userAllowlist, ["keep.example.com"])
        XCTAssertEqual(loaded.generation, 9)
        XCTAssertEqual(Set(loaded.sources.map(\.id)).isSuperset(of: Set(FilterListSource.builtIn.map(\.id))), true)

        // Fresh load with no file yields the built-ins.
        let fresh = FilterListState.load(from: url.appendingPathExtension("missing"))
        XCTAssertEqual(fresh.sources.map(\.id), FilterListSource.builtIn.map(\.id))
        XCTAssertTrue(fresh.sources.first { $0.id == "oisd-big" }!.enabled)
    }
}
