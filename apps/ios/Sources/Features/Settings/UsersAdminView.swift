import SwiftUI

struct UsersAdminView: View {
    @StateObject private var viewModel: UsersAdminViewModel
    @State private var isShowingNewUser = false
    @State private var newUsername = ""
    @State private var newDisplayName = ""
    @State private var newIsAdmin = false
    @State private var revealedPassword: RevealedPassword?

    init(apiClient: APIClient) {
        _viewModel = StateObject(wrappedValue: UsersAdminViewModel(apiClient: apiClient))
    }

    var body: some View {
        Group {
            if viewModel.isLoading && viewModel.users.isEmpty {
                ProgressView()
            } else if let errorMessage = viewModel.errorMessage {
                ContentUnavailableView(
                    "Couldn't load users",
                    systemImage: "exclamationmark.triangle",
                    description: Text(errorMessage)
                )
            } else {
                List(viewModel.users) { user in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(user.displayName?.isEmpty == false ? (user.displayName ?? user.username) : user.username)
                                .foregroundStyle(Theme.Colors.textPrimary)
                            if user.isAdmin {
                                Text("Admin")
                                    .font(.caption2)
                                    .padding(.horizontal, 6).padding(.vertical, 2)
                                    .background(Theme.Colors.accent.opacity(0.2))
                                    .foregroundStyle(Theme.Colors.accent)
                                    .clipShape(Capsule())
                            }
                        }
                        Text("@\(user.username)")
                            .font(.caption)
                            .foregroundStyle(Theme.Colors.textSecondary)
                    }
                    .swipeActions {
                        Button("Delete", role: .destructive) {
                            Task { await viewModel.delete(user) }
                        }
                        Button("Reset Password") {
                            Task {
                                if let newPassword = await viewModel.resetPassword(user) {
                                    revealedPassword = RevealedPassword(username: user.username, password: newPassword)
                                }
                            }
                        }
                        .tint(Theme.Colors.warning)
                    }
                    .listRowBackground(Theme.Colors.bgPrimary)
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .background(Theme.Colors.bgPrimary)
                .refreshable { await viewModel.load() }
            }
        }
        .background(Theme.Colors.bgPrimary)
        .navigationTitle("Users")
        .task { await viewModel.load() }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { isShowingNewUser = true } label: { Image(systemName: "plus") }
                    .accessibilityLabel("Add")
            }
        }
        .sheet(isPresented: $isShowingNewUser) {
            NavigationStack {
                Form {
                    TextField("Username", text: $newUsername)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("Display Name (optional)", text: $newDisplayName)
                    Toggle("Admin", isOn: $newIsAdmin)
                        .tint(Theme.Colors.accent)
                }
                .listRowBackground(Theme.Colors.bgSecondary)
                .scrollContentBackground(.hidden)
                .background(Theme.Colors.bgPrimary)
                .navigationTitle("New User")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { isShowingNewUser = false }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Create") {
                            Task {
                                let username = newUsername
                                let displayName = newDisplayName
                                let isAdmin = newIsAdmin
                                newUsername = ""; newDisplayName = ""; newIsAdmin = false
                                isShowingNewUser = false
                                if let initialPassword = await viewModel.createUser(
                                    username: username,
                                    displayName: displayName.isEmpty ? nil : displayName,
                                    isAdmin: isAdmin
                                ) {
                                    revealedPassword = RevealedPassword(username: username, password: initialPassword)
                                }
                            }
                        }
                        .disabled(newUsername.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                }
            }
        }
        .alert(item: $revealedPassword) { revealed in
            Alert(
                title: Text(revealed.username),
                message: Text("Password: \(revealed.password)\n\nShown once — save it now."),
                dismissButton: .default(Text("OK"))
            )
        }
    }
}

private struct RevealedPassword: Identifiable {
    let username: String
    let password: String
    var id: String { username + password }
}
