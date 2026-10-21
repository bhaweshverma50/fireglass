#!/usr/bin/env node
// Mock TV Home launcher: implements docs/remote-api.md (v1) so the phone app can be exercised
// without the Fire TV. Zero setup beyond `npm install` here (for mDNS); no TV is touched.
//
//   node server.mjs [--port 8090] [--name "Living Room TV"] [--keys ready|needs_bootstrap]
//                   [--latency 60] [--no-mdns] [--no-bulbs]
//
// While running, type into this terminal:
//   pin     show a fresh PIN (as if a phone asked)      keys   toggle keys ready / needs_bootstrap
//   play    toggle now-playing audio                    forget drop all paired tokens
//   bulb    toggle the fake WiZ bulb online / offline   (--no-bulbs: none until the phone looks)
import { createServer } from 'node:http';
import { randomBytes, randomInt } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { networkInterfaces } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createInterface } from 'node:readline';
import { REC_IDS, TITLES, shape } from './titles.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const arg = (name, def) => {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 && process.argv[i + 1] && !process.argv[i + 1].startsWith('--') ? process.argv[i + 1] : def;
};
const PORT = Number(arg('port', 8090));
const NAME = arg('name', 'Living Room TV');
const LATENCY = Number(arg('latency', 60));
const MDNS = !process.argv.includes('--no-mdns');

const APPS = JSON.parse(readFileSync(join(here, 'apps.json'), 'utf8'));
const LAUNCHER = { pkg: 'dev.bhawesh.tvhome', label: 'Home' };
const STATE_FILE = join(here, '.state.json');

const state = {
  tokens: {}, // token -> device name
  keys: arg('keys', 'ready'),
  now: { ...LAUNCHER, playing: false },
  recent: [],
  pin: null, // { code, device, until }
};
if (existsSync(STATE_FILE)) {
  try { Object.assign(state, { tokens: JSON.parse(readFileSync(STATE_FILE, 'utf8')).tokens ?? {} }); } catch {}
}
const save = () => writeFileSync(STATE_FILE, JSON.stringify({ tokens: state.tokens }, null, 2));

const c = { dim: (s) => `\x1b[2m${s}\x1b[0m`, b: (s) => `\x1b[1m${s}\x1b[0m`, cyan: (s) => `\x1b[36m${s}\x1b[0m`, yellow: (s) => `\x1b[33m${s}\x1b[0m` };
const log = (...a) => console.log(c.dim(new Date().toLocaleTimeString()), ...a);

function showPin(device) {
  const code = String(randomInt(0, 10000)).padStart(4, '0');
  state.pin = { code, device, until: Date.now() + 120_000, tries: 3 };
  const line = `   PIN for ${device}:  ${code.split('').join(' ')}   `;
  const bar = '─'.repeat(line.length);
  console.log(`\n  ┌${bar}┐\n  │${c.b(c.yellow(line))}│\n  └${bar}┘  ${c.dim('(valid 2 minutes)')}\n`);
}

// ---------------------------------------------------------------- lights (one fake WiZ bulb)

const SCENES = [
  [1, 'Ocean'], [2, 'Romance'], [3, 'Sunset'], [4, 'Party'], [5, 'Fireplace'], [6, 'Cozy'], [7, 'Forest'],
  [8, 'Pastel colors'], [9, 'Wake up'], [10, 'Bedtime'], [11, 'Warm white'], [12, 'Daylight'], [13, 'Cool white'],
  [14, 'Night light'], [15, 'Focus'], [16, 'Relax'], [17, 'True colors'], [18, 'TV time'], [19, 'Plantgrowth'],
  [20, 'Spring'], [21, 'Summer'], [22, 'Fall'], [23, 'Deepdive'], [24, 'Jungle'], [25, 'Mojito'], [26, 'Club'],
  [27, 'Christmas'], [28, 'Halloween'], [29, 'Candlelight'], [30, 'Golden white'], [31, 'Pulse'], [32, 'Steampunk'],
].map(([id, name]) => ({ id, name }));

const FAKE_BULB = { ip: '192.168.0.10', mac: '9877d5d7871e', online: true, on: true, dimming: 80, temp: 2700, rgb: null, scene: null };
const lights = {
  bulbs: process.argv.includes('--no-bulbs') ? [] : [{ ...FAKE_BULB }],
  auto: { dimWhilePlaying: false, matchHome: false },
};
const OFFLINE_FIELDS = { on: null, dimming: null, temp: null, rgb: null, scene: null };
const lightsBody = () => ({
  bulbs: lights.bulbs.map((b) => (b.online ? b : { ...b, ...OFFLINE_FIELDS })),
  scenes: SCENES,
  auto: lights.auto,
});

