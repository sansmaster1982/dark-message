import SwiftUI

struct ContentView: View {

    @EnvironmentObject var chatStore: ChatStore
    @EnvironmentObject var settings: SettingsStore

    // SceneStorage (not State) so the selected tab survives the full view-tree
    // rebuild DarkMessageApp performs via .id(settings.language) when the user
    // switches language in Settings (otherwise the app would jump to Chats).
    @SceneStorage("selected_tab") private var selectedTab: Int = 0
    @State private var showOnboarding = false
    @State private var showTutorial = false
    @State private var pendingFileURL: URL?
    /// Payload of an incoming "darkmessage://" deep link, handed to ChatsView,
    /// which asks for the PIN and for confirmation. Nothing is ever saved here.
    @State private var pendingQR: ParsedQR?
    @State private var qrAlertMessage: String?

    var body: some View {
        ZStack {
            TabView(selection: $selectedTab) {
                ChatsView(pendingQR: $pendingQR)
                    .tabItem {
                        Label(
                            L("nav_chats"),
                            systemImage: "bubble.left.and.bubble.right"
                        )
                    }
                    .tag(0)

                EncryptView()
                    .tabItem {
                        Label(
                            L("nav_encrypt"),
                            systemImage: "lock"
                        )
                    }
                    .tag(1)

                DecryptView(pendingFileURL: $pendingFileURL)
                    .tabItem {
                        Label(
                            L("nav_decrypt"),
                            systemImage: "lock.open"
                        )
                    }
                    .tag(2)

                SettingsView()
                    .tabItem {
                        Label(
                            L("nav_settings"),
                            systemImage: "gearshape"
                        )
                    }
                    .tag(3)
            }
            .tint(AppTheme.primary)

            if showTutorial {
                TutorialOverlay(
                    currentTab: $selectedTab,
                    isPresented: $showTutorial
                )
            }
        }
        .onAppear {
            configureTabBarAppearance()
            if !settings.onboardingCompleted {
                showOnboarding = true
            }
        }
        .fullScreenCover(isPresented: $showOnboarding) {
            OnboardingView {
                settings.onboardingCompleted = true
                showOnboarding = false
                if !settings.tutorialCompleted {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                        showTutorial = true
                    }
                }
            }
        }
        .alert(
            qrAlertMessage ?? "",
            isPresented: Binding(
                get: { qrAlertMessage != nil },
                set: { if !$0 { qrAlertMessage = nil } }
            )
        ) {
            Button(L("qr_done")) {
                qrAlertMessage = nil
            }
        }
        .onOpenURL { url in
            // Raw-string prefix match on purpose: the QR payload is a path
            // segment, so URLComponents/percent-decoding would corrupt it.
            // A "darkmessage:" URL must never reach the file handler.
            if url.absoluteString.lowercased().hasPrefix(QRChatCodec.urlScheme + ":") {
                handleIncomingQR(url.absoluteString)
            } else {
                handleIncomingFile(url)
            }
        }
    }

    private func handleIncomingFile(_ url: URL) {
        pendingFileURL = url
        selectedTab = 2  // Switch to Decrypt tab
    }

    private func handleIncomingQR(_ text: String) {
        switch QRChatCodec.parse(text) {
        case .success(let parsed):
            pendingQR = parsed
            selectedTab = 0  // Chats presents the PIN step
        case .failure(let error):
            switch error {
            case .unsupportedVersion:
                qrAlertMessage = L("qr_error_version")
            default:
                qrAlertMessage = L("qr_error_malformed")
            }
        }
    }

    private func configureTabBarAppearance() {
        let appearance = UITabBarAppearance()
        appearance.configureWithOpaqueBackground()
        appearance.backgroundColor = UIColor(AppTheme.surface)
        UITabBar.appearance().standardAppearance = appearance
        UITabBar.appearance().scrollEdgeAppearance = appearance
    }
}
