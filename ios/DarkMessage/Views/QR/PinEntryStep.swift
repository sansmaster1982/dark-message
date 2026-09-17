import SwiftUI
import UIKit

/// Step 2 of the receiver flow (spec section 3.3): the 6-digit PIN that unlocks
/// a scanned QR payload.
///
/// Six boxes are drawn over a hidden `.numberPad` `TextField` (the standard iOS
/// trick for a code field). Opening the payload runs PBKDF2 with 600 000
/// iterations, which takes about a second, so it always happens off the main
/// thread while a spinner is shown. The typed PIN is cleared after every attempt
/// and when the step disappears.
struct PinEntryStep: View {

    let parsed: ParsedQR

    /// Called with the decrypted payload after a correct PIN.
    var onOpened: (QRChatPayload) -> Void

    /// Called when the attempts are used up (or the payload turned out to be
    /// corrupt): the caller discards the payload and returns to scanning.
    var onLocked: () -> Void

    @FocusState private var isPinFocused: Bool

    @State private var pin: String = ""
    @State private var attemptsLeft: Int = 5
    @State private var isChecking: Bool = false
    @State private var errorText: String?
    @State private var lockedText: String?
    @State private var shakeOffset: CGFloat = 0

    private let pinLength: Int = 6

    var body: some View {
        VStack(spacing: 20) {
            Text(L("qr_pin_enter_hint"))
                .font(.subheadline)
                .multilineTextAlignment(.center)
                .foregroundColor(AppTheme.onSurfaceVariant)
                .padding(.top, 16)

            ZStack {
                boxes
                hiddenField
            }
            .offset(x: shakeOffset)

            if let locked = lockedText {
                Text(locked)
                    .font(.callout)
                    .multilineTextAlignment(.center)
                    .foregroundColor(AppTheme.error)
            } else if let error = errorText {
                Text(error)
                    .font(.callout)
                    .multilineTextAlignment(.center)
                    .foregroundColor(AppTheme.error)
            }

            if isChecking {
                ProgressView(L("qr_pin_checking"))
                    .tint(AppTheme.primary)
                    .foregroundColor(AppTheme.onSurfaceVariant)
            } else if lockedText == nil {
                Button {
                    submit()
                } label: {
                    Text(L("qr_pin_continue"))
                }
                .buttonStyle(DarkMessageButtonStyle())
                .disabled(pin.count < pinLength)
            }

            Spacer(minLength: 0)
        }
        .padding()
        .frame(maxWidth: .infinity)
        .toolbar {
            // An iPhone has no hardware back button and the number pad has no
            // return key: always offer an explicit way to close the keyboard.
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button(L("qr_done")) {
                    isPinFocused = false
                }
            }
        }
        .onAppear {
            // Slight delay: focusing while the full-screen cover is still
            // animating does not reliably raise the keyboard.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                if lockedText == nil {
                    isPinFocused = true
                }
            }
        }
        .onDisappear {
            pin = ""
            isPinFocused = false
        }
        .onChange(of: pin) { newValue in
            // ASCII digits only, never longer than 6: the codec accepts exactly
            // `^[0-9]{6}$`, and paste or dictation can deliver anything.
            let digits = newValue.filter { $0.isASCII && $0.isNumber }
            let limited = String(digits.prefix(pinLength))
            if limited != newValue {
                pin = limited
            }
        }
    }

    // MARK: - Subviews

    /// The real input, laid over the boxes at their full size so a tap reaches it
    /// directly and iOS raises the number pad on its own. Text and caret are clear,
    /// so only the boxes below are visible; they render `pin` themselves.
    private var hiddenField: some View {
        TextField("", text: $pin)
            .keyboardType(.numberPad)
            .textContentType(.oneTimeCode)
            .focused($isPinFocused)
            .multilineTextAlignment(.center)
            .foregroundColor(.clear)
            .tint(.clear)
            .disabled(lockedText != nil)
            .frame(height: 56)
            .contentShape(Rectangle())
            .accessibilityLabel(Text(L("qr_pin_enter_hint")))
    }

    private var boxes: some View {
        HStack(spacing: 10) {
            ForEach(0..<pinLength, id: \.self) { index in
                RoundedRectangle(cornerRadius: 10)
                    .fill(AppTheme.surfaceVariant)
                    .frame(width: 44, height: 56)
                    .overlay(
                        RoundedRectangle(cornerRadius: 10)
                            .stroke(
                                isPinFocused && index == pin.count
                                    ? AppTheme.primary
                                    : Color.clear,
                                lineWidth: 2
                            )
                    )
                    .overlay(
                        Text(digit(at: index))
                            .font(.system(size: 26, weight: .semibold, design: .monospaced))
                            .foregroundColor(AppTheme.onSurface)
                    )
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    // MARK: - Logic

    private func digit(at index: Int) -> String {
        let characters = Array(pin)
        guard index >= 0, index < characters.count else { return "" }
        return String(characters[index])
    }

    private func submit() {
        guard pin.count == pinLength, !isChecking, lockedText == nil else { return }

        let entered = pin
        isPinFocused = false
        isChecking = true
        errorText = nil

        Task {
            do {
                let payload = try await QRChatCodec.open(parsed, pin: entered)
                await MainActor.run {
                    isChecking = false
                    pin = ""
                    onOpened(payload)
                }
            } catch {
                let corrupt = Self.isMalformed(error)
                await MainActor.run {
                    handleFailure(corrupt: corrupt)
                }
            }
        }
    }

    /// A wrong PIN and a corrupt payload are reported only after a full PBKDF2
    /// derivation, so timing tells an attacker nothing.
    private func handleFailure(corrupt: Bool) {
        isChecking = false
        pin = ""

        if corrupt {
            // The header parsed but the plaintext did not: the code itself is
            // unusable, so no further attempts make sense.
            errorText = nil
            lockedText = L("qr_error_malformed")
            scheduleReturnToScanning()
            return
        }

        attemptsLeft -= 1

        if attemptsLeft <= 0 {
            attemptsLeft = 0
            errorText = nil
            lockedText = L("qr_pin_locked")
            scheduleReturnToScanning()
        } else {
            errorText = String(format: L("qr_pin_wrong"), attemptsLeft)
            shake()
            isPinFocused = true
        }
    }

    private func scheduleReturnToScanning() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.8) {
            onLocked()
        }
    }

    private func shake() {
        withAnimation(.linear(duration: 0.06).repeatCount(5, autoreverses: true)) {
            shakeOffset = 10
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
            shakeOffset = 0
        }
    }

    private static func isMalformed(_ error: Error) -> Bool {
        guard let qrError = error as? QRChatError else { return false }
        switch qrError {
        case .malformed:
            return true
        default:
            return false
        }
    }
}
