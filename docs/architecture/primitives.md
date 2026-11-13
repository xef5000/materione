# Primitives, not features: the audit of today's surface (D12)

> **Scope.** [D12](../architecture.md#31-d12-in-full) says the engine ships
> *universal primitives* and no feature-shaped surface, and that the first experience of a new author is a layer of
> *presets* over those primitives. This document does the work D12 implies: it fixes the vocabulary and the test (§2),
> decomposes one real feature end to end to find what is missing (§3), classifies every piece of surface that is shipped
> today (§4), names the primitives the audit proposes (§5), says which open issues change shape (§6), and lists what a first
> preset catalog is seeded from (§7).
>
> **Status.** The decision (D12) is in the master document. This document is an **audit and a set of directions**, not a
> design: nothing here deletes or renames shipped content, and every direction in §5 needs its own design document and issue
> before code (the way [`gui-input-durability.md`](gui-input-durability.md) preceded its rows). Where a claim was read from code
> rather than run, the reference says which file; §8 lists what was not verified.
>
> **Traceability.**
>
> | Decision or rule | How this doc applies it |
> |---|---|
> | D12 | The whole document: the primitive test (§2.2), the classification (§2.3), the audit (§4), the fold-down (§5, §6). |
> | D1 | A preset is ordinary content on disk, never editor-only state (§7). A fold-down never changes what an existing file means (master §5.5, §5.6). |
> | D4 | Behavior stays one Event, Condition, Action system. The audit asks which parts of it are host-shaped (§4.3) and which verbs are missing (§4.4). |
> | D5 | A primitive is a registry entry that describes itself, because that description is what the editor and the agent's tool layer render. A surface that does not self-describe is invisible to an agent (§2.2, test U4). |
> | D6 | A preset declaration is resolved by the pipeline when it builds the runtime; there is no "apply preset" side channel (§7). |
> | D11 | The agent surface is a chatbot in the cloud editor over the existing protocol: no new endpoint, relay still untrusted (master §14.8). |
> | D13 | The agent reaches primitives through a generated SDK ([`codemode.md`](codemode.md)); a primitive the audit calls a gap is a *type error* in the agent's program, which is how the benchmark finds it (codemode.md §9.2). |
> | §7.3 | Built-in packs are where first-party features live as content (§4.9). |

---

## 1. Why this audit exists

Materione's reason to exist (master §1) is that incumbents each generalize one area (resource packs, GUIs, mobs) and make the owner
wire them together. The strategic bet of [#320](https://github.com/xef5000/materione/issues/320) is one step further: an agent
connected through an MCP-style tool layer (api.md §1, the third consumer; deployed as a chatbot in the cloud editor, master §14.8) should be able to build a whole gameplay feature by *composing*
Materione's building blocks, with typed, validated calls instead of the Skript pattern of rebuilding every primitive from
scratch. That only holds if the building blocks are universal. A feature-shaped piece of engine surface ("input slots",
"GUI sessions", a "trade system") is the opposite: it solves one case, its name tells an agent so, and the next case needs
another one. The engine would grow the way the incumbents did, one use case at a time, inside one JAR.

The audit measures how far the shipped surface is from that, and it does so with a test that can fail, not with taste.

---

## 2. Vocabulary and method

### 2.1 Vocabulary

| Term | Meaning |
|---|---|
| **Primitive** | A typed, composable capability of the engine that names *what it does*, works for any key, holder, viewer or host that content names, and describes itself through a registry (D5). Examples that already exist: the `if`/`require`/`wait` steps, `set_var`, a `gui` layer component, an item source. |
| **Feature-shaped** | Surface that is named for a use case (trade, backpack, input slots, session, shop) or hard-wired to one subject (the run's player, one GUI window). It works, and a second use case needs a copy of it. |
| **Composition** | A feature built from primitives alone: content in a pack, with no engine code. |
| **Preset** | The first-experience layer (D12 Layer 2): a named, parameterised composition the editor offers as a quick setting ("this item is a backpack"). It is declared by reference and its logic is resolved from its definition at runtime, built from primitives and built-in content. §7. |
| **Gap** | A primitive that does not exist and that a plausible feature needs. Found by the benchmark of §3, closed by adding a primitive, never by adding the feature. |
| **Holder** | Something that holds items (or state) on someone's behalf: a player's account, an item stack, a keyed state, a block. Used in §3 and §5.1. |
| **View** | A rendering of state to one or more viewers: a container GUI today; a dialog (#6) and a HUD (#7) are the same shape. §5.3. |

### 2.2 The universality tests

The five tests D12 states (master §3.1) are applied to every piece of surface, new or shipped. A piece that fails one is
feature-shaped on that axis.

| # | Test | Fails when |
|---|---|---|
| **U1** | **Name.** Is it named for a capability (hold, transfer, key, view, emit, command) and not for a use case (trade, backpack, shop, input slot)? | The name would be wrong in a second feature. |
| **U2** | **Generality.** Does it work for any key, holder, viewer or host that content names? | It is hard-wired to one: the run's own player, one GUI window, one item. |
| **U3** | **Composition.** Can content combine it with the other primitives without engine code? | Wiring it into a new feature needs Java. |
| **U4** | **Self-description.** Is it a registry entry with a typed schema and stable diagnostics, so the editor and the agent's tool layer render it (D5)? | It is reachable only from code, a hard-coded command or a hand-written tool. |
| **U5** | **Safety.** Does it validate offline, dry-run without side effects, fail soft and keep its crash semantics (D6, master §11)? | It cannot be tested or rolled back without a live player. |

And the **benchmark** that makes the tests concrete: a feature such as `/trade <player>` with a custom two-player UI must be
buildable from content alone, through MCP, with no new engine code (§3). When an agent or an author needs engine code, the
missing piece is a primitive, and that is the issue to file. #320's 20 to 30 benchmark tasks double as D12's regression suite;
they are written out, with the gaps ranked by the tasks each unblocks, in [`agent-benchmark.md`](agent-benchmark.md).

### 2.3 The four classes

| Class | Meaning | What happens to it |
|---|---|---|
| **P** | Primitive. Passes U1 to U5. | Nothing. |
| **P+** | A primitive with feature-shaped coupling: the mechanism is general, the API, name or hosting is not. | Generalize in place; the file format keeps its meaning. |
| **F** | Feature-shaped. | Becomes a preset (declared by reference, defined over primitives) or a composition. The shipped YAML keeps loading. |
| **G** | Gap: a primitive that is missing. | A design document, then a primitive. |

"Feature-shaped" is not a verdict on quality. Backpacks and soulbound items are shipped, tested and used. The class says where
the next case will cost engine work, and therefore where to spend design effort first.

---

## 3. The worked example: `/trade <player>`

The feature: a player runs `/trade Alex`. Alex gets a request and accepts. Both see one window with their own offer and the
other's. Each puts items in and confirms. When both have confirmed the items swap, once, and a crash or a disconnect at any
moment loses and duplicates nothing. A request that nobody answers expires.

None of this should need engine code. Decomposed, each need is a primitive, and each primitive is checked against what is
shipped:

| # | Need | The primitive | Today | Class |
|---|---|---|---|---|
| 1 | A command with typed arguments, a permission and a requirement | **Command as content**: names, typed args, gate, a flow | `open.commands`, `open.args` (type `player` included), `open.permission` and `open.requirement` exist (logic.md §6.1, §23), but only as the `open:` block of a `gui` resource. A command that opens nothing, or that runs a flow before any window, has no home. | P+ |
| 2 | State shared by two players (both offers, both confirm flags, the expiry) | **Keyed state with a TTL**: variables scoped by an arbitrary key | The scopes are the four reserved words `local`, `session`, `player`, `server` (logic.md §3.1, §20 decision 3). `session` is `GuiSession.vars`, a map owned by one player's window. There is no key scope, no TTL and no expiry hook. | G |
| 3 | A window both players see, that updates when either changes something | **A view over state**, rendered for any number of viewers | One `GuiSession` per (player, GUI) (`plugin/gui/session/GuiSession`); `refresh:` and `gui.refresh` re-render that viewer's own window. Nothing renders "every viewer of key K". | P+ |
| 4 | Items each player offers, held safely | **Item custody** | The escrow of #247: an account inside the player's own data and `storage/escrow/<uuid>.ledger` (gui-input-durability.md §5). It is reachable only through a `slots` layer with `input: true` and `durable: required`, plus the GUI's close and death paths. | P+ |
| 5 | The swap is atomic and crash-safe | **Atomic transfer between holders** | The ledger's record kinds are `CONSUME = 1` and `HAND_OFF = 2` (`HAND_OFF` reserved; gui-input-durability.md §5.1 *Exit*, §12 ambiguity 1). Recovery is per player. | G |
| 6 | An unanswered request expires; a disconnect cancels | State TTL, `gui.close` with `reason: quit`, and a trigger on expiry | Close reasons exist (logic.md §6.2). There is no expiry trigger; `wait` parks a run per player (budget 64, logic.md §5.3). | G |
| 7 | "Tell the other player", "give to the other player" | **Target addressing** in actions: act on another player or holder by reference | `message` takes `target: player\|actionbar\|broadcast` only (`MessageAction`); `give_item`, `teleport` and the rest act on the run's own player. | G |
| 8 | A request and accept handshake | A second command with a `player` argument, plus 2 and 6 | Follows from 1, 2 and 6. | Composition |
| 9 | Both confirmed, so run the effects once | `require`, plus 5 | `require` exists (logic.md §2.2). | Composition |

Read the table as the audit's headline: **five of nine needs are gaps or half-primitives, and each one is a general capability
that `/trade` merely exposes.** Once the same five exist, `/pay`, an auction house, a party-invite system and a duel challenge
are the same composition with different content. That is the case for D12 in one example.

**What the content looks like when the gaps are closed** (shape, not syntax; each file is an ordinary document in a pack):

| File | Role |
|---|---|
| `commands/trade.yml` | `/trade <player>`, typed argument, permission; its flow writes the offer state and messages the target. |
| `commands/trade_accept.yml` | The accept handshake; reads the state, opens the view for both. |
| `variables/trade.yml` | The keyed state: offers, confirm flags, expiry. |
| `guis/trade.yml` | The view: two custody-backed slot regions bound to the offer state; confirm and cancel buttons. |
| flows inside those | `require` the confirmations, then one transfer between the two holders; expiry and quit cancel and return. |

**The honest unknown (#5).** The shipped protocol is safe because the account lives *inside the player's own snapshot*
(gui-input-durability.md F3), so one player's inventory and escrow cannot be split by a save, and the one decision that must
survive a rollback (a consume) is a record in that player's ledger. A transfer between two holders has two snapshots and two
ledgers that a crash can persist independently. The decision record must be written once and be visible to the recovery of
both holders. Whether the per-player ledger layout can carry a joint decision, or needs a shared transaction record, is a design
question for the custody document of §5.1. It is not answered here, and no row of #247 claims it.

---

## 4. The audit

Each table lists the shipped surface, where it lives, its class (§2.3), and the proposed direction. Directions are proposals for
the owner; the numbered primitives they point to are in §5.

### 4.1 Registries and content types

Registry keys (`api/registry/RegistryKeys`): content types, mechanics, actions, conditions, triggers, expression functions,
currency integrations, item sources, list sources, input methods, (logic) recipes, delivery backends, GUI components. The
registry model itself is the strongest primitive in the project; it is what makes the rest checkable.

Content types registered by `content/` (D5), 11 in total:

| Type (directory) | Role | Class | Direction |
|---|---|---|---|
| `item` (`items/`) | Appearance, components, `mechanics:`, `behavior:` | P, with F in `mechanics:` (§4.2) | The vanilla component pass-through (#103's M7 item, #161) is the primitive under `food`, `consumable` and the like. |
| `block` (`blocks/`), `furniture` (`furnitures/`) | World-placed appearance plus behavior hosts | P | None. Depth work (#14, #15) is appearance primitives. |
| `glyph`, `sound`, `painting`, `effect` | Appearance-only pack resources | P | None. |
| `recipe` (`recipes/`) | A vanilla crafting recipe (runtime-systems §5) | P | None. The name collides with the logic `Recipe` SPI (§4.10). |
| `gui` (`guis/`) | One file holding the view (layers), the opening command (`open:`), the rules, and the input and custody policy (`input:`, `durable:`) | **P+** | Split the concerns, not the format: view / command / state / custody (§5.1 to §5.4). `open:` keeps working as sugar for a command whose flow is `gui.open`. |
| `currency` (`currencies/`) | A named binding to a money provider (logic.md §18) | P | Watch: providers are a fixed set (§4.11). |
| `variable` (`variables/`) | Declared typed state | **P+** | Keyed scope and TTL (§5.2). Existing declarations keep their meaning. |
| templates (pack layout, not a type; plugin.md §3.1) | Inheritance bases, including items as templates (#203, PR #285) | P | A precedent for referencing a base by id (master §14.7); presets resolve at runtime and are not copied. |

**Content types that do not exist and that the audit needs:**

| Missing | Why |
|---|---|
| A **command** type | §3 need 1. |
| A **rule or flow unit** that is not owned by an item, block, furniture or GUI | Rules live in a host resource today (logic.md §2.1, §2.4: named flows are callable only inside one resource). A command, a world-event rule or a timer has no host. §5.5. |
| A **preset** type (if presets are data, master §14.7 sub-question ii) | A preset's definition must be content, or presets become an addon-only Java surface (the recommended answer to master §14.7 (ii)). §7. |
| Other **view kinds**: dialog (#6), HUD (#7) | They are the GUI's siblings (§5.3). |

### 4.2 Mechanics (the natives)

The mechanics registry (`api/mechanics`, mechanics.md) is the seam through which built-in and third-party abilities attach to
items. Five built-ins, each in `plugin/runtime/mechanics/builtin/`:

| Mechanic | Implementation (mechanics.md) | Class | What it really is | Direction |
|---|---|---|---|---|
| `commands` | Repeatable canned ECA: `run_command` rules | **F** | A `click` rule plus `run_command`, written as a mechanic before the logic layer existed. | A preset, or just a rule. Keep loading. |
| `food` | Platform item modifier plus a typed consume-effect listener | **F** | The vanilla `food`/`consumable` components plus a consume trigger. | A preset over the component pass-through (#161). |
| `durability` | Native damage listener; counters in the stack's PDC | **F** | A counter held on a stack (keyed state whose holder is a stack) plus the item damage trigger. | Keyed state (§5.2) with a stack holder, a preset on top. |
| `soulbound` | Native death, respawn and join listeners; a death stash in player PDC (`SoulboundMechanic`) | **F** | Custody of items across a death, plus death and respawn triggers. | Custody (§5.1): the stash is a *second* hand-rolled holder. |
| `backpack` | Native interaction and close listeners; a plain `Bukkit.createInventory` chest; contents serialized into the stack's PDC (`backpack-id`, `backpack-data`) (`BackpackMechanic`) | **F** | A container whose items are held on an item's behalf, plus a view of it. It does not use the GUI system at all. | The first preset (#304): custody with a stack holder, plus the built-in backpack GUI, declared with `rows` and `title`. **A backpack is not an engine behavior** (owner, PR #322): the native mechanic folds into the preset, and existing stacks keep their stored contents. |

**The finding that matters here: three separate hand-rolled holders of items.** Backpack contents live in the stack's PDC,
the soulbound stash in the player's PDC, and the escrow account of #247 in the player's PDC under its own key. Each has its own
serialization, its own recovery story and its own bugs to find. One custody primitive (§5.1) makes the second and third
instances of a problem the first one already solved.

**The mechanics SPI stays.** `createRuntime` (native listeners) is the extension seam for addons whose behavior no primitive can
express. D12's reading of it: a *built-in* mechanic that needs native listeners is evidence of a missing primitive, usually a
missing trigger (§4.3), and the fix is the trigger, not a longer list of natives.

### 4.3 Triggers

Item, block and furniture triggers (`behavior/logic/BuiltinTriggers`): 17 registered, 11 live (`click`, `break`, `place`,
`interact`, `equip`, `unequip`, `drop`, `pickup`, `consume`, `damage`, `death`) and 6 reserved and unsupported (`inv_click`,
`tick`, `menu_click`, `hud_threshold`, `dialog_submit`, `custom`). GUI triggers (`plugin/gui/trigger/GuiTriggers`): 9
(`gui.opening`, `gui.open`, `gui.close`, `gui.click`, `gui.click_outside`, `gui.click_inventory`, `gui.slot_change`, `gui.tick`,
`gui.page`).

| Finding | Class | Direction |
|---|---|---|
| Every trigger is fired by a *host* (an item, a block, a furniture, a GUI), and a rule has to live in that host. | **P+** | A rule unit with no host (§5.5). |
| `damage` is *item durability damage* ("The item takes durability damage"), not combat. #320's own example ("hits apply a bleed effect") has no trigger to hang on: no trigger carries the struck entity or the damage dealt, and none fires when an entity dies, a player joins, quits or respawns, a command runs, or chat is sent. | **G** | World-event triggers, host-less (§5.5, §5.7). |
| `custom` is reserved, and so is a generic `tick`. Without a named event content can emit and answer, content cannot compose with other content. | **G** | `custom` with a typed payload and an `emit` action; a timer; an expiry trigger (§5.2). |
| The `gui.*` triggers carry the GUI's own context (`gui.*`, `slot.*`, `entry.*`). | P | Stays. Views other than the container GUI add their own sibling triggers (#6, #7). |

### 4.4 Actions

28 built-ins at the time of the audit, 29 since #248 added `custody.consume` (`plugin/logic/BuiltinLogic`, `plugin/logic/action/`):

| Group | Actions | Class |
|---|---|---|
| Player and world verbs | `run_command`, `message`, `broadcast`, `actionbar`, `title`, `chat`, `sound`, `stop_sound`, `potion`, `teleport`, `cancel` | P |
| Items and experience | `give_item`, `take_item`, `give_exp`, `take_exp` | P (`take_*` fail safely and compose under `require`, logic.md §2.2) |
| State | `set_var` | P+ (scopes fixed, §4.6) |
| Input | `prompt` (with the input-method registry) | P |
| Custody | `custody.consume` (#248; logic.md §36): consume what custody holds at a slot, atomically, optionally until a saved snapshot proves it | P (passes U1 to U5, logic.md §36). The trash slot is the recipe `trash_slot` over it |
| Providers | `give_money`, `take_money`, `give_permission`, `take_permission`, `connect` | P (each reaches a provider SPI, §4.11) |
| View | `gui.open`, `gui.close`, `gui.back`, `gui.refresh`, `gui.page`, `gui.slot_item` | **P+**: they act on "the run's own GUI session" implicitly |

| Finding | Class | Direction |
|---|---|---|
| **Actions have an implicit subject.** Each acts on the run's own player (and window). There is no way to say "this player, the other player, the holder of this key". | **G** | Target addressing (§5.6): an optional target on actions that make sense on another subject, resolved and permission-checked by the engine. |
| The verb set stops at players, items and messages. Nothing damages or heals an entity, applies an attribute modifier, spawns a particle, launches or kills. A combat-spell catalog (#13: lunge, lifeleech, bleed, fireball, knockback) cannot be data until these exist. | **G** | New verbs as primitives (and #21 for particles), each universal (any entity), not each spell. |
| No `emit` and no cross-resource `call`. | **G** | §5.5. |

### 4.5 Conditions and expression functions

| Surface | Class | Notes |
|---|---|---|
| 10 conditions (`has_permission`, `has_money`, `has_exp`, `has_item`, `has_item_space`, `is_near`, `in_world`, `in_gui`, `var`, `slot_has`) | P, with `in_gui` and `slot_has` view-shaped | Fine. `slot_has` reads an input slot of the run's GUI; it stays a view condition (§5.3). |
| 23 expression functions (`contains`, `matches`, `round`, `random`, `format_number`, and since #328 the clock: `now`, `today`, `format_duration`) plus the currency pair `balance` and `format_currency` | P | The expression engine is the universal glue (D4). The clock is a primitive for U5: it is injected (a `java.time.Clock`), so a test runs a fixed one (logic.md §15). |

### 4.6 Variables and scopes

`variable` content type, two stores behind a port (`plugin/logic/variable/`: `PdcVariableStore`, `ServerVariableStore`,
`SnapshotVariableStore`), four scopes (logic.md §3.1, §20).

| Scope | Backing | Class |
|---|---|---|
| `local` | one run, in memory | P |
| `player` | the player's PDC (`materione:var.<pack>.<key>`) | P as a *key choice* (the key is the player) |
| `server` | `storage/variables.json` | P as a key choice (a constant key) |
| `session` | `GuiSession.vars`, alive while one player's window is open | **F** |

**Finding.** `player` and `server` are *special cases of a keyed store* (the key is the player's id, or nothing), and `session`
is the key "this GUI window". The four reserved words (`MTR-0063`, logic.md §20 decision 3) are a closed set because the key is
closed. Opening the key (any expression), with a TTL and an expiry trigger, makes all four instances of one primitive without
changing any file that exists (§5.2). The cross-server adapters of master §14.2 fit behind the same storage port.

### 4.7 GUI components, runtime and sessions

| Surface | Class | Notes |
|---|---|---|
| 8 components (`panel`, `image`, `paint`, `text`, `button`, `item`, `slots`, `list`), registered through `GUI_COMPONENTS` | P | View-layer primitives. The registry is the proof. |
| Item sources: `materione:item`, `head`, `player_slot`, plus `hdb`, `itemsadder`, `nexo`, `mmoitems` when present (`integrations/items`) | P | #251 hardens the optional ones. |
| List sources: `online_players`, `pack_items`, `content`, `variable`, `static` | P, `content` is borderline | `content` is the content-graph read model the browser needs; it is a primitive only if exposed as a general graph query. |
| Input methods: `chat`, `anvil`, `sign` (sign unavailable on 1.21.4) | P | Dialogs (#6) register the next. |
| **`GuiSession`** (per player and GUI: args, session variables, list pages, run identity) | **F** | The trade example (§3 need 3) cannot be built on it: one player, one window. Its variables become keyed state; its page and render state become *viewer-local view state*; the session stops being the unit of sharing. |
| **`slots` with `input: true`, `accept:`, `durable:`, `player_inventory: locked\|free`** | **F** | A custody holder placed inside a view, named for the use case. The mechanism is §5.1. |
| `gui.slot_change` and `gui.slot_item` | **P+** | The change event and the edit verb of a custody-backed region. |
| `GuiOpener` and its eight stages (logic.md §6.1) | P+ | Correct, and generalizes as the opening path of a command (§5.4). |

### 4.8 Custody (the escrow work)

`plugin/escrow` (`io`, `model`, `runtime`), gui-input-durability.md: the account (`materione:gui_escrow`, a versioned value in the
player's PDC), the ledger (`storage/escrow/<uuid>.ledger`, `INTENT`/`DONE`), recovery before interaction, the owed-items claim
(`/materione claim`), the death release, `MTR-0100` to `MTR-0102`.

| Aspect | Class | Why |
|---|---|---|
| The mechanism: items held on someone's behalf, in the holder's own snapshot, with a durable decision log and recovery | **P** | General by construction; nothing in the protocol needs a window. |
| The surface: the account key is `gui_escrow`, the entries carry a GUI origin, the only way in is a strict `slots` layer, and `claim` is a hard-coded subcommand | **P+** | GUI-shaped naming and entry points over a general mechanism. |
| `HAND_OFF` (reserved) | **G** | The atomic transfer of §3 need 5. |
| `custody.consume` (#248) | P | The exit verb, built: the tier-2 exit as an action, with `await: saved` for an effect behind the barrier. The trash slot is the recipe `trash_slot` over it (#222), with no engine code of its own. `gui.slot_item clear` and `set_amount 0` on a required layer are the same consume without a wait. |
| `/materione claim` | **F** | A hard-coded command (`MaterioneCommand`) for what a content command could do over a `claim` verb (§5.4). |

This is the single largest piece of already-built work that D12 *validates* rather than disturbs: the design is the custody
primitive under a GUI-specific name, and its rows (#306 to #310 built; #311 to #313 open) proceed unchanged. §5.1 says what
changes: the name, the set of holders, and the entry points, in that order, and none of them before the kill-boundary proof
(rows B and H) lands, because the generalization must not weaken a guarantee that is not yet proven.

### 4.9 The built-in pack

`plugin/src/main/resources/builtin-packs/materione/` holds one resource: `guis/browser.yml` (master §7.3). The rule of §7.3 is
the right home for D12's "a feature is content": the browser is a GUI built from the `content` list source, `prompt`,
`gui.open` and `gui.back`, with no code that names it except a command alias.

| Finding | Direction |
|---|---|
| One built-in resource is a proof, not yet a catalog. The next ones are the backpack GUI (#304) and whatever presets ship. | The built-in pack is where first-party presets point (D12 Layer 2). |
| §7.3's "No special case" row says no code names a built-in pack or one of its resources. Presets that reference built-in resources by id are *content naming content*, which is the same thing a user pack does; they are resolved by the one generic pipeline stage, not by Java that knows `materione:backpack`. | Master §7.3 gets a sentence saying so (this PR). |

### 4.10 Recipes: two things share the name

| Meaning | Where | Class |
|---|---|---|
| **Crafting recipe**: a content type, the vanilla recipe projection | `content/recipes`, runtime-systems §9 | P |
| **Logic recipe**: a parameterised flow fragment the studio inserts (`Recipe` SPI, `RegistryKeys.RECIPES`), YAML in the jar: `back_button`, `close_button`, `confirm_dialog`, `paged_player_list`, `pay_to_receive`, `server_selector`, `toggle_setting`, `trash_slot` (since #248), `welcome_message`, and `cooldown_gate` (since #328: a persistent cooldown as a declared player number and `now()`) (`plugin/src/main/resources/recipes/`) | logic.md §4.6, §30 | **P** (and the seed of presets, §7) |

The logic recipes are the existing precedent for the preset layer, at flow scale: parameters with types and ranges, expansion
server-side into ordinary steps, and (logic.md §4.6) "a recipe never survives into the file as a recipe". That is the *rejected* option (b) of the
representation question, which the owner decided against for presets (master §14.7): it fits fragments, not features. This document does not rename either meaning; it uses *preset* for the new layer so that
there are not three.

### 4.11 Integrations and providers

`LogicProviders` has a fixed set of slots: currencies (`CurrencyCatalog`), permissions (`PermissionProvider`), server transfer
(`ServerTransfer`). Integrations: Vault (currency, permissions), LuckPerms (permissions), BungeeCord/plugin messaging
(transfer), optional item sources, a PlaceholderAPI variable bridge (`integrations/`).

| Finding | Class | Direction |
|---|---|---|
| Infrastructure plugins are reached through providers, not reimplemented. This is the one area already aligned with #320's non-goals. | P | Keep. |
| The provider kinds are a closed set in code. A region query (WorldGuard), a claims plugin or a different economy shape is a new SPI, new slot and new actions. | **P+** | Open provider kinds: a registry of provider kinds with capability descriptors, so an integration declares what it offers and content reaches it through typed verbs (§5.8). Do not start before a second concrete kind forces the shape. |

### 4.12 Commands and the API

| Surface | Class | Direction |
|---|---|---|
| `/materione version\|doctor\|reload\|dev\|give\|browse\|editor\|gui\|var\|claim` (`plugin/command`) | Mostly P (operate, don't author: master §9.5) | `browse` is already an alias that opens a GUI. `claim` is the F in §4.8. |
| Typed GUI authoring ops (`gui.get`, `gui.edit`, `gui.customizeItem`, `gui.render`, `gui.validate`; api.md §3.9) beside the generic authoring ops | **P+** | The chatbot's tools should be generic typed content operations derived from self-description, with a typed high-level operation only where it earns it (#320 open question 3). A *declared preset* is the high-level operation for features (master §14.7). |
| The `MaterioneAPI` facade (api.md): registries, authoring, assets, pipeline, delivery, runtime, admin | P | The chatbot's tool layer is an adapter over it (api.md §1, master §14.8). |

---

## 5. Directions: the primitives the audit proposes

Each is a one-paragraph direction with what exists today. None is designed here; each needs its own document and issue, and none
may regress a shipped guarantee or a performance budget (plugin.md §9).

### 5.1 Custody

*Items held by Materione on someone's behalf, safely, with a recovery story.* Today: the escrow of #247. Direction, in order:
(1) finish the proof of the shipped mechanism (rows B, H: #311, #313); (2) generalize the **holder**: a player's account (shipped),
an item stack (the backpack's store), a keyed state (a trade offer), later a block container; (3) rename in prose and, with a
migration, in the persisted key (`gui_escrow`) only after (1); (4) make input slots *one place* custody can sit, not the thing the
mechanism is named after; (5) a `claim` verb reachable from content. The atomic **transfer between holders** (`HAND_OFF`, §3 #5)
is the part with an open design problem (the joint decision across two snapshots). Re-scopes #247, #248, #304.

### 5.2 Keyed state with a TTL

*Variables scoped by an arbitrary key.* Today: four fixed scopes (§4.6). Direction: a key scope whose key is an expression
(`${trade.id}`), a TTL, an expiry trigger, and a store registry (memory, player PDC, server file; the §14.2 adapters later);
`player`, `server` and `session` become named special cases; every existing `variables/` file and `vars.*` read keeps its meaning.
Needs the checks of master §5.5 (additive schema). The durability class of a keyed store (memory only, or durable) is a
per-declaration choice, not a hidden property.

### 5.3 Views over state

*A rendering of state for any number of viewers.* Today: `gui` plus one `GuiSession` per (player, GUI). Direction: a view names
the state it renders; its viewers are a set; "re-render the viewers of key K" is a verb; per-viewer state (page, search text) is
the only thing that stays viewer-local. The container GUI, the dialog (#6) and the HUD (#7) become three view kinds over one
state model rather than three systems with three kinds of binding. This also answers where "HUD values" (master §8.3's
persistence table) live: in keyed state.

### 5.4 Commands as content

*A command with typed arguments, a gate and a flow.* Today: `open:` on a GUI (logic.md §6.1, §23), including the argument types,
`suggest:`, permission, requirement, deny, bypass and the Brigadier sync. Direction: lift the block into a `command` content
type whose flow may `gui.open`; `open:` remains valid and means exactly what it does. `claim` and `browse` become content.

### 5.5 A rule unit with no host, and the missing triggers

*Behavior that is not attached to an item, block, furniture or GUI.* Direction: a standalone rule/flow unit (new content type or
a facility of the command type, a design choice for its document); host-less triggers (`custom` with a typed payload and an `emit`
action, a timer, an expiry, entity hit/kill, player join/quit/respawn, command run); cross-resource `call` (logic.md §2.4 already
accepts a namespaced id in the syntax). This is also what unblocks #320's own example, whose bleed has no trigger today (§4.3).

### 5.6 Target addressing

*Actions on a subject other than the run's own player.* Direction: an optional, typed `target` on the actions where it makes
sense, resolved by the engine (a player argument, a holder, an entity selector), permission- and region-checked through the
thread gateway (D8), and visible in the self-description so an agent sees which actions take one.

### 5.7 Entity and world verbs

*The missing verbs* (damage, heal, attribute modifier, velocity, particle, spawn, kill), added once each as a primitive that
works on any entity. #21 (particles) is already one. This is what #13's combat spells need and what the bleed of #320's example
needs; each spell then is a preset.

### 5.8 Open provider kinds

*An integration declares what it offers.* Direction in §4.11; deferred until a second kind forces the shape.

---

## 6. Re-scoping the open issues

Proposals for the owner; no issue is changed by this PR. The mechanism for each is a comment linking D12 and this section, then
a re-title if the owner agrees.

| Issue | Today | Proposed re-scope |
|---|---|---|
| #247, #311, #312, #313, #314 | Input slots: crash-safe ownership | The custody primitive's proof (§5.1). Work and rows proceed unchanged; the vocabulary generalizes after row H. |
| #248 | Custody exit primitive | **Built as `custody.consume`** (logic.md §36): the exit verb of custody; with `HAND_OFF`, the `transfer` verb is still to come (§5.1). The trash slot is the recipe `trash_slot` over it. |
| #304 | Backpack as a preset over primitives | The first preset (§7). Depends on a stack holder (§5.1); the representation is decided (master §14.7). |
| #162 | Item modules: parameter-level modules | The editor surface presets plug into: a preset parameter is a module; derivation stays registry-driven. |
| #6 | Server-side dialogs | A **view kind** over state with `dialog.submit` as a trigger (§5.3), not a separate dialog system. |
| #7 | Native HUD system | A **view kind** bound to keyed state (§5.3), not a HUD-specific binding engine. |
| #13 | Mechanics catalog: combat and farming spells as canned ECA | Every entry is a preset. The "few action executors" the issue expects become universal entity and world verbs (§5.7) and host-less triggers (§5.5), not per-spell natives. |
| #10, #11 | All recipe types and an in-game builder; loot tables | Unchanged as primitives. #11: scope check that a loot table is a draw usable from flows, not only block drops. |
| #17 | Importers | Map incumbent features onto primitives and presets (an imported ItemsAdder backpack becomes the backpack preset), never onto new engine surface. |
| #20 | Integrations | Providers (§4.11, §5.8). |
| #251 | Item sources: typed optional APIs | Fits as is. |
| #320 | The agent story | The umbrella; its benchmark is D12's regression suite (§2.2). |

---

## 7. Presets: what the first catalog is seeded from

D12's second layer (master §3.1). The end-user experience is not primitives: a first-time user making a backpack must not learn
custody, views or keyed state. The editor offers "this item is a backpack"; the author's file then declares the preset and its
parameters, and at runtime the preset's definition (the built-in pack's GUI and logic) supplies the behavior. The owner's two routes
(master §14.7) are the whole model: **define the exact behavior yourself, or declare the preset.** The file carries no copy. Four things already exist that point at the same shape:

| Existing piece | What it does today | Relation to presets |
|---|---|---|
| Item modules (#152, web.md §6) | A searchable picker that adds a mechanic or a field to an item, derived from self-description | The editor surface: a preset appears as a module or a quick setting. |
| The Start workspace's starters (master §9.1) | Creation starters derived from the registered content types | The entry point: starters for a *feature* ("a backpack") beside starters for a *type*. |
| Logic recipes (§4.10) | Parameterised flow fragments, expanded into steps | The precedent at flow scale, and the model the owner did *not* choose for presets (master §14.7). |
| Customize and template linking (gui.md *Decisions taken in #203*, PR #285; plugin.md §3.1) | A new item that `template:`-links a base and overrides only what the author changes; Detach materializes the inherited values in one revision | A precedent for the route *from declaring a preset to defining it yourself* (Customize), and for referencing by id. |

**First candidates**, to prove the layer, each chosen because it is already shipped as a feature and therefore exercises the
fold-down: *backpack* (#304; custody with a stack holder plus the built-in backpack GUI), *soulbound* (custody across death),
*durability bar* (a stack-held counter), *food* (the component pass-through), *confirm dialog* and *paged player list* (the
existing logic recipes lifted to resources). How each is stored is decided (a declaration resolved at runtime,
master §14.7); the spelling and versioning are open.

The rules a preset must satisfy are D12's (master §3.1, R1 to R7); in particular the **by-hand rule**: a benchmark task that is
solved with a preset must be solvable, with the same result, by an agent that writes only primitives. That is what makes the preset
layer a convenience and never a capability.

---

## 8. Owner questions, and what was not verified

The owner answered the three questions of the first draft on PR #322; the decisions and what remains open (the preset reference's
spelling and versioning, who runs and pays for the model behind the chatbot) are in master §14.7 to §14.9. Not repeated here.

**Not verified in this audit** (read from docs and from registration code, not run):

- Behavior of individual classes beyond what the registrations and the cited docs state; the classes are *classified from their
  registered shape*, not re-reviewed.
- Whether `message` can carry a click action that would let a chat line say "accept" without a command (it would change the
  handshake of §3 need 8, not the primitives).
- Whether the `content` list source can be exposed as a general graph query without changing the content-graph API.
- Whether the per-player ledger layout can carry a joint decision for two holders (§3, the honest unknown); that is the custody
  document's first question.
- Performance of a keyed store and of multi-viewer rendering against the plugin.md §9 budgets. Both are design inputs, not
  claims.
