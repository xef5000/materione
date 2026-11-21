# Views over state: many viewers; the GUI, the dialog and the HUD as view kinds (#335) — design

> **Status.** Design, for review on the PR that adds it (`Refs #335`). Nothing here is built. The owner decided on 2026-11-19
> (dialogs design, PR #451, answer Q7) that this document is written **before** dialogs (#6) and the HUD (#7) are built, so
> that both are built to it once and not twice. The document is therefore pulled forward from M7; dialogs and the HUD stay M6.
> The parts that need keyed state (#333 step 2) stay M7 and are marked as such (§6, §13). **The owner answered every question
> on 2026-11-19 by accepting the defaults (§14.1)**, and qualified Q1 and Q7 in a review on #335 the same day (the
> schema-versioned GUI default, since withdrawn; reactive-dialog safeguards, §4.3) with three acceptance cases (§11.1). The
> same day the owner settled compatibility: **there is no public release with existing GUIs, so no GUI behavior is kept for its
> own sake** (§7). This document is therefore the clean design, not a fold-down: where the first draft compromised to preserve
> shipped GUI behavior, it now says what the model would be from scratch and adopts it (§7.1). The decisions are in §14.2; every
> owner question is answered (Q9, the last, on 2026-11-19).
>
> **Scope.** The view model that primitives.md §5.3 directs: what a view kind is, what is viewer-local and what is state, how a
> write to state finds the viewers that read it, how many players view one thing, and how the shipped container GUI, the dialog
> (#6) and the HUD (#7) map onto it, with room for the map canvas (#453). It also lists the exact changes the two unmerged design
> documents (`dialogs.md`, PR #451; `hud.md`, PR #452) need to conform (§12), and the implementation rows sequenced so #6 and #7
> can start against it in M6 (§13).
>
> **Traceability.**
>
> | Decision or rule | How this doc applies it |
> |---|---|
> | **D12** (master §3.1) | The whole document. A view is a primitive (U1 to U5, §8); nothing is named for a use case; T02's shared window is a composition (§6.3). |
> | **D1** | Views add no content of their own beyond the view kinds' existing documents. Viewer-local state and the reads index are runtime state, never a second source of truth (§3.3, §5.2). |
> | **D4** | One engine: a view reads state with the logic engine's templates and conditions (logic.md §2.3, §2.5) and the one variable store (logic.md §20). There is no view binding language (§5.1). |
> | **D5** | A view kind is a content type that declares a **view facet** in its self-description (§9); the new verb and keys are registry entries and schema fields, so the editor and the SDK render them with no hand-written surface. |
> | **D6** | A view renders compiled definitions published by the shadow swap; no editor path changes a live view directly (§5.2). |
> | **D8** | Every render and every send runs on the viewer's owner thread through the gateway; a write marks viewers dirty from any thread without touching their state (§5.6). |
> | **D13** | The SDK surface (the verb, the keys, the view references) is generated (§9). |
> | master §5.5, §5.6 | **Lossless round-trip binds** (load, untouched save, byte-identical; unknown fields kept). **GUI behavior is not preserved** (owner, 2026-11-19: no public release has GUIs); the breaking changes are listed in §7, and shipped fixtures and built-in GUIs are updated by the rows that change them. |
> | master §14.2 | Not decided here: whether a view's state can live across servers is the store adapter's question (§6.4). |
> | primitives.md §4.6, §4.7, §5.2, §5.3, §5.6 | `GuiSession` (class F) is split into binding, viewer-local state and state (§3.3); session variables are declared state behind the store port, per view instance (§3.4); target addressing reaches views (§3.6). |
> | plugin.md §2, §5, §9 | Threading (§5.6), placeholder diagnostics (§10), new budget rows (§5.7). |

---

## 1. Goals and non-goals

**Goals.**

| # | Goal | Traces to |
|---|---|---|
| G1 | One **view model** that the container GUI, the dialog and the HUD each implement as a **kind**, with one way to bind state, one way to find viewers, and one re-render path. | primitives.md §5.3, #335 |
| G2 | **Viewer-local state is render state only** (page, search, last render, a dialog's token); every datum a rule or another viewer could need is **state** in the variable store. | #335 "only page and search stay viewer-local" |
| G3 | "**Re-render the viewers of key K**" is a verb (`view.refresh`), and a write to K does it on its own where the kind allows (§5.5). | #335 |
| G4 | **Many viewers** of one thing, with no shared session object: two players in one trade window are two viewers of the same keyed state (§6). | primitives.md §3 need 3, agent-benchmark.md T02 |
| G5 | #6 and #7 can be built in M6 against this document without waiting for keyed state (#333 step 2); what waits is named (§6.1, §13). | owner, PR #451 Q7 |
| G6 | **The cleanest, most extensible model**: it does everything we can think of and extends to what we did not think of (owner, 2026-11-19). Shipped GUI behavior is changed where a cleaner model needs it; lossless round-trip still binds (§7). | owner, master §5.6 |
| G7 | Budgets stated and CI-tracked, including the fan-out of one write to many viewers (§5.7). | plugin.md §9 |

**Non-goals.**

| Not here | Why, and where it goes |
|---|---|
| Keyed state itself (key expressions, TTL, expiry, the entity holder) | #333 step 2 designs and builds it (logic.md §20, to be written there). This document states only what views need from it (§6.2). |
| Custody of items that two players can both move (a shared offer region) | #334 (custody holders and transfer). A view displays custody; it does not make a second holder's items editable (§6.3). |
| Cross-server viewers | Master §14.2 (cross-server state adapters) is open; views read state through the store port and inherit whatever that port answers (§6.4). |
| The map canvas view kind | #453 designs it. §4.5 says what the model leaves room for. |
| A rendering consistency guarantee between viewers | Views converge at each viewer's next eligible render, normally the next tick but later under a held cursor (logic.md §6.4) or a dialog's 20-tick floor (§5.4). No view promises visible consistency within one tick. Decisions read state, never what a viewer was shown (§6.3 rule 3). |

---

## 2. Vocabulary

| Term | Meaning |
|---|---|
| **View kind** | A content type whose resources render state for viewers: `gui` (shipped), `dialog` (#6), `hud` (#7), later the map canvas (#453). Declared by the **view facet** of the type's self-description (§9). |
| **View definition** | One resource of a view kind (`shop:menu`, `rpg:mana`), compiled and published by the shadow swap. |
| **Binding** | What a view was opened *about*, fixed for its lifetime: `args` (logic.md §6.1), the **subject stack** (backpack-preset.md §2.2, P-A), and later the **key** (§6.2). A HUD has no binding. |
| **Viewer** | One player attached to one view definition under one binding. The record the runtime keeps for it is one generic **viewer record** (`ViewerRecord`: identity, binding, session state handle, dirty bit) carrying a kind-specific viewer-local payload (`GuiViewerState`, `DialogViewerState`, `HudViewerState`). `GuiSession` folds into it (§3.8). |
| **Viewer-local state** | What only the viewer's own screen needs: list pages, search text, the last render (the diff base and the layers click routing follows), a dialog's token and snapshots, a HUD's last-sent strings and animation phase. Never read by a rule outside that viewer's own runs. |
| **State** | Values in the variable store: the scopes `player`, `server`, `session` (logic.md §20 decision 3), `item` (#333 step 1) and later `keyed` (#333 step 2). |
| **State key** | One addressable value: a declared variable plus its holder (the player, nothing for `server`, the viewer for `session`, the stack for `item`, the key value for `keyed`). |
| **Reads** | The state keys and polled sources a view definition's templates and conditions read, extracted at compile time (§5.2) and refined per viewer at render (§5.3). |
| **Viewer-set rule** | How a kind forms its viewers: `opened` (a verb attaches, a close detaches), `predicate` (continuously, every player for whom a condition holds), `spatial` (players in range of a placement; reserved for #453). |
| **Rerender policy** | Whether a change to a view's reads re-renders its viewers on its own: `on_change` or `off` (§5.5). |

---

## 3. The view model

### 3.1 Shape

```
                       shadow swap (D6)
 view definitions ──► compiled definitions + ReadsIndex (StateKey family ──► definitions)      immutable, per generation
                                     │
 variable store write (any thread) ──┼──► StateWriteObserver ──► ReadsIndex hit? ──no──► return (≤ 250 ns)
                                     │                                 │yes
                                     │                                 ▼
                                     │           ViewRegistry: viewers of those definitions whose
                                     │           binding or last-render keys match the written holder
                                     │                                 │ set dirty bit (CAS)
                                     │                                 ▼
                                     │           ViewScheduler: one wake per viewer per tick, on the
                                     │           viewer's owner thread (gateway, D8), honouring the
                                     │           kind's rerender policy and minimum interval
                                     ▼                                 │
 opener / predicate / placement ──► ViewRegistry.attach(viewer)        ▼
                                              kind.render(definition, binding, viewer, viewer-local, ctx)
                                                 reads state NOW ──► frame ──► kind.deliver (diff, send)
```

There is **no shared session object**. Two viewers of "the same" view share it only because their renders read the same state
keys. That is the whole of G4, and it is what makes a view a primitive rather than a trade window: anything that can be
written to the store can be watched by any number of viewers of any kind.

### 3.2 The view kind contract

A kind implements one internal SPI in `plugin/view` (module placement in §13). It is not a public addon API in v1 (decision
D-13, owner answer Q5).

| Member | Meaning | GUI | Dialog | HUD |
|---|---|---|---|---|
| `contentType` | The registered type it renders | `gui` | `dialog` | `hud` |
| `viewerRule` | §2 | `opened` | `opened`, at most one per player | `predicate` |
| `reads(definition)` | Compile-time read set (§5.2) | templates, `visible_if`, item and list sources, dynamic titles | templates, `visible_if`, item and list sources, at open | layer templates, `visible_if`, `worlds`, `permission` |
| `render(...)` | Pure: definition, binding, viewer, viewer-local state, the logic context → a frame | slot appearances, title, hidden layers (logic.md §24 decision 13) | `RenderedDialog` (dialogs.md §7.1) | one string per channel (hud.md §8.2) |
| `deliver(...)` | Owner thread: diff with the last frame, send only what changed | `RenderDiff` (logic.md §24 decision 13) | re-send the dialog, new token (§4.3) | channel writers (hud.md §8.2) |
| `rerenderDefault` | §5.5 | `on_change` (owner, Q1) | `off` | always `on_change` |
| `minInterval` | Fewest ticks between two automatic renders of one viewer | 1 | 20 | 1 (2 for server-driven animation, hud.md §5.6) |
| Viewer record | The generic `ViewerRecord` with the kind's payload | per window (`GuiViewerState`) | per shown dialog (`DialogViewerState`) | `HudViewerState`: **one per online player**, from join to quit, attached to zero or more visible HUDs, composing them into one output per channel (§5.3, hud.md §8.2) |

The contract is the one sketched in hud.md §3.1 (`reads`, `viewers`, `render`, `viewerLocal`), with `deliver`, the rerender
policy and the minimum interval added because the dialog needs them (§4.3).

### 3.3 Binding, viewer-local state and state

The audit classes `GuiSession` as feature-shaped (primitives.md §4.7): it is the unit of sharing, and it holds one player's
data. The model splits what it holds into three classes, each with one owner:

| Class | Lifetime | Who may read it | Where it lives | GUI today (`GuiSession`, logic.md §21 decision 1) |
|---|---|---|---|---|
| **Binding** | fixed at open, for the viewer's life | the view's own runs, its renders | the viewer record | `args`; the subject stack (P-A, when #333 step 1 or #304 builds it); the key (§6.2) |
| **Viewer-local** | the viewer's life; a reload keeps it (logic.md §21 decision 17) | the viewer's own renders and runs only | the viewer record, confined to the owner thread | `pages()`, the search text (logic.md §29), the last render, the ticker handles |
| **State** | the store's (per declaration) | any run, any render, through `vars.*` | the variable store, behind the port (logic.md §20 decision 12) | `vars()` (the `session` scope): see §3.4 |

The rule that makes the split hold: **a value another player's view or a rule outside the viewer's own runs could need is
state, never viewer-local.** Page and search text are the only per-viewer *navigation* the issue names; the last render and
a dialog's token are per-viewer *mechanism*, invisible to content, and so viewer-local by the same rule. The view history
(§3.6) is not viewer-local: it belongs to the player's navigation across views, and lives in `ViewRegistry` per player.

### 3.4 Session variables

**`session` is state whose key is the view instance**: private to one viewer of one opened view, alive while that view is open,
gone when it closes. A replaced view is a new instance; `view.back` re-opens a snapshot's binding and session values as a new
instance (§3.6). This is the shipped GUI semantics (logic.md §21 decisions 1, 2, 17), and it stays because it is good design
on its own merits: per-window UI state (a mode toggle, a selected tab, a draft value) has no other correct home, and it must not
leak into `player` state or be shared with another viewer.

What changes from the shipped GUI, because the clean model wants it:

| Shipped | Clean model | Why |
|---|---|---|
| GUIs only | **every `opened` kind**: GUIs and dialogs (a dialog's re-render keeps the instance and its session; a new dialog is a new instance) | one scope for one concept; a dialog with `stay` buttons that toggle a choice needs it as much as a GUI. HUDs have none: they are not opened and have no instance (hud.md §7.1, `MTR-0116`) |
| two spellings, `vars.session.x` and `gui.session.x` | **one**: `vars.session.x` | two names for one value is a trap for authors and the reads index; `gui.*` is gone as a root (§3.7) |
| undeclared names (any `set_var session.x` creates a key) | **declared** (owner, Q9): a `variable` with `scope: session`, typed and defaulted, like every other scope; an undeclared `vars.session.x` or `set_var session.x` is `MTR-0063` at load | a typo then fails at load instead of creating a silent key (U5); the type and default reach the studio and the SDK (U4, D5); the reads index keys on the declared id (§5.2) |
| `GuiSession.vars()`, a map beside the store, written by `GuiSessionVariables.put` past the port | **behind the store port** from row V1: a `session` store keyed by the viewer record's identity; one write path for every scope (§5.3) | the port is the one place writes are observed; a bypass is a bug class (acceptance case 2) |

When #333 step 2 restates the scopes as named keys, `session` is the named key whose holder is the view instance; nothing about
it changes then, because V1 already put it behind the port. A shared view does not share session state: each viewer of a keyed
view has its own, and the shared part is keyed state (§6.2).

### 3.5 Viewer-set rules

| Rule | Attach | Detach | Kinds |
|---|---|---|---|
| `opened` | `view.open` admits the player through the kind's opener and the shared `OpenGate` (dialogs.md §7.1) | close, replace, quit, removal, expiry | GUI; dialog (one live dialog per player: a new one replaces it, dialogs.md §6.2) |
| `predicate` | the visibility condition becomes true, re-evaluated on the events and writes of hud.md §6.2. Every online player is a **candidate** (the kind's per-player record exists from join), so a write to a visibility read reaches a player who sees no HUD yet (§5.3) | the condition becomes false (the record stays, attached to nothing), quit | HUD |
| `spatial` | reserved: a player comes into range of a placement | out of range, quit | map canvas (#453) |

The viewer set is never authored as a list of players. It follows state: a HUD shown to one player reads a variable in its
`visible_if` (hud.md §6.3); a GUI shown to two players is opened for both (§3.6). U2 holds because the set is whatever content
makes it.

### 3.6 Verbs and target addressing

From scratch, a view verb names what it does to **a view**, not to one kind: the same four verbs serve every `opened` kind, and
a fifth kind (#453) needs no new verbs. The shipped per-kind verbs (`gui.open`, `gui.close`, `gui.back`, `gui.refresh`) are
replaced, not aliased: an alias would be a second spelling of one capability (U1), and there are no existing GUIs to keep.

| Verb | Parameters | Meaning | Row |
|---|---|---|---|
| **`view.open`** | `view:` (a reference to any definition whose kind is `opened`), `args`; later `target:` (#331) and `key:` (§6.2) | Attach a viewer (the run's player, or the target) under a binding, through the kind's opener and the shared `OpenGate`. `FAILED` when refused or unavailable, so `require`/`else` compose. Naming a `predicate` kind (a HUD) is a load error (`MTR-0057`). | V0 |
| **`view.close`** | optional `view:` (close only if that definition is the one shown), later `target:` | Detach the run's viewer, or the target's. The kind fires its own close trigger with reason `action`. | V0 |
| **`view.back`** | — | Re-open the previous view from the player's **view history**, a per-player stack (16 deep) of snapshots (definition, binding, session values) of opened views that another open replaced, **across kinds** (a GUI, then a dialog, then back to the GUI). Any other end drops the stack. `FAILED` "nothing to go back to" when empty. | V0 |
| **`view.refresh`** | at most one selector: none (the run's own viewer), `view:` (a definition: all its viewers), `var:` with optional `holder:` (the viewers whose reads contain that state key); later `key:` (§6.2) | Re-render the selected viewers. With no selector it is a **full** render of the run's own viewer (logic.md §24 decision 15) and fails `"no open view"` when that viewer's record is stale or closed (the shipped `gui.refresh` rule, kept because it is right: a flow must learn that its window is gone). | V2 |

Kind-specific verbs remain only where the capability belongs to one kind's components: `gui.page` (a `list` component's page)
and `gui.slot_item` (a `slots` region) stay, because they act on a GUI component, not on a view. The condition `in_gui` becomes
`in_view` (`view:`, `kind:`), by the same reasoning.

`view.refresh` is the verb #335 names ("re-render the viewers of key K"). Automatic re-render (§5.5) makes it unnecessary for
writes to declared state; it remains for what the reads index cannot see (a polled read the author knows has changed) and for a
definition-wide refresh. Selecting zero viewers is `OK`. Threading: `ANY`; renders run on each viewer's owner thread (§5.6).

**Target addressing.** A view verb with `target:` attaches or detaches *another* player, resolved and checked by the engine
through the gateway (primitives.md §5.6). That is how a flow run by one player shows a window to two (§6.3). The target value is
#331's and shipped before V4 ([logic.md](logic.md) §39: `target: self | event.target | {arg} | {player} | {nearby}`, resolved once by the
engine and handed to the action as a subject). V4 therefore adds no parser and no resolver: `view.open` and `view.close` declare
`.withTargets(PLAYER)` in their self-description (which is what puts `target` in the SDK) and the GUI and dialog openers read their
viewer with `Subjects.player(context)` instead of the run's player; the opener's `OpenGate` and the viewer's own region do the rest.

### 3.7 The view context: `view.*`

From scratch, the facts of "the view this run or render belongs to" have **one root for every kind**. The shipped kind roots
(`gui.id`, `gui.args.*`, `gui.session.*`, `gui.page.*`) and the planned `dialog.id`, `dialog.args.*` are replaced by:

| Path | Value | Kinds |
|---|---|---|
| `view.id` | The definition id | all |
| `view.kind` | `gui`, `dialog`, `hud` (later the map canvas) | all |
| `view.args.*` | The binding's arguments | opened kinds |
| `view.item` | The subject stack (P-A); null when none | opened kinds |
| `view.key` | The binding key (§6.2); null until #333 step 2 | opened kinds |
| `view.page.*` | A `list` component's page state, by layer id | GUI |

Event facts keep their own roots, because they describe the event, not the view: `click.*`, `slot.*`, `layer.*`, `entry.*`,
`close.*` (GUI), `input.*`, `button.*`, `entry.*`, `close.*` (dialog). Session state is `vars.session.*` (§3.4). A logic recipe
or a preset written against `view.*` works for every kind, which is the extensibility the owner asked for. The compiler's root
check (`MTR-0060`) admits `view` and no longer admits `gui` or `dialog` as roots.

### 3.8 What becomes of `GuiSession`

| Today (`GuiSession`) | Clean model |
|---|---|
| GUI id, arguments | the `ViewerRecord`'s definition and **binding** |
| `vars()` (session variables) | **state**, in the `session` store behind the port (§3.4) |
| `pages()`, search text, last render, rendered layers | **viewer-local**, in `GuiViewerState` (the click router follows the last render, logic.md §24 decision 14) |
| `SessionHistory` (GUI back stack) | the per-player **view history** across opened kinds (§3.6) |
| run identity (cancellation key) | the `ViewerRecord`'s identity |
| "one per player" (`GuiSessions`) | kept: vanilla shows one container and one dialog at a time, a platform fact, not a compromise. `ViewRegistry` enforces it per kind and is indexed by player, by definition and (step 2) by key |
| `GuiSessionPort`, `%materione_gui_open%`, `%materione_gui_last%` | `%materione_view_open%` and `%materione_view_last%` (`<kind>:<id>`), answered by `ViewRegistry` and the view history |
| the unit of sharing | **gone**: nothing is shared through a viewer record (§3.1) |

`GuiSession` does not survive as a class: the generic record plus a kind payload is what lets a fourth kind arrive without a
fourth session type. Row V0 makes the change and updates the shipped GUI code, tests and fixtures with it (§13).

---

## 4. The kinds

### 4.1 Overview

| | Container GUI (shipped) | Dialog (#6) | HUD (#7) | Map canvas (#453, reserved) |
|---|---|---|---|---|
| Content type | `gui` | `dialog` | `hud` | to be designed |
| Viewer-set rule | `opened`, one GUI per player | `opened`, one dialog per player | `predicate` | `spatial` or `opened` |
| Binding | `args`, subject stack, key | `args`, key | none | to be designed |
| Viewer-local | pages, search, last render | token, button table, list snapshots, option snapshots | last-sent strings, poll cache, phase | likely: last frame per map |
| Sibling triggers | `gui.*` | `dialog.*` | none in v1 | to be designed |
| Rerender default | `on_change`; `off` per file | `off`; opt-in `rerender: on_change` | always | likely always |
| What a re-render costs the player | nothing (slots and title rewritten in place) | **their typing** (vanilla has no update packet) | nothing | nothing |
| Many viewers | yes, via keyed state (§6) | yes, each its own modal | yes, by nature | yes, by nature |

### 4.2 Container GUI

The GUI is already almost a view kind: its renderer is pure and per viewer (`SlotRenderer`, logic.md §24 decision 13), it diffs
(`RenderDiff`), it has a partial render that re-reads only dynamic layers (decision 15), and its clock (`refresh:`, decision 6)
polls. What changes:

1. Its session becomes a viewer record (§3.8); nothing visible.
2. Its dynamic layers' reads are indexed (§5.2), and a write to one re-renders the viewer with a **partial** render (the dynamic
   layers and the dynamic title, logic.md §6.4) under the GUI's rerender policy (§5.5: `on_change` by default, `off` per file).
3. `refresh:` keeps its meaning: the clock re-reads dynamic layers every N ticks. It is now needed only for polled reads; a
   GUI whose dynamic layers read only declared variables updates without one.
4. A window is never changed in the middle of the player's own click (logic.md §21 decision 8): automatic renders are
   scheduled wakes, never inline in a click.
5. **The held-cursor rule stands** (logic.md §6.4): while the cursor holds an item and a title change is pending, the whole
   displayed render, its click and input rules and its hitboxes stay frozen together. An automatic render that would change
   the title then does not apply; the dirty bit stays set, and the owned retry after the cursor clears renders the latest
   state. The displayed frame and its click targets never diverge.
6. **Click targets follow state (a design property, owner review Q1).** The click router follows the last rendered frame
   (logic.md §24 decision 14). With automatic re-render, a write can reveal or hide a layer between two clicks without the
   author calling `view.refresh`, so the same click can run a different rule than it did a moment earlier. That is the intended
   meaning: the player clicks what they see, and what they see is current state. Two rules keep it safe: a frame and its click
   targets change together, never one without the other (and the held-cursor freeze of item 5 holds both still while the
   player drags); and an effect never depends on which frame was clicked, only on state the flow re-reads (§6.3 rule 3). An
   author who wants a window that changes only when a flow says so writes `rerender: off`.

### 4.3 Dialog

A dialog is a native modal screen; the client offers no update packet, so **re-sending is the only re-render, and it resets
what the player typed** (dialogs.md §8). The kind therefore differs on purpose:

- **One live dialog per player** (dialogs.md §6.2). The viewer-set rule is `opened` with that cap; a second open replaces the
  first.
- **Rerender default `off`.** A dialog renders at open and on an explicit `view.refresh` that selects it by `view:` or as the
  run's own viewer. A `var:`-selected refresh and an automatic change do not re-send it unless the document says
  `rerender: on_change`. Rationale: an explicit selection is the author asking for a re-send; a state change is not.
- **A re-render is a re-open under the same binding.** The renderer re-reads state, the opener's gates are **not** run again
  (the viewer was admitted), a **new token** is issued and the old one consumed (dialogs.md §6.2 single use), the list and
  option snapshots are retaken, and **no `dialog.close` fires** (it is the same view, not a replacement). A submission carrying
  the old token after the re-send is refused like any stale token (`MTR-D06` in dialogs.md's placeholders).
- **No resend when nothing changed (owner, Q7).** On an automatic wake the renderer builds the new `RenderedDialog` and the new
  submission snapshot (button table, offered option ids, source entries) and compares both with the session's. When **both are
  unchanged**, nothing is sent and the **current token is kept**; only a difference in either re-sends. An explicit selection by
  `view.refresh` re-sends regardless (the author asked).
- **`minInterval` 20 ticks.** An `on_change` dialog over a hot variable re-sends at most once a second. The floor **limits how
  often a form resets; it does not protect an unfinished form**: a change that lands while the player is mid-way still wipes
  their entries at the next eligible re-send.
- **An editable-input warning (owner, Q7).** `rerender: on_change` on a dialog with **any editable input** (`text`, `number`,
  `boolean`, `option`) is a load warning (§10, `MTR-V03`), because a re-send loses a checkbox or an option selection exactly as it
  loses text. The opt-in stays allowed. A vote or a live tally with buttons only is the intended use (§6.3).

### 4.4 HUD

A HUD is continuous: its viewers are every player for whom its visibility holds (hud.md §3.1, §6), it has no open or close verb,
no binding and no triggers in v1. It differs on purpose in three ways:

- **Per-viewer key resolution, no binding.** A HUD that reads keyed state resolves the key per viewer at render
  (`vars.keyed.party[${vars.player.rpg.party}]…`, §5.3); nothing is fixed at attach.
- **Per-tick budgets and its own wakes.** The HUD keeps the action-bar keepalive and the server-driven animation wake
  (hud.md §8.3) as kind-specific wakes in its own scheduler link; the dirty wake is the shared one (§5.4).
- **Polled reads by default.** World facts and PlaceholderAPI tokens poll at the HUD's `refresh` (default 5 ticks,
  hud.md §4.2), which is the GUI's `refresh:` clock under another default.

### 4.5 Room for a fourth kind: the map canvas (#453)

The model leaves these seams for #453 and decides nothing for it:

| Seam | What the map canvas would use |
|---|---|
| `viewerRule = spatial` | Viewers in range of an in-world placement (item frames), or `opened` for the fullscreen view |
| `render` / `deliver` | A frame of map pixels from the GUI's raster layers (gui.md §11); deliver diffs per map |
| `minInterval` | Map packets are heavy; a larger floor, set by #453's frame budget |
| Reads index, write observer, `view.refresh`, `view.*` context | Unchanged |
| Viewer record | Per (player, map) last frame |

A fifth kind follows the same table. That is the U2 test for the model itself: no part of §3 or §5 names a kind.

---

## 5. Change propagation

### 5.1 Two classes of read

| Class | Examples | How a change reaches the view |
|---|---|---|
| **Notified** | a declared variable in any scope (`vars.player.shop.coins`, `vars.server.shop.season`, `vars.session.page_mode`, later `vars.item.*`, `vars.keyed.*`); viewer facts the engine sees (the viewer's world, permission recalculation where the provider signals it) | The store's write path or the event tells the observer (§5.3); the viewer re-renders under its rerender policy. |
| **Polled** | world and entity facts (`player.health`), PlaceholderAPI `%tokens%`, item and list sources (`online_players`, `materione:item`), provider reads (`balance`, `has_money`), another player's variable read by name, a variable named by a computed id | The kind's clock re-reads it (`refresh:` on a GUI, `refresh` on a HUD); `view.refresh` forces it. |

There is no third class and no binding language: a view reads exactly what a rule can read, with the same templates.

### 5.2 Compile-time extraction (the reads index)

**Decision (D-1): reads are extracted from the compiled definition, not declared by the author and not recorded at render.**

| Option | Verdict |
|---|---|
| (a) **Extract at compile time** from every template, condition, item source and list source of the definition (the expression AST, logic.md §15; the compiled condition trees, §2.3) | **Chosen.** No author effort, no second list to drift from the templates (master §5.6's one-source rule), offline-checkable (U5), and the index is a pure function of the shadow, built off-thread at the swap. |
| (b) Author-declared `reads:` | Rejected: a second truth beside the templates that goes stale on the first edit. |
| (c) Record every read during each render | Rejected as the primary: a render that skips a branch (a hidden layer) would forget a read that un-hides it. Used only as a refinement for concrete keys (§5.3). |

The index maps a **state-key family** (a declared variable id and its scope, `session` included, §3.4) to the set of definitions that read it. For a `predicate` kind it records the
family's role too: a **visibility read** (the definition's `visible_if`, `worlds`, `permission`) or a **content read** (its
layers' templates and conditions). A read
extraction cannot place (a variable named by a computed id) is classed polled and reported once as an info (`MTR-V01`, §10), so
the author learns why a value updates only on the clock. The index is rebuilt with every shadow and swapped with it; a reload
that adds a read is followed by the next write.

### 5.3 The write observer and matching

One observer is called after **every successful write**, on the writer's thread. It looks up the family in the current index;
a miss returns (the common case, budget §5.7). The shipped code has three write paths, and each must call it (verified in the
code at 305ecdd8; acceptance case 2, §11.1):

| Path | Where | How the observer is reached |
|---|---|---|
| `VariableStore.write` (set, clear: `set_var` with `set`/`remove`/`reset`, `/materione var set`, `LogicApi.variables().set`) | `api/…/variable/VariableStore.java`; callers `RunVariables.write`, `VariableService` | an observing decorator around each scope's store, the way `SnapshotVariableStore` already wraps the player store |
| `VariableStore.mutate` (the atomic ops: `add`, `subtract`, `multiply`, `toggle`) | the same port, a separate default method; caller `RunVariables.mutate` | the same decorator overrides `mutate` and notifies when the outcome succeeded |
| `SessionVariables.put` (`set_var session.x`) | today `GuiSessionVariables.put` writes `GuiSession.vars()` directly and **bypasses the store port** | removed by row V1: session state moves behind the port as the `session` store (§3.4), so it takes the two rows above; no bypass remains |

A hit narrows to viewers by the written holder:

| Scope of the write | Holder | Viewers re-rendered |
|---|---|---|
| `player` | the player P | viewers of the reading definitions **whose viewer is P** (a view reads its own viewer's player variables); for a `predicate` kind, **P's candidate record** whether or not it is attached to that definition |
| `server` | — | every viewer of every reading definition; for a `predicate` kind whose **visibility** reads it, every candidate record (every online player) |
| `session` | the window instance | that window's viewer record only |
| `item` (#333 step 1) | the stack (its holder id) | viewers whose binding's subject stack has that holder id |
| `keyed` (#333 step 2) | the key value K | viewers whose binding key is K, plus viewers whose **last render resolved K** (a HUD or a layer that computes its key) |
| `local` | — | never (a run's locals are not state) |

"Last render resolved K" is the one place a render records keys: each render stores the concrete keys its keyed reads resolved,
at most 16 per viewer (a 17th classes that read polled for that viewer, with `MTR-V02`). The record is viewer-local, replaced
by each render, so a key that stops being read stops matching.

**Attaching on visibility (acceptance case 1).** The table matches *attached* viewers for content reads, but a `predicate`
kind's visibility reads match **candidates**: the kind's per-player record exists from join to quit even when no definition is
visible to that player. A write that matches a visibility read marks that record dirty; its wake re-evaluates the visibility of
every definition of the kind for that player, attaches the newly visible, detaches the newly hidden, and renders one composed
output per channel from all visible definitions (hud.md §8.2). So `set_var: {var: "rpg:hud_on", value: true}` on a player who sees
no HUD shows `rpg:mana` at that player's next eligible render, with no rejoin and no unrelated poll; and when two HUDs are visible
their pieces still concatenate into one boss-bar string and one action-bar string. A candidate costs memory, not wakes (§5.7).

A write that changes nothing (the same value) still notifies in v1: the store does not compare. The render's diff then sends
nothing (§3.2 `deliver`), so the cost is one render, not a packet.

### 5.4 Coalescing and scheduling

- **One dirty bit per viewer record.** The observer sets it with a compare-and-set; only the transition from clean to dirty
  schedules a wake. A hundred writes in one tick cost one automatic render.
- **The wake** is a one-shot on the viewer's owner thread through the gateway (`executeEntityLater`, plugin.md §2), at the next
  tick, or later if the kind's `minInterval` since the last automatic render has not passed. There is no global loop over
  viewers; the pattern is `gui.tick`'s chain of one-shots (logic.md §21 decision 15).
- **The one-per-tick limit applies to automatic renders only.** An explicit `view.refresh` from the viewer's own thread renders
  at once (the shipped `gui.refresh` rule, logic.md §21 decision 16); from another thread it schedules an immediate wake that
  ignores `minInterval`.
- **Clear, then read.** A render clears the dirty bit **before** it reads state. A write that arrives during the render sets the
  bit again and schedules another wake, so no write is lost and none is rendered twice for nothing.
- **Explicit refresh while a wake is queued.** The explicit render clears the bit; the queued wake runs, finds the bit clean and
  does nothing (unless a later write set it again).
- **The window replaced before the wake runs.** A wake carries its viewer record's identity and checks, on the owner thread,
  that the record is still live and still the player's current window (the check `view.refresh` makes). A stale record's wake
  is dropped: it never renders into the new window, which rendered itself when it opened. `view.refresh` with no selector on a stale
  record fails `"no open view"` (§3.6).
- **Held cursor and throttled dialogs.** A wake that meets the GUI's held-cursor freeze (§4.2 item 5) leaves the bit set and
  applies nothing; a dialog wake inside its 20-tick floor is deferred to the end of the floor (§4.3).
- **Latency and consistency.** A write is shown at each viewer's **next eligible render**: normally the next tick, later under a
  held cursor or a dialog's floor. Two viewers may show different values meanwhile. No kind promises visible consistency within
  one tick; views are a display, not a lock (§6.3 rule 3).

### 5.5 Rerender policy

| Kind | Key | Values | Default | Effect |
|---|---|---|---|---|
| `gui` | `rerender:` (new, top level) | `on_change`, `off` | `on_change` | `off`: only the clock, `view.refresh` and a reload render |
| `dialog` | `rerender:` (new, top level) | `on_change`, `off` | `off` | §4.3 |
| `hud` | none | — | always `on_change` | a HUD is continuous |

The key is a schema field of each kind's type, so it self-describes (D5). Absent means the default, so no existing file is
rewritten.

**No schema-versioned default (owner, 2026-11-19, Q8).** The review's first form of Q1 asked for a default that flips with the
pack's `schema_version`. The owner withdrew the need: no public release has GUIs, so `on_change` is simply the GUI default
everywhere and `off` is a per-file choice. `pack.yml`'s `schema_version` (parsed by `PackManifest`) is left as it is; this
design gives it no meaning. Shipped fixtures and built-in GUIs that relied on no automatic re-render are updated when row V1
lands (§13).

### 5.6 Threading (D8) and Folia regions

| Work | Thread | Rule |
|---|---|---|
| Extract reads, build the index | pipeline pool | pure; published by the shadow swap's volatile write |
| The write observer | the writer's thread (any: player writes run on the player's region, server writes on any, logic.md §20 decision 11) | reads the immutable index; walks concurrent viewer sets; sets dirty bits; schedules wakes. **Touches no viewer-local state.** |
| Render and deliver | the viewer's owner thread (its entity scheduler on Folia, the main thread on Paper), via the gateway | the only place a viewer record is mutated |
| State reads during a render | the viewer's owner thread | the viewer's own player state is live; another holder's state is read through the store, which answers off-owner reads with snapshots (logic.md §18, *Player variables*, #250). Keyed state must offer the same (§6.2) |
| Attach and detach | the opener's or predicate's owner thread | `ViewRegistry` is concurrent; attach is published before the first render |

On Folia, two viewers of one keyed view in different regions render on their own regions. Neither reads the other's player
state live; the shared state they both read is in the store, never in either viewer. That is why §3.3 forbids shared data in
viewer records: it would be state read across regions with no owner.

### 5.7 Budgets (proposed rows for plugin.md §9)

| Operation | Budget | Test |
|---|---|---|
| A variable write that no view reads | ≤ 250 ns added to the write (one index lookup) | `ViewReadsIndexTest`; the `WorldEventGate` precedent |
| A write that one view reads, one viewer | ≤ 2 µs to match, mark dirty and schedule (render excluded) | `ViewReadsIndexTest` |
| Fan-out: a `server` write read by one definition with 200 viewers | ≤ 100 µs on the writer's thread (≤ 0.5 µs per viewer) | `ViewFanOutPerfTest` |
| Automatic renders per viewer per tick | ≤ 1, whatever the writes (explicit refreshes are not limited) | `ViewSchedulerTest` (virtual clock) |
| Candidate records of a `predicate` kind | 0 wakes while nothing they read changes; ≤ 512 B each | `ViewSchedulerTest`, `ViewFootprintTest` |
| Idle viewer (no write, no polled read) | 0 wakes | `ViewSchedulerTest` |
| Memory per viewer added by the model | ≤ 512 B (dirty bit, schedule handle, ≤ 16 resolved keys) | `ViewFootprintTest` |
| Each kind's render | the kind's own row: GUI refresh pass (shipped, `DynamicSlotsPerfTest`), dialog render (dialogs.md §13.1), HUD render pass (hud.md §10) | unchanged |

Honesty note: a `server` variable that changes every tick and is read by a view every player has renders every viewer every
tick. The index makes that cost proportional to the readers rather than to all views, but it does not make it free; the
budget per viewer is the kind's render row. `/materione doctor` lists the definitions with the most viewers re-rendered per
second (row V1), so an owner can see which variable drives it.

---

## 6. Many viewers

### 6.1 Without #333 step 2 (buildable in M6)

| Capability | How |
|---|---|
| Many viewers of **server** state (a shop's stock, a season counter, a global event bar) | Any view reading `vars.server.*`: every viewer re-renders on a write (§5.3). |
| One player's state shown on that player's views, live | `vars.player.*` reads, matched by viewer. |
| A view shown to another player | `view.open` with `target:` (#331, M6.5). |
| Re-render a definition's viewers or a variable's readers on demand | `view.refresh` (row V2). |
| A dialog vote shown to many, each with a live tally of a server variable | `view.open` of the dialog per player, `rerender: on_change`, buttons only (§4.3). |

### 6.2 With #333 step 2 (M7, or when T02 is scheduled)

What waits, and what views need of keyed state:

| Capability | Needs from #333 step 2 | View side (row V5) |
|---|---|---|
| Per-instance shared mutable state (two offers, two confirm flags) | `scope: keyed` with `key:` an expression | reads index matches `keyed` writes by key (§5.3) |
| A view **bound** to a key | — | `key:` on `view.open` (a template); `view.key` in context (§3.7); `ViewRegistry` indexed by key |
| "Re-render the viewers of key K" | — | `view.refresh: {key: …}` |
| Close every viewer when the key expires | the `expire` trigger and TTL | a flow on `expire` runs `view.close` with `target:` per viewer, or `view.refresh` to show "expired"; the close reason stays `action` (no new reason; dialogs keep `expired` for their own session TTL, dialogs.md §6.2) |
| `session` as a named key | the restatement of the four scopes | §3.4 |
| Off-owner reads of keyed state during renders on other regions | keyed stores answer concurrent reads safely (snapshots or a concurrent map; writes serialised per key) | none: a requirement stated to #333 |

A key binding is a label, not a container: it holds no data. Opening the same definition with the same key for two players
gives two viewers whose renders read the same keyed values. The owner confirmed this over a shared session object (Q3).

### 6.3 Walk-through: T02's shared window

agent-benchmark.md T02, with #330 (commands), #331 (target), #333 step 2 (keyed state) and #334 (custody) assumed. Shape, not
syntax (primitives.md §3's table, made concrete):

```yaml
# variables/trade.yml — keyed by the trade id
offer_a_ready: { scope: keyed, key: "${args.trade}", type: boolean, default: false, ttl: 60s }
offer_b_ready: { scope: keyed, key: "${args.trade}", type: boolean, default: false, ttl: 60s }

# guis/trade.yml — one definition, two viewers per trade
trade:
  container: chest_6
  title: "Trade"
  layers:
    - { id: mine,   type: slots, region: …, input: true, durable: required, holder: …  }   # custody holder per #334
    - { id: theirs, type: list,  source: …, … }                                             # display of the other offer
    - { id: ready_a, type: item, item: "${vars.keyed.trade.offer_a_ready ? 'lime_dye' : 'gray_dye'}", … }
  behavior:
    - on: gui.click
      layer: confirm
      do:
        - set_var: { var: "trade:offer_a_ready", value: true }   # both viewers re-render next tick (§5.3)
        - require: [ … both flags … ]                            # the decision reads state
        - …transfer (#334)…

# commands/trade_accept.yml — opens the view for both under one key
do:
  - view.open: { view: trade, key: "${args.trade}" }                         # the accepter
  - view.open: { view: trade, key: "${args.trade}", target: { player: "${args.from}" } } # the requester (#331)
```

Three rules make the composition correct, and none is trade-specific:

1. **Sharing is state.** The two windows are two GUI viewers bound to one key; each has its own pages, cursor and session
   variables.
2. **Freshness is automatic.** A write to `offer_a_ready` re-renders every viewer bound to that key (§5.3) at each viewer's next
   eligible render (§5.4); no flow lists viewers.
3. **Decisions read state, never the frame.** A click routed on a stale frame runs a flow that re-reads the flags (`require`),
   so no effect is decided on what a player was shown. The click router follows the rendered frame (logic.md §24 decision 14)
   only to choose which rule runs, never to decide an effect. **This does not make the swap happen exactly once**: two clicks
   handled on two regions can both read both flags as true. Exactly-once belongs to the atomic transfer between holders, its
   joint decision record and its recovery (#334, primitives.md §3 the honest unknown), not to views.

**What #335 does not supply.** Moving items in the *other* player's offer region, the atomic swap and its recovery are #334's
(primitives.md §3 needs 4 and 5). Until #334, each player edits only their own custody, and the other's offer is shown
read-only from state. The exit criterion of #335 says exactly this much: "T02's shared window renders for both players".

### 6.4 Across servers

A view reads state through the store port. If an adapter (master §14.2, open) makes a keyed or server variable shared across a
network, a write on another server reaches this server's views only if the adapter calls the same write observer when it
learns of the change. That is a requirement on any future adapter, recorded here, not a choice of adapter.

---

## 7. Compatibility: what binds and what breaks

**Decided (owner, 2026-11-19):** *there is no public release with existing GUIs yet, so we don't have to support older formats;
the goal is the GUI system that can do everything we can think of and can be extended to what we did not think of.* Two things
follow and are kept apart:

- **Lossless round-trip still binds** (master §5.6). It is a property of the file layer, not legacy support: a document loaded
  and saved untouched is byte-identical, and unknown fields survive. Every key this design adds or renames obeys it.
- **GUI behavior is not preserved for its own sake.** Where the clean model differs from the shipped GUI, the shipped GUI
  changes, and the row that changes it updates the fixtures, the built-in pack's GUIs (the content browser), the logic recipes,
  the showcase pack, the QA catalog and the SDK goldens in the same PR.

### 7.1 Breaking changes from the shipped GUI

| Shipped | Clean model | Row | Kept or changed, and why |
|---|---|---|---|
| `gui.open`, `gui.close`, `gui.back` | `view.open`, `view.close`, `view.back` | V0 | **Changed**: one verb set for every opened kind (§3.6). |
| `gui.refresh` | `view.refresh` (no selector = full render of the run's own viewer, `"no open view"` when stale) | V2 | **Changed** name; the semantics are kept because they are right (§3.6). |
| `in_gui` condition | `in_view` (`view:`, `kind:`) | V2 | **Changed**: the same reasoning. |
| `gui.id`, `gui.args.*`, `gui.page.*` roots | `view.id`, `view.args.*`, `view.page.*` | V0 | **Changed**: one root for view facts (§3.7). |
| `gui.session.*` | removed; `vars.session.*` only | V0 | **Changed**: one spelling (§3.4). |
| undeclared session variables | declared, `scope: session`, typed and defaulted; undeclared is `MTR-0063` | V1 | **Changed** (owner, Q9; §3.4). |
| session state in `GuiSession.vars()`, written past the store port | the `session` store behind the port | V1 | **Changed**: one observed write path (§3.4, §5.3). |
| `GuiSession`, `SessionHistory`, `GuiSessionPort` | `ViewerRecord` + kind payload; per-player view history across kinds; `ViewRegistry` | V0 | **Changed**: no per-kind session type (§3.8). |
| `%materione_gui_open%`, `%materione_gui_last%` | `%materione_view_open%`, `%materione_view_last%` | V0 | **Changed** with the registry. |
| re-render only on the clock, `gui.refresh` or a reload | `rerender: on_change` by default; `off` per file | V1 | **Changed** (owner, Q1, Q8): click targets follow state (§4.2 item 6). |
| `refresh:` (the clock) | the same key; now only for polled reads | — | **Kept**: the clock is the right tool for what cannot notify, and the HUD uses the same word. |
| session per window, carried by back, kept across reload | the same, for every opened kind | — | **Kept** as good design (§3.4). |
| one GUI per player; close reasons; live reload keeps the viewer | the same | — | **Kept**: a platform fact and correct semantics. |
| `gui.page`, `gui.slot_item` | the same | — | **Kept**: they act on GUI components, not on views. |
| The `MTR-` codes | unchanged meanings; new codes are additive (§10) | — | **Kept**: codes are API (plugin.md §5). |

## 8. Universality tests and the D12 audit

### 8.1 What this design adds (master §3.1, primitives.md §2.2)

| Surface | U1 name | U2 generality | U3 composition | U4 self-description | U5 safety |
|---|---|---|---|---|---|
| The view model (kind contract, registry, reads index, observer) | "view", a capability | any kind, any state key, any number of viewers | content binds state with templates; no Java to make a view live | each kind is a registered content type with a view facet (§9) | the index is built offline from the compiled shadow; a dry run builds it and renders nothing |
| `view.refresh` | a verb named for what it does | any definition, any state key, any viewer | a flow step; composes with `require` | an action description with typed selectors | `FAILED` only on a bad selector; zero viewers is `OK`; dry-run stages nothing |
| `rerender:` | a policy, not a use case | every opened kind | a key on the document | a schema field with an enum | absent = the schema-versioned default; validated at load (`MTR-V03` for editable inputs) |
| `key:` on `<kind>.open` (step 2) | a binding | any key expression, any opened kind | a parameter of the existing verb | a parameter description | a template typed at run; unknown keyed variable is `MTR-0063` |
| `view.*` context | the view's own facts | every kind | readable by any template | in every view trigger's context schema | no effect |

Nothing is named for a use case: no trade window, shared menu, vote, scoreboard or party HUD exists in the engine; each is a
composition (§6).

### 8.2 The audit row this changes (primitives.md §4.7)

| Surface | Class before | Class after | How |
|---|---|---|---|
| `GuiSession` | **F** | **P** (`ViewerRecord`) + state | replaced per §3.3, §3.8; session variables are declared state behind the port |
| `session` scope (primitives.md §4.6) | **F** | **P** after #333 step 2 | the viewer's named key (§3.4) |
| `gui.open`, `gui.close`, `gui.back`, `gui.refresh` (primitives.md §4.4, View row) | P+ | P | replaced by `view.open`, `view.close`, `view.back`, `view.refresh` for every opened kind; `target:` from #331 |
| "Re-render every viewer of key K" (primitives.md §3 need 3) | P+ | P | §5.3, `view.refresh`, row V5 for keys |

The audit document gets a pointer from §5.3 to this design (this PR); its tables are updated when the rows ship, as every
fold-down is (master §3.1, consequence 2).

---

## 9. Self-description and the SDK (D5, D13)

- **The view facet.** A content type's self-description gains an optional `view` block: `{viewerRule, rerenderDefault,
  supportsTarget, supportsKey}`. `gui`, `dialog` and `hud` declare it. It is how the SDK generator and the editor know that a
  type is a view kind, with no list of kinds written anywhere else. **No new registry** (decision D-6): the content-type
  registry already holds the kinds, and an addon view kind needs the internal SPI too, which is not public in v1 (owner answer Q5).
- **Session declarations.** A `scope: session` declaration is an ordinary `variable` resource, so it is in the `variable`
  type's self-description and the generated SDK like any other (`p.variables.create(id, { scope: "session", type, default })`,
  and `vars.session.<pack>.<key>` typed by it). The GUI and dialog studios' Logic tabs offer **declaring a session variable**
  where a rule or template names an undeclared one (the existing declare-variable path, typed from the self-description), so an
  author never has to leave the studio to fix `MTR-0063`.
- **References.** `view.refresh`'s `view:` parameter is a `REFERENCE` whose kinds are the types that declare the facet, so the
  generated type is the union of GUI, dialog and HUD handles; a static id that resolves to nothing is `MTR-0105`, its existing
  meaning (plugin.md §5).
- **Generated surface.** `m.actions.viewOpen(handle, …)`, `viewClose`, `viewBack`, `viewRefresh({ view } | { var, holder } | { key })`, the
  `rerender` field on `p.guis.create` and `p.dialogs.create`, and (with #331 and #333) `target` and `key` on `viewOpen`. Nothing is
  written by hand (codemode.md §3.5).
- **codemode.md §9.2** writes the missing primitive as `rpg.guis.get("trade").views.forEachViewer(offers, …)`. Under this design
  the passing program needs no such loop: it opens the GUI with `key:` for both players (§6.3) and freshness follows state. The
  error that program shows today stays an honest type error (`viewOpen`, `key` and `target` do not exist yet); codemode.md is
  updated when row V5 ships, not here.
- **Benchmark.** T28 passes with #6 (the dialog kind on rows V0 and the shared opener); T20 with #7 (the HUD kind on V0 and V1),
  #329 and #332; T02's shared window with V5, #330, #331, #333 step 2, and #334 for the swap (agent-benchmark.md §4.2 rank 9).

---

## 10. Diagnostics

Codes are **assigned at implementation**, from the next free codes after the blocks the dialog (#6) and HUD (#7) designs take
(plugin.md §5). The placeholders below are names for review and are never emitted.

| Placeholder | Severity | Meaning and recovery |
|---|---|---|
| `MTR-V01` | info | A view reads a variable the reads index cannot place (a computed id, another player's variable by name); that read updates only on the view's clock or `view.refresh`. Once per definition and read per reload. Name the variable statically, or add a clock. |
| `MTR-V02` | info | A viewer's render resolved more than 16 keyed keys; the excess reads are polled for that viewer. Rate-limited per definition. |
| `MTR-V03` | warning | `rerender: on_change` on a dialog with an editable input (`text`, `number`, `boolean`, `option`): a change to its reads re-sends it and loses what the player entered or selected; the 20-tick floor limits how often, not whether. Use buttons, or `off` with an explicit `view.refresh`. |
| `MTR-V04` | warning | `key:` on an open whose definition reads no `keyed` variable, or before keyed state exists on this build. The key is ignored; the view opens unbound. (Row V5.) |

Existing codes keep their meaning: `MTR-0057` (a bad `view.refresh` parameter), `MTR-0060` (an unknown root, now admitting
`view`), `MTR-0063` (an undeclared variable in `var:`), `MTR-0073` (a render failure), `MTR-0105` (a static view id that
resolves to nothing).

---

## 11. Tests and QA

| Tier | What | Where |
|---|---|---|
| Unit (pure) | read extraction for every template, condition and source shape; the index for a corpus of GUIs; the matching table of §5.3 row by row; dirty coalescing and `minInterval` on a virtual clock; the "no viewer-local mutation off-owner" rule with a recording gateway | `plugin` `view` tests |
| Goldens | GUI render goldens updated deliberately by the row that changes a behavior (V0 context roots and verbs, V1 auto re-render); every update names its §7.1 row | existing GUI render goldens |
| Perf | the rows of §5.7 | `ViewReadsIndexTest`, `ViewFanOutPerfTest`, `ViewSchedulerTest`, `ViewFootprintTest` |
| Harness IT (1.21.4) | a `server` variable written by the console reaches an open GUI's next render; `view.refresh` by definition; reload keeps viewers | `PaperViewIT` |
| QA catalog (`qa/catalog/`, qa/README.md) | `view.server-var.live` (auto + visual): two clients have one GUI open; one clicks a button that writes a server variable; both windows show the new value at their next eligible render. `view.refresh.polled` (auto): a polled read changes and `view.refresh` shows it. With row V5: `view.keyed.two-viewers` (visual): T02's shared window renders for both players. Blessed on main. | `qa/catalog/views.mjs` |

---

### 11.1 Acceptance cases (owner review, 2026-11-19; required before V1 and V2 merge)

| # | Case | What must hold | Row |
|---|---|---|---|
| 1 | **A player with no visible HUD shows their first one** | With `visible_if: "${vars.player.rpg.hud_on}"` and `hud_on` false, a `set_var` to true attaches the HUD for that player at the next eligible render, with no rejoin and no unrelated poll (the candidate record, §5.3). Setting it false detaches it. With two visible HUDs, their pieces compose into one boss-bar string and one action-bar string. | V1 (index roles, candidates); #7 row V (composition) |
| 2 | **Every successful write path notifies the right reader** | A console `/materione var set` (`VariableStore.write`), an atomic server increment (`set_var` `add`, `VariableStore.mutate`) and a session toggle (`set_var session.x` `toggle`, through the `session` store that replaces the `GuiSessionVariables.put` bypass) each re-render exactly the matched viewers. `vars.session.x` is the one spelling of a window-local key (the owner's wording asked that `gui.session.x` index the same key; that spelling is removed, §3.4). Session variables are **declared** (owner, Q9): the index keys on the declared id, an undeclared session read or write is `MTR-0063` at load, and a never-written session variable reads its declared default. Holds before V5. | V1 |
| 3 | **A wake updates the right window at the next eligible render** | The one-per-tick limit applies to automatic renders only. An explicit refresh while a wake is queued renders once, and the wake is a no-op. A write during a render is shown by a following render (clear, then read). A window replaced before its wake runs is not rendered by that wake; `view.refresh` on a stale record fails `"no open view"` (the shipped `gui.refresh` rule under the new name). Under a held cursor, frame and click targets stay frozen together and the latest state renders after release. No GUI or throttled dialog test asserts visible consistency within one tick. | V1 (scheduler); V2 (explicit refresh) |

## 12. Required changes to dialogs.md (PR #451) and hud.md (PR #452)

Neither branch is edited by this PR. Each change below is what that design must say to conform; each is small.

### 12.1 `dialogs.md` (#451)

| # | Where | Change |
|---|---|---|
| D1 | §8 (whole section) | Replace the "view contract (#335 sketch)" table with a reference to views.md §3 and §4.3, keeping only the dialog-specific statements: one live dialog per player, rerender default `off`, re-send resets typing. Owner answer Q7 already asks for this. |
| D2 | §1 non-goals, "Shared, multi-viewer dialogs" | Restate: many players can each have a dialog bound to the same state (views.md §6.1, §6.2; `key:` with #333 step 2), each its own modal; what is out of scope is a single screen shared by two players, which vanilla does not offer. |
| D3 | §3 vocabulary, "Dialog session" | Split per views.md §3.3: `args` (and later the key) are the **binding**; token, button table and list/option snapshots are **viewer-local**; the record is the dialog kind's **viewer record**. |
| D4 | §4 file format, §4.x | Add the top-level key `rerender: off \| on_change` (default `off`), to the schema (row M) and the example, with views.md §4.3's semantics. |
| D5 | §6.2 session binding | Add: a re-render (an `on_change` dialog, or `view.refresh` that selects it) re-reads state, does not re-run the gates, issues a new token and consumes the old one, retakes the snapshots, and fires no `dialog.close`; a submission with the old token is refused as stale (`MTR-D06`). |
| D6 | §5.1 triggers | Every `dialog.*` context also carries `view.*` (views.md §3.7); `dialog.args` and `view.args` are one map. |
| D7 | §5.2 actions | The dialog open action (`view.open` after D14) gains `key` (views.md §6.2, with #333 step 2) beside the planned `target`; note that `view.refresh` applies to dialogs (views.md §3.6, §4.3) and no `dialog.refresh` is added. |
| D8 | §7.1 opener | `DialogOpener` attaches the viewer to `ViewRegistry` (views.md row V0). Row G (`OpenGate` extraction) and row V0 both touch `GuiOpener`: G lands first or in the same PR as V0. |
| D9 | §11 diagnostics | Add the views placeholder `MTR-V03` (rerender on a dialog with any editable input) to the list of codes that apply to dialogs, or own it there; the numbering stays "at implementation". |
| D10 | §13.3 rows | Row S depends on views row V0; the `rerender` opt-in depends on V1 and V2; row K's SDK check includes `rerender`. |
| D11 | §8 universality table | Add the `rerender` key row (views.md §8.1). |
| D12 | §15 decisions 4 and 5, §16 Q7 | Point decisions 4 (no session scope) and 5 (no re-render by default) and the Q7 answer at views.md (§3.4, §4.3) instead of "when #335 lands". |
| D13 | §4 rule 7, §6.2 (owner review of #335, Q7 qualified) | `MTR-V03` warns for every editable input, not only `text`/`number`; an automatic wake whose rendered dialog and submission snapshot are both unchanged keeps the token and sends nothing; the 20-tick floor limits resets but does not protect an unfinished form. |
| D14 | §5.1, §5.2, §7.2, §8, §12.2, §15 decision 4 (owner, 2026-11-19: no legacy) | The clean-model ripple: the actions `dialog.open`/`dialog.close` become `view.open`/`view.close` (the triggers `dialog.opening`, `dialog.open`, `dialog.submit`, `dialog.close` stay: they name dialog events); the `dialog.*` context root becomes `view.*` (`view.id`, `view.args`); dialogs gain the `session` scope per shown dialog, kept across a re-render (reverses decision 4); `dialog_list` and `fallback:` examples run `view.open`; the SDK is `viewOpen`/`viewClose`. |

### 12.2 `hud.md` (#452)

| # | Where | Change |
|---|---|---|
| H1 | §3.1 | Replace "the minimum contract … whichever lands first" with a reference to views.md §3.2: the HUD implements the view kind contract (`viewerRule = predicate`, no binding, always `on_change`). Delete the "if the HUD lands first, `plugin/hud/` implements it privately" paragraph. |
| H2 | §17 Q4 | Mark answered: (b), views.md first (owner, 2026-11-19, PR #451 Q7, applied to both kinds by #335). |
| H3 | §7.2 reads | The reads table and the "write observer" are views.md's shared reads index and `StateWriteObserver` (§5.2, §5.3, row V1), not a HUD index; keep the HUD's polled classes and PlaceholderAPI-on-Folia rule. Add per-viewer key resolution for keyed reads (views.md §4.4, §5.3). |
| H4 | §8.1 runtime shape | `HudService`'s "registry of viewers, the reads index" become `ViewRegistry` and `ReadsIndex` in `plugin/view`; `HudViewer` is the HUD kind's viewer record (viewer-local state only). |
| H5 | §8.3 cadence | The dirty wake is views.md's `ViewScheduler` (§5.4); poll, animation and keepalive stay HUD wakes in the same per-viewer chain. |
| H6 | §10 budgets | Move "a variable write that no HUD reads ≤ 250 ns" and "one viewer ≤ 2 µs" to views.md §5.7 (shared rows); keep the HUD render, packet, PlaceholderAPI, memory and compile rows. |
| H7 | §7.1 bindings | HUD renders carry `view.*` (views.md §3.7: `view.id`, `view.kind`; no `args`, `item` or `key`). `MTR-0116` stands and is consistent with views.md §3.4. |
| H8 | §1 non-goals, "HUD-specific actions" | Add: the generic `view.refresh` (views.md §3.6) re-renders a HUD's viewers, including polled reads; no `hud.refresh` is added. |
| H9 | §6 visibility | Name the rule: the HUD's viewer-set rule is `predicate` (views.md §3.5). |
| H10 | §16 rows | Row V depends on views rows V0 and V1; remove "(or #335's, if landed)". Row K's SDK check includes the view facet. |
| H11 | §17 Q5 | Note that views.md endorses (a), one component registry with surfaces, as the D12 reading of one view model. |
| H12 | §12 diagnostics | No change of numbers; views.md takes its codes after the HUD's block. |
| H13 | §6.2, §6.3, §8.1, §8.3 (owner review of #335, acceptance case 1) | State the attach-on-visibility rule: `HudViewer` exists per online player from join (a candidate, attached to no HUD); a write to a visibility read marks it dirty and its wake attaches newly visible HUDs, so the `hud_on` toggle of §6.3 works with no rejoin or unrelated poll; two visible HUDs compose into one string per channel. |

---

## 13. Implementation rows

Sized for agents (agent delegation policy: senior and cross-cutting work on Sonnet at high effort, regular implementation on
Haiku). The milestone column is the owner's answer to Q6.

| Row | Scope | Depends on | Milestone | Size | Agent |
|---|---|---|---|---|---|
| **V0** ([#455](https://github.com/xef5000/materione/issues/455)) | `plugin/view`: the kind SPI (§3.2), `ViewRegistry`, the generic `ViewerRecord` with kind payloads replacing `GuiSession`/`GuiSessions`/`SessionHistory` (§3.8); `view.open`, `view.close`, `view.back` with the per-player view history across kinds, replacing `gui.open`/`gui.close`/`gui.back`; `view.*` as the one view root, replacing `gui.id`/`gui.args`/`gui.page`/`gui.session` (§3.7); `%materione_view_open%`/`_last%`; the view facet in self-descriptions (`gui` declares it); the built-in browser, recipes, showcase, fixtures, QA catalog and SDK goldens updated in the same PR (§7.1) | #6 row G (the `OpenGate` extraction from `GuiOpener`) merged first, or folded into the same PR | M6 | M | Sonnet, high |
| **V1** ([#456](https://github.com/xef5000/materione/issues/456)) | Read extraction and `ReadsIndex` built at the shadow swap (with visibility/content roles); the `session` store behind the port (declared `scope: session`, typed and defaulted, owner Q9; `MTR-0063` for an undeclared name), removing the `GuiSessionVariables.put` bypass; the observer on `write` and `mutate` for `player`, `server`, `session`; `predicate` candidates; matching (§5.3); `ViewScheduler` (dirty bit, one-shot wakes, `minInterval`); the GUI's partial render on change under `rerender:` (schema field, default `on_change`); shipped fixtures and built-in GUIs that relied on no automatic re-render updated in the same PR; acceptance cases 1 to 3 of §11.1; `MTR-V01`; the §5.7 rows; doctor listing | V0 | M6 | L | Sonnet, high |
| **V2** ([#457](https://github.com/xef5000/materione/issues/457)) | `view.refresh` (selectors none / `view` / `var`+`holder`), replacing `gui.refresh`; `in_view` replacing `in_gui`; action self-description; `PaperViewIT`; QA `view.server-var.live`, `view.refresh.polled` | V1 | M6 | S | Haiku |
| #6 row S | `DialogOpener` and the dialog payload as the dialog kind on V0, reached through `view.open`/`view.close`; `rerender:` on dialogs via V1, V2; `MTR-V03` | V0 (V1, V2 for `on_change`) | M6 | (dialogs.md) | (dialogs.md) |
| #7 row V | `HudViewer` as the HUD kind on V0, V1 | V0, V1 | M6 | (hud.md) | (hud.md) |
| **V3** | `item` scope matching by subject stack (§5.3) | V1, #333 step 1 (or #304's P-A) | M6.5 | S | Haiku |
| **V4** | `target:` on `view.open` and `view.close` (the view side of #331) | V0, #331 | M6.5 | S | Haiku |
| **V5** | `key:` binding on opens, `ViewRegistry` by key, `view.refresh {key}`, `keyed` matching with per-render resolved keys (≤ 16, `MTR-V02`), `MTR-V04`; `session` moved behind the store port as a named key (§3.4); T02's shared window in the benchmark harness; QA `view.keyed.two-viewers` | V1, #333 step 2 | M7 | L | Sonnet, high |
| **V6** | The map canvas kind | #453's design, V0, V1 | M7 | (#453) | (#453) |
| **VQ** | Close-out: plugin.md §5 codes and §9 rows; primitives.md §4.4, §4.6, §4.7 tables; logic.md §21 and §24 cross-references; glossary; roadmap notes; codemode.md §9.2 when V5 ships | each row's own PR, final pass after V2 and V5 | M6, M7 | S | Haiku |

**Sequence for M6.** #6 row G (the `OpenGate` extraction from `GuiOpener`) lands first, because it and V0 both change
`GuiOpener` (dialogs.md §7.1, §12.1 D8); V0 then follows, or folds G into its own PR. Dialogs rows R, C, M and HUD rows R, S, C
run in parallel with both (none touches runtime sessions). #6 row S starts when V0 merges; #7 row V when V1 merges. V2 is small and lands before either kind's
QA close-out so their catalog entries can use `view.refresh`.

---

## 14. Owner questions, and the decisions this design takes

### 14.1 Owner questions and answers

**Answered by the owner on 2026-11-19: every default accepted** (option (a) for Q1 to Q7), then **qualified in the owner's review
on #335 the same day**: Q1 becomes a schema-versioned default, Q7 gains two safeguards and a caveat, Q2's wording is made
precise, and three acceptance cases are required (§11.1). The review raised Q8, which the owner made moot the same day by settling compatibility (§7): no GUI behavior is kept for its
own sake, so Q1 is a plain default and Q4's "kept" `gui.refresh` is replaced. Q9 followed and is answered. None decides a master §14
question; §6.4 records the requirement §14.2's adapters inherit without choosing one.

| # | Question | Answer (owner, 2026-11-19) | Rationale |
|---|---|---|---|
| **Q1** | Should a GUI re-render on its own when a declared variable it reads changes? | **(a): `rerender: on_change` is the GUI default everywhere; `off` is a per-file choice** (owner, 2026-11-19, after Q8). The review's schema-versioned form is withdrawn: there are no existing GUIs. | The click router follows the last rendered frame (logic.md §24 decision 14), so click targets follow state; that is kept as a stated design property (§4.2 item 6), not a migration. |
| **Q2** | The `session` scope | **(a) Keep it, per window instance**, including the back snapshot semantics (`gui.back`, now `view.back`); widened to every opened kind by the no-legacy answer (§3.4); restated as that window's named key when #333 step 2 lands (§3.4); no deprecation. (Rejected: (b) deprecate it for keyed state.) | It has real uses (a per-window mode toggle) and costs nothing once it is a named key. |
| **Q3** | How many players share one view | **(a) No shared session object**: a key binding is a label; all shared data is keyed state (§6.2). (Rejected: (b) a shared session per key.) | (b) would be a second state store with its own lifetime, TTL and Folia owner problem: a feature-shaped copy of keyed state. |
| **Q4** | The re-render verb | **(a) One generic `view.refresh`** with selectors (Rejected: (b) per-kind refresh verbs). Revised the same day under the no-legacy answer: `gui.refresh` is **replaced**, not kept as an alias, and the same reasoning makes `view.open`, `view.close` and `view.back` the verbs of every opened kind (§3.6, §7.1). | One verb per capability passes U1 and U2 across kinds; per-kind copies would be wrong in the next kind (#453). |
| **Q5** | Addon view kinds | **(a) Internal SPI plus the self-description facet now**; a public `api` SPI when #453 or an addon forces the shape. (Rejected: (b) public now.) | One shape built on three kinds is safer to publish after the fourth. |
| **Q6** | Milestones | **(a)** Rows V0 to V2 are filed as M6 issues under #335 and ride #6/#7; V3 and V4 are M6.5; #335 itself stays M7 for V5. (Rejected: (b) move #335 to M6 whole.) | M6 gets what dialogs and HUDs need; the keyed part waits for #333 step 2, as #335 says. Filed as #455 (V0), #456 (V1), #457 (V2). |
| **Q7** | Dialog re-render with typed inputs | **(a), qualified.** The opt-in stays allowed. `MTR-V03` warns for **every editable input** (`text`, `number`, `boolean`, `option`); an automatic wake whose rendered content and submission snapshot are both unchanged keeps the token and sends nothing; the 20-tick floor limits resets but does not protect an unfinished form (§4.3). (Rejected: (b) refused at load.) | A re-send loses a checkbox or option selection as surely as text; a form that must reflect a hot value is rare but legitimate. |
| **Q8** | Which mechanism versions the GUI default? | **Moot (owner, 2026-11-19):** no public release has GUIs, so there is no versioned default; `pack.yml`'s `schema_version` gets no meaning from this design (§5.5). | — |
| **Q9** | Session variables: declared or free-form? | **(a) Declared** (owner, 2026-11-19): a `variable` with `scope: session`, typed and defaulted; an undeclared `vars.session.x` or `set_var session.x` is `MTR-0063`, as for every other scope. (Rejected: (b) free-form names, as shipped.) The review's "without requiring a declaration" is superseded. | Typos fail at load (U5); types and defaults reach the studio and the SDK (U4, D5); the reads index keys on declared ids (§5.2). |

### 14.2 Decisions this design takes

| # | Decision | Where |
|---|---|---|
| D-1 | Reads are extracted at compile time; author-declared reads and render recording are rejected as the primary mechanism | §5.2 |
| D-2 | Viewer-local state is render state and navigation only; anything else is state | §3.3 |
| D-3 | Dialogs default to `rerender: off`; an explicit selection re-sends, a state change does not unless opted in | §4.3 |
| D-4 | A dialog re-render keeps the binding, re-runs no gate, issues a new token and fires no `dialog.close` | §4.3 |
| D-5 | Views converge at each viewer's next eligible render, with no one-tick visibility promise; decisions read state, never frames; exactly-once effects are #334's | §5.4, §6.3 |
| D-6 | No new registry: a view facet on the content type's self-description | §9 |
| D-7 | A common `view.*` context subtree beside each kind's root | §3.7 |
| D-8 | Close on expiry is content (a flow on #333's `expire` trigger); no new close reason | §6.2 |
| D-9 | GUIs default to `rerender: on_change` everywhere; `off` per file (owner, Q1, Q8 moot) | §4.2, §5.5, §7 |
| D-10 | `session` is per view instance, back-snapshot semantics included, for every opened kind (GUI and dialog), behind the store port, one spelling (`vars.session.*`) (owner, Q2; widened by the no-legacy answer) | §3.4 |
| D-11 | Sharing is through keyed state only; a key binding holds no data (owner, Q3) | §3.1, §6.2 |
| D-12 | One verb set for every opened kind: `view.open`, `view.close`, `view.back`, `view.refresh`; the `gui.*` view verbs and `in_gui` are replaced; component verbs (`gui.page`, `gui.slot_item`) stay (owner, Q4; widened by the no-legacy answer) | §3.6, §7.1 |
| D-13 | The view-kind SPI is internal in v1; the facet is public through self-description (owner, Q5) | §3.2, §9 |
| D-14 | Rows V0 to V2 are M6, V3 and V4 M6.5, V5 M7 with #335 (owner, Q6) | §13 |
| D-15 | `rerender: on_change` is allowed on any dialog, with `MTR-V03` for any editable input; an unchanged automatic wake keeps the token and sends nothing (owner, Q7 as qualified) | §4.3, §10 |
| D-16 | Every write path notifies (`write`, `mutate`, the session facet); `predicate` kinds keep a candidate record per online player so visibility writes can attach; one-per-tick limits automatic renders only (owner review, acceptance cases 1 to 3) | §5.3, §5.4, §11.1 |
| D-17 | `view.*` is the one root for view facts; `gui.*` and `dialog.*` roots are replaced; event roots stay | §3.7 |
| D-18 | One generic `ViewerRecord` with kind payloads replaces `GuiSession`; a per-player view history spans opened kinds; `%materione_view_open%`/`_last%` replace the GUI tokens | §3.8 |
| D-19 | Session variables are declared (`scope: session`, typed, defaulted); undeclared is `MTR-0063`; declarations reach the self-description, the SDK and a declare action in the GUI and dialog studios (owner, Q9) | §3.4, §9, §11.1 |

D-1 to D-8 were taken by this design and accepted with it on 2026-11-19; D-9 to D-15 are the owner's answers. All remain
reversible by the owner.

---

## 15. What this PR changes elsewhere

- [`README.md`](README.md): the index row for this document.
- [`primitives.md`](primitives.md) §5.3: a pointer to this design.
- [`../glossary.md`](../glossary.md): the **View kind** entry.
