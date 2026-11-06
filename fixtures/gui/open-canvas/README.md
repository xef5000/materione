# Open canvas research pack (#275)

The inputs of the probe runs behind [`gui.md`](../../../docs/architecture/gui.md) §9.8–§9.15. **Research input, not a golden:** no test
reads this folder, and nothing here is shipped.

| Path | What it is |
|---|---|
| `gen.mjs` | Generates the client resource pack `r275` (fonts, test textures, shift font) and the titles. `node gen.mjs <outDir>` writes `<outDir>/pack`, `<outDir>/titles.json` and `<outDir>/json`. Deterministic. |
| `json/font/*.json`, `json/titles.json` | The exact provider JSON and the exact title components the runs used (private-use characters escaped). One font per case: `c1r6`, `c1r1` (markers around a 6-row and a 1-row screen), `c2*` (the `ascent ≤ height` rule and its limits), `c3` (530 × 530 in 9 tiles), `c4` (1024 × 1024 in 16 tiles), `c5on`, `c6first`, `c7text`. `titles.json` also holds each glyph's computed layout (`x`, `y`, `ascent`, `height`, `shift`). |
| `r275_click.yml` | The Materione GUI the click test opened (`gui.click_outside` and a `gui.click` on a gem, each running `say`). Copy to the probe server's `sample` pack `guis/` folder, then `materione reload`. |
| `runs/` | The Node drivers that placed the titles, took the captures and compared them with the model. |

The textures are not committed: `gen.mjs` regenerates them byte for byte.

## Reproducing a run

Boot the probe (`docs/probe.md`), then from the repository root, with `MTR_R275_OUT` unset (the default is `harness/build/probe/r275`):

```bash
node fixtures/gui/open-canvas/gen.mjs harness/build/probe/r275
node scripts/probe/probe.mjs up && node scripts/probe/probe.mjs client && node scripts/probe/probe.mjs join
node fixtures/gui/open-canvas/runs/reload.mjs      # copies the pack into the client's resourcepacks, reloads
```

`reload.mjs` assumes the pack `file/r275` is already selected in the client. Select it once (the server's own pack stays on top):

```bash
node scripts/probe/probe.mjs exec "def r = mc.getResourcePackRepository(); r.reload(); r.setSelected(['vanilla','file/r275']); mc.reloadResourcePacks(); return 'ok'"
```

Then run a driver, for example `node fixtures/gui/open-canvas/runs/b1.mjs` (markers at four scales, comparing every marker box with the
formula). Each driver prints one line per comparison (`MATCH` / `DIFF`) or a pixel count (`mismatch 0`). The captures land in
`harness/build/probe/r275/shots/`. `runs/mkev.mjs` crops and halves the ones kept under `docs/screenshots/gui-open-canvas/`.

| Driver | Question |
|---|---|
| `b1.mjs`, `b2.mjs`, `b3.mjs` | Off-screen art around the 6-row and 1-row screens, scales 1–4, windows 1920 × 1080, 1280 × 720, 854 × 480 (§9.9, §9.12) |
| `b4.mjs`, `b5.mjs`, `b11.mjs` | Ascent rule, padding, the 256-px cell, a failing font (§9.10) |
| `b6.mjs`, `b10.mjs` | Tiles: 530 × 530 and 1024 × 1024, pixel-exact (§9.11) |
| `b7.mjs`, `b11.mjs` | Draw order against slot items and the Inventory label (§9.13) |
| `b9.mjs` | Clicks outside the container (§9.14); needs `r275_click.yml` loaded and `gamemode survival Probe` |
| `b8.mjs`, `b12.mjs` | A title without art; the first glyph at the pen (§9.15) |

The drivers hard-code nothing machine-specific; they need the probe's client window (the bridge on 9876) and RCON.
