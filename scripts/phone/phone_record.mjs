#!/usr/bin/env node
// Records a mobile job page in headless Chrome with iPhone 15-like emulation.
// usage: node phone_record.mjs --url <mobile page URL> --out <path.mp4> --seconds <N>
//                              [--approve-after-ready <sec>] [--shots <dir>]
import { chromium } from 'playwright-core';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const args = {};
for (let i = 2; i < process.argv.length; i++) {
  const a = process.argv[i];
  if (a.startsWith('--')) args[a.slice(2)] = process.argv[++i];
}
const url = args.url;
const out = args.out && path.resolve(args.out);
const seconds = Number(args.seconds || 10);
const approveAfter = args['approve-after-ready'] !== undefined ? Number(args['approve-after-ready']) : null;
const shotsDir = args.shots ? path.resolve(args.shots) : null;
if (!url || !out || !(seconds > 0)) {
  console.error('usage: node phone_record.mjs --url <url> --out <path.mp4> --seconds <N> [--approve-after-ready <sec>] [--shots <dir>]');
  process.exit(2);
}

const FFMPEG = fs.existsSync('/opt/homebrew/bin/ffmpeg') ? '/opt/homebrew/bin/ffmpeg' : 'ffmpeg';
const VIEW = { width: 393, height: 852 };
const VIDEO = { width: 786, height: 1704 }; // output mp4: 2x of the CSS viewport, downscaled from 3x capture
const UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1';

fs.mkdirSync(path.dirname(out), { recursive: true });
if (shotsDir) fs.mkdirSync(shotsDir, { recursive: true });
const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'phone_rec_'));

const browser = await chromium.launch({ channel: 'chrome', headless: true });
const context = await browser.newContext({
  viewport: VIEW,
  screen: VIEW,
  deviceScaleFactor: 3,
  isMobile: true,
  hasTouch: true,
  locale: 'ko-KR',
  userAgent: UA,
});
const page = await context.newPage();

// Capture via CDP screencast (full 3x device pixels, JPEG frames with timestamps) and
// encode with the system ffmpeg: sharper than Playwright's built-in VP8 recordVideo
// and needs no Playwright ffmpeg download.
const cdp = await context.newCDPSession(page);
const frames = [];
cdp.on('Page.screencastFrame', (f) => {
  const file = path.join(tmpDir, `f${String(frames.length).padStart(6, '0')}.jpg`);
  fs.writeFileSync(file, Buffer.from(f.data, 'base64'));
  frames.push({ file, ts: f.metadata.timestamp });
  cdp.send('Page.screencastFrameAck', { sessionId: f.sessionId }).catch(() => {});
});

let t0 = Date.now();
const el = () => ((Date.now() - t0) / 1000).toFixed(1) + 's';
const log = (m) => console.log(`PHONE ${el()} ${m}`);
const shot = async (name) => {
  if (!shotsDir) return;
  try {
    await page.screenshot({ path: path.join(shotsDir, name) });
    log(`shot ${name}`);
  } catch (e) {
    log(`shot ${name} failed: ${e.message.split('\n')[0]}`);
  }
};

