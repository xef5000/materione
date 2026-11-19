# The backpack as a preset over primitives (#304) — design

> **Scope.** How the shipped native `backpack` mechanic becomes **content**: a preset ("this item is a backpack") declared by
> reference on an item and resolved by the one pipeline into a click rule plus a built-in GUI whose input slots are held in
> custody **by the item stack**. This document says which primitives that composition needs, which exist and which are missing
> (§2), the content it resolves to (§3), the preset's declaration and resolution (§4), the lossless migration of stacks that
> already hold contents (§5), durability and the backpack's specific hazards (§6), the editor (§7), code mode (§8), diagnostics
> (§9), tests (§10) and implementation rows (§11). It ends with the owner's answers (§12).
>
> **Status: design, not built.** Nothing here changes a shipped file's meaning. The native mechanic
> (`plugin/src/main/java/dev/materione/plugin/runtime/mechanics/builtin/BackpackMechanic.java`) keeps working until row F (§11)
> retires it, and it is retired only when the composition passes the round-trip tests of §10 against stacks the native mechanic
> wrote. **The owner answered every question on PR #450 (2026-11-19, §12):** presets are content, a `preset` content type that the
> built-in pack and content makers' own packs both ship (§4.4), and every other recommendation is accepted. Row P waits on the custody
> holder design of #334.
>
> **Traceability.**
>
> | Decision or rule | How this doc applies it |
> |---|---|
> | **D12** (master §3.1) | The whole document. The engine gains two primitives (§2.2), each passing U1 to U5; the backpack itself is content (§3) and a preset (§4) that obeys R1 to R7, the by-hand rule (R4) above all. |
> | **D1** | The author's file holds the declaration only (§4.1). Stack contents are runtime state in the stack, not content; nothing becomes a second source of truth (§5.3). |
> | **D2, D6** | The preset is resolved by a pipeline stage with graph edges item → preset → GUI (§4.3); no "apply preset" path writes files. |
> | **D4** | The behavior is one `click:right` rule and one `gui.open` step on the one ECA engine; the native listeners go away (§3.1). |
> | **D5** | Both new primitives and the preset self-describe through registries, so the editor (§7) and the SDK (§8) render them with no hand-written surface. |
> | **D8** | Every stack write is an owner-thread memory step; no IO on a region thread (§6.3). |
> | **D13** | The agent declares the preset or writes the composition; both are staged operations (§8). |
> | master §5.5, §5.6 | `mechanics: [backpack: {rows, title}]` loads with its meaning and round-trips byte-identically; stack PDC bytes are untouched by an open that changes nothing (§5). |
> | master §7.3 | The backpack GUIs are resources in the read-only built-in pack `materione`; no code names them (§3.2, §4.3). |
> | primitives.md §4.2, §5.1, §7 | The backpack is the audit's first preset; its custody is the **stack holder** of §5.1 step (2). |
> | gui-input-durability.md | Phase B (§6.2) is that design's tier 1 with the stack as the holder; no shipped guarantee is weakened and `EscrowBarrierBansTest` still binds. |

---

## 1. What exists, read from code

`BackpackMechanic` (class F, primitives.md §4.2) does four things with native listeners:

| Step | Today | Problem under D12 |
|---|---|---|
| Open | `PlayerInteractEvent`, main hand, right-click air or block: cancels the event, lazily writes a random UUID to `materione:backpack-id` (STRING) on the held stack, opens `Bukkit.createInventory(holder, rows*9, MaterioneText.parse(title))` | A plain Bukkit chest: the GUI system, its art, its rules and its policy table are not used at all. |
| Load | `ItemListCodec.decode(materione:backpack-data)` (BYTE_ARRAY, length-framed `serializeAsBytes` frames, air kept as a zero frame) into the first `rows*9` slots | Stored stacks past `rows*9` are not loaded. |
| Save | `InventoryCloseEvent` at MONITOR: finds the stack by `backpack-id` among storage contents and off hand, writes `ItemListCodec.encode(getContents())` | Written **only on close**, and only `rows*9` entries. |
| Lifecycle | none while open | The stack is free to move while its window is open. |

Read from that code, the shipped mechanic has five defects. They are not regressions to preserve; the composition fixes each
and the tests of §10 pin the fix:

