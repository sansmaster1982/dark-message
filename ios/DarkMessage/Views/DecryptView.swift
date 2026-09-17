import SwiftUI

enum DecryptInputMode {
    case pasteText, importFile
}

struct DecryptView: View {

    @EnvironmentObject var chatStore: ChatStore
    @Binding var pendingFileURL: URL?
    @FocusState private var isTextFocused: Bool

    @State private var selectedChat: Chat?
    @State private var inputMode: DecryptInputMode = .pasteText
    @State private var encryptedText = ""
    @State private var selectedFileURL: URL?
    @State private var selectedFileName: String?

    @State private var isDecrypting = false
    @State private var decryptedText: String?
    @State private var decryptedImage: UIImage?
    @State private var decryptedDocName: String?
    @State private var decryptedDocURL: URL?
    @State private var errorMessage: String?

    @State private var showFilePicker = false
    @State private var infoMessage: String?
    /// ONE presentation for the whole screen. SwiftUI honours a single presentation
    /// per view node and silently drops any stacked next to it, which is how Open
    /// came to do nothing at all. With one modifier driven by one enum the mistake
    /// cannot be made again. Identifiable-driven, so the content closure is built
    /// only once the payload exists (the BUG 7 lesson).
    @State private var activeSheet: DecryptSheet?

