import SwiftUI

struct ChatSelector: View {
    let chats: [Chat]
    @Binding var selectedChat: Chat?

    var body: some View {
        Menu {
            ForEach(chats) { chat in
                Button {
                    selectedChat = chat
                } label: {
                    HStack {
                        Text(chat.name)
                        if selectedChat?.id == chat.id {
                            Image(systemName: "checkmark")
                        }
                    }
                }
            }
        } label: {
            HStack {
                if let chat = selectedChat {
                    AvatarView(name: chat.name, hue: chat.colorHue, size: 32)
                    Text(chat.name)
                        .foregroundColor(.primary)
                } else {
                    Image(systemName: "person.circle")
                        .font(.title2)
                        .foregroundColor(.secondary)
                    Text(L("chats_title"))
                        .foregroundColor(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.down")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            .padding()
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(AppTheme.surfaceVariant)
            )
        }
    }
}
