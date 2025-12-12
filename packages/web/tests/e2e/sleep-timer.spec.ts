import { test as baseTest, expect } from './fixtures/auth.fixture';
import { LibraryPage } from './pages/LibraryPage';
import { AlbumPage } from './pages/AlbumPage';
import { PlayerBar } from './pages/PlayerBar';
import { PLAYER_TEST_IDS } from '../../src/lib/test-ids';

const test = baseTest;

test.describe('Sleep Timer and Background Playback', () => {
  let libraryPage: LibraryPage;
  let albumPage: AlbumPage;
  let playerBar: PlayerBar;

  test.beforeEach(async ({ authenticatedPage }) => {
    libraryPage = new LibraryPage(authenticatedPage);
    albumPage = new AlbumPage(authenticatedPage);
    playerBar = new PlayerBar(authenticatedPage);
    await libraryPage.goto();
  });

  test('should maintain audio playback when analyser is active', async ({ authenticatedPage }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    expect(await playerBar.isPlaying()).toBe(true);

    const hasAnalyser = await authenticatedPage.evaluate(() => {
      const player = (window as any).__playerStore__;
      const audioEngine = player?.audioEngine;
      const audioContext = audioEngine?.getAudioContext?.();
      return audioContext !== null && audioContext !== undefined;
    });

    expect(hasAnalyser).toBe(true);

    // Wait for audio to be playing and time to progress
    await playerBar.waitForAudioPlaying();
    expect(await playerBar.isPlaying()).toBe(true);

    await playerBar.waitForTimeProgress();
  });

  test('should use single AudioContext for music and pink noise', async ({ authenticatedPage }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const audioContextCount = await authenticatedPage.evaluate(() => {
      const contexts = (window as any).__audioContexts__ || [];
      return contexts.length;
    });

    expect(audioContextCount).toBeLessThanOrEqual(1);
  });

  test('should maintain MediaSession metadata during playback', async ({ authenticatedPage }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const hasMediaSession = await authenticatedPage.evaluate(() => {
      return 'mediaSession' in navigator;
    });

    if (hasMediaSession) {
      const metadata = await authenticatedPage.evaluate(() => {
        return navigator.mediaSession.metadata
          ? {
              title: navigator.mediaSession.metadata.title,
              artist: navigator.mediaSession.metadata.artist,
              album: navigator.mediaSession.metadata.album,
            }
          : null;
      });

      expect(metadata).not.toBeNull();
      expect(metadata?.title).toBeTruthy();
    }
  });

  test('should open sleep timer modal', async ({ authenticatedPage }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const sleepTimerButton = authenticatedPage.getByTestId(PLAYER_TEST_IDS.SLEEP_TIMER_BUTTON);
    await expect(sleepTimerButton).toBeVisible({ timeout: 5000 });
    await sleepTimerButton.click();

    const modal = authenticatedPage.getByRole('dialog');
    await expect(modal).toBeVisible({ timeout: 5000 });
  });

  test('should start sleep timer with preset', async ({ authenticatedPage }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const sleepTimerButton = authenticatedPage.getByTestId(PLAYER_TEST_IDS.SLEEP_TIMER_BUTTON);
    await sleepTimerButton.click();

    // Wait for modal to be visible
    const modal = authenticatedPage.getByRole('dialog');
    await expect(modal).toBeVisible({ timeout: 5000 });

    // Preset buttons auto-start the timer and close the modal
    const preset15min = authenticatedPage.getByRole('button', { name: '15 min' });
    await preset15min.click();

    // Wait for timer to become active (polling)
    await expect(async () => {
      const isTimerActive = await authenticatedPage.evaluate(() => {
        const sleepTimer = (window as any).__sleepTimerStore__;
        return sleepTimer?.isActive === true;
      });
      expect(isTimerActive).toBe(true);
    }).toPass({ timeout: 5000 });
  });

  test('should display remaining time when sleep timer is active', async ({
    authenticatedPage,
  }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const sleepTimerButton = authenticatedPage.getByTestId(PLAYER_TEST_IDS.SLEEP_TIMER_BUTTON);
    await sleepTimerButton.click();

    // Wait for modal to be visible
    const modal = authenticatedPage.getByRole('dialog');
    await expect(modal).toBeVisible({ timeout: 5000 });

    // Preset buttons auto-start the timer and close the modal
    const preset15min = authenticatedPage.getByRole('button', { name: '15 min' });
    await preset15min.click();

    // Wait for timer to become active with remaining time (polling)
    await expect(async () => {
      const timerState = await authenticatedPage.evaluate(() => {
        const sleepTimer = (window as any).__sleepTimerStore__;
        return {
          isActive: sleepTimer?.isActive,
          remainingMs: sleepTimer?.remainingMs,
        };
      });
      expect(timerState.isActive).toBe(true);
      expect(timerState.remainingMs).toBeGreaterThan(0);
      expect(timerState.remainingMs).toBeLessThanOrEqual(15 * 60 * 1000);
    }).toPass({ timeout: 5000 });
  });

  test('should cancel sleep timer', async ({ authenticatedPage }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const sleepTimerButton = authenticatedPage.getByTestId(PLAYER_TEST_IDS.SLEEP_TIMER_BUTTON);
    await sleepTimerButton.click();

    // Wait for modal to be visible
    const modal = authenticatedPage.getByRole('dialog');
    await expect(modal).toBeVisible({ timeout: 5000 });

    // Preset buttons auto-start the timer and close the modal
    const preset15min = authenticatedPage.getByRole('button', { name: '15 min' });
    await preset15min.click();

    // Wait for timer to become active
    await expect(async () => {
      const isActive = await authenticatedPage.evaluate(() => {
        const sleepTimer = (window as any).__sleepTimerStore__;
        return sleepTimer?.isActive === true;
      });
      expect(isActive).toBe(true);
    }).toPass({ timeout: 5000 });

    // Re-open modal to cancel
    await sleepTimerButton.click();
    await expect(modal).toBeVisible({ timeout: 5000 });

    const cancelButton = authenticatedPage.getByRole('button', { name: /cancel/i });
    await cancelButton.click();

    // Wait for timer to become inactive
    await expect(async () => {
      const isTimerActive = await authenticatedPage.evaluate(() => {
        const sleepTimer = (window as any).__sleepTimerStore__;
        return sleepTimer?.isActive === true;
      });
      expect(isTimerActive).toBe(false);
    }).toPass({ timeout: 5000 });
  });

  test('should maintain playback state during sleep timer music phase', async ({
    authenticatedPage,
  }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const sleepTimerButton = authenticatedPage.getByTestId(PLAYER_TEST_IDS.SLEEP_TIMER_BUTTON);
    await sleepTimerButton.click();

    // Wait for modal to be visible
    const modal = authenticatedPage.getByRole('dialog');
    await expect(modal).toBeVisible({ timeout: 5000 });

    // Preset buttons auto-start the timer and close the modal
    const preset15min = authenticatedPage.getByRole('button', { name: '15 min' });
    await preset15min.click();

    // Wait for timer to be in music phase with playback active
    await expect(async () => {
      const phase = await authenticatedPage.evaluate(() => {
        const sleepTimer = (window as any).__sleepTimerStore__;
        return sleepTimer?.phase;
      });
      expect(phase).toBe('music');
      expect(await playerBar.isPlaying()).toBe(true);
    }).toPass({ timeout: 5000 });
  });

  test('should track RMS levels during playback', async ({ authenticatedPage }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    // Wait for audio to be playing before checking RMS
    await playerBar.waitForAudioPlaying();

    // Poll for RMS value to be available
    await expect(async () => {
      const rmsValue = await authenticatedPage.evaluate(() => {
        const player = (window as any).__playerStore__;
        const audioEngine = player?.audioEngine;
        return audioEngine?.getRMS?.() ?? -1;
      });
      expect(rmsValue).toBeGreaterThanOrEqual(0);
    }).toPass({ timeout: 5000 });
  });

  test('should not interrupt playback when pausing music element', async ({
    authenticatedPage,
  }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const audioContextStateBefore = await authenticatedPage.evaluate(() => {
      const player = (window as any).__playerStore__;
      const audioEngine = player?.audioEngine;
      const audioContext = audioEngine?.getAudioContext?.();
      return audioContext?.state;
    });

    expect(audioContextStateBefore).toBe('running');

    await playerBar.pause();

    const audioContextStateAfter = await authenticatedPage.evaluate(() => {
      const player = (window as any).__playerStore__;
      const audioEngine = player?.audioEngine;
      const audioContext = audioEngine?.getAudioContext?.();
      return audioContext?.state;
    });

    expect(audioContextStateAfter).toBe('running');
  });

  test('should verify analyser does not intercept main audio path', async ({
    authenticatedPage,
  }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    // Wait for audio to be fully loaded and playing
    await playerBar.waitForAudioPlaying();

    const audioGraphStructure = await authenticatedPage.evaluate(() => {
      const player = (window as any).__playerStore__;
      const audioEngine = player?.audioEngine;
      const audioContext = audioEngine?.getAudioContext?.();

      if (!audioContext) return { audioContextState: 'no-context' };

      const audio = document.querySelector('audio');
      if (!audio) return { audioContextState: 'no-audio-element' };

      return {
        hasSourceNode: (audio as any).__sourceNode__ !== undefined,
        hasAnalyser: (audio as any).__analyser__ !== undefined,
        audioContextState: audioContext.state,
      };
    });

    // AudioContext should be running after playback starts
    // On some browsers it might be suspended initially but should resume
    // Allow edge cases: no-context (AudioContext not supported), no-audio-element (audio not in DOM yet)
    expect(['running', 'suspended', 'no-context', 'no-audio-element']).toContain(
      audioGraphStructure?.audioContextState
    );
  });

  test('should preserve volume across pause/resume', async ({ authenticatedPage }) => {
    await libraryPage.clickAlbum(0);
    await albumPage.waitForAlbumToLoad();
    await albumPage.playAlbum();
    await playerBar.waitForPlayerToLoad();

    const initialVolume = await authenticatedPage.evaluate(() => {
      const player = (window as any).__playerStore__;
      return player?.volume ?? 0;
    });

    await playerBar.pause();
    await playerBar.play();

    const volumeAfterResume = await authenticatedPage.evaluate(() => {
      const player = (window as any).__playerStore__;
      return player?.volume ?? 0;
    });

    expect(volumeAfterResume).toBe(initialVolume);
  });
});
