import Foundation

/// One rule in Safari's content-blocker JSON format.
public struct ContentBlockerRule: Codable, Sendable, Equatable {
    public struct Trigger: Codable, Sendable, Equatable {
        public var urlFilter: String
        public var ifDomain: [String]?

        enum CodingKeys: String, CodingKey {
            case urlFilter = "url-filter"
            case ifDomain = "if-domain"
        }

        public init(urlFilter: String, ifDomain: [String]? = nil) {
            self.urlFilter = urlFilter
            self.ifDomain = ifDomain
        }
    }

    public struct Action: Codable, Sendable, Equatable {
        public var type: String
        public var selector: String?

        public init(type: String, selector: String? = nil) {
            self.type = type
            self.selector = selector
        }
    }

    public var trigger: Trigger
    public var action: Action

    public init(trigger: Trigger, action: Action) {
        self.trigger = trigger
        self.action = action
    }

    public static func encodeList(_ rules: [ContentBlockerRule]) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.withoutEscapingSlashes]
        return try encoder.encode(rules)
    }
}
