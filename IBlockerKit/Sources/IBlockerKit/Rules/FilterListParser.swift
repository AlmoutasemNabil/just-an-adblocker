import Foundation

public struct ParsedFilterList: Sendable, Equatable {
    public var blockDomains: [String] = []
    public var allowDomains: [String] = []
    public var totalLines = 0
    public var commentLines = 0
    public var skippedLines = 0

    public init() {}
}

/// One parser for every DNS-blocklist format in the wild, with per-line
/// autodetection:
///
///   hosts lines        `0.0.0.0 ads.example.com`, `127.0.0.1 x.com y.com`, `:: z.com`
///   raw domain lists   `ads.example.com`
///   AdGuard DNS rules  `||ads.example.com^`  (+`$important`), `@@||good.example.com^` allows
///   comments           `#…`, `!…`, `[Adblock Plus 2.0]` headers
///
/// Every entry blocks the domain and all of its subdomains. Anything the
/// grammar does not cover (regex rules, cosmetic `##` rules, other `$`
/// modifiers, mid-domain wildcards) is counted as skipped, never an error.
public enum FilterListParser {

    private static let sinkAddresses: Set<Substring> = [
        "0.0.0.0", "127.0.0.1", "::", "::1", "0:0:0:0:0:0:0:0", "255.255.255.255",
        "fe80::1%lo0", "ff00::0", "ff02::1", "ff02::2", "ff02::3",
    ]

    private static let localhostNames: Set<String> = [
        "localhost", "localhost.localdomain", "local", "broadcasthost",
        "ip6-localhost", "ip6-loopback", "ip6-localnet", "ip6-mcastprefix",
        "ip6-allnodes", "ip6-allrouters", "ip6-allhosts", "0.0.0.0",
    ]

    public static func parse(_ text: String) -> ParsedFilterList {
        var result = ParsedFilterList()
        text.enumerateLines { line, _ in
            parseLine(line, into: &result)
        }
        return result
    }

    static func parseLine(_ rawLine: String, into result: inout ParsedFilterList) {
        result.totalLines += 1
        let line = rawLine.trimmingCharacters(in: .whitespaces)

        guard !line.isEmpty else {
            result.commentLines += 1
            return
        }
        if line.hasPrefix("#") || line.hasPrefix("!") || line.hasPrefix("[") {
            result.commentLines += 1
            return
        }

        // Cosmetic/element-hiding and scriptlet rules are not DNS rules —
        // never let `example.com##.ad` fall through and block example.com.
        if line.contains("##") || line.contains("#@#") || line.contains("#?#") || line.contains("#$#") {
            result.skippedLines += 1
            return
        }

        // AdGuard-style allow: @@||domain^ (any trailing modifiers tolerated).
        if line.hasPrefix("@@") {
            if line.hasPrefix("@@||"), let domain = adGuardDomain(line.dropFirst(4), allowAnyModifier: true) {
                result.allowDomains.append(domain)
            } else {
                result.skippedLines += 1
            }
            return
        }

        // AdGuard-style block: ||domain^ with no modifiers (or $important).
        if line.hasPrefix("||") {
            if let domain = adGuardDomain(line.dropFirst(2), allowAnyModifier: false) {
                result.blockDomains.append(domain)
            } else {
                result.skippedLines += 1
            }
            return
        }

        // Hosts-file line: sink address followed by hostnames.
        let tokens = line.split(separator: " ", omittingEmptySubsequences: true)
            .flatMap { $0.split(separator: "\t", omittingEmptySubsequences: true) }
        if let first = tokens.first, sinkAddresses.contains(first) {
            var added = false
            var invalid = false
            for token in tokens.dropFirst() {
                if token.hasPrefix("#") { break }
                let name = String(token)
                if localhostNames.contains(name) { continue }
                if let domain = DomainValidator.normalize(name) {
                    result.blockDomains.append(domain)
                    added = true
                } else {
                    invalid = true
                }
            }
            if invalid && !added {
                result.skippedLines += 1
            } else if !added {
                // Pure localhost boilerplate (127.0.0.1 localhost etc.).
                result.commentLines += 1
            }
            return
        }

        // Bare domain (strip an inline comment first).
        let bare = line.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false)[0]
            .trimmingCharacters(in: .whitespaces)
        if let domain = DomainValidator.normalize(bare) {
            result.blockDomains.append(domain)
        } else {
            result.skippedLines += 1
        }
    }

    /// Extracts the domain from the remainder of an AdGuard rule after `||`.
    /// Accepts `domain^`, `domain` (end of line), and `domain^$important`;
    /// with `allowAnyModifier` (used for `@@` allows) any `$` suffix is fine.
    private static func adGuardDomain(_ remainder: Substring, allowAnyModifier: Bool) -> String? {
        let domainPart: Substring
        let afterCaret: Substring
        if let caret = remainder.firstIndex(of: "^") {
            domainPart = remainder[..<caret]
            afterCaret = remainder[remainder.index(after: caret)...]
        } else {
            domainPart = remainder
            afterCaret = ""
        }

        if !afterCaret.isEmpty {
            guard afterCaret.hasPrefix("$") else { return nil }
            guard allowAnyModifier || afterCaret == "$important" else { return nil }
        }

        guard !domainPart.contains("/"), !domainPart.contains("*") else { return nil }
        return DomainValidator.normalize(String(domainPart))
    }
}
