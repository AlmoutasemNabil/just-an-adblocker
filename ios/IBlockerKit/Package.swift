// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "IBlockerKit",
    platforms: [
        .iOS("18.0"),
        .macOS("15.0"),
    ],
    products: [
        .library(name: "IBlockerKit", targets: ["IBlockerKit"]),
        .library(name: "IBlockerTunnelKit", targets: ["IBlockerTunnelKit"]),
        .library(name: "IBlockerUI", targets: ["IBlockerUI"]),
    ],
    targets: [
        .target(name: "IBlockerKit"),
        .target(name: "IBlockerTunnelKit", dependencies: ["IBlockerKit"]),
        .target(name: "IBlockerUI", dependencies: ["IBlockerKit"]),
        .testTarget(name: "IBlockerKitTests", dependencies: ["IBlockerKit"]),
    ],
    swiftLanguageModes: [.v5]
)