/** Validates a POST /api/lights body; returns an error code or null. */
function badLights(b) {
  const int = (v, lo, hi) => Number.isInteger(v) && v >= lo && v <= hi;
  if (b.on !== undefined && typeof b.on !== 'boolean') return 'bad_on';
  if (b.dimming !== undefined && !int(b.dimming, 10, 100)) return 'bad_dimming';
  if (b.temp !== undefined && !int(b.temp, 2200, 6500)) return 'bad_temp';
  if (b.rgb !== undefined && !(Array.isArray(b.rgb) && b.rgb.length === 3 && b.rgb.every((v) => int(v, 0, 255)))) return 'bad_rgb';
  if (b.scene !== undefined && !SCENES.some((s) => s.id === b.scene)) return 'bad_scene';
  return null;
}

function applyLights(b) {
  for (const bulb of lights.bulbs) {
    if (!bulb.online) continue;
    if (b.dimming !== undefined) bulb.dimming = b.dimming;
    if (b.temp !== undefined) Object.assign(bulb, { temp: b.temp, rgb: null, scene: null });
    if (b.rgb !== undefined) Object.assign(bulb, { rgb: b.rgb, temp: null, scene: null });
    if (b.scene !== undefined) Object.assign(bulb, { scene: b.scene, temp: null, rgb: null });
    // Like a WiZ bulb: any colour or brightness lights it up.
    if (b.dimming !== undefined || b.temp !== undefined || b.rgb !== undefined || b.scene !== undefined) bulb.on = true;
    if (b.on !== undefined) bulb.on = b.on;
  }
}

// ---------------------------------------------------------------- helpers

const titles = () => TITLES.map((t) => shape(t, APPS));
const norm = (s) => String(s ?? '').toLowerCase().normalize('NFKD').replace(/[^a-z0-9 ]+/g, ' ').replace(/\s+/g, ' ').trim();

function search(q) {
  const nq = norm(q);
  if (!nq) return [];
  const words = nq.split(' ').filter((w) => w.length > 2 && !['the', 'and', 'new', 'good', 'some', 'shows', 'movies', 'like', 'series', 'films'].includes(w));
  const scored = titles().map((t) => {
    const nt = norm(t.title);
    let s = 0;
    if (nt === nq) s += 100;
    else if (nt.startsWith(nq)) s += 60;
    else if (nt.includes(nq)) s += 40;
    for (const w of words) {
      if (nt.includes(w)) s += 12;
      const stem = w.replace(/s$/, '').replace(/er$/, '');
      if (t.genres?.some((g) => norm(g).startsWith(stem))) s += 8;
      if (norm(t.overview).includes(w)) s += 2;
    }
    return { t, s };
  }).filter((x) => x.s > 0).sort((a, b) => b.s - a.s || (b.t.rating ?? 0) - (a.t.rating ?? 0));
  if (scored.length) return scored.slice(0, 10).map((x) => x.t);
  // typo tolerance: "darc", "stranger thing", "intersteller"
  return titles()
    .map((t) => ({ t, d: lev(nq, norm(t.title).slice(0, Math.max(nq.length, 3))) }))
    .filter((x) => x.d <= Math.max(1, Math.floor(nq.length / 4)))
    .sort((a, b) => a.d - b.d)
    .slice(0, 10)
    .map((x) => x.t);
}

function lev(a, b) {
  const d = Array.from({ length: a.length + 1 }, (_, i) => [i, ...Array(b.length).fill(0)]);
  for (let j = 1; j <= b.length; j++) d[0][j] = j;
  for (let i = 1; i <= a.length; i++)
    for (let j = 1; j <= b.length; j++)
      d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
  return d[a.length][b.length];
}

function remember(item) {
  const key = item.id ?? item.pkg;
  state.recent = [item, ...state.recent.filter((x) => (x.id ?? x.pkg) !== key)].slice(0, 12);
}

function openApp(pkg, playing = false) {
  const app = APPS.find((a) => a.pkg === pkg);
  if (!app) return false;
  state.now = { pkg, label: app.label, playing };
  return true;
}

// ---------------------------------------------------------------- routes

const json = (res, status, body) => {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(body));
};

async function body(req) {
  let raw = '';
  for await (const chunk of req) raw += chunk;
  try { return raw ? JSON.parse(raw) : {}; } catch { return {}; }
}

