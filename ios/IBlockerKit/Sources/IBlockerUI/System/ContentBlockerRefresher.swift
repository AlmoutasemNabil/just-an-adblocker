#if os(iOS)
import Foundation
import SafariServices
import IBlockerKit

/// Downloads EasyList, converts the supported subset to Safari
/// content-blocker JSON in the App Group, and asks Safari to reload the
/// blocker extension.
public enum ContentBlockerRefresher {

    public static let easyListURL = URL(string: "https://easylist.to/easylist/easylist.txt")!

    @discardableResult
    public static func refresh(paths: AppGroupPaths) async throws -> EasyListConverter.Stats {
        let (data, response) = try await URLSession.shared.data(from: easyListURL)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200,
              let text = String(data: data, encoding: .utf8) else {
            throw URLError(.badServerResponse)
        }

        let (rules, stats) = await Task.detached(priority: .userInitiated) {
            EasyListConverter.convert(text)
        }.value

        let json = try ContentBlockerRule.encodeList(rules)
        try? paths.ensureDirectories()
        try json.write(to: paths.contentBlockerJSONURL, options: .atomic)

        try await SFContentBlockerManager.reloadContentBlocker(
            withIdentifier: AppEnvironment.contentBlockerBundleID
        )
        return stats
    }
}
#endif
