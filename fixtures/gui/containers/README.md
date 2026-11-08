# Container research pack (#289)

The inputs of the probe runs behind [`gui.md`](../../../docs/architecture/gui.md) §12.2.6 (a hopper and a dispenser on the real
client). **Research input, not a golden:** no test reads this folder, and nothing here is shipped. Same spirit as
[`../open-canvas/`](../open-canvas/README.md) (#275), whose conventions it reuses.

| Path | What it is |
|---|---|
| `gen.mjs` | Generates the client resource pack `r289` (one font per case: the shift `space` providers and the `bitmap` providers together, as the compiler emits them), the server-side titles (`titles/<case>.json`, Adventure JSON read by the probe plugin) and `layouts.json` (each glyph's position, ascent, advance and the title's total width). `node gen.mjs <outDir>`. Deterministic; the textures are not committed. |
| `runs/lib.mjs` | Helpers: install the pack and titles, open a window, read the screen, grab a lossless frame, compare markers and the position-coded image with the model. |
| `runs/install.mjs` | Generate, install into the probe client, select the pack. Run once after `probe client` + `join`. |
| `runs/u1.mjs` | U1, U2: the dispenser's centred title with and without the trailing shift; marker placement on both screens at three scales. |
| `runs/u3-sheets.mjs` | U3: where the vanilla player-inventory art begins in `hopper.png` and `dispenser.png` (needs the sheets extracted from the 26.1.2 client jar into `harness/build/probe/r289/vanilla/`). |
| `runs/u3.mjs` | U2, U3, U4: each live screen against its sheet; tiles across the screen edges; items and the label over full-screen art; a lone marker over slots. |
| `runs/clicks.mjs` | Real clicks by slot id and outside the screen, as the server's `InventoryClickEvent` sees them. |
| `runs/u5.mjs` | U5: dynamic title refreshes on an open hopper and dispenser. |
| `runs/mkev.mjs` | Crops and halves the captures kept under [`docs/screenshots/gui-containers/`](../../../docs/screenshots/gui-containers/). |

## Reproducing a run

The server side is a test-only plugin, `harness/src/containerProbe` (`:harness:containerProbeJar`). From the repository root:

```bash
./gradlew :plugin:shadowJar :harness:containerProbeJar
cp harness/build/libs/materione-harness-*-container-probe.jar harness/build/probe/server/plugins/MaterioneContainerProbe.jar
node scripts/probe/probe.mjs up --no-build && node scripts/probe/probe.mjs client && node scripts/probe/probe.mjs join
node fixtures/gui/containers/runs/install.mjs       # pack + titles; reloads the client's resources
node fixtures/gui/containers/runs/u1.mjs            # then u3.mjs, clicks.mjs, u5.mjs
```

The probe plugin must be in `plugins/` before `up` (the server does not load a jar added later). Each driver prints one line per
comparison (`MATCH` / `DIFF`, a pixel count, or the server's click line); the captures land in `harness/build/probe/r289/shots/`.

`u3-sheets.mjs` and the plain-screen comparison of `u3.mjs` read the vanilla sheets from `harness/build/probe/r289/vanilla/`. Extract
them from the launcher's 26.1.2 client jar (a read, nothing is downloaded or committed):

```powershell
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [System.IO.Compression.ZipFile]::OpenRead("$env:APPDATA\.minecraft\versions\26.1.2\26.1.2.jar")
New-Item -ItemType Directory -Force harness\build\probe\r289\vanilla | Out-Null
foreach ($n in 'hopper','dispenser','generic_54') {
  [System.IO.Compression.ZipFileExtensions]::ExtractToFile($z.GetEntry("assets/minecraft/textures/gui/container/$n.png"), "harness\build\probe\r289\vanilla\$n.png", $true)
}
$z.Dispose()
```

`install.mjs` regenerates `harness/build/probe/r289/` (it removes the folder first), so extract the sheets after it.
