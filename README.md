# Dark Message

Offline encrypted messaging for Android and iOS.

You encrypt on your own phone, send the result through whatever app you already use — a
messenger, e-mail, a file transfer, a printed QR code — and the other phone decrypts it.
The app itself never touches a network.

[Русская версия](README.ru.md)

---

## What it is

Dark Message is not a messenger. It has no servers, no accounts, no contact list and no
delivery. It is a pair of tools — encrypt and decrypt — plus a place to keep the keys.
Everything it produces is an ordinary piece of text or an ordinary file that you carry
yourself.

That is the whole design: if the app never sends anything, there is no traffic to intercept,
no metadata to collect and no account to seize.

**What it deliberately does not do**

- No network access at all. The Android manifest not only omits `INTERNET`, it removes the
  permission explicitly with `tools:node="remove"`, and a Gradle task parses the *merged*
  release manifest at build time and fails the build if either `INTERNET` or
  `ACCESS_NETWORK_STATE` ever reappears through a dependency.
- No accounts, no registration, no telephone number.
- No analytics, no crash reporting, no advertising identifiers, no ads.
- Nothing leaves the device unless you hand it to another app yourself.

## How it works

Four screens, mirrored on both platforms.

| Screen | What it does |
| --- | --- |
| **Chats** | A named chat is one shared passphrase. Each row shows the key fingerprint. |
| **Encrypt** | Text, a photo or a document → base64 on the clipboard, or a `.darkm` file to share. |
| **Decrypt** | Paste base64 or open a `.darkm`; the result can be viewed, saved or shared on. |
| **Settings** | Theme, language (English / Russian), instructions. |

Both people need the same passphrase. You can type it on both phones, or hand it over in
person with the built-in QR exchange: one phone shows a code, the other scans it, and the
sender reads out a six-digit PIN. The passphrase inside the code is encrypted under that PIN,
so a photograph of the screen is useless without it.

To confirm two phones really hold the same key, compare the **fingerprint** shown under each
chat name — eight hex characters, the first four bytes of `SHA-256(passphrase)`. It reveals
nothing about the key, and nobody has to read a passphrase aloud.

## Cryptography

Deliberately boring and standard, and identical on both platforms.

| | |
| --- | --- |
| Key derivation | PBKDF2-HMAC-SHA512, **600 000** iterations, 16-byte random salt, 32-byte output |
| Cipher | AES-256-GCM, 12-byte random nonce per message, 16-byte tag |
| Randomness | `SecureRandom` (Android), `SecRandomCopyBytes` (iOS) |
| Key storage | Tink AEAD over DataStore (Android), Keychain, `WhenUnlockedThisDeviceOnly` (iOS) |

The passphrase is hashed as **raw UTF-8 bytes** on both sides. This matters: Java's
`PBEKeySpec` and Apple's `CCKeyDerivationPBKDF` disagree about how to turn a non-ASCII
password into bytes, so a Russian passphrase used to derive two different keys. Both
platforms now run PBKDF2 over the UTF-8 bytes directly.

### Payload format

A `.darkm` file, and the base64 text form, are the same bytes:

```
[version:1][contentType:1][salt:16][nonce:12][ciphertext‖tag:N]
```

`version` is `0x01`. `contentType` is `0x01` text, `0x02` image, `0x03` document. For a
document, the decrypted plaintext is framed as:

```
[nameLen:2 big-endian][name UTF-8][file bytes]
```

A length that does not fit the plaintext means the bytes are not a name frame and the whole
plaintext is the file; a length of zero means the sender had no name for it.

### QR key exchange

```
darkmessage://chat/1/<base64url, no padding>
```

The body is `kdfId(0x01) ‖ salt(16) ‖ nonce(12) ‖ ciphertext‖tag`, sealed with AES-256-GCM
under a key derived from the six-digit PIN by the same PBKDF2 parameters. The additional
authenticated data is `"darkmessage-qr-v1" ‖ kdfId ‖ salt ‖ nonce`, and the plaintext is
`nameLen(1) ‖ name(UTF-8, ≤64 B) ‖ passphrase(UTF-8, ≤128 B)`.

The sending and scanning screens set `FLAG_SECURE` / hide themselves from the app switcher,
and the code disappears after a minute.

## Repository layout

```
android/    Kotlin, Jetpack Compose, Material 3   (minSdk 26, targetSdk 35)
ios/        Swift, SwiftUI                        (iOS 17+, iPhone and iPad)
```

The two apps share no code — they share a *format*, and a test suite on each side that
asserts the same vectors byte for byte. That is on purpose: each app uses its own platform's
audited primitives rather than a bundled crypto library.

## Building

### Android

```bash
cd android
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

A release build needs your own signing key. Create `android/local.properties` with

```properties
RELEASE_STORE_FILE=/absolute/path/to/your.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

or set the same names as environment variables. `local.properties` is git-ignored and no
keystore is included in this repository.

### iOS

The Xcode project is generated, not committed:

```bash
cd ios
brew install xcodegen
xcodegen generate
open DarkMessage.xcodeproj
```

Set your own signing team in Xcode. There are no third-party dependencies — nothing to
resolve, nothing to trust but Apple's frameworks and this repository.

### Tests

```bash
cd android && ./gradlew testDebugUnitTest          # 122 tests
cd ios && xcodebuild test -scheme DarkMessage \
    -destination 'platform=iOS Simulator,name=iPhone 17 Pro'   # 91 tests
```

The iOS suite includes UI tests that drive the real screens on a simulator, and both suites
assert the same cross-platform vectors, so a change that breaks compatibility fails a build
on both sides rather than surfacing on someone's phone.

## Security

**This is not audited cryptography.** It is a careful, conventional composition of standard
primitives — PBKDF2 and AES-GCM as the platforms implement them — written by one developer
and reviewed by no independent party. Read the code before you trust it with anything that
matters. Issues and corrections are welcome.

What the design does protect against: someone reading the message in transit, the transport
keeping a copy, and a server holding your history — because there is no server and the
transport only ever sees ciphertext.

What it does not protect against: a compromised phone, someone who learns the passphrase, a
weak passphrase (use the generator), or anyone who can see that you and another person are
exchanging files at all. It hides the contents, not the fact.

If you find a vulnerability, please open an issue — or, if you would rather not do that in
public, write to darkmessageapp@gmail.com.

## Licence

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

---

## Support the project

Dark Message is free, has no ads, no subscriptions and collects nothing. It is built and
maintained by one person in his own time.

If it has been useful and you would like to help it keep going, these are the addresses.
It is entirely voluntary, and it unlocks nothing — there is nothing to unlock.

| | Network | Address |
| --- | --- | --- |
| **USDT** | TRON (TRC-20) | `TVfxWMieo8xUGu73FjGR6Xb7Q3atYgUMr3` |
| **BTC** | Bitcoin | `bc1qs5fly0u7fa9dgg2dmlzqf82ttxvwy2hl68g059` |
| **ETH** | Ethereum (ERC-20) | `0x7a36d08EF5dC64dDC50a5687A9F209CC72e857d5` |

Please send only the named coin on the named network — a transfer on the wrong network is
lost for good. Every address above was checked against its own checksum (base58check, bech32
and EIP-55) before being published here.

Starring the repository, reporting a bug or fixing a typo helps just as much.
