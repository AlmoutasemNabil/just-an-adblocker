import WidgetKit
import SwiftUI
import IBlockerKit

struct StatusEntry: TimelineEntry {
    let date: Date
    let blockedToday: UInt64
    let totalBlocked: UInt64
    let active: Bool
    let paused: Bool
}

/// App Group accessors resolved from the widget bundle's Info.plist.
enum WidgetEnvironment {
    static var settings: SharedSettings? {
        guard let groupID = AppGroupPaths.groupID(from: .main) else { return nil }
        return SharedSettings(groupID: groupID)
    }

    static var statsURL: URL? {
        guard let groupID = AppGroupPaths.groupID(from: .main),
              let paths = AppGroupPaths(groupID: groupID) else { return nil }
        return paths.statsURL
    }
}

struct StatusProvider: TimelineProvider {
    func placeholder(in context: Context) -> StatusEntry {
        StatusEntry(date: Date(), blockedToday: 1234, totalBlocked: 98765, active: true, paused: false)
    }

    func getSnapshot(in context: Context, completion: @escaping (StatusEntry) -> Void) {
        completion(makeEntry())
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<StatusEntry>) -> Void) {
        let next = Date(timeIntervalSinceNow: 15 * 60)
        completion(Timeline(entries: [makeEntry()], policy: .after(next)))
    }

    private func makeEntry() -> StatusEntry {
        let stats = WidgetEnvironment.statsURL.map { StatsPersistence.load(from: $0) } ?? BlockerStats()
        let settings = WidgetEnvironment.settings
        return StatusEntry(
            date: Date(),
            blockedToday: stats.counters(day: BlockerStats.dayKey()).blocked,
            totalBlocked: stats.totalBlocked,
            active: settings?.protectionActive ?? false,
            paused: settings?.pausedUntil != nil
        )
    }
}

struct StatusWidgetView: View {
    var entry: StatusEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        switch family {
        case .accessoryInline:
            Text("\(entry.blockedToday) blocked")
        case .accessoryRectangular:
            VStack(alignment: .leading) {
                Label("IBlocker", systemImage: statusSymbol)
                    .font(.headline)
                Text("\(entry.blockedToday.formatted()) blocked today")
                    .font(.caption)
            }
        case .accessoryCircular:
            Gauge(value: 0) {
                Image(systemName: statusSymbol)
            } currentValueLabel: {
                Text(entry.blockedToday.formatted(.number.notation(.compactName)))
            }
            .gaugeStyle(.accessoryCircularCapacity)
        default:
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Image(systemName: statusSymbol)
                        .foregroundStyle(statusColor)
                    Text(statusText)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(statusColor)
                    Spacer()
                }
                Spacer()
                Text(entry.blockedToday.formatted(.number.notation(.compactName)))
                    .font(.system(.largeTitle, design: .rounded, weight: .bold))
                    .foregroundStyle(.red)
                Text("blocked today")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text("\(entry.totalBlocked.formatted(.number.notation(.compactName))) all time")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
        }
    }

    private var statusSymbol: String {
        if entry.paused { return "pause.circle.fill" }
        return entry.active ? "checkmark.shield.fill" : "shield.slash"
    }

    private var statusColor: Color {
        if entry.paused { return .orange }
        return entry.active ? .green : .secondary
    }

    private var statusText: String {
        if entry.paused { return "Paused" }
        return entry.active ? "Protected" : "Off"
    }
}

struct StatusWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetKinds.statusWidget, provider: StatusProvider()) { entry in
            StatusWidgetView(entry: entry)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("IBlocker")
        .description("Ads blocked today and protection status.")
        .supportedFamilies([
            .systemSmall, .systemMedium,
            .accessoryInline, .accessoryRectangular, .accessoryCircular,
        ])
    }
}
