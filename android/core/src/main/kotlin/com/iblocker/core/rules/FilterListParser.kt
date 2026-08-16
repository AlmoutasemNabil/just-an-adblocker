package com.iblocker.core.rules

data class ParsedFilterList(
    val blockDomains: MutableList<String> = ArrayList(),
    val allowDomains: MutableList<String> = ArrayList(),
    var totalLines: Int = 0,
    var commentLines: Int = 0,
    var skippedLines: Int = 0,
)

/**
 * One parser for every DNS-blocklist format in the wild, with per-line
 * autodetection:
 *
 *   hosts lines        `0.0.0.0 ads.example.com`, `127.0.0.1 x.com y.com`, `:: z.com`
 *   raw domain lists   `ads.example.com`
 *   AdGuard DNS rules  `||ads.example.com^`  (+`$important`), `@@||good.example.com^` allows
 *   comments           `#…`, `!…`, `[Adblock Plus 2.0]` headers
 *
 * Every entry blocks the domain and all of its subdomains. Anything the
 * grammar does not cover (regex rules, cosmetic `##` rules, other `$`
 * modifiers, mid-domain wildcards) is counted as skipped, never an error.
 */
object FilterListParser {

    private val sinkAddresses = setOf(
        "0.0.0.0", "127.0.0.1", "::", "::1", "0:0:0:0:0:0:0:0", "255.255.255.255",
        "fe80::1%lo0", "ff00::0", "ff02::1", "ff02::2", "ff02::3",
    )

    private val localhostNames = setOf(
        "localhost", "localhost.localdomain", "local", "broadcasthost",
        "ip6-localhost", "ip6-loopback", "ip6-localnet", "ip6-mcastprefix",
        "ip6-allnodes", "ip6-allrouters", "ip6-allhosts", "0.0.0.0",
    )

    fun parse(text: String): ParsedFilterList {
        val result = ParsedFilterList()
        // Splitting on any line terminator keeps CRLF lists (plenty of them
        // in the wild) from leaving a stray \r on every domain.
        for (line in text.split('\n')) {
            parseLine(line.removeSuffix("\r"), result)
        }
        return result
    }

    fun parseLine(rawLine: String, result: ParsedFilterList) {
        result.totalLines += 1
        val line = rawLine.trim()

        if (line.isEmpty()) {
            result.commentLines += 1
            return
        }
        if (line.startsWith("#") || line.startsWith("!") || line.startsWith("[")) {
            result.commentLines += 1
            return
        }

        // Cosmetic/element-hiding and scriptlet rules are not DNS rules —
        // never let `example.com##.ad` fall through and block example.com.
        if (line.contains("##") || line.contains("#@#") || line.contains("#?#") || line.contains("#\$#")) {
            result.skippedLines += 1
            return
        }

        // AdGuard-style allow: @@||domain^ (any trailing modifiers tolerated).
        if (line.startsWith("@@")) {
            val domain = if (line.startsWith("@@||")) {
                adGuardDomain(line.substring(4), allowAnyModifier = true)
            } else {
                null
            }
            if (domain != null) result.allowDomains.add(domain) else result.skippedLines += 1
            return
        }

        // AdGuard-style block: ||domain^ with no modifiers (or $important).
        if (line.startsWith("||")) {
            val domain = adGuardDomain(line.substring(2), allowAnyModifier = false)
            if (domain != null) result.blockDomains.add(domain) else result.skippedLines += 1
            return
        }

        // Hosts-file line: sink address followed by hostnames.
        val tokens = line.split(' ', '\t').filter { it.isNotEmpty() }
        if (tokens.isNotEmpty() && sinkAddresses.contains(tokens[0])) {
            var added = false
            var invalid = false
            for (token in tokens.drop(1)) {
                if (token.startsWith("#")) break
                if (localhostNames.contains(token)) continue
                val domain = DomainValidator.normalize(token)
                if (domain != null) {
                    result.blockDomains.add(domain)
                    added = true
                } else {
                    invalid = true
                }
            }
            if (invalid && !added) {
                result.skippedLines += 1
            } else if (!added) {
                // Pure localhost boilerplate (127.0.0.1 localhost etc.).
                result.commentLines += 1
            }
            return
        }

        // Bare domain (strip an inline comment first).
        val bare = line.substringBefore('#').trim()
        val domain = DomainValidator.normalize(bare)
        if (domain != null) result.blockDomains.add(domain) else result.skippedLines += 1
    }

    /**
     * Extracts the domain from the remainder of an AdGuard rule after `||`.
     * Accepts `domain^`, `domain` (end of line), and `domain^$important`;
     * with [allowAnyModifier] (used for `@@` allows) any `$` suffix is fine.
     */
    private fun adGuardDomain(remainder: String, allowAnyModifier: Boolean): String? {
        val caret = remainder.indexOf('^')
        val domainPart: String
        val afterCaret: String
        if (caret >= 0) {
            domainPart = remainder.substring(0, caret)
            afterCaret = remainder.substring(caret + 1)
        } else {
            domainPart = remainder
            afterCaret = ""
        }

        if (afterCaret.isNotEmpty()) {
            if (!afterCaret.startsWith("$")) return null
            if (!allowAnyModifier && afterCaret != "\$important") return null
        }

        if (domainPart.contains("/") || domainPart.contains("*")) return null
        return DomainValidator.normalize(domainPart)
    }
}
