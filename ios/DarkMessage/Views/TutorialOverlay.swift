import SwiftUI

struct TutorialOverlay: View {

    @Binding var currentTab: Int
    @Binding var isPresented: Bool
    @EnvironmentObject var settings: SettingsStore

    @State private var step = 0

    private let steps: [(tab: Int, titleKey: String, descKey: String, icon: String)] = [
        (0, "tutorial_step1_title", "tutorial_step1_desc", "bubble.left.and.bubble.right"),
        (1, "tutorial_step2_title", "tutorial_step2_desc", "lock"),
        (2, "tutorial_step3_title", "tutorial_step3_desc", "lock.open"),
        (3, "tutorial_step4_title", "tutorial_step4_desc", "gearshape")
    ]

    var body: some View {
        ZStack {
            // Semi-transparent background
            Color.black.opacity(0.7)
                .ignoresSafeArea()
                .onTapGesture { /* block taps */ }

            VStack(spacing: 24) {
                Spacer()

                // Tutorial card
                VStack(spacing: 16) {
                    Image(systemName: steps[step].icon)
                        .font(.system(size: 48))
                        .foregroundColor(AppTheme.primary)

                    Text(L(steps[step].titleKey))
                        .font(.title2.bold())
                        .foregroundColor(.white)

                    Text(L(steps[step].descKey))
                        .font(.body)
                        .foregroundColor(.white.opacity(0.8))
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)

                    Button {
                        if step < steps.count - 1 {
                            step += 1
                            currentTab = steps[step].tab
                        } else {
                            settings.tutorialCompleted = true
                            isPresented = false
                        }
                    } label: {
                        Text(step < steps.count - 1
                             ? L("tutorial_next")
                             : L("tutorial_got_it"))
                            .font(.headline)
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(
                                RoundedRectangle(cornerRadius: 10)
                                    .fill(AppTheme.primary)
                            )
                    }
                    .padding(.horizontal)
                }
                .padding(24)
                .background(
                    RoundedRectangle(cornerRadius: 20)
                        .fill(AppTheme.surface)
                )
                .padding(.horizontal, 32)

                // Arrow pointing to tab bar
                Image(systemName: "arrowtriangle.down.fill")
                    .font(.title)
                    .foregroundColor(AppTheme.primary)
                    .padding(.bottom, 60)
            }
        }
        .animation(.easeInOut, value: step)
        .onAppear {
            currentTab = steps[0].tab
        }
    }
}
