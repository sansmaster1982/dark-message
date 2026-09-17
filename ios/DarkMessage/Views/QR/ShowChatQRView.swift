import Combine
import SwiftUI
import UIKit

/// Sender side of the QR passphrase exchange (spec section 3.1).
///
/// Presented by `ChatsView` as `.fullScreenCover(item: $qrRoute)` for
/// `QRRoute.show(chat)`.
///
/// The screen is a small state machine: `intro` -> `generating` -> `code`,
/// with `code` and `pin` swapping places (the QR code and the PIN are NEVER on
/// screen at the same time), plus `hidden` (timer, backgrounding, screen
/// capture, screenshot) and `expired` (session older than 5 minutes).
///
/// Security notes (spec section 6):
/// - no copy / share / save-image control exists anywhere on this screen;
/// - the payload string, the PIN and the fingerprint live only in this view
///   state and are wiped on dismiss;
/// - nothing sensitive is rendered before the explicit "Show code" tap;
/// - the code hides when the app leaves the foreground, when the screen is
///   being recorded or mirrored, and when a screenshot is taken (which also
///   burns the session: only "New code" is offered afterwards);
/// - the payload, the PIN and the passphrase are never logged.
struct ShowChatQRView: View {

    /// Which panel is on screen. Deliberately without associated values so the
    /// enum stays `Equatable` for the `==` checks in the timer code.
    private enum Phase: Equatable {
        case intro
        case generating
        case code
        case pin
        case hidden
        case expired
    }

    /// Why the sensitive panel was hidden; selects the explanation text.
    private enum HiddenReason {
        case plain       // visibility timer elapsed, or the app left the foreground
        case capture     // screen recording / mirroring is active
        case screenshot  // the user took a screenshot: this session is burned
    }

    let chat: Chat

    @EnvironmentObject private var chatStore: ChatStore
    @EnvironmentObject private var settings: SettingsStore

    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase

    @FocusState private var isNameFocused: Bool

    @State private var phase: Phase = .intro
    @State private var hiddenReason: HiddenReason = .plain
    @State private var senderName: String = ""
    @State private var payloadText: String = ""
    @State private var pin: String = ""
    @State private var fingerprint: String = ""
    /// Monotonic timestamp (ProcessInfo.systemUptime), not a wall-clock Date: a clock
    /// change must not extend the life of a visible key.
    @State private var sessionStartedAt: TimeInterval?
    @State private var isBurned: Bool = false
    @State private var secondsLeft: Int = 0
    @State private var errorMessage: String?
    @State private var showNoPassphraseAlert: Bool = false
    @State private var didLoadSenderName: Bool = false

    // Spec section 0: QR 60 s, PIN 20 s, whole session 300 s.
    private let codeVisibleSeconds: Int = 60
    private let pinVisibleSeconds: Int = 20
    private let sessionLifetime: TimeInterval = 300
    private let nameByteLimit: Int = 64
    private let passphraseByteLimit: Int = 128

    private let ticker = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    // MARK: - Body

