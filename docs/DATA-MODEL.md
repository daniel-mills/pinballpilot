# Knowledge versions, evidence and game state

The editorial database keeps immutable knowledge history. Published packs are immutable delivery snapshots. Private games pin the pack version used when they start. Pack schema 1 remains supported; schema 2 adds explicit evidence pins, software applicability and a bounded state expression language.

## Editorial history

- `knowledge_sources` is a source's current metadata. Every change to title, URL, kind, locator, access date, notes or optional document hash creates a `source_revisions` snapshot.
- `claims` remains the current editorial record. Changes to content, claim kind, software applicability, supporting sources, source relations or reviewed rule behaviour create a `claim_revisions` snapshot and revoke current approval.
- `claim_revision_evidence` links a specific claim revision to a specific source revision, with its locator and `supports` / `contradicts` relationship. `current_claim_evidence` exposes only the current revision's citations. The legacy `source_ids` input still exists; its `claim_sources` projection is maintained by the database.
- `review_events` records decisions on particular revisions. Snapshots preserve the original content; review events determine subsequent decisions. History tables reject update/delete, including service-role writes.
- A source change creates a new revision of affected claims and returns them to review. Existing published packs retain their original evidence.
- `software_releases` stores edition-specific release identities, original vendor version labels and optional release dates. `claim_revision_releases` enforces edition consistency through foreign keys. Do not compare vendor labels lexically or assume semantic versioning.

The migration captures the existing current claim revision and its sources. It cannot reconstruct wording overwritten before this migration. It preserves existing approval and does not invent missing historical revisions. Source snapshots contain metadata, not archived PDF/video/page bytes. `content_hash`, when provided by research, is the SHA-256 of retrieved document bytes; it is not proof that the server independently fetched or verified those bytes.

## Applicability and review

Version 2 packs and their claims carry:

```json
{"status":"unknown","releaseIds":[],"settings":"Operator settings have not been verified."}
```

`releases` requires one or more registered release IDs; `not_applicable` is reserved for machines where software does not apply. Unknown is never automatically expanded to all releases. Settings are currently documented assumptions, not a formal operator-setting schema. The existing `software` description remains for compatibility and human context.

MCP proposals optionally include `applicability`, `ruleSpec`, source `relation`, and source `contentHash`. Old proposals remain accepted with unknown applicability and supporting citations. The connector returns releases, current citation pins and state definitions. Register a release in the admin portal before proposing release-specific applicability. Each proposal remains pending until individual human review; approval does not update a published guide.

For a stateful recommendation, `ruleSpec` must match the rule ID, shot ID, prerequisites, outcome, repeatable flag, conditions, effects and entire state-variable definition list in the draft. Publication also requires the previously established exact instruction/reason review. Changing a threshold, initial value, reset scope or shot therefore needs another review, even when wording is unchanged. The admin shows this behaviour in the proposal review and permanent claim history.

## Pack schema 2

The new fields are `engineVersion: 1`, `applicability`, `releases`, and `stateVariables`. Sources include `revision` and optional `contentHash`; claims include `citations` and applicability. Rules may add `repeatable`, `conditions` and `effects`.

Example state definition, for a fictional machine:

```json
{"id":"lock_credits","label":"Lock credits","type":"counter","scope":"game","initial":0}
```

Types are `boolean`, `counter`, `enum`, and `timer`. Scopes are `game`, `ball`, and `mode`; mode scope additionally requires `modeId`. Null initial values mean unknown. An enum declares permitted `values`. Counters are nonnegative safe integers. Timer state is an absolute expiry in milliseconds, with 0 meaning inactive and null meaning unknown.

Conditions combine `all` predicates with an optional `any` group. Supported operators are `eq`, `gte`, `lt`, `active`, and `expired`. Missing, null or inferred values do not satisfy a predicate. An inactive timer does not satisfy `expired`. Timer evaluation takes an explicit timestamp for deterministic tests.

Effects are `set`, `increment`, `reset`, and `startTimer`. The latter takes a duration in milliseconds. Unknown counts cannot be incremented until confirmed. Effects apply atomically to a copied state; invalid effects do not partially change the original. Repeatable rules remain eligible after their legacy outcome has been recorded, provided their conditions hold. Nonrepeatable rules and legacy prerequisites retain schema 1 semantics. Use typed state for repeatable qualification rather than a permanent prerequisite outcome.

