#!/usr/bin/env node
/**
 * Mock Instagram for testing the content script without an account.
 *
 *   node extension/test/mock-instagram.mjs      → http://localhost:8787
 *
 * The page stubs the extension storage API, then loads the real
 * src/content/content.js and content.css. The logged-in user is @me.tester.
 *
 *   /reel/OWNREEL0001/      your reel            → stays
 *   /reel/OTHERREEL01/      someone else's reel  → kicked to /
 *   /reels/                 Reels feed           → kicked to /
 *   /me.tester/reels/       your Reels tab       → stays
 *   /someone.else/reels/    their Reels tab      → kicked to /someone.else/
 */
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const SRC = join(dirname(fileURLToPath(import.meta.url)), '..', 'src');
const PORT = Number(process.env.PORT || 8787);
const ME = { id: '4242', username: 'me.tester' };
const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
const mediaId = (code) => [...code.slice(0, 11)].reduce((id, ch) => id * 64n + BigInt(ALPHABET.indexOf(ch)), 0n).toString();
const OWN_REELS = new Set([mediaId('OWNREEL0001')]);

const json = (res, body) => { res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify(body)); };

const page = (path) => {
  const parts = path.split('/').filter(Boolean);
  const author = parts[0] === 'reel' ? (parts[1] === 'OWNREEL0001' ? ME.username : 'someone.else') : null;
  return `<!doctype html><html><head><meta charset="utf-8"><title>Mock Instagram</title>
<script>
  // Stub of the WebExtension storage API, persisted in localStorage.
  (() => {
    const KEY = 'unreel-test-storage';
    const read = () => JSON.parse(localStorage.getItem(KEY) || '{}');
    const listeners = [];
    window.browser = { storage: {
      local: {
        get: async (defaults) => ({ ...defaults, ...read() }),
        set: async (patch) => {
          localStorage.setItem(KEY, JSON.stringify({ ...read(), ...patch }));
          const changes = Object.fromEntries(Object.entries(patch).map(([k, v]) => [k, { newValue: v }]));
          listeners.forEach((fn) => fn(changes, 'local'));
        },
      },
      onChanged: { addListener: (fn) => listeners.push(fn) },
    } };
  })();
</script>
<link rel="stylesheet" href="/__ext/content/content.css">
<script src="/__ext/content/content.js"></script>
<style>body{font:15px system-ui;background:#000;color:#eee;margin:0;display:flex}nav{width:220px;padding:20px;border-right:1px solid #333;display:flex;flex-direction:column;gap:10px}nav a,main a{color:#9cf}main{padding:20px}video{width:240px;height:400px;background:#222}</style>
</head><body>
<nav>
  <a href="/">Home</a><a href="/explore/">Explore</a><a href="/reels/">Reels</a><a href="/direct/inbox/">Messages</a>
  <a href="/${ME.username}/"><img alt="" width="20" height="20" style="background:#888;border-radius:50%"> Profile</a>
  <hr><b>Test links</b>
  <a href="/reel/OWNREEL0001/">My reel</a>
  <a href="/reel/OTHERREEL01/">Other's reel</a>
  <a href="/${ME.username}/reels/">My Reels tab</a>
  <a href="/someone.else/reels/">Their Reels tab</a>
</nav>
<main>
  <h1 id="path">${path}</h1>
  ${author ? `<p>Reel by <a href="/${author}/">${author}</a></p><video></video>` : '<p>Not a reel.</p>'}
</main>
</body></html>`;
};

createServer((req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const p = url.pathname;

  if (p.startsWith('/__ext/')) {
    const file = join(SRC, p.slice('/__ext/'.length));
    const types = { css: 'text/css', js: 'text/javascript', html: 'text/html; charset=utf-8', png: 'image/png' };
    res.writeHead(200, { 'content-type': types[file.split('.').pop()] || 'application/octet-stream' });
    return res.end(readFileSync(file));
  }
  if (p === `/api/v1/users/${ME.id}/info/`) return json(res, { user: { username: ME.username } });
  const media = p.match(/^\/api\/v1\/media\/(\d+)\/info\/$/);
  if (media) {
    const owner = OWN_REELS.has(media[1]) ? ME.username : 'someone.else';
    return json(res, { items: [{ user: { username: owner }, coauthor_producers: [] }] });
  }
  if (p.startsWith('/api/')) { res.writeHead(404); return res.end(); }

  res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'set-cookie': `ds_user_id=${ME.id}; Path=/` });
  res.end(page(p));
}).listen(PORT, () => console.log(`Mock Instagram on http://localhost:${PORT}`));
