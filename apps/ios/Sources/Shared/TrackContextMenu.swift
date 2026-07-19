import SwiftUI

/// Standard track-row context menu (long-press) shared across every list that shows
/// playable tracks — matches modern player conventions (Play Next / Add to Queue)
/// plus this app's own Start Radio entry point.
struct TrackContextMenu: View {
    @EnvironmentObject private var player: PlayerViewModel
    @EnvironmentObject private var radio: RadioViewModel
    let item: BaseItem

    var body: some View {
        Button {
            player.playNext(item)
        } label: {
            Label("Play Next", systemImage: "text.insert")
        }
        Button {
            player.addToQueue(item)
        } label: {
            Label("Add to Queue", systemImage: "text.append")
        }
        Button {
            Task { await radio.start(seedTrackId: item.id) }
        } label: {
            Label("Start Radio", systemImage: "antenna.radiowaves.left.and.right")
        }
    }
}
