import Foundation

/// The single value that drives **every** QR presentation in the app.
///
/// ## Why `.fullScreenCover(item:)` and never `.fullScreenCover(isPresented:)`
///
/// `.fullScreenCover(isPresented: $flag) { SomeQRView(payload: payload) }`
/// builds its content from state that the very same event mutated, so the
/// first presentation captures the *previous* (usually empty) payload — this is
/// exactly the "first tap shows nothing, second tap works" bug that was fixed
/// in `EncryptView` / `DecryptView` by switching the share sheet to
/// `.sheet(item:)` (see `Views/Components/ShareSheet.swift`).
///
/// With `.fullScreenCover(item: $qrRoute)` the payload travels *inside* the
/// item, so it is handed into the content closure at presentation time and can
/// never be stale, empty or nil.
///
/// ## Usage (ChatsView)
///
///     @State private var qrRoute: QRRoute?
///
///     // Sender entry points (swipe action / context menu):
///     qrRoute = .show(chat)
///     // Receiver entry points (toolbar icon / empty state button):
///     qrRoute = .scan
///     // Deep link `darkmessage://chat/1/…` already parsed by ContentView:
///     qrRoute = .importing(parsed)
///
///     .fullScreenCover(item: $qrRoute) { route in
///         switch route {
///         case .show(let chat):
///             // sender screen for this chat
///         case .scan:
///             // receiver screen, starts on the camera step
///         case .importPayload(_, let parsed):
///             // receiver screen, starts on the PIN step with `parsed`
///         }
///     }
///
/// `id` is stable per value, so SwiftUI never re-presents the same cover for
/// the same chat. A payload that arrives a second time must therefore be
/// wrapped in a *new* `importPayload` — `QRRoute.importing(_:)` mints a fresh
/// id for exactly that reason.
///
/// Deliberately **not** `Equatable`/`Hashable`: `ParsedQR` carries raw
/// ciphertext and makes no such promise, and identity is `id` alone.
enum QRRoute: Identifiable {

    /// Sender side: show the QR code (and, on its own second screen, the PIN)
    /// for a chat whose passphrase lives on this device.
    case show(Chat)

    /// Receiver side: open the camera scanner.
    case scan

    /// Receiver side: a payload that arrived already parsed (deep link), so the
    /// receiver screen opens directly on the PIN step. `id` exists only to give
    /// every arriving payload a distinct identity.
    case importPayload(id: UUID, ParsedQR)

    var id: String {
        switch self {
        case .show(let chat):
            return "show-" + chat.id.uuidString
        case .scan:
            return "scan"
        case .importPayload(let payloadID, _):
            return "import-" + payloadID.uuidString
        }
    }

    /// Wraps a freshly parsed payload with a new identity, so presenting it
    /// twice in a row still re-presents the cover.
    static func importing(_ parsed: ParsedQR) -> QRRoute {
        .importPayload(id: UUID(), parsed)
    }
}
