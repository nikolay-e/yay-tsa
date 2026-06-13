import { createLogger } from '@yay-tsa/core';

export const log = {
  auth: createLogger('auth'),
  player: createLogger('player'),
  api: createLogger('api'),
  app: createLogger('app'),
};
