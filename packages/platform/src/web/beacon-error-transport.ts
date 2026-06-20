import { redactSecrets } from '@yay-tsa/core';
import { type ClientErrorReport, type ErrorTransport } from '../error-transport.interface.js';

const KEEPALIVE_BYTE_BUDGET = 60 * 1024;

export class BeaconErrorTransport implements ErrorTransport {
  constructor(private readonly endpoint: string) {}

  send(report: ClientErrorReport): void {
    try {
      const body = redactSecrets(JSON.stringify(report));
      if (body.length > KEEPALIVE_BYTE_BUDGET) return;

      const nav = globalThis.navigator;
      if (nav && typeof nav.sendBeacon === 'function') {
        const queued = nav.sendBeacon(this.endpoint, body);
        if (queued) return;
      }

      void globalThis
        .fetch?.(this.endpoint, {
          method: 'POST',
          body,
          keepalive: true,
          credentials: 'same-origin',
        })
        .catch(() => {});
    } catch {
      /* telemetry transport must never throw */
    }
  }
}
