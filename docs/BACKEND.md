# Backend setup and API contracts

Apply `supabase/migrations/` in filename order. `supabase/seed.sql` inserts the catalog, original fictional fixture and pending Iron Maiden evidence without overwriting existing rows. No real finding is approved by seed. Database schema covers machines/variants, playfields/shots, evidence, prerequisite/outcome graphs, strategies, versioned packs, research jobs, private histories and budget reservations. Room v1 schemas are exported under `android/app/schemas`.

## Membership and email

After an email account has been created, explicitly provision membership from a trusted SQL/admin environment:

```sql
insert into public.memberships(user_id, role)
values ('USER_UUID_FROM_AUTH_USERS', 'owner');
-- Other users receive 'tester'; content collaborators can receive 'editor'.
```

The application checks JWTs with Supabase Auth, then checks membership. `verify_jwt=false` in the edge configuration is intentional: the handler performs its own verified user lookup before any route. Do not remove that authentication. Email registration alone does not allow access to pilot data or paid services.

Allow exactly the deployed admin origin in `ADMIN_ORIGIN`; set matching Auth redirect URLs. Android uses PKCE email sign-in with `pinballpilot://auth`, stores tokens encrypted with Android Keystore, and rejects account switching within one installation to prevent mixing local private histories. A production public release should add verified HTTPS App Links and explicit account switching with isolated local databases.

## HTTP endpoints

All endpoints are under `/functions/v1/pilot`. App/admin endpoints use a Supabase bearer JWT. Research connector routes accept a scoped `pp_mcp_` bearer token; these credentials are denied on every other route. Research reads require an editor or owner, and proposal submission requires a connector token. Mutations use JSON. Responses use `Cache-Control: no-store`.

| Method | Route | Purpose |
|---|---|---|
| GET | `/catalogue` | Latest published pack per edition |
| GET | `/packs/{variantId}` | Immutable pack snapshot |
| POST | `/coach` | `{variantId, question, requestId: UUID}`; returns `{answer, kind, claimIds, shotIds, uncertainty}` |
| POST | `/identify` | `{requestId: UUID, photos: [JPEG/PNG/WebP data URL]}`; one or two photos; always returns `requiresConfirmation: true` |
| POST | `/sync` | Private game snapshots, append-only UUID events, learning and favourites; returns merged game/learning data |
| POST | `/activity` | Compatibility route for an individual private activity event |
| GET | `/admin/workspace` | Drafts, pending/reviewed evidence, research jobs and budget configuration |
| POST | `/admin/review` | `{id, revision, status: approved/rejected, reviewNote}`; optimistic revision check and audit event |
| POST | `/admin/draft` | `{pack}`; draft storage only |
| POST | `/admin/publish` | `{variantId, version}`; validates pack and atomically rechecks canonical approvals |
| POST | `/admin/research` | `{variantId, query}`; queues bounded web research, with findings stored pending review |
| POST | `/admin/budget` | Owner only: `{limitMicroAud}` |
| GET | `/research/machines` | Editorial catalog, including unpublished editions |
| GET | `/research/knowledge/{variantId}?offset=0` | Up to 100 claims, supporting sources and draft guide text; returns `nextOffset` |
| GET | `/research/proposals?variantId=...&offset=0` | Up to 50 proposals and review notes; machine filter optional |
| POST | `/research/proposals` | Strict `shared/research.ts` payload; idempotent pending-only submission |
| GET | `/admin/proposals?offset=0` | Paginated editorial proposal queue |
| POST | `/admin/proposals/review` | `{id, status: approved/rejected, note}`; atomic revision-checked application and audit |
| GET/POST | `/admin/connector-tokens` | List own token metadata / create `{label, days: 1..90}`; secret returned once |
| POST | `/admin/connector-tokens/revoke` | `{id}`; revoke own connector token |

See `shared/contracts.ts` for JSON pack validation and `handler.ts` for request schemas. Normalised coordinates are in `[0,1]`; polygons have at least three vertices. Rules refer to shot and evidence IDs; prerequisites refer to outcome IDs; strategies reference ordered rule IDs. Exact instructional text and its explanation must match a reviewed recommendation. Publishing rejects missing sources, mismatched editions, stale approvals, unlicensed images, missing guide types and unreachable prerequisite cycles. Publication transactions prevent a concurrent source/claim edit from bypassing review.

## Budget activation

MCP research runs in the user's assistant and does not invoke the paid API worker or reserve app AI spend. The connector can read editorial content and submit proposals only. See [MCP setup](MCP.md) for authentication and review flow. Apply migration `202610090005_research_connector.sql` before enabling the connector.

Amounts are integer millionths of AUD. A$60 = 60,000,000. Configure actual hosting allowance in `budget_config.infrastructure_micro_aud`; default 15,000,000 is a conservative placeholder, not a quote. The ledger period uses Australia/Hobart. A row lock serialises reservations; repeated request IDs cannot trigger another paid call. Reservations survive provider errors/timeouts and do not automatically expire, because provider work may already have incurred cost.

Before setting `online_enabled=true`, configure `AI_MODEL`, provider credentials, `COACH_MAX_MICRO_AUD`, `VISION_MAX_MICRO_AUD`, `RESEARCH_MAX_MICRO_AUD`, and `COST_BOUNDS_VERIFIED=true`. Verify bounds against current provider pricing, maximum request/input limits, output token limits, image charges, up to three research tool calls, taxes and conservative AUD conversion. Server-side `max_output_tokens` is bounded; research and normal calls have explicit timeouts. Provision provider billing restrictions as defence in depth and keep this project’s keys separate from other workloads.

No automatic cost refund/reconciliation is enabled. An owner may reconcile actual charges deliberately; do not lower a reservation without evidence from the provider invoice. Infrastructure/egress charges outside this API cannot be stopped by this ledger. Keep hosting plans and provider account settings within the total monthly envelope before release.

## Privacy

Private writes use the caller’s Supabase client/JWT with owner RLS. The service role is used for publishing, research, auth checking and budget reservation, not browsing private histories. Workshop routes return no player photos, questions or advice. The cost ledger contains no player content or user identifiers. Private storage paths begin with the authenticated user UUID. Raw provider errors and request bodies are not logged or returned to clients. Provider calls use `store:false`; this is not a claim of zero provider retention under every account policy.

## Verification and deployment

Deploy only after setting project configuration and reviewing migrations. Test with two tester accounts and an editor on staging, verify deep-link email delivery, upload/download a private image, run one budgeted request, and compare the ledger against provider usage. The disposable PostgreSQL suite verifies core RLS and transactions, but actual Supabase Auth/Storage/provider integration requires a configured project.

Official API references used: [image inputs](https://developers.openai.com/api/docs/guides/images-vision), [web search](https://developers.openai.com/api/docs/guides/tools-web-search), [Responses API](https://developers.openai.com/api/reference/resources/responses).
