import SwiftUI

struct ChatsView: View {

    /// QR payload parsed by ContentView.onOpenURL from a "darkmessage://" deep
    /// link. It is only ever presented as the scanner's PIN step (nothing is
    /// added without confirmation) and only after onboarding is finished; the
    /// binding is cleared the moment the cover is presented.
    @Binding var pendingQR: ParsedQR?

    @EnvironmentObject var chatStore: ChatStore
    @EnvironmentObject var settings: SettingsStore

    @State private var showAddSheet = false
    @State private var editingChat: Chat?
    @State private var deletingChat: Chat?
    @State private var chatName = ""
    @State private var passphrase = ""
    @State private var showPassphrase = false

    /// Key fingerprints per chat id, shown as the second line of every row so a
    /// key can be compared with the contact without ever reading the passphrase
    /// aloud. Only the fingerprint is kept here: it is a truncated hash and not
    /// a secret, so it needs no reveal guard (Android shows the same line).
    @State private var fingerprints: [UUID: String] = [:]

    // MARK: QR state

    /// A single Identifiable value drives every QR presentation, so the cover
    /// content is built only once its payload exists (BUG 7 lesson: never
    /// isPresented plus state mutated in the same event).
    @State private var qrRoute: QRRoute?
    /// Set by the scanner when the user chooses "Enter manually": the normal
    /// New-chat sheet is opened from the cover's onDismiss.
    @State private var pendingManualEntry = false
    /// Capsule toast shown for 2 s after a chat was added / updated by QR.
    @State private var qrToast: String?

