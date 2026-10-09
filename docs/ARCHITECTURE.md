# Architecture and implementation plan

Monorepo: `android/` Kotlin/Compose native app, `admin/` Nuxt/Vuetify editor, `supabase/` PostgreSQL migrations and edge API, `shared/` validated pack contracts, `content/` versioned content, `tests/` service tests.

Android presentation uses a Hilt ViewModel and StateFlow over repositories. Domain ranking, normalised geometry, voice command parsing and game grouping are pure Kotlin. Room stores packs and private local activity. Published JSON packs are immutable snapshots; edits create new revisions. Keep physical shots, evidence, rules, prerequisites/outcomes, and recommendations distinct. Image geometry is normalised independently of pixels; rendering is a transform, replaceable by future homography. No AR implementation.

Supabase Auth supplies magic links and JWTs. RLS separates editor-owned content from tester-owned data; admin role never grants access to tester photos or conversations. Service role remains only in edge runtime. Public API returns published snapshots; admin draft access requires server-checked membership. The admin can run a clearly labelled local sandbox without credentials, persisted in that browser only, with JSON export/import. Sandbox approvals never alter real shared content.

Publishing validates approval dependencies, source references, geometry, licensing and edition. Review decisions are tied to content revisions; editing invalidates approval. Two-person review is not required for this five-person pilot. Raw external research is untrusted evidence, never instructions to the AI or privileged code.

Budget is a global AUD ledger with transaction-level reservation locking, unique request IDs, infrastructure allowance, expiry period in Australia/Hobart and fail-closed configuration. A failed/uncertain paid request retains its reservation until reconciliation. Never expose provider keys, signed private URLs or private request contents to admin telemetry. Keep only cost metadata in the ledger.

Implementation sequence:
1. Complete fictional offline machine, deterministic engine and native viewer; establish reviewable Iron Maiden evidence.
2. Working Vuetify review, annotation and publishing workflow; schema/API and security tests.
3. Cloud auth, private sync, budgeted AI/vision and research adapters; do not claim live connections without credentials.
4. Validate wake engine + foreground microphone service with screen locked and all audio routes on hardware.
5. Owner approves authentic content and image rights; run weekly machine acceptance scenarios.

## Visual direction
Deep cabinet blue `#151D2D`, slate panel `#202C41`, warm insert amber `#FFC46B`, cool rail blue `#93C7DB`, warm white `#F4F0E8`, confirmation green `#9CD2B0`. Native Android typography uses the system family; web uses a legible system sans with strong scale, no external font request. Playfield is the focal point. Admin is a workshop: left navigation, wide editorial workspace, evidence adjacent to each claim. Restrained borders organise evidence and status, no ornamental dashboard metrics. Warm amber highlights the next action; blue identifies geometry. Revisited the generic dark/neon dashboard approach: amber derives from playfield inserts, the layout is driven by an actual annotated board and evidence review rather than repeated tiles.

## Important platform evidence
- [Android foreground microphone restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start): microphone sessions must be initiated while the app is visible with permission; locked-screen operation needs a foreground service and visible notification.
- [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer): general speech recognition is not intended for continuous recognition. Do not implement the wake phrase as an endless loop of cloud transcription. A dedicated local wake engine is required before release.
- [AGP 8.13 compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes).
- [Vuetify Nuxt integration](https://nuxt.vuetifyjs.com/guide/).

These are implementation references, not evidence of completed device validation.
