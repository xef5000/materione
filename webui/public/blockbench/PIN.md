# Vendored Blockbench build (pinned)

This folder carries the **unmodified web build of Blockbench v5.1.6**, served
by the editor SPA host at `/blockbench/` (architecture.md §9.3, decision D9).

## Pin

| | |
|---|---|
| Upstream | https://github.com/JannisX11/blockbench |
| Tag | `v5.1.6` |
| Commit | `794e964e966b6783b4e9b98ecbdda5152c0620cc` |
| License | GPL-3.0 (source: the upstream repo; this folder ships only its build output) |

## Build recipe (the one that produced this folder)

```
git clone --depth 1 --branch v5.1.6 https://github.com/JannisX11/blockbench.git
cd blockbench
npm install --no-audit --no-fund
node build.js --target=web
```

Then copy into `webui/public/blockbench/`:

- `index.html`, `favicon.png`, `icon.png`, `icon_full.png`,
  `icon_maskable.png`, `manifest.webmanifest`
- `css/`, `assets/`, `font/`, `icons/`
- `dist/bundle.js` (the 17 MB `bundle.js.map` is deliberately not vendored)

## What is NOT vendored

- `js/`, `lang/`, `keymaps/`, `lib/`, `themes/` — all bundled into
  `dist/bundle.js` by the upstream web build (esbuild).
- `icons/icomoon/icon blockbench.svg` — unreferenced source file whose name
  contains a space the editor listener's path filter rejects.
- `bundle.js.map` — devtools-only; stripped to keep the plugin jar lean.

## Rules (D9)

Blockbench runs **unmodified**: no source patches, no forks. Upgrading is a
pin bump (re-run the recipe above for the new tag). Our integration lives in
`webui/src/lib/blockbench/` as a postMessage bridge + a Blockbench *runtime
plugin* injected into the iframe — code that never touches this folder.
