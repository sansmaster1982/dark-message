import XCTest
import Foundation
import CryptoKit
@testable import DarkMessage

/// Contract tests for the QR passphrase exchange, payload format v1.
///
/// The four vectors below ARE the cross-platform contract: the Android
/// `ChatQrCodecTest` asserts the same `encoded` strings. If one of these fails,
/// an Android phone and an iPhone can no longer exchange a chat key, so never
/// "fix" a vector - fix the implementation.
///
/// Every encode and every open derives a 600 000-iteration PBKDF2 key, which
/// costs roughly a second on the simulator (about 24 derivations in this file,
/// so expect the suite to run for half a minute).
final class QRChatCodecTests: XCTestCase {

    // MARK: - Vectors (spec section 2)

    private struct Vector {
        let label: String
        let name: String
        let passphrase: String
        let pin: String
        let saltHex: String
        let nonceHex: String
        let nameHex: String
        let passphraseHex: String
        let keyHex: String
        let ciphertextHex: String
        let tagHex: String
        let fingerprint: String
        let encoded: String

        var salt: Data { return QRChatCodecTests.data(fromHex: saltHex) }
        var nonce: Data { return QRChatCodecTests.data(fromHex: nonceHex) }
    }

    /// 62 ASCII characters: A-Z, a-z, 0-9.
    private static let asciiAlphabet =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + "abcdefghijklmnopqrstuvwxyz" + "0123456789"

    /// Vector 4 name: the 32 Cyrillic capitals U+0410...U+042F, exactly 64 UTF-8 bytes.
    private static let vector4Name: String = {
        var result = ""
        for code in UInt32(0x0410)...UInt32(0x042F) {
            if let scalar = Unicode.Scalar(code) {
                result.append(Character(scalar))
            }
        }
        return result
    }()

    /// Vector 4 passphrase: 62 + "-" + 62 + Cyrillic yo U+0451 (2 B) + "!" = exactly 128 UTF-8 bytes.
    private static let vector4Passphrase: String =
        QRChatCodecTests.asciiAlphabet + "-" + QRChatCodecTests.asciiAlphabet + "\u{451}" + "!"

    private static let alphabetHex =
        "4142434445464748494a4b4c4d4e4f505152535455565758595a" +
        "6162636465666768696a6b6c6d6e6f707172737475767778797a" +
        "30313233343536373839"

