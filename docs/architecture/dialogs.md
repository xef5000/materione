# Server-side dialogs — design (#6, M6)

> **Status.** Design, for review on the PR that adds it (`Refs #6`). Nothing here is built. The owner questions are in §16; the
> decisions this design takes are reversible by the owner and are listed in §15. §14 measures the design against the
> competitor the owner named, mcgui.
>
> **Scope.** The `dialog` content type: a faithful wrapper over the vanilla dialog screen (Java Edition 1.21.6+), its file format,
> kinds, bodies, inputs and buttons; how a submission enters the logic engine as the `dialog.submit` trigger; how the server treats
> the client as untrusted; how dialogs open from logic, commands, GUIs and items; capability gating on the 1.21.4 floor; Folia
> threading; the studio; the code-mode SDK; diagnostics; tests and implementation rows. It refines
> [`runtime-systems.md`](runtime-systems.md) §8.2 and owns the M6 roadmap bullet "server-side dialogs".
>
> Traceability: files canonical (D1) — a dialog is a YAML document, nothing else; one behavior system (D4) — a button runs the same
> flows an item or a GUI runs, and a submission is a trigger of the one engine; registries with self-description (D5) — the type's
> schema is the one source for the parser, the studio, validation and the SDK; one pipeline (D6) — the studio writes files, the
> pipeline compiles and applies them; Folia-native (D8) — every firing and every send crosses the thread gateway; the 1.21.4 floor
> (D10) — dialogs are a capability, not a floor bump; primitives, not features (D12) — a dialog is a **view kind** over state
> ([`primitives.md`](primitives.md) §5.3, #335), not a dialog system; code mode (D13) — the SDK surface is generated, never written.

---

## 1. Goals and non-goals

**Goals.**

| # | Goal | Traces to |
|---|---|---|
| G1 | Every vanilla dialog kind, body, input and client action is expressible in a `dialogs/*.yml` document, with nothing Materione-only in the way of a vanilla capability. | feature-parity §3.10, runtime-systems §8.2 |
| G2 | A button that reaches the server runs a **flow of the one engine**; there is no dialog action language. | D4, logic.md §2 |
| G3 | The submitted values arrive **typed** in the run's context (`input.<key>`), validated against the declaration the server sent, never trusted as sent. | logic.md §2.6, master §11 |
| G4 | The exit criterion of #6: an NPC-style form collects player input and drives a rule, with no client mod. | #6 |
| G5 | Dialogs are a **view kind** (#335): they reuse the GUI's opener, gates, argument types, item sources, list sources, triggers' shape and studio Logic tab, and add only what a modal screen needs. | D12, primitives.md §5.3 |
| G6 | A 1.21.4 or 1.21.5 server loads, validates and edits dialogs; only *showing* one needs the capability, and its absence is a stated, author-controlled fallback. | D10, plugin.md §6 |
| G7 | At least equal to **mcgui**, the competitor the owner named, row by row, and better where D1–D13 allow; the measured bar and the gaps left on purpose are in §14. | owner input on #6 |

**Non-goals (v1).**

| Non-goal | Why | Where it goes |
|---|---|---|
| Dialogs in the pause menu or the quick-actions key (vanilla's `pause_screen_additions` and `quick_actions` dialog tags) | They need the dialog **registry**, which is frozen at bootstrap, so they are restart-required content, unlike everything else here. | Owner question Q5 (§16), still open. |
| Dialogs during the configuration phase (before the player joins the world) | Paper exposes them (`PlayerCommonConnection`), but nothing in the logic engine runs before a player exists. | Later; the host interface (§9) does not preclude it. |
| A generated chest-GUI imitation of a dialog for old clients | A second renderer is feature-shaped and duplicates ViaBackwards (§10.3). An author who wants a GUI names one in `fallback:`. | §10.2. |
| Bedrock forms (Geyser) | Bedrock is not supported by Materione yet; master §14.3 (Geyser timing) stays open. | Owner answer Q2 (§16): not now. |
| One dialog screen shared by two players | Vanilla offers no shared modal: a dialog is one modal per player. Many players can each have their own dialog over the **same state** (a vote with a live tally: `rerender: on_change` over a server variable now; bound to a key with `key:` once keyed state, #333 step 2, lands), per the views design (`views.md` §4.3, §6.1, §6.2). | §8; `views.md` row V5 for the key. |
| Code stored or run as content | D13. | — |

---

## 2. The platform: what vanilla and Paper provide

### 2.1 Vanilla (Java Edition 1.21.6+)

Dialogs were added in Java Edition 1.21.6 (snapshot 25w20a). A dialog is a modal screen the server sends; it is Java-only. Its root
fields are `type`, `title` (required), `external_title`, `body`, `inputs`, `can_close_with_escape` (default true), `pause` (default
true, single-player only) and `after_action` (`close` default, `none` only when `pause` is false, `wait_for_response`, which shows a
"waiting for response" screen until the server sends another screen).

| Vanilla kind | Shape |
|---|---|
| `notice` | one footer button (`action`) |
| `confirmation` | two buttons, `yes` and `no`; Escape is `no` |
| `multi_action` | a scrollable grid of buttons (`actions`, non-empty), `columns` (default 2), optional `exit_action` |
| `server_links` | the server's links as a grid, `columns`, `button_width`, optional `exit_action` |
| `dialog_list` | a grid of buttons that open other dialogs (by their `external_title`), `columns`, `button_width`, optional `exit_action` |

| Body | Fields |
|---|---|
| `plain_message` | `contents` (a text component; no NBT, score or selector components), `width` 1–1024 (default 200) |
| `item` | an item stack, optional `description` (text, own width), `show_decoration`, `show_tooltip`, `width`/`height` 1–256 |

| Input (`key` letters, digits, `_`; `label` required) | Fields | Value in the payload |
|---|---|---|
| `text` | `width`, `label_visible`, `initial`, `max_length` (default 32), `multiline` (`max_lines`, `height` 1–512) | string |
| `boolean` | `initial`, `on_true`, `on_false` | byte `1b`/`0b` |
| `single_option` | `options` (non-empty; each `id`, `display`, `initial`; at most one initial), `width`, `label_visible` | string (the option id) |
| `number_range` | `start`, `end` (required), `step`, `initial` (midpoint by default), `width`, `label_format` | float |

Button actions mirror text-component click events (except `open_file`): `open_url`, `run_command` (as if typed by the player),
`suggest_command`, `change_page` (books only), `copy_to_clipboard`, `show_dialog` (an id or an inline dialog) and `custom` (`id`,
optional `payload`; a vanilla server ignores it). Two **dynamic** actions read the inputs: `dynamic/run_command` (a `$(key)` macro
template, substituted client-side) and `dynamic/custom` (`id`, optional `additions`): the client sends a `minecraft:custom` click
whose payload is a compound holding **every input's value under its key**, with `additions` merged in.

Source: the Minecraft Wiki's *Dialog* page, read 2026-11-18. The wiki states **no limit** on the number of inputs, buttons or the
payload size; the limits of §6.4 are therefore Materione's own.

### 2.2 Paper (verified in the API jars this repo builds against)

| Fact | Evidence |
|---|---|
| The **1.21.4 API has no dialog classes** and bundles Adventure 4.20.0 (no `Audience#showDialog`, which is `@since 4.22.0`). | `paper-api-1.21.4-R0.1-SNAPSHOT.jar` (zero `dialog` entries); its Gradle module requires `adventure-bom` 4.20.0; `adventure-api-4.26.1-sources.jar`, `Audience.java`. |
| The **26.1.2 API** has `io.papermc.paper.dialog.Dialog` (`Dialog.create(...)` builds an *inline* dialog, no registry entry needed), `DialogResponseView` (`payload()`, `getText`, `getBoolean`, `getFloat`), the builders under `io.papermc.paper.registry.data.dialog` (`DialogBase`, `ActionButton`, bodies, the four inputs, the five types), `DialogAction.customClick(Key, BinaryTagHolder additions)` (vanilla `dynamic/custom`), `DialogAction.customClick(DialogActionCallback, ClickCallback.Options)` (a Paper-managed callback), `DialogAction.commandTemplate`, `DialogAction.staticAction(ClickEvent)`, and `PlayerCustomClickEvent` (`getIdentifier()`, `getTag()`, `getDialogResponseView()`, `getCommonConnection()`; a `PlayerGameConnection` yields the `Player`). | `paper-api-26.1.2.build.74-stable-sources.jar`. |
| The API is **`@ApiStatus.Experimental`** (`Dialog.create`, `DialogResponseView`, `PlayerCustomClickEvent`, the `action` package). | same sources. |
| `Audience#showDialog(DialogLike)` and `Audience#closeDialog()` exist; closing returns the client to the previous dialog when one opened it. | `Audience.java`, Adventure 4.26.1. |

**Unverified** (row R, §13, closes each one in a client or on the harness):

| # | Question | Why it matters |
|---|---|---|
| V1 | The first Paper build line with the dialog API (believed 1.21.6, from Paper's release notes; no 1.21.6 jar is in this repo's caches). | The capability is detected by class presence (§10.1), so the answer changes no code, only the docs. |
| V2 | The thread `PlayerCustomClickEvent` fires on, on Paper and on Folia. | §9: the handler is written thread-agnostic either way. |
| V3 | The thread a Paper `DialogActionCallback` runs on, and how its registrations expire. | Why §6.1 does not use it. |
| V4 | What the client does with an open **container** when a dialog is shown, and whether closing the dialog returns to it. | Decides the dialog `InputMethod`'s `closesWindow` (logic.md §29 decision 19 assumed false) and the GUI hand-off (§7.3). |
| V5 | Whether resource-pack fonts (`<glyph:…>`, `<shift:N>`, effects) render in dialog titles, labels and bodies. | The studio preview and the text pipeline (master §5.7). |
| V6 | Whether the client enforces `max_length` and `number_range` bounds (assumed yes for a vanilla client; irrelevant for a hostile one, §6.3). | Only user experience; the server re-validates regardless. |
| V7 | ViaBackwards' chest emulation of dialogs for 1.21.5 clients: which inputs it supports and what payload it sends back. | §10.3. **Not probed**: Q1 is answered, older clients are the server owner's concern. |
| V8 | The largest custom-click payload the server accepts before the packet is refused. | §6.4 sets a lower cap of its own. |

---

## 3. Vocabulary

| Term | Meaning |
|---|---|
| **Dialog** | One `dialogs/**/*.yml` resource (content type `dialog`): a modal screen design plus its rules. |
| **Kind** | The vanilla screen shape: `notice`, `confirmation`, `multi_action`, `dialog_list`, `server_links` (`type:` in the file). |
| **Body** | An ordered list of read-only elements above the inputs: `text` or `item`. |
| **Input** | A typed control whose value is submitted: `text`, `boolean`, `option` (vanilla `single_option`), `number` (vanilla `number_range`). |
| **Button** | An entry the player clicks. A **server button** submits (fires `dialog.submit`); a **client button** does one client-side thing (open a URL, copy, suggest a command, show a static dialog) and the server hears nothing. |
| **Dialog session** | The server's record of one dialog shown to one player: the dialog kind's **viewer record** (`views.md` §3.3). Its **binding** is fixed at open: the compiled dialog, its `args` and (with #333 step 2) its key. Its **viewer-local state** is the token its server buttons carry, the button table, the list and option snapshots and its expiry. It holds no state another player or a rule outside its own runs could need. |
| **Token** | An unguessable value the server puts in every server button's `additions` and checks on submission (§6.2). |

---

## 4. File format

```yaml
# content/packs/<pack>/dialogs/report.yml
report:                                  # one resource per key (master §7.2); the key is the dialog id
  type: multi_action                     # notice | confirmation | multi_action | dialog_list | server_links
  title: "<gold>Report a player"         # stored text form (master §5.7); a template (logic.md §2.5)
  external_title: Report                 # optional; the label of a button that leads here
  close_on_escape: true                  # vanilla can_close_with_escape (default true)
  after_submit: close                    # close (default) | stay | wait  (vanilla close | none | wait_for_response)
  rerender: off                          # off (default) | on_change  (views.md §4.3)
  columns: 2                             # multi_action, dialog_list, server_links
  body:
    - text: "Reports go to staff. Abuse of this form is punished."
      width: 260
    - item: { item: "materione:item", id: shop:gui/banhammer }   # an item source (logic.md §4.4)
      description: "Staff will review within 24 hours."
  inputs:
    - key: target
      type: option
      label: Player
      options: { source: online_players, id: "${entry.name}", display: "${entry.name}" }   # a list source (logic.md §6.7)
    - key: reason
      type: option
      label: Reason
      options:
        - { id: cheating, display: Cheating }
        - { id: chat, display: "Chat abuse", initial: true }
    - key: details
      type: text
      label: Details
      max_length: 200
      multiline: { max_lines: 4 }
    - key: anonymous
      type: boolean
      label: "Stay anonymous"
    - key: severity
      type: number
      label: Severity
      start: 1
      end: 5
      step: 1
      initial: 3
  buttons:                               # multi_action; notice uses `button:`, confirmation `yes:`/`no:`
    - id: send                           # [a-z0-9_]+, unique; a rule filters on it
      label: "<green>Send"
      tooltip: "Sends the report to staff"
    - id: rules
      label: "Server rules"
      client: { show_dialog: rules }     # a client button: no submission
    - id: discord
      label: Discord
      client: { open_url: "https://example.org/discord" }
  exit:                                  # optional; Escape and the footer button (multi_action, dialog_list, server_links)
    label: Cancel
  open:                                  # the GUI's open block, same schema and meaning (logic.md §6.1, §23)
    commands: [report]
    permission: myserver.report
  fallback:                              # steps run instead of showing, where dialogs are unavailable (§10.2)
    - view.open: myserver:report_menu
  behavior:                              # rules of the one engine (logic.md §2.1)
    - on: dialog.submit
      button: send
      cooldown: 30000
      do:
        - run_command: { as: console, command: "staffnotify report" }
        - set_var: { var: "myserver:last_report", value: "${input.target}: ${input.reason}" }
        - message: "<green>Thanks. Your report about ${input.target} was sent."
  flows: {}
```

Rules of the format:

1. **One resource per top-level key**, directory `dialogs/`, type `dialog` (the directory is already in `PackLayout.CONTENT_DIRS`;
   the type is derived by stripping the trailing "s", as for `rules/`, logic.md §37 decision 3).
2. **Names are Materione's, shapes are vanilla's.** Kinds keep vanilla's names. Where vanilla's name describes a widget rather than
   a value (`single_option`, `number_range`), the file says what the author gets (`option`, `number`); `after_action` becomes
   `after_submit` because it only applies after a button. Every rename is a table row in the self-description (§12), so the studio
   and the SDK show the vanilla name beside ours. Nothing is copied from another plugin's format.
3. **Every player-visible string is a template** in the stored text form (master §5.7): `${…}` expressions, PlaceholderAPI
   `%tokens%`, MiniMessage. Templates resolve for the viewer when the dialog is shown (§7.1).
4. **Lossless round-trip** (master §5.6): a load followed by an untouched save is byte-identical; unknown keys are preserved and
   reported once as warnings (the existing codes, `MTR-0008` family), never dropped. Fixture-held (§13).
5. **Kind-specific keys** are validated against the kind: `button` only on `notice`, `yes`/`no` only on `confirmation`,
   `buttons`/`exit` on `multi_action`, `dialogs` on `dialog_list`, `columns` and `button_width` on the three grid kinds. A key on
   the wrong kind is a warning and is ignored (fail-soft); a missing required key quarantines the dialog (§11).
6. **`behavior:` and `flows:` are a host's shape** (logic.md §2.1, §2.4, §37 decision 1): the same compiler, interpreter, trace and
   `LogicEdit` vocabulary. A dialog's layout is sent as data on open, not compiled into the pack; the one exception is an `image`
   body (§4.2), whose bitmap is appearance and compiles into the pack as a glyph like a GUI's raster (D3).
7. **`rerender: off | on_change`** (default `off`) is the view-kind rerender policy of `views.md` §4.3 and §5.5. With `off`, a
   dialog renders at open and when a `view.refresh` selects it explicitly (by `view:` or as the run's own viewer); a change to
   the state it reads, or a `var:`-selected refresh, does not re-send it. With `on_change`, a change to its reads re-sends it, at
   most once per 20 ticks, and only if something the player would see or submit changed: on an automatic wake, when both the
   rendered dialog and the server-side submission snapshot (button table, offered option ids, source entries) are unchanged,
   nothing is sent and the current token is kept. The 20-tick floor limits how often a form resets; it does **not** protect an
   unfinished form, whose entries a later re-send still wipes. `on_change` on a dialog with **any editable input** (`text`,
   `number`, `boolean`, `option`) is a load warning (`MTR-V03`), because a re-send loses a checkbox or an option selection as
   surely as typed text (views design, owner answer Q7 as qualified in the owner's review of #335: allowed, warned).

### 4.1 Kinds

| `type:` | Required | Optional | Compiles to |
|---|---|---|---|
| `notice` | `button` (a server or client button; default label `OK`, server) | — | vanilla `notice` |
| `confirmation` | `yes`, `no` (each a button) | — | vanilla `confirmation`; Escape is `no` |
| `multi_action` | `buttons` (1–64, or a `source:`, §4.4) | `exit`, `columns`, `button_width` | vanilla `multi_action` |
| `dialog_list` | `dialogs` (a list of dialog ids) | `exit`, `columns`, `button_width` | vanilla **`multi_action`** whose buttons are server buttons that run `view.open` (labelled with each target's `external_title`), so every target's `open.permission` and `open.requirement` are checked server-side. Vanilla `dialog_list` would open the targets client-side without those gates. |
| `server_links` | — | `exit`, `columns`, `button_width` | vanilla `server_links` over the server's links (Paper `ServerLinks`; not Materione content) |

### 4.2 Bodies

| Element | Fields | Notes |
|---|---|---|
| `text` | `text` (template), `width` 1–1024 | vanilla `plain_message`. Selector, score and NBT components are refused at load (vanilla drops them). |
| `image` | `image` (a texture under `textures/`, as a GUI `image` layer names one), `scale` 1–4, `width` 1–1024 | **Materione-only, closes a parity gap (§14).** Compiled into the pack as a glyph in the GUI font machinery (gui.md §4, §11.3: the same provider limits, a durable codepoint allocation per image, D7) and sent as a `plain_message` whose contents are that glyph, with `<shift:N>` for centring. This is how a dialog carries a banner, a portrait or a full designed header. Depends on V5 (pack fonts render in dialog bodies); if V5 fails, the element is unavailable and reported with `MTR-D04`. |
| `item` | `item` (an **item source**, logic.md §4.4: `materione:item`, `head`, `player_slot`, optional integrations), `description` (template), `width`, `height` 1–256, `decoration`, `tooltip` | The item is produced for the viewer at open, as a GUI item layer's is; a source that cannot produce one shows a barrier and reports `MTR-0072` (its existing meaning). |

### 4.3 Inputs

| `type:` | Fields (beyond `key`, `label`, `label_visible`, `width`) | Context type of `input.<key>` |
|---|---|---|
| `text` | `initial` (template), `max_length` 1–2048 (default 32), `multiline: {max_lines, height}`, `pattern` (optional regex, server-side only), `trim` (default true) | `string` |
| `boolean` | `initial`, `on_true`, `on_false` (display strings for `dynamic/run_command` parity; the context value is always the boolean) | `boolean` |
| `option` | `options`: a static list (`id` `[a-z0-9_.-]{1,64}`, `display` template, `initial`), or a list-source block (`source`, `id`, `display`, `filter`, `sort`, `limit` ≤ 256; logic.md §6.7) | `string`, always one of the ids **the server sent to this viewer** |
| `number` | `start`, `end`, `step` (optional), `initial`, `label_format` | `number`; an `int` when `start`, `end` and `step` are all whole |

`key` is `[a-z0-9_]{1,32}` (vanilla allows upper case; Materione keys are lower case everywhere) and unique within the dialog.
`pattern` is Materione-only and never sent: the client cannot enforce it, the server does (§6.3).

### 4.4 Buttons

```yaml
- id: buy                                # required on server buttons
  label: "Buy for $${args.price}"        # template
  tooltip: "…"                           # optional template
  width: 150                             # 1–1024
  visible_if: "${has_money(args.price)}" # optional condition (logic.md §2.3); a hidden button is not sent
  client: { … }                          # optional; present = a client button
```

| `client:` action | Vanilla | Notes |
|---|---|---|
| `open_url: <url>` | `open_url` | `https` and `http` only; other schemes are refused at load. The client asks the player before opening. |
| `copy_to_clipboard: <template>` | `copy_to_clipboard` | |
| `suggest_command: <template>` | `suggest_command` | Fills the chat box; the player sends it (or not). |
| `show_dialog: <dialog id>` | `show_dialog` with the target **inlined** | Static navigation that needs no round trip. The target is compiled inline at open, resolved for the same viewer, and its server buttons share the opening session (§6.2). Depth ≤ 4; a cycle is a load error; the target's `open.requirement` is **not** checked (it is client-side), so a target with an `open.requirement` or `open.permission` is refused here with a warning, and the author uses a server button with `dialog.open`. |

Two vanilla actions are deliberately **not** offered as client buttons, because a server button covers each with the gates and
validation of §6: `run_command` (a server button whose rule runs `run_command: {as: player}`) and `dynamic/run_command` (the same
with `${input.<key>}`, after the server has validated the values; §6.5 covers injection). `change_page` is books-only and has no
meaning here. Owner question Q3 asks whether the two command actions should be offered anyway.

**Buttons from a list source.** `buttons: {source: <list source>, id: …, label: …, filter, sort, limit ≤ 64}` builds a
`multi_action` grid from the same `ListSource` SPI a GUI list uses (logic.md §6.7): a server selector, a player picker. The clicked
entry's fields arrive as `entry.*` in `dialog.submit`, **taken from the snapshot the server made when it showed the dialog**, not
from the client (§6.2). This is the "dynamic actions" row of feature-parity §3.10 in primitive form: the dynamic part is the data,
and the action is always a flow.

---

## 5. Logic: triggers, actions, the input method

### 5.1 Triggers

The dialog triggers are siblings of the GUI's (logic.md §6.2; primitives.md §4.3 "views other than the container GUI add their own
sibling triggers"). `dialog.submit` is already a reserved id (`EventTypes.DIALOG_SUBMIT`, `BuiltinTriggers`); it becomes live and
the others are added. `firedBy`: `dialog`.

| Trigger | Filters | Context (beyond `player.*`, `server.*`) | Cancellable |
|---|---|---|---|
| `dialog.opening` | — | `view.*` | yes: the dialog is not shown |
| `dialog.open` | — | `view.*` | no |
| `dialog.submit` | `button` (an id, or a list) | `view.*`, `button.id`, `input.<key>` (typed, §4.3), `entry.*` (a button from a source) | no (a submission already happened client-side) |
| `dialog.close` | `reason: escape\|replaced\|quit\|expired` | `view.*`, `close.reason` | no |

The view facts are the one view root of `views.md` §3.7: `view.id`, `view.kind` = `dialog`, `view.args.*`, `view.key` (no
`dialog.*` root: the trigger names are dialog events, the facts are the view's). Session state is `vars.session.*`, per shown
dialog (§8). The compiler checks `${input.k}` against the host dialog's declared inputs, as it checks
`view.args.*` against the view's `open.args` (logic.md §2.6); an unknown key is the existing unknown-root warning.

**When the server hears a close.** Vanilla sends nothing when a dialog without a server-bound exit is dismissed. So Materione
compiles every Escape path it can into a server button: `confirmation`'s `no` and `notice`'s button are already buttons; on the
grid kinds, `exit` (or, when `close_on_escape` is true and no `exit` is written, an implicit exit with the label `Close`) is a server
button that fires `dialog.close` with `reason: escape`. `replaced` fires when the server shows another dialog or a GUI to the
player, `quit` on disconnect, `expired` when the session's lifetime ends (§6.2). A client button with `after_submit: close` closes
the screen without telling the server; its session simply expires.

### 5.2 Actions

| Action | Parameters | Threading | Notes |
|---|---|---|---|
| `view.open` (on a dialog) | `view` (a dialog id), `args` (map of templates), later `target` (primitives.md §5.6, #331) and `key` (`views.md` §6.2, with #333 step 2) | `PLAYER_REGION` | The generic open verb of `views.md` §3.6 dispatching to the opener of §7.1. `FAILED` when refused or unavailable, so `require`/`else` compose (logic.md §2.2). |
| `view.close` (on a dialog) | optional `view`, later `target` | `PLAYER_REGION` | Closes the player's dialog (Adventure `closeDialog`) and ends the session; the `dialog.close` trigger is not fired for a close the flow asked for (`views.md` §3.6). |

Dialogs add **no actions of their own**: `view.open`, `view.close`, `view.back` and `view.refresh` (`views.md` §3.6, owner
no-legacy answer 2026-11-19) are the verbs of every opened kind, registered once with self-descriptions (D5); the dialog kind
only declares, through its view facet, that it accepts them. `view.refresh` re-renders dialogs under rule 7 of §4; `view.back`
from a dialog re-opens the view it replaced (a GUI or another dialog) from the player's view history. Neither names a use case (U1); both work on any
dialog content names (U2).

### 5.3 The `dialog` input method

`prompt: {method: dialog, text, var, timeout}` (logic.md §6.6) gains `materione:dialog`: a generated one-input `notice` whose
button submits. It is the `InputMethod` SPI's next registration (logic.md §29 decisions 12, 19), with `closesWindow` set by the
answer to V4 rather than by assumption. It has no file: the method builds a compiled dialog in memory through the same compiler, so
its validation and session binding are §6's. Unavailable without the capability (`Availability` with the reason; the prompt refuses
it with `MTR-0084`, its existing meaning).

---

## 6. Submission, validation and abuse resistance

The client is untrusted. A modified client can send any `minecraft:custom` click with any identifier and payload at any time,
whether or not a dialog is open, including replays of a valid one. Every rule below assumes that.

### 6.1 The wire

Every server button compiles to vanilla `dynamic/custom` (`DialogAction.customClick(Key, BinaryTagHolder)`), identifier
**`materione:dialog`**, with `additions` `{mt: <token>, mb: <button index>}`. The client returns every input's value plus those two
fields. One identifier for all dialogs; the token routes.

Paper's callback form (`customClick(DialogActionCallback, Options)`) is not used: its thread (V3), its expiry and its memory are
Paper's, invisible to the session model, untestable headlessly, and it would put the binding outside the rate limiter.

### 6.2 Session binding

```
open ──► DialogSession{player, compiled, args, token=128-bit random, buttons[], entries[], expires}
           │ registered in DialogSessions (one live session per player; a new open replaces it → dialog.close replaced)
           ▼
client ─ custom click materione:dialog {mt, mb, <inputs>} ─►  PlayerCustomClickEvent (any thread)
           │ 1 rate limit (per connection)        → drop + count
           │ 2 parse: size cap, mt/mb present     → drop + MTR-D06
           │ 3 resolve player from the connection  (a non-game connection → drop)
           │ 4 lookup token; session.player == player, not expired, not consumed → else drop + MTR-D06
           │ 5 hop to the player's thread (gateway, D8)
           ▼
       validate every input against the compiled declaration (§6.3) → refuse + MTR-D07 + one-line message
           ▼
       consume (after_submit: close|wait) or keep (stay)  ──►  dispatch dialog.submit (button, input.*, entry.*)
```

- **The token is the capability.** It is random (`SecureRandom`, 128 bits), never derived from the player, the dialog or time, and
  bound to one player: a token presented on another player's connection is refused. Nested static dialogs (§4.4 `show_dialog`) and
  buttons from a source share the opening session's token; `mb` indexes the session's own table.
- **Single use by default.** `after_submit: close` and `wait` consume the session on the first valid submission; a replay finds
  nothing. `stay` keeps it (the dialog remains on screen) and is held to the per-session rate limit of §6.4.
- **The server sends, the server remembers.** Everything the submission refers to is looked up server-side: the button's rules,
  the entries of a source-built grid, the options a viewer was offered, the `args`. The client's payload contributes the input
  values and two indices, nothing else. Arguments therefore cannot be forged by a client and need no signing.
- **Expiry.** A session lives at most `dialog_session_ttl_seconds` (default 600) and ends on quit, on replacement and on reload of
  a dialog whose input declaration changed (below). An expired session fires `dialog.close` `expired` at most once.
- **Reload while shown.** The session holds the compiled dialog it showed. On submission, rules run from the **live** definition
  when the input declaration's fingerprint is unchanged, so a fixed typo in a rule takes effect; when the inputs changed or the
  dialog was removed, the submission is refused with a one-line "this form changed, please reopen it" and no trigger fires.
- **Re-render (`views.md` §4.3).** A re-render (an `on_change` dialog whose rendered content or submission snapshot changed, or a
  `view.refresh` that selects it explicitly) is a re-open under the same binding. An automatic wake that finds both unchanged
  sends nothing and keeps the current token. A re-render that does send is a re-open: state is re-read, the gates of §7.1 are **not** run again, a **new token** is issued and
  the old one consumed, the list and option snapshots are retaken, and **no `dialog.close` fires** (it is the same view, not a
  replacement). A submission carrying the old token is refused as stale (`MTR-D06`).
- **The opener is the only issuer.** Sessions are created only by §7.1, and only a re-render of a session it created mints a
  replacement token; nothing else does.

### 6.3 Input validation (server-side, every submission)

| Input | Accepted when | Otherwise |
|---|---|---|
| any | the key is declared; its tag type matches (string / byte / float) | refused |
| (missing) | — | refused: vanilla always sends every input |
| (extra keys) | ignored, except `mt`/`mb` | — |
| `text` | after `trim`, length ≤ `max_length` (code points), no C0/C1 control characters except `\n` when `multiline` (and ≤ `max_lines` lines), no `§`, matches `pattern` when set | refused |
| `boolean` | `0b` or `1b` | refused |
| `option` | one of the ids **this viewer was sent** (a source-built list is snapshotted at open) | refused |
| `number` | finite, within `[start, end]`, and on the `step` grid within 1e-4 of a step when `step` is set | refused |

A refused submission fires nothing, reports `MTR-D07` (rate-limited, naming the dialog, the input and the rule broken, never the
value), tells the player one line, and leaves the session as it was (a `stay` dialog can be corrected; a consumed session was not
consumed). A vanilla client never trips these (V6); a trip is either a client mod or a reload race.

### 6.4 Bounds and rate limits

| Bound | Default | Setting |
|---|---|---|
| `materione:dialog` clicks per player connection | token bucket, 4 per second, burst 8 | `dialog_submit_rate` |
| Submissions per `stay` session | 1 per 250 ms | — |
| Payload size (the custom-click tag, as received) | 32 KiB; larger is dropped before parsing | `dialog_payload_max_bytes` |
| Inputs per dialog / buttons per dialog / options per input / inline nesting depth | 32 / 64 / 256 / 4 | schema (load error past them) |
| `text` `max_length` | ≤ 2048 | schema |
| Live sessions | one per player | — |

Clicks dropped by the limiter or for an unknown token are counted, and reported as `MTR-D06` at most once per player per minute;
they are never logged per packet. Clicks with any **other** identifier are ignored entirely (Materione does not own them).

### 6.5 Values are tainted text

An input value is data, not markup or code:

- **In text templates** (`message`, `title`, `actionbar`, lore, a dialog title), `${input.*}` and `${entry.*}` values from a
  submission are inserted as **literal text**: the text pipeline escapes them (`\<`, `\\`, master §5.7) so a player cannot inject
  MiniMessage tags, click events or `<glyph:>`. This is a requirement on the template renderer, held by a test (§13), not a
  convention.
- **In commands.** `run_command` with `as: console` (or `op`) whose template references `${input.*}` is a **load warning**
  (`MTR-D08`): the player's text becomes part of a console command. The author either runs it `as: player`, constrains the input
  (`option`, `number`, or `text` with a `pattern`), or accepts the warning. Materione does not invent command escaping, because
  command syntax is per command.
- **In variables.** `set_var` stores the value as typed; a later read is still the player's text and is escaped the same way when
  rendered.

---

## 7. Opening a dialog

### 7.1 One opener, the GUI's stages

`DialogOpener` follows `GuiOpener`'s eight stages (logic.md §6.1) and shares their code where they are the same thing: resolve from
the live view; `open.permission`; parse `args` with the GUI's argument types (`string`, `int`, `number`, `player`, `boolean`,
`choice`); `open.requirement` with `deny` and the bypass `materione.dialog.bypass.<pack>.<id>`; **capability** (§10); fire
`dialog.opening`; create the session; render and show; fire `dialog.open`. The shared part (stages 2 to 4 and the argument codec) is
extracted from `GuiOpener` into one `OpenGate` used by both, so the two cannot drift; this is the generalisation primitives.md §5.4
anticipates ("lift the block into a `command` content type"), done for two view kinds now.

**Viewer registration.** `DialogOpener` attaches the session to the shared `ViewRegistry` (`views.md` row V0, #455) when it
creates it, and detaches it on every end of §5.1. Row G (the `OpenGate` extraction) and row V0 both touch `GuiOpener`, so G
lands before V0 or in the same PR.

**Render** resolves every template, item source and list source for the viewer on the player's thread, builds a
version-independent `RenderedDialog` (data only), and hands it to the platform's `DialogHost` (§9), which maps it to Paper objects
and calls `showDialog`.

### 7.2 Ways in

| From | How | Notes |
|---|---|---|
| A rule of any host (item, block, furniture, GUI, `rule` document, another dialog) | `view.open: <dialog id>` | The NPC case: an integration's interact trigger, or `entity.hit`/`player.join` from a `rule` document (logic.md §37). |
| A command | the dialog's `open:` block (`commands`, `args`, `permission`, `requirement`, `deny`), Brigadier-registered and re-synced like a GUI's | Same codes and meaning: `MTR-0055`, `MTR-0066` to `MTR-0069`. A command name taken by a GUI or another dialog is `MTR-0067`. |
| Admin | `/materione dialog open <id> [player] [args…]`, `/materione dialog list` | Permission `materione.dialog.admin`. |
| The editor | **Preview in game** (shows the open dialog to the editing player), through `RuntimeApi` | As the GUI studio's preview (gui.md §6). |
| A prompt | `prompt: {method: dialog}` | §5.3. |
| Another dialog | a server button whose rule runs `view.open` (gated), or a client `show_dialog` (static, §4.4) | Server navigation is the default; a wizard carries state forward in `args` (`view.open: {view: step2, args: {name: "${input.name}"}}`), held server-side, and `view.back` returns to the previous step. |

### 7.3 Dialogs and open GUIs

A `view.open` of a dialog from a `gui.click` rule shows a dialog while a container is open. What the client does with the container (V4)
decides the hand-off. Until row R answers it, the design is: the opener treats the dialog as a window-closing view
(`GuiLifecycle.hideForPrompt` / `showAfterPrompt`, logic.md §29 decisions 8 to 11), so the GUI's viewer record survives, `gui.close` does
not fire, and the window returns when the dialog's session ends **if** the dialog's author set `return_to_gui: true` (default true
when opened from a GUI rule). If V4 shows the client keeps the container underneath, the hide becomes a no-op and nothing else
changes.

---

## 8. A dialog is a view kind (#335, D12)

primitives.md §5.3 and #335 direct that the container GUI, the dialog and the HUD be three **view kinds** over one state model, not
three systems. The model is designed in **`views.md`** (#335, PR #454; owner answers 2026-11-19), written before this design is
built (owner answer Q7, §16). A dialog implements its view-kind contract (`views.md` §3.2) with the values of `views.md` §4.1 and
§4.3; this section keeps only what is specific to dialogs.

| Dialog-specific | Statement | Where |
|---|---|---|
| One live dialog per player | The viewer-set rule is `opened`, capped at one: a new open replaces the shown dialog (`dialog.close` `replaced`). | §6.2, `views.md` §3.5 |
| Re-send resets inputs | Vanilla has no update packet, so re-sending is the only re-render and it wipes every input (text, numbers, checkboxes, options). Hence `rerender: off` by default; for `on_change`, no re-send when content and submission snapshot are unchanged, a 20-tick minimum interval that limits resets but does not protect an unfinished form, and `MTR-V03` on any editable input. | §4 rule 7, `views.md` §4.3 |
| Re-render semantics | Same binding, no gates, new token, retaken snapshots, no `dialog.close`. | §6.2, `views.md` §4.3 |
| Session state per shown dialog | `vars.session.*` is per view instance for every opened kind and is **declared** (`scope: session`, typed and defaulted; an undeclared name is `MTR-0063`; `views.md` §3.4, owner answer Q9 there): a dialog's session lives while it is shown and survives its re-renders; a new dialog is a new instance; `args` carry a wizard forward; shared state is keyed state (#333 step 2). | §15 decision 4, `views.md` §3.4 |
| Many viewers | Each player has their own modal over shared state; `target:` (#331) opens a dialog for another player and `key:` (#333 step 2) binds it to a key. | §1 non-goals, `views.md` §6 |

Universality tests (master §3.1, primitives.md §2.2) for what this design adds:

| Surface | U1 name | U2 generality | U3 composition | U4 self-description | U5 safety |
|---|---|---|---|---|---|
| `dialog` content type | a view kind, not a use case | any content, any viewer | rules, flows, sources, item sources | registry entry, typed schema | offline-validated like `gui`; quarantine per dialog |
| `dialog.*` triggers | named for the view event | fired for any dialog | ordinary rules | trigger descriptions with context schemas | no effect of their own |
| `view.open` / `view.close` on dialogs | the generic view verbs (`views.md` §3.6) | any dialog, any opened kind; `target:` later | flow steps, `require` | one action description each, the view reference typed by the facet | `FAILED` on refusal; dry-run stages nothing |
| `materione:dialog` input method | an input method | any prompt | the `prompt` step | input-method description | refuses when unavailable |
| Buttons from a list source | reuses `ListSource` | any source | — | the source's description | snapshot at open |
| `rerender:` | a policy, not a use case | any dialog | a key on the document | a schema field with an enum | absent = `off`; `MTR-V03` warns of the input-loss hazard (`views.md` §8.1) |

Nothing here is named for a use case: no "report form", "rules prompt" or "NPC dialog" exists in the engine. The rules-on-join
benchmark task (agent-benchmark.md T28) and a confirmation step are **compositions** (a `rule` document on `player.join`, a
`confirmation` dialog, a declared player variable), and the existing `confirm_dialog` logic recipe (logic.md §30) gains a variant
that opens a `confirmation` dialog instead of a sub-GUI. A future preset "ask the player to accept the rules" would be declared by
reference over these (master §14.7), not added to the engine.

---

## 9. Threading (D8) and module placement

| Work | Thread | Why |
|---|---|---|
| Parse, validate, compile `dialog` documents | pipeline pool | as every content type (plugin.md §2) |
| Render (templates, PlaceholderAPI, item and list sources) and `showDialog` | the player's entity thread, via the gateway | the GUI render's rule; Adventure's thread-safety for `showDialog` is not relied on |
| `PlayerCustomClickEvent` handler | **whatever thread Paper uses (V2)** | the handler does only rate limiting, size check, tag parsing and a concurrent-map lookup, then hops |
| Validation, consume, `dialog.submit` dispatch | the player's entity thread | the run touches the player and may mutate the world |
| Session expiry | `executeEntityLater` on the gateway (plugin.md §2, as built #208) | one cancellable handle per session |

The listener declares its thread class as `ANY` at registration (plugin.md §2) and is an adapter with no policy (logic.md §29
decision 16, R10).

| Module | Holds |
|---|---|
| `api` | `DialogApi` read surface (`RuntimeApi`), the trigger and action ids; no Paper types |
| `content` | `DialogType` (self-description, parse, validate, round-trip, compile to the immutable `CompiledDialog`) |
| `plugin` | `DialogOpener`, the shared `OpenGate`, `DialogSessions`, `SubmissionValidator`, `DialogTriggers`, the two actions, the input method, the admin commands |
| `platform` | the `DialogHost` port (`show(Player, RenderedDialog)`, `close(Player)`, `onSubmit(handler)`) and capability resolution |
| `modern` | `PaperDialogHost`: maps `RenderedDialog` to `io.papermc.paper.dialog.Dialog`, listens to `PlayerCustomClickEvent`. Compiled against the 26.x API (the modern seam, logic.md §29 decision 14); loaded only when §10.1 detects the API |

`platform` compiles against the 1.21.4 API, so no Paper dialog type may appear outside `modern`; a ban test (build.md §5)
fails the build on an `io.papermc.paper.dialog` or `registry.data.dialog` import anywhere else.

---

## 10. Capability gating on the 1.21.4 floor (D10)

### 10.1 Detecting the capability

The descriptor today adds `PlatformCapability.DIALOGS` **unconditionally**, commented "1.21+, below the floor"
(`platform/.../PlatformDescriptors.java`). That is wrong twice: dialogs are 1.21.6+, and the floor is 1.21.4. Row C (§13) fixes it:
`DIALOGS` is present exactly when the server's own classes contain `io.papermc.paper.dialog.Dialog` **and**
`io.papermc.paper.event.player.PlayerCustomClickEvent`, detected by reflection on the server's classes, never by parsing a version
string (the pattern of `modern/RegistryCapabilities`). The descriptor is resolved once at bootstrap (plugin.md §1.1, §6).

### 10.2 Server without the capability (1.21.4, 1.21.5)

| Layer | Behaviour |
|---|---|
| Load, validate, compile | **unchanged**: dialogs parse, validate (levels 1–3) and appear in the studio and the SDK on every server. Level 4 (master §5.4) adds one **info** per pack, `MTR-D01`, "this server cannot show dialogs (needs 1.21.6+)", and the studio greys out **Preview in game**. No quarantine: the content is valid and will work after an upgrade. |
| `view.open` of a dialog, an `open:` command, the admin command | the opener's capability stage runs the dialog's **`fallback:`** steps in the opener's run, if written, and returns `OK` when they succeed; with no `fallback:`, it returns `FAILED`, tells the player one line, and reports `MTR-D02` (rate-limited) |
| `prompt: {method: dialog}` | the method is unavailable; the prompt refuses it before anything is hidden (`MTR-0084`) |
| `client: show_dialog` inside a GUI? | not applicable: client actions exist only inside a dialog |

`fallback:` is an ordinary flow (logic.md §2), so the alternative is whatever the author writes: `view.open` of a GUI they designed,
a `prompt` over chat, a `message`. It is the same shape as `deny:`. Materione does not generate a GUI from a dialog (§1).

### 10.3 Clients older than 1.21.6 on a 1.21.6+ server

A server of 1.21.6 or later only admits older clients through ViaVersion and ViaBackwards. ViaBackwards 5.4.0 added 1.21.6 servers
with dialogs invisible to older clients; 5.6.0 added chest-inventory emulation for 1.21.5 clients, on by default, behind the
`dialogs-via-chests` option (from ViaBackwards' release notes on Hangar and Modrinth). How the emulation lays out inputs and what it
returns is unverified (V7).

**Decided (owner, Q1, 2026-11-19): Materione does not account for older clients.** It sends the dialog to every player; a
server owner who enables dialogs is expected not to admit clients older than 1.21.6. If ViaBackwards translates the dialog anyway,
the server validates whatever comes back like any other submission (§6.3), so a malformed translation is refused, never trusted.
No protocol lookup, no `old_clients:` key and no ViaVersion dependency are added. Bedrock players (Geyser) are not supported yet
(Q2); `fallback:` (§10.2) exists for servers below 1.21.6 and may serve Bedrock work later, but nothing Bedrock-specific is built.

---

## 11. Diagnostics

The block is **to be assigned at implementation** from the next free codes after `MTR-0105` (plugin.md §5), after any block the
parallel HUD design (#7) takes; `DiagnosticCodesTest` pins the count when the codes land. The placeholders `MTR-D01` to `MTR-D09`
below are names for review only and are never emitted.

| Placeholder | Severity | Meaning and recovery |
|---|---|---|
| `MTR-D01` | info | This server lacks the dialog capability (§10.2); dialogs load and validate but cannot be shown. Once per reload per pack with dialogs. Upgrade to 1.21.6+ or write `fallback:`. |
| `MTR-D02` | warning | A dialog could not be shown: no capability and no `fallback:`, or the platform refused the send. The open returned `FAILED`. Rate-limited per dialog. |
| `MTR-D03` | error | A dialog is malformed beyond a per-part skip: unknown `type:`, a required key of its kind missing (`yes`/`no`, `button`, `buttons`, `dialogs`), a duplicate input key or button id, a bound of §6.4 exceeded. The dialog is quarantined; the pack compiles. |
| `MTR-D04` | warning | A part was skipped: an input, body element or button with an unknown type or invalid field, a key on the wrong kind, an `open_url` with a refused scheme, a refused component in body text. The part is absent; the dialog stays live. Targets the part (`inputs.<i>`, `buttons.<i>`). |
| `MTR-D05` | error | A `client: show_dialog` or `dialog_list` chain is a cycle, exceeds depth 4, names an unknown dialog, or names one with `open.permission`/`open.requirement` (client-side navigation cannot check them). The button is skipped (`dialog_list`: that entry). |
| `MTR-D06` | warning | Submissions were dropped before validation: rate limit, oversize payload, missing token fields, an unknown, expired, consumed or foreign token. Counted; at most once per player per minute, with the count. |
| `MTR-D07` | warning | A submission failed input validation (§6.3) and fired nothing. Names the dialog, input and broken rule, never the value. Rate-limited per player. |
| `MTR-D08` | warning | A rule runs a console or op command whose template reads `${input.*}` (§6.5). Run it as the player or constrain the input. |
| `MTR-D09` | warning | A submission arrived for a dialog whose input declaration changed or which was removed since it was shown (§6.2); the player was asked to reopen it. |

The views design's placeholder `MTR-V03` (a `rerender: on_change` dialog with any editable input: `text`, `number`, `boolean` or `option`; `views.md` §10) applies to
dialogs and is emitted by row M; it is numbered with the views block, after this one.

Existing codes keep their meaning for dialogs: `MTR-0008` (schema), `MTR-0021`, `MTR-0057`, `MTR-0058`, `MTR-0060` (rules and flows),
`MTR-0055`, `MTR-0066`–`MTR-0069` (the open block), `MTR-0072` (an item body that cannot be produced), `MTR-0084` (the input method),
`MTR-0105` (a static id in a rule that does not resolve, now including dialog ids).

---

## 12. Editor and code mode

### 12.1 The studio (web.md, gui.md §6, logic.md §7)

The dialog studio is a workspace of the content editor with three tabs, the GUI studio's arrangement:

- **Design.** A form generated from `DialogType.describe()` (no hand-written field list): kind, title, body, inputs and buttons as
  reorderable lists, each row's inspector from the self-description, with the vanilla name of a renamed field shown beside ours.
  Beside it a **preview** drawn in the browser in the vanilla dialog's proportions (widths in GUI pixels, the grid's columns, the
  footer), with templates shown as their source and fonts drawn from the server's pack once V5 confirms they render. The preview is
  an approximation and says so; **Preview in game** shows the real thing.
- **Logic.** The GUI studio's node canvas over `behavior:` and `flows:` (logic.md §7.1–§7.6), with `dialog.*` triggers in its
  palette, the `button:` filter offered from the document's button ids, and `${input.…}` autocomplete from its inputs (the context
  schema, logic.md §2.6). Design ↔ Logic linkage (logic.md §7.4): selecting a button in Design shows its `dialog.submit` rules.
- **Script.** The YAML, as for every type.

The studio writes files through the one authoring path (D1, D6); there is no studio-only state. Diagnostics of §11 target their
part, so a squiggle opens the input or button concerned. Live trace (logic.md §7.6) works unchanged: a submission is a run.

### 12.2 Code mode (D13)

Nothing is written by hand for the SDK (codemode.md §3.1, §3.5). From the registries the generator emits `p.dialogs.create(id, {...})`
with the schema's types (kinds as a discriminated union on `type`, so `yes` on a `notice` is a type error), the actions
the generic `m.actions.viewOpen(handle, args)` / `viewClose()` typed to accept dialog handles, and the four triggers with their context schemas. agent-benchmark.md T28's
program (`p.dialogs.create("rules", …)`, then a `player.join` rule that opens it) type-checks once rows M and S land; its "no
accept, no play" half composes a declared player variable and `run_command` until the entity verbs (#329) give a kick verb.

**Gap stated, not papered over.** `input.<key>` is typed per *document*, not per registry entry. Today's trigger context schemas are
per trigger type, so the generated SDK types `input` as `Record<string, string | number | boolean>`; a precise per-dialog type
needs the generator to read a document's declared inputs (as it would need for `view.args.*`). That is a generator feature recorded for
#337's successor, not a reason for hand-written types.

---

## 13. Test plan and implementation rows

### 13.1 Tests

| Tier | What | Where |
|---|---|---|
| Golden round-trip | every kind, body, input, button and client action, unknown keys, comments; load → save byte-identical | `fixtures/dialogs/*.yml`, `DialogRoundTripTest` |
| Golden compile | `CompiledDialog` and `RenderedDialog` as JSON for each fixture, fixed clock and fixed viewer | `fixtures/dialogs/golden/*.json` |
| Validation | each `MTR-D03`/`D04`/`D05`/`D08` case; bounds of §6.4; the cycle and depth rules | `DialogTypeTest` |
| Submission model (pure) | token single use, foreign player, expiry, replay, `stay` rate, reload fingerprint; every row of §6.3 accepted and refused; **property test** over random payloads (types, sizes, missing and extra keys) that nothing unvalidated reaches dispatch | `SubmissionValidatorTest`, `DialogSessionsTest`, `SubmissionFuzzTest` |
| Taint | `${input.x}` containing `<red>`, `<click:run_command:…>`, `<glyph:x>` renders literally in `message` and `title` | `InputTaintTest` |
| Opener | shared `OpenGate` parity with `GuiOpener` (the same table of permission, requirement, deny, bypass and argument cases runs against both) | `OpenGateParityTest` |
| Mapping (26.x seam) | `RenderedDialog` → Paper `Dialog` for every kind, input and action, compiled and run against `paper-api-26.1.2` | `modern` tests |
| Ban | no Paper dialog import outside `modern` | the ban tests (build.md §5) |
| Capability | descriptor without `DIALOGS` on the 1.21.4 harness; `fallback:` runs; no `fallback:` → `FAILED` + `MTR-D02` | 1.21.4 harness IT (CI) |
| Performance | render of a 32-input, 64-button dialog ≤ 1 ms on the player thread; submission handling before the hop ≤ 50 µs; added as plugin.md §9 rows | `DialogPerfTest` |
| Client (probe) | each kind rendered; submit drives a rule; escape fires `dialog.close`; V2, V4, V5, V6 answered with screenshots | `scripts/probe/` (26.x seam, local) |

### 13.2 QA catalog entries (`qa/catalog/`, qa/README.md)

| Entry | Kind | Asserts |
|---|---|---|
| `dialog.notice.renders` | visual | a `notice` with text and item bodies, glyph in the title |
| `dialog.confirmation.yes-no` | auto + visual | `yes` fires `dialog.submit` `button: yes`; `no` and Escape fire their rules |
| `dialog.form.submit-drives-rule` | auto | #6's exit criterion: an NPC-style form (text, option, boolean, number) sets a variable and messages the values |
| `dialog.multi-action.source-buttons` | auto + visual | a player picker from `online_players`; `entry.name` in context |
| `dialog.list.gates-targets` | auto | a `dialog_list` entry whose target requires a permission is refused server-side |
| `dialog.nested.wizard` | auto | step 1 → step 2 with `args`; step 2 reads them |
| `dialog.prompt.method` | auto + visual | `prompt: {method: dialog}` writes the answer and resumes |
| `dialog.from-gui.returns` | visual | a GUI button opens a dialog; the GUI returns after (V4) |
| `dialog.fallback.below-1216` | auto (1.21.4 harness) | `fallback:` opens the named GUI |
| `dialog.abuse.forged-submit` | auto | a forged custom click (wrong token, other player, replay, bad option, oversize) fires nothing |
| `dialog.image.body` | visual | an `image` body (a designed header) renders inside a `notice`, centred (row I, V5) |

### 13.3 Implementation rows (sized for agents)

| Row | Scope | Depends on | Size | Agent |
|---|---|---|---|---|
| **R** | Probes V2, V4, V5 (first, it gates row I), V6, V8 on the 26.x seam (Paper and Folia). V7 is not probed (Q1). Records results in §2.2 and closes the unverified list. | — | S | Haiku |
| **C** | Capability fix: `DIALOGS` by class detection (§10.1); descriptor tests; the ban test for dialog imports | — | S | Haiku |
| **M** | `DialogType`: self-description (including the view facet, `views.md` §9), parse, validate, round-trip, compile to `CompiledDialog`; the `rerender` field; fixtures and golden files; `MTR-D03` to `D05`, `D08`, `MTR-V03` (numbers assigned here) | — | M | Haiku |
| **G** | Extract `OpenGate` from `GuiOpener`; parity test; no behaviour change for GUIs | — | S | Haiku |
| **S** | Runtime: `DialogOpener`, `DialogSessions` as the dialog kind's viewer records in `ViewRegistry`, `SubmissionValidator`, the rate limiter, taint in the template renderer, the four triggers, the dialog kind behind `view.open`/`view.close`, admin commands, `fallback:`, the re-render path of §6.2; `MTR-D01`, `D02`, `D06`, `D07`, `D09`. The `rerender: on_change` path needs views rows V1 (#456) and V2 (#457) | M, G, C, views V0 (#455); V1, V2 for `on_change` | L | Sonnet, high |
| **H** | `modern`: `PaperDialogHost` mapping and listener; mapping tests | M, C | M | Haiku |
| **P** | The `materione:dialog` input method; GUI hand-off per V4 | S, H, R | S | Haiku |
| **W** | Studio: Design form and preview, Logic tab wiring, Preview in game; `webui` specs | M (S for preview in game) | M | Sonnet, high |
| **I** | The `image` body (§4.2, §14.3): glyph compilation through the GUI raster path, allocation, the studio's image picker, golden pack output | M, R (V5) | M | Haiku |
| **X** | The studio's **HTML/CSS image source** (§14.3, owner answer Q8): the author writes or imports an HTML/CSS fragment, stored as a pack asset; the editor's browser rasterizes it to the PNG the pack compiler needs, recorded against the HTML's hash; a missing or stale raster is a diagnostic; the `image` body or GUI `paint` layer references the HTML | W, I | M | Sonnet, high |
| **K** | Code mode: confirm the generated surface (including `rerender` and `view.refresh` over dialogs), T28 passes in the benchmark harness, the `confirm_dialog` recipe variant | M, S | S | Haiku |
| **Q** | QA catalog entries, the showcase pack's dialog page, docs close-out (runtime-systems §8.2, feature-parity §3.10 as built, parity-reconciliation row, glossary, roadmap) | all | S | Haiku |

R, C, M and G can start in parallel, and so can views row V0 (#455); S is the critical path and starts when G and V0 have
merged; I follows R's answer to V5.

**Row G, as built (folded into views row V0's PR, #455; views.md §13 allows the fold).** The gate is one class, not a copy per
kind: `plugin.view.open.OpenSpecGate` implements `OpenGate`, which now takes a kind-neutral `OpenGate.Subject(kind, id, plan)`
instead of a `RuntimeGuis.Live`. The kind supplies two things and nothing else: its name (the usage line
`/materione <kind> open <id>` and the bypass node `materione.<kind>.bypass.<pack>.<id>`, `OpenPlan.bypassNode(kind, id)`) and an
`OpenContexts` that builds the context its requirement and deny steps run against (the GUI's is `GuiContexts::open`, wired by
`GuiOpenGates.of`). `GuiOpener` asks the gate through a `Subject` and is otherwise unchanged, so GUI behavior is identical.
`OpenGateParityTest` runs one table of permission, argument, requirement, deny, bypass and default cases against a `gui` and a
`dialog` subject. `DialogOpener` (row S) builds its gate with `new OpenSpecGate(permissions, players, denies, variables,
<dialog contexts>)` and attaches its viewer records to `ViewRegistry` through the `ViewKind` SPI (views.md §16).

---

## 14. Competitive bar: mcgui

The owner named **mcgui** (plugin `resourcepack-ai/mcgui`, editor at mcgui.dev) as the competitor dialogs must at least equal. It was
read as reference only, from its README on GitHub (2026-11-18). Its licence is **AGPL-3.0-or-later** with additional terms; nothing of
its code or configuration structure is used here, and nothing below depends on it. mcgui.dev's page is a client-side application
whose fetched text names it only as a free "Minecraft UI Builder" from resourcepack.ai, so the editor's features are **unverified**
beyond what the README states (pages import into a server with `/mcgui import <code>`).

### 14.1 What it is

| Aspect | mcgui (from its README) |
|---|---|
| Authoring | Static **HTML and CSS** files in the plugin's `pages/` folder, drawn by its own renderer ("no browser runs"). A large CSS subset: selectors, flexbox, grid, tables, positioning, gradients, transforms, filters, `clip-path`, inline SVG, PNG/JPEG/GIF, CSS animations and transitions, bundled and custom TTF/OTF fonts. JavaScript only to draw on a `<canvas>` (Rhino, fetched on first use). Not supported: WOFF, WebP, video, iframes, floats, **typing into inputs**, scripts that change the page. |
| How it reaches the client | **Not vanilla dialogs.** Pages are drawn onto **maps**: on walls (invisible glow item frames) or as a **fullscreen view** that locks the camera and moves a pointer, restoring the player's position on exit. With its generated resource pack, maps use a YCbCr encoding and a text shader; without it, dithered map colours. |
| Widgets | links, `<details>`, checkboxes, radios, `<select>`, range and number inputs (click-driven), forms with submit and reset; hover and active states, custom cursors (up to 64), sounds per element, key bindings (`data-key`), player-head images. No text entry. |
| Actions | `data-command` (as the player), `data-console` (refused when a substituted value contains a space or `;`), `data-message` (MiniMessage), `data-action` (close, back, reload, top, `page:`), `data-event` (a Java event for other plugins); placeholders and PlaceholderAPI. |
| Integration | Java events (`PageClickEvent`, `PageOpenEvent`, `PageCloseEvent`) and an API (`open`, `close`, drawing classes). |
| Limits | No stated cap on pages. 2 s render CPU cap per page; images above 128 px per side refused; walls drawn within 48 blocks; shader packs (Iris, OptiFine) break the views; Bedrock players refused. |
| Floor | Spigot, Paper or Folia **1.21.11+**. |

So mcgui and vanilla dialogs are different things: mcgui is a **pixel canvas on maps** with pointer interaction; a dialog is a
**native modal screen** with real form controls. The comparison below is against what an author wants (a rich, interactive screen),
across Materione's view kinds where that is the honest answer.

### 14.2 Parity table

| Capability | mcgui | Materione (this design + shipped GUI) | Verdict |
|---|---|---|---|
| Unlimited screens, hot-reloaded | yes | yes: dialogs are inline (`Dialog.create`), no registry, reloaded by the pipeline | **equal** |
| Text entry (typed answers) | **no** | `text` input, single or multiline, validated (§4.3, §6.3); `prompt: {method: dialog}` | **exceeds** |
| Checkbox, select, slider, number | yes (click-driven) | native `boolean`, `option` (static or from a list source), `number` | **equal**; options from live data **exceed** |
| Buttons, links, copy, suggest | links, commands, actions | server buttons running flows, `open_url`, `copy_to_clipboard`, `suggest_command`, `show_dialog` (§4.4) | **equal** |
| Behavior on click | fixed attributes (`data-command`, `data-console`, `data-message`, `data-action`) or a Java event | the full logic engine: conditions, `require`, money, items, variables, waits, GUIs, every registered action (D4) | **exceeds** |
| Console-command injection | blocks spaces and `;` in substituted values | typed and validated inputs, `pattern`, a load warning for console templates that read inputs, tainted-text rendering (§6.5) | **exceeds** |
| Forged-click resistance | not described | per-session tokens, player binding, single use, rate limits, server-held state (§6) | **exceeds**, as far as can be compared |
| Navigation between screens | `page:`, back | `view.open` (gated), `view.back`, `args` for wizards, static `show_dialog`, `dialog_list` with gates | **equal or better** |
| Player heads, items | head images | `item` bodies from any item source (Materione items, heads, integrations) | **equal**; custom items **exceed** |
| Free-form layout and styling (CSS: flex, grid, gradients, fonts, transforms) | **yes, its core strength** | Vanilla dialogs have a fixed layout (title, body column, inputs, button grid). Freedom comes from (a) **`image` bodies** (§4.2): any designed bitmap, drawn in the GUI studio or imported, shown inside the dialog; (b) the text pipeline's styling (colors, gradients, glyphs, shader effects, master §5.7); (c) the **GUI view kind** for screens whose whole surface is designed (gui.md §11, the open canvas: art past the container, pixel layers, buttons, items) | **gap closed for static art** by `image` bodies (row I) and by HTML/CSS image sources (row X, Q8); **not equal** for layout that reflows live, which Materione does not attempt (§14.3) |
| Animations (CSS, GIF) | yes | text shader effects (master §5.7); no animated images in dialogs | **gap**, recorded; animated glyphs are a pack-compiler question outside #6 |
| Hover states, custom cursors, per-element sounds, key bindings | yes | native button hover and tooltips; sounds through flows on submit; no cursors or key bindings (vanilla dialogs expose neither) | **partial**; the vanilla client offers no hook for the rest |
| Canvas scripting | Rhino JavaScript on the server | **refused by D13**: code is never stored content and never runs on the server; dynamic content is templates, sources and conditions | **deliberately not** |
| In-world wall screens, many viewers | yes (maps on item frames) | none | **gap, outside #6** (Q9: filed as #453, a map-canvas view kind with #335) |
| Fullscreen camera-locked view | yes | dialogs and GUIs are native screens; no camera lock | **different model**; the view kind of #453 would cover it |
| Works with shader packs (Iris, OptiFine) | **no** (views break) | yes: native screens are not drawn through world shaders | **exceeds** |
| Works without a resource pack | dithered colours | dialogs fully, except `image` bodies and custom glyphs | **exceeds** |
| Bedrock players | refused | not supported yet (Q2); nothing Bedrock-specific in v1 | **neither**; not a target yet |
| Version floor | 1.21.11+ | content loads on 1.21.4+; dialogs show on 1.21.6+; `fallback:` below | **exceeds** |
| Web editor | mcgui.dev (features unverified), import by code | the live relay editor (D11): Design form and preview, Logic node canvas, Script, preview in game, diagnostics, live trace; agent authoring through code mode (D13) | **exceeds** on what can be verified |
| Extension | Java events and a drawing API | registries (D5): addons add actions, conditions, item and list sources, input methods; the SDK picks them up | **exceeds** |

### 14.3 How Materione reaches the bar, and why HTML/CSS is not the file format

**Why the file format is not HTML/CSS.** The layout freedom mcgui sells needs a renderer that turns CSS into pixels. On a server
that is a large component (mcgui's own, under the AGPL) whose output is a bitmap: every screen becomes a picture on maps, with
pointer input and no native text entry. Taking that route for dialogs would (1) give up the native controls that are a dialog's
point, starting with typing; (2) put a second layout language beside the stored text form (master §5.7) and the GUI's layer model,
with no self-description that a studio form or the SDK can be generated from (D5, D13); (3) make HTML documents a second source of
truth for appearance beside the pack (D1, D3); and (4) bind content to a CSS subset whose edge cases one renderer defines. The
better choice for a *modal form* is the native screen, and for a *designed surface* it is the GUI view kind, which already gives
pixel-level freedom with a studio built for it (gui.md §6, §11).

**What closes the real gap.** Static designed art inside a dialog is the part of mcgui's freedom that an author of forms, rules
screens and NPC panels uses, and the design now covers it:

1. **`image` bodies** (§4.2, row I): any bitmap, shown inside the dialog, compiled through the existing GUI raster path. Authors
   draw it with the GUI studio's paint and image tools or upload it.
2. **HTML/CSS as an image source** (row X, owner answer Q8): the author's HTML/CSS is **kept as the stored asset**, because a
   PNG alone loses the design (owner, 2026-11-19). The PNG is a derived output: the editor runs in a real browser, so it
   rasterizes the HTML natively when a raster is needed (on save, and whenever the HTML's hash no longer matches the recorded
   raster), and the pack compiler consumes that PNG through the GUI raster path. The server needs no HTML renderer. The HTML is
   the one source of truth (D1); the raster is a cache of it, never edited by hand, and a missing or stale raster is reported,
   not silently used (D6). An HTML file edited outside the editor therefore needs the editor to re-rasterize it; a server-side
   renderer would remove that step but would draw CSS differently from the browser the author previewed in, so it is not
   planned. Live reflow and CSS animation do not carry over, as the table records.
3. **The GUI view kind** for screens whose whole surface is the design, unchanged.

**What is left as a gap, on purpose or for the owner.** Animated images (a pack-compiler question), custom cursors and key bindings
(no vanilla hook), canvas scripting (D13), and map-drawn wall and fullscreen screens (#453, a view kind of its own with #335).

---

## 15. Decisions this design takes (reversible by the owner)

1. **Every input-reading action is a server flow.** No client `run_command` or `dynamic/run_command` (owner answer Q3).
2. **`dialog_list` compiles to a gated `multi_action`**, not to vanilla's client-side list (§4.1).
3. **One custom-click identifier, a random per-session token, single use by default** (§6.1, §6.2); Paper's callback API is not used.
4. **Session state per shown dialog** (§8, `views.md` §3.4; reversed from "no session scope" by the owner's no-legacy answer of
   2026-11-19, which made `session` the per-instance scope of every opened kind): wizard state is `args`; shared state is keyed
   state (#333 step 2).
5. **No re-render on state change by default** (§8, `views.md` §4.3): re-sending resets the player's typing; `rerender: on_change` opts in.
6. **The capability is detected by class presence**, and the unconditional `DIALOGS` is a bug to fix (§10.1).
7. **Below 1.21.6, content stays valid**; showing runs `fallback:` or fails with a diagnostic (§10.2). No generated GUI imitation.
8. **Renamed fields** (`option`, `number`, `after_submit`, `close_on_escape`, `client:`) say what the author gets; vanilla's names
   appear beside them in the studio and SDK descriptions.
9. **Dialogs render as vanilla dialogs**, not as maps or a camera-locked view (§14.2); rich imagery comes through `image` bodies,
   and free-form canvases stay the GUI view kind's job.
10. **Escape is made observable** by compiling an exit into a server button where vanilla allows one (§5.1).
11. **Older clients are not accounted for** (owner answer Q1): dialogs are sent to everyone; admitting pre-1.21.6 clients is the
    server owner's choice.
12. **`dialog.submit` reaches the dialog's own host only** (owner answer Q4); `rule` documents do not receive it.
13. **HTML/CSS is a stored image source and its PNG is derived** in the editor's browser (owner answer Q8, row X).

---

## 16. Owner questions and answers

Answered by the owner on 2026-11-19 unless marked open (Q5). None decides a master §14 question; Q2 leaves §14.3 open.

| # | Question | Answer |
|---|---|---|
| **Q1** | Old clients (≤ 1.21.5) on a 1.21.6+ server through ViaVersion/ViaBackwards: send, or run `fallback:` for them? | **Send; do not account for them.** A server owner who enables dialogs should not admit older clients. V7 is not probed (§10.3). |
| **Q2** | Bedrock players (Geyser/Floodgate)? (master §14.3) | **Not now.** Bedrock is not supported by Materione yet and is much later work; nothing Bedrock-specific in v1. What helps later at no cost (`fallback:`) stays. |
| **Q3** | Offer vanilla's client-side `run_command` / `dynamic/run_command` buttons? | **No: server flows only** (§15 decision 1). |
| **Q4** | Should `rule` documents also receive `dialog.submit`? | **No**; the dialog host only (§15 decision 12). |
| **Q5** | Pause-menu and quick-actions dialogs: in scope? | **Open.** Vanilla 1.21.6+ lets a server add a button to the pause (Escape) screen that opens a dialog (`pause_screen_additions` tag) and list dialogs under the Quick Actions key (`quick_actions` tag). Tags belong to the dialog registry the client receives while joining, and Paper freezes registries at server start, so adding or removing such a dialog takes a restart. Materione already has a restart-required path for registry paintings (runtime-systems.md, `MTR-0028`/`MTR-0029`), so (b) reuses it; v1 left it out only to keep the first slice reload-only. Options: (a) out of v1; (b) a `pause_menu:` / `quick_actions:` key, restart-required, through the paintings' registry seam, as a late row of #6. |
| **Q6** | Diagnostic block: the next free block after `MTR-0105` and after the HUD design's (#7), at implementation? | **Yes.** |
| **Q7** | Sequencing with #335: build now to §8's contract, or wait for #335? | **(c): write #335's design document first, then build #6 to it** (owner, 2026-11-19). The document is pulled forward from M7 so dialogs stay in M6 and are not built twice; §8's table is replaced by references to it: the document is **`views.md`** (PR #454), whose owner answers (2026-11-19) are folded into §4 rule 7, §6.2 and §8. #335 depends on keyed state #333 (M6.5); what dialogs need from it is small (one modal per player, no re-render by default). |
| **Q8** | The studio's HTML/CSS import: keep only the PNG? | **Keep the HTML; derive the PNG when needed.** A PNG alone loses information. The HTML/CSS is the stored asset; the editor's browser rasterizes it when a raster is needed (§14.3, row X). |
| **Q9** | mcgui's map-drawn wall and fullscreen screens: file a view-kind issue? | **Filed as #453** (M7 with #335; the owner may move it). |
