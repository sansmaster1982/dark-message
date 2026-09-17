import XCTest
import Foundation
import QuickLook
@testable import DarkMessage

/// Tolerance for the damage a transport does to an encrypted file.
///
/// Mirrors the Android cases in `PayloadCodecTest` and `CryptoEngineImplTest`
/// one for one. If a case here is changed, change it on Android in the same
/// commit: both apps have to accept and refuse exactly the same bytes.
///
/// Origin, 2026-09-13: every `.darkm` file that reached an Android phone from an
/// iPhone arrived with a leading CR LF and was rejected as an invalid format,
/// although the payload behind those two bytes was intact.
final class PayloadToleranceTests: XCTestCase {

    // MARK: - Helpers

    /// A syntactically valid payload. The ciphertext is not real, which is fine:
    /// these cases are about parsing, not about authentication.
    private func samplePayload(
        contentType: ContentType = .image,
        ciphertext: Data? = nil
    ) -> Data {
        PayloadCodec.encode(
            contentType: contentType,
            salt: Data((0..<16).map { UInt8($0) }),
            nonce: Data((0..<12).map { UInt8($0 + 100) }),
            ciphertext: ciphertext ?? Data((0..<40).map { UInt8($0 + 7) })
        )
    }

    private func assertDecodesIdentically(
        _ mangled: Data,
        to original: Data,
        file: StaticString = #filePath,
        line: UInt = #line
    ) throws {
        let expected = try PayloadCodec.decode(original)
        let actual = try PayloadCodec.decode(mangled)
        XCTAssertEqual(actual.version, expected.version, file: file, line: line)
        XCTAssertEqual(actual.contentType, expected.contentType, file: file, line: line)
        XCTAssertEqual(actual.salt, expected.salt, file: file, line: line)
        XCTAssertEqual(actual.nonce, expected.nonce, file: file, line: line)
        XCTAssertEqual(actual.ciphertext, expected.ciphertext, file: file, line: line)
    }

    // MARK: - Leading noise

    func testPayloadPrecededByCarriageReturnLineFeedStillDecodes() throws {
        let payload = samplePayload()
        try assertDecodesIdentically(Data([0x0D, 0x0A]) + payload, to: payload)
    }

    func testSpacesTabsAndNewlinesInFrontAreAllSkipped() throws {
        let payload = samplePayload()
        let mangled = Data([0x20, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20]) + payload
        try assertDecodesIdentically(mangled, to: payload)
    }

    func testCiphertextEndingInANewlineIsLeftAlone() throws {
        // Nothing may be stripped from the end while parsing: the last byte belongs
        // to the GCM tag and 0x0A is a perfectly legal value for it.
        var tail = Data((0..<39).map { UInt8($0 + 7) })
        tail.append(0x0A)
        let payload = samplePayload(ciphertext: tail)

        let decoded = try PayloadCodec.decode(payload)

        XCTAssertEqual(decoded.ciphertext.last, 0x0A)
        XCTAssertEqual(decoded.ciphertext.count, 40)
    }

    func testNothingButWhitespaceIsStillRejected() {
        XCTAssertThrowsError(try PayloadCodec.decode(Data(repeating: 0x0A, count: 64)))
    }

    func testNoiseInFrontDoesNotMakeATooShortPayloadDecode() {
        let tooShort = Data([0x0D, 0x0A, 0x01, 0x01]) + Data(repeating: 0, count: 10)
        XCTAssertThrowsError(try PayloadCodec.decode(tooShort))
    }

    func testWithoutLeadingNoiseLeavesACleanPayloadUntouched() {
        let payload = samplePayload()
        XCTAssertEqual(PayloadCodec.withoutLeadingNoise(payload), payload)
    }

    // MARK: - Trailing noise, through the real engine

    private let engine = CryptoEngine()

    func testFileThatArrivedWithATrailingCarriageReturnLineFeedStillDecrypts() async throws {
        let original = "Встретимся в 19:00"
        let encrypted = try await engine.encrypt(
            data: Data(original.utf8), contentType: .text, passphrase: "ключ-1"
        )

        let result = try await engine.decrypt(
            payload: encrypted + Data([0x0D, 0x0A]), passphrase: "ключ-1"
        )

        XCTAssertEqual(String(data: result.data, encoding: .utf8), original)
    }