    private static let vectors: [Vector] = [
        Vector(
            label: "V1 ASCII",
            name: "Alice",
            passphrase: "correct horse battery staple",
            pin: "123456",
            saltHex: "000102030405060708090a0b0c0d0e0f",
            nonceHex: "101112131415161718191a1b",
            nameHex: "416c696365",
            passphraseHex: "636f727265637420686f727365206261747465727920737461706c65",
            keyHex: "ff2c26d5c53a0e8c375dbc125eb3b89a9aa4553a01ac06dfba5d0d8862991c8b",
            ciphertextHex: "371c59d477c9f77cff98abe8cf4cc8f0c544ec27647fefffba850ad1f0897ef967e4",
            tagHex: "be9872a043cf53c2b3c8a63526ebe595",
            fingerprint: "C4BB CB1F",
            encoded: "darkmessage://chat/1/AQABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhs3HFnUd8n3fP-Yq-jPTMjwxUTsJ2R_7_-6hQrR8Il--WfkvphyoEPPU8KzyKY1JuvllQ"
        ),
        Vector(
            label: "V2 Russian name and passphrase, PIN with leading zeros",
            name: "Бабушка",
            passphrase: "тайный ключ 2026",
            pin: "007042",
            saltHex: "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf",
            nonceHex: "b0b1b2b3b4b5b6b7b8b9babb",
            nameHex: "d091d0b0d0b1d183d188d0bad0b0",
            passphraseHex: "d182d0b0d0b9d0bdd18bd0b920d0bad0bbd18ed1872032303236",
            keyHex: "d1d3bda3055e92d63365ac33ac13cf894ecf82cfad2b362b1e6b6c5ec7df3ca0",
            ciphertextHex: "b84f9a2fae890dd9a4162c4ef597e55bae1d6233a33904a273413c7351b3db4798d9b06db55474892e",
            tagHex: "139148e449e778606dfba0c1f49402dc",
            fingerprint: "C74C 859F",
            encoded: "darkmessage://chat/1/AaChoqOkpaanqKmqq6ytrq-wsbKztLW2t7i5uru4T5ovrokN2aQWLE71l-Vbrh1iM6M5BKJzQTxzUbPbR5jZsG21VHSJLhORSORJ53hgbfugwfSUAtw"
        ),
        Vector(
            label: "V3 spaces and emoji",
            name: "Bob",
            passphrase: "my secret 🔑 phrase 🚀",
            pin: "999999",
            saltHex: "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff",
            nonceHex: "e0e1e2e3e4e5e6e7e8e9eaeb",
            nameHex: "426f62",
            passphraseHex: "6d792073656372657420f09f94912070687261736520f09f9a80",
            keyHex: "15089200c5cdf8d27986b705282f394cceaa9401725481e0570946bfef2984c5",
            ciphertextHex: "e49469f3d7e23bae5e6d59f0fc0337f0874015564c348c79183778761b76",
            tagHex: "bc2309ce3dfc16564dd36c994b0e7a74",
            fingerprint: "DF58 6381",
            encoded: "darkmessage://chat/1/AfDx8vP09fb3-Pn6-_z9_v_g4eLj5OXm5-jp6uvklGnz1-I7rl5tWfD8Azfwh0AVVkw0jHkYN3h2G3a8IwnOPfwWVk3TbJlLDnp0"
        ),
        Vector(
            label: "V4 maximum length",
            name: QRChatCodecTests.vector4Name,
            passphrase: QRChatCodecTests.vector4Passphrase,
            pin: "000000",
            saltHex: "ffffffffffffffffffffffffffffffff",
            nonceHex: "ffffffffffffffffffffffff",
            nameHex: "d090d091d092d093d094d095d096d097d098d099d09ad09bd09cd09dd09ed09f" +
                     "d0a0d0a1d0a2d0a3d0a4d0a5d0a6d0a7d0a8d0a9d0aad0abd0acd0add0aed0af",
            passphraseHex: QRChatCodecTests.alphabetHex + "2d" + QRChatCodecTests.alphabetHex + "d19121",
            keyHex: "86f86159b77d64df056024b7fa967580a7fc9ece27f18e484020857fb55b1d22",
            ciphertextHex: "53d594207a692dc754f791016ab976bf72eddbd889877bec7b450325b543a842" +
                           "30ffb1f17396b99262eaf88ca6a1ef48d6b8c1a36f8da12c8afca8878576356f" +
                           "165e54034268c13ecdbbd8dfd272d88ddfac18982dad6ddd7b19621604f7905e" +
                           "20f3f320fead6f98d4ccaf53e97b65290ebdbb3e2e2e37e0839292c2aeaac747" +
                           "73a1849eb6937310ba56b77e9b4cf16f17d0c2ac4ead47f5db9f5d972123091a" +
                           "e9f488bdb7bd62191f966cab9a048babc5cb737c8dee65aae0f89d45c42c00a1e7",
            tagHex: "459b54ca7da02ab9eb5ecaab01c3446b",
            fingerprint: "F12D 440C",
            encoded: "darkmessage://chat/1/Af____________________________________9T1ZQgemktx1T3kQFquXa_cu3b2ImHe-x7RQMltUOoQjD_sfFzlrmSYur4jKah70jWuMGjb42hLIr8qIeFdjVvFl5UA0JowT7Nu9jf0nLYjd-sGJgtrW3dexliFgT3kF4g8_Mg_q1vmNTMr1Ppe2UpDr27Pi4uN-CDkpLCrqrHR3OhhJ62k3MQula3fptM8W8X0MKsTq1H9dufXZchIwka6fSIvbe9YhkflmyrmgSLq8XLc3yN7mWq4PidRcQsAKHnRZtUyn2gKrnrXsqrAcNEaw"
        )
    ]

    /// Section 1.6: the version-2 and kdfId-0x02 rows, verbatim.
    private static let versionTwoText =
        "darkmessage://chat/2/AQABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhs3HFnUd8n3fP-Yq-jPTMjwxUTsJ2R_7_-6hQrR8Il--WfkvphyoEPPU8KzyKY1JuvllQ"
    private static let kdfIdTwoText =
        "darkmessage://chat/1/AgABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhs3HFnUd8n3fP-Yq-jPTMjwxUTsJ2R_7_-6hQrR8Il--WfkvphyoEPPU8KzyKY1JuvllQ"

    /// Section 1.6: V1 with body byte 40 (a ciphertext byte) XOR 1, re-encoded.
    private static let tamperedCiphertextText =
        "darkmessage://chat/1/AQABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhs3HFnUd8n3fP-Yq-nPTMjwxUTsJ2R_7_-6hQrR8Il--WfkvphyoEPPU8KzyKY1JuvllQ"

    private static let prefixLength = 21   // "darkmessage://chat/1/"

    // MARK: - Positive vectors

    func testVector1ASCII() async throws {
        try await runVector(QRChatCodecTests.vectors[0])
    }

