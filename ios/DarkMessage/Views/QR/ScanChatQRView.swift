import AVFoundation
import PhotosUI
import SwiftUI
import UIKit

/// Receiver side of the QR passphrase exchange (spec section 3.3).
///
/// Presented by `ChatsView` as `.fullScreenCover(item: $qrRoute)` for
/// `QRRoute.scan` (starts at the scanner) and for
/// `QRRoute.importPayload(id:_:)` (deep link: starts at the PIN step, the
/// camera is never touched).
///
/// Three steps live inside the one cover: scanning -> PIN -> confirm. Scanned
/// content that is not a valid Dark Message code is never displayed, opened or
/// logged: only a fixed red banner is shown and scanning continues.
struct ScanChatQRView: View {

    private enum Step: Equatable {
        case scanning
        case pin
        case confirm
    }

    private enum CameraGate: Equatable {
        case checking
        case granted
        case denied
        case restricted
        case missing
    }

    /// Non-nil for the deep-link path: the payload was already parsed by
    /// `ContentView.onOpenURL`, so the flow starts at the PIN step.
    private let initialParsed: ParsedQR?

    /// Set to true right before dismissing when the user chose "Enter manually";
    /// `ChatsView` opens its own New-chat sheet from the cover onDismiss.
    @Binding private var pendingManualEntry: Bool

    /// Success text for the capsule toast shown by `ChatsView`.
    @Binding private var toast: String?

    @Environment(\.dismiss) private var dismiss

    @State private var step: Step = .scanning
    @State private var gate: CameraGate = .checking
    @State private var parsed: ParsedQR?
    @State private var payload: QRChatPayload?
    @State private var banner: String?
    @State private var lastRejectedText: String?
    @State private var lastRejectedAt: Date?
    @State private var isTorchOn: Bool = false
    @State private var hasTorch: Bool = false
    @State private var photoItem: PhotosPickerItem?
    @State private var didStart: Bool = false

    /// Identical codes are ignored for this long, so one bad code cannot flood
    /// the banner (spec section 3.3).
    private let duplicateWindow: TimeInterval = 2

    init(
        initialParsed: ParsedQR? = nil,
        pendingManualEntry: Binding<Bool> = .constant(false),
        toast: Binding<String?> = .constant(nil)
    ) {
        self.initialParsed = initialParsed
        self._pendingManualEntry = pendingManualEntry
        self._toast = toast
    }

    // MARK: - Body

