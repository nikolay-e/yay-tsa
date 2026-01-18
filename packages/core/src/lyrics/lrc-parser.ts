export interface LyricLine {
  time: number;
  text: string;
}

export interface ParsedLyrics {
  lines: LyricLine[];
  isTimeSynced: boolean;
}

const LRC_TIME_REGEX = /^\[(\d{2}):(\d{2})(?:\.(\d{2,3}))?\](.*)$/;

function parseTimestamp(minutes: string, seconds: string, centiseconds?: string): number {
  const mins = parseInt(minutes, 10);
  const secs = parseInt(seconds, 10);
  const cs = centiseconds ? parseInt(centiseconds.padEnd(3, '0').slice(0, 3), 10) / 1000 : 0;
  return mins * 60 + secs + cs;
}

export function parseLyrics(content: string | null | undefined): ParsedLyrics | null {
  if (!content || content.trim() === '') {
    return null;
  }

  const rawLines = content.split(/\r?\n/);
  const lines: LyricLine[] = [];
  let hasTimestamps = false;

  for (const line of rawLines) {
    const trimmed = line.trim();
    if (!trimmed) continue;

    const match = trimmed.match(LRC_TIME_REGEX);
    if (match) {
      hasTimestamps = true;
      const time = parseTimestamp(match[1], match[2], match[3]);
      const text = match[4].trim();
      if (text) {
        lines.push({ time, text });
      }
    } else if (!trimmed.startsWith('[')) {
      lines.push({ time: -1, text: trimmed });
    }
  }

  if (lines.length === 0) {
    return null;
  }

  if (hasTimestamps) {
    lines.sort((a, b) => a.time - b.time);
  }

  return {
    lines,
    isTimeSynced: hasTimestamps && lines.every(l => l.time >= 0),
  };
}

export function findActiveLineIndex(lines: LyricLine[], currentTime: number): number {
  if (lines.length === 0) return -1;

  let low = 0;
  let high = lines.length - 1;
  let result = -1;

  while (low <= high) {
    const mid = Math.floor((low + high) / 2);
    if (lines[mid].time <= currentTime) {
      result = mid;
      low = mid + 1;
    } else {
      high = mid - 1;
    }
  }

  return result;
}
