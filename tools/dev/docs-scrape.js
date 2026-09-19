#!/usr/bin/env node
/**
 * Scrape the Tongji open-platform docs into readable text.
 *
 * The docs site is Nextra; every page embeds the whole nav tree, so one page
 * is enough to enumerate every endpoint doc URL.
 *
 * Usage:
 *   node tools/dev/docs-scrape.js --list                  # dump all doc URLs
 *   node tools/dev/docs-scrape.js <docpath> [docpath...]   # fetch -> .probe/docs/<name>.txt
 *   node tools/dev/docs-scrape.js --match <regex>          # fetch every URL matching regex
 */
'use strict';

const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..', '..');
const PROBE = path.join(ROOT, '.probe');
const OUT = path.join(PROBE, 'docs');
const FETCH = path.join(__dirname, 'fetch.js');
const INDEX = path.join(PROBE, 'docs_teacher_timetable.html');
const BASE = 'https://api.tongji.edu.cn';

fs.mkdirSync(OUT, { recursive: true });

function textify(html) {
  let s = html;
  s = s.replace(/<script[\s\S]*?<\/script>/gi, '');
  s = s.replace(/<style[\s\S]*?<\/style>/gi, '');
  s = s.replace(/<svg[\s\S]*?<\/svg>/gi, '');
  s = s.replace(/<nav[\s\S]*?<\/nav>/gi, '');
  s = s.replace(/<aside[\s\S]*?<\/aside>/gi, '');
  s = s.replace(/<\/(td|th)>/gi, ' | ');
  s = s.replace(/<\/(tr|table)>/gi, '\n');
  s = s.replace(/<\/(p|div|h1|h2|h3|h4|h5|h6|li|pre|section)>/gi, '\n');
  s = s.replace(/<br\s*\/?>/gi, '\n');
  s = s.replace(/<[^>]+>/g, '');
  const ents = { '&amp;': '&', '&lt;': '<', '&gt;': '>', '&quot;': '"', '&#x27;': "'", '&#39;': "'", '&nbsp;': ' ', '&mdash;': '—', '&hellip;': '…' };
  s = s.replace(/&[a-z#0-9x]+;/gi, (m) => (ents[m] !== undefined ? ents[m] : m));
  s = s.replace(/[ \t\u00a0]+/g, ' ');
  s = s.replace(/\n\s*\n\s*\n+/g, '\n\n');
  return s.split('\n').map((l) => l.trim()).filter((l) => l.length && l !== '|').join('\n');
}

function listDocUrls() {
  if (!fs.existsSync(INDEX)) {
    console.error(`missing ${INDEX} — run a fetch of the teacher_timetable doc first`);
    process.exit(1);
  }
  const html = fs.readFileSync(INDEX, 'utf8');
  const set = new Set();
  for (const m of html.matchAll(/href="(\/docs\/[^"#?]+)"/g)) set.add(m[1]);
  for (const m of html.matchAll(/data-href="(\/interface\/[^"#?]+)"/g)) set.add('/docs' + m[1]);
  return [...set].sort();
}

function fetchDoc(url) {
  const name = url.replace('/docs/', '').replace(/[\\/]/g, '__');
  const htmlFile = path.join(OUT, name + '.html');
  const txtFile = path.join(OUT, name + '.txt');
  if (fs.existsSync(txtFile) && fs.statSync(txtFile).size > 200) {
    console.log(`[skip] ${name}`);
    return txtFile;
  }
  try {
    execFileSync(process.execPath, [FETCH, BASE + url, '--out', htmlFile], {
      stdio: ['ignore', 'ignore', 'ignore'],
      env: { ...process.env, NODE_NO_WARNINGS: '1' },
    });
  } catch (e) {
    console.error(`[fail] ${url}: ${e.message}`);
    return null;
  }
  if (!fs.existsSync(htmlFile)) { console.error(`[fail] ${url}: no output`); return null; }
  fs.writeFileSync(txtFile, textify(fs.readFileSync(htmlFile, 'utf8')), 'utf8');
  console.log(`[ok  ] ${name}.txt`);
  return txtFile;
}

const argv = process.argv.slice(2);
if (argv.includes('--list')) {
  for (const u of listDocUrls()) console.log(u);
} else if (argv[0] === '--match') {
  const re = new RegExp(argv[1], 'i');
  const urls = listDocUrls().filter((u) => re.test(u));
  console.log(`# ${urls.length} docs match ${argv[1]}`);
  for (const u of urls) fetchDoc(u);
} else if (argv.length) {
  for (const a of argv) fetchDoc(a.startsWith('/docs/') ? a : '/docs' + (a.startsWith('/') ? a : '/' + a));
} else {
  console.error('usage: --list | --match <re> | <docpath...>');
  process.exit(2);
}