    private let cryptoEngine = CryptoEngine()

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 16) {
                    // Chat selector
                    ChatSelector(chats: chatStore.chats, selectedChat: $selectedChat)

                    // Input mode picker
                    Picker("", selection: $inputMode) {
                        Text(L("decrypt_paste_text")).tag(DecryptInputMode.pasteText)
                        Text(L("decrypt_import_file")).tag(DecryptInputMode.importFile)
                    }
                    .pickerStyle(.segmented)

                    // Input area
                    inputArea

                    // Decrypt button
                    if !isDecrypting {
                        Button {
                            decrypt()
                        } label: {
                            Text(L("decrypt_button"))
                        }
                        .buttonStyle(DarkMessageButtonStyle())
                        .disabled(selectedChat == nil || !hasInput)
                    } else {
                        ProgressView(L("decrypt_decrypting"))
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

                    // Info (e.g. a .darkm file arrived but no chat is selected yet)
                    if let info = infoMessage {
                        Text(info)
                            .foregroundColor(AppTheme.primary)
                            .font(.callout)
                            .padding()
                            .frame(maxWidth: .infinity)
                            .background(
                                RoundedRectangle(cornerRadius: 12)
                                    .fill(AppTheme.primary.opacity(0.12))
                            )
                    }

                    // Result
                    resultArea

                    // Clear button
                    if decryptedText != nil || decryptedImage != nil || decryptedDocURL != nil {
                        Button {
                            clearResult()
                        } label: {
                            Text(L("decrypt_clear"))
                                .foregroundColor(.secondary)
                        }
                    }
                }
                .padding()
            }
            .background(AppTheme.background.ignoresSafeArea())
            .navigationTitle(L("decrypt_title"))
            .toolbar {
                // The keyboard covers the Decrypt button, so the bar above it
                // carries the action itself. Before this, the only way forward
                // was to dismiss the keyboard and then find the button again,
                // and the single button here said "Cancel", which reads as
                // "abandon this" rather than "put the keyboard away".
                ToolbarItemGroup(placement: .keyboard) {
                    Button(L("keyboard_done")) {
                        isTextFocused = false
                    }
                    Spacer()
                    Button(L("decrypt_button")) {
                        isTextFocused = false
                        decrypt()
                    }
                    .fontWeight(.semibold)
                    .disabled(selectedChat == nil || !hasInput)
                }
            }
            .onTapGesture {
                isTextFocused = false
            }
            .sheet(isPresented: $showFilePicker) {
                DocumentPicker { url in
                    selectedFileURL = url
                    selectedFileName = url.lastPathComponent
                }
            }
            .onChange(of: pendingFileURL) { newURL in
                if let url = newURL {
                    handleIncomingFile(url)
                    pendingFileURL = nil
                }
            }
            .onAppear {
                if let url = pendingFileURL {
                    handleIncomingFile(url)
                    pendingFileURL = nil
                }
            }
        }
        // The ONE presentation this screen has, driven by `.sheet(item:)` so the
        // payload is handed into the content closure at presentation time (fixes the
        // empty first-tap share sheet). Attached to the NavigationView, not the
        // ScrollView, so it lives on a different node than the file picker above.
        .sheet(item: $activeSheet) { sheet in
            switch sheet {
            case .share(let payload):
                ActivityView(items: payload.items)
            case .preview(let url):
                DocumentPreview(url: url)
                    .ignoresSafeArea()
            case .export(let url):
                DocumentExporter(url: url) { activeSheet = nil }
            }
        }
    }

    // MARK: - Input Area

    @ViewBuilder
    private var inputArea: some View {
        switch inputMode {
        case .pasteText:
            VStack(spacing: 8) {
                TextEditor(text: $encryptedText)
                    .focused($isTextFocused)
                    .frame(minHeight: 120)
                    .font(.system(.caption, design: .monospaced))
                    .padding(8)
                    .background(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(Color.secondary.opacity(0.3))
                    )
                    .overlay(alignment: .topLeading) {
                        if encryptedText.isEmpty {
                            Text(L("decrypt_input_label"))
                                .foregroundColor(.secondary)
                                .font(.system(.caption, design: .monospaced))
                                .padding(.horizontal, 12)
                                .padding(.vertical, 16)
                                .allowsHitTesting(false)
                        }
                    }

                Button {
                    if let clip = UIPasteboard.general.string {
                        encryptedText = clip
                    }
                } label: {
                    Label(
                        L("decrypt_paste_clipboard"),
                        systemImage: "doc.on.clipboard"
                    )
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(
                        RoundedRectangle(cornerRadius: 10)
                            .stroke(Color.secondary.opacity(0.3))
                    )
                }
            }

        case .importFile:
            VStack(spacing: 12) {
                if let name = selectedFileName {
                    HStack {
                        Image(systemName: "doc.fill")
                            .foregroundColor(AppTheme.primary)
                        Text(L("decrypt_file_selected") + ": " + name)
                            .lineLimit(2)
                    }
                    .padding()
                    .darkMessageCard()
                }

                Button {
                    showFilePicker = true
                } label: {
                    Label(
                        L("decrypt_select_file"),
                        systemImage: "doc.badge.arrow.up"
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
        if let text = decryptedText {
            VStack(spacing: 12) {
                Text(L("decrypt_result_text"))
                    .font(.headline)

                Text(text)
                    .padding()
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(
                        RoundedRectangle(cornerRadius: 12)
                            .fill(AppTheme.surfaceVariant)
                    )

                HStack(spacing: 12) {
                    Button {
                        UIPasteboard.general.string = text
                    } label: {
                        Label(L("decrypt_copy"), systemImage: "doc.on.doc")
                    }
                    .buttonStyle(.bordered)

                    Button {
                        activeSheet = .share(SharePayload(items: [text]))
                    } label: {
                        Label(L("decrypt_share"), systemImage: "square.and.arrow.up")
                    }
                    .buttonStyle(.bordered)
                    .tint(AppTheme.primary)
                }
            }
            .padding()
            .darkMessageCard()
        }

        if let image = decryptedImage {
            VStack(spacing: 12) {
                Text(L("decrypt_result_image"))
                    .font(.headline)

                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(maxHeight: 300)
                    .cornerRadius(12)

                HStack(spacing: 12) {
                    Button {
                        UIImageWriteToSavedPhotosAlbum(image, nil, nil, nil)
                    } label: {
                        Label(L("decrypt_save_gallery"), systemImage: "photo")
                    }
                    .buttonStyle(.bordered)

                    Button {
                        activeSheet = .share(SharePayload(items: [image]))
                    } label: {
                        Label(L("decrypt_share"), systemImage: "square.and.arrow.up")
                    }
                    .buttonStyle(.bordered)
                    .tint(AppTheme.primary)
                }
            }
            .padding()
            .darkMessageCard()
        }

        if let docURL = decryptedDocURL, let docName = decryptedDocName {
            VStack(spacing: 12) {
                Text(L("decrypt_result_document"))
                    .font(.headline)

                HStack {
                    Image(systemName: "doc")
                        .foregroundColor(AppTheme.primary)
                    Text(docName)
                }
                .padding()

                // Three distinct actions. Open and Save both used to be the share
                // sheet, so a received PDF could only be looked at by sending it
                // out to another app first.
                Button {
                    activeSheet = .preview(docURL)
                } label: {
                    Label(L("decrypt_open_document"), systemImage: "eye")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(AppTheme.primary)

                HStack(spacing: 12) {
                    Button {
                        activeSheet = .export(docURL)
                    } label: {
                        Label(L("decrypt_save_document"), systemImage: "square.and.arrow.down")
                    }
                    .buttonStyle(.bordered)

                    Button {
                        activeSheet = .share(SharePayload(items: [docURL]))
                    } label: {
                        Label(L("decrypt_share"), systemImage: "square.and.arrow.up")
                    }
                    .buttonStyle(.bordered)
                    .tint(AppTheme.primary)
                }
            }
            .padding()
            .darkMessageCard()
        }
    }

    // MARK: - Logic

    private var hasInput: Bool {
        switch inputMode {
        case .pasteText: return !encryptedText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case .importFile: return selectedFileURL != nil
        }
    }

    private func decrypt() {
        guard let chat = selectedChat else { return }
        guard let passphrase = chatStore.getPassphrase(for: chat) else {
            errorMessage = L("decrypt_error_no_passphrase")
            return
        }

        errorMessage = nil
        infoMessage = nil
        clearDecryptedResults()
        isDecrypting = true

        Task {
            do {
                let payload: Data

                switch inputMode {
                case .pasteText:
                    guard !encryptedText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                        throw CryptoError.decryptionFailed(L("decrypt_error_empty_text"))
                    }
                    // A paste that is not base64 gets the base64 message, the way
                    // Android does; "invalid data format" is for a payload that decoded
                    // but does not parse. That string shipped translated in both
                    // languages and was referenced nowhere until now.
                    guard let cleaned = PayloadCodec.normalizedBase64(encryptedText),
                          let decoded = Data(base64Encoded: cleaned) else {
                        throw CryptoError.decryptionFailed(L("decrypt_error_bad_base64"))
                    }
                    payload = decoded

                case .importFile:
                    guard let url = selectedFileURL else {
                        throw CryptoError.decryptionFailed(L("decrypt_error_no_file"))
                    }
                    payload = try Data(contentsOf: url)
                }

                let result = try await cryptoEngine.decrypt(payload: payload, passphrase: passphrase)

                await MainActor.run {
                    chatStore.updateLastActivity(chatId: chat.id)

                    switch result.contentType {
                    case .text:
                        decryptedText = String(data: result.data, encoding: .utf8)
                            ?? L("decrypt_error_format")

                    case .image:
                        if let img = UIImage(data: result.data) {
                            decryptedImage = img
                        } else {
                            errorMessage = L("decrypt_image_error")
                        }

                    case .document:
                        // Take off anything a transport glued to the front of the file
                        // itself before it is named or written. Only bytes that were
                        // demonstrably not the file's own are removed - see
                        // transportJunkLength.
                        if let docData = result.documentData.map(Self.repairedDocument) {
                            // The name comes from whoever built the file, so it is never
                            // used as a path as-is (Android does the same in
                            // DecryptViewModel.sanitizeFileName). A name like
                            // "../Documents/chats.json" would otherwise be resolved by the
                            // file system and overwrite the user's own data.
                            //
                            // A name that cannot be used is never a reason to withhold the
                            // document: Android falls back to showing it without one, and
                            // so does this. Only a failed write is reported as an error.
                            let tempDir = FileManager.default.temporaryDirectory
                            var safeName = Self.documentFileName(result.documentName, data: docData)
                            var fileURL = tempDir.appendingPathComponent(safeName)
                            if !Self.isInside(directory: tempDir, url: fileURL) {
                                // Even a refused name must not cost the extension: the
                                // content still says what the file is.
                                safeName = Self.documentFileName(nil, data: docData)
                                fileURL = tempDir.appendingPathComponent(safeName)
                            }
                            do {
                                try docData.write(to: fileURL, options: .atomic)
                                decryptedDocName = safeName
                                decryptedDocURL = fileURL
                            } catch {
                                errorMessage = L("decrypt_error_save")
                            }
                        } else {
                            errorMessage = L("decrypt_error_format")
                        }
                    }

                    isDecrypting = false
                }
            } catch let error as CryptoError {
                await MainActor.run {
                    errorMessage = error.localizedDescription
                    isDecrypting = false
                }
            } catch {
                await MainActor.run {
                    errorMessage = String(format: L("decrypt_error"), error.localizedDescription)
                    isDecrypting = false
                }
            }
        }
    }

    private func clearResult() {
        encryptedText = ""
        selectedFileURL = nil
        selectedFileName = nil
        errorMessage = nil
        infoMessage = nil
        clearDecryptedResults()
    }

    private func clearDecryptedResults() {
        decryptedText = nil
        decryptedImage = nil
        decryptedDocName = nil
        decryptedDocURL = nil
    }

    private func handleIncomingFile(_ url: URL) {
        inputMode = .importFile
        errorMessage = nil
        infoMessage = nil
        clearDecryptedResults()

        // Copy to temp for access (the incoming URL may be security-scoped).
        // Skip the copy if the caller already handed us exactly this temp file.
        let temp = FileManager.default.temporaryDirectory.appendingPathComponent(url.lastPathComponent)
        if url != temp {
            try? FileManager.default.removeItem(at: temp)
            let scoped = url.startAccessingSecurityScopedResource()
            defer {
                if scoped { url.stopAccessingSecurityScopedResource() }
            }
            do {
                try FileManager.default.copyItem(at: url, to: temp)
            } catch {
                errorMessage = L("decrypt_error_read_file")
                return
            }
        }
        selectedFileURL = temp
        selectedFileName = url.lastPathComponent

        // Post-import UX: with exactly one chat, select it automatically. If a chat
        // is selected (auto or by the user) decrypt right away; otherwise tell the
        // user to pick a chat (the Decrypt button stays disabled until they do).
        if selectedChat == nil, chatStore.chats.count == 1 {
            selectedChat = chatStore.chats[0]
        }
        if selectedChat != nil {
            decrypt()
        } else {
            infoMessage = L("decrypt_file_received")
        }
    }

    // MARK: - Untrusted file names

    /// Turns a sender-supplied file name into something safe to hang off a directory.
    /// Same rules as Android's `sanitizeFileName`: keep only the last path segment,
    /// replace the characters that mean something to a file system, drop leading dots
    /// so nothing becomes hidden or a relative reference, and cap the length.
    /// Extension for a document whose name arrived without one, guessed from the
    /// first bytes. The system preview decides what to render from the extension
    /// alone: without one it shows a grey placeholder with the size and nothing
    /// else, however intact the file is.
    static func guessedExtension(for data: Data) -> String? {
        if let ext = signature(of: data, at: 0) { return ext }
        // Nothing recognisable at byte 0. A transport that treated the attachment as
        // text may have glued a CR LF or a byte order mark to the FRONT OF THE FILE,
        // exactly as it does to the payload - a .darkm was observed arriving with
        // "0D 0A" in front of its version byte, and the document inside the very same
        // delivery carries the same two bytes. Look again past that junk.
        let skip = transportJunkLength(in: data)
        return skip > 0 ? signature(of: data, at: skip) : nil
    }

    /// How many leading bytes were added by something that mistook the file for text.
    ///
    /// Zero unless dropping them reveals a format we recognise: without that proof
    /// these are the file's own bytes and must not be touched. Bounded, because
    /// genuine transport damage is a handful of bytes, not a kilobyte.
    static func transportJunkLength(in data: Data) -> Int {
        guard signature(of: data, at: 0) == nil else { return 0 }

        var skip = 0
        if data.count >= 3,
           data[data.startIndex] == 0xEF,
           data[data.startIndex + 1] == 0xBB,
           data[data.startIndex + 2] == 0xBF {
            skip = 3                                            // UTF-8 byte order mark
        }
        while skip < min(data.count, maxTransportJunk),
              PayloadCodec.asciiWhitespace.contains(data[data.startIndex + skip]) {
            skip += 1
        }
        guard skip > 0, signature(of: data, at: skip) != nil else { return 0 }
        return skip
    }

    /// The document as the sender meant it, with any such junk taken off the front.
    ///
    /// Naming it correctly is not enough for every format. A PDF tolerates bytes
    /// before its header - the spec says so - but a .docx is a ZIP, and a ZIP with
    /// two bytes in front of "PK" is simply not a ZIP: Word refuses to open it. The
    /// owner sends Office files constantly, so leaving the junk in place fixed the
    /// label and left the file broken.
    static func repairedDocument(_ data: Data) -> Data {
        let skip = transportJunkLength(in: data)
        return skip > 0 ? Data(data.dropFirst(skip)) : data
    }

    private static let maxTransportJunk = 16

    /// The magic-number table, asked at a given offset.
    private static func signature(of data: Data, at origin: Int) -> String? {
        func starts(_ bytes: [UInt8], at offset: Int = 0) -> Bool {
            let from = origin + offset
            guard data.count >= from + bytes.count else { return false }
            return Array(data.dropFirst(from).prefix(bytes.count)) == bytes
        }
        let head = data.dropFirst(origin).prefix(8192)
        func contains(_ text: String) -> Bool {
            head.range(of: Data(text.utf8)) != nil
        }
        /// The names inside a legacy Office container are UTF-16, so "Workbook"
        /// is stored as W\0o\0r\0k\0... Searching for the plain string misses it.
        func containsWide(_ text: String) -> Bool {
            var wide = Data()
            for scalar in text.unicodeScalars {
                wide.append(UInt8(scalar.value & 0xFF))
                wide.append(UInt8(scalar.value >> 8))
            }
            return head.range(of: wide) != nil
        }

        if starts([0x25, 0x50, 0x44, 0x46]) { return "pdf" }                  // %PDF
        if starts([0xFF, 0xD8, 0xFF]) { return "jpg" }
        if starts([0x89, 0x50, 0x4E, 0x47]) { return "png" }
        if starts([0x47, 0x49, 0x46, 0x38]) { return "gif" }
        if starts([0x49, 0x49, 0x2A, 0x00]) || starts([0x4D, 0x4D, 0x00, 0x2A]) { return "tif" }
        if starts([0x25, 0x21, 0x50, 0x53]) { return "ps" }                   // %!PS
        if starts([0x7B, 0x5C, 0x72, 0x74, 0x66]) { return "rtf" }            // {\rtf
        if starts([0x1F, 0x8B]) { return "gz" }
        if starts([0x52, 0x61, 0x72, 0x21]) { return "rar" }                  // Rar!
        if starts([0x37, 0x7A, 0xBC, 0xAF]) { return "7z" }                   // 7z
        if starts([0x49, 0x44, 0x33]) { return "mp3" }                        // ID3
        if starts([0x52, 0x49, 0x46, 0x46]) {                                 // RIFF
            if starts([0x57, 0x45, 0x42, 0x50], at: 8) { return "webp" }
            if starts([0x57, 0x41, 0x56, 0x45], at: 8) { return "wav" }
        }
        if starts([0x66, 0x74, 0x79, 0x70], at: 4) {                          // ....ftyp
            if starts([0x68, 0x65, 0x69], at: 8) { return "heic" }            // heic / heix
            if starts([0x71, 0x74], at: 8) { return "mov" }                   // qt
            return "mp4"
        }
        if starts([0x50, 0x4B, 0x03, 0x04]) {
            // Every modern Office file is a zip. Look for the part that names the
            // flavour.
            if contains("word/") { return "docx" }
            if contains("xl/") { return "xlsx" }
            if contains("ppt/") { return "pptx" }
            return "zip"
        }
        // Word 97-2003, Excel 97-2003 and the rest share one container format, so
        // the stream names inside it are what tell them apart. The owner sends .xls
        // and .doc regularly, and without this they were the formats a damaged name
        // could not be rescued from.
        if starts([0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1]) {
            if containsWide("WordDocument") { return "doc" }
            if containsWide("Workbook") || containsWide("Book") { return "xls" }
            if containsWide("PowerPoint") { return "ppt" }
            return "doc"
        }
        return nil
    }

    /// Extensions the system knows how to open. A name ending in anything else -
    /// "КП 14.09.2026", "отчёт за 2026г." - has no usable extension however much
    /// it looks like one, and the content decides instead.
    ///
    /// This is the difference between a document that opens and a grey placeholder:
    /// Russian file names carry dates and version numbers after a dot all the time,
    /// so "the name has a dot in it" was never a safe test.
    static let knownExtensions: Set<String> = [
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "rtf", "txt", "csv",
        "json", "xml", "html", "htm", "md", "log", "ps", "epub", "pages", "numbers",
        "key", "jpg", "jpeg", "png", "gif", "tif", "tiff", "bmp", "heic", "heif",
        "webp", "svg", "mp3", "m4a", "wav", "aac", "mp4", "mov", "m4v", "avi",
        "mkv", "zip", "rar", "7z", "gz", "tar", "darkm"
    ]

    /// True when the name already ends in an extension the system recognises.
    static func hasUsableExtension(_ name: String) -> Bool {
        let ext = (name as NSString).pathExtension.lowercased()
        return knownExtensions.contains(ext)
    }

    /// The name a decrypted document is shown and saved under: the sender's name when
    /// it carries an extension the system understands, otherwise that name with one
    /// guessed from the content. Android's `documentFileName` does the same.
    static func documentFileName(_ rawName: String?, data: Data) -> String {
        let safe = sanitizedFileName(rawName ?? "")
        if hasUsableExtension(safe) { return safe }
        guard let guessed = guessedExtension(for: data) else { return safe }
        return sanitizedFileName(safe + "." + guessed)
    }

    static func sanitizedFileName(_ raw: String) -> String {
        let lastSegment = raw
            .split(separator: "/", omittingEmptySubsequences: false).last
            .map(String.init) ?? raw
        let lastComponent = lastSegment
            .split(separator: "\\", omittingEmptySubsequences: false).last
            .map(String.init) ?? lastSegment

        let forbidden: Set<Character> = ["/", "\\", ":", "*", "?", "\"", "<", ">", "|"]
        var cleaned = String(lastComponent.map { character in
            (forbidden.contains(character) || character.isNewline
                || character.unicodeScalars.allSatisfy { scalar in
                    CharacterSet.controlCharacters.contains(scalar)
                }) ? "_" : character
        })

        while cleaned.hasPrefix(".") {
            cleaned.removeFirst()
        }
        cleaned = cleaned.trimmingCharacters(in: .whitespaces)
        // Cutting a long name off at 120 characters used to take the extension with
        // it, which is exactly what makes a perfectly intact document unopenable.
        // Keep the tail and shorten the middle instead.
        if cleaned.count > 120 {
            let ext = (cleaned as NSString).pathExtension
            if !ext.isEmpty, ext.count <= 8 {
                let keep = 120 - ext.count - 1
                let stem = (cleaned as NSString).deletingPathExtension
                cleaned = String(stem.prefix(keep)) + "." + ext
            } else {
                cleaned = String(cleaned.prefix(120))
            }
        }
        return cleaned.isEmpty ? "document" : cleaned
    }

    /// Belt and braces after sanitising: the resolved path must still sit directly
    /// inside the directory we meant to write to.
    /// True when `url` sits directly inside `directory`.
    ///
    /// The one rule here: NEVER normalise a path that ends in the file, because the
    /// file does not exist yet, and every Foundation normalisation consults the file
    /// system. Two versions of this check fell into that trap from opposite sides,
    /// and each one passed every simulator test while breaking every document on the
    /// phone:
    /// - `resolvingSymlinksInPath()` rewrote the existing directory (/var -> /private/var)
    ///   and left the not-yet-created file alone, so the two stopped matching;
    /// - `standardizedFileURL` did the reverse: it strips a leading "/private" ONLY
    ///   when the result exists. The temporary directory exists and became
    ///   /var/mobile/.../tmp; the file did not and stayed /private/var/mobile/.../tmp/x.
    ///   Parent != base, the name fell back to a bare "document", and QuickLook
    ///   showed a grey placeholder. The simulator's paths never carry /private, which
    ///   is why 105 tests could not see it.
    /// So the file's PARENT directory is normalised, which exists, with exactly the same
    /// calls as the base, and the name itself is checked to be a single component.
    static func isInside(directory: URL, url: URL) -> Bool {
        let name = url.lastPathComponent
        guard !name.isEmpty, name != ".", name != "..", !name.contains("/") else { return false }
        let parent = url.deletingLastPathComponent().resolvingSymlinksInPath().standardizedFileURL.path
        let base = directory.resolvingSymlinksInPath().standardizedFileURL.path
        return parent == base
    }
}
