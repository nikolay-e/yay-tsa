import { test as authTest } from './auth.fixture';
import { LibraryPage } from '../pages/LibraryPage';
import { AlbumPage } from '../pages/AlbumPage';

type LibraryFixtures = {
  libraryPage: LibraryPage;
  albumPage: AlbumPage;
};

export const test = authTest.extend<LibraryFixtures>({
  libraryPage: async ({ authenticatedPage }, use) => {
    const libraryPage = new LibraryPage(authenticatedPage);
    await libraryPage.goto();
    await use(libraryPage);
  },

  albumPage: async ({ authenticatedPage }, use) => {
    await use(new AlbumPage(authenticatedPage));
  },
});

export { expect } from './auth.fixture';
