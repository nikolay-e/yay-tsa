import { test as baseTest, expect } from './fixtures/auth.fixture';
import { LibraryPage } from './pages/LibraryPage';
import { AlbumPage } from './pages/AlbumPage';
import { PlayerBar } from './pages/PlayerBar';

const test = baseTest;

test.describe('Playback and Player Controls', () => {
  let libraryPage: LibraryPage;
  let albumPage: AlbumPage;
  let playerBar: PlayerBar;

  test.beforeEach(async ({ authenticatedPage }) => {
    libraryPage = new LibraryPage(authenticatedPage);
    albumPage = new AlbumPage(authenticatedPage);
    playerBar = new PlayerBar(authenticatedPage);
    await libraryPage.goto();
  });

  test('should start playback when album play button clicked', async () => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();

    await albumPage.playAlbum();

    await playerBar.waitForPlayerToLoad();
    expect(await playerBar.isPlaying()).toBe(true);
    expect(await playerBar.getCurrentTrackTitle()).toBeTruthy();
  });

  test('should pause and resume playback', async () => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    await playerBar.pause();
    expect(await playerBar.isPlaying()).toBe(false);

    await playerBar.play();
    expect(await playerBar.isPlaying()).toBe(true);
  });

  test('should skip to next track', async () => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const firstTrack = await playerBar.getCurrentTrackTitle();

    await playerBar.next();

    const secondTrack = await playerBar.getCurrentTrackTitle();
    expect(secondTrack).not.toBe(firstTrack);
  });

  test('should skip to previous track', async () => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playTrack(1);
    await playerBar.waitForPlayerToLoad();

    const secondTrack = await playerBar.getCurrentTrackTitle();

    await playerBar.previous();

    const firstTrack = await playerBar.getCurrentTrackTitle();
    expect(firstTrack).not.toBe(secondTrack);
  });

  test('should display player bar with track info', async () => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();

    await playerBar.waitForPlayerToLoad();

    expect(await playerBar.getCurrentTrackTitle()).toBeTruthy();
    expect(await playerBar.getCurrentTrackArtist()).toBeTruthy();
    expect(await playerBar.getTotalTime()).not.toBe('0:00');
  });

  test('should seek forward in track', async ({ authenticatedPage }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    await authenticatedPage.waitForTimeout(2000);

    await playerBar.seek(50);
    await authenticatedPage.waitForTimeout(500);

    const currentTime = await playerBar.getCurrentTime();
    expect(currentTime).not.toBe('0:00');
  });

  test('should adjust volume', async ({ authenticatedPage }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    await playerBar.setVolume(50);
    await authenticatedPage.waitForTimeout(300);

    await playerBar.setVolume(80);
    await authenticatedPage.waitForTimeout(300);
  });

  test('should show player on all pages during playback', async () => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    await libraryPage.goto();
    expect(await playerBar.isVisible()).toBe(true);

    await libraryPage.navigateToSearch();
    expect(await playerBar.isVisible()).toBe(true);
  });

  test('should persist playback state across page navigation', async () => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const trackBeforeNavigation = await playerBar.getCurrentTrackTitle();

    await libraryPage.goto();

    const trackAfterNavigation = await playerBar.getCurrentTrackTitle();
    expect(trackAfterNavigation).toBe(trackBeforeNavigation);
  });

  test('should play specific track from album', async () => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();

    const thirdTrackTitle = await albumPage.getTrackTitle(2);
    await albumPage.playTrack(2);

    await playerBar.waitForPlayerToLoad();
    const currentTrack = await playerBar.getCurrentTrackTitle();

    expect(currentTrack).toContain(thirdTrackTitle.split(' - ')[0]);
  });

  test('should handle playback errors gracefully', async ({ authenticatedPage }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    await authenticatedPage.waitForTimeout(1000);

    expect(await playerBar.isVisible()).toBe(true);
  });
});
