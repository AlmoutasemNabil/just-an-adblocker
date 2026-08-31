#if os(iOS)
import SwiftUI
import IBlockerKit

/// Editor for the personal allow/deny domain lists.
struct AllowDenyView: View {
    enum Mode {
        case allow
        case deny

        var title: String {
            switch self {
            case .allow: return "My Allowlist"
            case .deny: return "My Blocklist"
            }
        }

        var explanation: String {
            switch self {
            case .allow: return "These domains (and their subdomains) are never blocked, even if a filter list contains them."
            case .deny: return "These domains (and their subdomains) are always blocked."
            }
        }
    }

    @Environment(FilterListsViewModel.self) private var lists
    let mode: Mode
    @State private var newDomain = ""
    @State private var invalidInput = false

    private var domains: [String] {
        switch mode {
        case .allow: return lists.state.userAllowlist
        case .deny: return lists.state.userDenylist
        }
    }

    var body: some View {
        List {
            Section {
                HStack {
                    TextField("example.com", text: $newDomain)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .onSubmit(add)
                    Button("Add", action: add)
                        .disabled(newDomain.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                if invalidInput {
                    Text("That doesn't look like a domain.")
                        .font(.caption)
                        .foregroundStyle(.orange)
                }
            } footer: {
                Text(mode.explanation)
            }

            Section {
                ForEach(domains, id: \.self) { domain in
                    Text(domain)
                        .font(.callout.monospaced())
                }
                .onDelete { offsets in
                    let doomed = offsets.map { domains[$0] }
                    Task {
                        for domain in doomed {
                            switch mode {
                            case .allow: await lists.removeAllow(domain)
                            case .deny: await lists.removeDeny(domain)
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle(mode.title)
    }

    private func add() {
        let candidate = newDomain.trimmingCharacters(in: .whitespaces)
        guard DomainValidator.normalize(candidate) != nil else {
            invalidInput = true
            return
        }
        invalidInput = false
        newDomain = ""
        Task {
            switch mode {
            case .allow: await lists.addAllow(candidate)
            case .deny: await lists.addDeny(candidate)
            }
        }
    }
}
#endif
