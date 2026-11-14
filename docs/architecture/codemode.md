# Code mode: how an agent authors in Materione (D13)

> **Scope.** [D13](../architecture.md#32-d13-in-full) says an agent changes Materione by **writing a TypeScript program against a
> typed SDK** that is generated from the registries, and that the program's only effect is **authoring operations**: the same
> validated edits the editor sends, through the one pipeline. This document is the design of that surface: why it is shaped this
> way and not as raw YAML or as many tools (§2), the SDK (§3), the sandbox and where it runs (§4), the execution model from
> program to applied change (§5), how errors reach the agent (§6), how the SDK is versioned against the plugin and the editor
> (§7), what is explicitly out (§8), a worked example (§9), what the server must add (§10), how it is proven (§11), and the
> the owner's answers to its questions (§12).
>
> **Status.** **Design, docs only (#320).** The decisions of §1 are the owner's: C1 to C6 on #320, C7 to C14 on PR #326
> (2026-11-12, §12). The model provider is the one question still open (§12, master §14.8). Lines still marked "recommended"
> are this document's proposal on details the owner did not rule on. Of the server work in §10, items 1, 2 and 7 are built (#341); **the SDK generator is built (#337, §3.8)**, with its recorded registries, goldens and the first program corpus, and **the SDK core is built (#339, §3.9)**: handles, the read API, the stage recorder, the operation table; the sandbox, the type-check Worker and the rest of §10 are not. Code in this document is **shape, not syntax**: names are illustrative, and the
> SDK is generated, so its real spelling comes from the registries it is generated from (§3.1). §3.8 has the real spellings.
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
| **C7** | **The sandbox is QuickJS compiled to WebAssembly in a sandboxed opaque-origin iframe**, with a Worker in a sandboxed iframe as the fallback. | §4.3 |
| **C8** | **The SDK is not published for now.** Local or external agents are not a priority; no trust model for them is designed. | §8 |
| **C9** | **The type-check runs in the editor page, in a Worker**, with the TypeScript checker as a static asset. | §5.1 |
| **C10** | **The agent is trusted for the most part.** A session may create new content without approval. Changing or deleting **existing** content needs the owner's approval the first time for each object; once approved, later changes to that object in the same session need no new approval. | §5.6 |
| **C11** | **Permission is asked once.** One approval covers every existing object a plan names, across the programs of that plan; the owner is not asked again at each step. | §5.5, §5.6 |
| **C12** | **The one-pack rule and the 50-document cap stay for now**, open to lifting later if they become a problem. | §5.4 |
| **C13** | **The program text is kept** (size-bounded) in the audit, not only its digest. | §5.6, §10 |
| **C14** | **Code mode is D13**, a decision of its own, not a layer of D12. | master §3.2 |

C1 to C6 were decided on #320; C7 to C14 are the owner's answers on PR #326 to the questions of the first draft (§12). What this
document still proposes on details the owner did not rule on is marked **Recommended**.

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

### 3.8 As built: the generator (#337)

*As built (#337, M6.5 track A1).* The generator of §3.1 exists, with its recorded registries and goldens. It is the first piece of
SDK code; the core (#339, §3.9) and the type-check Worker (#342, §3.10) are built too; the sandbox is not (the server's transaction is, #341, §10).

| Piece | Where | What it is |
|---|---|---|
| **Surface document** | `webui/src/lib/codemode/generate/surface.ts` | One typed JSON value, `SurfaceDocument`, assembled by `assembleSurface(describe, capabilities)` from the reply of `authoring.describe` (content types with field schemas; the behavior vocabulary: actions, conditions, triggers with filters and context schemas, mechanics, expression functions, recipes, item, list and input sources, step kinds, rule and action options; the GUI components and containers) and the session blob's capability list. Its field names are the wire's, so the assembler is a tolerant reader and not a second schema: a malformed entry is dropped, a registry the server does not send is empty (a smaller surface, §7), and a jar that predates the capability list gives `capabilities: null` (the full surface is assumed). `fetchSurface(channel)` (`codemode/surface-source.ts`) makes the one existing call, `authoring.describe`. |
| **Generator** | `generate/generate.ts` | `generateSdk(surface): string`, pure, DOM-free, importing only the surface types. Deterministic: entries sorted by id, parameters in the registry's order, LF. |
| **Fixed core types** | `generate/core.d.ts` | Hand-written declarations of what the generated text refers to (`Ref`, `MiniMessage`, `Expr`, `ActionStep`, `TriggerBinding`, `RuleSet`, `m`, ...), with **empty interfaces the generated text merges members into** (`PackWriter`, `RefFactory`, `ActionCatalog`, ...). Types only. #339 owns it and may reshape it; the generator needs only the names it declares. It is excluded from the editor's own `tsconfig.json`, because it declares globals. |
| **Recorded registries** | `fixtures/codemode/registry/` | `plugin-current.json`, written by the JVM test `EditorDemoSnapshotTest` from the real `authoring.describe` op and `SessionBlob.CURRENT_CAPABILITIES` (so it cannot drift: a new action changes it there, and the generator golden then shows the SDK diff); `with-addon.json`, derived from it by a test helper (one fake addon's action, trigger and content type) and checked to equal that derivation. |
| **Goldens** | `fixtures/golden/codemode/*.d.ts` | The generated text for each snapshot, compared byte for byte (`codemode-generate.spec.ts`). Regenerate with `MATERIONE_GOLDEN_REGEN=true` (the vitest counterpart of `-Dmaterione.golden.regen=true`), review, commit. |
| **Program corpus** | `fixtures/codemode/corpus/` | `<task>.ts` with `<task>.expect.json` (registry, `pass` or `fail`, the diagnostics a failure must show). The first six: T04 (the daily kit, with `m.fn.now()`), T08, T11, T25 (pass), T27 (fails with TS2339 on `commands`) and T05 (fails with TS2322: the clock work added the `on_cooldown` rule option, but self-description describes it as a `raw` value, so a step list in it is rejected until a step-list parameter kind exists, #355). `codemode-compile.spec.ts` type-checks each with the real `typescript` package against the golden surface. |
| **Ban tests** | `codemode-generator-bans.spec.ts` | The generator source holds no registry id as a string literal (every id of both snapshots is compared), imports only `./surface`, and touches no DOM, storage or network global. |

**The mapping, as built** (§3.1's table with the real spellings):

| Self-description | Generated |
|---|---|
| `string`, `integer`, `decimal`, `boolean` | `string`, `number`, `number`, `boolean`; `min`, `max`, `unit`, `default`, `example` become TSDoc |
| `enum` | A union of the `allowedValues`; one with more than 16 values gets a named alias (`ItemFieldsMaterial`) |
| `reference` with a `referenceKind` | `Ref<"kind">`; without one, `Ref` |
| `string` whose widget hint is `formatted-text` | `MiniMessage`; a `formatted-lines` list is `MiniMessage[]`. Built with `m.mm`, so a plain string literal is a type error |
| `string` whose widget hint names a content type (`item`, `sound`, `variable`) | `string \| Ref<"that type">` (read from the registry, not listed) |
| `list` with `nested` | An array of an object type; a list of `referenceKind` `mechanic` is `MechanicEntry[]` and one whose nested `type` references `gui-component` is `GuiLayerEntry[]` (the two reference kinds that name a polymorphic registry) |
| `raw` | `JsonValue` |
| A list whose widget hint is `behavior-rules` | Not a field: the resource's handle gets `rules` (below) |

**Names.** The collection of a content type is its `directory` (`p.items`, `p.guis`, `p.currencies`); its record type is
`<Name>Fields` and its handle `<Name>Handle`. An action, condition, mechanic, trigger, GUI component, recipe or source is a method
of its catalog, named by the camel-cased local part of its id (`run_command` is `runCommand`, `gui.open` is `guiOpen`,
`materione:backpack` is `backpack`). Two entries whose local parts collide are told apart by their namespaces
(`exampleGreet`, `otherGreet`); an id without a namespace is never renamed. The registry id is always in the return type
(`ActionStep<"gui.open">`) and the TSDoc carries `@since`, `@registeredBy`, `@category` and, for an entry the server reports
unavailable (a provider that is not installed), a plain line `Unavailable on the connected server: <reason>` (not `@deprecated`: a missing provider is a configuration of the recorded server, not a verdict on the entry). A registry's ids are
also exported as literal unions (`ActionId`, `TriggerId`, `RefKind`, `Capability`, ...).

**A builder is called with its parameters, or with the one bare value the registry's `shorthand` names.**
`m.actions.takeMoney(250)` and `m.actions.takeMoney({ amount: 250, delay: 20 })` are both valid; the shorthand form exists only
when every other parameter is optional. Every action also takes the registry's `actionOptions` (`chance`, `delay`).

**Trigger-typed rules (benchmark S1, S7).** A trigger is a value built by calling its member, with its variant and filters:
`m.triggers.click({ detail: "right" })`, `m.triggers.guiClick({ layer: "buy", click: "left" })`; filters are typed from the
trigger's `filters` and are **not** widened to lists (the self-description does not say which accept one; §13). The result is a
`TriggerBinding<Id, Context>`, where `Context` is generated from the trigger's declared context schema **by the host that fires
it**: `{ item: {...}, furniture: {...} }`, each host's entry holding the variables whose `hosts` list is empty or names it, as a
tree of `ContextValue<T>` (`ctx.player.sneaking`). A resource handle's `rules` is a `RuleSet<"item">`, and

```ts
sword.rules.add(m.triggers.click({ detail: "right" }), (ctx) => ({
  if: m.expr`${ctx.player.sneaking}`,
  cooldown: 1000,
  do: [ m.actions.message("careful") ],
}));
```

takes the trigger first and types the rest from it: the callback receives the context for the rule's own host, so `ctx.player.health`
is a type error on a furniture rule (the schema fills it for items only); a trigger no host of that type fires (`guiClick` on an
item, or a trigger whose `firedBy` is empty) is a type error on the argument, naming the missing host; and a key the rule body
does not carry is a type error (the registered `ruleOptions`, `cooldown` among them, are the only options). A context value
interpolated into `m.expr` becomes its path, so a variable the trigger does not declare cannot be written by accident. What is not
checked is the expression *text* itself: only its interpolations are typed; its syntax and its bare identifiers are the stage
check's and the server's (§13).
**Expression functions** are `m.fn.<id>(...)`, typed from the function's parameters (`minArguments` makes trailing ones optional,
`variadic` makes the last a rest parameter), accepting a literal, an `Expr` or a context value and returning `Expr<number>`,
`Expr<string>` or `Expr<boolean>`. A function the server lacks (`m.fn.sqrt()` today) is TS2339, the same as every other gap.

**Where the generated surface stops.** A registry the wire does not carry produces nothing, and this is the list of what that
leaves out today (issue #355 tracks the self-description additions): presets (D12 layer 2: there is no preset registry yet); currencies and economy providers (a `currency` is a
content type, so it is a collection, but there is no provider registry; an unavailable `give_money` shows through `available`);
which action and condition parameters accept an `Expr` (every scalar is typed as its literal); that a trigger filter accepts a list;
`supersededBy` (S5); the parameters of the structural steps (`if`, `require`, `choose`, `wait`, `call`), which `core.d.ts` types by
hand because they are the flow model and not registry entries (`StepBuilders` follows logic.md §2.2 and the engine's step compiler: `require` takes steps, `if` takes a condition and a `then` or `else`; **this hand-typing is temporary until #355** puts the step grammar in self-description, after which the generator emits it); and the GUI layer rules (a layer's behavior is the GUI's own
`gui.click` rules filtered by `layer`, so that is how a shop button is written). None of this needed a server change, and none was
made.

### 3.9 As built: the core (#339)

*As built (#339, M6.5 track A2).* The fixed half of §3.1 exists: `webui/src/lib/codemode/core/`, the runtime that the generated
types of §3.8 are types *of*. It is plain ES2020, DOM-free and dependency-free, and it is tested **bundled** (esbuild, one
script) in a bare `vm` context that holds the language's intrinsics and nothing else, with a fake port, so what is proven is what
the sandbox (#343, not built) will load. The sandbox itself, the chat tool and the review UI are
not part of it.

| Piece | Where | What it is |
|---|---|---|
| **Entry** | `core/index.ts`, `core/session.ts` | `createSession({ surface, port, limits?, programFile? })` returns `{ m, finish(outcome), late() }`. `m` is the program's one global; `finish` ends the run and returns a `ProgramResult`. |
| **The port** | `core/types.ts` | `HostPort { read(op, payload): ReadReply; stage?; log?; done? }`. `read` is the only way out: the core asks for an op of its table and the host answers (and holds the allowlist too). It is synchronous from the program's side, so the sandbox must suspend the program while the host answers (an asyncified embedding, or a read the host pre-fetched); `stage`, `log` and `done` are observers. |
| **Operation table** | `core/ops.ts` | The one place that names editor ops: `READS` and `WRITES`, one row per SDK member and, for a write, per operation kind, with the capability that gates it. `scripts/compat/editor-compat.mjs` scans it as it scans the editor's `.call(...)` sites and fails when a supported release lacks an op named there, unless it is gated in `editor-op-gates.json` on a capability the row names (the core checks it before it exposes the member). |
| **Step grammar** | `core/steps.ts` | `m.steps.*` and the two rule keys that are flow vocabulary (`if`, `do`). The only file that names engine vocabulary, temporary until #355 (§3.8, "Where the generated surface stops"); a ban test confines it. |
| **Naming, context, capabilities** | `generate/names.ts`, `generate/context.ts`, `generate/capabilities.ts` | One implementation each, **imported by both the generator and the core**: how an id becomes a member name, how a trigger's context schema becomes a tree, and when a capability takes members away. The generator and the core cannot spell a member differently because there is one spelling. |
| **Diagnostics** | `core/diagnostics.ts`; plugin.md §5 | `SDK-0001` to `SDK-0017`, stable API, documented with the `MTR` codes. |

**The mapping, as built** (SDK call to transaction operation; everything is `authoring.transaction`, capability `codemode`, and the
list the program stages *is* the request's `operations`, untouched):

| SDK call | Stages | Reads first |
|---|---|---|
| `p.<collection>.create(id, fields, { folder? })` | `create { type: <the type's directory>, id, folder?, edits }`, one `{ field: [id, key], value }` per field in the program's order | `browse.packs`, once per run (the pack exists and is not built in) |
| `handle.set(patch)` | `setFields { path, rev?, edits }` | `browse.get` once per stored document (its revision), none for a created one |
| `handle.unset(...keys)` | `setFields { path, rev?, edits: [{ field, remove: true }] }` | as `set` |
| `handle.duplicate(id, { folder?, fields? })` | `duplicate { fromPath, rev?, id, folder?, edits? }`; returns the copy's handle | as `set`, for the source |
| `handle.delete()` | `delete { path, rev? }`; a resource this program creates is refused (SDK-0012) | as `set` |
| `handle.detachTemplate()` | `detachTemplate { path, rev?, resource }` | as `set` |
| `handle.saveAsTemplate(templateId, fields)` | `saveAsTemplate { fromPath, rev?, resource, templateId, fields }` (at least one field, as the server requires) | as `set` |
| `handle.rules.add(trigger, build)` | `logicEdit { path, rev?, edits: [{ op: "addRule", index, rule }] }`; `rule` is `{ on: "id[:detail]", <filters>, <options>, if?, do }` | as `set`; the document's rule count (for `index`) from the same read |
| `handle.rules.instantiate(recipe)` | one `logicEdit` with an `addRule` per rule the server expanded | `logic.instantiate` (capability `logic`) |
| `m.edit(ref)` | no operation: the handle of a stored resource, whose later calls stage the rows above | `authoring.summaries` to resolve the id, then as `set` |

`rev` is the stored revision, sent on every operation that names a stored document (the transaction needs it the first time and
accepts the same number again) and never for a document the program creates (S3). A stored document's rules continue its
count: `index` is the number it had when read plus what the program has added.

Not staged by the SDK yet, and not hidden: **`move`** (the moved document's own key would also have to be re-keyed, which the
server's `Move` does not do), **`guiEdit`** (a GUI is authored through its `layers` field and `gui.click` rules, S10; the
`GuiEdit` vocabulary has no SDK helper), rule and step patches, moves and removals, and named flows as edits (a `flows` field
is a field). Each is a row to add in `ops.ts` and a member to add to `core.d.ts` when a task asks for it; none changes the
mapping above.

**The read side**, over ops that exist (§3.2): `m.read.packs()` (`browse.packs`), `resources({ pack, type?, query?, limit? })`
(`authoring.summaries`, paged), `get(ref)` (`browse.get`: the resource's tree typed by its content type, its revision, its
computed metadata), `references(ref)` (`authoring.references`, with its coverage so "no hits" is not read as "unused"),
`history(ref)` (`browse.history`), `diagnostics()` (`admin.diagnostics.recent`, the server's recent list), and `m.ref.<type>("pack:id")`
(resolved with `authoring.summaries`, the nearest ids named when it fails). `m.docs.type(name)` and `m.docs.search(words)` are
pure functions of the surface document, with no read. Reads see the stored state (§3.2 rule 2): a handle `create` returned is
addressed by itself, and `m.read.get` of one is refused. A read is cached for the run by its op and payload, so a loop that asks
twice asks once. What has no op yet (traces, runtime variables, the graph, a source excerpt) is not exposed (benchmark S16).

**Values.** A step, condition, mechanic entry, GUI layer, source, expression, context value, trigger and reference is a frozen
object the core builds and records in a private `WeakMap`; the program cannot forge one, and anything it writes by hand is plain
data. A reference serializes as its `pack:id`, an expression as `${...}`, a context value as its path (interpolated into an
expression); an action is `{ id: params }` (or the bare id) with the registry's action options (`chance`, `delay`) beside it, a
mechanic is `{ id: params }`, a layer is `{ type: id, ...params }`. Parameters are checked against the registry entry's own
parameter ids (the types already refuse a stray key; the core refuses it for a program that was not type-checked), a function's
arity is checked against `minArguments` and `variadic`, and a value a document cannot hold (a function, a non-finite number,
`undefined` in a list) is SDK-0009 with its field path. Two spellings the registry cannot yet settle are benchmark S12 (a
variable inside an expression) and S14 (an asset in a field).

**The run.** `finish` returns the `ProgramResult` of §5.2: `ok`, `operations`, `sources` (the program line and column of each
operation, from the stack frame of the call, parallel to `operations`), `diagnostics`, `summary`, `logs`, the bounded `returned`,
and counters. A run with an error diagnostic has **no operations**, and an error is recorded before it is thrown, so a program
that catches it still fails. Anything the program throws that is not the core's is one SDK-0001 with the program's line. After
`finish` every call on `m` or a handle fails with SDK-0003 and is reported by `late()`; the result is not changed. `sources` are
positions in the *transpiled* program: the host (the editor, #343) maps them through the source map of its own transpile, which
the test harness does with the `typescript` package, so the goldens carry the lines the agent wrote.

| Limit (SDK-0005) | Default | Why |
|---|---|---|
| Staged operations | 200 | the transaction's cap (§10) |
| Edits in one operation | 256 | the transaction's cap |
| Distinct documents touched | 50 | the transaction's cap, decision C12 |
| Reads that reach the host | 200 | relay quotas (§4.2); a repeated identical read is free |
| Bytes of read replies | 4 MiB | as above |
| Rows of `m.read.resources` | 1000 | |
| Log bytes, return-value bytes, summary characters | 16 KiB, 16 KiB, 500 | what reaches the model (§4.2) |

**Determinism** (§4.2) holds by construction and by test: no clock, no `Math.random`, member order from sorted ids, so a program
and a set of reads give the same list byte for byte. **What changed in the types** to match the transaction (§3.8): `CreateOptions`
is `{ folder? }` (the transaction has no file parameter, benchmark S13); `ResourceHandle` gained `set`, `unset`, `duplicate`,
`delete`, `detachTemplate` and `saveAsTemplate`; `Materione` gained `edit`, `read`, `docs`, `sdk` and `log`; the generator emits the
`ContentTypeFields` and `ContentTypeHandles` maps those are typed from, and leaves `PackWriter` and `rules` out of a surface whose
jar lacks the `codemode` or `logic` capability (§7), which the core leaves off at run time by the same function.

**How it is proven.** `codemode-core.spec.ts`: the corpus programs that type-check are executed and their lists recorded as
goldens (`fixtures/golden/codemode/ops/`; a program that needs stored state or assets has a `<task>.world.json` beside it that the fake port answers from, T11's asset list today), each checked against the wire shape read from the plugin's own parser
(`TransactionOps.operationOf`), every kind of operation, every error. `codemode-core-drift.spec.ts`: the declarations and the
bundled core are walked with the TypeScript checker and compared member by member (`m`, each catalog, the pack writer, each
collection, each handle, each rule set) over both recorded registries, a jar without each capability, and a jar that predates the
capability list. `codemode-core-bans.spec.ts`: the core holds no registry id as a literal (the generator's ban, applied here),
names no content type, reaches no op outside the table, has no write op but the transaction and never reads it, asks the port
for four things only, and touches no DOM, storage, network, timer, Node or clock global. The compat gate fails when the table
names an op a supported release lacks (`editor-compat.test.mjs`).

### 3.10 As built: the type-check Worker (#342)

*As built (#342, M6.5 track A2).* §5.1's check exists: `webui/src/lib/codemode/typecheck/`. It is a **browser module Worker of the
editor's own origin** (not a Cloudflare Worker: no endpoint, no invocation, cost model "no change"), holding the TypeScript
compiler (Apache-2.0, `NOTICE`), and it only parses, checks and transpiles.

| Piece | Where | What it is |
|---|---|---|
| **Client** | `typecheck/client.ts`, `index.ts` | `check(program, surface) -> Promise<CheckResult>` (the shared client) and `createTypecheckClient({ limits?, spawn? })` (`check`, `dispose`, `stats`). `surface` is the `SurfaceDocument` (the client calls `generateSdk`, once per object) or declarations already generated. The Worker is spawned on the **first** check. One check runs at a time; later calls queue. |
| **Worker** | `typecheck/worker.ts` | Closed vocabulary (`ready`, `check`, `result`, `failed`); imports `generate/core.d.ts` as text and `libs.ts`. Vite emits it as `assets/worker-<hash>.js`, a static file beside the SPA. |
| **Checker** | `typecheck/checker.ts` | `createChecker({ libs, core })`: pure and DOM-free, so vitest runs the exact code the Worker runs. Declaration files stay parsed between checks; a changed surface text (a registry change, §7) is a changed file, so the program is rebuilt over it. |
| **Wrapper** | `typecheck/wrap.ts` | The one wrapper, shared with `test/helpers/typecheck.ts`: the program is checked as the body of `(async () => {` ... `})();` (reads are promises and a program awaits them, so top-level `await` and `return` check, and the sandbox runs the same shape). The prefix is one line, so lines are the program's own; line 1's columns are shifted back by the prefix length; a position past the program (an unclosed brace) is clamped to its end. The **transpiled JavaScript and its source map are of the program as written**, not of the wrapper: the host wraps for the run. |
| **Libraries** | `typecheck/libs.ts` | ES5 to ES2022 and `decorators`, read from the pinned `typescript` package at build time. No DOM, no webworker, no Node, no `@types`; `noResolve` and `types: []`, so no import, `/// <reference>` or type package reaches a file the host did not hand over. |

**Result** (`typecheck/types.ts`): `{ ok, diagnostics, truncated, js?, map? }`; a diagnostic is `{ code, severity, message, line, column,
hint?, missingPrimitive? }` with 1-based positions in the program as written. `js` and `map` (ES2020, the emitter's own source
map) exist only when `ok`: a failing program is never handed back as runnable text. Output is deterministic (sorted by position,
no timings in it; timings are `client.stats`).

**Gap classification.** A `TS2339` or `TS2353` whose type is one the generated surface declares or extends (`PackWriter`,
`ItemFields`, the function catalog) carries `missingPrimitive: { member, catalog, nearest }` and a hint that says to report it.
The same error on a type of the fixed core only (`m.nonsense`) is a plain diagnostic; a near miss is TS2551 with TypeScript's
"Did you mean" as the hint, and is not a gap.

**What is refused whatever the types say** (`CHK-0001`, plugin.md section 5): `@ts-nocheck`, `@ts-ignore`, `@ts-expect-error`
(a textual, conservative scan), `declare`, `namespace`/`declare global`, `import`/`export`, and a program that closes its own
body to write at the wrapper's top level. Without these a program could silence the check or conjure `fetch` with
`declare const fetch: any`. The check is quality gating, not the security boundary: the sandbox (§4) is, and it has no `fetch`.

**Limits** (the numbers §4.2 lacked for the checker; proposed here, fixed by the tests):

| Limit | Value | Why |
|---|---|---|
| Program size | 65,536 characters (`CHK-0002`) | the transaction takes at most 200 operations; a real program is a few KB |
| Generated declarations | 1 MiB (`CHK-0002`) | the current surface is 195 KB |
| Diagnostics returned | 50, then `truncated` | what reaches the model (§4.2) |
| Check deadline | 5 s once the Worker is up, then the Worker is **terminated** (`CHK-0003`) and the next check boots a fresh one | measured: 4.5 to 30 ms warm; the deadline bounds a runaway, it does not ration a program |
| Boot deadline | 20 s for the asset to load and parse (`CHK-0004`) | 1.07 MB gzip, once |

A stack overflow inside the compiler (a 30,000-deep literal) is caught in the Worker and returned as `CHK-0004`, and the Worker
keeps serving.

**Measured** (Chromium, Vite dev server; the production file is the same compiler): Worker asset 4,087,907 B raw / 1,085,065 B
gzip (the spike's 1,075,236 B); boot about 290 ms, first check about 460 ms round trip (compiler 172 ms, 195 KB surface), warm
about 10 ms. In Node, first check about 50 to 140 ms, warm 10 to 30 ms. A runaway quadratic type is cut at its 1.5 s test deadline
with the page's main thread never stalled for more than 250 ms.

**Lazy, proven by build** (`codemode-typecheck-bundle.spec.ts`): the editor's production output holds no compiler and no worker
asset; a page that imports the client eagerly adds 21.5 KB raw (7.7 KB gzip) of JavaScript and the compiler stays its own file,
fetched on the first check. The editor's own bundle is unchanged by this work: nothing imports the client yet.

**A build finding.** With Rollup's default tree-shaking the production Worker builds and then fails at its first check (`Cannot read
properties of undefined (reading 'get')` inside the compiler: the CommonJS `typescript` package relies on side effects Rollup drops),
while the dev server and Node pass. The Worker is therefore built with `treeshake: false` (`webui/vite.worker.ts`, shared with the specs),
and the production artifact has its own Playwright spec.

**Hosting and CSP.** Same static files, no new header or endpoint. Neither host sets a Content-Security-Policy today
(`EditorListener.serveStatic` sends only `Content-Type`; the relay's `public/_headers` sets caching and the cloud marker; `index.html`
has no meta CSP), so a same-origin module Worker is allowed. The plugin serves `.js` as `text/javascript` and accepts the file
names Vite emits (`[A-Za-z0-9_./-]+`); the relay's Workers static assets serve `/assets/*` as files. If a CSP is ever added it needs
`worker-src 'self'` (or `default-src 'self'`) and `script-src 'self'`; the spec runs under exactly that. One failure to know:
under the relay's single-page fallback a missing worker file answers `index.html` as text/html, which a module Worker refuses
(the client reports `CHK-0004`).

**How it is proven.** `codemode-typecheck.spec.ts` (Node): the corpus through the checker with the expected codes and the gap, the
agreement with the compile spec's own TypeScript API run, the ambient names, the refusals, determinism, limits, the client's
deadline, restart and queue against an in-process stand-in, a ban test on the checker's sources. `codemode-typecheck.pw.ts` and
`codemode-typecheck-built.pw.ts` (Playwright): the same corpus through the real Worker, dev server and production build; laziness;
no request while checking (the context offline); the deadline against a real runaway; the main thread's responsiveness. When the
SDK's reads become promises (#381) the corpus programs that read are checked through the same wrapper, and the agreement test
picks them up with no change here.

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

**Proposed numbers (spike #340, to be fixed by fixtures in #343).** Measured typical cost of a staging program is a fraction of a
millisecond of engine CPU (§4.3, measured), so every number below is generous by two to three orders of magnitude and exists to
bound a runaway, not to ration a good program:

| Limit | Proposed | Notes from the spike |
|---|---|---|
| CPU deadline | **1000 ms** of *engine* time, counted only while guest code runs (waiting on a host read does not count) | The interrupt handler fires on `while(true)`, on a loop that swallows the interrupt error, and on a promise ping-pong; each ended within 4 ms of a 250 ms deadline |
| Wall-clock backstop | **10 s** per run and **5 s** per read, enforced by the host, which then destroys the frame | The backstop is the only defence against an engine that never answers; tested at 400 ms |
| Memory | **64 MiB hard cap on the engine's `WebAssembly.Memory`** (`maximum` pages), *not* QuickJS's `setMemoryLimit` | `setMemoryLimit` does not bound array or string storage: 22,000 arrays of 80 KB (1.7 GB) were allocated under a 16 MB limit. A capped `WebAssembly.Memory` ends the same program in about 40 ms with `out of memory`, and the module stays usable |
| Native stack | **256 KiB** QuickJS stack (about 1,300 frames of simple recursion) | 512 KiB overflows the browser's *native* wasm stack first: a `RangeError` out of wasm that poisons the instance. 256 KiB raised QuickJS's own `stack overflow` |
| Read calls / bytes | **100** calls, **2 MiB** of answers per run | Counted by the engine wrapper and re-checked by the host |
| Staged operations / bytes | **200** operations (and at most the transaction's document cap, §5.4), **256 KiB** | A stage past the cap marks the run failed even when the program catches the error; no operations are returned |
| Logs / return value | **16 KiB** / **64 KiB** | Same: exceeding either fails the run and returns no operations |

### 4.3 Options

All options run in the browser. Server-side options appear only to be excluded.

| Option | Isolation | CPU and memory control | Cost to us | Verdict |
|---|---|---|---|---|
| **A. A WebAssembly JavaScript engine (QuickJS compiled to WASM), inside a sandboxed iframe** (`sandbox="allow-scripts"` with no `allow-same-origin`, so an opaque origin; a CSP with `connect-src 'none'`) | **Strongest.** The engine is a separate interpreter with no globals but those the host injects; the iframe adds an origin boundary, so even an engine bug lands in an origin with nothing in it. | An interrupt handler and a memory limit are part of the engine's embedding interface; the host can also destroy the iframe. | A small WASM asset, served as a static asset (free, unlimited; web.md §3.3). Slower than native JS, which does not matter for programs that stage edits. Async host calls need the engine's async embedding. | **Chosen (C7).** |
| **B. A Web Worker in a sandboxed iframe, running the browser's native JavaScript** | Good. No DOM in a worker; the iframe's opaque origin and CSP remove network and storage. But the worker's global scope still carries ambient browser APIs that must be removed or blocked one by one, so isolation depends on a deny list. | CPU: `terminate()`. Memory: not capped by the platform. | None. Fastest. | A fallback if A's embedding proves too costly. Weaker by construction: a deny list can miss an API a later browser adds. |
| **C. A plain Worker or `new Function` in the editor's own origin** | Weak. Same origin as the signing key, storage and channel. | `terminate()` only. | None. | **Rejected.** |
| **D. The Blockbench pattern: a same-origin iframe with a postMessage bridge** (web.md §6) | Adequate for *our* code, which is why Blockbench uses it; it is not a boundary against hostile code. | None. | None. | **Rejected** for untrusted code. Its bridge discipline (exact origin check, allowlisted, payload-capped messages) is reused for the host channel in A. |
| **E. Execution on Cloudflare** (a Worker, a dynamic isolate, a container) | Strong. | Platform-enforced. | **Per-invocation compute on an account with no spend cap.** | **Excluded** by decision and by guardrail (§4.5). |
| **F. Execution on the game server** (GraalJS, a JVM-embedded engine) | Needs a JVM sandbox the platform no longer provides (§2.5). | Hard. | Plugin weight; threading rules (D8). | **Excluded** (C2, C4). |

**Decided (owner, C7): A.** QuickJS compiled to WebAssembly, in a sandboxed opaque-origin iframe, driven from
the editor by a postMessage host channel with the discipline of the Blockbench bridge. Reasons: **no ambient authority by
construction** (the program sees only what the host injects, rather than the browser minus a deny list), **resource limits that
are part of the engine** rather than of the browser, and a boundary in depth (engine, then origin). B is the fallback. The
type checker (§5.1) is a different component: it only *parses* untrusted text and never executes it, so it runs in an ordinary
Worker of the editor.

**Measured (spike #340; harness in `webui/spike/codemode/`, run with `npm run spike:codemode`; Chromium only, localhost
static server, no cache headers, so network time is near zero; not part of the editor build or CI).** Verdict: **go on option A**,
with the amendments below. Nothing here forces fallback B.

*The build.* `quickjs-emscripten-core` 0.32.0 with `@jitl/quickjs-wasmfile-release-sync` 0.32.0: Fabrice Bellard's QuickJS
(vendored 2025-09-13), compiled by `quickjs-emscripten`, **MIT** (the wrapper, the FFI types and the vendored engine alike).
Sync build, not asyncify: async host calls need no asyncify, because the host function returns a guest promise
(`context.newPromise()`) that the host resolves when the channel answers, then pumps `executePendingJobs()`. The asyncify wasm
is 2.0x larger (1.03 MB against 0.50 MB raw) and was not needed. Guest `m.read` costs **about 0.1 ms per call** across the
channel (50 sequential reads in 4 ms, 50 in `Promise.all` in 2 ms); the cost of a read in production is the relay round trip, not
the embedding.

| Asset | Raw | gzip | brotli |
|---|---|---|---|
| `quickjs.wasm` | 503,134 B | 234,043 B | 199,016 B |
| engine + wrapper JS (`engine-worker.js`) | 58,577 B | 17,006 B | 15,260 B |
| **Sandbox total (what code mode adds to a first run)** | ~562 KB | **~251 KB** | ~214 KB |
| TypeScript checker Worker, with `lib.es5`..`es2022` d.ts, no DOM (`tsworker.js`) | 4,085,391 B | 1,075,236 B | 827,085 B |

Both are static assets, loaded **lazily on the first agent use**, never at editor start (the SPA bundle is unchanged: the spike
adds nothing to `npm run build`). The TypeScript Worker is the heavy one: about 0.54 MB of its raw size is the lib `.d.ts` files;
a hand-trimmed lib (`Array`, `Promise`, `Map`, `Set`, `JSON`, `Math`, `String`, `Number`) would cut it by roughly that much, at
the price of maintaining a second lib; not done.

*Timing (Chromium, localhost; medians of 8 to 30 runs; the full table is the harness's `measurements.json`).*

| What | Engine in a blob Worker inside the iframe | Engine on the iframe's main thread |
|---|---|---|
| Sandbox cold start (iframe load + engine init, files cached) | 16.4 ms (first ever 19 ms) | 8.4 ms (first ever 32 ms) |
| of which engine instantiate (compile + start) | 2.1 ms | 1.4 ms |
| Per-run overhead, trivial program (setup, run, answer; excludes TS transpile) | **0.5 ms** engine, 1.3 ms total with transpile | 0.7 ms engine, 1.9 ms total |
| `while(true)` with a 250 ms deadline: editor page frozen for | **11 ms** | **260 ms** |
| Unbounded allocation to the 32 MiB cap: time / editor frozen for | 45 ms / 12 ms | 40 ms / 41 ms |

| TypeScript checker (Worker of the editor's origin) | Time |
|---|---|
| Worker cold start (spawn, parse the 4 MB bundle) | 170 ms |
| First transpile / first check, 25-line program against a **200-member, 25 KB `.d.ts`** | 37 ms / 83 ms (includes parsing the lib files) |
| Warm transpile / warm check, the same | 2.2 ms / 4.5 ms (lib and SDK `SourceFile`s cached; the `.d.ts` changed only per SDK version) |
| Diagnostics | line, column and TS code, with `Did you mean 'ns9'?` suggestions: the §6 error channel works as designed |

*The headline finding, and an amendment to D13's wording.* In Chromium an opaque-origin sandboxed iframe **shares the editor's main
thread** with the page: a CPU-bound guest in the iframe froze the editor for the entire deadline (260 ms of a 250 ms limit). The
interrupt bounds the freeze but does not remove it, so "the editor stays responsive" (#343's exit criterion) **fails with the
engine on the iframe's main thread** and **passes with the engine in a Worker that the iframe spawns from a `blob:` URL** (11 ms
of lag). A blob Worker created by an opaque-origin frame **inherits the opaque origin** (`self.origin === 'null'`) **and the
frame's CSP**: the probe inside it could not `fetch`, XHR, open a WebSocket, `importScripts`, `import()`, spawn a nested Worker
or touch the editor's IndexedDB, and a message it posted on a `BroadcastChannel` the editor listens on never arrived. **Proposed
amendment (for the owner; master §3.2 is not edited here):** read D13's "in a sandboxed opaque-origin iframe" as "the iframe
document spawns the engine in a Worker", which is still inside the iframe, and make that the production shape (#343). The
host-side wall-clock backstop and `frame.remove()` kill the whole thing.

*What the spike found that would have bitten #343.*

1. **QuickJS's memory limit is not a memory cap.** Use a `WebAssembly.Memory` with a `maximum` (§4.2).
2. **The native stack, not QuickJS's, can overflow first.** A 512 KiB QuickJS stack made the wasm itself throw a host
   `RangeError`; the instance is then unusable. The engine wrapper reports `poisoned` and the host discards the frame. The same
   flag covers a program that retains memory in a global and makes QuickJS's leak assertion abort the module on dispose.
3. **`fetch` of the wasm cannot work under `connect-src 'none'`**, and a fetch from an opaque origin needs CORS headers on the
   asset anyway. The host fetches the wasm (and, for the Worker, the engine's source text) and **transfers the bytes into the
   frame** with the `init` message; the frame never fetches. This also makes the frame correct on any static host with no
   header configuration.
4. **`targetOrigin` must be `'*'`** for the first message to an opaque-origin frame (it has no nameable origin). The frame
   accepts exactly one `init`, checks `e.source === window.parent`, and from then on everything travels on a private
   `MessageChannel` port that the host transferred with it; the host never accepts a window `message` from the frame.
5. **`location.origin` inside the frame reports the URL's origin, not the security origin.** Test with `window.origin`, which is
   `"null"` for an opaque origin.
6. **The sandbox attribute alone does not stop the network.** Without the CSP, code in the frame reached the server with
   `fetch(..., { mode: 'no-cors' })` (the server's hit log recorded it), and could `eval`, `import()` a `data:` URL and inject a
   `<script>`. It *did*
   stop every read of the editor (parent DOM, storage, cookies, IndexedDB, top navigation, `window.open`). So the CSP is
   load-bearing, and the origin boundary holds by itself.
7. **`script-src 'self'` is too wide.** `'self'` is the URL's origin, so a `'self'` frame could still `importScripts` and `import()`
   any URL on the editor's own static host. The sandbox documents carry their one script **inline** and name its **sha256**
   (`script-src 'sha256-...' 'wasm-unsafe-eval'`): then no URL is a script source at all. The hash is computed at build time.
8. **Guest globals** are exactly QuickJS's intrinsics (`Object`, `Array`, `Promise`, `Proxy`, `Reflect`, `Date`, `Math`, `JSON`,
   typed arrays, `Map`/`Set`/`Weak*`, `eval`, `Function`, `globalThis`, ...). There is no `fetch`, `XMLHttpRequest`,
   `WebSocket`, `window`, `document`, `parent`, `localStorage`, `indexedDB`, timer, `queueMicrotask`, `console`, `postMessage`,
   `import()` (it rejects), `WebAssembly`, `Atomics` or `performance`. `m` is passed as an argument, not a global.
   `Function('return this')()` and `m.constructor.constructor(...)` reach nothing. `eval` and `Function` exist *inside* the engine
   and are harmless there; #343 may delete them as a defence in depth. `SharedArrayBuffer` exists as a type but `Atomics` does not.
9. **Determinism** works as designed: a prelude replaces `Date` (fixed instant, `Date()` and `new Date()` agree, `instanceof`
   holds) and `Math.random` (mulberry32, seeded); two fresh sandboxes and a rerun in the same one gave identical results.
10. **A staged operation's line** comes from the guest's own `new Error().stack` at `m.stage` time, mapped back through the
    TypeScript source map (a small VLQ decoder, 30 lines); a thrown error's line is mapped the same way. The program is wrapped
    so that its first line stays on the wrapper's line 1, so a TS diagnostic line is the program's line with no offset.

*CSP and hosting.* The frame document needs, and can carry as a `<meta http-equiv="Content-Security-Policy">` with **no header
change on either host**: `default-src 'none'; script-src 'sha256-<hash of the inline script>' 'wasm-unsafe-eval'; connect-src
'none'; worker-src blob:; frame-src 'none'; img-src 'none'; style-src 'none'; font-src 'none'; media-src 'none'; object-src
'none'; base-uri 'none'; form-action 'none'` (`worker-src blob:` only if the engine runs in a Worker, which is the recommendation).
The embedding page sets `sandbox="allow-scripts"` and never `allow-same-origin`. The relay-served editor (a Workers static-assets
deployment, `public/_headers`) and the plugin-served editor (`EditorListener.serveStatic`, which already maps `.html`, `.js` and
`.wasm` to content types and sends no CSP of its own) both just serve the files; the plugin's path filter
(`[A-Za-z0-9_./-]+`) accepts the file names used. `sandbox.html`, the wasm, and the engine source must therefore be part of the
SPA build output (Vite `public/`, the same artifact in both hosts). Two optional hardenings, neither needed: a
`Content-Security-Policy` *header* on `sandbox.html` (the relay's `_headers` could carry it) would apply before any parsing, and
`frame-ancestors 'self'` would stop the document being framed elsewhere. **Proposed (not applied):** none required; if the owner
wants the header variant, the relay's `public/_headers` gains one `/sandbox.html` block, a static-asset change with cost model
**no change** (no Worker invocation, no new endpoint, free static-asset requests). A parent-page CSP, if one is ever added,
needs `frame-src 'self'` and nothing for `blob:` (the blob Worker belongs to the frame's CSP, not the parent's).

*Licence.* QuickJS and every wrapper package are MIT; TypeScript is Apache-2.0 (the repository's own licence is Apache-2.0, which
is also the licence of the code that would ship the checker). Shipping either in the editor adds an attribution line to the
third-party notices (`NOTICE`); no GPL component is involved, so the open licence question (master §14) is not affected.
The spike's three new packages are `devDependencies` of `webui` and are not in the production bundle.

*Go or no-go.* **Go on A**, as amended: engine in a blob Worker spawned by the opaque-origin iframe; memory capped through
`WebAssembly.Memory`; stack at 256 KiB; the wasm and engine source passed in as bytes; inline script with a hash CSP; a
wall-clock backstop that destroys the frame; a `poisoned` flag that makes the host discard a frame that aborted. The cost is about
**251 KB gzip of static assets plus about 1.1 MB gzip for the checker, both lazy**, **16 ms** to a ready sandbox, **about 1 ms**
per run, **0.1 ms** per host read. Fallback B (§4.3) is not needed.

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
- **The model call is the one thing that leaves the page**, and it is not decided here (§12, question 2; master §14.8). Whatever the owner chooses, two
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
the edits are combined (both a reference *from* a document the list wrote, and one *to* a document the list deleted or whose id it
removed, from an untouched resource of this pack or of another; a document a later operation creates satisfies an earlier
reference; #357), a flow the compiler rejects, a note-block state that cannot be allocated, a stage into a built-in pack
(read-only, master §7.3), an operation the actor's permissions do not allow (the dry run checks each operation's node:
`materione.edit.<pack>`, master §9.2).

### 5.4 One program, one transaction

The atomic unit today is `authoring.bulk` (api.md §3.3): all-or-none, revision-checked, up to 50 documents **of one pack**. The
behavior and GUI edits each write one document at one revision. **Decided (C12):** a program may write to **one pack**, and
touches at most the transaction's cap of documents; it may read any pack and reference any pack's resources. Staging into a
second pack is a diagnostic ("this change needs two packs; split it into two programs"). This follows from what the server can
guarantee atomically today; lifting it is a server change (§10), not an SDK change. **The owner keeps both limits for now (C12)**
and is open to lifting them later if they become a problem. The server needs one transaction
operation that takes the *whole* heterogeneous list, which is §10 item 1.

### 5.5 Programs are one-shot: iterating and building on prior work

- A **refused proposal is replaced, not patched.** The agent fixes it by writing a corrected whole program against the stored
  state, which is why a program is short (tens of lines): it describes a change, not a repair.
- **Building on an applied change** is a new program that reads the state the apply produced. A multi-step plan ("first the
  item, then its GUI") is several programs, each applied in turn (§5.6). **The owner is asked once for the plan (C11)**, not at
  each program: the agent declares the existing objects the plan will change or delete, the owner approves that set once, and
  every program of the plan that stays inside it applies without a new prompt. A program that reaches an object outside the set
  stops and asks for that object (§5.6).
- **Nothing is carried between programs except the stored files.** No variables, no module, no cache. That is C2 made concrete,
  and it is why SDK changes cost nothing durable (§7).

### 5.6 Review, then apply

**The agent is trusted for the most part (C10).** Whether a proposal applies on its own or waits for the owner depends only on
**what it touches**, after the dry run (§5.3) is clean:

| A proposal that... | Applies |
|---|---|
| only **creates** content, or changes content this session created | **without approval**; shown in the session's change log, with revert |
| changes or deletes an **existing** object the owner has **already approved** in this session | **without approval** (the grant holds for the session) |
| changes or deletes an **existing** object **not yet approved** | **after the owner approves that object**; the grant then covers every later change to it in the session |
| has an error diagnostic in its dry run | **never**; the diagnostics go back to the agent (§6) |

*Existing* means present in the stored files when the session started and not created by the session since. A grant is per
object (one document or resource), per session, and is held by the editor host, outside the sandbox: the SDK has no call that
reads, asks for or grants one. Permission is asked **once** (C11): a plan's approval prompt lists every existing object the plan
declares, the owner approves the set, and the programs of the plan apply in turn without asking again. A dry-run failure, a
stale revision or an object outside the set brings the plan back to the owner only for what is new.

When a proposal needs approval, it is shown to the owner **in the editor**, not in the chat transcript alone (the flow of #320,
step 6); a proposal that applies on its own is shown the same way in the change log, after the fact:

| Shown | From |
|---|---|
| The agent's summary of the change, in domain terms ("adds 1 item, 1 GUI, 2 rules; changes nothing else") | `m.summary(...)` |
| The **diff** of the resulting files: only what changes, minimal | the dry run's resulting documents against the stored ones |
| **Impact**: what else references what changes | `findReferences`, `queryGraph` |
| Diagnostics: errors block, warnings are shown | the dry run |
| The program text, collapsed, as the witness of how the list was produced | the proposal |

**The owner grants with a button the model cannot press.** The SDK has no apply and no grant, the chat tool has no apply or grant
parameter, and the approval is a UI event in the editor host. On apply, approved or not, the editor sends the **same operation
list** (not the program) as a single transaction with each document's `expectedRevision` from the dry run, so what was checked is
what is applied (D6, master §6.4):

- It is **transactional**: all-or-none under the store lock; a failure leaves no partial files, and the pipeline's shadow-swap
  rolls back an apply that fails to load (master §6.4).
- It is **audited**: the audit entry carries the session's actor (the same permissions, no new actor kind, no new scope), plus
  an `origin` tag `code-mode`, whether the apply was granted by the owner or ran without approval (C10), and **the program text**,
  size-bounded, with its digest (C13); §10 item 4.
- It is **reversible**: a transaction identifier lets one request put each touched document back to the text it had before,
  as a new revision, not by deleting history (§10 item 3, as built in #344: the revision journal keeps hashes, so the
  server keeps the before-images beside it).
- **A stale revision** (a human edited a file between dry run and apply) is `MTR-0034` for that document and the whole
  transaction is refused. The agent is told, writes the program again against the new state, and the owner reviews again. This is
  **rebase by re-running**, which is only cheap because the program is short, one-shot and deterministic (§4.2).

The grant is the owner's protection, not the transaction's: every apply, granted or not, is transactional, audited and
reversible as above.

---

## 6. How errors reach the agent

Every failure returns to the agent as **structured diagnostics**, so it acts on a record and not on a log. One shape for all
phases, the project's own (`{code, severity, message, resource, file:line, hint}`, plugin.md §5), plus the phase and the program
line:

| Phase | Source | Code | Example | The agent's right move |
|---|---|---|---|---|
| **1. Type-check** | TypeScript | `TS####`, `CHK-####` (§3.10) | `Property 'commands' does not exist on type 'Materione'` | If a *primitive* is missing (§9.2), report the gap; otherwise fix the call |
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
| **The core's operation table is checked by the compatibility gate** | The map from SDK writes and reads to editor operations (`webui/src/lib/codemode/`) is scanned by `scripts/compat/editor-compat.mjs` the way it scans the editor's calls today: it fails when the core calls an operation a supported release does not register, unless the operation is gated on a capability in `fixtures/protocol/editor-op-gates.json` that the SDK checks (`hasCapability`). Extending the script to this table is part of the implementation. *Built (#339, §3.9): the script reads `core/ops.ts`.* |
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
| **A program that grants itself approval**, or a chat tool that grants or applies outside the rules of §5.6. | D6 and C10: whether a change to existing content applies is decided by the owner's grant, a UI event the model cannot trigger (§5.6). |
| **Network, storage or DOM access from a program**, and reading anything outside the SDK. | §4.1. |
| **Execution on Cloudflare or on the game server.** | §4.3 E and F; the guardrails (`CLAUDE.md`). |
| **Asset generation and binary uploads from a program** in this design. | Binary assets are a separate, non-atomic write (web.md §6: an asset copy is an ordinary write made before the bulk call); #320 lists asset generation as open. A program may *reference* assets the owner has uploaded. |
| **A standalone MCP server for external agents**, and publishing the SDK for them. | Master §14.8: not part of the decision. C8: not a priority, not published for now. |
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
| 4 | **An additive `origin`, approval mode (granted or not needed, C10), program text (size-bounded, C13) and `digest` in the audit entry.** | §5.6 | The audit has `actor`. |
| 5 | **Read operations on the wire** for traces and runtime variable reads, with their own permission nodes. | §3.2: debugging is #320 step 8 | `LogicApi.traces()` and `variables()` are API methods; the wire exposure is not verified here (§13). |
| 6 | **A read-only expanded view of a preset** (master §14.7(iv)). | §3.5 | Open. |
| 7 | **A capability** advertising the above. | §7 | The capability list exists (web.md §4.1). |

**As built: items 1, 2 and 7 (#341, track A1).** `AuthoringApi.transaction(actor, TransactionRequest)` exists and is specified in
[`api.md`](api.md) §3.3.1; the table above keeps the state *before* it. What the design settled, in brief:

| Question | As built |
|---|---|
| Request | `TransactionRequest(pack, operations, dryRun)`: one pack, at most 200 operations, at most 50 distinct documents touched (C12). Operations: `Create`, `Duplicate`, `SetFields`, `Move`, `Delete`, `DetachTemplate`, `SaveAsTemplate`, `EditLogic`, `EditGui`, each the edit vocabulary of the single-document call of that name. |
| Typed edits on an in-memory document | The list runs on a working set of document texts. Field edits use `DocumentStore.patched`, the lossless patch `applyBatch` calls too. `LogicEdit` and `GuiEdit` go through a new `stage(pack, path, tree, edits)` on each authoring surface, which returns the field edits and reads no store; `logic().edit` and `gui().edit` are now `read, check revision, stage, setFields`. |
| A later operation addressing a created document | A path names a document as it is after the operations before it. A created, copied or moved document is addressable; a path deleted or moved away is not. Templates and items the list creates are visible to a later `DetachTemplate` or `SaveAsTemplate`. |
| Revisions | `expectedRevision` is the stored revision, required the first time an existing document is named, ignored for a created one; stale is `MTR-0034` and refuses the list. One revision per document: created 1, changed stored plus one, a delete entry, a move target 1 with `move-from:` lineage. |
| Order and failure | Strictly in order; shape, limits and permissions are reported together first; then planning stops at the first operation that cannot apply, with its index. All-or-none write with checkpoints, rolled back (files, journals, created directories) on any failure. |
| Permissions (item 2) | `materione.edit.<pack>` per operation and built-in packs read-only: one diagnostic per refused operation (`MTR-0055`, `MTR-0095`). |
| Dry run | Same path under the store lock; writes nothing, queues no reload, returns diagnostics tagged with the operation index and the resulting documents with their would-be revisions. An apply writes exactly those documents, with one audit entry (it names the transaction id) and one pack reload. |
| Validation stages | Run: syntax; schema and build per resource through the registered content types and the allocation preview; the flow compiler for `behavior:` and `flows:`; the GUI compile; **references** (#357: `ReferenceValidator` over the staged pack and the other packs as stored, both directions, only the findings the list adds for untouched resources; see api.md §3.3.1). **Not run: the platform gate (D10), which needs the live server, and the pack-wide allocation (the build stage allocates the list's own resources on a copy of the real table; the final table is the reload's, so no further allocation preview is built).** What `ReferenceValidator` does not cover is not covered here either: it checks `template`, `model`, `texture`, recipe, drop, placement, evolution, glyph-alias, jukebox and asset-source references, and a GUI's references to items are not among them (the `gui` stage reports what the GUI compile reports; whether the pipeline should resolve them at level 3 is a separate question). The report lists each stage and whether it ran. |
| Wire and capability (item 7) | `authoring.transaction`, gated on the new session capability `codemode` (`editor-op-gates.json`, golden frames in `editor-codemode-v1.json`). Additive: an older editor never calls it. |

**As built: items 3 and 4 (#344, track A2).** `AuthoringApi.revertTransaction(actor, transactionId, dryRun)` and the audit's
additive fields. The design, in brief:

| Question | As built |
|---|---|
| What the audit entry carries (item 4) | `authoring.transaction` entries gain, additively, `origin` (`editor`, the default, or `code-mode`), `approval` (`not_needed`, the default, or `granted`), `transaction` (the id), `program` (the program text, at most 8192 characters) with `programLength` (the length of the text received) and `digest` (`sha256:` over the whole text the server received, computed by the server and never taken from the caller). An oversized program is truncated (never in the middle of a character) and its digest still covers all of it, so the truncation is detectable. A revert is audited as `authoring.revertTransaction` with origin `editor` and its own transaction id. Older entries read as before: origin `editor`, the rest absent. `admin.audit.recent` returns the fields. `approval` is what the editor host asserts: the server holds no grant (§5.6), so it records the claim and does not verify it. |
| Transaction id in the journal | Every journal entry a transaction writes carries `"tx":"<id>"` (additive; an older line has none), so the documents of one transaction can be found from the journals alone. The id is created before the write, so the entries and the audit agree. |
| What a revert needs (item 3) | The revision journal keeps only each entry's hash, so it cannot restore text. Each applied transaction therefore leaves a **record** under `storage/transactions/`: for every document it wrote, the revision before and after, the hash it left, and the **before-image** (the text before; none for a document it created). The record is history, never content (D1): nothing reads it to learn what a document says now. |
| How a revert is computed | The inverse of each recorded document: a changed one goes back to its before-image (a `set` with action `revert`), a created or moved-to one is deleted, a deleted one is restored. Restoring a deleted document is the **next revision after the delete**, never revision 1 again (the new `RESTORE` write kind), so every touched document gains exactly one revision and revisions only grow. It runs as **a transaction through the same path**: the same permission check, the same validation stages (a restored resource is compiled, and the references stage refuses a revert that would orphan a later change), the same `DocumentStore.commit` (all-or-none, checkpointed and rolled back on a fault), one audit entry, one reload, and its own record, so a revert can itself be reverted. A dry run (`dryRun: true`) reports the documents it would restore and writes nothing. |
| When a revert refuses | Before anything is planned it checks each document against what the transaction left: a document that exists must be at the recorded revision and hash (a hand edit is journaled as an `external` revision when read, so it fails the check), a document the transaction deleted must still be absent. Every document that moved on is reported, `MTR-0034` each, and nothing is written. An unknown id, an id past the retention rule, a record that cannot be read and a server with no journal are `MTR-0104`. A missing `materione.edit.<pack>` or a built-in pack is the per-operation check as-is (`MTR-0055`, `MTR-0095`), one diagnostic per document. An operation index in a revert's report is the position of the document in the reverted transaction's result. Like any read, a dry run or a refused revert journals an `external` baseline for a document that was hand-edited since its last journaled state. The restored state goes through the validation stages like any written state, so **a revert that would restore a state that does not validate is refused**: a later change now depends on a created document, or the before-image itself had an error that the transaction fixed. There is no side door; the dry run's diagnostics are the answer. |
| Retention | Bounded three ways: a transaction whose record would exceed 4 MiB is applied but **not recorded** (the result says `revertible: false` and why); only the newest 100 records are kept; the kept records total at most 32 MiB, the oldest deleted first. Past the cap a revert of that transaction is `MTR-0104` and the apply's own result had already said whether it was revertible. A record that cannot be written never fails the transaction it describes (D6). The cost is one small write after the commit and a directory listing of at most 100 files, outside the store lock. |
| Wire | `authoring.revertTransaction` `{transactionId, dryRun}` (`dryRun` mandatory, as for `authoring.transaction`), gated on `codemode`; the reply is the transaction's report plus `revertOf`. `authoring.transaction` gains the optional `origin`, `approval` and `program` and its reply `revertible` and `revertNote`; all additive, frames in `editor-codemode-v1.json`. |
| Permissions | The existing per-operation check, unchanged. The editor session's actor still carries `node -> true`, so any session that can name a transaction id can revert it; that is the open owner decision on the session permission model (#358). Revert would need from that decision: whether a revert may be done by a session other than the one that applied (an id is unguessable but is not a capability), and whether reverting a transaction that touched what other sessions approved needs the owner's grant (§5.6). Nothing here widens or narrows what a session may do. |

Still open in §10: item 5 (trace and variable reads, #345), item 6 (the preset view).
Two limits worth knowing for the SDK: a transaction carries no binaries (a paint layer's PNG still goes through `gui().edit`),
and `findReferences` (§5.6) remains the way to *show* impact before a list is written: the dry run now *refuses* a list that breaks a reference the pipeline checks, but it does not list every use of a resource (a GUI's item reference, for one, is not a pipeline reference).

---

## 11. How it is proven

Under the project's rules (roadmap rule 2, the golden-file rule, `qa/README.md`):

- **Golden SDK output.** The generator's `.d.ts` for each recorded registry snapshot (`fixtures/`), including a snapshot with an
  addon-registered primitive, so §3.6 is tested rather than claimed. *Built (#337, §3.8).*
- **A program corpus that only grows.** Positive programs (the Bloodfang shape) and negative ones (`/trade` against a snapshot
  without the primitives) with their expected diagnostics, type-checked in CI against every supported jar's recorded registry (§7).
- **Sandbox tests as ban tests.** A program that touches `fetch`, `globalThis`, timers, the DOM, `postMessage`, `import`, or
  loops forever, allocates without bound, or stages past the cap, each yields a diagnostic and no operations. The ban list is
  kept by tests, not by review goodwill.
- **Server tests.** Dry run mutates nothing (a property test over the store); the transaction is all-or-none with fault
  injection at each document; revert restores prior revisions; a stale revision is `MTR-0034` for the transaction.
  *Built (#341, #344):* a property test applies random lists and reverts each, and the pack files are byte-identical every
  time (created documents removed, deleted ones restored byte for byte, a revert of a revert too); the revert is fault-injected
  at each document; a revert after a later edit of any kind refuses with every stale document reported; the harness IT applies a
  transaction over the wire on a live Paper, reverts it by id and checks the bytes, two more revisions and both audit entries.
- **The benchmark is the evaluation (#320).** The tasks, their programs and the paper run are in
  [`agent-benchmark.md`](agent-benchmark.md); #349 moves them into CI. The tasks are run twice: with typed code mode, and with a
  tool-per-operation baseline built on the same operations. Measured: first-attempt success, fix iterations, human
  interventions, tokens and steps, regressions after follow-up changes, and **the count of tasks that fail on a missing
  primitive**. That second run is what turns §2.4's borrowed evidence into this project's own.
- **QA catalog entries** for the user-visible part (proposal, diff, approve, refusal), with `visual` tests for the review UI
  (`qa/catalog/`, `qa/README.md`).

---

## 12. The owner's answers (2026-11-12)

The owner answered the nine questions of the first draft on PR #326. They are recorded in §1 (C7 to C14) and applied in the
sections cited. One question stays open.

| # | Question | Answer | Lands in |
|---|---|---|---|
| 1 | Sandbox technology | **A**: QuickJS compiled to WebAssembly in a sandboxed opaque-origin iframe; B (a Worker in a sandboxed iframe) is the fallback. | C7, §4.3 |
| 2 | Model provider and billing | **Still open.** The specific model is to be decided. Every answer stays bound by the cost guardrails and by the end-to-end boundary (a model call is where content leaves the page, master §14.8). | master §14.8 |
| 3 | Publishing the SDK for local or external agents | **Not a priority; not published for now.** The core stays DOM-free so the door is not closed, but no package boundary, trust model or handshake for an outside agent is designed or built. | C8, §8 |
| 4 | Where the type-check runs | **In the editor page, in a Worker**, with the TypeScript checker as a static asset. | C9, §5.1 |
| 5 | Autonomy | **The agent is trusted for the most part.** Creating new content needs no approval. Changing or deleting existing content needs the owner's approval **once per object**: after the first approval, later changes to that object in the session apply without asking again. | C10, §5.6 |
| 6 | Stacked proposals | **Ask once.** Asking for permission at every step is the annoyance to avoid: one approval covers every existing object a plan names, across the programs of that plan. | C11, §5.5, §5.6 |
| 7 | The one-pack rule and the 50-document cap | **Kept for now**, open to lifting later if they become a problem. | C12, §5.4 |
| 8 | Program retention | **Keep the program text** (size-bounded) in the audit, not only a digest. | C13, §5.6, §10 |
| 9 | D13 or an amendment to D12 | **D13**, a decision of its own. | C14, master §3.2 |

---

## 13. Not verified

- The numbers of §2.4 are as the fetched pages report them: the arXiv page through a mirror, and the daily.dev page is a
  summary of an article that was not read. None was cross-checked against the full papers or the original article.
- *Resolved by #340 (§4.3, "Measured"):* the embedding (promise-returning host function, interrupt handler, hard memory cap
  through `WebAssembly.Memory`), the bundle sizes, the engine in a blob Worker spawned by an opaque-origin iframe, and the
  TypeScript checker's size, cold start and check time were **measured in Chromium** (Playwright 1.64.1's build, localhost, one
  machine, Windows 11). **Still not tested:** Firefox and WebKit (the repository's Playwright config has Chromium only, and
  installing the other browsers was out of scope), so in those browsers the following are open: a `blob:` Worker spawned from
  an opaque-origin frame (Safari has historically been the strict one), the native wasm stack depth that decides whether the
  256 KiB QuickJS stack is safe, `WebAssembly.Memory` `maximum` behaviour, and `transfer` of an `ArrayBuffer` plus a
  `MessagePort` in one `postMessage` to a `'*'` target. Also untested: whether the frame is in the editor's renderer process on
  other browsers (the measured freeze shows it shares the thread in Chromium), phones, real network latency to the relay for
  reads, and realistic program sizes (the #320 benchmark programs were not run; the 25-line program is synthetic, the `.d.ts` is a
  200-member stand-in, not #337's golden). The proposed limits of §4.2 are therefore floors to be raised by #343's fixtures, not
  ceilings.
- Which reads exist on the editor wire today (traces, runtime variables) was read from `api.md` and `web.md`, not from the
  code (§10 item 5).
- **Narrowed by #337 (§3.8).** Polymorphic entries are built and compiled: an action, condition, mechanic, trigger or GUI
  component is a typed builder keyed by registry id, type-checked by the real `typescript` package against the recorded
  registries. What stays open is the logic expression language (`m.expr`): only its *interpolations* are typed (a trigger's
  context values, handles, `m.fn` results); how much of the expression *text* TypeScript or the stage check can verify is not
  decided, and a bare identifier in the text is checked by neither today.
- Not verified by #337: the generated surface was type-checked on the headless recording of today's plugin
  (`EditorDemoSnapshotTest`: no economy provider, so `give_money` and `take_money` are recorded as unavailable) and on a fake addon,
  never against a live server's `authoring.describe`; and that every `firedBy` entry of a trigger names a content type was read from
  the data, not enforced (hosts are matched by name, so one that is not a content type simply has no handle that can add the rule).
- *Resolved by #341:* the `LogicEdit` and `GuiEdit` vocabularies could be driven from one in-memory transaction without
  reworking their revision handling: each surface got a `stage` method that applies the edits to a parsed tree, and its `edit`
  keeps its own revision check (§10, as built). `bulk` is untouched.
