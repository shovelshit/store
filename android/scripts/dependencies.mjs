import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import https from 'node:https';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const androidRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const lock = JSON.parse(readFileSync(join(androidRoot, 'dependencies.lock.json'), 'utf8'));
const cache = join(androidRoot, '.deps');

function sha(bytes) { return createHash('sha256').update(bytes).digest('hex'); }
function download(url) {
  return new Promise((resolve, reject) => {
    if (!url.startsWith('https://repo.maven.apache.org/maven2/')) return reject(new Error('Unexpected dependency source'));
    https.get(url, { timeout: 15000 }, response => {
      if (response.statusCode !== 200) { response.resume(); return reject(new Error(`Dependency HTTP ${response.statusCode}`)); }
      const parts = [];
      let size = 0;
      response.on('data', part => {
        size += part.length;
        if (size > 3 * 1024 * 1024) response.destroy(new Error('Dependency too large'));
        else parts.push(part);
      });
      response.on('end', () => resolve(Buffer.concat(parts)));
      response.on('error', reject);
    }).on('timeout', function () { this.destroy(new Error('Dependency timed out')); }).on('error', reject);
  });
}

export async function resolveDependencies() {
  mkdirSync(cache, { recursive: true });
  const files = [];
  for (const item of lock) {
    if (!/^[a-z0-9.-]+\.jar$/.test(item.name) || !/^[a-f0-9]{64}$/.test(item.sha256)) throw new Error('Invalid dependency lock');
    const destination = join(cache, item.name);
    if (!existsSync(destination) || sha(readFileSync(destination)) !== item.sha256) {
      const bytes = await download(item.url);
      if (sha(bytes) !== item.sha256) throw new Error(`Dependency hash mismatch: ${item.name}`);
      const temporary = destination + '.part';
      try { writeFileSync(temporary, bytes); renameSync(temporary, destination); }
      finally { rmSync(temporary, { force: true }); }
    }
    files.push(destination);
  }
  return files;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) await resolveDependencies();