let failed = null;
let endTs = 0;
try {
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 });
  t0 = Date.now();
  await cdp.send('Page.startScreencast', { format: 'jpeg', quality: 95, maxWidth: VIEW.width * 3, maxHeight: VIEW.height * 3, everyNthFrame: 1 });
  log(`loaded ${url}`);

  const endAt = t0 + seconds * 1000;
  let stopAt = endAt;
  let lastLine = null;
  let shot1 = false, shot2 = false, shot3 = false;
  let resAt = null, readyAt = null, tapped = false, deployedAt = null;

  while (Date.now() < stopAt) {
    const s = await page.evaluate(() => {
      const $ = (id) => document.getElementById(id);
      const btn = $('btn'), dep = $('dep'), res = $('res'), frame = $('frame');
      return {
        line: $('line') ? $('line').textContent : '',
        btnVisible: !!(btn && dep && !dep.hidden && btn.offsetParent !== null),
        btnEnabled: !!(btn && !btn.disabled),
        btnText: btn ? btn.textContent.trim() : '',
        resShown: !!(res && !res.hidden && frame && frame.getAttribute('src')),
      };
    }).catch(() => null);

    if (s) {
      if (s.line !== lastLine) {
        lastLine = s.line;
        log(`line=${s.line}`);
      }
      const t = Date.now() - t0;
      if (!shot1 && t >= 1500) { shot1 = true; await shot('phone_01_status.png'); }
      if (resAt === null && s.resShown) { resAt = Date.now(); log('result shown'); }
      // result shot after the status shot, once the iframe had time to paint
      if (!shot2 && shot1 && resAt !== null && Date.now() - resAt >= 2000) { shot2 = true; await shot('phone_02_result.png'); }
      if (approveAfter !== null) {
        if (!tapped && readyAt === null && s.btnVisible && s.btnEnabled) {
          readyAt = Date.now();
          log(`ready, approving in ${approveAfter}s`);
        }
        if (!tapped && readyAt !== null && Date.now() - readyAt >= approveAfter * 1000) {
          await page.tap('#btn');
          tapped = true;
          console.log('PHONE approve tapped');
        }
        if (tapped && deployedAt === null && s.btnText === '배포됨') {
          deployedAt = Date.now();
          log('deployed (배포됨)');
          stopAt = Math.min(endAt, deployedAt + 4000);
          if (!shot3) { shot3 = true; await page.waitForTimeout(400); await shot('phone_03_approved.png'); }
        }
      }
    }
    await page.waitForTimeout(150);
  }
  endTs = Date.now() / 1000;
  await cdp.send('Page.stopScreencast').catch(() => {});
  log(`recording done (${frames.length} frames)`);
} catch (e) {
  failed = e;
}

await context.close();
await browser.close();
if (failed) {
  console.error(`PHONE_REC_FAIL ${failed.message}`);
  process.exit(1);
}

if (!frames.length) {
  console.error('PHONE_REC_FAIL no frames captured');
  process.exit(1);
}
// Screencast only emits frames on change: hold each frame until the next one (last until the end).
const list = [];
const startTs = t0 / 1000;
frames.forEach((f) => { f.ts = Math.min(Math.max(f.ts, startTs), endTs); }); // stale first frame can predate the start
frames.forEach((f, i) => {
  const next = i + 1 < frames.length ? frames[i + 1].ts : Math.max(endTs, f.ts + 0.05);
  list.push(`file '${f.file}'`, `duration ${Math.max(next - f.ts, 0.001).toFixed(4)}`);
});
list.push(`file '${frames[frames.length - 1].file}'`);
const listFile = path.join(tmpDir, 'frames.txt');
fs.writeFileSync(listFile, list.join('\n') + '\n');
const ff = spawnSync(FFMPEG, [
  '-y', '-loglevel', 'error',
  '-f', 'concat', '-safe', '0', '-i', listFile,
  '-t', (endTs - startTs).toFixed(2),
  '-vf', `fps=30,scale=${VIDEO.width}:${VIDEO.height}:flags=lanczos:in_range=pc:out_range=tv,format=yuv420p`,
  '-c:v', 'libx264', '-preset', 'medium', '-crf', '17', '-pix_fmt', 'yuv420p', '-color_range', 'tv', '-r', '30',
  '-movflags', '+faststart', '-an', out,
], { stdio: ['ignore', 'inherit', 'inherit'] });
if (ff.status !== 0 || !fs.existsSync(out)) {
  console.error(`PHONE_REC_FAIL ffmpeg exit ${ff.status} (frames kept in ${tmpDir})`);
  process.exit(1);
}
fs.rmSync(tmpDir, { recursive: true, force: true });
console.log(`PHONE_REC_OK ${out}`);