    func testVector2Cyrillic() async throws {
        try await runVector(QRChatCodecTests.vectors[1])
    }

    func testVector3SpacesAndEmoji() async throws {
        try await runVector(QRChatCodecTests.vectors[2])
    }

    func testVector4MaximumLength() async throws {
        let vector = QRChatCodecTests.vectors[3]
        XCTAssertEqual(Data(vector.name.utf8).count, 64, "V4 name must be exactly 64 bytes")
        XCTAssertEqual(Data(vector.passphrase.utf8).count, 128, "V4 passphrase must be exactly 128 bytes")
        XCTAssertEqual(vector.encoded.count, 339, "V4 text must be 339 characters")
        try await runVector(vector)
    }

    /// The plaintext framing (1-byte name length) byte for byte, no crypto.
    func testPlaintextFramingMatchesVectors() {
        XCTAssertEqual(
            plaintextHex(of: QRChatCodecTests.vectors[0]),
            "05416c696365636f727265637420686f727365206261747465727920737461706c65"
        )
        XCTAssertEqual(
            plaintextHex(of: QRChatCodecTests.vectors[1]),
            "0ed091d0b0d0b1d183d188d0bad0b0d182d0b0d0b9d0bdd18bd0b920d0bad0bbd18ed1872032303236"
        )
        XCTAssertEqual(
            plaintextHex(of: QRChatCodecTests.vectors[2]),
            "03426f626d792073656372657420f09f94912070687261736520f09f9a80"
        )
        let fourth = plaintextHex(of: QRChatCodecTests.vectors[3])
        XCTAssertTrue(fourth.hasPrefix("40"), "V4 nameLen must be 0x40")
        XCTAssertEqual(fourth.count, 2 + 128 + 256, "V4 plaintext must be 193 bytes")
    }

    /// The shared PBKDF2 parameters: the single most likely cause of a silent
    /// cross-platform failure, so assert the derived keys directly.
    func testDerivedKeysMatchVectors() async throws {
        for vector in QRChatCodecTests.vectors {
            let key = try await PBKDF2KeyDeriver().deriveKey(passphrase: vector.pin, salt: vector.salt)
            XCTAssertEqual(key.count, 32, vector.label)
            XCTAssertEqual(QRChatCodecTests.hexString(key), vector.keyHex, vector.label)
        }
    }

    func testFingerprintsMatchVectors() {
        for vector in QRChatCodecTests.vectors {
            XCTAssertEqual(QRChatCodec.fingerprint(of: vector.passphrase), vector.fingerprint, vector.label)
        }
        let sample = QRChatCodec.fingerprint(of: "x")
        XCTAssertEqual(sample.count, 9, "fingerprint format XXXX XXXX")
        XCTAssertEqual(Array(sample)[4], " ", "fingerprint separator")
        XCTAssertEqual(sample, sample.uppercased(), "fingerprint must be uppercase hex")
    }

    func testEncodeTrimsTheName() async throws {
        let vector = QRChatCodecTests.vectors[0]
        let encoded = try await QRChatCodec.encode(
            name: "  Alice \n",
            passphrase: vector.passphrase,
            pin: vector.pin,
            salt: vector.salt,
            nonce: vector.nonce
        )
        XCTAssertEqual(encoded, vector.encoded, "a trimmed name must produce the vector 1 text")
    }

    // MARK: - Wrong PIN

    func testWrongPinReturnsWrongPin() async throws {
        let vector = QRChatCodecTests.vectors[0]
        let parsed = try parsedOrFail(vector.encoded, "V1")
        await assertOpenFails(parsed, pin: "123457", .wrongPin, "wrong PIN")
    }

    func testTamperedCiphertextReturnsWrongPin() async throws {
        let parsed = try parsedOrFail(QRChatCodecTests.tamperedCiphertextText, "tampered V1")
        XCTAssertEqual(QRChatCodecTests.hexString(parsed.salt), QRChatCodecTests.vectors[0].saltHex)
        XCTAssertEqual(QRChatCodecTests.hexString(parsed.nonce), QRChatCodecTests.vectors[0].nonceHex)
        XCTAssertNotEqual(
            QRChatCodecTests.hexString(parsed.ciphertextAndTag),
            QRChatCodecTests.vectors[0].ciphertextHex + QRChatCodecTests.vectors[0].tagHex,
            "the tampered vector must differ from V1"
        )
        await assertOpenFails(parsed, pin: "123456", .wrongPin, "tampered ciphertext")
    }

    // MARK: - Whitespace tolerance (P1)