    func testNoiseOnBothEndsAtOnceStillDecrypts() async throws {
        let original = "both ends"
        let encrypted = try await engine.encrypt(
            data: Data(original.utf8), contentType: .text, passphrase: "ключ-2"
        )

        let result = try await engine.decrypt(
            payload: Data([0x0A]) + encrypted + Data([0x0A]), passphrase: "ключ-2"
        )

        XCTAssertEqual(String(data: result.data, encoding: .utf8), original)
    }

    func testExactlyFourTrailingBytesAreStillAccepted() async throws {
        let original = "at the boundary"
        let encrypted = try await engine.encrypt(
            data: Data(original.utf8), contentType: .text, passphrase: "ключ-3"
        )

        let result = try await engine.decrypt(
            payload: encrypted + Data(repeating: 0x0A, count: 4), passphrase: "ключ-3"
        )

        XCTAssertEqual(String(data: result.data, encoding: .utf8), original)
    }

    func testMoreTrailingNoiseThanAllowedIsRejected() async throws {
        let encrypted = try await engine.encrypt(
            data: Data("secret".utf8), contentType: .text, passphrase: "ключ-4"
        )

        do {
            _ = try await engine.decrypt(
                payload: encrypted + Data(repeating: 0x0A, count: 5), passphrase: "ключ-4"
            )
            XCTFail("five trailing bytes should not be tolerated")
        } catch {}
    }

    func testDamagedNonWhitespaceTailIsNotSilentlyAccepted() async throws {
        let encrypted = try await engine.encrypt(
            data: Data("secret".utf8), contentType: .text, passphrase: "ключ-5"
        )

        do {
            _ = try await engine.decrypt(payload: encrypted + Data([0x41]), passphrase: "ключ-5")
            XCTFail("a damaged tail should not decrypt")
        } catch {}
    }

    func testWrongPassphraseIsStillReportedAsWrongAndNotRetriedAway() async throws {
        let encrypted = try await engine.encrypt(
            data: Data("secret".utf8), contentType: .text, passphrase: "правильный"
        )

        do {
            _ = try await engine.decrypt(payload: encrypted, passphrase: "неправильный")
            XCTFail("the wrong passphrase must not decrypt")
        } catch let error as CryptoError {
            guard case .badPassphrase = error else {
                return XCTFail("expected badPassphrase, got \(error)")
            }
        }
    }

    // MARK: - Base64 that travelled as text

    func testLineWrappedBase64NormalisesToTheUnwrappedForm() {
        let plain = "AQFhYmNkZWZnaGlqa2xtbm9wcXJzdHV2"
        let wrapped = "AQFhYmNkZWZn\r\naGlqa2xtbm9w\r\ncXJzdHV2"

        XCTAssertEqual(PayloadCodec.normalizedBase64(wrapped), plain)
    }

    func testUrlSafeAlphabetIsAccepted() {
        // "----Pz8_" is the URL-safe spelling of "++++Pz8/"
        XCTAssertEqual(PayloadCodec.normalizedBase64("----Pz8_"), "++++Pz8/")
    }

    func testMissingPaddingIsRestored() {
        XCTAssertEqual(PayloadCodec.normalizedBase64("YWJjZA"), "YWJjZA==")
        XCTAssertEqual(PayloadCodec.normalizedBase64("YWJj"), "YWJj")
    }

    func testTextThatIsNotBase64AtAllIsRejected() {
        XCTAssertNil(PayloadCodec.normalizedBase64("Привет, это не шифр"))
        XCTAssertNil(PayloadCodec.normalizedBase64("   "))
        // A length that leaves one character over is impossible in base64.
        XCTAssertNil(PayloadCodec.normalizedBase64("YWJjZ"))
    }

    // MARK: - A file the transport turned into text

    func testByteOrderMarkInFrontIsSkipped() throws {
        let payload = samplePayload()
        try assertDecodesIdentically(Data([0xEF, 0xBB, 0xBF]) + payload, to: payload)
    }

