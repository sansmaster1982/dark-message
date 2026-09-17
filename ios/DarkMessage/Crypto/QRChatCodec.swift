import Foundation
import CryptoKit
import Security

// MARK: - Types

/// Header of a scanned `darkmessage://chat/1/<base64url>` code after syntax
/// validation, but before any key derivation: the PIN is not known at scan time.
struct ParsedQR: Equatable {
    let kdfId: UInt8
    let salt: Data
    let nonce: Data
    let ciphertextAndTag: Data
}

/// Decrypted contents of a QR chat invitation.
struct QRChatPayload: Equatable {
    /// Display name of the sender. `nil` when the sender left it empty.
    let name: String?
    /// The exact stored passphrase string: no trimming, no Unicode normalization.
    let passphrase: String
}

enum QRChatError: Error, Equatable {
    case notDarkMessage
    case unsupportedVersion
    case malformed
    case wrongPin
    case passphraseTooLong
    case nameTooLong
    case invalidPin
    case noPassphrase
}

// MARK: - Codec

/// Payload format v1 of the QR passphrase exchange.
///
///     qr-text = "darkmessage://chat/1/" base64url-nopad(body)
///     body    = kdfId(1) || salt(16) || nonce(12) || ciphertext || tag(16)
///     AAD     = "darkmessage-qr-v1" || kdfId || salt || nonce            (46 bytes)
///     key     = PBKDF2-HMAC-SHA512(pinBytes, salt, 600000, 32)
///     plain   = nameLen(1) || name(UTF-8, 0..64) || passphrase(UTF-8, 1..128)
///
/// Byte-compatible with the Android `ChatQrCodec`. Nothing in this file may log
/// its input, the PIN, the key, the plaintext or the passphrase.
enum QRChatCodec {

    // MARK: Constants

    /// Deep-link scheme registered in Info.plist.
    static let urlScheme = "darkmessage"

    /// 19 lowercase ASCII characters, compared byte-exactly.
    static let textPrefix = "darkmessage://chat/"

    /// The only version this implementation emits and accepts.
    static let supportedVersion = "1"

    /// PBKDF2-HMAC-SHA512, 600 000 iterations, 6-digit PIN.
    static let kdfIdPBKDF2: UInt8 = 0x01

    static let maxNameBytes = 64
    static let maxPassphraseBytes = 128
    static let pinDigits = 6

    private static let aadPrefix = "darkmessage-qr-v1"
    private static let saltCount = 16
    private static let nonceCount = 12
    private static let tagCount = 16
    private static let headerCount = 29          // kdfId + salt + nonce
    private static let minBodyBytes = 47
    private static let maxBodyBytes = 238
    private static let minBodyChars = 63
    private static let maxBodyChars = 318
    private static let maxTextBytes = 600

    // MARK: - Encoding (spec section 1.4)

    /// Builds the QR text. `salt` and `nonce` are injectable for the test vectors;
    /// production callers let them default to fresh CSPRNG values.
    static func encode(
        name: String,
        passphrase: String,
        pin: String,
        salt: Data = PBKDF2KeyDeriver().generateSalt(),
        nonce: Data = QRChatCodec.randomNonce()
    ) async throws -> String {

        // 1. passphrase: exact stored string, UTF-8, no trim, no normalization.
        let passphraseBytes = Data(passphrase.utf8)
        if passphraseBytes.isEmpty {
            throw QRChatError.noPassphrase
        }
        if passphraseBytes.count > maxPassphraseBytes {
            throw QRChatError.passphraseTooLong
        }

        // 2. name: trimmed, optional.
        // ASCII-only trimming, identical to the Android side: Swift's
        // .whitespacesAndNewlines and Kotlin's trim() cover different sets
        // (U+00A0 vs U+001C-U+001F), which would rename the chat across platforms.
        let trimmedName = asciiTrimmed(name)
        let nameBytes = Data(trimmedName.utf8)
        if nameBytes.count > maxNameBytes {
            throw QRChatError.nameTooLong
        }

        // 3. pin and injected randomness.
        guard isValidPin(pin) else {
            throw QRChatError.invalidPin
        }
        guard salt.count == saltCount, nonce.count == nonceCount else {
            throw QRChatError.malformed
        }

        // 4. key through the existing deriver (PBKDF2-HMAC-SHA512, 600 000, 32 B).
        var keyBytes: Data
        do {
            keyBytes = try await PBKDF2KeyDeriver().deriveKey(passphrase: pin, salt: salt)
        } catch {
            throw QRChatError.malformed
        }
        let keyByteCount = keyBytes.count
        defer { keyBytes.resetBytes(in: 0..<keyByteCount) }

        // 5. seal.
        var plaintext = Data()
        plaintext.append(UInt8(nameBytes.count))
        plaintext.append(nameBytes)
        plaintext.append(passphraseBytes)
        let plaintextCount = plaintext.count
        defer { plaintext.resetBytes(in: 0..<plaintextCount) }

        let sealedBox: AES.GCM.SealedBox
        do {
            let gcmNonce = try AES.GCM.Nonce(data: nonce)
            sealedBox = try AES.GCM.seal(
                plaintext,
                using: SymmetricKey(data: keyBytes),
                nonce: gcmNonce,
                authenticating: authenticatedData(kdfId: kdfIdPBKDF2, salt: salt, nonce: nonce)
            )
        } catch {
            throw QRChatError.malformed
        }

        // 6. body and text.
        var body = Data()
        body.append(kdfIdPBKDF2)
        body.append(salt)
        body.append(nonce)
        body.append(sealedBox.ciphertext)
        body.append(sealedBox.tag)

        return textPrefix + supportedVersion + "/" + base64URLEncodedNoPadding(body)
    }

