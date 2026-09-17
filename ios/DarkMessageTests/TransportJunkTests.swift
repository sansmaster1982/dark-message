import XCTest
import Foundation
import QuickLook
@testable import DarkMessage

/// Damage that lands INSIDE the file, not just on the payload around it.
///
/// Established from a real delivery. A `.darkm` the owner sent for inspection begins
/// `0D 0A 01 03 ...` - a CR LF glued in front of its version byte by a transport that
/// treated the attachment as text. The payload parser already skips that. What was
/// missed is that the SAME transport damages the document inside the same delivery the
/// same way: the decrypted PDF he sent earlier begins `0D 0A 25 50 44 46`.
///
/// For a PDF that is survivable - the specification lets a reader scan for the header.
/// For a `.docx` it is fatal: a ZIP whose first bytes are not "PK" is not a ZIP, and
/// Word refuses it. He works in Office all day, which is why "not a single file works"
/// stayed true after a fix that only ever looked at PDFs.
///
/// Mirrored by `DocumentFileNameTest` on Android, case for case.
final class TransportJunkTests: XCTestCase {

    private var pdf: Data {
        Data("%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>".utf8)
    }

    private func officeZip(entry: String) -> Data {
        var data = Data([0x50, 0x4B, 0x03, 0x04])
        data.append(Data(repeating: 0x00, count: 26))
        data.append(Data(entry.utf8))
        data.append(Data(repeating: 0x00, count: 256))
        return data
    }

    private func oleDocument(streamName: String) -> Data {
        var data = Data([0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1])
        data.append(Data(repeating: 0x00, count: 504))
        for scalar in streamName.unicodeScalars {
            data.append(UInt8(scalar.value & 0xFF))
            data.append(UInt8(scalar.value >> 8))
        }
        data.append(Data(repeating: 0x00, count: 64))
        return data
    }

    private var jpeg: Data {
        Data([0xFF, 0xD8, 0xFF, 0xE0]) + Data(repeating: 0x00, count: 64)
    }

    // MARK: - The real case

    func testATransportThatGluesCRLFToTheFrontOfTheFileIsUndone() throws {
        let docx = officeZip(entry: "word/document.xml")

        let junks: [Data] = [
            Data([0x0D, 0x0A]),                  // CR LF - the observed one
            Data([0x0A]),                        // LF alone
            Data([0xEF, 0xBB, 0xBF]),            // UTF-8 byte order mark
            Data([0xEF, 0xBB, 0xBF, 0x0D, 0x0A]) // both
        ]

        for junk in junks {
            let damaged = junk + docx
            XCTAssertEqual(DecryptView.transportJunkLength(in: damaged), junk.count)
            XCTAssertEqual(DecryptView.guessedExtension(for: damaged), "docx")
            XCTAssertEqual(DecryptView.repairedDocument(damaged), docx,
                           "the file itself must come back whole, not merely relabelled")
            XCTAssertEqual(
                DecryptView.documentFileName("document", data: DecryptView.repairedDocument(damaged)),
                "document.docx")
        }
    }

    /// The point of the whole fix: after repair, iOS will actually open it.
    func testTheRepairedOfficeFileIsPreviewable() throws {
        let docx = officeZip(entry: "word/document.xml")
        let damaged = Data([0x0D, 0x0A]) + docx

        let repaired = DecryptView.repairedDocument(damaged)
        let name = DecryptView.documentFileName("document", data: repaired)
        XCTAssertEqual(name, "document.docx")

        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: url)
        try repaired.write(to: url, options: .atomic)
        XCTAssertTrue(QLPreviewController.canPreview(url as NSURL))

        // And the damaged one, written under the same name, is what he has been seeing.
        let brokenURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("still-damaged.docx")
        try? FileManager.default.removeItem(at: brokenURL)
        try damaged.write(to: brokenURL, options: .atomic)
        XCTAssertNotEqual(try Data(contentsOf: brokenURL).prefix(4), Data([0x50, 0x4B, 0x03, 0x04]),
                          "the whole problem: those two bytes stop it being a ZIP at all")
    }

    func testTheSameRepairWorksForEveryFormatNotJustPdf() {
        let cases: [(String, Data)] = [
            ("pdf", pdf),
            ("docx", officeZip(entry: "word/document.xml")),
            ("xlsx", officeZip(entry: "xl/workbook.xml")),
            ("jpg", jpeg),
            ("xls", oleDocument(streamName: "Workbook"))
        ]
        for (expected, body) in cases {
            let damaged = Data([0x0D, 0x0A]) + body
            XCTAssertEqual(DecryptView.guessedExtension(for: damaged), expected)
            XCTAssertEqual(DecryptView.repairedDocument(damaged), body)
        }
    }

    // MARK: - What must NEVER be touched

    func testBytesTheFileGenuinelyOwnsAreNeverStripped() {
        // Nothing is removed unless doing so reveals a format we know. Silently eating a
        // user's first bytes would be far worse than a missing extension.
        let text = Data([0x0D, 0x0A]) + Data("Обычный текст, а не повреждение".utf8)
        XCTAssertEqual(DecryptView.transportJunkLength(in: text), 0)
        XCTAssertEqual(DecryptView.repairedDocument(text), text)

        XCTAssertEqual(DecryptView.transportJunkLength(in: pdf), 0)
        XCTAssertEqual(DecryptView.repairedDocument(pdf), pdf)
    }

    func testJunkLongerThanTheBoundIsNotTreatedAsDamage() {
        let damaged = Data(repeating: 0x20, count: 64) + pdf   // beyond maxTransportJunk
        XCTAssertEqual(DecryptView.transportJunkLength(in: damaged), 0)
        XCTAssertNil(DecryptView.guessedExtension(for: damaged))
    }

    // MARK: - End to end, the way it actually arrives

    /// A whole delivery with the damage in both places at once: the payload wrapped in a
    /// CR LF, and the document inside wrapped in one too. This is the owner's case.
    func testAPayloadAndItsDocumentBothDamagedStillArriveWhole() async throws {
        let docx = officeZip(entry: "word/document.xml")
        var plain = Data([0x00, 0x08])                 // name length: "document"
        plain.append(Data("document".utf8))
        plain.append(Data([0x0D, 0x0A]))               // the damage inside the file
        plain.append(docx)

        let engine = CryptoEngine()
        let payload = try await engine.encrypt(data: plain, contentType: .document,
                                               passphrase: "both-ends")
        let damagedPayload = Data([0x0D, 0x0A]) + payload   // and the damage outside it

        let result = try await engine.decrypt(payload: damagedPayload, passphrase: "both-ends")
        XCTAssertEqual(result.documentName, "document")

        let body = DecryptView.repairedDocument(try XCTUnwrap(result.documentData))
        XCTAssertEqual(body, docx, "the document must come back byte for byte")
        XCTAssertEqual(DecryptView.documentFileName(result.documentName, data: body), "document.docx")
    }
}
