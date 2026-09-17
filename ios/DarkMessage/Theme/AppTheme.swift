import SwiftUI

enum AppTheme {
    // Brand colors
    static let primary = Color(red: 0.74, green: 0.56, blue: 1.0)       // #BD8FFF
    static let primaryDark = Color(red: 0.55, green: 0.35, blue: 0.85)  // #8C59D9
    static let surface = Color(red: 0.11, green: 0.11, blue: 0.14)      // #1C1C23
    static let surfaceVariant = Color(red: 0.16, green: 0.16, blue: 0.20) // #292930
    static let background = Color(red: 0.07, green: 0.07, blue: 0.09)   // #121215
    static let onSurface = Color.white
    static let onSurfaceVariant = Color(white: 0.7)
    static let error = Color(red: 1.0, green: 0.4, blue: 0.4)
    static let success = Color(red: 0.4, green: 0.9, blue: 0.4)

    // Light theme colors
    static let lightBackground = Color(red: 0.96, green: 0.96, blue: 0.98)
    static let lightSurface = Color.white
    static let lightSurfaceVariant = Color(red: 0.93, green: 0.93, blue: 0.96)
    static let lightOnSurface = Color(red: 0.1, green: 0.1, blue: 0.12)
}

struct DarkMessageButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundColor(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(AppTheme.primary)
                    .opacity(configuration.isPressed ? 0.7 : 1.0)
            )
    }
}

extension View {
    func darkMessageCard() -> some View {
        self
            .padding()
            .background(
                RoundedRectangle(cornerRadius: 16)
                    .fill(AppTheme.surfaceVariant)
            )
    }
}
