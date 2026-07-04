import { describe, it, expect, afterEach } from 'vitest';
import { installClientTelemetry } from './client-telemetry';

describe('client-telemetry send()', () => {
  afterEach(() => {
    vi_restoreSendBeacon();
  });

  function captureSentBody(): { get: () => string | undefined } {
    let sent: string | undefined;
    const original = globalThis.navigator?.sendBeacon;
    Object.defineProperty(globalThis.navigator, 'sendBeacon', {
      configurable: true,
      value: (_url: string, body: string) => {
        sent = body;
        return true;
      },
    });
    (globalThis as unknown as { __restoreSendBeacon?: () => void }).__restoreSendBeacon = () => {
      if (original) {
        Object.defineProperty(globalThis.navigator, 'sendBeacon', {
          configurable: true,
          value: original,
        });
      }
    };
    return { get: () => sent };
  }

  function vi_restoreSendBeacon(): void {
    (globalThis as unknown as { __restoreSendBeacon?: () => void }).__restoreSendBeacon?.();
  }

  // Regression: a message quoting a failed URL that itself contains a secret-shaped
  // substring (e.g. `url="https://x.com?token=abc123"`) used to corrupt the JSON body.
  // JSON.stringify escapes the embedded quote as \", and the old code ran redaction
  // AFTER stringifying -- the token= regex stopped right at that escaped quote and
  // consumed the escaping backslash, turning \" into a bare " that prematurely closed
  // the JSON string. The server logged 63 unparsable "malformed" reports over 7 days
  // in production before this was found and fixed.
  it('produces valid, parseable JSON when the message contains an embedded quoted secret', () => {
    const capture = captureSentBody();
    const handle = installClientTelemetry({ endpoint: '/v1/client-errors', appVersion: 'test' });

    // A literal trailing quote right after the redacted [REDACTED] marker: the first,
    // safe per-field scrub replaces `token=abc123xyz` but leaves the quote character
    // untouched (it's not part of the match). JSON.stringify then escapes that quote
    // as \" -- and it's specifically that escaped quote a second, post-stringify scrub
    // pass can corrupt.
    handle.report(new Error('Auth failed token=abc123xyz"'), 'network');

    const body = capture.get();
    expect(body).toBeDefined();
    // The real assertion: JSON.parse must not throw. Before the fix, redacting the
    // already-serialized JSON text stripped the backslash escaping the embedded quote,
    // corrupting everything after it and producing an unparsable body server-side.
    const parsed = JSON.parse(body!);
    expect(parsed.message).not.toContain('abc123xyz');
  });
});
