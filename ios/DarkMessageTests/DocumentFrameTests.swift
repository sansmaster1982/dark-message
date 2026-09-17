import XCTest
import Foundation
@testable import DarkMessage

/// What the two platforms do with a DOCUMENT frame that is not perfect.
///
/// The frame is `[nameLen:2 big-endian][name UTF-8][file bytes]`. Every case below has a
/// matching Kotlin test asserting the same answer, because the same payload must become
/// the same file on both phones. They diverged twice: this side threw a whole name away
/// for one malformed byte where Java substitutes a replacement character, and a
/// zero-length name left Android handing the user a file with the two length bytes still
/// glued to the front of it.
final class DocumentFrameTests: XCTestCase {

    private let pdf = Data("%PDF-1.4\ntrailer<</Root 1 0 R>>".utf8)

    private func frame(name: Data, body: Data, declaredLength: Int? = nil) -> Data {
        let length = declaredLength ?? name.count
        var data = Data([UInt8((length >> 8) & 0xFF), UInt8(length & 0xFF)])
        data.append(name)
        data.append(body)
        return data
    }

    private func roundTrip(_ plain: Data, passphrase: String) async throws -> DecryptionResult {
        let engine = CryptoEngine()
        let payload = try await engine.encrypt(data: plain, contentType: .document, passphrase: passphrase)
        return try await engine.decrypt(payload: payload, passphrase: passphrase)
    }

    func testAnOrdinaryFrameGivesTheNameAndTheFile() async throws {
        let name = "Отчёт за сентябрь.pdf"
        let result = try await roundTrip(frame(name: Data(name.utf8), body: pdf), passphrase: "ключ-1")

        XCTAssertEqual(result.documentName, name)
        XCTAssertEqual(result.documentData, pdf)
    }

    func testOneDamagedByteInTheNameDoesNotThrowTheWholeNameAway() async throws {
        // 0xFF can never appear in UTF-8. Java substitutes U+FFFD and keeps going;
        // String(data:encoding:) returns nil, which used to turn the name into "document".
        var damaged = Data("отчет".utf8)
        damaged.append(0xFF)
        damaged.append(Data(".pdf".utf8))

        let result = try await roundTrip(frame(name: damaged, body: pdf), passphrase: "ключ-2")
        let name = try XCTUnwrap(result.documentName, "the name vanished instead of arriving damaged")

        XCTAssertTrue(name.hasPrefix("отчет"), "got \(name)")
        XCTAssertTrue(name.hasSuffix(".pdf"), "the extension was lost with it: \(name)")
        XCTAssertEqual(result.documentData, pdf)
    }

    func testAZeroLengthNameStillTakesTheTwoLengthBytesOffTheFile() async throws {
        let result = try await roundTrip(frame(name: Data(), body: pdf), passphrase: "ключ-3")

        XCTAssertNil(result.documentName)
        XCTAssertEqual(result.documentData, pdf, "the length bytes are still glued to the file")
        // What the screen would then show, and what Android shows for the same payload.
        XCTAssertEqual(DecryptView.documentFileName(result.documentName, data: pdf), "document.pdf")
    }

    func testALengthThatCannotFitMeansTheBytesAreNotAFrameAtAll() async throws {
        let plain = frame(name: Data("короткое".utf8), body: pdf, declaredLength: 5000)
        let result = try await roundTrip(plain, passphrase: "ключ-4")

        XCTAssertNil(result.documentName)
        XCTAssertEqual(result.documentData, plain, "the whole plaintext is the file")
    }

    func testANameThatFillsTheWholePayloadLeavesAnEmptyFile() async throws {
        let name = "только имя.pdf"
        let result = try await roundTrip(frame(name: Data(name.utf8), body: Data()), passphrase: "ключ-5")

        XCTAssertEqual(result.documentName, name)
        XCTAssertEqual(result.documentData?.count, 0)
    }

    func testALongNameIsStillAName() async throws {
        let name = String(repeating: "и", count: 30_000)   // 60 000 bytes, two per letter
        let result = try await roundTrip(frame(name: Data(name.utf8), body: pdf), passphrase: "ключ-6")

        XCTAssertEqual(result.documentName?.utf8.count, 60_000)
        XCTAssertEqual(result.documentData, pdf)
    }
}
