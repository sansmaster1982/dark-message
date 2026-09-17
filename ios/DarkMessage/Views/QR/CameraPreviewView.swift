import SwiftUI
import AVFoundation
import CoreImage
import UIKit

// MARK: - Preview host view

/// Plain `UIView` whose only job is to host the `AVCaptureVideoPreviewLayer`
/// and keep its frame in sync. A `CALayer` added as a sublayer is **not**
/// resized by UIKit, so without this the preview stays 0x0 (or keeps the size
/// it had before a rotation / cover presentation) — a classic camera bug.
final class CameraPreviewHostView: UIView {

    let previewLayer = AVCaptureVideoPreviewLayer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        previewLayer.videoGravity = .resizeAspectFill
        layer.addSublayer(previewLayer)
    }

    required init?(coder: NSCoder) {
        fatalError("CameraPreviewHostView is created in code only")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        previewLayer.frame = bounds
    }
}

// MARK: - Capture session controller

/// Owns the `AVCaptureSession`. All session work happens on one serial
/// background queue (`startRunning()` / `stopRunning()` block for a noticeable
/// time and must never run on the main thread); the metadata delegate is
/// served on that same queue and hands accepted strings back on the main queue.
///
/// Nothing here is ever logged: a scanned string may be a foreign QR code and
/// spec section 6 item 2 forbids printing scanner input.
final class QRScannerController: NSObject, AVCaptureMetadataOutputObjectsDelegate {

    /// The same string is handed over at most once per 2 s.
    private static let identicalCodeInterval: TimeInterval = 2.0
    /// Short cool-down after *any* delivered code, so a single code in front of
    /// the lens cannot produce a burst of callbacks.
    private static let deliveryCooldown: TimeInterval = 0.5

    /// Attached to the preview layer by `CameraPreviewView.makeUIView`.
    let session = AVCaptureSession()

    private let sessionQueue = DispatchQueue(label: "com.darkmessage.ios.qr.session")

    /// Called on the main queue for every accepted code.
    var onCode: ((String) -> Void)?

    // Touched on `sessionQueue` only.
    private var videoDevice: AVCaptureDevice?
    private var isConfigured = false
    private var configurationFailed = false
    private var shouldRun = false
    private var lastDeliveredCode: String?
    private var lastDeliveredAt: Date?
    private var lastDeliveryAt: Date?

    private var observers: [NSObjectProtocol] = []

    override init() {
        super.init()
        addObservers()
    }

    deinit {
        let center = NotificationCenter.default
        for observer in observers {
            center.removeObserver(observer)
        }
        // Do not capture `self` in the closure: only the session is needed.
        let session = self.session
        sessionQueue.async {
            if session.isRunning {
                session.stopRunning()
            }
        }
    }

    // MARK: Running state

