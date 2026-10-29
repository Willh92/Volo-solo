import {createHash} from 'node:crypto';
import {mkdir, readFile, writeFile, rename, rm} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';

// Pinned upstream data, never downloaded by the running application.
const base = 'https://raw.githubusercontent.com/lionsoul2014/ip2region/v3.18.0/data/';
const directory = new URL('../src/main/distribution/geoip/', import.meta.url);
const files = [
  ['ip2region_v4.xdb', '8e31bbdccb5bf21028af10592d4312ec975da0bffa108c0c5d862a12190f9ad3'],
  ['ip2region_v6.xdb', '939f6b46bd2b8bec3cf7c5ceb8ba782266ae9b1f35b5ba7916700dec0b7506ed'],
];
await mkdir(directory, {recursive:true});
for (const [name, expected] of files) {
  const destination = new URL(name, directory);
  const matches = bytes => createHash('sha256').update(bytes).digest('hex') === expected;
  try { if (matches(await readFile(destination))) { console.log(`${name}: verified`); continue; } } catch {}
  const response = await fetch(base + name, {signal:AbortSignal.timeout(180000)});
  if (!response.ok) throw Error(`${name}: HTTP ${response.status}`);
  const bytes = Buffer.from(await response.arrayBuffer());
  if (!matches(bytes)) throw Error(`${name}: unexpected SHA-256; refusing changed upstream data`);
  const temporary = fileURLToPath(destination) + '.download';
  try { await writeFile(temporary, bytes); await rename(temporary, destination); }
  finally { await rm(temporary, {force:true}); }
  console.log(`${name}: downloaded and verified`);
}
