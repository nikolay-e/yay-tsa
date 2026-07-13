import { log } from '@/shared/utils/logger';

const DEGRADED_STREAM_THRESHOLD = 5;
const RECONNECT_BASE_DELAY_MS = 2000;
const RECONNECT_MAX_DELAY_MS = 30000;

export interface ReconnectingEventSourceOptions {
  url: string;
  stream: string;
  listeners: Record<string, (event: MessageEvent) => void>;
  onReconnect?: () => void;
}

export function openReconnectingEventSource({
  url,
  stream,
  listeners,
  onReconnect,
}: ReconnectingEventSourceOptions): () => void {
  let closed = false;
  let source: EventSource | null = null;
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  let reconnectAttempts = 0;
  let consecutiveFailures = 0;
  let degradedReported = false;
  let sawOpen = false;

  const connect = () => {
    if (closed) return;
    source = new EventSource(url);

    for (const [eventName, handler] of Object.entries(listeners)) {
      source.addEventListener(eventName, handler);
    }

    source.onerror = () => {
      source?.close();
      source = null;
      if (closed) return;
      reconnectAttempts++;
      consecutiveFailures++;
      if (consecutiveFailures >= DEGRADED_STREAM_THRESHOLD && !degradedReported) {
        degradedReported = true;
        log.player.warn('Device event stream degraded', {
          stream,
          attempts: consecutiveFailures,
        });
      }
      const delay = Math.min(RECONNECT_BASE_DELAY_MS * reconnectAttempts, RECONNECT_MAX_DELAY_MS);
      reconnectTimer = setTimeout(connect, delay);
    };

    source.onopen = () => {
      reconnectAttempts = 0;
      consecutiveFailures = 0;
      degradedReported = false;
      // An event lost while the stream was down would leave stale state; resync from the
      // authoritative snapshot on every reconnect (not the first open).
      if (sawOpen) onReconnect?.();
      sawOpen = true;
    };
  };

  connect();

  return () => {
    closed = true;
    if (reconnectTimer) clearTimeout(reconnectTimer);
    source?.close();
  };
}