    /// `true` while the scanner should be live. The receiver view passes
    /// `false` as soon as a code is accepted (stop after the first accepted
    /// code) and while the app is not active.
    func setRunning(_ run: Bool) {
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            self.shouldRun = run
            if run {
                self.configureIfNeeded()
                guard self.isConfigured, !self.session.isRunning else { return }
                self.session.startRunning()
            } else {
                self.resetDebounce()
                guard self.session.isRunning else { return }
                self.session.stopRunning()
            }
        }
    }

    /// Torch, guarded by `hasTorch` (the toggle is only offered on devices that
    /// have one).
    func setTorch(_ on: Bool) {
        sessionQueue.async { [weak self] in
            guard let self = self,
                  let device = self.videoDevice,
                  device.hasTorch else { return }
            let mode: AVCaptureDevice.TorchMode = on ? .on : .off
            guard device.isTorchModeSupported(mode), device.torchMode != mode else { return }
            do {
                try device.lockForConfiguration()
                device.torchMode = mode
                device.unlockForConfiguration()
            } catch {
                // The torch is a convenience; a device locked by another
                // client is not worth surfacing to the user.
            }
        }
    }

    // MARK: Configuration

    private func configureIfNeeded() {
        guard !isConfigured, !configurationFailed else { return }
        guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized,
              let device = AVCaptureDevice.default(for: .video) else {
            // The receiver view runs the permission / no-camera gate before it
            // ever presents this view; this is only a safety net.
            configurationFailed = true
            return
        }

        session.beginConfiguration()

        do {
            let input = try AVCaptureDeviceInput(device: device)
            guard session.canAddInput(input) else {
                session.commitConfiguration()
                configurationFailed = true
                return
            }
            session.addInput(input)
        } catch {
            session.commitConfiguration()
            configurationFailed = true
            return
        }

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            session.commitConfiguration()
            configurationFailed = true
            return
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: sessionQueue)
        session.commitConfiguration()

        // `metadataObjectTypes` may only be set AFTER the output has been added
        // to a session (setting it on a detached output raises an exception),
        // and `availableMetadataObjectTypes` is only guaranteed to reflect the
        // new connection once the configuration has been COMMITTED — hence the
        // commit above before reading it. If QR is not offered there is nothing
        // this view could ever decode, so fail instead of running a live
        // preview that can never deliver a code.
        guard output.availableMetadataObjectTypes.contains(AVMetadataObject.ObjectType.qr) else {
            configurationFailed = true
            return
        }
        output.metadataObjectTypes = [AVMetadataObject.ObjectType.qr]

        videoDevice = device
        isConfigured = true
    }

    // MARK: Interruptions and runtime errors

    private func addObservers() {
        let center = NotificationCenter.default
        observers.append(
            center.addObserver(
                forName: AVCaptureSession.wasInterruptedNotification,
                object: session,
                queue: .main
            ) { [weak self] _ in
                // The system has already stopped the session (phone call,
                // Slide Over, another camera client). Forget the debounce so
                // the same code can be scanned again afterwards.
                self?.clearDebounce()
            }
        )
        observers.append(
            center.addObserver(
                forName: AVCaptureSession.interruptionEndedNotification,
                object: session,
                queue: .main
            ) { [weak self] _ in
                self?.restartIfNeeded()
            }
        )
        observers.append(
            center.addObserver(
                forName: AVCaptureSession.runtimeErrorNotification,
                object: session,
                queue: .main
            ) { [weak self] _ in
                // Restarting is the documented remedy for a media-services
                // reset; it is a no-op when the scanner must not run.
                self?.restartIfNeeded()
            }
        )
    }

    private func clearDebounce() {
        sessionQueue.async { [weak self] in
            self?.resetDebounce()
        }
    }

    private func restartIfNeeded() {
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            self.resetDebounce()
            guard self.shouldRun, self.isConfigured, !self.session.isRunning else { return }
            self.session.startRunning()
        }
    }

    private func resetDebounce() {
        lastDeliveredCode = nil
        lastDeliveredAt = nil
        lastDeliveryAt = nil
    }

    // MARK: AVCaptureMetadataOutputObjectsDelegate

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard shouldRun else { return }

        var code: String?
        for object in metadataObjects {
            guard let readable = object as? AVMetadataMachineReadableCodeObject,
                  readable.type == AVMetadataObject.ObjectType.qr,
                  let value = readable.stringValue,
                  !value.isEmpty else { continue }
            code = value
            break
        }
        guard let value = code else { return }

        let now = Date()
        if let last = lastDeliveryAt,
           now.timeIntervalSince(last) < QRScannerController.deliveryCooldown {
            return
        }
        if value == lastDeliveredCode,
           let last = lastDeliveredAt,
           now.timeIntervalSince(last) < QRScannerController.identicalCodeInterval {
            return
        }

        lastDeliveredCode = value
        lastDeliveredAt = now
        lastDeliveryAt = now

        DispatchQueue.main.async { [weak self] in
            self?.onCode?(value)
        }
    }
}

// MARK: - SwiftUI wrapper

