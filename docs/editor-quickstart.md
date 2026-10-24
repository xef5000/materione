# Editor quickstart

The built-in web editor lets you create and edit content from the browser —
no YAML knowledge required, no restart needed, no ports to open. Everything
you save reaches your server in about a second.

## 1. Open the editor

1. In-game or from the console, run:

   ```
   /materione editor
   ```

2. A clickable URL prints — by default it points at the hosted editor,
   `https://editor.materione.com/s/…#…`. Open it in a desktop browser
   (Chrome/Edge 133+, Firefox 129+, or Safari 17+). Your server dials out
   to the relay; nothing listens inbound, so this works identically behind
   NAT, shared hosting, or a proxy network. If you are on the same LAN as
   the server, the editor automatically prefers the faster local listener
   when one is reachable.

3. The first connection asks for trust in game: an **[Approve]** /
   **[Deny]** prompt appears for every admin online (and the console).
   Approve it within two minutes. Choose **[Approve & trust this browser]**
   to skip the prompt for that browser next time (revoke later with
   `/materione editor revoke <prefix>`). The part of the URL after `#` is
   the session key — it never leaves your browser; do not share the URL.

Prefer everything local? `/materione editor local` always opens the
embedded listener (`editor_host`/`editor_port` in `settings.yml`, loopback
by default) — the power-user and air-gapped path, no cloud involved. If the
relay is ever unreachable, `/materione editor` falls back to it
automatically and says so. Servers can also point `relay_base_url` at their
own relay instance (self-hosting: `cloud/editor-worker/README.md`).

## 2. Create your first item

1. Open **Content** and choose **Create your first item**.
2. Name the project once, then name the item. Materione creates the pack,
   item document, and display name in one operation; no paths are shown.
3. The Item Studio groups controls by Identity, Appearance, Gameplay, and
   Behavior. Pick a **material** and choose a **texture** — the picker lists the pack's
   textures with thumbnails, the upload button takes a PNG straight
   from your disk, and **Edit pixels** opens Materione's own pixel editor
   (pencil, fill, eyedropper, shapes, mirror, undo/redo) without leaving the item.
4. Save. Only the fields you changed are written, and they are patched
   losslessly, so comments and anything the studio does not show survive
   untouched — a footer tells you when a field is only editable in Advanced Files.
   The pipeline dock shows the targeted reload; within ~2 s the
   item is live on the server. Grab it with
   `/materione dev stack <pack>:<item>` to check the look.

Blocks, furniture and recipes work the same way — pick their tab at the top of
Content. Each type has its own studio, and a placement item is generated
automatically for blocks and furniture, so everything you create is grabbable in
the item browser.

## 3. Finding things once the pack grows

Content is a file explorer, one tab per content type, with the pack switcher in
the top-left corner. Make folders if you want them (they are organisational only —
ids do not change), double-click an entry to open its studio, and use the toolbar
search, F2 to rename, or the right-click menu to duplicate, move, delete, or reveal
an entry in Advanced Files. **Advanced Files** keeps the tree, the generic form,
raw YAML, revisions and assets for everything a studio deliberately does not touch.

## 4. Model with Blockbench

Choose **Create model** or **Edit model** from the item's Appearance section.
The pinned, unmodified Blockbench build opens as a workbench over the item you
are editing — no start screen and no format picker: your existing `.bbmodel` is
reopened, or a correctly-formatted starter project is created for you with the
item's texture already attached (works offline; the build and its pin are
documented in `webui/public/blockbench/PIN.md`).

- **Model → save back:** build your model and click **Save**. Every target — the
  exported Java model, its textures, the `.bbmodel` project source and the item
  field that points at it — is derived from the item you opened it from; open
  **Advanced targets** only if you need to override them. One atomic save
  triggers the usual targeted reload — the model is in game immediately, and a
  render is cached so the studio can show it without reopening Blockbench.
- **Existing project:** use **import .bbmodel** to load an external
  Blockbench project into the embedded build, then save it back as above.
  Standalone Blockbench, for free modelling unattached to any item, stays in
  Advanced Files.

`.bbmodel` sources are authoring state: they stay in your pack folder and
are never shipped to clients.

## 5. Glyphs, emoji, sounds and paintings

**Glyphs** are custom pictures in text — emoji for chat, icons for names.
Create one under **Glyphs**, pick a texture (the preview shows exactly how
it renders at its ascent/height) and save. You never pick a codepoint: the
server allocates one durably and the preview shows it. Add
**placeholders** (like `:heart:`) and turn on **emoji** to use it in chat —
permitted players type the placeholder (tab-completion offers it) and it
renders in chat, item names, signs, books and anvil renames. Glyphs without
an explicit font are referenced from the client's default font, so they
render in every text context; `font:` is only for separating glyphs into a
custom font of your own. Pixel-precise layout composes with `<shift:-4>`
tags. Players without your pack simply see the placeholder text, never
empty boxes.

**Sounds** are one event per file: list your `.ogg` files (uploaded under
`assets/sounds/`, playable right in the studio's preview), set
volume/pitch/subtitle, then check it in game with
`/materione dev sound <pack>:<id>`. A sound with a **jukebox** section
(description, duration, comparator output) becomes a music-disc song: point
an item's **jukebox song** field at it and discs play it in jukeboxes.

**Paintings** take a texture sized exactly 16 px per block (a 2×1 painting
needs 32×16 — the compiler checks and tells you) plus author/title. Set
`placeable: true` to join the vanilla random-placement pool.

Glyph and sound saves go live in about a second like everything else;
**paintings and jukebox songs apply at the next server restart** — on Paper
1.21.6+ they compose through the registry events at bootstrap, and on older
servers they ride the world datapack. Materione says so honestly — a
restart-required warning appears while changes are pending and clears after
the restart loads them.

## 6. Preview and publish

Every valid save updates runtime behavior immediately. A changed resource pack
is sent only to the player who opened the editor, and the app header shows
**Preview ready** with its hash. Choose **Publish** to promote that exact
hash to all players. If another edit built a newer candidate, publish fails
visibly instead of releasing bytes you did not preview. The published pack
survives restarts and remains the version sent to joining players.

## 7. Hand-edited files stay welcome

Editing YAML directly (or a `git pull`) is a first-class workflow: the
plugin watches `content/packs/`, journals the change as an *external*
revision, and every open editor tab shows a "changed on disk" banner
instead of silently overwriting. Clean tabs refresh automatically; tabs
with unsaved edits keep them. The feed can be disabled with
`editor_file_watcher: false` in `settings.yml` (editable in the editor's
Server view).

## 8. When things go wrong

- **Diagnostics view** — every parse/validation failure streams in live,
  with click-through to the offending file and line.
- **Server view** — manual full or per-pack reloads, the `settings.yml`
  editor (validated before anything lands on disk), and the audit trail.
- **Pipeline log** (bottom dock) — the outcome of every save/reload.
- A save that hits a file changed elsewhere fails with `MTR-0034` instead
  of clobbering: reload the document and re-apply your edit.
- **A glyph shows as the missing-glyph box (tofu) for one player** — their
  client is not running the current pack (declined the prompt, or a newer
  pack is still downloading). Materione substitutes placeholders only for
  players whose pack actually loaded; everyone else sees the literal
  `:heart:` text. `/materione doctor` lists each online player's pack state
  (`pack acceptance: Name=loaded/…`) — have the player accept the pack
  prompt and the glyphs appear.
