import XCTest
import Foundation
@testable import DarkMessage

/// The containment check under the paths a real iPhone actually hands out.
///
/// On the device `FileManager.default.temporaryDirectory` is
/// `/private/var/mobile/Containers/Data/Application/<id>/tmp/`. On the simulator it is
/// `/Users/<you>/Library/Developer/CoreSimulator/.../tmp/` - no `/private` anywhere.
/// That single difference let a broken `isInside` pass 105 tests while every document on
/// the phone came out as a bare "document": `standardizedFileURL` strips a leading
/// `/private` only when the resulting path EXISTS, so the directory lost it and the
/// not-yet-written file kept it, and the two no longer matched.
///
/// macOS has the same layout under `/private/tmp` (`/tmp` is a symlink to it), so the
/// device's condition can be reproduced on the simulator exactly. If these tests are
/// ever skipped, the machine has no `/private/tmp` and the check is unverified there.
final class PrivatePrefixTests: XCTestCase {

    private var privateDir: URL!
    private var plainDir: URL!

    override func setUpWithError() throws {
        try super.setUpWithError()
        let name = "darkmessage-private-\(UUID().uuidString)"
        plainDir = URL(fileURLWithPath: "/tmp/\(name)", isDirectory: true)
        privateDir = URL(fileURLWithPath: "/private/tmp/\(name)", isDirectory: true)
        try FileManager.default.createDirectory(at: plainDir, withIntermediateDirectories: true)
        guard FileManager.default.fileExists(atPath: privateDir.path) else {
            throw XCTSkip("/private/tmp is not a symlink target on this machine")
        }
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: plainDir)
        try super.tearDownWithError()
    }

    /// The exact shape on the phone: directory and file both spelled with /private,
    /// the file not written yet.
    func testANewFileUnderThePrivateSpellingIsInside() {
        let file = privateDir.appendingPathComponent("Отчёт за сентябрь.docx")
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
        XCTAssertTrue(DecryptView.isInside(directory: privateDir, url: file))
    }

    /// And once the file exists the answer must not change.
    func testAnExistingFileUnderThePrivateSpellingIsInside() throws {
        let file = privateDir.appendingPathComponent("report.pdf")
        try Data("%PDF-1.4".utf8).write(to: file)
        XCTAssertTrue(DecryptView.isInside(directory: privateDir, url: file))
    }

    /// The two spellings of the same directory are the same directory.
    func testMixedSpellingsStillAgree() {
        XCTAssertTrue(DecryptView.isInside(directory: plainDir,
                                           url: privateDir.appendingPathComponent("a.pdf")))
        XCTAssertTrue(DecryptView.isInside(directory: privateDir,
                                           url: plainDir.appendingPathComponent("a.pdf")))
    }

    /// Tolerance for the prefix must not become tolerance for escaping.
    func testEscapingIsStillRefusedUnderThePrivateSpelling() {
        let escaping = privateDir.appendingPathComponent("..").appendingPathComponent("chats.json")
        XCTAssertFalse(DecryptView.isInside(directory: privateDir, url: escaping))

        let sibling = URL(fileURLWithPath: "/private/tmp/other-\(UUID().uuidString)/x.pdf")
        XCTAssertFalse(DecryptView.isInside(directory: privateDir, url: sibling))
    }

    func testDotNamesAreRefused() {
        XCTAssertFalse(DecryptView.isInside(directory: privateDir, url: privateDir.appendingPathComponent(".")))
        XCTAssertFalse(DecryptView.isInside(directory: privateDir, url: privateDir.appendingPathComponent("..")))
        XCTAssertFalse(DecryptView.isInside(directory: privateDir, url: privateDir))
    }

    /// What the screen does when the check fails: the extension survives the fallback.
    func testTheFallbackNameKeepsTheExtension() {
        let pdf = Data("%PDF-1.4 fallback".utf8)
        XCTAssertEqual(DecryptView.documentFileName(nil, data: pdf), "document.pdf")
    }
}
