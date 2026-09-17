import SwiftUI

struct OnboardingView: View {

    let onComplete: () -> Void

    @State private var currentPage = 0

    private let pages: [(icon: String, titleKey: String, descKey: String)] = [
        ("lock.shield", "onboarding_welcome_title", "onboarding_welcome_desc"),
        ("plus.bubble", "onboarding_step1_title", "onboarding_step1_desc"),
        ("lock", "onboarding_step2_title", "onboarding_step2_desc"),
        ("paperplane", "onboarding_step3_title", "onboarding_step3_desc"),
        ("lock.open", "onboarding_step4_title", "onboarding_step4_desc")
    ]

    var body: some View {
        ZStack {
            AppTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                // Skip button
                HStack {
                    Spacer()
                    if currentPage < pages.count - 1 {
                        Button(L("onboarding_skip")) {
                            onComplete()
                        }
                        .foregroundColor(AppTheme.onSurfaceVariant)
                        .padding()
                    }
                }

                Spacer()

                // Icon
                Image(systemName: pages[currentPage].icon)
                    .font(.system(size: 80))
                    .foregroundColor(AppTheme.primary)
                    .padding(.bottom, 32)

                // Title
                Text(L(pages[currentPage].titleKey))
                    .font(.title.bold())
                    .foregroundColor(AppTheme.onSurface)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)

                // Description
                Text(L(pages[currentPage].descKey))
                    .font(.body)
                    .foregroundColor(AppTheme.onSurfaceVariant)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
                    .padding(.top, 16)

                Spacer()

                // Page indicator
                HStack(spacing: 8) {
                    ForEach(0..<pages.count, id: \.self) { index in
                        Circle()
                            .fill(index == currentPage ? AppTheme.primary : AppTheme.onSurfaceVariant.opacity(0.3))
                            .frame(width: 8, height: 8)
                    }
                }
                .padding(.bottom, 24)

                // Button
                Button {
                    if currentPage < pages.count - 1 {
                        withAnimation {
                            currentPage += 1
                        }
                    } else {
                        onComplete()
                    }
                } label: {
                    Text(currentPage < pages.count - 1
                         ? L("onboarding_next")
                         : L("onboarding_get_started"))
                }
                .buttonStyle(DarkMessageButtonStyle())
                .padding(.horizontal, 32)
                .padding(.bottom, 48)
            }
        }
    }
}