    func testByteOrderMarkPlusNewlineIsSkipped() throws {
        let payload = samplePayload()
        try assertDecodesIdentically(Data([0xEF, 0xBB, 0xBF, 0x0D, 0x0A]) + payload, to: payload)
    }

    func testThreeBytesThatOnlyLookLikeAMarkDoNotEatThePayload() {
        let payload = samplePayload()
        XCTAssertThrowsError(try PayloadCodec.decode(Data([0xEF, 0xBB, 0x41]) + payload))
    }

    func testAFileHoldingTheBase64TextOfAPayloadIsAccepted() throws {
        let payload = samplePayload()
        let asText = Data(payload.base64EncodedString().utf8)

        let decoded = try XCTUnwrap(PayloadCodec.decodeFromBase64Text(asText))

        XCTAssertEqual(decoded.contentType, .image)
        XCTAssertEqual(decoded.salt, try PayloadCodec.decode(payload).salt)
    }

    /// Built from scalars so this file's own line endings cannot change it.
    private var crLf: String { String(UnicodeScalar(13)) + String(UnicodeScalar(10)) }

    func testWrappedBase64TextInAFileIsAlsoAccepted() throws {
        let payload = samplePayload()
        var wrapped = ""
        for (index, character) in payload.base64EncodedString().enumerated() {
            if index > 0 && index % 76 == 0 { wrapped += crLf }
            wrapped.append(character)
        }

        XCTAssertNotNil(PayloadCodec.decodeFromBase64Text(Data(wrapped.utf8)))
    }

    func testADamagedFileIsStillRefused() {
        XCTAssertNil(PayloadCodec.decodeFromBase64Text(Data(repeating: 0x41, count: 200)))
        XCTAssertNil(PayloadCodec.decodeFromBase64Text(Data(repeating: 0, count: 10)))
        XCTAssertNil(PayloadCodec.decodeFromBase64Text(samplePayload()))
    }

    // MARK: - Where a decrypted document is written

    func testAPlainNameLandsDirectlyInTheTemporaryDirectory() {
        let tempDir = FileManager.default.temporaryDirectory
        let url = tempDir.appendingPathComponent("report.pdf")

        XCTAssertTrue(DecryptView.isInside(directory: tempDir, url: url))
    }

    func testANameWithACyrillicWordAndSpacesIsStillInside() {
        let tempDir = FileManager.default.temporaryDirectory
        let name = DecryptView.sanitizedFileName("Отчёт за сентябрь.pdf")

        XCTAssertTrue(DecryptView.isInside(directory: tempDir, url: tempDir.appendingPathComponent(name)))
    }

    func testAnEscapingNameIsRefused() {
        let tempDir = FileManager.default.temporaryDirectory
        let escaping = tempDir.appendingPathComponent("..").appendingPathComponent("chats.json")

        XCTAssertFalse(DecryptView.isInside(directory: tempDir, url: escaping))
    }

    func testTheSanitiserAlreadyRemovesEverySeparator() {
        XCTAssertEqual(DecryptView.sanitizedFileName("../Documents/chats.json"), "chats.json")
        let backslashed = ".." + String(UnicodeScalar(92)) + "Documents" + String(UnicodeScalar(92)) + "chats.json"
        XCTAssertEqual(DecryptView.sanitizedFileName(backslashed), "chats.json")
        XCTAssertEqual(DecryptView.sanitizedFileName(""), "document")
        XCTAssertEqual(DecryptView.sanitizedFileName("..."), "document")
    }

    // MARK: - Guessing an extension when the name arrived without one

    func testPdfIsRecognisedByItsHeader() {
        let pdf = Data("%PDF-1.4".utf8) + Data([0x0A]) + Data(repeating: 0x20, count: 40)
        XCTAssertEqual(DecryptView.guessedExtension(for: pdf), "pdf")
    }

