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
| Size and container type | `size`, `inventory_type` (18 types) | `rows` or a type | `rows` (chest) in v1. Other containers stay a gui.md §8 seam (later). |
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
| Actions: economy, exp, permissions | `[give/takemoney]`, `[give/takeexp]`, `[give/takepermission]` | `[vault]`, `[xp]`, `[grant]` | `give_money`/`take_money`, `give_exp`/`take_exp` and `give_permission`/`take_permission`, through provider SPIs (§4.5). |
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

Cross-server variables use the storage interface of master §8.3. Adapters are #25, and nothing in
this doc assumes a single server beyond the default adapter.

### 3.2 Declared, typed variables

`player` and `server` variables are **declared** in a pack's `variables/` content type:

```yaml
# content/packs/shop/variables/shop.yml
coins:      { scope: player, type: number, default: 0, description: "Shop coins" }
daily_claimed: { scope: player, type: boolean, default: false }
season:     { scope: server, type: string,  default: "spring" }
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
- **Expressions.** `vars.local.x`, `vars.session.x`, `vars.player.shop.coins` and
  `vars.server.shop.season`. Bare `vars.x` keeps meaning local, so M2 behaviour is unchanged.
- **PlaceholderAPI.** The expansion provides `%materione_var_<pack>_<key>%`,
  `%materione_var_<pack>_<key>_<player>%`, `%materione_gui_open%` and `%materione_gui_last%`.
- **Admin.** `/materione var get|set|list <player> [<var>] [<value>]`, permission
  `materione.var`.
- **API.** `MaterioneAPI.logic().variables()` (§8).

---

## 4. Extension SPIs (`dev.materione.api.logic`)

Every vocabulary item is a registry entry implemented against the public API. The built-ins
register exactly the way an addon does, as the mechanics registry already proves (mechanics.md).

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
  (`ExpressionFunction`), so addons can add them.

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

### 4.4 Item sources

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
| `EconomyProvider` (balance, has, withdraw, deposit) | `has_money`, `give_money`, `take_money` | none (the actions fail with "no economy provider", and the editor shows the action as unavailable) | Vault |
| `PermissionProvider` (grant, revoke, temporary grant) | `give_permission`, `take_permission`, `run_command as: op` | Bukkit attachments, for the session only | Vault, LuckPerms |
| `ServerTransfer` | `connect` | the 1.20.5+ transfer packet when configured with a host | BungeeCord or Velocity plugin messaging |
| `InputMethod` | `prompt` (§6.6) | chat, sign, anvil | dialogs (#6) |
| `ListSource` | the `list` component (§6.7) | online players, a pack's items, a variable list, static `entries:` | addon sources |

Providers are resolved at use, not cached at load, so installing Vault later only needs a reload.
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
- Item, block and furniture rules run through the interpreter unchanged. The golden behaviour
  tests and the `behavior.*` QA catalog entries are the regression net, and they must pass
  untouched.

---

## 6. GUI runtime

### 6.1 Opening

```yaml
shop:
  rows: 6
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
   `materione.gui.bypass.<pack>.<id>`.
5. **Fire `gui.opening`.** The event is cancellable, so a flow may `stop: {cancel: true}`.
6. **Create the `GuiSession`.**
7. **Render (§6.3) and open.**
8. **Fire `gui.open`.**

**Command registration.** Commands register through Paper's Brigadier lifecycle and are re-synced
to clients after a reload. An argument mismatch must not reproduce #96 (the client's synced tree
rejecting typed arguments), and §13 assigns that check explicitly.

### 6.2 Triggers fired by the GUI runtime

| Trigger | Filters | Context (beyond player and server) | Cancellable |
|---|---|---|---|
| `gui.opening` | n/a | `gui.*` | yes (the open does not happen) |
| `gui.open` | n/a | `gui.*` | no |
| `gui.close` | `reason: player\|action\|replaced\|quit` | `gui.*` | no |
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
`gui.click` rule matching the click kind. One map lookup per click, no geometry at runtime.

### 6.3 Dynamic content

- **Visibility.** `visible_if: <condition>` is a common layer field, like `visible`. Within a slot,
  the topmost visible layer wins. That covers DeluxeMenus' priority and CommandPanels' first-match
  order, and layer order *is* priority, so authors see it.
- **Templated item overrides.** On item layers, `name`, `lore` (`lore_mode: replace|append|prepend`),
  `amount` and `glint` are templates, and `item:` is an item source (§4.4).
