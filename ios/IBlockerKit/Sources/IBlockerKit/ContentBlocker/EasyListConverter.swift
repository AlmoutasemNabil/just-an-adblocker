import Foundation

/// Deliberately small EasyList → Safari content-blocker converter.
///
/// Supported subset (everything else counted as skipped):
///   `||domain^`            → block resource loads to the domain
///   `example.com##.sel`    → hide `.sel` on example.com pages
///   `##.sel`               → hide `.sel` everywhere (capped)
///
/// Safari enforces a per-blocker rule limit (150k since iOS 15); when over,
/// rules are trimmed lowest-value first: generic hiding, then scoped hiding,
/// then domain blocks.
public enum EasyListConverter {

    public struct Stats: Sendable, Equatable {
        public var blockRules = 0
        public var scopedHidingRules = 0
        public var genericHidingRules = 0
        public var skippedLines = 0
        public var trimmedRules = 0

        public init() {}

        public var totalRules: Int { blockRules + scopedHidingRules + genericHidingRules }
    }

    public static let safariRuleLimit = 150_000
    public static let genericHidingLimit = 5000

    public static func convert(_ text: String,
                               maxRules: Int = safariRuleLimit,
                               maxGenericHiding: Int = genericHidingLimit) -> (rules: [ContentBlockerRule], stats: Stats) {
        var stats = Stats()
        var blocks: [ContentBlockerRule] = []
        var scopedHiding: [ContentBlockerRule] = []
        var genericHiding: [ContentBlockerRule] = []

        text.enumerateLines { rawLine, _ in
            let line = rawLine.trimmingCharacters(in: .whitespaces)
            if line.isEmpty || line.hasPrefix("!") || line.hasPrefix("[") {
                return
            }
            // Exception rules (@@, #@#) are out of scope for the subset.
            if line.hasPrefix("@@") || line.contains("#@#") {
                stats.skippedLines += 1
                return
            }

            if let range = line.range(of: "##") {
                let domainsPart = line[..<range.lowerBound]
                let selector = String(line[range.upperBound...]).trimmingCharacters(in: .whitespaces)
                guard !selector.isEmpty, !selector.contains("\\") else {
                    stats.skippedLines += 1
                    return
                }
                if domainsPart.isEmpty {
                    if genericHiding.count < maxGenericHiding {
                        genericHiding.append(ContentBlockerRule(
                            trigger: .init(urlFilter: ".*"),
                            action: .init(type: "css-display-none", selector: selector)
                        ))
                    } else {
                        stats.trimmedRules += 1
                    }
                } else {
                    // Negated domains (~example.com) are unsupported.
                    let domains = domainsPart.split(separator: ",").map(String.init)
                    guard !domains.contains(where: { $0.hasPrefix("~") }) else {
                        stats.skippedLines += 1
                        return
                    }
                    let normalized = domains.compactMap { DomainValidator.normalize($0) }
                    guard !normalized.isEmpty else {
                        stats.skippedLines += 1
                        return
                    }
                    scopedHiding.append(ContentBlockerRule(
                        trigger: .init(urlFilter: ".*", ifDomain: normalized.map { "*" + $0 }),
                        action: .init(type: "css-display-none", selector: selector)
                    ))
                }
                return
            }

            if line.hasPrefix("||") {
                let remainder = line.dropFirst(2)
                let domainPart: Substring
                if let caret = remainder.firstIndex(of: "^") {
                    let after = remainder[remainder.index(after: caret)...]
                    guard after.isEmpty || after == "$important" else {
                        stats.skippedLines += 1
                        return
                    }
                    domainPart = remainder[..<caret]
                } else {
                    domainPart = remainder
                }
                guard let domain = DomainValidator.normalize(String(domainPart)) else {
                    stats.skippedLines += 1
                    return
                }
                blocks.append(ContentBlockerRule(
                    trigger: .init(urlFilter: blockFilter(for: domain)),
                    action: .init(type: "block")
                ))
                return
            }

            stats.skippedLines += 1
        }

        // Assemble in priority order, trimming lowest value first.
        var rules: [ContentBlockerRule] = []
        for group in [blocks, scopedHiding, genericHiding] {
            for rule in group {
                if rules.count < maxRules {
                    rules.append(rule)
                } else {
                    stats.trimmedRules += 1
                }
            }
        }

        stats.blockRules = min(blocks.count, rules.count)
        stats.scopedHidingRules = min(scopedHiding.count, max(0, rules.count - blocks.count))
        stats.genericHidingRules = max(0, rules.count - blocks.count - scopedHiding.count)
        return (rules, stats)
    }

    /// Anchored resource filter matching the domain and its subdomains.
    /// Uses only regex features WebKit's content-blocker DFA supports
    /// (no alternation): normalized URLs always have `/` or `:` after the host.
    static func blockFilter(for domain: String) -> String {
        let escaped = domain.replacingOccurrences(of: ".", with: "\\.")
        return "^https?://([^/:]+\\.)?" + escaped + "[/:]"
    }
}
