import SwiftUI

enum ThemeMode: String, CaseIterable {
    case dark, light, system

    /// UserDefaults key backing `SettingsStore.themeMode`; also used by
    /// `DarkMessageApp.init()` to register the first-launch default.
    static let storageKey = "theme_mode"
}

enum AppLanguage: String, CaseIterable {
    case english = "en"
    case russian = "ru"

    /// UserDefaults key backing `SettingsStore.language`; also used by
    /// `DarkMessageApp.init()` to register the first-launch default.
    static let storageKey = "language"

    var displayName: String {
        switch self {
        case .english: return "English"
        case .russian: return "Русский"
        }
    }

    /// Language used while the user has not picked one in Settings:
    /// Russian if the device language is Russian OR the device region is Russia,
    /// otherwise English. Never consulted once a choice has been saved.
    /// First-launch language only. Mirrors Android's `LanguageDetector.decide`:
    /// ANY preferred language being Russian counts, not just the first one, because
    /// a phone set to English with Russian second is still a Russian-speaking phone.
    /// Android additionally consults the SIM and network country; iOS has no
    /// permission-free equivalent since CTCarrier stopped reporting it, so the
    /// region is as close as this platform gets.
    static func detectDefault() -> AppLanguage {
        let languageIsRussian = Locale.preferredLanguages.contains { identifier in
            identifier.lowercased().hasPrefix("ru")
        }
        let regionIsRussia = Locale.current.region?.identifier.uppercased() == "RU"
        return (languageIsRussian || regionIsRussia) ? .russian : .english
    }
}

final class SettingsStore: ObservableObject {

    // @AppStorage inside an ObservableObject publishes objectWillChange on every
    // write (SwiftUI's enclosing-instance hook), so views observing this store
    // re-render when a setting changes. The fallback values below are only used
    // if a key is missing from every UserDefaults domain; DarkMessageApp.init()
    // registers the same defaults before anything reads them.
    @AppStorage(ThemeMode.storageKey) var themeMode: ThemeMode = .dark
    @AppStorage(AppLanguage.storageKey) var language: AppLanguage = AppLanguage.detectDefault() {
        didSet {
            // Runs after the new value has been stored, so L() switches bundles
            // before SwiftUI's next render pass (scheduled by objectWillChange).
            LocaleManager.shared.update(language: language)
        }
    }
    @AppStorage("onboarding_completed") var onboardingCompleted = false
    @AppStorage("tutorial_completed") var tutorialCompleted = false
    /// Display name the sender puts into a chat QR code (what the receiver's
    /// chat will be called). Optional, never a secret, remembered between codes.
    @AppStorage("qr_sender_name") var qrSenderName = ""

    var colorScheme: ColorScheme? {
        switch themeMode {
        case .dark: return .dark
        case .light: return .light
        case .system: return nil
        }
    }
}

// MARK: - Locale Manager

final class LocaleManager {
    static let shared = LocaleManager()

    private(set) var bundle: Bundle = .main

    func update(language: AppLanguage) {
        if let path = Bundle.main.path(forResource: language.rawValue, ofType: "lproj"),
           let langBundle = Bundle(path: path) {
            bundle = langBundle
        } else {
            bundle = .main
        }
    }
}

func L(_ key: String) -> String {
    NSLocalizedString(key, bundle: LocaleManager.shared.bundle, comment: "")
}
