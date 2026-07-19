import SwiftUI

struct DjPreferencesView: View {
    @StateObject private var viewModel: DjPreferencesViewModel

    init(apiClient: APIClient) {
        _viewModel = StateObject(wrappedValue: DjPreferencesViewModel(apiClient: apiClient))
    }

    var body: some View {
        List {
            Section("Hard Rules") {
                Stepper(
                    "Max consecutive from one artist: \(viewModel.maxArtistConsecutive)",
                    value: $viewModel.maxArtistConsecutive,
                    in: 1...10
                )
                .onChange(of: viewModel.maxArtistConsecutive) { _, _ in
                    Task { await viewModel.save() }
                }

                Stepper(
                    "No repeats within: \(viewModel.noRepeatHours)h",
                    value: $viewModel.noRepeatHours,
                    in: 0...48
                )
                .onChange(of: viewModel.noRepeatHours) { _, _ in
                    Task { await viewModel.save() }
                }
            }
            .listRowBackground(Theme.Colors.bgSecondary)

            Section {
                VStack(alignment: .leading, spacing: 4) {
                    Text(discoveryLabel)
                        .font(.subheadline)
                        .foregroundStyle(Theme.Colors.textSecondary)
                    Slider(
                        value: $viewModel.discoveryPct,
                        in: 0...100,
                        step: 1,
                        onEditingChanged: { editing in
                            if !editing { Task { await viewModel.save() } }
                        }
                    )
                }
            } header: {
                Text("Discovery")
            } footer: {
                Text("How often the DJ picks unfamiliar tracks vs. your established favorites.")
            }
            .listRowBackground(Theme.Colors.bgSecondary)

            Section {
                ForEach(viewModel.redLines, id: \.self) { line in
                    Text(line).foregroundStyle(Theme.Colors.textPrimary)
                }
                .onDelete { viewModel.removeRedLines(at: $0) }

                HStack {
                    TextField("Artist, genre, or keyword to avoid", text: $viewModel.newRedLine)
                        .onSubmit { viewModel.addRedLine() }
                    Button("Add") { viewModel.addRedLine() }
                        .disabled(viewModel.newRedLine.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            } header: {
                Text("Red Lines")
            } footer: {
                Text("The DJ will never queue anything matching these.")
            }
            .listRowBackground(Theme.Colors.bgSecondary)

            if let errorMessage = viewModel.errorMessage {
                Section {
                    Text(errorMessage).foregroundStyle(Theme.Colors.error)
                }
                .listRowBackground(Theme.Colors.bgSecondary)
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.Colors.bgPrimary)
        .navigationTitle("DJ Preferences")
        .toolbar {
            if viewModel.isSaving {
                ToolbarItem(placement: .topBarTrailing) {
                    ProgressView()
                }
            }
        }
        .task { await viewModel.loadIfNeeded() }
    }

    private var discoveryLabel: String {
        switch viewModel.discoveryPct {
        case ..<20: return "Conservative"
        case ..<50: return "Balanced"
        case ..<80: return "Adventurous"
        default: return "Full discovery"
        }
    }
}
