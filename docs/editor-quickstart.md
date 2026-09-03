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
   textures with thumbnails, and the upload button takes a PNG straight
   from your disk.
4. Save (single fields patch losslessly, so comments
   survive). The pipeline dock shows the targeted reload; within ~2 s the
   item is live on the server. Grab it with
   `/materione dev stack <pack>:<item>` to check the look.

Blocks and furniture work the same way — a placement item is generated
automatically, so everything you create is grabbable in the item browser.

## 3. Model with Blockbench

Choose **Create model** or **Edit model** from the item's Appearance section.
The pinned, unmodified Blockbench build appears contextually inside the editor
(works offline; the build and its pin are documented in
`webui/public/blockbench/PIN.md`).

- **Model → save back:** build your model, click **save to Materione**,
  set where the exported Java block/item model, its textures and the
  `.bbmodel` project source land inside the pack, and optionally wire the
  model onto a new or existing item/block right in the same step. One atomic save
  triggers the usual targeted reload — the model is in game immediately.
- **Existing project:** use **import .bbmodel** to load an external
  Blockbench project into the embedded build, then save it back as above.

`.bbmodel` sources are authoring state: they stay in your pack folder and
are never shipped to clients.

## 4. Preview and publish

Every valid save updates runtime behavior immediately. A changed resource pack
is sent only to the player who opened the editor, and Content shows
**Preview ready** with its hash. Choose **Publish preview** to promote that exact
hash to all players. If another edit built a newer candidate, publish fails
visibly instead of releasing bytes you did not preview. The published pack
survives restarts and remains the version sent to joining players.

## 5. Hand-edited files stay welcome

Editing YAML directly (or a `git pull`) is a first-class workflow: the
plugin watches `content/packs/`, journals the change as an *external*
revision, and every open editor tab shows a "changed on disk" banner
instead of silently overwriting. Clean tabs refresh automatically; tabs
with unsaved edits keep them. The feed can be disabled with
`editor_file_watcher: false` in `settings.yml` (editable in the editor's
Server view).

## 6. When things go wrong

- **Diagnostics view** — every parse/validation failure streams in live,
  with click-through to the offending file and line.
- **Server view** — manual full or per-pack reloads, the `settings.yml`
  editor (validated before anything lands on disk), and the audit trail.
- **Pipeline log** (bottom dock) — the outcome of every save/reload.
- A save that hits a file changed elsewhere fails with `MTR-0034` instead
  of clobbering: reload the document and re-apply your edit.
