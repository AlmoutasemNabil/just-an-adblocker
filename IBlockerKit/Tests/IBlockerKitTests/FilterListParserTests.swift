import XCTest
@testable import IBlockerKit

final class FilterListParserTests: XCTestCase {

    func testParsesMixedFormats() {
        let text = """
        [Adblock Plus 2.0]
        ! Title: Sample list
        # hosts-style comment

        0.0.0.0 ads.example.com
        0.0.0.0 tracker.one.net tracker.two.net
        127.0.0.1 localhost
        :: metrics.example.org
        0.0.0.0 localhost.localdomain broadcasthost

        plain-domain.example.io
        inline.example.com # with a trailing note

        ||adservice.example.net^
        ||important.example.net^$important
        ||modified.example.net^$third-party
        @@||allowed.example.com^
        @@||alsoallowed.example.com^$badfilter

        example.com##.ad-banner
        /banner[0-9]+/
        ||wild*.example.com^
        not_a_domain
        """

        let parsed = FilterListParser.parse(text)

        XCTAssertEqual(Set(parsed.blockDomains), [
            "ads.example.com",
            "tracker.one.net", "tracker.two.net",
            "metrics.example.org",
            "plain-domain.example.io",
            "inline.example.com",
            "adservice.example.net",
            "important.example.net",
        ])
        XCTAssertEqual(Set(parsed.allowDomains), [
            "allowed.example.com",
            "alsoallowed.example.com",
        ])

        // ||modified…$third-party, cosmetic, regex, wildcard, not_a_domain
        XCTAssertEqual(parsed.skippedLines, 5)
    }

    func testLocalhostFamilyIsIgnoredSilently() {
        let parsed = FilterListParser.parse("""
        127.0.0.1 localhost
        ::1 ip6-localhost ip6-loopback
        """)
        XCTAssertTrue(parsed.blockDomains.isEmpty)
        XCTAssertEqual(parsed.skippedLines, 0)
    }

    func testHostsLineWithOnlyInvalidNamesCountsSkipped() {
        let parsed = FilterListParser.parse("0.0.0.0 %%bogus%%")
        XCTAssertTrue(parsed.blockDomains.isEmpty)
        XCTAssertEqual(parsed.skippedLines, 1)
    }

    func testNonASCIILinesAreSkipped() {
        let parsed = FilterListParser.parse("münchen-ads.example.de")
        XCTAssertTrue(parsed.blockDomains.isEmpty)
        XCTAssertEqual(parsed.skippedLines, 1)
    }

    func testDomainValidator() {
        XCTAssertEqual(DomainValidator.normalize("ADS.Example.COM."), "ads.example.com")
        XCTAssertEqual(DomainValidator.normalize("  spaced.example.com "), "spaced.example.com")
        XCTAssertEqual(DomainValidator.normalize("under_score.example.com"), "under_score.example.com")
        XCTAssertNil(DomainValidator.normalize("nodots"))
        XCTAssertNil(DomainValidator.normalize(".leading.example.com"))
        XCTAssertNil(DomainValidator.normalize("double..dot.com"))
        XCTAssertNil(DomainValidator.normalize("bad domain.com"))
        XCTAssertNil(DomainValidator.normalize("emoji🎉.example.com"))
        XCTAssertNil(DomainValidator.normalize(String(repeating: "a", count: 64) + ".example.com"))
        XCTAssertNil(DomainValidator.normalize(
            (0..<40).map { _ in String(repeating: "x", count: 10) }.joined(separator: ".")
        ))
    }
}
