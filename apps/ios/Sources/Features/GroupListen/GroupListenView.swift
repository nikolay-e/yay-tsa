import SwiftUI

struct GroupListenView: View {
    @EnvironmentObject private var group: GroupSyncViewModel
    @EnvironmentObject private var session: SessionStore
    @Environment(\.dismiss) private var dismiss
    @State private var mode: Mode = .create
    @State private var name = "Listening Party"
    @State private var joinCode = ""

    private enum Mode: String, CaseIterable, Identifiable {
        case create = "Create"
        case join = "Join"
        var id: String { rawValue }
    }

    var body: some View {
        NavigationStack {
            Group {
                if let snapshot = group.snapshot {
                    activeGroupContent(snapshot)
                } else {
                    joinOrCreateContent
                }
            }
            .background(Theme.Colors.bgPrimary)
            .navigationTitle("Group Listen")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
    }

    private var joinOrCreateContent: some View {
        VStack(spacing: 20) {
            Picker("Mode", selection: $mode) {
                ForEach(Mode.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)

            if mode == .create {
                TextField("Group name", text: $name)
                    .textFieldStyle(.roundedBorder)
                    .padding(.horizontal)
                Button {
                    Task { await group.create(name: name) }
                } label: {
                    if group.isLoading { ProgressView() } else { Text("Create Group") }
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.Colors.accent)
                .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || group.isLoading)
            } else {
                TextField("Join code", text: $joinCode)
                    .textFieldStyle(.roundedBorder)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .padding(.horizontal)
                Button {
                    Task { await group.join(code: joinCode) }
                } label: {
                    if group.isLoading { ProgressView() } else { Text("Join Group") }
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.Colors.accent)
                .disabled(joinCode.trimmingCharacters(in: .whitespaces).isEmpty || group.isLoading)
            }

            if let errorMessage = group.errorMessage {
                Text(errorMessage)
                    .font(.caption)
                    .foregroundStyle(Theme.Colors.error)
                    .padding(.horizontal)
            }

            Spacer()
        }
        .padding(.top, 24)
    }

    @ViewBuilder
    private func activeGroupContent(_ snapshot: GroupSnapshot) -> some View {
        VStack(spacing: 20) {
            VStack(spacing: 6) {
                HStack(spacing: 6) {
                    Circle()
                        .fill(group.isConnected ? Theme.Colors.accent : Theme.Colors.textTertiary)
                        .frame(width: 8, height: 8)
                    Text(snapshot.name).font(.headline)
                }
                Text("Join code: \(snapshot.joinCode)")
                    .font(.title3.monospaced())
                    .foregroundStyle(Theme.Colors.accent)
            }
            .padding(.top, 12)

            if group.isOwner {
                Picker("Control", selection: Binding(
                    get: { snapshot.controlMode },
                    set: { group.setControlMode($0) }
                )) {
                    Text("Host only").tag(GroupControlMode.host)
                    Text("Everyone").tag(GroupControlMode.everyone)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)
            } else {
                Text(group.canControl ? "You can control playback" : "Only the host controls playback")
                    .font(.caption)
                    .foregroundStyle(Theme.Colors.textSecondary)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text("Members (\(snapshot.members.count))")
                    .font(.subheadline.bold())
                    .foregroundStyle(Theme.Colors.textPrimary)
                    .padding(.horizontal)
                List(snapshot.members) { member in
                    HStack {
                        Image(systemName: member.stale ? "wifi.exclamationmark" : "wifi")
                            .foregroundStyle(member.stale ? Theme.Colors.error : Theme.Colors.accent)
                            .accessibilityHidden(true)
                        Text(member.userId == session.userId ? "You" : member.deviceId)
                            .foregroundStyle(Theme.Colors.textPrimary)
                        if member.userId == snapshot.ownerId {
                            Text("Host").font(.caption).foregroundStyle(Theme.Colors.textTertiary)
                        }
                    }
                    .listRowBackground(Theme.Colors.bgSecondary)
                    .accessibilityElement(children: .combine)
                    .accessibilityValue(member.stale ? "Reconnecting" : "Connected")
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }

            Spacer()

            HStack {
                if group.isOwner {
                    Button("End Group", role: .destructive) {
                        Task { await group.end() }
                    }
                } else {
                    Button("Leave Group", role: .destructive) {
                        Task { await group.leave() }
                    }
                }
            }
            .padding(.bottom, 12)
        }
    }
}