    func testTrailingNewlineIsAccepted() async throws {
        let vector = QRChatCodecTests.vectors[0]
        let clean = try parsedOrFail(vector.encoded, "V1")

        for (label, text) in [
            ("trailing newline", vector.encoded + "\n"),
            ("trailing CRLF", vector.encoded + "\r\n"),
            ("leading spaces", "  " + vector.encoded),
            ("surrounding whitespace", " \t" + vector.encoded + " \r\n")
        ] {
            let parsed = try parsedOrFail(text, label)
            XCTAssertEqual(parsed, clean, label)
        }

        let payload = try await QRChatCodec.open(clean, pin: vector.pin)
        XCTAssertEqual(payload.name, "Alice")
        XCTAssertEqual(payload.passphrase, "correct horse battery staple")
    }

    // MARK: - Negative vectors of section 1.6 (parse level)

    func testNegativeParseVectors() {
        let text = QRChatCodecTests.vectors[0].encoded
        let body = String(text.dropFirst(QRChatCodecTests.prefixLength))
        XCTAssertEqual(body.count, 106, "V1 body length")

        assertParseFails("https://darkmessage.app/chat/1/" + body, .notDarkMessage, "https URL")
        assertParseFails("DARKMESSAGE://chat/1/" + body, .notDarkMessage, "uppercase scheme")
        assertParseFails(QRChatCodecTests.versionTwoText, .unsupportedVersion, "version 2")
        assertParseFails(QRChatCodecTests.kdfIdTwoText, .unsupportedVersion, "kdfId 0x02")
        assertParseFails(text + "==", .malformed, "base64 padding appended")
        assertParseFails(replacingBodyCharacter(text, at: 29, with: "+"), .malformed, "plus in body")
        assertParseFails(replacingBodyCharacter(text, at: 29, with: "/"), .malformed, "slash in body")
        assertParseFails(insertingInBody(text, at: 29, " "), .malformed, "space inside body")
        assertParseFails(String(text.prefix(60)), .malformed, "truncated body")
        assertParseFails(text + "AAA", .malformed, "body length 1 mod 4")
        assertParseFails(
            "darkmessage://chat/1/" + String(repeating: "A", count: 319),
            .malformed,
            "body longer than 318 chars"
        )
        assertParseFails("darkmessage://chat/x/" + body, .malformed, "non-digit version")
        assertParseFails("darkmessage://chat/", .malformed, "no version")
        assertParseFails("darkmessage://chat/1/", .malformed, "empty body")
        assertParseFails("darkmessage://chat//" + body, .malformed, "empty version")
        assertParseFails("darkmessage://chat/1", .malformed, "version without a slash")

        // Foreign codes are never mistaken for ours (acceptance step 18).
        assertParseFails("", .notDarkMessage, "empty string")
        assertParseFails("https://example.com/", .notDarkMessage, "website QR")
        assertParseFails("WIFI:T:WPA;S:home;P:secret;;", .notDarkMessage, "Wi-Fi QR")
        assertParseFails("AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8w", .notDarkMessage, "darkm base64 text")
        assertParseFails("darkmessage://other/1/" + body, .notDarkMessage, "different host")

        // P2 runs before P3: an over-long input is malformed, whatever it contains.
        assertParseFails(String(repeating: "x", count: 601), .malformed, "601 characters")
        assertParseFails(
            "darkmessage://chat/1/" + String(repeating: "A", count: 600),
            .malformed,
            "621 characters"
        )
    }

    /// The parser is the only code that ever touches foreign input, so it must
    /// return a `Result` for absolutely everything and never throw or trap.
    func testParseNeverTrapsOnArbitraryInput() {
        var inputs: [String] = [
            "", " ", "\n", "\t\r\n ", "darkmessage", "darkmessage:", "darkmessage://",
            "darkmessage://chat", "darkmessage://chat/1", "darkmessage://chat/1/=",
            "darkmessage://chat/-1/AAAA", "darkmessage://chat/99999999999999999999/AAAA",
            "darkmessage://chat/1/\u{0}\u{0}\u{0}", "darkmessage://chat/1/🔑🔑🔑",
            "Бабушка", "🚀", String(repeating: "🔑", count: 200),
            String(repeating: "darkmessage://chat/1/", count: 40)
        ]

        for length in 0...200 {
            inputs.append(String(QRChatCodecTests.vectors[0].encoded.prefix(length)))
            inputs.append(String(QRChatCodecTests.vectors[3].encoded.prefix(length)))
        }

        // Deterministic pseudo-random garbage, so a failure is reproducible.
        var state: UInt64 = 0x9E3779B97F4A7C15
        func nextRandom() -> UInt64 {
            state = state &* 6364136223846793005 &+ 1442695040888963407
            return state
        }
        for _ in 0..<300 {
            var noise = ""
            let count = Int(nextRandom() % 90)
            for _ in 0..<count {
                let value = UInt32(nextRandom() % 0x2000)
                if let scalar = Unicode.Scalar(value) {
                    noise.append(Character(scalar))
                }
            }
            inputs.append(noise)
            inputs.append("darkmessage://chat/1/" + noise)
            inputs.append("darkmessage://chat/" + noise)
        }

        for input in inputs {
            switch QRChatCodec.parse(input) {
            case .success(let parsed):
                // Anything that does parse must satisfy the whole envelope.
                XCTAssertEqual(parsed.kdfId, 0x01)
                XCTAssertEqual(parsed.salt.count, 16)
                XCTAssertEqual(parsed.nonce.count, 12)
                XCTAssertGreaterThanOrEqual(parsed.ciphertextAndTag.count, 18)
                XCTAssertLessThanOrEqual(parsed.ciphertextAndTag.count, 209)
            case .failure:
                break
            }
        }
    }

