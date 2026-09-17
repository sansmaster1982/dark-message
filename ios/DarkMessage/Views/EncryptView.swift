import SwiftUI
import PhotosUI

enum EncryptInputMode: String, CaseIterable {
    case text, photo, document

    var label: String {
        switch self {
        case .text: return L("encrypt_text")
        case .photo: return L("encrypt_photo")
        case .document: return L("encrypt_document")
        }
    }
}

struct EncryptView: View {

    @EnvironmentObject var chatStore: ChatStore
    @FocusState private var isTextFocused: Bool

    @State private var selectedChat: Chat?
    @State private var inputMode: EncryptInputMode = .text
    @State private var messageText = ""
    @State private var selectedPhotoItem: PhotosPickerItem?
    @State private var selectedImageData: Data?
    @State private var selectedImagePreview: UIImage?
    @State private var documentURL: URL?
    @State private var documentName: String?

    @State private var isEncrypting = false
    @State private var encryptedBase64: String?
    @State private var encryptedFileURL: URL?
    @State private var errorMessage: String?

    @State private var showDocumentPicker = false
    @State private var shareSheet: SharePayload?

    private let cryptoEngine = CryptoEngine()

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 16) {
                    // Chat selector
                    ChatSelector(chats: chatStore.chats, selectedChat: $selectedChat)

                    // Input mode picker
                    Picker("", selection: $inputMode) {
                        ForEach(EncryptInputMode.allCases, id: \.self) { mode in
                            Text(mode.label).tag(mode)
                        }
                    }
                    .pickerStyle(.segmented)

                    // Input area
                    inputArea

                    // Encrypt button
                    if !isEncrypting {
                        Button {
                            encrypt()
                        } label: {
                            Text(L("encrypt_button"))
                        }
                        .buttonStyle(DarkMessageButtonStyle())
                        .disabled(selectedChat == nil || !hasInput)
                    } else {
                        ProgressView(L("encrypt_encrypting"))
                            .padding()
                    }

                    // Error
                    if let error = errorMessage {
                        Text(error)
                            .foregroundColor(AppTheme.error)
                            .font(.callout)
                            .padding()
                            .frame(maxWidth: .infinity)
                            .background(
                                RoundedRectangle(cornerRadius: 12)
                                    .fill(AppTheme.error.opacity(0.1))
                            )
                    }

                    // Result
                    resultArea

                    // Clear button
                    if encryptedBase64 != nil || encryptedFileURL != nil {
                        Button {
                            clearResult()
                        } label: {
                            Text(L("encrypt_clear"))
                                .foregroundColor(.secondary)
                        }
                    }
                }
                .padding()
            }
            .background(AppTheme.background.ignoresSafeArea())
            .navigationTitle(L("encrypt_title"))
            .toolbar {
                // Same as on the Decrypt screen: the keyboard hides the Encrypt
                // button, so the action lives on the bar above the keyboard too.
                ToolbarItemGroup(placement: .keyboard) {
                    Button(L("keyboard_done")) {
                        isTextFocused = false
                    }
                    Spacer()
                    Button(L("encrypt_button")) {
                        isTextFocused = false
                        encrypt()
                    }
                    .fontWeight(.semibold)
                    .disabled(selectedChat == nil || !hasInput)
                }
            }
            .onTapGesture {
                isTextFocused = false
            }
            .onChange(of: selectedPhotoItem) { newItem in
                loadPhoto(newItem)
            }
            .sheet(isPresented: $showDocumentPicker) {
                DocumentPicker { url in
                    documentURL = url
                    documentName = url.lastPathComponent
                }
            }
        }
        // Share sheet is driven by `.sheet(item:)` so the payload is handed into the
        // content closure at presentation time (fixes the empty first-tap share sheet).
        // Attached to the NavigationView, not the ScrollView, so it lives on a
        // different view node than the DocumentPicker sheet above.
        .sheet(item: $shareSheet) { payload in
            ActivityView(items: payload.items)
        }
    }

    // MARK: - Input Area

    @ViewBuilder
    private var inputArea: some View {
        switch inputMode {
        case .text:
            TextEditor(text: $messageText)
                .focused($isTextFocused)
                .frame(minHeight: 120)
                .padding(8)
                .background(
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(Color.secondary.opacity(0.3))
                )
                .overlay(alignment: .topLeading) {
                    if messageText.isEmpty {
                        Text(L("encrypt_message"))
                            .foregroundColor(.secondary)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 16)
                            .allowsHitTesting(false)
                    }
                }

        case .photo:
            VStack(spacing: 12) {
                if let preview = selectedImagePreview {
                    Image(uiImage: preview)
                        .resizable()
                        .scaledToFit()
                        .frame(maxHeight: 200)
                        .cornerRadius(12)
                }
                PhotosPicker(
                    selection: $selectedPhotoItem,
                    matching: .images
                ) {
                    Label(
                        L("encrypt_select_image"),
                        systemImage: "photo"
                    )
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(AppTheme.primary, lineWidth: 1)
                    )
                }
            }

        case .document:
            VStack(spacing: 12) {
                if let name = documentName {
                    HStack {
                        Image(systemName: "doc")
                            .foregroundColor(AppTheme.primary)
                        Text(String(format: L("encrypt_document_selected"), name))
                            .lineLimit(2)
                    }
                    .padding()
                    .darkMessageCard()
                }
                Button {
                    showDocumentPicker = true
                } label: {
                    Label(
                        L("encrypt_select_document"),
                        systemImage: "doc.badge.plus"
                    )
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(AppTheme.primary, lineWidth: 1)
                    )
                }
            }
        }
    }

    // MARK: - Result Area

    @ViewBuilder
    private var resultArea: some View {
        if let base64 = encryptedBase64 {
            VStack(spacing: 12) {
                HStack {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundColor(AppTheme.success)
                    Text(L("encrypt_success"))
                        .font(.headline)
                        .foregroundColor(AppTheme.success)
                }

                Text(base64)
                    .font(.system(.caption, design: .monospaced))
                    .lineLimit(6)
                    .padding()
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(
                        RoundedRectangle(cornerRadius: 12)
                            .fill(AppTheme.surfaceVariant)
                    )

                HStack(spacing: 12) {
                    Button {
                        UIPasteboard.general.string = base64
                    } label: {
                        Label(L("encrypt_copy"), systemImage: "doc.on.doc")
                    }
                    .buttonStyle(.bordered)

                    Button {
                        shareSheet = SharePayload(items: [base64])
                    } label: {
                        Label(L("encrypt_send"), systemImage: "paperplane")
                    }
                    .buttonStyle(.bordered)
                    .tint(AppTheme.primary)
                }
            }
            .padding()
            .darkMessageCard()
        }

        if let fileURL = encryptedFileURL {
            VStack(spacing: 12) {
                HStack {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundColor(AppTheme.success)
                    Text(L("encrypt_success"))
                        .font(.headline)
                        .foregroundColor(AppTheme.success)
                }

                Button {
                    shareSheet = SharePayload(items: [fileURL])
                } label: {
                    Label(L("encrypt_send_file"), systemImage: "square.and.arrow.up")
                }
                .buttonStyle(DarkMessageButtonStyle())
            }
            .padding()
            .darkMessageCard()
        }
    }

    // MARK: - Logic

    private var hasInput: Bool {
        switch inputMode {
        case .text: return !messageText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case .photo: return selectedImageData != nil
        case .document: return documentURL != nil
        }
    }

    private func encrypt() {
        guard let chat = selectedChat else { return }
        guard let passphrase = chatStore.getPassphrase(for: chat) else {
            errorMessage = L("encrypt_error_no_passphrase")
            return
        }

        errorMessage = nil
        encryptedBase64 = nil
        encryptedFileURL = nil
        isEncrypting = true

        Task {
            do {
                let payload: Data
                let contentType: ContentType

                switch inputMode {
                case .text:
                    guard let textData = messageText.data(using: .utf8) else {
                        throw CryptoError.encryptionFailed(L("encrypt_error_empty_text"))
                    }
                    contentType = .text
                    payload = try await cryptoEngine.encrypt(data: textData, contentType: contentType, passphrase: passphrase)

                case .photo:
                    guard let imageData = selectedImageData else {
                        throw CryptoError.encryptionFailed(L("encrypt_error_no_image"))
                    }
                    contentType = .image
                    payload = try await cryptoEngine.encrypt(data: imageData, contentType: contentType, passphrase: passphrase)

                case .document:
                    guard let url = documentURL else {
                        throw CryptoError.encryptionFailed(L("encrypt_error_no_document"))
                    }
                    let docData = try Data(contentsOf: url)
                    // Last line of defence, the same one Android applies: whatever the picker
                    // gave us, the name that goes INTO the payload carries an extension if the
                    // content can name one. The receiving phone decides what to do with a file
                    // from its extension alone, and a name that arrives without one opens
                    // nowhere.
                    let name = DecryptView.documentFileName(url.lastPathComponent, data: docData)
                    let nameData = Data(name.utf8)
                    var combined = Data()
                    combined.append(UInt8(nameData.count >> 8))
                    combined.append(UInt8(nameData.count & 0xFF))
                    combined.append(nameData)
                    combined.append(docData)
                    contentType = .document
                    payload = try await cryptoEngine.encrypt(data: combined, contentType: contentType, passphrase: passphrase)
                }

                // Always save as .darkm file: required for images/documents, offered as
                // an alternative for text. The file name is built from a sanitized chat
                // name (a "/" or ":" in the raw name made the write fail silently).
                let fileURL = Self.tempFileURL(forChatName: chat.name)
                // Drop any stale file from a previous run before writing the new one.
                try? FileManager.default.removeItem(at: fileURL)
                try payload.write(to: fileURL, options: .atomic)

                await MainActor.run {
                    chatStore.updateLastActivity(chatId: chat.id)

                    if contentType == .text && payload.count < 50_000 {
                        encryptedBase64 = payload.base64EncodedString()
                    }

                    encryptedFileURL = fileURL

                    isEncrypting = false
                }
            } catch {
                await MainActor.run {
                    errorMessage = error.localizedDescription
                    isEncrypting = false
                }
            }
        }
    }

    /// Temp file URL for the encrypted payload: `tmp/encrypted_<safeName>.darkm`.
    /// Only letters, digits, "-" and "_" are kept from the chat name (capped at
    /// 64 characters); if nothing is left the name falls back to "message".
    private static func tempFileURL(forChatName name: String) -> URL {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_"))
        var safeName = ""
        for scalar in name.unicodeScalars where allowed.contains(scalar) {
            if safeName.count >= 64 { break }
            safeName.append(Character(scalar))
        }
        if safeName.isEmpty {
            safeName = "message"
        }
        return FileManager.default.temporaryDirectory
            .appendingPathComponent("encrypted_\(safeName)")
            .appendingPathExtension("darkm")
    }

    private func clearResult() {
        encryptedBase64 = nil
        encryptedFileURL = nil
        errorMessage = nil
        messageText = ""
        selectedPhotoItem = nil
        selectedImageData = nil
        selectedImagePreview = nil
        documentURL = nil
        documentName = nil
    }

    private func loadPhoto(_ item: PhotosPickerItem?) {
        guard let item else { return }
        item.loadTransferable(type: Data.self) { result in
            if case .success(let data) = result, let data {
                DispatchQueue.main.async {
                    selectedImageData = data
                    selectedImagePreview = UIImage(data: data)
                }
            }
        }
    }
}

// MARK: - Document Picker

struct DocumentPicker: UIViewControllerRepresentable {
    let onPick: (URL) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item])
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onPick: onPick)
    }

    class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPick: (URL) -> Void
        init(onPick: @escaping (URL) -> Void) { self.onPick = onPick }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            guard let url = urls.first else { return }
            guard url.startAccessingSecurityScopedResource() else { return }
            defer { url.stopAccessingSecurityScopedResource() }

            // Copy to temp to ensure access later
            let temp = FileManager.default.temporaryDirectory.appendingPathComponent(url.lastPathComponent)
            try? FileManager.default.removeItem(at: temp)
            try? FileManager.default.copyItem(at: url, to: temp)
            onPick(temp)
        }
    }
}
