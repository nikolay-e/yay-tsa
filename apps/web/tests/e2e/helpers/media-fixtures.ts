// Minimal valid 8-bit mono PCM WAV (~3s of silence) — small but decodable, so Chromium can
// actually load it, seek within it, and advance currentTime in mocked-backend E2E suites.
export function buildWav(): Buffer {
  const sampleRate = 8000;
  const seconds = 3;
  const numSamples = sampleRate * seconds;
  const header = Buffer.alloc(44);
  header.write('RIFF', 0);
  header.writeUInt32LE(36 + numSamples, 4);
  header.write('WAVE', 8);
  header.write('fmt ', 12);
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20);
  header.writeUInt16LE(1, 22);
  header.writeUInt32LE(sampleRate, 24);
  header.writeUInt32LE(sampleRate, 28);
  header.writeUInt16LE(1, 32);
  header.writeUInt16LE(8, 34);
  header.write('data', 36);
  header.writeUInt32LE(numSamples, 40);
  const data = Buffer.alloc(numSamples, 128);
  return Buffer.concat([header, data]);
}

export const TRANSPARENT_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
  'base64'
);
