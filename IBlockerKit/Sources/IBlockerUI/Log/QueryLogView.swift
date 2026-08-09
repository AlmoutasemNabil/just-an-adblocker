#if os(iOS)
import SwiftUI
import IBlockerKit

public struct QueryLogView: View {
    @Environment(QueryLogViewModel.self) private var log
    @Environment(FilterListsViewModel.self) private var lists

    public init() {}

    public var body: some View {
        @Bindable var log = log
        NavigationStack {
            Group {
                let rows = log.filtered
                if rows.isEmpty {
                    ContentUnavailableView(
                        "No queries yet",
                        systemImage: "list.bullet.rectangle.portrait",
                        description: Text("DNS decisions appear here live while protection is on.")
                    )
                } else {
                    List(Array(rows.enumerated()), id: \.offset) { _, record in
                        QueryLogRow(record: record)
                            .contextMenu {
                                if record.verdict == .blocked {
                                    Button {
                                        Task { await lists.addAllow(record.domain) }
                                    } label: {
                                        Label("Allow this domain", systemImage: "checkmark.circle")
                                    }
                                } else {
                                    Button(role: .destructive) {
                                        Task { await lists.addDeny(record.domain) }
                                    } label: {
                                        Label("Block this domain", systemImage: "nosign")
                                    }
                                }
                                Button {
                                    UIPasteboard.general.string = record.domain
                                } label: {
                                    Label("Copy domain", systemImage: "doc.on.doc")
                                }
                            }
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle("Query Log")
            .searchable(text: $log.searchText, prompt: "Filter by domain")
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Picker("Filter", selection: $log.filter) {
                        ForEach(QueryLogViewModel.Filter.allCases) { filter in
                            Text(filter.rawValue).tag(filter)
                        }
                    }
                    .pickerStyle(.segmented)
                    .frame(maxWidth: 280)
                }
            }
        }
        .onAppear { log.startPolling() }
        .onDisappear { log.stopPolling() }
    }
}

struct QueryLogRow: View {
    let record: QueryLogRecord

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: symbol)
                .foregroundStyle(color)
                .font(.footnote.weight(.bold))
                .frame(width: 18)

            VStack(alignment: .leading, spacing: 2) {
                Text(record.domain)
                    .font(.callout.monospaced())
                    .lineLimit(1)
                    .truncationMode(.middle)
                HStack(spacing: 6) {
                    Text(DNSRecordType.name(record.qtype))
                        .font(.caption2.weight(.semibold))
                        .padding(.horizontal, 5)
                        .padding(.vertical, 1)
                        .background(.quaternary, in: Capsule())
                    Text(record.date, style: .time)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            Text(verdictText)
                .font(.caption.weight(.semibold))
                .foregroundStyle(color)
        }
        .padding(.vertical, 2)
    }

    private var symbol: String {
        switch record.verdict {
        case .blocked: return "nosign"
        case .allowed: return "checkmark"
        case .failed: return "exclamationmark.triangle"
        }
    }

    private var color: Color {
        switch record.verdict {
        case .blocked: return .red
        case .allowed: return .green
        case .failed: return .orange
        }
    }

    private var verdictText: String {
        switch record.verdict {
        case .blocked: return "Blocked"
        case .allowed: return "Allowed"
        case .failed: return "Failed"
        }
    }
}
#endif
