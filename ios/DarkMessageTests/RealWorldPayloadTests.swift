import XCTest
import Foundation
import QuickLook
@testable import DarkMessage

/// The shapes a real `.darkm` from the field actually turns out to have.
///
/// The owner reports that every document he has sent from Android still arrives called
/// "document" with no extension and will not open - after two rounds of fixes that each
/// passed their own tests. So these are not tidy synthetic cases: each one is a payload
/// that could plausibly be sitting in the files on his phone right now, built from what
/// was actually observed.
///
/// Two facts drive them:
/// - the Android build that made those files wrote the LITERAL string "document" into the
///   payload whenever the content provider gave it no display name;
/// - the PDF he sent for inspection genuinely begins with a CR LF before "%PDF", because
///   the source file on the phone was already damaged that way before Dark Message ever
///   saw it.
///
/// Every case here must end with a name the system will open. If one of them fails, that
/// is the bug; if all of them pass, the fault is not in this code and the next place to
/// look is the device.
final class RealWorldPayloadTests: XCTestCase {

    /// A small but structurally real PDF.
    private var pdf: Data {
        Data("""
        %PDF-1.3
        1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
        2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
        3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]>>endobj
        trailer<</Root 1 0 R>>
        %%EOF
        """.utf8)
    }

    /// Builds the DOCUMENT plaintext the way both apps frame it.
    private func frame(name: Data, body: Data, declaredLength: Int? = nil) -> Data {
        let length = declaredLength ?? name.count
        var out = Data([UInt8((length >> 8) & 0xFF), UInt8(length & 0xFF)])
        out.append(name)
        out.append(body)
        return out
    }

    private func decrypt(_ plain: Data, passphrase: String = "field-sample") async throws -> DecryptionResult {
        let engine = CryptoEngine()
        let payload = try await engine.encrypt(data: plain, contentType: .document, passphrase: passphrase)
        return try await engine.decrypt(payload: payload, passphrase: passphrase)
    }

    /// The name the Decrypt screen would put on the card, and whether the system opens it.
    private func nameAndPreviewability(_ result: DecryptionResult) throws -> (String, Bool) {
        let body = try XCTUnwrap(result.documentData)
        let name = DecryptView.documentFileName(result.documentName, data: body)

        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: url)
        try body.write(to: url, options: .atomic)

        XCTAssertTrue(DecryptView.isInside(directory: FileManager.default.temporaryDirectory, url: url),
                      "the containment check refused \"\(name)\" - the screen would fall back to a bare \"document\"")
        return (name, QLPreviewController.canPreview(url as NSURL))
    }

    // MARK: - What his existing files most likely contain

    /// Android wrote the literal "document" as the name, and the PDF itself carries a
    /// CR LF in front of its header. This is the exact combination in the file he sent.
    func testLiteralDocumentNamePlusLeadingCRLFStillOpens() async throws {
        let body = Data([0x0D, 0x0A]) + pdf
        let result = try await decrypt(frame(name: Data("document".utf8), body: body))

        XCTAssertEqual(result.documentName, "document")
        let (name, previewable) = try nameAndPreviewability(result)
        XCTAssertEqual(name, "document.pdf", "the extension was not recovered from the content")
        XCTAssertTrue(previewable, "iOS still refuses to open it")
    }

    /// The same, without the damaged file - a plain PDF the sender could not name.
    func testLiteralDocumentNameOnACleanPdfStillOpens() async throws {
        let result = try await decrypt(frame(name: Data("document".utf8), body: pdf))

        let (name, previewable) = try nameAndPreviewability(result)
        XCTAssertEqual(name, "document.pdf")
        XCTAssertTrue(previewable)
    }

    /// A sender that wrote no name at all: a zero-length frame.
    func testZeroLengthNamePlusLeadingCRLFStillOpens() async throws {
        let body = Data([0x0D, 0x0A]) + pdf
        let result = try await decrypt(frame(name: Data(), body: body))

        XCTAssertNil(result.documentName)
        let (name, previewable) = try nameAndPreviewability(result)
        XCTAssertEqual(name, "document.pdf")
        XCTAssertTrue(previewable)
    }

    /// A frame whose declared length cannot fit: the whole plaintext is the file, and the
    /// two length bytes are then part of it - so "%PDF" sits at offset 2 again.
    func testAnImpossibleNameLengthStillOpens() async throws {
        let plain = frame(name: Data(), body: pdf, declaredLength: 0xFFFF)
        let result = try await decrypt(plain)

        XCTAssertNil(result.documentName)
        let (name, previewable) = try nameAndPreviewability(result)
        XCTAssertEqual(name, "document.pdf")
        XCTAssertTrue(previewable)
    }

    /// A real name that simply lost its extension somewhere upstream.
    func testARealNameWithoutAnExtensionGetsOneBack() async throws {
        let body = Data([0x0D, 0x0A]) + pdf
        let result = try await decrypt(frame(name: Data("КП 14.09.2026".utf8), body: body))

        let (name, previewable) = try nameAndPreviewability(result)
        XCTAssertEqual(name, "КП 14.09.2026.pdf")
        XCTAssertTrue(previewable)
    }

    /// And the case that must keep working: a proper name, untouched.
    func testAProperNameIsLeftAlone() async throws {
        let result = try await decrypt(frame(name: Data("1 Ф9И7 (1).pdf".utf8), body: pdf))

        let (name, previewable) = try nameAndPreviewability(result)
        XCTAssertEqual(name, "1 Ф9И7 (1).pdf")
        XCTAssertTrue(previewable)
    }

    // MARK: - How far in front of the header the junk may sit

    /// The rescue is bounded. Junk longer than the PDF specification's own 1024-byte
    /// window is not a PDF that merely arrived stretched, and guessing would be a lie.
    func testJunkBeyondTheSpecWindowIsNotGuessed() {
        let farOut = Data(repeating: 0x00, count: 1024) + pdf
        XCTAssertNil(DecryptView.guessedExtension(for: farOut))

        let justInside = Data(repeating: 0x00, count: 1000) + pdf
        XCTAssertEqual(DecryptView.guessedExtension(for: justInside), "pdf")
    }
}
