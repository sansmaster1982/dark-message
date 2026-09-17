import Foundation

struct Chat: Identifiable, Codable, Equatable {
    let id: UUID
    var name: String
    var colorHue: Double        // 0–360 for avatar color
    var sortOrder: Int
    var lastActivityAt: Date?
    let createdAt: Date

    init(
        id: UUID = UUID(),
        name: String,
        colorHue: Double = Double.random(in: 0...360),
        sortOrder: Int = 0,
        lastActivityAt: Date? = nil,
        createdAt: Date = Date()
    ) {
        self.id = id
        self.name = name
        self.colorHue = colorHue
        self.sortOrder = sortOrder
        self.lastActivityAt = lastActivityAt
        self.createdAt = createdAt
    }
}
