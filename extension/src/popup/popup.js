(() => {
  'use strict';

  const api = globalThis.browser ?? globalThis.chrome;
  const USERNAME_RE = /^[A-Za-z0-9._]{1,30}$/;
  const DEFAULTS = {
    enabled: true,
    manualUsername: '',
    detectedUsername: '',
    redirectTo: 'home',
    hideNavButton: true,
    blockedCount: 0,
  };

  // Lets the popup be opened as a plain page (for previews/screenshots).
  const memory = { ...DEFAULTS, detectedUsername: 'your.username', blockedCount: 128 };
  const storage = api?.storage?.local ?? {
    get: async (d) => ({ ...d, ...memory }),
    set: async (v) => Object.assign(memory, v),
  };

  const $ = (id) => document.getElementById(id);
  const app = document.querySelector('.app');
  let state = { ...DEFAULTS };

  function render() {
    $('enabled').checked = state.enabled;
    app.classList.toggle('disabled', !state.enabled);

    $('status').classList.toggle('off', !state.enabled);
    $('status-title').textContent = state.enabled ? 'Protection is on' : 'Protection is paused';
    $('status-sub').textContent = state.enabled
      ? "Other people's reels are blocked"
      : 'Reels are not being blocked right now';

    $('count').textContent = Number(state.blockedCount || 0).toLocaleString('en-US');

    const manual = (state.manualUsername || '').trim().replace(/^@/, '');
    const effective = USERNAME_RE.test(manual) ? manual : state.detectedUsername;
    $('account-name').textContent = effective ? '@' + effective : 'Not detected yet';
    $('account-source').textContent = effective
      ? (USERNAME_RE.test(manual) ? 'Set manually' : 'Detected automatically')
      : 'Open instagram.com while logged in';
    $('avatar').textContent = effective ? effective[0] : '?';

    if (document.activeElement !== $('manualUsername')) $('manualUsername').value = manual;
    $('hideNavButton').checked = state.hideNavButton;

    document.querySelectorAll('[data-redirect]').forEach((b) => {
      b.setAttribute('aria-checked', String(b.dataset.redirect === state.redirectTo));
    });
  }

  async function save(patch) {
    state = { ...state, ...patch };
    render();
    await storage.set(patch);
  }

  $('enabled').addEventListener('change', (e) => save({ enabled: e.target.checked }));
  $('hideNavButton').addEventListener('change', (e) => save({ hideNavButton: e.target.checked }));

  document.querySelectorAll('[data-redirect]').forEach((b) =>
    b.addEventListener('click', () => save({ redirectTo: b.dataset.redirect })),
  );

  let debounce;
  $('manualUsername').addEventListener('input', (e) => {
    const v = e.target.value.trim().replace(/^@/, '');
    const ok = v === '' || USERNAME_RE.test(v);
    e.target.parentElement.classList.toggle('invalid', !ok);
    clearTimeout(debounce);
    if (ok) debounce = setTimeout(() => save({ manualUsername: v }), 300);
  });

  api?.storage?.onChanged?.addListener((changes, area) => {
    if (area !== 'local') return;
    for (const [k, { newValue }] of Object.entries(changes)) state[k] = newValue;
    render();
  });

  try { $('version').textContent = 'v' + api.runtime.getManifest().version; } catch { /* preview */ }

  storage.get(DEFAULTS).then((s) => {
    state = { ...DEFAULTS, ...s };
    render();
  });
})();
