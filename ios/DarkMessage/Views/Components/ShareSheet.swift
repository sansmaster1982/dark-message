import SwiftUI
import UIKit

/// Payload for the system share sheet.
///
/// Driven by `.sheet(item:)`: the payload is handed INTO the sheet content
/// closure at presentation time, so the share sheet can never be built with
/// stale or empty items (which is what happened with the old
/// `.sheet(isPresented:)` + separate `@State` items-array pattern: the first
/// tap showed an empty sheet, the second tap showed the previous tap items).
struct SharePayload: Identifiable {
    let id = UUID()
    let items: [Any]
}

/// UIActivityViewController wrapper. Shared by EncryptView and DecryptView.
struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
