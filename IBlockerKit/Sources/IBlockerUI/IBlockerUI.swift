/// SwiftUI app layer. All real content is iOS-only; this constant keeps the
/// module non-empty for non-iOS builds (Linux/macOS CI test runs).
public enum IBlockerUIModule {
    public static let name = "IBlockerUI"
}
