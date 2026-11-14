# The agent benchmark: 29 feature tasks, written as code-mode programs (#320, D12, D13)

> **Scope.** [#320](https://github.com/xef5000/materione/issues/320) asks for a benchmark of 20 to 30 realistic feature tasks.
> [D12](../architecture.md#31-d12-in-full) makes that benchmark the regression suite of "primitives, not features", and
> [D13](../architecture.md#32-d13-in-full) says how a gap shows up: as a **type error** in the agent's program
> ([`codemode.md`](codemode.md) §9.2). This document is the benchmark, run **on paper**: the tasks (§2), the program each one needs
> against the SDK shape of codemode.md §3 with every line that fails on today's surface (§3), the tally of failures per missing
> primitive and the ranking that follows (§4), the answer to "is a separate logic DSL still needed" (§5), what writing 29 programs
> taught about the SDK itself (§6), and the sequence of M6.5 (§7).
>
> **Status.** **Design, docs only.** No SDK exists yet, so nothing here was type-checked by a machine. "Fails" means: the member the
> line uses is not in a registry today, so a generator that reads only self-description (codemode.md §3.1) cannot emit it. The
> evidence for "not in a registry" is [`primitives.md`](primitives.md) §4 plus the registration code it cites
> (`BuiltinTriggers`, `BuiltinLogic`, `VariableType`); §8 lists what was not verified. Code is **shape, not syntax**, as in
> codemode.md. When the SDK generator lands (#337), these programs become the corpus of codemode.md §11 and the tally of §4 is
> recomputed by CI instead of by hand.
>
> **Traceability.**
>
> | Decision or rule | How this doc applies it |
> |---|---|
> | D12 | A task that needs engine surface that does not exist fails on a named primitive (§3), never on a missing feature. The ranking of §4 proposes primitives only; each passes U1 to U5 or is not proposed. |
> | D13 | Every task is one or more one-shot TypeScript programs that stage authoring operations. No task is solved by a workaround that fakes a primitive in content (codemode.md §6 rule 2). |
> | D4, §5.6 | The stored behavior language stays the one ECA flow model, with one schema. §5 says why no second language is added. |
> | D1 | Every program's output is YAML in one pack. No task stores a program. |
> | D5 | Every proposed primitive is a registry entry with self-description, which is what makes it appear in the SDK with no SDK code. |
> | Roadmap M6.5 | "The primitives the slice needs are chosen by the benchmark, not by completeness": §4 is that choice. |

---

## 1. Method

1. **A task is a prompt a server owner would type**, in the style of #320's Bloodfang example. The 29 tasks cover #320's list
   (custom weapons, GUIs, progression, shops, mobs, furniture interactions) and D12's `/trade`.
2. **For each task, the program is written the way an agent would write it** against codemode.md §3: `m.pack`, typed `create`
   calls, `rules.add({on, if, do})`, `m.triggers`, `m.actions`, `m.conditions`, `m.expr`, `m.ref`, `m.presets`, `m.summary`.
3. **A line that uses surface no registry holds today is marked `✗`** with the TypeScript error the generated surface would give
   and a **gap id** (§4.1). A program with no `✗` **passes**: it type-checks against today's registries. Passing on paper is not
   passing in a dry run; that is the corpus's job once #337 and #341 exist.
4. **The strict rule (codemode.md §6 rule 2).** A task fails if the honest program needs a missing primitive, even when a
   workaround exists (a `run_command` to another plugin, a global variable used as a session map). A workaround that is itself
   a plain composition of shipped primitives is *not* a fake and counts as a pass (T16 is the example).
5. **Verdicts.** **Pass** (no `✗`), **Partial** (the feature's core passes; a stated clause fails), **Fail** (the core fails).

**What today's surface is** (from primitives.md §4, checked against the registrations):

| Kind | Shipped |
|---|---|
| Content types | `item`, `block`, `furniture`, `glyph`, `sound`, `painting`, `effect`, `recipe`, `gui`, `currency`, `variable`, `rule` (since #327) |
| Mechanics | `commands`, `food`, `durability`, `soulbound`, `backpack` |
| Triggers (live) | `click`, `break`, `place`, `interact`, `equip`, `unequip`, `drop`, `pickup`, `consume`, `damage` (durability), `death` (the carrier dies); since #327 `entity.hit`, `entity.kill`, `player.hurt`, `player.join`, `player.quit`, `player.respawn`, `player.death`; `gui.opening`, `gui.open`, `gui.close`, `gui.click`, `gui.click_outside`, `gui.click_inventory`, `gui.slot_change`, `gui.tick`, `gui.page` |
| Actions | `run_command`, `message`, `broadcast`, `actionbar`, `title`, `chat`, `sound`, `stop_sound`, `potion`, `teleport`, `cancel`, `give_item`, `take_item`, `give_exp`, `take_exp`, `set_var`, `prompt`, `custody.consume`, `give_money`, `take_money`, `give_permission`, `take_permission`, `connect`, `gui.open`, `gui.close`, `gui.back`, `gui.refresh`, `gui.page`, `gui.slot_item` |
| Conditions | `has_permission`, `has_money`, `has_exp`, `has_item`, `has_item_space`, `is_near`, `in_world`, `in_gui`, `var`, `slot_has` |
| Flow steps | action, `if`, `require`, `wait`, `stop`, `call` (same resource), `choose`; rule options `id`, `cooldown`, `charges`, `per`; action options `chance`, `delay` (there is no rule-level `chance`) |
| Variables | scopes `local`, `session`, `player`, `server`; types `number`, `string`, `boolean` |
| GUI | components `panel`, `image`, `paint`, `text`, `button`, `item`, `slots`, `list`; the `open:` block (commands, typed args, permission, requirement) |

---

## 2. The tasks

| # | Task (the prompt, shortened) | Area | Verdict | Fails on |
|---|---|---|---|---|
| T01 | **Bloodfang sword** (#320): custom model, bleed on hit scaled by level, XP from kills to level 5, sneak-right-click GUI with a 500-coin upgrade, `rpg.weapons` only | weapon, progression, GUI | Fail | STATE, TARGET |
| T02 | **`/trade <player>`** (D12): request, accept, shared two-player window, atomic swap, expiry | command, GUI, custody | Fail | CMD, STATE, VIEW, TARGET, CUSTODY |
| T03 | **Backpack**: a 3-row backpack item that keeps its contents | item | Pass (preset route) | by-hand route: CUSTODY |
| T04 | **Kits**: `/kits` menu with Starter (once) and Daily (every 24 h) | GUI, items | Partial | TIME |
| T05 | **Healing wand**: right-click heals 4 hearts, 30 s cooldown, tells the time left | ability | Partial | TIME |
| T06 | **Quest**: kill 10 zombies, get 100 coins and a trophy; progress in `/quests` | progression | Pass (#327) | |
| T07 | **Daily reward**: `/daily`, one claim per day, a streak that resets if a day is missed | GUI, progression | Fail | TIME |
| T08 | **Shop**: `/shop` with buy and sell for coins | GUI, economy | Pass | |
| T09 | **Warp menu**: `/warps` with spawn, mines, arena, and a link to the survival server | GUI | Pass | |
| T10 | **Welcome**: greet players on join; first join gets the starter kit and a broadcast | server | Pass (#327) | |
| T11 | **Golden berry**: food that gives regeneration and speed for 5 s | item | Pass | |
| T12 | **Lifesteal dagger**: heal the wielder for 20% of the damage dealt | weapon | Fail | VERB |
| T13 | **Excavator pickaxe**: mines a 3x3 area | tool | Fail | VERB |
| T14 | **Dash blade**: right-click launches the player forward with a particle trail, 5 s cooldown | ability | Fail | VERB |
| T15 | **Mob drop**: zombies have a 5% chance to drop a Rotten Heart | mobs | Pass (#327) | |
| T16 | **Crate**: a placed crate opened with a key gives one of five weighted rewards and announces rare ones | furniture | Pass | |
| T17 | **Leaderboard**: `/top` shows the ten players with the most kills | GUI, progression | Fail | QUERY |
| T18 | **Player vaults**: `/pv <1-3>` opens private storage that persists | GUI, custody | Fail | CUSTODY |
| T19 | **Vending machine**: a furniture that opens a shop when right-clicked | furniture | Pass | |
| T20 | **Mana and a fire wand**: mana regenerates to 100, shows as a HUD bar, the wand spends 20 to shoot a fireball | ability, HUD | Fail | TIMER, VIEW, VERB |
| T21 | **`/pay <player> <amount>`** | command, economy | Fail | CMD, TARGET |
| T22 | **Arena region**: entering `arena` gives the PvP kit, leaving removes it | integration | Fail | PROVIDER |
| T23 | **Rank-up**: `/rankup` pays 10 000 coins for the next rank and announces it | GUI, economy | Pass | |
| T24 | **Ruby ore**: a custom block that drops a ruby and 5 XP, 10% chance of a second ruby | block | Pass | |
| T25 | **Miner's heirloom**: a pickaxe with 2 000 durability that is kept on death | item | Pass | |
| T26 | **Announcements**: every 5 minutes broadcast the next of four tips | server | Fail | TIMER |
| T27 | **`/discord`**: prints the invite link | command | Fail | CMD |
| T28 | **Rules on first join**: a dialog with the rules and an Accept button; no accept, no play | dialog | Fail | VIEW |
| T29 | **Forge**: put a ruby sword and 3 rubies in, pay 200 coins, get the refined sword | GUI, custody | Pass | |

**13 pass, 2 are partial, 14 fail** after #327 (rules without a host and the entity and player triggers, logic.md §37): T06, T10
and T15 flipped from Fail, and the gaps RULE, TRIG-ENTITY and TRIG-PLAYER left the "Fails on" column of the eight other tasks that
listed them (T01, T12, T17, T20, T22, T26, T28, and T06's own). The count was 10, 2 and 17 when the page was written. The clock of
#328 is not re-checked here: T04, T05 and T07 keep the verdicts they had until that issue's author re-runs them against the real
shapes. "Fails on" lists what the honest program needs, not everything that would be nice.

**The follow-up set** (#320: "five follow-up change requests succeed without regressions"), applied to T01 after it is built:

| # | Follow-up | Program shape | Verdict |
|---|---|---|---|
| F1 | "Make bleed stack up to 3 times" | one variable held by the *struck entity*, one changed rule | Fail: STATE (entity holder) |
| F2 | "Show the level in the sword's lore" | one step that edits the held stack | Fail: VERB (stack edit) |
| F3 | "The upgrade costs 500 times the level" | one `setFields` on the button's rule | Pass once T01 passes |
| F4 | "Rename the permission to `rpg.bloodfang`" | `findReferences`, then `setFields` on each hit | Pass once T01 passes |
| F5 | "Level 5 swords emit red particles when held" | one rule on `equip` with a repeating particle | Fail: VERB, TIMER |

---

## 3. The programs

Conventions: `const p = m.pack("bench")`. A `✗` comment is the error the generated surface would give. Lines without one
type-check against today's registries. Summaries (`m.summary`) are omitted where they add nothing.

### T01 Bloodfang sword — Fail (STATE, TARGET)

The program is codemode.md §9.1. Its `✗` lines, restated against today's surface (the `entity.hit` and `entity.kill` triggers
pass since #327: an item holds them and `target.*` and `damage.*` are in their context):

```ts
const level = p.variables.create("bloodfang_level", { type: "number", holder: "item", default: 1 });
//                                                                    ^^^^^^ ✗ TS2353 'holder' does not exist; scope is
//                                                                      "player" | "server"                       [STATE]
sword.rules.add({
  on: m.triggers.entityHit,
  do: [ m.actions.potion({ effect: "wither", seconds: 5, amplifier: m.expr`${level} - 1`, target: m.target.struck }) ],
});                                  // ✗ TS2353 'target' does not exist in type 'PotionParams'                  [TARGET]
sword.rules.add({ on: m.triggers.entityKill, do: [ m.actions.setVar(xp, { op: "add", value: 10 }) ] });
```

What passes today: the item, the model reference, the two combat triggers, the GUI with its text and button, `has_money`/`take_money` under `require`,
the sneak-right-click rule (`click` with `player.sneaking`), the permission test (`has_permission` as each rule's condition).
The bleed is written with the shipped `potion` verb aimed at the struck entity, so it needs TARGET and not a new damage verb;
a *true-damage* bleed would add VERB. With level and XP held by the **player** instead of the stack the state lines pass, but
that is a different feature (two Bloodfangs share one level), so it is not offered as the solution.

### T02 `/trade <player>` — Fail (CMD, STATE, VIEW, TARGET, CUSTODY)

The program is codemode.md §9.2: five errors, one per need of primitives.md §3 (`p.commands`, keyed state with a TTL, a view
for two viewers, `message` to the other player, `transfer` between holders). Nothing of the two-player core passes today.
What an agent can offer instead: nothing honest. A single-player "offer window" is not a trade.

### T03 Backpack — Pass by the preset route; the by-hand route fails (CUSTODY)

```ts
p.items.create("traveler_pack", {
  material: "leather", display_name: "Traveler's Pack",
  mechanics: [ m.mechanics.backpack({ rows: 3, title: "Traveler's Pack" }) ],
});
```

Passes: `backpack` is a registered mechanic with a schema, so it is generated. It is also class F in the audit
(primitives.md §4.2) and becomes the first preset (#304); the program text changes from `mechanics: [m.mechanics.backpack(..)]`
to `m.presets.backpack(item, {..})` only if the owner picks a generic spelling (master §14.7 (i)). **The by-hand rule (R4) is
not met today**: an agent that writes the same behavior from primitives needs custody held by a stack:

```ts
const store = p.custody.create("pack_store", { holder: "item", slots: 27 });
//              ^^^^^^^ ✗ TS2339 'custody' does not exist on type 'PackWriter'                                   [CUSTODY]
```

### T04 Kits — Partial (TIME)

```ts
const claimed = p.variables.create("kit_starter_claimed", { scope: "player", type: "boolean", default: false });
const daily   = p.variables.create("kit_daily_at",        { scope: "player", type: "number",  default: 0 });
const kits = { starter: ["stone_sword", "bread"], daily: ["golden_apple", "experience_bottle"] };

const menu = p.guis.create("kits", { container: "chest_3", title: "Kits", open: { commands: ["kits"] } });
menu.layers.addButton({ slot: 11, item: "minecraft:stone_sword", name: "Starter kit", rules: [{
  on: m.triggers.guiClick,
  do: [ m.steps.if(m.conditions.var(claimed, "==", false), { else: [ m.actions.message("<red>Already claimed."), m.steps.stop() ] }),
        ...kits.starter.map(id => m.actions.giveItem({ item: `minecraft:${id}`, amount: 1 })),   // an authoring-time loop
        m.actions.setVar(claimed, { op: "set", value: true }) ],
}]});
menu.layers.addButton({ slot: 15, item: "minecraft:golden_apple", name: "Daily kit", rules: [{
  on: m.triggers.guiClick,
  if: m.expr`now() - ${daily} >= 86400`,
  //         ^^^ ✗ MTR (stage): unknown function 'now'; the expression functions have no clock                   [TIME]
  do: [ ...kits.daily.map(id => m.actions.giveItem({ item: `minecraft:${id}`, amount: 1 })),
        m.actions.setVar(daily, { op: "set", value: m.expr`now()` }) ],                           // ✗ same      [TIME]
}]});
```

The menu, the once-only kit and the item loop pass. "Every 24 hours" cannot be said: a rule's `cooldown` is a debounce in
milliseconds held in memory, not a persistent timestamp, and no expression function reads a clock. `/kit starter` as a direct
command (no menu) would add CMD; the prompt asked for a menu, and a GUI's `open:` block covers it.

### T05 Healing wand — Partial (TIME)

```ts
const wand = p.items.create("healing_wand", { material: "blaze_rod", display_name: "<green>Healing Wand" });
wand.rules.add({
  on: m.triggers.click({ detail: "right" }), cooldown: 30_000,
  do: [ m.actions.potion({ effect: "instant_health", amplifier: 1 }), m.actions.sound("entity.player.levelup") ],
  onCooldown: [ m.actions.actionbar(m.expr`"Ready in " + cooldown.remaining + "s"`) ],
  // ^^^^^^^^^ ✗ TS2353 'onCooldown' does not exist in type 'RuleOptions'; nothing exposes a cooldown's remaining time [TIME]
});
```

The ability and its cooldown pass. "Tells the time left" does not: a blocked run is silent and the remaining time is not a
context variable. With a clock this is one player variable and one expression, the same shape as T04.

### T06 Quest — Pass (#327)

```ts
const kills = p.variables.create("quest_zombies", { scope: "player", type: "number", default: 0 });
const done  = p.variables.create("quest_zombies_done", { scope: "player", type: "boolean", default: false });
const trophy = p.items.create("zombie_trophy", { material: "zombie_head", display_name: "<gold>Zombie Slayer" });

p.rules.create("quest_zombies", {                                      // a `rule` document: rules/quest_zombies.yml
  on: m.triggers.entityKill({ type: "zombie" }),                       // `entity.kill`, filter `type: zombie`
  if: m.conditions.var(done, "==", false),
  do: [ m.actions.setVar(kills, { op: "add", value: 1 }),
        m.steps.if(m.conditions.var(kills, ">=", 10), { then: [        // a `var` condition: the shape the harness runs
          m.actions.giveMoney({ amount: 100 }), m.actions.giveItem({ item: trophy, amount: 1 }),
          m.actions.setVar(done, { op: "set", value: true }), m.actions.title("<gold>Quest complete") ] }) ],
});
const menu = p.guis.create("quests", { container: "chest_3", title: "Quests", open: { commands: ["quests"] } });
menu.layers.addText({ slot: 13, text: m.expr`"Zombies: " + min(${kills}, 10) + " / 10"` });
```

State, reward, the progress menu and the counting rule pass. The rule is a `rule` document of its own (logic.md §37), and the
only line that changed from the paper version is the finish test: a variable is read by the `var` condition, which the harness
scenario (`PaperWorldEventsIT`) runs for a two-kill quest on Paper, rather than by an expression. The player is the killer;
a zombie that dies of fall damage fires nothing, and neither does a kill by a mob, which is correct for this task.

### T07 Daily reward — Fail (TIME)

```ts
const last   = p.variables.create("daily_day", { scope: "player", type: "number", default: 0 });
const streak = p.variables.create("daily_streak", { scope: "player", type: "number", default: 0 });
const menu = p.guis.create("daily", { container: "chest_3", title: "Daily reward", open: { commands: ["daily"] } });
menu.layers.addButton({ slot: 13, item: "minecraft:chest", name: "Claim", rules: [{
  on: m.triggers.guiClick,
  do: [ m.actions.setVar(m.local("today"), { op: "set", value: m.expr`floor(now() / 86400)` }),  // ✗ 'now'      [TIME]
        m.steps.if(m.expr`vars.today > ${last}`, { else: [ m.actions.message("<red>Come back tomorrow."), m.steps.stop() ] }),
        m.actions.setVar(streak, { op: "set", value: m.expr`vars.today == ${last} + 1 ? ${streak} + 1 : 1` }),
        m.actions.setVar(last, { op: "set", value: m.expr`vars.today` }),
        m.actions.giveMoney({ amount: m.expr`100 * min(${streak}, 7)` }), m.actions.guiRefresh() ],
}]});
```

Everything is shipped except the clock, and without the clock there is no feature.

### T08 Shop — Pass

```ts
const stock = [ { id: "diamond", buy: 250, sell: 100 }, { id: "iron_ingot", buy: 40, sell: 15 }, { id: "bread", buy: 5, sell: 1 } ];
const shop = p.guis.create("shop", { container: "chest_3", title: "Shop", open: { commands: ["shop"] } });
stock.forEach((s, i) => shop.layers.addButton({
  slot: 10 + i * 2, item: `minecraft:${s.id}`, name: `Buy ${s.buy} / Sell ${s.sell}`,
  rules: [
    { on: m.triggers.guiClick({ click: ["left"] }),
      do: [ m.steps.if(m.conditions.hasItemSpace(1), { else: [ m.actions.message("<red>Your inventory is full."), m.steps.stop() ] }),
            m.steps.require([ m.actions.takeMoney({ amount: s.buy }), m.actions.giveItem({ item: `minecraft:${s.id}`, amount: 1 }) ],
                            { else: [ m.actions.message("<red>Not enough coins.") ] }) ] },
    { on: m.triggers.guiClick({ click: ["right"] }),
      do: [ m.steps.require([ m.actions.takeItem({ item: `minecraft:${s.id}`, amount: 1 }) ],
                            { else: [ m.actions.message("<red>You have none to sell.") ] }),
            m.actions.giveMoney({ amount: s.sell }) ] },
  ],
}));
```

Three products are one loop and six staged rules. This is the showcase pack's `guis/shop.yml` shape (logic.md §34).

### T09 Warp menu — Pass

```ts
const warps = [ { name: "Spawn", at: [0, 64, 0], icon: "compass" }, { name: "Mines", at: [120, 40, -80], icon: "iron_pickaxe" },
                { name: "Arena", at: [-300, 70, 220], icon: "diamond_sword" } ];
const menu = p.guis.create("warps", { container: "chest_3", title: "Warps", open: { commands: ["warps", "warp"] } });
warps.forEach((w, i) => menu.layers.addButton({ slot: 10 + i * 2, item: `minecraft:${w.icon}`, name: w.name,
  rules: [{ on: m.triggers.guiClick, do: [ m.actions.guiClose(), m.actions.teleport({ x: w.at[0], y: w.at[1], z: w.at[2], world: "world" }) ] }] }));
menu.layers.addButton({ slot: 16, item: "minecraft:ender_pearl", name: "Survival server",
  rules: [{ on: m.triggers.guiClick, do: [ m.actions.connect({ server: "survival" }) ] }] });
```

### T10 Welcome — Pass (#327)

```ts
p.rules.create("welcome", {                                              // a `rule` document: rules/welcome.yml
  on: m.triggers.playerJoin,                                             // `player.join`, with `player.first_join` in context
  do: [ m.steps.if(m.expr`player.first_join`, {
          then: [ m.actions.broadcast(m.expr`"<yellow>Welcome " + player.name + " to the server!"`),
                  m.actions.giveItem({ item: "minecraft:stone_sword", amount: 1 }) ],
          else: [ m.actions.message(m.expr`"<gray>Welcome back, " + player.name`) ] }) ],
});
```

The paper version stored a `joined_before` variable to tell a first join from a later one. The trigger now says it
(`player.first_join` is true when the server has never seen the player), so the variable, and the `set_var` that kept it, are
gone. The shipped logic recipe `welcome_message` (primitives.md §4.10) is a flow fragment that now has a host to be instantiated
into. The harness scenario greets a fake player with `first=true`, and the QA catalog's rejoin test reads `first=false`.

A player who joins while the server is starting, before the first reload applies, fires nothing: a rule is resolved from the live
shadow at event entry (logic.md §37).

### T11 Golden berry — Pass

```ts
const berry = p.items.create("golden_berry", { material: "sweet_berries", display_name: "<gold>Golden Berry",
  texture: m.ref.asset("textures/item/golden_berry.png"), mechanics: [ m.mechanics.food({ nutrition: 4, saturation: 6 }) ] });
berry.rules.add({ on: m.triggers.consume, do: [
  m.actions.potion({ effect: "regeneration", seconds: 5, amplifier: 1 }), m.actions.potion({ effect: "speed", seconds: 5 }) ] });
```

### T12 Lifesteal dagger — Fail (VERB)

```ts
const dagger = p.items.create("lifesteal_dagger", { material: "iron_sword", display_name: "<dark_red>Leech" });
dagger.rules.add({
  on: m.triggers.entityHit,                                              // passes since #327: `damage.final` is in context
  do: [ m.actions.heal({ amount: m.expr`damage.final * 0.2` }) ],        // ✗ TS2339 'heal'                     [VERB]
});
```

`potion: instant_health` heals in fixed steps of 4 and cannot express "20% of the damage"; using it would change the feature.

### T13 Excavator pickaxe — Fail (VERB)

```ts
const pick = p.items.create("excavator", { material: "diamond_pickaxe", display_name: "Excavator" });
pick.rules.add({ on: m.triggers.break, do: [
  m.actions.breakBlocks({ around: m.where.event, shape: { box: [1, 1, 1] }, asPlayer: true, drops: true }) ] });
//          ^^^^^^^^^^^ ✗ TS2339 'breakBlocks' does not exist on type 'ActionCatalog'                           [VERB]
```

The trigger exists (`break` fires for the held item). No verb changes a block. The verb must act *as the player* so that
protection plugins are asked (D8's gateway, primitives.md §5.6), which is why it is a primitive and not a `run_command`.

### T14 Dash blade — Fail (VERB)

```ts
const blade = p.items.create("dash_blade", { material: "iron_sword", display_name: "<aqua>Dash Blade" });
blade.rules.add({ on: m.triggers.click({ detail: "right" }), cooldown: 5_000, do: [
  m.actions.velocity({ forward: 1.6, up: 0.3 }),                         // ✗ TS2339 'velocity'                 [VERB]
  m.actions.particle({ particle: "cloud", count: 20 }),                  // ✗ TS2339 'particle' (#21)           [VERB]
  m.actions.sound("entity.ender_dragon.flap") ] });
```

### T15 Mob drop — Pass (#327)

```ts
const heart = p.items.create("rotten_heart", { material: "rotten_flesh", display_name: "<dark_green>Rotten Heart" });
p.rules.create("zombie_heart", {                                          // a `rule` document: rules/zombie_heart.yml
  on: m.triggers.entityKill({ type: "zombie" }),                          // `entity.kill`, filter `type: zombie`
  do: [ m.actions.giveItem({ item: heart, amount: 1 }, { chance: 0.05 }) ],   // `chance:` is an option of the action
});
```

Written with `give_item` to the killer, which is shipped. "Drops on the ground" would add a drop-at-location verb (VERB);
the prompt does not force it. A drop when no player made the kill is loot-table territory (#11) and is out of the task.

**Correction found while re-checking against the real shapes.** The paper version put `chance: 0.05` on the rule. There is no
rule-level `chance`: it is an option of an action (`give_item: {...}` then `chance: 0.05`, logic.md §2.2), and it is the one that
exists. The program above is the real shape; T24's second ruby had the same slip and is fixed below.

### T16 Crate — Pass

```ts
const key = p.items.create("crate_key", { material: "tripwire_hook", display_name: "<gold>Crate Key" });
const rewards = [ { w: 50, item: "minecraft:iron_ingot", n: 8 }, { w: 30, item: "minecraft:gold_ingot", n: 4 },
                  { w: 15, item: "minecraft:diamond", n: 2 }, { w: 4, item: "minecraft:netherite_scrap", n: 1 },
                  { w: 1, item: "minecraft:nether_star", n: 1, rare: true } ];
const crate = p.furnitures.create("crate", { model: m.ref.asset("models/crate.json") });
let floor = 0;
crate.rules.add({ on: m.triggers.click({ detail: "right" }), do: [
  m.steps.require([ m.actions.takeItem({ item: key, amount: 1 }) ], { else: [ m.actions.message("<red>You need a Crate Key.") ] }),
  m.actions.setVar(m.local("roll"), { op: "set", value: m.expr`random(1, 100)` }),
  m.steps.choose(rewards.map(r => ({
    if: m.expr`vars.roll <= ${(floor += r.w)}`,                           // cumulative weights, computed at authoring time
    then: [ m.actions.giveItem({ item: r.item, amount: r.n }),
            ...(r.rare ? [ m.actions.broadcast(m.expr`"<gold>" + player.name + " found a Nether Star!"`) ] : []) ],
  }))) ] });
```

A weighted draw is one `random()` and a `choose`, and the arithmetic on the weights is done by the program, not by the server.
This is the pattern §5 relies on: **authoring-time computation replaces a runtime feature**. A first-class draw (#11, loot
tables) would be shorter, and nothing here needs it.

### T17 Leaderboard — Fail (QUERY)

```ts
const kills = p.variables.create("kills", { scope: "player", type: "number", default: 0 });
p.rules.create("count_kills", { on: m.triggers.entityKill, do: [ m.actions.setVar(kills, { op: "add", value: 1 }) ] });
// the counting rule passes since #327 (a kill by a player, any entity type: `entity.kill` with no `type:` filter)
const top = p.guis.create("top", { container: "chest_3", title: "Top kills", open: { commands: ["top"] } });
top.layers.addList({ slots: [10, 11, 12, 13, 14, 15, 16, 19, 20, 21],
  source: m.lists.ranking({ variable: kills, order: "desc", limit: 10 }),
  //              ^^^^^^^ ✗ TS2339 'ranking' does not exist on type 'ListSources'                               [QUERY]
  entry: { item: m.items.head({ player: m.expr`entry.name` }), name: m.expr`entry.name + ": " + entry.value` } });
```

The `variable` list source reads one list-valued variable; nothing queries one variable *across holders*, offline players
included.

### T18 Player vaults — Fail (CUSTODY)

```ts
const vault = p.guis.create("vault", { container: "chest_6", title: m.expr`"Vault #" + gui.args.n`,
  open: { commands: ["pv"], args: { n: { type: "integer", min: 1, max: 3 } }, permission: "vaults.use" } });
vault.layers.addSlots({ slots: m.range(0, 53), input: true, durable: "required",
  keep: { holder: m.holder.key(m.expr`"vault:" + player.uuid + ":" + gui.args.n`) } });
//^^^^ ✗ TS2353 'keep' does not exist in type 'SlotsLayer': a required input layer returns its items on close;
//                the only exit that keeps them from the player is custody.consume                              [CUSTODY]
```

The command, its typed argument and the durable slots exist. What is missing is a **holder that outlives the window**
(primitives.md §5.1, holder generalization): the escrow account is the player's own and is paid back on close.

### T19 Vending machine — Pass

```ts
const shop = m.ref.gui("bench:shop");                                     // T08's GUI; resolved at run time
const machine = p.furnitures.create("vending_machine", { model: m.ref.asset("models/vending_machine.json") });
machine.rules.add({ on: m.triggers.click({ detail: "right" }), do: [ m.actions.guiOpen(shop), m.actions.sound("block.note_block.chime") ] });
```

### T20 Mana and a fire wand — Fail (TIMER, VIEW, VERB)

```ts
const mana = p.variables.create("mana", { scope: "player", type: "number", default: 100, min: 0, max: 100 });
p.rules.create("mana_regen", {                                            // a rule document: passes since #327
  on: m.triggers.every({ seconds: 1, for: "each_player" }),               // ✗ TS2339 'every'                   [TIMER]
  do: [ m.actions.setVar(mana, { op: "add", value: 2 }) ] });
p.huds.create("mana_bar", { layers: [ m.hud.bar({ value: m.expr`${mana} / 100`, anchor: "hotbar_right" }) ] });
//^^^^ ✗ TS2339 'huds' does not exist on type 'PackWriter' (#7)                                                 [VIEW]
const wand = p.items.create("fire_wand", { material: "blaze_rod", display_name: "<red>Fire Wand" });
wand.rules.add({ on: m.triggers.click({ detail: "right" }), do: [
  m.steps.if(m.expr`${mana} >= 20`, { else: [ m.actions.actionbar("<blue>Not enough mana"), m.steps.stop() ] }),
  m.actions.setVar(mana, { op: "subtract", value: 20 }),
  m.actions.launch({ projectile: "small_fireball", speed: 1.5 }) ] });    // ✗ TS2339 'launch'                  [VERB]
```

The mana variable and the spend-or-refuse rule pass. An `actionbar` line on every tick would imitate a HUD; it is a
workaround and it still needs TIMER.

### T21 `/pay <player> <amount>` — Fail (CMD, TARGET)

```ts
p.commands.create("pay", {                                                // ✗ TS2339 'commands'                [CMD]
  args: { to: { type: "player" }, amount: { type: "number", min: 1 } },
  do: [ m.steps.require([ m.actions.takeMoney({ amount: m.expr`args.amount` }) ], { else: [ m.actions.message("<red>Not enough coins.") ] }),
        m.actions.giveMoney({ amount: m.expr`args.amount`, target: m.target.arg("to") }),   // ✗ TS2353 'target' [TARGET]
        m.actions.message({ text: m.expr`player.name + " paid you " + args.amount`, target: m.target.arg("to") }) ] });  // ✗ [TARGET]
```

`open.args` already has the `player` argument type, the Brigadier sync and `suggest:`; they are reachable only from a GUI.

### T22 Arena region — Fail (PROVIDER)

```ts
p.rules.create("arena_enter", {                                           // a rule document: passes since #327
  on: m.triggers.regionEnter({ region: "arena" }),                        // ✗ TS2339: no region provider kind  [PROVIDER]
  do: [ m.actions.giveItem({ item: "minecraft:iron_sword", amount: 1 }), m.actions.message("<red>PvP is on.") ] });
```

Regions belong to WorldGuard (#320's non-goals). Reaching them is a provider kind that does not exist (primitives.md §5.8).

### T23 Rank-up — Pass

```ts
const ranks = [ { id: "member", next: "knight", cost: 10_000 }, { id: "knight", next: "lord", cost: 50_000 } ];
const menu = p.guis.create("rankup", { container: "chest_3", title: "Rank up", open: { commands: ["rankup"] } });
menu.layers.addButton({ slot: 13, item: "minecraft:emerald", name: "Rank up", rules: [{ on: m.triggers.guiClick, do: [
  m.steps.choose(ranks.map(r => ({
    if: m.conditions.all([ m.conditions.hasPermission(`rank.${r.id}`), m.conditions.not(m.conditions.hasPermission(`rank.${r.next}`)) ]),
    then: [ m.steps.require([ m.actions.takeMoney({ amount: r.cost }) ], { else: [ m.actions.message(`<red>You need ${r.cost} coins.`) ] }),
            m.actions.givePermission(`rank.${r.next}`),
            m.actions.broadcast(m.expr`"<gold>" + player.name + ${" is now a " + r.next + "!"}`), m.actions.guiClose() ],
  })), { else: [ m.actions.message("<gray>You are at the highest rank.") ] }) ] }] });
```

`give_permission` reaches LuckPerms or Vault through the provider (logic.md §18). A rank ladder is an authoring-time table.

### T24 Ruby ore — Pass

```ts
const ruby = m.ref.item("sample:ruby");
const ore = p.blocks.create("ruby_ore", { texture: m.ref.asset("textures/block/ruby_ore.png"), hardness: 3 });
ore.rules.add({ on: m.triggers.break, do: [ m.actions.giveItem({ item: ruby, amount: 1 }), m.actions.giveExp({ points: 5 }) ] });
ore.rules.add({ on: m.triggers.break, do: [ m.actions.giveItem({ item: ruby, amount: 1 }, { chance: 0.1 }) ] });   // `chance:` is an action option
```

The ruby goes to the breaker's inventory. A world drop is the block's own `drops:` field where the block type has one.

### T25 Miner's heirloom — Pass

```ts
p.items.create("miners_heirloom", { material: "netherite_pickaxe", display_name: "<gold>Miner's Heirloom",
  mechanics: [ m.mechanics.durability({ max: 2000 }), m.mechanics.soulbound({}) ] });
```

Both are class F mechanics (primitives.md §4.2) and both work. They are the next presets after the backpack.

### T26 Announcements — Fail (TIMER)

```ts
const tips = ["Use /kits for a starter kit.", "Vote daily for keys.", "Join our Discord: /discord", "Read /rules."];
const next = p.variables.create("tip_index", { scope: "server", type: "number", default: 0 });
p.rules.create("announce", {                                              // a rule document: passes since #327
  on: m.triggers.every({ minutes: 5 }),                                   // ✗ [TIMER]
  do: [ m.steps.choose(tips.map((t, i) => ({ if: m.expr`${next} == ${i}`, then: [ m.actions.broadcast(`<aqua>[Tip] ${t}`) ] }))),
        m.actions.setVar(next, { op: "set", value: m.expr`(${next} + 1) % ${tips.length}` }) ] });
```

### T27 `/discord` — Fail (CMD)

```ts
p.commands.create("discord", { do: [ m.actions.message("<blue>Join us: <u>discord.gg/example</u>") ] });   // ✗ [CMD]
```

The smallest task in the set, and it fails. A GUI whose `gui.opening` rule messages and cancels would imitate it.

### T28 Rules on first join — Fail (VIEW)

```ts
const accepted = p.variables.create("rules_accepted", { scope: "player", type: "boolean", default: false });
const rules = p.dialogs.create("rules", { title: "Server rules", body: ["Be kind.", "No cheating."],     // ✗ 'dialogs' (#6) [VIEW]
  buttons: [ { label: "Accept", do: [ m.actions.setVar(accepted, { op: "set", value: true }) ] },
             { label: "Leave",  do: [ m.actions.runCommand({ command: "kick %player% Rules declined", as: "console" }) ] } ] });
p.rules.create("rules_gate", { on: m.triggers.playerJoin, if: m.conditions.var(accepted, "==", false),   // passes since #327
  do: [ m.actions.dialogOpen(rules) ] });
```

### T29 Forge — Pass

```ts
const refined = p.items.create("refined_ruby_sword", { template: m.ref.item("sample:ruby_sword"), display_name: "<red>Refined Ruby Sword" });
const forge = p.guis.create("forge", { container: "chest_3", title: "Forge", open: { commands: ["forge"] } });
forge.layers.addSlots({ id: "sword", slots: [11], input: true, durable: "required", accept: [ m.ref.item("sample:ruby_sword") ] });
forge.layers.addSlots({ id: "rubies", slots: [13], input: true, durable: "required", accept: [ m.ref.item("sample:ruby") ] });
forge.layers.addButton({ slot: 15, item: "minecraft:anvil", name: "Forge (200 coins)", rules: [{ on: m.triggers.guiClick, do: [
  m.steps.if(m.conditions.all([ m.conditions.slotHas({ layer: "sword", amount: 1 }), m.conditions.slotHas({ layer: "rubies", amount: 3 }) ]),
             { else: [ m.actions.message("<red>A ruby sword and 3 rubies."), m.steps.stop() ] }),
  m.steps.require([ m.actions.takeMoney({ amount: 200 }) ], { else: [ m.actions.message("<red>You need 200 coins.") ] }),
  m.actions.custodyConsume({ layer: "sword", amount: 1, await: "saved" }), m.actions.custodyConsume({ layer: "rubies", amount: 3, await: "saved" }),
  m.actions.giveItem({ item: refined, amount: 1 }), m.actions.guiRefresh() ] }] });
```

The custody work of #247 and #248 pays here: items go in, are consumed behind the save barrier, and the result is given. This
is the task an incumbent stack needs a crafting plugin for.

---

## 4. The tally, and the ranking

### 4.1 The gaps

Each is a capability, named for what it does (U1). The third column is where the audit already proposed it.

| Gap id | The missing primitive | primitives.md | Tasks that need it | Tasks it alone unblocks |
|---|---|---|---|---|
| **RULE** | A rule unit with no host: behavior not attached to an item, block, furniture or GUI | §5.5 | T06 T10 T15 T17 T20 T22 T26 T28 (8) | **Closed by #327** (the `rule` content type, logic.md §37); with the triggers below it flipped T06 T10 T15 |
| **TRIG-ENTITY** | Triggers for entity events with the entity and the damage in context: hit, hurt, kill | §4.3, §5.5 | T01 T06 T12 T15 T17 (5) | **Closed by #327** (`entity.hit`, `entity.kill`, `player.hurt`); T06 T15 pass, the rest keep other gaps |
| **TRIG-PLAYER** | Triggers for a player's lifecycle: join, quit, respawn, death | §5.5 | T10 T28 (2) | **Closed by #327** (`player.join`, `player.quit`, `player.respawn`, `player.death`); T10 passes, T28 keeps VIEW |
| **VERB** | Entity and world verbs: heal, damage, velocity, particle, launch, break or set a block, edit a stack | §5.7 | T12 T13 T14 T20, F2 F5 (4) | T13 T14 |
| **TIME** | A clock in expressions (`now()`), durations, and a cooldown that persists and can be read | new | T04 T05 T07 (3) | T04 T05 T07 |
| **CMD** | Commands as content | §5.4 | T02 T21 T27 (3) | T27 |
| **TARGET** | Target addressing: an action on another player or an entity | §5.6 | T01 T02 T21 (3) | with CMD: T21 |
| **VIEW** | Views over state: more than one viewer; the dialog and HUD view kinds | §5.3 | T02 T20 T28 (3) | none alone |
| **STATE** | Keyed state: a holder other than player or server (a stack, an entity, a key), a TTL, an expiry trigger | §5.2 | T01 T02, F1 (2) | none alone |
| **TIMER** | A trigger that fires on a schedule | §5.5 | T20 T26, F5 (2) | with RULE: T26 |
| **CUSTODY** | Custody holders beyond the player's own account, and the atomic transfer | §5.1 | T02 T18, T03 by hand (2) | T18 |
| **QUERY** | A read across the holders of one variable (ranking) | new | T17 (1) | none alone |
| **PROVIDER** | Open provider kinds (regions) | §5.8 | T22 (1) | none alone |

Two things the audit proposed that **no task needed**: `emit` with the `custom` trigger, and cross-resource `call`
(primitives.md §5.5). They stay good ideas and are not in the slice. That is the benchmark doing its job in the other direction.

### 4.2 The ranking

Ranked by the tasks each *issue* turns from Fail or Partial to Pass, counting an issue's effect given the issues above it, with
the cost class as the tie-breaker. An issue may bundle gaps that have no use apart (a host-less rule and its first triggers).

| Rank | Issue | Gaps | Passes after it (cumulative) | Newly passing | Cost |
|---|---|---|---|---|---|
| 0 | (today) | | 10 of 29 | | |
| 1 | **Rules without a host, with the entity and player triggers** (#327), **shipped** | RULE, TRIG-ENTITY, TRIG-PLAYER | 13 | T06 T10 T15 | Medium: one content type, seven trigger registrations, firing sites behind the thread gateway (as built: logic.md §37) |
| 2 | **A clock: time in expressions, persistent cooldowns** (#328) | TIME | 16 | T04 T05 T07 | Small: expression functions and one rule option |
| 3 | **Entity and world verbs** (#329) | VERB | 19 | T12 T13 T14 | Medium: about eight actions, each small; protection checks |
| 4 | **Commands as content** (#330) | CMD | 20 | T27 | Small to medium: lift the `open:` block |
| 5 | **Target addressing** (#331) | TARGET | 21 | T21 | Medium: a cross-cutting parameter on the actions that admit it |
| 6 | **Timers** (rides #327's rule unit) (#332) | TIMER | 22 | T26 | Small once RULE exists; budgets matter |
| 7 | **Keyed state: holders, keys, TTL** (#333) | STATE | 23 | T01 (needs 1 and 5) | Large: the store port, stack and entity holders, migration checks |
| 8 | **Custody holders and transfer** (#334) | CUSTODY | 24 | T18 | Large, with an open design problem (primitives.md §3) |
| 9 | **Views over state; dialog and HUD as view kinds** (#335) | VIEW | 27 | T02 (needs 4, 5, 7, 8), T20 (needs 1, 3, 6), T28 (needs 1) | Large: #6 and #7 reshaped |
| 10 | **A ranking read across holders** (#336) | QUERY | 28 | T17 | Medium; touches offline player data |
| 11 | **Open provider kinds** (primitives.md §5.8, no issue) | PROVIDER | 29 | T22 | Deferred until a second kind forces the shape |

**What the ranking says.**

1. **Four issues of small or medium cost (ranks 1 to 4) take the benchmark from 10 to 20 of 29.** None of them is on the
   `/trade` path. The audit's worked example is the *hardest* task in the set (five gaps, three of them large), not a typical one.
2. **`#320`'s own example, Bloodfang, needs ranks 1, 5 and 7.** The slice's exit criterion (#320's acceptance) therefore fixes the
   minimum: **rules and triggers, target addressing, and stack-held state**. Rank 2 to 4 are cheap and broad and belong with them.
3. **`/trade` is not in the slice.** It needs custody transfer and multi-viewer views, both large and one with an unsolved
   design problem. It stays D12's benchmark and keeps failing visibly until those land, which is what the count is for.
4. **Proposed scope of M6.5's primitive work: ranks 1 to 7.** Ranks 8 to 11 keep their issues, in M7 or later.

Issue numbers are those filed with this document; each issue carries its design sketch, its U1 to U5 statement and its exit
criteria (the tasks of this benchmark that must pass).

---

## 5. Is a separate logic DSL still needed?

**No. Recommended: no second behavior language, and no second schema.** The question arises because the audit's gaps look like
language gaps (no loops, no functions over collections, no cross-resource calls). The benchmark says they are not.

1. **What failed is vocabulary, not grammar.** Of the 13 gaps in §4.1, 12 are missing registry entries (triggers, actions, a
   content type, holders, a list source, a provider kind) and one is a missing expression function (TIME). **No task failed for
   lack of a control structure.** The closed step set of logic.md §2.2 (`if`, `require`, `choose`, `wait`, `call`, `stop`) was
   enough in all 29.
2. **TypeScript is now the composition language, at authoring time.** Every loop a task needed is a loop in the *program*: a
   product list becomes six rules (T08), a rank ladder becomes a `choose` (T23), cumulative weights are computed before staging
   (T16), kit contents are mapped into steps (T04). The stored flow stays flat, readable and graph-projectable (logic.md §7.2).
   This is the reason a logic DSL was tempting before D13 and is not after it: the abstraction an author wanted from a richer
   stored language (functions, loops, parameters), the agent gets from a language that never reaches the server.
3. **A second stored language is forbidden twice.** D4: one behavior system. Master §5.6: one schema source per content type,
   never a second hand-maintained one. A DSL beside the YAML flows would need its own parser, validator, editor projection and
   SDK surface, and two forms of one rule would have to round-trip into each other.
4. **So the "logic system changes" are the primitives of §4.2**, plus two changes inside the existing system:

   | Change | Why | Where |
   |---|---|---|
   | The **expression language gets a typed front in the SDK**: `m.expr` checks paths against the trigger's declared context (logic.md §2.6) and variables by handle, at stage time; where TypeScript can type it (a context object passed to a rule builder), it does | Half of an agent's mistakes in a flow are in an expression string, and codemode.md §13 left this open | The SDK generator (#337); no server change, it compiles to the same `${...}` |
   | **Runtime sets are handled by the verbs, not by a loop step**: a target is a selector ("players within 8 blocks"), a ranking is a list source | Keeps "a flow cannot spin" (logic.md §2.2) | #331, #336 |

5. **What would change this answer.** A benchmark task that needs iteration over a set *known only at run time* and that no
   selector, list source or trigger expresses. None of the 29 does. If one appears, the fix is a `for_each` **step** in the
   existing closed set, with the run budget of logic.md §5.3, still one schema.

This is a recommendation on an architecture question; the owner confirms it on #338, and the outcome is recorded in logic.md.

---

## 6. What writing the programs taught about the SDK

Findings for the SDK's design (#337, #339), none of them a primitive:

| # | Finding | Consequence |
|---|---|---|
| S1 | A rule's `if` and `do` use context that depends on its `on` (`damage.final` only under an entity trigger). A plain object literal cannot type that. | The rule builder takes the trigger first and types the rest from it: `rules.add(m.triggers.entityHit, ctx => ({ do: [...] }))`. The generator emits one context type per trigger from its self-description. |
| S2 | A variable or a resource created by the program is used in expressions before it exists on the server. | Handles interpolate into `m.expr`; the recorder resolves them to ids; the dry run checks the combination (codemode.md §3.2 rule 2). |
| S3 | Most programs create a resource and then add rules or layers to it. Each is its own editor operation today (`createResource`, then `logic.edit`, then `gui.edit`) against a document that does not exist yet. | The transaction (#341) must accept edits addressed to a document created earlier in the same list. This is the heart of codemode.md §10 item 1. |
| S4 | One file can hold many keyed resources (plugin.md §7.2). `create` needs a place. | `create(id, fields, { file? })`, defaulting to one file per resource under the type's directory, as the editor's scaffold does. |
| S5 | Mechanics, rules and (later) presets are three ways to give an item behavior. | The TSDoc of a class F mechanic says so and names the preset that replaces it, generated from a `supersededBy` field of the self-description; no hand-written note. |
| S6 | Half the tasks reference an asset the owner uploaded. | `m.ref.asset` and a clear `m.fail` when it is missing; asset upload stays out (codemode.md §8). The chatbot asks for the upload. |
| S7 | A rule option that does not exist (`onCooldown`, T05) and an expression function that does not exist (`now`, T04) are reported by different phases: the first by TypeScript, the second at stage time or by the server. | Expression functions are a small closed set per server: generate them as a typed helper (`m.fn.now()`) so the gap is a type error like the rest. Folded into S1. |
| S8 | #320 step 5 ("Test: a level-2 sword hit applies bleed") has no surface: there are no content tests and no simulator. | **Out of the first slice.** Filed as a decision (#350); the slice proves behavior with the dry run and the trace, not with simulated play. |
| S9 | The tasks' programs are 3 to 25 lines. The 50-document cap and the one-pack rule (C12) were never reached. | No change. |
| S10 | The benchmark's GUI calls (`layers.addButton({slot, item, rules})`, `addText`, `addSlots`) do not exist: a button is a `gui-component` layer with pixel geometry (`m.guiComponents.button({ id, x, y, width, height })`), and its rules are the GUI's own `gui.click` rules filtered by `layer`. #337 compiled T08 in that shape (`fixtures/codemode/corpus/t08-shop.ts`). | #339 decides whether a helper (a button at a slot with its click rule) earns its place in the core, or programs use the registry's shape. The programs of §3 keep the paper spelling for GUI layers; only their step shapes were corrected to the engine's. |
| S11 | Four things a program needs are not in self-description: the step grammar (`if`, `require`, `choose`, `wait`, `stop`, `call`: `require` takes steps, `if` a condition and a branch), which parameters accept an expression, that a trigger filter accepts a list (T08's `click: ["left"]`), and `supersededBy` (S5). | The core hand-types them (`StepBuilders` in `core.d.ts`, temporary), and they move into self-description in #355, after which the generator emits them. |

---

## 7. The sequence of M6.5

The slice has two tracks that meet at the benchmark. Issue numbers are on the [project board](https://github.com/users/xef5000/projects/1),
milestone **M6.5**.

```
 track A, the agent surface (D13)                               track B, the primitives (D12)
 ┌──────────────────────────────────────────────┐               ┌────────────────────────────────────────┐
 │ #337 SDK generator ──► #339 SDK core ─┐      │               │ #327 rules + triggers ─► #332 timers   │
 │ #340 spike ─► #342 type-check Worker ─┤      │               │ #328 clock                             │
 │              #343 sandbox ────────────┼─► #346 proposal     │ #329 verbs        #330 commands        │
 │ #341 transaction + dry run ───────────┤    and grants ─► #347 review UI ─► #348 chatbot              │
 │        └─► #344 revert + audit        │      │               │ #331 targets ─► #333 keyed state       │
 │ #345 runtime reads on the wire ───────┘      │               └────────────────────────────────────────┘
 └──────────────────────────────────────────────┘                             │
                         └────────────► #349 the corpus in CI ◄───────────────┘
                                        exit: #320's acceptance, Bloodfang end to end
 open, the owner's: #351 model provider and billing (master §14.8) · #338 no logic DSL · #350 content tests
```

| Order | Issue | What | Depends on |
|---|---|---|---|
| A1 | #337 | The SDK generator: self-description to `.d.ts`, golden-tested against recorded registry snapshots | none |
| A1 | #340 | Spike: QuickJS in WASM in an opaque-origin iframe, and the TypeScript checker in a Worker, measured | none |
| A1 | #341 | Server: one transaction over the mixed edit list, with a dry run, per-operation permissions, the capability | none |
| A2 | #339 | The SDK core: handles, the read API, the stage recorder, the operation table, the compatibility gate | #337 |
| A2 | #342 | The type-check Worker | #337, #340 |
| A2 | #343 | The sandbox and its host channel, limits and ban tests | #340, #339 |
| A2 | #344 | Server: revert by transaction, and the audit's origin, approval mode and program text | #341 |
| A2 | #345 | Runtime reads on the wire: variables; traces as a bounded query | none |
| A3 | #346 | The proposal lifecycle and the approval grants in the editor host | #339, #341, #343 |
| A4 | #347 | The review surface and the session change log | #346, #344 |
| A5 | #348 | The chatbot layer and the agent loop behind a provider interface | #346, #347; the provider itself waits for #351 |
| B | #327 to #333 | The primitives, in the rank order of §4.2 | each independent of track A |
| end | #349 | The corpus of §3 type-checked in CI against recorded registries, with expected diagnostics; then the measured run of codemode.md §11 | #337; grows with every B issue |

Track A1's three issues share no file and start together. Track B does not wait for track A: a primitive is a registry entry
and is tested by the harness as today; it appears in the SDK by itself when the generator exists (D5).

---

## 8. Not verified

- **Almost nothing was compiled.** The verdicts are a reading of the registries, not a checker's output. #349 replaces this section.
  Since #337 six programs are compiled by the real TypeScript checker (`webui/test/codemode-compile.spec.ts`) against the recorded
  registry of today's plugin (`fixtures/codemode/registry/plugin-current.json`, which includes the clock): T04 (its daily kit, with
  `now()`), T08, T11 and T25 type-check cleanly, T27 fails with TS2339 on `commands`, and T05 fails with TS2322 because `on_cooldown`
  is a `raw` rule option in self-description (a step list is not a JSON value to the type surface: #355). They are in the spellings
  the generated surface really has (they differ from the paper ones: S10), kept in `fixtures/codemode/corpus/`. The other 23
  programs are still on paper. The step shapes of §3 were corrected to the engine's
  grammar (logic.md §2.2, `StepCompiler`): `require` takes steps, a condition guards with `if` and `stop`.
- Parameter names in the programs (`amplifier`, `seconds`, `points`, `detail`) are illustrative; the real ones are the
  registries'. A verdict depends on a member *existing*, not on its spelling.
- That a rule's `cooldown` is in memory and not persisted was read from logic.md §16, not from a restart test.
- That `potion` with `instant_health` heals in fixed steps is vanilla behavior, not tested here.
- T24 assumes the block type exposes `hardness` and that `break` on a block host gives to the breaker; T16 and T19 assume the
  furniture `click` trigger carries the player, as `BuiltinTriggers` declares.
- T29 assumes `slot_has` and `custody.consume` address a layer by id as logic.md §36 describes; the showcase has no forge.
- Costs in §4.2 are estimates from the audit, not from designs. Each issue's design may move its rank.