    // MARK: - Negative vectors of section 1.6 (plaintext level)

    func testPlaintextLevelNegativeVectors() async throws {
        let salt = Data(repeating: 0x11, count: 16)
        let nonce = Data(repeating: 0x22, count: 12)
        let pin = "123456"
        let key = try await PBKDF2KeyDeriver().deriveKey(passphrase: pin, salt: salt)

        // nameLen = 65 (greater than the 64-byte maximum)
        var nameTooLong = Data()
        nameTooLong.append(UInt8(65))
        nameTooLong.append(Data(repeating: UInt8(0x41), count: 65))
        nameTooLong.append(Data("p".utf8))
        let sealedNameTooLong = try sealed(plaintext: nameTooLong, key: key, salt: salt, nonce: nonce)
        await assertOpenFails(sealedNameTooLong, pin: pin, .malformed, "nameLen 65")

        // nameLen = pt.count - 1, so there is no passphrase byte left
        var noPassphrase = Data()
        noPassphrase.append(UInt8(3))
        noPassphrase.append(Data("ABC".utf8))
        let sealedNoPassphrase = try sealed(plaintext: noPassphrase, key: key, salt: salt, nonce: nonce)
        await assertOpenFails(sealedNoPassphrase, pin: pin, .malformed, "no passphrase bytes")

        // passphrase of 129 bytes
        var passphraseTooLong = Data()
        passphraseTooLong.append(UInt8(0))
        passphraseTooLong.append(Data(repeating: UInt8(0x78), count: 129))
        let sealedPassphraseTooLong = try sealed(plaintext: passphraseTooLong, key: key, salt: salt, nonce: nonce)
        await assertOpenFails(sealedPassphraseTooLong, pin: pin, .malformed, "passphrase 129 bytes")

        // name containing 0x0A
        var controlInName = Data()
        controlInName.append(UInt8(5))
        controlInName.append(Data([0x41, 0x62, 0x0A, 0x43, 0x64]))
        controlInName.append(Data("p".utf8))
        let sealedControlInName = try sealed(plaintext: controlInName, key: key, salt: salt, nonce: nonce)
        await assertOpenFails(sealedControlInName, pin: pin, .malformed, "newline inside the name")

        // passphrase that is not valid UTF-8
        var invalidPassphrase = Data()
        invalidPassphrase.append(UInt8(0))
        invalidPassphrase.append(Data([0xFF, 0xFE]))
        let sealedInvalidPassphrase = try sealed(plaintext: invalidPassphrase, key: key, salt: salt, nonce: nonce)
        await assertOpenFails(sealedInvalidPassphrase, pin: pin, .malformed, "non-UTF-8 passphrase")

        // name that is not valid UTF-8
        var invalidName = Data()
        invalidName.append(UInt8(2))
        invalidName.append(Data([0xFF, 0xFE]))
        invalidName.append(Data("p".utf8))
        let sealedInvalidName = try sealed(plaintext: invalidName, key: key, salt: salt, nonce: nonce)
        await assertOpenFails(sealedInvalidName, pin: pin, .malformed, "non-UTF-8 name")

        // one-byte plaintext: too short to carry a name length and a passphrase
        let sealedTooShort = try sealed(plaintext: Data([0x00]), key: key, salt: salt, nonce: nonce)
        await assertOpenFails(sealedTooShort, pin: pin, .malformed, "single-byte plaintext")

        // Positive control with the same key: nameLen = 0 means "no name".
        var emptyName = Data()
        emptyName.append(UInt8(0))
        emptyName.append(Data("secret".utf8))
        let parsedEmptyName = try sealed(plaintext: emptyName, key: key, salt: salt, nonce: nonce)
        let payload = try await QRChatCodec.open(parsedEmptyName, pin: pin)
        XCTAssertNil(payload.name, "nameLen 0 must yield a nil name")
        XCTAssertEqual(payload.passphrase, "secret")
    }