async function handle(req, res) {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const p = url.pathname;
  const m = req.method;

  if (m === 'POST' && p === '/api/pair/request') {
    const b = await body(req);
    // Like the TV: a PIN still showing stands. (The TV's cooldown between PINs isn't emulated.)
    if (!state.pin || Date.now() > state.pin.until) showPin(String(b.device ?? 'a phone').slice(0, 40));
    log('pair request from', c.cyan(b.device ?? '?'));
    return json(res, 200, { ok: true });
  }
  if (m === 'POST' && p === '/api/pair/confirm') {
    const b = await body(req);
    if (!state.pin || Date.now() > state.pin.until) return json(res, 403, { error: 'pin_expired' });
    if (String(b.pin) !== state.pin.code) {
      const left = --state.pin.tries;
      log('pair confirm', c.yellow('bad PIN'), b.pin, `(${left} left)`);
      if (left <= 0) state.pin = null;
      return json(res, 403, { error: 'bad_pin', tries_left: Math.max(left, 0) });
    }
    const token = randomBytes(16).toString('hex');
    state.tokens[token] = state.pin.device;
    state.pin = null;
    save();
    log('paired', c.cyan(state.tokens[token]));
    return json(res, 200, { token, name: NAME });
  }

  const auth = (req.headers.authorization ?? '').replace(/^Bearer\s+/i, '');
  if (!state.tokens[auth]) return json(res, 401, { error: 'unpaired' });

  if (m === 'GET' && p === '/api/info') return json(res, 200, { name: NAME, model: 'AFTSSS · mock', version: '1', keys: state.keys });
  if (m === 'GET' && p === '/api/keys/bootstrap') {
    return json(res, 200, { adb_port: 5555, cmd: 'pkill -f dev.bhawesh.tvhome.KeyServer; CLASSPATH=/data/app/dev.bhawesh.tvhome/base.apk nohup app_process / dev.bhawesh.tvhome.KeyServer >/dev/null 2>&1 &' });
  }
  if (m === 'GET' && p === '/api/now') return json(res, 200, state.now);
  if (m === 'GET' && p === '/api/apps') return json(res, 200, APPS);
  const bm = p.match(/^\/api\/apps\/([^/]+)\/banner\.png$/);
  if (m === 'GET' && bm) {
    const file = join(here, 'banners', `${decodeURIComponent(bm[1])}.png`);
    if (!existsSync(file)) return json(res, 404, { error: 'no_banner' });
    res.writeHead(200, { 'Content-Type': 'image/png', 'Cache-Control': 'max-age=3600' });
    return res.end(readFileSync(file));
  }

  if (m === 'POST' && p === '/api/key') {
    const b = await body(req);
    if (state.keys !== 'ready') return json(res, 503, { error: 'needs_bootstrap' });
    const keys = 'up down left right select back home menu play_pause rewind fast_forward next previous volume_up volume_down mute'.split(' ');
    if (!keys.includes(b.key)) return json(res, 200, { ok: false, error: 'unknown_key' });
    if (b.key === 'home') state.now = { ...LAUNCHER, playing: false };
    if (b.key === 'play_pause' && state.now.pkg !== LAUNCHER.pkg) state.now.playing = !state.now.playing;
    log('key', c.cyan(b.key), b.long ? c.yellow('(long)') : '');
    return json(res, 200, { ok: true });
  }
  if (m === 'POST' && p === '/api/text') {
    const b = await body(req);
    if (state.keys !== 'ready') return json(res, 503, { error: 'needs_bootstrap' });
    log('text', c.cyan(JSON.stringify(b.text ?? '')), b.submit ? c.yellow('+ enter') : '');
    return json(res, 200, { ok: true });
  }
  if (m === 'POST' && p === '/api/launch') {
    const b = await body(req);
    if (!openApp(b.pkg)) return json(res, 200, { ok: false, error: 'not_installed' });
    remember({ pkg: b.pkg, label: state.now.label });
    log('launch', c.cyan(state.now.label));
    return json(res, 200, { ok: true });
  }
  if (m === 'POST' && p === '/api/open') {
    const b = await body(req);
    if (b.pkg && !openApp(b.pkg, true)) return json(res, 200, { ok: false, error: 'not_installed' });
    log('open', c.cyan(b.url), b.pkg ?? '');
    return json(res, 200, { ok: true });
  }

  if (m === 'GET' && p === '/api/search') return json(res, 200, search(url.searchParams.get('q')));
  if (m === 'GET' && p === '/api/recs') {
    const all = titles();
    return json(res, 200, REC_IDS.map((id) => all.find((t) => t.id === id)).filter((t) => t && t.offers.length));
  }
  if (m === 'GET' && p === '/api/recent') return json(res, 200, state.recent);
  if (m === 'POST' && p === '/api/play') {
    const b = await body(req);
    const all = titles();
    const t = b.id ? all.find((x) => x.id === b.id) : search(b.title ?? '').find((x) => norm(x.title) === norm(b.title)) ?? null;
    if (!t) {
      // Not a known title: with an app named, open that app's search (YouTube "lofi").
      if (b.pkg && openApp(b.pkg, true)) {
        log('play', c.cyan(b.title), 'via search in', state.now.label);
        remember({ pkg: b.pkg, label: state.now.label });
        return json(res, 200, { ok: true, opened: { title: b.title, pkg: b.pkg, how: 'search' } });
      }
      const candidates = search(b.title ?? '').filter((x) => x.offers.length).slice(0, 6);
      log('play', c.yellow('not found'), b.title, `${candidates.length} candidates`);
      return json(res, 404, { ok: false, error: 'not_found', candidates });
    }
    if (!t.offers.length) return json(res, 200, { ok: false, error: 'not_on_your_apps', candidates: [] });
    const offer = (b.pkg && t.offers.find((o) => o.pkg === b.pkg)) || t.offers[0];
    openApp(offer.pkg, true);
    remember(t);
    const how = offer.url ? 'deeplink' : 'search';
    log('play', c.cyan(t.title), 'on', offer.label, c.dim(how));
    return json(res, 200, { ok: true, opened: { title: t.title, pkg: offer.pkg, how } });
  }

  if (m === 'GET' && p === '/api/lights') return json(res, 200, lightsBody());
  if (m === 'POST' && p === '/api/lights') {
    const b = await body(req);
    if (!lights.bulbs.some((x) => x.online)) return json(res, 503, { error: 'no_bulbs' });
    const bad = badLights(b);
    if (bad) return json(res, 400, { error: bad });
    applyLights(b);
    log('lights', c.cyan(JSON.stringify(b)));
    return json(res, 200, lightsBody());
  }
  if (m === 'POST' && p === '/api/lights/discover') {
    log('lights', c.dim('scanning…'));
    await new Promise((r) => setTimeout(r, 2500));
    if (!lights.bulbs.length) lights.bulbs.push({ ...FAKE_BULB });
    log('lights', 'found', c.cyan(lights.bulbs.filter((x) => x.online).length), 'online');
    return json(res, 200, lightsBody());
  }
  if (m === 'POST' && p === '/api/lights/auto') {
    const b = await body(req);
    for (const k of ['dimWhilePlaying', 'matchHome']) if (typeof b[k] === 'boolean') lights.auto[k] = b[k];
    log('lights auto', c.cyan(JSON.stringify(lights.auto)));
    return json(res, 200, lightsBody());
  }

  return json(res, 404, { error: 'not_found' });
}

