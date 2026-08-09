import Foundation

/// The compiled on-disk blocklist format shared between the app (writer) and
/// the tunnel extension (reader). Designed to be mmap'd: the tunnel never
/// materializes the domain set in its 50 MB memory budget.
///
///     offset 0   magic "IBK1"                    (4 bytes)
///     offset 4   format version, u32 LE          (= 1)
///     offset 8   entry count, u32 LE
///     offset 12  generation, u32 LE              (monotonic, for change detection)
///     offset 16  reserved, 16 zero bytes
///     offset 32  count × u64 LE FNV-1a hashes, ascending
public enum CompiledBlocklist {
    public static let magic: [UInt8] = Array("IBK1".utf8)
    public static let formatVersion: UInt32 = 1
    public static let headerSize = 32

    public static func serialize(hashes: some Collection<UInt64>, generation: UInt32) -> Data {
        let sorted = Array(Set(hashes)).sorted()
        var data = Data(capacity: headerSize + sorted.count * 8)
        data.append(contentsOf: magic)
        appendU32(&data, formatVersion)
        appendU32(&data, UInt32(sorted.count))
        appendU32(&data, generation)
        data.append(contentsOf: [UInt8](repeating: 0, count: 16))
        for hash in sorted {
            var le = hash.littleEndian
            withUnsafeBytes(of: &le) { data.append(contentsOf: $0) }
        }
        return data
    }

    /// Atomically writes the compiled blob so the tunnel never sees a
    /// half-written file.
    public static func write(hashes: some Collection<UInt64>, generation: UInt32, to url: URL) throws {
        let data = serialize(hashes: hashes, generation: generation)
        try data.write(to: url, options: .atomic)
    }

    private static func appendU32(_ data: inout Data, _ value: UInt32) {
        var le = value.littleEndian
        withUnsafeBytes(of: &le) { data.append(contentsOf: $0) }
    }
}
