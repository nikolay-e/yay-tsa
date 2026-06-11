// @vitest-environment jsdom
import type React from 'react';
import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type MusicAlbum, type AudioItem } from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { AlbumGrid } from './AlbumGrid';
import { TrackList } from './TrackList';

// Stub client whose getImageUrl echoes the requested maxWidth into the URL so the test can assert
// the size tier the component asked for.
const stubClient = {
  getImageUrl: (id: string, _type: string, opts?: { maxWidth?: number }) =>
    `mock://img/${id}?w=${opts?.maxWidth ?? 0}`,
};

beforeEach(() => {
  cleanup();
  useAuthStore.setState({ client: stubClient as never });
});

const album = (id: string): MusicAlbum =>
  ({
    Id: id,
    Name: id,
    Type: 'MusicAlbum',
    Artists: ['A'],
    ArtistItems: [],
    ImageTags: { Primary: `tag-${id}` },
  }) as unknown as MusicAlbum;

const track = (id: string): AudioItem =>
  ({
    Id: id,
    Name: id,
    Type: 'Audio',
    Artists: ['A'],
    ArtistItems: [],
    AlbumId: 'al',
    AlbumPrimaryImageTag: 'imgtag',
    RunTimeTicks: 1,
    UserData: { IsFavorite: false },
  }) as unknown as AudioItem;

const wrap = (ui: React.ReactNode) => (
  <QueryClientProvider client={new QueryClient()}>
    <MemoryRouter>{ui}</MemoryRouter>
  </QueryClientProvider>
);

describe('image request tiers (display-appropriate sizes, not full-res)', () => {
  it('grid cards request a 160px thumbnail (DPR 1 in jsdom), not 300/full-res', () => {
    render(wrap(<AlbumGrid albums={[album('a1'), album('a2')]} />));
    const imgs = screen.getAllByTestId('album-cover');
    expect(imgs[0]!.getAttribute('src')).toContain('w=160');
    expect(imgs[0]!.getAttribute('src')).not.toContain('w=300');
    // width/height attributes are set so there is no layout shift.
    expect(imgs[0]!.getAttribute('width')).toBe('160');
    expect(imgs[0]!.getAttribute('height')).toBe('160');
  });

  it('only the first grid card is the eager fetchpriority=high LCP image; the rest stay lazy', () => {
    render(wrap(<AlbumGrid albums={[album('a1'), album('a2')]} />));
    const imgs = screen.getAllByTestId('album-cover');
    expect(imgs[0]!.getAttribute('loading')).toBe('eager');
    expect(imgs[0]!.getAttribute('fetchpriority')).toBe('high');
    expect(imgs[1]!.getAttribute('loading')).toBe('lazy');
    expect(imgs[1]!.getAttribute('fetchpriority')).toBeNull();
  });

  it('track rows request a 48px thumbnail, never 300/full-res', () => {
    render(wrap(<TrackList tracks={[track('t1')]} />));
    const src = document.querySelector('img')?.getAttribute('src') ?? '';
    expect(src).toContain('w=48');
  });
});
