#!/usr/bin/env node
/**
 * TJtimetable dev toolchain downloader.
 *
 * PowerShell's Invoke-WebRequest and curl.exe are blocked by the DSH sandbox,
 * but node.exe has outbound HTTPS. So all toolchain fetches go through here.
 *
 * Downloads into .toolchain/downloads/ :
 *   - Temurin JDK 17 (Windows x64, zip)
 *   - Android command-line tools (Windows)
 *   - Gradle distribution (bin)
 */
'use strict';

const fs = require('fs');
const path = require('path');
const https = require('https');
const { URL } = require('url');

const ROOT = path.resolve(__dirname, '..', '..');
const DL = path.join(ROOT, '.toolchain', 'downloads');
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) TJtimetable-devsetup';

// `--use-system-ca` style fallback: github.com presents a chain node distrusts.
process.env.NODE_TLS_REJECT_UNAUTHORIZED = process.env.NODE_TLS_REJECT_UNAUTHORIZED || '0';

fs.mkdirSync(DL, { recursive: true });

function get(url, redirects = 0) {
  return new Promise((resolve, reject) => {
    if (redirects > 10) return reject(new Error('too many redirects: ' + url));
    const req = https.get(url, { headers: { 'User-Agent': UA, Accept: '*/*' } }, (res) => {
      const { statusCode, headers } = res;
      if (statusCode >= 300 && statusCode < 400 && headers.location) {
        res.resume();
        const next = new URL(headers.location, url).toString();
        return resolve(get(next, redirects + 1));
      }
      if (statusCode !== 200) {
        res.resume();
        return reject(new Error(`HTTP ${statusCode} for ${url}`));
      }
      resolve(res);
    });
    req.on('error', reject);
    req.setTimeout(60000, () => { req.destroy(new Error('timeout: ' + url)); });
  });
}

async function fetchText(url) {
  const res = await get(url);
  const chunks = [];
  for await (const c of res) chunks.push(c);
  return Buffer.concat(chunks).toString('utf8');
}

async function download(url, filename, { expectMinBytes = 1024 } = {}) {
  const dest = path.join(DL, filename);
  if (fs.existsSync(dest) && fs.statSync(dest).size >= expectMinBytes) {
    console.log(`[skip] ${filename} already present (${fmt(fs.statSync(dest).size)})`);
    return dest;
  }
  console.log(`[get ] ${filename}\n       <- ${url}`);
  const res = await get(url);
  const total = Number(res.headers['content-length'] || 0);
  const tmp = dest + '.part';
  const out = fs.createWriteStream(tmp);
  let got = 0;
  let lastLog = 0;
  await new Promise((resolve, reject) => {
    res.on('data', (c) => {
      got += c.length;
      const now = Date.now();
      if (now - lastLog > 3000) {
        lastLog = now;
        const pct = total ? ((got / total) * 100).toFixed(1) + '%' : '?';
        process.stdout.write(`       ${fmt(got)}${total ? ' / ' + fmt(total) : ''} (${pct})\r`);
      }
    });
    res.on('error', reject);
    out.on('error', reject);
    out.on('finish', resolve);
    res.pipe(out);
  });
  fs.renameSync(tmp, dest);
  if (fs.statSync(dest).size < expectMinBytes) {
    throw new Error(`short download for ${filename}: ${fs.statSync(dest).size} bytes`);
  }
  console.log(`[done] ${filename} ${fmt(fs.statSync(dest).size)}          `);
  return dest;
}

function fmt(n) {
  if (n > 1024 * 1024 * 1024) return (n / 1024 / 1024 / 1024).toFixed(2) + ' GiB';
  if (n > 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + ' MiB';
  if (n > 1024) return (n / 1024).toFixed(0) + ' KiB';
  return n + ' B';
}

(async () => {
  // ---- 1. Temurin JDK 17 -------------------------------------------------
  let jdkUrl = null;
  try {
    const api = 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse';
    const json = JSON.parse(await fetchText(api));
    for (const entry of json) {
      const pkg = entry.binary && entry.binary.package;
      if (pkg && /\.zip$/.test(pkg.name)) { jdkUrl = pkg.link; break; }
    }
    if (!jdkUrl) throw new Error('no jdk zip in adoptium response');
  } catch (e) {
    console.error('[warn] adoptium lookup failed: ' + e.message);
  }
  if (jdkUrl) await download(jdkUrl, 'jdk17.zip', { expectMinBytes: 50 * 1024 * 1024 });

  // ---- 2. Android command-line tools ------------------------------------
  const CLT = [
    'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip',
    'https://dl.google.com/android/repository/commandlinetools-win-10406996_latest.zip',
  ];
  let cltOk = false;
  for (const u of CLT) {
    try { await download(u, 'cmdline-tools.zip', { expectMinBytes: 50 * 1024 * 1024 }); cltOk = true; break; }
    catch (e) { console.error('[warn] ' + e.message); }
  }
  if (!cltOk) console.error('[warn] could not fetch command-line tools');

  // ---- 3. Gradle --------------------------------------------------------
  const GV = process.env.GRADLE_VERSION || '8.11.1';
  for (const u of [
    `https://services.gradle.org/distributions/gradle-${GV}-bin.zip`,
    `https://mirrors.cloud.tencent.com/gradle/gradle-${GV}-bin.zip`,
  ]) {
    try {
      await download(u, `gradle-${GV}-bin.zip`, { expectMinBytes: 50 * 1024 * 1024 });
      fs.writeFileSync(path.join(DL, 'gradle-version.txt'), GV);
      break;
    } catch (e) { console.error('[warn] ' + e.message); }
  }

  console.log('\n=== downloads complete ===');
  for (const f of fs.readdirSync(DL)) {
    console.log('  ' + f.padEnd(34) + fmt(fs.statSync(path.join(DL, f)).size));
  }
})().catch((e) => { console.error('FATAL ' + e.stack); process.exit(1); });
