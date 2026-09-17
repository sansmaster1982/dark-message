import Foundation
import CommonCrypto

// MARK: - Protocol

protocol KeyDeriver {
    func deriveKey(passphrase: String, salt: Data) async throws -> Data
    func generateSalt() -> Data
}

// MARK: - PBKDF2 (always available on iOS)
// Uses same parameters as Android PBKDF2 fallback:
// SHA-512, 600,000 iterations, 256-bit output

final class PBKDF2KeyDeriver: KeyDeriver {

    private let iterations: UInt32 = 600_000
    private let keyLength = 32  // 256 bits

    func deriveKey(passphrase: String, salt: Data) async throws -> Data {
        try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                let passphraseData = Array(passphrase.utf8)
                var derivedKey = Data(count: self.keyLength)

                let status = derivedKey.withUnsafeMutableBytes { keyPtr in
                    salt.withUnsafeBytes { saltPtr in
                        CCKeyDerivationPBKDF(
                            CCPBKDFAlgorithm(kCCPBKDF2),
                            passphraseData,
                            passphraseData.count,
                            saltPtr.bindMemory(to: UInt8.self).baseAddress!,
                            salt.count,
                            CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA512),
                            self.iterations,
                            keyPtr.bindMemory(to: UInt8.self).baseAddress!,
                            self.keyLength
                        )
                    }
                }

                if status == kCCSuccess {
                    continuation.resume(returning: derivedKey)
                } else {
                    continuation.resume(throwing: CryptoError.keyDerivationFailed)
                }
            }
        }
    }

    func generateSalt() -> Data {
        var salt = Data(count: 16)
        _ = salt.withUnsafeMutableBytes { ptr in
            SecRandomCopyBytes(kSecRandomDefault, 16, ptr.baseAddress!)
        }
        return salt
    }
}

// MARK: - Factory

enum KeyDeriverFactory {
    static func create() -> KeyDeriver {
        return PBKDF2KeyDeriver()
    }
}
