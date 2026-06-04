// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type MusicAlbum, type AudioItem } from '@yay-tsa/core';
import { AlbumGrid } from './AlbumGrid';
import { TrackList } from './TrackList';

// Regression guard for the rendering perf work: React.memo on AlbumCard / TrackListRow with
// comparators that intentionally ignore the inline onPlay/onPause closures. The risk is broken or
// stale UI — a memoized row firing the wrong action or showing the wrong per-row state. These tests
// drive the real components (no browser; Playwright's browser download is blocked in this sandbox)
// and assert behaviour stays correct across an "append" re-render.

const album = (id: string, name: string): MusicAlbum =>
  ({
    Id: id,
    Name: name,
    Type: 'MusicAlbum',
    Artists: ['A'],
    ArtistItems: [],
    ImageTags: {},
  }) as unknown as MusicAlbum;

const track = (id: string, name: string, fav: boolean): AudioItem =>
  ({
    Id: id,
    Name: name,
    Type: 'Audio',
    Artists: ['A'],
    ArtistItems: [],
    RunTimeTicks: 1_800_000_000,
    UserData: { IsFavorite: fav, PlaybackPositionTicks: 0, PlayCount: 0, Played: false },
  }) as unknown as AudioItem;

describe('AlbumCard memo/comparator', () => {
  it('fires the correct onPlay after an append re-render (ignored closure identity stays correct)', () => {
    const onPlay = vi.fn();
    const { rerender } = render(
      <MemoryRouter>
        <AlbumGrid albums={[album('a1', 'First'), album('a2', 'Second')]} onPlayAlbum={onPlay} />
      </MemoryRouter>
    );
    expect(screen.getAllByTestId('album-card')).toHaveLength(2);

    // Append a page: existing cards keep identity (memo path); new card is added.
    rerender(
      <MemoryRouter>
        <AlbumGrid
          albums={[album('a1', 'First'), album('a2', 'Second'), album('a3', 'Third')]}
          onPlayAlbum={onPlay}
        />
      </MemoryRouter>
    );
    expect(screen.getAllByTestId('album-card')).toHaveLength(3);

    // The appended card's play overlay must invoke onPlay with *its* album, not a stale one.
    fireEvent.click(screen.getByRole('button', { name: 'Play Third' }));
    fireEvent.click(screen.getByRole('button', { name: 'Play First' }));
    expect(onPlay).toHaveBeenNthCalledWith(1, expect.objectContaining({ Id: 'a3' }));
    expect(onPlay).toHaveBeenNthCalledWith(2, expect.objectContaining({ Id: 'a1' }));
    cleanup();
  });
});

describe('TrackListRow memo/comparator', () => {
  const wrap = (ui: React.ReactNode) => (
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>
  );

  it('keeps per-row favorite state and play index correct across an append', () => {
    const onPlayTrack = vi.fn();
    const tracks = [track('t1', 'One', true), track('t2', 'Two', false)];
    const { rerender } = render(wrap(<TrackList tracks={tracks} onPlayTrack={onPlayTrack} />));

    // Per-row favorite state comes from each row's own track prop — no cross-contamination.
    const favButtons = screen.getAllByRole('button', { name: /favorite/i });
    expect(favButtons[0]!.getAttribute('aria-pressed')).toBe('true'); // t1 favorited
    expect(favButtons[1]!.getAttribute('aria-pressed')).toBe('false'); // t2 not

    // Append a third track; existing rows keep identity.
    rerender(
      wrap(
        <TrackList tracks={[...tracks, track('t3', 'Three', false)]} onPlayTrack={onPlayTrack} />
      )
    );
    expect(screen.getAllByTestId('track-row')).toHaveLength(3);

    // Clicking the appended row plays it at the right index (closure captured the correct item).
    fireEvent.click(screen.getByText('Three'));
    expect(onPlayTrack).toHaveBeenCalledWith(expect.objectContaining({ Id: 't3' }), 2);
    cleanup();
  });
});
