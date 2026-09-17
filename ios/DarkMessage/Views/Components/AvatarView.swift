import SwiftUI

struct AvatarView: View {
    let name: String
    let hue: Double
    let size: CGFloat

    init(name: String, hue: Double, size: CGFloat = 44) {
        self.name = name
        self.hue = hue
        self.size = size
    }

    var body: some View {
        ZStack {
            Circle()
                .fill(Color(hue: hue / 360, saturation: 0.5, brightness: 0.7))
                .frame(width: size, height: size)

            Text(String(name.prefix(1)).uppercased())
                .font(.system(size: size * 0.4, weight: .bold))
                .foregroundColor(.white)
        }
    }
}
