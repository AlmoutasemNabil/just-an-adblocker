#if os(iOS)
import SwiftUI
import IBlockerKit

public struct FilterListsView: View {
    @Environment(FilterListsViewModel.self) private var lists
    @State private var showAddSheet = false

    public init() {}

    public var body: some View {
        NavigationStack {
            List {
                if let stats = lists.lastCompileStats {
                    Section {
                        LabeledContent("Active rules", value: stats.blockedEntryCount.formatted())
                        if stats.skippedLines > 0 {
                            LabeledContent("Skipped lines", value: stats.skippedLines.formatted())
                        }
                    } footer: {
                        Text("Every rule blocks a domain and all of its subdomains, system-wide.")
                    }
                }

                Section("Blocklists") {
                    ForEach(lists.visibleSources) { source in
                        SourceRow(source: source)
                    }
                }

                Section {
                    NavigationLink {
                        AllowDenyView(mode: .allow)
                    } label: {
                        Label("My allowlist", systemImage: "checkmark.circle")
                            .badge(lists.state.userAllowlist.count)
                    }
                    NavigationLink {
                        AllowDenyView(mode: .deny)
                    } label: {
                        Label("My blocklist", systemImage: "nosign")
                            .badge(lists.state.userDenylist.count)
                    }
                } header: {
                    Text("Personal rules")
                } footer: {
                    Text("Your allowlist always wins — use it to unbreak a site.")
                }

                if let error = lists.errorMessage {
                    Section {
                        Label(error, systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.orange)
                    }
                }
            }
            .navigationTitle("Filter Lists")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        showAddSheet = true
                    } label: {
                        Label("Add list", systemImage: "plus")
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    if lists.isUpdating {
                        ProgressView()
                    } else {
                        Button {
                            Task { await lists.updateAndCompile(force: true) }
                        } label: {
                            Label("Update now", systemImage: "arrow.clockwise")
                        }
                    }
                }
            }
            .sheet(isPresented: $showAddSheet) {
                AddCustomListSheet()
            }
        }
    }
}

private struct SourceRow: View {
    @Environment(FilterListsViewModel.self) private var lists
    let source: FilterListSource

    var body: some View {
        let metadata = lists.metadata(for: source.id)
        Toggle(isOn: Binding(
            get: { source.enabled },
            set: { enabled in Task { await lists.setSource(id: source.id, enabled: enabled) } }
        )) {
            VStack(alignment: .leading, spacing: 3) {
                Text(source.name)
                HStack(spacing: 8) {
                    if source.enabled, metadata.entryCount > 0 {
                        Text("\(metadata.entryCount.formatted()) rules")
                    }
                    if let fetched = metadata.lastFetched {
                        Text("updated \(fetched, style: .relative) ago")
                    }
                }
                .font(.caption2)
                .foregroundStyle(.secondary)

                if let error = metadata.lastError {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .font(.caption2)
                        .foregroundStyle(.orange)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if !source.isBuiltIn {
                Button(role: .destructive) {
                    Task { await lists.removeSource(id: source.id) }
                } label: {
                    Label("Remove", systemImage: "trash")
                }
            }
        }
    }
}

private struct AddCustomListSheet: View {
    @Environment(FilterListsViewModel.self) private var lists
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var urlText = ""
    @State private var isValidating = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name", text: $name)
                    TextField("https://…", text: $urlText)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } footer: {
                    Text("Any hosts-format, domain-list or AdGuard-style DNS list URL. The list is test-downloaded before it's added.")
                }
                if let error = lists.errorMessage, isValidating == false {
                    Section {
                        Text(error).foregroundStyle(.orange)
                    }
                }
            }
            .navigationTitle("Add Blocklist")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if isValidating {
                        ProgressView()
                    } else {
                        Button("Add") {
                            guard let url = URL(string: urlText.trimmingCharacters(in: .whitespaces)),
                                  url.scheme == "https" else {
                                lists.errorMessage = "Enter a valid https:// URL"
                                return
                            }
                            isValidating = true
                            Task {
                                let displayName = name.isEmpty ? (url.host() ?? "Custom list") : name
                                if await lists.addCustomSource(name: displayName, url: url) {
                                    dismiss()
                                }
                                isValidating = false
                            }
                        }
                        .disabled(urlText.isEmpty)
                    }
                }
            }
        }
        .presentationDetents([.medium])
    }
}
#endif
