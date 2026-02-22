import { useEffect, useRef } from 'react';
import { useImageUrl } from '@/features/auth/hooks/useImageUrl';
import {
  extractAlbumPalette,
  getDefaultPalette,
  type ThemePalette,
} from '@/shared/utils/color-extraction';
import { getTrackImageUrl } from '@/shared/utils/track-image';
import { useCurrentTrack } from '../stores/player.store';

const THEME_VARS: (readonly [keyof ThemePalette, string])[] = [
  ['bgPrimary', '--color-bg-primary'],
  ['bgSecondary', '--color-bg-secondary'],
  ['bgTertiary', '--color-bg-tertiary'],
  ['bgHover', '--color-bg-hover'],
  ['accent', '--color-accent'],
  ['accentHover', '--color-accent-hover'],
  ['border', '--color-border'],
  ['textPrimary', '--color-text-primary'],
  ['textSecondary', '--color-text-secondary'],
  ['textTertiary', '--color-text-tertiary'],
  ['textOnAccent', '--color-text-on-accent'],
];

function applyPalette(palette: ThemePalette) {
  const root = document.documentElement;
  for (const [key, cssVar] of THEME_VARS) {
    root.style.setProperty(cssVar, palette[key]);
  }
}

function clearPalette() {
  const root = document.documentElement;
  for (const [, cssVar] of THEME_VARS) {
    root.style.removeProperty(cssVar);
  }
}

const COLOR_EXTRACTION_DEBOUNCE_MS = 150;

export function useAlbumColors() {
  const currentTrack = useCurrentTrack();
  const { getImageUrl } = useImageUrl();
  const lastUrlRef = useRef<string>('');
  const debounceRef = useRef<ReturnType<typeof setTimeout>>(undefined);

  useEffect(() => {
    let cancelled = false;

    if (!currentTrack) {
      clearTimeout(debounceRef.current);
      applyPalette(getDefaultPalette());
      lastUrlRef.current = '';
      return () => {
        cancelled = true;
      };
    }

    const imageUrl = getTrackImageUrl(getImageUrl, {
      albumId: currentTrack.AlbumId,
      albumPrimaryImageTag: currentTrack.AlbumPrimaryImageTag,
      trackId: currentTrack.Id,
      maxWidth: 100,
      maxHeight: 100,
    });

    if (imageUrl === lastUrlRef.current) return;

    if (imageUrl.startsWith('data:')) {
      clearTimeout(debounceRef.current);
      applyPalette(getDefaultPalette());
      lastUrlRef.current = imageUrl;
      return () => {
        cancelled = true;
      };
    }

    lastUrlRef.current = imageUrl;

    clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => {
      void extractAlbumPalette(imageUrl).then(palette => {
        if (!cancelled && lastUrlRef.current === imageUrl) {
          applyPalette(palette);
        }
      });
    }, COLOR_EXTRACTION_DEBOUNCE_MS);

    return () => {
      cancelled = true;
      clearTimeout(debounceRef.current);
    };
  }, [currentTrack?.Id, currentTrack?.AlbumId, getImageUrl]);

  useEffect(() => {
    return () => clearPalette();
  }, []);
}