    /// Proves that the test-only `sealed(...)` helper builds the same AAD and the
    /// same ciphertext framing as the codec, so the plaintext-level negatives above
    /// really do exercise `open` and not a broken fixture.
    func testSealHelperReproducesVector1Ciphertext() async throws {
        let vector = QRChatCodecTests.vectors[0]
        let key = try await PBKDF2KeyDeriver().deriveKey(passphrase: vector.pin, salt: vector.salt)
        XCTAssertEqual(QRChatCodecTests.hexString(key), vector.keyHex)

        let plaintext = QRChatCodecTests.data(fromHex: plaintextHex(of: vector))
        let parsed = try sealed(plaintext: plaintext, key: key, salt: vector.salt, nonce: vector.nonce)
        XCTAssertEqual(
            QRChatCodecTests.hexString(parsed.ciphertextAndTag),
            vector.ciphertextHex + vector.tagHex,
            "the helper must reproduce the vector 1 ciphertext and tag"
        )
    }

    // MARK: - encode validation (section 1.4, steps 1-3)

    func testEncodeValidationErrors() async {
        let longPassphrase = String(repeating: "a", count: 129)
        let longName = String(repeating: "b", count: 65)

        await assertEncodeFails(name: "Alice", passphrase: "", pin: "123456",
                                expect: .noPassphrase, label: "empty passphrase")
        await assertEncodeFails(name: "Alice", passphrase: longPassphrase, pin: "123456",
                                expect: .passphraseTooLong, label: "passphrase of 129 bytes")
        await assertEncodeFails(name: "Alice", passphrase: String(repeating: "\u{451}", count: 65), pin: "123456",
                                expect: .passphraseTooLong, label: "passphrase of 130 bytes")
        await assertEncodeFails(name: longName, passphrase: "ok", pin: "123456",
                                expect: .nameTooLong, label: "name of 65 bytes")
        await assertEncodeFails(name: String(repeating: "\u{451}", count: 33), passphrase: "ok", pin: "123456",
                                expect: .nameTooLong, label: "name of 66 bytes")

        for badPin in ["", "1", "12345", "1234567", "12345a", "12 456", "abcdef", "١٢٣٤٥٦", "12.456"] {
            await assertEncodeFails(name: "Alice", passphrase: "ok", pin: badPin,
                                    expect: .invalidPin, label: "PIN \(badPin)")
        }

        // A wrong-sized injected salt or nonce must never silently produce a code.
        await assertEncodeFails(name: "Alice", passphrase: "ok", pin: "123456",
                                salt: Data(repeating: 0x01, count: 15),
                                nonce: Data(repeating: 0x02, count: 12),
                                expect: .malformed, label: "15-byte salt")
        await assertEncodeFails(name: "Alice", passphrase: "ok", pin: "123456",
                                salt: Data(repeating: 0x01, count: 16),
                                nonce: Data(repeating: 0x02, count: 16),
                                expect: .malformed, label: "16-byte nonce")
    }

    func testOpenRejectsInvalidPinWithoutCrypto() async throws {
        let parsed = try parsedOrFail(QRChatCodecTests.vectors[0].encoded, "V1")
        for badPin in ["", "12345", "1234567", "12345a", "abcdef"] {
            await assertOpenFails(parsed, pin: badPin, .malformed, "open with PIN \"\(badPin)\"")
        }
    }

    func testOpenRejectsBrokenHeaders() async {
        let salt = Data(repeating: 0x11, count: 16)
        let nonce = Data(repeating: 0x22, count: 12)
        let body = Data(repeating: 0x33, count: 40)

        await assertOpenFails(
            ParsedQR(kdfId: 0x01, salt: Data(repeating: 0x11, count: 15), nonce: nonce, ciphertextAndTag: body),
            pin: "123456", .malformed, "15-byte salt"
        )
        await assertOpenFails(
            ParsedQR(kdfId: 0x01, salt: salt, nonce: Data(repeating: 0x22, count: 11), ciphertextAndTag: body),
            pin: "123456", .malformed, "11-byte nonce"
        )
        await assertOpenFails(
            ParsedQR(kdfId: 0x01, salt: salt, nonce: nonce, ciphertextAndTag: Data(repeating: 0x33, count: 17)),
            pin: "123456", .malformed, "17-byte ciphertext and tag"
        )
    }

    // MARK: - PIN generation

