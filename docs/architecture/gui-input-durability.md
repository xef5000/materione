# GUI input slots — crash-safe ownership (#247) — design

> **Scope.** Who owns the items a player has put into a GUI's input slots (logic.md §6.5, §25), and what survives a
> `kill -9`: a versioned, two-part escrow protocol (an account inside the player's own saved data, plus an append-only
> ledger file for the one thing the player's data cannot hold: a decision that must outlive a rollback), its recovery,
> its crash-boundary proof obligations, its test plan and its implementation rows. It also corrects the durability
> claims the shipped journal makes (§2).
>
> **Status: design, answered by the owner on PR #303 (§14); rows M (the pure model, codecs and recovery, #307), R (the probes, #306), S (the `durable:` schema and studio field, #308), L (the ledger IO, #309), P (the plugin runtime: manual clicks, owed and claim, the death release, the recovery gate, #310) and H (the kill-boundary IT, K1 to K22 against a real `kill -9`, #313) are built, as recorded in §12; the tier-3 barrier (row B) and the barrier's crash row K23 are not.** The owner first kept the work
> blocked until the platform acknowledges saves, then ruled that **the tier-3 barrier (`Player.saveData()` plus a file
> read-back, §5.4) *is* that acknowledgment: the block on #247 and #248 is lifted.** This document is the accepted design
> to build. What the lift does and does not change: the rows of §12 may now be filed and built, and #248 waits on them
> (row X), not on an outside ack. Row P (#310) made `durable: required` layers take the escrow path; `best_effort` layers (the default) are the journal of logic.md §25 decision 12, unchanged. **Nothing may be described as crash-safe until rows M, L, P, B and H are green on main:** row B is not built and row H is built **except K23, which needs row B** (§12, *Row H as built*), so a `required` layer is *tracked, recoverable by construction and verified by a kill-boundary run for every tier-1 and recovery row (K1 to K22)* and *not yet verified for the barrier (K23)*, and every word in the product says so.
>
> **Row R (#306) has run the platform probes P1 to P4 (§11).** All four are settled and the design stands: a fake-connection
> player works on the 1.21.4 harness (P4), a completed save survives `destroyForcibly` (P2), the death ordering and both
> `keepInventory` values are as designed (P3), and on Folia the player save runs on the thread that runs entity-scheduler
> tasks, shown by experiment with a positive control (P1, F12), so Folia needs no atomicity mechanism of its own. What the probes
> added to the design is small and listed in §11 ("What the results changed"): the Folia stop path, the Folia off-thread-save
> exposure, and a platform note on the fake player's reach.
>
> Traceability: **D1** (§9: the escrow is runtime player state, not content, and creates no second source of truth for
> content); **D4/D6** (the feature is runtime plumbing under the one ECA engine and the one GUI pipeline; no flow reaches
> the player's items except through it); **D7** (derived runtime state is durable and lives in `storage/`);
> **D8** (no IO on a region thread — §5.6); **D10** (both pins, 1.21.4 and 26.1.2, are read — §3).
>
> **D12 reading** (architecture §3.1, [`primitives.md`](primitives.md) §4.8, §5.1): the account and the ledger are the **custody**
> primitive under a GUI-specific name. Input slots are one place custody can sit, and the reserved `HAND_OFF` kind is the atomic
> transfer between holders. Nothing in this design changes, and no row is reordered: the generalization of the name, the holders
> and the entry points waits for rows B and H, so that it never weakens a guarantee that is not yet proven.

---

## 1. Goal, non-goals, the guarantee

**Goal.** An item that the plugin took from a player into an input slot is, at every instant a `kill -9` can land,
either still the player's (in the inventory their saved data holds) or recoverable from state the next boot reads before
the player can interact — exactly once, never zero times, never twice.

**Non-goals.** Power loss or OS crash (the server's own player-data write has no `fsync` — §3 F1 — so no plugin can be
stronger than the server there; our own ledger's `fsync`, on by default, is §5.3, but the player data is the weaker half).
Items on the player's cursor at the instant of the crash (vanilla loses them too — §3 F4). **Owner answer (§14 Q3): the
vanilla cursor exposure is accepted and is documented as a non-guarantee (G5).** A stack picked up from an input slot
leaves the account in the same step it lands on the cursor, and is the player's cursor stack from then on. Rollback of unrelated player state (XP, position, other
plugins' data) — recovery restores the last saved snapshot as a vanilla restart would. Cross-server shared player data.
Another plugin that calls `Player.saveData()` off the owner thread, or that rewrites inventories wholesale (§3 F8).

**The guarantee, stated once so no other sentence needs to widen it.** For a platform with checkpoint support (§5.8):

| | |
|---|---|
| **G1 Conservation** | After recovery, every stack that was in the saved inventory or the saved escrow account is present exactly once (inventory, or the account as *owed*), except stacks whose consumption was durably decided (G2) and a stack that was on the cursor at the crash (G5). |
| **G2 No resurrection** | A stack whose consume decision reached the ledger (§5.4) is never returned by recovery, however old the saved snapshot is. |
| **G3 Bounded rollback** | The player's state after recovery is *some completed save* plus recovery. A transfer or consumption after that save may be undone as a whole; it is never half-visible. A consume whose source removal postdates the save leaves the item **in the inventory** (not duplicated, not consumed). Only the approved barrier (§5.4, tier 3; Q4) removes this. |
| **G4 No world IO** | The protocol never writes to a world (no drop at the feet) on a path a crash could repeat; overflow stays in the account (§5.5). |
| **G5 Cursor: not guaranteed** | A stack on the player's cursor at the crash is lost, exactly as in vanilla (F4). This includes a stack the player has just lifted out of an input slot. The protocol keeps the window's other stacks and the inventory exact; it does not reach the cursor. (Owner-accepted, §14 Q3.) |

Anything stronger (e.g. "the trash happened exactly once even though the player's save predates it") is **not claimed**.

---

## 2. What exists, what it claims, and which claims are wrong

The shipped mechanism (#218, logic.md §25 decisions 8 and 12): after each change the server has already made, the plugin
copies the items of the player's current window's input slots into the player's persistent data under
`materione:gui_inputs` (`InputJournal`, `GuiJournal`); a release (close, quit, replacement, removal) gives the items to
the inventory, then mirrors the empty window; the join handler gives back whatever the journal still holds. The manual
moves (`shiftIn`, `shiftOut`, `doubleClick`, `gui.slot_item`, rewrite, re-open) mirror in the same call; the **single-slot
clicks and drags are applied by the server after the handler returns, so they mirror one tick later**
(`GuiJournal.mirrorNextTick`).

| # | Claim (where) | Verdict | Why |
|---|---|---|---|
| W1 | `InputJournal` Javadoc: "the crash journal"; "the invariant that makes it duplication-proof"; "so a save can never see both" | **Overstated** | Holds only for steps done synchronously on the owner thread. For a server-applied click or drag the mirror is the next tick, and the autosave pass runs *between* (§3 F6). Two states a save can capture: **move-in** (item gone from the inventory, journal not yet written) → a crash **loses** it; **move-out** (item back in the inventory, journal still holds it) → a crash **duplicates** it. `InputJournalTest.aReleaseWithoutAMirrorWouldReturnTheItemTwice` already encodes the second. |
| W2 | `GuiJournal`/`InputJournal` Javadoc, logic.md §25 header "including the crash journal" | **Misnamed** | It is a recovery journal with a known open window (W1), not a crash journal. logic.md §25 decision 12 already says "best-effort"; the header and the Javadocs do not. |
| W3 | #218 exit criterion: "items placed in an input slot survive close/quit/crash-safe paths … dupes impossible" | **Unproved** | No test kills a process. `InputJournalTest` models "saved" as plain lists; `guilogic.input-slots` is a client run on a live server. Neither can show a crash boundary. |
| W4 | logic.md §6.5 and §25 decision 8: items "go back on every end of a session"; "nothing is ever deleted unless a flow says so" | **False in two paths, unproved in a third** | *Death:* `ServerPlayer.die` computes the drop list from the inventory, fires `PlayerDeathEvent`, and only then calls `closeContainer(DEATH)` [SRV, both pins]; our close handler therefore hands the window's items to a player whose death drops are already fixed — with `keepInventory=false` they vanish at the respawn: not dropped, and not copied to the respawned player [PROBE P3, §11: confirmed]. *Overflow:* what does not fit is `dropItemNaturally`'d; a world entity is a different store from the player's data, so a crash after the drop and before the save, or an entity that despawns, or a cancelled spawn, deletes or duplicates (W6). *Cursor:* an item picked up from an input slot is on the cursor, in neither the journal nor any save (the owner accepted this exposure, G5). |
| W5 | `GuiJournal.mirror` / `restore` catch `RuntimeException` and log one warning | **Silent loss of protection** | A failure to record leaves the window unprotected with the player unaware and the click already applied. Fail-soft is right for content (D6); it is wrong for an ownership transfer, which must fail *closed*: refuse the transfer. |
| W6 | `restore` on join: "gives back what the journal still holds, once" | **Not idempotent under overflow** | `restore` clears the journal in the live data, hands items to `InputReturns.giveBack`, which drops what does not fit. Crash again before the next save: the saved data still has the journal *and* a dropped entity may already be in the world. |
| W7 | logic.md §25 decision 12: "A separate plugin journal or `.dat` replacement cannot coordinate subsequent server saves" and "A server extension must coordinate all saves … acknowledge an ordered asynchronous write before interaction resumes" | **Too broad, in one respect** | True that the platform gives no acknowledged save and that the plugin cannot order a write against the server's. **Not true that this makes plugin-side crash-safety impossible:** the player's persistent data is serialised *in the same compound as the inventory* and written in one file replace (§3 F1, F3). A marker in that data is therefore atomic with the inventory *by construction*, with no coordination, if the plugin changes both in one owner-thread step. What genuinely stays unavailable is a platform acknowledgment of *when* a snapshot is durable — which only matters for the optional barrier (tier 3). This design uses that. The owner first kept the block until a platform acknowledgment existed (§14 Q1), then ruled that the tier-3 barrier is that acknowledgment (§14 Q1b) and lifted the block. |

Corrections made in the same PR as this document: the logic.md wording of W2, W4 and W7 (§25 decisions 8 and 12 carry
a pointer here). The Javadoc text of W1/W2 is a **comment-only** fix that lives in the module the QA ledger's `covers`
globs watch, so it rides row D0 rather than a docs PR (it would stale the blessed `guilogic.input-slots` result).

---

## 3. Platform facts

Evidence labels (the manner of gui.md §12.2.1): **API** — Paper API sources of the 1.21.4 and 26.1.2 pins, from the
Gradle cache; **SRV** — the Paper server jars the harness and the probe already hold (`paper-1.21.4.jar`,
`paper-26.1.2.jar`), read with `javap -p -c -constants`, method names given; **REPO** — this repository's code;
**U** — assumption, with the probe that settles it (§11); **PROBE** — a harness run on a real server (row R, #306, §11); **FOLIA** —
the Folia server jars (`folia-1.21.4-6`, `folia-26.1.2-8`, §11 P1) read with `javap -p -c -constants`, method names given. The
design pass (#303) downloaded nothing and ran no server; row R fetched the two Folia jars (owner-approved, §14 Q9b) and ran the
Paper 1.21.4 and Folia 1.21.4 servers.

| # | Fact | Label | 1.21.4 | 26.1.2 |
|---|---|---|---|---|
| **F1** | `PlayerDataStorage.save(Player)` serialises the **live** player (`Player.saveWithoutId`) on the **caller's thread** into a temp file in `playerdata/`, writes it with `NbtIo.writeCompressed` (a buffered stream; **no `force`/`FileChannel` anywhere in `NbtIo`**), then `Util.safeReplaceFile(.dat, temp, .dat_old)`. Any exception is caught and logged (`Failed to save player data for {}`); the method is `void`. `SpigotConfig.disablePlayerDataSaving` skips the save entirely. | SRV | ✔ | ✔ (same shape, `TagValueOutput`) |
| **F2** | `safeReplaceFile` moves `.dat` to `.dat_old`, then the temp file to `.dat`, undoing on failure. `load` reads `.dat` and falls back to `.dat_old` (`Optional.or`). A kill between the two moves therefore yields the **previous complete snapshot**, never a torn file. A kill mid-write leaves a stray temp file and the old `.dat`. | SRV | ✔ | ✔ |
| **F3** | The inventory (`Inventory`, `EnderItems`, …) and the persistent data (`CraftEntity.storeBukkitValues`) are written by the **same `Entity.saveWithoutId` call into one compound**: one snapshot, one file. No cursor/carried-item key exists (`Player.addAdditionalSaveData`). | SRV | ✔ | ✔ |
| **F4** | The cursor is not saved. `PlayerList.remove` calls `closeContainer(DISCONNECT)` (which fires `InventoryCloseEvent` and runs `AbstractContainerMenu.removed`, returning the carried item to the inventory or the world) **before** `PlayerQuitEvent` and **before** `save`. | SRV | ✔ | ✔ |
| **F5** | `CraftPlayer.saveData()` calls `playerIo.save(handle)` directly: synchronous, `void`, **on whatever thread calls it**. `PlayerList.saveAll(int)` goes through `MCUtil.ensureMain`, and `PlayerList.save` skips non-persistent players and stamps `lastSave`. | SRV / API (`Player.saveData` Javadoc) | ✔ | ✔ |
| **F6** | In `MinecraftServer.tickServer`: `mainThreadHeartbeat` (plugin scheduler tasks) → `tickConnection` (`ServerConnectionListener.tick`: packet handlers, hence `handleContainerClick`) → `tickChildren` → **`PlayerList.saveAll(rate)`** (the Paper per-player autosave; a negative `player-autosave.rate` falls back to the world autosave period, Paper's 6000 ticks by its documentation, not read here) → tick end. All on the main thread. `tickChildren` also contains a heartbeat and a `tickConnection`, in that order (heartbeat first). A task scheduled "one tick later" from a packet handled by that last `tickConnection` therefore runs in the **next** tick, after this tick's `saveAll`; the hole is real whenever a click arrives there (whether the earlier `tickConnection` is also exposed was not traced). | SRV | ✔ | ✔ (same order) |
| **F7** | Shutdown: `stopServer` stops the connection listener, calls `PlayerList.saveAll()` **while windows are still open**, then `removeAll(true)` (which disconnects each player). A quit or kick goes through `PlayerList.remove`: close the container, fire the quit event, **then** save. **On Folia the stop path has no such full save: F15.** | SRV | ✔ | ✔ (same order) |
| **F8** | Nothing prevents another plugin from calling `Player.saveData()` from another thread, or from replacing inventories wholesale (inventory-sync plugins that serialise the inventory but not our persistent data). | API / SRV (F5) | ✔ | ✔ |
| **F9** | `AsyncPlayerPreLoginEvent` is fired off the main thread, before the player exists (it knows the UUID), so IO may run in it. | API | ✔ | ✔ |
| **F10** | `ServerPlayer.die`: drop list from `getInventory().getContents()` → `CraftEventFactory.callPlayerDeathEvent` (fires the event, then drops the list) → `closeContainer(DEATH)`. | SRV + **PROBE (P3)**: the order was observed, death event, then `InventoryCloseEvent(DEATH)`, then the respawn event, with `inventoryAtClose` still holding the dead inventory | ✔ | ✔ (`die` signature differs; same order read) |
| **F11** | A kill of the JVM (`destroyForcibly`/`kill -9`) cannot interrupt a `rename` half-way on the platforms we run on, and data already `write()`n to the OS survives it (page cache) — so for **process** crashes a completed `write`+`rename` is durable without `fsync`. | **PROBE (P2)** for the second half: a save that returned, then a kill, left the complete new snapshot on disk (K1, K2, K21 of the skeleton IT, Windows 11). The first half (no half-done `rename`) is OS semantics: the gate cannot sit *inside* `safeReplaceFile`, so K3/K4 are checked as file states (F2's fallback is exercised, the kill itself is not) | ✔ (Paper 1.21.4 b232) | not run (the harness is the 1.21.4 floor) |
| **F12** | **Folia:** the thread that runs the player's save is the same one that runs `Entity` scheduler tasks (so a plugin step and a save cannot interleave). **Folia must be supported for every layer (§14 Q9), so this was a target to prove, not a reason to refuse. Proved (P1).** In one region tick, `MinecraftServer.tickServer(…TickRegionData)` runs, in this order: the region task queue, `FoliaRegionScheduler.tick`, **`EntityScheduler.executeTick` for every entity of the region**, `tickChildren` (which contains the region's `tickConnections`, hence packet handlers and `handleContainerClick`), then **`PlayerList.saveAll(rate)`**. `saveAll` saves only players for which `TickThread.isTickThreadFor(player)` holds, i.e. the player's own region thread, and calls the same `PlayerList.save` → `PlayerDataStorage.save` as F1. The quit path (`ServerGamePacketListenerImpl.removePlayerFromWorld` → `PlayerList.remove`: close container, quit event, save) makes no scheduler hop; that its caller, `onDisconnect`, is driven by the region's connection tick was not traced further. | **FOLIA** (bytecode, both builds) **+ PROBE (P1)**: on Folia 1.21.4 b6 a step that writes `a=n`, holds the region thread 20 ms and writes `b=n`, against an autosave of rate 1 and a reader of the `.dat`, showed **0 torn snapshots in 4 495 reads (190 distinct saves)**; the same step against an off-thread `saveData()` loop showed **419 torn snapshots in 4 397 reads**, so the detector sees interleaving when it exists | ✔ Folia 1.21.4 b6 (read + run) | ✔ Folia 26.1.2 b8 (read: same call order, same `isTickThreadFor` guard; not run: the protocol client has no 26.x build) |
| **F13** | `ItemStack.serializeAsBytes`/`deserializeBytes` (Paper) round-trip a stack including its data version, and are what `GuiJournal` already uses. | API / REPO | ✔ | ✔ |
| **F14** | `spigot.yml` `players.disable-saving` is readable at enable (a file read off-thread) and equals `disablePlayerDataSaving` (F1). | SRV (field) / API (`Spigot#getSpigotConfig`, deprecated for removal) | ✔ | ✔ |
| **F15** | **Folia stop path.** `MinecraftServer.stopServer` halts the region schedulers (`TickRegionScheduler.halt`) and then calls `PlayerList.removeAll(true)`; **there is no `PlayerList.saveAll()` before it** (Paper has one, F7). A server stop on Folia is therefore the quit path for every online player (close the container, quit event, then save), never "a full save with windows still open". `isTickThreadFor` also admits the shutdown thread for players, after the regions have stopped, so no region step runs during it. | **FOLIA** (read; `stopServer` offsets, both builds) | ✔ Folia 1.21.4 b6 | ✔ Folia 26.1.2 b8 |
| **F16** | **Folia does not reject an off-thread `Player#saveData()`.** `CraftPlayer.saveData` calls `PlayerDataStorage.save` on the caller's thread and the serialisation of the live player reads it from there; no thread check intervenes. Another plugin that does this (F8) can capture the inventory and the persistent data from different instants of an in-flight step, on Folia as on Paper — more often on Folia, where saves and steps run concurrently on many threads. | **PROBE (P1 control)**: 400 requested, 400 completed, no failure, 419 torn snapshots in the reader | ✔ Folia 1.21.4 b6 | not run |

**What follows.** F3 + F5/F6 give the one fact the whole design stands on: on a single-owner-thread platform, **a plugin
step that changes the inventory and the persistent data cannot be split by a save** (saves run on the main thread, at a
fixed point of the tick, never inside a handler). F1/F2 give crash-consistent file replacement for free. F11 means our
own ledger needs a completed `write` (not an `fsync`) for process-crash durability. F4/F10 bound what the cursor and
death can do. F6 is why the next-tick mirror is a real hole. F12 was the one genuine platform gap (the owner requires Folia support); probe P1 closed it: on Folia the same fact holds, with the one difference F15 and one exposure F16 records.

---

## 4. Alternatives considered

| Option | Verdict |
|---|---|
| **A. Mirror everything into the PDC synchronously (today's journal, minus the one-tick hole).** | Atomic with the inventory by F3; no IO, no latency. Correct *as rollback to a snapshot*. Cannot by itself keep a **consumption** from being rolled back (G2), cannot track overflow without a world drop, cannot detect that its own record was lost. **Kept as tier 1 — it is the anchor.** |
| **B. A plugin file as the escrow (items live in `storage/`).** | A file write and a player save are two durable steps with no common commit; the protocol that makes them safe is write-ahead plus a marker *in the player's data* — i.e. A plus a log, with IO on the hot path of every click. Rejected as the primary store. |
| **C. Server-side checkpoint extension (the earlier "needs" in logic.md §25.12).** | Would give an acknowledged save, at the price of a server patch/agent every operator must install. Not needed for G1–G4; only tier 3 wants an acknowledgment, and §5.4 builds one from `saveData()` plus a read-back. The owner ruled that this read-back *is* the acknowledgment that lifts the block (§14 Q1b). |
| **D. Call `Player.saveData()` after every transfer.** | Synchronous disk IO on the owner thread per click: violates D8 and the budget (§9 of plugin.md). Allowed only as the approved, rate-limited tier-3 barrier (a D8 exception, §13). |
| **E. Pre-login gating alone.** | Gives recovery-before-interaction (used, §5.7) but no online checkpoint. |

---

## 5. The design

### 5.1 Vocabulary

| Term | Meaning |
|---|---|
| **Account** | The player's escrow account: one versioned binary value `materione:gui_escrow` in the player's persistent data (replaces `materione:gui_inputs`; the old key is read once for migration, §10). It lives in the player's snapshot (F3). |
| **Entry** | One escrowed stack: `id` (128 bit, random), `state`, `stack` (F13), `origin` (gui id, slot hint, for diagnostics only), `since`. States: **HELD** (in an input slot of the open window), **OWED** (to be returned, no room yet), **EXITING** (frozen: a consume decision is being made, `txn` set). |
| **Generation** (`gen`) | A `u64` in the account, incremented by every mutation. Not a clock: it orders snapshots of one player. |
| **Ledger** | `storage/escrow/<uuid>.ledger`: an append-only record log of **exit decisions** (§5.4). Created lazily — a player who never consumes has none. |
| **Exit** | An entry leaving the player's snapshot for a place the snapshot does not hold: **consume** (deleted) today; a *hand-off* (to a world drop, another player, a chest) is reserved for later and uses the same records. |
| **Checkpoint support** | The platform properties of §5.8 under which G1–G4 hold. |

### 5.2 Invariants

| # | Invariant | Held by |
|---|---|---|
| **I1** | Every stack the plugin removes from the player's inventory into a window is added to the account **in the same owner-thread step** (no `await`, no scheduler hop, no event dispatch between the two). | §5.5, enforced by one `EscrowTransfer` API that takes both sides and a ban test that the old per-slot mirror is gone. |
| **I2** | Every stack leaves the account for the inventory in the same step as it enters the inventory. | same |
| **I3** | A stack is in **at most one** of {inventory, window slot, cursor}, and an account entry exists for a stack **iff** it is in a window slot or owed. A stack that moves to the cursor leaves the account in that same step; the cursor is outside the guarantee (G5). | §5.5 click matrix |
| **I4** | No disk IO, and no blocking wait, on a region/main thread. The owner-thread work is in-memory encoding (≤ a budget, §8). | §5.6 |
| **I5** | A consume decision is **durable before** its effect is applied, and an entry with a durable decision is never returned. | §5.4 |
| **I6** | Recovery is a pure function of (saved account, ledger, saved inventory); running it twice on the same inputs is the same as once. | §5.7, property-tested |
| **I7** | Any condition under which I1–I6 cannot be met **refuses the transfer** with a stable diagnostic; it never degrades silently. | §5.8, §7 |

### 5.3 Formats (v1)

Both are versioned containers; readers accept N and N−1 and treat a newer version as **opaque and read-only**
(plugin.md §7.1). Both ship **golden byte fixtures** (§8); the format only grows additively.

**Account** (`BYTE_ARRAY`, big-endian):

```
u8   magic 0x4D   u8 version = 1
u64  gen
u16  entryCount
entry*  : 16B id | u8 state | 16B txn (zero unless EXITING) | u64 sinceMillis
          | u16 len + utf8 originGui | u16 slotHint | u32 len + stack bytes (F13)
u32  crc32 over everything above
```

A bad CRC, an unknown newer `version`, or a decode error makes the account **unreadable**: it is preserved untouched,
the player's strict transfers are refused (`MTR-0101`), and nothing is returned or deleted. (Rationale: an unreadable
account is the one state in which guessing could destroy items.)

**Ledger file** (`storage/escrow/<uuid>.ledger`): `magic "MTRL" | u16 version | records…`, each record
`u32 length | u32 crc32(type+payload) | u8 type | payload`. Types: `INTENT(txn, entryId, kind, stackHash16, amount,
atMillis)`, `DONE(txn)`. Appends are `FileChannel.write` in `APPEND` mode; a **torn tail** (kill mid-append) fails its CRC
and is *not a record* — it is ignored on read and truncated by the next writer. A bad record **before** the end is
corruption: the file is renamed `<uuid>.ledger.corrupt-<millis>`, `MTR-0101`, strict transfers refused.
`escrow.fsync` (default `true`) adds `force(false)` after each append — it helps only against power loss, is not needed for the
process-crash guarantee (F11), and is affordable because it is off the region thread (§5.6). Compaction (rewrite to a temp
file, atomic rename) runs off-thread at recovery and drops `INTENT`+`DONE` pairs older than `escrow.retention`
(default 30 days, owner-confirmed, §14 Q6 — it bounds how old a restored player-data backup may be before G2 stops covering it). The ledger lives in `storage/`, so it is covered by the operator's `storage/` backups (D7, owner-confirmed).

**Why a file here and not the PDC alone (D1/D7).** The account can say what the player owns; it cannot say that an entry
*was consumed* once the snapshot that recorded the consumption is rolled back by a crash — the older snapshot still holds
the entry. A decision that must survive a rollback has to live outside the rolled-back store. §9 justifies the file.

### 5.4 The transfer protocol — three tiers

**Tier 1 — an ownership transfer inside the player's snapshot** (move-in, move-out, swap, rewrite, re-open, close,
overflow). Pure memory, one owner-thread step, no IO:

```
step (owner thread):
   verify the request against the live state (stale/foreign requests are dropped, §5.5)
   mutate inventory / window slots            ─┐ one step: no save can land between (F3, F5, F6)
   mutate the account (entries, gen++)         ─┘
   encode the account to bytes (in memory), set the PDC value
```

The account is encoded *eagerly in the step* (not lazily at the next tick): the live PDC always matches the live
inventory. A snapshot is therefore always (inventory, account) from the same instant — **G1 by construction**.
If encoding fails (a stack that does not serialise, the account over its size cap of 256 KiB or 54 entries, owner-confirmed §14 Q7), the step is not performed:
the click is cancelled, `MTR-0102`, the player is told (I7).

**Tier 2 — an exit with a durable decision** (consume now; hand-off later). Used by #248:

```
1  owner:  validate every check for the whole move (as #248 states); move the incoming stack into a new
           entry E in state EXITING(txn T)   — a tier-1 step: source removed, account+E, gen++; E is frozen:
           clicks on its slot are refused ("busy"), release/close/quit do NOT return it
2  async:  append INTENT(T, E.id, …) to the ledger; ack when the write (and force) returned
           — queued per player, FIFO, off every region thread
3  owner:  on ack, re-enter the owner thread via the scheduling gateway; if the player is online and E is still
           in the account as EXITING(T): delete E (tier-1 step: slot cleared, account−E, gen++)
           otherwise: nothing (the decision is durable; recovery finishes it — §5.7)
4  async:  once the barrier (tier 3) has acknowledged a snapshot with gen ≥ g(E), append DONE(T) — only then is the
           exit *acknowledged*; before that it is decided (durable, applied live) but still exposed to G3's rollback case
```

*The decision point is the CRC-valid `INTENT` record.* Before it: a crash returns E (recovery sees `EXITING` with no
`INTENT` → returns the stack). After it: a crash consumes E (recovery sees the `INTENT` → drops the entry). There is no
third state, and step 3 never "revalidates into an abort" (an abort after a durable decision is exactly the dup/loss
the ledger exists to prevent). A request is stale — rejected **before step 1** — if its session id, window generation or
click sequence number is not the live one; after step 1 only the ledger decides.

*The one rollback case (G3).* If the snapshot on disk predates step 1, E is not in its account and the stack is still in
its inventory; recovery finds an `INTENT` for an entry it does not hold and does nothing — the player keeps the item.
That is a rollback, not a duplicate (no second copy exists), **but** if the consume was paired with an *effect* (a flow
that pays money after consuming), the effect survived the rollback. #248's trash has no effect. For effect-carrying
exits, **tier 3**:

**Tier 3 — the barrier, which is the acknowledgment (owner-approved §14 Q4; ruled to be the platform acknowledgment that lifts the block, §14 Q1b; row B).** Before the effect, require proof that a snapshot with `gen ≥ g(E)` is on disk:
(a) wait for it (the next autosave; minutes), or (b) request one: on the owner thread call `Player.saveData()` once per
player per `escrow.barrier.cooldown` (default 5 s) — this is the *platform's own* save, bounded in cost to what the
autosave already pays per player, and the only synchronous IO this design asks for. **This is an explicit, owner-approved exception to D8**
(recorded in §13 and as an amendment note on D8 in architecture.md, effective when row B ships) — then confirm by **reading the file back** off-thread (parse the gzip NBT, read `BukkitValues → materione:gui_escrow`,
compare `gen`). The read-back is the acknowledgment the platform does not provide (`saveData` is `void`, F1); a failed
or stale read-back means "not yet", never "assume". Every tier-2 exit is acknowledged by it (step 4: `DONE`, and the end of its G3 exposure), and an exit that
carries an *effect* additionally **waits** for it before the effect runs. #248's trash has no effect, so it does not
wait: the player sees the deletion at step 3, the acknowledgment follows within the cooldown. The exception is narrow: one `Player.saveData()` per player per cooldown, only for such exits, and never on a
tier-1 or recovery path.

### 5.5 What every action does

Strict input layers (§5.8) apply this table. "Manual" means the plugin cancels the Bukkit event and applies the whole
result itself in one tier-1 step (the server never applies a click that changes an escrowed slot) — what the multi-slot
moves already do; this design extends it to every click that touches an input slot, closing W1.

| Action | Behaviour | Notes |
|---|---|---|
| **Click: place from cursor** (left, right, one) | manual: cursor → slot; entry created/merged; cursor reduced | The cursor was the source; whatever happened to it before is vanilla's. |
| **Click: pick up from a slot** (left, right, middle) | manual: slot → the **cursor**, as in vanilla; the entry leaves the account in the same step | The cursor is outside the guarantee (G5, owner-accepted §14 Q3): a crash while the stack is on the cursor loses it, exactly as vanilla. No redirect to the inventory, no lien mode. |
| **Click: swap** (cursor ↔ slot, number key, offhand) | manual, one step: the slot's stack goes to the cursor (or the hotbar/offhand slot), the other stack into the slot | The hotbar and offhand slots are inventory, so a number-key or offhand swap is fully inside the guarantee; a cursor swap is as pickup. |
| **Shift-click in / out, double-click collect** | manual (as today) | Already planned by `Transfers`; moves to the tier-1 API. |
| **Drag** | manual: cancel, distribute `getNewItems()` into the input slots, reduce the cursor | A drag touching a non-input window slot stays refused. |
| **Drop key, Ctrl-drop, creative clone, unknown action** | refused (as today) | A drop is a world write (G4). |
| **`gui.slot_item` `take`** | tier 1 (entry → inventory; overflow → OWED) | |
| **`gui.slot_item` `clear`, #248's consume-incoming** | tier 2 | The only consumers. |
| **`gui.slot_item` `set_amount`/`repair`/`enchant`** | tier 1 (entry updated, `gen++`) | An in-place edit of an escrowed stack. |
| **Close (player, flow, replaced window)** | tier 1: every HELD entry → inventory; what does not fit → OWED; frozen EXITING entries stay | Nothing is dropped. The player is told how many stacks are owed (`/materione claim`). |
| **Reload / shape-changing re-open** | tier 1: entries keep ids, slot hints change; entries whose slot stopped being an input are returned | No IO. The in-flight window is replaced in one step. |
| **Quit / kick / server stop** | the platform fires `InventoryCloseEvent(DISCONNECT)` before the save (F4, F7): the close above runs, so the snapshot has the stacks in the inventory or OWED, **not** HELD; EXITING entries stay in the account with their `txn` | The save that follows contains the matching (inventory, account). |
| **Death** | owner-decided (§14 Q10): in `PlayerDeathEvent`, one tier-1 step: with `getKeepInventory()` the HELD entries return to the inventory; otherwise their stacks are appended to `event.getDrops()` (the list is mutable and dropped right after the event, F10). EXITING entries follow the ledger: consumed if an `INTENT` exists, else treated like HELD | Fixes W4. The ordering is F10 and both `keepInventory` values were measured (P3, §11): a stack appended to `getDrops()` is dropped at the death location, a stack added to the inventory in the event survives the respawn, and a stack released later, at the `closeContainer(DEATH)`, survives only when `keepInventory` is true. |
| **Plugin disable / `/reload`** | `onDisable` releases every window synchronously (tier 1) before the server saves | |
| **Overflow** | stays **OWED** in the account; claimed by `/materione claim`, on join, and on the next close; never `dropItemNaturally` | G4. Fixes W6. |
| **Cursor held across a re-open** | carried as today (`setItemOnCursor`); not escrowed | Vanilla exposure (G5). |
| **Stale / foreign / out-of-context request** | dropped: wrong player, wrong session id, wrong window generation, a click for a slot that is not an input in the live view, a request after the recovery gate closed | |

### 5.6 Threading (D8)

| Work | Thread | IO? |
|---|---|---|
| Tier-1 steps; account encode; PDC set | owner (main on Paper; the entity's region on Folia) | none — memory only |
| Ledger append (+ `force`), read, compaction, quarantine rename | the existing IO executor (`SchedulingGateway.executeAsync`; plugin.md §2 "IO threads") | yes |
| Ledger preload for a joining player | `AsyncPlayerPreLoginEvent` handler (F9) | yes (read) |
| Tier-3 read-back | IO executor | yes (read) |
| Tier-3 `Player.saveData()` | owner, rate-limited | yes — the platform's own; the owner-approved D8 exception (Q4) |
| Continuation after a write ack | back to the owner via `executeEntity`; never a blocking `get()` | none |

**On Folia** the owner thread of a player is the thread of the region that owns it (F12): a tier-1 step is one `executeEntity` task, and the tier-3 `Player.saveData()` is called from such a task too (it ran there in P1, in 9 ms). A step split over two entity tasks is not atomic against the save, on Folia as anywhere: I1 is unchanged.

A ban test (build.md R-series, new rule) fails the build if a class of the `escrow` package's owner-side half imports
`java.io`/`java.nio.file` or calls `Future#get`/`join`; the IO half may not import Bukkit entities.

### 5.7 Recovery (before interaction, idempotent)

```
AsyncPlayerPreLoginEvent (async):  read + validate the ledger into memory, queue behind any in-flight writes for the
                                   same UUID (a rejoin right after a quit waits for its own INTENT). Failure → mark
                                   the player UNRECOVERABLE (kept in memory), MTR-0101; the join is NOT refused.
PlayerJoinEvent (owner, LOW):      gate = CLOSED
                                   account = decode(PDC)            (absent → empty; unreadable → UNRECOVERABLE)
                                   for each entry e:
                                       if ledger.hasIntent(e.id): drop e   # G2: the decision is durable (any state)
                                       else: owed(e)                       # G1: HELD, OWED, or EXITING without INTENT
                                   try inventory.add for each owed e; the rest stay OWED in the account
                                   gen++, PDC = encode(account); tell the player (count of stacks returned / owed)
                                   gate = OPEN  — strict input transfers and strict GUI opens are refused until OPEN
```

* **Idempotent** (I6): the inputs are the saved snapshot and ledger records that only grow within `escrow.retention`;
  a second crash before the next save recomputes the identical result. A saved account that recovery already drained is
  empty, so a third run does nothing.
* **Before interaction**: the gate is closed from the join handler's first line; the pre-login read means it needs no IO.
  If the pre-login preload did not finish (it is a timeout, not a lock — 5 s) the gate stays closed and the player sees
  why (`MTR-0101` warning, message), instead of the join being held.
* **`.dat_old` fallback** (F2) and a player-data **backup restore** both present an older snapshot: recovery treats it
  like any other; G2 covers consumed entries as far back as `escrow.retention`.
* **Legacy `gui_inputs`**: decoded once, converted to OWED entries, removed (§10).

### 5.8 Checkpoint support — the capability, and the explicit blocker

Strict durability is **on** only when all of these hold; the result is computed at enable and re-checked on reload, and
published to `/materione doctor`.

| Check | Source | If false |
|---|---|---|
| The platform is a pin whose F1–F7 and F10 were read (Paper 1.21.4, 26.1.2) | the platform probe (plugin.md §1) + this table | unsupported platform |
| Single-owner-thread saves (F12) hold on this platform. Paper 1.21.4 and 26.1.2: read. **Folia: supported** — probe P1 passed on Folia 1.21.4 b6 (run, with a positive control) and the same call order and guard were read on Folia 26.1.2 b8; the platform probe recognises Folia and the capability check applies the Folia rules below | platform probe | unsupported platform |
| Player-data saving enabled (F14) | `spigot.yml` `players.disable-saving` at enable | unsupported platform |
| The escrow directory is writable (a create+delete probe at enable, off-thread) | `storage/escrow/` | ledger unavailable |

**Folia (P1 passed, so no Folia-specific atomicity mechanism is designed).** F12 holds: the save and the entity-scheduler
tasks of a player run on the player's region thread, one after the other within a region tick, so a tier-1 step (one
`executeEntity` task) cannot be split by a save. Folia changes four things, none of them a new mechanism:

1. every tier-1 step and every tier-3 `Player.saveData()` runs inside an `executeEntity` task of the owning player (§5.6);
2. **K8's server-stop row is the quit row on Folia** (F15): `stopServer` calls `removeAll` only, so every window is closed
   before the one save, never saved while open;
3. F8's foreign off-thread `saveData()` is a live exposure on Folia (F16: not rejected, torn snapshots seen), which the plugin
   cannot prevent; it stays the documented limit of P7 and `doctor` lists known offenders the same way;
4. a player in transit between regions is not on any region thread, so the autosave guard skips it (read from `saveAll`'s
   `isTickThreadFor` test, not run) and no step runs on it either.

The capability check keeps its other rows for environments that genuinely cannot meet G1–G4
(saving disabled, an unwritable `storage/escrow/`, a platform build nobody has read).

**When a check fails** the platform lacks checkpoint support. This is an **explicit documented blocker, not a silent
degrade**: (1) `MTR-0100` once at enable naming the failed check (error if any layer is `durable: required`, warning
otherwise); (2) every input layer declared `durable: required` — and every flow action that needs the ledger (#248) — is
**refused**: the layer renders but its slots accept nothing, the click is cancelled, and the player gets a one-line
message ("this slot is unavailable on this server"); (3) layers that are `best_effort` keep today's behaviour and
journal (logic.md §25.12), labelled as such in `doctor` and in the GUI's validation result; (4) nothing in docs, messages
or QA calls the result crash-safe. `durable: required | best_effort` is a per-layer setting (owner-decided, §14 Q2), defaulting to `best_effort` until the
block lifts; #248's consume action requires a `durable: required` layer.

### 5.9 Diagnostics (proposed; assigned from the free range)

`MTR-0099` stays with gui.md §12.4 (an input on a slot the client refuses). **Owner answer (§14 Q8): this feature takes
`MTR-0100`, the code gui.md §12.4 had left reserved-unassigned, so its codes are `MTR-0100` to `MTR-0103`** (the block runs
from `MTR-0100`, one number earlier than the first draft). `MTR-0101` and `MTR-0102` were **assigned by row L (#309)**: they are in
`DiagnosticCodes`, the ledger IO emits them, and plugin.md §5 documents them. `MTR-0100` and `MTR-0103` were **assigned by row P
(#310)** and are emitted by the runtime (§12, *Row P as built*).

| Code | Severity | Meaning and recovery |
|---|---|---|
| `MTR-0100` | error / warning | This platform lacks checkpoint support (§5.8): the failed check is named. Error when a `durable: required` layer or action exists (it is refused), warning otherwise. Recovery: fix the named check (enable player-data saving, make `storage/escrow/` writable, run a supported build), or set the layer to `best_effort`. |
| `MTR-0101` | warning | A player's escrow state is unreadable: a corrupt or newer-version account or ledger, or a failed preload. The data is preserved untouched; that player's strict transfers are refused until an admin resolves it by hand (the file is preserved; admin tooling is a follow-up, §14 Q11). Names the file and the cause. |
| `MTR-0102` | warning | A strict transfer was refused because it could not be recorded: an account that cannot be encoded or is over its cap, or a ledger append that failed (disk full, permissions). The click did nothing; the player was told. |
| `MTR-0103` | warning | Recovery found stacks it could not return (full inventory): they are OWED in the player's account and listed. Reported once per join with the count. |

### 5.10 The crash-boundary table

`S` = the last completed player save on disk; `A(n)` = the account at generation n in the live data; `L` = ledger
records that completed. "Kill" = `kill -9` at that instant; the next boot runs §5.7. Every row is a test (§8).

| # | Boundary | On disk at the kill | After boot | Why nothing is lost or duplicated |
|---|---|---|---|---|
| **K1** | Tier-1 **move-in**, handler returned, no save since | `S` (stack in inventory, `A(n)` or none) | stack in the inventory | The step is in memory; the older snapshot is self-consistent (F3). Rolled back as a whole (G3). |
| **K2** | …a save then completed (`rename` done) | `S'`: stack gone from the inventory, in `A(n+1)` as HELD | stack returned once | The save wrote inventory and account from the same instant (I1, F3, F6). |
| **K3** | Kill **during** that save (temp file partially written) | `S` + stray temp | as K1 | F2: the old `.dat` is intact; the temp is never read. |
| **K4** | Kill between `safeReplaceFile`'s two moves | `.dat_old` = `S`, no `.dat` | as K1 | F2: `load` falls back to `.dat_old`, a complete snapshot. |
| **K5** | Tier-1 **move-out** (slot → inventory), no save since | `S'` (stack in `A`, not in the inventory) | stack returned once | The older snapshot still holds the stack in the account only. |
| **K6** | …a save then completed | `S''` (stack in the inventory, gone from `A`) | nothing owed | Same instant for both (I2). The old design duplicated here on the next-tick mirror (W1). |
| **K7** | **Close with overflow** | the save has the stacks OWED in `A`, inventory full | stacks stay OWED; claim later | No world drop (G4). Kill after the save: still OWED. Before it: as K5. |
| **K8** | **Quit** (`remove`: close → quit event → save) or **server stop** (`saveAll` with windows still open, then `removeAll`) | `S` of that save: on a quit, stacks in the inventory or OWED (none HELD); on a stop, HELD entries may still be in `A` | as K2/K7 (on Folia a server stop is the quit sequence: F15) | F4/F7: on a quit the close runs before the save; on a stop the full save already holds HELD entries in the account (tier 1), and the later releases only move them. Kill mid-quit before the save: as K1/K5. |
| **K9** | **Death** | the next save holds the stacks in the inventory (`keepInventory`) or they are drops on the ground (the world's store, outside the guarantee once dropped); the account no longer holds them | stacks follow the normal death path | F10: the event handler moves the entries in the same step, before the list is dropped. (Probe P3 confirmed both `keepInventory` values, §11.) |
| **K10** | Tier-2 consume, **before `INTENT` completed** (mid-append / not yet written) | `S` with E not yet in `A`, or `S'` with E EXITING; no valid `INTENT` | E returned (or the item stays in the inventory) | The decision point is the CRC-valid record; a torn tail is not a record (§5.3). |
| **K11** | …`INTENT` completed, step 3 not yet run | `INTENT(T)`; `S'` with E EXITING (or `S` without E) | E dropped by recovery (or nothing to drop: the item is in the inventory, G3) | Decision durable ⇒ never returned (G2/I5). |
| **K12** | …step 3 ran, no save since | `INTENT(T)`; `S'` with E EXITING | E dropped by recovery | Same. |
| **K13** | …step 3 ran, a save completed | `INTENT(T)`; `S''` without E | nothing | Already consumed once; the record is inert. |
| **K14** | Tier-2 consume, **player quits before the ack** | save with E EXITING; `INTENT` appended after | on rejoin the preload waits for the append (FIFO); recovery sees `INTENT` → drops E | The frozen entry was *not* returned at close (§5.5), so there is no second copy. |
| **K15** | **Recovery itself** killed at any point | unchanged (recovery only mutates live memory) | the same recovery, the same result | I6. |
| **K16** | **Second crash** after a recovery that was saved | `S` with the stacks now in the inventory, `A` empty | nothing | A drained account returns nothing. |
| **K17** | Ledger **torn tail** from a kill mid-append | valid records + a partial one | partial ignored and truncated by the next append | CRC. |
| **K18** | Ledger **corrupt mid-file** / account corrupt / newer version | untouched + `.corrupt-<ms>` rename | strict transfers refused for that player; `MTR-0101`; **nothing returned or deleted** | Guessing is the only way to lose items; refusing is not. |
| **K19** | **Autosave lands between a click and its next-tick mirror** (the W1 window) | cannot occur: no deferred mirror exists; every strict click is manual | — | I1. This is the row the old design failed. |
| **K20** | **Plugin disable / `/reload`** with windows open | releases ran before the server's save | as K8 | `onDisable` is synchronous tier 1. |
| **K21** | **Pickup** (slot → cursor), kill with the stack on the cursor | `S'` (stack in `A`) before the pickup step, or `S''` (gone from `A`) after a save that followed it | before a save of the pickup: the stack is returned (rollback, as K5); after it: **the stack is lost** | G5: the cursor is in no save (F4); owner-accepted (§14 Q3). The one row where the answer is "loss", and it is vanilla's. |
| **K22** | **Placement** (cursor → slot) | `S` with no entry (the stack was on the cursor), or `S'` with it HELD | nothing owed, or returned once | The step creates the entry atomically with the cursor reduction; a kill before it is the vanilla cursor case (G5), after it K2. |
| **K23** | **Barrier**: kill after `saveData()` before the read-back confirms, or the read-back finds an older `gen` | the snapshot may or may not contain E's exit; `INTENT(T)` without `DONE` | an effect-carrying exit's effect **never ran** (it waits for the acknowledgment); recovery applies the `INTENT` as K11/K12 | "Not acknowledged" is never read as "acknowledged" (§5.4); the effect is delivered at most once and only after the decision and the snapshot are both durable. |

Rows K10–K14 are the answer to "does the delete happen exactly once": it happens iff `INTENT` is durable, applied by the
live step 3 or by recovery, and a record applied twice is a no-op because the entry id is gone.

---

## 6. Relationship to #218 and #248

* **#218 (shipped):** its click policy, `accept:`, `gui.slot_change` before the change, `SlotPolicy`/`Transfers`/
  `InputSlots` stay. What changes is *how a result is applied* (one tier-1 API instead of "let the server apply, mirror
  later") and what is recorded (the account instead of the flat journal). `gui.slot_change` still fires pre-move.
* **#248 (unblocked by the lift of the block, waits for rows M, L, P, B, H):** its "share the recoverable ownership transaction with the durability follow-up" is §5.4 tier 2;
  "stage consumption against the incoming amount, effective only after every check for the whole move passes" is step 1
  (nothing is removed from the source until every check passed, and the removal is the tier-1 step that creates E);
  "prevent stale/delayed/out-of-context requests and duplicate consumption" is §5.5's stale rule plus the entry-id
  idempotence of K11–K13; "recovery preserves deliberate deletion" is G2. #248 additionally needs the typed action
  through the public GUI action SPI and its fixture — unchanged by this design and **not started**.

## 7. Refusal UX

The owner accepted the refusal copy (§14 Q5). With the cursor exposure accepted (Q3) a pickup behaves as in vanilla, so there
is no "inventory full" refusal; the only added lines are *unavailable*, *busy* (a frozen EXITING slot) and *owed*.

A refused strict transfer: the click is cancelled (nothing moved), the player gets one actionbar line from the
`messages` table (`gui.escrow.unavailable`, `gui.escrow.busy`; a close that leaves stacks owed adds `gui.escrow.owed`), and the reason is logged with its code
at most once per player per minute. `doctor` lists the capability result and any player currently UNRECOVERABLE.

## 8. Test plan

| Layer | What | Notes |
|---|---|---|
| **Unit (pure)** | The escrow model generic over holder and stack (as `InputJournal<K, T>` is): tier-1 steps, tier-2 state machine, recovery. | no Bukkit, no IO |
| **Model-based property test** | Random sequences of {move-in, move-out, swap, close, overflow, consume, quit, death, reload} × a **crash at every step boundary** × **every earlier snapshot** as the saved one; the oracle checks G1–G3 after recovery. The seed set is committed; failures print the seed. | this is the executable form of the K table; it also replays K19 against the *old* design as a negative control |
| **Golden fixtures** | Account v1 and ledger v1 as hex goldens (empty, one HELD, OWED+EXITING, a torn tail, a bad CRC, a newer version, the legacy `gui_inputs`); decode→encode is byte-identical; N−1 accepted, N+1 opaque. The corpus only grows (roadmap rule 2). | `fixtures/escrow/` |
| **Ban tests** | owner-side half has no `java.io`/`java.nio.file`/`Future#get`; IO half has no Bukkit entity import; no `dropItemNaturally` in the input package; no `mirrorNextTick`. | plugin.md §11 style |
| **Harness IT (kill boundaries)** | `PaperEscrowCrashIT`: a test-only probe plugin (as `containerProbe`) owns a fake-connection player and a **crash gate** — a named point in the production code that, only when `-Dmaterione.escrow.gate=<name>` is set, logs `MATERIONE-ESCROW-GATE <name>` and parks the thread. The harness sees the marker, `killTree`s the process (it already has it), boots the same workspace, rejoins the player and asserts G1–G3 for K1–K23 (`saveData()` is called from the console at chosen moments to fix the snapshot). | CI, 1.21.4 floor. **R1 is done (#306, §11 P4):** the rig exists (`CrashRig`, the probe plugin's fake-connection player and `dprobe gate`) and `PaperInputDurabilityIT` carries K1, K2, K4 (file state simulated) and K21 as the skeleton, opt-in (`-Pharness.inputDurability=true`). **Row H is built (#313, §12 *Row H as built*):** the stand-in account and `dprobe gate` are gone, the product's account and `CrashGate` replace them, `PaperInputDurabilityIT` keeps only the save-path and death probes, and `PaperEscrowCrashIT` carries K1 to K22 (K23 waits for row B): its core group (K1, K2, K5, K11/K12, K18) is in the default run, the rest opt-in. |
| **Probe loop (local)** | The same boundaries with a real client on 26.1.2 for the ones a fake player cannot show (cursor, UI messages, hotbar swaps). | local only (CLAUDE.md) |
| **QA catalog** (`qa/catalog/guiescrow.mjs`) | `guiescrow.refused-unsupported` (auto: the layer is inert, message line, `MTR-0100`), `guiescrow.owed-claim` (auto: full inventory, close, `/materione claim`), `guiescrow.recovery-message` (auto: the join line after a runner-side `kill`), `guiescrow.pickup-lifts-to-cursor` (visual: the stack lifts as in vanilla and the account loses it in the same step), `guiescrow.death-release` (auto: `keepInventory` on/off). | blessed on main; kill/restart needs a `probe.mjs` server-restart verb (row Q) |
| **Perf** | `EscrowPerfTest`: a 27-stack account encode ≤ 200 µs on the owner thread (added to plugin.md §9 by row P); a tier-1 step allocates no IO. | budget enforced in CI |

## 9. D1, D7 and the second source of truth

D1 says *content* — the thing authors edit and the editor round-trips — lives in files, and nothing may be a second
source of truth for it. The escrow account and ledger are not content: no editor reads or writes them, no pack derives
from them, and deleting them loses no authored data. They are **player runtime state**, in the class the persistence
layout (plugin.md §7) already gives the player PDC (cooldowns, declared variables) and `storage/` (`variables.json`,
sessions, audit). D7 already says derived *runtime* state is durable and operator-backed-up, with the allocation table as
its precedent for "history the content graph cannot re-derive". The ledger is the same shape: a decision history the
player's data cannot re-derive after a rollback. **No D1 or D7 amendment is proposed**; row Q adds the persistence-table
row (`storage/escrow/<uuid>.ledger`, "runtime state, written by append from the IO executor, backed up with `storage/`").
There is one authoritative store per fact: *what the player owns* is the account (in the snapshot); *what was decided to be
consumed* is the ledger. Neither duplicates the other, and recovery is defined for every combination.

## 10. Rollout and migration

1. `durable: required | best_effort` is a **per-layer** setting (owner-decided, §14 Q2), default `best_effort`, so nothing
   changes for an existing GUI. There is no global `escrow.mode`. A `best_effort` layer keeps today's journal and its
   honest label (W1 corrected in comments by D0); a `required` layer uses the account and ledger and is refused where
   §5.8 fails. The attribute is part of the layer's schema self-description (D5) and the studio inspector (row S).
2. The legacy key `gui_inputs` is read once on join (a crash-era leftover), converted to OWED entries and removed, on
   any platform where the account exists.
3. Downgrade: an older plugin ignores `gui_escrow`; a player whose account holds entries would not get them back until
   upgraded again — documented, and `doctor` lists accounts with entries.
4. Nothing ships until rows M, L, P, B and H are green on main; the block no longer gates it (§14 Q1b).

## 11. Probes: what was unverified, and what row R found

Row R (#306) ran P1 to P4. The harness pieces are in `harness/` (opt-in, §12 row R): the **crash rig** (`CrashRig`: boot, kill at a
named gate, boot again on the same world, read the player's `.dat` from the harness with `PlayerDat`), the test-only
`durabilityProbe` plugin (the fake-connection player, the gate command and the K-row primitives, never shipped), `DeathProbeRunner`,
`FoliaRunner` and the test-only `foliaProbe` plugin. To reproduce:

```
./gradlew :harness:integrationTest --tests dev.materione.harness.PaperInputDurabilityIT -Pharness.inputDurability=true
./gradlew :harness:integrationTest --tests dev.materione.harness.PaperEscrowCrashIT -Pharness.inputDurability=true     # row H: the kill rows, K1 to K22
./gradlew :harness:integrationTest --tests dev.materione.harness.FoliaInputDurabilityIT -Pharness.folia=true   # after: npm ci --ignore-scripts, in harness/protocol
```

| # | Claim | Result | Evidence |
|---|---|---|---|
| **P1** | **F12:** on Folia the player save and `executeEntity` tasks share a thread | **Verified** on Folia 1.21.4 build 6 (read and run); read on Folia 26.1.2 build 8 | F12, F15, F16 (§3). The call order of `tickServer`, the `isTickThreadFor` guard of `PlayerList.saveAll` and the stop path are the same in both builds (`javap`). The experiment ran on 1.21.4 only: its protocol client has no 26.x build. **0 torn snapshots in 4 495 reads (190 distinct saves)** against an autosave of rate 1 and a step that holds the region thread 20 ms between two writes; the positive control (an off-thread `saveData()` loop against the same step) showed **419 in 4 397**, so the detector is sensitive. A `saveData()` called inside an entity-scheduler task ran on `Region Scheduler Thread #0` (`owned=true`) and took 9 ms. F12 holds, so §5.8 gets no Folia atomicity mechanism; it gets the three consequences F15, F16 and the "one entity task per step" rule |
| **P2** | **F11:** a completed `write`+`rename` survives `destroyForcibly` | **Verified** for the half that matters (a completed save is on disk after a kill); the half about a kill *inside* the replace is OS semantics and was not exercised (K3/K4 are file states) | `PaperInputDurabilityIT` rows K1, K2, K21 (since moved to `PaperEscrowCrashIT`, row H, against the product's account): the JVM tree is `destroyForcibly`'d while the main thread is parked at a named gate, the harness reads the `.dat`, the same workspace boots and the rejoined player is asked what it holds. **K1** (a move-in, no save since): the disk holds the earlier snapshot, the stack is still in the inventory and no account exists; after the boot the stack is in the inventory. **K2** (move-in, then a save): the disk holds the stack in the account (`gen=1`) and not in the inventory, from one save (F3); after the boot the account still owes it. **K21** (a stack lifted to the cursor by a real click packet, then a save): the stack is in neither the inventory nor any cursor key on disk, and gone after the boot: G5 as designed. **K4** (file state simulated: `.dat` deleted after two saves): `.dat_old` exists and is the *first* snapshot, whole, and the boot loads it: F2's fallback, and the observation that after every completed save `.dat_old` is the snapshot before it. Windows 11, Java 21, Paper 1.21.4 b232; nothing in the IT is Windows-specific, but only Windows was run. The gate cannot sit inside `PlayerDataStorage.save`, so K3 (a kill mid-write) and a real K4 (between the two moves) stay untested at the kill itself; row H can add a JVM-level hook if wanted |
| **P3** | **W4/F10:** `keepInventory=false` — what becomes of stacks released by the close after the death event; and that appending to `PlayerDeathEvent#getDrops()` drops them and `keepInventory=true` keeps the returned ones | **Verified**, all four cells (table below) | `DeathProbeRunner`: a fake player holds 3 apples in the inventory and 5 diamonds in an open window, then `setHealth(0)`; two release strategies, both gamerule values. The event order was observed: `PlayerDeathEvent` (drops computed from the inventory only: the window's diamonds are not in them), then `InventoryCloseEvent(DEATH)` (the inventory still holds the dead player's items), then `PlayerRespawnEvent` |
| **P4** | A fake-connection player on the 1.21.4 harness is enough for `PlayerDataStorage` saves, PDC, `InventoryClickEvent`/`DragEvent` and `closeContainer` | **Verified**, with the limits below | `platformPaths`: a real `ServerPlayer` placed by `PlayerList.placeNewPlayer` over a `Connection` on a netty `EmbeddedChannel` (reflection only, no server dependency at build time), on a server that also runs Materione's own join handlers. **Saves:** `Player#saveData`, the quit path (`PlayerList.remove`), the shutdown path (`stop` with the player online, exit 0) and the Paper autosave (`player-auto-save.rate: 20`, a later change reached the file with no save command) each wrote the player's PDC to `<uuid>.dat`, and the next join loaded it. **Persistent data:** a string, a long and a byte-array account round-tripped, atomically with the inventory (K2). **Events:** a `ServerboundContainerClickPacket` handled by the player's own listener fired `InventoryClickEvent` (raw 54 in the probe window), three quick-craft packets fired `InventoryDragEvent` (raw slots `[0, 1]`, two diamonds each, one left on the cursor), and `Player#closeInventory` fired `InventoryCloseEvent` showing the window and returned the cursor remainder to the inventory (F4). **Death and respawn** (P3) work on the same player |

**P3, the four cells** (Paper 1.21.4 b232; "released" = the window's 5 diamonds; the inventory held 3 apples):

| Release strategy | `keepInventory` | Dropped in the world | Respawned player holds | Reading |
|---|---|---|---|---|
| at `InventoryCloseEvent` (the shipped behaviour) | false | the apples only | nothing | **the diamonds vanish**: given to the inventory after the drops were fixed, then not copied to the respawned player. W4 is confirmed |
| at `InventoryCloseEvent` | true | nothing | apples and diamonds | survives, because the inventory is kept whole, including what was added after the event |
| in `PlayerDeathEvent`: appended to `getDrops()` | false | apples and diamonds | nothing | **the design's path**: the list is mutable and is dropped right after the event (F10) |
| in `PlayerDeathEvent`: added to the inventory | true | nothing | apples and diamonds | **the design's path** |

**What the fake player does not reach** (the limits of P4; none changes the design, all matter to row H):

* No login sequence: `AsyncPlayerPreLoginEvent` and `PlayerLoginEvent` are not fired, so the pre-login ledger preload (§5.7, F9) cannot **Row H (built):** the probe fires the pre-login event by hand from a plain thread, so the product's own listener preloads (`dprobe login`).
  be exercised through a join. Row H calls the preload directly, or fires the event by hand.
* The connection is not in `ServerConnectionListener`'s list, so the listener's per-tick work (keep-alive, packet limits, the
  disconnect handling) never runs; `leave` calls `PlayerList.remove` directly, which is what `onDisconnect` does.
* Clientbound packets are discarded, so nothing about what a *client* sees (a title, a cursor sync, a hotbar swap) can be asserted:
  that is the protocol client's and the 26.x probe loop's ground (§8, "Probe loop").
* The probe reaches the server by reflection on Mojang-mapped names, which Paper 1.21.4 uses at runtime; it was not run on 26.1.2.
* The probe's account (a byte array and a `gen` in the player's persistent data) is a stand-in for the escrow account, and its
  `dprobe gate` command stands in for the production crash gate (`-Dmaterione.escrow.gate`, §8). Row H replaced both (built): the probe now has neither, and the product's account and `CrashGate` are what the kill-boundary IT uses.

**Folia versions relative to the pins** (fill.papermc.io, project `folia`, checked for row R):

| Pin | Folia builds | Pinned | Channel | Java |
|---|---|---|---|---|
| Paper harness pin 1.21.4 (build 232) | 1.21.4: builds 1, 4, 6 (2024-12-15 to 2025-06-13) | `folia-harness-*`: build 6, sha256 `dcf23332…fa2ac3a` | ALPHA | 21 |
| Paper probe pin 26.1.2 (build 74) | 26.1.2: builds 6, 7, 8 (2026-05-06) | `folia-probe-*`: build 8, sha256 `607afd1c…79ebf57b` | STABLE | 25 |

Both pins have a Folia build. Folia also publishes 1.19.4, 1.20.1 to 1.20.6, 1.21.5, 1.21.6, 1.21.8, 1.21.11, 26.2 and 26.3, so a pin bump
need not leave Folia behind. The two jars are pinned like the Paper jars (absolute URL and sha256 in `gradle/libs.versions.toml`, verified by
the same download task into the Gradle cache); only the opt-in Folia scenario uses them, and nothing is committed. The Folia 1.21.4 line's last
build is from 2025-06-13 and its channel is ALPHA; Folia's own changes to the join and disconnect flow
(region-scheduled, `placeNewPlayer` with extra arguments on 1.21.4) are why the Folia player is a real protocol client, not a fake connection.

**What the results changed in this document** (none changes G1 to G5, a D-number or the protocol):

1. F11 and F12 are no longer assumptions (§3); F15 (the Folia stop path) and F16 (Folia accepts an off-thread `saveData`) are new facts.
2. §5.8: Folia is **supported**, no longer "not yet probed"; the paragraph that held a placeholder for a Folia mechanism is replaced by the
   four consequences of F12 (one `executeEntity` task per step; K8's stop row is the quit row on Folia; the off-thread exposure; a
   player in transit). **This is the one substantive change: the owner's "Folia must be supported" is now met by the design as it stood.**
3. W4's open item (the clearing step of the dead inventory was not traced) is closed: the stacks released at the close vanish at the
   respawn; the death design (§5.5, K9) is confirmed as written.
4. §8 and §12: rows R is done; rows H and F carry what R found (what the rig is, what the fake player cannot reach, the Folia gap list).

| # | Claim | Why unverified | Probe |
|---|---|---|---|
| **P5** | `ItemSpawnEvent` cancelled ⇒ what `dropItemNaturally` returns (W4 overflow) | irrelevant once G4 removes the drop; kept only to size W4 | none needed after row P |
| **P6** | Read-back of `BukkitValues → materione:gui_escrow` from a live `<uuid>.dat` while the server may replace it (F2 window) | needed for tier 3 (approved). Row R's `PlayerDat` reads a `.dat` from outside the server and the Folia run read it about 8 900 times under a live autosave of rate 1 with every unreadable read simply skipped, which is encouraging but not the probe: the F2 window was not forced | row B |
| **P7** | An inventory-sync plugin that does not copy our PDC defeats G1 (F8) | by construction | documented limit; `doctor` warns when a known sync plugin is present (list is data) |

## 12. Implementation rows (rows M, R, S, L, P and H built; K23 of row H waits for row B; the others proposed)

Model policy (agent-delegation): senior and cross-cutting work on Sonnet at high effort, regular implementation on Haiku.

| Row | Title | Scope | Depends on | Model |
|---|---|---|---|---|
| **D0** | Claims correction | Javadoc/comment-only fix of W1/W2 in `InputJournal`, `GuiJournal`, `GuiWindowListener`; re-bless note for `guilogic.input-slots`. Docs half is in this PR. | none | Haiku |
| **R** | Probes | R1: fake-connection player + crash gate + restart skeleton on the 1.21.4 harness (P4, P2); R2: Folia (P1) on a Folia jar the owner has **approved fetching** (sha256-pinned like the other pins; not downloaded in the design pass), and the Folia mechanism if F12 fails; R3: death/`keepInventory` (P3); output: §11 filled in, K-row skeleton IT. | none | Sonnet, high. **Done (#306):** R1 to R3 ran, §11 is filled in (P1 to P4 verified). As built: the crash rig (`CrashRig`, `PlayerDat`), the `durabilityProbe` plugin (fake-connection player, `dprobe gate`, the K-row primitives), `PaperInputDurabilityIT` (the save paths, K1/K2/K4/K21, death; the K rows moved to `PaperEscrowCrashIT` with row H) and `FoliaInputDurabilityIT`, all opt-in, plus the sha256-pinned Folia jars (`folia-harness-*`, `folia-probe-*`) |
| **M** | Model, codecs, recovery | Pure escrow model (tier 1/2), account and ledger codecs, recovery, goldens, the property test with the old-design negative control. **Built (#307), as built below the table.** | none (R informs) | Sonnet, high |
| **L** | Ledger IO | Per-player FIFO async writer on the IO executor, `fsync` option, torn-tail truncation, compaction, quarantine, pre-login preload, ban tests, `MTR-0101`/`MTR-0102`. **Built (#309)**: see *Row L as built* below | M | Sonnet, high |
| **P** | Plugin runtime | One tier-1 API; every strict click made manual (single-slot, number key, offhand, drag; pickup lifts to the cursor); OWED + `/materione claim`; the `PlayerDeathEvent` release; recovery gate; capability check and refusal UX; `MTR-0100`/`MTR-0103`; `gui_inputs` migration; `EscrowPerfTest`. **Built (#310)**: see *Row P as built* below | M, L | Sonnet, high |
| **S** | Schema and studio | `durable: required \| best_effort` on `slots` layers: parse, `describe` self-description, lossless round-trip, the studio inspector field and its `webui` spec, goldens; default `best_effort`. | none (lands before P uses it) | Sonnet, high (web UI). **Built (#308)**: see *Row S as built* below |
| **H** | Kill-boundary IT | `PaperEscrowCrashIT` for K1–K23, on the row R rig: it replaces the probe's stand-in account with the product's, `dprobe gate` with the production gate, calls the pre-login preload directly (the fake player fires no login events, §11), and decides which rows join the default run. | R, P | Sonnet, high. **Built (#313), K23 pending row B**: see *Row H as built* below |
| **Q** | Close-out | QA catalog `guiescrow.*`, `probe.mjs` restart verb, plugin.md §5/§7 (the `storage/escrow/` ledger row, backed up with `storage/`)/§9, logic.md §6.5/§25 as built, glossary, CHANGELOG. | P, H | Haiku |
| **B** | Barrier (tier 3, owner-approved; **the acknowledgment**) | `saveData()` rate limit + read-back ack that writes `DONE` and releases effect-carrying exits; the D8 exception's code is this row only; ban-test allowlist for exactly one call site; K23. | L, P | Sonnet, high |
| **T** (follow-up) | Admin tooling | `/materione escrow <player>` (list, force-return, force-drop). Out of scope here (§14 Q11). | P | later |
| **X** | #248 consume-incoming action | Starts when rows M, L, P, B and H are green on main (the block was lifted, §14 Q1b, so no outside acknowledgment is awaited). Not part of this design. | M, L, P, B, H | per #248 |
| **F** | Folia platform axis in the harness and the QA suite | **Design note only; not implemented.** The owner approved fetching a Folia jar for P1 (§14 Q9b) and noted the whole QA suite may need to run on Folia. (1) `:harness` gains a platform axis `paper \| folia` beside the existing pin mechanism (`gradle/libs.versions.toml` pin, sha256-verified download, Gradle cache): each existing IT is parameterised, the log-marker contract is unchanged, and `PaperEscrowCrashIT` runs on both. (2) The probe server gains a platform choice (Folia 26.1.2 build 8 exists for the probe pin, §11); the QA runner records the platform in each ledger entry's `env`, so a pass on Paper never counts as a pass on Folia, and `plan` lists due tests per platform. (3) Tests whose steps assume the Bukkit main thread (RCON-driven `give`, `tp`, teleports to an anchor) get a Folia path or a documented skip with a reason. (4) **Row R2's first output is the measured gap list in §12.1** (Folia ships builds for both pins, §11). Its own issue, not folded into #247. | R2 | Sonnet, high |

### 12.1 Row F's gap list: the Paper integration tests on Folia (measured in row R, #306)

The Paper integration tests were run once against the pinned Folia 1.21.4 build 6 by pointing the existing `paper.jar` property at the Folia jar
(a temporary local edit, not committed; the product plugin already declares `folia-supported: true`). Of six, **three passed unchanged**
(`PaperBootstrapIT`, `PaperFailSoftIT`, `PaperWorldPersistenceIT`: the M1 and M2 exit gates, including placed blocks, furniture and a restart) and
**three failed, for three different reasons**, which is the gap list for #314:

| Failed on Folia | Why | Kind |
|---|---|---|
| `PaperContainerWindowIT` (and any IT that installs a test-only plugin: `containerProbe`, `richTitleProbe`) | Folia refuses to load a plugin not marked `folia-supported` ("Could not load plugin 'MaterioneContainerProbe v1' as it is not marked as supporting Folia!"); the probes' commands also act on players from the console's (global) thread, which on Folia must go through the entity scheduler | harness: mark the probes `folia-supported` and route per-player calls through `executeEntity` (the `foliaProbe` of row R shows the shape) |
| `PaperOpenCommandIT` | `OpenCommandRegistrar` asks for `Server#reloadData()` when a content swap adds or changes an open command (logic.md §23, decision 15); on Folia the reload fails with `UnsupportedOperationException` (Folia stubs the server's main-thread executor methods, e.g. `MinecraftServer.submit`, to throw; the exact throw site of `reloadData` was not traced). The product already degrades (`MTR-0069`: "applies at the next restart") but the IT expects the live path | **product**: a Folia path for open-command reload (restart-only is the current fallback) or an accepted, documented limit; the IT then expects `MTR-0069` on Folia |
| `PaperGuiRuntimeIT` | the 2 s live-loop budget (plugin.md §9) was exceeded (47.8 s and 3.5 s) while the Folia watchdog reported a 5.8 s global-region stall in class loading | **undiagnosed**: a real scheduling cost or machine load (the run was on a 1-tick-thread Folia, the default for this machine); needs a rerun with nothing else running before it is called a Folia defect |

Not run on Folia: `PaperEditorHandshakeIT`, `PaperRelayEditorIT`, the opt-in protocol-client floors, the QA suite and the probe loop (the 26.x client
bridge and RCON). The platform axis of (1) is, from this run, one property (the server jar) plus the per-IT expectations above; a Folia 26.1.2
server needs a JDK 25 server process, while the harness launches the server with the test JVM (21), so that axis also needs a configurable server
`java`.

### Row M as built (#307)

**Where.** `plugin` module, package `dev.materione.plugin.escrow.model` (`plugin/src/main/java/.../escrow/model/`).
The doc names an `escrow` package but no module (§5.6 says "the `escrow` package's owner-side half"); the plugin module
is where #218's input code (`plugin.gui.input`, `InputJournal<K, T>`) lives and where rows L and P will put the sibling
IO and runtime packages, so the model sits beside them. The alternatives fail on the evidence of plugin.md §11:
`behavior` is the embeddable ECA engine (R7) and has nothing to do with player inventories; `core` and the others have
no GUI input concept; a *new* Gradle module for a dozen classes would change the master §10 module list (a D-level
amendment) for a property an ArchUnit rule already enforces. Purity is therefore a build rule, not module isolation:
`EscrowModelBansTest` fails the build if a model class depends on anything but JDK value types (so: no Bukkit or Paper
type, no `java.io`/`java.nio.file`/`java.nio.channels`, no `java.util.concurrent`, no `java.time`) or on a clock, a
random source, `Thread` or `UUID`; both rules are proven against a planted violation. The package is deliberately
*outside* `plugin/.../gui/**`, so adding it does not stale the blessed QA results whose `covers` globs watch that
tree (`guicontainers.*`, `guilogic.input-slots`), and it is not under the 400-line GUI size guard.

**What exists.** `Account`/`Entry`/`EntryState`, `Ledger`/`LedgerRecord` (`Intent`, `Done`)/`ExitKind`, the codecs
(`AccountCodec`, `LedgerCodec`; reads return the sealed `AccountRead`/`LedgerRead`, never throw), `Inventory` (the
two-method port: `remove`, `tryAdd`; there is deliberately no drop method, which is G4 by construction),
`EscrowTransfer` (every tier-1 step as one call taking both sides: `moveIn`, `place`, `pickUp`, `swap`, `moveOut`,
`editStack`, `reslot`, `close`, `claim`, `death`, `adoptLegacy`), `Exits` (tier 2: `beginExit`, `freeze`, `intent`,
`complete`, `abortUndecided`, `acknowledge`) and `Recovery`. Steps return `Applied` (the next account, its eagerly
encoded bytes, and what was released to the cursor or drop list, returned, owed or consumed), `NoOp`, or `Refused` with
a `Refusal` (I7); a refusal changes neither side. No diagnostic code is assigned (`MTR-0100` to `MTR-0103` stay
proposed); `Refusal` and `Recovery.Result.Unrecoverable` are what rows L and P will map to them.

**Ambiguities the doc left, as resolved.**

| # | Question | Resolution |
|---|---|---|
| 1 | Ledger record layout (§5.3 gives names, not widths) | `length` counts `type + payload` and is a `u32`; `INTENT` payload is `16B txn, 16B entryId, u8 kind, 16B stackHash16, u32 amount, u64 atMillis` (62 bytes with the type); `DONE` is `16B txn`; types `1`/`2`; kinds `CONSUME = 1`, `HAND_OFF = 2` (reserved). Header is `"MTRL"` and `u16 1`. |
| 2 | Torn tail versus corruption | A record cut short (fewer than 8 head bytes, or an extent past the end), a record that fails its CRC and *ends the file*, and a zero-filled tail are torn: not records, ignored, `validLength` says where the next writer truncates. A bad CRC or malformed record *before* the end, an unknown type or kind, and **a length above 256 are corruption**: an append is one write, so a prefix of a record always carries its true length, and reading a garbled mid-file length as "a long record cut short" would silently drop every later INTENT and resurrect their entries. A header cut short is a file not yet born (valid, empty, `validLength` 0). |
| 3 | "Accept N and N-1" when N = 1 | The window is `[1, 1]` today (`MIN_READABLE_VERSION`); a newer version is `NEWER_VERSION` / `LedgerRead.Newer`, opaque, never appended to. |
| 4 | When does `gen` advance | Once per step, however many entries it moves. Recovery advances it only when it changed the account (an absent or empty account, or one whose entries stay OWED unchanged, is left as is, so a drained account really does nothing and costs no write). |
| 5 | Where the caps (54 entries, 256 KiB) bind | On steps that can *grow* the account (`moveIn`, `place`, `swap`, `editStack`, `adoptLegacy`), before the inventory is touched, as `ENTRY_CAP`/`SIZE_CAP`. Never on decode and never on a step that only shrinks or keeps the account (move-out, pick-up, close, claim, death, recovery): releasing items must not be refused because an account is over a cap it was not written under. |
| 6 | Recovery for OWED, and for a frozen entry that does not fit | Recovery retries every entry: HELD, OWED, and EXITING without an INTENT. What does not fit stays OWED (an EXITING entry that stays loses its `txn`, since it is now simply owed). An INTENT drops the entry in any state. Matching is by **entry id** (the INTENT's `stackHash16` and `amount` are audit fields, never matched on): a stack that re-entered the account under a new id after an older snapshot held it under another is a rollback to that snapshot (G3), not a G2 breach. |
| 7 | `close` order | OWED entries try the inventory before HELD ones (they are the oldest debt); frozen entries are untouched; with nothing to release it is a `NoOp`. |
| 8 | A failed INTENT append (the `MTR-0102` path) | `Exits.abortUndecided` returns the frozen stack (or owes it) **only while no INTENT is durable**; after the decision it refuses (`DECIDED`). There is no other abort. `Exits.complete` (step 3) refuses without a durable INTENT (`UNDECIDED`) and is a `NoOp` when the entry is already gone. |
| 9 | Step 4 | `Exits.acknowledge` yields `DONE` only for a decided, not-yet-done exit whose step-1 `gen` the saved snapshot's `gen` has reached (unsigned order); "not yet" is empty, never "acknowledged" (K23's model half; the `saveData()` and read-back are row B). |
| 10 | The legacy `gui_inputs` golden of §8 | Deferred to row P: the legacy bytes are Bukkit's `ItemStack.serializeItemsAsBytes`, which cannot be decoded without a server. The model offers `adoptLegacy` (the decoded stacks as OWED entries in one step); the legacy golden lands where its decoder does. |

**What the property test proves** (`CrashBoundaryPropertyTest`, 400 committed seeds x 60 steps, ~490,000 simulated
`kill -9`s, about 2 s). Random sequences of give, move-in, move-out, pick-up, place, swap, close, claim, edit, reslot,
freeze, incoming consume, ledger append, apply (step 3), acknowledge, abort-while-undecided, save, death (both
`keepInventory` values), quit and rejoin run against the production model with real byte codecs (the account goes
through `AccountCodec` at every step, the ledger is a byte file written by `LedgerCodec`). After **every** step a kill
is simulated against **every save so far and the join-time disk**, with the ledger as it stood and with a torn partial
append of the next record cut at three places. The oracle checks: **G1/G3** the recovered inventory plus what is still
owed equals exactly the saved snapshot's holdings minus the stacks whose INTENT is durable (nothing lost, nothing
twice, at stack granularity); **G2** no stack of an entry with a durable INTENT returns, from any snapshot, however
old; **G4** recovery wrote nothing to a world; **I6/K15/K16** recovering twice is identical and a kill right after a
saved recovery recovers to the same holdings; **save atomicity** (I1, "never half-visible") every save holds exactly
what the player owned at that instant, checked before any crash, because a save can land between any two steps (F6);
and I7 (a refused step leaves the inventory untouched). Coverage counters assert that the run really reached G2
recoveries, G3 "INTENT for a predating snapshot" recoveries, overflow-stays-owed recoveries and torn tails. It proves
the *model*; the kill-boundary IT on a real server (row H) and the platform facts (F1-F12, probes P1-P4) are not
touched by it, K21/K22 are modelled only as "the entry leaves or enters the account in the same step as the cursor",
and K23's `saveData()` and read-back are row B.

**Negative controls (the oracle must be able to fail).** (1) The shipped design: the real `InputJournal` with the
next-tick mirror (`OldMirrorWorld`) is caught on most seeds as a **loss at save** (move-in saved before its mirror,
K19), a **duplicate at save** (move-out saved before its mirror, W1), a **loss on recovery** and a **world write**
(release overflow dropped at the feet, W6); `k19ByHand...` scripts the first two. (2) `IGNORE_LEDGER` (recovery forgets
the ledger) is caught as **resurrection**; (3) `DROP_ON_OVERFLOW` (overflow dropped instead of owed) is caught as a
**world write** and a **loss**. Rows that follow reproduce a failure with
`CrashHarness.run(new EscrowWorld(seed, Mutation.NONE), seed, 60)`; a failing seed is printed with its step trace.

**Goldens.** `fixtures/escrow/*.hex` (15 byte-exact fixtures, see `fixtures/README.md`): account v1 empty, one HELD,
OWED + EXITING with a non-ASCII origin, bad CRC, newer version, truncated, valid CRC with an unknown state byte; ledger
v1 empty, INTENT + DONE, torn tail, bad-CRC last record, corrupt mid-file, newer version, zero-filled tail, torn
header. Every readable one decodes to the expected model and re-encodes byte-identically; every unreadable one keeps
its raw bytes. Single-bit-flip and every-prefix tests cover the rest.

### Row L as built (#309)

The ledger's file IO, its preload, compaction and quarantine, and the two diagnostics. **Nothing at runtime calls the
append API yet**: the tier-1 runtime and the recovery gate are row P, the barrier is row B. What does run on a live server
is the preload (every allowed login reads that player's ledger into memory; a player with no file costs one failed file
open) and the release on quit, wired by the bootstrap phase `escrow-ledger`.

**Where.** Three packages under `plugin/.../escrow/`, one per permission set of §5.6 (`EscrowModelBansTest` and
`EscrowIoBansTest` enforce all three):

| Package | May | May not |
|---|---|---|
| `model` (row M, plus `Compaction`) | JDK value types | everything else (row M's rule) |
| `io` | files, channels, blocking an executor thread | any `org.bukkit` or `io.papermc` type, so it cannot do owner-thread work |
| `runtime` (the listener now; rows P and B's owner-side classes later) | Bukkit | `java.io`, `java.nio.file`, `java.nio.channels`, `Future#get`/`join`, `Thread#sleep`, a latch or condition `await`; calling `EscrowLedgerStore#awaitPreload` (only `EscrowLedgerListener` may, across the whole plugin) |

**The API rows P and B call** (`EscrowLedgerStore`, one per plugin, `MaterionePlugin#escrowLedger()`). Every method
returns at once and does no IO on the caller's thread; the work runs on the IO executor, behind every earlier operation
for the same player. A future completes on an IO thread: continue by hopping back with
`scheduling.executeEntity(plugin, player, ...)`, never `get()`/`join()`.

| Call | Use | Result |
|---|---|---|
| `preloaded(uuid)` | the join handler's recovery input; memory only, any thread | `Optional<LedgerState>`: **empty means the preload has not finished or the image was dropped, and the recovery gate stays closed** (§5.7) |
| `preload(uuid)` | a player who is already online when the plugin enables or reloads (the pre-login event is past); idempotent | `CompletableFuture<LedgerState>` |
| `LedgerState#recover(AccountRead, Inventory)` | the join handler: `Recovery.recover` against the preloaded ledger, no IO | `Recovery.Result` (an `Unrecoverable` state maps to `Result.Unrecoverable`, cause `LEDGER`; an unreadable account is reported first, cause `ACCOUNT`) |
| `append(uuid, LedgerRecord)` | tier 2 step 2 (`Exits.intent(...)`) and step 4 (`Exits.acknowledge(...)`'s `DONE`) | `CompletableFuture<AppendResult>`: `Durable(ledger)` after the write (and the force when `fsync`) returned, and the ledger image to give `Exits.complete` (step 3); `Refused(reason, detail)` with `UNRECOVERABLE`, `WRITE_FAILED` or `SHUTDOWN` |
| `compact(uuid)` | on demand (the preload also does it when something is droppable) | `CompactResult` (`COMPACTED`, `NOTHING_TO_DO`, `SKIPPED`, `FAILED`); never throws |
| `release(uuid)` | quit (the listener does it) | drops the in-memory image once the player's queue is empty |
| `fileOf(uuid)`, `close()` | tests; plugin disable | |

The decision rule on a result: `Durable` is the decision point (§5.4), apply step 3 on the owner thread. `Refused` means
**no record exists**: `Exits.abortUndecided` is correct, and the click is refused (I7). That "refused means not recorded"
is made true by cutting the record back off when its write or force fails (below). An `UNRECOVERABLE` refusal is the
`MTR-0101` state, not `MTR-0102`.

**Decisions and ambiguities, as resolved.**

| # | Question | Resolution |
|---|---|---|
| 1 | What "the IO executor" is | `SchedulingGateway.executeAsync` runs each task on Paper's async pool with **no ordering between tasks**, so FIFO per player is built on top: a per-player queue drained by at most one task at a time (a lock, so `close` can take over a drain that never started), at most 16 tasks per executor task before it yields. Different players' queues run in parallel; nothing blocks an executor thread on another player |
| 2 | Torn-tail truncation: "on open" or "by the next writer" (§5.3 says the latter) | **When the writer opens the file for its first append after a load** (and by a compaction, which rewrites only valid records). A read never mutates, so a preload leaves a torn tail (a kill mid-append, or a flipped bit in the last record) on disk for forensics until the next write. The append also checks the file's size against what it last saw and re-reads on a mismatch, so a tail added or a file rewritten behind its back is handled the same way |
| 3 | "Refused" versus a record that landed | A failed write or force cuts the partial record back off (`truncate(validLength)`, best effort) before the refusal, so a caller that aborts the exit is never contradicted by a CRC-valid INTENT. The residue is a disk too broken to truncate, which the next load reads as it finds it |
| 4 | Quarantine (K18) across a restart | The corrupt file is renamed to `<uuid>.ledger.corrupt-<millis>` (a numeric suffix when taken; never replaced, never deleted), `MTR-0101`, the player's appends are refused. After a restart there is no ledger file, and a fresh empty one would forget every INTENT and let their entries return, so **a `.corrupt-*` file beside the ledger keeps the player unrecoverable (`QUARANTINED`)**; the admin resolves it by hand by moving that file out of `storage/escrow/` (admin tooling is row T) |
| 5 | A newer-version ledger | Untouched, never appended to, `MTR-0101`, `NEWER_VERSION`. Not quarantined: it is valid for the newer build |
| 6 | A read error (permissions, disk) | `IO_ERROR` for that load only, `MTR-0101`; retried the next time the player is loaded or appended to, because it says nothing about the file |
| 7 | Compaction rule (§5.3 says "INTENT+DONE pairs older than `escrow.retention`") | `Compaction.plan`, pure: an INTENT is dropped, with its DONE, when its DONE is durable **and** its `atMillis` is at least the retention old (the DONE record carries no time, so the INTENT's decides). **An INTENT without a DONE is never dropped, at any age**: with no acknowledgment no snapshot is known to have replaced the one holding the entry, so G2 may not weaken; until row B writes DONE records nothing is droppable (70 bytes per consume). A DONE whose INTENT is gone is inert and dropped. A ledger compacted to nothing stays a valid header-only file (no delete) |
| 8 | When compaction runs | In the preload (so at join, off-thread, per §5.3) when `Compaction.plan` would drop something, and on request. Serialised with the player's appends by the same queue |
| 9 | Compaction atomicity | The kept records are written to `<uuid>.ledger.tmp` (forced when `fsync`), then `ATOMIC_MOVE` over the ledger (a plain replace where the file system has no atomic move, the pattern `ServerVariableStore` uses). A kill before the rename leaves the old file whole and a stray temp that is never read and the next compaction overwrites; a failed rewrite is logged and leaves memory and the file as they were |
| 10 | The in-memory image | A join-time cache, not a store: `release` on quit drops it after the queue drains (a rejoin's preload already queued behind it keeps it alive), and an image untouched for 5 minutes is dropped by the next preload, which also bounds a login that passed pre-login and never joined. Anything dropped is re-derived from the files, deterministically. Appends carry the fresh ledger in their `Durable`, so row P needs the image only at join |
| 11 | An append for a player nobody preloaded (a reload) | The queue loads the file first, then appends, so it is correct without a preload |
| 12 | The pre-login preload | `EscrowLedgerListener` at `MONITOR` priority, for a login still `ALLOWED`, waits at most `PRELOAD_TIMEOUT` (5 s) with `awaitPreload`. It never refuses the login. A timeout leaves `preloaded` empty (the gate stays closed) and reports `MTR-0101` ("did not finish loading"); the read continues and fills in later |
| 13 | Plugin disable | `onDisable` calls `close`, which drains every queued operation **on the disabling thread** (Paper cancels async tasks on disable, and an INTENT in flight must reach its file): the one place the IO half runs on the main thread, as `variables.close()` does; it waits for a drain already running (one batch of file IO per player). Work after `close` is refused (`SHUTDOWN`) |
| 14 | Diagnostics | `MTR-0101` (warning): corrupt, newer, an unresolved quarantine, an unreadable file, a preload timeout. `MTR-0102` (warning): a failed append. Both are reported at most once per player per code per minute (§7), through `diagnostics::publish`; they are assigned in `DiagnosticCodes` (the count is 100) and documented in plugin.md §5. `MTR-0100` and `MTR-0103` stay proposed (row P) |
| 15 | Settings | `escrow_fsync` (default `true`) and `escrow_retention_days` (default 30, minimum 1), flat keys like every other setting (§5.3 wrote `escrow.fsync`/`escrow.retention`), **read with fallbacks and not added to the written defaults**, so the settings golden and the editor's settings snapshot did not move. Documented in plugin.md §7.1 |
| 16 | Where the ledger directory is created | Lazily, by the first append (`storage/escrow/`). A preload of a player with no file creates nothing |

**Tests** (`plugin/src/test/.../escrow/`): `EscrowLedgerOrderingTest` (FIFO per player under an 8-thread pool and under
four submitting threads, one player's appends never overlap, two players write at the same instant, the caller's thread
does no IO and the work runs on the executor, a rejoin's preload waits for its own in-flight INTENT, release and the idle
sweep, the preload timeout, `close` draining on the caller); `EscrowLedgerFilesTest` (preload reads without mutating, the
torn tail cut at four places, a torn header, a file changed behind the writer, corruption quarantined and reported once,
the quarantine surviving a restart until the admin moves it, a newer version untouched, an unreadable file retried,
compaction by retention and by DONE, nothing-to-do, compaction to nothing, a kill before the rename leaves the old file whole
and a restart reads it, a stray partial temp, `fsync` counted on and off, a failed append refused and reported once per minute
and healed, a failed force cut back off, a torn tail added behind the writer's back); `CompactionTest` (the pure rule);
`LedgerStateRecoveryTest` (the recovery input); `EscrowLedgerListenerTest`; `EscrowIoBansTest` (the three rules over the real
packages, and each proven against a planted violation).

**Not done here, by design.** The click paths and the one tier-1 API (row P); the recovery gate and the join-time
`recover` call (row P); the barrier's `DONE` and read-back (row B); a kill-boundary IT (row H); the persistence-table row in
plugin.md §7 and the glossary (row Q). The ban rule is recorded here and in `EscrowIoBansTest`; adding it to build.md's
R-series table is left to row Q with the rest of the close-out.

### Row S as built (#308)

Schema only: **nothing at runtime reads `durable` yet** (row P, #310, does), so a `required` layer is not crash-safe and the
build says so wherever the value appears.

| Question | As built |
|---|---|
| Schema source | `SlotsComponent.describe()` declares `durable`: `enum`, optional, default `best_effort`, allowed `best_effort` / `required`, section `behavior`, order 35 (right after `input`); component version `1.2.0`; the editor demo snapshot golden moved. The option descriptions carry the "not available in this build yet" wording, so the studio shows it from the schema (D5), not from a copy |
| Parse | `SlotsComponent.Layer.durable()` (`SlotsComponent.Durability`, `BEST_EFFORT` when absent). Any other value, or a non-string, skips the layer with `MTR-0052` rather than reading it as `best_effort`: guessing would quietly weaken what the author asked for |
| Non-input layer | Meaningful only with `input: true`. On a layer that is not an input, `durable: required` loads and is ignored with `MTR-0076` (the rule `accept:` already follows); `durable: best_effort` there is the default and says nothing. Not skipped, so no authored layer is lost |
| Input layer, `required` | Loads and behaves exactly as `best_effort`. `gui.validate` returns an `MTR-0076` warning on the layer saying crash-safe slots are not available in this build and the items are not crash-safe. Row P replaces that warning with the real capability check (`MTR-0100`, §5.9) when it makes `required` do something. `MTR-0100` is **not** emitted by row S: it stays proposed and unassigned |
| Round trip | Layers are kept as authored maps, so a load and untouched save is byte-identical, an absent `durable` stays absent (the default is implicit, never written) and an unknown field survives. Fixture `fixtures/gui/durable-slots.yml` joins the byte-for-byte round-trip corpus |
| Studio | The inspector's Durability segmented control (Behavior section, with the other slots fields) is rendered from the registry like every field. It shows the chosen option's description under the control (a generic addition to every segmented schema field). Nothing is written until the author clicks an option; clicking `Best effort` writes `durable: best_effort` explicitly |
| Tests | `GuiInputSlotsTest` (default, both values, rejects a bad value, the warnings, the description, the fixture); `webui/test/gui-model.spec.ts` and `gui-save.spec.ts` (description, round trip, save writes only what was chosen); `gui-studio.pw.ts` (the control, its help text, the saved file); QA `guidurability.*` (not blessed) |

### Row P as built (#310)

The plugin runtime: a `durable: required` layer takes the escrow path. Tier-2 exits (#248's consume) and the tier-3 barrier
(row B) are not here, and the kill-boundary IT is row H.

**Where.**

| Package | What |
|---|---|
| `escrow.runtime` (owner side, no IO, no `Future#get`) | `Tier1<T>` (**the one tier-1 API**, generic over the stack so it is tested without a server), `Stacks`, `Live`, `BagInventory`, `Delta`, `ClickPlanner`, `PlayerEscrow`, `EscrowCapability`, `EscrowMessages`; the Bukkit side `ItemStacks`, `BukkitLive`, `EscrowRuntime` (capability, one `PlayerEscrow` per online player, the gate, death, claim, refusal copy), `EscrowPlayerListener` |
| `escrow.io` | `DirectoryProbe` (the §5.8 create-and-delete check; file IO, so it lives here and runs on the IO executor) |
| `gui.input` | `StrictInputs` (the GUI side's one door to `Tier1`), `ManualClicks` (single-slot and drag), `SlotMoves` (shift in and out, collect; carved out of `InputSlotClicks` for the 400-line guard) |

**The one tier-1 API.** Every method takes both sides, a `PlayerEscrow` (account, gate, the writer into the player's persistent data) and a `Live` (window slots, cursor, inventory), and does *plan on the account alone, write the persistent value, change the live
stacks* in one owner-thread call; a refusal changes neither side. `apply(Delta)` is every manual click (the click planner or the move planners build the `Delta`: the final window slots, inventory slots and cursor), `release` is a close, `take`/`edit` are `gui.slot_item`, `claim`, `death`, `recover` (join), and `beginCarry`/`placeCarried`/`restoreCarried` are a re-open. It composes the row M model steps (`place`, `pickUp`, `editStack`, `moveOut`, `close`, `claim`, `death`, `adoptLegacy`) and the account is encoded eagerly by the last of them. A strict click can therefore take several model steps (and several `gen`s): `gen` is an ordering, not a count of clicks.

**What every strict action does** (the §5.5 table, as built):

| Action | Built as |
|---|---|
| Single-slot click (place, pickup, swap, number key, offhand swap) | `GuiListener` answers `Verdict.MANUAL`: the event is cancelled, the gui's `gui.click` rules see it, then `ManualClicks` applies `ClickPlanner`'s outcome (vanilla's rules per `InventoryAction`) as one `Delta`. The server never applies it. The `accept:` and `gui.slot_change` checks ran first on the server's own prediction (`SlotPolicy`), as for `best_effort` |
| Drag | cancelled, `ManualClicks.drag` applies `getNewItems()` (window and inventory slots) and the cursor left, in one step |
| Shift in/out, double-click collect | `SlotMoves`: the existing planners, now building a `Delta` that goes through `StrictInputs.apply` for every layer (for a window with no required slot the writes are the plain ones, as before) |
| Pickup | the stack lands on the cursor and the entry leaves the account in the same step (G5: nothing guards the cursor) |
| Drop key, Ctrl-drop, clone, unknown | refused, as before |
| `gui.slot_item` | `take`: the inventory, or OWED; `set_amount`/`repair`/`enchant`: `Tier1.edit` on a copy of the stack. **`clear` and `set_amount 0` are refused** on a required layer ("needs the exit ledger"): a deletion is a tier-2 exit (#248), and doing it as tier 1 would let a crash resurrect it |
| Close, session end, replaced window | `StrictInputs.release` from `GuiWindowListener` and `BukkitWindowHost.releaseInputs`: every HELD entry to the inventory, OWED retried first, the rest OWED, the slots cleared. Entries *between windows* are skipped |
| Re-open, rewrite that changes the input slots | `beginCarry` lifts the old window's escrowed stacks out (they stay HELD, in the account, marked *carrying*), `placeCarried` puts each back at the same slot when it is still escrowed, else returns it to the player (inventory, or OWED); the old window's close, which fires inside `openInventory`, therefore cannot return a carried stack a second time. A window that does not open gets its stacks back (`restoreCarried`) |
| Death | `PlayerDeathEvent` at `MONITOR` (so `getKeepInventory()` is final): `keepInventory` returns the HELD entries to the inventory (OWED on overflow), otherwise appends them to `getDrops()`; the window's escrowed slots are cleared in the same step, so the `closeContainer(DEATH)` after it returns nothing a second time. A respawn rewrites the live account into the respawned player's data |
| Quit, kick, server stop | the platform's `InventoryCloseEvent(DISCONNECT)` runs the close first (F4, F7); `PlayerQuitEvent` drops the live state. `onDisable` releases every online player's open window before the server saves (K20) |

**The gate and recovery (§5.7).** `EscrowPlayerListener` handles `PlayerJoinEvent` at `LOWEST`: a new closed `PlayerEscrow`, then `Tier1.recover` with the account decoded from `materione:gui_escrow`, the preloaded ledger (`EscrowLedgerStore#preloaded`, memory only) and the legacy journal. If the preload has not finished the gate stays closed, the player is told, and `store.preload(uuid)` (idempotent) is continued with `executeEntity` to run the recovery then: never a blocking wait. An unreadable account (`MTR-0101`, reported here) or ledger (already reported by the store) leaves the gate `UNRECOVERABLE` and touches nothing; the player's strict clicks are refused. Players already online at enable or reload are recovered the same way (`adoptOnline`). The recovery result is announced: a chat line for what returned, `MTR-0103` plus a line when stacks stay OWED.

**Capability and refusal (§5.8, §7).** `EscrowCapability` is the pure decision (platform known, `spigot.yml players.disable-saving` off, ledger started, `storage/escrow/` writable by `DirectoryProbe`); `EscrowRuntime.check` runs at enable and again on every reload (the plugin wires both), `publishCapability` emits `MTR-0100` once per change of the failed check or severity (error while a loaded GUI has a required layer, warning otherwise; a probe still running is not reported), and `/materione doctor` prints the capability and any player whose state is unreadable. When the capability fails, or the player's gate is not open, every click that touches a required slot is refused (`Verdict.DENY`, a `MoveOut`/`collect` skips the slot): the layer renders, accepts nothing, and the player sees one actionbar line from `EscrowMessages` (`gui.escrow.unavailable`, `.busy`, `.owed`; overridable by the settings keys `message_gui_escrow_*`, read with a fallback and never written, like row L's options); the reason is logged with its code at most once per player per minute (`MTR-0102` for an account over its cap, `MTR-0100` for the capability). `StrictInputs.NONE`, a host with no runtime, refuses required slots too: a required layer is never quietly `best_effort`. Row S's interim `MTR-0076` warning for `durable: required` on an input layer is gone (it stays for a non-input layer); the studio's description of `Required` now says what it does and that it is not yet verified (`SlotsComponent` 1.3.0).

**Legacy migration (§10.2).** At join, when the gate can open, the `gui_inputs` journal of a crash-era leftover is decoded with Bukkit (`ItemStack.deserializeItemsFromBytes`), its stacks go through `adoptLegacy` as OWED entries of origin `legacy:gui_inputs` in the same recovery step and are claimed at once; the old key is removed. If the account is at its cap the key is left alone and the old `GuiJournal.restore` still runs; if the gate cannot open, `restore` runs as before. The golden `fixtures/escrow/account-v1-legacy-adopted.hex` fixes what the adoption writes; the raw Bukkit bytes need a server to produce, so their decode is exercised by the live server, not a unit test.

**Decisions and ambiguities, as resolved.**

| # | Question | Resolution |
|---|---|---|
| 1 | Which slots take the escrow path in a window with both kinds | Per slot: `GuiMenu.durableSlots()` (a subset of `inputSlots()`, set when the window is shown or rewritten from `InputSlots.Input#durable`). The journal's `held`, `InputReturns.take` and `release` use `journaledSlots()` (the rest), so the two paths never touch each other's slots |
| 2 | Slot to entry | An entry stands for the stack in window slot *s* when it is HELD or EXITING with `slotHint == s`; nothing else is stored. The slot a window shows is what the player sees, so it is the truth the account is brought back to when they disagree (another plugin changed a stack under us): a stack with no entry is adopted, an entry for an empty slot dropped before a click; a release returns what the slot holds *and* what the account owes. A stack the account cannot hold (a cap hit on a desync) is returned the old way (`Done.strays`), never lost |
| 3 | Strict GUI opens "refused until OPEN" (§5.7) | Not refused: the window opens and its required slots accept nothing while the gate is not open, which is a refused open as far as items go and avoids a second refusal path in the opener |
| 4 | `best_effort` labelling in the GUI's validation result (§5.8.3) | Done in `/materione doctor` only; the content module cannot know the platform, and the load-time `MTR-0076` warning would be wrong on a supported server |
| 5 | The cursor across a re-open | Unchanged: carried by the window host as before, never escrowed (G5) |
| 6 | Permission of `/materione claim` | `materione.claim`, **default true** (it returns the player's own items; the other nodes default to op). Players only, run through the entity scheduler |
| 7 | `death` for a `best_effort` window | Unchanged (W4 stays for that path) |

**Tests.** `ClickPlannerTest` (every action), `Tier1ClicksTest` (each click type on a required slot, refusals, a frozen slot, a desync), `Tier1LifecycleTest` (close and overflow, claim, take, edit, carry, death with both `keepInventory` values and a ledger decision, recovery, the gate, idempotence, the legacy adoption, an unreadable account or ledger), `Tier1CrashPropertyTest` (150 seeds x 70 steps of random strict clicks, closes, claims and deaths, with a simulated kill after every step recovered by the real codec and recovery), `EscrowCapabilityTest`, `EscrowPerfTest` (27-stack encode under 200 microseconds; a strict click on a 27-entry account under 1 ms; a nine-slot shift under 2 ms, medians), `EscrowIoBansTest` still holds the runtime (`java.nio.file` moved to `DirectoryProbe`), `EscrowGoldenFixturesTest.accountLegacyAdopted`, `GuiInputSlotsTest` (the compiled input carries `durable`). QA entries `guiescrow.*` (not blessed): `pickup-lifts-to-cursor` (auto and visual), `owed-claim`, `death-release`.

**Left for rows B, H and Q (H is built, see *Row H as built*).** The tier-2 consume exit and the `clear` refusal (X/#248); the barrier (B); the kill-boundary IT and its swap of the probe's stand-in account for this runtime (H); the QA entries that need the `probe.mjs` restart verb (`guiescrow.refused-unsupported`, `guiescrow.recovery-message`, the legacy migration) and the persistence-table row (Q). `StrictInputs` and `Tier1` are the hooks: tier 2 adds `Exits` calls beside `take`, and row B adds the one `saveData()` call site.

### Row H as built (#313)

The kill-boundary integration test: K1 to K22 against a real `kill -9` of a real Paper 1.21.4 server running the product's own
runtime. **K23 is not built: it needs row B (#311)**, see *Left for later* below. Run commands, the run table and the harness
mechanics are in `harness/README.md`; this section records what was decided and what was found.

**What runs.** `PaperEscrowCrashIT` (`EscrowCrashRunner`, `EscrowFiles`, `CrashRig`, the `durabilityProbe` plugin). The server
carries the product and the `guilogic` pack, so the player opens the real `guilogic:escrow` GUI (a one-row chest, one
`durable: required` layer). The player is row R's fake-connection player, but it now **logs in through the product's pre-login
preload** (`dprobe login` fires `AsyncPlayerPreLoginEvent` by hand from a plain thread, so `EscrowLedgerListener` preloads the
ledger exactly as for a real login, and retries the login as a client would if a loaded host exceeds the 5 s preload wait) and the
**real join handler** recovers; it plays with real `ServerboundContainerClickPacket`s. The probe's persistent-data account stand-in
(`movein`, `moveout`, `account`/`gen` keys) and `dprobe gate` are **gone**: they are replaced by the product's account and
`CrashGate`.

**The production crash gate (`dev.materione.plugin.escrow.gate.CrashGate`).** Named points inside the escrow code; only under
`-Dmaterione.escrow.gate=<value>` the thread that reaches the point named by the property's *current* value logs
`MATERIONE-ESCROW-GATE <name>` and parks forever (the harness sees the line and `destroyForcibly`s the process tree).

| Gate | Where | Reached by |
|---|---|---|
| `account-written` | `PlayerEscrow#commit`, after the account is written into the player's data, before the live stacks change | every tier-1 step (K1 inside a step) |
| `click-applied` | end of `Tier1#apply` for a click that touched an escrowed slot | K1 after the step, K5, K21b |
| `release-done` | end of `Tier1#release` | a close, a quit (K8a), a plugin disable (K20a) |
| `recovery-before-commit`, `recovery-done` | `Tier1#recover`, before the account is committed, and after the gate opened | the join (K15) |
| `death-released` | `EscrowRuntime#death`, after the drops were appended | `PlayerDeathEvent` (K9a, K9b) |
| `claim-done` | end of `EscrowRuntime#claim`, whatever the claim did | `/materione claim`: the **quiescent kill** that follows a completed save without touching any state |

*Inert in production, by construction:* the property is read once into a `static final boolean` at class initialisation, so without
it `CrashGate.at` is one load of a constant `false` the JIT folds away; nothing else (no command, no config, no editor) reads or
sets it; with it set, the value is re-read at every reach so the harness can re-arm a gate from inside the JVM between two
steps of one boot (a JVM argument cannot change); a value that names no gate (`off`) arms nothing. `CrashGateTest` (inert without
the property, parks only at the armed name, re-armed live, names distinct) and `CrashGateBansTest` (ArchUnit: `CrashGate` is the
only class of the escrow code that parks or sleeps a thread, proven against a planted parker) hold it. The call sites are in
`PlayerEscrow`, `Tier1` and `EscrowRuntime` (one line each, plus `claim` split into `claim` and `claimNow`); the gate lives in its
own package because `escrow.runtime` may not block (I4). Row B adds its own gate name beside `saveData()`.

**What is real and what is crafted.** Everything of tier 1 (K1 to K9, K15, K16, K19 to K22) is the product end to end, killed at a
production gate or at the quiescent `claim-done` gate. Tier 2 has no caller before #248, so for **K10 to K14 and K17** the harness crafts the
two things the missing caller would have left: an EXITING entry (the real HELD entry of a real click, its state flipped by the
harness's own account codec and committed through the player's live `PlayerEscrow` by the probe's `dprobe adopt`, which uses the
product's `AccountCodec`, followed by a real `Player#saveData`), and the ledger records (`EscrowFiles` writes INTENT records
between the kill and the boot: a second, independent implementation of the §5.3 layout, so the doc's layout and the plugin's are
checked against each other). The preload, the gate, recovery, the quarantine and the refusal that act on them are the product's.
**K3 and K4** are file states (a stray half-written temp file; `.dat` removed so `.dat_old` is loaded): the gate cannot sit inside
`PlayerDataStorage.save`, and no JVM-level hook was added.

**The rows.** Every row asserts against the player's `.dat` read by the harness itself and against the rebooted player: **G1**
conservation (every stack exactly once, inventory or owed), **G2** no resurrection, **G4** no dropped item entity, **I6** idempotent
recovery.

| Row | Test | Kill | What it shows |
|---|---|---|---|
| K1 | `coreRows` (inside the step), `tier1Rows` (after the step) | `account-written`; `click-applied` | disk is S (stack in the inventory, no account); the boot keeps it |
| K2 | `coreRows` | `claim-done` after a save | S' has the stack HELD in the account and not in the inventory; the boot returns it once |
| K3 | `cursorAndFileStateRows` | file state | a stray partial temp file is never read |
| K4 | `cursorAndFileStateRows` | file state | `.dat_old` is the previous complete snapshot and the boot loads it |
| K5 | `coreRows` | `click-applied` of a move-out | the older snapshot still holds the stack in the account only; returned once |
| K6 | `tier1Rows` | after a save | stack in the inventory, none owed |
| K7 | `tier1Rows` | after a save | OWED on disk with a full inventory, `MTR-0103`, no dropped item before or after, `/materione claim` returns it |
| K8a/b/c | `tier1Rows` | `release-done` inside a quit; a completed quit; a graceful stop with a window open | each leaves the stack exactly once |
| K9a to d | `deathAndDisableRows` | `death-released` (both `keepInventory` values); completed deaths | an unsaved death is rolled back; a completed death drops (off) or keeps (on) once, nothing is owed or duplicated |
| K10 | `ledgerRows` | quiescent + torn INTENT | no decision, E returned; the torn tail is left in place (a read never writes) |
| K11/K12 | `coreRows` | quiescent + INTENT | E EXITING on disk and a durable INTENT: dropped (G2) |
| K11 rolled back | `ledgerRows` | quiescent | INTENT for an entry the snapshot never held: the stack stays in the inventory (G3) |
| K13 | `ledgerRows` | quiescent + INTENT | snapshot already without E: the record is inert |
| K14a/b | `ledgerRows` | a real quit with E EXITING in the live account | the close does not return a frozen entry (no second copy); INTENT after the save: dropped; none: returned |
| K15, K16 | `ledgerRows` | `recovery-before-commit`, `recovery-done`, then a saved recovery | recovery changes only memory (the disk is byte-identical after each kill); a drained account returns nothing on the next crash |
| K17 | `ledgerRows` | quiescent + valid INTENT + torn tail | the INTENT counts, the tail is ignored, no quarantine, no `MTR-0101` |
| K18 | `coreRows` | quiescent | a ledger corrupt before its end (renamed `.corrupt-<ms>`), an account with a bad CRC and one of a newer version: `MTR-0101`, the strict click is refused, nothing returned, and the account value on disk is byte-identical after a save |
| K19 | `k19AnAutosaveNeverSeesHalfAClick` | none | Paper autosave on every tick, a click every 700 ms: every snapshot read is the state before or after a click, never both stacks or neither |
| K20a/b | `deathAndDisableRows` | `release-done` inside `onDisable`; a completed disable then a save | released before the save; once |
| K21a/b | `cursorAndFileStateRows` | after a save; `click-applied` | the stack on the cursor at a save is in no save and is lost (G5, vanilla); unsaved, it is returned (rollback) |
| K22a/b | `cursorAndFileStateRows` | after a save | a stack lifted onto the cursor is lost (G5); a placement is atomic with the cursor reduction and returned once |

**Which runs are default.** Only `coreRows` (K1 inside the step, K2, K5, K11/K12, K18: six boots) joins the default
`:harness:integrationTest` run; every boot is 25 to 50 s on the development machine, so the five opt-in groups
(`-Pharness.inputDurability=true`: 7, 7, 10, 7 and 2 boots) stay out of it. A boot that follows a kill is the check of its row and the
start of the next, so a group of N rows costs N+1 boots. The workspace is a fresh directory per group and the harness retries a
boot whose game port another process holds (observed when two harness runs share ports), instead of waiting out the boot timeout.

**Left for later.**

* **K23 (the barrier)** needs row B (#311): `saveData()`, the read-back, `DONE`. `PaperEscrowCrashIT#k23TheBarrier` is `@Disabled` with
  that reason. When B lands: add a gate name to `CrashGate.Gates` between `saveData()` and the read-back start, a row that kills there
  (and one where the read-back finds an older `gen`), and assert that an effect-carrying exit's effect never ran and that recovery
  applies the `INTENT` as K11/K12. The K10 to K14 and K17 crafting should then be replaced by the real tier-2 exit.
* **Folia (#314).** Not run. The product runs on Folia but the harness player there is a real protocol client (`idle-bot.mjs`), which
  cannot click, and `dprobe` acts from the console's global thread, which Folia refuses for player state. The subset that matters
  there (K1 inside a step, K8 as the quit sequence because a Folia stop has no full save (F15), K9, K15) needs a click-capable
  protocol client and entity-scheduler routing of the probe's per-player calls, as `foliaProbe` shows.
* **Truncation by the next writer (K17's second half)** is not exercised: no code appends to a ledger before #248, so the test shows
  that a read leaves the torn tail alone, and `EscrowLedgerFilesTest` shows that the writer cuts it.

## 13. Decisions this design takes

Each is reversible by the owner.

1. **The anchor is the player's own persistent data**, because it is saved in the same compound as the inventory (F3); a plugin file alone cannot be atomic with it.
2. **A ledger file exists only for exit decisions**, not for transfers: it is written rarely, off-thread, and never on the hot path.
3. **Every strict click that touches an input slot is manual** — the server never applies it — so no deferred mirror exists.
4. **The cursor is outside the guarantee** (owner-accepted): pickup lifts to the cursor as in vanilla and the account loses the stack in the same step; no redirect, no lien mode (G5).
5. **Nothing is dropped into the world**; overflow is OWED and claimed.
6. **Fail closed**: an unreadable account/ledger, an encode failure and a ledger write failure refuse transfers; none degrades silently.
7. **A platform that cannot meet G1–G4 ⇒ explicit refusal of `required` layers**, never an unmarked best-effort. Folia is a supported target, proved by P1, not a refusal platform.
8. **No new thread class**; the IO half uses the existing IO executor.
9. **One narrow D8 exception** (owner-approved, §14 Q4): the tier-3 barrier may call `Player.saveData()` on the owner thread, rate-limited per player and only for effect-carrying exits, because the platform has no asynchronous save. Recorded as an amendment note on D8 in architecture.md; effective when row B ships.
10. **Death releases at `PlayerDeathEvent`** (owner-decided, Q10), before the drop list is dropped.
11. **`durable` is a per-layer setting**, default `best_effort`; no global mode.

## 14. Owner answers (PR #303) and what is still open

The owner answered the eleven questions of the first draft on PR #303. Each answer is folded into the section named.

| # | Question | Owner answer | Folded into |
|---|---|---|---|
| 1 | Lift or narrow the block on #247/#248? | First answer: **no, wait for a platform acknowledgment** (superseded by 1b). | Status, §2 W7 |
| 1b | Does the tier-3 barrier count as the platform acknowledgment? (follow-up) | **Yes. The block on #247 and #248 is lifted.** #248 now waits on rows M, L, P, B and H, not on an outside ack. | Status, §2 W7, §5.4 (step 4, K23), §6, §10, rows B and X; logic.md §25 decision 12 |
| 2 | Per-layer or global durability setting? | **Per-layer `durable: required \| best_effort`.** | §5.8, §10, row S, §13.11 |
| 3 | Cursor handling | **Accept the vanilla cursor exposure**: no pickup-into-inventory redirect, no lien mode; document it honestly as a non-guarantee. | §1 (non-goals, G1, G5), I3, §5.5, K21/K22, §13.4 |
| 4 | Tier-3 barrier | **Yes**, with the D8 exception recorded explicitly. | §5.4, §5.6, §13.9, row B, architecture.md D8 note |
| 5 | Pickup UX and refusal copy | **Acceptable**, reconciled with 3: pickup no longer redirects, so the "inventory full" line is gone. | §5.5, §7 |
| 6 | Retention and backups | **30 days; `storage/` backups cover the ledger.** | §5.3, row Q |
| 7 | Caps | **256 KiB account, 54 entries.** | §5.3, §5.4 |
| 8 | Diagnostic codes | **Start at `MTR-0100`**: `MTR-0100` to `MTR-0103`, still proposed and unassigned (read as: the block starts at 0100, one earlier than the draft). | §5.9, plugin.md §5, gui.md §12.4 |
| 9 | Folia | **Must be supported for all layers.** The capability design is "probe and support Folia", not "refuse until probed". | F12, §5.8, §11 P1, row R2, §13.7 |
| 9b | May a Folia jar be fetched for P1? (follow-up) | **Yes, approved.** The owner notes the whole QA suite may need to run on Folia too. | F12, §11 P1, rows R2 and F |
| 10 | Death | **Release at `PlayerDeathEvent`** (into the inventory or the drops per the event, before the drops are fixed). | §5.5, K9, §13.10 |
| 11 | Admin tooling | **Follow-up**, out of scope. | §5.9 (`MTR-0101`), row T |

**Still open** (none of which this document can close):

1. ~~The Folia jar has not been fetched.~~ **Done (row R, #306):** both Folia jars are pinned and P1 ran (§11); F12 holds on Folia 1.21.4.
2. **The size of the Folia work in the QA suite** is partly known: Folia has builds for both pins (§11), and row F's gap list (§12) records what a
   first run of the Paper integration tests on Folia found. The QA suite itself has not run there (row F, #314).
3. ~~The probes P2 to P4 of §11 are not run.~~ **Done (row R, #306):** all verified (§11). P5 to P7 remain, by design.
