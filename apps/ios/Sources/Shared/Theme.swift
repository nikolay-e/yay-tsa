import SwiftUI

/// Colors and metrics mirroring the PWA's Tailwind theme (apps/web/src/index.css)
/// so the iOS app reads as the same visual system.
enum Theme {
    enum Colors {
        static let bgPrimary = Color(hex: 0x0f0f0f)
        static let bgSecondary = Color(hex: 0x1a1a1a)
        static let bgTertiary = Color(hex: 0x252525)

        static let textPrimary = Color.white
        static let textSecondary = Color(hex: 0xbababa)
        static let textTertiary = Color(hex: 0x909090)

        static let accent = Color(hex: 0x1db954)
        static let accentHover = Color(hex: 0x1ed760)

        static let error = Color(hex: 0xe22134)
        static let success = Color(hex: 0x22c55e)
        static let warning = Color(hex: 0xf59e0b)

        static let border = Color(hex: 0x333333)
        static let textOnAccent = Color.black
    }

    enum Radius {
        static let sm: CGFloat = 4
        static let md: CGFloat = 8
        static let lg: CGFloat = 12
    }
}

extension Color {
    init(hex: UInt32) {
        let r = Double((hex >> 16) & 0xFF) / 255
        let g = Double((hex >> 8) & 0xFF) / 255
        let b = Double(hex & 0xFF) / 255
        self.init(red: r, green: g, blue: b)
    }
}