/// Live camera preview that reports decoded QR strings.
///
///     CameraPreviewView(torchOn: isTorchOn, onCode: { text in
///         handleScannedCode(text)
///     })
///
/// API contract for the receiver screen:
///
/// - `onCode`: called on the main queue; the same string is never delivered
///   twice within 2 s, and every delivery is followed by a 0.5 s cool-down.
/// - `torchOn`: only bind a toggle when the device has a torch
///   (`CameraPreviewView.deviceHasTorch`, or `AVCaptureDevice.default(for:
///   .video)?.hasTorch` as `ScanChatQRView` does). The torch is switched off
///   again when the view goes away.
/// - Stopping after the first accepted code: the receiver screen renders this
///   view only in its scanning step, so moving to the PIN step removes it and
///   `dismantleUIView` stops the session (camera and its indicator go off).
///   `isScanning: false` does the same without removing the preview, for a
///   caller that wants to keep the frozen viewfinder on screen.
/// - The preview pauses itself whenever `scenePhase != .active` and resumes
///   when the app becomes active again.
struct CameraPreviewView: UIViewRepresentable {

    var isScanning: Bool = true
    var torchOn: Bool = false
    let onCode: (String) -> Void

    @Environment(\.scenePhase) private var scenePhase

    /// The torch toggle is only shown when the camera actually has a torch.
    static var deviceHasTorch: Bool {
        AVCaptureDevice.default(for: .video)?.hasTorch ?? false
    }

    func makeCoordinator() -> QRScannerController {
        QRScannerController()
    }

    func makeUIView(context: Context) -> CameraPreviewHostView {
        let view = CameraPreviewHostView()
        view.previewLayer.session = context.coordinator.session
        context.coordinator.onCode = onCode
        apply(to: context.coordinator)
        return view
    }

    func updateUIView(_ uiView: CameraPreviewHostView, context: Context) {
        context.coordinator.onCode = onCode
        // SwiftUI can resize this view without UIKit laying it out first, so
        // sync the layer here as well as in `layoutSubviews`.
        uiView.previewLayer.frame = uiView.bounds
        apply(to: context.coordinator)
    }

    static func dismantleUIView(_ uiView: CameraPreviewHostView, coordinator: QRScannerController) {
        coordinator.setTorch(false)
        coordinator.setRunning(false)
    }

    private func apply(to controller: QRScannerController) {
        let live = isScanning && scenePhase == .active
        controller.setRunning(live)
        controller.setTorch(torchOn && live)
    }
}

// MARK: - Photo library fallback

/// Decodes a QR code out of a still image, for the "Choose from photos"
/// fallback (shown when there is no camera or access was denied).
///
/// Synchronous and CPU-bound — call it off the main queue for large photos.
/// The decoded string is returned verbatim; validating it is
/// `QRChatCodec.parse`'s job, and it is never logged here.
enum QRPhotoDetector {

    static func decode(_ image: UIImage) -> String? {
        let ciImage: CIImage
        if let cgImage = image.cgImage {
            ciImage = CIImage(cgImage: cgImage)
        } else if let existing = image.ciImage {
            ciImage = existing
        } else {
            return nil
        }

        guard let detector = CIDetector(
            ofType: CIDetectorTypeQRCode,
            context: nil,
            options: [CIDetectorAccuracy: CIDetectorAccuracyHigh]
        ) else {
            return nil
        }

        // `CIImage(cgImage:)` drops the UIImage's orientation, so pass it in.
        let options: [String: Any] = [
            CIDetectorImageOrientation: NSNumber(value: exifOrientation(of: image.imageOrientation))
        ]

        for feature in detector.features(in: ciImage, options: options) {
            guard let qr = feature as? CIQRCodeFeature,
                  let message = qr.messageString,
                  !message.isEmpty else { continue }
            return message
        }
        return nil
    }

    private static func exifOrientation(of orientation: UIImage.Orientation) -> Int {
        switch orientation {
        case .up: return 1
        case .upMirrored: return 2
        case .down: return 3
        case .downMirrored: return 4
        case .leftMirrored: return 5
        case .right: return 6
        case .rightMirrored: return 7
        case .left: return 8
        @unknown default: return 1
        }
    }
}