- **Animation.** `frames: {every: <ticks>, items: [sources...]}` on item layers.
- **Dynamic detection.** A layer is *dynamic* when it has `visible_if`, a template, a templated
  source or `frames`. The compiler records this, and refresh re-evaluates **only** dynamic layers.
- **`refresh: <ticks>`** on the GUI sets the clock (default off; `gui.refresh` forces a render).
- **The render model.** `SlotRenderer` produces the session's `RenderedSlots`. `RenderDiff` writes
  only changed slots, which keeps a 54-slot, 20-tick refresh within budget.

### 6.4 Conditional raster and dynamic title text

The background is one flattened glyph (gui.md §4), so raster layers cannot toggle at runtime.
**Decision:** a raster layer (`panel`, `image`, `paint`, `button`) with `visible_if` or `frames`
does not flatten. It compiles to its **own overlay glyph**: its own codepoint, from the
`gui-background` allocation. The title assembler then includes or omits it per render, at its
place in the layer order.

- A title change re-sends the title (`InventoryView#setTitle`) without re-opening the window. This
  needs verification on 1.21.4 and 26.x as part of the issue.
- The cost is one codepoint per dynamic raster layer; the block has about 14k.
- This gives tabs, toggles, selected states and progress art, which neither incumbent can do.

Text layers with templates, and conditional text, are re-laid out per render through the same
title assembler. The default-font width table (#202) makes arbitrary text measurable.

### 6.5 Input slots and the player's inventory

- **Input slots.** A `slots` layer may set `input: true` (with an optional `accept: <condition>` on
  `slot.item`). Its slots accept the player's items, and moves into or out of them fire
  `gui.slot_change`.
- **Returning items.** On close, items left in input slots go back to the player (or drop at the
  player's feet when the inventory is full). Nothing is ever deleted unless a flow says so
  (`gui.slot_item: {slot, op: clear}`).
- **The trash recipe.** "Trash slot" is a recipe that clears an input slot on `gui.slot_change`.
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

### 6.7 Lists and pagination

`list` is a component with target `SLOT`:

```yaml
- id: players
  type: list
  slots: { from: 10, to: 34, skip_columns: [0, 8] }   # or an explicit slot list
  source: { type: online_players, sort: name }         # ListSource SPI (§4.5)
  filter: "${contains(lower(entry.name), lower(vars.session.search))}"
  entry:                                               # per-entry item template, entry.* in scope
    item: { head: { player: "${entry.name}" } }
    name: "<yellow>${entry.name}"
    lore: ["<gray>Click to teleport"]
```

- **State.** Page state is per session per list (`gui.page.<list>`).
- **Paging actions.** `gui.page: {list, to: next|previous|first|last|<n>}`. It fires `gui.page`.
- **Context.** Clicks on a list slot carry `entry.*` in context, so a single rule handles every
  entry.
- **Ordering.** Sources sort and filter **before** paging, and page count is exposed for
  "Page 2/5" text.
- **Large sources.** A source that is expensive (for example all items of all packs) declares
  itself `cached`, and the session snapshots it on open and on `gui.refresh`, not on every tick.

---

## 7. The studio's Logic tab

### 7.1 Three views of one file

The GUI studio gains **Design**, **Logic** and **Script** tabs. All three edit the same resource
through the same authoring API (§8):

- **Design** is gui.md §6.
- **Logic** is the node canvas.
- **Script** is the resource's `behavior:`, `flows:` and `open:` YAML, with live validation.

There is no editor-only model (D1). The undo history is shared across tabs.

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
- **Animation.** The current trace (§7.6) animates the edges.
- **Theme.** Nodes use the studio's Minecraft-flavoured panels (gui.md §6). Field editors are typed
  from the self-description: item pickers reuse the item browser, sound pickers the sound catalog,
  and variables are a dropdown of declared variables.
- **Template fields.** A template field autocompletes `${` from the trigger's context schema (§2.6)
  and `%` from known PlaceholderAPI expansions.
- **Palette.** The palette is grouped as Triggers, then the action categories, then Conditions,
  Flow and Recipes. It is searchable (`/`), and it is entirely derived from the vocabulary op
  (§8). An unavailable provider greys out its actions with the reason.

### 7.4 Design ↔ Logic linkage

- **From Design.** Selecting a button, item or list layer offers "Add click logic". That creates a
  `gui.click` rule filtered to that layer and opens it in Logic.
- **On the canvas.** Trigger nodes show the layer's thumbnail and name, and hovering them
  highlights the layer on a mini preview.
- **In the layers panel.** A layer with logic shows a small bolt badge. Deleting a layer with
  rules asks whether to delete or detach them.
- **Across tabs.** Validation diagnostics carry step paths and are drawn on the node in Logic and
  on the layer in Design.

### 7.5 Recipes panel

Recipes (§4.6) are listed with a one-line description, and inserting one expands it server-side.
The v1 recipes are:

- Pay to receive
- Trash slot
- Close button
- Welcome message
- Confirm dialog (a sub-GUI with yes and no)
- Paged player list
- Toggle setting (a player boolean variable with an on/off overlay)
- Server selector (`connect` plus an online-count placeholder)
- Back button

### 7.6 Live trace (debugging)

When the studio is connected and the author clicks in game, the run's trace (step paths and
outcomes) streams to the studio. It is opt-in per session and per GUI, rate-limited and capped.
Executed edges light up, and a failed action shows its reason on the node. Nothing in either
incumbent compares to this, and it falls out of §5.2's step-boundary events at little cost.

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
| Read and write player or server variables | `MaterioneAPI.logic().variables()` (needs `materione.var`) | `logic.variables.get/set` (admin-gated) |
| Open a GUI with args | `RuntimeApi.gui().open(player, id, args)` | `runtime.openGui` (gains `args`) |
| Live trace | `MaterioneAPI.logic().traces().subscribe(filter)` | `logic.trace.subscribe` (stream) |

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
behavior/ dev.materione.behavior.flow      Step (sealed), Condition (sealed), FlowCompiler, StepPath
behavior/ dev.materione.behavior.run       FlowInterpreter, FlowRun, RunBudget, Trace, RunScheduler (port)
behavior/ dev.materione.behavior.expr      + FunctionCall node, function registry binding
content/ dev.materione.content.gui.logic   OpenSpec, ClickMap compiler, dynamic-layer analysis, GuiRules
content/ dev.materione.content.variable    VariablesType (the `variables` content type)
plugin/  dev.materione.plugin.logic        LogicService (wires registries ↔ interpreter ↔ scheduler)
plugin/  dev.materione.plugin.logic.action    one class per action (≤ ~150 lines each)
plugin/  dev.materione.plugin.logic.condition one class per condition
plugin/  dev.materione.plugin.logic.variable  PdcVariableStore, ServerVariableStore, VarCommand
plugin/  dev.materione.plugin.gui.session  GuiSession, GuiSessions, SessionHistory (back stack)
plugin/  dev.materione.plugin.gui.open     GuiOpener, OpenCommandRegistrar, ArgParser
plugin/  dev.materione.plugin.gui.render   SlotRenderer, TitleRenderer, RenderDiff, RefreshClock
plugin/  dev.materione.plugin.gui.input    ClickClassifier, ClickRouter, SlotPolicy, InputSlots
plugin/  dev.materione.plugin.gui.list     ListPager, built-in ListSources
plugin/  dev.materione.plugin.gui.prompt   ChatInput, SignInput, AnvilInput
integrations/ vault, luckperms, placeholderapi (expansion), headdatabase, …  (soft-depend adapters)
webui/   src/lib/logic/                    graph projection, layout, edit commands, vocabulary client
webui/   src/lib/logic/canvas/             node canvas, ports, wires, palette, inspector
```

Rules:

1. **Listeners are adapters.** A Bukkit listener converts the event into a normalized interaction
   (`GuiClick`, `GuiSlotChange` and so on), hands it to one collaborator, and returns. No
   listener holds policy, rendering or action logic. **Build check:** ban-test rule R10: classes
   annotated `@EventHandler`-bearing in `plugin.gui..` and `plugin.logic..` may not depend on
   `plugin.logic.action..` or `plugin.gui.render..` directly.
2. **One action, one class; one condition, one class.** Each registers through the public API.
   Actions do not depend on each other. **Build check:** R11 forbids dependencies between classes
   in `plugin.logic.action..`.
3. **The engine is Bukkit-free.** `behavior` and `content` keep their existing bans (R1–R8). The
   interpreter talks to the world only through `LogicContext` facets and the `RunScheduler` port,
   so it is unit-tested without a server.
4. **Size guard.** A build test fails any main-source class over **400 lines** in the new packages
   above. Splitting is cheaper than an exception. Generated and test code is exempt.
5. **Typed parse at load.** No action or condition reads raw maps at run time (§4.1).
6. **Every action and condition ships with** a self-description, a unit test of parse
   (accept and reject), a unit test of execute or test against a fake context, and, when
   player-visible, a QA catalog entry (CLAUDE.md).

---

## 10. Diagnostics

The implementing PRs assign codes from `MTR-0056` upwards and document them in plugin.md §5. Each
code below names the class of problem, and every one carries a step path where one applies.

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
| Non-chest containers | `container:` (gui.md §8), with click-kind mapping per container. |
| Cross-resource flow libraries | `call` already takes a namespaced id. |
| Loops | A `repeat` step with a hard iteration cap, if real menus need it. |
| Bedrock forms (#24) | A Geyser-side renderer of the same GUI and flows. |
| Importing incumbent menus (#17) | Map DeluxeMenus and CommandPanels YAML onto §1's constructs. |
| Item and block rules in the node editor | The canvas is resource-agnostic (§8, `AuthoringApi.logic()`). The item studio's rule editor can switch to it. |

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

The epic is #189. Each row is one issue and one PR. Columns: **Model** is the delegation target
(Haiku for regular implementation, Sonnet for API, engine or web-UI design); **Needs** lists the
rows it waits for.

| # | Issue | Model | Needs |
|---|---|---|---|
| L1 | `api.logic` SPI (actions, conditions, triggers, functions, `LogicContext`, facets, outcomes) + vocabulary v2 + port the six built-ins | Sonnet | n/a |
| L2 | Flow compiler and interpreter (steps, resumable runs, budgets, step paths, trace events), with M2 rules migrated | Sonnet | L1 |
| L3 | Expression function calls + the function registry + the built-in functions | Haiku | L1 |
| L4 | Built-in conditions (§4.2) | Haiku | L1, L2 |
| L5 | Variables: the `variables` content type, scopes, stores, `set_var` v2, PlaceholderAPI expansion, `/materione var` | Haiku | L2 |
| L6 | Built-in player, world and server actions (§1 rows) | Haiku | L1 |
| L7 | Provider SPIs + Vault, LuckPerms and transfer adapters + economy and permission actions | Haiku | L1 |
| G1 | GUI sessions, `GuiOpener`, click map, click router, GUI triggers, GUI actions (open, close, back, refresh) | Sonnet | L2 |
| G2 | Open commands (Brigadier), typed args, requirement and deny, bypass, plus a #96 check | Haiku | G1 |
| G3 | Dynamic slots: `visible_if`, templated overrides, item sources (heads, `player_slot`, external), `frames`, refresh clock, render diff | Haiku | G1 |
| G4 | Overlay glyphs for dynamic raster + dynamic title text + `setTitle` research on 1.21.4 and 26.x | Sonnet | G1, #202 |
| G5 | Input slots, `gui.slot_change`, `player_inventory: locked`, the click-policy rows | Haiku | G1 |
| G6 | `list` component + `ListSource` SPI + paging actions + `gui.page` | Sonnet | G1, G3 |
| G7 | `prompt` + `InputMethod` (chat, sign, anvil) + list search | Haiku | L2, G6 |
| A1 | `AuthoringApi.logic()` (edit, validate, instantiate), `GuiEdit.SetOpen`, web ops, the `logic` capability, gates and goldens | Haiku | L2 |
| A2 | The recipe registry + the v1 recipes as data | Haiku | A1 |
| W1 | Logic tab: graph projection, auto layout, canvas, ports and wires, palette from the vocabulary, structural edit rules | Sonnet | A1 |
| W2 | Node inspector: typed fields, template autocomplete, condition stacks, variable pickers | Sonnet | W1 |
| W3 | Design ↔ Logic linkage + Script tab + Recipes panel | Sonnet | W1, A2 |
| W4 | Live trace, end to end (API stream + canvas animation) | Sonnet | W1, L2 |
| Q1 | Parity showcase GUIs (shop, server selector, paged player list, settings toggles) + QA catalog entries + docs | Haiku | G1–G7 |

Rows that can run in parallel once their dependencies land:

- L3, L4, L5, L6 and L7, after L1 and L2.
- G2, G3 and G5, after G1.
- A1, alongside the G rows.
