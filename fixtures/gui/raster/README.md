# GUI raster vectors

The shared contract between the **Java compiler** (`ArgbCanvas`, bevel painter, blit,
flattening - issue #184) and the **TypeScript studio renderer**
(`webui/src/lib/gui/raster/`, issue #186). Spec: `docs/architecture/gui.md` section 4. The
studio preview is not an approximation of the compiled background, it is the same function;
these files are how that stays true.

Each vector is a pair with the same base name:

| File | Contents |
|---|---|
| `<name>.json` | the input: canvas size, optional slot-grid `rows`, optional inline `images`, and `layers` |
| `<name>.png` | the expected output, RGBA8, straight (non-premultiplied) alpha |

Both implementations must reproduce every `<name>.png` **pixel for pixel** (compare decoded
ARGB values, not PNG bytes; PNG encoding is a separate, JDK-dependent concern). The set only
grows. `generate-vectors.mjs` regenerates the files from an independent reference
implementation (`node fixtures/gui/raster/generate-vectors.mjs`); regeneration is for review,
the committed bytes are the fixture, and CI never regenerates.

## Input format

```jsonc
{
  "name": "chest-6-rows",              // equals the file base name
  "description": "...",                // prose, ignored by tests
  "canvas": { "width": 176, "height": 222 },
  "rows": 6,                           // only needed by `slots` layers (the slot grid depends on it)
  "images": {                          // bitmaps by the reference `image`/`paint` layers use
    "gui/test/checker": {
      "width": 2, "height": 2,
      "pixels": ["#FF0000", "#00FF00", "#0000FF", "#80FFFFFF"]   // row-major, one color per pixel
    }
  },
  "layers": [ /* gui.md section 2 layer objects, bottom to top */ ]
}
```

`layers` use exactly the layer shape of a GUI file (gui.md section 2), so a vector is a GUI
document with its textures inlined. Vectors only contain *valid* layers; validation and
quarantine are not part of this contract. Fields a renderer does not know are ignored.

**Colors** are strings: `#RRGGBB` (opaque) or `#AARRGGBB` (alpha first, as the gui-components descriptions say). Internally a
color is one ARGB integer with straight alpha. **Alpha 0 is always stored as `0x00000000`**:
transparent has exactly one representation, so a pixel of a bitmap with alpha 0 is never
written to the canvas, whatever its RGB.

The expected PNG has exactly the size in `canvas`.

## Which layers draw

Only the `RASTER`-target components draw into the vector's canvas: `panel`, `slots`, `image`,
`paint`, `button`. `text`, `item` and unknown components draw nothing (their targets are the
title and the container slots). A layer with `"visible": false` is skipped.

## Rules

All coordinates are integer GUI pixels, origin top-left, +x right, +y down. Everything is
clipped to the canvas: a pixel outside it is dropped, and the clip never changes which source
pixel or bevel part a surviving pixel gets.

### Layer compositing

Each raster layer is painted onto its **own transparent canvas** of the vector's size; that
layer canvas is then composited onto the accumulating output, bottom to top, with the layer's
`opacity` (0..100, default 100). The output starts fully transparent.

For every pixel of the layer canvas with alpha > 0:

1. **Opacity.** `sa = floor((alpha * opacity + 50) / 100)` (round half up). If `sa == 0` the
   pixel is skipped.
2. **Source-over** onto the output pixel `d = (dr, dg, db, da)` with source `(sr, sg, sb, sa)`,
   straight alpha, integers only:
   * `sa == 255` or `da == 0`: the result is the source `(sr, sg, sb, sa)` exactly.
   * otherwise `keep = 255 - sa`, `den = sa*255 + da*keep`,
     `outA = floor((den + 127) / 255)`,
     `outC = floor((2 * (sc*sa*255 + dc*da*keep) + den) / (2 * den))` for each of R, G, B.

Because a layer is painted on a transparent canvas first, painting a layer's own pixels never
blends them with each other, and `opacity` is a true group opacity. Within one layer every
pixel is written at most once (the bevel parts below partition the rectangle; slot wells tile
without overlap).

### Bevel painter (`panel`, `button`, `slots`)

Style `{ fill, highlight, shadow, outline }`, each a color string; a missing or unparsable
field takes the component default:

| Component | fill | highlight | shadow | outline |
|---|---|---|---|---|
| `panel`, `button` | `#C6C6C6` | `#FFFFFF` | `#555555` | `#000000` |
| `slots` | `#8B8B8B` | `#FFFFFF` | `#373737` | (unused) |

**Raised** (`panel` with edge `t = 2`, `button` with `t = 1`) over the `w x h` rectangle at
`(x, y)`; pixel `(lx, ly)` is its offset inside the rectangle, `0 <= lx < w`, `0 <= ly < h`:

1. **Ring.** If `lx` is `0` or `w-1`, or `ly` is `0` or `h-1`: the pixel is a *corner* when
   both coordinates are on the ring (the four corners of the rectangle) and is **not drawn**
   (transparent corner); every other ring pixel is `outline`.
2. **Inside the ring**, with `ix = lx - 1`, `iy = ly - 1`, inner size `iw = w - 2`, `ih = h - 2`:
   * `light` = `iy < t` or `ix < t` (the first `t` rows or columns);
   * `dark` = `iy >= ih - t` or `ix >= iw - t` (the last `t` rows or columns);
   * `light` and `dark` both (the top-right and bottom-left corner blocks, `t x t`), or neither
     (the interior): **fill**; `light` only: **highlight**; `dark` only: **shadow**.

A rectangle with `w <= 0` or `h <= 0` draws nothing. Rectangles of width or height 1 or 2 are
all ring, all corners, or a corner-skipped ring; the rules above give the answer, no special
cases.

**Inset** (slot wells, always 18 x 18 for `slots`, no outline), pixel `(lx, ly)` of `w x h`:
`dark` = `ly == 0` or `lx == 0`; `light` = `ly == h-1` or `lx == w-1`. Both: **fill** (the
top-right and bottom-left pixels); `dark` only: **shadow** (so the top and left edges are the
dark ones); `light` only: **highlight**; neither: **fill**.

### Chest geometry (`slots`)

The canvas of a chest GUI is `176 x (114 + rows*18)`. A `slots` layer with `region` draws one
inset well per slot, the 18 x 18 well at `(x-1, y-1)` for the slot's 16 x 16 item origin `(x, y)`:

| Region | Item origins |
|---|---|
| `container` (also the value of an absent `region`) | `x = 8 + col*18`, `y = 18 + row*18`; `col` 0..8, `row` 0..rows-1 |
| `player` | inventory: `x = 8 + col*18`, `y = rows*18 + 31 + row*18`, `row` 0..2; hotbar: `x = 8 + col*18`, `y = rows*18 + 89` |
| `all` | both |

(6 rows: inventory at `y = 139, 157, 175`, hotbar at `y = 197`, exactly vanilla.) Wells tile the
grid without overlapping or leaving gaps, so their pixels are written once each.

### `image`

Fields `image` (the key into the vector's `images`), `x`, `y` (default 0), `width`, `height`
(default: the bitmap's own size). A missing bitmap draws nothing. Destination pixel `(i, j)`,
`0 <= i < width`, `0 <= j < height`, lands at `(x+i, y+j)` and samples source pixel

```
sx = floor((2*i + 1) * sourceWidth  / (2 * width))
sy = floor((2*j + 1) * sourceHeight / (2 * height))
```

(nearest neighbour, sample point at the destination pixel's center: integer upscales replicate
evenly, downscales are symmetric). The sampled source pixel is painted onto the layer canvas.
A source pixel with alpha 0 writes nothing.

### `paint`

Field `image` (key into `images`). The bitmap is copied 1:1 with its top-left at `(0, 0)` onto
the layer canvas, never scaled; a bitmap larger than the canvas is clipped, a smaller one leaves
the rest transparent. A missing bitmap draws nothing.

## The vectors

| Vector | What it pins |
|---|---|
| `button-1px-bevel` | raised 1 px bevel at sizes 10x6, 20x12, 5x5, 3x3, 2x2, 40x10 |
| `panel-2px-bevel` | raised 2 px bevel, including panels too small for a clean interior |
| `button-custom-style` | full and partial `style` blocks |
| `slots-container-1row`, `slots-player-3rows`, `slots-custom-style` | the slot grid per region and the inset bevel with partial style |
| `chest-6-rows`, `chest-1-row` | the starter look of a new GUI (panel + all-region slots) |
| `chest-with-button` | layer order, and `opacity` on a bevel layer |
| `blend-translucent` | the full source-over formula over translucent and opaque backgrounds |
| `opacity-rounding` | round-half-up opacity edge cases (including alpha 1) |
| `image-nearest-neighbour` | 4x/3x upscales, non-integer stretch, downscale, natural size, translucent texels, missing image |
| `paint-layer` | 1:1 copy, smaller-than-canvas bitmap, opacity |
| `clipping` | layers partly or entirely outside the canvas, zero-size layers |
| `visibility-and-targets` | hidden layers; text/item/unknown draw nothing |

## Consuming the vectors

* **TypeScript:** `webui/test/gui-raster.spec.ts` renders each JSON through
  `webui/src/lib/gui/raster/vector.ts` and compares against the decoded PNG.
* **Java (issue #184):** read each `<name>.json`, build the layers through the gui type's
  component parsers, flatten with `ArgbCanvas`, and compare the ARGB ints with the decoded
  expected PNG (`ImageIO` is fine in tests). The same files are the golden source for the
  compiled background in the pack goldens.

Do not edit a `.png` by hand. To change a rule: amend this README and `gui.md` section 4,
change `generate-vectors.mjs`, regenerate, and fix both implementations in the same change.
