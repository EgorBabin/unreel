/*
 * Unreel — content script
 *
 * Runs on instagram.com. Watches every (SPA) navigation and:
 *   • kicks you out of the Reels feed (/reels/…)
 *   • kicks you out of any single reel (/reel/<code>) that is not yours
 *   • kicks you out of other people's profile "Reels" tab (/<user>/reels/)
 *   • lets you watch and edit your own reels normally
 *
 * Ownership of a reel is decided in this order:
 *   1. URL hint            /<you>/reel/<code>/
 *   2. Instagram web API   media info → owner + collaborators
 *   3. DOM                 author link rendered on the page
 *   4. Context             you opened it from your own profile
 * If nothing can prove the reel is yours, it is blocked.
 */
(() => {
  'use strict';

  const api = globalThis.browser ?? globalThis.chrome;
  const IG_APP_ID = '936619743392459';
  const SHORTCODE_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
  const USERNAME_RE = /^[A-Za-z0-9._]{1,30}$/;
  const DOM_LOOKUP_TIMEOUT = 4000;

  // First path segments that are Instagram features, not usernames.
  const RESERVED = new Set([
    'reels', 'reel', 'p', 'tv', 'explore', 'direct', 'accounts', 'stories', 'about',
    'legal', 'developer', 'web', 'api', 'challenge', 'emails', 'session', 'graphql',
    'ar', 'topics', 'locations', 'directory', 'your_activity', 'notifications',
    'create', 'privacy', 'oauth', 'lite', 'static', 'invites', 'nametag', 'qr',
    'threads', 'meta-verified', 'sw', 'data', 'download', 'help', 'press', 'blog',
  ]);

  const DEFAULTS = {
    enabled: true,
    manualUsername: '',
    detectedUsername: '',
    detectedForUid: '',
    redirectTo: 'home', // 'home' | 'profile'
    hideNavButton: true,
    blockedCount: 0,
  };

  let settings = { ...DEFAULTS };
  let settingsReady = false;
  let lastHref = null;
  let lastPath = null;
  let checkToken = 0;
  let detectPromise = null;
  const ownerCache = new Map(); // shortcode -> string[] (owner + collaborators, lowercase)

  /* ───────────────────────── helpers ───────────────────────── */

  const eq = (a, b) => !!a && !!b && a.toLowerCase() === b.toLowerCase();
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  function ownUsername() {
    const manual = (settings.manualUsername || '').trim().replace(/^@/, '');
    if (USERNAME_RE.test(manual)) return manual;
    const uid = getCookie('ds_user_id');
    if (settings.detectedUsername && (!uid || uid === settings.detectedForUid)) {
      return settings.detectedUsername;
    }
    return null;
  }

  function getCookie(name) {
    const m = document.cookie.match(new RegExp('(?:^|;\\s*)' + name + '=([^;]*)'));
    return m ? decodeURIComponent(m[1]) : null;
  }

  function classify(pathname) {
    const parts = pathname.split('/').filter(Boolean);
    const first = (parts[0] || '').toLowerCase();
    if (first === 'reels') return { type: 'reel', code: parts[1] || null, feed: true };
    if (first === 'reel' && parts[1]) return { type: 'reel', code: parts[1] };
    if (parts.length >= 2 && !RESERVED.has(first) && parts[1] === 'reels') {
      return { type: 'profileReels', user: parts[0] };
    }
    if (parts.length >= 3 && !RESERVED.has(first) && parts[1] === 'reel') {
      return { type: 'reel', code: parts[2], hintUser: parts[0] };
    }
    return { type: 'other' };
  }

  function shortcodeToMediaId(code) {
    let id = 0n;
    for (const ch of code.slice(0, 11)) {
      const i = SHORTCODE_ALPHABET.indexOf(ch);
      if (i < 0) return null;
      id = id * 64n + BigInt(i);
    }
    return id.toString();
  }

  // Firefox content scripts must use content.fetch to send the page's cookies.
  async function igJson(path) {
    const doFetch =
      typeof content !== 'undefined' && content && typeof content.fetch === 'function'
        ? content.fetch.bind(content)
        : fetch;
    const res = await doFetch(new URL(path, location.origin).href, {
      credentials: 'include',
      headers: { 'X-IG-App-ID': IG_APP_ID, 'X-Requested-With': 'XMLHttpRequest' },
    });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    return JSON.parse(await res.text());
  }

  /* ───────────────────────── identity ───────────────────────── */

  function detectOwnUsername() {
    if (detectPromise) return detectPromise;
    detectPromise = (async () => {
      const uid = getCookie('ds_user_id');
      let name = null;
      if (uid) {
        try {
          const j = await igJson(`/api/v1/users/${uid}/info/`);
          name = j?.user?.username ?? null;
        } catch { /* try next */ }
      }
      if (!name) {
        try {
          const j = await igJson('/api/v1/accounts/edit/web_form_data/');
          name = j?.form_data?.username ?? null;
        } catch { /* try next */ }
      }
      if (!name) name = await waitFor(detectOwnFromDom, 5000);
      if (name && USERNAME_RE.test(name) && (name !== settings.detectedUsername || (uid || '') !== settings.detectedForUid)) {
        settings.detectedUsername = name;
        settings.detectedForUid = uid || '';
        api.storage.local.set({ detectedUsername: name, detectedForUid: uid || '' });
      }
      return name;
    })().finally(() => { setTimeout(() => (detectPromise = null), 30000); });
    return detectPromise;
  }

  // The navigation sidebar/tab bar has exactly one link with an avatar <img>: your profile.
  function detectOwnFromDom() {
    const anchor = document.querySelector('a[href="/explore/"], a[href="/direct/inbox/"]');
    let node = anchor;
    for (let depth = 0; node && depth < 10; depth++, node = node.parentElement) {
      for (const a of node.querySelectorAll('a[href]')) {
        const m = (a.getAttribute('href') || '').match(/^\/([A-Za-z0-9._]{1,30})\/$/);
        if (m && !RESERVED.has(m[1].toLowerCase()) && a.querySelector('img')) return m[1];
      }
    }
    return null;
  }

  async function resolveOwnUsername() {
    return ownUsername() ?? (await detectOwnUsername());
  }

  /* ───────────────────────── ownership ───────────────────────── */

  async function fetchOwners(code) {
    if (ownerCache.has(code)) return ownerCache.get(code);
    const id = shortcodeToMediaId(code);
    if (!id) return null;
    try {
      const j = await igJson(`/api/v1/media/${id}/info/`);
      const item = j?.items?.[0];
      if (!item?.user?.username) return null;
      const owners = [item.user.username, ...(item.coauthor_producers || []).map((u) => u.username)]
        .filter(Boolean)
        .map((u) => u.toLowerCase());
      ownerCache.set(code, owners);
      return owners;
    } catch {
      return null;
    }
  }

  function ownerFromDom() {
    const roots = [...document.querySelectorAll('[role="dialog"]'), document.querySelector('main')].filter(Boolean);
    for (const root of roots) {
      for (const a of root.querySelectorAll('a[href]')) {
        const m = (a.getAttribute('href') || '').match(/^\/([A-Za-z0-9._]{1,30})\/?$/);
        if (!m || RESERVED.has(m[1].toLowerCase())) continue;
        if (eq(a.textContent.trim(), m[1])) return m[1];
      }
    }
    return null;
  }

  async function waitFor(fn, timeout) {
    const end = Date.now() + timeout;
    while (Date.now() < end) {
      const v = fn();
      if (v) return v;
      await sleep(200);
    }
    return null;
  }

  async function isOwnReel(route, prevPath) {
    const me = await resolveOwnUsername();
    if (!me) return false; // unknown identity → strict
    if (route.hintUser) return eq(route.hintUser, me);
    if (!route.code) return false; // bare /reels/ feed

    const owners = await fetchOwners(route.code);
    if (owners) return owners.includes(me.toLowerCase());

    const domOwner = await waitFor(ownerFromDom, DOM_LOOKUP_TIMEOUT);
    if (domOwner) return eq(domOwner, me);

    // Last resort: you clicked it from your own profile grid.
    return !route.feed && !!prevPath && prevPath.toLowerCase().startsWith(`/${me.toLowerCase()}/`);
  }

  /* ───────────────────────── shield & UI ───────────────────────── */

  let shieldEl = null;
  let pauseTimer = null;

  function shield() {
    document.documentElement.classList.add('unreel-shield');
    if (!shieldEl) {
      shieldEl = document.createElement('div');
      shieldEl.id = 'unreel-shield';
      shieldEl.innerHTML =
        '<div class="unreel-card"><div class="unreel-spinner"></div>' +
        '<div class="unreel-title">Unreel</div>' +
        '<div class="unreel-sub">Checking whose reel this is…</div></div>';
    }
    if (!shieldEl.isConnected) document.documentElement.appendChild(shieldEl);
    if (!pauseTimer) {
      pauseTimer = setInterval(() => {
        document.querySelectorAll('video').forEach((v) => { if (!v.paused) v.pause(); });
      }, 100);
    }
  }

  function unshield(resume) {
    document.documentElement.classList.remove('unreel-shield');
    shieldEl?.remove();
    if (pauseTimer) {
      clearInterval(pauseTimer);
      pauseTimer = null;
      if (resume) {
        const v = [...document.querySelectorAll('video')].find((el) => {
          const r = el.getBoundingClientRect();
          return r.width > 0 && r.top < innerHeight && r.bottom > 0;
        });
        v?.play().catch(() => {});
      }
    }
  }

  function toast(text) {
    const el = document.createElement('div');
    el.id = 'unreel-toast';
    el.innerHTML = '<span class="unreel-toast-dot"></span>';
    el.append(document.createTextNode(text));
    (document.body || document.documentElement).appendChild(el);
    requestAnimationFrame(() => el.classList.add('show'));
    setTimeout(() => { el.classList.remove('show'); setTimeout(() => el.remove(), 400); }, 2600);
  }

  let styleEl = null;
  function applyNavStyle() {
    const me = ownUsername();
    let css = '';
    if (settings.enabled && settings.hideNavButton) {
      css += 'a[href="/reels/"]{display:none!important}';
      css += me
        ? `a[href$="/reels/"]:not([href="/${me}/reels/"]){display:none!important}`
        : 'a[href$="/reels/"]{display:none!important}';
    }
    if (!styleEl) {
      styleEl = document.createElement('style');
      styleEl.id = 'unreel-nav-style';
    }
    styleEl.textContent = css;
    if (!styleEl.isConnected) (document.head || document.documentElement).appendChild(styleEl);
  }

  /* ───────────────────────── decisions ───────────────────────── */

  function block(route) {
    const me = ownUsername();
    let target = '/';
    if (route.type === 'profileReels') target = `/${route.user}/`;
    else if (settings.redirectTo === 'profile' && me) target = `/${me}/`;

    settings.blockedCount = (settings.blockedCount || 0) + 1;
    api.storage.local.set({ blockedCount: settings.blockedCount });
    try { sessionStorage.setItem('unreel:toast', '1'); } catch { /* private mode */ }
    location.replace(target);
  }

  async function evaluate(force = false) {
    if (!settingsReady) return;
    const href = location.href;
    if (href === lastHref && !force) return;
    const prevPath = lastPath;
    lastHref = href;
    lastPath = location.pathname;

    const token = ++checkToken;
    const route = classify(location.pathname);

    if (!settings.enabled || route.type === 'other') {
      unshield(false);
      return;
    }

    shield();

    if (route.type === 'profileReels') {
      const me = await resolveOwnUsername();
      if (token !== checkToken) return;
      if (me && eq(route.user, me)) unshield(true);
      else block(route);
      return;
    }

    const own = await isOwnReel(route, prevPath);
    if (token !== checkToken) return;
    if (own) unshield(true);
    else block(route);
  }

  /* ───────────────────────── boot ───────────────────────── */

  // Hide reels instantly on hard loads, before settings arrive.
  if (classify(location.pathname).type !== 'other') shield();

  api.storage.local.get(DEFAULTS).then((stored) => {
    settings = { ...DEFAULTS, ...stored };
    settingsReady = true;
    applyNavStyle();
    evaluate(true);
    if (!ownUsername()) detectOwnUsername().then(applyNavStyle);
  });

  api.storage.onChanged.addListener((changes, area) => {
    if (area !== 'local') return;
    for (const [k, { newValue }] of Object.entries(changes)) settings[k] = newValue;
    applyNavStyle();
    if ('enabled' in changes || 'manualUsername' in changes) evaluate(true);
  });

  // Instagram is a single-page app: watch for URL changes.
  setInterval(() => evaluate(false), 250);
  addEventListener('popstate', () => evaluate(false));

  document.addEventListener('DOMContentLoaded', () => {
    applyNavStyle();
    let pending = false;
    try { pending = sessionStorage.getItem('unreel:toast') === '1'; sessionStorage.removeItem('unreel:toast'); } catch { /* ignore */ }
    if (pending) toast('Reel blocked — only your own reels are allowed');
  });
})();
