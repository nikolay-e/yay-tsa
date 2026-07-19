import SwiftUI

struct LoginView: View {
    @EnvironmentObject private var session: SessionStore
    @StateObject private var viewModel: LoginViewModel

    init(session: SessionStore) {
        _viewModel = StateObject(wrappedValue: LoginViewModel(session: session))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Server") {
                    TextField("http://192.168.1.10:8080", text: $viewModel.serverAddress)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                .listRowBackground(Theme.Colors.bgSecondary)

                Section("Account") {
                    TextField("Username", text: $viewModel.username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .textContentType(.username)
                        .keyboardType(.asciiCapable)
                    SecureField("Password", text: $viewModel.password)
                        .textContentType(.password)
                        .keyboardType(.asciiCapable)
                }
                .listRowBackground(Theme.Colors.bgSecondary)

                if let errorMessage = viewModel.errorMessage {
                    Section {
                        Text(errorMessage)
                            .foregroundStyle(Theme.Colors.error)
                    }
                    .listRowBackground(Theme.Colors.bgSecondary)
                }

                Section {
                    Button {
                        Task { await viewModel.signIn() }
                    } label: {
                        if viewModel.isLoading {
                            ProgressView()
                                .frame(maxWidth: .infinity)
                        } else {
                            Text("Sign In")
                                .frame(maxWidth: .infinity)
                        }
                    }
                    .disabled(!viewModel.canSubmit)
                    .tint(Theme.Colors.accent)
                }
                .listRowBackground(Theme.Colors.bgSecondary)
            }
            .scrollContentBackground(.hidden)
            .background(Theme.Colors.bgPrimary)
            .navigationTitle("Yay-tsa")
        }
    }
}
