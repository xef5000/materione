# Protection: the port every world change asks

**Status: built (#466, M6.5).** The decisions of this document are binding on code and on the verbs that change the world on someone's
behalf; the design record in [`logic.md`](logic.md) §43 points here. Traceability: D1 (nothing here is stored content), D5 (providers
and conditions describe themselves), D6 (a verb's change is still one pipeline step), D8 (threads), D12 (a primitive, not a feature),
master §5.6 (no file changes meaning).

World verbs ([`logic.md`](logic.md) §41), furniture and custom blocks change the world on someone's behalf. Until #466 the only
protection check was the **event path**: `Player#breakBlock` or a `BlockPlaceEvent` raised as the player, which every protection
plugin listens to. It is universal, but it needs an online player on the right thread, it runs every other listener as a side effect,
it cannot ask about damage or interaction, it cannot ask when there is no player, and it cannot answer a preview ("would this be
allowed?"). The **protection port** is the second path: one question, `may this actor change this place?`, asked of the region and
claim plugins directly. Both paths stay, and a setting combines them.

## 1. The primitive test (master §3.1)

| Test | How the port passes |
|---|---|
| U1 name | `ProtectionPort`, `canBreak`, `canPlace`, `canInteract`, `canDamage`, `canUse`: what an actor wants to do at a place. Nothing is named for WorldGuard, for a claim, or for a use case |
| U2 generality | any actor (a player, an offline player, no one), any location in any world, any verb or condition, any provider (a plugin's adapter, an addon's own) |
| U3 composition | one port asked by every verb and by three conditions (`can_build`, `can_break`, `can_interact`); a rule composes the condition with `if`, `require` and the verbs it protects |
| U4 self-description | an SPI: `ProtectionApi#providers()` lists who is present with the name, owner, thread safety and description each declared; the conditions carry full parameter descriptions, so the editor form, `logic.vocabulary`, `authoring.describe` and the code-mode SDK show them with no code |
| U5 safe | an adapter that throws is a deny for that one call and `MTR-0122`, rate-limited; a bad setting is the default and `MTR-0123`; a provider that cannot be used on this server is `MTR-0124`; nothing here can crash the server |

## 2. Decisions, in brief

Every decision below is reversible by the owner. The principles the owner set: Materione is an abstraction of Minecraft features, so
allow as much as possible and restrict only where needed; prefer "X and Y behind a switch" to "X or Y"; code is cheap and annoyances
are expensive, above all for a first-time user, so **a server with WorldGuard installed gets correct protection with zero
configuration**.

| # | Question | Decision |
|---|---|---|
| 1 | Where does the port live? | `ProtectionPort` (the SPI) and `ProtectionApi` (the service) in `api`, `dev.materione.api.protection`; the service in `plugin.protection`; the adapters in `integrations.protection`, with the other soft-depend adapters |
| 2 | The setting | `protection_mode`: `event`, `port` or `both` (the issue spelled it `protection.mode`; settings are flat, as `world_verbs_*` are). Default `both`. Optional, not written to a fresh file, so the default settings golden does not move |
| 3 | The no-actor switch | `protection_no_actor`: `ask` (default) or `allow`. `ask` puts an actor-less change (`as_player: false`) to the providers; `allow` lets it through unasked. The switch is the "X and Y" the principles ask for: the §41 decision says a claim may refuse an actor-less break, and a server that wants actor-less verbs unrestricted says so in one line |
| 4 | Caching | **None.** Every adapter reads an in-memory index (a region tree, a claim map). A cache would have to learn each plugin's own claim changes to stay correct, and a stale allow is a grief. The cost is bounded by the verbs' own caps: a run asks at most 2,048 times (`world_verbs_max_blocks_per_run`) |
| 5 | Adapter failure | Fail closed for that one call (a deny), `MTR-0122` at most once per provider per ten seconds, and the provider is asked again on the next call: a failure is not remembered |
| 6 | MTR-0119 (the Folia refusal) | **Lifted in `port` mode, kept in `event` and `both`.** §5 gives the proof |
| 7 | The conditions | `can_build`, `can_break`, `can_interact`; they always ask the providers, whatever `protection_mode` says (§7) |
| 8 | Adapters | Seven built, three skipped, each with its reason in §9 |
| 9 | `damage` and `launch` in `port` mode | They behave as in `both`: the damage or launch event is the effect itself, not a separate check that could be skipped (§8) |
| 10 | The operator's `/materione dev place` and `give` | **Not routed.** They are permission-gated operator tools, the equivalent of an admin's own `//set`; the player-facing placement paths are routed |

## 3. The port

`ProtectionPort` is the SPI: one provider's answers. Every question has a default that abstains, so a provider answers only what its
plugin can answer.

| Method | Question | Location |
|---|---|---|
| `canBreak(actor, location)` | may the actor break the block here? | the block |
| `canPlace(actor, location)` | may the actor place a block or a furniture cluster here? | the block |
| `canInteract(actor, location)` | may the actor open or press what is here (a chest, a door, a lever)? | the block |
| `canDamage(attacker, victim)` | may the attacker hurt the victim? (PvP flags, claims that protect animals) | the victim's |
| `canUse(actor, location)` | may the actor use an item or ability here (shoot, cast)? | the use's |

**Actor.** `ProtectionActor` is an online player, an offline player (known by id), or none. An online actor holds the live `Player`,
which a provider may use only on the thread that owns that player; across a region boundary the service hands the provider an offline
actor (§5). An actor-less question asks about the place alone ("may the world change this by itself?"): a provider answers deny for a
place it protects from everyone but its members, and abstain for the rest.

**Answer.** `ProtectionAnswer` is `ALLOW`, `DENY` or `ABSTAIN`, the provider's name, and a short reason. Abstain means "not mine to
decide here" (no region of this plugin covers the place), not "allowed": it falls through to the next provider and to the event path,
and a verb treats it as permission to go on. A refused step says who refused: `refused by WorldGuard (a region's flags or membership
deny it)`.

**Combining providers.** `ProtectionApi#ask` puts the question to the providers in registration order. The first deny ends the
question and is the answer; otherwise any allow is an allow (the allowing providers are named); otherwise the answer is an abstain.
**Deny wins; abstain falls through.** Registration order is the adapters' order in `ProtectionAdapters` (WorldGuard first), then
addons in the order they register.

**Self-description.** `ProtectionApi#providers()` returns `ProviderInfo(name, owner, safety, description)` per provider. The server
logs `MATERIONE-PROTECTION-OK mode=both providers=WorldGuard,GriefPrevention` after the integrations refresh (at boot and on every
`/materione reload`), and `/materione doctor` prints the same line.

## 4. Modes: how the port and the event path combine

`protection_mode` says which of the two checks a verb runs. In every mode a deny wins and an abstain falls through.

| Mode | The verb asks the port | The verb runs the event path | For |
|---|---|---|---|
| `event` | no | yes (a player's action) | the behavior before the port; a server that distrusts an adapter |
| `port` | yes | no | a server that wants no foreign listener to run for a verb's own change, and Folia (§5) |
| `both` (default) | yes, first, and a deny stops the verb | yes, for a player's action | everyone else: the port is the early, cheap answer, the event is the universal fallback |

**What "the event path" is, per verb.**

| Verb or path | Event path | Port question |
|---|---|---|
| `break_blocks` as the player | `Player#breakBlock` (the player's tool, durability, drops, the `BlockBreakEvent`) | `canBreak`, per block, before the event |
| `break_blocks` with `as_player: false` | none (never was) | `canBreak` with no actor |
| `set_block` as the player | a `BlockPlaceEvent` asked before the block changes | `canPlace` before the event |
| `set_block` with `as_player: false` | none | `canPlace` with no actor |
| `damage` | the damage itself: an attributed `EntityDamageEvent` (and `DamageWatch` reporting its cancellation) | `canDamage(attacker, victim)` before it |
| `launch` | the launch itself: a `ProjectileLaunchEvent` | `canUse` at the shooter's eyes before it |
| furniture placed | none of its own (the interact event is cancelled by Materione to place; a click on a custom block cancels it too, so it cannot be read as a veto) | `canPlace` at the anchor |
| furniture taken up (sneak-hit) | the damage event, which protection plugins cancel (the handler ignores cancelled events) | `canBreak` at the cluster |
| custom block placed by an item that is not a block, or against our own block | the interact event the handler already honours (`useItemInHand`) | `canPlace` at the target |
| custom block placed from a vanilla block against our block | a `BlockPlaceEvent` announced after the block is set, reverted on a veto | `canPlace` before it |
| custom block placed by a block item the vanilla way | the player's own `BlockPlaceEvent`, vetted by every protection plugin before Materione sees it (MONITOR, ignoring cancelled) | none: asking again would repeat what the event answered |

**`as_player: false` now asks the port with no actor** (the §41 decision 1, now built). In `event` mode it asks nobody, as it always did.
With `protection_no_actor: allow` it asks nobody in any mode. A refused actor-less change is a failed step a `require` can answer; with
`as_player: false` in a rule that does not guard, it is the rate-limited failure every verb already is.

**`port` mode and the player's tool.** With no `BlockBreakEvent` there is no player break path, so the block is broken the way the
player's main-hand tool would break it (`Block#breakNaturally(tool)`: the tool's drops apply, its durability is not spent). A
Materione block goes through its own removal path either way. A placement in `port` mode is the plain placement after the port's
answer.

## 5. Threads and Folia (D8)

The port is asked **on the region that owns the location**. `ProtectionApi#ask` requires the calling thread to own the query's
location (the verbs already run there: the player's region, or each block's region); `ProtectionApi#askAt` hops first through the same
`Regions` seam the verbs use. A provider is asked only where its declaration allows:

| `ProtectionSafety` | Meaning | On Paper | On Folia |
|---|---|---|---|
| `ANY_THREAD` | reads only immutable or concurrent data | asked where the caller stands | asked where the caller stands |
| `OWNING_REGION` (default; every shipped adapter) | reads the world or the plugin's data of the location | asked on the main thread | asked on the owning region's thread |
| `MAIN_THREAD_ONLY` | a plugin never made region-aware | asked on the main thread | **not installed**: `MTR-0124`, and the event path still covers its plugin |

A provider asked from a thread that does not own the location (a bug in a caller) is **not read**: that call is a deny and `MTR-0122`.
A provider is therefore never reached from the wrong thread, which is the same promise a currency backend makes
([`logic.md`](logic.md) §18).

**The actor across a region boundary.** A live `Player` may be used only on its own thread. When the asking thread owns the location
but not the player (a verb breaking a block in another region for a player elsewhere), the service passes an **offline actor** built
from the player's id; an adapter that needs permissions or a live player (PlotSquared's bypass permissions, WorldGuard's bypass)
falls back to what the offline record gives (membership), and an adapter whose plugin cannot take an offline player abstains. This is
stricter than the live check, never looser: a bypass permission an offline actor cannot show is a deny the live player would not have
had, which is why the `both` default keeps the event path.

**The MTR-0119 decision (§41 decision 3: "#466 revisits it").** The refusal said: a block as the player across a region boundary needs
the player's thread and the block's, which Folia does not give. The reason was the event: `Player#breakBlock` and the placement event
must run beside the player. The port has no such need, so the question splits:

- **`port` mode: lifted.** The port answers on the block's own region with the offline actor, and the block is broken or placed there
  by the same hop an `as_player: false` verb already uses. The tool is read on the player's thread first and carried to the block's.
  A foreign block is no longer skipped or counted, and `MTR-0119` is not reported.
- **`both` and `event` modes: kept.** The event path cannot run on the block's region, and skipping it for a foreign block would let a
  protection plugin that has no adapter (and so only an event) be bypassed on exactly the blocks nobody is watching. The refusal is
  correct, cheap and rate-limited, and the diagnostic's hint now names the way out: `protection_mode: port`.
- **`as_player: false` is unchanged**: each block is reached on its own region's thread, and now asked of the port there with no actor.

So a Folia server that wants cross-region excavators sets `protection_mode: port` and installs the adapters for the plugins it runs;
Paper, where every location is owned, never sees the refusal in any mode. What is proven where: the service's ownership rules, the hop,
the offline actor, and the verbs' use of both modes are proven by unit tests with a scripted ownership probe
(`ProtectionServiceTest`, `ProtectionVerbsTest`); a real Folia server was not run for this issue (the `foliaProbe` source set is opt-in
evidence and CI is Paper 1.21.4), so the cross-region dispatch is the `SchedulingGatewayImpl.executeSync` every other region-aware path
already uses.

**The attacker's own region.** An adapter reads the victim's location, never the attacker's, because the attacker may be in another
region. A PvP rule that depends on where the attacker stands (WorldGuard tests both ends) is therefore asked of the victim's end only
through the port; the event path, which runs on the victim's thread with both, still covers it in `both` mode.

## 6. Failure (U5)

| Failure | Result |
|---|---|
| a provider throws or fails to link | that call is a deny from that provider; `MTR-0122`, at most once per provider per ten seconds, naming the provider and the failure; asked again next call |
| a provider asked from a thread that does not own the location | not read; a deny; `MTR-0122` |
| the hop to the owning region fails | a deny from `protection`; `MTR-0122` |
| an adapter's plugin API does not link when it would register | the adapter is not installed; `MTR-0124`; the event path still covers the plugin |
| a `MAIN_THREAD_ONLY` provider on Folia | refused at registration; `MTR-0124` |
| a bad `protection_*` value | the default for that key; `MTR-0123` |

Failing closed is the one place the port restricts on a fault, because the alternative (a broken adapter silently allowing every
change) is the failure a protection system must never have. The deny names the provider, so the owner sees which adapter to update.

## 7. The conditions

| Condition | Asks | Shorthand |
|---|---|---|
| `can_build` | `canPlace` | `at` |
| `can_break` | `canBreak` | `at` |
| `can_interact` | `canInteract` | `at` |

`at` is the world verbs' location value (`event`, `player`, or `{of, x, y, z}`; default `player`); `as_player` (default `true`) asks as
the run's player, and `false` asks with no actor. The condition is **true unless a provider denies**: with no provider, or with every
provider abstaining, nothing protects the place, so it is true. A location that cannot be read, or a run with no player when
`as_player` is true, is false. It is a pure read, so a preview is the same call as a check.

**Why they ignore the mode.** `protection_mode` governs what a verb asks on its own; a condition is the author asking, and an author
who wrote `can_build` wants the answer. A server that sets `event` to keep verbs off the port still lets a rule ask the port in an
`if`. The three are one class with a subclass per question, so a fourth is three lines.

```yaml
behavior:
  - on: click:right
    do:
      - if: {can_build: {at: {of: player, y: 1}}}     # may this player build above their feet?
        then:
          - set_block: {block: glass, at: {of: player, y: 1}}
        else:
          - message: "You cannot build here"
```

## 8. How each verb uses the port

See the table in §4. Three points of detail.

- **Order.** The port is asked before the event path and before anything changes, so a deny needs no revert and no other plugin's
  listener sees the attempt. The check precedes the event for a placement (`both`) and the break.
- **Outcome.** A denied block is counted with the event path's refusals: `break_blocks` fails only when blocks were eligible and none
  was broken, and its message names the first provider that refused (`3 refused by WorldGuard (…)`). A denied `set_block`, `damage` or
  `launch` is a failed step with the same phrase, said quietly (no chat line, no diagnostic), as a vetoed damage is (§41 decision 2).
- **Not asked.** `heal`, `velocity`, `particle`, `potion`, `drop_item` and `stack.edit` change no protected state (a block, or a
  blow, or a shot); they do not ask. `drop_item` puts an item entity in the world, which no claim plugin treats as a build.

## 9. Adapters

An adapter is the logic of one plugin: it is `BackendPort` (the routing, written and tested once) over a small `Backend` (what the
plugin says to an online player, an offline player, and no one), over an `Api` (the calls into the plugin). Each is written from the
protected plugin's own public API, read with `javap` from the artifact the plugin publishes; **no part of any other project's
protection library was read or copied** (the one that exists has no license). Each is compiled `compileOnly` and non-transitive against
the plugin's API artifact from the plugin's own official repository, so nothing is shaded; each is constructed only after the gate
saw the plugin enabled, and one whose API does not link is `MTR-0124` and skipped. Every plugin is a soft dependency in
`paper-plugin.yml` (`load: BEFORE`, `join-classpath: true`, `required: false`).

| Plugin | API read | Answers (online player / offline / no actor) | Not answered |
|---|---|---|---|
| **WorldGuard** 7.0.14 | `WorldGuardPlugin#createProtectionQuery` (break, place, interact, entity damage: bypass, flags and membership as WorldGuard applies them to the player's own action); `RegionQuery#testBuild` with `wrapOfflinePlayer` or `Associables.constant(NON_MEMBER)` | all three; damage for an online attacker only | damage by an offline or absent actor |
| **GriefPrevention** 16.16.0 | `GriefPrevention#allowBuild`, `#allowBreak`; `Claim#allowContainers`, `#allowAccess`; `DataStore#getClaimAt` | break, place, interact for an online player; no actor is denied inside a claim | an offline player (the API takes a `Player`), damage, item use |
| **Towny** 0.103.2.16 | `PlayerCacheUtil#getCachePermission` (build, destroy, switch, item use); `CombatUtil#preventDamageCall`; `TownyAPI#isWilderness` | an online player incl. damage; no actor is denied on a claimed town block | an offline player (the cache is per connected player) |
| **PlotSquared** 7.6.1 | `Location#getPlotArea`, `#getPlot`; `Plot#hasOwner`, `#isAdded`; the `plots.admin.build.*` permissions | online (membership and bypass), offline (membership), no actor (denied in a plot world) | the per-plot flags that open a plot to strangers; damage; item use |
| **HuskClaims** 1.5.10 | `BukkitHuskClaimsAPI#isOperationAllowed(user, type, position)` and `(position, type)` | online player incl. damage kinds; no actor through the operation with no user | an offline player, item use |
| **HuskTowns** 3.1.4 | the same shape of `BukkitHuskTownsAPI#isOperationAllowed` | as HuskClaims | as HuskClaims |
| **BentoBox** 2.7.0 | `IslandsManager#getProtectedIslandAt`; `Island#isAllowed(user, flag)` and `(flag)`; `Flags` (break, place, container, door, trapdoor, lever, button, hurt animals, villagers, monsters, PvP) | online, offline, and no actor (the island's settings) for every game mode | actor-less damage, item use |

**Skipped, and why (the event path still covers each; an addon can register a port for any of them).**

| Plugin | Why not built |
|---|---|
| **Lands** | the API is published on JitPack only (`com.github.Angeschossen`), a repository that has been unreliable in CI; several of its tags do not build. CI reliability is a hard rule, so no repository that can break the build is added |
| **Residence** | the same: JitPack only, and every listed build of the API fails (`Error` in JitPack's own build index) |
| **FactionsUUID** | no maintained, published API artifact: the one on the CodeMC mirror is a years-old fork's, and the plugin's current API is not published to any repository |

**Repositories and CI.** Four repositories were added to `settings.gradle.kts`, each with a `content` filter naming only the plugin's
own Maven groups, so no other dependency resolves from it and one outage cannot affect another dependency: EngineHub (WorldGuard,
WorldEdit), CodeMC (GriefPrevention, BentoBox: its poms carry a `${revision}` version Gradle cannot read, so its jars are fetched with
no metadata, which is safe because they are non-transitive), the Towny repository, and William278's (HuskClaims, HuskTowns).
PlotSquared is on Maven Central. All eleven artifacts are pinned in `gradle/libs.versions.toml`. The cost model is: **no deployed
infrastructure change**; a build downloads about 25 MB of jars once, and Gradle's cache keeps them.

**Tests.** Each adapter has a unit test over a test double of its plugin's calls (no plugin, no server): the actor routing, the verdict
mapping, the actor-less rule, what abstains. The `Real` classes (the calls into the plugin) are verified by compiling against the
published API; that a real WorldGuard answers is not run in CI (no plugin jar runs there), which is why every adapter is covered by
the stand-in provider's integration test for the port itself and by the event path for the plugin.

## 10. Diagnostics

| Code | Severity | Meaning |
|---|---|---|
| `MTR-0122` | warning | a protection provider threw, was asked from the wrong thread, or its hop failed; that call was denied; reported at most once per provider per ten seconds |
| `MTR-0123` | warning | a `protection_*` setting is not one of its values; the default is used for that key |
| `MTR-0124` | warning | a protection provider cannot be used on this server: it is main-thread only on Folia, or its plugin's API did not link; the event path still covers that plugin |

`MTR-0106` to `MTR-0117` are reserved by the HUD and frame-cycling designs, `MTR-0118` to `MTR-0121` are #329's and #464's.

## 11. What changed for existing content

Nothing migrates and no file is rewritten. No file says anything new: the settings are optional, and the verbs' parameters are
unchanged. With no protection plugin installed the default `both` mode asks an empty port (an abstain) and then runs the event path,
which is what it did. With a protection plugin installed, the change is the intended one: a verb now asks that plugin directly as well,
and a verb with `as_player: false` is refused where a claim protects the place.

## 12. Verification

`ProtectionServiceTest` (combining, the no-actor switch, a throwing provider, a link error, the wrong thread, the hop, the offline
actor, registrations and plugin disable, self-description, Folia refusal), `ProtectionSettingsTest`, `ProtectionVerbsTest` (each verb in
each mode, the denied block before the event path, `as_player: false` asking with no actor, MTR-0119 kept in `both` and lifted in
`port`), `ProtectionConditionsTest`, the api types test, `BackendPortTest` and one test per adapter, `IntegrationsGuardTest` (an adapter
whose plugin cannot link is `MTR-0124`, an absent plugin loads nothing), `DiagnosticCodesTest` (111 codes), the registry, editor
snapshot and `.d.ts` goldens (three conditions on the wire), and `PaperProtectionIT` on Paper 1.21.4 with a stand-in provider
registered through the SPI (a port deny stops a `break_blocks` block before any break event is raised for it, an actor-less
`set_block` is asked with no actor, `protection_mode: event` ignores the port, `can_build` reads it in both modes). QA `protection.*`
(auto; not run and not blessed).

## 13. Owner questions

None. Each of the open questions the issue could have raised (the mode's default, the Folia refusal, the actor-less rule, caching,
the adapters) is decided above under the owner's principles. Nothing here touches a license, money, Cloudflare, or a decision D1 to D13.

## 14. Not built, on purpose

A cache of answers (decision 4); per-verb or per-provider mode overrides (the single `protection_mode` and the no-actor switch are the
switches; a per-provider disable is `protection_mode: event` plus removing the adapter's plugin from the server, or an addon that
closes the registration); adapters for Lands, Residence and FactionsUUID (§9); a verb `drop_item` check; a `can_damage` and `can_use`
condition (the verbs ask them; a condition for them is one subclass the day a rule needs it); reading a plot's own flags in the
PlotSquared adapter; a preview of a verb's whole box ("which of these blocks would be allowed?", a loop of `can_break`
today); any Folia run.
