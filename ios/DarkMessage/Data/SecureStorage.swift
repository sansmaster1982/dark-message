import Foundation
import Security

/// Keychain wrapper for storing passphrases securely
final class SecureStorage {

    private let service = "com.darkmessage.app.passphrases"

    /// Stores the key and says whether it really landed.
    ///
    /// The result used to be thrown away. A keychain write CAN fail - an unsigned build
    /// has no entitlement for it (errSecMissingEntitlement), and a locked device refuses
    /// one - and when it did, the chat was still created and still listed, but nothing
    /// sent to it could ever be decrypted: the screen only ever said the chat has no
    /// passphrase, with no hint that the key was never saved in the first place.
    @discardableResult
    func storePassphrase(chatId: String, passphrase: String) -> Bool {
        let data = Data(passphrase.utf8)

        // Delete existing first
        deletePassphrase(chatId: chatId)

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: chatId,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        ]

        return SecItemAdd(query as CFDictionary, nil) == errSecSuccess
    }

    func getPassphrase(chatId: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: chatId,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)

        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func deletePassphrase(chatId: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: chatId
        ]
        SecItemDelete(query as CFDictionary)
    }
}