    func testGeneratePin() {
        var seen = Set<String>()
        for _ in 0..<200 {
            let pin = QRChatCodec.generatePin()
            XCTAssertEqual(pin.count, 6, "PIN must have 6 characters")
            XCTAssertEqual(pin.utf8.count, 6, "PIN must be 6 ASCII bytes")
            XCTAssertTrue(pin.allSatisfy({ $0.isASCII && $0.isNumber }), "PIN must be digits only")
            seen.insert(pin)
        }
        XCTAssertGreaterThan(seen.count, 100, "generatePin must not be close to constant")
        // Leading zeros are kept, which is what the %06d format guarantees.
        XCTAssertEqual(String(format: "%06d", 7042), "007042")
        XCTAssertEqual(String(format: "%06d", 0), "000000")
    }

    func testPrefixConstants() {
        XCTAssertEqual(QRChatCodec.textPrefix, "darkmessage://chat/")
        XCTAssertEqual(QRChatCodec.textPrefix.utf8.count, 19)
        XCTAssertEqual(QRChatCodec.supportedVersion, "1")
        XCTAssertEqual(QRChatCodec.urlScheme, "darkmessage")
        XCTAssertEqual(QRChatCodec.kdfIdPBKDF2, 0x01)
        XCTAssertEqual(QRChatCodec.maxNameBytes, 64)
        XCTAssertEqual(QRChatCodec.maxPassphraseBytes, 128)
        XCTAssertEqual(QRChatCodec.pinDigits, 6)
        for vector in QRChatCodecTests.vectors {
            XCTAssertTrue(vector.encoded.hasPrefix("darkmessage://chat/1/"), vector.label)
        }
    }

    func testRandomNonceIsTwelveFreshBytes() {
        var seen = Set<Data>()
        for _ in 0..<50 {
            let nonce = QRChatCodec.randomNonce()
            XCTAssertEqual(nonce.count, 12)
            seen.insert(nonce)
        }
        XCTAssertEqual(seen.count, 50, "every nonce must be different")
        XCTAssertEqual(PBKDF2KeyDeriver().generateSalt().count, 16)
    }

    // MARK: - Shared vector runner

    private func runVector(_ vector: Vector) async throws {
        // The UTF-8 bytes of the inputs are part of the contract: if the source
        // file lost its encoding, fail here rather than somewhere confusing.
        XCTAssertEqual(QRChatCodecTests.hexString(Data(vector.name.utf8)), vector.nameHex,
                       "\(vector.label): name bytes")
        XCTAssertEqual(QRChatCodecTests.hexString(Data(vector.passphrase.utf8)), vector.passphraseHex,
                       "\(vector.label): passphrase bytes")

        // encode must reproduce the spec string character for character.
        let encoded = try await QRChatCodec.encode(
            name: vector.name,
            passphrase: vector.passphrase,
            pin: vector.pin,
            salt: vector.salt,
            nonce: vector.nonce
        )
        XCTAssertEqual(encoded, vector.encoded, "\(vector.label): encoded text")

        // Size envelope of section 1.3.
        XCTAssertTrue(encoded.allSatisfy({ $0.isASCII }), "\(vector.label): text must be pure ASCII")
        XCTAssertGreaterThanOrEqual(encoded.count, 84, "\(vector.label): text too short")
        XCTAssertLessThanOrEqual(encoded.count, 339, "\(vector.label): text too long")
        let bodyLength = encoded.count - QRChatCodecTests.prefixLength
        XCTAssertGreaterThanOrEqual(bodyLength, 63, "\(vector.label): body too short")
        XCTAssertLessThanOrEqual(bodyLength, 318, "\(vector.label): body too long")
        XCTAssertNotEqual(bodyLength % 4, 1, "\(vector.label): body length must not be 1 mod 4")

        // parse splits the header byte-exactly.
        let parsed = try parsedOrFail(vector.encoded, vector.label)
        XCTAssertEqual(parsed.kdfId, 0x01, "\(vector.label): kdfId")
        XCTAssertEqual(QRChatCodecTests.hexString(parsed.salt), vector.saltHex, "\(vector.label): salt")
        XCTAssertEqual(QRChatCodecTests.hexString(parsed.nonce), vector.nonceHex, "\(vector.label): nonce")
        XCTAssertEqual(QRChatCodecTests.hexString(parsed.ciphertextAndTag),
                       vector.ciphertextHex + vector.tagHex, "\(vector.label): ciphertext and tag")

        // open returns exactly the name and the passphrase.
        let payload = try await QRChatCodec.open(parsed, pin: vector.pin)
        XCTAssertEqual(payload.name, vector.name, "\(vector.label): name")
        XCTAssertEqual(payload.passphrase, vector.passphrase, "\(vector.label): passphrase")
        XCTAssertEqual(QRChatCodec.fingerprint(of: vector.passphrase), vector.fingerprint,
                       "\(vector.label): fingerprint")
    }

