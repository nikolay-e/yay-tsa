import { describe, it, expect } from 'vitest';
import { parseLyrics, findActiveLineIndex, type LyricLine } from './lrc-parser.js';

describe('parseLyrics', () => {
  it('parses synced LRC with sorted timestamps', () => {
    const lrc = `[00:05.00]First line
[00:10.50]Second line
[00:02.00]Before first`;

    const result = parseLyrics(lrc);
    expect(result).not.toBeNull();
    expect(result!.isTimeSynced).toBe(true);
    expect(result!.lines).toHaveLength(3);
    expect(result!.lines[0].time).toBeCloseTo(2);
    expect(result!.lines[0].text).toBe('Before first');
    expect(result!.lines[1].time).toBeCloseTo(5);
    expect(result!.lines[2].time).toBeCloseTo(10.5);
  });

  it('parses unsynced plain text', () => {
    const text = `First line
Second line
Third line`;

    const result = parseLyrics(text);
    expect(result).not.toBeNull();
    expect(result!.isTimeSynced).toBe(false);
    expect(result!.lines).toHaveLength(3);
    expect(result!.lines.every(l => l.time === -1)).toBe(true);
  });

  it('returns null for negative cache marker', () => {
    expect(parseLyrics('[no lyrics found]')).toBeNull();
  });

  it('returns null for empty/null/undefined/whitespace', () => {
    expect(parseLyrics(null)).toBeNull();
    expect(parseLyrics(undefined)).toBeNull();
    expect(parseLyrics('')).toBeNull();
    expect(parseLyrics('   \n  \n  ')).toBeNull();
  });

  it('skips metadata lines', () => {
    const lrc = `[ar:Artist Name]
[ti:Song Title]
[00:05.00]Actual lyric`;

    const result = parseLyrics(lrc);
    expect(result).not.toBeNull();
    expect(result!.lines).toHaveLength(1);
    expect(result!.lines[0].text).toBe('Actual lyric');
  });

  it('handles multi-timestamp lines', () => {
    const lrc = '[00:05.00][00:30.00]Repeated chorus';

    const result = parseLyrics(lrc);
    expect(result).not.toBeNull();
    expect(result!.lines).toHaveLength(2);
    expect(result!.lines[0].time).toBeCloseTo(5);
    expect(result!.lines[1].time).toBeCloseTo(30);
    expect(result!.lines[0].text).toBe('Repeated chorus');
    expect(result!.lines[1].text).toBe('Repeated chorus');
  });

  it('handles mixed synced and unsynced as not fully time-synced', () => {
    const lrc = `[00:05.00]Synced line
Plain text line`;

    const result = parseLyrics(lrc);
    expect(result).not.toBeNull();
    expect(result!.isTimeSynced).toBe(false);
    expect(result!.lines).toHaveLength(2);
  });
});

describe('findActiveLineIndex', () => {
  const lines: LyricLine[] = [
    { time: 0, text: 'Start' },
    { time: 5, text: 'Five' },
    { time: 10, text: 'Ten' },
    { time: 20, text: 'Twenty' },
    { time: 30, text: 'Thirty' },
  ];

  it('finds line at middle', () => {
    expect(findActiveLineIndex(lines, 12)).toBe(2);
  });

  it('finds line at exact timestamp', () => {
    expect(findActiveLineIndex(lines, 10)).toBe(2);
  });

  it('finds first line at start', () => {
    expect(findActiveLineIndex(lines, 0)).toBe(0);
  });

  it('finds last line at end', () => {
    expect(findActiveLineIndex(lines, 999)).toBe(4);
  });

  it('returns -1 before first line', () => {
    expect(findActiveLineIndex(lines, -1)).toBe(-1);
  });

  it('returns -1 for empty array', () => {
    expect(findActiveLineIndex([], 5)).toBe(-1);
  });
});
