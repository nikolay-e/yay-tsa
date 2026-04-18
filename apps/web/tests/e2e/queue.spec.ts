import { test, expect } from './fixtures/playback.fixture';

test.describe('Queue Management', () => {
  test('should create queue from album', async ({ libraryPage, albumPage, playerBar }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();

    const trackCount = await albumPage.getTrackCount();

    const firstTrackTitle = await albumPage.getTrackTitle(0);

    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    expect(await playerBar.isVisible()).toBe(true);
    expect(trackCount).toBeGreaterThan(0);
    await expect(playerBar.currentTrackTitle).toHaveText(firstTrackTitle);
  });

  test('should advance through queue automatically', async ({
    libraryPage,
    albumPage,
    playerBar,
  }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();

    const trackCount = await albumPage.getTrackCount();
    test.skip(trackCount < 2, 'Album has fewer than 2 tracks');

    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const firstTrack = await playerBar.getCurrentTrackTitle();

    await playerBar.clickNextAndWait();

    const secondTrack = await playerBar.getCurrentTrackTitle();
    expect(secondTrack).not.toBe(firstTrack);
  });

  test('should maintain queue when navigating between pages', async ({
    libraryPage,
    albumPage,
    playerBar,
  }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const trackBefore = await playerBar.getCurrentTrackTitle();

    await libraryPage.navigateHome();
    const trackAfterNavigation = await playerBar.getCurrentTrackTitle();

    expect(trackAfterNavigation).toBe(trackBefore);
  });

  test('should skip forward and backward through queue', async ({
    libraryPage,
    albumPage,
    playerBar,
  }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();

    const trackCount = await albumPage.getTrackCount();
    test.skip(trackCount < 2, 'Album has fewer than 2 tracks');

    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    await playerBar.clickNextAndWait();
    await expect(playerBar.currentTrackTitle).toBeVisible();
    const secondTrack = await playerBar.getCurrentTrackTitle();

    await playerBar.clickNextAndWait();
    await playerBar.waitForAudioReady();
    await playerBar.pause();
    await playerBar.seek(0);

    await playerBar.clickPreviousAndWait();
    await expect(playerBar.currentTrackTitle).toBeVisible();
    const backToSecond = await playerBar.getCurrentTrackTitle();

    expect(backToSecond).toBe(secondTrack);
  });

  test('should clear queue on new album play', async ({ libraryPage, albumPage, playerBar }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const firstAlbumTrack = await playerBar.getCurrentTrackTitle();

    await albumPage.goBack();
    await libraryPage.clickAlbum(1);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForTrackChange(firstAlbumTrack);

    const secondAlbumTrack = await playerBar.getCurrentTrackTitle();
    expect(secondAlbumTrack).not.toBe(firstAlbumTrack);
  });

  test('should stop at queue end when repeat is off', async ({
    libraryPage,
    albumPage,
    playerBar,
  }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();

    const trackCount = await albumPage.getTrackCount();

    await albumPage.playTrack(trackCount - 1);
    await playerBar.waitForPlayerToLoad();
    await playerBar.waitForAudioReady();

    const lastTrackTitle = await playerBar.getCurrentTrackTitle();

    await playerBar.clickNext();

    await expect(playerBar.currentTrackTitle).toHaveText(lastTrackTitle, { timeout: 2000 });

    const currentTrackAfterNext = await playerBar.getCurrentTrackTitle();

    if (trackCount > 1) {
      expect(currentTrackAfterNext).toBe(lastTrackTitle);
    }
  });

  test('should display queue tracks on home page after starting playback', async ({
    libraryPage,
    albumPage,
    playerBar,
  }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();

    const trackCount = await albumPage.getTrackCount();
    test.skip(trackCount < 2, 'Album has fewer than 2 tracks');

    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const currentTrack = await playerBar.getCurrentTrackTitle();

    await libraryPage.navigateHome();

    await expect(async () => {
      const queueText = await libraryPage.page.locator('main').textContent();
      expect(queueText).toContain(currentTrack);
    }).toPass({ timeout: 10000 });
  });

  test('should toggle shuffle button state', async ({
    playAlbumFromLibrary,
    authenticatedPage,
  }) => {
    await playAlbumFromLibrary();

    const shuffleButton = authenticatedPage.locator(
      'button[aria-label="Shuffle"][aria-pressed]:visible'
    );
    const isVisible = await shuffleButton.isVisible().catch(() => false);
    test.skip(!isVisible, 'Shuffle button not visible at current viewport size');

    const initialPressed = await shuffleButton.getAttribute('aria-pressed');
    expect(initialPressed).toBe('false');

    await shuffleButton.click();
    await expect(shuffleButton).toHaveAttribute('aria-pressed', 'true');

    await shuffleButton.click();
    await expect(shuffleButton).toHaveAttribute('aria-pressed', 'false');
  });

  test('should cycle repeat button through modes', async ({
    playAlbumFromLibrary,
    authenticatedPage,
  }) => {
    await playAlbumFromLibrary();

    const repeatButton = authenticatedPage.getByRole('button', { name: /Repeat/ });
    const isVisible = await repeatButton.isVisible().catch(() => false);
    test.skip(!isVisible, 'Repeat button not visible at current viewport size');

    await expect(repeatButton).toHaveAttribute('aria-label', 'Repeat: off');
    await expect(repeatButton).toHaveAttribute('aria-pressed', 'false');

    await repeatButton.click();
    await expect(repeatButton).toHaveAttribute('aria-label', 'Repeat: all');
    await expect(repeatButton).toHaveAttribute('aria-pressed', 'true');

    await repeatButton.click();
    await expect(repeatButton).toHaveAttribute('aria-label', 'Repeat: one');
    await expect(repeatButton).toHaveAttribute('aria-pressed', 'true');

    await repeatButton.click();
    await expect(repeatButton).toHaveAttribute('aria-label', 'Repeat: off');
    await expect(repeatButton).toHaveAttribute('aria-pressed', 'false');
  });
});
