import { test as baseTest, expect } from './fixtures/auth.fixture';
import { LibraryPage } from './pages/LibraryPage';
import { AlbumPage } from './pages/AlbumPage';
import { PlayerBar } from './pages/PlayerBar';

const test = baseTest;

test.describe('Queue Management', () => {
  let libraryPage: LibraryPage;
  let albumPage: AlbumPage;
  let playerBar: PlayerBar;

  test.beforeEach(async ({ authenticatedPage }) => {
    libraryPage = new LibraryPage(authenticatedPage);
    albumPage = new AlbumPage(authenticatedPage);
    playerBar = new PlayerBar(authenticatedPage);
    await libraryPage.goto();
  });

  test('should create queue from album', async () => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();

    const trackCount = await albumPage.getTrackCount();

    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    expect(await playerBar.isVisible()).toBe(true);
    expect(trackCount).toBeGreaterThan(0);
  });

  test('should advance through queue automatically', async ({ authenticatedPage }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const firstTrack = await playerBar.getCurrentTrackTitle();

    await playerBar.next();
    await authenticatedPage.waitForTimeout(500);

    const secondTrack = await playerBar.getCurrentTrackTitle();
    expect(secondTrack).not.toBe(firstTrack);
  });

  test('should toggle shuffle mode', async ({ authenticatedPage }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const shuffleOffInitially = await playerBar.isShuffleOn();

    await playerBar.toggleShuffle();
    await authenticatedPage.waitForTimeout(300);

    const shuffleOnAfterToggle = await playerBar.isShuffleOn();
    expect(shuffleOnAfterToggle).not.toBe(shuffleOffInitially);
  });

  test('should cycle through repeat modes', async ({ authenticatedPage }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const initialMode = await playerBar.getRepeatMode();

    await playerBar.toggleRepeat();
    await authenticatedPage.waitForTimeout(300);
    const secondMode = await playerBar.getRepeatMode();
    expect(secondMode).not.toBe(initialMode);

    await playerBar.toggleRepeat();
    await authenticatedPage.waitForTimeout(300);
    const thirdMode = await playerBar.getRepeatMode();
    expect(thirdMode).not.toBe(secondMode);

    await playerBar.toggleRepeat();
    await authenticatedPage.waitForTimeout(300);
    const fourthMode = await playerBar.getRepeatMode();
    expect(fourthMode).toBe(initialMode);
  });

  test('should shuffle album on shuffle button click', async () => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();

    await albumPage.shuffleAlbum();
    await playerBar.waitForPlayerToLoad();

    const isShuffled = await playerBar.isShuffleOn();
    expect(isShuffled).toBe(true);
  });

  test('should maintain queue when navigating between pages', async () => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const trackBefore = await playerBar.getCurrentTrackTitle();

    await libraryPage.goto();
    const trackAfterNavigation = await playerBar.getCurrentTrackTitle();

    expect(trackAfterNavigation).toBe(trackBefore);
  });

  test('should skip forward and backward through queue', async ({ authenticatedPage }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    await playerBar.next();
    await authenticatedPage.waitForTimeout(500);
    const secondTrack = await playerBar.getCurrentTrackTitle();

    await playerBar.next();
    await authenticatedPage.waitForTimeout(500);

    await playerBar.previous();
    await authenticatedPage.waitForTimeout(500);
    const backToSecond = await playerBar.getCurrentTrackTitle();

    expect(backToSecond).toBe(secondTrack);
  });

  test('should clear queue on new album play', async () => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const firstAlbumTrack = await playerBar.getCurrentTrackTitle();

    await albumPage.goBack();
    await libraryPage.clickAlbum(1);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const secondAlbumTrack = await playerBar.getCurrentTrackTitle();
    expect(secondAlbumTrack).not.toBe(firstAlbumTrack);
  });

  test('should handle queue end without repeat', async ({ authenticatedPage }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();

    const trackCount = await albumPage.getTrackCount();

    await albumPage.playTrack(trackCount - 1);
    await playerBar.waitForPlayerToLoad();

    await authenticatedPage.waitForTimeout(1000);
  });

  test('should loop queue with repeat all', async ({ authenticatedPage }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    await playerBar.toggleRepeat();
    await authenticatedPage.waitForTimeout(300);

    const repeatMode = await playerBar.getRepeatMode();
    if (repeatMode === 'off') {
      await playerBar.toggleRepeat();
      await authenticatedPage.waitForTimeout(300);
    }

    const trackCount = await albumPage.getTrackCount();
    for (let i = 0; i < trackCount; i++) {
      await playerBar.next();
      await authenticatedPage.waitForTimeout(500);
    }

    expect(await playerBar.isVisible()).toBe(true);
  });

  test('should repeat one track with repeat one', async ({ authenticatedPage }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const initialTrack = await playerBar.getCurrentTrackTitle();

    await playerBar.toggleRepeat();
    await authenticatedPage.waitForTimeout(300);
    await playerBar.toggleRepeat();
    await authenticatedPage.waitForTimeout(300);

    const repeatMode = await playerBar.getRepeatMode();
    if (repeatMode === 'one') {
      await playerBar.next();
      await authenticatedPage.waitForTimeout(500);

      const trackAfterNext = await playerBar.getCurrentTrackTitle();
      expect(trackAfterNext).toBe(initialTrack);
    }
  });
});
