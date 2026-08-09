import Foundation

public struct DayCounters: Codable, Sendable, Equatable {
    public var total: UInt64
    public var blocked: UInt64

    public init(total: UInt64 = 0, blocked: UInt64 = 0) {
        self.total = total
        self.blocked = blocked
    }
}

/// Cumulative counters, persisted as JSON in the App Group. The tunnel keeps
/// the authoritative copy in memory and flushes on the log-ring cadence; the
/// app reads the file when the tunnel is not reachable.
public struct BlockerStats: Codable, Sendable, Equatable {
    public var totalQueries: UInt64
    public var totalBlocked: UInt64
    public var days: [String: DayCounters]

    public init(totalQueries: UInt64 = 0, totalBlocked: UInt64 = 0, days: [String: DayCounters] = [:]) {
        self.totalQueries = totalQueries
        self.totalBlocked = totalBlocked
        self.days = days
    }

    public mutating func record(blocked: Bool, on day: String) {
        totalQueries += 1
        var counters = days[day] ?? DayCounters()
        counters.total += 1
        if blocked {
            totalBlocked += 1
            counters.blocked += 1
        }
        days[day] = counters
        if days.count > 60 {
            trim(keepDays: 45)
        }
    }

    public mutating func trim(keepDays: Int) {
        let keep = Set(days.keys.sorted().suffix(keepDays))
        days = days.filter { keep.contains($0.key) }
    }

    public func counters(day: String) -> DayCounters {
        days[day] ?? DayCounters()
    }

    /// Local-calendar day key, e.g. "2026-08-09".
    public static func dayKey(for date: Date = Date(), calendar: Calendar = .current) -> String {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }
}

public enum StatsPersistence {
    public static func load(from url: URL) -> BlockerStats {
        guard let data = try? Data(contentsOf: url),
              let stats = try? JSONDecoder().decode(BlockerStats.self, from: data) else {
            return BlockerStats()
        }
        return stats
    }

    public static func save(_ stats: BlockerStats, to url: URL) {
        if let data = try? JSONEncoder().encode(stats) {
            try? data.write(to: url, options: .atomic)
        }
    }
}
