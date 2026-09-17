import SwiftUI
import CoreImage
import CoreImage.CIFilterBuiltins
import UIKit

/// Renders a QR code for `text` on a white card.
///
/// Spec §1.3: byte mode, error correction **M**, black `#000000` on white
/// `#FFFFFF` *regardless of the app theme*, nearest-neighbour scaling (no
/// interpolation, so module edges stay crisp), at least 10 px per module and a
/// quiet zone of white around the modules.
///
/// The card is square and fills whatever space it is offered, so the caller
/// decides the on-screen size (spec: at least 260 pt):
///
///     QRCodeView(text: payloadText)
///         .frame(width: 280, height: 280)
///         .privacySensitive()
///
/// The caller also owns `.privacySensitive()` and dropping the text when the
/// code is hidden — this view only draws what it is given and never logs,
/// copies or shares it.
struct QRCodeView: View {

    /// The exact `darkmessage://chat/1/…` string to encode. Encoded as UTF-8
    /// bytes (`Data(text.utf8)`); the payload is pure ASCII by construction.
    let text: String

    /// White margin between the modules and the card's edge — the quiet zone.
    /// Spec §1.3: at least 16 pt.
    var cardPadding: CGFloat = 16

    /// Minimum pixels per module (spec §1.3).
    private static let minimumPixelsPerModule: Double = 10

    /// Bitmap side to aim for: large enough that no realistic on-screen size
    /// needs to stretch it, small enough to stay a few MB. Nearest-neighbour
    /// scaling of a bitmap this dense never drops a module in either direction.
    private static let targetBitmapSide: Double = 1000

    private static let ciContext = CIContext(options: nil)

    @State private var image: UIImage?

    var body: some View {
        content
            .padding(cardPadding)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(Color.white)
            )
            .aspectRatio(1, contentMode: .fit)
            .onAppear { render() }
            .onChange(of: text) { _, _ in render() }
    }

    @ViewBuilder
    private var content: some View {
        if let image = image {
            Image(uiImage: image)
                .interpolation(.none)
                .antialiased(false)
                .resizable()
                .scaledToFit()
                .accessibilityLabel(L("qr_a11y_qr_image"))
        } else {
            // Generating a QR for <= 339 ASCII chars cannot realistically fail;
            // this is a visible, non-crashing fallback instead of a blank card.
            // No body text: the only QR-error string in the catalogue addresses
            // the receiver, and this card belongs to the sender.
            Image(systemName: "exclamationmark.triangle")
                .font(.largeTitle)
                .foregroundColor(.black)
                .accessibilityLabel(L("qr_error_malformed"))
        }
    }

    private func render() {
        image = QRCodeView.makeImage(
            text: text,
            minimumSidePixels: QRCodeView.targetBitmapSide
        )
    }

    /// Black-on-white QR bitmap, scaled by a whole number so every module is an
    /// exact square block of pixels.
    static func makeImage(text: String, minimumSidePixels: Double) -> UIImage? {
        guard !text.isEmpty else { return nil }

        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"

        guard let output = filter.outputImage else { return nil }

        // The generator emits one pixel per module (plus its own small border),
        // so the extent's width is the number of pixels to scale up.
        let moduleSide = Double(output.extent.width)
        guard moduleSide >= 1 else { return nil }

        let neededScale = (minimumSidePixels / moduleSide).rounded(.up)
        let scale = max(minimumPixelsPerModule, neededScale)

        let scaled = output.transformed(
            by: CGAffineTransform(scaleX: CGFloat(scale), y: CGFloat(scale))
        )
        guard let cgImage = ciContext.createCGImage(scaled, from: scaled.extent) else {
            return nil
        }
        return UIImage(cgImage: cgImage)
    }
}
