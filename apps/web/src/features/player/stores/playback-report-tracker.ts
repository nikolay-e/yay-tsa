import { log } from '@/shared/utils/logger';

// Playback-position reporting (start/progress/stopped) is fire-and-forget best-effort: a single
// failed report is a transient network blip, not a bug, so it logs at debug (never spams telemetry).
// But a SUSTAINED run of failures across these calls means the report pipeline itself is down (bad
// token, backend outage) and must surface — mirrors useDeviceHeartbeat's consecutive-failure escalator
// so this failure class isn't a permanent blind spot in client-error telemetry. Shared across all
// report call sites (start/progress/stopped) since they hit the same endpoint family and root cause.
const MAX_CONSECUTIVE_REPORT_FAILURES = 3;

let consecutivePlaybackReportFailures = 0;

export function recordPlaybackReportOutcome(succeeded: boolean, context: string): void {
  if (succeeded) {
    consecutivePlaybackReportFailures = 0;
    return;
  }
  consecutivePlaybackReportFailures++;
  if (consecutivePlaybackReportFailures === MAX_CONSECUTIVE_REPORT_FAILURES) {
    log.player.warn('Playback reporting failing repeatedly', {
      context,
      consecutiveFailures: consecutivePlaybackReportFailures,
    });
  }
}
