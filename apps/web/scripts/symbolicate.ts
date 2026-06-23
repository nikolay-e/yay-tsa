import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import sourceMap from 'source-map';

const { SourceMapConsumer } = sourceMap;

const __dirname = path.dirname(fileURLToPath(import.meta.url));

interface ParsedFrame {
  file: string;
  line: number;
  column: number;
}

function usage(): never {
  process.stderr.write(
    [
      'Resolve a minified production stack frame to its original source location.',
      '',
      'Usage:',
      '  node --experimental-strip-types apps/web/scripts/symbolicate.ts \\',
      '    --version <main-sha> <file:line:col> [<file:line:col> ...]',
      '',
      'Arguments:',
      '  --version <id>   GIT_SHA / appVersion the report was emitted under (e.g. main-1a2b3c4).',
      '                   Selects which hidden-sourcemap artifact to resolve against.',
      '  --maps-dir <p>   Override the sourcemap artifact directory',
      '                   (default: apps/web/dist-maps, or $SOURCEMAPS_DIR).',
      '  <file:line:col>  A minified location straight from a Loki client_error log line,',
      '                   e.g. assets/index-Co1OT0gP.js:1:88213',
      '',
      'The maps live OUTSIDE the served bundle (Dockerfile builder stage moves',
      'dist/**/*.map into dist-maps/). Pull the dist-maps artifact for the matching',
      'version, then run this offline. Nothing is uploaded or fetched.',
    ].join('\n') + '\n'
  );
  process.exit(2);
}

function parseFrame(raw: string): ParsedFrame {
  const match = /^(.*):(\d+):(\d+)$/.exec(raw.trim());
  if (!match) {
    process.stderr.write(`Cannot parse "${raw}" — expected file:line:col\n`);
    process.exit(2);
  }
  return { file: match[1], line: Number(match[2]), column: Number(match[3]) };
}

function resolveMapPath(mapsDir: string, version: string, file: string): string | null {
  const candidates = [
    path.join(mapsDir, version, `${file}.map`),
    path.join(mapsDir, `${file}.map`),
    path.join(mapsDir, version, file.split('/').pop()! + '.map'),
    path.join(mapsDir, file.split('/').pop()! + '.map'),
  ];
  return candidates.find(existsSync) ?? null;
}

async function symbolicate(frame: ParsedFrame, mapsDir: string, version: string): Promise<string> {
  const mapPath = resolveMapPath(mapsDir, version, frame.file);
  if (!mapPath) {
    return `${frame.file}:${frame.line}:${frame.column}  ->  [no sourcemap found for version ${version}]`;
  }
  const rawMap = await readFile(mapPath, 'utf8');
  const consumer = await Promise.resolve(
    new SourceMapConsumer(JSON.parse(rawMap) as sourceMap.RawSourceMap)
  );
  try {
    const original = consumer.originalPositionFor({ line: frame.line, column: frame.column });
    if (original.source == null) {
      return `${frame.file}:${frame.line}:${frame.column}  ->  [unmapped]`;
    }
    const name = original.name ? ` (${original.name})` : '';
    return `${frame.file}:${frame.line}:${frame.column}  ->  ${original.source}:${original.line}:${original.column}${name}`;
  } finally {
    if (typeof (consumer as { destroy?: () => void }).destroy === 'function') {
      (consumer as { destroy: () => void }).destroy();
    }
  }
}

async function main(): Promise<void> {
  const args = process.argv.slice(2);
  let version: string | undefined;
  let mapsDir = process.env.SOURCEMAPS_DIR ?? path.resolve(__dirname, '..', 'dist-maps');
  const frames: string[] = [];

  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg === '--version') version = args[++i];
    else if (arg === '--maps-dir') mapsDir = path.resolve(args[++i]);
    else if (arg === '-h' || arg === '--help') usage();
    else frames.push(arg);
  }

  if (!version || frames.length === 0) usage();

  for (const raw of frames) {
    process.stdout.write((await symbolicate(parseFrame(raw), mapsDir, version)) + '\n');
  }
}

main().catch(err => {
  process.stderr.write(`${err instanceof Error ? err.stack : String(err)}\n`);
  process.exit(1);
});