    // MARK: - Assertion helpers

    private func assertParseFails(
        _ text: String,
        _ expected: QRChatError,
        _ label: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        switch QRChatCodec.parse(text) {
        case .success:
            XCTFail("\(label): expected \(expected), but parse succeeded", file: file, line: line)
        case .failure(let error):
            XCTAssertEqual(error, expected, label, file: file, line: line)
        }
    }

    private func parsedOrFail(
        _ text: String,
        _ label: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) throws -> ParsedQR {
        switch QRChatCodec.parse(text) {
        case .success(let parsed):
            return parsed
        case .failure(let error):
            XCTFail("\(label): expected a parsed code, got \(error)", file: file, line: line)
            throw error
        }
    }

    private func assertOpenFails(
        _ parsed: ParsedQR,
        pin: String,
        _ expected: QRChatError,
        _ label: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async {
        do {
            _ = try await QRChatCodec.open(parsed, pin: pin)
            XCTFail("\(label): expected \(expected), but open succeeded", file: file, line: line)
        } catch let error as QRChatError {
            XCTAssertEqual(error, expected, label, file: file, line: line)
        } catch {
            XCTFail("\(label): unexpected error \(error)", file: file, line: line)
        }
    }

    private func assertEncodeFails(
        name: String,
        passphrase: String,
        pin: String,
        salt: Data = Data(repeating: 0x01, count: 16),
        nonce: Data = Data(repeating: 0x02, count: 12),
        expect expected: QRChatError,
        label: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async {
        do {
            _ = try await QRChatCodec.encode(
                name: name,
                passphrase: passphrase,
                pin: pin,
                salt: salt,
                nonce: nonce
            )
            XCTFail("\(label): expected \(expected), but encode succeeded", file: file, line: line)
        } catch let error as QRChatError {
            XCTAssertEqual(error, expected, label, file: file, line: line)
        } catch {
            XCTFail("\(label): unexpected error \(error)", file: file, line: line)
        }
    }

    // MARK: - Test-only sealing (to build the plaintext-level negative vectors)

    /// Seals an arbitrary plaintext exactly the way section 1.3 prescribes.
    /// Verified against vector 1 by `testSealHelperReproducesVector1Ciphertext`.
    private func sealed(plaintext: Data, key: Data, salt: Data, nonce: Data) throws -> ParsedQR {
        var aad = Data("darkmessage-qr-v1".utf8)
        aad.append(UInt8(0x01))
        aad.append(salt)
        aad.append(nonce)
        let gcmNonce = try AES.GCM.Nonce(data: nonce)
        let box = try AES.GCM.seal(
            plaintext,
            using: SymmetricKey(data: key),
            nonce: gcmNonce,
            authenticating: aad
        )
        return ParsedQR(
            kdfId: 0x01,
            salt: salt,
            nonce: nonce,
            ciphertextAndTag: box.ciphertext + box.tag
        )
    }

    // MARK: - Hex helpers

    private func plaintextHex(of vector: Vector) -> String {
        let nameBytes = Data(vector.name.utf8)
        var plaintext = Data()
        plaintext.append(UInt8(nameBytes.count))
        plaintext.append(nameBytes)
        plaintext.append(Data(vector.passphrase.utf8))
        return QRChatCodecTests.hexString(plaintext)
    }

    private func replacingBodyCharacter(_ text: String, at offset: Int, with replacement: Character) -> String {
        var characters = Array(text)
        let index = QRChatCodecTests.prefixLength + offset
        guard index < characters.count else {
            return text
        }
        characters[index] = replacement
        return String(characters)
    }

    private func insertingInBody(_ text: String, at offset: Int, _ inserted: String) -> String {
        var characters = Array(text)
        let index = min(QRChatCodecTests.prefixLength + offset, characters.count)
        characters.insert(contentsOf: Array(inserted), at: index)
        return String(characters)
    }

    fileprivate static func hexString(_ data: Data) -> String {
        var text = ""
        for byte in data {
            text += String(format: "%02x", byte)
        }
        return text
    }

    fileprivate static func data(fromHex hex: String) -> Data {
        var bytes: [UInt8] = []
        var index = hex.startIndex
        while index < hex.endIndex {
            guard let next = hex.index(index, offsetBy: 2, limitedBy: hex.endIndex) else {
                break
            }
            guard let byte = UInt8(hex[index..<next], radix: 16) else {
                break
            }
            bytes.append(byte)
            index = next
        }
        return Data(bytes)
    }
}
