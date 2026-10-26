# Editor quickstart

The built-in web editor lets you create and edit content from the browser —
no YAML knowledge required and no ports to open. Most authoring changes apply
without a restart; restart-required cases are called out below. **Save & apply**
writes authoring changes to the server and requests the normal pipeline; pack
publication is a separate action.

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

Document drafts are kept in this browser tab's memory (up to 32 documents).
They survive workspace navigation and an unexpected disconnect, but are not
saved to browser storage and cannot be recovered after a confirmed page reload
or tab close. The browser warns before unloading tracked document/settings
drafts or while an asset or model editor is open. This warning is conservative:
it can appear for an open texture, asset, or model editor even if you have not
changed it. If you confirm unloading, there is no durable draft recovery. After
a connection drops, choose **Reconnect** and paste a fresh session URL for the
same server. Materione checks the server identity and reconciles drafts against
the files on that server; it never automatically replays a save. Explicit
disconnect asks before discarding tracked drafts or leaving an active editor.

## 2. Create your first item

1. Open **Content** and choose **Create your first item**.
2. Name the project once, then name the item. Materione creates the pack,
   item document, and display name in one operation; no paths are shown.
3. The Item Studio groups controls by Identity, Appearance, Gameplay, and
   Behavior. Search **Base material** by its human label or technical ID; the
   choices come from this server's registered material list. If an existing
   item has an ID the server no longer advertises, it stays intact until you
   choose a replacement. Choose a **texture** — the picker lists the pack's
   textures with thumbnails, the upload button takes a PNG straight from your
   disk, and **Edit pixels** opens Materione's own pixel editor (pencil, fill,
   eyedropper, shapes, mirror, undo/redo) without leaving the item.
4. Choose **Save & apply**. Only changed fields are sent in one revision-checked
   batch; comments and fields the studio does not show stay untouched, and a
   footer says when fields are only editable in Files. Save & apply updates the
   server's gameplay runtime globally and requests the normal pipeline. The
   Activity/status area reports the latest server-wide reload signal; it cannot
   attribute that signal to this particular save. Check the item with
   `/materione dev stack <pack>:<item>`.

The item preview keeps its 32 px inventory slot and enlarged static visual
separate from the scrolling form. Its live name/lore tooltip is a browser
approximation: existing `&` color/decorations are shown, unsupported tokens
stay literal, and authored text is not interpreted as HTML. Fonts, glyphs,
effects, obfuscation, and gameplay still need an in-game check. Click the
preview name or lore to focus that editor field. Name and lore edits—including
blank lore lines and spaces—remain local until **Save & apply**; moving focus
away does not save. An unchanged document is not written. The preview's
**View server and pack status** link opens the Server support view; candidate
availability or a connected-server label is not proof that an item is live in
game or that a client accepted the pack.

The plain-lines lore editor refuses non-string entries and entries containing
embedded line breaks rather than flattening them; the original value remains
intact in Files. Decoration formatting respects existing color codes; choosing
a color recolors the selected text. Formatting across multiple lore lines is
handled one line at a time.

The pixel editor has its own **Save & apply**: it commits the texture and, when
needed, its owning texture reference. Other pending document fields remain
unsaved. Conflicting changes to the same field are blocked for review rather
than merged automatically.

Blocks, furniture and recipes work the same way — pick their tab at the top of
Content. Each type has its own studio, and a placement item is generated
automatically for blocks and furniture, so everything you create is grabbable in
the item browser.

## 3. Finding things once the pack grows

The initial authoring surface is **Content**, with Items selected; the workbench
also has **Files**. Its labels are expanded by default and can be collapsed.
**Problems**, **Activity**, and **Server** are support destinations. Activity is
hidden by default; click its sidebar button to open or close it, or use the panel's
close button. New activity stays in the log without reopening a closed panel.
Collapsing navigation labels keeps the buttons in place. There is no separate Start or Assets workspace in this slice:
open assets from a studio slot or in Files. Make folders if you want them (they
are organisational only — ids do not change), double-click an entry to open its
studio, and use search, F2, or the right-click menu to duplicate, move, delete,
or reveal it in Files. Content rows represent resources, not just documents:
they show the server's resource name, material/summary, health, and a supported
static thumbnail when available. Search includes human-readable names and
materials. Choose the dense list or compact grid independently for each content
type; resource IDs and file paths are optional technical columns. A shared YAML
document can therefore have multiple resource rows; opening one keeps that exact
root. If summaries or visuals are unavailable, the catalog keeps the resource
visible with a fallback name, unknown health, and an honest preview placeholder.
Health remains unknown unless a direct read proves an error; it never proves that a save was applied.
`MTR-0051` means the summary read budget was exceeded, not that a save was rejected:
Files and the running content remain available.
Rename, move and delete operate on the entire file and show the impact on shared
resources. Duplicating a shared file as one resource is not offered.
From a document in Files, choose **Open in Studio**; a multi-resource document
keeps the selected resource root. Files retains the tree, generic form, raw YAML,
revisions, and assets for everything a studio does not touch.

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
  Files.

`.bbmodel` sources are authoring state: they stay in your pack folder and
are never shipped to clients.

## 5. Glyphs, emoji, sounds and paintings

**Glyphs** are custom pictures in text — emoji for chat, icons for names.
The Glyphs catalog shows source-texture miniatures in both list and grid.
Sheets show their source image, animations show a static frame, and references
borrow the target's source preview; these are not font-layout or in-game rendering proofs.
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

## 6. Apply and publish

**Save & apply** changes gameplay runtime server-wide and requests a pipeline
reload. The latest `push.reload` is global rather than tied to one save, so a
missing or delayed signal does not prove that a particular save applied.

When available, the header shows **Candidate available** and its hash. This
means a candidate pack exists; it does not confirm that a player received or
accepted it. **Publish pack** promotes the exact current candidate hash for
other players. If a newer candidate replaced it, publication is rejected rather
than silently substituting different bytes. The published pack survives
restarts and remains the version sent to joining players.

## 7. Hand-edited files stay welcome

Editing YAML directly (or a `git pull`) is a first-class workflow: the
plugin watches `content/packs/`, journals the change as an *external*
revision, and every open editor tab shows a "changed on disk" banner
instead of silently overwriting. Clean tabs refresh automatically; tabs
with unsaved edits keep them. The feed can be disabled with
`editor_file_watcher: false` in `settings.yml` (editable in the editor's
Server view).

## 8. When things go wrong

- **Problems** — diagnostics stream in live, including repeated occurrences; file-backed locations open the
  source document in Files. Line/column links focus a read-only saved-source
  excerpt without changing pending form or YAML edits. Runtime diagnostics
  without a file location have no file click-through.
- **Server view** — manual full or per-pack reloads, the `settings.yml`
  editor (validated before anything lands on disk), the audit trail, and
  **disconnect**, which still asks before discarding local drafts.
- **Activity** (optional bottom panel) — recent saves/errors and the latest observed
  server-wide reload outcome, not a per-save delivery receipt.
- A save that hits a file changed elsewhere fails with `MTR-0034` instead
  of clobbering: reload the document and re-apply your edit.
- **A glyph shows as the missing-glyph box (tofu) for one player** — their
  client is not running the current pack (declined the prompt, or a newer
  pack is still downloading). Materione substitutes placeholders only for
  players whose pack actually loaded; everyone else sees the literal
  `:heart:` text. `/materione doctor` lists each online player's pack state
  (`pack acceptance: Name=loaded/…`) — have the player accept the pack
  prompt and the glyphs appear.
