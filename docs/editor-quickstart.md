# Editor quickstart

The built-in web editor lets you create and edit content from the browser —
no YAML knowledge required, no restart needed. Everything you save reaches
your server in about a second.

## 1. Open the editor

1. In-game or from the console, run:

   ```
   /materione editor
   ```

2. A clickable URL prints, for example
   `http://127.0.0.1:8089/?s=…#…`. Open it in a desktop browser
   (Chrome/Edge 133+, Firefox 129+, or Safari 17+).

3. The first connection asks for trust in game: an **[Approve]** /
   **[Deny]** prompt appears for every admin online (and the console).
   Approve it within two minutes. The part of the URL after `#` is the
   session key — it never leaves your browser; do not share the URL.

The listener binds to `127.0.0.1` by default (`editor_host` /
`editor_port` in `settings.yml`); disable it entirely with
`editor_enabled: false`. Editing from another machine is a documented
reverse-proxy pattern — the listener is authenticated end to end either way.

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

## 5. Preview and publish

Every valid save updates runtime behavior immediately. A changed resource pack
is sent only to the player who opened the editor, and the app header shows
**Preview ready** with its hash. Choose **Publish** to promote that exact
hash to all players. If another edit built a newer candidate, publish fails
visibly instead of releasing bytes you did not preview. The published pack
survives restarts and remains the version sent to joining players.

## 6. Hand-edited files stay welcome

Editing YAML directly (or a `git pull`) is a first-class workflow: the
plugin watches `content/packs/`, journals the change as an *external*
revision, and every open editor tab shows a "changed on disk" banner
instead of silently overwriting. Clean tabs refresh automatically; tabs
with unsaved edits keep them. The feed can be disabled with
`editor_file_watcher: false` in `settings.yml` (editable in the editor's
Server view).

## 7. When things go wrong

- **Diagnostics view** — every parse/validation failure streams in live,
  with click-through to the offending file and line.
- **Server view** — manual full or per-pack reloads, the `settings.yml`
  editor (validated before anything lands on disk), and the audit trail.
- **Pipeline log** (bottom dock) — the outcome of every save/reload.
- A save that hits a file changed elsewhere fails with `MTR-0034` instead
  of clobbering: reload the document and re-apply your edit.