    /// Trims the four ASCII whitespace bytes the spec defines (0x20 0x09 0x0D 0x0A) and
    /// nothing else, so a chat name survives a transfer between platforms unchanged.
    private static func asciiTrimmed(_ text: String) -> String {
        let bytes: [UInt8] = Array(text.utf8)
        var first = 0
        var afterLast = bytes.count
        while first < afterLast, isASCIIWhitespace(bytes[first]) {
            first += 1
        }
        while afterLast > first, isASCIIWhitespace(bytes[afterLast - 1]) {
            afterLast -= 1
        }
        if first == 0 && afterLast == bytes.count {
            return text
        }
        return String(decoding: bytes[first..<afterLast], as: UTF8.self)
    }

    // MARK: - Parsing, phase 1: syntax only (spec section 1.5, P1..P9)

    /// Never throws and never traps, whatever the scanner hands in.
    /// Performs no crypto: the PIN is not known yet.
    static func parse(_ text: String) -> Result<ParsedQR, QRChatError> {

        // P1 - strip leading/trailing ASCII whitespace only (0x20 0x09 0x0D 0x0A).
        let allBytes: [UInt8] = Array(text.utf8)
        var first = 0
        var afterLast = allBytes.count
        while first < afterLast, isASCIIWhitespace(allBytes[first]) {
            first += 1
        }
        while afterLast > first, isASCIIWhitespace(allBytes[afterLast - 1]) {
            afterLast -= 1
        }
        let bytes: [UInt8] = Array(allBytes[first..<afterLast])

        // P2 - hard length cap before anything else.
        if bytes.count > maxTextBytes {
            return .failure(.malformed)
        }

        // P3 - exact, case-sensitive prefix.
        let prefixBytes: [UInt8] = Array(textPrefix.utf8)
        guard bytes.count >= prefixBytes.count else {
            return .failure(.notDarkMessage)
        }
        guard Array(bytes[0..<prefixBytes.count]) == prefixBytes else {
            return .failure(.notDarkMessage)
        }

        // P4 - split the remainder at the first "/".
        let rest: [UInt8] = Array(bytes[prefixBytes.count...])
        guard let slashOffset = rest.firstIndex(of: UInt8(ascii: "/")) else {
            return .failure(.malformed)
        }
        let versionBytes: [UInt8] = Array(rest[0..<slashOffset])
        let bodyChars: [UInt8] = Array(rest[(slashOffset + 1)...])
        guard !versionBytes.isEmpty else {
            return .failure(.malformed)
        }
        for byte in versionBytes {
            if !isASCIIDigit(byte) {
                return .failure(.malformed)
            }
        }

        // P5 - only version "1".
        guard versionBytes == Array(supportedVersion.utf8) else {
            return .failure(.unsupportedVersion)
        }

        // P6 - base64url alphabet, length bounds, length % 4 != 1.
        guard bodyChars.count >= minBodyChars, bodyChars.count <= maxBodyChars else {
            return .failure(.malformed)
        }
        guard bodyChars.count % 4 != 1 else {
            return .failure(.malformed)
        }
        for byte in bodyChars {
            if !isBase64URLCharacter(byte) {
                return .failure(.malformed)
            }
        }

        // P7 - decode, then check the binary length envelope.
        guard let decoded = base64URLDecodedNoPadding(bodyChars) else {
            return .failure(.malformed)
        }
        let body: [UInt8] = Array(decoded)
        guard body.count >= minBodyBytes, body.count <= maxBodyBytes else {
            return .failure(.malformed)
        }

        // P8 - an unknown kdfId is never a fallback.
        guard body[0] == kdfIdPBKDF2 else {
            return .failure(.unsupportedVersion)
        }

        // P9 - split the header off.
        let salt = Data(body[1..<(1 + saltCount)])
        let nonce = Data(body[(1 + saltCount)..<headerCount])
        let ciphertextAndTag = Data(body[headerCount...])
        guard ciphertextAndTag.count >= tagCount + 2 else {
            return .failure(.malformed)
        }

        return .success(
            ParsedQR(
                kdfId: body[0],
                salt: salt,
                nonce: nonce,
                ciphertextAndTag: ciphertextAndTag
            )
        )
    }