    /// A real sample from the field: a PDF sent Android to iPhone through Dark
    /// Message, arriving with a CR LF glued to the front and no extension. The
    /// bytes are a genuine, complete PDF - proper header, proper %%EOF - so nothing
    /// in the crypto or framing damaged it. Only the offset-0 magic-byte check
    /// missed it, which reproduced "the extension is gone" on a file that was
    /// never actually broken. ISO 32000-1 §7.5.2 itself allows this: conforming
    /// readers scan the first 1024 bytes for "%PDF" rather than requiring byte 0.
    func testAPdfWithStrayBytesGluedToTheFrontIsStillRecognised() {
        let withCRLF = Data([0x0D, 0x0A]) + Data("%PDF-1.3".utf8) + Data(repeating: 0x20, count: 40)
        XCTAssertEqual(DecryptView.guessedExtension(for: withCRLF), "pdf")

        // The allowance has a limit: junk far past where any real tool would place
        // the header must not be treated as a PDF that merely arrived stretched.
        let tooFar = Data(repeating: 0x00, count: 1024) + Data("%PDF-1.3".utf8)
        XCTAssertNil(DecryptView.guessedExtension(for: tooFar))
    }

    func testOfficeFilesAreToldApartInsideTheZip() {
        func office(_ marker: String) -> Data {
            var d = Data([0x50, 0x4B, 0x03, 0x04])
            d.append(Data(repeating: 0x00, count: 26))
            d.append(Data(marker.utf8))
            d.append(Data(repeating: 0x00, count: 40))
            return d
        }
        XCTAssertEqual(DecryptView.guessedExtension(for: office("word/document.xml")), "docx")
        XCTAssertEqual(DecryptView.guessedExtension(for: office("xl/workbook.xml")), "xlsx")
        XCTAssertEqual(DecryptView.guessedExtension(for: office("ppt/presentation.xml")), "pptx")
        XCTAssertEqual(DecryptView.guessedExtension(for: office("plain.txt")), "zip")
    }

    func testImagesAreRecognised() {
        XCTAssertEqual(DecryptView.guessedExtension(for: Data([0xFF, 0xD8, 0xFF, 0xE0] + [UInt8](repeating: 0, count: 20))), "jpg")
        XCTAssertEqual(DecryptView.guessedExtension(for: Data([0x89, 0x50, 0x4E, 0x47] + [UInt8](repeating: 0, count: 20))), "png")
    }

    func testUnknownContentGetsNoExtension() {
        XCTAssertNil(DecryptView.guessedExtension(for: Data(repeating: 0x41, count: 100)))
        XCTAssertNil(DecryptView.guessedExtension(for: Data()))
    }

    func testANameThatAlreadyHasAnExtensionIsLeftAlone() {
        XCTAssertTrue(DecryptView.hasUsableExtension("report.pdf"))
        XCTAssertTrue(DecryptView.hasUsableExtension("таблица.xlsx"))
        XCTAssertFalse(DecryptView.hasUsableExtension("document"))
        XCTAssertFalse(DecryptView.hasUsableExtension("archive.superlongextension"))
    }

    // MARK: - A document built exactly the way Android builds one

    /// [nameLen: 2 bytes big-endian][name UTF-8][file bytes], which is what
    /// EncryptViewModel.kt writes before encrypting.
    private func androidDocumentPlaintext(name: String, body: Data) -> Data {
        let nameBytes = Data(name.utf8)
        var plaintext = Data()
        plaintext.append(UInt8(nameBytes.count >> 8))
        plaintext.append(UInt8(nameBytes.count & 0xFF))
        plaintext.append(nameBytes)
        plaintext.append(body)
        return plaintext
    }

    func testADocumentFromAndroidComesBackWholeWithItsName() async throws {
        let name = "01 Отчёт за сентябрь.pdf"
        let body = Data("%PDF-1.4".utf8) + Data(repeating: 0x41, count: 500)

        let encrypted = try await engine.encrypt(
            data: androidDocumentPlaintext(name: name, body: body),
            contentType: .document,
            passphrase: "ключ-док"
        )
        let result = try await engine.decrypt(payload: encrypted, passphrase: "ключ-док")

        XCTAssertEqual(result.contentType, .document)
        XCTAssertEqual(result.documentName, name)
        XCTAssertEqual(result.documentData, body)
    }

