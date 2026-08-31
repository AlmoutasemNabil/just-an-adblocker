import WidgetKit
import SwiftUI

@main
struct IBlockerWidgetBundle: WidgetBundle {
    var body: some Widget {
        StatusWidget()
        if #available(iOS 18.0, *) {
            ProtectionControl()
        }
    }
}
