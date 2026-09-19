#!/usr/bin/env node
/**
 * Fetch a URL and print it. Needed because curl.exe / Invoke-WebRequest are
 * blocked by the DSH sandbox while node.exe has outbound HTTPS.
 *
 * Usage:
 *   node tools/dev/fetch.js <url> [--json] [--out FILE] [--max BYTES]
 *   node tools/dev/fetch.js --post <url> --data '<json>' [--header 'k: v']
 */
'use strict';

const fs = require('fs');
const path = require('path');
const https = require('https');
const http = require('http');
const { URL } = require('url');

process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';

const argv = process.argv.slice(2);
const flags = { headers: [] };
const positional = [];
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (a === '--json') flags.json = true;
  else if (a === '--out') flags.out = argv[++i];
  else if (a === '--max') flags.max = parseInt(argv[++i], 10);
  else if (a === '--post') flags.post = true;
  else if (a === '--data') flags.data = argv[++i];
  else if (a === '--header') flags.headers.push(argv[++i]);
  else if (a === '--method') flags.method = argv[++i];
  else positional.push(a);
}

const target = positional[0];
if (!target) { console.error('usage: node tools/dev/fetch.js <url> [--json] [--out FILE] [--max N]'); process.exit(2); }

function request(url, redirects = 0) {
  return new Promise((resolve, reject) => {
    if (redirects > 10) return reject(new Error('too many redirects'));
    const u = new URL(url);
    const mod = u.protocol === 'http:' ? http : https;
    const headers = {
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36',
      'Accept': flags.json ? 'application/json, text/plain, */*' : 'text/html,application/json,application/xhtml+xml,*/*;q=0.8',
      'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
      ...Object.fromEntries(flags.headers.map((h) => {
        const idx = h.indexOf(':');
        return [h.slice(0, idx).trim(), h.slice(idx + 1).trim()];
      })),
    };
    if (flags.data) {
      headers['Content-Type'] = headers['Content-Type'] || 'application/json';
      headers['Content-Length'] = Buffer.byteLength(flags.data);
    }
    const req = mod.request(url, {
      method: flags.method || (flags.post || flags.data ? 'POST' : 'GET'),
      headers,
    }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        res.resume();
        return resolve(request(new URL(res.headers.location, url).toString(), redirects + 1));
      }
      const chunks = [];
      let size = 0;
      res.on('data', (c) => {
        size += c.length;
        if (!flags.max || size <= flags.max) chunks.push(c);
        else res.destroy();
      });
      res.on('end', () => resolve({
        status: res.statusCode,
        headers: res.headers,
        body: Buffer.concat(chunks),
      }));
      res.on('error', (e) => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks), err: e.message }));
    });
    req.on('error', reject);
    req.setTimeout(45000, () => req.destroy(new Error('timeout')));
    if (flags.data) req.write(flags.data);
    req.end();
  });
}

(async () => {
  const res = await request(target);
  const body = res.body.toString('utf8');
  console.error(`--- ${res.status} ${res.headers['content-type'] || ''} ${body.length} bytes`);
  if (flags.out) {
    const out = path.resolve(flags.out);
    fs.mkdirSync(path.dirname(out), { recursive: true });
    fs.writeFileSync(out, res.body);
    console.error(`--- written to ${out}`);
    if (!/json|text|html|xml/.test(res.headers['content-type'] || '')) return;
  }
  process.stdout.write(body + '\n');
})().catch((e) => { console.error('FATAL ' + e.message); process.exit(1); });
