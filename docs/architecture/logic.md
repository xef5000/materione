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
| `InputMethod` | `prompt` (§6.6) | chat, sign, anvil | dialogs (#6) |
| `ListSource` | the `list` component (§6.7) | online players, a pack's items, a variable list, static `entries:` | addon sources |

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
behavior/ dev.materione.behavior.logic     (#207) LogicLookup, RegistryLogicLookup, MapLogicContext, EngineExpressions,
                                            BuiltinTriggers, StepKinds, VocabularyBuilder, LogicIds
behavior/ dev.materione.behavior.flow      (#208) Step (sealed), Condition (sealed), FlowCompiler, StepPath, one record per
                                            step and condition kind; RuleCompiler, StepCompiler, ConditionCompiler, FlowSet
behavior/ dev.materione.behavior.run       (#208) FlowInterpreter (+ ControlSteps), FlowRun, Frame, RunBudget, TraceEvent,
                                            TraceSink, RunScheduler (port), RunRegistry, RunReporter
behavior/ dev.materione.behavior.expr      + FunctionCall node, function registry binding
content/ dev.materione.content.gui.logic   (#214) ClickMap, ClickMapCompiler, GuiRules, GuiRuleChecker; (#215) OpenSpec;
                                            (#216) dynamic-layer analysis
content/ dev.materione.content.variable    VariablesType (the `variables` content type)
plugin/  dev.materione.plugin.logic        LogicService (wires registries ↔ interpreter ↔ scheduler), GatewayRunScheduler, LogicRegistryImpl,
                                            BuiltinLogic (the built-ins, registered through the public API)
plugin/  dev.materione.plugin.logic.action    one class per action (≤ ~150 lines each)
plugin/  dev.materione.plugin.logic.support   shared action helpers (Quantity, ItemRef, InventoryPlan, Stacks); not actions (§17)
plugin/  dev.materione.plugin.logic.condition one class per condition
plugin/  dev.materione.plugin.logic.variable  PdcVariableStore, ServerVariableStore, VarCommand
plugin/  dev.materione.plugin.logic.action.gui (#214) OpenGuiAction, CloseGuiAction, BackGuiAction, RefreshGuiAction
plugin/  dev.materione.plugin.gui          (#214) GuiStack (the wiring), GuiBridge (how the actions reach it), GuiRun, Viewer,
                                            GuiLookup, GuiMenu, GuiRuntime (the API facade), RuntimeGuis, GuiListener
                                            (the click policy table)
plugin/  dev.materione.plugin.gui.session  (#214) GuiSession, GuiSessions, SessionHistory (back stack), CloseReason
plugin/  dev.materione.plugin.gui.open     (#214) GuiOpener, GuiLifecycle, OpenRequest, OpenResult, OpenGate;
                                            (#215) OpenCommandRegistrar, ArgParser
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
`MTR-0073` (§24); #218 took `MTR-0074` to `MTR-0076` (§25); the next free code is `MTR-0077`.
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
| Non-chest containers | `container:` (gui.md §8), with click-kind mapping per container. |
| Cross-resource flow libraries | `call` already takes a namespaced id. |
| Loops | A `repeat` step with a hard iteration cap, if real menus need it. |
| Bedrock forms (#24) | A Geyser-side renderer of the same GUI and flows. |
| Importing incumbent menus (#17) | Map DeluxeMenus and CommandPanels YAML onto §1's constructs. |
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
18. **Budgets.** Steps per run: every step *visited* counts, branches and calls included (1,000). Parked runs
    per player count `wait` and `delay:` parks only (64); a thread hop is not a wait and does not count, so a
    burst of Folia clicks is not refused. A player at the limit has a *new* run refused at start; a park that
    would cross it aborts. Call depth is also enforced at run time (16) although the compiler already bounds
    it. Each breach is `MTR-0059`.
19. **Cancellation by owner key.** `RunOwner(playerKey, anchor, cancelKey)`. `BehaviorEvent.withCancelKey(key)`
    marks the runs an event starts; `FlowInterpreter.cancel(key)` cancels them: a parked run's pending
    resume is cancelled, a running one ends at its next step boundary. A run with a key is tracked from
    its start (so it is reachable even while running, which `stop: {cancel_session_runs: true}` uses); other
    runs are tracked only while parked, so the common click costs no bookkeeping. `cancelPlayer` is called
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
    **The `balance()` function cannot declare a thread:** an expression function has no threading hook, so a
    balance read is evaluated on the thread of the step that evaluates it. For an XP currency that is the
    player's region (correct); for a Vault or command currency the read is safe on Paper and needs the global
    thread on Folia. Adding a threading hook to `ExpressionFunction` is the follow-up that closes this.
    **Folia gap:** a `custom` balance read through PlaceholderAPI reads player data on the global thread. That is
    correct on Paper and a known gap on Folia.

**Deferred**

26. Pack-first bare-name resolution and load-time currency validation (items 8 and 9); a threading hook for
    `balance()` and the PlaceholderAPI player-data read on Folia (item 25); the `variable` and `item`
    integrations (item 13); `has_money` (#210,
    which takes `currency:` too); no per-player target and no duration on the permission actions; `run_command
    as: op` still uses the operator flag, not the permission provider (#208).
27. **For #211:** the PlaceholderAPI expansion must expose `%materione_balance_<pack>_<id>%` (the raw balance)
    and `%materione_balance_<pack>_<id>_formatted%` (through `format_currency`). The expansion is #211's work.
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
   so `vars.player` always means the scope. A name written with one of them is `MTR-0063` (a load error).
4. **Reads.** `vars.x` and `vars.local.x` are the run's locals (M2 unchanged). `vars.session.x` is the GUI
   session's variable, read through the `VariableFacets.SESSION` facet (absent: empty). `vars.player.<pack>.<key>`
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
   flows, so a rule may name a variable declared in a file that sorts after it. `LogicLookup.variable(id)` defaults
   to empty, so a lookup with no declarations rejects every qualified persistent name.
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
    `GuiSessionVariables` facet into every GUI run, so `vars.session.x` and `gui.session.x` are one map. The `balance` placeholders for
    currencies (#233) are reserved and resolve to null; #233 adds its branch beside `var_` and does not touch the
    resolver's grammar.

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
   compares and ignores. This is what lets a title change re-open a window without ending the session.

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
| #217 overlays | The title is assembled per GUI by the compiler; `WindowHost.reopen` is the title-change path. |
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
`plugin.gui.open` (the gate, the commands, the compile), `content.gui.logic` (the block's reader) and
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
    of the previous reload is logged as `MATERIONE-OPEN-TREE reload-deferred` and coalesced into the next swap after the
    window. A deferred change therefore applies at the next swap after the window, or at a restart if no swap comes; the
    registrar does not schedule a timer of its own. An alias the reload does not put in the tree is `MTR-0069`, and no
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
2. **Only item layers honour `visible_if` in this build.** A raster or text layer (`panel`, `button`, `slots`,
   `image`, `paint`, `text`) is drawn into the background or the title, which cannot change while a window is open
   until overlay glyphs exist (§6.4, #217). A `visible_if` on one of them skips that layer (`MTR-0052`) with the
   reason in its message, rather than shipping a layer that silently ignores its condition. #217 lifts the
   restriction. This is where the design's "every layer" is narrower than written.
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

- Raster and text `visible_if` and their animation: #217 (overlay glyphs, §6.4).
- `item-sources` in the behaviour vocabulary (`VocabularyBuilder`) and the editor's palette for them: with the Logic
  tab (#223, #224). The studio's layer inspector reads the new fields from the self-description, but no inspector
  control for `visible_if` or `frames` is designed here.
- The click policy for input slots: #218.

---

## 25. Decisions taken in #218 (G5: input slots, gui.slot_change, player_inventory: locked, click-policy rows)

#218 ships §6.5, including the crash journal (decision 12). The code is in `plugin.gui.input` (`SlotPolicy`,
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
   mechanism. This is flagged for the owner.
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
   gets them first; what does not fit drops at their feet. A re-open (a title or size change) carries them to the
   new window, and a rewrite keeps them in slots that are still inputs and returns the rest. Nothing is deleted unless
   a flow says so (`gui.slot_item: {op: clear}`).
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
    follow-up to that extension: #247 and the dependent consume-incoming trash action (#248, used by #222) remain
    blocked. Existing input behavior stays enabled and best-effort; no crash-safety or transactional-consumption
    guarantee is introduced. Live QA of normal input transfers cannot substitute for a process-kill checkpoint test.
13. **The `layer:` filter of `gui.slot_change` is not yet checked against input layers.** A rule that names a non-input
    layer loads and never runs. `GuiRuleDiagnoser` checks that the layer exists, not that it is an input.
14. **The QA entries (`guilogic.input-slots`, the `trade` fixture) are written but not run here.** They need a real
    client and are blessed on main.

**Diagnostics.** `MTR-0074` (accept does not compile), `MTR-0075` (static item under an input slot) and `MTR-0076`
(an input-slot setting that cannot apply), documented in plugin.md §5. The next free code is `MTR-0077`.

**Seams this leaves for later issues.** `GuiStack.inputClicks()` is the input collaborator the router consults;
`GuiBridge.Services.inputs()` is the port `gui.slot_item` reaches the window through (`InputSlotPort`). `slot_has` (a
condition, in `BuiltinLogic.conditions`, §22's convention) reads the window through the `SlotPeek` port
(`plugin.logic.support`), which `BuiltinLogic.register` builds from the same bridge, as `in_gui` builds `OpenGuis`.
The trash recipe (#222) is the one place that will extend it.

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
    condition stacks are #224.
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

- The node inspector, typed fields and condition stacks (#224); the Script tab, Design ↔ Logic linkage and the
  recipes panel (#225); the live trace (#226).
- Multi-select, copy and paste, and moving a single node out of the middle of a chain (the chain-lift above moves
  a node and what follows it).
- Dragging a flow entry (a named flow has no position) and creating a `call`'s target flow from the node.