    var body: some View {
        NavigationView {
            ZStack {
                AppTheme.background.ignoresSafeArea()

                content

                if let message = banner {
                    bannerView(message)
                }
            }
            .navigationTitle(navigationTitleText)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    if step == .pin {
                        Button {
                            backToScanning()
                        } label: {
                            Image(systemName: "chevron.left")
                        }
                        .accessibilityLabel(Text(L("qr_scan_title")))
                    } else {
                        Button(L("chats_cancel")) {
                            cancel()
                        }
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    if step == .scanning, gate == .granted, hasTorch {
                        Button {
                            isTorchOn.toggle()
                        } label: {
                            Image(systemName: isTorchOn ? "bolt.fill" : "bolt.slash")
                        }
                        .accessibilityLabel(Text(L("qr_torch")))
                    }
                }
            }
        }
        .onAppear {
            start()
        }
        .onDisappear {
            // Nothing sensitive outlives the cover.
            parsed = nil
            payload = nil
            isTorchOn = false
        }
        .onChange(of: photoItem) { newItem in
            loadPhoto(newItem)
        }
    }

    // MARK: - Steps

    @ViewBuilder
    private var content: some View {
        switch step {
        case .scanning:
            scanningContent
        case .pin:
            if let parsedValue = parsed {
                PinEntryStep(
                    parsed: parsedValue,
                    onOpened: { opened in
                        payload = opened
                        parsed = nil
                        banner = nil
                        step = .confirm
                    },
                    onLocked: {
                        backToScanning()
                    }
                )
            } else {
                EmptyView()
            }
        case .confirm:
            if let payloadValue = payload {
                ConfirmChatStep(
                    payload: payloadValue,
                    onAdded: { name in
                        finish(withToast: String(format: L("qr_added"), name))
                    },
                    onUpdated: { name in
                        finish(withToast: String(format: L("qr_updated"), name))
                    },
                    onCancel: {
                        cancel()
                    }
                )
            } else {
                EmptyView()
            }
        }
    }

    @ViewBuilder
    private var scanningContent: some View {
        switch gate {
        case .checking:
            ProgressView()
                .progressViewStyle(.circular)
                .tint(AppTheme.primary)
        case .granted:
            VStack(spacing: 12) {
                scannerView
                // Also reachable while the camera works: a code often arrives as a
                // picture in a messenger, and a phone cannot film its own screen.
                PhotosPicker(selection: $photoItem, matching: .images) {
                    Label(L("qr_scan_from_photo"), systemImage: "photo")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(AppTheme.primary, lineWidth: 1)
                        )
                }
                .padding(.horizontal, 20)
            }
        case .denied, .restricted, .missing:
            fallbackPanel
        }
    }

    private var scannerView: some View {
        ZStack {
            CameraPreviewView(
                isScanning: true,
                torchOn: isTorchOn,
                onCode: { text in
                    handleScannedCode(text)
                }
            )
            .ignoresSafeArea(edges: .bottom)

            RoundedRectangle(cornerRadius: 20)
                .stroke(Color.white.opacity(0.9), lineWidth: 3)
                .frame(width: 260, height: 260)
                .allowsHitTesting(false)

            VStack {
                Spacer()
                Text(L("qr_scan_hint"))
                    .font(.footnote)
                    .multilineTextAlignment(.center)
                    .foregroundColor(.white)
                    .padding(12)
                    .background(
                        RoundedRectangle(cornerRadius: 12)
                            .fill(Color.black.opacity(0.55))
                    )
                    .padding(.horizontal)
                    .padding(.bottom, 32)
            }
            .allowsHitTesting(false)
        }
    }

    /// Shown when there is no camera, or access is denied or restricted.
    /// "Choose from photos" and "Enter manually" are fallbacks only.
    private var fallbackPanel: some View {
        VStack(spacing: 16) {
            Image(systemName: "camera.fill")
                .font(.system(size: 44))
                .foregroundColor(AppTheme.onSurfaceVariant.opacity(0.6))

            Text(gate == .missing ? L("qr_no_camera") : L("qr_camera_denied_title"))
                .font(.title3.bold())
                .multilineTextAlignment(.center)
                .foregroundColor(AppTheme.onSurface)

            if gate != .missing {
                Text(L("qr_camera_denied_body"))
                    .font(.footnote)
                    .multilineTextAlignment(.center)
                    .foregroundColor(AppTheme.onSurfaceVariant)
            }

            if gate == .denied {
                Button {
                    openSystemSettings()
                } label: {
                    Text(L("qr_open_settings"))
                }
                .buttonStyle(DarkMessageButtonStyle())
            }

            PhotosPicker(selection: $photoItem, matching: .images) {
                Label(L("qr_scan_from_photo"), systemImage: "photo")
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(AppTheme.primary, lineWidth: 1)
                    )
            }

            Button {
                enterManually()
            } label: {
                Text(L("qr_enter_manually"))
            }
            .buttonStyle(.bordered)
            .tint(AppTheme.primary)
        }
        .padding()
    }

    private func bannerView(_ text: String) -> some View {
        VStack {
            Text(text)
                .font(.footnote.bold())
                .foregroundColor(.white)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .frame(maxWidth: .infinity)
                .background(
                    RoundedRectangle(cornerRadius: 12)
                        .fill(AppTheme.error)
                )
                .padding(.horizontal)
                .padding(.top, 8)
            Spacer()
        }
        .allowsHitTesting(false)
    }

    // MARK: - Derived values

    private var navigationTitleText: String {
        switch step {
        case .scanning:
            return L("qr_scan_title")
        case .pin:
            return L("qr_pin_title")
        case .confirm:
            return ""
        }
    }

    // MARK: - Camera gate

    private func start() {
        guard !didStart else { return }
        didStart = true

        if let initialValue = initialParsed {
            // Deep link: the payload is already parsed, so the camera (and its
            // permission prompt) is not needed for the PIN step. The gate stays
            // `.checking` and is evaluated only if the user walks back to the
            // scanner (`backToScanning`).
            parsed = initialValue
            step = .pin
            return
        }

        evaluateCameraGate()
    }

    /// Decides what the scanning step shows: the live preview, the fallback
    /// panel, or a spinner while the system prompt is up.
    ///
    /// Called before the scanner step is shown for the first time AND every
    /// time the user returns to it from the PIN step, because the deep-link
    /// path reaches the PIN step without ever touching the camera: a user who
    /// then taps back must still get the system prompt (`.notDetermined`) or
    /// the denied / no-camera panel instead of a black preview.
    private func evaluateCameraGate() {
        guard AVCaptureDevice.default(for: .video) != nil else {
            gate = .missing
            return
        }
        hasTorch = CameraPreviewView.deviceHasTorch

        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            gate = .granted
        case .notDetermined:
            gate = .checking
            AVCaptureDevice.requestAccess(for: .video) { granted in
                DispatchQueue.main.async {
                    gate = granted ? .granted : .denied
                }
            }
        case .restricted:
            gate = .restricted
        case .denied:
            gate = .denied
        @unknown default:
            gate = .denied
        }
    }

    private func openSystemSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    // MARK: - Scanning

    private func handleScannedCode(_ text: String) {
        guard step == .scanning else { return }

        if let last = lastRejectedText,
           let at = lastRejectedAt,
           last == text,
           Date().timeIntervalSince(at) < duplicateWindow {
            return
        }

        process(text)
    }

    /// The only place scanned text is interpreted. On failure nothing from the
    /// scanned string is shown: only one of three fixed messages.
    private func process(_ text: String) {
        switch QRChatCodec.parse(text) {
        case .success(let result):
            lastRejectedText = nil
            lastRejectedAt = nil
            banner = nil
            let feedback = UINotificationFeedbackGenerator()
            feedback.prepare()
            feedback.notificationOccurred(.success)
            parsed = result
            step = .pin
        case .failure(let error):
            lastRejectedText = text
            lastRejectedAt = Date()
            showBanner(Self.message(for: error))
        }
    }

    private func showBanner(_ message: String) {
        banner = message
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            if banner == message {
                banner = nil
            }
        }
    }

    private static func message(for error: QRChatError) -> String {
        switch error {
        case .notDarkMessage:
            return L("qr_error_not_darkmessage")
        case .unsupportedVersion:
            return L("qr_error_version")
        default:
            return L("qr_error_malformed")
        }
    }

    // MARK: - Photos fallback

    private func loadPhoto(_ item: PhotosPickerItem?) {
        guard let item = item else { return }
        item.loadTransferable(type: Data.self) { result in
            // This completion runs off the main queue, which is where the
            // CPU-bound CIDetector work belongs.
            var decoded: String?
            if case .success(let maybeData) = result,
               let data = maybeData,
               let image = UIImage(data: data) {
                decoded = QRPhotoDetector.decode(image)
            }

            DispatchQueue.main.async {
                photoItem = nil
                if let text = decoded {
                    process(text)
                } else {
                    showBanner(L("qr_error_no_qr_in_photo"))
                }
            }
        }
    }

    // MARK: - Navigation

    private func backToScanning() {
        parsed = nil
        payload = nil
        banner = nil
        lastRejectedText = nil
        lastRejectedAt = nil
        step = .scanning
        evaluateCameraGate()
    }

    private func enterManually() {
        pendingManualEntry = true
        cancel()
    }

    private func finish(withToast message: String) {
        parsed = nil
        payload = nil
        toast = message
        dismiss()
    }

    private func cancel() {
        parsed = nil
        payload = nil
        dismiss()
    }
}
