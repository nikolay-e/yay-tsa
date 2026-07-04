import { redactSecrets } from '@yay-tsa/core';
import { type ClientErrorReport, type ErrorTransport } from '../error-transport.interface.js';

// Aligned with the server's 16 KB body cap (JellyfinClientErrorsController.MAX_BODY)
// so a report the client sends is never silently dropped server-side as oversize.
const KEEPALIVE_BYTE_BUDGET = 16 * 1024;

export class BeaconErrorTransport implements ErrorTransport {
  constructor(private readonly endpoint: string) {}

  send(report: ClientErrorReport): void {
    try {
      // Redact each free-text field BEFORE stringifying, never the serialized JSON text
      // itself. A secret-shaped substring can sit right next to a quote character that
      // JSON.stringify escaped as \" (e.g. a message quoting a failed URL) -- redacting
      // the already-stringified text can consume that escaping backslash, turning the
      // escaped inner quote into a bare " that prematurely closes the JSON string and
      // corrupts everything after it. Redacting the plain field values first means the
      // regexes never see JSON syntax at all.
      const redacted: ClientErrorReport = {
        ...report,
        message: redactSecrets(report.message),
        stack: report.stack ? redactSecrets(report.stack) : report.stack,
        route: report.route ? redactSecrets(report.route) : report.route,
      };
      const body = JSON.stringify(redacted);
      if (new TextEncoder().encode(body).length > KEEPALIVE_BYTE_BUDGET) return;

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
