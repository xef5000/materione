# Logic: flows, the node editor and GUI behavior (M6)

> **Scope.** This doc turns the GUI builder ([`gui.md`](gui.md)) into a working menu system that
> can replace DeluxeMenus and CommandPanels. It covers four things:
>
> - **Engine.** It upgrades the one ECA engine (D4, [`runtime-systems.md`](runtime-systems.md) §2)
>   from flat action lists to structured **flows**. It adds typed, registry-backed actions and
>   conditions, persistent variables and integration SPIs.
> - **GUI runtime.** It binds that engine to GUIs: sessions, clicks, dynamic slots, input slots,
>   lists and pagination, prompts and open commands.
> - **Studio.** It specifies the studio's **Logic** tab, a node editor that is one view of the
>   same files.
> - **Code and work.** It fixes the code layout, and the order in which the work is cut into
>   issues (§13).
>
> **Spelling since views V0 (#455; [`views.md`](views.md) §16).** This document records how the GUI runtime was built, and most
> of it was written before the view model. Read these old spellings as the new ones: the actions `gui.open`, `gui.close` and
> `gui.back` are **`view.open`, `view.close` and `view.back`**; the context root `gui` (`gui.id`, `gui.args.*`, `gui.page.*`) is
> **`view`** (`view.id`, `view.kind`, `view.args.*`, `view.page.*`); `gui.session.*` is gone and a window-local value is
> **`vars.session.*`**; `GuiSession`, `GuiSessions` and `SessionHistory` are **`ViewerRecord` (with `GuiViewerState`),
> `ViewRegistry` and `ViewHistory`** in `plugin.view`; `GuiSessionPort` is `ViewPort`; `%materione_gui_open%` and
> `%materione_gui_last%` are `%materione_view_open%` and `%materione_view_last%`. The triggers (`gui.opening`, `gui.open`,
> `gui.close`, `gui.click`, ...), `gui.refresh`, `in_gui`, `gui.page` and `gui.slot_item` keep their names. A decision below that
> says "session" means the viewer record's binding, session values and GUI payload.
>
> **Traceability.**
>
> | Decision or rule | How this doc applies it |
> |---|---|
> | D4 | There is one behavior system. A GUI button runs the same flow that an item right-click runs, and every improvement here (flows, typed actions, variables) is also available to items, blocks and furniture. |
> | D1 | A flow is YAML in the resource file. The graph is drawn from it, and there is no graph file. |
> | D5 | Actions, conditions, triggers, list sources, input methods and recipes are registries that describe themselves. The node palette is derived from those registries. |
> | D6 | The studio writes files, and the pipeline compiles them. |
> | Fail-soft | A bad step is skipped and diagnosed. A failing action never stops the chain or the server. |
> | Lossless round-trip | Node positions and unknown keys survive a load and save. |

---

## 1. Parity target

The sources are the DeluxeMenus wiki (`wiki.helpch.at/helpchat-plugins/deluxemenus`) and the
CommandPanels docs (`docs.commandpanels.net`), both inventoried on 2026-10-29. The table maps every
incumbent feature family to the Materione construct that covers it. "Later" rows name their owner.

| Incumbent feature | DeluxeMenus | CommandPanels | Materione |
|---|---|---|---|
| Menu file, id, title | `menu_title`, `gui_menus` | panel file, `title` | `guis/**/*.yml` (gui.md §2). The title can be dynamic text (§6.4). |
| Size and container type | `size`, `inventory_type` (18 types) | `rows` or a type | `container:` naming a profile (`chest_1`..`chest_6`, `hopper`, `dispenser`), required in a GUI file (gui.md §12.4, built in #290; the plugin opens the hopper and dispenser with row P). Anvil and furnaces later. |
| Open commands and aliases | `open_command`, `register_command` | `command`, `aliases` | `open.commands` (§6.1), registered with Brigadier. |
| Open arguments | `args`, `args_usage_message`, per-argument requirements | command args read as session data | Typed `open.args` (§6.1), validated by conditions and exposed as `gui.args.<name>`. |
| Open gate | `open_requirement` with deny commands, bypass permission | panel `conditions` | `open.permission`, plus an `open.requirement` condition with a `deny` flow (§6.1). |
| Open, close and refresh hooks | `open_commands`, `close_commands` | `commands.open/close/refresh/outside` | Triggers `gui.open`, `gui.close`, `gui.tick` and `gui.click_outside` (§6.2). |
| Item forms | material, heads (name, base64, texture), hdb, ItemsAdder, Oraxen, Nexo, MMOItems, placeholder, hand or armor copy, air | material, `[head]`, `[hdb]`, `[nexo]`, `[itemsadder]`, `[mmo]` | An item layer's `item:` is an **item source** (§4.4): Materione item, vanilla, `head`, a registered external source, `player_slot`, or `${template}`. |
| Item text and meta | name, lore, `lore_append_mode`, amount, `dynamic_amount`, model data, enchantments, flags, trims, potions, banners, rgb | name, lore, stack, enchant, glint, model, trims, potions, banner, leather colour | Static appearance comes from the item studio (customize, gui.md §2). Per-slot overrides: `name`, `lore`, `lore_mode`, `amount` and `glint`, all templated (§6.3). |
| Conditional items | `view_requirement` plus `priority` | first matching item in the `layout` list | `visible_if:` on any layer, with the topmost visible layer winning per slot (§6.3). |
| Live update | `update: true` plus `update_interval` | `update-interval` | `refresh:` interval. Dynamic layers are detected rather than flagged (§6.3). |
| Animations | none | the `animate:` chain | `frames:` on item layers (§6.3), sharing the clock with glyph animation (#9). |
| Click types | left, right, shift-left, shift-right, middle (legacy) | left, right, shift-left, shift-right, any | `gui.click` with a `click:` filter covering every Bukkit click kind (§6.2). |
| Requirement then action, deny and success lists | `*_requirement` + `deny_commands`/`success_commands` | `requirements` + `commands` + `fail` | The `if` step: `then` and `else` (§2.2). |
| Requirement types | permission(s), money, item, exp, meta, is near, JavaScript, string equals, contains, length, regex, is object, comparators, `!` | `$HASPERM`, `$EQUALS`, `$ATLEAST`, `$AND`/`$OR`/`$NOT`, `[vault]`, `[xp]`, `[item]`, `[data]` | Registered conditions plus the expression engine with functions (§4.2). JavaScript is **intentionally not** offered: the expression engine covers its uses without a script sandbox. |
| `minimum_requirements`, `optional`, `stop_at_success` | yes | no | The `at_least: {count, of: [...]}` composite (§2.3). |
| Paywalls (check and take) | `has money` + `[takemoney]` | `[vault]`, `[xp]`, `[item]`, `[data]` take on success | The `if` step plus a take action. Take actions also fail safely (§4.1), and the "Pay to receive" recipe packages the pattern (§7.5). |
| Actions: commands | `[player]`, `[console]`, `[commandevent]`, `[chat]` | `[console]`, `[chat]`, `[grant]` | `run_command` with `as: player\|console\|op`, plus `chat`. |
| Actions: messages | `[message]`, `[minimessage]`, `[broadcast]`, `[json]` … | `[msg]` | `message`, `broadcast` (scoped), `actionbar` and `title`, all in Materione rich text. |
| Actions: sound | `[sound]`, `[broadcastsound]`, `[broadcastsoundworld]` | `[sound]`, `[stopsound]` | `sound` (scope: player, world or server) and `stop_sound`. |
| Actions: GUI | `[openguimenu]`, `[close]`, `[refresh]` | `[open]`, `[close]`, `[refresh]`, `[previous]`, `[itemaction]` | `gui.open`, `gui.close`, `gui.refresh`, `gui.back`, `gui.set_slot` and `gui.slot_item` (§4.1). |
| Actions: economy, exp, permissions | `[give/takemoney]`, `[give/takeexp]`, `[give/takepermission]` | `[vault]`, `[xp]`, `[grant]` | `give_money`/`take_money` against a named currency (`currencies/`, §18: Vault, the XP integration, or a command pair), `give_exp`/`take_exp` (an XP currency), and `give_permission`/`take_permission`, through provider SPIs (§4.5). |
| Actions: world | `[connect]` | `[server]`, `[teleport]`, `[give]` | `connect`, `teleport`, `give_item`, `take_item` and `potion`. |
| Delay and chance | `<delay=>`, `<chance=>` | `[delay]` (nested block) | Kept as action options (`delay:` and `chance:`), plus the `wait` step (§2.2). |
| Per-player data | meta (PDC, typed), set/add/subtract/switch/remove | session data, persistent `data.yml` (set/add/math/del/clear) | Declared **variables** with local, session, player and server scopes (§3). |
| Placeholders out | `%deluxemenus_meta_*%`, `opened_menu`, `last_menu` | `%commandpanels_session_*%`, `data_*`, `random_*` | A `%materione_var_*%`, `%materione_gui_*%` PlaceholderAPI expansion (§3.3). |
| Text input | none | dialog and floodgate inputs | The `prompt` action with an input-method SPI (chat, sign, anvil; dialog when #6 lands) (§6.6). |
| Items players put into the menu | none | `[item] source=panel` | **Input slots** and the `gui.slot_change` trigger (§6.5). |
| Inventory lock | none | `inventory-lock` | `player_inventory: locked\|free` (§6.5). |
| Pagination, lists | none (community workarounds) | none | The `list` component with a list-source SPI and page actions (§6.7). |
| Bedrock forms | none | floodgate panels | Later: Geyser work (#24). |
| Dialog panels | none | `type: dialog` | Later: dialogs (#6) reuse these flows, and `dialog.submit` is a trigger. |
| Admin commands | `/dm open/list/reload/execute/meta/dump` | `/pa open/reload/data/generate` | `/materione gui open/list`, `/materione var get/set/list` (§3.3), and the existing reload. |
| Extension API | none | material, item, requirement and command registries | Public registries for actions, conditions, triggers, item sources, list sources, input methods and recipes (§4). |
| Importing incumbent menus | n/a | n/a | Later: the importers issue (#17) gains DeluxeMenus and CommandPanels mappings once this model ships. |

### 1.1 Coverage by the showcase (#227)

Every row above that is not "Later" is listed below with what demonstrates it: a showcase GUI (`showcase:<name>`, the pack in
`fixtures/packs/showcase`), an in-game QA entry (`showcase.*` in `qa/catalog/showcase.mjs`, or an existing entry named in full), or
an explicit **gap**. A gap is not covered by any client run; the reason is given. The QA entries are authored and pass
`qa/runner.mjs check`, but they have **not run against a client** (blessing is pending, qa/README.md). Rows are in §1 order.

| §1 row | Demonstrated by | Status |
|---|---|---|
| Menu file, id, title | All seven showcase GUIs. | Partial. Titles are static in the showcase. A dynamic title (§6.4) has a client entry on the hopper, `guicontainers.hopper-dynamic-title-auto` (not blessed yet); on the chest it has none, and `GuiTitleTest` covers it. **Gap** on the chest. |
| Size and container type | `container: chest_1` (servers, settings, confirm), `container: chest_3` (shop, players), `container: hopper` (`showcase:hopper`) and `container: dispenser` (`showcase:dispenser`), the last two opened by name with `materione gui open`. | Covered for the chest, the hopper and the dispenser: the `guicontainers.*` entries (window type and size, clicks by slot id, the outside click, the hopper's dynamic title, the art; not blessed yet). Other containers are profiles (gui.md §12.5.1) that compile and that the runtime opens (row P); they get a client entry when they ship. |
| Open commands and aliases | `showcase:shop` opens with `/shop` (`showcase.shop-open-command`). | Partial. One name per GUI here. Several names: `opencommands.deferred-alias-applies-automatically` (existing). |
| Open arguments | Shop `category` (choice, default `all`): the console marker carries `category=gems`. | Covered. `suggest:` is not checked client-side. |
| Open gate | Not in the showcase. `opencommands.failed-requirement-denies-with-its-message` (existing, the `guilogic:opener` GUI). | Covered by an existing entry. The `open.permission` gate has no client entry. **Gap.** |
| Open, close and refresh hooks | `gui.open` in all seven; `gui.close` in the shop; `gui.click_outside` in confirm (`showcase.confirm-click-outside`); `refresh: 20` on the shop; `gui.tick` in servers. | Partial. `gui.tick` is authored and compiled, and no client entry counts its refreshes. **Gap** (the ticker has unit tests). |
| Item forms | Vanilla items in every GUI. | Partial. The `head:` source is `guidynamic.viewer-head` (existing). Materione items, external sources, `player_slot` and templates are not in the showcase, by design (no items in the pack). **Gap.** |
| Item text and meta | `name` and `lore` on every item layer; lore templated (`${vars.server.showcase.stock}`, `${gui.args.category}`). | Partial. `amount`, `glint` and `lore_mode` have no client entry. **Gap.** |
| Conditional items | `visible_if` on the sold-out barrier, the tip line and the two toggle overlays. | Covered: `showcase.shop-sold-out`, `showcase.settings-toggle`, `showcase.tip-line-follows-the-toggle` (visual). `guidynamic.conditional-item` (existing). |
| Live update | `refresh: 20` on the shop; `gui.tick` every 100 ticks on servers. | Partial, as above. `guiruntime.live-refresh` and the `guidynamic` entries (existing) cover the clock. |
| Animations | Not in the showcase. | Covered by `guidynamic.animated-slot` (existing). |
| Click types | Left on every toggle and button; right on the toggle (`showcase.settings-toggle`). | Partial. Shift, middle, drop and the rest are unit-tested (`ClickRouterTest`). **Gap** at client level. |
| Requirement then action, deny and success | `require` … `else` on the emerald and the lobby button; `if` … `then`/`else` on the sold-out check and the toggle. | Covered: `showcase.shop-buy-with-points`, `showcase.shop-buy-refused-without-points`, `showcase.shop-sold-out`, `showcase.servers`. |
| Requirement types | Expression conditions (`visible_if`, `if: "${…}"`) and the `var` condition (`if: var:` in settings). | Partial. `has_permission`: `logic.conditions-permission` (existing). `has_money`, `has_item_space` and `at_least` have no client entry. **Gap**; `at_least` is unit-tested (`ConditionsInFlowsTest`). |
| `minimum_requirements`, `optional`, `stop_at_success` | Not in the showcase. | **Gap** (unit-tested). |
| Paywalls (check and take) | The emerald: `require: [take_money, give_item]`, failing with the message and no stock change. | Covered: `showcase.shop-buy-with-points`, `showcase.shop-buy-refused-without-points`. |
| Actions: commands | `run_command` with `as: console` on every marker. | Covered for `console`. `as: player` and `as: op` have no client entry. **Gap.** |
| Actions: messages | `message` throughout. | Covered. `broadcast`, `chat`, `actionbar`, `title`: `logic.messages-broadcast-chat` and `logic.title-actionbar` (existing). |
| Actions: sound | Not in the showcase. | Covered by `guilogic.click-message-sound` (sound step) and `logic.sound-stop` (existing). |
| Actions: GUI | `gui.open` (servers, players, settings, confirm), `gui.close`, `gui.refresh`, `gui.back`. | Covered for open, close, refresh and back. `gui.page` is authored on the list's arrows, but a one-page list never pages: `guilogic.roster-paged` (existing) covers it. `gui.slot_item` has no client entry. **Gap.** |
| Actions: economy, exp, permissions | `take_money` and `give_item` (shop). | Partial. `give_money`: the currency reads in `logic.currency-live-read-surfaces` (existing), but no client run gives money. **Gap.** `take_exp` and `give_exp`: `logic.exp-give-take` (existing). `give_permission`: `logic.conditions-permission` (existing). |
| Actions: world | `connect` (servers; the failure path, because the probe has no transfer). `give_item` (shop). | Partial. `teleport`: `logic.teleport` (existing). `take_item`: `logic.items-take-give` (existing). `potion`: **gap**. |
| Delay and chance | Not in the showcase. | **Gap** (unit-tested). |
| Per-player data | `showcase:show_tips` (player boolean, settings and confirm); `showcase:stock` (server number, shop); `session.search` (players). | Covered for player, server and session scopes. Local variables are not in the showcase; `variables.*` (existing) cover the scopes. |
| Placeholders out | `${…}` templates in every GUI. `${server.online}` on servers. | Partial. The PlaceholderAPI `%materione_var_*%` expansion has no client entry. `logic.currency-xp-placeholders` (existing) covers currency tokens. **Gap.** |
| Text input | `prompt` on the players search (`showcase.players-search`, chat answers "pro" and "zzz"). | Covered for chat. `guilogic.chat-prompt-cancel` (existing) covers cancel. Sign and anvil: sign is unavailable on 1.21.4; anvil has no entry. **Gap.** |
| Items players put into the menu | Not in the showcase. | Covered by `guilogic.input-slots` and `guioverlay.input-ownership` (existing). |
| Inventory lock | Not in the showcase. The existing trade GUI uses `player_inventory: free`. | **Gap**: `locked` has no client entry. |
| Pagination, lists | `online_players` list with `sort: name`, `search`, and the "Page x of y" indicator (players). | Partial. Search, sort and the indicator are covered (`showcase.players-search`). Paging with more than one page: `guilogic.roster-paged` (existing). |
| Admin commands | `/materione var get|set` in every showcase entry; `/materione gui open` (existing). | Covered. |
| Extension API | Not demonstrated: the showcase uses built-ins only. | **Gap** at client level. Registries are unit-tested (`BuiltinLogic`, `LogicServiceTest`). |

**Gaps, collected.** The client-level gaps are: a dynamic title on the chest; the `open.permission` gate; `gui.tick` refresh counts; Materione item
forms and item meta beyond name and lore; `has_money` and `has_item_space` as conditions; `minimum_requirements`; `as: player` and
`as: op`; `gui.slot_item`; `give_money` in a live run; `potion`; the delay and chance options; the PlaceholderAPI variable
expansion; sign and anvil input; the `locked` inventory; and the extension API. Each of these is either covered by an existing
entry named above, or unit-tested, or both. None is faked by a showcase GUI.

---

## 2. The flow model

### 2.1 Rules stay; their body becomes a flow

A rule keeps its M2 shape (runtime-systems §2.1). Its `do:` list becomes a **flow**: an ordered
list of steps. A step is either an action (as today) or a control step. Every rule that loads
today means the same thing after this change, because a flat action list is a flow with no
control steps.

```yaml
behavior:
  - on: gui.click
    layer: buy                       # trigger filter (§6.2)
    click: [left, shift_left]
    id: buy_sword                    # existing rule option
    cooldown: 500
    do:
      - if:
          all:
            - has_money: 250
            - has_item_space: 1
        then:
          - take_money: 250
          - give_item: { item: shop:gui/main/sword, amount: 1 }
          - sound: entity.player.levelup
          - message: "<green>Bought for $250. Balance: %vault_eco_balance%"
        else:
          - message: "<red>You need $250."
          - sound: { sound: block.note_block.bass, pitch: 0.5 }
      - gui.refresh
```

### 2.2 Step kinds (engine-owned, closed set)

The step set is closed: control flow belongs to the engine, and only actions, conditions and
triggers are open registries. A small, fixed grammar is what makes the graph projection (§7.2)
total and lossless.

| Step | Shape | Meaning |
|---|---|---|
| action | `<action-id>: <scalar>` or `<action-id>: {params}`, plus optional `chance:`, `delay:` and `node:` | Runs a registered action (§4.1). It reports `ok` or `failed`, and a failure never aborts the flow unless the action is wrapped in `require` (below). |
| `if` | `if: <condition>`, `then: [steps]`, `else: [steps]` | A branch. Execution continues after the step once the chosen branch ends. |
| `require` | `require: [steps]`, `else: [steps]` | Runs the steps in order until one **fails**. On a failure it runs `else` and **stops the flow**. This is the atomic-paywall shape (`take_money` then `give_item`), and it does not need a separate `if` check. |
| `wait` | `wait: <ticks>` or `wait: {ticks}` | Suspends the run (§5.2). It never blocks a thread. |
| `stop` | `stop` | Ends this run. With `cancel: true` it also cancels the underlying event. |
| `call` | `call: <flow-id>` | Runs a named flow from the same resource (§2.4) inline. Cycles are a compile error. |
| `choose` | `choose: [{if, then}...]`, `else: [steps]` | Runs the first branch whose condition holds. It replaces long `if/else` chains, which matters for priorities. |

There is no loop step in v1. Repetition comes from triggers (`gui.tick`) and lists (§6.7), not
from authored loops, so a flow cannot spin. The run budget (§5.3) is the second guard.

### 2.3 Conditions

A condition is one of these:

- **An expression string.** The one expression engine (runtime-systems §2.1) evaluates it, for
  example `"${player.level >= 10 && vars.player.rank == 'gold'}"`. The engine gains function calls
  (§4.2), which cover DeluxeMenus' string and regex requirements and CommandPanels' `$EQUALS` and
  `$ATLEAST`.
- **A registered predicate.** `has_permission: x.y`, `has_money: 250`, `has_item: {...}` and so on
  (§4.2).
- **A composite.** `all: [...]`, `any: [...]`, `not: <cond>`, and `at_least: {count: 2, of: [...]}`.
  The last one is DeluxeMenus' `minimum_requirements`, and it evaluates lazily, which gives
  `stop_at_success` for free.

Conditions compile once at load. An unknown predicate or a malformed expression skips the
**step** that holds it (diagnostic), and the rest of the flow still loads.

### 2.4 Named flows

A resource may declare `flows: {<id>: [steps]}` beside `behavior:`. `call: <id>` runs one inline.
This is the reuse unit: a shop GUI's "purchase" flow is written once and called by every product
button with different `with:` arguments (`call: {flow: purchase, with: {price: 250, item: ...}}`).
The arguments arrive as `args.*` in the callee. A named flow can only be called from inside the
same resource in v1. Cross-resource libraries are a later extension; the `call` syntax already
accepts a namespaced id.

### 2.5 Values: templates, not data wires

Every parameter value is a **template**: a literal, `${expression}` (the existing `Template`), or a
PlaceholderAPI `%token%`. There are no data edges between nodes. Values are read from **context
variables** (§2.6) and written to variables with `set_var` (§3). This is the deliberate shape of the
node editor: edges carry execution order only, and values are typed fields with autocomplete. The
alternative, dataflow wires (Blueprint-style), multiplies node count for the trivial expressions
menu authors write ("balance ≥ price"). It also has no readable YAML form, which D1 forbids.

### 2.6 Context variables are declared by triggers

Each trigger type declares the variables it provides, as a typed schema in its self-description
(§4.3). Examples are `gui.id`, `gui.args.*`, `click.type`, `click.slot`, `layer.id`, `entry.*`
(list entries), `slot.item`, and `player.*` and `server.*` (always present). The compiler checks
`${...}` paths against the trigger's schema and warns about unknown roots. The studio uses the same
schema to autocomplete. One schema serves both checking and editing (D5).

---

## 3. Variables

### 3.1 Scopes

| Scope | Lifetime | Storage | Incumbent equivalent |
|---|---|---|---|
| `local` | one run (today's `vars`) | in memory | n/a |
| `session` | one open GUI (survives refresh, `gui.back` restores it) | `GuiSession` (§6) | CommandPanels session data |
| `player` | permanent, per player | player PDC, `materione:var.<pack>.<key>` | DeluxeMenus meta, CommandPanels data |
| `server` | permanent, server-wide | `storage/variables.json` (runtime state, not content: master §8.3) | CommandPanels data (global use) |
| `item` | permanent, with the item stack the run is about (§38) | the stack's own data (PDC), `materione:var.<pack>.<key>` | n/a (the benchmark's Bloodfang sword, T01) |

Cross-server variables use the storage interface of master §8.3. Adapters are #25, and nothing in
this doc assumes a single server beyond the default adapter.

### 3.2 Declared, typed variables

`player`, `server` and `item` variables are **declared** in a pack's `variables/` content type:

```yaml
# content/packs/shop/variables/shop.yml
coins:      { scope: player, type: number, default: 0, description: "Shop coins" }
daily_claimed: { scope: player, type: boolean, default: false }
season:     { scope: server, type: string,  default: "spring" }
level:      { scope: item,   type: number,  default: 1, description: "Weapon level, held by the sword stack" }
```

Declaring them is what makes three things possible: type checks at compile time (`set_var` of a
string into a number variable is a diagnostic), studio autocomplete and a Variables panel, and safe
defaults when a value is read before any write. `local` and `session` variables need no
declaration. A write to an undeclared persistent variable is a compile error: a typo must never
create a second key silently. The key is pack-namespaced (`shop:coins`), and a bare name resolves
within the resource's own pack.

### 3.3 Reading and writing

- **Actions.** `set_var: {var, op, value}`, where `op` is one of `set`, `add`, `subtract`,
  `multiply`, `toggle`, `remove` or `reset`. `value` is a template. Arithmetic on a non-number is a
  failed action, not a crash.
- **Expressions.** `vars.local.x`, `vars.session.shop.page`, `vars.player.shop.coins`,
  `vars.server.shop.season` and `vars.item.rpg.level` (the item stack the run is about, §38). Bare `vars.x` keeps meaning local, so M2 behaviour is unchanged.
- **PlaceholderAPI.** The expansion provides `%materione_var_<pack>_<key>%`,
  `%materione_var_<pack>_<key>_<player>%`, `%materione_gui_open%` and `%materione_gui_last%`.
- **Admin.** `/materione var get|set|list <player> [<var>] [<value>]`, permission
  `materione.var`.
- **API.** `MaterioneAPI.logic().variables()` (§8).

---

## 4. Extension SPIs (`dev.materione.api.logic`)

Every vocabulary item is a registry entry implemented against the public API. The built-ins
register exactly the way an addon does, as the mechanics registry already proves (mechanics.md).

> **As built (#207).** The signatures below are the design sketch. The shipped contracts are in
> `dev.materione.api.logic` and the choices that differ in detail (`threading()` as a method on the
> type, `availability()`, `ExpressionCompiler`, the registry facades) are recorded in §14.

### 4.1 Actions

```java
public interface ActionType<P> {
    String id();                                   // "take_money", "gui.open"
    ActionDescription describe();                  // SelfDescription + category + shorthand + threading
    P parse(ParamReader params);                   // pure, at load; IllegalArgumentException rejects the step
    ActionOutcome execute(P params, LogicContext ctx);   // OK | FAILED(reason)
}
```

- **Parse once, run many.** Parameters become a typed, immutable `P` at load time. Errors surface
  as diagnostics on apply, not at click time. Today's executors re-read raw maps on every run; that
  code is replaced.
- **Outcome.** `FAILED` feeds `require` (§2.2) and the trace (§7.6). An exception counts as
  `FAILED` and also emits a rate-limited diagnostic (runtime-systems §2.1).
- **Threading.** Each action declares `PLAYER_REGION`, `GLOBAL` or `ANY`, and the interpreter
  dispatches through the thread gateway (plugin.md §2). This replaces today's boolean `syncSafe`.
- **Targets.** An action that can act on another player or entity declares `targetable` in its description; the engine then
  parses its `target:` once at load and runs it on each resolved subject (§39). An action never resolves or parses a target.
- **Category.** One of `player`, `gui`, `world`, `server`, `economy`, `data`, `flow` or
  `integration`. Addons may add categories. The node editor colours by category (§7.3).

### 4.2 Conditions

```java
public interface ConditionType<P> {
    String id();                                   // "has_money"
    ConditionDescription describe();               // SelfDescription + category + shorthand
    P parse(ParamReader params);
    boolean test(P params, LogicContext ctx);      // pure read; must not mutate the world
}
```

- **v1 built-ins.** `has_permission` (one, or many with `min:`), `has_money`, `has_exp` (points or
  levels), `has_item` (a matcher: item id or material, amount, name or lore contains, ignore case,
  strict, armor or offhand, model), `has_item_space`, `is_near`, `in_world`, `in_gui`, `var` (typed
  compare on a declared variable, so non-programmers avoid expressions) and `slot_has` (an input
  slot's contents, §6.5).
- **Expression functions.** The engine gains calls. `contains`, `equals_ignore_case`,
  `starts_with`, `matches`, `length`, `lower`, `upper`, `is_number`, `is_player`, `number`,
  `round`, `min`, `max`, `random` and `format_number` cover DeluxeMenus' string, regex, length and
  is-object requirements, and CommandPanels' `random`. Functions are a registry too
  (`ExpressionFunction`), so addons can add them. The calling syntax, the parse-time binding and
  the full built-in list are in §15 (#209).

### 4.3 Triggers

```java
public interface TriggerType {
    String id();                                   // "gui.click"
    TriggerDescription describe();                 // SelfDescription + firedBy + filters + context schema + cancellable
}
```

Triggers are **descriptions**. The runtime that fires one, such as the GUI runtime or the item
listener, constructs the `LogicContext` and asks the engine to dispatch. Today's `EventTypes`
table becomes the built-in trigger registrations. `describe()` declares:

- the **filters** a rule may set (`layer`, `click`, `slot`, `every`);
- the **context schema** (§2.6);
- whether the trigger is **cancellable** (`gui.opening` is; `gui.close` is not).

A trigger may name the hosts that fire it in `firedBy` (`item`, `block`, `furniture`, `gui`, and since #327 `rule`, a document that holds rules and no resource). The six world triggers (§37) are the first to be fired by a world event with no host in the way.

### 4.4 Item sources

> **As built (#216).** The SPI is `dev.materione.api.logic.ItemSource`, registered through
> `LogicApi.itemSources()` (`RegistryKeys.ITEM_SOURCES`). The choices are in §24.

`ItemSource` is the SPI behind every place a GUI names an item. It parses a source string or map
at load, and resolves it to an `ItemStack` at render time against the context. The built-ins are:

- Materione ids;
- `minecraft:` materials;
- `head: {player | texture | base64}`;
- `player_slot: main_hand | off_hand | helmet | ...`;
- templated ids, such as `${...}` that evaluates to an id.

Integrations register `hdb:`, `itemsadder:`, `nexo:` and `mmoitems:` sources from the
`integrations` module when those plugins are present. An unresolvable source renders a placeholder
barrier item that carries the reason in its lore (fail-soft and visible), never air.

### 4.5 Provider SPIs (integrations)

| SPI | Used by | Default | Integration |
|---|---|---|---|
| `CurrencyCatalog` (named currencies, §18: each reaches its money through a `CurrencyIntegration`) | `has_money`, `give_money`, `take_money`, `balance()`, `format_currency()` | `materione:money`, bound to Vault; unavailable (with the reason) until Vault's economy runs | Vault (`vault`), the XP integration (`xp`), the command pair (`custom`); addons register more |
| `PermissionProvider` (grant, revoke, temporary grant) | `give_permission`, `take_permission`, `run_command as: op` | Bukkit attachments, for the session only | Vault, LuckPerms |
| `ServerTransfer` | `connect` | the 1.20.5+ transfer packet when configured with a host | BungeeCord or Velocity plugin messaging |
| `InputMethod` | `prompt` (§6.6) | chat, anvil; sign registered but unavailable on this build (§29) | dialogs (#6) |
| `ListSource` | the `list` component (§6.7) | online players, a pack's items, the content tree (§35), a variable list, static `entries:` | addon sources |

Providers are resolved at use, not cached at load, so installing Vault later only needs a reload. Money
is the one provider kind that is content rather than a slot: a currency file names its integration, and
the integrations are a registry (§18).
Availability is part of the self-description (`available: false, reason`), which lets the studio
grey out what the server cannot run.

### 4.6 Recipes

A **recipe** is a parameterized flow fragment: id, name, description, parameters and a step
template. Recipes are registered as data, shipped as YAML resources in the plugin jar, and addons
may add more. The studio's Recipes panel (§7.5) and the API's `instantiate` call (§8) expand a
recipe server-side into ordinary steps. A recipe never survives into the file as a recipe: once
expanded, the steps are the truth (D1).

---

## 5. The engine (module `behavior`)

### 5.1 Compilation

`FlowCompiler` turns a `behavior:`/`flows:` section into `CompiledRule`s that hold a sealed `Step`
tree:

- `ActionStep(ActionType, P, chance, delay)`
- `IfStep`
- `RequireStep`
- `ChooseStep`
- `WaitStep`
- `StopStep`
- `CallStep(resolved)`

Conditions compile to a sealed `Condition` tree (`ExpressionCond`, `PredicateCond`, `AllCond`,
`AnyCond`, `NotCond`, `AtLeastCond`). Compilation is pure and runs in the pipeline. Each problem is
a diagnostic that carries the **step path** (`behavior.2.do.0.then.1`), so the studio and
`validate` can point at a node.

### 5.2 Interpretation: runs are resumable state machines

`FlowInterpreter` executes a `FlowRun`: an explicit frame stack of (step list, index) entries, not
Java recursion. That one decision gives four properties:

- **Suspension.** `wait`, an action's `delay:` and `prompt` (§6.6) park the run (frame stack plus
  context) and resume it later on the right thread, without holding a thread or nesting
  callbacks.
- **Bounded cost.** Every step increments a counter that the budget checks (§5.3).
- **Tracing.** Every step boundary is an observable event (§7.6).
- **Cancellation.** A run owned by a GUI session can be cancelled cleanly, and its pending
  `wait`s dropped.

Runs belong to the **player**, not to the window. A flow that says `gui.close`, then `wait: 40`,
then `message` still sends the message after the close; DeluxeMenus behaves the same way. GUI
actions in a run whose session has ended are skipped with outcome `FAILED("no open gui")`.
`stop: {cancel_session_runs: true}` lets an author opt into the stricter behaviour.

### 5.3 Budgets and guards

| Guard | Default | On breach |
|---|---|---|
| Steps per run | 1,000 | The run is aborted with a diagnostic, and the flow is flagged in `/materione diagnostics`. |
| Parked runs per player | 64 | The new run is refused with a diagnostic. |
| Call depth | 16, with cycles rejected at compile | Compile error. |
| Click-to-first-action latency | ≤ 0.5 ms p99, excluding the actions' own work | perf test (plugin.md §9) |

### 5.4 Migration of the M2 engine

- `ActionExecutor`, `ActionSpec` and `RuleEngine.execute` are replaced by `ActionType` and the
  interpreter.
- The six built-in executors become `ActionType`s, in their own classes.
- `BehaviorVocabulary` grows the following: conditions, triggers (with filters and context
  schema), step kinds, categories, expression functions, recipes and provider availability.
  Since #355 the grammar is data there too, additively: each step kind says which parameter is the value of its own key and
  which are keys beside it, a parameter says when it holds steps, a condition or a flow name, when it accepts an
  expression and when it takes a list; `conditionForms`, `ruleBody` and `guardOptions` describe the composite conditions,
  the flow keys of a rule and a guarding action's `else` (codemode.md §3.8). `StepKinds` is the one table: the step
  compiler reads its control keys from it.
- Item, block and furniture rules run through the interpreter unchanged. The golden behaviour
  tests and the `behavior.*` QA catalog entries are the regression net, and they must pass
  untouched.
- **Status.** #207 did the first half: `ActionExecutor` and `ActionSpec` are gone, the six built-ins
  are `ActionType`s in `plugin.logic.action`, and the vocabulary is version 2. #208 did the second:
  `BoundAction` and the flat action loop are replaced by the sealed step tree and the resumable
  interpreter, `RuleEngine` is now only the rule gate in front of it, and the seven step kinds are all
  `supported` (§16).

---

## 6. GUI runtime

### 6.1 Opening

```yaml
shop:
  container: chest_6
  open:
    commands: [shop, store]            # Brigadier, registered at enable and re-synced on reload
    args:
      - { name: category, type: string, default: all, suggest: [all, tools, food] }
      - { name: target, type: player, optional: true }
    permission: shop.use               # optional; refused with MTR-0055 (gui.md §10)
    requirement: { has_permission: shop.unlocked }
    deny:
      - message: "<red>Unlock the shop first."
  layers: [...]
  behavior: [...]
  flows: {...}
```

`GuiOpener` is the one path. Every way in goes through it: commands, the `gui.open` action,
`/materione gui open`, `RuntimeApi`, and the editor preview. The path has eight stages:

1. **Resolve** the GUI from the live view.
2. **Check `open.permission`.**
3. **Parse and validate `args`.** Argument types are `string`, `int`, `number`, `player`,
   `boolean` and `choice`. A failure sends a usage message that is generated from the schema.
4. **Evaluate `open.requirement`.** When it fails, run `deny`. The bypass permission is
   `materione.gui.bypass.<pack>.<id>`. A known unavailable window capability also refuses here,
   before the previous session or its input ownership is changed.
5. **Fire `gui.opening`.** The event is cancellable, so a flow may `stop: {cancel: true}`.
6. **Create the `GuiSession`.**
7. **Render (§6.3) and open.**
8. **Fire `gui.open`.**

An unexpected immediate host failure returns `FAILED`; if it follows replacement, the retired
window is closed only while it is still the viewer's window. Deferred failures perform the same
session-identity check. A failed shape-reload reopen ends its session, returns inputs once and
closes that session's still-visible window; a stale failure cannot close a newer window, even
within the same session. Rewrites, shape changes and unsupported-title closes requested during
an inventory transaction coalesce; at execution the live resolver checks the owned session and
window again and chooses the action from the current published GUI, including removal.

**Command registration.** Commands register through Paper's Brigadier lifecycle and are re-synced
to clients after a reload. An argument mismatch must not reproduce #96 (the client's synced tree
rejecting typed arguments), and §13 assigns that check explicitly.

### 6.2 Triggers fired by the GUI runtime

| Trigger | Filters | Context (beyond player and server) | Cancellable |
|---|---|---|---|
| `gui.opening` | n/a | `gui.*` | yes (the open does not happen) |
| `gui.open` | n/a | `gui.*` | no |
| `gui.close` | `reason: player\|action\|replaced\|quit` | `gui.*`, `close.reason` | no |
| `gui.click` | `layer`, `slot`, `click` | `gui.*`, `click.type`, `click.slot`, `layer.id`, `entry.*` (lists) | the click is always cancelled except on input slots |
| `gui.click_outside` | `click` | `gui.*`, `click.type` | n/a |
| `gui.click_inventory` | `click` | `gui.*`, `click.*`, `slot.item` (the player's item) | yes |
| `gui.slot_change` | `layer` (an input-slots layer) | `gui.*`, `slot.index`, `slot.item`, `slot.previous` | yes (reverts the change) |
| `gui.tick` | `every` (ticks, ≥ 1) | `gui.*` | n/a |
| `gui.page` | `layer` (a list) | `gui.*`, `page.index`, `page.count` | n/a |

`gui.*` is `gui.id`, `gui.args.*`, `gui.session.*` (session variables) and `gui.page.<list>`. The
click kinds are `left`, `right`, `shift_left`, `shift_right`, `middle`, `double`, `drop`,
`ctrl_drop`, `number_key` (with `click.key`) and `swap_offhand`. They are the union of both
incumbents plus everything Bukkit reports.

**Click mapping is compiled.** `GuiCompiler` emits a `ClickMap`: for each slot, the ordered list of
layers whose footprint covers it. A layer covers a slot when the slot's 16×16 interior centre
falls inside the layer's rectangle (buttons, text, images), or when the slot is in its slot set
(item, slots, list). At click time the router takes the topmost **visible** layer that has a
`gui.click` rule matching the click kind. One map lookup per click, no geometry at runtime. As built
(#214, §21): a rule with no `layer:` filter answers a click that no layer's rule claimed.

### 6.3 Dynamic content

- **Visibility.** `visible_if: <condition>` is a common layer field, like `visible`. Within a slot,
  the topmost visible layer wins. That covers DeluxeMenus' priority and CommandPanels' first-match
  order, and layer order *is* priority, so authors see it.
- **Templated item overrides.** On item layers, `name`, `lore` (`lore_mode: replace|append|prepend`),
  `amount` and `glint` are templates, and `item:` is an item source (§4.4).
- **Animation.** `frames: {every: <ticks>, items: [sources...]}` on item layers;
  raster layers opt in with `materione:overlay_frames: {every: <ticks>, layers: [component-field patches...]}` (§6.4).
- **Dynamic detection.** A layer is *dynamic* when it has `visible_if`, a template, a templated
  source or `frames`. The compiler records this, and refresh re-evaluates **only** dynamic layers.
- **`refresh: <ticks>`** on the GUI sets the clock (default off; `gui.refresh` forces a render). A write to a declared variable a
  dynamic layer or the dynamic title reads re-renders the window on its own, at the next tick, unless the file says
  `rerender: off` (views.md §4.2, §5.5, §17.8): the clock is then needed only for polled reads and animations.
- **The render model.** `SlotRenderer` produces the session's `RenderedSlots`. `RenderDiff` writes
  only changed slots, which keeps a 54-slot, 20-tick refresh within budget.

### 6.4 Conditional raster and dynamic title text

Static-only GUIs retain their flattened glyph (gui.md §4). When a GUI has conditional or
animated raster or conditional or templated text, its background glyph is transparent and
every visible raster layer compiles to its **own overlay glyph**, with a separate glyph per
frame. The title assembler includes or omits each piece per render, raster first and then text,
retaining authored order within each target. Static raster pieces also remain individual so
their order relative to conditional raster pieces is preserved.

- A changed rich title is sent to the existing native server menu; unchanged titles are not sent.
  No Bukkit inventory open or close is performed for that refresh.
- When a non-structural refresh would change the title while the cursor holds an item, the
  entire displayed render, its definition (including click and input rules), hitboxes and input
  ownership remain frozen together. An owned retry checks only ownership and cursor while held, and
  resolves the latest live GUI and state after the cursor clears, even without a refresh clock;
  intermediate frames are not replayed. The window clock keeps advancing. Item-only updates
  whose title is unchanged still apply when no title change is pending; once pending, the
  whole presentation stays frozen until release. Structural reopens retain their existing policy.
- The cost is one codepoint per raster frame in a dynamic GUI, plus its transparent background;
  the block has 5,888 codepoints
  (`U+E200`–`U+F8FF`).
- This gives tabs, toggles, selected states and progress art, which neither incumbent can do.

Text layers with templates, and conditional text, are re-laid out per render through the same
title assembler. The default-font width table (#202) makes arbitrary text measurable.

**Paper API research and native verification.** Paper's
[`InventoryView#setTitle(String)` on 1.21.4](https://github.com/PaperMC/Paper/blob/73116ac9dd1d55c59db411a55de7e91e025a0c80/paper-api/src/main/java/org/bukkit/inventory/InventoryView.java)
and [26.1.2](https://github.com/PaperMC/Paper/blob/e4e17fc90d31c3dca6de8bebc87c741749f8f3df/paper-api/src/main/java/org/bukkit/inventory/InventoryView.java)
is deprecated as unsupported and accepts a legacy string, not an Adventure component. The
[1.21.4 implementation](https://github.com/PaperMC/Paper/blob/73116ac9dd1d55c59db411a55de7e91e025a0c80/paper-server/src/main/java/org/bukkit/craftbukkit/inventory/CraftInventoryView.java)
sends an open-screen packet then calls `updateInventory`; the
[26.1.2 implementation](https://github.com/PaperMC/Paper/blob/e4e17fc90d31c3dca6de8bebc87c741749f8f3df/paper-server/src/main/java/org/bukkit/craftbukkit/inventory/CraftInventoryView.java)
sends that packet then `sendAllDataToRemote`. Neither API can represent the custom font
components required by this GUI title. The owner selected a pinned platform transport instead:
for 1.21.4 and 26.1.2 it resolves Paper's native packet and Adventure bridge signatures, sends the
component with the current server menu ID, and resynchronizes its contents and cursor. An
unsupported version or signature refuses dynamic-visual opens (`MTR-0073`); a failure during
refresh closes the window through the normal return path rather than continuing with a stale title
and newer click policy. There is no silent reopen fallback. The opt-in 1.21.4 protocol-client
test checks the native server menu and session, rich title packets, cursor and input conservation,
and absence of close packets; it does not test client rendering or pointer gestures.

On a vanilla 26.1.2 client, screenshots showed conditional overlays and a physical shift-click
transferred the input items. The open-screen packet reconstructed the **client** screen and menu
while retaining the server menu and session. A physical quick-craft drag held across an animation
update was canceled by that reconstruction before the held-cursor deferral policy above was
added; the observed items remained on the cursor. Deferral avoids sending a changed title while
the server sees a held cursor, but does not promise atomic delivery, protect every structural
reopen or establish that all drag interruptions and flicker are absent.

The decisions taken in #217 are recorded in §27.

### 6.5 Input slots and the player's inventory

- **Input slots.** A `slots` layer may set `input: true` (with an optional `accept: <condition>` on
  `slot.item`). Its slots accept the player's items, and moves into or out of them fire
  `gui.slot_change`. It may also set `durable: required | best_effort` (default `best_effort`; input layers only;
  [`gui-input-durability.md`](gui-input-durability.md) §10). **Row P of #247 (#310) makes `required` do something:** every click
  that touches such a slot is applied by the plugin itself, in one step with the player's escrow account (single-slot
  place, pickup, swap, number key, offhand swap, shift, drag, double-click collect; a pickup lifts the stack to the cursor
  as in vanilla and the account loses it in the same step), and nothing is ever dropped in the world for it (what does not
  fit is *owed*, `/materione claim`). On a server that cannot support it (player-data saving off, `storage/escrow/` not
  writable, no ledger) the layer renders and its slots accept nothing (`MTR-0100`). The
  player path is verified by K1–K23, and keyed/stack persistence plus joint transfer have
  their own holder gates; `best_effort` remains the §25.12 journal, unchanged.
  A strict layer can also name `holder:`. Omitted/`player` is exactly the account above;
  `keyed` parks entries in a canonical player-PDC account that outlives the window; and
  `stack` parks them in the opening view's stable stack PDC while the `StackRef` is live.
  Stack consume/HAND_OFF is refused: stack PDC has no acknowledged surrounding save.
- **Returning items.** On close, items left in input slots go back to the player; for a `best_effort` layer what does not
  fit drops at the player's feet, for a `durable: required` layer it stays owed in the player's account and is claimed with
  `/materione claim` (on join and on the next close too). Nothing is ever deleted unless a flow says so
  (`gui.slot_item: {slot, op: clear}`, or `custody.consume`). On a `required` layer both are the same atomic **consume**
  (#248, §36): the stack leaves custody through the tier-2 exit (an INTENT in the ledger, then the deletion), so a deletion
  outlives a rollback; `clear` and `set_amount 0` there start it and do not wait, `custody.consume` waits for the decision
  and, with `await: saved`, for a saved snapshot, so an effect after it cannot outlive a rolled-back consume.
- **The trash recipe.** "Trash slot" is a **composition** (D12), not an engine feature: the built-in recipe `trash_slot`
  (§7.5, §36) is a rule on `gui.slot_change` whose flow waits one tick and runs `custody.consume` on the slot that changed.
- **`player_inventory: locked | free`** (default `free`, as today, gui.md §5). `locked` cancels
  every bottom-inventory click and fires `gui.click_inventory`.
- **Click policy.** The policy table (`GuiListener.route`, gui.md §5) gains the input-slot rows:
  shift-move and double-click collection are allowed into and out of input slots and denied
  elsewhere.

### 6.6 Prompts (text input)

`prompt: {method: chat|sign|anvil|dialog, text, var, timeout, cancel_word}` parks the run (§5.2),
asks through the chosen `InputMethod`, writes the answer to `var` (session or local), and resumes.
A timeout or cancel resumes into the step's `else:` branch. The GUI is re-opened afterwards when
the prompt had to close it (chat, sign). Search in lists is this plus a filter (§6.7).

As built (#220): the decisions, the diagnostics (`MTR-0081` to `MTR-0084`) and the search key are in §29.

### 6.7 Lists and pagination

`list` is a component with target `SLOT`:

```yaml
- id: players
  type: list
  slots: { from: 10, to: 34, skip_columns: [0, 8] }   # or an explicit slot list
  source: online_players                               # ListSource SPI (§4.5)
  sort: name                                           # filter, then sort, then page (§28)
  filter: "${entry.level > 1}"                         # a condition over entry.*
  entry:                                               # per-entry item template, entry.* in scope
    item: { head: { player: "${entry.name}" } }
    name: "<yellow>${entry.name}"
    lore: ["<gray>Click to teleport"]
```

- **State.** Page state is per session per list (`gui.page.<list>`: `number`, `count` and, since #8, `size`, the entries after search and filter).
- **Paging actions.** `gui.page: {list, to: next|previous|first|last|<n>}`. It fires `gui.page`, once per
  page change.
- **Context.** Clicks on a list slot carry `entry.*` in context, so a single rule handles every
  entry.
- **Ordering.** Sources are filtered and sorted **before** paging, and page count is exposed for
  "Page 2/5" text.
- **Large sources.** A source that is expensive (for example all items of all packs) declares
  itself `cached`, and the session snapshots it on open and on `gui.refresh`, not on every tick.

As built (#219): the decisions, the diagnostics (`MTR-0077` to `MTR-0080`) and the seams for search (#220) are
in §28. The search key is built (#220, §29). The content browser (#8, §35) is a GUI built from a `list` over the
`content` source, `prompt`, `gui.open` and `gui.back`: the worked example of this section.

---

## 7. The studio's Logic tab

### 7.1 Three views of one file

The GUI studio gains **Design**, **Logic** and **Script** tabs. All three edit the same resource
through the same authoring API (§8):

- **Design** is gui.md §6.
- **Logic** is the node canvas.
- **Script** is the resource's `behavior:`, `flows:` and `open:` YAML, with live validation.

There is no editor-only model (D1). The undo history is shared across tabs.

*As built (#225, §33):* the three tabs are Design, Logic and Script. A Script edit is a history command like a canvas gesture, so
Ctrl+Z from any tab undoes it, and the dirty marker and Save are shared; `behavior:` and `flows:` reach the file as `logic.edit`s and
`open:` with the layers' own save. Text that does not parse is kept as a draft and never saved (§33 decisions 8 to 14).

### 7.2 Graph projection (lossless)

The canvas is a deterministic projection of the rules:

| File | Graph |
|---|---|
| a rule | a **trigger node** (filters as fields) with an `exec` output |
| an action step | an **action node**, with `exec in` and `exec out` |
| `if` | a **branch node**, with `then`, `else` and `after` outputs |
| `require` | a **guard node**, with `steps`, `failed` and `after` outputs |
| `choose` | a **switch node**, with one output per case, plus `else` and `after` |
| `wait` / `stop` / `call` | **flow nodes** (`call` links to the named flow's entry) |
| a named flow | a **flow-entry node** |
| a condition | **inline in the branch node**, edited as a small condition stack; a composite expands in place |

Each node input accepts exactly one edge, and each output emits at most one edge. Branches rejoin
through their `after` port, which keeps the graph structured, and structure is what makes the YAML
readable and the projection lossless. The canvas therefore refuses an edge that would create a
join or a cycle, with a one-line explanation. Fan-out from one port, as in some node tools, is not
allowed: its execution order would be invisible. "Run these in parallel" has no meaning on one
player's thread anyway.

### 7.3 Look and feel

- **Colour by category.** The colour comes from the registry category (§4.1), with a fixed palette
  in the studio's tokens: trigger amber, player blue, gui purple, economy gold, data teal, world
  green, server slate and flow white.
- **Edges.** Exec edges are pixel-stepped "redstone" wires in the source port's colour.
- **Animation.** The current trace (§7.6, §32) animates the edges, and stands still under `prefers-reduced-motion`.
- **Theme.** Nodes use the studio's Minecraft-flavoured panels (gui.md §6). Field editors are typed
  from the self-description: item pickers reuse the item browser, sound pickers the sound catalog,
  and variables are a dropdown of declared variables. They live in the **inspector** beside the canvas
  (shipped in #224, §31).
- **Template fields.** A template field autocompletes `${` from the trigger's context schema (§2.6)
  and `%` from known PlaceholderAPI expansions (§31 decision 10: the ones Materione defines; the server does not
  publish the rest yet).
- **Palette.** The palette is grouped as Triggers, then the action categories, then Conditions,
  Flow and Recipes. It is searchable (`/`), and it is entirely derived from the vocabulary op
  (§8). *(As built, the palette lists nodes; the recipes are the Recipes panel of §7.5, which is where §33 put them.)* An unavailable provider greys out its actions with the reason.

### 7.4 Design ↔ Logic linkage

- **From Design.** Selecting a button, item or list layer offers "Add click logic". That creates a
  `gui.click` rule filtered to that layer and opens it in Logic.
- **On the canvas.** Trigger nodes show the layer's thumbnail and name, and hovering them
  highlights the layer on a mini preview.
- **In the layers panel.** A layer with logic shows a small bolt badge. Deleting a layer with
  rules asks whether to delete or detach them.
- **Across tabs.** Validation diagnostics carry step paths and are drawn on the node in Logic and
  on the layer in Design.

*As built (#225, §33 decisions 1 to 7):* the link is the rule's `layer:` filter, read from the file (nothing is stored). "Add click
logic" sits in a Logic section of the Design inspector for a button, an item or a list; a trigger node shows the layer's thumbnail and
name and, on hover or focus, a mini preview of the whole GUI with the layer outlined; a layer's row wears a bolt (a button that opens
the rule); a diagnostic is drawn on the layer's row and, with its step path, in the inspector; and deleting layers that have rules asks
whether to delete the rules or keep them without the layer filter, as one undo step. A rule shared with a layer that stays is never removed.

### 7.5 Recipes panel

Recipes (§4.6) are listed with a one-line description, and inserting one expands it server-side.
The v1 recipes are:

- Pay to receive
- Trash slot (a composition over `custody.consume`, shipped with #248; §36)
- Close button
- Welcome message
- Confirm dialog (a sub-GUI with yes and no)
- Paged player list
- Toggle setting (a player boolean variable with an on/off overlay)
- Server selector (`connect` plus an online-count placeholder)
- Back button

*As built (#225, §33 decisions 15 to 19):* the panel lists the vocabulary's recipes with a name and a line each, draws the chosen
recipe's parameters with the inspector's typed fields (a parameter that names a layer is a choice of the GUI's layers), has the
server expand it (`logic.instantiate`), shows the `MTR-0088` warnings and inserts the rules with one undo step. A recipe the server
cannot run here is greyed with its reason and can still be inserted ("Insert anyway"). The trash slot is listed since #248 (§36).

### 7.6 Live trace (debugging)

When the studio is connected and the author clicks in game, the run's trace (step paths and
outcomes) streams to the studio. It is opt-in per session and per GUI, rate-limited and capped.
Executed edges light up, and a failed action shows its reason on the node. Nothing in either
incumbent compares to this, and it falls out of §5.2's step-boundary events at little cost.

*As built (#226, §32):* `api.logic().traces().subscribe(filter, listener)` hears batches of entries (a step path, a kind, a
reason, a run id); the web ops `logic.trace.subscribe` and `logic.trace.unsubscribe` open and close a leased, capped stream of
`push.logic.trace` frames to the one editor session; and the Logic tab's "Live trace" toggle lights the executed wires and
nodes, dims what the run did not reach, puts a failure's reason on its node and says when the server dropped entries.

---

## 8. API and wire parity

Everything the studio does is an API call first (the #204 rule: web → api only, R9).

| Capability | Java API | Web op (capability `logic`) |
|---|---|---|
| The vocabulary: triggers, actions, conditions, steps, functions, categories, recipes, availability | `MaterioneAPI.logic().vocabulary()` | `logic.vocabulary` |
| Register an action, condition, trigger, function, item source, list source, input method or recipe | `MaterioneAPI.logic().actions()` etc. (registries, D5) | n/a |
| Edit flows on **any** resource with `behavior:` (gui, item, block, furniture): insert, patch, move or remove a step; add, remove or reorder a rule; set a node position | `AuthoringApi.logic().edit(actor, LogicEditRequest)` with sealed `LogicEdit` | `logic.edit` |
| Validate a draft flow against a trigger | `AuthoringApi.logic().validate(...)` | `logic.validate` (and `gui.validate` includes logic) |
| Expand a recipe | `AuthoringApi.logic().instantiate(recipe, params)` | `logic.instantiate` |
| GUI open spec, `visible_if`, `refresh` | `GuiEdit.SetOpen`, `PatchLayer` (existing) | `gui.edit` |
| Declared variables | the `variables` content type, through ordinary authoring | existing `authoring.*` |
| Read and write player, server or item variables (item: the stack in a player's main hand, §38) | `MaterioneAPI.logic().variables()` (needs `materione.var`) | `logic.variables.get/set` (admin-gated) |
| Open a GUI with args | `RuntimeApi.gui().open(player, id, args)` | `runtime.openGui` (gains `args`) |
| Live trace | `MaterioneAPI.logic().traces().subscribe(filter, listener)` | `logic.trace.subscribe` and `logic.trace.unsubscribe`, streaming `push.logic.trace` frames (§32) |

The `logic` capability gates every new op in `SessionBlob` and `editor-op-gates.json`, as `gui`
did (gui.md §10, decisions taken in #185). The editor-compat check stays green against every
committed release.

---

## 9. Code organisation

The failure mode to avoid is the 2,300-line `GuiListener`. These rules hold for every logic PR.
They are checked in review **and** by the build where possible.

```
api/     dev.materione.api.logic           ActionType, ConditionType, TriggerType, ExpressionFunction,
                                            ItemSource, ListSource, InputMethod, LogicContext,
                                            ContextFacet<T>, ParamReader, ActionOutcome, LogicApi, Recipe
api/     dev.materione.api.logic.provider  EconomyProvider, PermissionProvider, ServerTransfer
api/     dev.materione.api.logic.trace     (#226) LogicTraces, TraceFilter, TraceEntry, TraceKind, TraceBatch, TraceListener, TraceSubscription,
                                            TraceLimits
behavior/ dev.materione.behavior.logic     (#207) LogicLookup, RegistryLogicLookup, MapLogicContext, EngineExpressions,
                                            BuiltinTriggers, StepKinds, VocabularyBuilder, LogicIds
behavior/ dev.materione.behavior.flow      (#208) Step (sealed), Condition (sealed), FlowCompiler, StepPath, one record per
                                            step and condition kind; RuleCompiler, StepCompiler, ConditionCompiler, FlowSet
behavior/ dev.materione.behavior.run       (#208) FlowInterpreter (+ ControlSteps), FlowRun, Frame, RunBudget, TraceEvent,
                                            TraceSink, RunScheduler (port), RunRegistry, RunReporter
behavior/ dev.materione.behavior.run.trace (#226) TraceHub (the sink and the API), TraceQueue (the per-subscription limits)
behavior/ dev.materione.behavior.expr      + FunctionCall node, function registry binding
content/ dev.materione.content.gui.logic   (#214) ClickMap, ClickMapCompiler, GuiRules, GuiRuleChecker; (#215) OpenSpec;
                                            (#216) dynamic-layer analysis
content/ dev.materione.content.variable    VariablesType (the `variables` content type)
plugin/  dev.materione.plugin.logic        LogicService (wires registries ↔ interpreter ↔ scheduler), GatewayRunScheduler, LogicRegistryImpl,
                                            BuiltinLogic (the built-ins, registered through the public API)
plugin/  dev.materione.plugin.logic.trace     (#226) TracePump (installs the sink and ticks the hub while someone subscribes)
plugin/  dev.materione.plugin.logic.action    one class per action (≤ ~150 lines each)
plugin/  dev.materione.plugin.logic.support   shared action helpers (Quantity, ItemRef, InventoryPlan, Stacks); not actions (§17)
plugin/  dev.materione.plugin.logic.condition one class per condition
plugin/  dev.materione.plugin.logic.variable  PdcVariableStore, ServerVariableStore, VarCommand
plugin/  dev.materione.plugin.logic.action.gui (#214) OpenGuiAction, CloseGuiAction, BackGuiAction, RefreshGuiAction
plugin/  dev.materione.plugin.gui          (#214) GuiStack (the wiring), GuiBridge (how the actions reach it), GuiRun, Viewer,
                                            GuiLookup, GuiMenu, GuiRuntime (the API facade), RuntimeGuis, GuiListener
                                            (the click policy table)
plugin/  dev.materione.plugin.gui.session  (#214) GuiSession, GuiSessions, SessionHistory (back stack), CloseReason
plugin/  dev.materione.plugin.gui.open     (#214) GuiOpener, GuiLifecycle, OpenRequest, OpenResult, OpenGate; (#215) OpenSpecGate
plugin/  dev.materione.plugin.commands     (#215, moved by #467) the command system both `open:` and a `command` document use:
                                            OpenCommandRegistrar, OpenCommandHandler, OpenCommandTree, ArgParser, ArgType, OpenPlan
plugin/  dev.materione.plugin.gui.render   (#214) WindowRenderer; (#216) SlotRenderer, TitleRenderer, RenderDiff, RefreshClock
plugin/  dev.materione.plugin.gui.input    (#214) ClickKind, ClickClassifier, ClickRouter, GuiClick, GuiInput, InventoryInput,
                                            InputGuard, LayerVisibility, GuiWindowListener; (#218) SlotPolicy, InputSlots
plugin/  dev.materione.plugin.gui.event    (#214) GuiEvents, GuiContexts, RuleDispatcher
plugin/  dev.materione.plugin.gui.window   (#214) WindowHost, BukkitWindowHost
plugin/  dev.materione.plugin.gui.rules    (#214) GuiRuleSet, GuiRuleDiagnoser
plugin/  dev.materione.plugin.gui.live     (#214) GuiLiveRefresh
plugin/  dev.materione.plugin.gui.tick     (#214) GuiTicker
plugin/  dev.materione.plugin.gui.trigger  (#214) GuiTriggers
plugin/  dev.materione.plugin.gui.list     ListPager, built-in ListSources
plugin/  dev.materione.plugin.gui.prompt   ChatInput, SignInput, AnvilInput
integrations/ vault, luckperms, placeholderapi (expansion), headdatabase, …  (soft-depend adapters)
webui/   src/lib/logic/                    graph projection, layout, edit commands, vocabulary client
webui/   src/lib/logic/canvas/             node canvas, ports, wires, palette, inspector
webui/   src/lib/logic/trace.ts, trace.svelte.ts  (#226) the live trace: path to node, the fold of a stream, the toggle
```

Rules:

1. **Listeners are adapters.** A Bukkit listener converts the event into a normalized interaction
   (`GuiClick`, `GuiSlotChange` and so on), hands it to one collaborator, and returns. No
   listener holds policy, rendering or action logic. **Build check:** ban-test rule R10 (shipped with
   #214): classes in `plugin.gui..` and `plugin.logic..` that have an `@EventHandler` method may not
   depend on `plugin.logic.action..` or `plugin.gui.render..` directly.
2. **One action, one class; one condition, one class.** Each registers through the public API.
   Actions do not depend on each other. **Build check:** R11 forbids dependencies between classes
   in `plugin.logic.action..`.
3. **The engine is Bukkit-free.** `behavior` and `content` keep their existing bans (R1–R8). The
   interpreter talks to the world only through `LogicContext` facets and the `RunScheduler` port,
   so it is unit-tested without a server.
4. **Size guard.** A build test fails any main-source class over **400 lines** in the new packages
   above, as well as `content.gui.GuiType` and `GuiLayerParser` and its named helpers (#253).
   It does not impose a new limit on the rest of the legacy `content.gui` package.
   Splitting is cheaper than an exception. Generated and test code is exempt.
5. **Typed parse at load.** No action or condition reads raw maps at run time (§4.1).
6. **Every action and condition ships with** a self-description, a unit test of parse
   (accept and reject), a unit test of execute or test against a fake context, and, when
   player-visible, a QA catalog entry (CLAUDE.md).

---

## 10. Diagnostics

The implementing PRs assign codes from `MTR-0057` upwards and document them in plugin.md §5. #207
took `MTR-0057` (a step the registry or its type rejects); #208 took `MTR-0058` (a call that cannot be
linked), `MTR-0059` (a run budget) and `MTR-0060` (an unknown `${root}`); #214 needed no new code (§21);
#211 took `MTR-0063` to `MTR-0065` (§20); #215 took `MTR-0066` to `MTR-0069` (§23); #216 took `MTR-0070` to
`MTR-0073` (§24); #218 took `MTR-0074` to `MTR-0076` (§25); #219 took `MTR-0077` to `MTR-0080` (§28); #220 took
`MTR-0081` to `MTR-0084` (§29); #222 took `MTR-0085` to `MTR-0088` (§30); #226 took `MTR-0089` (§32), so the next free code is `MTR-0090`; #331 took none (§39); #329 took `MTR-0118` and `MTR-0119`, after the block `MTR-0106` to `MTR-0117` that the HUD and frame-cycling designs reserve (§41); #467 took `MTR-0125` (§40); #455 took `MTR-0126` (views.md §16, V0-3); #456 took `MTR-0127` (views.md §17, V1-10).
Each row below names the class of problem, and every one carries a step path where one applies.

| Problem | Severity | Effect |
|---|---|---|
| Unknown action, condition or trigger id; a parameter the type rejects | warning | That step, or that rule, is skipped. |
| A malformed expression, or an unknown `${root}` for the trigger | warning | The step is skipped (unknown root: warning only). |
| `call` of an unknown flow, or a call cycle | error | The rule is skipped. |
| A write to an undeclared persistent variable, or a type mismatch | error | The step is skipped. |
| A run budget exceeded | warning (rate-limited) | The run is aborted. |
| An action failed at run time (an exception) | warning (rate-limited) | Outcome `FAILED`, and the chain continues. |
| A provider is unavailable (no economy and so on) | info at load, warning at run | The action fails. |
| An open-command conflict with another plugin | warning | The alias is skipped; other aliases and `/materione gui open` still work. |

---

## 11. Deferred and how it plugs in

| Later | Seam |
|---|---|
| Dialogs (#6) | `dialog.submit` is a trigger, `dialog` is an `InputMethod`, and dialog buttons run flows. |
| HUDs (#7) | `hud.threshold` is a trigger, and `hud.set` is an action. |
| Non-chest containers | `container:` (gui.md §12). The hopper and the dispenser are built (rows P and S), and the furnaces, the anvil and the rest are later phases with a design each (gui.md §12.3, §12.11). v1 needs no click-kind mapping: the click kinds are Bukkit's and the v1 menus are plain slots. A container's own behaviour (rename, buttons, trades, progress) becomes triggers and components of that container when it is designed. |
| Cross-resource flow libraries | `call` already takes a namespaced id. |
| Loops | A `repeat` step with a hard iteration cap, if real menus need it. |
| Bedrock forms (#24) | A Geyser-side renderer of the same GUI and flows. |
| Importing incumbent menus (#17) | Map DeluxeMenus and CommandPanels YAML onto §1's constructs. The container mapping is fixed (gui.md §12.13 item 6): an imported chest menu's `size` maps to `container: chest_N` (N = size / 9), `HOPPER` and `DISPENSER` (and `DROPPER`, the same menu) to `container: hopper` and `container: dispenser`, and an unsupported `inventory_type` is a skipped GUI with a diagnostic, never a chest. |
| Item and block rules in the node editor | The canvas is resource-agnostic (§8, `AuthoringApi.logic()`). The item studio's rule editor can switch to it. |
| Currency integrations beyond Vault, XP and the command pair (#213, §18) | An addon registers a `CurrencyIntegration` in its own namespace (`example:coinsengine`, `example:playerpoints`), and a currency file names it. No core change. |
| The `variable` and `item` currency integrations (§18) | A declared player number variable (#211) and an item count in the inventory. Reserved names; not implemented in #213. |

---

## 12. Decisions taken in this design (reversible by the owner)

1. **Structured flows, not a free graph.** The file holds steps (sequence, `if`, `require`,
   `choose`, `wait`, `stop`, `call`), and the canvas draws them. The canvas forbids fan-out and
   joins: that is what keeps YAML readable, round-trips lossless, and execution order visible.
2. **Exec edges only; values are templates.** There are no data wires (§2.5).
3. **Node positions live in the file** as an optional `node: {x, y}` on rules and steps. It is an
   editor hint like `locked` (gui.md §2), ignored by the compiler and preserved losslessly. When it
   is absent, the canvas lays out automatically and deterministically.
4. **Typed parse at load for every action and condition**, replacing per-run map reading (§4.1).
5. **Persistent variables are declared**, typed and pack-namespaced. Session and local variables
   are not (§3.2).
6. **Runs belong to the player, not the window** (§5.2).
7. **Dynamic raster layers compile to overlay glyphs** rather than being flattened (§6.4).
8. **JavaScript requirements are not supported.** Expression functions cover them (§4.2).
9. **Recipes are data**, expanded server-side, and never persisted as recipes (§4.6).
10. **Layer order is priority** for conditional slots. There is no separate `priority:` field
    (§6.3).

---

## 13. Work breakdown

The epic is #189. Each row is one issue and one PR. Columns: **Issue** is the GitHub issue; **Model** is the delegation target
(Haiku for regular implementation, Sonnet for API, engine or web-UI design); **Needs** lists the
rows it waits for.

| # | Issue | Work | Model | Needs |
|---|---|---|---|---|
| L1 | #207 | `api.logic` SPI (actions, conditions, triggers, functions, `LogicContext`, facets, outcomes) + vocabulary v2 + port the six built-ins | Sonnet | n/a |
| L2 | #208 | Flow compiler and interpreter (steps, resumable runs, budgets, step paths, trace events), with M2 rules migrated | Sonnet | L1 |
| L3 | #209 | Expression function calls + the function registry + the built-in functions | Haiku | L1 |
| L4 | #210 | Built-in conditions (§4.2) | Haiku | L1, L2 |
| L5 | #211 | Variables: the `variables` content type, scopes, stores, `set_var` v2, PlaceholderAPI expansion, `/materione var` | Haiku | L2 |
| L6 | #212 | Built-in player, world and server actions (§1 rows) | Haiku | L1 |
| L7 | #213 | Provider SPIs + Vault, LuckPerms and transfer adapters + economy and permission actions | Haiku | L1 |
| G1 | #214 | GUI sessions, `GuiOpener`, click map, click router, GUI triggers, GUI actions (open, close, back, refresh) | Sonnet | L2 |
| G2 | #215 | Open commands (Brigadier), typed args, requirement and deny, bypass, plus a #96 check | Haiku | G1 |
| G3 | #216 | Dynamic slots: `visible_if`, templated overrides, item sources (heads, `player_slot`, external), `frames`, refresh clock, render diff | Haiku | G1 |
| G4 | #217 | Overlay glyphs for dynamic raster + dynamic title text + `setTitle` research on 1.21.4 and 26.x | Sonnet | G1, #202 |
| G5 | #218 | Input slots, `gui.slot_change`, `player_inventory: locked`, the click-policy rows | Haiku | G1 |
| G6 | #219 | `list` component + `ListSource` SPI + paging actions + `gui.page` | Sonnet | G1, G3 |
| G7 | #220 | `prompt` + `InputMethod` (chat, sign, anvil) + list search | Haiku | L2, G6 |
| A1 | #221 | `AuthoringApi.logic()` (edit, validate, instantiate), `GuiEdit.SetOpen`, web ops, the `logic` capability, gates and goldens | Haiku | L2 |
| A2 | #222 | The recipe registry + the v1 recipes as data | Haiku | A1 |
| W1 | #223 | Logic tab: graph projection, auto layout, canvas, ports and wires, palette from the vocabulary, structural edit rules | Sonnet | A1 |
| W2 | #224 | Node inspector: typed fields, template autocomplete, condition stacks, variable pickers | Sonnet | W1 |
| W3 | #225 | Design ↔ Logic linkage + Script tab + Recipes panel | Sonnet | W1, A2 |
| W4 | #226 | Live trace, end to end (API stream + canvas animation) | Sonnet | W1, L2 |
| Q1 | #227 | Parity showcase GUIs (shop, server selector, paged player list, settings toggles) + QA catalog entries + docs | Haiku | G1–G7 |

Rows that can run in parallel once their dependencies land:

- L3, L4, L5, L6 and L7, after L1 and L2.
- G2, G3 and G5, after G1.
- A1, alongside the G rows.

---

## 14. Decisions taken in #207 (L1: the `api.logic` SPI and vocabulary v2)

#207 shipped the SPI of §4, the registries, the typed port of the six M2 actions and the version-2
vocabulary. Where §4, §5.4 and §8 left a choice open, these are the choices, with the rationale.
Each is reversible by the owner. The Java contracts are in `dev.materione.api.logic`; the
registry facade is [`api.md`](api.md) §3.11.

**The SPI shape**

1. **Registries are facades with an explicit id.** `LogicApi` exposes `actions()`, `conditions()`,
   `triggers()` and `functions()`, each a `LogicRegistry<T>`: `register(Plugin owner, NamespacedId
   id, T type)` returns a `LogicRegistration` handle (the `MechanicsApi` precedent), and `get`,
   `resolve` and `entries` read. A late registration opens one bounded write and takes effect on the
   next reload. The type's own `id()` is its local name and must equal `id.value()`, so the registry
   key and the name an author writes cannot drift. `LogicApi.vocabulary()` returns the full version-2
   vocabulary, mechanics included; the editor transport no longer composes it.
2. **Authored spelling.** An unqualified id is `materione:<id>`; an addon's types are written
   qualified (`example:greet`). This is the mechanics rule, and it means an addon can never shadow a
   built-in by accident.
3. **Trigger selectors.** `on: click:right` is trigger `click` with detail `right`. An addon
   trigger is written `example:storm`, with a detail after a second colon (`example:storm:heavy`).
   A first segment that names a Materione trigger is never read as a namespace, so
   `click:weird` is always `click` with detail `weird`. A spelled-out `materione:click` collapses to
   the bare `click` the firing sites use.
4. **Categories are a value, not an enum.** `LogicCategory(id, displayName)` has the eight
   built-ins as constants and `LogicCategory.of(id)` for an addon's own. The vocabulary lists the
   built-ins first, then any addon category in use.
5. **Availability is read, not stored.** `ActionType` and `ConditionType` have a default
   `availability()`, read each time the vocabulary is built, so installing Vault later needs no
   reload (§4.5). An unavailable type still parses and loads; running it fails with the reason. The
   vocabulary carries availability on the entry it affects. A separate providers list belongs to
   the provider SPIs (L7).
6. **Threading is static per type, and `run_command` is `GLOBAL`.** `threading()` is a method on
   the type, not a field of the description, because the interpreter reads it on every step. The
   console is `run_command`'s default sender, so it is `GLOBAL`. Its `as: player` and `as: op`
   senders need the player's thread. **#208 must route those two senders through the player's
   region** when it dispatches for real, either by adding a parameter-dependent `threading(P)` or by
   hopping inside the interpreter. Until then the M2 adapter runs every `GLOBAL` action through the
   existing deferred runner, which is anchored to the player, exactly as the old
   "must-defer" flag did.

**Context and parameters**

7. **`LogicContext` is four things**: typed facets (`facet`, `has`, `require`), the
   expression-visible variable tree (`resolve`, `value`), the run-local variables (`locals()`) and
   placeholder resolution (`applyPlaceholders`, identity by default). There is no Bukkit handle on
   it. The engine's implementation is `MapLogicContext` in `behavior`, which is also the fake every
   test uses.
8. **Facets are keyed by a namespaced id** (`ContextFacet<T>(NamespacedId, Class<T>)`, equal by id
   alone). `Facets` holds the public ones: `PLAYER` and `CANCELLATION`. `Facets` names a Bukkit
   type, so the Bukkit-free engine (R7) reads the cancellation facet from `Cancellation.FACET`
   instead, the same constant. The GUI session, the list entry and any other facet whose type
   belongs to the plugin are declared in the plugin next to that type, not in `api`.
9. **`ParamReader` is strict and non-coercing.** `"5"` is not an integer and `1` is not a boolean;
   a present value of the wrong type is an error, never replaced by the fallback. The engine
   normalises the scalar form before the type sees it: `message: "hi"` arrives as `{text: "hi"}`
   through the description's `shorthand`, and a scalar for an action with no shorthand is rejected.
10. **Templates and expressions cross a port.** The expression engine lives in `behavior`, which
    `api` cannot see, so `ParamReader.template()` and `.expression()` compile through an
    `ExpressionCompiler` that the engine supplies. `TemplateText.render(ctx)` evaluates `${...}`
    first and `%placeholders%` second (the M2 order). `ExpressionCompiler.LITERAL` serves an
    addon's own unit tests and refuses `${...}` so a test cannot pass a value the engine would
    evaluate.

**Outcomes and diagnostics**

11. **`FAILED` is reported like an exception in this adapter.** §4.1 says only an exception emits
    a diagnostic. The M2 engine also reported the failures that its executors threw, and the six
    ported actions now return `FAILED` for them, so the adapter publishes the rate-limited `MTR-0023`
    for both and the behaviour is unchanged. #208 decides whether a plain `FAILED` stays a
    diagnostic or becomes trace-only.
12. **`MTR-0057`** covers an unknown action or condition id and a step the type rejects while
    parsing. Only that step is skipped. A rule whose steps all fail to load is skipped with
    `MTR-0021`. An unknown trigger keeps `MTR-0021`, the code it always had, with the same effect.

**Vocabulary v2**

13. **Additive.** The version-1 keys (`events`, `actions`, `ruleOptions`, `actionOptions`,
    `mechanics`) keep their shape. The record gains `triggers`, `conditions`, `steps`, `categories`
    and `functions`, and each action gains `category`, `threading`, `available`,
    `unavailableReason` and `registeredBy`. `events` is derived from the same trigger
    registrations, so it cannot disagree with `triggers`. The web payload also carries
    `"version": 2`. `recipes` waits for the recipe registry (A2).
14. **A trigger's context schema is listed per firing host.** Every built-in trigger declares the
    variables the M2 firing sites fill, annotated with the hosts that fill them (an item rule sees
    `item.*`, a block rule sees `block.*`), filtered to the hosts that fire that trigger. A trigger
    nothing fires yet promises no variables.
15. **Step kinds are listed with a `supported` flag.** All seven kinds of §2.2 are in the
    vocabulary so the studio can draw the grammar; only `action` is `supported` until #208 flips
    the others, one flag at a time.

**Behaviour that changed on purpose.** Typed parse is the point of the change, so a few malformed
steps that used to fail at run time are now rejected at load. Well-formed content behaves exactly
as before.

- `cancel: false` does nothing. It used to cancel anyway.
- An unknown `run_command` `as:` is rejected. It used to run as the console. `op` is now listed in
  the schema; it was always accepted.
- Out-of-range numbers are rejected: `sound` volume and pitch outside 0 to 1000, `potion` duration
  below 1 and amplifier outside 0 to 255. Non-numeric values used to fall back to the default.
- A `set_var` value that reads as an expression and does not parse is rejected at load.
- A bare scalar for an action with no shorthand (`potion: speed`) is rejected at load.

**For #208.**

- The seam is small and named. `BoundAction` stands where `ActionStep` goes, `Rule.actions` holds a
  flat list where the step tree goes, and `RuleEngine.runActions` is the loop the interpreter
  replaces. Everything an action sees is already the final shape.
- `BehaviorService`'s deferred runner ignores its `delayTicks` argument: it hops to the player's
  scheduler once and runs, so a rule's `delay:` is not honoured today. This is M2 behaviour and is
  kept. `wait` and `delay:` must schedule for real.
- The M2 engine reads a rule's `if:` list as expressions only. The condition registry is complete
  and the lookup resolves conditions, but no rule evaluates a registered predicate before the flow
  compiler (§5.1) defines the condition tree.

**Build checks.** R11 (one action, one class, §9 rule 2) and the 400-line guard (§9 rule 4) ship
with this change, each proven against a planted violation. R10 had no subjects until the GUI runtime
created the `plugin.gui..` packages, and arrived with it (#214, §21).

---

## 15. Decisions taken in #209 (L3: expression function calls and built-in functions)

#209 binds calls into the expression grammar, puts the function registry behind the parser, and
ships the built-in set of §4.2. Where §4.2 and §9 left a choice open, these are the choices.
Each is reversible by the owner.

**Calling syntax and binding**

1. **A call is `name(arguments…)`.** Arguments are separated by commas and are full expressions,
   so a ternary or a nested call is an argument. Zero arguments (`now()`) are allowed. A call is
   an operand like any other value, so `round(x) >= 2` and `echo(a ? b : c)` both parse.
2. **Calls bind at parse time, against the live registry.** `ExpressionParser.parse(source,
   FunctionResolver)` asks the resolver for the function a call names, and the tree holds the
   resolved `ExpressionFunction`. The engine builds the resolver as `RegistryLogicLookup.functions()`
   over the `expression-functions` registry, and `RuleParser` hands it to every expression it
   compiles: each `if:` condition and each parameter the action types read as an expression or a
   template. `LogicLookup.functions()` defaults to `FunctionResolver.NONE`, so a lookup with no
   registry rejects every call, and the one-argument `ExpressionParser.parse(source)` keeps its
   behaviour with no functions. The `behavior` module stays Bukkit-free: the resolver is an
   interface in `behavior.expr`, and only the plugin supplies the registry.
3. **An unknown function, or a wrong argument count, is a load error.** Both are reported at
   parse time with a 1-based character position (`unknown function 'nope' at position 5`) and
   surface as the existing expression diagnostic, `MTR-0022`. The rule or the template is skipped,
   and nothing else is. A count outside the description's `minArguments()` to `maxArguments()` is
   refused; an optional parameter is one whose `required` is false.
4. **Names.** A bare name is one of Materione's functions: `contains` is `materione:contains`. An
   addon's function is written with a dot, `example.greet(…)`, because the grammar has no colon,
   and it resolves as `example:greet`. A dotted name of more than two parts is unknown.
5. **Templates compile against the registry, and hold the result.** `${…}` in a template is
   compiled once at rule load into a `Template.Compiled` that the step keeps. The process-wide text
   cache is not used for a template with calls, so a function that a reload removes cannot be
   reached through a parse that predates it.

**Evaluation**

6. **A function sees the run.** The evaluator passes the run's `LogicContext` to the function. The
   `MapLogicContext` attaches itself to its variable tree, so a function reads facets (the player)
   through the same typed port an action uses. Without a run, the function gets an empty facet view
   and the same variables.
7. **Functions are pure, typed and total.** Each returns a value of the kind its description
   declares. A bad argument never throws: a predicate answers `false`, a value function answers
   `null`, and the expression goes on. A function that throws anyway is contained as an expression
   failure (`function 'x' failed`), the same path as a type error. Every number a function returns
   is a `Double`, whatever the function returned.
8. **Argument kinds.** Text arguments are a string, or a number or boolean as the engine shows it
   in an interpolation (`20` is `"20"`). Anything else (null, a list, a map) is not text. Number
   arguments are a finite number, or text that reads as one (`" 12.5 "`, `"-3e2"`). A boolean is
   never a number, and neither is null. `number` is the one function that converts text to a
   number, so the other numeric functions take a value the author has already converted, or a
   number from the variable tree.
9. **`random` is the one impure function.** When both bounds are whole numbers (and within 10^15),
   the result is a whole number in `[min, max]`, both ends included. Otherwise it is a real in
   `[min, max)`. Reversed bounds are swapped. It is the documented exception to purity in
   `ExpressionFunction`.
10. **`matches` is a full match, and it is bounded.** The regular expression must match the whole
    text (write `.*` at either end for a partial match). The limits are enforced in one place
    (`RegexGuard`): a pattern longer than **256** characters, a text longer than **1,024**
    characters, an invalid pattern, and a match that reads more than **1,000,000** characters of
    the text all answer `false`. The read budget is what stops a catastrophically backtracking
    pattern: backtracking reads the text again at every step, so a cap on the length of the text
    alone would not bound the work. A pattern compiles once, and the cache keeps 256 patterns.
11. **`is_player` reads the run's player and nobody else.** It is plugin-side because its facet
    (`Facets.PLAYER`) is a Bukkit type. It answers `true` when the argument is the run's player's
    name (case-insensitive) or UUID. It does not look up other players, so it is a pure read of the
    run and needs no server call. A run without a player answers `false`.
12. **Rounding and formatting follow one rule.** `round` and `format_number` round halves away from
    zero (`round(-2.5)` is `-3`). `round` takes an optional number of places from 0 to 15.
    `format_number` takes an optional number of decimals from 0 to 10, uses `,` for thousands in the
    root locale, and shows no decimals by default.
13. **Text functions count and convert by Unicode.** `length` counts code points, so an emoji is one
    character. `lower` and `upper` use the root locale, so a result does not depend on the server's
    language. `equals_ignore_case` compares without locale rules. `trim` removes Unicode whitespace.

**The built-in set**

| Function | Arguments | Returns | Bad input |
|---|---|---|---|
| `contains(text, part)` | text, text | boolean | `false` |
| `equals_ignore_case(a, b)` | text, text | boolean | `false` |
| `starts_with(text, prefix)` | text, text | boolean | `false` |
| `ends_with(text, suffix)` | text, text | boolean | `false` |
| `matches(text, pattern)` | text, regex (full match, bounded, decision 10) | boolean | `false` |
| `length(text)` | text | number (code points) | `null` |
| `lower(text)` | text | text | `null` |
| `upper(text)` | text | text | `null` |
| `trim(text)` | text | text | `null` |
| `is_number(value)` | any | boolean | `false` |
| `is_player(name)` | text (a player name or UUID) | boolean (the run's player only) | `false` |
| `number(value)` | any | number | `null` |
| `round(value[, places])` | number, optional places 0–15 | number (halves away from zero) | `null` |
| `floor(value)` | number | number | `null` |
| `ceil(value)` | number | number | `null` |
| `min(a, b, …)` | one or more numbers | number | `null` |
| `max(a, b, …)` | one or more numbers | number | `null` |
| `abs(value)` | number | number | `null` |
| `random(min, max)` | number, number | number (whole when both bounds are whole) | `null` |
| `format_number(value[, decimals])` | number, optional decimals 0–10 | text (`1,234.50`) | `null` |

**Limits, and what was left out**

14. **No new diagnostic code.** A bad call is an expression error, so it uses `MTR-0022`, and
    plugin.md §5 is unchanged.
15. **The vocabulary carries the built-ins.** `api.logic().vocabulary().functions()` lists all
    twenty, each with its self-description and its registration owner (`materione`). The
    editor's demo snapshot (`fixtures/golden/editor-demo-snapshot.json`) records editor operations,
    not the vocabulary, so it does not change.
16. **No QA catalog entry.** Functions have no player-visible behaviour of their own. Their effect
    shows only through a rule or a message that uses them, and those are covered by the rule-parser
    and function tests here. The catalog entry arrives with the first condition or flow that makes
    a call visible to a player (L4).

**As built in #328 (the clock: three more functions)**

17. **Three clock functions, through the same registry.** `now()`, `today()` and `format_duration(seconds)` are one class
    each in `plugin.logic.function`, registered by `BuiltinLogic.functions(Clock)`. The built-in set is now twenty-three
    (the twenty above plus these three; the money pair of §18 is separate, as before). There was no engine clock to reuse:
    the rule gate and `CooldownStore` read `System.currentTimeMillis` directly. The injection point is therefore the
    function's constructor, a `java.time.Clock`; `BuiltinLogic.functions()` passes the system clock in the server's default
    zone, and a test passes a fixed one. Item 20 gives the rule gate the same kind of clock for its own tests.

| Function | Value | Notes |
|---|---|---|
| `now()` | whole epoch seconds (`floor(millis / 1000)`), a number | Takes no arguments. Not pure: the same call gives a later value each time it runs. |
| `today()` | the local day number, whole days since 1970-01-01 in the clock's zone, a number | The server's default zone. The value is the same for the whole local day and changes at local midnight. |
| `format_duration(seconds)` | text, or `null` for a non-number | Fixed format: fractions round **up** to whole seconds, so a running wait never reads `0s`; zero or less is `0s`; units `d`, `h`, `m`, `s`; the output names the **two largest non-zero units**, descending, one space apart. `93784` is `1d 2h`, `90061` is `1d 1h`, `200` is `3m 20s`, `45` is `45s`, `3600` is `1h`. |

A clock function is evaluated only when its call runs. Parsing and binding a rule read no clock, so nothing time-dependent
is baked in at load (a test asserts that describing the function reads nothing). Every clock function returns `null` or a
value for any argument and never throws (test `noBadArgumentThrows`).

---

## 16. Decisions taken in #208 (L2: flows and the resumable interpreter)

#208 shipped §2 and §5: the sealed step and condition trees, `FlowCompiler`, `FlowInterpreter` and the
`RunScheduler` port, with item, block and furniture rules running through them. Where the design left a
choice open, these are the choices. Each is reversible by the owner. The code is in
`dev.materione.behavior.flow` (compile) and `dev.materione.behavior.run` (execute).

**The model**

1. **Everything is sealed, one class per kind.** `Step` permits `ActionStep`, `IfStep`, `RequireStep`,
   `ChooseStep`, `WaitStep`, `StopStep` and `CallStep`. `Condition` permits `ExpressionCond`,
   `PredicateCond`, `AllCond`, `AnyCond`, `NotCond` and `AtLeastCond`. A `Rule` is `{owner, id, on,
   condition, steps, options, path}`; the rule-level `if:` list is an `AllCond`. `BoundAction`,
   `DeferredRunner` and the flat `Rule.actions` are gone. `RuleEngine` stays as the rule *gate*
   (matching, cooldown, charges, the rule-level condition) and hands the run to the interpreter;
   `RuleParser` keeps only the self-description of the rule and action option keys.
2. **Step paths.** A path is the list of keys and indexes that reaches the node in the YAML, and it is the
   diagnostic target. A rule is `behavior.<i>`, its steps `behavior.<i>.do.<j>`, an `if` branch
   `...then.<k>` / `...else.<k>`, a `require` body `...require.<k>` (else `...else.<k>`), a `choose`
   case `...choose.<c>` with its steps under `...choose.<c>.then.<k>`, a rule-level condition
   `behavior.<i>.if` (`.if.<n>` when it is a list), a step condition `<step>.if`, a flow
   `flows.<id>` with steps `flows.<id>.<j>`, and a `call` argument `<step>.with.<name>`. Rules that a mechanic
   generates are not in the file, so their diagnostics name the rule and carry no target.
3. **Two scopes of "skipped".** A problem that makes the author's intent *unreadable* skips the whole rule
   or flow with `MTR-0021` at the step's path: a step that is not a mapping or a name, a step that mixes
   kinds or carries keys that do not belong to it, a bad `chance:`, `delay:` or `wait:` value, a
   condition that is not a condition. A problem the compiler *understood* skips only the step that
   holds it: an unknown action or condition and rejected parameters (`MTR-0057`), a malformed expression
   (`MTR-0022`). Siblings load either way.
4. **A skipped step never weakens the steps that guard others.** A `require` that lost any step in its body
   (at any depth) compiles with `blocked`: it fails every time, so its `else` runs and the run ends, instead of
   running the survivors unprotected (a skipped `take_money` must not turn a paywall into a gift). A `choose`
   with a condition that does not load is dropped whole, so no later branch inherits the missing branch's
   turn. An `if` whose condition does not load is dropped whole (nothing runs). A rule-level `if:` that
   does not load skips the rule.
5. **Condition spellings.** A string is an expression, bare or wrapped as `"${...}"` when the whole string is
   one template; a boolean is a constant; a list is `all`; a one-entry mapping is `all`, `any`, `not`,
   `at_least: {count, of}` or a registered predicate (`has_money: 250`). The four composite names are reserved:
   a registered condition with one of those ids would be shadowed. A leaf that throws makes the *whole*
   condition false (the interpreter contains it and reports `MTR-0023`), so `not: <broken>` is false, not
   true: a broken check never grants.
6. **Step spellings.** A step is a mapping or a bare name (`- gui.refresh`, `- stop`). `stop` is `stop`,
   `stop: true` or `stop: {cancel, cancel_session_runs}`. `wait` is `wait: n` or `wait: {ticks}` with `0 <= n
   <= 1,728,000` (24 hours). `call` is `call: id`, `call: {flow, with}` or `call: id` with a sibling `with:`.
   `node: {x, y}` is accepted on rules and steps, ignored and never stored; the compiler only reads its
   input, so a load, compile and untouched save is byte-identical.
7. **Named flows.** `flows: {<id>: [steps]}` sits beside `behavior:`. Item, block and furniture definitions
   keep their unknown keys in the definition's raw map, so `flows:` is read from there; the schema
   self-description does not list it yet (the studio gets it with the authoring API, A1/#221). A flow
   compiles the first time a `call` links to it, so a cycle is found by the compile itself; flows nothing
   calls are still compiled so their problems are reported. Every call site holds the compiled flow
   directly. The longest chain of nested calls is computed statically and must be 16 or fewer.
   An unknown flow, a cycle, a chain past 16 or a callee that did not load is `MTR-0058` (error) and skips the
   rule that holds the call, as §10 says. A flow whose steps all fail to load is itself broken, for the same
   reason a rule is.
8. **Arguments.** `with:` values are templates evaluated in the caller's scope when the call is reached. A
   string that is exactly one `${expression}` keeps the expression's type (a number stays a number); other
   strings are rendered; non-strings pass through. They are visible as `args.*` in the callee only: a
   nested call has its own, and back in the caller they are the caller's again. The run-local `vars` are
   shared by every frame.
9. **`${root}` checking.** Every template and expression an action, condition or call argument compiles is
   noted (the engine's `ExpressionCompiler` keeps a notebook, so no action type has to cooperate) and its
   variable roots are checked against the roots of the rule's trigger context plus `player`, `server`
   and `vars`. An unknown root is `MTR-0060` (warning) and changes nothing else. A flow is checked against
   the union of its resource's triggers plus `args`. A trigger that declares no context is not checked, so
   "unknown" never turns into a warning on every variable. Per-host precision (a block rule using
   `item.*`) is not attempted: the schema already merges the hosts of a trigger.
10. **Diagnostics.** `MTR-0058` flow call cannot be linked (error, load), `MTR-0059` flow run aborted by its
    budget (warning, runtime, rate-limited per rule), `MTR-0060` unknown variable root (warning, load). All
    carry the step path as their target; `DiagnosticCodesTest` is pinned at 60.

**The interpreter**

11. **A run is a frame stack.** `FlowRun` holds `Frame`s (a step list and an index; kinds block, require,
    fail-else, call). There is no recursion in the interpreter; a `call` is a pushed frame, and a parked run is
    just its stack and its context. A synchronous scheduler (the unit-test one) nests Java calls per
    suspension; the production scheduler never does.
12. **`require` semantics.** A failure is an action returning `FAILED` or throwing anywhere in the
    require's dynamic extent: its own list, a nested branch, or a flow it `call`s. The nearest enclosing
    require handles it: the stack is unwound to it, its `else` steps run and the *run* ends (a `stop` in a
    called flow ends the whole run for the same reason). The else frame shields outer requires: a failing step
    in an `else` does not trigger a guard further out.
13. **Plain `FAILED`: reported unless handled.** An action's `FAILED` outside any `require` stays the
    rate-limited `MTR-0023` it was in M2, because nobody handles it and the author wants to know (a missing
    economy provider shows up once per second, not never). Inside a `require` it is control flow and is only
    traced. An exception is always reported, handled or not, because it is a bug.
14. **`delay:` parks the run.** An action's `delay: n` is a `wait: n` placed in front of it, so the steps after
    it wait too (§5.2). Until now it was parsed and ignored (the M2 deferred runner hopped once and dropped
    the delay), so no working content depended on it. This is the one deliberate change of M2 behaviour
    besides ordering (decision 16).
15. **Threading is parameter-dependent through an additive default.** `ActionType.threading(P params)` defaults
    to `threading()`; a type whose thread depends on its parameters overrides it. The interpreter calls
    only this overload; the bare `threading()` stays the headline value the vocabulary lists.
    `run_command` overrides it (`as: console` is `GLOBAL`, `as: player|op` is `PLAYER_REGION`). `sound`
    (#232: `scope: player` is player-local, `world|server` touch other players) takes the same
    override once both PRs land. The rejected alternative was the interpreter hopping inside the action: it
    would have made the SPI's `threading()` a lie for exactly the types that need the distinction.
16. **Inline when the thread allows, hop when it does not, in the order written.** Before an action the
    interpreter asks `RunScheduler.canRunInline(anchor, threading)`. On Paper there is one thread, so the
    answer is always yes and a flow runs entirely inline, in order, with `cancel` still effective after a
    command. On Folia the answer is the region/global ownership check; a step for another thread parks the
    run and `schedule`s the rest there, and consecutive steps for one thread share one hop. This changes two
    M2 behaviours that were accidents: a `GLOBAL` action used to be deferred one hop *past* the steps after it
    (so `[run_command, message]` messaged first), and every `GLOBAL` action ran on the player's thread even
    on Folia. Both now follow the order written and the thread declared. Consequence for `cancel`: the event
    is over once the run first suspends, so a `cancel` or `stop: {cancel: true}` reached after a thread
    hop or a wait is recorded and has no effect on the event (on Folia put it first).
17. **Predicates run on the player's thread.** A condition that contains a registered predicate reads live
    player and world state, so the interpreter hops to the player's region before evaluating it, as it does
    for an action. A pure expression reads only the run's own data and evaluates wherever the run is.
18. **Budgets.** Steps per run: every step *visited* counts, branches and calls included (1,000). The per-player
    parked limit (64) counts `wait`, `delay:`, pending asynchronous evaluations and pending rule-gate reads;
    a plain action-owner hop is not a wait and does not count. A player at the limit has a *new* run refused
    at start; a park that would cross it aborts. Call depth is also enforced at run time (16) although the
    compiler already bounds it. Run breaches are `MTR-0059`; a refused rule-gate read is `MTR-0023`.
19. **Cancellation by owner key.** `RunOwner(playerKey, anchor, cancelKey)`. `BehaviorEvent.withCancelKey(key)`
    marks the runs an event starts; `FlowInterpreter.cancel(key)` cancels them: a parked run's pending
    resume is cancelled, a running one ends at its next step boundary. A run with a key is tracked from
    its start (so it is reachable even while running, which `stop: {cancel_session_runs: true}` uses).
    Rule-gate-started runs are tracked from creation until completion, closing the cancellation race between
    a pending gate and its new run. Other unkeyed runs are tracked only while parked. `cancelPlayer` is called
    on quit; the entity scheduler's `retired` callback also drops the run (`DROPPED`) and releases its
    count. A key of `null` matches nothing.
20. **Trace.** `TraceEvent(runId, rule, path, type, detail)` with types `RUN_STARTED`, `STEP_OK`,
    `STEP_FAILED`, `STEP_SKIPPED`, `BRANCH`, `REQUIRE_FAILED`, `CALLED`, `SUSPENDED`, `RESUMED`, `RUN_ENDED`
    goes to an optional `TraceSink` installed with `FlowInterpreter.traceTo`. With no sink nothing is built;
    a sink that throws is contained.
21. **The gateway grew two methods.** `SchedulingGateway.executeEntityLater(plugin, entity, ticks, task,
    retired)` and `executeGlobalLater(plugin, ticks, task)` return a `ScheduledHandle`. They are `default`
    methods that throw, so a test double that never schedules ahead need not implement them and nothing silently
    drops a delay; `SchedulingGatewayImpl` overrides them. `GatewayRunScheduler` in `plugin.logic` is the
    `RunScheduler` over the gateway and the server's ownership checks, and `LogicService.engine()` wires
    the gate, the interpreter and that scheduler for item, block and furniture events.

**Behaviour that changed on purpose.** `delay:` now delays (decision 14). Steps run in the order written and
on the thread declared (decision 16). A rule that used to skip because one `do:` entry was not a mapping
still skips, now with the entry's path. A bare name is a valid step (`- cancel`), where it used to skip the
rule. A `delay:` that is not a whole number of ticks from 0 to 24 hours is rejected at load (it used to
accept `2.5`).

**For #210 (conditions).** Register `ConditionType`s through `LogicApi.conditions()`; they compile in
`rule if:` and in `if`/`choose`/`require`-adjacent conditions without any engine change, and
`BuiltinLogic.register` is where the built-ins join. Parse is typed at load and rejection is `MTR-0057` at
the condition's path. `test` must be a pure read and may rely on running on the player's thread. Do not
register `all`, `any`, `not` or `at_least`. A condition that cannot decide returns false; one that throws makes
the enclosing condition false and a rate-limited `MTR-0023`.

**For #211 (variables).** `vars.*` is a root every trigger has, so `vars.player.coins` already passes the
root check; the *declared-variable* checks (undeclared write, type mismatch) belong in `set_var`'s parse,
which gets the same `StepPath` through the diagnostic it throws into. The `${root}` notebook sees any template or
expression `set_var` compiles through `ParamReader`. `args` is frame-scoped, `vars` is the run's.

**For #214 (GUI sessions).** Compile a GUI's `behavior:` and `flows:` with `FlowCompiler.compile(owner, behavior,
flows, lookup)`; declare the GUI triggers' context variables in their `TriggerDescription` so the root check
knows them. Start a click's run with `BehaviorEvent.withCancelKey(session)` and call
`engine.interpreter().cancel(session)` when the session ends; an author opts into the stricter
"close ends the waits" with `stop: {cancel_session_runs: true}` and the default stays "runs belong to the
player". GUI actions in a run whose session ended should return `FAILED("no open gui")` (§5.2). A GUI action
that must run on the player's thread declares `PLAYER_REGION` (or overrides `threading(P)`).

**As built in #328 (`on_cooldown` and `cooldown.remaining`)**

These extend the rule shell of this section; a rule without them behaves exactly as before.

17. **The option.** `on_cooldown: [steps]` is a flow that runs when the rule matched (its event and trigger filters) but its
    cooldown blocked it. Its steps compile in their own unit, whose variable roots are the trigger's roots plus `cooldown`,
    so `${cooldown.remaining}` is checked there and nowhere else. `RuleOptions.ON_COOLDOWN` is the key. It is described in
    `RuleParser.RULE_OPTIONS` as a `raw` parameter, so the studio shows it as JSON and never as a text field that would
    flatten its steps.
18. **The variable.** `cooldown.remaining` is the whole seconds left in the rule's window, rounded **up** (1 ms left is
    `1`). It is a number. It exists only inside an `on_cooldown` run; the rule's own steps do not see it.
19. **The gate.** `RuleEngine` asks `CooldownStore.tryFireRemaining`, which returns `0` when the rule may fire (and records the
    window, as `tryFire` did) or the milliseconds still open. When it is `0`, the path is exactly the one before: charges,
    the rule's `if:`, then its `do:`. When it is positive and the rule has no `on_cooldown`, the rule stays silent, as before.
    When it is positive and the rule has one, the rule's `if:` is evaluated as usual, the charges are **not** spent, and if
    the condition holds the `on_cooldown` flow runs as a run of the same rule identity and path, with `cooldown.remaining`
    in a context of its own (`MapLogicContext.withRoot`; the event's context is unchanged).
20. **The clock.** `RuleEngine` takes a `LongSupplier` of epoch milliseconds, defaulting to `System::currentTimeMillis`, so
    tests advance the window without sleeping. Nothing else about timing changes.
21. **Editing.** `on_cooldown` is a step list, so it is protected like `do`: `patchRule` refuses it and the edit is made
    through the step operations. It is not an option in the rule view (`LogicViews`), and an edit to any other key leaves it
    byte for byte as written (test `anOnCooldownBranchLoadsAndSurvivesOtherEditsByteForByte`).
22. **Not in this change.** The node canvas does not draw the `on_cooldown` steps (they are reachable as JSON in the
    inspector). The cooldown itself is still in memory and resets on a restart, as before: a restart-proof cooldown is the
    `cooldown_gate` recipe of §30, which is a variable and the clock, not this option.

---

## 17. Decisions taken in #212 (L6: built-in player, world and server actions)

#212 adds the player, world and server verbs of §1 that the M2 vocabulary lacked, each as one
`ActionType` in `plugin.logic.action`, registered in `BuiltinLogic` through the public API like the
six before them. `connect` is not here: it needs the `ServerTransfer` provider of #213.

**As built**

| Action | Category | Threading | Parameters (shorthand in bold) | Fails (returns `FAILED`) |
|---|---|---|---|---|
| `broadcast` | server | GLOBAL | **`text`**, `scope` (server, world, permission), `target` (world name, or permission node) | unknown world; empty target at run time; no player for the default world |
| `actionbar` | player | PLAYER_REGION | **`text`** | no player |
| `title` | player | PLAYER_REGION | **`title`**, `subtitle`, `fade_in` (10), `stay` (70), `fade_out` (20), in ticks | no player |
| `sound` | player | PLAYER_REGION (player scope), GLOBAL (world, server) | **`sound`**, `volume`, `pitch`, `scope` (player, world, server) | no player for player or world scope |
| `stop_sound` | player | PLAYER_REGION | **`sound`**, `category` (a sound category) | no player |
| `chat` | player | PLAYER_REGION | **`text`** (a leading `/` runs a command as the player) | no player; empty text; no such command |
| `teleport` | world | PLAYER_REGION | `x`, `y`, `z`, `world`, `yaw`, `pitch` | no player; unknown world; coordinates that do not render; refused by the server |
| `give_item` | player | PLAYER_REGION | **`item`**, `amount` (1 to 2304) | no player; unknown or ambiguous item; no content loaded |
| `take_item` | player | PLAYER_REGION | **`item`**, `amount` (required), `name_contains` | no player; unknown item; fewer held than `amount` (takes nothing) |
| `give_exp` | player | PLAYER_REGION | **`amount`** (1 to 1,000,000), `unit` (points, levels) | no player |
| `take_exp` | player | PLAYER_REGION | **`amount`** (1 to 1,000,000), `unit` (points, levels) | no player; fewer held than `amount` (takes nothing) |

`message`, `run_command`, `potion`, `cancel` and `set_var` are unchanged. `sound` gains `scope`, and
its declared threading moves from PLAYER_REGION to GLOBAL (see decision 3 below). Every text
parameter is a template (`${...}` and `%placeholder%`); every number may be a template that renders
to one.

**Decisions**

1. **Take actions are all or nothing, decided before anything moves.** `InventoryPlan` computes the
   removals from a snapshot of the whole inventory (main, armour and offhand) and returns no plan
   when the held amount is short. Only a complete plan is applied, so a failed take changes nothing,
   and a `require` step (#208) can rely on it. Experience takes check the same way: points against
   the total, levels against the level.
2. **One item matcher, shared with `has_item`.** An `item` value is either `minecraft:<material>` or
   a Materione id, resolved through the same `ItemIdResolver` that `/materione give` uses (so a bare
   name means the same in both). A vanilla reference matches only stacks that carry no Materione
   identity, so a custom item shaped like paper is not "paper" for a take. `name_contains` is a
   case-insensitive match on the plain display name. The matcher lives in `plugin.logic.support`,
   where `has_item` (#210) can reuse it.
3. **Threading follows the scope where it can.** `broadcast` declares GLOBAL, because every scope
   reaches other players. `sound` overrides `threading(P)` (added by #208, §16): `scope: player`
   runs in the player's region, `world` and `server` run on the global thread, and the headline
   `threading()` stays GLOBAL. Everything else touches only the acting player and declares
   PLAYER_REGION.
4. **Item actions read the live registries through a supplier, not the pipeline.** `ItemCatalog` is
   a one-method interface that `MaterionePlugin` wires to the current runtime registries; it is empty
   before the first content load, and a Materione item then fails with "no content is loaded yet".
   `BuiltinLogic.actions(ItemCatalog)` takes it, so no action reaches the plugin.
5. **Shared helpers live outside the action package.** `plugin.logic.support` holds `Quantity`,
   `ItemRef`, `ItemView`, `InventoryPlan`, `Stacks` (the Bukkit edge), `ItemCatalog` and
   `ExperienceUnit`. R11 forbids one action depending on another, so a shared constant such as the
   experience ceiling lives in `ExperienceUnit`, not on `GiveExpAction`.
6. **Counts are whole numbers at load; coordinates are not.** An `amount` literal that is fractional
   is rejected when the step loads. A template that renders fractional fails the step at run time.
   Coordinates and angles stay decimal, and an out-of-range angle is rejected at load.
7. **`chat` says text as the player, and runs a leading slash as a command.** A plain line goes
   through `Player.chat`, which is not rich text (chat is plain). A `/` line uses `performCommand`
   and fails when the server has no such command, which matches `run_command as: player`.
8. **`broadcast` world scope defaults to the event player's world.** A `permission` scope needs a
   `target` at load, because a permission broadcast without a node would be a server broadcast by
   accident.
9. **`teleport` keeps the player's facing unless `yaw` or `pitch` is written,** and a server that
   refuses the move fails the step rather than reporting success.
10. **`stop_sound` is player-scoped in v1.** It stops a sound for the player who ran the step, by
    key, by category, both, or everything. Stopping a world- or server-wide sound needs the scope
    vocabulary that `sound` has and `stop_sound` does not yet; that is left to a later milestone.

**Verification.** Parse accept and reject, and execute on the paths that need no server, are unit
tests (`BuiltinActionsTest`, `PlayerActionsTest`, `ItemActionsTest`). Take planning is tested
exhaustively as a pure function. The Bukkit-only paths (the server broadcast, world lookup by name,
online-player sound, stack building and inventory mutation, the experience and teleport calls on a
real player) need a live server and are covered by the `logic` QA suite (`qa/catalog/logic.mjs`) on
the probe items `probe:probe_messages`, `probe_items`, `probe_exp`, `probe_move`, `probe_sound` and
`probe_stop`. The QA entries are authored and their integrity is checked by `qa/runner.mjs check`;
they have not been run on a probe in this change, so their ledger state is "never" until a main
swarm records them.

**Not covered by a test in this change.** `broadcast` with `scope: permission` and `sound` with
`scope: server` have no QA entry (they need a permission-holding second player and a second client).
Their parse is a unit test; their execution calls static server state and is not run by any test
yet.

## 18. Decisions taken in #213 (L7: provider SPIs, named currencies, the money, permission and connect actions)

#213 ships the provider SPIs of §4.5, the **named-currency** content type, three built-in currency
integrations, the Vault, LuckPerms and BungeeCord adapters, the Bukkit default permission provider, the
transfer packet, five actions, and two expression functions. The currency model follows the owner's
ItemsAPI currency system; the XP, command-pair and Vault integrations are adapted from that code with
the owner's permission, and ItemsAPI is not a dependency. The SPI shapes are in [`api.md`](api.md) §3.11.

**Currencies are content**

1. **A currency is a file in `currencies/`, under its pack's namespace.** The `currency` content type
   (`dev.materione.content.currency.CurrencyType`) parses `shop:gems` from `currencies/gems.yml`. Its own
   keys are `integration` (required), `display_name` (defaults to the id's name), `symbol` (blank by
   default) and `decimals` (0 to 6, default 2). Every other key belongs to the integration and is kept as
   authored, so the file round-trips losslessly and the integration reads its own keys at bind time.
   A file takes one of two shapes (#469, master §7.2). **Keyed**: each top-level key is a currency id with its fields
   beneath it (`gems:` then `integration: xp`), and a file holds any number. **Flat**: the file's top-level keys are
   one currency's fields and the file name is its id, so `currencies/gems.yml` with `integration: xp` is `shop:gems`.
   A file that mixes the two, or declares no currency, is reported (`MTR-0008`, plugin.md §5), and a flat file with no
   `integration` is refused as `MTR-0062` with both shapes named. The editor's new-currency scaffold writes the keyed
   form; the flat form is for a file written by hand, and the editor and code mode work on it as on a keyed one (#475,
   web.md section 4.2). It is listed as one currency named by the file; its form opens as `pack:<file name>`; a field edit
   (the editor's, a bulk change's, a transaction's, a code-mode `m.edit(...).set(...)`) writes the field **at the top
   level of the same file**, with every comment and unknown key kept and no rewrite to the keyed form. An edit that
   would put a mapping at the top level (an integration's nested option) is refused rather than let the file read as
   keyed, so such an option is written in the keyed form. Which directories take the form is the type's
   self-description (`flatFile`), not a list the editor keeps. A copy of a flat file is a flat file named by the new id;
   renaming it in the editor renames the file, which is renaming the currency (references to the old id then report as
   unresolved); the SDK has no rename, so a program duplicates and deletes.
2. **Money is runtime state, so nothing is emitted to the pack.** The type's `compile` is a no-op. The
   defined currencies travel as a runtime view (`RuntimeCurrencies`) built by the same apply that builds
   items and sounds, and they swap with it (D6). The catalog never stores a balance.
3. **Quarantine is `MTR-0062`** (plugin.md §5): a file that does not parse (no integration, a bad
   `decimals`), a currency in the reserved `materione` namespace, or an integration that rejects the
   currency's keys when bound at apply. The rest of the pack loads. An integration that is **not
   installed** keeps the currency, which then reports unavailable at use, with the reason; no reload is
   needed once the plugin arrives.
4. **The built-in `materione:money` always exists**, bound to `vault`, with no file. The
   `default_currency` setting (default `materione:money`) names the currency the money actions use when
   they name none. A value that names nothing defined falls back to `materione:money`.

**The SPI**

5. **`CurrencyIntegration` is a registry entry.** It lives in the `currency-integrations` registry
   (`RegistryKeys.CURRENCY_INTEGRATIONS`), registered through `LogicApi.currencyIntegrations()` like
   actions. `bind(ParamReader config)` is pure over the currency's mapping: it returns a `CurrencyBackend`
   or rejects with `IllegalArgumentException`. It runs at apply (for quarantine) and at each use (so an
   integration that arrives later answers without a reload).
6. **`CurrencyBackend` answers for one bound currency.** It reports `availability()` with its reason, and
   `balance`, `has`, `withdraw` and `deposit` return the sealed `EconomyResult` (Succeeded, InsufficientFunds,
   Failed). **A withdrawal the balance cannot cover takes nothing**; that is a contract, and it is stronger
   than ItemsAPI's void withdraw, which could not say so.
7. **`CurrencyCatalog` is what the actions read** (`LogicProviders.currencies()`): `all`, `get`, `resolve`,
   `defaultCurrency`, `backend` and `availability`. The live implementation,
   `MaterioneCurrencyCatalog`, is replaced after every successful swap (`observeSwaps`), so a removed
   currency leaves with the reload that removed it.
8. **Name resolution.** A qualified id (`shop:gems`) matches exactly. A bare name (`gems`) matches
   `materione:` first, and otherwise the one currency of that name in any pack; two packs defining the same
   bare name make it ambiguous and it resolves to nothing. **Deviation from the design note:** the spec
   asked for a bare name to resolve in the *resource's own pack* first. The rule parser binds an action's
   parameters without the rule's owner, so the action cannot know its pack at load. Threading the owner
   through `RuleParser`'s bind is the follow-up; until then a bare name is matched by name across packs, and
   a clash must be qualified.
9. **Unknown names fail at run time, not at load.** **Deviation from the design note:** the spec asked for
   an unknown currency to be rejected at load as `MTR-0057`. The currencies a reload defines are published
   by the same apply that binds the rules, after the rules bind, so a load-time check would reject a rule
   that names a currency defined in the same reload. The step fails with `unknown currency <name>` instead,
   and the load-time check is the follow-up: publish the currency view before the rule bind, then validate.

**The built-in integrations**

10. **`xp`** (`unit: points | levels`, default `points`). Ported from ItemsAPI's XP integration. Points are
    the player's total experience: the server keeps that total, so the port reads `getTotalExperience()`
    instead of recomputing it from the level and the progress bar, which gives the same value. Amounts are
    whole units. **Threading:** the XP backend declares `PLAYER_REGION`, so a step that pays XP runs on the
    player's region (see item 25).
11. **`custom`** (`deposit-command`, `withdraw-command`, `balance-placeholder`, all required). Ported from
    ItemsAPI's custom integration. The balance is read through PlaceholderAPI, which the integration reaches
    by **reflection**, so Materione takes no compile dependency on it. Without PlaceholderAPI the currency
    is unavailable, with that reason. A leading `/` is stripped; `{player}` and `{amount}` are filled in; the
    command runs as the console. A withdrawal checks the balance first. A command that does not dispatch is
    `Failed`. A deposit reports the expected balance (the placeholder is authoritative on the next read).
12. **`vault`** is registered as `materione:vault` **only while Vault is enabled**, by `Integrations`, so a
    server without Vault never loads its classes. It takes no keys: the economy Vault exposes is the currency.
    Ported from ItemsAPI's Vault integration, with one change: ItemsAPI cached the `Economy` at construction,
    and this resolves it through Vault's service registry at each call, so an economy registered after Vault
    is picked up without a reload.
13. **Reserved seams, not implemented.** `variable` (a declared player number variable, #211) and `item` (a
    count of an item in the inventory) are reserved integration names. CoinsEngine, PlayerPoints and other
    shop plugins are addon integrations (§11), not built in.

**Actions and functions**

14. **`give_money` and `take_money`** take `amount` (the shorthand, above 0, at most 1e12) and an optional
    `currency:`. Threading is `GLOBAL` and the category is `economy`. `take_money` fails with `insufficient
    funds: balance X, needs Y` and takes nothing, so a flow branches on it with `require` (§2.2). Each
    fails with the catalog's reason when its currency cannot be used. Availability is that of the default
    currency.
15. **`balance(currency)`** reads the run's player's balance now, and is null without a player or when the
    currency cannot answer. It is **not pure** (it reads live state, as `random` draws), so it is documented
    as such. **`format_currency(amount, currency)`** is pure over the currency's definition: the symbol before
    the number, thousands separators, the currency's decimals, and a sign before the symbol (`-G$3`). Both are
    registered like the #209 functions, through the same function registry.

**Settings, markers and diagnostics**

16. **Settings.** `default_currency` (default `materione:money`), `transfer_host` (blank: the packet is off),
    `transfer_port` (`25565`) and `transfer_mode` (`packet`, or `bungeecord`). All four are in the default
    settings file and the key table of [`plugin.md`](plugin.md) §7.1, with defaults that keep the feature
    off, and the settings golden is regenerated with them.
17. **The boot marker** is `MATERIONE-LOGIC-OK currency=<default id> available=<bool> transfer=<yes|none>
    unavailable=<action ids|none>` (build.md §7). The harness asserts it on a workspace with no Vault:
    `materione:money` is unavailable, and `give_money`, `take_money` and `connect` report unavailable. The
    server boots clean.

**Adapters and the gate**

18. **Permissions and transfer are single slots** (`LogicProviders.permissions()`, `transfer()`), unchanged
    from the first #213 design: the session-only Bukkit default; LuckPerms when enabled (native expiry),
    else Vault's permission service when Vault runs; the transfer packet when `transfer_host` is set, else the
    BungeeCord plugin channel when `transfer_mode: bungeecord`.
19. **`Integrations` is the only guarded entry point.** It takes the `LogicApi`, so it can register the Vault
    currency integration as well as the provider slots. An absent plugin loads no class. An enabled plugin
    whose API cannot load is reported as `MTR-0061` and skipped. The gate refreshes on start, on every
    reload, and when Vault or LuckPerms enables or disables.
20. **`paper-plugin.yml`** declares Vault and LuckPerms as optional server dependencies with
    `join-classpath: true`, so Materione's classloader can see them when present.

**Dependencies and licences (compileOnly, never shaded)**

21. **Vault API** `com.github.MilkBowl:VaultAPI:1.7.1`, resolved from JitPack through a settings repository
    filtered to that group. The upstream repository's licence is **LGPL-3.0** (as the reviewer stated; the
    pom itself declares none, so verify before any shaded use). Compile-only and not shaded, so Materione
    takes on no distribution obligation.
22. **LuckPerms API** `net.luckperms:api:5.4`, MIT, Maven Central, no runtime dependencies.
23. **PlaceholderAPI** is **not** a dependency at all: the custom integration reaches it by reflection.
24. **ItemsAPI** is not a dependency. Code adapted from it is attributed in the file headers, under the
    owner's grant of rights.

**Threading**

25. **Threading follows the currency, not a fixed value.** Each `CurrencyBackend` declares `threading()`
    (default `GLOBAL`, the conservative choice): `vault` is `GLOBAL` (economies are written for the main thread),
    `xp` is `PLAYER_REGION` (experience belongs to the player), and `custom` is `GLOBAL` (its take and give are
    console commands, which may touch any region). `give_money` and `take_money` override `threading(P)` (§4.1,
    #208): the engine hops to the thread of the currency the step names, resolved at dispatch because currencies
    are live state. An unknown currency, or one whose integration is not bound, reports `PLAYER_REGION`; the
    step then fails with its reason. The headline `threading()` stays `GLOBAL`, the common case, for the
    vocabulary. Permission actions and `connect` stay `PLAYER_REGION`.
    **Read capability (#250):** `CurrencyBackend.readCapability()` separately declares the read
    owner, explicit Folia support with a reason when unsupported, and an optional offline owner. Existing
    integrations default to unsupported on Folia; a `GLOBAL` thread label is not evidence that Vault or
    an arbitrary PlaceholderAPI provider is safe. `CurrencyIntegration.lookupCapability()` independently
    declares the owner and Folia safety of binding and availability; its default is unsupported. A live
    read proves that capability before invoking either callback, then rechecks availability after the
    read on its declared owner. Synchronous vocabulary availability fails closed when it cannot prove
    the lookup owner without a player. XP declares the player's region and no offline read.
    `balance()` and `has_money` guard synchronous calls: on Folia an unsupported provider, unavailable
    backend or wrong owner is a read failure, not a balance of zero or a false result that `not` can invert.
    Paper retains its previous synchronous behaviour.

    Rule gates, conditions, action templates and computed values, and flow-call arguments use ordered
    nonblocking continuations. A continuation retains prior operands and does not replay a callback.
    Existing action and condition callbacks still run once on their declared owner; legacy expression
    functions return to the evaluation's captured original owner after their arguments resolve. One
    capture spans sequential action parameters, composite conditions, and flow-call arguments. An
    unidentifiable original owner is unavailable, not guessed. Addons that need a different hop inside
    a callback opt into the continuation API. A computed `set_var` operand resolves before the target's
    current value is read and updated on its owner. Server-scope `set_var` operations use the same
    atomic in-memory mutation as all server-store writers, so they may run inline without a global hop;
    operand evaluation and file flushing happen outside the mutation lock. Live inventory and location
    predicates read their state after dynamic parameters resolve,
    while `is_near` still short-circuits an initial world mismatch before those parameters.
    Unsupported reads fail rather than become a value.
    For a cancellable pre-event, an evaluation requiring a hop before dispatch returns is unavailable
    and requests cancellation; explicit waits retain their existing delayed semantics. As a conservative
    pre-event boundary, an action requiring a different owner is refused before it runs. The GUI
    opening, slot-change and inventory-click events are pre-events as well as cancellable Bukkit events.
    Generic PlaceholderAPI expansion is rejected on Folia, rather than assuming arbitrary installed
    expansions are safe; Paper's existing resolution is unchanged. Item-source adapter continuations
    remain separate work (#251). Direct requests to Materione's expansion follow the narrower currency
    placeholder rules below rather than enabling arbitrary PlaceholderAPI expansion on Folia.

    **Player variables (#250):** live player PDC access is checked against the entity owner.
    Synchronous external API reads may use immutable last-acknowledged snapshots when called off-owner;
    no snapshot means unavailable, not the declared default. The `getResult` API distinguishes this from
    an undeclared variable. Snapshots are invalidated on writes, join/quit, reload and scheduler retirement;
    join refreshes the current declarations on the player thread. Offline reads retain their documented
    default. GUI rule, open-gate and render contexts bind the viewer's variables. Snapshots are not a
    balance cache and are not used in place of live flow reads. Fake-owner tests exercise these contracts;
    real Folia runtime verification is still outstanding.

**Deferred**

26. Pack-first bare-name resolution and load-time currency validation (items 8 and 9); resumable fresh
    reads and remaining Folia handling (item 25); the `variable` and `item`
    integrations (item 13); `has_money` (#210,
    which takes `currency:` too); no per-player target and no duration on the permission actions; `run_command
    as: op` still uses the operator flag, not the permission provider (#208).
27. **Balance placeholders (#252):** the expansion exposes `%materione_balance_<pack>_<id>%` (raw)
    and `%materione_balance_<pack>_<id>_formatted%` (through the currency formatter), with the
    owner and consistency rules described in §20.
28. **For #210:** `has_money` takes `currency:` with the same resolution as the actions, and reads through the
    catalog like `take_money` does.
29. **In-game money flows are not in the QA catalog.** `logic.providers-unavailable-without-vault` proves the
    boot-time state without Vault. A catalog entry that pays through Vault needs a probe server with Vault and
    an economy, which the probe does not provision.

**For #208 and #212.** `BuiltinLogic.providerActions`, `BuiltinLogic.currencyIntegrations` and
`BuiltinLogic.currencyFunctions` are separate blocks in `BuiltinLogic`, appended beside the six M2 actions and
#212's additions, so the streams touch different lines.

---

## 19. Decisions taken in #221 (A1: the logic authoring API, the GUI `open:` block and the web ops)

#221 shipped §8 (the authoring surface behind the node canvas) and the `open:` shape from §6.1. The
canvas (#223) and the Script tab (#225) are built on these operations alone. Each decision is
reversible by the owner. The Java contracts are in `dev.materione.api.logic` (`LogicAuthoringApi`,
`LogicEdit`) and `dev.materione.api.gui` (`GuiAuthoringApi.SetOpen`, `OpenSpec`); the implementation is in
`dev.materione.behavior.logic.authoring` (logic) and `dev.materione.content.gui` (`GuiOpen`, the GUI
side). The surface is [`api.md`](api.md) §3.12, the wire is [`web.md`](web.md) (*As built, #221*).

**The edit model**

1. **Edits are a closed sealed set, addressed by step path.** `LogicEdit` permits `AddRule`, `RemoveRules`,
   `ReorderRules`, `PatchRule`, `InsertStep`, `PatchStep`, `MoveStep`, `RemoveStep`, `SetNodePosition`, `AddFlow`,
   `RemoveFlow` and `RenameFlow`. Paths are the §5.1 step paths (`behavior.2.do.0.then.1`, `flows.purchase.1`). An
   **insertion** path names the index it inserts at, so `InsertStep("behavior.0.do.1", …)` is the second step.
2. **One resource, one transaction, the whole value.** The resource's working copy is edited in memory and the
   changed values of `behavior` and `flows` are written through one `setFields` (one revision check, one audit, one
   reload). Edits apply in order; a refused edit refuses the request and writes nothing. An edit that changes nothing
   writes nothing, and an empty request returns the view with no revision.
3. **Refusals are structural, and carry the step path.** A refusal (`MTR-0057` for a step, `MTR-0021` for a rule) names
   the path it is about as the diagnostic's target. The refusals are: a path that leads nowhere or past the end of a
   list; an insertion into a list that holds rules, cases or a value; a step with no kind, with two kinds, or with an
   action no registry knows; an `if` without its `then:`, a `require` without its list; a move into the moved step's
   own subtree; a branch key (`then`, `else`, `require`, `choose`, `do`) patched through `PatchStep` or `PatchRule`; a
   rule whose `on:` names no registered trigger; a position that is not finite; a flow id outside `[A-Za-z0-9_.-]+`, a
   duplicate flow, or a missing one. An **unknown action is a refusal** on the edit (the author asked for it
   directly), and an unknown action *in a draft* is the compiler's warning (`validate`), which is what the spec of §10
   says.
4. **A move is remove, then insert, and `to` is read after the removal.** `MoveStep(from, to)` takes the step out and
   then resolves `to` against the tree without it. A refused move puts the step back before the refusal is returned.
5. **Renaming a flow rewrites its calls.** `RenameFlow` keeps the flow's position in `flows:` and rewrites every `call:`
   (the bare and the `{flow: …}` spelling) in the resource. Removing a flow does not: the dangling `call:` is the
   compiler's `MTR-0058` on the accepted edit, and the write stands, so the author sees the broken call instead of a
   silent loss.
6. **Unknown keys survive; comments inside the blocks do not.** The rule and step maps are copied with their keys, so
   an unknown key and a `node:` hint round-trip through every other edit (tested). The `behavior:` and `flows:` values are
   rewritten whole, as the GUI's `layers:` is (gui.md, decisions taken in #204, decision 5), so a comment inside those blocks is lost. A
   list-item path in `LosslessYamlDocument` would lift this; it is still not in this change.
7. **Node positions are edited, not computed.** `SetNodePosition` writes `node: {x, y}` on a rule or a step; both
   coordinates null removes it. A named flow has no position of its own and is refused.

**The read model**

8. **The view is the tree, typed.** `LogicView` lists the rules (index, path, `on:`, the other keys as `options`, the
   rule-level `if:` as authored, the node position, and the steps) and the flows. A `StepView` has its path, its `kind`
   (an action's id as written, or `if`, `require`, `choose`, `wait`, `stop`, `call`), its `value` (the kind's key, as
   authored), its `options` (`chance`, `delay`, `with`, …), its position and its `branches`, keyed by the path segments
   that reach them: `then`, `else`, `require`, and `choose.<c>.then` for a switch case. The view reads what the author
   wrote even when the compiler would refuse it, so the canvas can show a broken step. Only the compiler decides validity.
9. **Validation is the compiler's, on a draft.** `validate(LogicDraft)` runs `FlowCompiler` over the draft's
   `behavior:` and `flows:` with the registries' lookup, and returns its problems with their step paths. With a
   `trigger`, a rule that names no `on:` takes it, so a draft can be checked against the trigger it will be bound to.
   A draft with no rules has no trigger schema to check roots against, so its `${root}`s are not checked.
10. **The GUI's `gui.validate` includes the logic compiler.** `GuiAuthoringApiImpl` takes a `LogicCheck` seam (the content
    module does not depend on `behavior`); the plugin supplies `LogicDiagnostics.of`. The GUI's `behavior:` and `flows:`
    problems join its compile diagnostics, both in `validate` and in the warnings of `edit`.

**The `open:` block (§6.1)**

11. **`open:` is a typed record, and `GuiEdit.SetOpen` replaces it whole.** `OpenSpec` has `commands` (a list of names),
    `args` (`OpenArg`: name, type, optional, default, suggest), `permission`, `requirement` and `deny`. `SetOpen(null)`
    removes the block. The typed `GuiDocumentView` carries it as `open`, so `gui.get` and `gui.edit` return it.
12. **The shape is checked, the meaning is not.** `GuiOpen` refuses a command name outside `[a-z0-9_-]+` or named twice, an
    argument name outside `[a-z][a-z0-9_]*` or declared twice, an argument type other than `string`, `int`, `number`,
    `player`, `boolean` or `choice`, a blank permission, and an `open:` key the block does not have (on read). A
    `requirement` is a condition's outer shape (a string, a mapping, a list or a boolean); a `deny` is a list of steps.
    Their compile is the logic compiler's, reached through the GUI's logic check. The runtime that acts on the block is #215.
13. **`SetOpen` is a field, not a layer.** `GuiLayerEdits` ignores it, and `edit` writes `open:` as its own field in the same
    `setFields` transaction as the layers. A block equal to the one on disk writes nothing.
14. **The GUI's self-description lists the logic keys.** `behavior`, `flows` and `open` are declared in `GuiType` and
    `flows` in the item, block and furniture types (`content.logic.LogicSchemas`), so the editor learns the keys from the
    schema (D5), not from a list in the studio.

**The API and the ops**

15. **`AuthoringApi.logic()` is built like `gui()`.** `AuthoringApiImpl` takes a logic factory; a build without the behavior
    engine answers every call with `MTR-0011` (`UnavailableLogicAuthoring`), so the rest of the API keeps working.
16. **`instantiate` is defined and refused.** It takes a recipe id and parameters and returns the steps it expands to. The
    recipe registry and the v1 recipes are #222, so every recipe is refused as unknown (`MTR-0008`). It never writes.
    *(Superseded by §30: #222 shipped the registry, and `instantiate` now expands recipes into rules.)*
17. **The variables ops are not here.** `logic.variables.get/set` depend on #211 and are not added; the seam is the
    `logic` capability, which they will join under the same gate.
18. **Five ops, one capability.** `logic.vocabulary`, `logic.get`, `logic.edit`, `logic.validate` and `logic.instantiate` are
    translators over `AuthoringApi.logic()` (R9), gated on the `logic` capability in `editor-op-gates.json`. The
    capability is advertised in `SessionBlob.CURRENT_CAPABILITIES`, and the SPA checks it with `hasCapability` before it
    calls any of them (`webui/src/lib/logic/server.ts`). A refused edit's wire error carries the step path in its
    `resource` field, so the canvas can point at the node.
19. **No new diagnostic codes.** Every refusal reuses `MTR-0057`, `MTR-0021`, `MTR-0008` or `MTR-0034`, and the compiler's
    problems keep their codes (`MTR-0057`, `MTR-0058`, `MTR-0060`). The next free code stays `MTR-0061`.
20. **The file-size rule holds.** Every main class of the new packages is under the §9 limit of 400 lines; the logic surface is
    split into `LogicAuthoringApiImpl` (reads and writes), `LogicEditor` (the edits), `LogicTree` (paths), `StepShape` (the kind
    checks), `LogicViews` (the typed view) and `LogicRefusal` (the refusal).

## 20. Decisions taken in #211 (L5: declared variables, stores and `set_var` v2)

#211 shipped §3: the `variable` content type, the two stores behind a port, the scoped reads of
`vars.*`, `set_var` v2, the PlaceholderAPI expansion, `/materione var` and `LogicApi.variables()`. Where §3
left a choice open, these are the choices. Each is reversible by the owner. The API is
`dev.materione.api.logic.variable`; the engine's reads are in `MapLogicContext`; the stores and the command
are in `dev.materione.plugin.logic.variable` and `dev.materione.plugin.command.VarCommand`.

**The declaration**

1. **The content type is `variable`, read from `variables/`.** It follows the other content types (singular
   name, plural directory), so the registry id is `materione:variable`. One file may declare several variables,
   one per top-level key, as every content type does. It compiles to no pack output: the declaration is runtime
   state in the shadow, published as the `RuntimeVariables` view (swapped with the other registries), and the
   value lives in the store. A definition with an unknown scope or type, or a `default:` of the wrong type, is
   quarantined like any other malformed resource.
2. **A declaration is `{id, scope, type, default, description}`.** `scope` is `player` or `server`, `type` is
   `number` (always a finite `double`), `string` or `boolean`. An omitted `default:` is `0`, empty text or `false`.
   The default is coerced to the type when the declaration is built, so every read of a never-written variable
   returns a value of the declared type. Unknown keys stay in the authored file (lossless, §9 rule 6).

**Scopes and the spelling of a reference**

3. **Four words are reserved as scopes.** `local`, `session`, `player` and `server` cannot be run-local names,
   so `vars.player` always means the scope. A name written with one of them is `MTR-0063` (a load error). *(§38 adds a fifth
   reserved word, `item`, for the stack holder, and designs the open key that restates all of them.)*
4. **Reads.** `vars.x` and `vars.local.x` are the run's locals (M2 unchanged). `vars.session.<pack>.<key>` is a declared variable
   held by the open view and read through the session store (views.md §3.4; it was a free-form `VariableFacets.SESSION` map before). `vars.player.<pack>.<key>`
   and `vars.server.<pack>.<key>` read a declared variable: the stored value, or the default when nothing is
   stored. A scope that does not match the declaration, or an undeclared name, reads as absent (`null`).
5. **Writes.** A `set_var` names its target with the same path as the read, without the `vars.` root: `count`
   (a local, always), `local.count`, `session.page`, `player.coins` (pack = the resource's own pack),
   `player.shop.coins`, `server.shop.season`, or `shop:coins` (scope from the declaration). A bare name is always
   local, so a typo never creates a stored key; only a qualified spelling can reach a persistent variable.
6. **A deliberate narrowing of §3.2.** §3.2 says a bare name resolves within the resource's own pack. That holds
   for `set_var` (`player.coins`; the parse knows the pack, see decision 9) and for declarations, but **not for
   expression reads**: an expression is parsed without its resource, so `vars.player.coins` is absent and the
   read spells the pack (`vars.player.shop.coins`). Making reads pack-relative would need the compile environment
   in the expression compiler; it is a reversible follow-up, not a change of the spelling of a stored key.

**`set_var` v2**

7. **Shape.** `{var, op, value}`; `op` is `set` (the default), `add`, `subtract`, `multiply`, `toggle`, `remove`
   or `reset`. The M2 shape `{name, value}` still works and writes a run-local with `set`. `value` is required for
   the four ops that take one, and rejected for `toggle`, `remove` and `reset`.
8. **Two kinds of error, decided at load.** A name that is not declared, or a scope that does not match, is
   `MTR-0063`. A literal that does not fit the type (`"lots"` into a number, `true` into a string), or an op the
   type does not take (`toggle` on a number, `add` on a boolean), is `MTR-0064`. Both skip the step only. The
   type chooses the code by throwing `ParseRejection` (a new `api.logic` type, a subclass of
   `IllegalArgumentException` that names its `DiagnosticCode`); `ActionCompiler` reports that code, and any other
   parse failure stays `MTR-0057`. A template or expression is typed only when it runs, so it is checked then.
9. **The parse reads the resource's pack and declarations through `CompileEnvironment`.** `ParamReader` carries it
   (`ParamReader.environment()`), and the flow compiler supplies it from the owner's pack and the lookup's
   `variable(id)`. The pipeline's shadow build parses every `variable` resource in a pre-pass, before items, rules and
   flows, so a rule may name a variable declared in a file that sorts after it. A reload runs the same pre-pass before
   the pack compile too, so the GUI rule checks the compile makes (`GuiRuleDiagnoser`) see the variables of the packs
   being compiled, not the previous generation's: a variable and the GUI rule that uses it can arrive in one reload.
   `LogicLookup.variable(id)` defaults to empty, so a lookup with no declarations rejects every qualified persistent
   name.
10. **Runtime semantics.** `set` coerces the value to the declared type (a template's text becomes a number when
    it reads as one), and a value that does not fit fails the step with a reason. Arithmetic works on numbers and
    coerces text the same way; a run-local that was never set counts as `0` (and `false` for `toggle`); a result
    that is not finite fails. `remove` and `reset` both clear the stored value, so a persistent read returns the
    default again (a local is removed). The step's effect is visible to the rest of the same run at once, because
    the run's reads go to the same store.
11. **Threading.** `threading()` stays `ANY` (the headline value the vocabulary lists). `threading(P)` returns
    `PLAYER_REGION` for a player-scope target, so a write to a player's container runs on that player's thread, and
    `ANY` for locals, sessions and server variables (the server store is synchronised).

**The stores**

12. **One port per scope.** `VariableStore` (api) is `read(declaration, player)` and `write(declaration, player, value)`
    (null clears), with the scope on the port. `VariableBinding` (api) is the run's view: the declarations, the stored
    value and the write, for the run's player (player scope) and the server (server scope).
    `LogicContext.variables()` hands it out, defaulting to `VariableBinding.NONE`, which declares nothing and refuses
    every write, so a fake context in a test sees absent variables. The cross-server adapters (#25) implement
    `VariableStore` for their scope and touch nothing else.
13. **The player store is the persistent data container.** `PdcVariableStore` keeps each value under
    `materione:var.<pack>.<key>` with the type the declaration gives it (`DOUBLE`, `STRING` or `BOOLEAN`). It looks the
    container up per call, so an offline player (no container) reads absent and refuses writes (`MTR-0065`); the admin
    command needs an online player. A value stored with a type the declaration no longer has reads as absent.
14. **The server store is one JSON file, written atomically and debounced.** `storage/variables.json` holds
    `{"<pack:key>": value}`. A write updates memory and marks the store dirty; the service flushes on a 5-second
    debounce (a global-scheduler loop, started with `VariableService.autoFlush`) and on disable. A flush writes a
    temporary sibling and moves it over the file (`ATOMIC_MOVE`, with a plain replace when the filesystem refuses it).
    A file that does not parse is moved aside as `variables.json.corrupt`, a warning is logged and the store starts
    empty. A failed flush stays dirty and is retried on the next tick (`MTR-0065`).
15. **Recovery is about the file, never about the declarations.** Removing a declaration from a pack leaves its stored
    value in the store; it is unreachable until the declaration returns. Nothing is deleted.

**Read and write APIs**

16. **`LogicApi.variables()` (`VariablesApi`).** `declarations()`, `declaration(id)`, `get(id, player)` and
    `set(actor, id, player, value)`. A write checks `materione.var` first (`MTR-0055`), then the id and the type,
    then the holder, and returns `ApiResult<Object>` carrying the effective value. Reads never check the actor.
    `get` for an offline player reads the default (the holder is not reachable, and the read does not claim otherwise).
17. **`/materione var get|set|list <player> [<var>] [<value>]`, permission `materione.var`.** `<var>` is `pack:key`,
    or a bare key when exactly one declaration has it. A server variable ignores `<player>`, so write `-`. `list`
    takes an optional player. The command calls the API, so it uses the same checks as a flow.

**PlaceholderAPI**

18. **The expansion grammar is in `integrations`, the class is in `plugin`.** `VariablePlaceholders` (pure, unit-tested)
    resolves `var_<pack>_<key>`, `var_<pack>_<key>_<player>`, `gui_open` and `gui_last`. The `PlaceholderExpansion`
    subclass must extend PlaceholderAPI's class, so it lives in `plugin` (`MaterionePlaceholders`), and is installed only
    when PlaceholderAPI is enabled (`installIfPresent`). `paper-plugin.yml` declares PlaceholderAPI as an optional
    dependency loaded before Materione. The compile dependency is `me.clip:placeholderapi` (compile only, not transitive),
    pinned in the version catalog and fetched from the extendedclip repository through a content filter.
19. **Declared ids split the placeholder.** `<pack>_<key>` may contain underscores on either side, so the resolver takes
    the longest declared prefix; what follows is the player name. A name no player is known by, and an undeclared id,
    resolve to null, which leaves the token as PlaceholderAPI found it.
20. **Seams left open.** `GuiSessionPort` (api) answers `%materione_gui_open%` and `%materione_gui_last%`; it is empty
    until the GUI runtime (#214) registers one with `VariableService.guiSessions`. *Closed after #214 merged:* the
    plugin registers `GuiSessionsPort` over the live `GuiSessions`, which now remembers the last GUI a player had
    open (`GuiSessions.last`, set when a session is replaced or closes), and `GuiContexts` puts a
    `GuiSessionVariables` facet into every GUI run, so `vars.session.x` and `gui.session.x` are one map.
    The #252 resolver uses a typed currency bridge without changing the variable grammar. The resolver compares the complete encoded
    `<pack>_<id>` of every declared currency, including the `_formatted` form, and leaves collisions unresolved
    rather than guessing an underscore split. A recognized token with no viewer or an unavailable, failed, or
    non-finite read returns empty and reports a rate-limited `MTR-0023`. Raw finite amounts use plain decimal text;
    formatted text delegates to the existing `format_currency` implementation for the explicit qualified id.
    A single typed bridge operation uses a consistent currency definition for both read and format, and fails
    unavailable if the definition, integration registration, availability or player changes during the read.
    A backend may supply a public provider instance or registration token (`readBindingIdentity`), which is checked
    before and after the read; XP uses its bound backend and Vault uses the registered service identity.
    On Paper the built-in `custom` integration reads through the current enabled PlaceholderAPI instance,
    checks the public registered-expansion snapshot around the callback, and resolves methods from that
    instance's classloader rather than retaining stale methods. This trusts the public live-read contract,
    not private downstream state within an expansion. Custom PlaceholderAPI reads remain unsupported on Folia.
    The live catalog and integration are re-resolved at use, without a scheduled wait or holding a registry
    monitor across provider code. Immutable id enumeration is safe on any thread; Paper rejects async
    callbacks before player or provider lookup. On Folia, an integration must explicitly declare an owner and
    demonstrated safety for binding and availability (`CurrencyLookupCapability`), independently of the
    backend's read capability. Both owners must be current without a hop. A backend must also explicitly
    establish that its online or offline read remains safe across reconnects; unsupported declarations fail
    unavailable. Native XP binds on any thread and reads the captured player on its region. A declared
    GLOBAL or ANY provider, including offline reads with an explicit offline owner, can answer on its proven
    owner. No cached balance substitutes for a current read. Recursive or simultaneous reads for the same
    player and currency fail unavailable until the in-flight call finishes.

**Holder extension (#333; full decisions in §38).** The variable primitive grows in two independent
steps. Step 1 is the fifth shipped holder, `scope: item`: the declaration keeps the same id/type/default
shape, `vars.item.<pack>.<key>` reads the stack of a trigger-declared run, and `set_var` writes through
`StackVariableStore` into that stack's own PDC. An item-triggered `view.open` carries the same handle in
the view binding, so GUI open gates, renders and click rules read and write the same state. A GUI open
with no subject reads the default and fails writes with the rate-limited `MTR-0065`.
Step 2 is designed, not built: an expression-keyed holder, `ttl:`, expiry, the entity holder and one
general holder port replace the holder-specific shape once there is more than the stack addition. The
existing `player`, `server` and `session` files and every current `vars.*` spelling remain unchanged;
the only Step 1 reserved-word migration is a local named `item`, diagnosed by `MTR-0063`.

**Diagnostics and the rest**

21. **New codes.** `MTR-0063` (undeclared or reserved name, error, load), `MTR-0064` (value or op does not fit the type,
    error, at load for literals and at run time for computed values), `MTR-0065` (a persistent variable could not be read
    or written, warning, run, rate-limited). The pinned count in `DiagnosticCodesTest` is 65.
22. **Fixtures.** `fixtures/packs/corpus/variables/qa.yml` declares two variables for the QA suite. It adds no item, model
    or texture, so no pinned count changes. `fixtures/golden/editor-demo-snapshot.json` is regenerated: the `set_var`
    schema changed, and the `variable` type is listed.

**Not in this PR (by design).** Cross-server adapters (#25), the GUI session that fills `VariableFacets.SESSION` and the
GUI port (#214), currencies and their placeholders (#233), and the QA rule that sets a variable from a click (it needs the
GUI or a QA rule fixture; the catalog suite drives the command and the store instead).

**Open question for review.** On Folia, a player's container may only be read on that player's region thread. Writes are
placed there by `threading(P)`. A pure read in an expression, or a placeholder request, reads the container from the
thread that evaluates it. On Paper this is one thread and is safe. On Folia it is a hazard that needs either a hop for
reads or a per-run cache of the values a run reads. Left open here; the decision belongs with the Folia pass.

## 21. Decisions taken in #214 (G1: sessions, the opener, click routing and the GUI triggers)

#214 shipped the GUI runtime's core (§6.1 stages 1, 2, 5 to 8, §6.2, §5.2 for GUIs). Every later GUI issue
builds on its seams, so the seams are named here. Where the design left a choice open, these are the choices.
Each is reversible by the owner. The code is in `plugin.gui..`, `plugin.logic.action.gui` and
`content.gui.logic` (§9).

> **As rebuilt by views V0 (#455, [`views.md`](views.md) §16).** The session of this section is now one **viewer record** per
> viewer, for every view kind: `GuiSession` became `ViewerRecord` (binding, session values, window, prompt hold) with a
> `GuiViewerState` payload (pages, last render, clocks, ticker claims); `GuiSessions` became `ViewRegistry` (one record per kind per
> player, indexed by player and by definition); `SessionHistory` became `ViewHistory` (per player, spanning kinds). The
> actions that open, close and go back are `view.open`, `view.close` and `view.back`, resolved across kinds through the internal
> `ViewKind` SPI; the placeholders are `%materione_view_open%` and `%materione_view_last%`; the context root is `view`. Decisions
> 1, 2, 3, 8, 14 to 17 below keep their meaning under those names: a live reload keeps the record (17), a window is never changed
> in the middle of the player's own click (8), and the click router follows the last render (24 decision 14).

**Sessions and the back stack**

1. **A session is one open GUI for one player.** `GuiSession` holds the GUI id, the arguments (a read-only
   name-to-text map), the session variables (`vars()`, read as `gui.session.*`), the per-list page state
   (`pages()`, the seam for the list component) and the identity that keys the runs its clicks start.
   `GuiSessions` holds at most one per player. It lives while the window is open; a live reload keeps it
   (decision 17). It is thread-confined to its player's thread, and its maps are concurrent so the quit and
   refresh paths can read them safely.
2. **Back means "the GUI I left by opening another".** `SessionHistory` is a per-player stack, 16 deep, oldest
   dropped. A replaced window keeps it and pushes a snapshot (arguments, variables, page state), every way of
   opening included (command, API, `gui.open`); any other end (`player`, `action`, `quit`, a removed GUI)
   drops it. `gui.back` pops and re-opens the snapshot's GUI with its arguments and variables, and does not
   push the window it leaves, so two backs go two menus back. The issue text said "open, close, back"; a
   close ends the conversation, so the stack is dropped there and the exit criterion is "open, open another,
   back". Reversing this means keeping the stack across `action` and `player` closes.
3. **Close reasons.** `CloseReason` is `player`, `action`, `replaced`, `quit`, plus the runtime's own
   `removed`, which fires nothing because the GUI's rules are gone with the GUI. A window that closes without
   a flow asking is `quit` when the server says it was a disconnect and `player` otherwise (Esc, death, a
   teleport, another plugin's window). `gui.close` exposes the reason as `close.reason` as well as the
   `reason:` filter. A session ends once (`GuiSession.end` is a compare-and-set), so a close that races a
   replace or a quit fires its trigger exactly once.
4. **A window is its session's window only while the host says so.** `GuiMenu` points at the session, and
   the session records which window currently shows it (`attach`). A close event for any other window (the
   old one when the host re-opens, or when one GUI replaces another) is not the player closing. The listener
   compares and ignores. This lets a shape-changing reload re-open without ending the session; dynamic
   title refreshes do not use that reopen path (§6.4).

**The opener**

5. **`GuiOpener` is the one path.** Stages: resolve the live GUI; ask the `OpenGate`; fire the cancellable
   `gui.opening`; create the session and replace the previous one; render and open through the
   `WindowHost`; fire `gui.open` and start the ticker once the window is up. The command, the runtime API
   (and with it the editor's preview) and the actions all build an `OpenRequest` and call it. A refusal
   (`NOT_LIVE`, `DENIED`, `CANCELLED`, `FAILED`) leaves the player's windows and sessions as they were.
6. **The gate is the seam for the open specification.** `OpenGate.admit(viewer, gui, args)` answers with the
   arguments the session opens with, or a refusal. The default admits everything with the arguments as given.
   #215 implements `open.permission`, the typed `open.args` (so a bad argument is a usage message), the
   `open.requirement` and its `deny` flow and the bypass node behind it, and installs itself with
   `GuiOpener.gate(...)`. Until then the only permission is the API-level `materione.gui.open` that
   `GuiRuntime` checks for an actor. `gui.open` runs as the player with no extra check, because the flow
   is the author's. Arguments are plain text everywhere in this issue.
7. **A `gui.close` rule that opens something else wins.** The previous GUI's close rules run while the new
   open is in progress. If one of them opens a third GUI, that open is the player's current one and the
   open in progress ends `CANCELLED` ("another GUI opened while this one was opening"). The alternative, two
   windows racing, is worse.
8. **A window is never changed in the middle of the player's own click.** Opening or closing from inside the
   click being handled desyncs the client's cursor and slots for a tick, so `InputGuard` marks a player
   mid-click and `BukkitWindowHost` runs the open or close on the player's next tick through the scheduling
   gateway. The session switches at once, and a click that arrives on the old window in between finds a
   stale session and is ignored. A flow resumed from a `wait`, a command, a tick and the API are never
   mid-click and change the window at once.

**The click map and the router**

9. **Components declare where they are clicked.** `CompiledGuiLayer.clickArea(TextureSource)` is a new
   default method returning `ClickArea.NONE`, so an addon component opts in with a `Rect` (GUI pixels) or
   `Slots`. Built-ins: `button`, `text` and `image` are rectangles, `item` is its slot, `slots` is the
   container's slots when its wells cover them (the player-inventory wells are not part of a GUI's map;
   `gui.click_inventory` is #218). `panel` and `paint` are not clickable. An `image` without an authored size
   is as big as its texture, and a missing texture means no area. A `text` layer's box is its measured
   advance wide and one glyph cell high.
10. **The rectangle rule is arithmetic.** A slot is covered when the centre of its 16 x 16 interior,
    `(16 + 18·col, 26 + 18·row)`, lies in the half-open rectangle `[x, x + width) x [y, y + height)`. A
    rectangle that only grazes a slot's edge does not take it. The map lists layers per slot **topmost first**.
    A hidden layer, and a layer the compiler skipped, are not in the map. `ClickMap` is compiled with the GUI
    (`CompiledGui.clicks()`), so a click is one lookup.
11. **The router.** For each layer over the slot, topmost first, that is showing (`LayerVisibility`, always
    true until `visible_if`, #216), it takes that layer's `gui.click` rules whose other filters match; the
    first layer with a match runs them and the layers under it never see the click. **Rules with no `layer:`
    filter are the fallback**: they answer a click no layer claimed, so one rule can catch "any other slot"
    without competing with the layers above it. The click is dispatched with the session as cancellation key.
    The window policy table (`GuiListener.route`) is unchanged: every click is cancelled. The one extension is
    that a click outside the window, still cancelled, is offered to the GUI (`PluginGui.onOutsideClick`).
12. **Click kinds.** `ClickClassifier` is a pure table from Bukkit's `ClickType`: left, right, shift_left,
    shift_right, middle, double, drop, ctrl_drop, number_key, swap_offhand. A number-key click carries
    `click.key`, **1 to 9** as pressed (Bukkit counts the hotbar from 0). The creative clone and unknown
    types classify to nothing: the click is cancelled and no rule runs. A click outside the window is `left`
    or `right` only.

**Triggers and filters**

13. **Filters are the engine's.** `Rule` gained `filters`: the keys the trigger's description declares
    (`TriggerDescription.filters()`), read by the compiler and typed by that schema: enum values lower-cased
    and checked, integers whole and at least the schema's minimum, a required filter must be present, a single
    value or a list. A filter that is written and wrong skips the rule with `MTR-0021` at the key's path
    (`behavior.1.click`). `BehaviorEvent.withFilterFacts` supplies what the event says about itself
    (`layer`, `slot`, `click`, `reason`), `RuleEngine` compares, and a rule that names a filter the event
    carries no fact for does not match. Items, blocks and furniture are unaffected (their triggers declare no
    filters).
14. **The six triggers**, registered through the public logic API, with their context schemas:
    `gui.opening` (cancellable), `gui.open`, `gui.close` (`reason`, `close.reason`), `gui.click` (`layer`,
    `slot`, `click`; `click.type`, `click.slot`, `click.key`, `layer.id`), `gui.click_outside` (`click`:
    left or right; `click.type`; `click.slot` is -1) and `gui.tick` (`every`, required, at least 1). All
    carry `player.*`, `server.*`, `gui.id`, `gui.args.*`, `gui.session.*`, `gui.page.*` and `vars.*`.
    `gui.args` and `gui.session` are the session's own maps, not copies. `GuiContexts` builds the tree in one
    place, so the schema and the runtime cannot drift. `entry.*` is #219's.
15. **`gui.tick` is a chain of one-shots.** One timer per open session, started only if the GUI has a tick
    rule, waking every gcd of the rules' `every`; each wake counts elapsed ticks and runs the rules whose
    `every` divides them. Each link re-reads the live GUI, so a reload that adds or removes tick rules is
    followed, and it stops when the session ends or the rules are gone: closing needs no cancel call. A due
    rule is dispatched alone with its own `every` as the fact its filter compares with.

**Actions and runs**

16. **The four actions** are `gui.open` (a GUI and optional `args`, templated), `gui.close`, `gui.back` and
    `gui.refresh`, one class each in `plugin.logic.action.gui`, all `PLAYER_REGION`, and they reach the
    runtime through a `GuiBridge` the runtime binds into (the actions register at boot, before the runtime
    exists; unbound they fail soft). `gui.open` needs only a player, so it works from an item or a block rule
    and from a run whose window already closed; a bare name means the GUI of that name in the running GUI's
    pack, else the only live GUI of that name. The other three need the run's own session, still open and still
    the player's current one, and fail with `"no open gui"` otherwise (§5.2). `gui.back` with nothing behind
    fails with "nothing to go back to"; `gui.refresh` rewrites the open window in place, and is where the
    dynamic-slot work re-renders. A click's runs belong to the player and survive the close; only
    `stop: {cancel_session_runs: true}` ends them.
17. **A reload keeps the session.** `GuiLiveRefresh` (the refresh of gui.md §5, now session-aware) rewrites a
    changed GUI's window in place, or re-opens it when the title or size changed, for the same session:
    arguments, variables, page state and the back stack carry on. Every click reads the live view at the moment
    it happens, so the next click runs the reloaded rules. A reload that changes only rules is not a
    "changed" GUI (the window is identical). A GUI that is gone ends its sessions (`removed`), closes the
    window and tells the viewer; a session whose window is not the GUI's any more is left alone.

**Rules in the content type, and diagnostics**

18. **The gui type carries `behavior:` and `flows:`.** `GuiRules` reads the two sections as authored (an
    entry that is not a mapping keeps its index, so a diagnostic names the right `behavior.<i>`), and they
    are in the type's self-description. The flow compiler lives in `behavior`, which `content` cannot see,
    so the type takes a `GuiRuleChecker` (the host's compile) and runs it in `diagnose`: the pack compile and
    `gui.validate` report the same problems with the same step paths. The runtime compiles the rules again
    when it builds the live view and does not republish what the diagnose stage reported. A section of the
    wrong shape is `MTR-0021` at `behavior` or `flows`. `gui.validate` shows rule problems for the `saved`
    and `yaml` sources; the `draft` source is the typed layers alone and carries no rules, so a draft is
    validated for rules by the Logic tab's own call (#221).
19. **No new diagnostic code.** What the compiler cannot read is `MTR-0021`, a step the registry rejects is
    `MTR-0057`, an unknown `${root}` is `MTR-0060`. Three checks only a GUI can make are `MTR-0021` warnings
    on a rule that loads and never runs: a `layer:` that names no layer of the GUI (or a hidden one), a
    `slot:` outside the container, and a trigger no GUI fires. The next free code is still `MTR-0061`.

**Surfaces**

20. **Arguments are plain text on every surface.** `RuntimeApi.GuiRuntimeApi.open(actor, id, player, args)`
    is a default method (an implementation that predates it ignores the arguments), `runtime.openGui`
    takes an optional flat `args` object (numbers and booleans become their text; anything nested is a
    bad request), and `/materione gui open <id> [player] [name=value...]` takes `name=value` tokens. The
    editor's capability gate is unchanged, since the field is optional and a release without it ignores it.
21. **Tests.** A headless server has no player, so the click, back and refresh paths are covered by unit tests
    over a recording `WindowHost` and the real registries and engine, and by the QA catalog's `guilogic`
    suite against a real client (`qa/catalog/guilogic.mjs`, the `guilogic` fixture pack). The Paper
    harness proves what a server can: the fixture's rules compile at boot with no diagnostic, the console and
    command surfaces resolve the GUIs, `gui.validate` reports rule problems at their step paths, and a save
    that breaks a rule is diagnosed at the apply.

**Seams for the later issues.**

| Issue | Where it plugs in |
|---|---|
| #215 open commands | `GuiOpener.gate(OpenGate)` for permission, typed args, requirement, deny and bypass; `OpenSpec` beside `GuiRules` in `content.gui.logic`; `GuiRuntime` and the command already pass the arguments through. |
| #216 dynamic slots | `LayerVisibility` (the router already asks it); `WindowRenderer` becomes the per-session renderer, and `gui.refresh` re-renders through it; the click map keeps conditional layers in and asks per click. |
| #217 overlays | The title is assembled per render; the pinned platform transport resends a changed title to the existing server menu (§6.4). |
| #218 input slots | The `GuiListener.route` policy table gains its rows; `GuiInput.click` is where `gui.slot_change` and `gui.click_inventory` fire from; `GuiEvents` fires them. |
| #211 variables | `GuiSession.vars()` is a live, concurrent map the session scope can write through and back a session facet with, and `GuiSessions.current(player)` / `SessionHistory` are what `%materione_gui_open%` and `%materione_gui_last%` read; nothing here depends on the variables work. |
| #219 lists | `GuiSession.pages()`; `ClickArea.Slots` for a list's slots; `GuiEvents.click` takes extra context for `entry.*`. |
| #220 prompts | `GuiLifecycle` and `GuiSession` for closing for a prompt; re-open through `GuiOpener` with an `OpenRequest.back`-style restore. |

**Build checks.** R10 (§9 rule 1) ships with this change, proven against a planted violation
(`LogicCodeOrganisationTest`) and applied whole-jar in `AllModulesArchitectureTest`; the 400-line guard now
covers `plugin.gui` and `content.gui.logic` as well.

**Reconciled with #221 at merge.** #221 added a `GuiAuthoringApiImpl.LogicCheck` that ran the logic
compiler over a GUI's `behavior:` in `validate` and `edit`. This PR's `GuiRuleChecker` already runs in
the diagnose stage, so `validate`, `edit` and the pack compile all carry the same rule diagnostics
(plus the GUI-only layer checks). The two would have reported every problem twice, so `LogicCheck`
was removed and `GuiRuleChecker` is the one seam. `LogicDiagnostics` stays the logic authoring API's own check.

## 22. Decisions taken in #210 (L4: built-in conditions)

#210 ships the eight built-in conditions of §4.2 that the current main can support. Each is one class in
`dev.materione.plugin.logic.condition`, registered through `LogicApi.conditions()` in its own block of
`BuiltinLogic`, and each takes the shape §4.2 gives. `var` followed once #211 merged (decision 1); `slot_has` is #218's.
Where §4.2 and §9 left a choice open, these are the choices. Each is reversible by the owner.

**As built**

| Condition | Category | Parameters (shorthand in bold) | Answers false when |
|---|---|---|---|
| `has_permission` | player | **`permission`** (one node, or a list), `permissions` (a list), `min` (default: all) | the player lacks enough of the nodes; no player |
| `has_money` | economy | **`amount`** (above 0), `currency` (default currency when omitted) | the currency is unknown or unavailable; the balance is short; no player |
| `has_exp` | player | **`amount`** (1 to 1,000,000), `unit` (points, levels) | the player holds less; no player |
| `has_item` | player | **`item`**, `amount` (default 1), `name_contains`, `lore_contains`, `ignore_case`, `strict`, `armor`, `offhand` | the matching count is short; the item does not resolve; no player |
| `has_item_space` | player | **`slots`** (free storage slots, 0 to 36), or `item` with `amount` (room for that many) | the room is short; the item does not resolve; no player |
| `is_near` | world | `world` (default: the player's world), `x`, `y`, `z`, `distance` (templates allowed) | the player is outside the radius or in another world; a template does not render to a number in range |
| `in_world` | world | **`worlds`** (one name, or a list) | the player is in none of the worlds |
| `in_gui` | gui | **`id`** (a GUI id, such as `shop:menu`), or none for any GUI | no GUI is open, or not the one named |
| `var` | data | **`var`** (named as `set_var` names it), `op` (`==`, `!=`, `>`, `>=`, `<`, `<=`; default `==`), `value` (template allowed) | the comparison fails; an ordering between values that are not both numbers; a session variable outside a GUI |

1. **Eight built-ins, `var` and `slot_has` left out.** `var` needs the variables API (#211), which is not on main,
   so it is not registered; a later change adds it beside `has_exp` and needs no engine change. `slot_has` is #218's
   (§6.5). The eight are the whole of #210's list that main can support. `BuiltinLogic.conditions(providers, guis,
   items)` builds the list, and `register(api, owner, items, bridge)` registers it. *Added after #211 merged:* `var`
   resolves its variable at load exactly as `set_var` does (`MTR-0063` for an undeclared name, `MTR-0064` for a
   literal that does not fit the declared type or an ordering on a variable that is not a number), and compares two
   numbers (or numeric text) as numbers and anything else as text, equality only. A declared variable never written
   compares as its default; an absent run-local or session variable equals nothing, so only `!=` is true.

2. **The composite names are never a predicate.** `all`, `any`, `not` and `at_least` are read as composites by the rule
   compiler before it looks for a registered predicate (decision 5 of §16), so a condition with one of those ids would
   be unreachable. None of the built-ins takes one, and `BuiltinConditionsTest` asserts that the built-in ids and the
   registry lookups exclude them. Parse accept and reject for the reserved names is the compiler's, and is covered by
   `ConditionsInFlowsTest`, which shows `all:` is a group and not a predicate.

3. **`has_permission` asks Bukkit, not the permission provider.** `PermissionProvider` (§4.5) grants and revokes and has no
   check, and adding one would widen an SPI that addons already implement. The check is `Player.hasPermission`, which is
   what every plugin reads and which the active provider feeds: the session attachments that `give_permission` writes,
   and LuckPerms through the permissible it injects. A Vault permission service that Materione uses but Bukkit does not
   mirror is not consulted by this condition. Several nodes need all of them unless `min` says otherwise; a bare list is
   accepted as the shorthand, so `has_permission: [a.one, a.two]` reads as the mapping form.

4. **`has_money` resolves the currency exactly as `give_money` and `take_money` do** (§18 item 28): a named currency by
   `ProviderParams.resolve`, the default when none is named, and false when that is unknown or its backend is not bound.
   It reads through `CurrencyBackend.has`, so it never mutates. **Threading** follows §16 decision 17: a predicate runs on
   the player's region, and the backend's own thread is not consulted. On Paper every backend is safe there. On Folia a
   GLOBAL backend (Vault, the custom command pair) is read from the player's region. That is the same gap `balance()`
   has (§18 item 25), and it is closed by the same follow-up, a threading hook for predicates. It is not closed here.

5. **Experience counts the way `take_exp` takes it.** Points are the player's total experience and levels are the level,
   so `has_exp: {amount: 5, unit: levels}` is true exactly when `take_exp` of five levels would succeed.

6. **`has_item` shares the take matcher.** The matcher moved into `ItemMatch` (in `plugin.logic.support`) and
   `InventoryPlan.wanted` now delegates to it. Its defaults are the take behaviour (text ignores case, a vanilla
   reference matches only stacks with no Materione identity), so `take_item` is unchanged, and a test pins the two to the
   same predicate. The options `has_item` adds are these:
   - `ignore_case` (default true) applies to `name_contains` and `lore_contains`.
   - `lore_contains` matches when some lore line contains the text. `ItemView` gained a `lore` field, read from the
     stack's lore; the four-argument constructor stays for the take tests.
   - `strict` (default true) decides what a vanilla reference matches. With `strict: false`, `minecraft:paper` counts a
     custom item whose material is paper. A Materione reference is not affected. `strict` is the defined meaning of the
     word in §4.2; the spec did not say more.
   - `armor` and `offhand` (default true) decide whether those slots count. An excluded group is blanked, and its
     positions are kept (`ItemSlots.included`), so nothing shifts. `take_item` still counts every slot.
   - `model` from §4.2 is not in this change. It needs the model comparison the item studio defines, and no #210 issue asks
     for it.

7. **`has_item_space` counts the storage, and reads the stack size from the item.** Free slots are the empty entries of
   slots `0..35`, the slots a pickup fills; armour and offhand never count. Room for an item is, per slot, a whole stack
   for an empty slot, the rest of its stack for a matching partial one, and nothing for any other stack. The maximum stack
   size comes from the item's built stack, so a custom item with a stack size of 16 is measured at 16. `give_item`'s
   builder moved to `ItemTemplates` (in `plugin.logic.support`) so both read it the same way; its behaviour is unchanged.
   The pure room arithmetic is `ItemSlots.room`, tested without a server.

8. **`is_near` is a straight-line test in one world.** It compares squared distances, so the check is exact, and `world`
   is optional. A point with a template that does not render to a number in range is false, not an error: a condition
   that cannot decide does not grant. The numbers go through `Quantity`, so `x: "${vars.spawn_x}"` works, and the range
   limits are the world border's reach (±30,000,000 for a coordinate, up to 60,000,000 for a distance).

9. **`in_world` matches exact names.** Bukkit keeps world names case-sensitively, so `in_world: World` does not match
   `world`. A scalar and a list read the same way, and an empty list is rejected at load.

10. **`in_gui` reads a port, not a session.** `OpenGuis` (in `plugin.logic.support`) is the seam: one method,
    `openGui(Player)`, answering the id of the GUI the player has open. `BuiltinLogic.register` backs it with the
    player's current open session, read through the `GuiBridge` the `gui.*` actions use (§21), so
    `plugin.logic.condition` never depends on `plugin.gui`. Before the runtime binds, and in tests,
    `OpenGuis.none()` answers "nothing open", so the condition is false and never throws. It reads the session store
    rather than the `GuiSession` facet so that it answers from any trigger, not only inside a GUI's own rules. An id names a pack's GUI, so it is written qualified (`shop:menu`); a bare id means
    `materione:`, as for every Materione name.

11. **Conditions run on the player's thread, and none declares a thread.** A predicate reads live player and world
    state, so the interpreter hops to the player's region before it tests one (§16 decision 17). That is correct for
    every condition here except the money read's Folia gap (decision 4). The inventory and the location are the
    player's, and the experience is too, so no condition needs a hop of its own.

12. **A condition that cannot decide answers false, and throws nothing on missing state.** A missing player, an
    unresolvable item, an unavailable currency, a template that does not render to a number and an unknown GUI all
    answer `false`. A load problem is `MTR-0057` at the condition's step path, as for every predicate (§16 decision 5).
    An exception from a condition is contained by the interpreter as `MTR-0023`. **No new MTR code** was needed, and
    `DiagnosticCodesTest` stays at 60.

13. **Shorthands** are `permission`, `amount` (`has_money` and `has_exp`), `item`, `slots`, `worlds` and `id`. `is_near`
    has none, because a point is never one number. The description's `shorthand` names a declared parameter, so the
    vocabulary and the form agree (§14 decision 1).

**Tests.** Unit tests for each condition's parse (accept and reject) and test against a fake player
(`PlayerFake`, now public, with permissions, a world name, a position and a UUID), `ItemMatchTest` and `ItemSlotsTest` for
the shared matcher and the slot arithmetic, `BuiltinConditionsTest` for registration, categories, shorthands and the
reserved names, and `ConditionsInFlowsTest` for a condition in a rule `if:`, a step `if:`, a `choose` branch and an
`at_least`, plus an expression function call in an `if:`. `ProviderActionsTest`'s currency fakes are public so
`has_money` reuses them. `LogicCodeOrganisationTest` passes, so every class is under 400 lines.

**QA catalog (player-visible, auto mode).** `probe:probe_conditions` runs `give_permission`, `has_permission`,
`has_item`, `has_exp` and an `if:` on `contains(lower(player.name), 'pro')`, each reporting a chat line.
`logic.conditions-permission`, `logic.conditions-item-exp` and `logic.conditions-expression-function` read those lines back.
The expression entry is the one #209 asked for. The scenarios are on the `probe` pack; no `world` pack file changed.
The entries are authored and checked by `qa/runner.mjs check`; their ledger state is "never" until a main swarm records them.
A permission check after `take_permission` is not asserted, because a server operator passes any unregistered node, so
that result depends on the probe player's operator state. The unit tests cover the negative branch.

---

## 23. Decisions taken in #215 (G2: open commands, typed arguments, the requirement and deny, the bypass)

#215 shipped the open path's stages 2 to 4 (§6.1) and the command surface that starts it. The block is the
`OpenSpec` of #221 (§19), unchanged in shape. Each decision is reversible by the owner. The code is in
`plugin.gui.open` (the gate) and `plugin.commands` (the commands and the compile; they were in `plugin.gui.open` until #467), `content.gui.logic` (the block's reader) and
`behavior.flow.FlowCompiler` (two entry points). The diagnostic codes are `MTR-0066` to `MTR-0069`, reserved for
this PR; the surface is in [`plugin.md`](plugin.md) §5.

**The block and its compile**

1. **The reader moves to the logic package.** `GuiOpen` (the shape reader and writer of #221) moves from `content.gui`
   to `content.gui.logic`, where `GuiRules` lives, and its readers are public. `GuiRules` carries the block as
   `OpenSpec open` next to `behavior` and `flows`, so the one parse of a GUI reads every logic section. A shape
   problem of the block (a non-mapping, an unknown key, a bad command name, an unknown argument type) is a
   `GuiRules.Problem` at `open`, reported as `MTR-0021` like a malformed `behavior:`. #221's typed write is unchanged.
2. **The block is typed at load, into an `OpenPlan`.** `OpenPlan.compile` runs with the live view, as the rules do.
   Each argument becomes an `OpenArgSpec` with its `ArgType` (`string`, `int`, `number`, `player`, `boolean`,
   `choice`) and its default in canonical text. A default that does not read as its type is dropped with `MTR-0066`
   and the argument keeps its other settings. A `choice` with no `suggest:` list reads as a `string`, with the same
   code. Nothing reads a raw map at open time (§9 rule 5).
3. **The requirement and the deny steps compile through the flow compiler, under the `gui.opening` trigger.** The two
   entry points are `FlowCompiler.condition` and `FlowCompiler.denied`. Their problems name `open.requirement` and
   `open.deny`, not `behavior.<n>`, so a diagnostic points at the block the author wrote. `denied` compiles only the
   flows its `call`s reach, because the GUI's own rules already report the rest.
4. **The diagnose stage reports the block.** `GuiRuleDiagnoser` adds the block's own compile problems to the rule
   problems, so the pack compile and `gui.validate` agree (as §21 decision 18 does for `behavior:`).

**The gate (stages 2 to 4)**

5. **The order is permission, arguments, requirement.** `OpenSpecGate` checks `open.permission` first (a refusal is
   `MTR-0055`, `DENIED`), then types the arguments (a failure is a usage line, `USAGE`), then evaluates the requirement.
   A GUI with no `open:` block is admitted with its arguments as given, as before.
6. **Arguments are typed by one parser.** `ArgParser` takes the raw text every surface passes (the command, the API,
   `gui.open`) and returns canonical values in the block's order. A missing argument takes its default; a missing one
   with no default is an error unless it is optional; an argument the block does not declare is an error, so a
   misspelt name is reported rather than ignored. Every refusal names the argument and then the usage line,
   generated from the block: `usage: /shop <item:string> [category:all|tools=all] [target:player]` (a required
   argument in angle brackets, an optional one or one with a default in square brackets).
7. **The requirement is never negated through the engine.** A rule's `if:` that throws counts the whole condition
   as false (§4), so `not` of a broken requirement would be true and grant the open. The gate instead tests the
   requirement itself: a throw, or a requirement that did not load, is "not met". `OpenPlan.requirementMet` is the one
   place that decides, and it is unit-tested with both.
8. **The bypass skips the requirement and nothing else.** `materione.gui.bypass.<pack>.<id>` skips the requirement
   (stage 4). It does not skip the permission or the argument types, which are the author's contract with the caller.
9. **A failed requirement runs the deny steps as the player's run, then refuses.** The deny steps are a rule with no
   condition, run by `FlowInterpreter.start` with the player's run owner and no cancellation key, so the window is
   never opened and a `cancel` on it cannot reach them. The requirement's context is `GuiContexts.open`: the player,
   the server, `gui.id`, the typed `gui.args`, and empty session variables and pages, since no session exists yet.
10. **A broken requirement fails closed, and says so.** A requirement that did not load refuses every player without the
    bypass, with `MTR-0068` at error severity, reported at pack compile. The author sees it before a player does.

**The commands**

11. **Each alias is a Brigadier literal, and the arguments are typed in the client's tree.** The client validates the
    value as it is typed and offers completions (issue #96: a typed argument has to reach the synced tree, or the
    client refuses the command before the server sees it). `int` is an integer, `number` a double, `boolean` a bool,
    `string` and `choice` a quotable string (a choice offers its values), and `player` a word the server resolves by
    exact online name, so a selector such as `@a` is not accepted. The server parses every value again with the live
    block, so a value the client let through, or a raw packet, gets the usage line and never a crash.
12. **Every prefix of the arguments reaches the executor.** `/shop` and `/shop tools` both run the handler, which decides
    whether what it has is enough. Declaring an argument optional or with a default is therefore a server decision,
    which keeps the tree's shape a function of names and types only.
13. **Activation is `requires`, not removal.** Brigadier 1.3.10 cannot remove a child, and `addChild` merges a same-name
    child, so an alias that is no longer declared stays in the tree with a predicate that reads the handler's declared
    set. It is neither suggested nor run. An alias that comes back reuses its node.
14. **The argument shape is fixed in the node, and a change to it is a reload.** Names and Brigadier types are fixed when
    the node is made, so a swap that changes them asks for a reload, which rebuilds the node from the built registries
    (decision 15). Only a reload that does not put the new shape in the tree is `MTR-0069`, and that applies at the next
    restart. Changes to defaults, optionality, `suggest:`, permission and the GUI itself are live without any reload: the
    executor reads the block at run time.
15. **The command tree is registered from the built registries, and a swap reloads only for a change it needs.**
    `OpenCommandRegistrar` registers from the server's `COMMANDS` lifecycle event, which Paper fires at start and on a
    server reload. The registrar is valid only inside that event: a registrar kept from it and used later throws
    `cannot access the dispatcher in this context` (found on Paper 1.21.4 by the harness scenario). The apply of a
    boot runs on the global thread after `onEnable`, and the COMMANDS event at boot runs before it, so the live view is
    still empty then. The registrar therefore registers from the **built** registries instead: `PipelineService.builtRegistries()`,
    the registries of the latest reload, which are built synchronously and are what the first apply publishes. At boot the
    tree holds the aliases the first swap will declare, so the first swap asks for nothing.

    A content swap never edits the tree. It updates the declared set at once, which hides a removed alias through its
    `requires` and needs no reload. It asks for a reload (`Server#reloadData`, which fires the COMMANDS event again with the
    built registries) only when a declared alias is missing from the tree or was built with another argument shape. Such
    a reload is **explicit**: it is logged as `MATERIONE-OPEN-TREE reload aliases=[...]`, and a request inside five seconds
    of the previous reload is logged as `MATERIONE-OPEN-TREE reload-deferred` and coalesced into one scheduled retry after
    the window. The retry reads the latest live aliases without another content swap; removed claims cancel it (#246). The
    registrar cancels the timer on shutdown. An alias the reload does not put in the tree is `MTR-0069`, and no
    further reload is asked for it until the next COMMANDS event.

    **Measured:** unchanged content at boot causes zero `reloadData` calls. `PaperOpenCommandIT` asserts it (no
    `MATERIONE-OPEN-TREE reload` line before the content change), and `OpenCommandRegistrarTest` counts the reloads.
    Reloading after an added alias was proven on Paper 1.21.4: `reloadData` fires the reloadable COMMANDS event there.
    The cost of `reloadData` (it re-sends recipes, advancements and tags, and reloads datapacks) is paid only by a swap that
    changes the set of open commands or their argument shapes.
16. **Conflicts are checked before registration.** Paper's main label overrides an existing command, so the registrar
    checks the dispatcher and the command map first. A name already taken, or claimed twice, is skipped with `MTR-0067`;
    the GUI's other aliases and `/materione gui open` still work. The check ignores the registrar's own earlier nodes, so
    a reload does not conflict with itself.
17. **Command execution is the API cause, and needs no `materione.gui.open`.** A player who types a GUI's command is
    governed by the GUI's `open.permission`, not by the API node. The command parses its arguments before the sender
    check, so a console sender gets the usage line for a bad value and the message "only a player can open" for a good
    one. Each outcome is logged as `MATERIONE-OPEN-COMMAND alias=<a> outcome=opened|refused|usage|console`, which the
    harness and the QA suite read.
18. **Result mapping.** `OpenResult.Outcome.USAGE` is new: an argument refusal, distinct from `DENIED` (permission or
    requirement). The API maps `USAGE` to `CONTENT_SCHEMA_INVALID` and `DENIED` to `PERMISSION_DENIED`, as before.

**Scope and what is not here**

19. **No per-argument permission.** The `OpenArg` of #221 has no `permission:` key, so `open.permission` is the one node,
    and it applies to the whole block. Adding a per-argument node would be a schema change, and the schema is shared
    with the editor.
20. **No Bedrock or prompt surface.** Dialog and prompt arguments are §11's and #220's. The chat argument flow is out.
21. **The probe run is deferred to the blessing run.** The in-game behaviour (the typed command reaching the client's
    tree, the window, the requirement and deny messages) is in `qa/catalog/opencommands.mjs`, and needs a real client.
22. **Numbering.** §20 is taken by #211 and §22 is reserved by an open PR; this section takes the next free number and
    is reconciled at merge.
23. **Since #330 the block is not only a GUI's.** The names, arguments, permission, requirement and deny this section
    describes are one schema (`CommandSchema`) that a `command` document reads too, and `open:` is defined as the sugar of a
    command whose flow opens the GUI. Nothing above changed: the registrar, the gate, the usage line and the tree serve both
    kinds. The command document, its trigger `command.run`, the typed `args.*`, the console rule and the collision order are
    in §40.

---

## 24. Decisions taken in #216 (G3: dynamic slots, item sources, the refresh clock and the render diff)

#216 makes a GUI's item slots depend on the viewer and the clock. It builds on the G1 seams (§21): the
`LayerVisibility` the router asks, the per-session renderer that `WindowRenderer` became, `gui.refresh`, and the
ticker. Where the design left a choice open, these are the choices. Each is reversible by the owner. The code is in
`content.gui` (the item layer and its compile), `api.logic` (the SPI), `behavior` (the condition entry point),
`plugin.gui.dynamic`, `plugin.gui.render`, `plugin.logic.item` and `integrations.items`.

**Layer fields**

1. **`visible_if` is a common layer field** that takes the shape a `require` takes (§2.3): an expression, a
   predicate or a composite. The gui type keeps the authored value; the engine compiles it when the live view is
   built, through `FlowCompiler.compileCondition`, so `content` stays rule-free. Its `${root}`s are checked against the
   GUI's roots (`player`, `server`, `vars`, `gui`; `MTR-0060`). The condition's own problems keep their codes.
2. **At #216 only item layers honoured `visible_if`.** A raster or text layer (`panel`, `button`, `slots`,
   `image`, `paint`, `text`) is drawn into the background or the title, which cannot change while a window is open
   until overlay glyphs exist (§6.4, #217). A `visible_if` on one of them skips that layer (`MTR-0052`) with the
   reason in its message, rather than shipping a layer that silently ignores its condition. #217 lifts the
   restriction for supported raster and text layers (§6.4). Conditional input-slot layers remain rejected.
3. **`name` means two things on an item layer, and the file does not change.** It is the layers-panel label on every
   layer, and on an item layer it is the item's display name, a template (§6.3). The value reaches a component only
   when the component's schema declares a `name` parameter, so a component that does not declare it still receives
   only its own fields. No shipped pack names an item layer, so no existing GUI changes meaning.
4. **`item:` takes a plain id, a template or one named source.** `shop:gui/sword` and `minecraft:diamond` are plain
   ids; `${entry.item}` is a template; `head: {player: "${player.name}"}` and `player_slot: main_hand` are named sources
   (§4.4). A layer has exactly one of `item:` and `frames:`. The authored value is kept as written, so a plain-id file
   is byte-identical through a load and an untouched save.
5. **The overrides are typed at load and templated at render.** `amount` is a whole number from 1 to 64 or a template,
   kept as text. `name` is text. `lore` is text or a list, with `lore_mode` (`replace`, `append`, `prepend`; it needs
   `lore`). `glint` is `true`, `false` or a template. Templates use the engine's `${...}` and `%token%` syntax.
6. **`refresh: <ticks>` is the GUI's clock**, a whole number of at least 1. An invalid value is reported (`MTR-0071`)
   and the GUI has no clock; `gui.refresh` and reloads still update it.

**Dynamic analysis**

7. **A layer is dynamic when** it has a `visible_if`, a templated override, a templated source, the `player_slot`
   source (the viewer's own item), or `frames`. The refresh clock re-reads only dynamic layers. A static layer keeps the
   item it resolved when the window was built: a fixed `head: {player: Notch}` is resolved on open, on `gui.refresh`
   and on a reload, not on the clock.
8. **A slot is static or per-viewer, as a whole.** A slot whose every layer is a plain id with no override and no
   condition is a static slot (`CompiledGui.slots`), resolved at apply time as before, and two static layers in one
   slot keep the existing skip (the earlier one, `MTR-0052`). Any other layer in a slot moves the whole slot to the
   per-viewer placements, where its layers compete by layer order: the topmost visible layer wins, and a visible layer
   that resolves to nothing leaves the slot empty. A stack of conditional items in one slot is the intended use, so
   the compile never skips one of them.

**Item sources (§4.4)**

9. **`ItemSource<P>` is an `api.logic` SPI** with `parse(ParamReader)` (pure, typed at load) and
   `resolve(P, LogicContext)`, which returns an `ItemResolution`: an `ItemStack`, nothing at all, or a reason. It is
   registered through `LogicApi.itemSources()` under `RegistryKeys.ITEM_SOURCES` (`item-sources`). The built-ins
   register exactly as an addon does: `materione:item` (plain ids, `minecraft:` materials and templates, resolved with
   the `give_item` rules), `materione:head` (exactly one of `player`, `texture` or `base64`; a literal skin is checked
   at load, a template when it renders) and `materione:player_slot` (six slots; an empty slot shows nothing). The
   source's shorthand is the key a scalar payload is read under.
10. **An unresolvable source is a barrier, never air.** The slot shows a red barrier whose lore is the reason, and the
    failure is reported as `MTR-0072`, rate-limited per layer. A source name the registry does not know at load skips
    the layer with `MTR-0070`.
11. **The optional catalogues are registered while their plugin runs.** `hdb`, `itemsadder`, `nexo` and `mmoitems` are
    registered by `integrations` when their plugin is enabled, and refreshed when it enables or disables. While the
    plugin is absent the name is still accepted at load and resolves to a barrier that names the plugin, so a pack that
    uses `hdb:` loads on a server without HeadDatabase. **Deviation from the issue text:** the four provider APIs are not
    pinned in the version catalogue and are not compiled in this build, so their adapters reach the provider by class
    name through reflection (`integrations.items.OptionalItemSources`) instead of through `compileOnly` calls. Those
    call paths are not exercised here. A call that does not match the installed provider shows as a barrier with the
    reason, so a mismatch is visible in the window. Pinning the provider jars and replacing the reflection with direct
    calls is the follow-up once the jars are verified.
12. **The item of a `materione:item` source is read from the live registry at each resolve.** A reload is seen by the
    next render, and a removed item becomes a barrier.

**Rendering and the refresh clock**

13. **The renderer is pure; one class touches the server.** `SlotRenderer` decides each slot from the compiled GUI and
    the viewer's `LogicContext` and returns a `Rendered`: the slot appearances, the hidden layers and the resolved
    layers. It keeps no state, since one instance serves every window on every region thread. `GuiRenderer` renders,
    diffs and paints. `RenderDiff` writes only the slots whose `Appearance` changed, and clears a slot that emptied.
    Bukkit stacks are built for a changed slot only.
14. **The click router routes to what the viewer sees.** The window remembers its last render on the session, and
    `LayerVisibility.RENDERED` answers from the layers that render hid. A click therefore follows the window, not the
    authored file. The router and the click policy (G5, #218) are otherwise unchanged.
15. **A partial render re-reads only the dynamic layers** and keeps the resolved appearance of the others. A full
    render re-reads everything, which is what a reload and `gui.refresh` need.
16. **The refresh clock is a second chain on the window's ticker.** Its period is the greatest common divisor of
    `refresh:` and every animation's frame rate. Each link re-reads the live GUI and the window, so a reload that
    changes the clock is followed, and the chain stops with the window. Animations run on the window's own clock, so
    `frames` animate even without `refresh:`.
17. **Failures hide or fall back, and are reported.** A condition that throws hides its layer; a template that throws
    renders its fallback (the line is dropped, the amount is 1). Each is `MTR-0073`, rate-limited per problem
    (`LoggingRenderReporter`, one entry per problem per 30 seconds).
18. **The refresh budget.** `DynamicSlotsPerfTest` measures one refresh pass for 50 sessions of 54 dynamic slots at
    0.43 ms (best of three) on the development machine, against a budget of 2 ms per pass (plugin.md §9). The pass
    measures the decision and the diff. The Bukkit stack build of a changed slot is outside it, because it needs a
    server to time, and only a changed slot pays it.

**Diagnostics and the engine**

19. **Four codes** (plugin.md §5): `MTR-0070` (an item source is unknown or rejects its parameters; the layer is left
    out), `MTR-0071` (the `refresh:` clock is not a whole number of ticks of at least 1), `MTR-0072` (a source could
    not resolve at render; a barrier is shown) and `MTR-0073` (a dynamic layer failed at render). A wrong layer shape
    is still `MTR-0052`, and a condition's own problems keep their codes (`MTR-0021`, `MTR-0022`, `MTR-0057`,
    `MTR-0060`).
20. **An unterminated `${` is malformed in a GUI.** The expression engine keeps an unclosed `${` as literal text, which
    is its existing behaviour and is unchanged. In a GUI layer it is reported as `MTR-0022` at its field, because it is
    almost always a typo that would otherwise show on screen.

**Deferred**

- Raster and text `visible_if` and raster animation were deferred to #217 (§6.4).
- `item-sources` in the behaviour vocabulary (`VocabularyBuilder`) and the editor's palette for them: with the Logic
  tab (#223, #224; the vocabulary part shipped in §31 decision 17). The studio's layer inspector reads the new fields from the self-description, but no inspector
  control for `visible_if` or `frames` is designed here.
- The click policy for input slots: #218.

---

## 25. Decisions taken in #218 (G5: input slots, gui.slot_change, player_inventory: locked, click-policy rows)

#218 ships §6.5, including the best-effort recovery journal (decision 12; it is not a crash journal, see
[`gui-input-durability.md`](gui-input-durability.md) §2). The code is in `plugin.gui.input` (`SlotPolicy`,
`Transfers`, `InputSlots`, `WindowPolicy`, `ClickTarget`, `InputSlotClicks`, `InputReturns`, `InputSlotOps`,
`SlotOp`, `InputJournal`, `GuiJournal`), in the `GuiListener` policy table, in `content.gui` (`SlotsComponent`, `PlayerInventory`,
`CompiledGui.Input`), and in the logic vocabulary (`slot_has`, `gui.slot_item`). Each choice below is reversible
by the owner.

**Which slots are input slots**

1. **The topmost layer decides.** A container slot is an input slot when the topmost visible clickable layer over
   it (the `ClickMap`, §6.2) is a `slots` layer with `input: true`. A button or item above the wells takes the
   slot, so the layer above owns the click and the slot is not an input. `CompiledGui.inputs()` lists the input
   slots; `region: player` cannot take `input: true` (the layer is skipped with `MTR-0052`).
2. **Static items do not sit in input slots.** An item layer under an input slot is dropped from the live view with
   `MTR-0075`. A static item there could be taken out, and the player's items own the slot.
3. **The policy is read from the live view at click time** (`WindowPolicy`). A reload is followed by the next click
   with no copy to keep in step. A window keeps its own record of the input slots it was shown with
   (`GuiMenu.inputSlots()`), and only that record decides what is returned (decision 8).

**Changes, and why they cannot duplicate**

4. **`gui.slot_change` fires before the change.** The change is predicted (`SlotPolicy`) and the rules see it: `slot.item`
   is what the slot will hold, `slot.previous` what it holds now. A cancel refuses the change, so the click is
   cancelled and nothing moves. A post-change revert would race the player's next click, and a revert of a shift
   that spans slots is not exact, so the check happens first. **Consequence:** a rule cannot delete the incoming
   item from the slot it is about; `accept:` refuses it instead. The trash recipe (#222) therefore needs its own
   mechanism. This is flagged for the owner. *(Resolved by #248, §36: the rule waits one tick, so the item has landed, and
   consumes what the slot then holds with `custody.consume`.)*
5. **Single-slot clicks are the server's, after the check.** Pickups, places, swaps, the number-key and offhand swaps
   touch one input slot and the cursor or the viewer's slot, so the server applies them and conserves the items.
   Drop keys, the creative clone and unknown actions take an item out of the window with no check, so they are
   refused in an input slot.
6. **Multi-slot moves are ours, all or nothing.** The server's own shift-click would also fill the window's display
   slots, and its double-click collection would take display items to the cursor. So a shift-click into the inputs,
   a shift-click out of one and a double-click collection are planned (`Transfers`, which conserves items) and
   applied here. Every slot the plan changes is checked first, and one refusal refuses the whole move. A drag is the
   server's, checked with the item each touched slot will hold (`getNewItems()`), and it is refused if it touches
   a non-input slot of the window.
7. **Accept is fail-closed.** `accept:` is an expression or predicate on `slot.item` (the candidate), compiled once per
   live view with the flow engine (`FlowCompiler.compileCondition`) and checked against `gui.slot_change`'s
   variables. One that does not compile, or throws while tested, accepts nothing (`MTR-0074`).
8. **Returns.** Items in input slots go back on every end of a session (close by the player, quit, a flow's close,
   replacement, removal) and on every window close, including a window that was replaced. The player's inventory
   gets them first; what does not fit drops at their feet. A shape-changing reload's re-open carries them to the
   new window, and a rewrite keeps them in slots that are still inputs and returns the rest. Nothing is deleted unless
   a flow says so (`gui.slot_item: {op: clear}`).
   **Correction (#247 design, [`gui-input-durability.md`](gui-input-durability.md) §2 W4):** "nothing is deleted" is not proved on
   three paths. On death the window closes after the death drops are fixed (`ServerPlayer.die` fires `PlayerDeathEvent`, then
   `closeContainer(DEATH)` [SRV, both pins]), so the returned stacks probably vanish at respawn without `keepInventory` [U]. What
   does not fit is dropped into the world, a store separate from the player's data, so a crash around that drop can lose or
   duplicate it. An item picked up from an input slot is on the cursor, which is in no save.
9. **`gui.click_inventory` fires for every click in the viewer's inventory while the window is open**, whatever
   `player_inventory` says, and a rule may cancel it. A `locked` inventory cancels the click regardless. A free one
   lets it through.
10. **`player_inventory: locked` cancels every viewer click.** A player with a locked inventory cannot pick up from
    it, so cannot put items into the input slots; the inputs are then only fed by a flow. This is the design's
    consequence, not an oversight, and the GUI author chooses it.

**Actions and conditions**

11. **`slot_has` reads the input slots only** and is false for any other slot, an empty one, or a run with no open GUI,
    so a check never reads a display item. **`gui.slot_item`** is one action with `op: clear|take|set_amount|repair|enchant`.
    It needs the run's own open session, as `gui.close` does. `clear` deletes the item and `take` gives it to the player.
    `set_amount` with 0 clears the slot.

**Deferred**

12. **The best-effort recovery journal, not an acknowledged crash checkpoint (#247).**
    While a window is open, the items in its input slots are not in the player's saved
    inventory, so a hard crash after an autosave would lose them. The journal (`InputJournal`, with its Bukkit side
    `GuiJournal`) copies them into the player's persistent data under `materione:gui_inputs`. It holds exactly the
    items the player's current window holds in its input slots: it is written right after each change the server
    has already made (the manual moves, the `gui.slot_item` operations, a rewrite, a re-open), and one tick after a
    single-slot click or a drag, which the server applies after the handler returns. Every release (a close, a
    quit, a flow's close, a replacement, a removal) gives the items to the inventory and then mirrors the empty
    window into the journal in the same tick. A join gives back
    what the journal still holds, once: `restore` clears the journal before it hands the items out. The journal
    follows the player's current window, not the window that closed, so a stale window's close never clears the
    journal of the window that replaced it. The rules are unit-tested over a plain store (`InputJournalTest`),
    including the normal-close case that must never return an item twice. These tests establish the in-memory
    journal invariant and normal recovery behavior, not process-crash durability. The one-tick mirror delay is
    an exposure, but it is not the only proven persistence boundary: player inventory, cursor and PDC must be
    checkpointed consistently with every autosave and disconnect save before no-loss/no-duplication can be claimed.

    **Checkpoint feasibility outcome.** The supported Paper APIs do not expose an immutable owner-thread player
    snapshot plus an asynchronous, ordered writer and definitive persistence acknowledgment. `Player.saveData()`
    is synchronous and returns `void`; moving it off-owner would serialize live player state unsafely, while
    invoking it on-owner violates D8's IO rule. A separate plugin journal or `.dat` replacement cannot coordinate
    subsequent server saves. Pre-login gating alone does not supply the missing online checkpoint.
    This was checked against Paper commit `73116ac9dd1d55c59db411a55de7e91e025a0c80`:
    [CraftPlayer.saveData](https://github.com/PaperMC/Paper/blob/73116ac9dd1d55c59db411a55de7e91e025a0c80/paper-server/src/main/java/org/bukkit/craftbukkit/entity/CraftPlayer.java#L1530-L1538),
    [player-data persistence](https://github.com/PaperMC/Paper/blob/73116ac9dd1d55c59db411a55de7e91e025a0c80/paper-server/patches/sources/net/minecraft/world/level/storage/PlayerDataStorage.java.patch#L14-L28),
    and [autosave/disconnect ownership](https://github.com/PaperMC/Paper/blob/73116ac9dd1d55c59db411a55de7e91e025a0c80/paper-server/patches/sources/net/minecraft/server/players/PlayerList.java.patch#L339-L345).
    A server extension must coordinate all saves, checkpoint inventory/cursor/PDC plus a transaction marker, and
    acknowledge an ordered asynchronous write before interaction resumes. The owner elected not to expand this
    follow-up to that extension: #247 and the dependent consume-incoming trash action (#248, used by #222) were blocked
    (**lifted since, see the Refinement below**). Existing input behavior stays enabled and best-effort; no crash-safety or transactional-consumption
    guarantee is introduced. Live QA of normal input transfers cannot substitute for a process-kill checkpoint test.

    **Refinement and the lift of the block (design PR #303).** [`gui-input-durability.md`](gui-input-durability.md) shows that the
    sentence "a separate plugin journal … cannot coordinate subsequent server saves" is too broad: the player's persistent data
    is written in the same compound as the inventory (`Entity.saveWithoutId`, one file replace, [SRV] both pins), so a marker
    changed in the same owner-thread step as the inventory is atomic with it without coordinating any save. What stays
    unavailable is a platform acknowledgment of *when* a snapshot is durable. The design proposes an account in the snapshot
    plus an exit ledger (G1 to G4 there) and the owner ruled on PR #303 (design §14 Q1b) that the design's tier-3 barrier, a rate-limited `Player.saveData()` plus
    a file read-back, **is** the platform acknowledgment this outcome said was missing: **the block on #247 and #248 is
    lifted.** The design is the accepted one (per-layer `durable: required | best_effort`; the cursor exposure accepted;
    Folia a supported target, its probe jar approved; death released at `PlayerDeathEvent`; the D8 exception for the
    barrier noted on D8). #248 now waits on the design's rows M, L, P, B and H, not on an outside acknowledgment.
    **Row P (#310) built the tier-1 runtime** for `durable: required` layers (see the design's *Row P as built*): their
    clicks are manual, with no deferred mirror, so the W1 window does not exist for them, and a death releases inside
    `PlayerDeathEvent` (W4) for them. The `best_effort` journal described above is unchanged and still not crash-safe (its
    one-tick mirror for server-applied clicks and drags can lose or duplicate an item across an autosave, W1; a death still
    loses the items of a `best_effort` window). **Row B (#311) built the tier-3 barrier and the tier-2 exit path** (see the design's *Row B as built*). **Row H (#313) built the kill-boundary IT** (`PaperEscrowCrashIT`; K23 and the real tier-2 rows with #248): every row of the crash-boundary table now has a test against a real kill. The word *crash-safe* stays gated on rows M, L, P, B and H being green on main, the owner's gate (the design's header); until then the product calls a `required` layer tracked and recoverable, no more.
13. **The `layer:` filter of `gui.slot_change` is not yet checked against input layers.** A rule that names a non-input
    layer loads and never runs. `GuiRuleDiagnoser` checks that the layer exists, not that it is an input.
14. **The QA entries (`guilogic.input-slots`, the `trade` fixture) are written but not run here.** They need a real
    client and are blessed on main.

**Diagnostics.** `MTR-0074` (accept does not compile), `MTR-0075` (static item under an input slot) and `MTR-0076`
(an input-slot setting that cannot apply; since row S of #247 (#308) also `durable: required`, ignored on a non-input
layer; an input layer's `durable: required` is no longer a load warning since row P: whether the server can honour it is the
capability check, `MTR-0100`), documented in plugin.md §5. The next free code is `MTR-0104`.

**Seams this leaves for later issues.** `GuiStack.inputClicks()` is the input collaborator the router consults;
`GuiBridge.Services.inputs()` is the port `gui.slot_item` reaches the window through (`InputSlotPort`). `slot_has` (a
condition, in `BuiltinLogic.conditions`, §22's convention) reads the window through the `SlotPeek` port
(`plugin.logic.support`), which `BuiltinLogic.register` builds from the same bridge, as `in_gui` builds `OpenGuis`.
The trash recipe (#222) was the one place expected to extend it; since #248 it is a composition over `custody.consume`, which
reaches the window through the same port (`InputSlotPort#consume`, §36).

---

## 26. Decisions taken in #223 (W1: the studio Logic tab)

#223 shipped §7.1 to §7.3: the **Logic** tab beside **Design** in the GUI studio, a node canvas over the same
document. Everything it edits goes through the authoring ops of §19 (`logic.vocabulary`, `logic.validate`,
`logic.edit`); no op, no wire shape and no protocol golden changed. The code is `webui/src/lib/logic/` (pure
rules) and `webui/src/lib/studio/logic/` (the components). Each decision is reversible by the owner.

**The model: a projection, never a second copy**

1. **The canvas holds no state of its own.** A GUI's `behavior:` and `flows:` are two top-level fields the GUI
   studio already carries verbatim (gui.md §2). The canvas draws `project(block)` and recomputes it after every
   edit. `unproject(project(block))` rebuilds the very same tree, key order and unknown fields included, so loading
   and saving an untouched logic block is byte-identical (§12 decision 3, master §5.6). A node carries the authored
   map it came from, minus the lists its ports feed; keys the projection does not understand (a `then:` on an
   action, a `choose:` that is not a list) stay in that map and are written back. The round trip is tested over
   every YAML fixture in the repository that holds logic, plus the studio's own.
2. **Ports are the ones §7.2 names, with `after` first.** A trigger and a flow entry have `out`; an action, `wait`,
   `stop` and `call` have `after`; `if` has `after`, `then`, `else`; `require` has `after`, `steps`, `failed`;
   `choose` has `after`, one `case N` per case, and `else`. The sequence continues from the first port, so a lane
   of steps runs level and the branches hang below it. Nothing is lost: a step after a `stop` still has its place.
3. **Layout is deterministic and a stored position wins.** A rule is a lane (trigger left, steps right), a branch
   hangs below its node, and the lane continues to the right of the branches; lanes stack, rules first and named
   flows after. A node with `node: {x, y}` is drawn there and only that node. A named flow has no position of its
   own (§19 decision 7), so a flow entry follows the layout.

**One history, one save**

4. **The Logic tab edits the GUI studio's document, so undo, redo, dirty and Save are shared with Design.** Each
   canvas gesture is one history command that holds the logic block before and after, the `LogicEdit`s that made
   the change, and the edits that take it back. Ctrl+Z from either tab undoes whatever was done last.
5. **Save replays the edits through `logic.edit` (D6).** The studio keeps a log of the edits the server has not seen,
   in step with the history: a new command appends its edits; undoing one that was never saved drops them (so a
   refused edit that was undone never reaches the server); undoing one that was saved appends its inverse, and
   redoing it appends the edits again. Consecutive position edits of one node fold into the last. A save sends
   the Design fields through `authoring.setFields` first (when they changed), then the log through `logic.edit`,
   at the revision the first one produced. If only the logic part fails, the layout is saved and the logic stays
   dirty, so a retry sends only the logic. A refusal shows the server's message and the step path it names.
6. **A log that falls out of step with the history saves the whole block instead.** It must not happen. If it
   does, `behavior:` and `flows:` go through `authoring.setFields`, which is also an existing edit op, rather than
   a guess. The compiler still checks the result (fail-soft, §10).
7. **A server without the `logic` capability gets a notice, not a broken canvas.** The tab says what is missing;
   the in-browser demo has no capability list, so it shows the notice too. A file whose logic cannot be drawn
   faithfully (a `behavior:` that is not a list) is shown as an error and not edited here; the studio's own
   read-only rule (gui.md §6) applies too.

**The structural rules**

8. **There are no loose nodes.** The file is a tree of lists (D1), so every node is in one. A new node is born
   spliced into a wire: from the palette at a port, after the selection, or at the end of the last rule. A wire is
   never "deleted" on its own, because it would leave a node the file cannot say. Deleting a node removes it with
   what hangs below its branches, and the chain closes up.
9. **A gesture is judged by what the file can say, and a refusal is one line.** Dragging from an output port onto
   a node is refused for a **join** ("*X* is already fed by *Y*; a node has one input"), for **fan-out** ("*A*
   '*port*' already feeds *B*; fan-out is not allowed") and for a **cycle** ("That would make a loop: *X* already
   leads to *Y*"), and nothing can feed a trigger. The way to rewire is the input end: dragging a node's input dot
   onto a **free** output port lifts the node and every step behind it and moves them there (`moveStep` per
   step, in order); the same three refusals apply to that drop. The status line says what a drop would do while
   the pointer is still down. Releasing a wire from a free port on empty canvas opens the palette at that port;
   from an occupied port it is the fan-out refusal.
10. **Writing to a branch the file does not have is a replacement.** `patchStep` never edits a branch key (§19
    decision 3), so an `if` with no `else:` gets one by removing the step and inserting a copy that has the empty
    list, at the same path, with its `node:` and everything under it. Adding or removing a case of a switch is the
    same replacement. A new `if` is born with `then: []` only, so nothing is invented that was not used.
11. **Known limit of `moveStep` the canvas steers around.** The server refuses a move whose target path starts
    with the source path (§19 decision 4), which also catches moving a step into a branch of a later sibling,
    where the target index is read after the removal. The canvas never plans that move: a chain lifted by its
    input includes every later sibling, so a drop below it is a cycle and is refused first.

**The palette and the nodes**

12. **The palette is the vocabulary and nothing else.** Grouped as Triggers, then the action categories in the
    server's order, then Conditions, then Flow (the control steps first). An add-on's category appears in its place
    and takes the add-on colour. Only the triggers the resource's host fires are listed (`firedBy` contains
    `gui`), and all of them when none match. An entry whose provider is unavailable is greyed with the server's
    reason and **cannot be inserted**; an existing node for it carries a warning with the same reason. (Reversible:
    insertable with a warning is the other reading of "greyed with reason".)
13. **A new node is born with the shape its entry implies.** An action with a `shorthand` starts as
    `{id: <parameter default or empty>}`; one with no required parameter is a bare name (`- gui.close`); one with
    required parameters and no shorthand starts as `{id: {}}`. `if` and each switch case start as `"${true}"`,
    `wait` as 20 ticks, `call` as the first named flow. The compiler's verdict then points at what is missing.
14. **Colour is a fixed token palette keyed by registry category** (§7.3): `--logic-trigger`, `-player`, `-gui`,
    `-economy`, `-data`, `-world`, `-server`, `-flow`, `-integration`, and `--logic-addon` for any other category.
    Wires take the colour of the node they leave, drawn as stepped lines on a four-pixel grid with a dust pixel at
    each bend.
15. **One inline field per node, until the inspector (#224).** An action, `if`, `wait` or `call` with a one-line
    value edits it in place (`patchStep`); a trigger edits its selector. Everything else is read-only on the
    node. This is a stop-gap so a flow can be built end to end; typed fields, template autocomplete and
    condition stacks are #224. *Shipped in §31: the inspector is the full editor, and the inline field stays as the
    quick edit of a one-line value.*
16. **The compiler's verdict is drawn on the nodes.** The draft is sent to `logic.validate` once editing pauses
    (the Design tab's pause), and each diagnostic goes to the deepest node whose step path is a prefix of its
    `field`. The vocabulary adds the two things only it knows: an unknown or unavailable action.

**Accessibility and motion**

17. **Every gesture has a keyboard route.** Arrow keys walk the graph (right follows the sequence, left goes
    back, up and down walk the nodes in order), `Enter` edits a node's value or, on a focused port, opens the
    palette there, `/` opens the palette, `Delete` removes, `F` frames the selection (or everything), `0` fits,
    `1` is actual size, `+` and `-` zoom, `Ctrl+Z` undoes. Nodes are a roving-focus tree. The page never
    scrolls sideways: the canvas pans inside its own box. Camera moves use the `--motion-*` tokens, so they are
    instant under reduced motion; the only animation is the loose wire's dashes, and only when motion is allowed.

**Deferred**

- ~~The node inspector, typed fields and condition stacks (#224)~~ (shipped, §31); ~~the Script tab, Design ↔ Logic
  linkage and the recipes panel (#225)~~ (shipped, §33); ~~the live trace (#226)~~ (shipped, §32).
- Multi-select, copy and paste, and moving a single node out of the middle of a chain (the chain-lift above moves
  a node and what follows it).
- Dragging a flow entry (a named flow has no position) and creating a `call`'s target flow from the node.

---

## 27. Decisions taken in #217 (G4: overlay glyphs and dynamic titles)

- Each GUI/layer/frame has an unambiguous synthetic allocation identity in `gui-background`.
  Allocations remain reserved when unused; an explicitly tombstoned identity is reactivated
  with its original codepoint.
  Compilation rejects a GUI on exhaustion rather than writing a partial pack. Preview uses an
  independent snapshot and reserves provisional values without altering durable allocations.
- `materione:overlay_frames` contains `every` (positive ticks) and a non-empty `layers` list (up to 256).
  Each entry patches component-specific fields over the base layer; identity, opacity,
  conditions and nested overlay frames cannot vary. A component that declares that namespaced
  field owns it instead, and existing component-owned `frames` is untouched. A GUI's refresh clock includes the frame period.
  Input slot layers cannot be conditional or animated; existing input ownership is unchanged.
- Text templates use the existing expression engine and are validated again after expansion.
  Invalid conditions hide their layer; a failed expansion omits its text and reports the
  existing `MTR-0073` dynamic-layer diagnostic. Click routing uses the last rendered visibility
  and current frame or expanded text footprint, not hidden layers or stale geometry.
- Dynamic title changes use the pinned platform transport (§6.4), not `openInventory`: the server
  menu, session and clock remain the same. A shape-changing reload still uses the existing reopen
  path, which attempts to carry cursor and inputs. Neither path establishes crash durability.
- Conditional and animated overlays are retained as potential input blockers; the displayed
  visibility and current footprint decide which authored input slots are active. An input
  becoming covered returns its item through the existing input-return path. Deferred title
  or ownership-changing refreshes during a click run after the transaction, are superseded
  by newer refreshes and re-render when they run. A `gui.slot_change` pre-event therefore
  cannot return a stack while the same shift transfer is still applying it.
- setTitle was not measured on a real client. Its cursor reset, item flicker and open-container
  side effects on 1.21.4 and 26.1.2 are unknown from this work. The Paper source shows that it sends an
  open-screen packet (§6.4), the same packet family the rich-title transport sends, and the held-cursor and
  flicker observations in §6.4 are for that transport only. setTitle is not used because it takes a legacy
  `String` and cannot carry the font components, so this work makes no claim about its client behaviour.

---

## 28. Decisions taken in #219 (G6: the list component, list sources, paging and `gui.page`)

#219 ships §6.7 and the `ListSource` SPI of §4.5. A list is a GUI component that fills a run of slots with one page of
entries; the window resolves it per viewer and per page, through the same renderer, diff and click map as the item
layers of G3 (§24) and the input rules of G5 (§25). The code is in `api.logic` (`ListSource`, `ListSourceDescription`),
`behavior.logic` (`OverlayLogicContext`), `content.gui` (`ListComponent`, `content.gui.list`), and `plugin.gui.list`
(the compiled layer, the binding, the paging math, the page service and the built-in sources), with the renderer,
router and refresh changes in `plugin.gui.render`, `plugin.gui.input`, `plugin.gui.live` and `plugin.gui.event`.
Where the design left a choice open, these are the choices. Each is reversible by the owner.

**The component and its placement**

1. **`list` is a SLOT component that places nothing statically.** Its `placeSlots` is empty, and the GUI compiler
   routes a `ListComponent.Layer` to `CompiledGui.lists` (a `ListPlacement`), not to the static slot table or the item
   placements. A list is always dynamic: its entries depend on the viewer and the page, so it is re-read on every
   partial render, unless its source is cached (decision 12).
2. **The fields are `slots`, `source`, `filter`, `sort` and `entry`; `visible_if` is not accepted on a list.** A list
   with any other key is rejected with its reason (`MTR-0052`, the existing layer-skip code). The shape of each field
   is checked at load: `slots` is a range `{from, to, skip_columns}` or an explicit list of slots in the GUI's rows,
   with no slot repeated; `source` is a name, a mapping with `type` and the source's parameters, or a mapping with
   `entries` (the static source); `filter` is a condition, shaped as `visible_if` is; `sort` is a field name or
   `{by, order}`.
3. **The entry template is an item layer.** `entry:` is parsed by `ItemComponent` itself, with a placeholder slot, so
   `item`, `name`, `lore`, `lore_mode`, `amount`, `glint` and `frames` mean exactly what they mean on an item layer
   (§6.3). It is compiled by `ItemLayerCompiler` with the roots `player`, `server`, `vars`, `gui` and `entry`
   (`ENTRY_ROOTS`), so the item sources and the overrides are the G3 ones and no second renderer exists. An `entry:`
   that names a slot is rejected: the list's slots place its entries.

**Sources (§4.5)**

4. **`ListSource<P>` is an `api.logic` SPI** with `parse(ParamReader)` (pure, typed at load) and
   `entries(P, LogicContext)`, which returns the entries as flat maps. It is registered through
   `LogicApi.listSources()` under `RegistryKeys.LIST_SOURCES` (`list-sources`), and `LogicLookup.listSource` resolves an
   author's name against it. A late registration takes effect on the next reload, as for item sources.
5. **`ListSourceDescription` carries the entry schema and the `cached` flag.** `entry` is the list of `entry.*`
   variables the entries carry, with types, so the editor can offer them; a source whose fields are the author's own
   declares the family `entry.*`. `cached` says the entries are expensive to read (decision 12); `announced` says the source reads only the variables its `variable`-widget parameters name (views.md §17, V1-21). The description
   is in the registry, so the editor reads it from there. **Deferred:** the vocabulary does not yet carry list sources,
   so the Logic tab cannot autocomplete `entry.*` from it. This is the same gap item sources have (§26), and it is
   closed with the node inspector (#224), not here. *Closed in §31 (decisions 9 and 17).*
6. **Four built-ins, registered through the public API like an addon's (`materione:`).** *(A fifth, `content`, arrived with #8: §35.)* `online_players` (`name`,
   `uuid`, and `exclude_viewer`; the viewer's own state is never read, because a list on one viewer's thread must not
   touch another player's state on Folia). `pack_items` (`pack`, the shorthand; each live item of a pack as `item`;
   cached). `variable` (`var`, the shorthand, a declared player or server string variable, split by `separator`; a
   bare name takes the pack of the resource). `static` (`entries:`, a list of mappings of text, numbers and booleans).
7. **`variable` reads a string, because the declared types have no list type.** Declared variables are number, string
   and boolean (§3.2), so a "variable list" is a declared string whose pieces are separated by a separator. This is
   the closest reading of "a variable list" without a new variable type, which is #211's to add and is not in this
   change. Reversing it means a `list` variable type with its own store and `set_var` operations.

**Filter, sort, and paging**

8. **Order: filter, then sort, then page (§6.7).** The filter runs once per entry with `entry.*` in scope, through an
   `OverlayLogicContext`, which answers `entry.*` from the entry and everything else from the window's context. It
   allocates no copy of the window's tree, so a window with forty-five entries costs one small object per entry. A
   filter that throws leaves its entry out and is reported (`MTR-0078`). The sort is stable, compares numbers as
   numbers and everything else as text (case-insensitively, then exactly), and puts an entry with no value for the
   field last whatever the direction.
9. **The `sort:` is on the list, not on the source.** One mechanism, applied to every source after the filter, so a source
   never has to sort its own entries. §6.7's example is corrected to match (`source: online_players`, `sort: name`).
10. **The page state is per session per list, in `GuiSession.pages()`, as `{number, count}`** *(plus `size` since #8, §35 decision 5)*. The renderer writes the
    count it computed and the number it clamped, before the window's title is laid out, so a text or an item name can
    read `gui.page.<list>.number` and `gui.page.<list>.count` (for "Page 2 of 5"). A page past the end is clamped, not
    refused. The stored state survives a reload (the session carries on, §21 decision 17) and is what `gui.back` restores.

**Slots and clicks**

11. **A visible list owns its slots, in entry order.** Entry *k* of the page goes in slot *k* of the list's slot list,
    so an entry's slot is stable. A slot a higher layer decided (an item, a button, an input) is left to that layer, and
    the entry for it is not shown there; a slot past the page's last entry is emptied, so a lower static item never
    shows through a list. An input slot is the player's (§25): its entry is dropped at render (the window already
    removes owned input slots), and building the live view reports it as `MTR-0080`. A list slot is therefore never an
    input slot, as §25 requires.
12. **A cached source is read on a full render, and kept between partial renders.** The rendered window keeps the
    entries each list read (`Rendered.lists().entries()`). A cached list reuses them on the refresh clock and on a page
    change, and reads its source again only when the window opens or `gui.refresh` or a reload rewrites it. `pack_items`
    is cached; `online_players`, `variable` and `static` are read on every render, which is cheap for them.
13. **A click on a list slot runs the list's rules with that entry, and an empty slot runs none.** The router asks the
    window what it shows (`Rendered.lists().shownAt(slot)`, §21 decision 14), so the entry is the one the player
    sees, and `entry.*` is read from it. A click on a slot that shows no entry skips the list and falls through to the
    layers under it, and then to the rules with no `layer:` filter, as any unclaimed click does (§21 decision 11). A rule
    that should catch every empty list slot therefore names the slots it means (`slot:`), as the roster fixture does.
14. **The entry is a snapshot of the window's last render.** `entry.*` is the entry that was shown when the click was
    routed, not a fresh read of the source, so a click on a row that moved on the same tick runs the rule for the row
    the player saw.

**Paging and `gui.page`**

15. **`gui.page: {list, to}` is one action, one class (`PageGuiAction`).** `to` is `next`, `previous`, `first`, `last`, a
    page number, or a template that renders one of those (an unknown word is a load-time refusal, `MTR-0057`). It runs
    in the window's own session, as `gui.refresh` does (`no open gui` otherwise), and it goes through `GuiPages`, the one
    service: it stores the new page, repaints the window partially (so a cached list keeps its snapshot), and then
    fires `gui.page` with the page the window now shows.
16. **`gui.page` fires only when the page changes.** A step past the last page, or before the first, moves nothing and
    fires nothing, so a rule on `gui.page` runs once per real change and an arrow on the last page is a quiet no-op.
    An action whose list the window does not have fails with its reason (`no list named 'x' in this GUI`).
17. **The `gui.page` trigger** has one filter, `layer` (the list's id), and two context variables: `page.index` (the
    page now shown, from 1) and `page.count` (at least 1). It is not cancellable: the page has moved by the time it runs.
    The trigger list grows from eight to nine, and the trigger ordering in the editor follows `GuiTriggers.all()`.

**Refresh, reload and the budget**

18. **A reload compares the authored lists, not the bindings.** `Live.sameAs` compares the `ListPlacement`s, which are
    records of authored values. A compiled binding has identity equality, so comparing it would make every reload of a
    GUI with a list a change.
19. **The refresh budget holds.** `DynamicListsPerfTest` runs one refresh pass for 50 windows of a 45-slot list, with
    a filter, a sort and every entry's item re-read on every pass (the worst case: an uncached source). It measured
    **0.55 ms** per pass (best of three) against a budget of 2 ms (plugin.md §9), and the existing dynamic-slot pass
    measured 0.32 ms on the same machine. The pass measures the decision and the diff; the Bukkit stacks of changed slots
    are outside it, as in G3 (§24, decision 18).

**Diagnostics and seams**

20. **Four codes (plugin.md §5):** `MTR-0077` (a list source is unknown or rejected; the list is left out, at the layer's
    `source` field, from the diagnose stage and from the live view), `MTR-0078` (a filter threw at render, rate-limited),
    `MTR-0079` (a source could not read at render, the list shows nothing, rate-limited) and `MTR-0080` (a list slot is an
    input slot, reported when the live view is built).
21. **The seam for search (#220, G7).** A list's filter is one condition over `entry.*`, applied in `ListEntries.arrange`
    and nowhere else. A search is a second condition the prompt writes into a session variable (`gui.session.search`),
    ANDed with the author's filter in that one method; the page state, the snapshot and the click routing are unchanged.
    G7 adds the prompt and the input method, not a second list path. Nothing here implements prompts.
22. **Seams for later.** A list takes no `visible_if` (decision 2); a later issue may lift that for the slots a list fills.
    The `variable` source reads a string until a list variable type exists (decision 7). The QA entry for the paged list
    uses the `roster` fixture, whose entries are static, so the client run does not depend on who is online; the
    `online_players` path is covered by unit tests and is not part of that run.

## 29. Decisions taken in #220 (G7: the prompt action, input methods and list search)

#220 ships §6.6 and the search seam of §28.21. A `prompt` step parks its run, asks the player for a line of text through
an `InputMethod`, writes the answer to a run-local or session variable and resumes. The code is in `api.logic`
(`InputMethod`, `InputMethodDescription`, `ActionType.takesElse()`), `behavior.flow` (`StepCompiler`), `plugin.gui.prompt`
(the registry, the methods and the listener), `plugin.logic.action.PromptAction` and `plugin.gui.list` (the search).
Where the design left a choice open, these are the choices. Each is reversible by the owner.

**The step and the run**

1. **`prompt` is one action, one class, and it parks through the interpreter's existing `await`.** Its `executeResumable`
   returns the stage the prompt settles, so the run parks as any read does (§5.2) and counts against the player's parked
   budget (`MTR-0059` when the budget is spent). The interpreter's loop did not change.
2. **An unanswered question ends the run, and its `else:` runs first.** A question that is declined, cancelled by its
   cancel word, timed out, or left by a quit is an unanswered outcome. The steps after the prompt were written to use the
   answer, so they must not run without one. The engine therefore compiles every action whose type says `takesElse()` as
   a `require` around itself, with its `else:` as the require's else (`ActionType.takesElse`, §4.1). A question with no
   `else:` ends the run as a `require` does, quietly. A question takes no `chance:`: a skipped question would let the
   steps after it run with no answer, so `chance` below 1 is a structural refusal (the rule is skipped, `MTR-0021`), and
   an `if` decides whether to ask. Rejected alternative: an `else:` on any action, which would be a second way to write
   `require`; the flag keeps the grammar small and lets the vocabulary say which types take one.
3. **An `else:` on any other action is a structural error**, and the rule is skipped, as for a step that mixes kinds. The
   authoring shape (`StepShape`) accepts `else` beside any action and leaves the refusal to the compiler, so `validate`
   and the load report the same thing. An unanswered question is not diagnosed: nothing is reported for a player who
   cancels.
4. **The answer goes to `var`, parsed as `set_var` parses it, but narrower.** A run-local name, or `session.<name>`. A
   persistent name (`player.x`, `server.pack.x`) is refused at load with `MTR-0082`: a question is not a declared write,
   and a typed number variable cannot take what a player types. A session target with no open GUI fails at run time and
   is reported (`MTR-0084`). The write happens on the player's thread, in the same callback that ends the question, so a
   run cancelled while it waited still gets its window back.
5. **Timeouts are in seconds, 1 to 600, default 60.** The prompt schedules its own timeout on the player's entity
   scheduler and cancels it when the question ends. Seconds, not ticks, because a player reads a timeout in seconds
   (`wait` stays in ticks, §16 decision 6).
6. **Empty and cancel-word answers are unanswered.** A blank answer (an anvil with no name) is "no answer", and the cancel word (default `cancel`, compared ignoring case; `cancel_word: ""` turns it off)
   is "the player cancelled". Both run the `else:`. A search that must clear its text uses `set_var`, not an empty answer.
7. **One question per player.** A prompt started while another waits is refused at once with `FAILED`, the player is
   told ("You are already answering a question; finish that one first."), and the run ends through its `else:` like any
   unanswered question. A waiting question whose run was cancelled is superseded, not refused: the stale one ends
   unanswered and the new one starts. The run's liveness is read from its `ReadExecutor`, which closes with the run.

**The window**

8. **A question hides the player's window, and the session lives on; the window is shown again for the same session.** A
   chat or anvil question must take the window away, because a player cannot type in chat with a container open.
   The session is marked `prompting` (`GuiSession.prompting`), its input items go back to the player, and its window
   closes (`GuiLifecycle.hideForPrompt`). Nothing fires: no `gui.close`, and no `gui.open` when the window returns. The
   close that the hide causes is ignored by `windowClosed` while the session is prompting, so it does not end the session.
   **Deviation from the §21 seam note**, which named `GuiOpener` with a restore. A re-open through the opener makes a new
   session, and the run that is waiting holds the old one in its context, so every GUI action after the prompt
   (`gui.refresh`, `set_var` into the session) would fail with `no open gui`. The search recipe depends on exactly those
   actions. The window returns through `WindowHost.open` for the same session (`GuiLifecycle.showAfterPrompt`), with the
   GUI as it is now; a GUI that is gone ends its session as `removed`.
9. **The window comes back when the player has not moved on.** The return is skipped when the player's current session is
   no longer the hidden one (they opened another GUI, from a command, while the question waited). A return the host
   refuses, or a GUI that went away, is reported (`MTR-0083`) and the player is told. A player who left gets no window.
10. **Which window is hidden.** The player's current open session, when the method closes the window
    (`InputMethodDescription.closesWindow`). It is the run's own session in practice; a command that opens a GUI while a
    question waits makes that one the one hidden and shown again.
11. **A method is asked after the window has gone.** It is asked two ticks after the prompt starts when the prompt hid a
    window (the window closes on the next tick, or at once when the player is not mid-click), and one tick after
    otherwise. The tick counts are a heuristic. A method that needs the window gone must not assume more than that.

**The input methods**

12. **`InputMethod` is an `api.logic` SPI, registered through `LogicApi.inputMethods()`** under
    `RegistryKeys.INPUT_METHODS`. A method asks (`ask`, on the player's thread; it may throw to refuse), answers through
    the `Reply` it was given (from any thread: the prompt hops to the player's thread before it settles), and is dismissed
    once when the prompt ends, whatever ended it. `closesWindow` is in its description, so a dialog (#6) can say it leaves
    the window in place. The methods are not in the vocabulary yet, as list sources are not (§28.5).
13. **Chat (`materione:chat`).** The question is a chat line. The chat listener cancels the next message from a player who
    is being asked, so it is not broadcast, and hands its text to the method on the player's thread. The listener asks
    the method whether the player awaits before it cancels, so no other chat is touched.
14. **Sign (`materione:sign`) is registered and unavailable on this build.** The first design placed a real sign block in
    the world for the editor and restored it afterwards. That was rejected: a crash, restart or chunk unload between the
    two steps leaves a sign in the world that other players see, and it can collide with region protection and block
    loggers. The client-only replacement needs Paper's `Player#openVirtualSign(Position, Side)`, which is in the 26.x API
    (`paper-api-26.1.2`, `Player.java`) and **not in the 1.21.4 API this build compiles against**; the answer comes back on
    `UncheckedSignChangeEvent`, also 26.x only. The 1.21.4 `sendBlockChange` and `sendBlockUpdate` can show a sign to a
    client but cannot receive an edit, so no closer 1.21.4 path exists. The method therefore reports `Availability`
    unavailable with that reason; a prompt refuses it before anything is hidden or pending (`MTR-0084`), and the studio
    can grey it out. The method never touches the world. A 26.x path goes behind the modern seam as a follow-up.
15. **Anvil (`materione:anvil`).** `MenuType.ANVIL` opens an anvil with a name tag in its first slot, named with the
    question. The player takes the result from slot 2, which is the answer: the click is cancelled, so the item is never
    given, and only once the rename text is non-blank. Closing the anvil without taking the result is no answer. The
    method closes the anvil itself when the prompt ends. **Not verified in a client.**
16. **Listeners are adapters (R10).** `PromptListener` holds no policy: it asks a method, cancels an event that is an
    answer, and hands the answer on. It reads no state of its own.

**The search key (§28.21)**

17. **`search:` on a list is `{fields: [...], var: <name>}`.** The fields are the entry fields a search text is matched
    against (case-insensitive substring, any listed field; *since #8 every whitespace-separated word must match, §35 decision 4*); `var` names the session variable the text is read from,
    default `search`. A prompt writes its answer there (`var: session.search`). A list without `search:` ignores the
    session text. The seam is `ListEntries.arrange`, and only there: the search is a second keep-condition ANDed with the
    author's `filter:`, applied before the sort and the paging (§6.7 order). An empty or unset search keeps every entry,
    so a list is unfiltered until a search is made. Rejected alternative: asking authors to write the match inside
    `filter:` with `lower`, `contains` and a default for a missing variable. The expression language has no null default,
    so the match would fail on the first render, before any search.

**Diagnostics (plugin.md §5)**

18. **Four codes, `MTR-0081` to `MTR-0084`.** `MTR-0081` (error, load): an unknown input method. `MTR-0082` (error,
    load): a persistent or malformed answer target. `MTR-0083` (warning, run time): a hidden GUI could not be shown again.
    `MTR-0084` (warning, run time): an input method could not start, or a session answer had no open GUI. The run-time
    codes are published through `PromptService.report`, at most once per code per five seconds. `DiagnosticCodesTest` is
    pinned at 84.

**Seams and what is deferred**

19. **Dialogs (#6)** add an `InputMethod` with `closesWindow` false. Nothing else changes.
20. **Vocabulary.** Neither input methods nor the `takesElse` flag are in the vocabulary yet. The node inspector (#224)
    is where the Logic tab reads both. *Closed in §31 (decisions 17 to 19).*
21. **Verification.** Headless: the engine (`PromptStepTest`), the registry (`PromptServiceTest`, `PromptActionTest`), the
    chat adapter on a real `AsyncChatEvent` (`PromptListenerTest`), the search filter (`ListSearchFilterTest`) and the
    search key's shape (`ListSearchTest`), and the sign method's refusal (`SignInputTest`, `PromptServiceTest`). The
    GUI-visible path (the hide, the chat line, the anvil and the window shown again) is in the QA catalog
    (`guilogic.search-recipe`, `guilogic.chat-prompt-cancel`) and was **not run against a client** in this change. The
    anvil method is **not verified in a client**: the 26.x probe loop was not available on the machine that wrote it.

---

## 30. Decisions taken in #222 (A2: the recipe registry and the v1 recipes)

#222 ships §4.6 (recipes), the expansion behind §8's `instantiate`, and the v1 recipes of §7.5 except the trash slot. A
recipe is a registry entry (`api.logic.Recipe`, with `RecipeDescription`, `RecipeParam` and `RecipeParamType`), its
template form is `api.logic.RecipeTemplate`, the expansion is `behavior.logic.recipe` (`RecipeExpander`, `RecipeParams`,
`RecipeAvailability`), and the built-ins are YAML resources in `plugin/src/main/resources/recipes/` loaded by
`plugin.logic.recipe.BuiltinRecipes`. Each choice below is reversible by the owner.

**The registry and the expansion**

1. **A recipe is a registry entry, read at use.** `LogicApi.recipes()` is a `LogicRegistry<Recipe>` under
   `RegistryKeys.RECIPES`, registered like an action (D5) and owned by its plugin. `instantiate` and the vocabulary read it
   on each call, so a late addon registration needs no reload.
2. **A recipe expands to rules, not to bare steps.** Each expansion is a complete rule, or a small set of rules, with its
   trigger and filters (`on: gui.click`, `layer:`), so one result is one insertion and the trigger is never left to the
   author. The wire key is `rules`; the #221 stub's `steps` key was never read by a client, so it is renamed.
3. **Parameters are typed and checked before anything expands.** The kinds are `string`, `number` (a Double), `integer` (an
   Integer), `boolean` and `variable` (a qualified `pack:key` naming a declared variable whose scope and value type the
   recipe declares). A name the recipe does not declare, a required one left out, a value of the wrong kind or out of its
   range, and a variable that is undeclared or declared with the wrong scope or type are all `MTR-0086`. Every problem is
   reported at once, and nothing expands. Defaults are filled in before the expansion runs.
4. **Templates use `{{name}}`.** A string that is exactly `{{name}}` takes the parameter's typed value, so a number stays a
   number. A placeholder inside longer text takes the value as text, a whole number without its decimal point. A blank
   optional text parameter removes the key or list item that holds it, so an optional `currency:` is absent when blank and
   the default currency applies. `${…}` and `%…%` pass through untouched and are not evaluated at expansion. There is no
   escape; Materione's templates never contain `{{`.
5. **The expansion is validated by the same compiler that loads a file.** After expansion `FlowCompiler` compiles the
   rules with the registries' lookup. A problem of any kind refuses the request (`MTR-0087`, with the compiler's own
   problems after it), so "the expanded steps validate cleanly" holds on every call, not only in the tests.
6. **`instantiate` never writes.** The caller inserts the returned rules through `logic.edit` (`AddRule`), in one
   transaction with its revision check. The recipe is not persisted (§4.6), and the rules it wrote are ordinary content
   from then on (D1).
7. **An unknown recipe is `MTR-0008`** (as #221 chose), with a hint to call `logic.vocabulary`.
8. **Availability is a warning, not a refusal.** The expansion walks the actions it contains, branches included, and
   reports each action the server cannot run now, once, with the action's own reason (`MTR-0088`). Without an economy,
   `pay_to_receive` carries one warning for `take_money`. Conditions are not checked here; they are evaluated where they
   are used.

**The v1 recipes**

9. **Eight recipes ship.** Each is one YAML resource, and its expansion is rules that use only actions, conditions and
   triggers already registered:

   | Recipe | Trigger | Expands to | Parameters (default in brackets) |
   |---|---|---|---|
   | `pay_to_receive` | `gui.click` on `layer` | `require`: `take_money`, then `give_item`; `else`: a message; then `gui.refresh` | `layer`, `price` (≥ 0.01), `currency` (blank: default), `item`, `amount` (1), `message` |
   | `close_button` | `gui.click` on `layer` | `gui.close` | `layer` |
   | `welcome_message` | `gui.open` | a message | `text` |
   | `confirm_dialog` | `gui.click` on `layer` | `gui.open` of `dialog` with a `question` argument | `layer`, `dialog`, `question` (*Are you sure?*) |
   | `paged_player_list` | two `gui.click` rules | `gui.page` to `previous` and to `next` of `list` | `list`, `previous`, `next` |
   | `toggle_setting` | `gui.click` on `layer` | `set_var … toggle`; `if` the variable is `true`, a message; `gui.refresh` | `layer`, `variable` (a declared player boolean), `on_message`, `off_message` |
   | `server_selector` | `gui.click` on `layer` | a message, then `connect` to `server` | `layer`, `server` |
   | `back_button` | `gui.click` on `layer` | `gui.back` | `layer` |
   | `cooldown_gate` (#328) | `on` (default `gui.click`), on `layer` (blank: any) | a `var` condition `variable > now() - seconds` then a message and `stop`; `set_var variable = now()` | `on`, `layer`, `variable` (a declared player number), `seconds` (≥ 1), `message` |

10. **Trash slot: blocked on #248, not shipped.** *(Superseded by §36: #248 shipped `custody.consume` and the trash slot is the
    built-in recipe `trash_slot`, a composition over it. What follows is the analysis that made it wait.)* §25 decision 12 stands (the durability block itself was lifted on PR #303;
    #248 now waits for its own implementation, which waits for the durability rows). The trash recipe needs a
    consume-incoming action, which the engine does not have. Nothing merged can express it safely. `gui.slot_change` fires
    before the change (§25 decision 4), so a `gui.slot_item: {op: clear}` there would clear what the slot already holds,
    not the item being dropped in, and clearing after the change races the player's next click. The recipe is not
    registered, and a test pins its absence (`BuiltinRecipesTest`). It ships when #248 lands.
11. **The confirm dialog writes the opening rule only.** The confirm GUI's yes and no buttons are a separate resource.
    `instantiate` writes one resource's rules, and the logic API has no cross-resource write. The recipe names the contract
    the confirm GUI follows (a `question` argument, declared under its `open:` block). The Recipes panel (#225) may offer to
    create the confirm GUI alongside the rule. *(Not done in #225: the panel inserts the opening rule only, §33 decision 19.)*
12. **Recipes write rules, not layers.** A list, its buttons and the overlays are GUI layers, edited through `gui.edit`. A
    recipe's rules refer to those layers by id, and the author places the layers in the Design tab. Writing both in one
    request would need a second transaction across two APIs, which the authoring surface does not offer. A rule that names
    a layer which does not exist is reported on the GUI (`GuiRuleDiagnoser`), not by `instantiate`.
13. **The toggle recipe requires a declared variable.** A variable is declared in a `variables/` document, which is another
    resource, so the recipe takes the variable as a parameter, checks its declaration (scope `player`, type `boolean`), and
    never writes the declaration. An undeclared name is refused with its file to create.
14. **Numbers keep the kind the step reads.** A `number` parameter is a Double, which `take_money`'s decimal amount reads.
    An `integer` is an Integer, which `give_item`'s count reads. Neither is coerced to text by a whole-value placeholder.

**Registration and wire**

15. **Built-ins register as addons do.** `BuiltinRecipes` reads each `recipes/<id>.yml` from the jar, parses it with
    `LosslessYaml` (the `on:` key normalised as everywhere else), and registers it under `materione:` through
    `LogicApi.recipes()`. A resource that does not load is left out with `MTR-0085`, and the others still register, so a
    broken recipe never stops the logic. `RecipeTemplate` is public in `api.logic`, so an addon with its own YAML can parse
    it and register the result.
16. **The vocabulary gains `recipes`, additively.** Each entry is `{id, displayName, description, params, registeredBy}`,
    and each param is `{name, type, required, default?, description, min?, max?, scope?, valueType?}`. The vocabulary
    stays version 2 (§14 decision 13): the change adds a key and removes none. The logic protocol golden and the editor's
    demo snapshot change by that addition, and by the new `logic.instantiate` answer.
17. **`logic.instantiate` answers `{rules, warnings}`.** A refusal keeps its diagnostics; the warnings of a successful
    expansion are the `MTR-0088` entries.

**Verification.** `RecipeTemplateTest` (the template's parse accepts and rejects, its fills, and its copy), `RecipeExpanderTest`
(parameter checks, variable checks, the compiler's verdict, availability and a throwing expansion, against a fake lookup),
`BuiltinRecipesTest` (each v1 recipe loads from its resource, expands with a fixed sample, validates against the real
built-in registries, and matches `fixtures/golden/recipes/<id>.json`; the eight are listed in the vocabulary; a bad
parameter is refused). `LogicAuthoringApiTest` pins the unknown-recipe refusal and that nothing is written. Recipe output is
ordinary rules whose actions are already catalogued in the logic QA suite, so #222 adds no QA catalog entry: no
player-visible behavior is new.

**Diagnostics.** `MTR-0085` (a recipe resource does not load, warning), `MTR-0086` (a parameter refused, error),
`MTR-0087` (an expansion refused, error) and `MTR-0088` (an action unavailable, warning), documented in plugin.md §5. The
next free code is `MTR-0089` (§10).

**Deferred.** (The trash slot, once deferred to #248, shipped as a composition: §36.) Recipes that write GUI layers, in the same transaction as their rules (a `gui.edit`
and `logic.edit` pair would need one). Conditions in the availability warning. An addon's YAML resource registered through
a loader, not only through the `Recipe` interface.


---

**As built in #328 (the `cooldown_gate` recipe)**

23. **A persistent cooldown is a composition.** `cooldown_gate` is a recipe YAML in `plugin/src/main/resources/recipes/`
    (in `BuiltinRecipes.IDS`). It expands to one rule. The rule's `do:` reads a declared player number (the epoch second
    of the last use) with a `var` condition: `var > now() - seconds` holds while the cooldown runs, and then the run
    `stop`s after the message. Otherwise a `set_var` writes `now()`. Nothing is stored by the engine: the number is a
    player variable like any other, so it survives a restart (`cooldown_gate` is the answer to the restart-proof case that
    `on_cooldown` of §16 is not).
24. **Parameters.** `variable` (a declared player number, `scope: player`, `value_type: number`, as `pack:key`), `seconds`
    (an integer of at least 1), `message` (default "You have to wait before doing that again."). Two more parameters are
    needed because a recipe expands to rules, and a rule has a trigger: `on` (default `gui.click`) and `layer` (default
    blank, which the template drops, so the rule has no layer filter). The brief named three parameters; these two are the
    addition, and the reason is the rule shape of §4.6.
25. **The remaining time is not shown.** The brief asked for the message to give the time left through `format_duration`.
    It cannot be done in this recipe: an expression reads a declared variable only by its dotted path
    (`vars.player.<pack>.<key>`, §3.3), and the recipe has the variable as one `pack:key` text, which a template cannot
    split. The message is the author's text. The follow-up is a two-part variable parameter or a typed variable reference
    in templates; `format_duration` then gives the wait as `format_duration(seconds - (now() - vars.player...))`.

## 31. Decisions taken in #224 (W2: the node inspector)

#224 shipped §7.3's field editors: an **inspector** beside the Logic canvas, in which the selected node's every parameter is
a typed field drawn from the node type's self-description, a template field with autocomplete and inline validation, a
condition stack, and a variable picker with a "declare new" shortcut. It also closes the vocabulary gaps earlier sections
deferred to it (§24, §28.5, §29.20). The code is `webui/src/lib/logic/` (pure rules: `params`, `condition`, `template`,
`scope`, `variables`, `edit-plan`, `diagnose`, `verdicts`, `control-params`, and `inspector.svelte.ts`) and
`webui/src/lib/studio/logic/inspector/` (the components). The plugin side adds keys to `logic.vocabulary` and nothing else.
Each decision is reversible by the owner.

**The inspector edits the document, nothing else (D1, D6)**

1. **An inspector edit is a plan, and a plan is the same `LogicEdit`s the canvas makes.** A field change becomes
   `patchStep` (a step's own value or an option), `patchRule` (a rule's filter, option or `if:`), or, where a branch key
   would be patched, the replacement §26 decision 10 describes: a `choose` case's condition and a bare step's first
   parameter (`- gui.close` has nothing to patch) replace the step with a copy at the same path, keeping its `node:`
   and everything under it. Each commit is one history command, so Ctrl+Z is the shared undo, and Save replays the
   edits through `logic.edit`. The panel keeps no model: its open/closed state is the only thing it remembers, in
   `localStorage` as a per-viewer convenience, and every field reads the file back.
2. **A payload is written back in the form the author chose.** `writeParam` keeps a scalar that still holds only the
   shorthand parameter a scalar (`sound: x`), turns it into a mapping when another parameter is set (`{sound: x,
   volume: 2}`), keeps a mapping a mapping and key order as it was, and removes a key when an optional parameter is
   cleared instead of writing a blank. The shape is the compiler's (`Shapes.paramsOf`): a scalar or list fills the
   shorthand.
3. **The inline field on the node stays.** §26 decision 15 called it a stop-gap "until the inspector". It is kept as the
   quick edit of a one-line value (and the keyboard route `Enter`); the inspector is the full editor. Both write the same
   plan, so there is no second path.

**Fields come from the self-description**

4. **One function picks the field.** `baseKind(param)` reads the widget hint (`uiHint`), then the allowed values, then the
   type: `item` the item browser (a pick writes `pack:id`, since a bare id resolves in the `materione` namespace), `sound`
   a text field with the packs' sound events as suggestions, `variable` the variable picker, `input-method` the input
   method chooser, `color` the studio's colour field, `reference` a text field with the matching resources offered,
   `toggle`/boolean a checkbox, `select`/enum a choice (segments up to four values, else a select), `number` a number
   field clamped to the description's range, `list` rows, `advanced`/`raw` JSON, anything else the template field.
5. **A value a field cannot hold falls back; it is never lost.** `kindFor(param, value)` shows a template where a number,
   toggle or choice holds text (`${price}`), rows where a scalar parameter holds a list (`layer: [a, b]`), and JSON where
   it holds a mapping. A number, toggle or choice can be switched to an expression by hand. An unknown widget hint is plain
   text. Parameters the description files as `advanced` are folded away.
6. **The plugin names the widgets, in the self-description.** Built-in parameters gain widget hints through the new
   `ParameterSchema.withWidget`: the `item` of `give_item`, `take_item` and `has_item`; the `sound` of `sound` and
   `stop_sound`; the `var` of `set_var`, the `var` condition and `prompt`; the `method` of `prompt` (`input-method`). The
   type and the validation are unchanged, and a client that does not know a hint draws the text field it drew before. An
   addon gets the same by calling `withWidget`.
7. **Keys the server does not describe are kept and shown read-only.** An unknown parameter of an action or condition, an
   unknown rule key and an unknown step option are listed under "Other keys" exactly as written. An action no registry knows
   says so, with its settings editable as raw JSON. A prompt that names a method this server cannot use keeps it selected,
   with the reason. Nothing is dropped (master §5.6); a Playwright spec edits next to such keys and compares the saved file.
8. **The engine's own steps have their keys described in the editor.** `wait` (`ticks`), `stop` (`cancel`,
   `cancel_session_runs`) and `call` (`flow`, `with`) are part of the closed grammar (§2.2), and the vocabulary lists the
   kinds but not their keys. `control-params.ts` describes them as `ParamInfo`, so the same fields and the same payload
   rules draw them. A named flow's inspector renames it (`renameFlow`, which rewrites its calls, §19 decision 5).

**The template field**

9. **`${` completes from one scope.** `scopeFor` collects, at the selected node: the trigger's context schema filtered to the
   host (§2.6), the declared variables as `vars.<scope>.<pack>.<key>` (§20 decision 4), the run-local names the
   rule's `set_var` and `prompt` steps write (`vars.name`; a session variable is a declared one, offered as `vars.session.<pack>.<key>`, and a name no declaration backs is flagged with a shortcut to declare it, views.md §17), `args.*` and the `with:` keys of the calls
   in a named flow (whose context is that of the rules that call it), and, for a rule on a list layer, the concrete
   `entry.<field>` paths the list source declares (§28.5). Completion offers the next path segment, with the value's type,
   and the expression functions with their signature and return type (`contains(text, part)`, from the vocabulary).
   Accepting a finished path closes the `${`. A condition row is an expression, so it completes bare names too.
10. **`%` completes from the expansions Materione defines, and the vocabulary has no list of the rest.** The offered tokens are
    `%materione_gui_open%`, `%materione_gui_last%` and `%materione_var_<pack>_<key>%` per declared variable (§3.3, §20
    decision 18). The server does not publish the PlaceholderAPI expansions that are installed, so `%vault_eco_balance%`
    cannot be offered, and no data source was invented. **Hook:** `readVocabulary` reads an optional `placeholders:
    [{token, description}]` into the same list, so a server that starts sending one is picked up with no editor change.
11. **Two validations, one authority.** The field lints locally and at once what the compiler would also say about a template: an
    unclosed `${`, a root the trigger does not provide, a function nothing registers. The compiler's verdict (`logic.validate`)
    is asked about the text **while it is being typed**: once typing pauses (350 ms) the plan is applied to a copy of the
    block and sent as a draft, so the document is not touched and nothing is written. The draft's diagnostics replace the
    node's verdict in the inspector until the value commits, and then the canvas's own check takes over (§26 decision 16). A
    value commits on Enter or blur, never per keystroke.
12. **A diagnostic is drawn on the field when it can be told which.** The compiler reports a step path, so the canvas draws a
    diagnostic on the node. The inspector additionally puts it on a field when its path goes on into the field's key
    (`behavior.0.on`, `behavior.0.do.1.if`), when its message quotes the parameter's name, or when it quotes a token the
    field's text holds (the compiler's `'bogus' is not a variable ...` lands on the template that says `bogus`). Anything
    else stays on the node: the compiler's verdict is the compiler's, and the editor does not guess past it. The flag keeps
    its step path (`verdicts.ts`).

**The condition stack**

13. **The stack edits the authored value by path.** A condition is an expression, a boolean, a one-key mapping (a registered
    predicate or `all`, `any`, `not`, `at_least: {count, of}`) or a list (which is `all`), the grammar of `ConditionCompiler`.
    `condition.ts` reads it into a tree with a path per node and edits it immutably: replace, add, remove, move, wrap in a
    composite, switch `all` and `any`, set a count. A shape the editor does not draw is shown as JSON and survives every
    edit around it. Predicates are listed from the vocabulary with their typed fields (the same form as an action's), an
    unavailable one is greyed with the reason, and a new one starts with its shorthand parameter's starting value. A second
    condition turns a lone one into a list; the old one's meaning is kept when the root is not an `all`.
14. **Where a stack appears.** A rule's `if:` (optional; cleared with `unset`), an `if` step (it always holds one: removing the
    last row writes `${true}`) and each case of a `choose` (replaced in place). `require` has no condition, it is a guard over
    steps (§2.2), so its inspector explains the guard and the `failed` output. The pay-to-receive flow of §2.1 is a rule on
    `gui.click` whose `require` holds `take_money` and `give_item`, with the message on `failed`.

**Variables**

15. **The picker lists declared variables, then session and run-local names.** Declared player and server variables come from
    the packs' `variables/` files, read with the ordinary `browse.get`, and are written `pack:key`, so the scope comes from
    the declaration and a typo cannot create a second key (§20 decision 5). Session names (`session.page`) and run-local
    names are the ones the flow's `set_var` and `prompt` steps write; a name typed that is none of them is allowed, because
    those need no declaration.
16. **"Declare new" is a file edit.** It writes the declaration into the pack's variables file with `authoring.setFields` (a new
    top-level key, which the lossless layer inserts), and creates `variables/<pack>.yml` with `edit.create` first when the pack has
    none. That needed one plugin change: `Scaffolds` had no starter for `variables`, so `variables` joins `SCAFFOLD_TYPES`
    with a one-variable starter (a player number); the studio then sets the scope, type, default and note the author chose. The key
    is checked locally (lowercase, digits, `_`; not a scope word; not already declared in the pack) before anything is written. The
    declaration is written at once, not at the GUI's Save: it is a different file, so it is its own revision, as the Assets
    workspace's uploads are. Nothing is kept in the editor.

**The vocabulary, caught up**

17. **`logic.vocabulary` gains `listSources`, `itemSources`, `inputMethods` and `takesElse`, additively.** The vocabulary stays version
    2 (§14 decision 13, §30 decision 16): keys are added, none removed. A list source is `{id, displayName, description,
    parameters, shorthand?, entry: [{path, type, description, hosts}], cached, registeredBy}`, an item source the same without
    `entry` and `cached`, an input method `{id, ..., closesWindow, available, unavailableReason?, registeredBy}`, and an action
    that asks a question carries `takesElse: true` (written only when true). They are read from the registries by
    `VocabularyBuilder`, like everything else in it. `BehaviorVocabulary` and its `Action` keep their old constructors. The
    recorded demo snapshot now registers the prompt step and its methods, as the plugin's boot does, so the editor tests run
    against `sign` unavailable with its real reason.
18. **A `takesElse` action projects an `else` port, like `if`'s.** `project(block, {takesElse})` gives such an action `after` and
    `else` ports; the branch's steps are chained from `else`, `planInsert` creates the missing list by replacing the step with a
    copy that has it (§26 decision 10), and `unproject` rebuilds the node byte for byte. Without the vocabulary (not loaded yet)
    the same file projects without the port and its `else:` rides in the node's own keys, so loading and saving is lossless
    either way. A step that is not such an action keeps a stray `else:` in its keys as before.
19. **The input method chooser is the vocabulary's.** An available method can be picked; an unavailable one is drawn greyed and
    dashed, cannot be picked, and its reason is written under the control. Item sources and list sources are read and kept
    in the editor's vocabulary, and the list source's entry schema is used for `entry.*` completion (decision 9); the GUI
    studio's layer inspector (`source:` of a list, an item layer's source) was not changed here.

**Layout**

20. **The inspector sits beside the canvas and always takes its place.** It collapses to a rail (the canvas never reflows under the
    author while a node is selected or deselected), is `clamp(280px, 30%, 380px)` wide, and the page never scrolls sideways. The
    canvas now shares its row with it, so the existing wire-drag spec frames the whole graph first (`0`) and drops on a header's
    centre instead of a fixed offset.

**Verification.** `webui/test/logic-inspector.spec.ts` (field kinds, payload writes, the condition stack, completion, lint, variables
and their declaration over a recording channel, the plans, diagnostics attribution, against the recorded real vocabulary),
`logic-project.spec.ts` (the `takesElse` round trip over every logic fixture), the Playwright spec
`webui/browser/specs/logic-inspector.pw.ts` (the pay-to-receive flow authored with no YAML and compared with the saved file; a
diagnostic on the right node and field; draft validation while typing; `%` and function completion; the picker and a declared
variable; the condition stack; the prompt step with `sign` greyed and its `else` branch; unknown keys kept), and on the plugin
side `VocabularySourcesTest` and `VocabularySourcesJsonTest`. The QA catalog has `gui.logic-inspector-live-save` (auto, against the
real local app: a message completed from the trigger context, saved and run) and `gui.logic-inspector-visual` (screenshots). They
were authored and pass `qa/runner.mjs check`; they were **not run against a client** in this change.

**Deferred.** ~~The Script tab, Design to Logic linkage and the Recipes panel~~ (shipped in #225, §33); the live trace (shipped in #226, §32). The GUI studio's layer
inspector does not offer the item sources and list sources it now has in the vocabulary (the Design tab's own controls). A
key/value editor for a `call`'s arguments (they are JSON for now). Multi-select, copy and paste on the canvas. The PlaceholderAPI
list of installed expansions (decision 10).


---


---

## 32. Decisions taken in #226 (W4: the live trace)

#226 shipped §7.6: a studio that is connected hears what a GUI's rules do while a player clicks, and the Logic canvas draws it. The
engine already emitted step-boundary events (§16 decision 20); this adds the subscription API on top of them, the two web ops and
the stream, and the canvas animation. The code is `dev.materione.api.logic.trace` (the contract), `dev.materione.behavior.run.trace`
(the hub, Bukkit-free), `dev.materione.plugin.logic.trace` (the pump, an adapter), `LogicTraceOps` in `web`, and
`webui/src/lib/logic/trace.ts` and `trace.svelte.ts` with the node and wire styling in `webui/src/lib/studio/logic/`. Each decision is
reversible by the owner.

**The API: `LogicApi.traces()`**

1. **Subscribe with a filter, hear batches.** `api.logic().traces().subscribe(filter, listener)` returns an `ApiResult<TraceSubscription>`;
   closing the subscription unsubscribes (idempotent, never throws). A `TraceFilter` is `{players, resources}` and **must name at least
   one**: there is no "everything" subscription, so a trace is opt-in per player and per GUI by construction, and the constructor
   rejects an empty filter. An empty `players` means any player, an empty `resources` any resource. A `TraceEntry` is `{runId, resource,
   rule, player, path, kind, detail, atMillis}`; `path` is the step path of §16 decision 2, the one the diagnostics use. `TraceKind` is
   the interpreter's event type renamed for a reader (`RAN` for `STEP_OK`, `FAILED` for `STEP_FAILED`, and so on), with the wire name
   lower-cased with dashes (`ran`, `require-failed`). A listener receives a `TraceBatch {entries, dropped}`: the entries since the last
   delivery, oldest first, and how many the subscription lost on the way.
2. **The engine's event grew two fields and two events.** `TraceEvent` now carries the rule's owning `resource` (a GUI's `pack:id`) and the
   run's `player`, so a filter can match without the hub reaching into the run. A `stop` step now emits a `STEP_OK` at its own path before the
   run ends, and a run aborted by its budget emits a `STEP_FAILED` at the step that hit it with the reason, so both nodes can light and say
   why. Nothing else about the interpreter changed.
3. **The hub is a sink, filtered per subscription.** `TraceHub` implements `TraceSink` and `LogicTraces`. An engine thread calls `accept`; for
   each subscription whose filter matches it builds one `TraceEntry` (shared by the subscriptions that want it) and offers it to that
   subscription's bounded `TraceQueue`. A listener is never called from the engine thread: a host pump calls `flush()`, which hands each
   queue's batch to its listener. A listener that throws loses its batch and nothing else. A queue holds its own lock for a few field
   updates, so a subscriber can never stall a run.
4. **Free when nobody subscribes.** The hub is installed as the interpreter's sink only while it holds at least one subscription (the first
   `subscribe` installs it, the last `close` removes it, through the hub's activity callback), so with none the interpreter builds no events
   at all (§16 decision 20), and nothing is scheduled. `FlowDispatchPerfTest` (the 0.5 ms p99 click-to-first-action budget, plugin.md §9)
   runs with no sink and is unchanged.
5. **The pump is an adapter.** `TracePump` (`plugin.logic.trace`) installs the sink and arms `SchedulingGateway.executeGlobalLater` every
   10 ticks while the hub is active, calling `hub.flush()`. It starts no thread (R5), owns no policy and is the only place the sink is wired.
   The 10 ticks (half a second) is the delivery cadence: at most two batches a second per subscription, which is also what bounds the
   relay's cost (decision 8).

**The limits**

6. **Every limit drops and says so.** `TraceLimits.DEFAULT`, and why each number:

   | Limit | Value | Why |
   |---|---|---|
   | Entries per second, per subscription | 200 | A token bucket whose capacity is one second's worth, so a click of ten steps (about 25 entries) goes through whole and a click storm is held to the rate. A person cannot click 8 times a second in a menu; 200 is generous for a human and tight for a loop. |
   | Step entries per run, per subscription | 256 | A run may take 1,000 steps (§5.3); the studio draws a graph a person reads, which never needs more. A run's start and end entries are exempt, so a run that hit the cap still shows how it ended. |
   | Pending entries, per subscription | 512 | The queue between two pumps. At 200 a second it holds five pumps' worth; anything past it is dropped, so memory is bounded however slow the listener. |
   | Entries per delivery | 128 | A pump's worth at the entry rate is 100, so 128 is one frame; it also keeps a frame well under the relay's size limit (decision 8). The rest waits for the next pump. |
   | Subscriptions, server-wide | 8 | A debugging aid: more than a few authors tracing at once is a mistake. A ninth subscribe is refused with `MTR-0089`, and a closed one frees its slot. |
   | Detail, per entry | 160 characters | A reason is a sentence; a longer one is cut with an ellipsis. |

   A drop is counted, never thrown: the next batch carries `dropped` (a batch may carry only a drop), and the stream reports it to the studio.
7. **One new code.** `MTR-0089` (warning, "Live trace subscription refused; limit reached") answers `subscribe` when the server's cap is reached,
   documented in plugin.md §5. The per-session cap (decision 9) uses the same code.

**The web ops**

8. **`logic.trace.subscribe` and `logic.trace.unsubscribe`, with `push.logic.trace` frames.** Both ops are gated on the `logic` capability
   (`editor-op-gates.json`, `since: post-0.1.0`) and are pure translators over `LogicTraces` (R9). The editor protocol has no per-request
   streaming reply, so the stream is the existing push channel, addressed to the one session that asked (the diagnostics and reload pushes
   go to every session). A frame is `{op: "push.logic.trace", id: "trace-<n>", payload: {resource, seq, entries: [{run, rule, path, kind,
   detail?, at}], dropped?}}`. It is sized for the relay (web.md §3.2: frames up to 1 MiB, a retention cap of 2,000 frames or 16 MiB per
   channel): at most two frames a second per subscription, at most 128 entries each, rule and path and detail cut at 160 characters, and a
   frame is halved until it fits 48 KiB (what falls off is counted in `dropped`). The relay stores each frame as a D1 row; a stream that runs
   continuously costs at most two rows a second per traced GUI, and nothing at all while nobody clicks. Cost model (CLAUDE.md): no new
   deployed infrastructure, no Worker or relay change; the free-tier line item affected is D1 rows written, bounded by the cadence and the
   lease below.
9. **Scope, lease and caps.** `subscribe {resource, allPlayers?}` traces one GUI for the calling session, scoped to **the player who opened the
   session** unless `allPlayers` is set; a console session has no player and traces everyone's runs of the GUI (the reply says which:
   `scope: "player" | "all"`). The studio never sets `allPlayers`; the op carries it for an API client. The subscription is a **lease** of
   `leaseSeconds` (300): the studio renews it by subscribing again, every 40% of the lease, and a lease that lapses closes the subscription. A
   session that ended closes it too, at the next delivery or the next subscribe, so a closed browser tab cannot hold one of the eight slots.
   A session holds at most four GUIs at once. Subscribing again for a GUI renews its lease and does not open a second subscription; the same
   GUI with the other scope replaces it. `unsubscribe {resource?}` closes one, or all of the session's when no resource is named, and answers
   how many closed.
10. **A malformed request is a bad request.** A `resource` that is missing or not `pack:name` is `MTR-0008`, as the other ops answer it; a
    session that is not active is the same. The cap is `MTR-0089`.

**The canvas**

11. **A toggle in the Logic tab, off by default, per GUI.** "Live trace" sits in the toolbar. The choice lives on the GUI's canvas state, not in
    `localStorage`, so it is per GUI and per tab and never persists across a reload (opt-in every time). While the toggle is on, the tab
    holds the server lease; leaving the tab, closing the GUI or losing the connection lets it go (`detach`) and returning takes it again (`attach`),
    keeping the author's choice. A refusal (the cap) or a server without the logic API is said in the toolbar and the toggle stays where it was
    put.
12. **Step paths map to nodes the way diagnostics do.** `nodeOfPath` picks the deepest node whose id is the path or a prefix of it, the rule
    §26 decision 16 uses for the compiler's verdict, so `behavior.0.do.1.if` lands on the `if` node and a path no node has is ignored. A
    node that ran lights up (a coloured ring) and so does the wire that feeds it: every edge whose target ran, failed or is waiting. Skipped
    steps are marked, and their wire stays dark. A branch not taken, and every rule the click did not run, stay as they are, and while a run is
    on screen everything the run did not reach is dimmed (nodes to 45%, wires to 25%), so the path through the graph reads at a glance. A
    run that has not arrived yet dims nothing.
13. **A failure shows its reason on the node.** A failed action, a failed `require` and a run the budget aborted put the reason in a pill
    under the node and in the node's header badge, the same `flag` badge a compiler verdict wears (§26 decision 16), as an error with the text
    "Failed in the last run: ...". A failure is never overwritten by a later success on the same node, because the node's job is to show what went
    wrong. A skipped step wears an info badge.
14. **Runs clear each other, and the picture fades.** A rule starting clears that rule's lane, and a `call` clears the flow it enters, so the next
    click replaces the last one without touching another rule's picture. The whole picture fades 8 seconds after the last entry. The server's
    drop count is shown as a notice on the canvas ("Trace truncated: the server dropped N entries") until the picture clears.
15. **Motion respects `prefers-reduced-motion`.** With motion, a lit wire draws a bright pulse from its source and a lit node eases in, each
    delayed by the order its target was reached, so the run is seen travelling; with reduced motion nothing animates and the wires and nodes are
    simply lit. The wires use the source colour; the pulse is the new token `--logic-trace-pulse`.
16. **The trace follows the saved file, and says so.** The server runs the rules it loaded, so the paths it reports are the saved file's. With
    unsaved edits the toolbar says a node moved or inserted may not line up until Save. The studio does not try to translate paths through the
    draft.

**Verification.** `TraceHubTest` (behavior: the filter, the rate limit, the per-run step cap, the pending cap, the batch size, the subscription
cap and its code, the drop marker, activity callbacks, a throwing listener, and a real interpreter run), `LogicTraceOpsTest` (web: the ops
over the real hub, scope, lease, session end, caps, the frame trim, and the goldens `fixtures/protocol/editor-logic-trace-v1.json`),
`LogicTraceIntegrationTest` (plugin, headless: a rule compiled by the real compiler fired through the real `LogicService` engine; the
subscription hears the step paths of that run on the pump's tick; the sink and the pump follow the first and last subscription; a click
storm of 2,000 clicks is held to the rate limit while the engine runs every action), `PaperGuiRuntimeIT` step 9 (a live Paper server: the ops
over the real handshake, the per-session cap and `MTR-0089`), webui `test/logic-trace.spec.ts` (the goldens through the wrappers, path to
node and wire, the fold of a stream, the toggle's life cycle), the Playwright spec `webui/browser/specs/logic-trace.pw.ts` (lit wires and
dim branches, a failure reason on its node, a skip, replacement, the drop notice, the fade, the cap, leaving the tab, reduced motion) and the QA
entries `logic.trace-stream` (auto: a real client click lights the node the studio shows) and `logic.trace-canvas-visual`. The QA entries were
authored and pass `qa/runner.mjs check`; they were **not run against a client** in this change.

**Deferred.** A click that makes a trace needs a client, and the Paper harness is headless, so the harness proves the ops and the wiring on a
live server and the in-game click path is the QA entries'. Tracing item, block and furniture rules (their resources are not opened in the
studio's Logic tab yet; the API and the stream already carry any resource). An `allPlayers` switch in the UI. Replaying or pausing a trace,
and keeping a history of runs. Translating paths through unsaved edits (decision 16).


---

## 33. Decisions taken in #225 (W3: Design↔Logic linkage, the Script tab and the Recipes panel)

#225 shipped the rest of §7: the Design tab and the Logic tab know about each other (§7.4), the **Script** tab is the third view
of the one document (§7.1), and the **Recipes** panel inserts the server's recipes (§7.5). Everything is in the studio: no op, no
wire shape, no diagnostic code and no plugin class changed, so the editor-compat check and every protocol golden are untouched.
The pure rules are `webui/src/lib/logic/linkage/`, `.../script/` and `.../recipes/`; the components are
`webui/src/lib/studio/logic/linkage/`, `.../script/` and `.../recipes/`. Each decision is reversible by the owner.

**Design ↔ Logic linkage (§7.4)**

1. **The link is the `layer:` filter, and nothing else.** A rule belongs to a layer when its `layer:` filter names it (a text, or a
   list of texts). The bolt, the count, the thumbnail and the diagnostics are all read from the rules as they stand
   (`layers.ts`: `layersOfRule`, `rulesOfLayer`, `ruleCounts`, `layerOfNode`, `layerFlags`), so there is no table to keep in step and
   no second source of truth (D1). A rule that names two layers counts for both.
2. **"Add click logic" is offered for a button, an item or a list** (`CLICKABLE`), in a new **Logic** section at the top of the
   Design inspector. It plans `addRule {on: gui.click, layer: <id>, do: []}` at the end of `behavior:` and runs it through the
   canvas, so it is one history command and one `logic.edit` on save (D6). Then the studio shows the Logic tab with the new trigger
   selected and framed. The button is disabled until the logic is editable (the `logic` capability and the vocabulary), with the
   reason as its tooltip. A layer that already has rules reads "N rules answer clicks on this layer" and offers **Open in Logic** and
   "Add another click rule". `LinkageState.view` holds the tab, so a gesture in one tab can open another.
3. **The tabs are Design, Logic and Script, and the logic is read whichever shows.** The vocabulary is read, and the draft checked
   once editing pauses, by the studio and not by the Logic tab, because the bolt and the diagnostics are drawn in Design. This
   moved the `scheduleCheck` effect from `LogicCanvas` to `GuiStudio`; the behaviour on the Logic tab is the same.
4. **A trigger shows its layer.** On a trigger node the `layer:` line is replaced by the layer's thumbnail (the part of the
   composited GUI the layer covers, drawn nearest-neighbour), its name and, when different, its id. Hovering or focusing the node
   shows a **mini preview** (the whole GUI at 176 px, the layer outlined in the trigger colour, bottom-left of the canvas, no pointer
   events) so the author sees where on the screen the rule lives. It is drawn from the same raster as the Design canvas.
5. **The bolt is a button.** A layer with rules wears a bolt in the layers panel (with the count when it is more than one); clicking
   it opens the first rule in Logic. It is on every layer a shared rule names.
6. **A diagnostic is drawn on the layer.** The compiler's verdict on any node of a rule (§26 decision 16) goes to the layers the rule
   names: a triangle on the layer's row and, in the inspector's Logic section, each message with the **step path** it was reported
   at. The server's static layer checks (a `layer:` that names no layer, §21) are `gui.validate` diagnostics, not step-path
   verdicts, and stay where they were (the banner above the studio).
7. **Deleting layers that have rules asks.** `GuiStudioState.deleteSelection` first asks `deleteGuard` (set by the linkage); when
   rules name the layers (and the logic can be edited faithfully) the deletion waits for the author's choice:
   *Delete the rules* removes the rules filtered **only** to the deleted layers; *Keep the rules* takes the layer filter off them
   (`patchRule … unset: [layer]`). A rule shared with a layer that stays is **never removed**: the deleted layer is taken off its
   list. Both are one history command (`Together`: the layer removal and the logic edits, carrying the logic effect so the save
   replays it through `logic.edit` and an undo of a saved deletion replays the inverse). The dialog says that a rule with no layer
   filter answers any click no other rule claimed (§21), so detaching is a choice, not a default. A layer with no rules is deleted at
   once, and a cancelled dialog changes nothing.

**The Script tab (§7.1)**

8. **The text is the document's `open:`, `behavior:` and `flows:`, in that order, as YAML** (`formatScript`), and nothing else of the
   resource: another top-level key in the text is refused with a line ("not edited here"), a section of the wrong shape is
   refused at its line, and a syntax error is shown at its line and column. A key with nothing under it is no section.
9. **A draft is committed when it parses and typing pauses (700 ms), as one history command; it is also committed on Save, on
   leaving the tab and before an undo or redo.** The commit is a `ScriptEdit` command, so Ctrl+Z from any tab undoes it, Redo
   redoes it and the dirty marker and Save are shared with Design and Logic. A commit does not coalesce with the next one (the
   edits log of §26 decision 5 assumes one command per entry).
10. **A commit is `LogicEdit`s.** `planBlockChange` turns a change of the two blocks into `removeRules` and `addRule` for the rules
    between the common head and tail (a rule that did not change is never rewritten) and `removeFlow` and `addFlow` for the flows
    after the common head (so the order of `flows:` is exactly the text's). They are applied locally with `applyWithInverse`, so
    the server's own refusals (a rule with no `on:`, a step naming two kinds) refuse the commit with their step path, drawn on the
    line. The `open:` block is a plain field of the GUI and is saved with the layers' own `authoring.setFields` (`planSave` gains
    `open`: set, or removed when taken out); `behavior:` and `flows:` stay out of that plan.
11. **Never destroys the file.** A draft that does not parse, or that the edits refuse, is never committed and never lost: the text
    stays, with the error on its line and in the state pill (`data-state`), through tab switches. A document that changes under an
    uncommitted draft (a Logic edit, an undo) marks the draft stale, keeps it and offers *Apply my draft* or *Discard my draft*;
    with no draft the text simply follows the document. Opening the tab, or leaving it, rewrites nothing: the document is never
    rebuilt from its own text unless something was typed.
12. **Lossless to the section.** A section the text leaves equal to the document is not written (so its comments and layout stay in
    the file), and a value is carried whole, so unknown keys inside a rule, a step or `open:` survive (tested over every logic
    fixture). A comment *inside* a section that is rewritten is lost, exactly as for a canvas edit (§19 decision 6: the server
    writes `behavior:` and `flows:` whole); comments elsewhere in the file are kept. A list-item path in `LosslessYamlDocument`
    would lift this.
13. **Live validation is the compiler's.** Every draft that parses is sent to `logic.validate` (450 ms after typing pauses) and each
    diagnostic is drawn on the line its step path names (`lineOfPath`, which falls back to the deepest place the text has), as a
    gutter mark and a row in the problems list that jumps to the line. The `open:` block is not part of `logic.validate`; its shape
    problems come back from `gui.validate` and the save.
14. **A plain editor.** The editor is a textarea with a line-number gutter, no editor dependency (a code editor is a heavy addition
    for three small sections). Tab indents two spaces and Escape then Tab leaves it. Syntax colouring and completion are not here.
    The tab needs the `logic` capability and shows the script read-only with a notice without it.

**The Recipes panel (§7.5)**

15. **The list is the vocabulary's** (`Vocabulary.recipes`, read from `recipes` in `logic.vocabulary`): a name and the one-line
    description, in the server's order. A server that lists none (an older plugin) shows an empty panel with a sentence.
16. **The form is the inspector's.** A recipe parameter becomes the typed field the inspector draws for the same kind
    (`paramInfo`): a number or integer field clamped to its range, a toggle, the variable picker (with "Declare a new variable"),
    the template field for text. A parameter that names a layer (`layer`, `list`, `previous`, `next`, or a description that says
    layer) is a choice of the GUI's layers, and `layer` starts as the layer selected in Design. Required parameters left blank
    disable Insert, with the names as its tooltip.
17. **Insert is the server's expansion and one undo step.** The panel calls `logic.instantiate`, shows what came back, and inserts
    the rules with `addRule` edits at the end of `behavior:` through the canvas (`planInsertRecipe`): one history command, one
    `logic.edit` on save, the first new trigger selected. The parameters sent are the form's non-blank values, so an optional
    parameter's default is the recipe's. A refusal (`MTR-0086`, `MTR-0087`) is shown in the panel and nothing is inserted; the
    `MTR-0088` warnings of an expansion that is valid but writes an action this server cannot run are shown with the insertion
    (`logicExpand` returns `{rules, warnings}`; the older `logicInstantiate` still returns the rules).
18. **Availability is found by asking, and greyed does not mean blocked.** The vocabulary does not say which recipes write an
    action the server cannot run, but the expansion does (§30 decision 8). So when the panel first opens it expands each recipe
    once with stand-in values of the right kinds (`standIns`; a recipe that needs a declared variable cannot be probed and is
    left as available), in parallel, and writes nothing. A recipe whose expansion warns is greyed with the server's reason (an
    entry that carries `available: false` in the vocabulary is greyed with its `unavailableReason`, so a server that learns to say
    it needs no probe). A greyed recipe can still be inserted, labelled *Insert anyway*, because the server treats availability
    as a warning, not a refusal (§30): the author may be building the GUI before installing the economy. (Reversible: block it,
    as the palette does for an unavailable action.)
19. **The trash slot was not listed** (blocked on #248, §30 decision 10; listed since #248, §36), and the confirm dialog's own GUI is not created from the
    panel (§30 decision 11: no cross-resource write in the authoring API).

**Verification.** vitest: `logic-linkage.spec.ts` (layer to rule mapping, verdicts to layers, the add plan, delete and detach with
shared rules and their inverses, the layer and rules as one command), `logic-script.spec.ts` (the text round trip over every logic
fixture, the errors and their lines, `lineOfPath`, merging into the document, the block edits applied to the target, the command),
`logic-recipes.spec.ts` (the real recorded registry, the parameter mapping, the stand-ins, the insertion plan, the expansion's
warnings), and `gui-save.spec.ts` (`open:` in the save plan). Playwright on the `?logic` fixture (`logic-linkage.pw.ts`,
`logic-script.pw.ts`, `logic-recipes.pw.ts`; the fixture gains `seed=linkage` and a `logic.instantiate` stand-in that writes the
recipes' golden shapes): Add click logic, the bolt, the hover highlight, delete with each choice, a shared rule, cancel, the
diagnostic on the layer, a Script edit saved as `removeRules`/`addRule`, `open:` saved with the layers, an invalid draft kept
across tabs and a stale one, a refused edit, line diagnostics, undo across the tabs, Ctrl+S flushing a draft, and Pay to receive
saved as the expected YAML. One existing assertion changed: `logic-inspector.pw.ts` now expects the trigger to show the layer's
name and `data-layer` where it expected the text `layer: buy`. The QA catalog has `gui.logic-linkage-live-save` (auto, against the
real local app) and `gui.logic-linkage-visual` (screenshots of the studio flow); they were authored and pass
`qa/runner.mjs check` but were **not run against a client** in this change.

**Deferred.** Creating the confirm dialog's GUI from the Recipes panel (§30 decision 11). A server-side `available` flag on each
recipe in the vocabulary, which would replace the probe. "Add click logic" for a multi-selection. Syntax colouring, completion and
inline `open:` validation in the Script tab. Keeping comments inside the three sections (a list-item path in `LosslessYamlDocument`).
Showing the linkage for item, block and furniture resources (only the GUI studio hosts the Logic tab).

## 34. Decisions taken in #227 (Q1: the showcase and close-out)

#227 closes the first GUI logic milestone (#189, Q1). It ships a **showcase pack** that uses the shipped GUI logic end to
end, its QA entries, and the coverage in §1.1. No engine change was needed for the showcase; the one code change is a test
(`ShowcasePackTest`). The decisions below are the choices the showcase forced.

**The showcase is a fixture pack, not a shipped example**

1. **The pack lives at `fixtures/packs/showcase`, id `showcase`.** There is no shipped GUI example to put it beside: the shipped
   recipes (`plugin/src/main/resources/recipes/`) are rule templates, not GUIs. A fixture pack is what the probe stages into every
   test server (`scripts/probe/probe.mjs` copies every pack under `fixtures/packs`), so the QA entries read it with no extra setup.
   The pack holds **no items**, so the item-count pins of other suites are unaffected. `PipelineRunnerTest` now expects nine packs.
   Whether a shipped example pack should exist as well is an owner question (it would be a second copy of the same GUIs); this
   change does not decide it.
2. **Five GUIs, one per showcase feature.** `shop` (the open command with a typed `category` argument; the paywall; the
   conditional stock; live `refresh`), `servers` (`connect`, `${server.online}`, `gui.tick`), `players` (the `online_players` list
   with `sort` and `search`, the `prompt` into `session.search`, the "Page x of y" indicator), `settings` (player booleans, on and off
   overlays in one slot, a right click), and `confirm` (a sub-GUI with a question argument, `gui.back`, `gui.click_outside`).
   The shop's other buttons open the other four. Every GUI announces its open and close on the console (`MATERIONE-SHOWCASE-*`).

**Declarations and economy**

3. **The price is in points, the XP integration, not in Vault.** `currencies/points.yml` binds `integration: xp`, which needs no
   economy plugin, so the paywall runs on a bare server and in the QA probe. The showcase does not depend on `materione:money`.
4. **Stock is a server-scope number and persists.** `showcase:stock` (default 3) is written to `storage/variables.json` (§3.1), so
   a stock left at 0 stays 0 until it is reset. The QA entries set it with `materione var set - showcase:stock 3` in every
   `finally`.
5. **The toggle is a player boolean with two item layers in one slot.** The "on" and "off" overlays are two item layers
   (`lime_dye`, `gray_dye`) under `visible_if`, and the topmost visible layer answers the click. This needs no overlay glyph. The
   sold-out barrier works the same way, and the flow repeats the stock check, because a layer that is only drawn does not refuse a
   click (§6.3).

**Authoring findings (not fixed here)**

6. **YAML reads `yes` and `no` as booleans, so a layer named `yes` is skipped.** The first confirm GUI used the ids `yes` and `no`.
   Its layers were skipped with `MTR-0052` "id must match [a-z0-9_]+", and the diagnostic showed `layerId=null`, so the author
   could not see which layer was meant. The showcase uses `accept` and `decline`. For the owner: the diagnostic should show the raw
   key, or the GUI loader should reject a boolean id with its own message.
7. **A text layer's rejection does not say why.** The first shop tip, `<gray>Tip: left click buys` at `x: 60`, was skipped with
   `MTR-0052` "text contains unsupported characters or has an invalid row". The shipped tip (`Click to buy`, plain, at the same `x`)
   compiles. The rich prefix and the text itself changed in the same edit, so the cause is not isolated. A diagnostic that names
   the cause (the character, or the x and the width) would make this a one-step fix.
8. **A rich-text argument reaches a text layer untested.** The settings GUI passes `question: "<yellow>Turn the tips off?"` to the
   confirm GUI's `${gui.args.question}` text layer. Compilation accepts it. Whether a `<…>` tag inside an argument renders (rather
   than showing as text) is not checked by any entry; the blessing run must check the confirm GUI's question.
9. **The test helper that compiles rules lacks the prompt.** `LogicTestSupport.withBuiltIns` does not register `prompt`, so a test
   that compiles a GUI with a prompt sees `MTR-0057 unknown action 'prompt'`. The plugin registers it at boot through
   `BuiltinLogic.registerPrompts`, and `ShowcasePackTest` does the same. This is a test-support gap, not an engine fault.

**Verification**

10. **`ShowcasePackTest` (plugin) compiles every showcase GUI** with the real GUI compiler (with overlay allocations), the real rule
    compiler and the real open-block compiler, against the built-in registries and the pack's declared variables. It asserts zero
    diagnostics, zero skipped layers and steps, and the shop's `open:` block (`commands: [shop]`). `LosslessYamlTest` walks every
    fixture pack and passes on the new files.
11. **The QA catalog has eleven `showcase.*` entries** (`qa/catalog/showcase.mjs`): nine automatic and two visual (the tip line, and
    the five-GUI tour). Each names the parity row it demonstrates. They are **not run against a client** in this change: the probe
    loop needs the 26.x seam and a windowed client, which this change could not drive. The ledger is untouched; blessing runs on
    main after merge (qa/README.md). `qa/runner.mjs check` passes.
12. **Two QA mechanics are unverified until the blessing run.** The entries run `/shop` as the probe player with
    `execute as Probe run shop` (a server-side dispatch, not a typed client command), and they answer the search prompt by sending
    chat through `mc.player.connection.sendChat`, as `guilogic.search-recipe` does. If the first does not open the shop for a
    player, the fix is a typed client command (`typedChat`).

**Coverage**

13. **Every §1 row that is not "Later" has a line in §1.1**, naming the showcase GUI or the QA entry that demonstrates it, or the
    existing entry (the `guilogic`, `guidynamic`, `guioverlay`, `logic`, `opencommands` and `variables` suites), or **a gap** with
    its reason. The gaps are collected at the end of §1.1.

## 35. Decisions taken in #8 (the content browser is a GUI resource)

#8 ships the in-game content browser, and its owner decision (#284) is that the browser is an ordinary `gui` resource in a
built-in pack ([`architecture.md`](../architecture.md) §7.3, [`runtime-systems.md`](runtime-systems.md) §8.3). The GUI runtime
needed three small, generic additions to carry it, none of them browser-specific. Each decision is reversible by the owner.
The code is in `plugin.gui.list` (`ContentTreeSource`, `ListEntries`, `ListPage`), `plugin.gui` (`GuiRuntime.openForCommand`),
`core.runtime` (`RuntimeContentIndex`) and the pack `plugin/src/main/resources/builtin-packs/materione/`.

1. **`content` is a fifth built-in list source** (`api.logic.ListSource`, registered under `materione:` through the public API
   like the others, §28.6). It lists the live content as packs, folders and entries, one location at a time, from a view of the
   shadow (`RuntimeContentIndex`, §28 decision 12 does not apply: it is not `cached`, because it reads an in-memory tree). Its
   parameters are `arg` (the GUI argument that carries the location, default `path`) and `search` (the session variable that
   carries a search). The location is **a GUI argument, not a session variable**, so opening a folder is `gui.open` of the
   same GUI with a new `path`, and `gui.back` is the way up: the back stack (§21, *Sessions and the back stack*) restores the previous window with its
   arguments, variables and page. The alternative, a session variable plus a parent computed in YAML, was rejected because the
   expression language has no substring function and a "back" would then have to be tracked by hand.
2. **A source may read the window's context.** `entries(params, context)` reads `gui.args.<arg>` and `gui.session.<search>` from
   the viewer's context, as `variable` reads a declared variable. This was always allowed (§28 decision 4); it is now used for
   navigation, and the source names which arguments it reads, so the editor and a reader of the GUI can see them.
3. **While the search variable holds text, `content` lists every item of every pack, flat.** The source decides the scope (all
   content, not the current folder); the list's own `search:` key still decides which entries match (§29 decision 17). The two
   do not overlap: with no search text the list is unfiltered and the tree shows.
4. **A list's `search:` matches every word.** The text is split on whitespace and every word must be contained, ignoring case, in
   at least one of the listed fields (§29 decision 17 read it as one substring). One word behaves as before; several words may
   match in any order and across fields, so a search over an item's id and its name finds `ruby sword` in `Ruby Sword` and in
   `shop:ruby_sword`. A phrase that matched as a substring still matches, so no existing search narrows.
5. **The page state gains `size`.** `gui.page.<list>` is now `{number, count, size}`; `size` is the number of entries after the
   list's search and filter, over every page (0 when it has none), so a GUI can say "no results". `ListPage.of` reads a state
   without it as 0. Reading it from an **item** layer lags one render, because the item layers of a render read the page state
   the previous render recorded (§28 decision 10 records it after the slots are decided, before the title); a **text** layer
   reads it in step, so the browser shows its page indicator and its "nothing here" messages as title text.
6. **`GuiRuntime.openForCommand(player, gui, args)` is the open path for a command that names a GUI.** It checks that the GUI is
   live and hands the open to the opener on the player's thread, and it answers with the opener's own result. Unlike
   `GuiRuntime.open` (the API), it does not require `materione.gui.open`: the GUI's `open:` block governs, as it does for an open
   command (§23 decision 17). `/materione browse` is this call with the id `materione:browser` and the argument `search`.
7. **The pipeline publishes `RuntimeContentIndex`** with each shadow: a row per live item, plus a row per block or furniture whose
   placement item was generated; a block or furniture with a real placement item is that item's row (runtime-systems §8.3). A
   row's folders are the folders of its declaring file under its content directory. The view is built from the same `ContentPack`s
   the shadow is, so a quarantined definition is not in it.
8. **The browser's own rules.** `materione:browser` takes `path` and `search` arguments (both optional), opens only with
   `materione.browse`, and takes with `give_item` inside a flow whose first step is `has_permission: materione.browse.get`
   (the deny is a `message`). Every take goes through the flow, so the check is made on every take.

**Verification.** `BuiltinBrowserPackTest` (plugin) compiles the shipped file with zero diagnostics, then runs the same file
through the real router and rule engine: root, pack, nested folders, back, take one, take a stack, the permission checked on every
take (revoked between two clicks), the open refusal, search (words, case, id), the empty messages, paging and the page kept across
a folder and back. The one step it replaces is `give_item`, which needs a server to build a stack. `ContentTreeSourceTest` and
`RuntimeContentIndexTest` pin the source and the tree; `ContentIndexBuildTest` pins which resources become rows. The QA entries
(`qa/catalog/browse.mjs`) are the in-game half and are **not run against a client** in this change.

## 36. Decisions taken in #248 (X: the custody consume primitive; the trash slot as a composition)

#248 began as "the trash slot, a consume-incoming action" and was re-scoped by the owner under D12
([`architecture.md`](../architecture.md) §3.1, [`primitives.md`](primitives.md) §4.8, §5.1): the engine ships the primitive underneath,
the **atomic exit of items held in custody** (tier 2 of [`gui-input-durability.md`](gui-input-durability.md)), and a trash slot is a
recipe over it. The code is in `plugin.logic.action` (`ConsumeCustodyAction`), `plugin.escrow.runtime` (`CustodyConsume`,
`EscrowRuntime#consume`), `plugin.gui.input` (`StrictInputs`, `InputSlotPort#consume`), the recipe
`plugin/src/main/resources/recipes/trash_slot.yml` and the fixtures `fixtures/packs/guilogic/guis/consume.yml` and `trash.yml`.
Each decision is reversible by the owner. The durability side (what runs, the kill-boundary rows) is
`gui-input-durability.md` §12, *Row X as built*.

**The action.**

```yaml
- custody.consume: { slot: 4 }                        # consume what slot 4 holds; the flow goes on once it is decided and applied
- custody.consume: { slot: "${slot.index}" }          # the slot may be a template (a count, as in take_item's amount)
- custody.consume: { slot: 4, await: saved }          # ...and only once a saved snapshot containing it is read back from disk
  else:
    - message: "<red>Nothing happened."               # a failure runs this and ends the run
- give_money: 100                                     # an effect: with await: saved it cannot outlive a rolled-back consume
```

1. **Name and shape: `custody.consume: {slot, await}`.** `slot` is the window slot whose held stack is consumed (a literal or a template that
   renders to a whole number, 0 to 53); `await` is `decided` (the default) or `saved`. Category `player` (no new palette colour), scalar
   shorthand `slot`. It passes D12's tests:

   | Test | How it passes |
   |---|---|
   | U1 name | `custody.consume` names a capability (consume) of a holder (custody), not a use case: the same step trashes, redeems a voucher, spends a ticket, pays out a crafted item |
   | U2 generality | any slot of any GUI held by the player or a keyed PDC holder; the slot is a template. A stack holder is the explicit limit: its PDC has no acknowledged save, so consume/HAND_OFF refuses |
   | U3 composition | proved by `trash_slot`, which is YAML only; a voucher or ticket is `custody.consume` then `give_item` or `run_command`, in a flow, with no Java |
   | U4 self-description | an `ActionType` in the actions registry with a typed schema (`slot`, `await` with its allowed values) and `takesElse` in the vocabulary, so the editor draws the `else` branch and an agent sees it |
   | U5 safety | fails soft with a reason and a visible refusal; offline: `parse` rejects a bad slot or `await` at load; it never runs outside a flow; its crash semantics are tier 2 and the barrier, proved by K10 to K14, K17 and K23 against a real kill |

2. **One vocabulary: `gui.slot_item clear` and `set_amount 0` route through the same exit.** On a `durable: required` layer they were refused
   until the exit ledger existed (§25, §6.5). They are now that exit, begun and **not waited for**: `gui.slot_item` is a synchronous action and
   not a guard. An immediate refusal (the slot is busy, the player's gate is shut) is the step's failure; a refusal that comes later (the
   INTENT could not be recorded, so the stack went back) is told to the player on the actionbar (`MTR-0102`). A flow that must not go on
   without the consume uses `custody.consume`. On a `best_effort` layer `clear` keeps its behaviour (the slot is emptied at once and the
   journal follows). Alternatives rejected: keeping `clear` refused (two vocabularies for one deletion, and the refusal is the very gap
   #248 exists to close) and a new action only (a second way to delete the same stack, with a different crash story).
3. **`custody.consume` fails on a `best_effort` slot** ("not held in custody"). A consume the author expects to be atomic and to gate an
   effect must not quietly become a journal deletion; the author uses `clear` there, or sets `durable: required`.
4. **It guards what follows (`takesElse`).** Like `prompt`, the step runs as a `require` around itself: a failure runs the `else:` steps and
   ends the run, so an effect written after it is never reached without the consume (and a `chance:` on it is a load error). The engine
   hook (`ActionType#takesElse`, the step compiler) was generalised from "asks a question" to "guards what follows"; `prompt` and
   `custody.consume` are the two. The vocabulary's `takesElse` list therefore grows by one (the editor demo snapshot golden moved).
5. **What it waits for.** `await: decided` completes at step 3 of the tier-2 exit: the INTENT is durable and the stack is gone and the player
   sees it. It requests **no** `Player.saveData()` (D8) and parks the run for the milliseconds of one ledger append. `await: saved` registers
   the exit as one that carries an effect (`ExitCallbacks#carriesEffect`), the only kind that makes the barrier ask the platform for a save
   (once per `escrow_barrier_cooldown_seconds`), and completes only when the barrier has read a saved snapshot that contains the consume back
   from disk. If the proof never comes (the player leaves, the plugin stops, no snapshot within 10 minutes) the step **fails**, so the
   effect does not run; the consume stands and recovery applies it. The effect therefore runs at most once, and only after the decision and
   the snapshot are both durable (K23).
6. **Failure reasons and diagnostics.** None added. The step fails with a reason in the trace for: not an input slot of the run's open GUI, an
   empty slot, a `best_effort` slot, a busy slot (another exit is in flight), a shut gate or an unsupported platform (`MTR-0100`, the
   actionbar line of §7), an unreadable state (`MTR-0101`), a decision that could not be recorded (`MTR-0102`) and, for `await: saved`, an
   abandoned proof. The next free code is still `MTR-0104`.
7. **Step 1 freezes the held entry (`Tier1.freezeSlot`).** The incoming stack became a HELD entry by an ordinary tier-1 placement, where
   `accept:`, `gui.slot_change` and the account caps already ran, so the consume starts from custody and removes nothing from a source.

**The trash slot, as a composition.** The recipe `trash_slot` (parameters `layer`, `message`, `refused_message`) expands to one rule:

```yaml
- on: gui.slot_change
  layer: bin
  do:
    - if: "${slot.amount > 0}"
      then:
        - wait: 1
        - custody.consume: { slot: "${slot.index}" }
          else:
            - actionbar: "<red>That could not be trashed."
        - actionbar: "<gray>Trashed."
```

8. **Why `wait: 1`.** `gui.slot_change` fires before the change lands (§25 decision 4), so a consume in the event would see the slot as it
   was. One tick later the click has been applied and the slot holds the new stack as a HELD entry. A `slot.amount` of 0 is the item being taken
   *out*, which needs no consume. The race the recipe used to be blocked on (§30 decision 10) is closed by the entry being frozen: a second
   item cannot land in a frozen slot (a refused click, *busy*), the consume takes whatever the slot holds when it runs, and an item the player
   took back in the tick between leaves an empty slot, so the consume fails and the `else` runs. None of these can lose or duplicate a stack.
9. **The layer must be `durable: required`.** A recipe writes rules, not layers (§30 decision 12), so it cannot enforce it: on a `best_effort`
   layer the consume fails with the reason in the trace and the player sees the recipe's `refused_message`. A server without checkpoint support
   shows the layer inert (`MTR-0100`), as for every required layer. A load-time warning for a `custody.consume` under a non-required layer is
   not built (it would be the `GuiRuleDiagnoser`'s, like the layer-exists check of §25 decision 13).
10. **The recipe is listed in the Recipes panel** (§7.5, §33 decision 19 no longer excludes it) and ships a golden expansion
    (`fixtures/golden/recipes/trash_slot.json`).

**Not built, on purpose.** A partial consume (`amount:`), consuming every slot of a layer (`layer:`) and a
`Tier1.beginExit` caller (an incoming stack straight from the
cursor): the trash is placed first, then consumed.

**Verification.** `CustodyConsumeTest`, `ConsumeCustodyActionTest`, `BuiltinRecipesTest`, `VocabularySourcesTest` (the `takesElse` list), the
editor demo snapshot golden, and `PaperEscrowCrashIT` (`gui-input-durability.md` §12, *Row H as built*: K10 to K14, K17, K23 and KT against a real
`kill -9`). QA `guiescrow.trash-consumes`, `.trash-feedback`, `.consume-effect-after-save`, `.consume-refusal-runs-else` (**not blessed**:
blessing runs happen on main).

---

## 37. Decisions taken in #327 (rules without a host, and the world triggers)

#327 is the first issue of M6.5 ([`agent-benchmark.md`](agent-benchmark.md) §4.2, rank 1) and a primitive under D12
([`architecture.md`](../architecture.md) §3.1, [`primitives.md`](primitives.md) §4.1, §4.3, §5.5): until it, every rule lived in an item, block,
furniture or GUI, `damage` was durability damage, and nothing fired on a hit, a kill, a join or a quit. It adds one content type and
six new triggers (and a new host, `rule`, on the existing `death`), and nothing named for a use case. The code is in `content.rules` (`RuleType`, `RuleDefinition`), `plugin.logic.trigger`
(`WorldTriggers`), `plugin.runtime.behavior` (`WorldEventListener`, `WorldEvents`, `WorldRules`, `RuleDocuments`) and
`platform.entity` (`EntityPalette`); the fixtures are `fixtures/packs/worldrules/`, `fixtures/rules/` and the golden `editor-demo-snapshot.json`.
Each decision is reversible by the owner.

**The document.** `rules/*.yml`, content type `rule`, one resource per top-level key like every type:

```yaml
quest_zombies:
  description: Two zombie kills finish the quest        # optional, for the people who edit it
  behavior:
    - on: entity.kill
      type: zombie                                      # a trigger filter (§4.3)
      do:
        - set_var: {var: "quest:zombie_kills", op: add, value: 1}
        - if:
            var: {var: "quest:zombie_kills", op: ">=", value: 2}
          then:
            - give_item: {item: "quest:zombie_trophy", amount: 1}
  flows: {}                                             # named flows, callable from this document's rules
```

1. **The shape is a host's shape, so one tool edits all of them.** `behavior:` and `flows:` are exactly what an item holds (§2.1, §2.4): the
   same compiler (`FlowCompiler`), interpreter, trace and `LogicEdit` vocabulary, with the same `on`, filters, `if`, `do`, `id`, `cooldown`,
   `charges`, `per` and `on_cooldown`, and per-action `chance:` and `delay:`. There is no rule-level `chance`: it is an action's option
   (the benchmark's first draft of T15 and T24 had that slip, `agent-benchmark.md` T15). The document adds only `description:`. It is a
   behavior *host* and nothing more: it declares no appearance, produces no pack output (`compile` emits nothing) and allocates nothing.
2. **One schema source.** `RuleType.describe()` is the self-description (`description`, `behavior` with the `behavior-rules` widget,
   `flows` as an advanced field). The studio's generic form, `authoring.describe`, the scaffold test and the code-mode SDK generator read it;
   nothing about rule documents is hand-written elsewhere. `behavior` is declared once for every host that wants it
   (`LogicSchemas.behavior(description)`).
3. **The directory is `rules/`; the type is `rule`.** The pipeline derives the type by stripping the trailing "s" (`ParseStage`), as for
   `items/`. `PackLayout.CONTENT_DIRS`, both scaffold tables (core and web, which a test pins byte for byte) and the authoring transaction's
   "has logic" list name it. The scaffold is a complete, running rule on `player.join`.
4. **What is a rule document, and what quarantines.** `behavior:` must be a list and `flows:` a mapping; anything else parses to nothing and
   the document is reported with `MTR-0008` and left out of the live shadow, its pack unaffected. Inside a good document, a bad rule
   is the compiler's business: it is skipped alone with `MTR-0021` (an unknown `on:`, a filter the trigger's schema rejects, an unknown
   entity type or damage cause) and its siblings load. A rule whose trigger no rule document fires (a `click`) loads and is reported as
   never running, the warning a GUI gives for the same mistake. No diagnostic code was added ([`plugin.md`](plugin.md) §5). The "never
   runs" warning is raised when the shadow is built, where the host is known, by `UnfiredTriggers`; the offline validation raises the
   same warning when the draft names its host (`LogicDraft.host`, set by an authoring transaction for a `rules/` document, #378), so a
   dry run reports it for a created rule document. `logic.validate` from the editor passes no host and reports the compiler's problems only.
5. **The subject of a host-less run is the player the trigger names.** The run belongs to that player (cooldowns are per player by
   default, a `cancelPlayer` on quit ends its parked runs, the player facet and PlaceholderAPI resolve for them). `player.*`, `server.*`,
   `world.*`, `location.*` and `vars` are in every trigger's context, built by the same code the item bridge uses (`BehaviorService.baseRoot`).
   `location.*` is where the event happened (the entity that was hit or died), which is not always where the player stands. A trigger
   with no player (an entity killed by a mob, an explosion) fires nothing: there is no subject to run for. Target addressing
   (an action on the struck entity, #331) is a different primitive and is not here.
6. **`item.*`.** On `entity.hit`, `entity.kill` and `player.hurt`, `item.*` describes the item the rule runs for: the item that hosts the
   rule, or, in a rule document, the item in the player's main hand. A rule document can therefore ask `item.material == "diamond_sword"`
   without being an item.

**The triggers.** Registered through `api.logic().triggers()` like the built-ins, by `WorldTriggers`, so the compiler checks `${...}` paths
against these schemas and the editor autocompletes from them (§2.6). Every trigger also guarantees `player.name/uuid/gamemode/sneaking/op/
health/level/x/y/z`, `location.x/y/z/world`, `world.name/time`, `server.online/max` and `vars.*`.

| Trigger | Fires when | Filters | Context added | Hosts | Cancellable |
|---|---|---|---|---|---|
| `entity.hit` | a player damages an entity, in melee or with a projectile they shot | `type`, `cause` | `target.type/uuid/health/is_player`, `damage.amount/final/cause`, `item.*` | item (main hand), rule | yes |
| `entity.kill` | an entity dies and a player is its killer | `type` | `target.type/uuid/is_player`, `item.*` | item (main hand), rule | no |
| `player.hurt` | a player takes damage | `cause` | `damage.amount/final/cause`, `source.type`, `item.*` | item (main hand, each armor piece), rule | yes |
| `player.join` | a player joins | none | `player.first_join` | rule | no |
| `player.quit` | a player leaves | none | none | rule | no |
| `player.respawn` | a player respawns | none | none | rule | no |
| `death` | a player dies (existing trigger; an item fires it once per stack among the drops, a rule document once) | none | `damage.cause` (rule documents) | item, rule | no |

7. **Filters.** `type` is an entity type key without namespace (`zombie`, `cave_spider`), `cause` a damage cause (`entity_attack`,
   `projectile`, `fall`); each takes a value or a list and is an enum filter, so an unknown value is `MTR-0021` at the key and skips the rule
   rather than loading it on every entity. The palettes come from the running server (`EntityPalette`: the entity registry, the Bukkit damage
   causes), the way item materials come from the platform. Where no server registry exists (a headless unit test) the `type` filter accepts any
   name rather than none; the harness and the plugin always have one.
8. **`damage.amount` and `damage.final`.** `amount` is what the attacker dealt before armor and effects; `final` is the event's final damage
   at the moment the rule runs, after other plugins that ran earlier have changed it. `target.health` is before the damage lands. A kill
   carries no health, because the entity has none left.
9. **Item hosts, order and cancellation.** An item's `entity.hit`/`entity.kill` rules fire for the item in the main hand, as `break` does
   today; `player.hurt` fires for the main hand and each armor piece worn that has Materione identity (the `item.*` of each is its own).
   Item rules run first, in hand-then-armor order, then rule documents' in pack and document order. Each run is one `BehaviorEvent` with its
   own cancel flag; for a cancellable trigger the dispatch is a synchronous pre-event (reads that would need another thread fail closed, as
   for `break`), and a `cancel` from any run cancels the Bukkit event after that run. Later runs still happen, so a rule document
   sees the event an earlier item rule cancelled, and may cancel it again. A `cancel` on a trigger that cannot be cancelled is accepted and
   ignored (`Cancellation`).
10. **Firing sites, ordering and threads.** `WorldEventListener` is static wiring (`EntityDamageByEntityEvent`, `EntityDamageEvent`,
    `EntityDeathEvent`, `PlayerJoinEvent`, `PlayerQuitEvent`, `PlayerDeathEvent`, `PlayerRespawnEvent`), registered once; rules are read from
    the live shadow at event entry, so a reload needs no listener churn. Handlers run at HIGH with `ignoreCancelled`, so protection plugins
    decide first and an event they cancelled fires nothing; `player.quit` runs at LOW so a quit rule starts before the logic service ends the
    player's parked runs (a step after a `wait` in a quit rule does not run: the player is gone). On Folia the damage and death events fire on
    the *victim's* region and a player in another region cannot be read from it, so the event fires nothing for that player (a fight inside one
    region, the common case, is unaffected); `Bukkit.isOwnedByCurrentRegion` is the check, always true on Paper. Every world mutation a rule
    makes goes through the actions and the thread gateway as before (D8); the listener only reads and dispatches.
11. **Budgets: a trigger nobody answers costs a set lookup.** The `WorldRules` registry view holds the rule documents by trigger and
    the set of triggers any item answers. Each handler's first line is `WorldEvents.subscribes(trigger)`: one view lookup and one set lookup,
    measured at a few nanoseconds per event against a budget of 250 ns (`WorldEventGateTest`, [`plugin.md`](plugin.md) §9). The work after it is
    staged, each step paid only if the one before found a reason: the rules whose trigger and filters match the event's facts (a cheap string
    compare), then the stacks (a persistent-data read, only when some item has a rule on the trigger), then the context tree. A server whose
    only rule is `entity.kill` of zombies builds no context for the death of a cow
    (`anEventThatNoRuleFiltersInReadsNothingFromTheWorld`).
12. **The editor.** `logic.get` and `logic.edit` accept `rules/` documents (and `furnitures/`: the folder list said `furniture/`, which the pack
    layout does not use, so furniture logic had been unaddressable through this API); every `LogicEdit` applies to a rule document and an
    edit that adds the first rule creates `behavior:`. The studio's generic form edits a rule document through the `behavior-rules` widget, whose
    event list is `firedBy` filtered to `rule`, so the six new triggers and `death` are offered and an item-only trigger is called out. The *graph* Logic tab is
    bound to the GUI studio's document state today (`LogicCanvasState` takes a `GuiStudioState`) and does not open a rule document: that is a
    follow-up, not built here (below).
13. **One trigger for a player dying: `death`, not `player.death`.** The first draft added `player.death` (host `rule`) beside the existing
    `death` ("the player dies carrying the item", host `item`): two names for one Bukkit event, split by host. D12 says the host is not part of
    the name (`entity.hit` already names both), so `death` is now the one trigger: `firedBy` is `item` and `rule`, and it gains `damage.cause`,
    provided to rule documents. Existing packs and fixtures that use `death` load unchanged and no file is rewritten; an item's `death` rule still
    fires once per stack among the drops (`ItemBehaviorListener`, untouched) and a rule document's fires once per death
    (`WorldEventListener`). An item-only `death` rule opens the gate, but the world bridge then finds no document rule and passes no item hosts,
    so nothing fires twice.

**The universality tests (master §3.1), for the content type and the triggers.**

| Test | How it passes |
|---|---|
| U1 name | `rule` names what the document is (rules), `entity.kill` and `player.join` name what fires. None names a quest, a drop, a boss or a welcome: T06, T10 and T15 are compositions of them |
| U2 generality | `entity.*` works for any entity type (the `type` filter is optional) and any player; a rule document is hosted by nothing, so it works for any pack; an item hosts the same triggers with the same context |
| U3 composition | every action and condition composes with them, proved by `worldrules` (a variable counted by `set_var`, read by a `var` condition, `give_item`, `cancel`, `run_command`) with no Java; the benchmark tasks T06, T10 and T15 are YAML only |
| U4 self-description | a content type and six triggers plus a host on `death` in registries, with typed filters, context and hosts, in `logic.vocabulary` and `authoring.describe`; no hand-written schema anywhere else |
| U5 safety | a rule document validates offline like any host's (`logic.validate`, a transaction's dry run), a bad rule is skipped with a code, the handlers never throw into the server (a bridge failure is a diagnostic and the event carries on), and the harness proves it on Paper |

**Deviations from the issue's sketch, and what was decided.**

| Sketch | As built | Why |
|---|---|---|
| `player.join` and `player.quit` share a row whose context is `player.first_join` | `player.first_join` on `player.join` only | The flag is only meaningful on a join (at a quit the player has always played before), so `player.quit` has no context of its own. The sketch's six rows are seven triggers, all built. |
| `player.respawn` carries `damage.cause` | It does not; `death` does | The cause of a death is known when the player dies. Carrying it to the respawn needs per-player state kept across the death screen for a variable nobody needed; a rule that wants it on respawn writes it in a `death` rule with `set_var`. |
| Item hosts "worn or held" for `player.hurt`; held for `entity.hit` and `entity.kill` | As sketched | Held is the main hand only; the off hand fires nothing (as for `click`). |
| A `chance` among a rule's keys | Not added | `chance` is an action option and composes with these triggers. A rule-level option would be a second way to say it. |
| "the Logic tab opens it" | The generic Studio form opens it; the graph tab does not | See decision 12. |

**Not built, on purpose.** `emit`, the `custom` trigger and a cross-resource `call` (no benchmark task needed them, [`agent-benchmark.md`](agent-benchmark.md)
§4.1); a command-run or chat trigger; block and furniture hosts for the combat triggers (a block has no
holder in a fight); the graph Logic tab for non-GUI documents.

**Verification.** `RuleTypeTest` (schema, parse, the lossless round-trip of `fixtures/rules/` byte for byte, the fault fixtures), `RuleDocumentsTest`
(the golden and the broken documents through the real compiler and trigger registry, the misfire warning, the context-path check, the view's index),
`WorldTriggersTest` (the table above as assertions), `WorldRulesPipelineTest` (files to the live shadow with no diagnostics, an item beside a document),
`WorldEventGateTest` (the gate, the staged reads, the budget), `LogicAuthoringApiTest` (a rule document through `get` and `edit`),
`ScaffoldSchemaConsistencyTest`, the editor demo snapshot golden, and `PaperWorldEventsIT` on Paper 1.21.4 (a join greets, a hit on a document and
on an item, two kills finish a quest held in a player variable, a cancelled hit leaves the husk at full health, hurt, death, respawn and quit). QA
`worldrules.entity-kill-counts`, `.entity-hit-document-and-item`, `.entity-hit-cancel`, `.entity-filter-excludes-other-types`,
`.player-join-greets`, `.player-death-and-respawn` (**not run and not blessed**: they need the local 26.x probe, and blessing runs happen on main).

**Owner questions.** (1) *Folia, a hit across regions:* the event fires nothing when the attacker is in another region than the victim,
because their inventory and variables belong to their own thread. A cross-region hit needs a hop to the attacker's region and then the
dispatch, which changes the cancel semantics of `entity.hit` (a cancel must be decided before the event returns); it is not built and the
choice between "hop and lose cancel" and "stay as is" is the owner's. (2) *The graph Logic tab for non-GUI hosts:* opening a rule document, or an
item's rules, in the node editor needs `LogicCanvasState` to stop assuming a GUI document. The form editor covers rule documents meanwhile.
(3) *Kills by something other than a player* (a mob kills a mob, a trap kills a zombie) fire no trigger, because a run needs a subject; a
subject-less run is a different primitive (a `world`-scoped run) and is not proposed here.

---

## 38. Decisions taken in #333 (keyed state: step 1, the item scope; step 2 designed, not built)

[#333](https://github.com/xef5000/materione/issues/333) is rank 7 of the benchmark ([`agent-benchmark.md`](agent-benchmark.md) §4.2, the `STATE`
gap) and the last blocker of #320's own example: Bloodfang's level lives on the sword, not on the player. The variable store of §3 and §20 has
four scopes whose holders are fixed (the run, a GUI window, a player, the server). The primitive the benchmark asks for is **state by holder**
([`primitives.md`](primitives.md) §4.6, §5.2: `player` and `server` are special cases of a keyed store), delivered in two steps. **Step 1 (built here)
adds one holder, the item stack a run is about.** **Step 2 (designed here, not built) opens the key.** Neither adds a feature: nothing in the engine
is named for a weapon, a level or a trade. The code of step 1 is in `api.logic.variable` (`VariableScope.ITEM`, `StackRef`, `StackVariableStore`),
`behavior` (`MapLogicContext`, `ExprRoots`, `CompileUnit`), `content.variable` (`VariablesType`), `plugin.logic.variable` (`PdcStackVariableStore`,
`BukkitStackRef`, `RunVariables`, `VariableService`), `plugin.runtime.behavior` (`BehaviorService`, `WorldEvents`),
`plugin.view` and `plugin.gui` (`OpenRequest`, `ViewerRecord`, `GuiOpener`, `GuiContexts`, `ViewChanges`) and
`plugin.command.VarCommand`; the
fixtures are `fixtures/packs/itemvars/`. Traces: D1 (the value is runtime state, never content, and files stay the source of truth), D5 (a registry
entry with self-description puts the scope in the editor and the code-mode SDK with no SDK code), D6 (one pipeline: the declaration is a file, the checks
are the compiler's), D8 (every read and write goes through the thread rule of the owner), D12 (a primitive; the universality tests are below), D13 (the
SDK is generated from the same description). Each decision is reversible by the owner.

### Step 1: the stack holder (built)

**The declaration and the spelling**

1. **`scope: item` in the `variable` content type.** The declaration is the `{id, scope, type, default, description}` of §20 decision 2 with a third
   scope, `item`: `VariableScope.ITEM`, and `item` joins the enum the schema lists. `type` and `default` mean what they mean for the other scopes (a never-written
   variable reads its default). The `description` of the content type and of its `scope` field say, in the self-description every consumer reads, that
   stacks whose item variables differ do not stack (decision 7).
2. **`item` is a fifth reserved scope word** (§20 decision 3). Reads are `vars.item.<pack>.<key>` (pack spelled, like every persistent read, §20 decision 6).
   Writes spell the target as for the other scopes: `item.level` (pack = the resource's own pack), `item.rpg.level`, or the qualified `rpg:level`
   (scope from the declaration). `set_var` (all seven ops) and the `var` condition take it with no change of shape; the editor's variable picker lists
   the declaration and writes the qualified spelling.
3. **`vars.item.*` is not the `item.*` root.** `item.id`, `item.material`, `item.amount` are the facts of the stack (§37 decision 6); `vars.item.rpg.level` is
   the state the stack holds. They share a name on purpose: both are about "the run's item".

**The run's item**

4. **The trigger declares it, the variable does not.** A run has an item stack when its trigger puts one in the run: every trigger fired by the item
   bridge (`click`, `break`, `equip`, `unequip`, `drop`, `pickup`, `consume`, `damage`, and `death` for an item) passes the stack the event is about; `entity.hit`,
   `entity.kill` and `player.hurt` pass the stack that hosts the rule, or, in a rule document, the item in the player's main hand (§37 decision 6). The
   bridge hands it to the binding of the run (`VariableService.bind(player, stack)`; `WorldEvents`, `BehaviorService.logicContext`). A `view.open` action started
   by that run carries the same handle into the view's fixed binding. Its history snapshot keeps the handle, and its open
   requirement, rules, layers and renders bind to it. The stack is **a handle, not a copy**: a read sees the stack's current
   data, a write lands on the stack. An empty hand is no stack. A trigger is "about an item" for the compiler
   exactly when its context schema provides the `item` root, so one declaration (the trigger's, §2.6) serves the runtime, the check and the editor's autocomplete.
5. **A handle belongs to one run or the view that run bound, and is checked at every access.** A carried
   stack is addressed by its fixed inventory slot, so opening a window does not leave the run on a pre-open
   item mirror. A handle also captures the physical stack token (Paper inventory/event mirrors to the same stack
   share it) and its Materione identity. A run parked by `wait` or `delay` resumes against that same subject:
   a reused slot with another stack, including another stack of the same id or a vanilla stack, reads absent and
   refuses a write. Amount is mutable state and not part of the subject key; a similar stack with another physical
   token is never substituted. A vanished or replaced subject takes the rate-limited `MTR-0065` miss path. A view
   retains the handle only for its record's lifetime.

**The store**

6. **A stack-specialized view behind the existing `VariableStore` port.** `StackVariableStore` extends the port with
   `read(declaration, stack)` and `write(declaration, stack, value)` (null clears), values and never defaults. Its
   `UUID` methods refuse a holder, because the holder is a `StackRef`, not a player id; the cross-server adapters of #25
   implement the `UUID`-keyed `VariableStore` and are untouched. Generalizing the holder of `VariableStore` itself is step 2's design (below), so that there
   is one port once there are more than two kinds of holder. `PdcStackVariableStore` (plugin) keeps each value in the **stack's own persistent data** under
   `materione:var.<pack>.<key>`, the key the player scope uses in a player's container, next to where `durability` keeps `materione:durability-used`. The value
   therefore travels with the stack, with no store of its own: through a drop, a pick-up, a chest, a hopper, an ender chest and a restart. `RunVariables`
   routes an `item`-scope declaration to it, and the `VariableService` API to the main-hand stack (decision 12).
7. **A stack with state does not stack with one that differs, and the store keeps state minimal.** Minecraft merges two stacks only when their data components are
   equal; the persistent data is the `custom_data` component, so two stacks whose item variables differ are never merged by a player, a hopper or
   a pick-up. The store maintains the converse: **a value equal to the declared default clears its key** instead of storing it, so two stacks in the same effective
   state carry the same bytes and stack together, and a stack whose variables are all at their defaults is byte-identical to one that never had any
   (a `reset`, or levelling down to the default, returns the untouched bytes). It follows that the state is **the stack's, not each item's**: a stack of 16
   shares one value, and splitting it copies the state to both halves, as Minecraft copies every component. Materione adds no behavior to what vanilla does with
   custom data in a crafting grid, an anvil or a grindstone.
8. **Threads (D8).** `set_var` of an item variable is `PLAYER_REGION` (`threading(P)`, as for player scope): the stack is touched on the thread of the player the run
   belongs to. The store asks the same question as the player store (`Access`: offline, owned here, or on another region): an offline owner reads absent and
   refuses a write; an owner on another region throws `ReadUnavailableException` for a read (the caller degrades as for a player variable) and refuses a write.
   No store call touches a world or an entity off its owner's thread, and the item store never schedules anything itself.
9. **Atomic mutation is not offered.** `mutate` (§20 decision 12) is for the server store, where concurrent writers exist; every write of a stack happens on its owner's
   thread, and `set_var` reads the current value and stores the result there, as it does for a player variable.
   A successful write notifies the view observer: a view that reads the declaration and is bound to an equal,
   stable stack handle is dirtied once and rerenders on its viewer's owner thread.

**Checks, at load and at run time**

10. **A rule that cannot name a stack is a diagnostic at load, with the existing code.** `MTR-0060` ("not a variable the trigger provides", §2.6) already checks every
    `${root.x}`; step 1 teaches it two more spellings of "this needs a stack": `vars.item.*` in a template, condition or call argument (`ExprRoots` adds `item` for
    it), and a declared write or condition that is not written as a template, which a step announces with `ParamReader.requireRoot("item", why)` (`set_var` and
    `var` do; any addon action may). A trigger that provides no `item` root (`player.join`, `place`) gets the warning with the trigger's name, the
    variable named and the hint "needs a trigger that is about an item stack"; the rule still loads, as for any unknown root. GUI triggers declare the optional
    subject stack, so `vars.item.*` and item writes compile in open requirements, rules, layers, titles and lists. `item.*`
    is the stack's facts and `view.item` names those facts from every opened kind. A GUI opened by a command, API or
    another run with no stack still opens: it reads defaults and reports the stack miss at run time.
    The offline validation (`logic.validate`, a transaction's dry run) runs the same compiler and reports the same warning. No code was added ([`plugin.md`](plugin.md) §5).
    *A known gap:* the roots are the trigger's, not the host's, so `vars.item.*` in a rule document on `death` (whose `item.*` is provided to item hosts only) passes the
    check and reads the default at run time.
11. **A run that has no stack reads the default, writes nothing and says so.** `RunVariables` answers an item-scope read of a stackless run with the default
    and refuses the write (the step fails with "no item stack to hold this item variable"), and reports `MTR-0065` once per variable per ten seconds
    (`VariableService.MISS_INTERVAL_MS`), because a hot trigger would otherwise report on every event. The author asked a stack for something it cannot answer.

**Readers that are not runs**

12. **The held item.** `LogicApi.variables().get/set(…, player, …)` (§20 decision 16) and `/materione var get|set|list <player> <pack:key>` address an item variable by
    **the stack the named player holds in the main hand** (`HeldStacks`); the player must be online for a write. An empty hand reads the default and refuses a write
    (`MTR-0065`); an owner on another region is an unavailable result, not the default. `%materione_var_<pack>_<key>%` and `…_<player>` read the viewer's (or the named
    player's) main hand the same way. The GUI `variable` list source refuses an item variable (it needs a run's stack).

**The universality tests (master §3.1) for step 1**

| Test | How it passes |
|---|---|
| U1 name | `item` names the holder (the stack a run is about), the same word the trigger context uses; the scope says nothing about a weapon, a level or an RPG. Bloodfang is two declarations (`level`, `xp`) and rules in a pack |
| U2 generality | any declared variable of any type on any stack the item bridge or a rule document's trigger names; a vanilla stack holds one as well as a Materione item (the key is the variable's, the identity is only checked for staleness) |
| U3 composition | read by the expressions and conditions every other scope uses, written by `set_var` (every op) with no engine code: `fixtures/packs/itemvars` is YAML only |
| U4 self-description | a value of the `scope` enum of the `variable` type in the registry; the editor's picker, `authoring.describe` and the SDK read it from there. No hand-written schema, no SDK code |
| U5 safety | offline-validated (`MTR-0060` at load for a run that cannot name a stack, `MTR-0063`/`MTR-0064` as for every scope); a run with no stack, an offline owner, another region, a vanished stack and a reused object all degrade to default and a rate-limited diagnostic, never an exception into the server |

### Step 2: the open key (designed here, built when T02 or F1 is scheduled)

The issue's step 2 and [`primitives.md`](primitives.md) §5.2 describe it in a paragraph; this is the design the build will start from. Nothing below is implemented, and
nothing in step 1 needs it.

13. **The holder becomes a value and the port becomes one.** `VariableHolder` is a sealed type: `Player(uuid)`, `Server`, `Session(id)`, `Stack(StackRef)`, `Entity(uuid)`, `Key(text)`. `VariableStore.read/write/mutate(declaration, holder, …)` takes it, and the
    `(declaration, UUID)` methods stay as default adapters (`Player`, or `Server` for a null id) so a cross-server adapter written against the shipped port (#25) keeps working. `StackVariableStore` folds into it,
    and `SnapshotVariableStore`-style wrappers (the views observer, [`views.md`](views.md) §5.3) decorate the one port once. The store registry maps each scope to its store (memory, a PDC, the server file, a #25 adapter).
14. **`scope: keyed`, with the key an expression on the declaration.** `offer_ready: { scope: keyed, key: "${args.trade}", type: boolean, default: false, ttl: 60s }`. The key is evaluated in the context of every read and write
    of the variable, so the compiler checks its roots at each use (`MTR-0060`) and a reader that cannot supply `args.trade` is a diagnostic at load, offline. The reads are `vars.keyed.<pack>.<name>` and the writes `keyed.<pack>.<name>`
    (or `<pack>:<name>`); the key never appears in the spelling, so one declaration is one family of values and the key stays with the declaration, which is where views bind it ([`views.md`](views.md) §6.2, §6.3).
15. **`ttl:` and the `expire` trigger.** A keyed value stores an expiry (epoch milliseconds, the server clock) beside it; a read past it is absent (lazy, so a reader never sees a stale value even between sweeps), and a sweeper on the
    global scheduler removes expired entries in bounded batches and fires `expire` once per entry (context `variable.id`, `key`, `value`). `expire` has no player, so it is a run with no subject: it waits for the subject-less
    run that §37 owner question 3 leaves open, and until that exists the sweeper only removes. A write refreshes the expiry unless the write says otherwise (`set_var` gains no new key: `ttl:` is the declaration's, per-write expiry is a later
    option).
16. **The durability class is the declaration's, stated.** `durable: false` (memory; gone on restart, no disk growth; the default for a keyed variable, because the trade offer and the cooldown are the use cases) or `durable: true` (the
    keyed file of `storage/`, written like `variables.json`, §20 decision 14). It is a field of the schema, never a hidden property of a scope (primitives.md §5.2). Owner question 4.
17. **The entity holder is `scope: entity`**, the stack's sibling: the holder is the entity the run is about (the `target` of #331, or the struck entity of `entity.hit`), the value lives in that entity's persistent data (a player, a mob,
    an armor stand), and an unloaded or dead entity reads absent. It exists for F1 (bleed stacks on the struck entity): a `ttl:` on it is the same lazy-expiry rule with the expiry stored beside the value. Owner question 5.
18. **`player`, `server` and `session` are restated as named keys, not rewritten.** The four shipped words stay as they are in every file; the doc names them the instances of the one primitive: `player` is the key `${player.uuid}` in the player's
    container, `server` the constant key in `variables.json`, `session` the key "this window" in the GUI session store (views.md V1). A pack that wants the same value under another key declares a `keyed` variable; nothing is deprecated.
19. **Budgets.** A keyed read is one map lookup and one clock compare; the sweeper is bounded per tick and runs only while entries with a `ttl` exist; the key expression is compiled once at load (§15). The store is concurrent-read-safe
    (a concurrent map, writes serialised per key) so a view on another region may read it (views.md §6.2's last row).

### Migration statement

Every existing `variables/` file keeps its meaning and its bytes: the schema is additive (master §5.5), unknown keys are still preserved (§5.6), and `scope: player` and `scope: server` are read exactly as before. Every existing
`vars.local`, `vars.session`, `vars.player.*` and `vars.server.*` read keeps its meaning; the bare `vars.x` is still a local. **One narrowing:** `item` becomes a reserved scope word, so a run-local named `item` (`vars.item`,
`set_var: {var: item}`) is `MTR-0063` where it used to be a local. No shipped pack, fixture or test names one (searched), and the owner confirmed (2026-11-19) that there is no public release to protect; step 2 will reserve `keyed`
and `entity` the same way, and a pack that used them as local names is told by `MTR-0063` at load, not at run time. The `durability` mechanic is untouched (its counters stay `materione:durability-used`/`-max`); folding it onto this scope is a follow-up
the issue names, not part of step 1.

### Deviations from the issue's sketch

| Sketch | As built | Why |
|---|---|---|
| "the GUI can read the stack a click or an `open` was about" | As sketched: `view.open` records the opening run's stack in the view binding and its back snapshots | This is P-A of the backpack design, implemented once here and reused by #304. Stack custody is still a separate primitive. |
| "the store port generalized" in step 1's "stack store behind the existing store port" | `StackVariableStore` is a specialized extension of `VariableStore`; the full holder value is step 2's design (decision 13) | The holder is not a player id; widening every existing method would change every adapter for a holder only step 2 needs a general form of |
| "a run with no stack that reads `vars.item.*` gets the default and a rate-limited diagnostic" | As sketched, plus a load-time `MTR-0060` when the trigger cannot provide a stack at all | Most of those mistakes are visible offline, which is the whole point of D6's checks |

### Not built, on purpose

Step 2 in full (`keyed`, `ttl`, `expire`, the entity holder, the generalized port). Stack custody and folding
`durability` onto the item scope. A stable holder id written to stacks for cross-handle view matching (step 1
observes the exact handle bound by `view.open` and writes no extra bytes). Atomic `mutate` for stacks. An
`item`-scope `variable` list source.

### Verification

`VariableRefTest` and `MapLogicContextVariablesTest` (the spelling, the reserved word, the read), `ItemScopeRootsTest` (`MTR-0060` for a read, a condition and a declared write on a trigger with no item, clean on one with it),
`StackVariablesTest` (the store: round trips, two stacks, the default clears the key, a vanished stack, a reused object, the owner's thread; the run: no stack, the rate limit; the API and the held item), `ItemScopeFlowTest` (two swords, two levels through `set_var`
and the `var` condition, `reset` returns the untouched bytes), `ItemVarsPipelineTest` (the `itemvars` fixture pack from files to the shadow with no diagnostics; the offline `MTR-0060`), `ItemLayerCompilerTest`,
`GuiRulesCompileTest`, `ViewVerbsTest`, `ViewReadsExtractionTest` and `ViewChangesTest` (a view reads, carries, indexes
and rerenders its subject stack), `VariablesTypeTest` and the
editor demo snapshot and code-mode registry goldens (the schema), `PaperItemVarsIT` on Paper 1.21.4 (two swords, two levels; a vanilla copy that carries its state; a drop and a pick-up; tokens that do and do not merge; a restart; a rule document reading the held stack; an item-opened GUI reading, writing and rerendering its subject; the pick-up is `ItemEntity#playerTouch` through the new `dprobe pickup`, because a fake-connection player is not ticked into collecting items) and the QA catalog's `itemvars` suite
(**not run and not blessed**: it needs the local 26.x probe, and blessing runs happen on main).

### Owner questions

1. **The GUI subject stack (P-A).** Shipped with #333 step 1: T01's upgrade button reads and writes the
   sword's level. The backpack's P-B custody holder, pinning and crash protocol remain #304/#334 work.
2. **Per stack, not per item.** The state is the stack's: a stack of 16 shares one value, and a split copies it (decision 7). That is the only definition vanilla's own stacking allows. If a pack wants one value per item, the item must not stack (`max_stack_size: 1`), which
   is content. Should the declaration warn when an `item` variable is read by an item whose stack size can exceed one?
3. **`death` in a rule document.** The `item` root is provided by the trigger, not the host, so the compile-time check cannot tell a rule document's `death` (no stack) from an item's. Per-host roots would fix it and change the compiler's contract for every
   trigger; it is cheap to do later and not needed by the benchmark. Leave it, or do it with step 2?
4. **Step 2: the default durability of a keyed variable.** Memory (decision 16) or file. Memory is safe and cheap, and the wrong default for a "HUD value" ([master §8.3](../architecture.md)); the field exists either way.
5. **Step 2: `scope: entity` or `scope: keyed` with `holder: entity`.** A sixth reserved word is the symmetric choice with `item` and reads best (`vars.entity.rpg.bleed`); a `holder` on `keyed` keeps the word list closed at five. Needed by F1.

---

## 39. Decisions taken in #331 (target addressing: an action on another player or entity)

#331 is the fifth issue of M6.5 ([`agent-benchmark.md`](agent-benchmark.md) §4.2, rank 5; [`primitives.md`](primitives.md) §5.6) and a
primitive under D12 ([`architecture.md`](../architecture.md) §3.1): until it, every action acted on the player the run belongs to, and
`message` carried a *channel* under the name `target`. It adds one optional parameter, `target:`, to the actions that can act on
something else, one value type behind it, and one resolver. Nothing in it is named for a use case: Bloodfang's bleed (T01), `/pay`
(T21) and the other half of `/trade` (T02) are compositions of it. The code is in `api.logic` (`TargetSpec`, `TargetKind`,
`ResolvedTarget`, `TargetResolver`, `Subjects`, `ActionDescription.targetable`), `behavior.run` (`TargetedAction`, `TargetedContext`,
`RunReadExecutor.aimedAt`) and `plugin.logic.target` (`BukkitTargetResolver`, `EntityTarget`). Each decision is reversible by the owner.

**Traceability.**

| Decision or rule | How this section applies it |
|---|---|
| D5 | `targetable` is part of an action's self-description; declaring it adds the shared `target` parameter to the schema, so the editor form, `logic.vocabulary` and the code-mode SDK show it with no code of their own ("Self-description" below) |
| D8 | resolution reads the world only on the thread that owns it; the action is dispatched to each subject's own region through the gateway, one subject at a time ("Threads" below) |
| D6, D1 | `target:` is a parameter in the step's YAML; the pipeline parses it at load like any parameter, and nothing applies it any other way |
| D12, master §3.1 | one value shape across every action (U3); an unresolved target fails the action, it never falls back to the run's player (U5); the table at the end |
| master §5.5, §5.6 | an action with no `target:` behaves as before; a file loads and saves byte for byte ("What changed for existing content" below) |

### The value

`target:` is written in the action's own parameters, beside `chance` and `delay` only in the sense that every targetable action
accepts it. It has five forms, and exactly one:

| Form | Resolves to | Online only | Load check |
|---|---|---|---|
| `self` (the default; also what an absent `target:` means) | the run's player; the action runs exactly as it did before targets, with no resolution step | | |
| `event.target` | the entity the trigger carries: the struck entity of `entity.hit`, the killed one of `entity.kill` | the entity must still be in the world | the rule's trigger must carry a `target` root (`MTR-0057` otherwise) |
| `arg: <name>` | the player a command or GUI argument named (`/pay <to> <amount>` is `arg: to`) | yes | the name must look like an argument |
| `player: "<template>"` | the player whose name or uuid the template renders to (`"${args.from}"`, `"${vars.session.pay.partner}"`) | yes | the template compiles and its `${root}`s are known (`MTR-0060`) |
| `nearby: {radius, type?, limit?}` | up to `limit` entities within `radius` blocks of the run's player, nearest first, never the run's player | | `radius` is 0.5 to 64, `limit` 1 to 16 (default 1), `type` an entity type key |

```yaml
behavior:
  - on: entity.hit                    # a rule document, or an item
    do:
      - potion: {type: wither, duration: 100, target: event.target}    # the bleed, on the struck entity
  - on: gui.click                     # a button whose GUI declares an argument `to` (the command form is #330's)
    do:
      - take_money: 100
      - give_money: {amount: 100, target: {arg: to}}                   # /pay
      - message: {text: "${player.name} paid you 100", target: {arg: to}}
      - sound: {sound: entity.player.levelup, target: {nearby: {radius: 8, type: player, limit: 4}}}
```

`self` and `event.target` are bare words; the other three are one-key mappings. A bare string that is neither word is a load error
(a name is `player:`, never a bare string, so `"self"` is never a player called Self), and the mapping forms reject unknown keys, so a
misspelled `limt` is a diagnostic, not a selector that silently takes one entity. The value is parsed once, at load, by one function
(`TargetSpec.read`), for every action: no action parses a target (U3). The result is a sealed type of five records that the engine
stores on the step (`ActionStep.target`, with the kinds the action admits); `self` is stored as nothing, so an untargeted step has
no resolution to run and the dispatch path pays one null check (plugin.md §9).

**Why these five.** They are the five ways a flow in the benchmark reached something other than its own player: the thing that was
hit (T01), a player named by a command argument (T21, T02), a player named by state (T02's requester), a set around the subject
with no loop step (agent-benchmark.md §5: "a target is a selector"), and the subject itself. A holder (the owner of a key, the
holder of a stack) is #333's, and a selector over *every* player is a list source (#336), not a target. **`event.target` is the
trigger's, not a free entity id**: a flow cannot name an entity by a uuid it stored, because nothing in the model keeps an entity
across ticks (an entity holder is #333).

### Self-description: `targetable`

`ActionDescription` gains `targetable`, the kinds the action accepts, in the order of `TargetKind`:

| Kind | Meaning |
|---|---|
| `player` | an online player |
| `entity` | any entity (a player is one, so `entity` admits players; the vocabulary lists both for an action that takes either) |

Declaring it does two things in the constructor and nothing else: it records the kinds, and it **appends the shared `target`
parameter** (`raw`, `valueKind: target`, advanced, default `self`) to the schema unless the type declared its own. So the form the
studio draws, `authoring.describe`, the scaffold and the SDK generator all see a `target` field on exactly the actions that take
one, from the one description (D5; codemode.md §3.5). `logic.vocabulary` also carries `targetable: ["player"]` on each such action
(absent otherwise), so a consumer that wants "which actions can aim" reads one key. The parameter is `raw`, not a typed union,
deliberately: the generated SDK types no engine vocabulary (issue 355, `core.d.ts`), so an agent's `target` is `JsonValue` and a
wrong form is the compiler's `MTR-0057`, which the dry run reports with the step path, exactly as for any parameter the schema
does not describe more finely. A typed target in the SDK would need the registry to describe the five forms as grammar
(`Grammar.conditions` is the precedent); that is a follow-up, not built here (below).

An action that is **not** targetable and has no parameter called `target` rejects one at load (`MTR-0057`, "this action cannot act
on a target"), so a target is never silently ignored. One action has its own parameter called `target` and is not targetable:
`broadcast` (a world name or a permission node, for its scope). Its meaning is untouched; it is the reason this check looks at the
schema and not at the key alone.

### The path through the engine

1. **Load.** `ActionCompiler` asks the type's description whether it is targetable, reads `target:` with `TargetSpec.read`, checks
   `event.target` against the trigger's context roots (the same set `${...}` is checked against, §2.6), and stores a `StepTarget`
   (the spec and the admitted kinds) on the `ActionStep`. A bad value skips that step with `MTR-0057` and the rule's other steps
   load.
2. **Reach the step.** `FlowContinuations.action` runs the chance roll and the delay as before, then the thread check for the
   action's `threading()`: the run first goes to the thread the action needs *for the run's own player*. That is deliberate: the
   target is resolved from the run's state, so the run must be where its own data is.
3. **Resolve.** `TargetedAction` captures the run's read origin (on the run's own thread, once), calls the platform's
   `TargetResolver`, and gets a `TargetResolution`: the subjects, or none and a reason.
4. **Run the action on each subject, in order.** For each subject the action's `executeResumable` is called with a
   `TargetedContext` (the run's context plus the `ResolvedTarget` facet) and a read executor aimed at the subject. The action reads
   its subject with `Subjects.player(context)` or `Subjects.entity(context)`, which return the run's own player when there is no
   target, so an action's code changes by one call at each place it touches the player, and *nothing else* about it changes.
5. **Outcome.** `OK` when every subject succeeded; otherwise `FAILED` with the first reason (and "k of n targets failed" for a set).

`Subjects` is the **only** place an action reads its subject, and **only a targetable action may call it** for that purpose: an
action that does not declare `targetable` never receives a target, because the compiler never parses one for it. Everything else the
action reads from the context stays the run's: `${player.name}`, placeholders, `vars`. A message "to the other player" says whose
name the sender has. (The first draft overrode `Facets.PLAYER` in the overlay, which was less code and wrong: an expression function
that reads the player, `balance()` in `${balance() / 2}`, would have read the *target's* balance.)

### Threads (D8)

| Step | Thread |
|---|---|
| the chance roll, the delay, the thread check | as for any action: the run's own |
| resolve `event.target`, `arg:`, `player:` | the thread the step is on (reading a facet, an argument, or the online-player list, none of which needs a region); a `player:` template is rendered through the run's read executor like any template |
| resolve `nearby` | the run's player's region (`reads.on(PLAYER_REGION)` on the run's executor): the search box is read from where the player stands |
| the action's own-region work (`PLAYER_REGION`) | **the subject's region**: the read executor handed to the action sends `PLAYER_REGION` to the subject's entity scheduler (`GatewayRunScheduler.schedule(anchor = subject)`), inline when the current thread already owns it |
| the action's `GLOBAL` work (a Vault deposit) | the global thread, as before |
| template and expression reads inside the action | the run's own region, through the origin captured *before* the first subject was touched, so a later subject's thread is never mistaken for the run's |
| continuing after the action | back on the run's own thread, through the interpreter's existing `await` continuation |

So `give_money` to a player in another Folia region deposits on that player's region thread (or the global thread for a Vault
currency), `potion` on an entity applies on the entity's region, and the next step runs on the run's own. Subjects of a set are
handled one after another, never in parallel: the order is the order resolved and a failure of one does not skip the rest. A subject
that leaves between resolution and dispatch (the entity scheduler retires the task) is that subject's failure ("left before the
action ran"), not an exception.

On Paper every ownership check is true and a targeted flow runs inline on the main thread. **What is proven where:** the dispatch
seam (the effect of a targeted action is submitted to the *subject's* entity scheduler and not run inline when another region owns
it, and the resolution of `nearby` is submitted to the run's player's) is proven by a unit test over the real
`GatewayRunScheduler` with a recording gateway and a probe that says "owned here" for one entity only
(`TargetedDispatchTest`). Nothing was run on a native Folia server for this issue: the `foliaProbe` source set is opt-in evidence
and the CI harness is Paper 1.21.4. `Entity#getNearbyEntities` on Folia requires the search box to lie in the region the thread
owns; a box that crosses a region boundary throws there, and the resolver turns that into a refused resolution ("the search area is
not owned by this region") rather than a partial answer. Owner question 3.

### Permission and ownership

**Targeting is not a grant, and it is not a check.** A rule is the pack author's, not the player's: a player who triggers
`entity.hit` is not exercising a command, so there is no node "may this player target that one" to test, and inventing one per action
would be a second permission system beside the author's own `if: {has_permission: ...}` (which composes with every action and is
how a pack gates a verb). The engine adds exactly these rules, none a permission:

1. An action can only act on what it admits: `message` on a zombie is `FAILED` ("the action cannot act on a entity"), `potion` on an
   item entity is `FAILED` ("a potion effect needs a living entity").
2. `player:` and `arg:` resolve **online** players only (offline is a refusal in v1; money to an offline player is out, as the issue
   says), and an unknown name is a refusal, never a partial match: `Bukkit.getPlayerExact`, not a prefix search.
3. `nearby` never includes the run's own player, skips players in spectator mode (an observer should not be healed or spoken to
   by a radius, though a command that names them still reaches them) and skips a mob that has just died: a killed mob stays in
   the search box while it plays its death, and the harness found a potion landing on that corpse instead of on the zombie beside
   it, so the selector requires `isValid()`.
4. A verb keeps its own checks on its subject: `give_money`/`take_money` go through the same currency backend and its own result
   (insufficient funds is `FAILED` on the *target's* balance); `give_item` delivers with the same overflow rules; protection plugins
   see the same Bukkit calls they saw before.

A command that takes a player argument is gated by the command's own permission (§23), which is where `/pay` and `/trade` ask the
question. **Owner question 1** is whether a player should be able to opt out of being the target of other players' rules (an
`materione.target.exempt`-style node).

### Failure (U5)

A target that does not resolve makes the **action** `FAILED`, so `require` runs its `else:` and the trace and the rate-limited
reporter see it. The reasons, as the resolver words them (each is prefixed with `target <spec>:`):

| Reason | When |
|---|---|
| `this event carries no target` | `event.target` and the trigger gave none (also caught at load for a rule whose trigger has no `target` root) |
| `the target is no longer in the world` | the struck or killed entity is gone (a dead mob; a player who quit) |
| `argument '<name>' is not set` | `arg:` and the command or GUI did not pass it |
| `no player named '<name>' is online` | `arg:` or `player:` |
| `the player name is empty` | the template rendered nothing |
| `the action cannot act on a <kind> (<who>)` | the resolved subject is a kind the action does not take |
| `no <type> within <r> blocks` | `nearby` found nothing; the failure of a selector that matches nobody is real (a flow can `require` an ally nearby), and a flow that wants "if any" writes it as a `require` with an `else: []` |
| `the run has no player to search around` | `nearby` on a run with no player |
| `'<type>' is not an entity type on this server` | `nearby.type` |
| `the search area is not owned by this region` | Folia, a box that crosses a region boundary |
| `targets are not available here` | an engine with no platform resolver (a unit test); never in the plugin |

The trace shows the resolved target. `STEP_OK` carries `on player Bob` (or `on zombie 1a2b3c4d, player Alex` for a set) in its
`detail`, and a `STEP_FAILED` after a resolution appends `(on ...)` to its reason, so the Logic tab's trace and `logic.trace`
subscribers read who an action touched without a new event type.

### Which actions, and why

| Action | Kinds | Why |
|---|---|---|
| `message` | player | tell another player; replaces the old channel `target` (below) |
| `actionbar`, `title` | player | the other two ways to tell a player; same value shape as `message` |
| `sound` | player | to a player or, with `scope: world`, to *their* world; `scope: server` with a `target` is a load error (it would play once per subject) |
| `potion` | player, entity | the bleed (T01): any living entity |
| `give_item` | player | a reward for the other player |
| `give_money`, `take_money` | player | `/pay` (T21); online players only |
| `teleport` | player, entity | send the struck entity or a partner somewhere; absolute coordinates, as for the run's player |

**Not targetable, and why.** `run_command` and `broadcast` already address a console or a scope; `set_var` addresses state by
declaration (a holder is #333); `give_exp`/`take_exp`, `take_item`, `give_permission`/`take_permission`, `stop_sound`, `chat` and the
`gui.*` family are candidates and are not in the first set because no benchmark task needed them: adding one is declaring
`.withTargets(...)` and reading `Subjects` at its one touch point, and is a small PR. `view.open` and `view.close` take `target:` in
views.md row V4 on exactly this value (`TargetSpec`, `StepTarget`, `TargetedAction`): V4 declares them targetable and teaches the
GUI opener to read `Subjects`; it adds no parser. **#329's `heal`, `damage` and `velocity` had not landed when this shipped.** The hook
is the same declaration: each declares `.withTargets(PLAYER, ENTITY)` and reads `Subjects.entity(context)`.

### What changed for existing content

`message` used to read `target: player|actionbar|broadcast` as a channel. That is the one collision with the new value (two meanings
of one key in one action), and the channels already have their own actions (`actionbar`, `broadcast`), so U1 says the spelling that
duplicates a capability goes. There is no release to keep compatible with (the owner, 2026-11-19), so there is no shim: a
`message` step that still writes one of the three words is skipped at load with a message that names the replacement
(`MTR-0057`), and its file is untouched (the file stays the source of truth; the step reloads when it is fixed). No fixture or
pack in the repository used it. `message: "text"` and `message: {text: ...}` are unchanged.

### Diagnostics

No code was added ([`plugin.md`](plugin.md) §5). A bad `target:` is `MTR-0057` ("action 'x' rejected: ..."), naming the step path; an
unknown `${root}` inside a `player:` template is `MTR-0060`; a run-time refusal is the action's `FAILED` and the reporter's
rate-limited warning when nothing guards it.

### The universality tests (master §3.1)

| Test | How it passes |
|---|---|
| U1 name | `target` names the capability: who the action acts on. Nothing is `message_other` or `heal_ally`; the channel spelling of `message` was removed so there is one way to say it |
| U2 generality | any subject the run can name: the event's entity, an argument or state value naming a player, a bounded set around the subject; any action that declares it, including an addon's (`ActionDescription.withTargets` is public API) |
| U3 composition | one value shape across seven built-in actions plus `give_money` and `take_money`, proved by `TargetedActionsTest` (each declares the one shared parameter and acts on the target and not on the run's player), `TargetsPipelineTest` and `PaperTargetAddressingIT` (potion on the struck zombie, a selector, a message to a flow argument), with no Java in the content |
| U4 self-description | `targetable` and the `target` parameter in `logic.vocabulary`, `authoring.describe`, the golden registry fixtures and the generated `.d.ts`; no hand-written editor or SDK surface |
| U5 safety | parsed at load; unresolved is `FAILED`; a refusal never falls back to the run's player; resolution never mutates; bounds are in the grammar (radius 64, limit 16) |

### Verification

`TargetSpecTest` (the five forms, their bounds, the rejections and messages, and the description: the declaration adds the one
shared parameter, once, and admits players when it names entities), `TargetedActionTest` (the interpreter with a scripted resolver
and a scheduler that models regions: resolve then act on the subject's region, inline when the run already owns it, a set one
subject after another, the failure cases and their trace text, a `require` seeing an unresolved target, a retired subject, the
context that adds one facet and changes nothing else, and the load checks), `BukkitTargetResolverTest` (each form against
stand-ins: the gone entity, the wrong kind, the empty name, the box and the sphere, the limit, spectators, the Folia refusal),
`TargetedActionsTest` (the actions act on the target and not on the run's player; the declarations), `TargetedDispatchTest`
(the Folia dispatch seam above, over the real `GatewayRunScheduler`), `TargetsPipelineTest` (the `targets` fixture pack from
files to the live shadow with no diagnostics; `event.target` refused where the trigger carries none; the retired channel
spelling skipped with its replacement named), `VocabularySourcesTest` and `VocabularySourcesJsonTest` (`targetable` on the wire),
the editor demo snapshot and the codemode registry and `.d.ts` goldens, the corpus (T01, T02 and T21 no longer fail on TARGET;
`scripts/benchmark/corpus-report.mjs --check` is in sync) and `PaperTargetAddressingIT` on Paper 1.21.4 (a bleed on the struck
zombie by `event.target`, read back from its active effects; a message to a flow argument that goes through for an online player
and fails the flow's `require` for one who is not; a selector that slows the nearest zombie and not the husk that was struck). QA
`targets.potion-on-struck-entity`, `.nearby-selector`, `.message-to-argument-player` and `.unresolved-target-fails-the-require`
(**not run and not blessed**: they need the local 26.x probe, and blessing runs happen on main).

### Owner questions

1. **Opting out of being targeted.** The engine adds no permission (above). Should a player be able to hold a node that exempts
   them from other players' `player:`, `arg:` and `nearby` targeting (not `event.target`, which is a fight they are in)? It is one
   resolver check, and the cost is that a pack's `/pay` would then fail on an exempt recipient.
2. **An empty selector.** `nearby` matching nobody is `FAILED`, as the issue says for any unresolved target; a flow that wants
   "heal everyone nearby, if anyone" must tolerate the failure, which is reported as a rate-limited warning when no `require`
   handles it. Should an *empty set* be `OK` instead (a selector's job is to select, and zero is an answer)?
3. **Folia and a search that crosses a region boundary.** `nearby` fails soft there; reading across regions needs a hop per region
   and a merge, which changes the "one run, in order" semantics. Keep the refusal, or build the merge?
4. **A typed target in the SDK.** The SDK sees `target` as `JsonValue` ("Self-description: targetable" above), so a wrong form is a dry-run diagnostic, not a
   type error. Typing it needs the registry to describe the five forms as grammar (a `Grammar` entry like the condition forms),
   which is the right place under #355 and is a small change in the generator: is it wanted before the editor's form?
5. **`player:` by name.** A name is not a stable identity (a player can rename). It resolves a *name or uuid*, online now, for the
   action about to run, and keeps nothing; storing a player in state is #333's holder question.

### Not built, on purpose

A target form for an offline player (money to an offline account: out, as the issue says); a holder target (the owner of a key, the
holder of a stack: #333); `heal`, `damage` and `velocity` (#329 had not landed); a target on `view.open` (views row V4); a
dedicated widget in the Logic tab's inspector (the field is the generic raw editor, advanced, with the description); `target:` on
`run_command` (`run_command` has `as:`); parallel handling of a set.

---

## 40. Decisions taken in #330 (commands as content)

[#330](https://github.com/xef5000/materione/issues/330) is rank 4 of the benchmark ([`agent-benchmark.md`](agent-benchmark.md) §4.2, the `CMD`
gap; [`primitives.md`](primitives.md) §5.4) and a primitive under D12 ([`architecture.md`](../architecture.md) §3.1): until it, a command with typed
arguments, a permission and a gate could be declared only as the `open:` block of a GUI, so a command that opens nothing (a `/discord` that prints
a link, a `/pay <player> <amount>`) had no home. It adds one content type, `command` (`commands/`), lifts the names, arguments and gate that
`open:` declares into one schema that both read, and defines `open:` as the sugar of the second. Nothing in it is named for a use case: T27
(`/discord`) is a document of three lines, T21 (`/pay`) is the same document with #331's `target:` (and does not pass the benchmark yet, for a gap
of its own that is not this primitive: "Benchmark" below), and the `claim` and `browse` commands that are hard-coded today are the follow-up the
issue names, not built here. The code is in `content.commands` (`CommandSchema`, `CommandType`, `CommandDefinition`), `behavior.flow`
(`FlowCompiler.document`), `plugin.commands` (the command system: `OpenPlan`, `ArgParser`, `ArgType`, `OpenCommandTree`, the registrar and the handler,
`OpenDeclaration` and `DocumentCommands`, with `RuntimeCommands`, `CommandDocuments` and `CommandRuns`; they were in `plugin.gui.open` until #467
moved them, see "Decisions taken in #467" below) and `plugin.logic.trigger` (`CommandTriggers`). Each decision is reversible by the owner.

**Traceability.**

| Decision or rule | How this section applies it |
|---|---|
| D5 | `command` is a registry entry with a complete self-description, so `authoring.describe`, the editor's generic form and the code-mode SDK (`p.commands.create`) show it with no code of their own; `command.run` is a registered trigger with its context schema |
| D8 | the tree's executor hops to the sender's own thread through the gateway (the entity scheduler for a player, the global thread for the console); the flow's world access is the actions' as for any run |
| D6, D1 | a command is a file in `commands/`; the pipeline parses, compiles and swaps it like every type, and nothing registers a command any other way |
| D12, master §3.1 | a content type with names, arguments and a flow (U1 to U4), validated offline and quarantined on a collision (U5); the table at the end |
| master §5.5, §5.6 | one schema source for the block both `open:` and a command read; every existing `open:` block loads, saves and behaves as before ("What changed for existing content" below) |

### The document

```yaml
pay:
  description: "Send coins to another player"
  names: [pay, give-coins]                 # the first is the command's name; the others are aliases
  permission: shop.pay                     # a node the sender needs; none means anyone
  args:                                    # the typed arguments, exactly as open.args declares them
    - {name: to, type: player}
    - {name: amount, type: number}
  requirement: "args.amount > 0"           # a condition the sender must meet
  deny:                                    # steps that run when it is not met
    - message: "<red>Pay a positive amount."
  console: false                           # whether a sender with no player may run it (default: no)
  do:                                      # the one flow the command runs
    - take_money: "${args.amount}"
    - give_money: {amount: "${args.amount}", target: {arg: to}}
  flows: {}                                # named flows `call` runs, as any host's
```

| Key | Meaning | Shared with `open:` |
|---|---|---|
| `names` | the names that run it, each `[a-z0-9_-]+`; required | `open.commands` (the one key that is spelled differently) |
| `args` | the typed arguments: `string`, `int`, `number`, `player`, `boolean`, `choice`, each with `optional`, `default` and `suggest` | `open.args`, byte for byte |
| `permission` | the node the sender needs | `open.permission` |
| `requirement` | a condition (an expression, a predicate or a composite) | `open.requirement` |
| `deny` | the steps that run when the requirement fails | `open.deny` |
| `console` | whether the console, a command block or RCON may run it | none: a GUI cannot be opened without a window |
| `do` | the flow the command runs: the one flow model of §2, required | none: the flow of an `open:` is "open this GUI" |
| `flows` | named flows callable by `do`'s `call` | the same key every host has |
| `description` | a note for the people who edit the document | none |

### Decisions

1. **One schema source, referenced by both.** `CommandSchema` (`content.commands`) reads, checks and writes the block (names, arguments, permission,
   requirement, deny) and draws its self-description once (`CommandSchema.argumentFields()` is the `args` entry's fields in `authoring.describe`).
   `GuiOpen`, the reader of #221, is now a thin spelling of it (`Dialect.OPEN`); the command document is the other (`Dialect.COMMAND`). The dialect
   decides three things and nothing else: the key that holds the names (`commands` in `open:`, `names` in a document), whether a key outside the
   block's own is a problem (the `open:` block is strict, a document's other keys are `do`, `flows` and the rest), and the label a message uses
   (`open.args` or `command.args`). The typed form is the same `OpenSpec` record, so the editor's typed `SetOpen` of #221 is unchanged. At run time
   the one `OpenPlan.compile` compiles both (the argument types and defaults, the requirement as a condition, the deny steps as a rule), and
   `ArgParser`, `ArgType`, `OpenUsage` and `OpenCommandTree` serve both with no copy: the usage line of `/pay` is generated by the code that
   generates `/shop`'s. The `open:` parameter of a GUI stays a raw value in the GUI's self-description (its editor is the GUI studio's typed
   `SetOpen`); a command document's `args`, `permission`, `requirement` and `deny` are typed parameters, described from the same constants.
2. **`open:` is defined as the sugar of a command.** A GUI's `open:` block means the command document `names: <open.commands>`, with the same
   `args`, `permission`, `requirement` and `deny`, whose `do:` is one `gui.open` of that GUI with each parsed argument passed on (`gui.open` is the
   verb in code today; it is `view.open` once [`views.md`](views.md) row V0 lands, and the definition is "the open action of the declaration's own
   kind", so the rename touches this sentence and nothing else). The runtime **does not synthesize that document**: a GUI's declaration and a
   document's are two `OpenDeclaration`s (`Kind.GUI` and `Kind.COMMAND`) that go through the same registrar, the same Brigadier tree and the same
   handler, and differ in what the handler runs once a name is typed (the opener, or `CommandRuns`). That keeps the one open path the single place a
   window is created (§6.1) and costs a GUI open no flow run. The equivalence is proven by content, not asserted: the `commands` fixture pack holds
   `commands:menu` with `open: {commands: [cmenu]}` and `commands:menu2`, a document whose flow is `gui.open: {gui: commands:menu, args: {page:
   "${args.page}"}}`, and `CommandsPipelineTest` shows both compile to the same names, argument shape and gate. **What is not the same, on purpose:**
   a GUI opened by name is still gated by the opener (permission, arguments, requirement, in the order of §23 decision 5); a document's gate and `args`
   are decision 3 and 4 below. (#467 made a GUI's `gui.args` typed as a document's `args.*` are: "Decisions taken in #467", row 3.)
3. **The gate of a command is permission, console, arguments, requirement.** `CommandRuns` runs it on the sender's thread, in this order, and each
   refusal is one logged outcome (`MATERIONE-COMMAND alias=<a> command=<id> outcome=...`):
   `denied` (the sender lacks `permission:`; the console holds every node), `console` (a sender with no player and no `console: true`),
   `usage` (an argument does not read as its type, refused with the usage line generated from the declaration), `refused` (the requirement is not
   met, or did not load, which fails closed with `MTR-0068` at pack compile), then `ran`. The bypass node `materione.command.bypass.<pack>.<id>`
   skips the requirement and nothing else, as `materione.gui.bypass.<pack>.<id>` does for a GUI (§23 decision 8). A failed requirement runs the
   `deny:` steps as the sender's run; with none, the sender is told "the requirement for /x is not met". (A GUI sends both, because the opener
   always answers; a command lets the author's own steps be the whole answer.) The first name is `command.name`, whatever was typed is
   `command.label`.
4. **The trigger is `command.run`, and `args.*` is typed.** The flow is one rule of a registered trigger, so it compiles with the flow compiler an
   item's rules go through and `${...}` paths are checked against the trigger's schema (an unknown root is `MTR-0060`). Its context is the sender's
   tree as any player-subject event has it (`player.*`, `location.*`, `world.*`, `server.*`, `vars`), `args.*`, `command.name` and `command.label`.
   `args.*` carries the **typed** values: an `int` as an integer, a `number` as a double, a `boolean` as a boolean, and everything else as text. A
   GUI's `gui.args` were text when this shipped (#467 typed them, row 3 below), and the consequence was real: with text, `${args.amount} > 0`
   would compare as strings. An optional argument left out with no default is absent (`${args.x}` reads as nothing); one with a default carries it.
   `args` is already the name of a called flow's own arguments (§2.4), and one engine rule changed so both read: a called flow's arguments shadow
   the run's `args` only when the call passed some, so a flow a command `call`s with no `with:` reads the command's `args.*`, and one called with
   `with:` reads what it was given (as before). A `target: {arg: to}` reads `args.to`, which is why #331's `arg:` works for `/pay`.
5. **The console is refused unless `console: true`.** A sender with no player (the console, a command block, RCON) gets "only a player can run /x" and
   the outcome `console`. With `console: true` the run has no player: `${player.name}` is the sender's name, the player-only roots are absent, and
   an action that needs a player fails with its own reason, so a console command is written from the actions that do not need one (`run_command`,
   `broadcast`, `set_var` on a server variable). The `console` outcome is checked before the arguments, so `/pay` at the console is told it has no
   console form, not what its usage is.
6. **Registration is the registrar's, and a collision is a diagnostic.** The open-command registrar (§23 decisions 11 to 16) claims names from every
   declaration: the GUIs' `open:` aliases first, in the view's order, then the command documents in the order the pipeline met them. The earlier claim
   keeps the name. A name already claimed (by another document, in this pack or another, or by a GUI), or already a command on the server (vanilla,
   another plugin), is skipped with `MTR-0067`, which names both owners and says "the alias is skipped"; the rest of the document's names still run
   it. A document all of whose names are refused claims nothing, which is its quarantine: it stays on disk, is reported on every reload that
   sees the conflict, and shadows nobody. **No GUI alias ever loses to a document**, which is what keeps every existing `open:` block behaving as
   it did the day the document type was added. A name that is not `[a-z0-9_-]+` is reported when the pack compiles (`MTR-0021`, a warning on the
   document) and never reaches the tree; the valid names of the same document do.
7. **Reload, resync and unregistration are the open commands'.** At the server's COMMANDS event (start, and a server reload) the registrar registers
   from the **built** registries, so documents are in the tree when the first apply publishes them and unchanged content at boot causes no data
   reload (§23 decision 15). A content swap updates the declared set at once: a removed document, or a removed name, is hidden through its
   `requires` and needs no reload (Brigadier cannot remove a child); a document that comes back reuses its node; a new name, or one whose argument
   names or types changed, asks for one rate-limited `reloadData` that fires the COMMANDS event again, and a name that reload still did not
   put in the tree is `MTR-0069` ("applies at the next restart"). This is unregistration: it is a hide, as it is for an `open:` alias, and the tree
   keeps the dead literal until a restart. Defaults, optionality, `suggest:`, permission, requirement and the flow are live without a reload: the
   executor reads the plan at run time, so an edit to `do:` takes effect on the next run.
8. **A command that does not load claims no name.** The flow compiles at shadow build with the problems the compiler gives any host, each at its
   path in the document (`do.1`, `flows.thank.0`, `requirement`, `deny.0`, never a `behavior.<n>` that is not in the file). A step that fails is
   skipped (`MTR-0057`) and its siblings load; a document whose steps all fail, with no `do:`, or with no usable name, is not live and says so
   (`MTR-0021`), so a half-written document cannot register a name that does nothing. A document whose `do:` is not a list, or whose `flows:` is not a
   mapping, parses to nothing and is quarantined whole (`MTR-0008`), and the healthy document beside it in the same file is not.
9. **Threads (D8).** Brigadier calls the executor on the thread of the source; the handler then hops with the gateway
   (`executeEntity` for a player, `executeGlobal` for any other sender) before it reads anything, so the gate and the context (the player's health, the
   world's time) are read on the thread that owns them, and a refusal costs one hop. The flow starts there and runs as any rule's does, hopping per
   action through the thread gateway. Nothing here touches a world or an entity on its own. **Proven on Paper only** (the harness and the unit tests);
   nothing ran on a native Folia server for this issue, as for §39.
10. **Authoring.** `commands/` is a pack directory (`PackLayout`, both scaffold tables, which a test pins byte for byte, and the authoring
    transaction's logic stage name it). The scaffold is a complete, running command. `logic.get` and `logic.edit` accept a command document: its
    flow is shown as the one rule of `command.run`, at the path `do`, so step edits address `do.0`, `do.1.then.0` and `flows.<id>.0` (the same
    `InsertStep`, `PatchStep`, `MoveStep`, `RemoveStep`, `AddFlow`, `RemoveFlow` and `RenameFlow` as for a host, `RenameFlow` also rewriting the
    `call`s in `do`), while the rule edits (`AddRule`, `RemoveRules`, `ReorderRules`, `PatchRule`) are refused with a message that points at the
    steps and at the fields: a command has one flow and no rules. The names, arguments and gate are fields (`authoring.setFields`), edited by the
    studio's generic form from the self-description. The dry run of a transaction compiles a command's `do`, `requirement` and `deny` with the same
    compiler, so a bad predicate or an unknown `${root}` is found before anything is written. **The graph Logic tab does not open a command
    document**, exactly as it does not open a rule document (§37 decision 12, issue #367, not fixed here): the generic form and the Script tab are the
    ways in, and a command is at least as reachable as a rule. `args.*` autocompletes in the inspector from the trigger's context like any path.

### What changed for existing content

Nothing migrates and no file is rewritten. Every `open:` block loads, saves and behaves as before: `GuiOpen` returns the same spec and the same problem
messages (a test pins them), the registrar claims GUI aliases first and in the same order, the handler's GUI path is the code it was, and the GUI
fixtures are byte-identical. Four things are visible, none of them a change of meaning:

- **`MTR-0066`, `MTR-0067`, `MTR-0068` and `MTR-0069` apply to commands as well as to `open:`.** They are the same conditions in the same code, so they
  keep their numbers; their titles and [`plugin.md`](plugin.md) §5 rows say "open or command". No code was added: a second set of four codes for the
  same four problems would be the second copy this section exists to avoid, and the block `MTR-0106` to `MTR-0117` is taken by the HUD and frame-cycling
  designs.
- **A flow called with no `with:` now sees the run's `args`** (decision 4). Before, `${args.x}` in such a flow read nothing, because the call
  frame's empty map shadowed the root; no fixture or pack read `args` that way.
- **`OpenCommandHandler.bindings()` returns a binding (the kind and the id), not only the id.** It is an internal read.
- **`BehaviorService.baseRoot` and `logicContext` are public**, because the command runner is in another package; they are unchanged.

### The universality tests (master §3.1)

| Test | How it passes |
|---|---|
| U1 name | `command` names what the document is, `names` what runs it, `args` what it takes, `do` what it does. Nothing is `kit`, `warp` or `pay`: `/pay`, `/discord` and `/tune` are three documents of the fixture pack |
| U2 generality | any names, any typed arguments (the six types), any flow of the one model, any permission and requirement; a name is anything `[a-z0-9_-]+` that no one holds |
| U3 composition | its flow is the one flow model: every action and condition composes with it, and `target:` (#331) addresses another player by an argument (`/pay`); proved by the `commands` pack (`CommandsPipelineTest`, `CommandRunsTest`, `PaperCommandsIT`) with no Java in the content; T27 and the command of T21 are YAML only |
| U4 self-description | a content type with typed parameters, one trigger with its context and a `firedBy` host, in `authoring.describe`, the golden registry fixtures and the generated `.d.ts` (`p.commands.create`); no hand-written editor or SDK surface |
| U5 safety | parsed at load; the gate refuses before the flow runs; a collision skips the name and quarantines a document with no name left; an unloadable flow claims nothing; the executor never throws into the server (a failed run is the action's `FAILED` and the reporter's warning) |

### Verification

`CommandTypeTest` (the self-description, the shared schema, the golden document's parse and its byte-identical round trip, the fault fixtures, and
that `GuiOpen` and the command dialect read the same block to the same spec), `ArgParserTest` (the typed values), `FlowRunArgsTest` (a flow called
with and without arguments), `CommandsPipelineTest` (the `commands` fixture pack from files to the live shadow with no diagnostics; a command with a
broken step; one whose requirement does not load; one with no usable name; `open:` and the written-out document compiling to one shape),
`CommandRunsTest` (the gate in order, the console, typed `args.*` reaching the flow, the deny steps, the bypass, a flow that `call`s a flow),
`CommandDocumentRegistrarTest` (the tree, collisions and their order, a swap that adds, hides and restores a document, a changed argument shape, the
suggestions), `LogicAuthoringApiTest` (a command document through `get` and `edit`), `ScaffoldSchemaConsistencyTest` and `ScaffoldDryRunTest`, the
editor demo snapshot and the code-mode registry and `.d.ts` goldens, the corpus (T27 passes, T21 does not: "Benchmark" below; `scripts/benchmark/corpus-report.mjs --check` is
in sync), the replay of T27's staged operations through the real server's dry run (every stage but the platform and GUI ones ran, no diagnostic), and
`PaperCommandsIT` on Paper 1.21.4 (typed arguments into `args.*`, a usage refusal, a permission refusal, the requirement and its deny
steps, an alias, the console form and the console refusal, the tree's completions through `dprobe tab`, and `open:` beside the document that opens
the same GUI). QA `commanddocs.discord-prints-a-link`, `.pay-sends-to-a-player`, `.pay-refuses-a-bad-amount`, `.permission-denied` and
`.suggestions-offer-players` (**not run and not blessed**: they need the local 26.x probe, and blessing runs happen on main).

### Benchmark

T27 (`/discord`) passes: the SDK program type-checks, stages `commands/discord.yml`, and the real server's dry run accepts it with no diagnostic
(`fixtures/golden/codemode/replay/t27-discord-command.json`). **T21 (`/pay <player> <amount>`) does not pass, and the reason is not this
primitive.** Everything of `/pay` that is a command passes (the typed `player` and `number` arguments reach `args.to` and `args.amount`, the
requirement refuses a non-positive amount, `target: {arg: to}` addresses the other player, and the harness runs it), but `give_money`,
`take_money` and `has_money` read `amount` as a literal number at load, so the amount that comes from an argument is a type error in the SDK and
would be a load error in a file. The benchmark page's first draft said the line passed; the machine-checked corpus says it does not. It is
recorded as its own gap, **QTY** (a quantity parameter that takes an expression, as `give_exp` and `give_item` already do through `Quantity`), at
rank 12 of [`agent-benchmark.md`](agent-benchmark.md) §4.2, small and with no issue yet. T02 loses CMD from its gaps and keeps STATE, VIEW and
CUSTODY.

### Owner questions

None open. The six this section asked were decided by the owner on 2026-11-21 and built by #467; each is answered, with its rationale, in
"Decisions taken in #467" at the end of this section (a question's number there is its number here).

### Not built, on purpose

`claim` and `browse` as built-in content (the follow-up the issue names); the graph Logic tab on a command document (#367); a per-argument
permission (§23 decision 19); a Bedrock or prompt surface for
arguments (§23 decision 20); a command with a different flow per name, or a subcommand tree (a command is one name, its aliases, and one argument
list: a `choice` argument and a `choose` step compose the same thing); a `custom` trigger or a command-run trigger for rule documents (primitives.md
§5.5: the flow of a command is the command's, not a rule's `on:`).

### Decisions taken in #467

[#467](https://github.com/xef5000/materione/issues/467) answers the six owner questions above. The owner decided them on 2026-11-21 by the project's
principles: **allow as much as possible and restrict only when needed; offer X *and* Y behind a switch rather than choosing; spend code to avoid
annoyance, above all for a first-time user.** Nothing here is a new primitive: each row widens the one schema (`CommandSchema`), the one tree
(`OpenCommandTree`) or the one gate (`CommandRuns`) that `open:` and a `command` document already share, so a key added to one is in the other.
No public release exists, so nothing migrates (the lossless round trip still binds: every existing GUI and command fixture is byte-identical).
Each decision is reversible by the owner.

| # | Question | Decision | Why |
|---|---|---|---|
| 1 | show a command to players who lack its permission? | **both, behind `visible:`**. `permitted` (the default) puts the permission in the Brigadier `requires`, so a player without it never sees the command in completion and typing it is Brigadier's "unknown command"; `always` keeps the earlier behaviour (shown, refused with `no permission (node)`). The same key on `open:` | the default matches Bukkit and avoids "why can I see a command I cannot run"; the switch keeps the visible, explained refusal for a server that wants it |
| 2 | `min` / `max` on `int` / `number`? | **yes.** The Brigadier integer and double types carry the bounds, so the client refuses an out-of-range value while it is typed; the server refuses it at parse with the usage line, which shows the range. `requirement:` stays for anything relational | the earliest, cheapest refusal is the best one, and the bound is part of the declaration, not a condition to write by hand |
| 3 | type `gui.args` too? | **yes.** A GUI's `gui.args.*` are typed exactly as a command's `args.*` (an `int` an integer, a `number` a double, a `boolean` a boolean, the rest text). The session still keeps the text (a snapshot and `gui.back` replay it) | one schema should give one result; there is no public release to protect |
| 4 | rule options on a command? | **yes.** `cooldown`, `charges`, `per` and `on_cooldown`, with the rule's meaning, read by the one rule compiler | a `/daily` is a command plus a cooldown, and the cooldown message already is the `on_cooldown` flow |
| 5 | offline players and selectors for `player` args? | **all of them, as options.** `offline: true` and `selector: true`; the default stays an online player by name with completion | a server that wants `/seen <player>` or `/hug @a` should not need a second type or a hand-written parser |
| 6 | move the machinery out of `plugin.gui.open`? | **yes, now,** to `plugin.commands` (and `CommandSchema` to `content.commands`): a pure move, no behaviour change | it is the command system, not a GUI detail |

**Row 6, the move.** `ArgParser`, `ArgType`, `OpenArgSpec`, `OpenPlan`, `OpenUsage`, `OpenCommandTree`, `OpenCommandHandler`, `OpenCommandRegistrar`,
`OpenDeclaration`, `DocumentCommands` and `PlayerNames` are in `plugin.commands`; what opens a window (`GuiOpener`, `GuiLifecycle`, `OpenGate`,
`OpenSpecGate`, `OpenRequest`, `OpenResult`) stays in `plugin.gui.open`. Class names are kept, so the move is a diff of package lines. The size guard
(`LogicCodeOrganisationTest`) and the listeners-are-adapters rule (`MaterioneArchRules.LISTENERS_ARE_ADAPTERS`) now cover the new packages, so the code that
left `plugin.gui` did not leave their reach.

**Row 1, `visible`.** `visible: permitted | always` (default `permitted`) on a command document and on `open:`. With `permitted` the root literal's
`requires` is `declared && (permission is none || sender.hasPermission(permission))`, read from the live plan at each check, so an edit to `permission:` or
`visible:` takes effect without a reload. The permission is still checked in the gate (it is the only check for a GUI opened through the API, and for a
command a `requires` does not cover, such as one reached through `/execute`), so `always` and the API path say `no permission (node)` as before. What a
player's client shows is what the server last sent: Brigadier syncs a player's tree when they join and when the server re-sends it (`updateCommands`),
so a permission granted mid-session reaches the completion at the next resync, while running the command works at once because `requires` is asked
again at run time. The console holds every node, so it always sees the command. A value other than the two reads as `permitted` with a problem.

**Row 2, `min` and `max`.** On `args` entries of type `int` and `number`; any other type with them is a warning and the bounds are ignored. An `int` rounds its
bounds inward (`min: 1.5` is 2, `max: 9.9` is 9), so an author is never told a whole number is out of a range it is inside. A range no value fits
(`min` above `max`) drops both bounds with a warning (`MTR-0066`, the code the other argument problems use), so the argument is
unbounded rather than unusable. A `default` outside the bounds is dropped with the same warning. The usage line shows the range:
`<amount:number(1..100)>`, `[times:int(>=1)=1]`. The tree's argument shape includes the bounds, so a changed bound asks for the one rate-limited reload
that a changed argument type does.

**Row 3, typed `gui.args`.** `GuiSession.typedArgs()` is the arguments through `ArgParser.typed` with the GUI's own `open:` block, set by the opener when it
creates the session; `GuiContexts.build` puts it under `gui.args`, and the open gate hands the requirement the same typed values. A GUI with no `open:` block
has none typed and keeps its arguments as text. `gui.args.*` is now `raw` in the trigger's self-description. A `number` argument `2` reads as the
double 2, so a message shows `2` and `${gui.args.ratio} * 2` is 4. The text a session keeps (`GuiSession.args()`, its snapshot, `gui.back`) is unchanged.

**Row 4, rule options on a command.** The four keys are described once, in `api.logic.RuleThrottle`, which `RuleParser.RULE_OPTIONS` (the rule's) and
`CommandType` (the document's) both list, so their descriptions cannot differ; the registry goldens show the document's four entries equal to the rule's.
`FlowCompiler.document` hands them to the one `RuleCompiler`, so a bad `per:` or a broken `on_cooldown` step is reported with the problems of any rule,
at `per` or `on_cooldown.0` in the document. The meaning is the rule's (§16): `cooldown` is milliseconds, `charges` the uses in all, `per` is `player`
(the sender, the default) or `global`, `on_cooldown` the steps that run when the cooldown held the command back, reading `${cooldown.remaining}`.
**One typed command is one use**, however many players its selector names: `RuleEngine.fireOnce` spends the cooldown and a charge once, then runs the flow for each
run. Two things differ from a rule, because a command has a sender who is waiting for an answer: a command held back by its cooldown with no `on_cooldown`
tells the sender `/x is on cooldown: N seconds left` (a rule stays silent), and a command whose charges are spent says `/x has no uses left`; each is one logged
outcome (`cooldown`, `spent`) beside `ran`. An author who wants silence writes `on_cooldown: []`. The gate order is permission, console, arguments,
requirement, then the throttle, so a refused command costs no use. A GUI's `open:` has no throttle keys (a GUI is not a flow).

**Row 5, offline players and selectors.**

- **`offline: true`** accepts a player who is online, or who has joined this server before and is in the user cache (`Bukkit.getOfflinePlayerIfCached`; it
  never asks the profile service, so an unknown name is refused, not looked up on the main thread). The value is the name in its own spelling. The run's
  subject is still the sender; the offline name is text in `args.<name>`, so an action that needs the player online (`target: {arg: who}` on `message`, `give_item`
  or a money verb) fails soft with the resolver's own reason `no player named 'x' is online` (§39 rule 2), and a flow that only stores or compares the name
  works. Completion offers the online players and then those who joined before, from a player-data listing that is read at most twice a minute.
- **`selector: true`** makes the tree's type Paper's players selector, so the client parses and completes `@p`, `@a[distance=..5]` and the online names itself.
  Paper checks the vanilla selector permission (`minecraft.command.selector`) when it parses, so a sender without it is refused by the server before the
  command runs. A plain name still works (online, or offline too when both switches are set; the server then completes the names, because the client's own
  completion cannot know the offline ones). Selector and plain value both reach the command as text: the selector is resolved with the sender's own position
  (`@p` is nearest to them, `@s` is them) into the matched players' names.
- **One run per matched player.** The flow of a command document runs once for each player a selector matched, in the order the server lists them, with
  `args.<name>` the player's name in that run; the requirement is checked for each (a refused player does not stop the others) and the throttle is spent once.
  The runs are capped at **16 in all**, as `nearby` caps `limit` (§39): the rest are dropped, the command runs for the first 16, and `MTR-0125` (a warning, once per
  command per ten seconds) says how many it matched. With two selector arguments the runs are their combinations, up to the same 16. A selector that matches nobody
  runs nothing and tells the sender `no player matches <selector>`. A GUI opens once: a selector in an `open:` argument opens it for the first player it matches.
- The key is allowed only on a `player` argument; on another type it is a problem and is ignored.

**Diagnostics.** One code: `MTR-0125` (a selector matched more players than the cap). Every other problem of the six rows uses an existing code:
`MTR-0021` (a shape problem of a command document, a warning on it) or `MTR-0008` (the same problem in a GUI's `open:` block) for a bad `visible`, a bound on the wrong
type or a non-number bound, and `MTR-0066` for a range or a default that cannot hold; the throttle keys report as any rule's. The block `MTR-0106` to `MTR-0117` stays reserved, `MTR-0118` to `MTR-0124` are taken.

**Editor and SDK.** Nothing hand-written: the document's self-description grew `visible`, `cooldown`, `charges`, `per` and `on_cooldown` and the `args` entry grew
`min`, `max`, `offline` and `selector`, so the studio's form, `authoring.describe`, the registry goldens and the generated `.d.ts` (`p.commands.create`) show them.
The typed `SetOpen` of a GUI carries the same keys over the wire (`visible` on the block, `min`, `max`, `offline`, `selector` on an argument; all optional, so an older
client's edit still reads).

**Verification.** `CommandTypeTest` (the self-description, the shared throttle entries, the keys read, written and checked), `ArgParserTest` and `OpenPlanTest` (bounds,
the usage range, offline, the default, visibility), `OpenCommandTreeTest` and `CommandDocumentRegistrarTest` (the bounded node refuses while parsed; `requires` hides a
command; the shape carries the bounds), `OpenSpecGateTest` (typed `gui.args` in the session, in a rule and in a numeric requirement), `CommandRunsTest` (cooldown, `on_cooldown`,
`per`, charges, one use for several runs, the requirement per run), `RuleEngineTest` (`fireOnce`), `ArgInvocationsTest` (the runs and the cap), and `PaperCommandsIT`
on Paper 1.21.4 (a player without the permission is not shown the command and one with `visible: always` is; an out-of-range number never runs; an offline player
is accepted and completed; a selector is refused to a sender without the vanilla permission and, for an operator, runs the flow once for each player it matches;
a cooldown holds the second run). QA `commanddocs.hidden-from-completion`, `.out-of-range-refused` and `.selector-runs-per-player` (**not run and not blessed**: they need
the local 26.x probe, and blessing runs happen on main).

**Not built, on purpose.** Folia (nothing here ran on a native Folia server, as for the rest of §40); a restart-proof cooldown (a command's is in memory like a rule's,
and the `cooldown_gate` recipe of §30 is the persistent one); money to an offline player (a gap of its own, §39: a verb that needs the player online fails soft);
the 16-run cap as a setting (it is the same bound `nearby` has, and a pack that wants more runs writes a loop it can see).

---

## 41. Decisions taken in #329 (entity and world verbs)

#329 is the third issue of M6.5 ([`agent-benchmark.md`](agent-benchmark.md) §4.2, rank 3; [`primitives.md`](primitives.md) §4.4, §5.7) and a
primitive set under D12 ([`architecture.md`](../architecture.md) §3.1). Until it, the verb set stopped at players, items and messages: nothing
healed or hurt an entity, pushed one, drew a particle, shot a projectile, changed a block, dropped an item into the world or edited the item in a
hand. It adds **nine actions**, each one registry entry with a typed schema and a self-description, each universal (any entity the run can name, any
block the platform can touch) and none named for a use case. A dash, a lifesteal dagger, an excavator and a fire wand are *content* over them
(benchmark tasks T14, T12, T13, T20; the follow-ups F2 and F5); so will be the combat and farming entries of #13 (below). The code is in
`plugin.logic.action` (nine classes, one each), `plugin.logic.world` (the platform seams and the caps), `plugin.logic.support` (`Triple`, `Quantities`,
`Stages`), `api.logic` (`RunMeter`, `VariableBinding#stack`, `ParamReader#templates`) and the two bridges that learn the verbs exist
(`plugin.runtime.behavior.WorldEventListener`, `ItemBehaviorListener`). The fixtures are `fixtures/packs/worldverbs/`. Each decision is reversible by
the owner.

**Traceability.**

| Decision or rule | How this section applies it |
|---|---|
| D5 | each verb is a registry entry whose `describe()` is its schema, so the editor form, `logic.vocabulary`, `authoring.describe` and the code-mode SDK show it with no hand-written code; every numeric parameter an author would compute is marked `acceptingExpression` (below) |
| D6, D1 | a verb is a step in the YAML of a rule, an item or a command; the pipeline parses it at load like any step and nothing applies it another way |
| D8 | every world mutation runs on the thread that owns its location, through the gateway; the entity verbs run on the subject's region by the machinery of §39 (below) |
| D12, master §3.1 | the universality tests are at the end; the verbs are nouns of the world (`heal`, `velocity`), never spells (`lifesteal`, `dash`) |
| D13 | nothing runs code and nothing is new on the server: a program in the editor stages these steps like any others (codemode.md §3.5) |
| #331, §39 | the five entity verbs are `targetable`; `at: target` composes with it; an unresolved target fails the verb and never falls back to the run's player |
| #333, §38 | `stack.edit` acts on the run's stack, which is the one handle §38 decision 4 defined; no second notion of "the run's item" exists |
| master §5.5, §5.6 | nothing existing changes meaning except one defect the verbs exposed (item `break`'s `location`, "What changed for existing content") |
| plugin.md §9 | no verb costs anything until it runs; a trigger nobody answers still costs a set lookup; each cap below is checked before the work, not after |

### The nine actions

Every parameter below is read by the engine's one parser (`ParamReader`, `Quantity`, `Triple`, `LocationSpec`), so a literal is range-checked at
load (`MTR-0057`, the step is skipped) and an expression is checked each time it renders; a template that does not render to a number in range fails the
step, it never guesses (§20 decision 8). "Expr" is the marker of the registry's self-description, so the SDK types it `number | Expr`. This is the mistake
#464 names for the money actions (a quantity read as a literal); no new verb makes it, and the money actions are #464's to fix, not this issue's.

| Action | Parameters (default) | Targetable | Threading | Acts on |
|---|---|---|---|---|
| `heal` | `amount` (required, Expr, 0 to 1024) | player, entity | `PLAYER_REGION` (the subject's) | a living entity |
| `damage` | `amount` (required, Expr, 0 to 10000), `cause` (a vanilla damage type) | player, entity | `PLAYER_REGION` | a damageable entity |
| `velocity` | `forward`, `up`, `strafe` **or** `x`, `y`, `z` (each Expr, -10 to 10 blocks per tick); `mode: set` or `add` (`set`); `follow_pitch` (`false`) | player, entity | `PLAYER_REGION` | any entity |
| `particle` | `particle` (required), `count` (1, Expr, 1 to 500), `offset` (a number, `[x,y,z]` or `{x,y,z}`, 0 to 16), `speed` (0, Expr), `at` (`target`), `color` (`#rrggbb`), `size` (1, Expr, 0.1 to 4) | player, entity | `PLAYER_REGION`, then the location's region | a location |
| `launch` | `projectile` (required), `speed` (1.5, Expr, 0.05 to 10), `spread` (0, Expr, 0 to 45 degrees), `count` (1, Expr, 1 to 16) | player, entity | `PLAYER_REGION` | a living shooter |
| `break_blocks` | `shape` (`0`, radius per axis 0 to 4), `relative_to: world` or `facing` (`world`), `at` (`event`), `as_player` (`true`), `drops` (`true`) | none | `PLAYER_REGION`, per block the block's region | the blocks of a box |
| `set_block` | `block` (required: a vanilla block or a Materione block id, Expr), `at` (`event`), `as_player` (`true`), `replace: any` or `air` (`any`) | none | `PLAYER_REGION`, then the block's region | one block |
| `drop_item` | `item` (required, as `give_item`), `amount` (1, Expr), `at` (`event`) | none | `PLAYER_REGION`, then the location's region | a location |
| `stack.edit` | `name`, `lore` (a list), `model`, `damage` (Expr); at least one | none | `PLAYER_REGION` (the stack owner's) | the run's stack |

The first parameter is the shorthand (`heal: 4`, `particle: cloud`, `launch: snowball`, `set_block: stone`, `drop_item: ruby`). The names `at`, `shape`, `as_player`
and `drops` are the vocabulary the issue's sketch and the benchmark corpus used; the SDK spells them `asPlayer` and `followPitch`.

```yaml
behavior:
  - on: break                              # T13: the excavator; the broken block is left to vanilla
    do:
      - break_blocks: {shape: [1, 1, 1]}   # 27 blocks around the broken one, as the player, each asking protection
  - on: click:right                        # T14: the dash
    cooldown: 5000
    do:
      - velocity: {forward: 1.6, up: 0.3}
      - particle: {particle: cloud, count: 20}
  - on: entity.hit                         # T12: heal for a fifth of the damage dealt
    do:
      - heal: {amount: "${damage.final * 0.2}"}
      - heal: {amount: 4, target: event.target}        # or heal the struck entity, by section 39
      - stack.edit: {lore: ["<gray>Last hit: ${damage.final}"]}   # F2: the stack this rule hangs on
```

### The location value

`at` is one value shape for every verb that acts at a place, read once by `LocationSpec.read`. It is the issue's "small typed location value":

| Form | Resolves to | Load check |
|---|---|---|
| `event` | where the trigger says it happened, `location.*` of the run: the broken block of a `break`, the struck entity of `entity.hit`, the clicked block of a block event | the trigger must carry a `location` root (`MTR-0060` otherwise, as `item` does for §38) |
| `player` | the run's own player | |
| `target` | the entity the action acts on: its `target:`, else the run's player | only on an action with a target (`particle`); `break_blocks`, `set_block` and `drop_item` say `player` |
| `{of: <word>, x, y, z}` | the anchor `of` (default: the verb's own default) shifted by a world-axis offset in blocks, each axis an Expr within 64 | unknown keys are `MTR-0057` |

A location whose coordinates are whole numbers (a block's) means the block's centre, so a particle or a drop appears *in* the block; a player's or an
entity's location is exact. The default is per verb because the common case differs: a particle appears where the action acts (`target`), a block or a drop
where the trigger says (`event`). Reading a location is a read of the run's own tree for `event` and of the entity for the other two, on the thread that
owns it (`reads.on(PLAYER_REGION)`, which for a targeted step is the subject's region); an offset is a template rendered before the world is touched.

### Threads (D8)

| Step | Thread |
|---|---|
| parsing, the caps' arithmetic, rendering a template | the run's own, as for any action |
| `heal`, `damage`, `velocity`, `launch`, `stack.edit` | the subject's region (`PLAYER_REGION` through the read executor of §39: inline when the thread owns it, else the entity scheduler, completing back on the run's own thread) |
| `particle`, `drop_item` | the **location's** region: `Regions.at(location, task)` is inline when this thread owns the location and a hop through `SchedulingGateway.executeSync(plugin, location, ...)` otherwise, completing the step there |
| `set_block` as the player | the thread that owns both the player and the block; a block in another region is **refused** (`MTR-0119`), because the placement event must run beside the player |
| `set_block` and `break_blocks` with `as_player: false` | each block on its own region's thread, one after another (the step completes when the last has) |
| `break_blocks` as the player | one pass on the player's thread over the blocks it owns; a block it does not own is skipped and counted (`MTR-0119`), never touched from the wrong thread |

On Paper every location is owned and every hop is an inline call. **What is proven where:** the seam (`Regions`) and the verbs' use of it are proven by unit
tests with a scripted ownership probe (a foreign block is hopped to when plain and refused when as the player); a real Folia server was not run for this
issue (the `foliaProbe` source set is opt-in evidence and CI is Paper 1.21.4), so the cross-region *dispatch* of the gateway itself is the code of
`SchedulingGatewayImpl.executeSync` that every other region-aware path already uses. The Folia boundary is decision 3.

### Platform seams

The verbs reach the server through four small ports in `plugin.logic.world`, so the decisions are unit-tested and only the platform call is not:

- **`BlockOps`** has two voices. *As a player*, a break is `Player#breakBlock` (the `BlockBreakEvent` a mined block fires, the player's tool, enchantments, durability
  and game mode, the block's natural drops), a break without drops is the same event with `setDropItems(false)` and the block then removed, and a placement is a
  `BlockPlaceEvent` asked **before** the block is changed (so nothing has to be reverted when a protection plugin says no). *Plain* acts with no actor and asks
  nobody. A Materione custom block cancels its break event to run its own teardown, so a cancelled event over an empty block is a success, not a veto.
- **`ProjectileLauncher`** is `ProjectileSource#launchProjectile` (the server's launch path, a `ProjectileLaunchEvent`), and stamps the projectile's own persistent
  data with `materione:launched_by` = the Materione id of the run's stack (below).
- **`Regions`** is the ownership check and the hop of the table above.
- **`CustomBlocks`** is what the custom-block runtime offers the verbs: place a Materione block by id and take one down through its own removal path, so the
  placement index never keeps a block that is gone. It is bound once content is applied; before that a Materione block id is "not loaded".

`WorldVerbs` bundles them with the caps and the two reporters, and is the only thing the world actions are constructed with.

### Reentrancy: a verb's event answers every rule but the one that caused it

Two verbs raise real server events so other plugins see them: `damage` raises an entity-damage event and `break_blocks` a block-break event per block (`set_block` a
placement event). Those events **are answered by rules**, with one exception: **the rule that caused the event is not answered by it.** The verb opens a scope
(`VerbScope`, per thread, because the events are delivered synchronously) that names its rule (`RunMeter#origin`, the opaque identity of the rule the run executes), and the
bridges fire the list of rules without that one (`VerbScope.allowed`): an excavator's `break` rule is not answered by the nine blocks it breaks, so it cannot dig for ever, while a
"blocks mined" counter on the same trigger hears all of them. The same for damage: the `entity.hit` or `player.hurt` a verb's damage raises runs every other rule (a bleed, a kill
counter, another item's) but not the rule that dealt it. `entity.kill` is unaffected, and the attribution below makes a kill the verb causes the run's player's.

**A depth cap is the backstop.** A rule that answers a verb's event may itself run a verb, and so on; the scopes nest, and an event raised at depth 4
(`WorldLimits.MAX_CHAIN_DEPTH`) is answered by no rule, whatever the rules are. The bridges drop it and report it once per ten seconds as `MTR-0118` (a budget: the chain's), so
two rules that answer each other end after four levels, loudly and cheaply, instead of freezing a region. Events from vanilla or other plugins start at depth 0.

**The event's own block.** While the item bridge runs the rules of a break, the block being broken is still in the world; `break_blocks` leaves that block to vanilla
(`VerbScope.breakEvent`), or the excavator would drop it twice. After the rules return vanilla finishes it. A rule that parks (`wait`, `delay`) resumes after the
break is done and finds that block already air, which is skipped as any air is.

### Caps and budgets

Caps are numbers in one record (`WorldLimits`: defaults here, overridable in `settings.yml`, decision 6), enforced **before** the work, so a step over a cap does nothing. Per-step caps are in the grammar and are checked at load for
a literal (`MTR-0057`) and at run time for an expression. Per-run caps are charged to the run's `RunMeter` (a facet of every run, `api.logic.RunMeter`): a tally of
totals by key that the interpreter creates with the run, so a flow that repeats a verb or `call`s one is bounded as a whole, and a run that parks keeps its totals.

| Budget | Per step | Per run | On overrun |
|---|---|---|---|
| blocks (`break_blocks` and `set_block` share one tally; a break is charged its shape's volume, a set 1) | 9 by 9 by 9 = 729 (radius at most 4 an axis) | 2,048 | the step fails, nothing is changed, `MTR-0118` |
| particles | 500 | 2,000 | the step fails, nothing spawns, `MTR-0118` |
| projectiles (`launch`) | 16 | 16 | the step fails, nothing launches, `MTR-0118` |
| dropped stacks (`drop_item`) | 2,304 items (36 stacks) | 128 stacks | the step fails, nothing drops, `MTR-0118` |
| an `at` offset | 64 blocks an axis | | `MTR-0057` at load for a literal; the step fails for an expression |

`MTR-0118` is reported once per verb per ten seconds (as `MTR-0065` is for §38 decision 11), because a hot trigger would otherwise report it on every event. A context that
is not a run (a test calling an action directly) has no meter and gets only the per-step bound. The numbers are chosen so the worst legal step is cheap (729 blocks is one
tick on one region) and the worst legal run is bounded; they are defaults, and the server owner moves any of them in `settings.yml` (decision 6).

### Failure and `require` (U5)

Every verb returns the engine's outcome, so a failed verb is `FAILED` for `require`'s `else:` and the trace. What fails, and what is deliberately **not** a failure:

| Verb | `FAILED` when | `OK` although nothing changed |
|---|---|---|
| `heal` | the subject is dead or not valid; not a living entity; a negative or non-numeric amount; a listener cancelled the regain | an amount of 0; a subject already at its maximum health |
| `damage` | the subject is dead or not valid; not an entity that can be hurt; an unknown `cause`; a negative or non-numeric amount; **a listener cancelled the damage event** (seen at `MONITOR`, said quietly: decision 2) | an amount of 0 |
| `velocity` | the subject is dead or not valid; a component that is not a number in range, or a result that is not finite | |
| `particle` | an unknown particle; a particle that needs data the verb does not carry (a block, an item); a location that cannot be read; the run's budget | |
| `launch` | the type is not a launchable projectile; the shooter is not alive; every launch was refused; the run's budget | |
| `break_blocks` | `as_player` and the run has no player; **eligible blocks and none could be broken** (the message counts refused, in another region, could not be broken); the budget | a box with no breakable block (air, liquid, bedrock, the event's own block); a box where at least one block broke |
| `set_block` | `as_player` and no player; a placement a listener refused; `replace: air` over an occupied block; not a block; a Materione id not loaded; another region when as the player; the budget | |
| `drop_item` | the item is unknown or not loaded; a location that cannot be read; the budget | |
| `stack.edit` | the run has no stack; the stack is gone or is now another item; `damage` on an item with no durability | |

An excavator that mines a block with nothing around it is therefore a success: a verb that warned for every empty neighbourhood would be reported on every swing. A vetoed
dig is a failure a pack can answer (`require: [break_blocks: ...]`, `else: [message: "You cannot dig here"]`).

### `damage` and attribution

`damage` is attributed to the run's player whenever there is one and the subject is someone else: with no `cause` it is `Damageable#damage(amount, player)` (a player attack, with a damager,
so combat, claim and kill-credit plugins see a normal blow); with a `cause` it is a `DamageSource` of that vanilla type with the player as causing and direct entity. With no player,
or when the player hurts themselves, it is unattributed (`generic`, or the `cause`). A kill it causes is the run's player's. A self-inflicted damage is not a player attack
because a player does not attack themselves.

### The projectile carries the item

`launch` writes `materione:launched_by` into the projectile's persistent data: the Materione id of the run's stack (the same handle as §38, through `VariableBinding#stack`), absent for a
vanilla item or a run with no stack. `entity.hit` already ran the shooter's main-hand rules for a projectile's hit (the shooter is the attacker, §37); it now reads the tag: for a
projectile that carries one, the host stack is the stack with **that identity** in the shooter's inventory (the main hand first, then the storage slots), so the item's `entity.hit`
rule answers the hit even when the shooter holds something else when it lands; when the item is gone from the inventory, no item rule answers and the rule documents still do. A projectile
with no tag (a bow's arrow, a ghast's fireball) behaves exactly as before.

### `stack.edit` and "the stack the run is about"

`stack.edit` asks the run's variable binding for its stack (`VariableBinding#stack`), which `RunVariables` holds: the stack the trigger declared (§38 decision 4), a handle and not a copy,
checked against the identity it had when the run was given it (decision 5). The verb edits **item meta in place** (`ItemStack#editMeta` on the live mirror): `name` and `lore` with the
item-text pipeline of everywhere else (MiniMessage, italics off by default, templates allowed), `model` (the `item_model` component) and `damage` (the vanilla `damage` component, clamped
below the maximum so an edit never breaks the item; Materione's own durability counter is untouched). It **patches components and never replaces the stack**, so identity, item variables,
enchantments and count survive. It fails on a run with no stack, on a stack that was replaced while the run was parked, and on `damage` for an item with no durability. A rule on a trigger
that is not about an item is told so at load (`MTR-0060`, `params.requireRoot("item", ...)`, the same check §38 decision 10 gave `set_var`). The issue limits the verb to the held stack, and it is
limited: the run's stack is the held stack for every item trigger (and the main-hand stack for a rule
document, §37 decision 6). A GUI opened by an item run has that subject stack; a command-opened GUI
has none, and the action then fails soft at run time.

### What changed for existing content

Nothing, with one exception, and it is a defect the verbs exposed rather than a feature:

- **Item `break`'s `location`.** (Decision 5: kept as the block; the player is `player.x/y/z`, and `block.*` names the block.) The trigger's context says `location.x/y/z` is "the event location", but the item bridge filled it with the *player's* position. The excavator needs the broken block,
  so for an item's `break` the bridge now passes the broken block's coordinates (whole numbers, a block's). A rule that read `${location.x}` on an item `break` now reads the block; no fixture,
  test or shipped pack did. No public release exists to keep compatible (owner, 2026-11-19). Every other trigger's `location` is unchanged. The player's own position is still `player.x/y/z`, and an item's `break` now also carries `block.type`, `block.x`, `block.y`, `block.z` and `block.face`.
- A hit by a projectile that carries a `launched_by` tag runs that item's rules (above). No projectile carried one before.
- The `entity.hit`, `player.hurt` and item `break` events that `damage` and `break_blocks` raise are answered by every rule but the one that caused them, and a chain stops at depth 4 (above); no rule could have caused one before.

A file that never writes one of the nine verbs loads and saves byte for byte (§5.6); the nine are new names, so no existing step can collide with them.

### Diagnostics

Three new codes, documented in [`plugin.md`](plugin.md) §5 (taken after the block `MTR-0106` to `MTR-0117` that the HUD and frame-cycling designs reserve; `DiagnosticCodesTest` pins the count):

| Code | Severity | Meaning |
|---|---|---|
| `MTR-0118` | warning | a world verb spent its per-run budget, or a chain of verb-raised events went 4 levels deep; the step failed and did nothing past the cap, or the bridges stopped answering the chain |
| `MTR-0119` | warning | a verb skipped a location in a region the running thread does not own and cannot hop to (as the player on Folia) |
| `MTR-0121` | warning | a `world_verbs_*` setting is not a whole number in its range; its default is used (`MTR-0120` is #468's) |

Everything else is existing codes: a bad parameter or an action that rejects a `target:` is `MTR-0057`; a verb that needs a `location` or an item the trigger does not provide is `MTR-0060`; a verb that fails
at run time is the action's `FAILED` and the reporter's rate-limited warning when nothing guards it.

### The universality tests (master §3.1)

| Test | How it passes |
|---|---|
| U1 name | `heal`, `damage`, `velocity`, `particle`, `launch`, `break_blocks`, `set_block`, `drop_item`, `stack.edit` name what is done to the world; none says `lifesteal`, `dash`, `fireball` or `excavate`. `as_player` and `at` name capabilities of the verb, not a use |
| U2 generality | any living entity for the entity verbs (the run's player, the struck entity, a named player, a selector: §39); any block the platform can touch, vanilla or Materione; any projectile type the server can launch; any location the value can name |
| U3 composition | every verb composes under `require`, `chance` and `delay` like any action (the table above is the outcome contract), takes `target:` where it has a subject, and takes the one `at` and the one expression marker; the four benchmark features are YAML only (`fixtures/packs/worldverbs`) |
| U4 registry actions | nine entries in the `actions` registry, each with full self-description (every parameter described, defaults and ranges stated, the Expr marker on every computed quantity); the editor form, `logic.vocabulary`, `authoring.describe` and the SDK are generated from it with no code |
| U5 parse at load, fail soft at run, capped | grammar and ranges checked at load; a verb that cannot act fails its step and never throws into the server; every verb that multiplies work is capped per step and per run, the caps are settings, and an overrun is a diagnostic |

### Presets, not engine behavior (#13 and #21)

[#13](https://github.com/xef5000/materione/issues/13)'s combat and farming entries are **presets over these verbs** (primitives.md §6), not natives, and none is built here: *lunge* is `velocity` with `forward`; *lifeleech* is
`heal` with an expression of `damage.final`; *knockback strike* is `velocity` on `target: event.target`; *fireball* and *wither skull* are `launch` with a `projectile`; *lightning strike* needs a
`spawn` verb (not in this issue's nine); *3x3 big-mining* is `break_blocks`; *harvest and replant* is `break_blocks` then `set_block`; *auto-smelt* needs an item transform (a recipe lookup, not a verb here);
*bedrock-break* is `break_blocks` with `as_player: false` and a rule that opts in. *Bleed* is §39's `potion` on `event.target`, or `damage` on a repeat that also carries a subject key; the generic timers of §44 fire for the server or online players, not for an arbitrary struck entity.
[#21](https://github.com/xef5000/materione/issues/21) (particles) is covered for its "integration point exposed to ECA actions" bullet by `particle` and for nothing else: its **shapes** (ring, helix, sphere) and its **aura triggers**
(on hold, ambient) are not built, because a shape is a pattern over many spawns (a loop or a repeating trigger) and the repeating trigger is #332. #21 stays open for them (decision 7).

### Verification

`EntityVerbsTest` and `WorldBlockVerbsTest` (each action against scripted seams: the subject and the target, the clamp, the attribution, the maths of a facing, the box, the veto, the event block, the regions, every
cap and its one diagnostic, the load rejections), `WorldLimitsTest` (the caps, the overrides, a bad value), `StackVerbsTest` (the run's stack handle, `stack.edit` end to end through the real `BukkitStackRef`, the stale handle, the item id on a launch),
`WorldVerbsPipelineTest` (the `worldverbs` fixture pack from files to the live shadow with no diagnostics; the load-time checks), `DiagnosticCodesTest` (106 codes), the registry, editor demo snapshot and `.d.ts` goldens
(nine actions on the wire), the corpus (T13 and T14 pass; T12, T20, F2 and F5 keep only their other gaps; `scripts/benchmark/corpus-report.mjs --check` is in sync) and `PaperWorldVerbsIT` on Paper 1.21.4 (a 3 by 3 break where a
protected block survives and the rest are gone; a counter rule on the same trigger hears every block that was really mined while the excavator's own rule runs once; a dash that moves the player). QA `worldverbs.*` (the particle test is `visual`; **not run and not blessed**: they need the local 26.x probe, and blessing runs happen on main).

### Decisions on the questions the first draft left open

The owner asked the technical questions to be settled by three principles: allow as much as possible and restrict only when needed; prefer "X and Y behind a switch" to "X or Y"; code is
cheap and annoyances are expensive, above all for a first-time user. Each decision is reversible by the owner.

| # | Question | Decision | Rationale |
|---|---|---|---|
| 1 | `as_player: false` asks nobody: an opt-in? | **No opt-in.** It stays allowed as written. #466 (the protection port, built: §43) made `as_player: false` ask the port with *no actor*, so a claim plugin may still refuse an actor-less break; `protection_no_actor: allow` turns that off | the author of a pack is the server's admin, and a switch on an admin's own file is an annoyance with no one to protect; the real answer is a port that can say no, which is #466's |
| 2 | `damage` cannot report a veto | **Report it, quietly.** `DamageWatch` runs the call inside `observe`, and a listener at `MONITOR` (the last word, after every protection plugin) records whether the damage event ended cancelled; the outcome is then `FAILED` ("the damage was cancelled by a listener"), so a `require` can branch on it and the trace shows it. No chat line, no diagnostic: if Materione and another plugin both police a blow, the owner decides which to disable | a veto is information a flow can use, and spam is an annoyance; one listener with one per-thread slot costs a thread-local read on every other damage event |
| 3 | Folia: a block as the player across a region boundary | **Keep the refusal** (`MTR-0119`, rate-limited, never on Paper); #466 revisited it (§43): lifted in `protection_mode: port`, kept in `event` and `both` | reading and breaking across regions at once needs the player's thread and the block's, which Folia does not give; the refusal is correct and cheap, and the port may change what is asked of whom |
| 4 | Chain reactions | **Not a blanket suppression** (the first draft's): see "Reentrancy". The rule that caused an event is not answered by it; every other rule and plugin is; a depth cap of 4 with `MTR-0118` is the backstop | allow the useful thing (a counter hears every block; a bleed rides every hit) and restrict only the one case that is always a bug (a rule answering itself) |
| 5 | `location` on an item `break` | **Keep it as the broken block.** The player's own place is `player.x/y/z` (the player facet, already in an item's context) and there is now a `block` root on an item's `break`: `block.type`, `block.x`, `block.y`, `block.z` and `block.face` (the face of the block the player looks at; empty when none) | one honest meaning of `location` (where the event happened), nothing lost, and the block is nameable without a workaround |
| 6 | The caps | **The numbers stay as defaults, and every one is overridable in `settings.yml`** as flat optional keys, none written to a fresh file: `world_verbs_max_radius` (1 to 8, default 4), `world_verbs_max_blocks_per_run` (1 to 65,536, 2,048), `world_verbs_max_particles_per_step` (1 to 5,000, 500), `world_verbs_max_particles_per_run` (1 to 100,000, 2,000), `world_verbs_max_launches_per_step` (1 to 64, 16), `world_verbs_max_launches_per_run` (1 to 256, 16), `world_verbs_max_drop_stacks_per_run` (1 to 4,096, 128). A value that is not a whole number in its range falls back to its default with `MTR-0121` | a survival server wants smaller and a creative one larger; the range is a hard ceiling so a typo cannot make a step unbounded; the step cap of a box is derived `(2r+1)^3` |
| 7 | #21 | **No change.** `particle` covers #21's integration bullet only; shapes and aura triggers stay #21's, and the aura needs #332. #21 stays open | |
| 8 | Facing-relative shapes | **Both, behind a switch.** `relative_to: world` (the default) measures `shape` along the world axes; `relative_to: facing` measures it **across** (x), **up** (y) and **depth** (z) from the face the player hit (`block.face`), or, when the trigger has none, the way the player looks (the horizontal axis they face, or vertical when they look steeply up or down). `[1, 1, 0]` is the flat 3 by 3 plane in front of the player, which is what an excavator wants. A run with no player and no face fails the step rather than guessing | the world box stays the simple default and the corpus program is unchanged; the plane costs one enum |

### Not built, on purpose

`spawn`, `kill` and an attribute modifier (the other verbs primitives.md §5.7 lists: no benchmark task needs them); particle shapes and aura triggers (#21's remainder, #332); the protection port that `as_player: false` and the Folia
boundary ask (built in #466, §43); particles that carry block, item or transition data; a typed `at` or `shape` in the SDK (both are `raw` in the schema, as `target` is, §39 owner question 4); the verbs in a GUI until it is opened about a stack; any Folia run.

---

## 42. Decisions taken in #464 (the money amount is an expression)

[#464](https://github.com/xef5000/materione/issues/464) closes **QTY**, the last gap of [`agent-benchmark.md`](agent-benchmark.md) T21 (`/pay`):
`give_money`, `take_money` and `has_money` read `amount` as a literal at load, so an amount from `args.amount` or a variable was a type error in
the code-mode SDK (`TS2322`). The amount now takes an expression, the way `give_exp` and `give_item` already take one, through the same
`Quantity` ([`ParamReader`](../../api/src/main/java/dev/materione/api/logic/ParamReader.java) `template` and `acceptingExpression()`). No new
engine surface: it widens an existing parameter to the one expression language, so it is not a primitive under D12 (the table of master §3.1
below records how it still meets the tests).
The code is in `plugin.logic.ProviderParams` (`amount`, `runAmount`, `refusedAmount`), `plugin.logic.action.GiveMoneyAction` and
`TakeMoneyAction`, and `plugin.logic.condition.HasMoneyCondition`. The refusal's engine path is `behavior.run` (`RunReporter.refused`,
`FlowInterpreter.failure`, `ControlSteps.holds`, `TargetedAction.aggregate`) and `behavior.runtime.RuleEngine`, with the two types it needs in
`api.logic` (`ActionOutcome.refused` and `CodedRefusal`). Each decision is reversible by the owner.

**Traceability.**

| Decision or rule | How this section applies it |
|---|---|
| D5 | `amount` declares `acceptingExpression()`; the SDK types it `number \| Expr` from the registry's `expression` flag, with no SDK code. The registry, editor snapshot and `.d.ts` goldens change by those lines and nothing else |
| D12, master §3.1 | no engine surface: a parameter widened to the existing expression language (U3); a literal and a template are one value |
| D8 | a template renders on the thread its own reads need (`resolveResumable`), and the currency then runs the deposit or withdrawal on its own thread, as §39 aims a target |
| D6, D1 | nothing is stored: the YAML and the file format are unchanged; a literal stays a number in the file |
| master §5.6 | literal files load, save and run byte-identically ("What changed for existing content") |

### Decisions

1. **One quantity, the one `give_exp` reads.** `ProviderParams.amount` returns a `Quantity`. A literal keeps its checks and messages, at load:
   `amount is required`, `amount must be a finite number in 0.0..1.0E12`, `amount must be above 0`. A string that holds `${` or `%` is a
   template, compiled at load and checked as it renders. A plain string such as `"100"` is still refused at load, as before, so no existing
   file changes meaning.
2. **At run time a template must render to a number above 0 and at most `MAX_AMOUNT`.** `ProviderParams.runAmount` parses the rendered text as a
   finite double and requires it above 0 and at most `1.0E12`; anything else has no amount. `give_money` and `take_money` then fail with
   `amount must be a number above 0 and at most 1.0E12, not "<what it rendered>"` before any balance is touched, so a negative result never
   pays or takes. `has_money` refuses the same way, with the same reason, and is false: a condition returns no outcome, so the refusal is
   the exception `CodedRefusal` (below), and the engine counts the test as false, as the contract of `ConditionType.test` requires.
3. **Placement is `give_exp`'s.** `executeResumable` renders the template first, on the thread its own reads need (the run's region, or the
   target's, through the aimed executor of §39), then runs the action on `threading(params)`, the currency's thread as before. `has_money`
   renders first and reads the balance on the currency's thread. A literal has nothing to resolve, so its path is the one it was.
4. **A refused amount is reported under `MTR-0120`.** A literal that does not load is `MTR-0057` (the step is skipped, as for any action).
   A template that renders to something other than a number above 0 and at most `1.0E12` is refused at run time under **`MTR-0120`**
   (warning, `DiagnosticCodes.QUANTITY_REFUSED`, plugin.md §5). The engine does three things with it, the same way for every step:
   - **the outcome carries the code.** `ActionOutcome.refused(code, reason)` is a `Failed` whose `code()` is set (an ordinary failure has
     none). The interpreter reports it through `RunReporter.refused`, rate-limited per rule and code as the other run-time codes are, and
     reports it **even inside a `require`**: a bad amount is a content error, where an insufficient balance is a business outcome and stays
     silent. A multi-target step keeps the code only when every failing target refused with that code (`TargetedAction.aggregate`);
   - **the trace names it.** The step's `STEP_FAILED` detail begins `MTR-0120`, so a live trace shows the code next to the step, not only the
     reason;
   - **a condition refuses by exception.** `has_money` cannot return a code, so it throws `CodedRefusal` (`api.logic`). Its test counts as
     false, and `ControlSteps.holds` and `RuleEngine.conditionResult` report the code and trace `MTR-0120 condition refused: …`. The composites
     share one caveat: inside `not` or `any`, a refused amount makes the whole condition false (fail closed) rather than only its own branch. A
     refusal is a content error, so that is the safe reading.
   `MTR-0106` to `MTR-0119` are not touched (#465 takes `MTR-0118` and `MTR-0119`).
5. **The survey: which quantities take an expression, and which stay literal.** The issue asked for the others to be checked, and widened only
   the obvious ones. The state after this section:

| Parameter | State | Reason |
|---|---|---|
| `give_money`, `take_money`, `has_money` `amount` | **widened here** (decisions 1 to 3) | the benchmark's `/pay`; a price or a payout that follows an argument or a variable |
| `give_exp`, `take_exp`, `has_exp` `amount`; `give_item`, `take_item`, `has_item`, `has_item_space` `amount` (and `slots`) | already expressions | `give_item`'s `amount` is the example the issue named, and it already is one |
| `teleport` `x`, `y`, `z`, `yaw`, `pitch`; `is_near` `x`, `y`, `z`, `distance` | already expressions | |
| `set_var` `value`; `custody.consume` `slot`; `prompt`, `title`, `message` text | already expressions | |
| `potion` `duration`, `amplifier` | literal | no benchmark task needs them; the widening is the same `Quantity` read and waits for the first one that does (question 3) |
| `title` `fade_in`, `stay`, `fade_out`; `sound` `volume`, `pitch`; `prompt` `timeout` | literal | presentation timings, not values a flow computes; no task needs them |
| `slot_has` `slot`, `amount`; `gui.slot_item` `amount`, `level`, `set_amount` | literal | a GUI's slot is a position in its own layout, fixed when the GUI is authored, and the GUI's item edits are its own |
| `has_permission` `min` | literal | a count over the node list the same step holds, checked at load against that list |
| rule `cooldown`, `charges`, `per`, `on_cooldown`; step `delay`, `chance`; `wait` ticks | literal | not action parameters: the rule and flow compilers read them at load into the cooldown store and the scheduler (logic.md §16, §37). An expression there is a change to the rule model, not a widening |

The rows marked literal for the presentation timings, the GUI slot, the permission count and the step keys `chance`, `delay` and
`wait` were widened by #470 (decisions below); the rule keys stayed literal there, with their reasons.

### What changed for existing content

Nothing migrates and no file is rewritten. A literal `amount` loads, saves and runs as it did: the same checks and messages, the same double
handed to the deposit and the withdrawal, the same `insufficient funds: balance <b>, needs <n>` reason, and the same `has_money` answer. The
recipe `pay_to_receive` expands to the same `take_money: {amount: 250.0}`. The SDK's `giveMoney(5)` and `takeMoney(5)` still type-check: the
shorthand widens to `number | Expr`, it does not narrow.

### The universality tests (master §3.1)

| Test | How it passes |
|---|---|
| U3 composition | `amount` is the expression language every other quantity reads; `target:` (#331) addresses the player the amount is paid to, and the same `args.amount` feeds both halves of `/pay` |
| U4 self-description | `expression: true` on the three entries in `authoring.describe`, the golden registries and the generated `.d.ts`, from the same declaration that parses them |
| U5 safety | a bad literal or template is refused at load and skips only its step; a template that renders to anything but a positive number fails the action or is false, moves nothing, and reports `MTR-0120` |

### Verification

`MoneyAmountExpressionTest` (nine tests: the declaration, the load-time refusals, a template that pays and takes, text, zero and negative results
that move nothing, `has_money` on a template and its refusal, the code on the outcome and on the refusal, the resumable paths with a template and
with a literal), `FlowInterpreterTest` (two new: a refused value inside a `require` is reported as `MTR-0120` and traced under that code; a
condition that refuses is false, the else runs, and the code is reported), `DiagnosticCodesTest` (the count is 105; `MTR-0120` is warning-level),
`ProviderActionsTest` and
`HasMoneyConditionTest` (the literal messages, unchanged), `TargetedActionsTest` (the money actions on a target), and `ConditionsInFlowsTest`
(a flow with the three entries and templates compiles with no problem, and a `"lots"` amount is still refused at load). The corpus: T21 passes
(`fixtures/codemode/corpus/t21-pay-command.expect.json`), with its staged operations pinned by `fixtures/golden/codemode/ops/t21-pay-command.json`
and the real server's dry run by `fixtures/golden/codemode/replay/t21-pay-command.json` (accepted, no diagnostic, one document: `commands/pay.yml`).
Regenerated and reviewed (the diffs are the three amounts, their descriptions, the `expression` flag and the shorthand signatures):
`fixtures/codemode/registry/plugin-current.json`, `with-addon.json`, `fixtures/golden/editor-demo-snapshot.json`,
`fixtures/golden/codemode/plugin-current.d.ts` and `with-addon.d.ts`. `scripts/benchmark/corpus-report.mjs --check` is in sync (18 of 29 tasks pass;
QTY is closed).

**Harness and QA.** `harness/` has a test economy (decision 1 below): `PaperEconomyIT` boots the `economy` fixture pack on Paper and moves
balances between two fake players. The QA catalog's `economy` suite (`qa/catalog/economy.mjs`, two auto entries, not run and not blessed) covers
the refusal a player can see on the probe, which has no economy provider and needs none for it: a bad amount is refused before any currency is
read, the run carries on, and the server logs `MTR-0120`. A payment on the probe is not covered, because the probe does not install the stub.

**Found while testing, fixed in #469.** A currency file with its fields at the top level (`integration: xp` with no id above it) defined
**no** currency, and nothing reported it: `PackResources.declared` found no resource and the file was dropped silently. So
`fixtures/packs/showcase/currencies/points.yml` left `showcase:points` undefined at run time, and the economy fixture's `coins.yml`
failed the same way before it was keyed. A pipeline test confirmed it on the showcase pack (`CurrencyFileShapeTest`: the live currencies
were `economy:coins` and the probe's, never `showcase:points`). The fix accepts the flat form for `currencies/` (item 1 above), reports
a file that declares no resource or mixes the two shapes as `MTR-0008`, and keeps the showcase file flat, so the QA showcase entries
(`showcase.shop-buy-with-points`, `showcase.shop-buy-refused-without-points`) exercise the flat form on a live server; they are not run
here. The web editor's resource forms read keyed documents only, so a flat currency file was not offered as a
resource there; that is fixed in #475 (item 1 above, web.md section 4.2). A top-level key with no value (`sword:` with nothing under it) used to abort the whole reload,
because the lossless document refused null values; it now reads as null, and a keyed file reports that key with `MTR-0008` while its
other resources load.

### Decisions taken on review (#464)

The first draft of this section left two questions to the owner; the owner delegated both, and the decisions are these.

1. **A test economy in the harness: yes; in the probe: no.** The harness implements Materione's own currency SPI
   (`api.logic.provider.CurrencyIntegration`), not a Vault jar: `EconomyStub`, balances held in memory, in the durability probe
   (`harness/src/durabilityProbe`, never shipped). The probe registers it with the product's logic registry as `harness:stub`, and
   the probe plugin declares `depend: [Materione]` so the product loads first. `fixtures/packs/economy` names it
   (`currencies/coins.yml`), and two command documents move coins through it: `coinpay` (the T21 shape: a player argument, the amount
   from `args.amount`, `take_money` in a `require`, then `give_money` to the other player) and `coinpay-raw` (the amount as text, so a bad one
   reaches the money code). `dprobe grant` and `dprobe balance` read and write the balances. The QA probe is not given the stub: it loads
   `Materione.jar` only, so the stub would need a second jar built and installed by the probe script, and that is not cheap. The probe copies every
   fixture pack, so the economy pack is in it too, with its currency reporting unavailable; the `economy` QA entries need nothing more.
2. **A refused run-time amount reports `MTR-0120`: yes.** Decision 4 above, with the trace and the `require` behaviour it fixes. The code is
   pinned in `DiagnosticCodesTest` (the count is 105) and documented in plugin.md §5. #465 takes `MTR-0118` and `MTR-0119`, so `MTR-0120` is
   free on `main`; the merge of #465 is a trivial conflict in that test's count and comment, as the coordinator expected.

### Decisions taken in #470

[#470](https://github.com/xef5000/materione/issues/470) widens the quantities this section left literal, and the step keys the compilers
read at load wherever the flow can read them per run. The owner's rule for the decisions was *allow as much as possible*; each choice below
is reversible. It answers the question §42 left open, which is decided here, not carried.

| Decision or rule | How this subsection applies it |
|---|---|
| D5 | each widened parameter and step key declares `acceptingExpression()`; the registry, the editor snapshot and the `.d.ts` goldens change by those flags and the `number \| Expr` types |
| D12, master §3.1 | no new engine surface: each quantity is the `Quantity` every expression parameter already reads (U3), and the step keys read the same type |
| D8 | a template renders on the thread its own reads need, before the effect: an action as `give_exp` does, a step key through the interpreter's own resumable evaluation |
| D6, D1 | the file format is unchanged; a literal stays a literal in the file |
| master §5.6 | literal files load, save and run as before, with the same load messages |

1. **`Quantity` moves to `api.logic`.** The flow compiler and the interpreter read `chance`, `delay` and `wait` through it, and `behavior`
   cannot depend on `plugin`. `api` is the lowest module and the type uses only `api` classes, so the boundaries of plugin.md §11 hold. Three
   helpers read a literal through the `ParamReader` method its parameter always used (`Quantity.integerOr`, `requiredInteger`, `decimalOr`), so
   a literal's load message is the text it always was (`fade_in must be an integer in 0..72000`), and the tests that pin those messages are
   unchanged.

2. **Widened: each takes a number, or a template that renders to one.** Potion `duration` and `amplifier`; title `fade_in`, `stay` and
   `fade_out`; sound `volume` and `pitch`; prompt `timeout`; `slot_has` `slot`; `gui.slot_item` `slot`; `has_permission` `min`, whose range is 0
   to the count of the nodes written. A literal is checked at load as before. A template is compiled with its step and checked when the step
   runs.

3. **A refused template is `MTR-0120`, generalised.** The code now means *a template value refused at run time: it renders outside the range its
   parameter takes*. Its Java constant is `DiagnosticCodes.QUANTITY_REFUSED` (renamed from `MONEY_AMOUNT_REFUSED`; the code string is the API and
   does not change). No new code is allocated: `MTR-0125` is taken by #473 and `MTR-0106` to `MTR-0117` are reserved, so the count stays at 111
   and `DiagnosticCodesTest` changes only its comment. Per shape:
   - an **action** returns `ActionOutcome.refused` before any effect: the step fails with a reason that names the parameter, its range and what it
     rendered to (`fade_out must be a whole number in 0..72000, not "-3"`). A `prompt` whose timeout is refused never asks its question, so its
     `else:` runs as it does for any unanswered prompt;
   - a **condition** throws `CodedRefusal`: `slot_has` and `has_permission` are false and the engine reports the code (§42 decision 4);
   - the value is checked **before** the context it is used in: before a GUI session is looked up and before the player's permissions are read,
     so a bad value is reported whatever the context.

4. **The step keys `chance`, `delay` and `wait` are widened.** The flow interpreter can read them per run within the budget of plugin.md §9. A
   literal step runs the code it always ran, with one boolean test added. A template step renders once per run, as an action's own parameters
   render on every run, before the roll or the park. The interpreter renders it through its own `await`, the resumable evaluation an action uses,
   so a template that reads a player's state hops to that state's thread as any action does. A refused value fails its step under `MTR-0120`
   before the roll and before the run parks; a refused `wait` fails in place and the next step runs. A guarding step (§6.6: `prompt`,
   `custody.consume`) takes no `chance`, a template included, because a template may render below 1 and a guard must always run.

5. **The rule keys stay literal: `cooldown`, `charges`, `per` and `on_cooldown`.** The rule gate reads them at dispatch, before any run exists and
   on the event's thread, to decide whether the rule fires at all, and the cooldown store keys on their values. A template there would render
   inside the gate, before an owner for the rule's reads is chosen, and neither the gate's budget (plugin.md §9: flow dispatch at most 0.5 ms
   at p99) nor the strict-reads rule allows that. `per` also selects the scope of the key rather than holding a number. Widening them changes the
   rule model, not a parameter, so they are not part of this widening.

6. **Not widened.** `gui.slot_item` `amount` and `level`, and `slot_has` `amount`: the issue named the slot numbers, and no task asks for an
   expression there. The reason is §42's own: a literal until a task needs one.

**Not done here.** The older quantity readers (`give_exp`, `take_exp`, `give_item`, `take_item`, `heal`, `teleport` and the others that read a
`Quantity` before this section) refuse a bad template with an ordinary failure and no code, as they did before. Reporting them under `MTR-0120`
too would change the trace of ten actions outside this issue's list, so it is a follow-up, not part of #470.

**Performance.** `FlowDispatchPerfTest` (the 0.5 ms p99 budget of plugin.md §9) passes unchanged. A literal step's path adds one boolean test. A
template step costs one render per run, which the budget does not measure on its own; no p99 is quoted for that path.

**What changed for existing content.** Nothing migrates. A literal in an existing file loads, saves and runs as before, with the same messages.
A template in `chance`, `delay` or `wait`, or in a widened parameter, was a load error before this section and is accepted now, so no existing
file changes meaning.

**Verification.** `QuantityExpressionTest` (plugin) covers each widened parameter: a literal's load message, a template read when the step runs,
and a refused template refused with its code before any effect (potion, title, sound, `slot_has`, `gui.slot_item`, `has_permission`).
`PromptActionTest` adds the template timeout: it renders before the question, and a refused one asks nothing. `FlowInterpreterTest` (behavior)
covers a template chance that renders before the roll (zero skips, one always runs), a template delay and wait that park for their rendered
ticks, a refused chance that fails its step under `MTR-0120` while the chain goes on, a refused delay that parks nothing, a wait that renders
text and is refused in place, and a refused value inside a `require` that runs its else under the code. `TemplateTimingCompileTest` (behavior)
covers the load side: literal keys compile as before, templates compile and keep their expressions, a literal out of range still skips the
rule (`MTR-0021`), bad syntax skips the step (`MTR-0022`), and the keys are declared as expressions. The goldens were regenerated and reviewed:
`fixtures/codemode/registry/plugin-current.json`, `with-addon.json`, `fixtures/golden/editor-demo-snapshot.json`,
`fixtures/golden/codemode/plugin-current.d.ts` and `with-addon.d.ts`; the diffs are the descriptions, the `expression` flags and the
`number | Expr` types.

**QA.** `qa/catalog/quantities.mjs` holds five auto entries over the commands in `fixtures/packs/commands/commands/qty.yml`: a potion duration
applied and refused, a sound volume applied and refused, and a title fade-in refused. They pass `node qa/runner.mjs check`; they have not been
run on the probe, and no ledger entry is recorded.

### Owner questions

None open in this section: the question §42 left open is decided above. Retrofitting `MTR-0120` to the older quantity readers is a follow-up
(see "Not done here").

### Not built, on purpose

A `min` and `max` on a money amount (question 2 of §40, still open: the amount is checked against the one range, `0` to `1.0E12`); a money amount
in another unit (the expression language does the arithmetic); and a list of amounts in one step (a payment per player is a loop in the TypeScript
program, not an engine list).

## 43. Decisions taken in #466 (the protection port)

The full design is [`protection.md`](protection.md); this section records what #466 decided and where, so the verbs' contracts stay in one
place. #466 is a primitive under D12 ([`architecture.md`](../architecture.md) §3.1): one question, "may this actor change this place?",
asked by every verb that changes the world on someone's behalf and by three conditions, answered by region and claim plugins through
adapters. The code is `api.protection` (`ProtectionPort`, `ProtectionApi`, `ProtectionActor`, `ProtectionAnswer`, `ProtectionMode`),
`plugin.protection` (`ProtectionService`, `ProtectionSettings`), `plugin.logic.condition` (`CanBuildCondition`, `CanBreakCondition`,
`CanInteractCondition`) and `integrations.protection` (seven adapters). Each decision is reversible by the owner.

**Traceability.**

| Decision or rule | How this section applies it |
|---|---|
| D12, master §3.1 | the port is a primitive (protection.md §1); a preset or a feature is content over it, never an engine behavior |
| D8 | the port is asked on the region that owns the location; providers declare their thread safety; the `MTR-0119` refusal is lifted where the port answers alone |
| D5 | the providers describe themselves (`ProtectionApi#providers`); the conditions are registry entries with full self-description |
| D6, D1 | nothing is stored; the YAML of every verb is unchanged and the port is a check in the one pipeline step |
| master §5.6 | the default settings golden is unchanged (the keys are optional); no file changes meaning |

| # | Question | Decision |
|---|---|---|
| 1 | The combination | `protection_mode`: `event` (the event path only, the behavior before), `port` (the port only), `both` (default: the port first and a deny stops the verb, then the event path for a player's action). Deny wins, abstain falls through |
| 2 | `as_player: false` | asks the port with no actor in `port` and `both`; asks nobody in `event`. `protection_no_actor: allow` lets an actor-less change through unasked in every mode |
| 3 | Folia and `MTR-0119` | **lifted in `port` mode** (the port answers on the block's region with an offline actor, the block is changed there); **kept in `both` and `event`**, because the event path cannot run on the block's region and skipping it would bypass any plugin that has no adapter |
| 4 | Caching | none: adapters read in-memory indexes and a cache would go stale on claim changes |
| 5 | Adapter failure | fail closed for that call, `MTR-0122` once per provider per ten seconds |
| 6 | Where each verb asks | `break_blocks` `canBreak`, `set_block` `canPlace`, `damage` `canDamage`, `launch` `canUse`, furniture placement `canPlace`, taking furniture up `canBreak`, the custom-block placements that have no event of their own `canPlace` |
| 7 | `damage` and `launch` in `port` mode | the event is the effect, so `port` behaves as `both` |
| 8 | The conditions | `can_build`, `can_break`, `can_interact`, true unless a provider denies; they ask whatever the mode says |
| 9 | Adapters | built: WorldGuard, GriefPrevention, Towny, PlotSquared, HuskClaims, HuskTowns, BentoBox. Skipped: Lands and Residence (JitPack-only APIs), FactionsUUID (no published API). Their plugins stay covered by the event path |
| 10 | `/materione dev place` and `give` | not routed: permission-gated operator tools |

### Diagnostics

| Code | Severity | Meaning |
|---|---|---|
| `MTR-0122` | warning | a protection provider failed; that call was denied |
| `MTR-0123` | warning | a `protection_*` setting is invalid; the default is used |
| `MTR-0124` | warning | a protection provider cannot be used on this server |

Documented in [`plugin.md`](plugin.md) §5 and pinned in `DiagnosticCodesTest` (111 codes). `MTR-0106` to `MTR-0117` stay reserved by the HUD and
frame-cycling designs.

### What changed for existing content

Nothing migrates. With no protection plugin installed `both` asks an empty port and runs the event path, as before. The §41 verbs'
messages for a vetoed block now name the provider when the port refused. `MTR-0119`'s hint names `protection_mode: port`.

### The universality tests (master §3.1)

See [`protection.md`](protection.md) §1: U1 to U5 pass as an actor-, location- and provider-general port, asked by every verb and three
conditions, described by the providers present, and safe (deny on failure, a diagnostic, never a crash).

### Verification

[`protection.md`](protection.md) §12. `PaperProtectionIT` (Paper 1.21.4, a stand-in provider through the SPI), the unit tests, the goldens
(`fixtures/codemode/registry/*.json`, `fixtures/golden/editor-demo-snapshot.json`, `fixtures/golden/codemode/*.d.ts`: three conditions) and
QA `protection.*` (auto, not run, not blessed).

### Owner questions

None (protection.md §13).

### Not built, on purpose

A cache; per-verb or per-provider overrides; adapters for Lands, Residence and FactionsUUID; condition forms for `canDamage` and `canUse`;
PlotSquared's per-plot flags; any Folia run (protection.md §14).

---

## 44. Decisions taken in #332 (scheduled triggers)

#332 is the timer primitive ranked sixth by the agent benchmark: a schedule, not an announcement, reset or held-item feature.
It composes with the host-less `rule` content type of §37 and the ordinary flow engine. The registered triggers `every` and
`schedule` fire only for rule documents; the reserved, unsupported `tick` trigger and the GUI-specific `gui.tick` keep their
existing meanings.

**The documents.**

```yaml
announce:
  behavior:
    - on: every
      interval: 5m
      for: server
      do:
        - broadcast: "<aqua>[Tip] ${vars.server.shop.tip_index}"

mana_regen:
  behavior:
    - on: every
      interval: 1s
      for: each_player
      do:
        - set_var: {var: "rpg:mana", op: add, value: 2}

daily_reset:
  behavior:
    - on: schedule
      at: "06:00"
      days: [monday, friday]
      do:
        - set_var: {var: "shop:daily_claims", op: set, value: 0}
```

`every.interval` is a positive whole number of server ticks or a duration made from `ms`, `s`, `m`, `h` and `d`
(compound values such as `1h30m` are valid). The minimum is one second, therefore 20 ticks. `for` is `server` by default, which
fires once with no player, or `each_player`, which gives every online player their own run and player context. `schedule.at` is a
quoted 24-hour `HH:MM` value in the Java server zone; omitted `days` means every day, otherwise the values are the lowercase full
names `monday` through `sunday`. The schedule run is server-scoped. Conditions, options (`cooldown`, `charges`, `per` and
`on_cooldown`), actions and waits are the normal flow model.

The timer filters are registry schema, including the interval and local-time value kinds. The editor's behavior vocabulary and
the generated code-mode SDK therefore accept `m.triggers.every({ interval: "5m" })` and
`m.triggers.schedule({ at: "06:00", days: ["monday"] })` without SDK code. Invalid intervals, local times, filters or missing
required filters skip only the rule with `MTR-0021`.

**The wheel.** `TimerWheel` in `platform.scheduling` is the one data structure. `GlobalTimerScheduler` is the only platform task:
on Folia it wakes once per global tick through `SchedulingGateway.executeGlobalLater`; on Paper that task is the main-thread
scheduler. No timer owns a Bukkit repeating task. A reload constructs a new wheel from the applied `WorldRules` view and cancels
the old wake-up, so removed or changed timers cannot survive the old shadow. A pack with no `every` or `schedule` rule creates no
scheduled task.

A tick interval counts global server ticks. A duration measures injected-clock time, so the pure wheel and its drift behaviour are
fake-clock testable. A wall schedule is recalculated in the server zone and uses Java's zone rules for daylight-saving gaps and
overlaps. Every registration's first fire is strictly after the wheel is built: a reload never catches up a missed interval and
does not immediately replay a schedule that already passed today. If the server stalls, a due timer fires once on the next wake;
its next deadline is advanced past the current time or tick. This prevents a backlog storm and keeps timers anchored to the
intended cadence.

`each_player` snapshots the online player collection when the timer is due. The wheel enqueues one run per player and admits at
most 32 player tasks on each global tick; the remaining players are spread over later global ticks and each action hops through
the entity scheduler. At most 640 admitted runs are waiting in the global queue **or already queued on an entity scheduler**.
One timer wave is admitted whole or refused whole: when a snapshot would cross the bound, no player from that wave is queued and
one `MTR-0129` reports the wave and run count. This is fail-soft rather than unbounded catch-up; an admitted run keeps the exact
single semantics of the timer and player snapshot. A reload cancels the live handles, clears the queue and advances the
generation, and a player who leaves before their turn is ignored.

A daily timer passes its logical deadline through the dispatch. Therefore a Monday 06:00 occurrence first observed on Tuesday
still builds Monday's `days` filter fact even if the player run waits in the fan-out queue until later. A `server` run has
`server.*` and persistent variables but deliberately no `player.*`, `location.*` or `world.*`. The `player`, `location` and
`world` entries on `every` are conditional context variables in registry self-description: the code-mode SDK guarantees them only
when `for` includes `each_player`; the compiler applies the same filter-aware root set at load.

**Budgets.** A registration is one interval/subject or one `at` value of one rule. The first 128 registrations in deterministic
authored rule order are scheduled per pack; duplicate interval/audience pairs and duplicate `at` values collapse to one distinct
registration. Excess registrations stay in the authored rule and are not scheduled. One `MTR-0128` warning names the pack, its
total, the first 128 retained and the number omitted. A single global wheel remains responsible for every pack; the 640-run
fan-out bound and the 32-per-tick admission rate keep dynamic registration and online-player counts from growing a queue without
limit.

**Traceability.**

| Decision or rule | How this primitive applies it |
|---|---|
| D12, master §3.1 | `every` and `schedule` name timing capabilities and work with any rule flow and any online player; they do not implement announcements, mana or resets |
| D1, D6, master §5.6 | the timer is YAML on a rule document and rebuilds from the normal shadow swap; no timer state is stored as editor-only content |
| D5 | both are trigger registry entries with typed filters and context |
| D8 | one Folia global wake-up advances the wheel; player context and player actions cross only through the entity scheduler |
| D6, fail-soft | a bad timer skips its rule, a wheel callback cannot stop the wheel, and the over-budget tail is diagnosed without losing the live pack |
| plugin.md §9 | the 128-per-pack registration cap and 32-player fan-out cap are explicit |

**Verification.** `TimerIntervalTest`, `TriggerFiltersTest`, `WorldTriggersTest`, `ScheduledTriggerPlanTest` and
`TimerWheelTest` pin the schema, budget, a pack with no timers, tick cadence, fake-clock drift, daily days and the one global
task, including logical-date drift, authored cross-type order, duplicate collapse and bounded whole-wave fan-out overload.
`PaperScheduledTriggersIT` enables the `timers` fixture's one-second server timer and reads the incremented server variable.
QA `timers.server-variable-increments` observes the corresponding broadcast in a real client. The benchmark corpus promotes T26
to pass; T20 keeps only VIEW, while F5 keeps its item-held context gap.

**Not built, on purpose.** Item or while-held timers; they need keyed state and an item subject from another rule host, so
`equip` and #333 remain their home. A `tick` trigger, per-player wall schedules, cross-time-zone schedules and timer persistence
across restart/reload are not part of this primitive. Rebuilding from the live clock is intentional.

---

## 45. Decisions taken in #334 (custody holders and atomic transfer)

`holder:` is an input-layer parameter, not a view or feature. Omitting it compiles to the
player holder and preserves existing bytes and runtime behavior. Keyed holders use the
rendered canonical key in the player's own PDC; stack holders use the #333 `StackRef`'s
stable id and account bytes, reject stale replacements, and restore only while the handle
is live. `best_effort` does not admit a holder: that request is skipped at load so a
crash-safe setting cannot silently become the journal.

`custody.transfer` is registered as a guarding action with either an exact
`source: {holder, entry}` or `source: {slot}`, plus `destination: {holder, slot}`. It
prepares `TRANSFERRING` + `RESERVED`, checkpoints each owner's compound, appends one
`TransferIntent`, commits both owners on their threads, checkpoints both commits and
appends `TransferDone`. `await: decided|saved` gates the following effect at the same
boundary as `custody.consume`. Reusing an id is idempotent. It never implements transfer
as two consumes. Stack sources/destinations are refused explicitly; stack PDC has no
acknowledged save.

The primitive passes U1–U5: names capability, works for player/keyed addresses, composes
in ordinary flow content, is generated into the editor/SDK by self-description, and has
model, codec, property, Paper kill-gate and QA coverage. The complete safety fact,
state table and alternatives are `gui-input-durability.md` §15.
