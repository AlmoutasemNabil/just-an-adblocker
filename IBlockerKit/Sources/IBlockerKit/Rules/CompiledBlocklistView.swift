import Foundation

/// Read-only, memory-mapped view over a compiled blocklist blob.
/// Lookups binary-search the file-backed pages directly, so resident memory
/// stays tiny regardless of list size.
public final class CompiledBlocklistView: Sendable {
    public enum ReadError: Error {
        case badMagic
        case unsupportedVersion
        case corrupt
    }

    private let data: Data
    public let count: Int
    public let generation: UInt32

    public init(contentsOf url: URL) throws {
        let mapped = try Data(contentsOf: url, options: .alwaysMapped)
        guard mapped.count >= CompiledBlocklist.headerSize else { throw ReadError.corrupt }
        guard Array(mapped.prefix(4)) == CompiledBlocklist.magic else { throw ReadError.badMagic }

        let version = Self.readU32(mapped, at: 4)
        guard version == CompiledBlocklist.formatVersion else { throw ReadError.unsupportedVersion }

        let entryCount = Int(Self.readU32(mapped, at: 8))
        guard mapped.count >= CompiledBlocklist.headerSize + entryCount * 8 else { throw ReadError.corrupt }

        self.data = mapped
        self.count = entryCount
        self.generation = Self.readU32(mapped, at: 12)
    }

    public var isEmpty: Bool { count == 0 }

    public func contains(_ hash: UInt64) -> Bool {
        guard count > 0 else { return false }
        return data.withUnsafeBytes { (buffer: UnsafeRawBufferPointer) -> Bool in
            var low = 0
            var high = count - 1
            while low <= high {
                let mid = (low + high) / 2
                let offset = CompiledBlocklist.headerSize + mid * 8
                let value = UInt64(littleEndian: buffer.loadUnaligned(fromByteOffset: offset, as: UInt64.self))
                if value == hash {
                    return true
                } else if value < hash {
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            return false
        }
    }

    private static func readU32(_ data: Data, at offset: Int) -> UInt32 {
        data.withUnsafeBytes { buffer in
            UInt32(littleEndian: buffer.loadUnaligned(fromByteOffset: offset, as: UInt32.self))
        }
    }
}
