import Foundation
import CryptoKit

// MARK: - Types

enum ContentType: UInt8, Codable {
    case text = 0x01
    case image = 0x02
    case document = 0x03
}

enum CryptoError: LocalizedError {
    case invalidFormat
    case badPassphrase
    case keyDerivationFailed
    case encryptionFailed(String)
    case decryptionFailed(String)

    var errorDescription: String? {
        switch self {
        case .invalidFormat:
            return L("decrypt_error_format")
        case .badPassphrase:
            return L("decrypt_error_bad_passphrase")
        case .keyDerivationFailed:
            return L("error_key_derivation")
        case .encryptionFailed(let msg):
            return String(format: L("encrypt_error"), msg)
        case .decryptionFailed(let msg):
            return String(format: L("decrypt_error"), msg)
        }
    }
}

struct DecryptionResult {
    let contentType: ContentType
    let data: Data

    /// For document content type, first 2 bytes = filename length, then filename, then file bytes.
    ///
    /// Decoded the way Java decodes it on Android - leniently. `String(data:encoding:.utf8)`
    /// returns nil for a single malformed byte, and a nil name here becomes the fallback
    /// "document", so one damaged byte in the middle of a name used to throw the whole name
    /// away silently. Java's `String(bytes, UTF_8)` never fails; it substitutes U+FFFD. A name
    /// that arrives damaged should look damaged, not disappear.
    /// Indexed relative to `data.startIndex`, never by absolute position. A `Data` that is a
    /// slice of a larger buffer keeps the parent's indices, so `data[0]` on such a slice is
    /// either the wrong byte or a trap. `PayloadCodec.decode` already indexes relatively;
    /// this did not - and it is the one place where the photo path (which hands `data` to
    /// UIImage whole) and the document path (which cuts it up by index) differ.
    var documentName: String? {
        guard contentType == .document, data.count >= 2 else { return nil }
        let i = data.startIndex
        let nameLen = Int(data[i]) << 8 | Int(data[i + 1])
        guard nameLen > 0, data.count >= 2 + nameLen else { return nil }
        return String(decoding: data[(i + 2)..<(i + 2 + nameLen)], as: UTF8.self)
    }

    /// A length that does not fit means these bytes are not a name frame at all, and the whole
    /// plaintext is the file. Android's decrypt takes the same two decisions, so a malformed
    /// frame gives the same file on both platforms rather than one of them two bytes short.
    var documentData: Data? {
        guard contentType == .document else { return nil }
        guard data.count >= 2 else { return data }
        let i = data.startIndex
        let nameLen = Int(data[i]) << 8 | Int(data[i + 1])
        guard data.count >= 2 + nameLen else { return data }
        return Data(data[(i + 2 + nameLen)...])
    }
}

// MARK: - Engine

final class CryptoEngine {

    private let keyDeriver: KeyDeriver

    init(keyDeriver: KeyDeriver? = nil) {
        self.keyDeriver = keyDeriver ?? KeyDeriverFactory.create()
    }

    /// Encrypt data and return binary payload
    func encrypt(data: Data, contentType: ContentType, passphrase: String) async throws -> Data {
        let salt = keyDeriver.generateSalt()
        let keyData = try await keyDeriver.deriveKey(passphrase: passphrase, salt: salt)
        let symmetricKey = SymmetricKey(data: keyData)

        // Generate 12-byte nonce
        var nonceBytes = Data(count: 12)
        _ = nonceBytes.withUnsafeMutableBytes { ptr in
            SecRandomCopyBytes(kSecRandomDefault, 12, ptr.baseAddress!)
        }
        let nonce = try AES.GCM.Nonce(data: nonceBytes)

        let sealedBox = try AES.GCM.seal(data, using: symmetricKey, nonce: nonce)

        // Android stores ciphertext + tag together
        let ciphertextAndTag = sealedBox.ciphertext + sealedBox.tag

        return PayloadCodec.encode(
            contentType: contentType,
            salt: salt,
            nonce: nonceBytes,
            ciphertext: ciphertextAndTag
        )
    }

    /// Decrypt binary payload
    func decrypt(payload: Data, passphrase: String) async throws -> DecryptionResult {
        // A transport that treats the attachment as text can hand over the base64 of
        // the payload instead of the payload. Tried only after the binary parse fails.
        let decoded: PayloadCodec.DecodedPayload
        do {
            decoded = try PayloadCodec.decode(payload)
        } catch {
            guard let fromText = PayloadCodec.decodeFromBase64Text(payload) else { throw error }
            decoded = fromText
        }
        let keyData = try await keyDeriver.deriveKey(passphrase: passphrase, salt: decoded.salt)
        let symmetricKey = SymmetricKey(data: keyData)

        let nonce = try AES.GCM.Nonce(data: decoded.nonce)

        // Tolerates a few whitespace bytes appended by whatever carried the file.
        // Mail and messengers have been seen to add a CR LF to an attachment; a
        // leading one is handled while parsing, but a trailing one lands inside the
        // GCM tag and makes an intact message look like a wrong passphrase. Only
        // whitespace is dropped, at most maxTrailingNoise bytes, and the result still
        // has to pass GCM authentication, so a successful open proves the dropped
        // bytes were not part of the message. The key is already derived, so the
        // extra attempts cost microseconds rather than another PBKDF2 run.
        let maxTrailingNoise = 4
        let whitespace = PayloadCodec.asciiWhitespace
        let tagSize = 16
        var blob = decoded.ciphertext
        var dropped = 0

        while true {
            guard blob.count >= tagSize else {
                // The payload parsed cleanly, so running out of bytes here is an
                // authentication failure, not a format one. Android reports the same.
                throw CryptoError.badPassphrase
            }
            do {
                let sealedBox = try AES.GCM.SealedBox(
                    nonce: nonce,
                    ciphertext: blob.prefix(blob.count - tagSize),
                    tag: blob.suffix(tagSize)
                )
                let decryptedData = try AES.GCM.open(sealedBox, using: symmetricKey)
                return DecryptionResult(contentType: decoded.contentType, data: decryptedData)
            } catch CryptoKitError.authenticationFailure {
                guard dropped < maxTrailingNoise,
                      let last = blob.last,
                      whitespace.contains(last) else {
                    throw CryptoError.badPassphrase
                }
                blob = Data(blob.dropLast())
                dropped += 1
            } catch {
                throw CryptoError.decryptionFailed(error.localizedDescription)
            }
        }
    }
}
