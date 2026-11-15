#!/usr/bin/env node
/**
 * Builds the Chrome and Firefox packages from extension/src.
 *
 *   node build.mjs            → dist/chrome/, dist/firefox/ and ../releases/*.zip|*.xpi
 *
 * No dependencies: uses Node's built-in zlib to write the archives.
 */
import { readFileSync, writeFileSync, mkdirSync, rmSync, readdirSync, statSync } from 'node:fs';
import { join, relative, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import zlib from 'node:zlib';

const here = dirname(fileURLToPath(import.meta.url));
const SRC = join(here, 'src');
const DIST = join(here, 'dist');
const RELEASES = join(here, '..', 'releases');

const base = JSON.parse(readFileSync(join(SRC, 'manifest.json'), 'utf8'));
const version = base.version;

const targets = {
  chrome: (m) => ({ ...m, minimum_chrome_version: '102' }),
  firefox: (m) => ({
    ...m,
    host_permissions: ['https://www.instagram.com/*', 'https://instagram.com/*'],
    browser_specific_settings: {
      gecko: {
        id: 'unreel@unreel-extension',
        strict_min_version: '128.0',
        data_collection_permissions: { required: ['none'] },
      },
      gecko_android: { strict_min_version: '128.0' },
    },
  }),
};

function walk(dir) {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    return statSync(p).isDirectory() ? walk(p) : [p];
  });
}

/** Minimal ZIP (deflate) writer. */
function zip(files) {
  const locals = [];
  const centrals = [];
  let offset = 0;
  const dosTime = 0, dosDate = (2026 - 1980) << 9 | 1 << 5 | 1; // fixed timestamp → reproducible
  for (const { name, data } of files) {
    const nameBuf = Buffer.from(name, 'utf8');
    const deflated = zlib.deflateRawSync(data, { level: 9 });
    const crc = zlib.crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x0800, 6);
    local.writeUInt16LE(8, 8);
    local.writeUInt16LE(dosTime, 10);
    local.writeUInt16LE(dosDate, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(deflated.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    local.writeUInt16LE(0, 28);
    locals.push(local, nameBuf, deflated);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0x0800, 8);
    central.writeUInt16LE(8, 10);
    central.writeUInt16LE(dosTime, 12);
    central.writeUInt16LE(dosDate, 14);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(deflated.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(nameBuf.length, 28);
    central.writeUInt32LE(offset, 42);
    centrals.push(central, nameBuf);
    offset += local.length + nameBuf.length + deflated.length;
  }
  const centralBuf = Buffer.concat(centrals);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(files.length, 8);
  end.writeUInt16LE(files.length, 10);
  end.writeUInt32LE(centralBuf.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, centralBuf, end]);
}

rmSync(DIST, { recursive: true, force: true });
mkdirSync(RELEASES, { recursive: true });

for (const [target, transform] of Object.entries(targets)) {
  const out = join(DIST, target);
  for (const file of walk(SRC)) {
    const dest = join(out, relative(SRC, file));
    mkdirSync(dirname(dest), { recursive: true });
    writeFileSync(dest, readFileSync(file));
  }
  const manifest = transform(structuredClone(base));
  writeFileSync(join(out, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');

  const files = walk(out)
    .map((p) => ({ name: relative(out, p).split('\\').join('/'), data: readFileSync(p) }))
    .sort((a, b) => (a.name === 'manifest.json' ? -1 : b.name === 'manifest.json' ? 1 : a.name.localeCompare(b.name)));

  const ext = target === 'firefox' ? 'xpi' : 'zip';
  const file = join(RELEASES, `unreel-${target}-v${version}.${ext}`);
  writeFileSync(file, zip(files));
  console.log(`✔ ${target.padEnd(8)} ${relative(join(here, '..'), file)}  (${files.length} files)`);
}
