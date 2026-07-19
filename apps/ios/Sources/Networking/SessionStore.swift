import Foundation

/// Holds the active server connection: base URL, logged-in user, and access token.
/// Persists across launches (server URL + user id in UserDefaults, token in Keychain).
@MainActor
final class SessionStore: ObservableObject {
    @Published private(set) var serverURL: URL?
    @Published private(set) var userId: String?
    @Published private(set) var accessToken: String?
    @Published private(set) var isAdmin: Bool = false

    private enum Defaults {
        static let serverURLKey = "yaytsa.serverURL"
        static let userIdKey = "yaytsa.userId"
        static let isAdminKey = "yaytsa.isAdmin"
    }

    var isAuthenticated: Bool {
        serverURL != nil && userId != nil && accessToken != nil
    }

    init() {
        if let urlString = UserDefaults.standard.string(forKey: Defaults.serverURLKey),
           let url = URL(string: urlString) {
            serverURL = url
        }
        userId = UserDefaults.standard.string(forKey: Defaults.userIdKey)
        isAdmin = UserDefaults.standard.bool(forKey: Defaults.isAdminKey)
        accessToken = KeychainStore.load()
    }

    func signIn(serverURL: URL, userId: String, accessToken: String, isAdmin: Bool = false) {
        self.serverURL = serverURL
        self.userId = userId
        self.accessToken = accessToken
        self.isAdmin = isAdmin

        UserDefaults.standard.set(serverURL.absoluteString, forKey: Defaults.serverURLKey)
        UserDefaults.standard.set(userId, forKey: Defaults.userIdKey)
        UserDefaults.standard.set(isAdmin, forKey: Defaults.isAdminKey)
        KeychainStore.save(accessToken)
    }

    /// Swaps in a fresh token without disturbing the rest of the session
    /// (used after a self-service password change, which revokes old tokens).
    func updateAccessToken(_ accessToken: String) {
        self.accessToken = accessToken
        KeychainStore.save(accessToken)
    }

    func signOut() {
        serverURL = nil
        userId = nil
        accessToken = nil
        isAdmin = false
        UserDefaults.standard.removeObject(forKey: Defaults.serverURLKey)
        UserDefaults.standard.removeObject(forKey: Defaults.userIdKey)
        UserDefaults.standard.removeObject(forKey: Defaults.isAdminKey)
        KeychainStore.clear()
    }
}
