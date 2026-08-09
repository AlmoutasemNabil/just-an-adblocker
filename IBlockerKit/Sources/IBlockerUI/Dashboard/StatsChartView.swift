#if os(iOS)
import SwiftUI
import Charts
import IBlockerKit

/// Blocked-per-hour bars for today plus the top blocked domains, computed
/// from the query-log window.
public struct StatsChartView: View {
    @Environment(QueryLogViewModel.self) private var log

    public init() {}

    public var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            let hourly = log.blockedPerHourToday
            if hourly.contains(where: { $0.blocked > 0 }) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Blocked today, by hour")
                        .font(.headline)
                    Chart(hourly, id: \.hour) { item in
                        BarMark(
                            x: .value("Hour", item.hour),
                            y: .value("Blocked", item.blocked)
                        )
                        .foregroundStyle(.red.gradient)
                        .cornerRadius(3)
                    }
                    .chartXScale(domain: 0...23)
                    .chartXAxis {
                        AxisMarks(values: [0, 6, 12, 18, 23])
                    }
                    .frame(height: 140)
                }
            }

            let top = log.topBlockedDomains
            if !top.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Top blocked")
                        .font(.headline)
                    ForEach(top, id: \.domain) { entry in
                        HStack {
                            Text(entry.domain)
                                .font(.callout.monospaced())
                                .lineLimit(1)
                                .truncationMode(.middle)
                            Spacer()
                            Text("\(entry.count)")
                                .font(.callout.weight(.semibold))
                                .foregroundStyle(.red)
                        }
                        .padding(.vertical, 2)
                    }
                }
            }

            if !log.records.contains(where: { $0.verdict == .blocked }) {
                ContentUnavailableView(
                    "Nothing blocked yet",
                    systemImage: "chart.bar",
                    description: Text("Turn protection on and browse — blocked queries land here.")
                )
                .frame(height: 160)
            }
        }
        .padding(16)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .padding(.horizontal)
        .onAppear { log.startPolling() }
    }
}
#endif
