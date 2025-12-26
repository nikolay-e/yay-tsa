import { test as authTest } from './auth.fixture';
import { LibraryPage } from '../pages/LibraryPage';
import { AlbumPage } from '../pages/AlbumPage';
import { PlayerBar } from '../pages/PlayerBar';

type PlaybackFixtures = {
  libraryPage: LibraryPage;
  albumPage: AlbumPage;
  playerBar: PlayerBar;
  playAlbumFromLibrary: (albumIndex?: number) => Promise<void>;
};

export const test = authTest.extend<PlaybackFixtures>({
  libraryPage: async ({ authenticatedPage }, use) => {
    const libraryPage = new LibraryPage(authenticatedPage);
    await libraryPage.goto();
    await use(libraryPage);
  },

  albumPage: async ({ authenticatedPage }, use) => {
    await use(new AlbumPage(authenticatedPage));
  },

  playerBar: async ({ authenticatedPage }, use) => {
    await use(new PlayerBar(authenticatedPage));
  },

  playAlbumFromLibrary: async ({ libraryPage, albumPage, playerBar }, use) => {
    const playAlbum = async (albumIndex: number = 0) => {
      await libraryPage.clickAlbum(albumIndex);
      await albumPage.waitForAlbumToLoad();
      await albumPage.playAlbum();
      await playerBar.waitForPlayerToLoad();
    };
    await use(playAlbum);
  },
});

export { expect } from './auth.fixture';
