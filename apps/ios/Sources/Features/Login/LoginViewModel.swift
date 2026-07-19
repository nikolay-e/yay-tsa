import Foundation

@MainActor
final class LoginViewModel: ObservableObject {
    @Published var serverAddress: String = ""
    @Published var username: String = ""
    @Published var password: String = ""
    @Published var isLoading: Bool = false
    @Published var errorMessage: String?

    private let session: SessionStore

    init(session: SessionStore) {
        self.session = session
    }

    var canSubmit: Bool {
        !serverAddress.trimmingCharacters(in: .whitespaces).isEmpty
            && !username.trimmingCharacters(in: .whitespaces).isEmpty
            && !password.isEmpty
            && !isLoading
    }

    func signIn() async {
        guard let serverURL = Self.normalize(serverAddress) else {
            errorMessage = "Enter a valid server address, e.g. http://192.168.1.10:8080"
            return
        }

        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        do {
            let auth = try await APIClient.login(serverURL: serverURL, username: username, password: password)
            session.signIn(
                serverURL: serverURL,
                userId: auth.user.id,
                accessToken: auth.accessToken,
                isAdmin: auth.user.policy.isAdministrator
            )
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Accepts "host:port", "http://host:port", etc. Defaults to http (dev servers rarely have TLS set up).
    private static func normalize(_ input: String) -> URL? {
        var trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if !trimmed.contains("://") {
            trimmed = "http://" + trimmed
        }
        guard let url = URL(string: trimmed), url.host != nil else { return nil }
        return url
    }
}
