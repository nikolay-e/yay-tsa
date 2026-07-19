import SwiftUI

struct AudiobooksListView: View {
    @EnvironmentObject private var apiClient: APIClient
    @StateObject private var viewModel: AudiobooksViewModel

    init(apiClient: APIClient) {
        _viewModel = StateObject(wrappedValue: AudiobooksViewModel(apiClient: apiClient))
    }

    var body: some View {
        Group {
            if viewModel.isLoading && viewModel.books.isEmpty {
                ProgressView()
            } else if let errorMessage = viewModel.errorMessage {
                ContentUnavailableView(
                    "Couldn't load audiobooks",
                    systemImage: "exclamationmark.triangle",
                    description: Text(errorMessage)
                )
            } else if viewModel.books.isEmpty {
                ContentUnavailableView("No audiobooks", systemImage: "book")
            } else {
                List(viewModel.books) { book in
                    NavigationLink {
                        AudiobookDetailView(book: book)
                    } label: {
                        BookRow(book: book)
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
        .navigationTitle("Audiobooks")
        .task { await viewModel.loadIfNeeded() }
    }
}

private struct BookRow: View {
    let book: AudiobookBook

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(book.title)
                .foregroundStyle(Theme.Colors.textPrimary)
            Text(book.author)
                .font(.caption)
                .foregroundStyle(Theme.Colors.textSecondary)
            HStack {
                ProgressView(value: book.progressPercent, total: 100)
                    .tint(Theme.Colors.accent)
                Text("\(book.finishedChapters)/\(book.totalChapters)")
                    .font(.caption2)
                    .foregroundStyle(Theme.Colors.textTertiary)
            }
        }
        .padding(.vertical, 4)
    }
}