    var body: some View {
        NavigationView {
            ZStack {
                AppTheme.background.ignoresSafeArea()

                if chatStore.chats.isEmpty {
                    emptyState
                } else {
                    chatList
                }
            }
            .overlay(alignment: .bottom) {
                toastOverlay
            }
            .navigationTitle(L("chats_title"))
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button {
                        qrRoute = .scan
                    } label: {
                        Image(systemName: "qrcode.viewfinder")
                    }
                    .accessibilityLabel(L("qr_scan"))
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        resetForm()
                        showAddSheet = true
                    } label: {
                        Image(systemName: "plus")
                    }
                }
            }
            .sheet(isPresented: $showAddSheet) {
                chatFormSheet(
                    title: L("chats_new_chat"),
                    buttonTitle: L("chats_create")
                ) {
                    // A keychain write can fail. Saying so beats a chat that looks
                    // created and then reports no passphrase for everything sent to it.
                    if chatStore.addChat(
                        name: chatName.trimmingCharacters(in: .whitespaces),
                        passphrase: passphrase
                    ) {
                        refreshFingerprints()
                    } else {
                        qrToast = L("chats_error_key_not_saved")
                    }
                }
            }
            .sheet(item: $editingChat) { chat in
                chatFormSheet(
                    title: L("chats_edit_chat"),
                    buttonTitle: L("chats_save")
                ) {
                    var updated = chat
                    updated.name = chatName.trimmingCharacters(in: .whitespaces)
                    // Blank after trimming means "keep the existing key", exactly as
                    // Android's `newPassphrase?.takeIf { it.isNotBlank() }` does. Without
                    // the trim a single stray space replaced the stored key for good:
                    // the old one is gone from the Keychain and every earlier message
                    // becomes unreadable.
                    let typed = passphrase.trimmingCharacters(in: .whitespacesAndNewlines)
                    let newPass = typed.isEmpty ? nil : passphrase
                    chatStore.updateChat(updated, newPassphrase: newPass)
                    refreshFingerprints()
                }
            }
            .alert(
                L("chats_delete_chat"),
                isPresented: Binding(
                    get: { deletingChat != nil },
                    set: { if !$0 { deletingChat = nil } }
                )
            ) {
                Button(L("delete"), role: .destructive) {
                    if let chat = deletingChat {
                        chatStore.deleteChat(chat)
                    }
                    deletingChat = nil
                }
                Button(L("cancel"), role: .cancel) {
                    deletingChat = nil
                }
            } message: {
                if let chat = deletingChat {
                    Text(String(format: L("chats_delete_confirm"), chat.name))
                }
            }
        }
        .fullScreenCover(item: $qrRoute, onDismiss: { handleQRDismiss() }) { route in
            qrCover(for: route)
        }
        .onAppear {
            refreshFingerprints()
            // Covers a deep link that arrived before this view was on screen.
            presentPendingQRIfPossible()
        }
        .onChange(of: chatStore.chats) { _, _ in
            // Catches add / delete / rename. A passphrase changed in place
            // leaves the array equal, so the save actions refresh explicitly.
            refreshFingerprints()
        }
        .onChange(of: pendingQR != nil) { _, hasPending in
            if hasPending {
                presentPendingQRIfPossible()
            }
        }
        .onChange(of: settings.onboardingCompleted) { _, completed in
            if completed {
                // Let the onboarding cover finish dismissing before ours appears.
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
                    presentPendingQRIfPossible()
                }
            }
        }
        .onChange(of: qrToast) { _, text in
            // The scanner writes the success text into the binding; hide it
            // again after 2 s unless a newer toast replaced it meanwhile.
            guard let text = text else { return }
            // A QR import may have replaced the key of an existing chat.
            refreshFingerprints()
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                if qrToast == text {
                    qrToast = nil
                }
            }
        }
    }

    // MARK: - Subviews

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "bubble.left.and.bubble.right")
                .font(.system(size: 60))
                .foregroundColor(AppTheme.onSurfaceVariant.opacity(0.4))
            Text(L("chats_empty_title"))
                .font(.title3.bold())
                .foregroundColor(AppTheme.onSurfaceVariant)
            Text(L("chats_empty_subtitle"))
                .font(.subheadline)
                .foregroundColor(AppTheme.onSurfaceVariant.opacity(0.7))
            Button {
                qrRoute = .scan
            } label: {
                Label(L("qr_scan"), systemImage: "qrcode.viewfinder")
            }
            .buttonStyle(.bordered)
            .tint(AppTheme.primary)
            .padding(.top, 8)
        }
    }

    private var chatList: some View {
        List {
            ForEach(chatStore.chats) { chat in
                HStack(spacing: 12) {
                    AvatarView(name: chat.name, hue: chat.colorHue)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(chat.name)
                            .font(.headline)
                        if let date = chat.lastActivityAt {
                            // An absolute stamp, formatted exactly like Android's
                            // SimpleDateFormat("dd.MM.yyyy HH:mm"). It used to be
                            // Text(date, style: .relative), which SwiftUI redraws every
                            // second, so the row carried a counter that ticked upwards.
                            // In an encryption app that reads as a self-destruct timer,
                            // and the owner asked what the timer in the chat was.
                            Text(Self.activityFormatter.string(from: date))
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                        if let fingerprint = fingerprints[chat.id] {
                            Text(String(format: L("chats_fingerprint"), fingerprint))
                                .font(.caption.monospaced())
                                .foregroundColor(AppTheme.onSurfaceVariant.opacity(0.8))
                        }
                    }
                    Spacer()
                }
                .contentShape(Rectangle())
                .swipeActions(edge: .trailing) {
                    Button(role: .destructive) {
                        deletingChat = chat
                    } label: {
                        Label(L("chats_delete"), systemImage: "trash")
                    }
                    Button {
                        chatName = chat.name
                        passphrase = ""
                        showPassphrase = false
                        editingChat = chat
                    } label: {
                        Label(L("chats_edit"), systemImage: "pencil")
                    }
                    .tint(.orange)
                    Button {
                        qrRoute = .show(chat)
                    } label: {
                        Label(L("qr_show"), systemImage: "qrcode")
                    }
                    .tint(AppTheme.primary)
                }
                .contextMenu {
                    Button {
                        chatName = chat.name
                        passphrase = ""
                        showPassphrase = false
                        editingChat = chat
                    } label: {
                        Label(L("chats_edit"), systemImage: "pencil")
                    }
                    Button {
                        qrRoute = .show(chat)
                    } label: {
                        Label(L("qr_show"), systemImage: "qrcode")
                    }
                    Button(role: .destructive) {
                        deletingChat = chat
                    } label: {
                        Label(L("chats_delete"), systemImage: "trash")
                    }
                }
                .listRowBackground(AppTheme.surfaceVariant)
            }
        }
        .listStyle(.plain)
    }

    @ViewBuilder
    private var toastOverlay: some View {
        if let text = qrToast {
            Text(text)
                .font(.subheadline)
                .foregroundColor(AppTheme.onSurface)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(
                    Capsule().fill(AppTheme.surfaceVariant)
                )
                .padding(.horizontal, 24)
                .padding(.bottom, 24)
        }
    }

    /// "dd.MM.yyyy HH:mm", the same stamp Android puts on a chat row. The POSIX
    /// locale keeps the digits Latin and the 24-hour clock fixed, so the two phones
    /// show the identical string for the identical moment.
    private static let activityFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "dd.MM.yyyy HH:mm"
        return formatter
    }()

    /// Reads every stored passphrase once and keeps only its fingerprint.
    /// The passphrase itself never leaves this function.
    private func refreshFingerprints() {
        var map: [UUID: String] = [:]
        for chat in chatStore.chats {
            if let passphrase = chatStore.getPassphrase(for: chat), !passphrase.isEmpty {
                map[chat.id] = QRChatCodec.fingerprint(of: passphrase)
            }
        }
        fingerprints = map
    }

    // MARK: - QR presentation

    @ViewBuilder
    private func qrCover(for route: QRRoute) -> some View {
        switch route {
        case .show(let chat):
            ShowChatQRView(chat: chat)
        case .scan:
            ScanChatQRView(
                initialParsed: nil,
                pendingManualEntry: $pendingManualEntry,
                toast: $qrToast
            )
        case .importPayload(_, let parsed):
            ScanChatQRView(
                initialParsed: parsed,
                pendingManualEntry: $pendingManualEntry,
                toast: $qrToast
            )
        }
    }

    /// Presents a deep-linked payload, but only when no other QR cover is on
    /// screen and onboarding is finished. Retried from handleQRDismiss().
    private func presentPendingQRIfPossible() {
        guard settings.onboardingCompleted else { return }
        guard qrRoute == nil else { return }
        guard let parsed = pendingQR else { return }
        pendingQR = nil
        // .importing() mints a fresh id, so the same payload arriving twice
        // still re-presents the cover.
        qrRoute = QRRoute.importing(parsed)
    }

    private func handleQRDismiss() {
        if pendingManualEntry {
            pendingManualEntry = false
            resetForm()
            // Give the cover time to leave the screen before presenting a sheet.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                showAddSheet = true
            }
            return
        }
        presentPendingQRIfPossible()
    }

    // MARK: - Form Sheet

    private func chatFormSheet(
        title: String,
        buttonTitle: String,
        onSave: @escaping () -> Void
    ) -> some View {
        NavigationView {
            Form {
                Section {
                    TextField(L("chats_contact_name"), text: $chatName)
                        .autocorrectionDisabled()
                }

                Section {
                    HStack {
                        if showPassphrase {
                            TextField(L("chats_passphrase"), text: $passphrase)
                                .autocorrectionDisabled()
                                .textInputAutocapitalization(.never)
                        } else {
                            SecureField(L("chats_passphrase"), text: $passphrase)
                                .textInputAutocapitalization(.never)
                        }
                        Button {
                            showPassphrase.toggle()
                        } label: {
                            Image(systemName: showPassphrase ? "eye.slash" : "eye")
                                .foregroundColor(.secondary)
                        }
                    }

                    Button {
                        passphrase = generateRandomKey()
                        showPassphrase = true
                    } label: {
                        Label(L("chats_generate_key"), systemImage: "key")
                    }
                } footer: {
                    Text(L("chats_passphrase_hint"))
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("chats_cancel")) {
                        showAddSheet = false
                        editingChat = nil
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(buttonTitle) {
                        onSave()
                        showAddSheet = false
                        editingChat = nil
                    }
                    // A new chat needs a key that is more than whitespace; Android
                    // guards the same way with `passphrase.isNotBlank()`.
                    .disabled(chatName.trimmingCharacters(in: .whitespaces).isEmpty ||
                              (editingChat == nil &&
                               passphrase.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty))
                }
            }
        }
    }

    // MARK: - Helpers

    private func resetForm() {
        chatName = ""
        passphrase = ""
        showPassphrase = false
    }

    /// 32 bytes, matching Android's `ChatsViewModel.generateKey()`. It used to be 24,
    /// which gave iPhone users a weaker generated key than Android users for no reason.
    private func generateRandomKey() -> String {
        var bytes = Data(count: 32)
        let ok = bytes.withUnsafeMutableBytes { ptr -> Int32 in
            guard let base = ptr.baseAddress else { return errSecParam }
            return SecRandomCopyBytes(kSecRandomDefault, 32, base)
        }
        guard ok == errSecSuccess else { return "" }
        return bytes.base64EncodedString()
    }
}
