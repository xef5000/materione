# Code mode: how an agent authors in Materione (D13)

> **Scope.** [D13](../architecture.md#32-d13-in-full) says an agent changes Materione by **writing a TypeScript program against a
> typed SDK** that is generated from the registries, and that the program's only effect is **authoring operations**: the same
> validated edits the editor sends, through the one pipeline. This document is the design of that surface: why it is shaped this
> way and not as raw YAML or as many tools (§2), the SDK (§3), the sandbox and where it runs (§4), the execution model from
> program to applied change (§5), how errors reach the agent (§6), how the SDK is versioned against the plugin and the editor
> (§7), what is explicitly out (§8), a worked example (§9), what the server must add (§10), how it is proven (§11), and the
> questions left to the owner (§12).
>
> **Status.** **Design, docs only (#320).** The six decisions of §1 are the owner's. The numbering as D13, the sandbox
> recommendation (§4.3), the one-pack rule (§5.4) and every "recommended" line are this document's proposal and are flagged
> where they occur. Nothing here is built. Code in this document is **shape, not syntax**: names are illustrative, and the
> SDK is generated, so its real spelling comes from the registries it is generated from (§3.1).
>
> **Traceability.**
>
> | Decision | How this doc applies it |
> |---|---|
> | D13 | The whole document. |
> | D1 | Every change a program makes is a change to files, made through the editor's operations. The program is never the stored source of a feature (§8). |
> | D2 | Typed handles are graph edges the SDK checks before the server does (§3.4). Impact analysis (`queryGraph`, `findReferences`) is part of the read API (§3.2) and of review (§5.6). |
> | D4 | There is one behavior system and it is data. A program never becomes runtime behavior (§8). |
> | D5 | The SDK is generated from registry self-description; an addon's registration is usable without SDK code (§3.1, §3.6). |
> | D6 | A program has no path to the runtime except the one pipeline (§5). It cannot apply its own changes (§5.6). |
> | D11 | The sandbox runs in the cloud editor's page. No new endpoint, no new token scope, the relay stays untrusted (§4). |
> | D12 | Primitives and presets appear in the SDK as what they are; a missing primitive appears as a type error (§3.5, §9.2). |
> | §5.6 | The SDK is a second consumer of the schema, never a second schema: it is generated, and the write API cannot reach a field the schema does not describe (§3.3). |
> | Cloudflare guardrails (`CLAUDE.md`) | Nothing in this design runs on Cloudflare compute (§4.5). |

---

## 1. What is decided

The owner decided the following on #320. They are recorded here as decided, not as proposals. The first column is a reference
for the rest of the document.

| # | Decision | Where it lands |
|---|---|---|
| **C1** | **All agent changes go through code mode.** The agent writes TypeScript against a typed SDK generated from registry self-description (D5). The program runs in a sandbox, and its only effect is authoring operations: the same validated edits the editor sends, through the one pipeline (D6), with validate, dry-run and transactional apply. | §3, §5 |
| **C2** | **Changes are always stored as YAML/JSON (D1).** Code is never the stored source of a feature and never runs as runtime logic on the server. Programs are **one-shot**: the stored files stay canonical and each change is a new short program. | §5, §8 |
| **C3** | **The SDK is also the preferred way to read.** An agent reads Materione content and state (objects, references, validation results, traces) through the SDK, not by reading raw YAML or JSON. | §3.2 |
| **C4** | **TypeScript, not Java.** Java would need heavy sandboxing. | §2.5 |
| **C5** | **Context from PR #322 stands.** The agent surface is a chatbot inside the cloud editor (`editor.materione.com`) that uses the existing editor protocol as an ordinary editor client. No new endpoint. Relays stay untrusted. | §4, master §14.8 |
| **C6** | **Java remains for addons.** Addons add *primitives* (D12), not features. A primitive an addon registers is usable by an agent with no SDK code (§3.6). | §3.6 |

What this document proposes on top of those, for the owner to accept or change, is marked **Recommended** and collected in §12.

---

## 2. Why code mode

An agent that authors Materione content has to do four things well: find out what this server supports, compose many small edits
that depend on each other, get precise feedback when it is wrong, and leave the owner something reviewable. There are three other
shapes of agent surface that could carry those. None was chosen, each for a reason that comes from this project and not only from
taste.

### 2.1 Against raw YAML written under the guidance of a skill

The agent reads a skill that documents the formats, then writes YAML text.

- **A skill is a second copy of the schema.** Master §5.6 forbids a second, hand-maintained schema for exactly this reason: it
  drifts. A skill that says what an `item` accepts, or which actions exist, is wrong the day an addon registers a type or a release
  adds an action, and the agent cannot tell. The SDK's types are generated from the same registries that drive validation and the
  editor's forms, so they cannot disagree with the server it is connected to.
- **Feedback arrives late and untyped.** A wrong key is found by the server's parse and schema stages (master §5.4), after a
  round trip, as a diagnostic about a file. A type checker finds it before anything is sent, at the line the agent wrote, and
  says what the valid values are.
- **Text rewrites fight the lossless round-trip.** Master §5.6 promises that an untouched document is byte-identical and that
  edits are minimal diffs. The editor keeps that by editing structurally (`setFields`, the logic and GUI edit vocabularies, with a
  revision guard). An agent that rewrites a file as text does not; it clobbers comments and unknown fields, and it can overwrite a
  concurrent edit unless it also learns the revision protocol.
- **It scales badly with composition.** "Give each of these 40 items the same ability" is a loop in a program and 40 edits in text.

Raw YAML stays what it is for people: the T4 surface (master §2). Nothing here removes it.

### 2.2 Against many fine-grained tools

The agent gets one tool per operation (`create_resource`, `set_fields`, `add_rule`, `validate_draft`, ...), as the sketch in
[`api.md`](api.md) §4.3 lists them.

- **The catalog is large and per-server.** Every content type, every action and condition and trigger with its parameter schema
  is a candidate tool or tool argument. Putting them all in context costs tokens on every turn; leaving them out costs the agent
  its ability to discover them. A typed API that is searched and checked on demand has neither problem (§3.7).
- **One round trip per step, and the model is the data bus.** Each result passes through the model to become the next call's
  argument. A program holds results in variables, loops, and branches without a model turn.
- **No atomic unit.** A sequence of tool calls is not a transaction. A failure at call 7 of 12 leaves the agent to work out what
  happened and to undo it. A program stages its edits and produces one operation list, so what is reviewed, validated and applied
  is one unit (§5).
- **Tools do not compose with each other's types.** A tool that returns a reference cannot have it checked as a typed argument of
  the next one.

### 2.3 Against runtime code (a scripting layer on the server)

The agent writes a script that Materione runs in game, in the style of Skript.

This is the thing Materione exists to replace, and D12 gives the reasons directly: Skript is general but makes every author, and
every agent, rebuild each primitive from scratch with less safety than a typed, validated call. Concretely for this design: a
script on the server is behavior the validation stages cannot check offline (D12 test U5), the content graph cannot see through
it (D2: no impact analysis), it has to be sandboxed on a Folia/Paper server whose threading rules are a review-level invariant
(D8) and whose performance budgets bind it (plugin.md §9), and it would be a second behavior language beside the one the
expression and ECA engine already is (D4). Code mode keeps the language and moves it to authoring time: the code produces edits,
and the *edits* are what runs.

### 2.4 What the research says

Four sources were read for this document (fetched 2026-11-12). Two are papers and two are practitioner writing. They support
"code as the agent's action format" in different degrees, and none tests it on content authoring or against a typed SDK, so they
are evidence for the shape and not a proof for the product. Numbers are as the sources report them.

| Source | What it says | What it supports here | What it does not |
|---|---|---|---|
| **Wang et al., "Executable Code Actions Elicit Better LLM Agents" (CodeAct), ICML 2024, PMLR 235** ([link](https://proceedings.mlr.press/v235/wang24h.html)) | Executable Python as a unified action space, instead of JSON or text action formats. Across 17 LLMs on API-Bank and a newly curated benchmark, CodeAct beat the common alternatives by up to 20% in success rate. The agent revises its actions across turns from the interpreter's observations, and the authors build a 7k-interaction instruction-tuning set. | Composition (one program uses many tools and control flow) and **self-correction from execution feedback**, which is our type-check and dry-run loop (§5). | It is Python with a general interpreter and no safety boundary, evaluated on older models, and on tool-use and reasoning tasks. It says nothing about a sandbox, about authoring, or about types. "Up to 20%" is a best case over the baselines it chose. |
| **Xu et al., "The Devil Is in the Interface: Evaluating How Tool Architecture Shapes Coding Agent Behavior", arXiv 2608.11386** (August 2026; read through the arxiv.science mirror) | Controlled comparison of six tool architectures on repository-level issue fixing (11,700 trajectories, three actors). A Python CodeAct-style interface reached **similar task performance with 41.6% fewer steps and 56.3% lower token usage**. Separately, *structured low-level* interfaces improved consistency across repeated attempts by up to 4.7x against bash-only. | The efficiency case: fewer turns and fewer tokens at no loss. Also a caution we take seriously: **consistency came from structure**. Our bet is that the type system supplies that structure inside code mode; the paper does not test it. | The task is fixing code in repositories, not authoring configuration. "Similar performance" means code mode is not shown to be *more correct*, only cheaper. The page does not discuss typed SDKs. |
| **"The Code-Only Agent"**, a blog post on rijnard.com ([link](https://rijnard.com/blog/the-code-only-agent)) | A single `execute_code` tool, enforced, so that every action (even reading) is a program. The program is a repeatable, reviewable artifact whose behavior follows the language's semantics, a "code witness", and loops replace the model's case-by-case tool choices. Large outputs go to a file rather than back into context. | **Reviewability and repeatability**: the program is a witness of what the agent did, which is what the review step of #320 wants (§5.6). Bounded output is adopted (§4.2). | It says nothing about sandboxing or safety of model-written code, which is our central engineering problem. Its guarantees hold "subject to the semantics" of the language, and it calls itself less suited to non-computable tasks. It argues for code as *the* artifact; D1 deliberately does not (§8). |
| **daily.dev's summary of Philipp Schmid's "Code Mode" article** ([link](https://daily.dev/posts/why-agents-are-soon-only-going-to-have-1-tool-v3krvnqkn)) | Agents are moving from many tools to one `exec(code)` tool: the model writes a short TypeScript or Python script that chains and filters actions in a sandbox. Reports that Factory's Droid harness gained 3.5 points on Terminal-Bench and cut model spend 15% after moving to it, and that several agent frameworks support code-based tool execution. | The naming ("code mode"), TypeScript as a common choice, and the one-tool direction. A commenter's point is **useful for us**: a script that throws halfway through a batch of side-effecting calls leaves the agent not knowing what completed. In code mode as designed here, writes are *staged*, never performed, so there is no partial completion (§5.2). | It is a **secondary summary** of an article this work did not read; the benchmark figures are one vendor's own harness change, without a described method (the summary's own benchmark name could not be verified); it is not evidence about authoring tasks. |

**Takeaway.** The evidence for code as the action format is strongest on **efficiency** (fewer steps, fewer tokens) and on
**feedback-driven self-correction**, and weakest exactly where Materione's risk is: safety of executing model-written code, and
correctness on a typed configuration domain. The design therefore leans on what the sources do not provide: a sandbox with no
ambient authority (§4), effects that are staged operations (§5), static types generated from the server (§3), and the project's
own benchmark (#320's 20 to 30 tasks, §11) to measure whether typed code mode beats a tool-per-operation baseline here.

### 2.5 Why TypeScript, and why not Java

- **The type checker is the feedback loop.** TypeScript gives errors with positions and valid alternatives before anything is
  sent. Python would need a separate checker bolted on; JSON and YAML have no types at all.
- **It already lives in the editor.** The cloud side and the editor SPA are TypeScript (web.md §1, §6). The SDK is generated from
  the same self-description the editor's forms render, in the same language, in the same build. The browser already ships a
  JavaScript engine, and a WebAssembly one is a small static asset (§4.3).
- **Java is the wrong place and the wrong tool (C4).** The plugin is Java 21 and runs on the game server. Running agent code
  there contradicts "no server-side execution" (§8), and the JVM no longer offers an in-process sandbox to rely on: the
  `SecurityManager` was deprecated for removal and is disabled in recent JDKs, so isolating untrusted Java would mean a separate
  process or container per program, with the operational weight that implies. Java keeps its role: the registered primitives and
  the pipeline (C6).

---

## 3. The SDK

The SDK is the program's whole world. It has a read side, a write side, and a generated type surface that is the only thing
that knows what *this* server supports.

```
 registries (D5)                    connected plugin                 editor page (browser)
 ┌──────────────────┐   describe   ┌───────────────┐   ┌─────────────────────────────────────────────┐
 │ content types    │─────────────►│ authoring.    │──►│ SDK generator ──► materione-sdk.d.ts (types)│
 │ actions, conds,  │   + caps     │ describe,     │   │        │                                    │
 │ triggers, mech., │              │ browse.types, │   │ SDK core (fixed, versioned with the editor):│
 │ presets, addons  │              │ capabilities  │   │  handles · read API · stage · op recorder   │
 └──────────────────┘              └───────────────┘   └─────────────────────────────────────────────┘
```

### 3.1 Two layers: a fixed core and a generated surface

| Layer | What it is | Where it comes from | Changes when |
|---|---|---|---|
| **Core** | Handles and references (§3.4), the read API (§3.2), the stage recorder that turns writes into an operation list (§3.3), diagnostics, limits, and the mapping from each SDK write to an editor operation. Hand-written TypeScript. | The editor build (`webui/`, web.md §6). | The editor is released. |
| **Generated surface** | The `.d.ts` that types the core for *this* server: one record type per content type, one builder per action, condition, trigger and mechanic, one function per preset, the unions of valid ids, and the documentation text. | Registry self-description (`authoring.describe`, `browse.types`, the capability list, the preset and provider catalogs) read when the session connects, and again after a reload that changes a registry. | The connected plugin, its addons, or its packs' presets change. |

The generator is a pure function from a self-description document to text, so it is **golden-file tested** (§11) against recorded
registry snapshots like every other compiled output in this project. It reads only self-description; it contains no list of
content types, actions or mechanics. That is what makes D5 true for agents: a registry entry becomes an SDK member without SDK
code (§3.6).

**Self-description becomes documentation.** A parameter's description, its `activation` sentence, constraints, defaults, and
`since` version become TSDoc on the generated member. The type declarations are the reference an agent reads; there is no second
document to keep in step (master §5.6).

**Schema to type mapping** (illustrative; the schema vocabulary is the registry's, web.md §6):

| Self-description | Generated TypeScript |
|---|---|
| string, number, integer, boolean | `string`, `number`, `number` with a range in TSDoc, `boolean` |
| enum | a union of string literals |
| `formatted-text` / `formatted-lines` (master §5.7) | `MiniMessage` / `MiniMessage[]`, a branded string; its tag syntax is checked when staged |
| reference to a resource type | `Ref<"item">`, `Ref<"gui">`, ... (§3.4) |
| nested object, list of nested | an object type, an array of it; required and optional as declared |
| a polymorphic entry (an action step, a mechanic entry) | a discriminated union keyed by the registry id |

### 3.2 The read API

Reading is **part of the SDK**, not a separate raw channel (C3). It is the editor's existing read operations behind typed
functions, so it needs no new endpoint and carries no new permission: reads require `materione.editor` alone (master §9.2).

| Area | What a program can ask | Backed by |
|---|---|---|
| Catalog | The packs, the content types, the registered actions, conditions, triggers, mechanics, presets, currencies and providers, with their descriptions. `m.docs` searches the same text the types carry. | `listPacks`, `authoring.describe`, `RegistryView` |
| Resources | List and filter, and **get one as a typed object**: its parsed tree typed by its content type, its revision, its computed metadata (including inheritance and allocated ids). | `listResourceSummaries`, `getDocument`, `computed` |
| References | "Where is this used?" and the graph around a resource: dead content, broken references. | `findReferences`, `queryGraph` |
| Validation | The current diagnostics of a resource or a pack: code, severity, message, source location, target field. The same shape as a dry run's (§5.3). | diagnostics, `validateDraft` |
| Runtime state | Declared variables and their values, and **traces**: why a rule did or did not run. | `variables()`, `LogicApi.traces()` (logic.md §7.6) |
| History | A resource's revisions and who changed it. | `getHistory` |

Rules of the read side:

1. **Typed objects, not text.** `get` returns a value typed by the content type's generated record. There is no `readFile`. A
   **source excerpt** (the lines around a diagnostic, read-only) is available for explaining a diagnostic and for the review
   diff; it cannot be fed back as an edit (§3.3).
2. **Reads see the stored state.** A program that creates an item and then reads it does not see its own staged edit; it holds
   the handle `create` returned. Combining staged edits is the server dry run's job (§5.3). This keeps the SDK from growing a
   second implementation of the engine.
3. **Reads stay in the browser.** A program can read a hundred resources and return a five-line summary to the model. What
   leaves the page for the model provider is what the program returns and the tool result carries (bounded, §4.2), not the
   session's content (§4.5, master §9.2).
4. **Reads are budgeted** (§4.2) and each is an ordinary editor operation over the encrypted channel, counted against the
   relay's quotas like any editor traffic (web.md §3.3).
5. **Some reads need an operation the editor protocol does not expose yet** (traces and runtime variables are on the API, not
   necessarily on the wire). They are listed in §10; the SDK exposes only what the connected plugin advertises (§7).

### 3.3 The write API

A write call **stages an operation**; it performs nothing. The SDK has no function that writes a file, calls the pipeline, or
applies anything.

| Family | Stages | Mirrors |
|---|---|---|
| Resources | create (from the type's scaffold, optionally from a template), duplicate, set fields, remove a field (reset to inherited), move, delete | `createResource`, `duplicateResource`, `setFields` (with `remove`), `moveDocument`, `deleteDocument` |
| Templates | link, detach, save as template | `detachTemplate`, `saveAsTemplate` |
| Behavior | add, patch, move and remove rules and steps; add, rename and remove flows; instantiate a recipe | the twelve `LogicEdit`s, `instantiate` (api.md §3.12) |
| GUI | the GUI builder's edits | `GuiEdit` (api.md §3.9) |
| Presets | declare a preset on a resource, with its parameters (§3.5) | `setFields` on the declaration (master §14.7) |
| Summary | a human-readable summary of the change, for review | not an operation; carried with the list |

Properties the write API holds, each a consequence of a decision:

- **It is a typed projection of the editor's operations (C1).** Every staged operation is one the editor already sends, with
  the same revision guards. There is no operation the editor lacks, so the editor's validation, audit and permissions are the
  program's.
- **It cannot reach what the schema does not describe (§5.6).** No `putText`, no free-form key. A field the registry does not
  describe is unreachable, as in the studios ("N fields are only editable in Advanced Files"). Unknown fields in a document
  survive every edit, as for the editor.
- **It is incapable of apply (D6).** The recorder yields an operation list and ends. Applying is a human gesture in the editor
  UI (§5.6) on that exact list.
- **Edits are structural and minimal (§5.6).** A `setFields` of one value changes that value's lines and nothing else, so the
  diff the owner reviews is the minimal diff a hand edit would make.

### 3.4 Typed handles and references

A reference is a typed value, not a string the agent hopes is right.

- **`Ref<T>`** is a branded `"pack:id"` for a resource of content type `T`. `create` returns the handle of the resource it
  staged; passing it where a `Ref<"item">` is expected type-checks, and passing a `Ref<"gui">` there is a compile error.
- **`m.ref.item("rpg:bloodfang")`** looks an existing resource up by id. It resolves against the stored state when the program
  runs and **fails with a diagnostic naming the id and the nearest existing ids** if there is none, rather than staging a
  dangling edge. Whether the id is a staged one is checked against the program's own handles.
- **Small closed sets are literal unions.** Event ids, action ids, mechanic ids, currencies and preset ids are generated as
  unions, so a typo is a compile error with the valid choices. **Open sets** (the ids of resources) are not unions: a pack can
  hold thousands, and their existence is a runtime check, not a type.
- **Handles carry no capability.** A handle is a name. Holding one does not grant a read or a write the actor's permissions
  would not.

### 3.5 Primitives, presets and addons in the SDK

How D12 appears to the agent, with the by-hand rule (R4) intact:

| Surface | In the SDK | Notes |
|---|---|---|
| **A primitive** (a registry entry that passes U1 to U5) | A generated member: an action, condition, trigger or content-type builder with typed parameters and its TSDoc. | Because it is self-describing (U4) it appears with no SDK change. |
| **A missing primitive** | **Absent**, so using it is a type error (§9.2). | This is the benchmark's signal: the error names the gap (D12: "the fix is to add or generalize the primitive, never to add the feature"). The agent reports it to the owner and does not work around it (§6). |
| **A preset** (D12 layer 2) | A function `m.presets.<id>(target, params)` with parameters typed from the preset's schema (R6). It stages the **declaration** only (R2, §14.7: "declared by reference", parameters only), never the preset's expanded logic. | The agent chooses the route the owner chose: declare the preset, or define the behavior with primitives. Both are honest at the file (D12). |
| **A preset's expansion** | A read-only view, `m.read.expand(ref)`, for debugging and for the agent. | Requires the read-only expanded view of master §14.7(iv); until then absent. |
| **An addon's primitive** | A member like any other, tagged with the addon that registered it (`registeredBy`) in its TSDoc, and namespaced by the addon's id. | C6. The addon needs no SDK code; it may ship better descriptions. |
| **A built-in pack's content** | Readable and referenceable (`m.ref.gui("materione:backpack")`); never writable (master §7.3). | A stage into a built-in pack is refused (§5.3). |

### 3.6 What an addon author does

Registers primitives through the API as today (api.md §3.2, §3.11). Writes good `describe()` text, because it is the
agent's documentation. Nothing else: no TypeScript, no SDK plugin. After the addon registers and the registry changes, the
editor regenerates the surface (§7). An addon that registers a *feature-shaped* entry gets no exemption: it fails the review gate
of master §3.1 like a built-in does.

### 3.7 Getting the surface into the model's context

The type checker always has the whole `.d.ts`; the model's context need not. The agent starts with a short index (content types,
registered action, condition and trigger ids, preset ids, a few lines each) and pulls declarations by running a small read-only
program (`m.docs.type("item")`, `m.docs.search("bleed")`). A type error returns the valid alternatives, so the checker teaches
the rest. How large the index may be and what lives in a system prompt versus a skill is a tuning question for the benchmark
(§11) and is not decided here.

---

## 4. The sandbox, and where it runs

### 4.1 The requirement

The sandbox runs **model-written, untrusted code**, in the page that holds the editor's session: the signing key (the
IndexedDB-persisted key of a trusted workstation, web.md §5.2), the session capability and the decrypted content. It must be
unable to reach any of them, or the network, or the DOM.

> **Contract.** The program receives exactly one object, `m` (the SDK), and nothing else. It has **no ambient authority**: no
> network, no storage, no DOM, no timers beyond what `await` needs, no module loading, no access to the editor's state, keys,
> frames or channel. Its only outputs are the staged operation list, bounded logs, and one bounded return value.

### 4.2 Limits

Enforced by the host, with the numbers decided at implementation and fixed by fixtures (§11):

| Limit | Why |
|---|---|
| CPU time (an interrupt deadline) and a memory cap | A runaway loop cannot freeze the editor; the host terminates the sandbox |
| Number of read calls, and bytes read | Reads are encrypted relay traffic with quotas (web.md §3.3) |
| Number of staged operations, and documents touched | Matches the transaction's cap (today 50 documents, §5.4) |
| Size of logs and of the return value | Bounds what reaches the model, and what could leave the page (§4.5); large results are summarized in the program, not dumped, which is the practice of the code-only agent (§2.4) |
| Determinism | `Date` and `Math.random` are fixed or seeded, so the same program against the same state yields the same operation list. A result an author can reproduce is a result a reviewer can trust |

### 4.3 Options

All options run in the browser. Server-side options appear only to be excluded.

| Option | Isolation | CPU and memory control | Cost to us | Verdict |
|---|---|---|---|---|
| **A. A WebAssembly JavaScript engine (QuickJS compiled to WASM), inside a sandboxed iframe** (`sandbox="allow-scripts"` with no `allow-same-origin`, so an opaque origin; a CSP with `connect-src 'none'`) | **Strongest.** The engine is a separate interpreter with no globals but those the host injects; the iframe adds an origin boundary, so even an engine bug lands in an origin with nothing in it. | An interrupt handler and a memory limit are part of the engine's embedding interface; the host can also destroy the iframe. | A small WASM asset, served as a static asset (free, unlimited; web.md §3.3). Slower than native JS, which does not matter for programs that stage edits. Async host calls need the engine's async embedding. | **Recommended.** |
| **B. A Web Worker in a sandboxed iframe, running the browser's native JavaScript** | Good. No DOM in a worker; the iframe's opaque origin and CSP remove network and storage. But the worker's global scope still carries ambient browser APIs that must be removed or blocked one by one, so isolation depends on a deny list. | CPU: `terminate()`. Memory: not capped by the platform. | None. Fastest. | A fallback if A's embedding proves too costly. Weaker by construction: a deny list can miss an API a later browser adds. |
| **C. A plain Worker or `new Function` in the editor's own origin** | Weak. Same origin as the signing key, storage and channel. | `terminate()` only. | None. | **Rejected.** |
| **D. The Blockbench pattern: a same-origin iframe with a postMessage bridge** (web.md §6) | Adequate for *our* code, which is why Blockbench uses it; it is not a boundary against hostile code. | None. | None. | **Rejected** for untrusted code. Its bridge discipline (exact origin check, allowlisted, payload-capped messages) is reused for the host channel in A. |
| **E. Execution on Cloudflare** (a Worker, a dynamic isolate, a container) | Strong. | Platform-enforced. | **Per-invocation compute on an account with no spend cap.** | **Excluded** by decision and by guardrail (§4.5). |
| **F. Execution on the game server** (GraalJS, a JVM-embedded engine) | Needs a JVM sandbox the platform no longer provides (§2.5). | Hard. | Plugin weight; threading rules (D8). | **Excluded** (C2, C4). |

**Recommendation (for the owner, §12.1): A.** QuickJS compiled to WebAssembly, in a sandboxed opaque-origin iframe, driven from
the editor by a postMessage host channel with the discipline of the Blockbench bridge. Reasons: **no ambient authority by
construction** (the program sees only what the host injects, rather than the browser minus a deny list), **resource limits that
are part of the engine** rather than of the browser, and a boundary in depth (engine, then origin). B is the fallback. The
type checker (§5.1) is a different component: it only *parses* untrusted text and never executes it, so it runs in an ordinary
Worker of the editor.

### 4.4 The host channel

The sandbox talks to the editor host through a message port with a small, closed vocabulary: `read(op, args)`, `stage(op)`,
`log(line)`, `done(result)` and, from the host, the answers. The host validates every message's shape and size and holds the
counters of §4.2. The host, not the sandbox, calls the editor's operations: **a read call is a request the host chooses to
make**, so the sandbox cannot make the editor send an operation the SDK's table does not map (§7).

### 4.5 No server-side execution, no new endpoint, cost guardrails

- **Where it runs.** In the cloud editor's page only (C5, master §14.8). A program runs in the browser of the person who asked
  for it, against the session they opened in game (D11).
- **No new endpoint.** The only network traffic a program causes is the editor's own operations over the existing encrypted
  channel. The relay still sees ciphertext only; the protocol, handshake, permissions and capability URL are unchanged.
- **No Cloudflare compute.** Nothing in this design is a Worker invocation, a D1 query beyond editor traffic, Workers AI, a
  queue or a container. The WASM engine and the TypeScript checker are static assets (web.md §3.3: free and unlimited). A PR
  that implements this states its cost model per the guardrails, and it should read "no change".
- **The model call is the one thing that leaves the page**, and it is not decided here (§12.2). Whatever the owner chooses, two
  facts bind it: a call that leaves the browser is a place where content leaves the end-to-end boundary of master §9.2 (code
  mode narrows what leaves, §3.2 rule 3, but does not remove it), and any cloud-side LLM call or billing is bound by the cost
  guardrails (Workers AI is off-limits, no plan purchases, no public endpoint without rate limits).
- **Threats this design adds** (to be added to the threat model, web.md §10): *a program escapes the sandbox*, mitigated by §4.3 A
  and by the staged-only write model (even a sandbox that misbehaves has no apply); *prompt injection through content the agent
  reads* (an item lore that says "ignore your instructions"): reads are data, the program's effects are limited to staged
  operations, and **nothing applies without a human reviewing the diff** (§5.6).

---

## 5. The execution model

One program is one proposal: it runs once, yields one operation list, and that list is validated, reviewed and applied as one
transaction, or discarded. The program is **never re-run to apply**.

```
 agent writes TS ──► 1 transpile + type-check ──► 2 run in sandbox ──► 3 server dry run ──► 4 review ──► 5 apply
                       (editor, Worker)             reads live; writes      (authoritative:        (human, diff)   (transaction,
                       TS errors → agent            stage ops               MTR-#### diags)                         reload, audit)
                              ▲                          │                        │                       │
                              └──────────────── diagnostics return to the agent ──┴───────────────────────┘
                                          (each failure: the agent writes a NEW program and the cycle restarts)
```

### 5.1 Type-check before run

The editor transpiles the TypeScript and type-checks it against the generated surface (§3.1) **before** anything executes. A
type error is returned to the agent with its position and message and the program does not run. This is where the *shape*
mistakes die: a wrong parameter name, a value outside a union, a reference of the wrong type, **and a primitive the server does
not have** (§9.2).

### 5.2 Run: reads execute, writes stage

In the sandbox the program's reads run against the live session (read-only editor operations); its writes append to the
recorder. A thrown error or an exceeded limit ends the run with a diagnostic and **no operations**. There is no half-performed
state to reason about: unlike a script that performs side effects as it goes, a program that fails at line 40 has changed
nothing (the concern the commenter on the summary raised, §2.4).

The run's output is a **`ProgramResult`**: the operation list (each operation tagged with the source line that staged it), the
client-side diagnostics, the logs, and the bounded return value. The client-side checks are the schema checks the editor's forms
already do and the reference checks of §3.4. They are conveniences: they are not authoritative.

### 5.3 Dry run: the server decides

The operation list is sent to the plugin for a **dry run**: every operation is applied in memory under the store lock, the
validation stages of master §5.4 run on the result (syntax, schema, semantic, platform, build, including the flow compiler for
behavior), and nothing is written. The answer is structured: the diagnostics with stable `MTR-####` codes and source locations,
and **the resulting documents**, from which the editor draws the diff. A dry run never mutates server state (an acceptance
criterion of #320).

This stage is the authority. A program can type-check and run and still be refused here: a reference that does not resolve after
the edits are combined, a flow the compiler rejects, a note-block state that cannot be allocated, a stage into a built-in pack
(read-only, master §7.3), an operation the actor's permissions do not allow (the dry run checks each operation's node:
`materione.edit.<pack>`, master §9.2).

### 5.4 One program, one transaction

The atomic unit today is `authoring.bulk` (api.md §3.3): all-or-none, revision-checked, up to 50 documents **of one pack**. The
behavior and GUI edits each write one document at one revision. **Recommended:** a program may write to **one pack**, and
touches at most the transaction's cap of documents; it may read any pack and reference any pack's resources. Staging into a
second pack is a diagnostic ("this change needs two packs; split it into two programs"). This follows from what the server can
guarantee atomically today; lifting it is a server change (§10, §12.7), not an SDK change. The server needs one transaction
operation that takes the *whole* heterogeneous list, which is §10 item 1.

### 5.5 Programs are one-shot: iterating and building on prior work

- A **proposal replaces the previous one.** It is not stacked. The agent fixes a refused proposal by writing a corrected whole
  program against the stored state, which is why a program is short (tens of lines): it describes a change, not a repair.
- **Building on an applied change** is a new program that reads the state the apply produced. A multi-step plan ("first the
  item, then its GUI") is several programs, each reviewed and applied. Whether several proposals may be stacked in one review
  is §12.6.
- **Nothing is carried between programs except the stored files.** No variables, no module, no cache. That is C2 made concrete,
  and it is why SDK changes cost nothing durable (§7).

### 5.6 Review, then apply

The proposal is shown to the owner **in the editor**, not in the chat transcript alone (the flow of #320, step 6):

| Shown | From |
|---|---|
| The agent's summary of the change, in domain terms ("adds 1 item, 1 GUI, 2 rules; changes nothing else") | `m.summary(...)` |
| The **diff** of the resulting files: only what changes, minimal | the dry run's resulting documents against the stored ones |
| **Impact**: what else references what changes | `findReferences`, `queryGraph` |
| Diagnostics: errors block, warnings are shown | the dry run |
| The program text, collapsed, as the witness of how the list was produced | the proposal |

**The owner approves with a button the model cannot press.** The SDK has no apply, the chat tool has no apply parameter, and the
approval is a UI event. On approval the editor sends the **same operation list** (not the program) as a single transaction with
each document's `expectedRevision` from the dry run, so what was approved is what is applied (D6, master §6.4):

- It is **transactional**: all-or-none under the store lock; a failure leaves no partial files, and the pipeline's shadow-swap
  rolls back an apply that fails to load (master §6.4).
- It is **audited**: the audit entry carries the session's actor (the same permissions, no new actor kind, no new scope), plus
  an `origin` tag `code-mode` and the program's digest. Recommended; §10 item 4.
- It is **reversible**: a transaction identifier lets one request restore each touched document's prior revision from the
  revision journal (§10 item 3), as a new revision, not by deleting history.
- **A stale revision** (a human edited a file between dry run and apply) is `MTR-0034` for that document and the whole
  transaction is refused. The agent is told, writes the program again against the new state, and the owner reviews again. This is
  **rebase by re-running**, which is only cheap because the program is short, one-shot and deterministic (§4.2).

Whether some proposals may skip human review is a question for the owner (§12.5). **Recommended default: never.**

---

## 6. How errors reach the agent

Every failure returns to the agent as **structured diagnostics**, so it acts on a record and not on a log. One shape for all
phases, the project's own (`{code, severity, message, resource, file:line, hint}`, plugin.md §5), plus the phase and the program
line:

| Phase | Source | Code | Example | The agent's right move |
|---|---|---|---|---|
| **1. Type-check** | TypeScript | `TS####` | `Property 'commands' does not exist on type 'Materione'` | If a *primitive* is missing (§9.2), report the gap; otherwise fix the call |
| **2. Sandbox** | the SDK core and the host | an SDK code, allocated at implementation | a thrown error with its source-mapped stack; a limit exceeded; `m.ref.item("rpg:nope")` with the nearest ids; staging into a second pack | Fix the program |
| **3. Dry run** | the plugin's validation | stable `MTR-####` | a schema error on a staged field; a flow the compiler rejects, with a step path; a reference that does not resolve | Fix the program; the `target` names the field, and the operation's tag names the program line that staged it |
| **4. Apply** | the store and pipeline | `MTR-####` | `MTR-0034` (stale revision); a pipeline load failure with a rollback | Re-run against the new state |

Two rules:

1. **Source mapping.** Each staged operation carries the program line that staged it, so a server diagnostic about
   `behavior.2.do.0` is reported at the `rule.add(...)` that created it.
2. **A missing primitive is reported, not worked around.** The agent's instructions say that a type error on a *member that does
   not exist* is an answer about the server, not a typo: say which capability is absent and stop that part of the plan, or build
   what exists. This is what makes #320's benchmark honest: a task that needs a gap fails on the gap, visibly, and the count of
   such failures is the measure of D12's debt. SDK diagnostic codes are **stable API** like `MTR-####` and are documented with
   them when they are allocated (plugin.md §5); this document names classes, not numbers.

---

## 7. Versioning: the SDK, the plugin and the editor

Three things move on their own schedules (web.md §6, *Versioning*): the plugin jar (registries, operations), the editor build
(SDK core), and the packs. The existing mechanism covers the SDK because **every SDK write is an editor operation**:

| Concern | Rule |
|---|---|
| **The generated surface is a function of the connected jar** | It is generated per session from that plugin's self-description and capability list (§3.1). A newer editor with an older jar generates a smaller surface. A member whose operation the jar does not advertise is not generated, so the failure is a **type error** ("no such member"), never a runtime error mid-apply. |
| **The core's operation table is checked by the compatibility gate** | The map from SDK writes and reads to editor operations (`webui/src/lib/codemode/`) is scanned by `scripts/compat/editor-compat.mjs` the way it scans the editor's calls today: it fails when the core calls an operation a supported release does not register, unless the operation is gated on a capability in `fixtures/protocol/editor-op-gates.json` that the SDK checks (`hasCapability`). Extending the script to this table is part of the implementation. |
| **New capabilities** | Code mode's server operations (the transaction with a dry run, §10) are introduced behind a capability the editor checks. No capability, no code mode for that jar: the chatbot says so. It does not fall back to raw writes (§8). |
| **Core semver** | The core has its own version, exposed to the program (`m.sdk.version`) and recorded in the proposal. Because programs are one-shot and stored nowhere, **a breaking change to the SDK breaks no stored artifact**; only the agent's prompt and examples must follow. This is the cost D1 and C2 buy. |
| **Content schema versions** (master §5.5) | The SDK writes the current schema of the connected plugin, generated from it. Migrations are the plugin's. |
| **Registry change during a session** | After a reload that changes a registry (an addon added or removed), the editor regenerates the surface and re-checks; a proposal computed on the old surface is re-run, not patched. |

The release gate that today asks "does this editor still talk to every supported jar?" gains one more question, "does the SDK
still type-check the benchmark corpus against every supported jar's recorded registry?" (§11).

---

## 8. What is explicitly out

| Out | Why |
|---|---|
| **Runtime scripting.** A program is never run by the server, never persisted as behavior, never invoked by a trigger. | D4: one behavior system, and it is data. D12 test U5. §2.3. |
| **Arbitrary JavaScript on the server** in any form: an eval action, a scripting mechanic, a "custom code" field. | C2. A hole here recreates the thing Materione replaces. |
| **Raw file writes.** No SDK path writes YAML text, writes a path, or reaches an unlisted key. | Lossless round-trip (§5.6), the schema as the only schema, and the revision guard. The raw `putDocument` stays the editor's T4 tool. |
| **Code as the stored source of a feature.** No `program.ts` in a pack, no regeneration of files from code. | D1: files are canonical, and a code source would be a second truth. Programs are one-shot (C2). |
| **A program that applies itself**, or a chat tool that applies. | D6 and the review step of #320: the human approves a diff (§5.6). |
| **Network, storage or DOM access from a program**, and reading anything outside the SDK. | §4.1. |
| **Execution on Cloudflare or on the game server.** | §4.3 E and F; the guardrails (`CLAUDE.md`). |
| **Asset generation and binary uploads from a program** in this design. | Binary assets are a separate, non-atomic write (web.md §6: an asset copy is an ordinary write made before the bulk call); #320 lists asset generation as open. A program may *reference* assets the owner has uploaded. |
| **A standalone MCP server for external agents.** | Master §14.8: not part of the decision. §12.3 asks whether the SDK is published later. |
| **Anything that relaxes D1 to D12.** | A program is a producer of the editor's operations, nothing more. |

---

## 9. A worked example

> Shape, not syntax. Names are illustrative; the real ones are generated (§3.1). The pack is `rpg`.
> **Lines marked ★ use surface the audit classifies as a gap today** ([`primitives.md`](primitives.md) §4.3, §4.4, §5): they do
> not type-check against a server that has not shipped those primitives, which is the point of §9.2.

### 9.1 The Bloodfang sword (#320)

The prompt: a sword with a custom model; hits apply a bleed that scales with the sword's level; the sword gains XP from kills and
reaches level 5; right-click while sneaking opens a GUI with level, XP progress and a 500-coin upgrade button; only players with
`rpg.weapons` may use it.

```ts
// Program 1 of 1. Writes only to pack "rpg"; reads anywhere.
const rpg = m.pack("rpg");

// Read first: what do we have to build on?
const coins = m.read.currencies().find(c => c.id === "coins");
if (!coins) m.fail("This server has no 'coins' currency; the upgrade button needs one.");

// The model is an asset the owner uploaded in the editor; a typed reference, checked at run time.
const model = m.ref.asset("models/bloodfang.json");

// State: level and XP live with the sword, not the player.            ★ stack-held state (primitives.md §5.1, §5.2)
const level = rpg.variables.create("bloodfang_level", { type: "integer", holder: "item", default: 1, min: 1, max: 5 });
const xp    = rpg.variables.create("bloodfang_xp",    { type: "integer", holder: "item", default: 0 });

// The item: appearance (model), metadata (permission), behavior (below).
const sword = rpg.items.create("bloodfang", {
  name: "<gradient:#8b0000:#ff3030>Bloodfang</gradient>",
  material: "netherite_sword",
  model,
  permission: "rpg.weapons",
});

// Bleed on hit, scaled by level; XP on kill.                          ★ combat triggers and entity verbs (primitives.md §4.3, §5.5, §5.7)
sword.rules.add({
  on: m.triggers.entityHit,
  do: [ m.actions.damageOverTime({ amount: m.expr`1 + ${level} * 0.5`, seconds: 5 }) ],
});
sword.rules.add({
  on: m.triggers.entityKill,
  do: [ m.actions.setVar(xp, m.expr`${xp} + 10`) ],
});

// The GUI: level, XP progress, an upgrade button that costs 500 coins.
const menu = rpg.guis.create("bloodfang_menu", { container: "chest_3", title: "<red>Bloodfang</red>" });
menu.layers.addText({ slot: 4, text: m.expr`"Level " + ${level} + " / 5   XP " + ${xp}` });
menu.layers.addButton({
  slot: 22, item: "minecraft:nether_star", name: "Upgrade (500 coins)",
  rules: [{ on: m.triggers.guiClick, do: [
    m.actions.require(m.conditions.hasMoney({ currency: coins.id, amount: 500 })),
    m.actions.takeMoney({ currency: coins.id, amount: 500 }),
    m.actions.setVar(level, m.expr`min(${level} + 1, 5)`),
  ]}],
});

// Right-click while sneaking opens the GUI.
sword.rules.add({
  on: m.triggers.click({ detail: "right" }),
  if: m.expr`player.sneaking`,
  do: [ m.actions.guiOpen(menu) ],
});

m.summary("Adds the Bloodfang sword with a level and XP, a bleed on hit, a level-up GUI costing 500 coins, gated by rpg.weapons.");
```

What happens:

1. **Type-check.** Against a server that has shipped the primitives behind the ★ lines, this compiles. Against one that has not,
   each ★ line is an error of the kind §9.2 shows, and the agent knows which capability to name.
2. **Run.** The two reads (`currencies`, `ref.asset`) execute; if `coins` were missing, `m.fail` ends the run with a diagnostic and
   no operations. Otherwise the recorder holds about six operations: three creates and the edits that fill them.
3. **Dry run.** The plugin applies them in memory, compiles the flows, checks that `sword`'s model reference resolves, and
   returns the resulting documents. A bleed rule whose `on:` carries no entity would come back as a diagnostic at the line that
   staged it.
4. **Review.** The owner sees the summary, a diff of `items/bloodfang.yml`, `guis/bloodfang_menu.yml` and two variable files,
   the program collapsed, and "nothing else references any of this". They press Apply.
5. **Apply.** The same list goes as one transaction; the pipeline reloads; the audit entry reads *code-mode*, with the digest.
6. **Debug and evolve.** "Bleed isn't working": a program reads the rule's traces (`m.read.traces(...)`) and returns one line
   per rule that ran or did not. "Make bleed stack up to 3": a new, short program that sets one field and one rule's step,
   reviewed and applied alone, with impact analysis showing what references the changed variable (#320 steps 8 and 9).

The same feature as an agent working from raw YAML would have written ~100 lines of text across four files, found out about a
mistyped action id from a server diagnostic after a round trip, and had no revision guard on any of them.

### 9.2 `/trade`: a missing primitive is a type error

D12's benchmark: build `/trade <player>` from content alone. Written as the agent would, against today's surface
([`primitives.md`](primitives.md) §3 lists what is missing):

```ts
const rpg = m.pack("rpg");

rpg.commands.create("trade", { args: { other: "player" }, permission: "rpg.trade" });
//  ^^^^^^^^ error TS2339: Property 'commands' does not exist on type 'PackWriter'.

const offers = rpg.state.keyed("trade", { key: m.expr`${m.arg("other")}`, ttl: "60s" });
//                  ^^^^^ error TS2339: Property 'state' does not exist on type 'PackWriter'.

rpg.guis.get("trade").views.forEachViewer(offers, (v) => /* ... */ );
//                    ^^^^^ error TS2339: Property 'views' does not exist on type 'GuiWriter'.

m.actions.message({ text: "Trade request sent", target: "other" });
//                                               ^^^^^^ error TS2322: Type '"other"' is not assignable to type
//                                                      '"player" | "actionbar" | "broadcast"'.

m.actions.transfer({ from: m.holder.player("a"), to: m.holder.player("b"), items: offers });
//        ^^^^^^^^ error TS2339: Property 'transfer' does not exist on type 'ActionCatalog'.
```

Each error is a row of the audit table (commands as content, keyed state, views over state, target addressing, atomic transfer:
five of the nine needs), reported by the checker without a server round trip, and each says what the agent must **not** do:
fake it with a global variable and a session map. The agent's reply to the owner is the gap list, with what it *can* build today
(the request flow without the shared window, say) offered as a separate proposal. When the primitives land, the **same program
text** type-checks and the benchmark task passes with no engine feature named "trade". That is D12's test, run automatically.

---

## 10. What the server must add

Code mode composes the operations the editor already has; it does not need a new endpoint (§4.5). It needs these additions to
what sits *behind* the existing protocol. Each needs its own design and issue before code, like the directions in
[`primitives.md`](primitives.md) §5; none is designed here.

| # | Addition | Why | Today |
|---|---|---|---|
| 1 | **A transaction operation over a heterogeneous list, with a dry-run mode.** It takes the staged list (resource, behavior and GUI edits), applies it in memory under the store lock, runs validation, and returns diagnostics plus the resulting documents; without `dryRun` it writes all-or-none and enqueues one reload. | §5.3, §5.4: one program is one transaction. | `bulk` is all-or-none for resource documents of one pack (50 max). `validateDraft` is a draft check. Logic and GUI edits are single-document writes (`LogicEdit`, `GuiEdit`). There is no list that spans them with a dry run. |
| 2 | **Permission checks per operation** in that transaction, with a diagnostic per refusal. | §5.3 | Each operation checks its own node today. |
| 3 | **Transaction-level revert** by identifier, over the revision journal. | §5.6: "reversible" (#320 acceptance) | Per-document `getHistory` exists. |
| 4 | **An additive `origin` and `digest` in the audit entry.** | §5.6 | The audit has `actor`. |
| 5 | **Read operations on the wire** for traces and runtime variable reads, with their own permission nodes. | §3.2: debugging is #320 step 8 | `LogicApi.traces()` and `variables()` are API methods; the wire exposure is not verified here (§13). |
| 6 | **A read-only expanded view of a preset** (master §14.7(iv)). | §3.5 | Open. |
| 7 | **A capability** advertising the above. | §7 | The capability list exists (web.md §4.1). |

---

## 11. How it is proven

Under the project's rules (roadmap rule 2, the golden-file rule, `qa/README.md`):

- **Golden SDK output.** The generator's `.d.ts` for each recorded registry snapshot (`fixtures/`), including a snapshot with an
  addon-registered primitive, so §3.6 is tested rather than claimed.
- **A program corpus that only grows.** Positive programs (the Bloodfang shape) and negative ones (`/trade` against a snapshot
  without the primitives) with their expected diagnostics, type-checked in CI against every supported jar's recorded registry (§7).
- **Sandbox tests as ban tests.** A program that touches `fetch`, `globalThis`, timers, the DOM, `postMessage`, `import`, or
  loops forever, allocates without bound, or stages past the cap, each yields a diagnostic and no operations. The ban list is
  kept by tests, not by review goodwill.
- **Server tests.** Dry run mutates nothing (a property test over the store); the transaction is all-or-none with fault
  injection at each document; revert restores prior revisions; a stale revision is `MTR-0034` for the transaction.
- **The benchmark is the evaluation (#320).** The 20 to 30 tasks are run twice: with typed code mode, and with a
  tool-per-operation baseline built on the same operations. Measured: first-attempt success, fix iterations, human
  interventions, tokens and steps, regressions after follow-up changes, and **the count of tasks that fail on a missing
  primitive**. That second run is what turns §2.4's borrowed evidence into this project's own.
- **QA catalog entries** for the user-visible part (proposal, diff, approve, refusal), with `visual` tests for the review UI
  (`qa/catalog/`, `qa/README.md`).

---

## 12. Open questions for the owner

Not decided here. Each has a recommendation where this document has one.

1. **Sandbox technology.** Recommended: **A** (QuickJS compiled to WASM in a sandboxed opaque-origin iframe, §4.3), fallback B.
   The questions behind it: is a WASM asset of a few hundred kilobytes acceptable in the editor's static assets, and is the
   async host-call embedding of the engine acceptable, or would B's weaker, deny-list isolation be preferred for simplicity?
2. **Model provider and billing.** Unchanged from master §14.8 and still open. Constraints that bind any answer: the
   cost guardrails; a model call is where content leaves the end-to-end boundary; and the likely options are a key supplied by
   the user in the browser (no project cost, no cloud-side call, the call leaves the page directly) or a project-run proxy
   (cost, rate limits and a new public endpoint, all of which the guardrails make hard). Code mode lowers tokens per task by
   the research's account (§2.4) but this has not been measured here.
3. **Publishing the SDK for local or external agents later.** The core is DOM-free and the sandbox is a separate component so
   that the door stays open: the same SDK could run in a Node harness against a direct-mode session. That is a new trust model
   (an agent outside the browser needs its own handshake and approval) and is the "standalone MCP server" master §14.8 left out
   of the decision. Decide whether it is a goal, so the package boundary is drawn once.
4. **Where the type-check runs.** Recommended: **in the editor page, in a Worker**, with the TypeScript checker loaded as a
   static asset when the chatbot is first opened (no server cost, no round trip, full `.d.ts` locally). The alternatives are a
   stripped-types-only run (cheaper, loses the feedback loop this design is built on) or a checker running anywhere that
   costs compute. The size of the checker asset is the real trade.
5. **Autonomy.** Recommended: **human approval before every apply, always** (§5.6). Should an owner be able to enable
   auto-apply for a proposal whose dry run is clean and whose impact is empty? It would be an owner setting, never an agent
   one.
6. **Stacked proposals.** Should several programs be reviewable as one plan (an overlay across proposals) or is "one program,
   one review, one apply" (§5.5) the rule? Stacking needs the dry run to accept a prior unapplied list as its base.
7. **The one-pack rule and the 50-document cap** (§5.4): lift them in the server transaction, or keep them as a forcing function
   for small proposals?
8. **Program retention.** Is the program's digest in the audit enough, or should its text be kept (size-bounded) so an owner
   can read what produced a change months later? Keeping it makes the audit larger and starts to look like a second record of
   what the files already say (D1).
9. **D13 or an amendment to D12.** This document recommends a new decision (§3.2 of the master) because it binds a different
   thing (how agents author, and a ban on stored or runtime code) from D12 (what the engine ships). If the owner prefers it
   folded into D12, §3.2 moves under §3.1 as a third layer and the count stays at twelve.

---

## 13. Not verified

- The numbers of §2.4 are as the fetched pages report them: the arXiv page through a mirror, and the daily.dev page is a
  summary of an article that was not read. None was cross-checked against the full papers or the original article.
- Embedding details of QuickJS-in-WASM (the async host-call path, interrupt and memory limit APIs, bundle size), the behavior of
  workers created from a sandboxed opaque-origin iframe across browsers, and the TypeScript checker's size and cold-start time
  in a browser Worker were **not tested** in this design; §4.3 and §12.4 are recommendations until a spike measures them.
- Which reads exist on the editor wire today (traces, runtime variables) was read from `api.md` and `web.md`, not from the
  code (§10 item 5).
- The generated type surface for polymorphic entries (an action step as a discriminated union) and for the logic expression
  language (`m.expr`) is a direction; how well TypeScript can type-check an embedded expression string, versus checking it at
  stage time, is a design question of the generator.
- Whether the current `LogicEdit`/`GuiEdit` vocabularies and `bulk` can be driven from one in-memory transaction without
  reworking their revision handling was not checked against the code.
