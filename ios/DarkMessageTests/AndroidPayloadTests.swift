import XCTest
import Foundation
@testable import DarkMessage

/// Decrypts a payload that the ANDROID app really produced.
///
/// Every other test on this side builds its "Android" payload with this app's own
/// encrypt, which can only ever prove that iOS agrees with itself. These bytes came
/// off a device: the Android app (1.3.0, versionCode 7) encrypted the file
/// "1 Ф9И7 (1).pdf" for a chat whose passphrase is below, and the payload was pulled
/// straight out of its cache directory.
///
/// Verified independently before being pasted here - an implementation written from
/// the format specification alone decrypts it to
/// `[00 10]["1 Ф9И7 (1).pdf"][%PDF-1.4...]`, so the name really is in there, whole.
/// If this test fails, iOS is losing a name that Android sent correctly, which is
/// exactly what the owner sees: a document that decrypts but is called "document".
final class AndroidPayloadTests: XCTestCase {

    private let payloadBase64 = """
        AQP/qlsPvPYay3S6RB9SdocqoCsoAosziVyvu6VmshNeW4Kf3ubBgkv68UqV9SExmHfbCL+4zgtL9OZFmRDF\
        EHwYFQr+5rCSPI3E20XG83X8O2yI0QVK/vFZVaOxHETahYKXxbpRRW8W5cZTAOMjKNzXzgs6ipwFogdF3bmf\
        fCdG2pqXFQt32qjDMN3kxaZb7V6Edz19c1llgFKW1u8IwaYsFHAZ1LogXDPwv7dsuEqRqLCdz6MxdPoX5+ow\
        6+d1mCHXv101ISHAaUXoLAf6cBWYB27aK4k3fnIh9CATErO1ngjVwi5UB0gwdbUka2Dxf2zjxkBFfS7MAaVp\
        ymkkDuE=
        """

    private let passphrase = "name-test-key-2026"
    private let expectedName = "1 Ф9И7 (1).pdf"

    private var payload: Data {
        get throws {
            let cleaned = payloadBase64.replacingOccurrences(of: "\n", with: "")
            return try XCTUnwrap(Data(base64Encoded: cleaned), "the sample is not valid base64")
        }
    }

    func testTheHeaderIsADocumentPayload() throws {
        let data = try payload
        XCTAssertEqual(data.count, 257)
        XCTAssertEqual(data[0], 1, "version byte")
        XCTAssertEqual(data[1], ContentType.document.rawValue, "content type byte")
    }

    func testTheNameArrivesWhole() async throws {
        let result = try await CryptoEngine().decrypt(payload: try payload, passphrase: passphrase)

        XCTAssertEqual(result.contentType, .document)
        XCTAssertEqual(result.documentName, expectedName,
                       "iOS lost the name Android sent; the raw plaintext starts 00 10 then the name")
    }

    func testTheFileArrivesWhole() async throws {
        let result = try await CryptoEngine().decrypt(payload: try payload, passphrase: passphrase)
        let body = try XCTUnwrap(result.documentData)

        XCTAssertEqual(body.count, 193, "the body is the PDF alone, without the name in front of it")
        XCTAssertEqual(body.prefix(8), Data("%PDF-1.4".utf8),
                       "the body starts at the wrong offset: the name is still attached to it")
    }

    /// The whole chain, ending at the name the screen would show.
    func testTheScreenWouldShowTheRealName() async throws {
        let result = try await CryptoEngine().decrypt(payload: try payload, passphrase: passphrase)
        let body = try XCTUnwrap(result.documentData)

        XCTAssertEqual(DecryptView.documentFileName(result.documentName, data: body), expectedName)
        XCTAssertNotEqual(DecryptView.documentFileName(result.documentName, data: body), "document",
                          "the fallback name means the real one was thrown away somewhere above")
    }

    /// The same bytes with a CR LF glued on each end, the way mail and messengers
    /// have been seen to deliver an attachment.
    func testTheNameSurvivesTransportNoiseToo() async throws {
        let damaged = Data([0x0D, 0x0A]) + (try payload) + Data([0x0D, 0x0A])
        let result = try await CryptoEngine().decrypt(payload: damaged, passphrase: passphrase)

        XCTAssertEqual(result.documentName, expectedName)
        XCTAssertEqual(try XCTUnwrap(result.documentData).count, 193)
    }
}
