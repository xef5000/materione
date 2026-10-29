# smallcaps — the first-party GUI title font

`smallcaps.txt` is the bitmap font behind every `text` layer that uses `font: smallcaps` (the
default). It is the single source of truth for the glyph artwork: the Java compiler
(`SmallcapsFont`, the `content` module) and the studio read this same file.

## License and provenance

- **Original artwork.** Every glyph was drawn for this repository, as seven-row pixel art typed
  out in `smallcaps.txt`. No glyph is copied, traced or derived from Mojang's font textures
  (`minecraft:font/ascii.png`), from any incumbent plugin, or from any third-party typeface.
- **License.** The artwork is released under the same terms as the rest of the repository (see
  [`LICENSE`](../../LICENSE)). The project's final license is still an open question (master
  architecture section 14); this file follows whatever that decision is.
- **Scope.** Printable ASCII only (`U+0020`–`U+007E`). The font has no non-ASCII glyphs.

## Metrics

| Property | Value |
|---|---|
| Cell (sheet) | 8 × 16 px; the art sits in the top seven rows |
| Capital height | 6 px (rows 0–5); the baseline is the bottom of row 5 |
| Small-capital height | 5 px (rows 1–5), lowercase, on the same baseline |
| Descender row | row 6 (`,`, `;`, `[`, `]`, `|`, `_`); parentheses and braces stay on the cap band |
| Space advance | 3 px |
| Advance rule | rightmost ink column + 1, then + 1 px of spacing (the client's bitmap rule) |

Lowercase letters are small capitals, not capitals in disguise: `Shop` is a capital `S` followed
by `hop` at the small-cap height.

## Width table

The advance of each printable glyph, in px. The table is derived from the artwork; the golden
`fixtures/gui/smallcaps/advances.txt` pins it, and both implementations test against that file.

Most glyphs advance 5 px. The table lists every exception and every glyph, so a reader can check
one character without running anything.

| Glyph | Advance | Glyph | Advance | Glyph | Advance |
|---|---|---|---|---|---|
| `!` | 2 | `A` | 5 | `a` | 5 |
| `"` | 4 | `B` | 5 | `b` | 5 |
| `#` | 5 | `C` | 5 | `c` | 5 |
| `$` | 4 | `D` | 5 | `d` | 5 |
| `%` | 5 | `E` | 5 | `e` | 5 |
| `&` | 5 | `F` | 5 | `f` | 5 |
| `'` | 2 | `G` | 5 | `g` | 5 |
| `(` | 4 | `H` | 5 | `h` | 5 |
| `)` | 4 | `I` | 4 | `i` | 4 |
| `*` | 4 | `J` | 5 | `j` | 5 |
| `+` | 4 | `K` | 5 | `k` | 5 |
| `,` | 3 | `L` | 5 | `l` | 5 |
| `-` | 4 | `M` | 6 | `m` | 6 |
| `.` | 2 | `N` | 5 | `n` | 5 |
| `/` | 5 | `O` | 5 | `o` | 5 |
| `0` | 5 | `P` | 5 | `p` | 5 |
| `1` | 4 | `Q` | 5 | `q` | 5 |
| `2` | 5 | `R` | 5 | `r` | 5 |
| `3` | 5 | `S` | 5 | `s` | 5 |
| `4` | 5 | `T` | 5 | `t` | 5 |
| `5` | 5 | `U` | 5 | `u` | 5 |
| `6` | 5 | `V` | 5 | `v` | 5 |
| `7` | 5 | `W` | 6 | `w` | 6 |
| `8` | 5 | `X` | 5 | `x` | 5 |
| `9` | 5 | `Y` | 5 | `y` | 5 |
| `:` | 2 | `Z` | 5 | `z` | 5 |
| `;` | 2 | `[` | 3 | `{` | 4 |
| `<` | 4 | `\` | 5 | `\|` | 2 |
| `=` | 4 | `]` | 3 | `}` | 4 |
| `>` | 4 | `^` | 4 | `~` | 5 |
| `?` | 4 | `_` | 5 | space | 3 |
| `@` | 5 | `` ` `` | 3 | | |

## Changing the font

1. Edit `smallcaps.txt`. Keep the format: seven rows per glyph, `#` for ink, the header keys
   unchanged (the cell size is fixed by `SmallcapsFont`).
2. Run the content tests. `SmallcapsFontTest` reports the glyphs whose advance changed.
3. Update `fixtures/gui/smallcaps/advances.txt` to the new table. The Java and studio tests both
   read it, so the two sides cannot disagree silently.
4. Regenerate the goldens that embed the sheet (`fixtures/golden/gui/shop-compiled.txt` records
   the sheet's SHA-256) and check the glyphs at 1× in the probe (`docs/probe.md`).
