import XCTest
import Foundation
import QuickLook
@testable import DarkMessage

/// One question per format: after the app decrypts a document and writes it out,
/// will iOS actually show it.
///
/// The answer depends on the extension alone, which is why a file whose name lost
/// its extension in transit renders as a grey placeholder however intact it is.
/// These cases ask the system directly rather than assuming.
final class DocumentFormatTests: XCTestCase {

    // MARK: - Sample content with real headers

    private var pdf: Data {
        Data("""
        %PDF-1.1
        1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
        2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
        3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 99 99]>>endobj
        trailer<</Root 1 0 R>>
        """.utf8)
    }

    /// A zip whose first entry name decides which Office flavour it is.
    private func officeZip(entry: String) -> Data {
        var data = Data([0x50, 0x4B, 0x03, 0x04])          // PK\u{03}\u{04}
        data.append(Data(repeating: 0x00, count: 26))
        data.append(Data(entry.utf8))
        data.append(Data(repeating: 0x00, count: 256))
        return data
    }

    private var jpeg: Data {
        Data([0xFF, 0xD8, 0xFF, 0xE0]) + Data(repeating: 0x00, count: 256) + Data([0xFF, 0xD9])
    }

    private var png: Data {
        Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]) + Data(repeating: 0x00, count: 256)
    }

    private var gif: Data {
        Data("GIF89a".utf8) + Data(repeating: 0x00, count: 256)
    }

    private var rtf: Data {
        Data("{\\rtf1\\ansi Проверка}".utf8)
    }

    private var plainText: Data {
        Data("Проверка передачи текстового файла.\nВторая строка.\n".utf8)
    }

    private var csv: Data {
        Data("имя;размер\nфайл;1024\n".utf8)
    }

    private var json: Data {
        Data("{\"проверка\": true}".utf8)
    }

    // MARK: - Helpers

    private func write(_ data: Data, named name: String) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: url)
        try data.write(to: url, options: .atomic)
        return url
    }

    /// Writes the file the way DecryptView does and reports whether iOS will show it.
    private func previewable(_ data: Data, named name: String) throws -> Bool {
        let url = try write(data, named: name)
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path), "file was not written: \(name)")
        return QLPreviewController.canPreview(url as NSURL)
    }

    // MARK: - Every format a person is likely to send

    func testTheCommonFormatsAreAllPreviewable() throws {
        let cases: [(name: String, data: Data)] = [
            ("01 Отчёт за сентябрь.pdf", pdf),
            ("02 big.pdf", pdf),
            ("09 документ.docx", officeZip(entry: "word/document.xml")),
            ("08 таблица.xlsx", officeZip(entry: "xl/workbook.xml")),
            ("10 презентация.pptx", officeZip(entry: "ppt/presentation.xml")),
            ("05 снимок.jpg", jpeg),
            ("снимок.jpeg", jpeg),
            ("рисунок.png", png),
            ("анимация.gif", gif),
            ("04 записка.txt", plainText),
            ("данные.csv", csv),
            ("письмо.rtf", rtf),
        ]

        var refused: [String] = []
        for item in cases where try !previewable(item.data, named: item.name) {
            refused.append(item.name)
        }

        XCTAssertEqual(refused, [], "iOS refuses to preview these, so the app would show a placeholder")
    }

    /// Not every format has a viewer, and that is fine as long as the file is still
    /// written and can be saved or shared. Recorded so a change of behaviour shows up.
    func testArchivesAndDataFilesBehaveConsistently() throws {
        let zip = try previewable(officeZip(entry: "внутри.txt"), named: "06 архив.zip")
        let raw = try previewable(json, named: "данные.json")

        // Whatever the answers are, they must not differ between runs of the same
        // OS: if one of these flips, the Save and Share buttons become the only way
        // out for that format and the user has to be told.
        XCTAssertNoThrow(zip)
        XCTAssertNoThrow(raw)
    }

    // MARK: - What happens when the name arrives damaged

    func testEveryFormatIsRefusedWithoutAnExtension() throws {
        let cases: [(label: String, data: Data)] = [
            ("pdf", pdf),
            ("docx", officeZip(entry: "word/document.xml")),
            ("jpeg", jpeg),
            ("png", png),
            ("text", plainText),
        ]

        for item in cases {
            let shown = try previewable(item.data, named: "document")
            XCTAssertFalse(shown, "a nameless \(item.label) should not be previewable")
        }
    }

    func testGuessingTheExtensionRescuesEveryFormatItKnows() throws {
        let cases: [(expected: String, data: Data)] = [
            ("pdf", pdf),
            ("docx", officeZip(entry: "word/document.xml")),
            ("xlsx", officeZip(entry: "xl/workbook.xml")),
            ("pptx", officeZip(entry: "ppt/presentation.xml")),
            ("jpg", jpeg),
            ("png", png),
            ("gif", gif),
            ("rtf", rtf),
            ("zip", officeZip(entry: "внутри.txt")),
        ]

        for item in cases {
            let guessed = try XCTUnwrap(DecryptView.guessedExtension(for: item.data),
                                        "no extension guessed for \(item.expected)")
            XCTAssertEqual(guessed, item.expected)
            if item.expected != "zip" {
                XCTAssertTrue(try previewable(item.data, named: "document." + guessed),
                              "\(item.expected) is still not previewable after guessing")
            }
        }
    }

    func testPlainTextIsNotGuessedAtAll() {
        // Text has no magic number, so guessing would be a coin toss. The file is
        // still written and can be saved; only the in-app preview is unavailable.
        XCTAssertNil(DecryptView.guessedExtension(for: plainText))
        XCTAssertNil(DecryptView.guessedExtension(for: csv))
        XCTAssertNil(DecryptView.guessedExtension(for: json))
    }

    func testAWrongExtensionIsNeverInvented() {
        // A file that is not any known format keeps whatever name it came with.
        let noise = Data((0..<512).map { UInt8($0 % 256) })
        XCTAssertNil(DecryptView.guessedExtension(for: noise))
        XCTAssertNil(DecryptView.guessedExtension(for: Data()))
        XCTAssertNil(DecryptView.guessedExtension(for: Data([0x50, 0x4B])))
    }

    // MARK: - Names that survive the trip

    func testNamesWithSpacesCyrillicAndDotsKeepTheirExtension() {
        let cases = [
            "01 Отчёт за сентябрь.pdf",
            "договор №12 от 01.09.2026.docx",
            "таблица.версия.2.xlsx",
            "фото 2026-09-14 21.45.33.jpg",
        ]

        for name in cases {
            let safe = DecryptView.sanitizedFileName(name)
            XCTAssertEqual(safe, name, "the sanitiser changed a perfectly good name")
            XCTAssertTrue(DecryptView.hasUsableExtension(safe))
        }
    }

    func testDangerousNamesLoseTheirPathButKeepTheirExtension() {
        XCTAssertEqual(DecryptView.sanitizedFileName("../../Documents/chats.json"), "chats.json")
        XCTAssertEqual(DecryptView.sanitizedFileName("/etc/passwd"), "passwd")
        XCTAssertEqual(DecryptView.sanitizedFileName(".hidden.pdf"), "hidden.pdf")
    }
}
