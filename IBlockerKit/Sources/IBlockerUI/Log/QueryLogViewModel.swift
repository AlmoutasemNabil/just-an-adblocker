#if os(iOS)
import Foundation
import Observation
import IBlockerKit

@MainActor
@Observable
public final class QueryLogViewModel {

    public enum Filter: String, CaseIterable, Identifiable {
        case all = "All"
        case blocked = "Blocked"
        case allowed = "Allowed"
        public var id: String { rawValue }
    }

    public private(set) var records: [QueryLogRecord] = []
    public var searchText = ""
    public var filter: Filter = .all

    private let reader: QueryLogRingReader
    private var cursor: UInt64 = 0
    private var pollTask: Task<Void, Never>?
    private let maxRecords = 4000

    public init(paths: AppGroupPaths) {
        self.reader = QueryLogRingReader(url: paths.queryLogURL)
        poll()
    }

    /// 1 Hz polling, only while the Log tab is visible.
    public func startPolling() {
        guard pollTask == nil else { return }
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                self?.poll()
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
    }

    public func stopPolling() {
        pollTask?.cancel()
        pollTask = nil
    }

    private func poll() {
        let (new, newCursor) = reader.read(since: cursor)
        guard newCursor != cursor else { return }
        cursor = newCursor
        records.append(contentsOf: new)
        if records.count > maxRecords {
            records.removeFirst(records.count - maxRecords)
        }
    }

    public var filtered: [QueryLogRecord] {
        var result = records
        switch filter {
        case .all: break
        case .blocked: result = result.filter { $0.verdict == .blocked }
        case .allowed: result = result.filter { $0.verdict != .blocked }
        }
        let query = searchText.trimmingCharacters(in: .whitespaces).lowercased()
        if !query.isEmpty {
            result = result.filter { $0.domain.contains(query) }
        }
        return result.reversed()
    }

    /// Blocked count per hour of the local day, for the dashboard chart.
    public var blockedPerHourToday: [(hour: Int, blocked: Int)] {
        var buckets = [Int](repeating: 0, count: 24)
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: Date())
        for record in records where record.verdict == .blocked {
            let date = record.date
            guard date >= today else { continue }
            buckets[calendar.component(.hour, from: date)] += 1
        }
        return (0..<24).map { ($0, buckets[$0]) }
    }

    public var topBlockedDomains: [(domain: String, count: Int)] {
        var counts: [String: Int] = [:]
        for record in records where record.verdict == .blocked {
            counts[record.domain, default: 0] += 1
        }
        return counts.sorted { $0.value > $1.value }.prefix(5).map { ($0.key, $0.value) }
    }
}
#endif
