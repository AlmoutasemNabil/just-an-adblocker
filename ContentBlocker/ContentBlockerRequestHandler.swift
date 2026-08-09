import Foundation
import IBlockerKit

/// Serves Safari the content-blocker rule JSON: the app-generated file from
/// the App Group when present, otherwise the small bundled fallback.
final class ContentBlockerRequestHandler: NSObject, NSExtensionRequestHandling {

    func beginRequest(with context: NSExtensionContext) {
        var attachment: NSItemProvider?

        if let groupID = AppGroupPaths.groupID(from: Bundle.main),
           let paths = AppGroupPaths(groupID: groupID),
           FileManager.default.fileExists(atPath: paths.contentBlockerJSONURL.path) {
            attachment = NSItemProvider(contentsOf: paths.contentBlockerJSONURL)
        }
        if attachment == nil, let bundled = Bundle.main.url(forResource: "blockerList", withExtension: "json") {
            attachment = NSItemProvider(contentsOf: bundled)
        }

        guard let attachment else {
            context.completeRequest(returningItems: nil)
            return
        }
        let item = NSExtensionItem()
        item.attachments = [attachment]
        context.completeRequest(returningItems: [item])
    }
}
