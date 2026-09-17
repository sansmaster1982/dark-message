import XCTest
import Foundation
import QuickLook
@testable import DarkMessage

/// The name a received document ends up with decides whether iOS will open it at all.
///
/// These cases come from the owner's own phone: real file names carrying dates, version
/// numbers and brackets after a dot. "The name has a dot in it" used to count as an
/// extension, so a file called "КП 14.09.2026" was written as-is and QuickLook showed a
/// grey placeholder - which is exactly what "the extension gets lost" looks like.
///
/// Mirrored by `DocumentFileNameTest` on Android over the same names: the two apps have
/// to name the same file the same way.
final class DocumentNameTests: XCTestCase {

    // MARK: - Sample content with real headers

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

    /// A legacy Office container: the header plus the stream name that identifies the
    /// flavour, stored UTF-16 the way the real format stores it.
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

    private var plainText: Data {
        Data("Обычный текст без заголовка".utf8)
    }

    // MARK: - Names that already carry a real extension

    func testRealNamesFromTheOwnersPhoneKeepExactlyTheNameTheyCameWith() throws {
        let names = [
            "1 Ф9И7 (1).pdf",
            "+КП_ Пушкинская 67_  КОНД 14.09.2026.pdf",
            "Хронология Ведучи КП 2025-2026 (1).docx",
            "ЗП офис СМР 2026г..xlsx",
            "Лист Microsoft Excel(1).xlsx",
            "Таганрог (ридан, труба, изоляция, ППР).xlsx",
            "2_5233187358324593625(1).xls",
            "Страницы_из_Раздел_ПД_5_Подраздел_4_Часть_1_ИОС4_1_3.pdf"
        ]

        for name in names {
            XCTAssertEqual(DecryptView.sanitizedFileName(name), name,
                           "the sanitiser changed a perfectly good name")
            XCTAssertTrue(DecryptView.hasUsableExtension(name),
                          "\(name) should count as having an extension")
            XCTAssertEqual(DecryptView.documentFileName(name, data: pdf), name)
        }
    }

    /// The names above have to survive being written and handed to the system, not just
    /// the string functions: brackets, a plus, a number sign and Cyrillic all at once.
    func testThoseNamesAreAlsoPreviewableOnceWritten() throws {
        let cases: [(String, Data)] = [
            ("1 Ф9И7 (1).pdf", pdf),
            ("+КП_ Пушкинская 67_  КОНД 14.09.2026.pdf", pdf),
            ("Хронология Ведучи КП 2025-2026 (1).docx", officeZip(entry: "word/document.xml")),
            ("ЗП офис СМР 2026г..xlsx", officeZip(entry: "xl/workbook.xml")),
            ("2_5233187358324593625(1).xls", oleDocument(streamName: "Workbook"))
        ]

        var refused: [String] = []
        for (name, data) in cases {
            let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
            try? FileManager.default.removeItem(at: url)
            try data.write(to: url, options: .atomic)
            XCTAssertTrue(DecryptView.isInside(directory: FileManager.default.temporaryDirectory, url: url),
                          "\(name) was refused by the containment check")
            if !QLPreviewController.canPreview(url as NSURL) {
                refused.append(name)
            }
        }
        XCTAssertEqual(refused, [], "iOS refuses to preview these, so the app shows a placeholder")
    }

    // MARK: - The bug: a date after a dot is not an extension

    func testADateAtTheEndOfTheNameIsNotMistakenForAnExtension() {
        XCTAssertFalse(DecryptView.hasUsableExtension("КП 14.09.2026"))
        XCTAssertFalse(DecryptView.hasUsableExtension("отчёт за 2026г."))
        XCTAssertFalse(DecryptView.hasUsableExtension("Смета v1.2"))
        XCTAssertFalse(DecryptView.hasUsableExtension("документ"))
    }

    func testANameWhoseExtensionWasLostGetsOneBackFromTheContent() {
        XCTAssertEqual(DecryptView.documentFileName("КП 14.09.2026", data: pdf), "КП 14.09.2026.pdf")
        XCTAssertEqual(
            DecryptView.documentFileName("Хронология 2025-2026", data: officeZip(entry: "word/document.xml")),
            "Хронология 2025-2026.docx"
        )
        XCTAssertEqual(
            DecryptView.documentFileName("смета", data: officeZip(entry: "xl/workbook.xml")),
            "смета.xlsx"
        )
        XCTAssertEqual(DecryptView.documentFileName("снимок", data: jpeg), "снимок.jpg")
    }

    /// And the rescued name must actually render, which is the whole point.
    func testTheRescuedNameIsPreviewable() throws {
        let name = DecryptView.documentFileName("КП 14.09.2026", data: pdf)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: url)
        try pdf.write(to: url, options: .atomic)
        XCTAssertTrue(QLPreviewController.canPreview(url as NSURL),
                      "the rescued name still does not preview: \(name)")
    }

    func testLegacyOfficeDocumentsAreRecognisedToo() {
        XCTAssertEqual(DecryptView.guessedExtension(for: oleDocument(streamName: "WordDocument")), "doc")
        XCTAssertEqual(DecryptView.guessedExtension(for: oleDocument(streamName: "Workbook")), "xls")
        XCTAssertEqual(DecryptView.guessedExtension(for: oleDocument(streamName: "PowerPoint Document")), "ppt")
        XCTAssertEqual(
            DecryptView.documentFileName("отчёт 2026г", data: oleDocument(streamName: "Workbook")),
            "отчёт 2026г.xls"
        )
    }

    func testContentThatCannotBeIdentifiedLeavesTheNameAlone() {
        XCTAssertNil(DecryptView.guessedExtension(for: plainText))
        XCTAssertNil(DecryptView.guessedExtension(for: Data()))
        XCTAssertNil(DecryptView.guessedExtension(for: Data([0x50, 0x4B])))
        XCTAssertEqual(DecryptView.documentFileName("записка", data: plainText), "записка")
    }

    func testAnExtensionTheSenderGaveIsNeverSecondGuessed() {
        // A PDF named .txt stays .txt: the sender named it, and inventing a different
        // extension would be a lie about someone else's file.
        XCTAssertEqual(DecryptView.documentFileName("странный.txt", data: pdf), "странный.txt")
    }

    // MARK: - Names that are dangerous or too long

    func testAPathInTheNameNeverEscapesTheDirectory() {
        XCTAssertEqual(DecryptView.sanitizedFileName("../../Documents/chats.json"), "chats.json")
        XCTAssertEqual(DecryptView.sanitizedFileName("/etc/passwd"), "passwd")
        XCTAssertEqual(DecryptView.sanitizedFileName(".hidden.pdf"), "hidden.pdf")
        XCTAssertEqual(DecryptView.sanitizedFileName("   "), "document")
    }

    func testAVeryLongNameIsShortenedInTheMiddleSoTheExtensionSurvives() {
        let long = String(repeating: "очень_длинное_имя_", count: 12) + ".pdf"
        XCTAssertGreaterThan(long.count, 120, "the sample must exceed the limit")

        let safe = DecryptView.sanitizedFileName(long)
        XCTAssertEqual(safe.count, 120)
        XCTAssertTrue(safe.hasSuffix(".pdf"), "the extension was cut off: \(safe)")
        XCTAssertTrue(DecryptView.hasUsableExtension(safe))
    }

    func testALongNameWithNoExtensionIsStillCapped() {
        XCTAssertEqual(DecryptView.sanitizedFileName(String(repeating: "имя", count: 100)).count, 120)
    }
}