const server = createServer((req, res) => {
  // CORS so the web preview of the app can talk to the mock too (the real TV doesn't need it).
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Headers', 'Authorization, Content-Type');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  if (req.method === 'OPTIONS') { res.writeHead(204); return res.end(); }
  setTimeout(() => handle(req, res).catch((e) => { console.error(e); json(res, 500, { error: 'server_error' }); }), LATENCY);
});

server.listen(PORT, '0.0.0.0', async () => {
  const ips = Object.values(networkInterfaces()).flat().filter((i) => i && i.family === 'IPv4' && !i.internal).map((i) => i.address);
  console.log(`\n  ${c.b('Mock TV')} “${NAME}” on port ${PORT}`);
  for (const ip of ips) console.log(`  ${c.cyan(`http://${ip}:${PORT}`)}`);
  console.log(`  keys: ${state.keys}   paired phones: ${Object.keys(state.tokens).length}`);
  console.log(c.dim('  type: pin | keys | play | forget | bulb\n'));
  if (MDNS) {
    try {
      const { Bonjour } = await import('bonjour-service');
      const bonjour = new Bonjour();
      bonjour.publish({ name: NAME, type: 'tvhome', protocol: 'tcp', port: PORT, txt: { v: '1' } });
      console.log(c.dim(`  advertising _tvhome._tcp. as “${NAME}”\n`));
      const stop = () => bonjour.unpublishAll(() => { bonjour.destroy(); process.exit(0); });
      process.on('SIGINT', stop);
      process.on('SIGTERM', stop);
    } catch (e) {
      console.log(c.dim(`  mDNS off (${e.code === 'ERR_MODULE_NOT_FOUND' ? 'run npm install in mock-tv/' : e.message})\n`));
    }
  }
});

createInterface({ input: process.stdin }).on('line', (l) => {
  const cmd = l.trim();
  if (cmd === 'pin') showPin('a phone');
  else if (cmd === 'keys') { state.keys = state.keys === 'ready' ? 'needs_bootstrap' : 'ready'; log('keys now', c.cyan(state.keys)); }
  else if (cmd === 'play') { state.now.playing = !state.now.playing; log('playing', state.now.playing); }
  else if (cmd === 'forget') { state.tokens = {}; save(); log('forgot all phones'); }
  else if (cmd === 'bulb') {
    if (!lights.bulbs.length) lights.bulbs.push({ ...FAKE_BULB });
    else lights.bulbs[0].online = !lights.bulbs[0].online;
    log('bulb', lights.bulbs[0].online ? c.cyan('online') : c.yellow('offline'));
  }
  else if (cmd) console.log(c.dim('commands: pin | keys | play | forget | bulb'));
});
