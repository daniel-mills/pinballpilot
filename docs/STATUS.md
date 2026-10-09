# Implementation status

This file distinguishes implemented paths from deployment and hardware evidence. The project is a development milestone, not an approved tester release.

## Implemented locally

- Version 2 knowledge foundation: immutable claim/source revisions, pinned supporting/contradicting citations, edition-specific software releases, reviewed applicability and exact state behaviour, permanent review history, and compatible schema 1 reads. See `DATA-MODEL.md` for the model and migration sequence.
- Matching TypeScript/Kotlin state engines for counters, booleans, enums, timers, AND/OR conditions, repeatable actions and scoped resets. Android pins each new game's guide version, archives downloaded packs, offers progress confirmation/undo and ball-end reporting, and migrates Room without deleting existing history.
- Brief between-ball display scan: two transient camera snapshots, authenticated/budgeted online recognition against a pinned guide, individual selection/correction of score, ball and supported progress, stale-scan rejection, atomic confirmation and score/progress undo. Private activity preserves original and confirmed readings. Images are not saved to account storage by this scan flow.

- Native Kotlin/Compose app with Hilt, StateFlow, Room, CameraX, dark UI, original static playfield, pinch zoom/pan, point/polygon hit testing and text-accessible shot lists.
- Deterministic prerequisite-aware simple/advanced scoring and multiball guides, progress recording, fresh-game correction, long-term learning, score entry, local favourites and automatic offline device speech.
- Foreground hands-free service with local Vosk wake recognition, intentional speech recognition, quiet default, optional prompts, stop notification, audio-route configuration and offline fallback. Bundled model setup is checksum-pinned.
- Email-link PKCE client, encrypted token storage, published-pack download/cache, server-side coaching and photo adapters, private snapshot/activity sync. These paths require live integration testing after configuration.
- Nuxt 4, Vue 3, Vuetify 3, TypeScript workshop: browser-local sandbox, source-linked individual review, separate real/demo drafts, reference upload and permission record, point/polygon editor, structured rules/guide JSON editor, snapshot publication and adjustable owner budget.
- Supabase migrations, seed, owner RLS, review auditing, source-change approval invalidation, immutable publication transaction, private image storage and atomic AUD spending reservations.
- On-demand research queue/worker with source-bound findings, manufacturer priority, tool/output/time bounds and mandatory pending status. Provider costs and credentials are intentionally unconfigured.
- Local stdio MCP connector for Codex/Claude: machine/evidence reads, proposal history and individually sourced submissions. Scoped expiring/revocable tokens, idempotent submissions, canonical revision checks, admin before/after review, audited human approval, and explicit evidence refresh into guide drafts. No provider API key is needed for the connector.

## Content gate

The Workshop table is fictional and complete enough to exercise the product flow. Iron Maiden Pro has seven sourced pending findings and an empty authentic playfield/guide draft. Actual geometry, playable guide sequences and scoring/risk assessments must be authored and approved from evidence and machine access. Never replace this gate with invented real-machine rules or auto-approval.

## Pilot limitations to resolve during integration

- The rules editor currently uses validated JSON for complex rules/strategies. A richer form-based prerequisite/step editor and a general new-machine catalog workflow remain to be implemented.
- Game grouping uses inactivity, machine changes and explicit fresh-game correction. It does not yet provide arbitrary historical event splitting/merging. Photos remain unconfirmed evidence and are not automatically assigned to the currently open game.
- Sync is user-triggered and capped to the most recent 500 game/event rows per request. Learning/favourites merge by union; cross-device favourite removal and robust conflict UI need a follow-up. Conversation events are uploaded privately, but the mobile history screen currently emphasises games, scores and learning rather than a full photo/chat gallery.
- The app allows one account per installation to prevent local history mixing. A production account switcher must use isolated per-account local storage.
- General machine photos offer candidate confirmation. The separate display scan maps visible readings to known state fields only after player confirmation; hidden state and timers remain unknown. Recognition accuracy, reflections/animations, camera lifecycle and the under-30-second interaction target need testing with real approved Iron Maiden content. A connected backend/provider is required; offline OCR is not implemented.
- The single bundled English wake model, generic phone speech provider and route handling are implemented but unvalidated on the reported Samsung A27, with the screen locked or in arcade noise. Hands-free remains an essential release gate.
- API tests use injected providers; no paid provider, email delivery or remote Supabase account has been exercised. Full staging integration is still required.
- The cost ledger refuses configured app requests beyond available budget. Actual provider prices/FX/tax bounds and hosting limits must be set and reconciled before claiming a guaranteed total A$60 spend.
- The dependency audit on 9 October 2026 reports 11 high-severity entries propagated from two transitive advisories: `braces` (nested-pattern stack exhaustion) and `node-forge` (RSA verification). Their installed registry versions have no patched successor available at verification time. Git-related critical advisories were resolved with pinned overrides. Review reachability and patched releases before deployment; a successful build does not resolve these advisories.

## Verification

Verified locally on 9 October 2026:

- Android debug APK assembled; all 17 domain tests pass, including selected scan updates, stale-game rejection and unknown/invalid reading handling; Android lint passes with 23 warnings and no errors.
- All 57 TypeScript contract/API/MCP and migration tests pass, including an actual stdio client round trip, repeated state cycles, source pins, behaviour review, unknown-state handling and scan validation/budget/sync boundaries. Admin and backend TypeScript checking passes. Room migration checks compare actual migration SQL with both exported schemas and retained saved data.
- The production Nuxt/Vuetify build passes. All 6 Chromium browser tests pass, including the version 2 upgrade/state editor, review persistence, publication, saved shot edits, spending-cap validation, mobile layout and mocked cloud connector review.
- All eight SQL migrations pass against isolated PostgreSQL 17 with minimal Supabase interfaces, including an existing-data upgrade fixture. Suites cover immutable claim/source history, citation forgery, release applicability, pinned sessions, legacy sync, owner isolation, publication races, budgets and connector permissions.

Android compilation is not a substitute for device tests. The hardware procedure is in `HARDWARE-VALIDATION.md`. Cloud email, paid AI and real Supabase integration remain untested.
