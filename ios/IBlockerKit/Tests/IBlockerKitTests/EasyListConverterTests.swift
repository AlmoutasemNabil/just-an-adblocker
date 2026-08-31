import XCTest
@testable import IBlockerKit

final class EasyListConverterTests: XCTestCase {

    func testConvertsSupportedSubset() throws {
        let text = """
        [Adblock Plus 2.0]
        ! comment
        ||ads.example.com^
        ||tracker.example.net^$important
        ||skipme.example.com^$third-party
        example.com##.banner
        foo.com,bar.com###ad-slot
        ##.generic-ad
        ~negated.com##.x
        @@||exception.com^
        example.com#@#.unhide
        /regex-rule/
        """

        let (rules, stats) = EasyListConverter.convert(text)

        XCTAssertEqual(stats.blockRules, 2)
        XCTAssertEqual(stats.scopedHidingRules, 2)
        XCTAssertEqual(stats.genericHidingRules, 1)
        XCTAssertEqual(stats.skippedLines, 5)
        XCTAssertEqual(rules.count, 5)

        // Blocks come first.
        XCTAssertEqual(rules[0].action.type, "block")
        XCTAssertEqual(rules[0].trigger.urlFilter, "^https?://([^/:]+\\.)?ads\\.example\\.com[/:]")
        XCTAssertNil(rules[0].trigger.ifDomain)

        let scoped = rules[2]
        XCTAssertEqual(scoped.action.type, "css-display-none")
        XCTAssertEqual(scoped.action.selector, ".banner")
        XCTAssertEqual(scoped.trigger.ifDomain, ["*example.com"])

        let multiDomain = rules[3]
        XCTAssertEqual(multiDomain.trigger.ifDomain, ["*foo.com", "*bar.com"])
        XCTAssertEqual(multiDomain.action.selector, "#ad-slot")

        let generic = rules[4]
        XCTAssertEqual(generic.action.selector, ".generic-ad")
        XCTAssertNil(generic.trigger.ifDomain)
        XCTAssertEqual(generic.trigger.urlFilter, ".*")
    }

    func testJSONEncodingUsesSafariKeys() throws {
        let (rules, _) = EasyListConverter.convert("||ads.example.com^\nexample.com##.banner")
        let data = try ContentBlockerRule.encodeList(rules)
        let json = String(decoding: data, as: UTF8.self)
        XCTAssertTrue(json.contains("\"url-filter\""))
        XCTAssertTrue(json.contains("\"if-domain\""))
        XCTAssertTrue(json.contains("\"css-display-none\""))

        // Round-trips through Codable.
        let decoded = try JSONDecoder().decode([ContentBlockerRule].self, from: data)
        XCTAssertEqual(decoded, rules)
    }

    func testRuleCapTrimsLowestValueFirst() {
        var lines: [String] = []
        for i in 0..<10 { lines.append("||block\(i).example.com^") }
        for i in 0..<10 { lines.append("scoped\(i).example.com##.ad") }
        for i in 0..<10 { lines.append("##.generic\(i)") }

        let (rules, stats) = EasyListConverter.convert(lines.joined(separator: "\n"), maxRules: 15)
        XCTAssertEqual(rules.count, 15)
        XCTAssertEqual(stats.trimmedRules, 15)
        XCTAssertEqual(rules.prefix(10).filter { $0.action.type == "block" }.count, 10)
        XCTAssertEqual(stats.genericHidingRules, 0)
    }

    func testGenericHidingCap() {
        var lines: [String] = []
        for i in 0..<20 { lines.append("##.g\(i)") }
        let (rules, stats) = EasyListConverter.convert(lines.joined(separator: "\n"), maxGenericHiding: 5)
        XCTAssertEqual(rules.count, 5)
        XCTAssertEqual(stats.trimmedRules, 15)
    }
}