The admin can upgrade a draft, register releases, edit state definitions and preview effects. Complex conditions/effects still use the existing structured pack editor. Previewing a rule does not approve it. Every version 2 rule that uses state must carry a matching reviewed `ruleSpec` before publication. The backend compares citation pins and applicability with database records inside the publication transaction.

## Private runtime state

State cells contain `value`, `origin` (`initial`, `player`, `inferred`) and `observedAt`. The Android and TypeScript engines share the same semantics and have tests for repeat cycles, unknown state, timer boundaries and reset scopes.

New games initialise state from their guide and retain `pack_version`. Android keeps a `pack_archive` alongside the latest installed packs. Downloading a new pack does not replace an active game's rule definitions. The API accepts `GET packs/{variant}?version=N` and coaching requests can pin the same version. Sync retrieves missing historical packs. Legacy sessions retain an unknown pin because their historical version cannot be proven.

Android supports player-confirmed state values, reporting ball end, and undoing the most recent local progress report. Ball end resets ball-scoped state and preserves game-scoped state. Mode-scoped reset is implemented in the domain engine; there is no automatic mode detection or generic mode-end UI. Authors can use explicit reset effects in reviewed rules. Missing progress does not imply guide completion.

Activity payloads retain before/after state, confirmation origin, prior completed outcomes and a superseded event ID for corrections. Activity is append-only; owner deletion of private history remains possible. Knowledge review never automatically promotes private observations into public facts.

### Brief display scans

Android offers **Scan between balls** on an open guide. CameraX takes two snapshots approximately 800 ms apart after the first capture, then sends them to authenticated `POST scan` with the game's pinned edition and pack version. This is a short capture sequence, not continuous video or ball tracking. Recognition uses the existing online vision provider and its two-image budget reservation. Manual progress remains available offline. Scan images are transient and are not uploaded to the app's photo storage; the provider adapter requests `store:false` (this is not a claim about the provider's separate retention policies).

The response contains `machineMismatch`, `note` and up to 12 typed `readings`. A reading has `target` (`score`, `ball`, `state`), nullable `variableId`, `value`, visible `evidence` and a model-estimated `confidence`. Progress IDs/types must match the pinned pack; timers, unknown fields, duplicate readings and invalid values are rejected. A visible machine mismatch yields no usable readings. The prompt requires omission of unreadable/conflicting values and distinguishes current score from high scores, bonus displays and thresholds. These instructions require real-machine validation; model confidence alone never confirms state.

The player confirms the machine/game and selects individual readings; all selections start unchecked and values can be corrected. Saving atomically updates only selected state fields and the current score, without ending the game, applying rule effects, adding completed outcomes or awarding learning. A displayed ball number is retained as an observation; it does not implicitly trigger ball-scope resets. **Ball ended** remains an explicit player action.

Confirmed events retain the captured time, pack version, original detected readings, selected/corrected readings, before/after state and before/after score. State cells have `origin:player` and the scan timestamp. Confirmation rejects a changed game, guide version, game update timestamp or a scan older than two minutes. Undo restores the preceding state and score, provided newer changes do not conflict. No database migration beyond the existing event-payload/state migrations is required.

Sync retains the existing latest-timestamp snapshot policy. It is not a conflict-free event-sourced merge: two devices actively changing the same game can produce competing snapshots. Events preserve uploaded reports, but full cross-device event replay/conflict resolution and arbitrary correction of older reports remain future work. Historical activity is not yet downloaded into the local undo stack.

## Migration and verification

Apply migrations 006–008 after 001–005, before deploying the new API/admin. These migrations are additive and backfill current knowledge. Android Room migration 1→2 adds state, pack pins, event payloads and the archive without deleting saved games. Existing pack JSON remains readable.

Run `npm test`, `npm run typecheck`, `npm run build`, `npm run test:browser`, `scripts/build-android.ps1`, and `scripts/verify-database.ps1` against a disposable PostgreSQL container. The database suite includes pre-migration data, immutable revisions, publication races, forged citation pins, edition applicability, legacy sync and owner privacy.

This change supplies the versioning/evidence/state foundation. Full hardware modelling, structured operator adjustments, scoring formulas, a unified relational guide authoring model, source-document storage and real-machine state recognition remain separate work. Iron Maiden findings still require review; no authentic rule or guide was auto-approved.
