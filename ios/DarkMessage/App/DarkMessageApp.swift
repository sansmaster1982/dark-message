import SwiftUI

@main
struct DarkMessageApp: App {

    @StateObject private var chatStore = ChatStore()
    @StateObject private var settings = SettingsStore()

    init() {
        // Register first-launch defaults BEFORE anything reads UserDefaults.
        // The registration domain is consulted only when no value has been saved,
        // so a language picked in Settings (persisted by @AppStorage) always wins;
        // until then the app follows the device language / region.
        UserDefaults.standard.register(defaults: [
            AppLanguage.storageKey: AppLanguage.detectDefault().rawValue,
            ThemeMode.storageKey: ThemeMode.dark.rawValue
        ])

        // Freeze that first decision, the way Android persists it. The registration
        // domain is not saved to disk, so without this the detection would re-run on
        // every launch and the UI language would follow the phone around: change the
        // region while travelling, or add a language, and the app would silently
        // switch. Only written when nothing was saved before, so a choice made in
        // Settings is never touched.
        if UserDefaults.standard.object(forKey: AppLanguage.storageKey) == nil {
            UserDefaults.standard.set(
                AppLanguage.detectDefault().rawValue,
                forKey: AppLanguage.storageKey
            )
        }

        // Point L() at the effective language (saved choice, else the registered
        // default) before the first view renders. SettingsStore's didSet keeps
        // LocaleManager in sync afterwards.
        let storedLanguage = UserDefaults.standard.string(forKey: AppLanguage.storageKey) ?? ""
        let language = AppLanguage(rawValue: storedLanguage) ?? AppLanguage.detectDefault()
        LocaleManager.shared.update(language: language)
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(chatStore)
                .environmentObject(settings)
                .preferredColorScheme(settings.colorScheme)
                // Rebuild the whole view tree when the language changes so every
                // L() string is re-evaluated, including tabs that do not observe
                // SettingsStore (Chats / Encrypt / Decrypt) and cached nav titles.
                .id(settings.language)
        }
    }
}