    // MARK: - Parsing, phase 2: open with the PIN (spec section 1.5, O1..O7)

    /// Runs the full PBKDF2 before reporting `wrongPin`: there is no early exit.
    static func open(_ parsed: ParsedQR, pin: String) async throws -> QRChatPayload {

        // O1
        guard isValidPin(pin) else {
            throw QRChatError.malformed
        }
        guard parsed.salt.count == saltCount,
              parsed.nonce.count == nonceCount,
              parsed.ciphertextAndTag.count >= tagCount + 2 else {
            throw QRChatError.malformed
        }

        // O2
        var keyBytes: Data
        do {
            keyBytes = try await PBKDF2KeyDeriver().deriveKey(passphrase: pin, salt: parsed.salt)
        } catch {
            throw QRChatError.malformed
        }
        let keyByteCount = keyBytes.count
        defer { keyBytes.resetBytes(in: 0..<keyByteCount) }
        let aad = authenticatedData(kdfId: parsed.kdfId, salt: parsed.salt, nonce: parsed.nonce)

        // O3 - split ciphertext and the 16-byte tag the way CryptoEngine does.
        let sealed: [UInt8] = Array(parsed.ciphertextAndTag)
        let ciphertext = Data(sealed[0..<(sealed.count - tagCount)])
        let tag = Data(sealed[(sealed.count - tagCount)...])

        var plaintext: Data
        do {
            let gcmNonce = try AES.GCM.Nonce(data: parsed.nonce)
            let sealedBox = try AES.GCM.SealedBox(nonce: gcmNonce, ciphertext: ciphertext, tag: tag)
            plaintext = try AES.GCM.open(
                sealedBox,
                using: SymmetricKey(data: keyBytes),
                authenticating: aad
            )
        } catch CryptoKitError.authenticationFailure {
            throw QRChatError.wrongPin
        } catch {
            throw QRChatError.malformed
        }
        let plaintextCount = plaintext.count
        defer { plaintext.resetBytes(in: 0..<plaintextCount) }

        // O4 - plaintext framing.
        var plaintextBytes: [UInt8] = Array(plaintext)
        let plaintextByteCount = plaintextBytes.count
        defer {
            for index in 0..<plaintextByteCount {
                plaintextBytes[index] = 0
            }
        }
        guard plaintextBytes.count >= 2 else {
            throw QRChatError.malformed
        }
        let nameLength = Int(plaintextBytes[0])
        guard nameLength <= maxNameBytes else {
            throw QRChatError.malformed
        }
        guard 1 + nameLength < plaintextBytes.count else {
            throw QRChatError.malformed
        }
        let nameData = Data(plaintextBytes[1..<(1 + nameLength)])
        var passphraseData = Data(plaintextBytes[(1 + nameLength)...])
        let passphraseByteCount = passphraseData.count
        defer { passphraseData.resetBytes(in: 0..<passphraseByteCount) }
        guard passphraseData.count <= maxPassphraseBytes else {
            throw QRChatError.malformed
        }

        // O5 - strict UTF-8. String(data:encoding:) returns nil on invalid bytes;
        // String(decoding:as:) must never be used here, it substitutes U+FFFD.
        guard let decodedName = String(data: nameData, encoding: .utf8),
              let passphrase = String(data: passphraseData, encoding: .utf8) else {
            throw QRChatError.malformed
        }

        // O6 - name: trim, reject control characters, empty means nil.
        let trimmedName = asciiTrimmed(decodedName)
        for scalar in trimmedName.unicodeScalars {
            if scalar.value <= 0x1F || scalar.value == 0x7F {
                throw QRChatError.malformed
            }
        }

        // O7 - key, plaintext and passphrase buffers are zeroed by the defers above.
        return QRChatPayload(
            name: trimmedName.isEmpty ? nil : trimmedName,
            passphrase: passphrase
        )
    }

