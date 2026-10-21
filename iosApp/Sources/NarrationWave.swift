import Foundation

enum NarrationWave {
    static func isComplete(_ url: URL) -> Bool {
        do {
            let handle = try FileHandle(forReadingFrom: url); defer { try? handle.close() }
            let size = try handle.seekToEnd()
            guard size >= 44, size <= 256 * 1024 * 1024 else { return false }
            try handle.seek(toOffset: 0)
            guard let header = try handle.read(upToCount: 12), header.count == 12,
                  String(decoding: header[0..<4], as: UTF8.self) == "RIFF", String(decoding: header[8..<12], as: UTF8.self) == "WAVE" else { return false }
            func uint32(_ bytes: Data, _ offset: Int) -> UInt32 { bytes.withUnsafeBytes { UInt32(littleEndian: $0.loadUnaligned(fromByteOffset: offset, as: UInt32.self)) } }
            func uint16(_ bytes: Data, _ offset: Int) -> UInt16 { bytes.withUnsafeBytes { UInt16(littleEndian: $0.loadUnaligned(fromByteOffset: offset, as: UInt16.self)) } }
            guard UInt64(uint32(header, 4)) + 8 == size else { return false }
            var cursor: UInt64 = 12, formatFound = false, dataFound = false, chunks = 0
            while cursor < size {
                chunks += 1; guard chunks <= 128 else { return false }
                try handle.seek(toOffset: cursor)
                guard let chunk = try handle.read(upToCount: 8), chunk.count == 8 else { return false }
                let length = UInt64(uint32(chunk, 4)), start = cursor + 8
                guard length <= size - start, length + (length % 2) <= size - start else { return false }
                switch String(decoding: chunk[0..<4], as: UTF8.self) {
                case "fmt ":
                    guard !formatFound, (16...1024).contains(length), let fields = try handle.read(upToCount: Int(length)), fields.count == Int(length),
                          uint16(fields, 0) == 1, uint16(fields, 2) == 1, uint32(fields, 4) == 24_000, uint16(fields, 12) == 2, uint16(fields, 14) == 16 else { return false }
                    formatFound = true
                case "data":
                    guard !dataFound, length > 0, length % 2 == 0 else { return false }
                    try handle.seek(toOffset: start + length - 2)
                    guard try handle.read(upToCount: 2)?.count == 2 else { return false }
                    dataFound = true
                default: break
                }
                cursor = start + length + (length % 2)
            }
            return formatFound && dataFound && cursor == size
        } catch { return false }
    }
}