    func testTheNameSurvivesSanitisingAndKeepsItsExtension() {
        let safe = DecryptView.sanitizedFileName("01 Отчёт за сентябрь.pdf")

        XCTAssertEqual(safe, "01 Отчёт за сентябрь.pdf")
        XCTAssertTrue(DecryptView.hasUsableExtension(safe))
        XCTAssertTrue(DecryptView.isInside(
            directory: FileManager.default.temporaryDirectory,
            url: FileManager.default.temporaryDirectory.appendingPathComponent(safe)
        ))
    }

    func testAWordFileFromAndroidKeepsItsName() async throws {
        let name = "09 документ.docx"
        var body = Data([0x50, 0x4B, 0x03, 0x04])
        body.append(Data(repeating: 0x00, count: 26))
        body.append(Data("word/document.xml".utf8))
        body.append(Data(repeating: 0x00, count: 200))

        let encrypted = try await engine.encrypt(
            data: androidDocumentPlaintext(name: name, body: body),
            contentType: .document,
            passphrase: "ключ-док-2"
        )
        let result = try await engine.decrypt(payload: encrypted, passphrase: "ключ-док-2")

        XCTAssertEqual(result.documentName, name)
        XCTAssertEqual(result.documentData, body)
        XCTAssertEqual(DecryptView.guessedExtension(for: body), "docx")
    }

    func testADocumentWhoseNameLostItsExtensionStillGetsOne() async throws {
        let body = Data("%PDF-1.4".utf8) + Data(repeating: 0x41, count: 500)

        let encrypted = try await engine.encrypt(
            data: androidDocumentPlaintext(name: "otchet", body: body),
            contentType: .document,
            passphrase: "ключ-док-3"
        )
        let result = try await engine.decrypt(payload: encrypted, passphrase: "ключ-док-3")
        let safe = DecryptView.sanitizedFileName(result.documentName ?? "")

        XCTAssertEqual(safe, "otchet")
        XCTAssertFalse(DecryptView.hasUsableExtension(safe))
        XCTAssertEqual(DecryptView.guessedExtension(for: result.documentData ?? Data()), "pdf")
    }

    // MARK: - Can the system preview actually show what we wrote

    /// The smallest valid PDF there is, so the answer is about the file rather than
    /// about a made-up header.
    private var tinyPDF: Data {
        let text = """
        %PDF-1.1
        1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
        2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
        3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 99 99]>>endobj
        trailer<</Root 1 0 R>>
        """
        return Data(text.utf8)
    }

    private func write(_ data: Data, named name: String) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: url)
        try data.write(to: url, options: .atomic)
        return url
    }

    func testAPdfWithACyrillicNameCanBePreviewed() throws {
        let url = try write(tinyPDF, named: "01 Отчёт за сентябрь.pdf")

        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
        XCTAssertTrue(QLPreviewController.canPreview(url as NSURL),
                      "the preview refuses a PDF whose name carries Cyrillic and spaces")
    }

    func testAPdfWithoutAnExtensionCannotBePreviewed() throws {
        // This is what a document called "document" looks like to the system: an
        // intact file it has no idea how to render, which is the grey placeholder.
        let url = try write(tinyPDF, named: "document")

        XCTAssertFalse(QLPreviewController.canPreview(url as NSURL))
    }

    func testTheGuessedExtensionMakesItPreviewable() throws {
        let guessed = try XCTUnwrap(DecryptView.guessedExtension(for: tinyPDF))
        let url = try write(tinyPDF, named: "document." + guessed)

        XCTAssertEqual(guessed, "pdf")
        XCTAssertTrue(QLPreviewController.canPreview(url as NSURL))
    }

    func testAWordFileWithItsRealNameCanBePreviewed() throws {
        var body = Data([0x50, 0x4B, 0x03, 0x04])
        body.append(Data(repeating: 0x00, count: 26))
        body.append(Data("word/document.xml".utf8))
        body.append(Data(repeating: 0x00, count: 200))
        let url = try write(body, named: "09 документ.docx")

        XCTAssertTrue(QLPreviewController.canPreview(url as NSURL))
    }
}
