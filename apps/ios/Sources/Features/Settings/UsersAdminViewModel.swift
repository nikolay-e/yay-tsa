import Foundation

@MainActor
final class UsersAdminViewModel: ObservableObject {
    @Published private(set) var users: [AdminUser] = []
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?

    private let apiClient: APIClient

    init(apiClient: APIClient) {
        self.apiClient = apiClient
    }

    func load() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            users = try await apiClient.fetchUsers()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func delete(_ user: AdminUser) async {
        do {
            try await apiClient.deleteUser(userId: user.id)
            users.removeAll { $0.id == user.id }
            Toast.show("User deleted", kind: .success)
        } catch {
            errorMessage = error.localizedDescription
            Toast.show("Couldn't delete user", kind: .error)
        }
    }

    func resetPassword(_ user: AdminUser) async -> String? {
        do {
            return try await apiClient.resetPassword(userId: user.id)
        } catch {
            errorMessage = error.localizedDescription
            return nil
        }
    }

    func createUser(username: String, displayName: String?, isAdmin: Bool) async -> String? {
        do {
            let initialPassword = try await apiClient.createUser(username: username, displayName: displayName, isAdmin: isAdmin)
            await load()
            return initialPassword
        } catch {
            errorMessage = error.localizedDescription
            return nil
        }
    }
}