    // MARK: - PIN and fingerprint

    /// Six decimal digits, leading zeros kept.
    static func generatePin() -> String {
        return String(format: "%06d", Int.random(in: 0..<1_000_000))
    }

    /// Uppercase hex of the first 4 bytes of SHA-256(UTF-8 passphrase), as "XXXX XXXX".
    /// UI only - never part of the payload.
    static func fingerprint(of passphrase: String) -> String {
        let digest = SHA256.hash(data: Data(passphrase.utf8))
        let firstFour: [UInt8] = Array(Array(digest).prefix(4))
        var hex = ""
        for byte in firstFour {
            hex += String(format: "%02X", byte)
        }
        return String(hex.prefix(4)) + " " + String(hex.suffix(4))
    }

    /// 12 CSPRNG bytes for the AES-GCM nonce.
    static func randomNonce() -> Data {
        var nonce = Data(count: nonceCount)
        let status: Int32 = nonce.withUnsafeMutableBytes { (pointer: UnsafeMutableRawBufferPointer) -> Int32 in
            guard let baseAddress = pointer.baseAddress else {
                return errSecParam
            }
            return SecRandomCopyBytes(kSecRandomDefault, nonceCount, baseAddress)
        }
        if status != errSecSuccess {
            var fallback = Data()
            for _ in 0..<nonceCount {
                fallback.append(UInt8.random(in: 0...255))
            }
            return fallback
        }
        return nonce
    }

    // MARK: - Private helpers

    private static func authenticatedData(kdfId: UInt8, salt: Data, nonce: Data) -> Data {
        var data = Data(aadPrefix.utf8)
        data.append(kdfId)
        data.append(salt)
        data.append(nonce)
        return data
    }

    private static func isValidPin(_ pin: String) -> Bool {
        let bytes: [UInt8] = Array(pin.utf8)
        guard bytes.count == pinDigits else {
            return false
        }
        for byte in bytes {
            if !isASCIIDigit(byte) {
                return false
            }
        }
        return true
    }

    private static func isASCIIWhitespace(_ byte: UInt8) -> Bool {
        return byte == 0x20 || byte == 0x09 || byte == 0x0D || byte == 0x0A
    }

    private static func isASCIIDigit(_ byte: UInt8) -> Bool {
        return byte >= 0x30 && byte <= 0x39
    }

    private static func isBase64URLCharacter(_ byte: UInt8) -> Bool {
        if byte >= 0x41 && byte <= 0x5A {       // A-Z
            return true
        }
        if byte >= 0x61 && byte <= 0x7A {       // a-z
            return true
        }
        if byte >= 0x30 && byte <= 0x39 {       // 0-9
            return true
        }
        return byte == 0x2D || byte == 0x5F     // "-" or "_"
    }

    /// RFC 4648 section 5, no padding. Hand-rolled: the standard initializers
    /// only speak the "+/" alphabet.
    private static func base64URLEncodedNoPadding(_ data: Data) -> String {
        var text = data.base64EncodedString()
        text = text.replacingOccurrences(of: "+", with: "-")
        text = text.replacingOccurrences(of: "/", with: "_")
        while text.hasSuffix("=") {
            text.removeLast()
        }
        return text
    }

    /// Maps "-" to "+" and "_" to "/", pads to a multiple of 4, then uses
    /// `Data(base64Encoded:)`. `ascii` must already have passed the P6 alphabet check.
    private static func base64URLDecodedNoPadding(_ ascii: [UInt8]) -> Data? {
        var mapped: [UInt8] = ascii
        for index in mapped.indices {
            if mapped[index] == UInt8(ascii: "-") {
                mapped[index] = UInt8(ascii: "+")
            } else if mapped[index] == UInt8(ascii: "_") {
                mapped[index] = UInt8(ascii: "/")
            }
        }
        while mapped.count % 4 != 0 {
            mapped.append(UInt8(ascii: "="))
        }
        guard let text = String(bytes: mapped, encoding: .utf8) else {
            return nil
        }
        return Data(base64Encoded: text)
    }
}
