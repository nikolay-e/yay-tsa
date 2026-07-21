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

  // Regression: Cloudflare Insights' beacon.min.js crashing in an old browser
  // (`t.entries.at is not a function`, every stack frame on cloudflareinsights.com)
  // was beaconed as OUR runtime error and paged ClientErrorReported in production.
  it('drops runtime errors whose stack lives entirely in a foreign origin', () => {
    const capture = captureSentBody();
    withLocationOrigin('https://app.example.com', () => {
      const handle = installClientTelemetry({ endpoint: '/v1/client-errors', appVersion: 'test' });
      const error = new Error('t.entries.at is not a function');
      error.stack =
        'TypeError: t.entries.at is not a function\n' +
        '    at https://static.cloudflareinsights.com/beacon.min.js:1:5773\n' +
        '    at https://static.cloudflareinsights.com/beacon.min.js:1:6001';
      handle.report(error, 'runtime');
    });
    expect(capture.get()).toBeUndefined();
  });

  it('keeps runtime errors with at least one same-origin stack frame', () => {
    const capture = captureSentBody();
    withLocationOrigin('https://app.example.com', () => {
      const handle = installClientTelemetry({ endpoint: '/v1/client-errors', appVersion: 'test' });
      const error = new Error('boom in our handler called from a vendor script');
      error.stack =
        'Error: boom\n' +
        '    at https://app.example.com/static/index-abc123.js:1:100\n' +
        '    at https://static.cloudflareinsights.com/beacon.min.js:1:200';
      handle.report(error, 'runtime');
    });
    expect(capture.get()).toBeDefined();
  });

  function withLocationOrigin(origin: string, run: () => void): void {
    const hadOwn = Object.getOwnPropertyDescriptor(globalThis, 'location');
    Object.defineProperty(globalThis, 'location', {
      configurable: true,
      value: { origin },
    });
    try {
      run();
    } finally {
      if (hadOwn) Object.defineProperty(globalThis, 'location', hadOwn);
      else delete (globalThis as { location?: unknown }).location;
    }
  }
});