| # | Defect | Effect |
|---|---|---|
| **B1** | Shrinking `rows` on reload (or an item edit) truncates: entries past the new `rows*9` are neither loaded nor re-encoded, so the next close **deletes** them. | Loss. |
| **B2** | The stack may be moved while open: dropped (Q over its slot in the lower inventory), put into the backpack's own window, put into a bundle, swapped to a slot `findStack` does not scan (an armor slot). The close then finds no stack and writes nothing. | **Dupe**: the stacks the player took out stay in the stored data too. The self-insertion case also deletes the backpack item with the window. |
| **B3** | Contents are persisted only at close. A server crash after an autosave while the window is open restores a player inventory holding the stacks taken out *and* a stack PDC that still holds them. | Dupe on crash (and loss for stacks put in). This is the exposure the issue calls `best_effort`. |
| **B4** | Two stacks with the same `backpack-id` (a creative middle-click clone, another plugin's copy) are indistinguishable to `findStack`: the close writes into the first match, which may be the other stack. | Contents written into the wrong stack. |
| **B5** | No size bound: a backpack may hold backpacks to any depth, each nesting the inner stack's whole PDC. | Unbounded item NBT (player data, chunk data when dropped, packets). |

---

## 2. The primitives the backpack needs

### 2.1 The decomposition

| # | Need | Primitive | Today | Class |
|---|---|---|---|---|
| 1 | Right-click with the item opens storage, and the vanilla use (placing a `CHEST` material) does not happen | Item `click` trigger with the `right` detail (`on: click:right`, logic.md §14 decision 3) and the `cancel` action | Shipped | P |
| 2 | A chest window of 1 to 6 rows with a per-item title | A `gui` resource with `container: chest_N` (gui.md §12.4) and a text layer templated on an open argument (`${gui.args.title}`, logic.md §6.4 dynamic title) | Shipped | P |
| 3 | The item passes its parameters to the window | `gui.open` with `args`, typed `open.args` (logic.md §6.1) | Shipped | P |
| 4 | Player items may be put in and taken out of the window | `slots` layer with `input: true` (logic.md §6.5, §25) | Shipped | P+ (primitives.md §4.7) |
| 5 | **The window knows which stack it was opened about**, and keeps knowing it while the stack sits in an inventory slot | **The run's item carried into a view** | **Missing** | **G** |
| 6 | **The slots load from, and are held by, that stack**, not returned to the player on close | **Custody with a stack holder** | **Missing** (custody has one holder, the player's account) | **G** |
| 7 | Crash safety for transfers in and out | Custody tier 1 (gui-input-durability.md §5.4) with the stack as the holder | Mechanism shipped for the account holder; Phase B of §6.2 | P (after 6) |
| 8 | Refuse nesting a backpack in a backpack by default (Q4: deny, `allow_nested` opts in) | An `accept:` condition that can tell a stack holds custody | **Missing** as an expression property | G (small) |
| 9 | "This item is a backpack" as one setting | The **preset** layer: a declaration resolved by the pipeline | **Missing**; representation partly open (master §14.7) | — (D12 Layer 2, not a primitive) |

Needs 1 to 4 compose today. Needs 5 and 6 are the two primitives this design depends on; need 8 is optional; need 9 is the
preset mechanism, which every later preset reuses.

### 2.2 The two missing primitives

**P-A: the run's item carried into a view (need 5).** A run started by an item trigger already has "its item" (the stack the
`click` fired for). #333 step 1 needs the same concept to read `vars.item.*` and states that "the GUI can read the stack a click or
an `open` was about". P-A is that concept for views, and it is **one** concept shared with #333: whichever issue lands first builds
it and the other reuses it.

| | Statement |
|---|---|
| What it is | `gui.open` run from a flow that has an item records that stack as the session's **subject stack**: a reference made of the owner player, the inventory slot index and the stack's holder id (§5.1). |
| How content reads it | `gui.item.*` in expressions (the same item view `slot.item.*` exposes), and as the default target of P-B. A GUI opened with no subject (a command, `/materione gui open`) reads `gui.item` as null and a layer that needs it renders inert with a diagnostic (§9, D-1). |
| Liveness | The reference is checked at each use: same player, same slot, same holder id. A mismatch ends the session (§6.4 H2). |
| U-tests | U1 names a capability (a view's subject), not a backpack. U2 any item trigger, any GUI. U3 content passes it with `gui.open`, no Java. U4 part of the `gui.open` action's and the GUI context's self-description. U5 validated offline: a GUI that reads `gui.item` and is opened by a command is a load-time warning. |

**P-B: custody with a stack holder (need 6).** primitives.md §5.1 step (2), and the second holder #334 schedules ("then stack
holder (#304)"). The `slots` layer names its holder, defaulting to today's behavior exactly (#334's sketch):

```yaml
- id: storage
  type: slots
  region: container
  input: true
  holder: item          # absent: the player's account, exactly as today
  durable: best_effort  # Phase A; required in Phase B (§6.2)
```

| | Statement |
|---|---|
| At rest | A holder decides where its items live when no window is open. The **account** holder's items rest in the player's inventory (returned on close, owed if full). The **stack** holder's items rest **in the stack**: nothing is returned on close. |
| Address | The subject stack of P-A. A `holder: item` layer in a GUI opened with no subject accepts nothing (D-1). |
| Store | Per stack, one store: the slot list in the stack's PDC (§5.1 for the keys and format). One custody region per stack in v1; a second region on the same stack is additive later (a region key). |
| Capacity | The store's length, not the view's. A view smaller than the store shows the first slots and **keeps** the rest (fixes B1, §6.4 H6). |
| Pinning | While a view of a stack holder is open, the holder stack cannot move (§6.4 H1). This is a property of the holder, not of a backpack. |
| Size bound | The encoded store is capped (256 KiB, the account's cap, gui-input-durability.md §5.4; Q5, measured in row B). A transfer that would exceed it is refused with a diagnostic. Because an inner holder's bytes are inside the outer one's, the cap bounds nesting at any depth (fixes B5). |
| Exits | `custody.consume` on a stack-held slot is **refused** in v1 ("not supported for this holder"): the backpack needs no exit, and an exit from a stack that can leave the player's snapshot is #334's open ledger question. |
| U-tests | U1 a holder kind (item), not a backpack. U2 any item, any GUI, any slots layer. U3 a key on an existing layer. U4 `holder` joins the `slots` component's schema with its allowed values. U5 load-time checks (§9); Phase B carries the kill-boundary proof (§6.2). |

**Rejected alternatives.**

| Alternative | Why not |
|---|---|
| "Persistent input slots" (the issue's first scope): a storage source on `input: true` slots that loads from the held backpack's PDC | Feature-shaped: named for one case and hard-wired to "the held backpack" (U1, U2). The owner re-scoped #304 away from it. |
| A templated `container:` so one GUI serves every row count | The container decides the compiled art and the title geometry (gui.md §12.5); a per-open container is a new compile-time dimension for one caller. Six static GUIs are content and cost nothing (§3.2). |
| Keeping the native mechanic and pointing it at a GUI | The engine would keep a backpack behavior, which master §3.1 rules out ("A backpack is therefore not a defined engine behavior"). |
| Items held on the player's account with the stack only as a label | The items would rest in the player's data, not travel with the stack: dropping or trading the backpack would no longer move its contents. |

---

## 3. What the backpack is, as content

### 3.1 The rule on the item

What the preset resolves to on an item (this is also exactly what an author writes **by hand** under R4, with their own GUI ids):

```yaml
behavior:
  - on: click:right
    do:
      - cancel: {}
      - gui.open:
          id: "materione:backpack_${preset.rows}"   # §4.2, check for row D: a templated id, or six `if` branches
          args: { title: "${preset.title}", allow_nested: "${preset.allow_nested}" }
```

`gui.open` from this rule carries the clicked stack as the session's subject (P-A).

### 3.2 The built-in GUIs

Six resources in the built-in pack, `builtin-packs/materione/guis/backpack.yml`: `backpack_1` to `backpack_6`, identical except
`container: chest_N`. Each is a GUI without art (gui.md §9.15, the vanilla chest look, as today):

```yaml
backpack_3:
  title: Backpack                    # accessibility fallback
  container: chest_3
  open:
    args:
      - { name: title, type: string, default: "Backpack" }
      - { name: allow_nested, type: boolean, default: false }
  layers:
    - id: title
      type: text
      x: 8
      y: 6
      text: "${gui.args.title}"
      font: default
      color: "#404040"
    - id: storage
      type: slots
      region: container
      input: true
      holder: item
      accept: "${!slot.item.holds_custody || gui.args.allow_nested}"   # Q4: nesting denied by default (need 8)
```

No code names these resources (master §7.3 "No special case"): the preset definition names them, which is content naming content.
They are not openable by a command (`open.commands` absent), and opening one with `/materione gui open` gives a window with no subject,
whose storage accepts nothing (D-1).

**Title fidelity.** Today's title is a vanilla inventory title through `MaterioneText.parse` (legacy `&` codes and the stored form of
master §5.7, plugin.md §6.1). The GUI title is compiled text. Whether formatting inside a templated argument renders is recorded as
unverified (logic.md §34, the confirm dialog's `${gui.args.question}`). Row R (§11) checks it on the client; if it does not render, the
text layer path gains the §5.7 stored form for templated arguments, which is a text-pipeline fix every GUI benefits from, not a backpack
special case.

---

## 4. The preset: declaration and resolution

### 4.1 What is decided, and what is open

Decided (master §3.1 R1 to R7, §14.7): the author's file declares the preset and its parameters; the definition lives in a pack (here
the built-in pack) and is loaded by the pipeline; nothing is copied into the author's files. **Decided by the owner on PR #450
(2026-11-19):** (ii) definitions are **content**, in a `preset` content type that content makers may author in their own packs (§4.4);
(i) for items, the spelling stays the mechanic-entry shape (S1 below). **Still open:** (iii) pinning and versioning, (iv) a read-only
expanded view; the backpack ships without either. Logic recipes (logic.md §4.6, §30) are the only other parameterised composition, and
they are the model the owner did **not** choose for presets: a recipe expands into the file and "never survives into the file as a recipe".

### 4.2 The options, and the owner's answers

**(i) Spelling. Answered: S1 for items (Q1).**

| Option | The author's file | Migration |
|---|---|---|
| **S1, keep the mechanic-entry shape** (chosen for items) | `mechanics: [- backpack: {rows: 2, title: "&6Satchel"}]`, unchanged | None: every existing file already is the declaration (`fixtures/packs/world/items/satchel.yml`). |
| S2, a generic key | `preset: {id: materione:backpack, rows: 2, title: "&6Satchel"}` | `mechanics: [backpack]` must load forever as an alias (master §5.5); the editor writes S2 only for new declarations, and an untouched file keeps S1 (§5.6). Two spellings of one thing. |

S1 holds for items because the `mechanics:` list is already "named bundles with parameters" (master §8.1) and the editor's module rows are
drawn from it (web.md §6). A generic key is still needed for hosts that have no `mechanics:` list (a block, a GUI); that is additive, not
the backpack's, and its spelling is decided when the first such preset is designed.

**(ii) Where definitions live. Answered: L1, a content type, user-extensible (Q2).**

| Option | Shape | Notes |
|---|---|---|
| **L1, a `preset` content type** (chosen) | `builtin-packs/materione/presets/backpack.yml`: `params:` (typed, the recipe parameter schema of logic.md §30), `applies_to: item`, and the host fields it contributes (`behavior:`, `flows:`), whose expressions read `preset.<param>` | Data, so the built-in pack, a server's own packs and an addon's pack ship presets the same way (R7). Self-description is generated from `params:` (R6). |
| L2, a Java registry entry like the logic `Recipe` SPI | A registered `Preset` that returns the host fields | Rejected: a preset would be an addon-only surface, against R1 ("never Java behavior"). |

A declaration's parameters reach the definition as a typed, read-only `preset.*` context in its expressions, not by text substitution
(the recipes' `{{param}}`): substitution would be a second templating language and an injection seam for a `title` that contains
`${...}`. **Check for row D:** whether `gui.open`'s `id` accepts a template. If not, the definition uses six `if` branches on
`preset.rows`, which is uglier and equally honest; the parameter schema bounds `rows` to 1..6 either way.

### 4.3 Resolution

1. **Parse.** The item's `mechanics:` entry `backpack` is looked up in the preset index the pipeline builds from every loaded `preset`
   resource. While the native mechanic is registered (until row F), the native mechanic wins and the preset is not consulted; after row F
   the name resolves to the preset. A name that is both a registered mechanic and a preset after row F is a load error on the preset (D-4),
   never a silent shadow.
2. **Validate.** The parameters against the preset's schema (`rows` integer 1..6, required; `title` text, default `"Backpack"`; `allow_nested` boolean, default false; from row P, `durable`), with the
   same messages the native `parse` gave, so an existing author sees no new wording for an existing mistake.
3. **Graph.** Edges item → preset definition → each resource it names (`materione:backpack_1..6`), so impact analysis and "where is this
   used" see through the declaration (R3, D2).
4. **Compile.** The definition's host fields are compiled into the item's **runtime** behavior with `preset.*` bound. The author's own
   `behavior:` rules on the same item run as well, in the documented order (author rules first, then preset rules), and either may
   `cancel`. Nothing is written to the author's file.
5. **Apply.** As every item, by the shadow-swap (runtime-systems §1).

**Customize (R5).** The route from declaring to defining is the existing Customize pattern (gui.md *Decisions taken in #203*): copy the
definition's rule into the item's `behavior:` and the chosen GUI into the author's pack, rewrite the ids, remove the declaration, in one
revision. The owner answered Q6 "later": v1 does not ship it. Stack contents survive Customize unchanged, because the holder keys of §5.1 do
not depend on which GUI opens the stack.

### 4.4 Presets authored by content makers (owner, PR #450)

The owner's answer to Q2: the built-in pack ships the `backpack` preset, and **content makers may create their own presets** to
streamline the creation of content (a server that makes forty themed backpacks, or forty quest-giver items, writes the composition once
and declares it forty times). A user preset is the same content type as the built-in one; nothing distinguishes them but the pack they
live in. What follows:

| Property | Statement |
|---|---|
| **Files (D1)** | A preset is a resource in `presets/` of a pack (master §7.2 layout), one resource per key like every type. The editor holds nothing a preset file does not say. |
| **Self-description and round-trip (D5, master §5.6)** | The `preset` type registers through the public content-type registry with its schema, like every built-in type. A preset file loads and saves losslessly: unknown fields kept, an untouched save byte-identical. A preset's own `params:` generate the schema of its declarations, so the editor's settings panel (§7) and the SDK member (§8) need no per-preset code (R6). |
| **One pipeline (D6)** | Preset resources are parsed, validated and graphed before the resources that declare them, in the same reload; a declaration is resolved by the stage of §4.3 and never by the editor. Editing a preset re-resolves every declaration of it on the next reload, as editing a template re-resolves the items linked to it (plugin.md §3.1). A preset that fails to validate is quarantined, and so is every declaration of it (D-4); the declaring resources otherwise load. |
| **Names and references** | A preset's identity is `<namespace>:<id>` like every resource (master §5.1): one namespace per pack, ids unique per type within it, `materione` reserved for built-in packs (master §7.3), so a user preset can never replace or shadow `materione:backpack`. A declaration names a preset the way a resource names any other resource, including across packs under the existing rules for cross-pack references. A preset id that collides with a registered mechanic name is D-4. |
| **The by-hand rule (R4)** | A user preset contributes only the host fields a content maker could write on the host by hand (`behavior:`, `flows:`, and fields of the host's own schema), with only registered triggers, conditions and actions, and it names only resources that exist. The preset type has no field that reaches the engine any other way, so a preset can never be a capability. Validation rejects a contributed field the host's schema does not have. |
| **Where it applies** | `applies_to:` names one content type. v1 resolves presets for `item` only (through S1, under `mechanics:`); other hosts follow with the generic spelling of §4.2 (i). |
| **Presets declaring presets** | Not in v1: a preset's contributed fields may not declare a preset. It is additive later, with a cycle check in the graph. |
| **Pinning and versioning** | Still open (master §14.7 (iii)). It matters less for a user preset (the author edits both sides on one server) than for a built-in preset across plugin updates. |
| **The editor** | Authoring a preset is §7.1. |
| **The agent (D13)** | A preset is content, so the agent creates one with the ordinary staged operations (a `presets` builder generated from the type's schema), and declares it with the member generated from that preset's `params:` (§8). |

---

## 5. Migration: identity and stored contents

### 5.1 The stack holder's keys (Phase A)

**Phase A reads and writes exactly the shipped keys and format.** The stack holder's identity is `materione:backpack-id` (STRING, a
UUID, created lazily on first open as today) and its store is `materione:backpack-data` (BYTE_ARRAY, `ItemListCodec`). The persisted
key names are feature-shaped, and that is accepted on the precedent of the escrow account (primitives.md §5.1 step (3): rename the
persisted key, with a migration, only after the mechanism is proven): a key name is not surface an author or an agent sees (U1 applies
to names in content and the SDK).

| Property | How Phase A keeps it |
|---|---|
| Existing contents load | The same decoder, positions kept (air frames stay air). |
| Untouched round-trip | An open and close that changed nothing writes nothing: the stack's PDC bytes are identical (golden test, §10). |
| Capacity | The stored list's length is kept: a write re-encodes `max(stored length, view size)` entries, so a smaller view never truncates (fixes B1). |
| Downgrade | A server rolled back to a build with the native mechanic reads the same keys and sees the same contents. |
| Stacks with no id | Get one on first open, exactly as today. |

### 5.2 Phase B's format change

Crash-safe transfers (§6.2) need a versioned store with a generation counter so a snapshot can be told apart from an older one, as the
account has (gui-input-durability.md §5.3). That is a format change, made once, by the #334 design that owns the holder formats:

- On the first strict touch of a stack that has `backpack-data` and no new store, the stack is **adopted** in one tier-1 step: decode,
  write the new store, remove `backpack-data`, in one `editMeta`, so no snapshot can hold both or neither. `backpack-id` becomes the new
  store's holder id.
- A stack with both keys (only possible after a downgrade and re-upgrade) is merged: the new store wins its slots, legacy entries go to
  free slots, and what does not fit is returned through the holder's overflow rule. Nothing is dropped silently (D-3).
- **Downgrade across the Phase B boundary** (Q3, answered: adopt and document): an older build sees an adopted stack as empty, and the
  release notes of the build that ships row P say so. There is no dual-write of `backpack-data`, because two stores of one truth diverge.

### 5.3 D1

Stack contents are runtime player state, like the account (gui-input-durability.md §9): they live in the world's and players' data and
are not content. The author's file says "this item is a backpack with 2 rows"; it never lists contents. No editor or SDK path writes
stack contents.

### 5.4 Files

No file changes: S1 (Q1) keeps today's spelling as the declaration, and the editor does not rewrite an untouched declaration. The fixture
`fixtures/packs/world/items/satchel.yml` is the regression input.

---

## 6. Durability and hazards

### 6.1 Phase A: `best_effort`, with the shipped exposure narrowed

Phase A ships the composition with `durable: best_effort` on the stack holder layer: the server applies clicks (as for every
`best_effort` input layer, logic.md §25), and the holder **mirrors the slot list into the stack after each change**, coalesced to one
write per tick per window, plus a final write at close. Compared with today (B3): a crash loses or duplicates at most the changes of the
last tick before the crash, where today it is everything since the window opened. This is still not crash-safe, and nothing in the product
may call it so (the gate of gui-input-durability.md's status note applies).

The mirror write is an in-memory `editMeta` on the live stack in its inventory slot, on the owner thread; the stack is then part of the
player's next save by the platform's own mechanism. No IO, no scheduler hop for IO (D8). Cost: one `ItemListCodec.encode` of at most 54
stacks per changed tick, bounded by the plugin.md §9 GUI click budget; row A measures it.

### 6.2 Phase B: crash-safe, as tier 1

The backpack needs **no exit**: every transfer is between the player's inventory and a stack that is itself in the player's inventory. Both
sides are in **one player snapshot** (gui-input-durability.md F3), so a transfer applied by the plugin in one owner-thread step, with the
stack's store re-encoded in the same step (I1, I2 with the stack holder in place of the account), is atomic against every save **by
construction**. No ledger record is needed for it, because nothing leaves the snapshot. Phase B is therefore:

- `durable: required` on a `holder: item` layer takes the strict path of row P (#310): every click that touches a holder slot is applied
  manually, the store re-encoded in the step, the account untouched (the stack holder is the other side, not the account).
- The **pin** (§6.4 H1) becomes part of the guarantee: the stack must not leave the player's snapshot while the view is open. A path that
  moves it anyway (another plugin, §6.4 H8) ends the session before the next transfer.
- Checkpoint support (gui-input-durability.md §5.8) is required as for any required layer; without it the layer is inert with `MTR-0100`.
- The proof: new kill-boundary rows in the K-table's style for the stack holder (a kill between a click and the next save, with the
  stack in the main hand, the off hand and a storage slot), in `PaperEscrowCrashIT`'s harness. Nothing is called crash-safe before they are
  green on main.

What Phase B does **not** cover, stated so no sentence widens it: a backpack at rest outside a player (in a chest, an item frame, dropped)
is in a chunk snapshot, and its contents are as safe as the chunk; exits from a stack holder (`custody.consume`), transfers between a stack
and another holder (`HAND_OFF`), and two holders' joint decision are #334's.

Once Phase B is green, the built-in backpack keeps `best_effort` as its default and gains a `durable` parameter that opts into `required`
(Q7, answered): a backpack that is inert on a server with saving disabled is a worse first experience than the narrowed exposure of §6.1.

### 6.3 Threading

| Work | Thread | IO |
|---|---|---|
| Open, load, every transfer, the mirror and the Phase B in-step encode | owner (main on Paper; the player's region on Folia) | none |
| The player's save that persists the stack | the platform's own, as for every inventory change | the platform's |

### 6.4 Hazards specific to a backpack

| # | Hazard | Rule |
|---|---|---|
| **H1** | The backpack is moved while its window is open: dropped (Q, Ctrl-Q over its slot), clicked into its own window, picked into a bundle, number-key or off-hand swapped, shift-clicked, dragged over | **The pin.** For the life of the session the holder stack's slot is locked: every click, drag, hotbar swap or bundle interaction whose source or target is that slot is cancelled. The stack stays where it was, so the slot index in the subject reference stays valid. Fixes B2. |
| **H2** | The stack changes under the session anyway (another plugin, `/clear`, an admin `/invsee`) | Every use of the subject checks player, slot and holder id. A mismatch closes the session, makes the slots accept nothing in the meantime, and writes nothing (Phase A: nothing was outstanding beyond the last tick's mirror). Diagnostic D-2. |
| **H3** | Nested backpacks | Bounded by the size cap at any depth (P-B). Insertion of a holder stack into a holder is refused by default, and the preset's `allow_nested: true` opts in (Q4, answered); existing nested stacks can always be taken out, because `accept:` gates only insertion. |
| **H4** | The backpack put into itself | Impossible under the pin (H1): its slot is locked. |
| **H5** | Two viewers | One open view per holder stack in v1: a second open of the same stack (same player and slot) replaces the first, which closes and writes. Two players cannot reach one stack in vanilla play. Multi-viewer views are #335. |
| **H6** | `rows` shrinks on reload while open, or between opens | The store keeps its length (P-B *Capacity*). Stacks past the new view stay stored and are listed by `gui.item` for content that wants to show "N hidden"; increasing `rows` again shows them. Fixes B1. The hidden tail is not returned to the player (Q8, answered): nothing leaves custody without the player's act. |
| **H7** | Cloned identity (creative middle-click, B4) | The subject reference uses the slot as well as the id, so writes go to the stack that was opened. Contents duplicate with the clone, as a shulker box's do in creative; this is vanilla behavior and not custody's to prevent. |
| **H8** | Death, quit, kick, stop, plugin disable while open | `InventoryCloseEvent` fires before the save (gui-input-durability.md F4, F7): the session closes and the final write runs. On death the stack drops or is kept with its contents inside, as any item. |
| **H9** | The item definition stops being a backpack (declaration removed, item deleted) while stacks exist | Contents stay in the stacks untouched (the holder keys are not removed). The stack cannot open until a declaration or a by-hand rule names it again. Diagnostic D-5 is a doctor-level info, not a deletion. |
| **H10** | The window is open during a reload that retires the native mechanic (row F) | Row F lands with a restart, not a hot reload; a hot reload with an open native window closes it through the native close path first. |

---

## 7. The editor: the quick setting (web.md §6)

The item studio is a display card plus modules (web.md §6, #152, #174). Under S1 the preset is a **module row** like a mechanic today,
labelled "Backpack" with its description, and its settings panel is derived from the preset's parameter schema (R6): `rows` as a 1..6
stepper, `title` as a rich-text field (the §5.7 stored form, web.md §6.2), `allow_nested` as a toggle. Nothing per preset is hand-written;
a content maker's own preset (§4.4) appears the same way.

| Element | Statement |
|---|---|
| Picker | The "Add module" picker lists presets that apply to the type beside mechanics, with a *Preset* chip so an author can filter, and the pack each preset comes from. Under S1 the entry writes the same `mechanics:` entry the native mechanic wrote. |
| Start workspace | A feature starter "A backpack" (master §9.1, primitives.md §7) creates an item with the declaration and opens it. |
| What it shows | A read-only line "Opens the built-in backpack window (`materione:backpack_N`)" with a link that opens the GUI read-only (built-in resources are never editable, master §7.3). For a content maker's preset, the line links to the preset file, which opens in its own studio (§7.1). |
| Customize | Not in v1 (Q6, answered "later"). |
| Durability | Under Phase A the settings panel says "Contents are saved as the player uses the backpack; a server crash can lose the last moment of changes." From row P the `durable` parameter's control carries the required-layer wording (Q7). |
| Honest coverage | Until row F, the module is the native mechanic's and the panel says nothing different; the switch is invisible to the author by design. |

### 7.1 Authoring a preset

`preset` is a content type, so it gets the editor's ordinary surfaces with no preset-specific framework: it is listed in Files and in the
Start workspace's type starters (master §9.1), and a preset resource opens in a **preset studio** built from the generic pieces that exist:

| Part | Built from |
|---|---|
| Parameters | A list editor over `params:` (name, type, default, range, description), the same schema shape as the logic recipes' parameters (logic.md §30), with a live preview of the settings panel a declaring item will show. |
| Applies to | A single choice of content type; v1 offers `item` only (§4.4). |
| Contributed behavior | The existing rules editor and Logic tab (logic.md §7) over the preset's `behavior:` and `flows:`, with `preset.*` offered in the expression completions and typed from `params:`. |
| Validation | The pipeline's diagnostics on the preset and on every declaration of it (D-4, D-6), shown in Problems like any resource's. |
| Used by | The graph's reverse edges (R3): the items that declare this preset, as for templates' "Used by N" (web.md §6). |
| Built-in presets | Open read-only, with the same studio, so a content maker can read `materione:backpack` before writing their own (master §7.3). |

---

## 8. Code mode (D13)

| Surface | In the SDK (codemode.md §3.5) |
|---|---|
| The declaration | Unchanged under S1 (Q1): `m.mechanics.backpack({ rows: 3, title: "Traveler's Pack" })` (agent-benchmark.md T03's program passes as is). A content maker's preset is declared the same way, by a member generated from its `params:`. |
| A content maker's preset | `preset` is a content type, so the generated SDK has its builder like any type's (codemode.md §3.5: a self-describing registry entry appears with no SDK code); an agent creates a preset in the author's pack with the ordinary staged operations, then declares it. Creating one is new content and applies without approval; editing an existing preset asks once per object per session, and the review shows every declaring item as affected (R3). |
| The by-hand route | With P-A and P-B shipped, the `holder: "item"` value appears on the generated `slots` layer builder and `gui.item` in the context types; T03's by-hand program (a `click:right` rule, `gui.open`, a GUI with a `holder: "item"` input layer) type-checks. Today it fails with the `[CUSTODY]` error of agent-benchmark.md T03. |
| The built-in GUIs | Readable and referenceable as `m.ref.gui("materione:backpack_3")`; never writable (master §7.3). |
| The expansion | `m.read.expand(ref)` only once master §14.7 (iv) is answered; until then absent. |
| Approval | Declaring a preset on a new item applies without approval; adding it to an existing item is a change to existing content and asks once per object per session (codemode.md §5.6). |

Agent-benchmark T03 moves to "Pass (preset route); Pass (by-hand route)" when rows A and B land; the benchmark row is updated by row B.

---

## 9. Diagnostics (proposed; codes assigned at implementation)

Other designs are proposing codes in parallel, so none is claimed here. Each implementing row takes the next free `MTR-####` and records it
in plugin.md §5.

| Ref | Severity | When |
|---|---|---|
| **D-1** | warning (load) / info (runtime) | A `holder: item` layer, or an expression reading `gui.item`, in a GUI that can be opened with no subject (it has `open.commands`, or a `gui.open` of it runs from a flow with no item). At runtime the layer accepts nothing and the window says why on the actionbar. |
| **D-2** | warning, rate-limited per player | A stack holder session ended because its subject stack changed under it (§6.4 H2), naming the player, the slot and what changed. |
| **D-3** | warning | A stack holder transfer was refused: the store would exceed its size cap, or the store could not be encoded. The click did nothing (the shape of `MTR-0102` for the account; may reuse it if the owner prefers one code per refusal kind). |
| **D-4** | error (load) | A preset name collides with a registered mechanic, or a declaration names a preset that does not exist or does not apply to this content type. The declaration is quarantined; the rest of the item loads. |
| **D-5** | info (doctor) | Stacks with a holder store exist for an item that no longer declares one (§6.4 H9). Never deletes. |
| **D-6** | error (load) | A preset resource is invalid (§4.4): a parameter with a bad type or default, an `applies_to:` that names no content type or one v1 does not resolve, a contributed field the host's schema does not have (R4), a contributed field that declares a preset, or a reference to a resource that does not exist. The preset is quarantined, and every declaration of it reports D-4 naming this preset. |

`MTR-0027` (unknown mechanic, skipped) keeps its meaning for names that are neither a mechanic nor a preset.

---

## 10. Test plan

| Tier | Test | Pins |
|---|---|---|
| Unit | Stack holder store: decode/encode of `ItemListCodec` lists with trailing air, a view smaller and larger than the store, cap refusal at the boundary | B1, B5, P-B capacity |
| Unit | Subject reference liveness: same slot different id, same id different slot, player gone | P-A, H2, H7 |
| Unit | Pin policy table: every click kind, drag, hotbar, off-hand and bundle path against the holder slot | H1, H4 |
| Unit | Preset resolution: S1 declaration resolves; schema errors keep the native wording; collision is D-4; graph edges present | §4.3 |
| Unit | The `preset` type: lossless round-trip (unknown fields kept, untouched save byte-identical); D-6 for each invalid shape; a contributed field absent from the host's schema rejected (R4); a user preset cannot take the `materione` namespace; cross-pack declaration resolves only under the reference rules; editing a preset re-resolves its declarations on reload | §4.4 |
| Golden | The `preset` type's self-description and generated SDK builder; a content maker's preset from `fixtures/packs/presets/` resolved onto two items | §4.4, §8 |
| Golden | **Legacy stack round-trip:** PDC bytes written by today's `BackpackMechanic` (captured fixture, before row F deletes the class) open and close through the composition with no change and come out byte-identical | §5.1, master §5.6 |
| Golden | The resolved runtime of `satchel.yml` (the compiled rule and GUI reference) | §3, §4 |
| Golden | The built-in `backpack_1..6` compiled screens | §3.2 |
| Integration (1.21.4 harness) | Open, put, take, close, re-open; a stack written by the native mechanic shows its contents; shrink and regrow `rows` across reloads keeps every stack; drop key and self-insertion refused while open; quit while open persists | B1 to B4, H1, H6, H8 |
| Integration (Phase B) | Kill-boundary rows for the stack holder in `PaperEscrowCrashIT`: a kill after a click and before the next save, the stack in main hand, off hand and storage | §6.2 |
| Codegen | The generated `.d.ts` golden gains `holder` and `gui.item`; T03's by-hand program type-checks (`fixtures/codemode/corpus/t03-backpack.*`) | §8 |
| Editor | The `?modules` fixture adds, configures and removes the backpack preset module; the settings panel is schema-derived. A `?preset-studio` fixture creates a preset, edits its parameters and rules, sees Used by, and opens `materione:backpack` read-only | §7, §7.1 |
| QA catalog | `backpack.opens-builtin-gui` (visual: the window, its title, its rows), `backpack.legacy-contents` (auto: a pre-migration stack's contents), `backpack.pinned-while-open` (auto), `backpack.shrink-keeps-items` (auto), `backpack.nesting-refused` (auto), `backpack.title-formatting` (visual), `presets.user-preset-applies` (auto: a content maker's preset declared on two items behaves on both) | player-visible behavior (`qa/README.md`) |

---

## 11. Implementation rows

Each row is one issue and one PR, sized for one agent. Rows are ordered by dependency.

| Row | Work | Depends on | Agent |
|---|---|---|---|
| **R** | Probe on the 26.x seam: a templated title argument with legacy and MiniMessage formatting in a GUI without art (§3.2 *Title fidelity*); `gui.open` with a templated id; screenshots | none | Haiku |
| **A** | P-A: the subject stack on `gui.open` from an item run, `gui.item.*` in context, the liveness check, D-1 (load half). Coordinate with #333 step 1 (one implementation) | none | Sonnet, high |
| **B** | P-B Phase A: `holder: item` on `slots` (schema, studio field, self-description), the stack store over the shipped keys, the per-tick mirror, capacity, the pin, the 256 KiB cap, D-1 runtime half, D-2, D-3, the `holds_custody` item property (need 8); unit and harness tests; the legacy-bytes golden captured from today's mechanic; T03 by-hand corpus update | A | Sonnet, high |
| **G** | The six built-in GUIs `materione:backpack_1..6` (vanilla look, nesting refused unless `allow_nested`), their compiled goldens, `backpack.opens-builtin-gui` visual QA | B, R | Haiku |
| **T** | The `preset` content type (§4.4): registration through the public content-type registry, its schema (`params:`, `applies_to:`, contributed `behavior:`/`flows:`), lossless round-trip fixtures, validation that a contributed field exists on the host's schema (R4), namespacing and the cross-pack reference rules, D-6; the type's self-description golden and the generated SDK builder | none | Sonnet, high |
| **D** | Resolution (§4.3): the preset index, S1 lookup under `mechanics:`, `preset.*` binding, graph edges, D-4, quarantine of a broken preset's declarations; the built-in `materione:backpack` preset definition; the SDK declaration members generated from each preset's `params:`; tests with a content maker's preset in a fixture pack (`fixtures/packs/presets/`) declared by items in the same pack and in a dependent pack | T, G | Sonnet, high |
| **E** | The editor (§7, §7.1): the picker's *Preset* chip and pack label, the schema-derived settings panel for any preset, the preset studio (parameters list, applies-to, the rules editor with `preset.*` completions, Used by), built-in presets read-only; the `?modules` and a new `?preset-studio` browser fixture | T, D | Sonnet, high |
| **F** | Fold-down: delete `BackpackMechanic` and its registration, the name resolves to the preset; harness tests over a world with native-written stacks; QA `backpack.legacy-contents`, `.pinned-while-open`, `.shrink-keeps-items`, `.nesting-refused`, and `presets.user-preset-applies` (a content maker's preset on two items); docs (mechanics.md table, primitives.md §4.2, runtime-systems.md, agent-benchmark.md T03) | D, E | Haiku |
| **P** | Phase B: the strict path for `holder: item`, the versioned store and adoption of §5.2 (no dual-write, Q3), the backpack preset's `durable` parameter (default `best_effort`, Q7), the stack-holder kill-boundary rows. Lands inside #334's design (the holder formats), not before #312 closes (primitives.md §5.1 order) | #334 design, B | Sonnet, high |

Customize for presets (former row C) is not scheduled (Q6, answered "later").

Row F's PR is where the plain-chest path disappears; the issue's exit criterion ("the plain-chest path is gone") is met there, and the
crash-safety sentence of the issue is met by row P.

---

## 12. Owner answers (2026-11-19)

The owner answered on PR #450: *"i like the idea of presets: the materione builtin pack would have the backpack preset, but content
makers would be allowed to create their own presets (to streamline the creation of content). Everything else looks good for backpacks."*
Q2 is answered with that extension (§4.4); Q1 and Q3 to Q9 are answered as recommended. Of master §14.7, part (ii) is now decided and
recorded there; part (i) is settled for items only; (iii) pinning and versioning and (iv) the expanded view stay open.

| # | Question | Options | Answer |
|---|---|---|---|
| **Q1** | The preset spelling on items (master §14.7 (i)) | S1 keep `mechanics: [backpack: {rows, title}]`; S2 a generic `preset:` key | **S1 for items** (no file migrates). A generic key for hosts with no `mechanics:` list is decided when the first such preset is designed. |
| **Q2** | Where preset definitions live (master §14.7 (ii)) | L1 a `preset` content type (data in packs); L2 a Java registry like the `Recipe` SPI | **L1, and user-extensible:** the built-in pack ships `backpack`; content makers author their own presets in their own packs (§4.4, §7.1, rows T, D, E). |
| **Q3** | Downgrade across Phase B's format change (§5.2) | Dual-write `backpack-data` for a deprecation window; or adopt and document that an older build sees adopted backpacks as empty | **Adopt and document**; no dual-write. |
| **Q4** | Nesting: may a backpack go inside a backpack by default? | Allow (today's behavior, bounded by the cap); deny by default with a preset parameter to allow (needs need 8) | **Deny by default**, `allow_nested: true` to opt in; existing nested stacks can always be taken out. |
| **Q5** | The stack store's size cap | 256 KiB like the account; smaller (vanilla packet and chunk pressure argue lower) | **256 KiB**, refusal with D-3; measured in row B. |
| **Q6** | Does the editor ship Customize for presets in v1? | Yes; later | **Later.** The by-hand route is reachable through the SDK and Files. |
| **Q7** | Once Phase B is green on main, does the built-in backpack default to `durable: required`? | Required (inert on servers without checkpoint support, `MTR-0100`); stay `best_effort` with `required` as a preset parameter | **`best_effort` default with a `durable` parameter** (row P). |
| **Q8** | Contents past a shrunken view (§6.4 H6) | Keep them stored and hidden; return them to the player on the next open | **Keep them stored**; regrowing `rows` restores the view. |
| **Q9** | The built-in GUIs' look | Vanilla chest without art (today's look); a Materione-styled backpack art | **Vanilla** in row G; art is content and can follow without a design change. |

**Not verified in this design** (read from code and docs, not run): defects B1 to B5 (read from `BackpackMechanic`; row B's harness
tests confirm them before fixing); whether `gui.open` accepts a templated id and whether formatting in a templated title argument renders
(row R); the mirror's cost against the plugin.md §9 click budget (row B); that a bundle interaction can move a stack out of its slot while
another container is open on 1.21.4 and 26.1.2 (row B's pin tests).
