import type { MediaPlaybackError } from '@yay-tsa/platform';

export const UNSUPPORTED_FORMAT_MESSAGE = 'This track format is not supported on this device';

export class UnsupportedFormatError extends Error implements MediaPlaybackError {
  constructor(public mediaErrorCode: number | null) {
    super(UNSUPPORTED_FORMAT_MESSAGE);
    this.name = 'UnsupportedFormatError';
  }
}
