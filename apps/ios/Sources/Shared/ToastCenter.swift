import Foundation

enum ToastKind {
    case success, error, info
}

struct ToastMessage: Identifiable, Equatable {
    let id = UUID()
    let text: String
    let kind: ToastKind

    static func == (lhs: ToastMessage, rhs: ToastMessage) -> Bool { lhs.id == rhs.id }
}

extension Notification.Name {
    static let yaytsaShowToast = Notification.Name("yaytsaShowToast")
}

/// Fire-and-forget toast API — posts a notification a single `ToastOverlay` (mounted
/// once at the root) picks up, so any ViewModel can show feedback without needing a
/// shared object threaded through its initializer.
enum Toast {
    static func show(_ text: String, kind: ToastKind = .info) {
        NotificationCenter.default.post(name: .yaytsaShowToast, object: nil, userInfo: ["message": ToastMessage(text: text, kind: kind)])
    }
}
