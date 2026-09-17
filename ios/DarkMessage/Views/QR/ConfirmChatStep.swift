import SwiftUI

/// Step 3 of the receiver flow (spec section 3.3): review what was received and
/// decide what to do with it. Nothing is ever saved automatically.
///
/// Shows the suggested chat name (renamed to "Name (2)" when the name is already
/// taken), the key fingerprint to compare with the sender, the passphrase masked
/// behind an eye toggle, a warning when another chat already uses the very same
/// passphrase, and three explicit actions: add a new chat, replace the key of
/// the conflicting chat (behind a confirmation alert), or cancel.
struct ConfirmChatStep: View {

    let payload: QRChatPayload

    /// Called with the final chat name after a successful add.
    var onAdded: (String) -> Void

    /// Called with the existing chat name after its passphrase was replaced.
    var onUpdated: (String) -> Void

    var onCancel: () -> Void

    @EnvironmentObject private var chatStore: ChatStore

    @FocusState private var isNameFocused: Bool

    @State private var name: String = ""
    @State private var fingerprint: String = ""
    @State private var conflictingChat: Chat?
    @State private var sameKeyChat: Chat?
    @State private var isPassphraseRevealed: Bool = false
    @State private var showUpdateAlert: Bool = false
    @State private var didPrepare: Bool = false

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Text(L("qr_confirm_title"))
                    .font(.title3.bold())
                    .multilineTextAlignment(.center)
                    .foregroundColor(AppTheme.onSurface)
                    .padding(.top, 8)

                nameCard
                keyCard

                Text(L("qr_confirm_body"))
                    .font(.footnote)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .foregroundColor(AppTheme.onSurfaceVariant)

                Button {
                    addChat()
                } label: {
                    Text(L("qr_confirm_add"))
                }
                .buttonStyle(DarkMessageButtonStyle())
                .disabled(trimmedName.isEmpty)

                if conflictingChat != nil {
                    Button {
                        showUpdateAlert = true
                    } label: {
                        Text(L("qr_confirm_update_existing"))
                    }
                    .buttonStyle(.bordered)
                    .tint(AppTheme.primary)
                }

                Button {
                    onCancel()
                } label: {
                    Text(L("chats_cancel"))
                        .foregroundColor(AppTheme.onSurfaceVariant)
                }
                .padding(.bottom, 8)
            }
            .padding()
        }
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button(L("qr_done")) {
                    isNameFocused = false
                }
            }
        }
        .alert(L("qr_confirm_update_title"), isPresented: $showUpdateAlert) {
            Button(L("cancel"), role: .cancel) {
                showUpdateAlert = false
            }
            Button(L("qr_confirm_update_existing")) {
                updateExistingChat()
            }
        } message: {
            if let existing = conflictingChat {
                Text(String(format: L("qr_confirm_update_body"), existing.name))
            }
        }
        .onAppear {
            prepare()
        }
    }

    // MARK: - Subviews

    private var nameCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L("chats_contact_name"))
                .font(.subheadline)
                .foregroundColor(AppTheme.onSurfaceVariant)

            TextField(L("chats_contact_name"), text: $name)
                .focused($isNameFocused)
                .autocorrectionDisabled()

            if let existing = conflictingChat {
                Text(String(format: L("qr_conflict_renamed"), existing.name, trimmedName))
                    .font(.caption)
                    .foregroundColor(AppTheme.onSurfaceVariant)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .darkMessageCard()
    }

    private var keyCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(String(format: L("qr_fingerprint"), fingerprint))
                .font(.subheadline.monospaced())
                .foregroundColor(AppTheme.onSurface)

            Text(L("qr_fingerprint_hint"))
                .font(.caption)
                .foregroundColor(AppTheme.onSurfaceVariant)

            Divider()

            HStack(alignment: .top, spacing: 8) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(L("chats_passphrase"))
                        .font(.caption)
                        .foregroundColor(AppTheme.onSurfaceVariant)

                    // Read-only on purpose: the received passphrase must reach
                    // the Keychain byte for byte.
                    Text(isPassphraseRevealed ? payload.passphrase : maskedPassphrase)
                        .font(.callout.monospaced())
                        .foregroundColor(AppTheme.onSurface)
                        .privacySensitive()
                }

                Spacer(minLength: 0)

                Button {
                    isPassphraseRevealed.toggle()
                } label: {
                    Image(systemName: isPassphraseRevealed ? "eye.slash" : "eye")
                        .foregroundColor(AppTheme.onSurfaceVariant)
                }
                .accessibilityLabel(
                    Text(isPassphraseRevealed ? L("chats_hide") : L("chats_show"))
                )
            }

            if let sameKey = sameKeyChat {
                Text(String(format: L("qr_same_key_exists"), sameKey.name))
                    .font(.caption)
                    .foregroundColor(AppTheme.error)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .darkMessageCard()
    }

    // MARK: - Derived values

    private var trimmedName: String {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var maskedPassphrase: String {
        let count = min(max(payload.passphrase.count, 1), 24)
        return String(repeating: "\u{2022}", count: count)
    }

    // MARK: - Logic

    private func prepare() {
        guard !didPrepare else { return }
        didPrepare = true

        fingerprint = QRChatCodec.fingerprint(of: payload.passphrase)

        let received = (payload.name ?? "").trimmingCharacters(in: .whitespacesAndNewlines)

        if !received.isEmpty, let existing = chat(named: received) {
            conflictingChat = existing
            name = firstFreeName(basedOn: received)
        } else {
            name = received
        }

        sameKeyChat = chatUsingSamePassphrase()
    }

    private func addChat() {
        let finalName = trimmedName
        guard !finalName.isEmpty else { return }
        chatStore.addChat(name: finalName, passphrase: payload.passphrase)
        onAdded(finalName)
    }

    private func updateExistingChat() {
        guard let existing = conflictingChat else { return }
        showUpdateAlert = false
        chatStore.updateChat(existing, newPassphrase: payload.passphrase)
        onUpdated(existing.name)
    }

    private func chat(named candidate: String) -> Chat? {
        chatStore.chats.first { chat in
            chat.name.trimmingCharacters(in: .whitespacesAndNewlines)
                .caseInsensitiveCompare(candidate) == .orderedSame
        }
    }

    /// "Anna" -> "Anna (2)", or the first free suffix after that.
    private func firstFreeName(basedOn base: String) -> String {
        var suffix = 2
        while suffix < 100 {
            let candidate = "\(base) (\(suffix))"
            if chat(named: candidate) == nil {
                return candidate
            }
            suffix += 1
        }
        return base
    }

    /// Compares UTF-8 bytes, exactly like the key deriver does, so a chat that
    /// already holds this key is recognised even for non-ASCII passphrases.
    private func chatUsingSamePassphrase() -> Chat? {
        let receivedBytes = Array(payload.passphrase.utf8)
        for existing in chatStore.chats {
            guard let stored = chatStore.getPassphrase(for: existing) else { continue }
            if Array(stored.utf8) == receivedBytes {
                return existing
            }
        }
        return nil
    }
}
