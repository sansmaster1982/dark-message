import Foundation

/// Manages chat persistence using a JSON file + Keychain for passphrases
final class ChatStore: ObservableObject {

    @Published private(set) var chats: [Chat] = []

    private let fileURL: URL
    private let secureStorage = SecureStorage()

    /// True when chats.json exists but could not be decoded. While it is set, save()
    /// does nothing, so a damaged file is never replaced by an empty list.
    private(set) var loadFailed = false

    init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        self.fileURL = docs.appendingPathComponent("chats.json")
        load()
    }

    // MARK: - CRUD

    /// Returns false when the key could not be stored. A chat whose key was not saved can
    /// never decrypt anything, so it is not left behind in the list pretending to work.
    @discardableResult
    func addChat(name: String, passphrase: String) -> Bool {
        let nextOrder = (chats.map(\.sortOrder).max() ?? -1) + 1
        let chat = Chat(name: name, sortOrder: nextOrder)
        guard secureStorage.storePassphrase(chatId: chat.id.uuidString, passphrase: passphrase) else {
            return false
        }
        chats.append(chat)
        save()
        return true
    }

    func updateChat(_ chat: Chat, newPassphrase: String? = nil) {
        guard let index = chats.firstIndex(where: { $0.id == chat.id }) else { return }
        chats[index] = chat
        if let pass = newPassphrase {
            secureStorage.storePassphrase(chatId: chat.id.uuidString, passphrase: pass)
        }
        save()
    }

    func deleteChat(_ chat: Chat) {
        chats.removeAll { $0.id == chat.id }
        secureStorage.deletePassphrase(chatId: chat.id.uuidString)
        save()
    }

    func getPassphrase(for chat: Chat) -> String? {
        secureStorage.getPassphrase(chatId: chat.id.uuidString)
    }

    func updateLastActivity(chatId: UUID) {
        guard let index = chats.firstIndex(where: { $0.id == chatId }) else { return }
        chats[index].lastActivityAt = Date()
        save()
    }

    // MARK: - Persistence

    private func load() {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return }
        do {
            let data = try Data(contentsOf: fileURL)
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .iso8601
            chats = try decoder.decode([Chat].self, from: data)
            chats.sort { ($0.sortOrder, $1.createdAt) < ($1.sortOrder, $0.createdAt) }
            loadFailed = false
        } catch {
            // The file exists but could not be read. Leave the in-memory list empty
            // AND refuse to write over the file: the user's chats may still be in
            // there. Writing an empty array here was a one-way loss, because every
            // mutation calls save() and the file is replaced atomically.
            loadFailed = true
            print("ChatStore load error: \(error)")
        }
    }

    private func save() {
        guard !loadFailed else {
            // See load(). Preserving the unreadable file is always better than
            // replacing it with whatever this session happens to hold.
            print("ChatStore save skipped: the existing chats.json could not be read")
            return
        }
        do {
            let encoder = JSONEncoder()
            encoder.dateEncodingStrategy = .iso8601
            let data = try encoder.encode(chats)
            try data.write(to: fileURL, options: .atomic)
        } catch {
            print("ChatStore save error: \(error)")
        }
    }
}
