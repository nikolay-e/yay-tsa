import SwiftUI
import UIKit

private struct ToastOverlayModifier: ViewModifier {
    @State private var current: ToastMessage?
    @State private var dismissTask: Task<Void, Never>?

    func body(content: Content) -> some View {
        content
            .overlay(alignment: .bottom) {
                if let current {
                    ToastBanner(message: current)
                        .padding(.bottom, 150)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(.spring(response: 0.35, dampingFraction: 0.85), value: current)
            .onReceive(NotificationCenter.default.publisher(for: .yaytsaShowToast)) { note in
                guard let message = note.userInfo?["message"] as? ToastMessage else { return }
                current = message
                // Toasts are transient and easy to miss via swipe navigation — announce
                // directly so VoiceOver users hear the same feedback sighted users see.
                UIAccessibility.post(notification: .announcement, argument: message.text)
                dismissTask?.cancel()
                dismissTask = Task {
                    try? await Task.sleep(nanoseconds: 3_000_000_000)
                    guard !Task.isCancelled else { return }
                    current = nil
                }
            }
    }
}

private struct ToastBanner: View {
    let message: ToastMessage

    private var icon: String {
        switch message.kind {
        case .success: return "checkmark.circle.fill"
        case .error: return "exclamationmark.triangle.fill"
        case .info: return "info.circle.fill"
        }
    }

    private var iconColor: Color {
        switch message.kind {
        case .success: return Theme.Colors.accent
        case .error: return Theme.Colors.error
        case .info: return Theme.Colors.textSecondary
        }
    }

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: icon).foregroundStyle(iconColor)
                .accessibilityHidden(true)
            Text(message.text)
                .font(.subheadline)
                .foregroundStyle(Theme.Colors.textPrimary)
                .lineLimit(2)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(Theme.Colors.bgSecondary, in: RoundedRectangle(cornerRadius: 12))
        .shadow(radius: 8)
        .padding(.horizontal, 20)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("toastBanner")
    }
}

extension View {
    /// Mount once at the app/tab root — any code can trigger a toast via `Toast.show(...)`.
    func toastOverlay() -> some View {
        modifier(ToastOverlayModifier())
    }
}
