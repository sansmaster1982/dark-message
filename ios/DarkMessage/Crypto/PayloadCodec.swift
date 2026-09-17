import Foundation

/// Binary payload format — identical to Android version:
/// [version: 1B][contentType: 1B][salt: 16B][nonce: 12B][ciphertext+GCM_tag: NB]
enum PayloadCodec {

    static let headerSize = 30  // 1 + 1 + 16 + 12
    static let gcmTagSize = 16
    static let minPayloadSize = headerSize + gcmTagSize  // 46

    struct DecodedPayload {
        let version: UInt8
        let contentType: ContentType
        let salt: Data
        let nonce: Data
        let ciphertext: Data   // includes GCM tag appended
    }

    static func encode(
        contentType: ContentType,
        salt: Data,
        nonce: Data,
        ciphertext: Data
    ) -> Data {
        var payload = Data(capacity: headerSize + ciphertext.count)
        payload.append(0x01)                    // version
        payload.append(contentType.rawValue)     // content type
        payload.append(salt)                     // 16 bytes
        payload.append(nonce)                    // 12 bytes
        payload.append(ciphertext)               // ciphertext + GCM tag
        return payload
    }

    /// The six ASCII whitespace bytes. One set is used everywhere transport damage is
    /// tolerated: in front of a payload, at the end of one, and inside pasted base64.
    /// Android uses exactly these six in the same three places.
    static let asciiWhitespace: Set<UInt8> = [0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20]

    /// Bytes that some transports put in front of an attachment. A valid payload
    /// always starts with the version byte 0x01, so none of these can ever be the
    /// real first byte and skipping them cannot damage a good file.
    private static let leadingNoise = asciiWhitespace

    /// The same set as a scalar test, for the pasted-text path.
    private static func isAsciiWhitespace(_ scalar: Unicode.Scalar) -> Bool {
        guard scalar.value < 0x80 else { return false }
        return asciiWhitespace.contains(UInt8(scalar.value))
    }

    /// Drops such bytes from the front. Observed in the wild on 2026-09-13: every
    /// `.darkm` file that reached an Android phone from an iPhone carried a leading
    /// CR LF, and the receiver rejected all of them as a bad format. Nothing is
    /// stripped from the END: the last byte belongs to the GCM tag and may legally
    /// be 0x0A.
    static func withoutLeadingNoise(_ data: Data) -> Data {
        var start = data.startIndex
        while start < data.endIndex {
            // A UTF-8 byte order mark, which anything that treats the attachment as
            // text is liable to prepend. 0xEF can never be a version byte either.
            let third = data.index(start, offsetBy: 2, limitedBy: data.index(before: data.endIndex))
            if let third,
               data[start] == 0xEF,
               data[data.index(after: start)] == 0xBB,
               data[third] == 0xBF {
                start = data.index(start, offsetBy: 3)
                continue
            }
            if leadingNoise.contains(data[start]) {
                start = data.index(after: start)
                continue
            }
            break
        }
        return start == data.startIndex ? data : Data(data[start...])
    }

    /// Last resort for a file whose bytes are not a payload at all but the base64
    /// TEXT of one. A transport that treats the attachment as text can hand over
    /// exactly that, and for the person holding the phone it is indistinguishable
    /// from a damaged file: everything else about the message is intact.
    ///
    /// Only attempted after the binary parse has already failed, and only when the
    /// bytes really are printable base64, so a genuinely damaged file still fails.
    /// Mirrors `PayloadCodec.decodeFromBase64Text` on Android.
    static func decodeFromBase64Text(_ data: Data) -> DecodedPayload? {
        let minBase64TextSize = 60            // 46 payload bytes in base64
        let maxBase64TextSize = 64 * 1024 * 1024
        guard data.count >= minBase64TextSize, data.count <= maxBase64TextSize else { return nil }
        guard data.allSatisfy({ $0 < 0x80 }) else { return nil }
        guard let text = String(data: data, encoding: .ascii),
              let normalized = normalizedBase64(text),
              let decoded = Data(base64Encoded: normalized) else { return nil }
        return try? decode(decoded)
    }

    static func decode(_ raw: Data) throws -> DecodedPayload {
        let data = withoutLeadingNoise(raw)

        guard data.count >= minPayloadSize else {
            throw CryptoError.invalidFormat
        }

        let version = data[data.startIndex]
        guard version == 0x01 else {
            throw CryptoError.invalidFormat
        }

        let typeByte = data[data.startIndex + 1]
        guard let contentType = ContentType(rawValue: typeByte) else {
            throw CryptoError.invalidFormat
        }

        let saltStart = data.startIndex + 2
        let salt = data[saltStart..<saltStart + 16]

        let nonceStart = saltStart + 16
        let nonce = data[nonceStart..<nonceStart + 12]

        let ciphertext = data[(nonceStart + 12)...]

        return DecodedPayload(
            version: version,
            contentType: contentType,
            salt: Data(salt),
            nonce: Data(nonce),
            ciphertext: Data(ciphertext)
        )
    }

    /// Normalises base64 that travelled as TEXT through a messenger or a mail client.
    /// Removes every whitespace character (clients wrap long lines), accepts the
    /// URL-safe alphabet as well as the standard one, and restores missing padding.
    /// Returns nil when what is left is not base64 at all.
    ///
    /// Deliberately NOT a "keep only base64 characters" filter: a signature line such
    /// as "Sent from my iPhone" is made of letters that all belong to the alphabet, so
    /// such a filter would silently splice it into the message and produce garbage.
    /// Mirrors `PayloadCodec.normalizeBase64` on Android: same whitespace set, same
    /// alphabet, same padding rule.
    static func normalizedBase64(_ raw: String) -> String? {
        var compact = String.UnicodeScalarView()
        // Walks SCALARS, not Characters. A CR LF pair is a single Character in Swift
        // but two Chars in Kotlin, so a Character-based loop failed to recognise the
        // most common line break of all and rejected the whole paste, while Android
        // accepted it. Caught by the simulator test the first time it ever ran.
        for scalar in raw.unicodeScalars {
            if Self.isAsciiWhitespace(scalar) { continue }
            switch scalar {
            case "-": compact.append("+")
            case "_": compact.append("/")
            default: compact.append(scalar)
            }
        }
        if compact.isEmpty { return nil }
        for scalar in compact {
            let value = scalar.value
            let isAllowed = (value >= 65 && value <= 90)      // A-Z
                || (value >= 97 && value <= 122)              // a-z
                || (value >= 48 && value <= 57)               // 0-9
                || value == 43 || value == 47 || value == 61  // + / =
            if !isAllowed { return nil }
        }
        let compactString = String(compact)
        var body = compactString
        while body.hasSuffix("=") { body.removeLast() }
        let remainder = body.count % 4
        if remainder == 1 { return nil }
        let padding = remainder == 0 ? "" : String(repeating: "=", count: 4 - remainder)
        return body + padding
    }
}
