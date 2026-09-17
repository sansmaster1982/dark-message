import SwiftUI
import QuickLook
import UIKit

/// Everything the Decrypt screen can put on top of itself, as ONE type.
///
/// SwiftUI honours a single presentation per view node and silently drops any
/// stacked next to it; that is how the Open button came to do nothing at all.
/// A single `.sheet(item:)` driven by this enum makes the mistake impossible.
/// Identifiable so the content closure is built only once the payload exists
/// (the BUG 7 lesson), and the id changes between cases so switching from one
/// to another re-presents rather than reusing the old content.
enum DecryptSheet: Identifiable {
    case share(SharePayload)
    case preview(URL)
    case export(URL)

    var id: String {
        switch self {
        case .share(let payload): return "share-\(payload.id)"
        case .preview(let url): return "preview-\(url.absoluteString)"
        case .export(let url): return "export-\(url.absoluteString)"
        }
    }
}

/// Shows a decrypted document inside the app.
///
/// Until this existed, both buttons on the document card opened the share sheet,
/// so the only way to look at a received PDF was to send it out to another app
/// first. Android has been able to open one in place all along.
struct DocumentPreview: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> UINavigationController {
        let controller = QLPreviewController()
        controller.dataSource = context.coordinator
        controller.navigationItem.leftBarButtonItem = UIBarButtonItem(
            title: L("qr_done"),
            style: .done,
            target: context.coordinator,
            action: #selector(Coordinator.dismiss)
        )
        let navigation = UINavigationController(rootViewController: controller)
        context.coordinator.navigation = navigation
        return navigation
    }

    func updateUIViewController(_ controller: UINavigationController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(url: url) }

    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        private let url: URL
        weak var navigation: UINavigationController?

        init(url: URL) { self.url = url }

        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }

        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem {
            url as NSURL
        }

        @objc func dismiss() {
            navigation?.dismiss(animated: true)
        }
    }
}

/// Hands a decrypted document to the system "save to Files" flow.
///
/// The Save button used to open the share sheet, exactly like the Share button
/// next to it. Android's Save really saves, and now this one does too.
struct DocumentExporter: UIViewControllerRepresentable {
    let url: URL
    let onFinish: () -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        // asCopy: the file lives in the app's temporary directory and is thrown
        // away when the result is cleared, so the system must copy it out.
        let picker = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish) }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        private let onFinish: () -> Void
        init(onFinish: @escaping () -> Void) { self.onFinish = onFinish }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            onFinish()
        }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            onFinish()
        }
    }
}
