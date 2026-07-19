import SwiftUI

struct ChangePasswordView: View {
    let apiClient: APIClient
    let session: SessionStore

    @State private var currentPassword = ""
    @State private var newPassword = ""
    @State private var confirmPassword = ""
    @State private var isSubmitting = false
    @State private var errorMessage: String?
    @State private var didSucceed = false

    private var canSubmit: Bool {
        !currentPassword.isEmpty
            && newPassword.count >= 8
            && newPassword == confirmPassword
            && !isSubmitting
    }

    var body: some View {
        Form {
            Section {
                SecureField("Current Password", text: $currentPassword)
                    .textContentType(.password)
                    .keyboardType(.asciiCapable)
                SecureField("New Password", text: $newPassword)
                    .textContentType(.newPassword)
                    .keyboardType(.asciiCapable)
                SecureField("Confirm New Password", text: $confirmPassword)
                    .textContentType(.newPassword)
                    .keyboardType(.asciiCapable)
            } footer: {
                if !newPassword.isEmpty && newPassword.count < 8 {
                    Text("Must be at least 8 characters.")
                } else if !confirmPassword.isEmpty && newPassword != confirmPassword {
                    Text("Passwords don't match.")
                }
            }
            .listRowBackground(Theme.Colors.bgSecondary)

            if let errorMessage {
                Section {
                    Text(errorMessage).foregroundStyle(Theme.Colors.error)
                }
                .listRowBackground(Theme.Colors.bgSecondary)
            }

            Section {
                Button {
                    Task { await submit() }
                } label: {
                    if isSubmitting {
                        ProgressView()
                    } else {
                        Text("Change Password")
                    }
                }
                .disabled(!canSubmit)
                .tint(Theme.Colors.accent)
            }
            .listRowBackground(Theme.Colors.bgSecondary)
        }
        .scrollContentBackground(.hidden)
        .background(Theme.Colors.bgPrimary)
        .navigationTitle("Change Password")
        .alert("Password Changed", isPresented: $didSucceed) {
            Button("OK") {}
        }
    }

    private func submit() async {
        isSubmitting = true
        errorMessage = nil
        defer { isSubmitting = false }
        do {
            let newToken = try await apiClient.changePassword(currentPassword: currentPassword, newPassword: newPassword)
            session.updateAccessToken(newToken)
            currentPassword = ""
            newPassword = ""
            confirmPassword = ""
            didSucceed = true
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