    var body: some View {
        NavigationView {
            ZStack {
                AppTheme.background.ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 16) {
                        switch phase {
                        case .intro:
                            introPanel
                        case .generating:
                            generatingPanel
                        case .code:
                            codePanel
                        case .pin:
                            pinPanel
                        case .hidden:
                            hiddenPanel
                        case .expired:
                            expiredPanel
                        }
                    }
                    .padding()
                    .frame(maxWidth: .infinity)
                }
            }
            .navigationTitle(L("qr_share_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(L("qr_done")) {
                        finish()
                    }
                }
                // An iPhone has no hardware back button: the name field needs an
                // explicit way to dismiss the keyboard.
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button(L("qr_done")) {
                        isNameFocused = false
                    }
                }
            }
            .alert(L("encrypt_error_no_passphrase"), isPresented: $showNoPassphraseAlert) {
                Button(L("qr_done")) {
                    finish()
                }
            }
        }
        .onAppear {
            if !didLoadSenderName {
                didLoadSenderName = true
                senderName = clampToByteLimit(settings.qrSenderName)
            }
        }
        .onDisappear {
            clearSensitiveState()
        }
        .onChange(of: senderName) { newValue in
            // Hard cap the field at 64 UTF-8 bytes (the payload limit), so the
            // encoder can never see a too-long name.
            let limited = clampToByteLimit(newValue)
            if limited != newValue {
                senderName = limited
            }
        }
        .onChange(of: scenePhase) { newPhase in
            if newPhase != .active {
                hideSensitive(reason: .plain)
            }
        }
        .onReceive(ticker) { _ in
            tick()
        }
        .onReceive(NotificationCenter.default.publisher(for: UIScreen.capturedDidChangeNotification)) { _ in
            if UIScreen.main.isCaptured {
                hideSensitive(reason: .capture)
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.userDidTakeScreenshotNotification)) { _ in
            // A screenshot of the QR code is the one leak a timer cannot undo:
            // burn the session so that only "New code" remains.
            isBurned = true
            hideSensitive(reason: .screenshot)
        }
    }

    // MARK: - Panels

    private var introPanel: some View {
        VStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Text(L("qr_sender_name"))
                    .font(.subheadline)
                    .foregroundColor(AppTheme.onSurfaceVariant)

                HStack(spacing: 8) {
                    TextField("", text: $senderName)
                        .focused($isNameFocused)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.words)

                    Text("\(senderName.utf8.count)/\(nameByteLimit)")
                        .font(.caption.monospacedDigit())
                        .foregroundColor(AppTheme.onSurfaceVariant)
                }

                Text(L("qr_sender_name_hint"))
                    .font(.caption)
                    .foregroundColor(AppTheme.onSurfaceVariant)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .darkMessageCard()

            warningCard

            if let error = errorMessage {
                errorCard(error)
            }

            Button {
                startSession()
            } label: {
                Text(L("qr_show_button"))
            }
            .buttonStyle(DarkMessageButtonStyle())
        }
    }

    private var generatingPanel: some View {
        VStack(spacing: 16) {
            ProgressView()
                .progressViewStyle(.circular)
                .tint(AppTheme.primary)
                .padding(.top, 40)

            Text(L("qr_code_hint"))
                .font(.footnote)
                .multilineTextAlignment(.center)
                .foregroundColor(AppTheme.onSurfaceVariant)
        }
    }

    private var codePanel: some View {
        VStack(spacing: 16) {
            // QRCodeView draws its own white card (black-on-white regardless of
            // theme). The card must be big enough that the MODULES alone are at
            // least 260 pt (spec §1.3): 260 + 2 x 16 pt of white card padding
            // (the quiet zone, `QRCodeView.cardPadding`) = 292 pt.
            QRCodeView(text: payloadText)
                .frame(width: 292, height: 292)
                .privacySensitive()

            Text(String(format: L("qr_fingerprint"), fingerprint))
                .font(.subheadline.monospaced())
                .foregroundColor(AppTheme.onSurface)

            Text(L("qr_fingerprint_hint"))
                .font(.caption)
                .multilineTextAlignment(.center)
                .foregroundColor(AppTheme.onSurfaceVariant)

            Text(L("qr_code_hint"))
                .font(.footnote)
                .multilineTextAlignment(.center)
                .foregroundColor(AppTheme.onSurfaceVariant)

            countdownText(String(format: L("qr_hides_in"), secondsLeft))

            Button {
                presentPin()
            } label: {
                Text(L("qr_show_pin"))
            }
            .buttonStyle(DarkMessageButtonStyle())

            newCodeButton
            doneButton
        }
    }

    private var pinPanel: some View {
        VStack(spacing: 16) {
            Text(L("qr_pin_label"))
                .font(.subheadline)
                .foregroundColor(AppTheme.onSurfaceVariant)

            Text(formattedPin)
                .font(.system(size: 44, weight: .bold, design: .monospaced))
                .foregroundColor(AppTheme.onSurface)
                .padding(.vertical, 12)
                .frame(maxWidth: .infinity)
                .background(
                    RoundedRectangle(cornerRadius: 16)
                        .fill(AppTheme.surfaceVariant)
                )
                .privacySensitive()
                .accessibilityLabel(Text(spokenPin))

            Text(L("qr_pin_tell_hint"))
                .font(.footnote)
                .multilineTextAlignment(.center)
                .foregroundColor(AppTheme.onSurfaceVariant)

            countdownText(String(format: L("qr_pin_hides_in"), secondsLeft))

            Button {
                presentCode()
            } label: {
                Text(L("qr_show_code_back"))
            }
            .buttonStyle(DarkMessageButtonStyle())

            newCodeButton
            doneButton
        }
    }

    private var hiddenPanel: some View {
        VStack(spacing: 16) {
            Image(systemName: "eye.slash.fill")
                .font(.system(size: 44))
                .foregroundColor(AppTheme.onSurfaceVariant.opacity(0.6))
                .padding(.top, 24)

            Text(L("qr_hidden"))
                .font(.title3.bold())
                .foregroundColor(AppTheme.onSurface)

            if let detail = hiddenDetailText {
                Text(detail)
                    .font(.footnote)
                    .multilineTextAlignment(.center)
                    .foregroundColor(AppTheme.onSurfaceVariant)
            }

            if canShowAgain {
                Button {
                    presentCode()
                } label: {
                    Text(L("qr_show_again"))
                }
                .buttonStyle(DarkMessageButtonStyle())
            }

            newCodeButton
            doneButton
        }
    }

    private var expiredPanel: some View {
        VStack(spacing: 16) {
            Image(systemName: "clock.badge.exclamationmark")
                .font(.system(size: 44))
                .foregroundColor(AppTheme.onSurfaceVariant.opacity(0.6))
                .padding(.top, 24)

            Text(L("qr_expired"))
                .font(.title3.bold())
                .foregroundColor(AppTheme.onSurface)

            newCodeButton
            doneButton
        }
    }

    // MARK: - Shared subviews

    private var warningCard: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundColor(AppTheme.error)
            Text(L("qr_show_warning"))
                .font(.footnote)
                .foregroundColor(AppTheme.onSurfaceVariant)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(AppTheme.error.opacity(0.12))
        )
    }

    private func errorCard(_ text: String) -> some View {
        Text(text)
            .font(.callout)
            .foregroundColor(AppTheme.error)
            .padding()
            .frame(maxWidth: .infinity)
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(AppTheme.error.opacity(0.1))
            )
    }

    private func countdownText(_ text: String) -> some View {
        Text(text)
            .font(.caption.monospacedDigit())
            .foregroundColor(AppTheme.onSurfaceVariant)
    }

    private var newCodeButton: some View {
        Button {
            startSession()
        } label: {
            Text(L("qr_new_code"))
        }
        .buttonStyle(.bordered)
        .tint(AppTheme.primary)
    }

    private var doneButton: some View {
        Button {
            finish()
        } label: {
            Text(L("qr_done"))
                .foregroundColor(AppTheme.onSurfaceVariant)
        }
    }

    // MARK: - Derived values

    /// `123 456` - grouped for reading out loud, entered as plain digits.
    private var formattedPin: String {
        guard pin.count == 6 else { return pin }
        return String(pin.prefix(3)) + " " + String(pin.suffix(3))
    }

    /// VoiceOver reads the digits one by one ("PIN 1 2 3 4 5 6").
    private var spokenPin: String {
        let digits = pin.map { String($0) }.joined(separator: " ")
        return L("qr_pin_label") + " " + digits
    }

    private var hiddenDetailText: String? {
        switch hiddenReason {
        case .plain:
            return nil
        case .capture:
            return L("qr_hidden_capture")
        case .screenshot:
            return L("qr_hidden_screenshot")
        }
    }

    private var isSessionAlive: Bool {
        guard let started = sessionStartedAt else { return false }
        return ProcessInfo.processInfo.systemUptime - started < sessionLifetime
    }

    private var canShowAgain: Bool {
        !isBurned && !payloadText.isEmpty && isSessionAlive
    }

    // MARK: - State machine

    /// Fresh salt / nonce / PIN and a fresh PBKDF2 derivation (about 1 s).
    private func startSession() {
        isNameFocused = false
        errorMessage = nil

        guard let passphrase = chatStore.getPassphrase(for: chat) else {
            showNoPassphraseAlert = true
            return
        }

        if passphrase.utf8.count > passphraseByteLimit {
            phase = .intro
            errorMessage = L("qr_error_passphrase_too_long")
            return
        }

        let trimmedName = clampToByteLimit(
            senderName.trimmingCharacters(in: .whitespacesAndNewlines)
        )
        senderName = trimmedName
        settings.qrSenderName = trimmedName

        let newPin = QRChatCodec.generatePin()
        let newFingerprint = QRChatCodec.fingerprint(of: passphrase)

        isBurned = false
        payloadText = ""
        pin = ""
        secondsLeft = 0
        sessionStartedAt = nil
        phase = .generating

        Task {
            do {
                let text = try await QRChatCodec.encode(
                    name: trimmedName,
                    passphrase: passphrase,
                    pin: newPin
                )
                await MainActor.run {
                    pin = newPin
                    fingerprint = newFingerprint
                    payloadText = text
                    sessionStartedAt = ProcessInfo.processInfo.systemUptime
                    presentCode()
                }
            } catch {
                await MainActor.run {
                    clearSensitiveState()
                    phase = .intro
                    errorMessage = Self.message(for: error)
                }
            }
        }
    }

    private func presentCode() {
        guard !payloadText.isEmpty, isSessionAlive else { return }
        if UIScreen.main.isCaptured {
            hiddenReason = .capture
            secondsLeft = 0
            phase = .hidden
            return
        }
        secondsLeft = codeVisibleSeconds
        phase = .code
    }

    private func presentPin() {
        guard pin.count == 6, isSessionAlive else { return }
        if UIScreen.main.isCaptured {
            hiddenReason = .capture
            secondsLeft = 0
            phase = .hidden
            return
        }
        secondsLeft = pinVisibleSeconds
        phase = .pin
    }

    private func hideSensitive(reason: HiddenReason) {
        guard phase == .code || phase == .pin else { return }
        hiddenReason = reason
        secondsLeft = 0
        phase = .hidden
    }

    private func tick() {
        // Hard session expiry first: it outranks the per-panel countdowns.
        if let started = sessionStartedAt,
           ProcessInfo.processInfo.systemUptime - started >= sessionLifetime {
            clearSensitiveState()
            phase = .expired
            return
        }

        guard phase == .code || phase == .pin else { return }

        if secondsLeft > 1 {
            secondsLeft -= 1
        } else {
            secondsLeft = 0
            hideSensitive(reason: .plain)
        }
    }

    private func finish() {
        clearSensitiveState()
        dismiss()
    }

    private func clearSensitiveState() {
        payloadText = ""
        pin = ""
        fingerprint = ""
        sessionStartedAt = nil
        secondsLeft = 0
    }

    // MARK: - Helpers

    /// Truncates on character boundaries so the name never exceeds 64 UTF-8 bytes.
    private func clampToByteLimit(_ value: String) -> String {
        if value.utf8.count <= nameByteLimit { return value }
        var result = ""
        var used = 0
        for character in value {
            let size = String(character).utf8.count
            if used + size > nameByteLimit { break }
            result.append(character)
            used += size
        }
        return result
    }

    private static func message(for error: Error) -> String {
        switch error as? QRChatError {
        case .some(.passphraseTooLong):
            return L("qr_error_passphrase_too_long")
        case .some(.noPassphrase):
            return L("encrypt_error_no_passphrase")
        default:
            // Unreachable in practice: the length, the name and the PIN are all
            // validated before encode() is called.
            return L("qr_error_generate_failed")
        }
    }
}
