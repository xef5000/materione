# Mechanics extension architecture

Implements D3-D6 and D8, master section 8.1, and
[`runtime-systems.md`](runtime-systems.md#23-built-in-mechanics--canned-eca).
This is the M7 extension foundation for the catalog in issue #13, not an
additional mechanics catalog.

## Contract and ownership

| Contract in `api.mechanics` | Responsibility |
|---|---|
| `MechanicsApi` | Register a namespaced factory owned by a Bukkit plugin; return an explicit lifetime handle |
| `Mechanic` | Self-description, explicit repeatability, pure per-item parsing, and one native runtime factory |
| `MechanicConfiguration` | Deeply immutable parameters with strict typed reads |
| `MechanicInstance` | Immutable typed item binding; optional shared ECA rules and item modifiers |
| `MechanicRuntime` | Native listener descriptors and runtime cleanup, once per registration |
| `MechanicListener` | Event type, priority, cancellation policy, thread class, and handler |
| `MechanicContext` | Owner plugin, diagnostics, scheduler, snapshot access, and tracked resources |
| `MechanicLookup` | Typed event-entry lookup scoped to the full registered mechanic id |

The registry contains factories, not item bindings. The pipeline applies the
registered schema's required fields, scalar/list types, nested schemas, and
scalar defaults before invoking `parse`. The factory validates domain constraints
(ranges and combinations) and returns a typed record. Unknown parameters survive;
neither parsing nor registration rewrites canonical files.

```
owner plugin
  -> api.mechanics().register(owner, id, factory)
       -> bounded registration window + managed runtime/listeners

canonical item YAML
  -> normal pipeline -> resolve full id -> schema -> factory.parse
       -> typed binding + ECA rules + item modifiers
       -> complete runtime shadow -> atomic publication

native event -> one snapshot -> scoped typed binding -> handler
ECA event    -> shared rule engine, including mechanic-contributed rules
stack build/refresh -> common platform factory -> ordered item modifiers
```

Unqualified YAML names alias **only** `materione:`. Addon names must be qualified,
so `example:food` can never accidentally execute `materione:food`. Factories
opt into multiple declarations per item with `repeatable()` (default `false`).
Each repeat is parsed independently in authored order; its ECA rules have unique
host-assigned identities and its item hooks run in that same order. Without
opt-in, later declarations of the same full id are rejected, including short/full
aliases; the first valid declaration is retained.
Invalid declarations are diagnosed with `MTR-0027` and skipped individually.
Parser exceptions and broken contribution factories are contained at that same
extension boundary. Metadata-only legacy implementations still link, but their
default parser rejects use explicitly instead of claiming to execute.

## Registration and reload

The initial built-in registrations and subsequent plugin registrations use
`MechanicsApi.register` identically. A late registration opens a bounded window
for that one registry mutation; it does not reopen unrelated registries, edit
files, or apply content. The addon requests the normal pipeline reload after
registering its factories. The existing child-first addon-JAR loader described
in the broader plugin design is not required or implemented by this seam.

Native listeners attach once after backing runtime services are ready, not once
per item or content reload. Every invocation receives an immutable lookup
captured at entry. A retained lookup keeps its old content parameters through
reload; a closed/replaced registration cannot read a replacement's bindings.
Mechanic parameters, ECA contributions, authored behavior, and jukebox references
participate in the item's runtime fingerprint: unchanged resource-pack bytes
cannot suppress a behavior-only apply. Registry entries also carry a monotonic
registration generation, so replacement factories with identical parameters
still rebuild recipe results and exact-choice ingredients through normal apply.
Malformed contributed rules are isolated individually during compilation, preserving
both authored rules and valid sibling contributions.

Closing a handle, disabling its owner, or disabling Materione detaches listeners
and closes the runtime and tracked resources. Initialization failure unwinds its
partial registration without affecting other mechanics. Native lookups, item
hooks, and contributed ECA rules are availability-guarded, so a retained runtime
shadow cannot keep a removed registration executing. Content removal itself still
uses the normal reload path. Other resources close in reverse ownership order.

Listener descriptors declare `REGION` or `ASYNC`. The host checks the arriving
event's asynchronous flag before invoking the handler; a mismatch is diagnosed,
not rescheduled (rescheduling would lose synchronous cancellation semantics).
Synchronous listeners already run on the event's owning thread. Deferred
entity/world mutation uses `context.scheduling()` and `context.plugin()`.
Runtime handler failures use rate-limited `MTR-0023` diagnostics.

## Implementing an addon

Compile against `api` and Paper, declare a plugin dependency on Materione, and
obtain the facade through Bukkit's service manager:

```java
MaterioneAPI api = getServer().getServicesManager().load(MaterioneAPI.class);
if (api == null) {
    throw new IllegalStateException("Materione API is unavailable");
}
MechanicRegistration registration = api.mechanics().register(
        this, NamespacedId.parse("example:greeting"), new Greeting());
api.pipeline().reload(ActorContext.addon(getName()),
        PipelineApi.ReloadRequest.everything());
```

Observe the reload future's `ApiResult` in real addon code; registration alone
does not imply the content has applied. Keep the handle for early removal, or
let owner-disable cleanup close it.

An ECA-only factory needs no listeners:

```java
final class Greeting implements Mechanic {
    public String name() { return "greeting"; }
    public boolean repeatable() { return true; }

    public SelfDescription describe() {
        return new SelfDescription("Greeting", "Greet on right-click",
                List.of(ParameterSchema.of("message", ParameterType.STRING, true)),
                "1.0.0");
    }

    public MechanicInstance parse(MechanicConfiguration config) {
        return new GreetingInstance(config.text("message", ""));
    }
}

record GreetingInstance(String message) implements MechanicInstance {
    public List<Map<String, Object>> behaviorRules() {
        return List.of(Map.of("on", "click:right",
                "do", List.of(Map.of("message", message))));
    }
}
```

The item declaration remains part of the existing format:

```yaml
wand:
  material: BLAZE_ROD
  mechanics:
    - example:greeting:
        message: Hello!
```

For a native mechanic, override `createRuntime` and return descriptors such as
`new MechanicListener<>(PlayerItemDamageEvent.class, EventPriority.HIGH, true,
ThreadClass.REGION, (event, lookup) -> { ... })`. Retrieve the typed record with
`lookup.onItem(event.getItem(), MyInstance.class)` for a singleton; native
repeatable mechanics iterate `lookup.allOnItem(event.getItem(), MyInstance.class)`.
The list is immutable and preserves declaration order; `onItem` returns only the
first match. Do not retain a mutable item-to-configuration map in the listener.
Track subscriptions/timer cancellation
handles with `context.track(...)`; do not spawn independent threads.

Item modifiers must be idempotent metadata edits that preserve material, amount,
identity, and player-owned state. They run in declaration order in the common
stack factory (give, browser, recipes, drops, pick-block, and refresh). Each hook
edits a clone; only successful metadata changes commit. A removed hook stops
running; an addon that persists its own metadata must define its own migration
or cleanup policy rather than deleting arbitrary player metadata. Version-sensitive
component operations stay in the platform layer.

## Built-ins and compatibility

`NativeMechanics` is only composition. Each built-in owns its schema, parser,
typed instance and runtime in `runtime.mechanics.builtin`; the generic host and
pipeline do not switch on built-in names.

| Mechanic | Implementation |
|---|---|
| Commands | Repeatable canned ECA `run_command` rules; `%player%` is translated to shared player interpolation |
| Food | Platform item modifier plus typed consume-effect listener |
| Durability | Native damage listener; existing stack-PDC counters |
| Soulbound | Native death/respawn/join listeners; existing player-PDC stash |
| Backpack | Native interaction/close listeners; existing stack identity and serialized contents |

Food, durability, soulbound and backpack are single-instance: each owns one
component, counter, stash policy or inventory per item. Commands may repeat,
including separate console/player declarations in existing packs. Parameterized
or conditional addons should generally opt into repeatability when their
bindings can execute independently rather than overwrite shared state.

Existing YAML keys and durable PDC keys are retained. Commands now honor the
same block-protection cancellation policy as authored ECA rules. Malformed
parameter types are rejected at load rather than silently coerced. Soulbound
does not stash items when `keepInventory` is already retaining them.

The conceptual separation of registration, per-item parsing, stack contributions,
and runtime ownership was informed by the
[Oraxen addon documentation](https://docs.oraxen.com/developers/addmechanic).
No Oraxen implementation or configuration structure is incorporated.
