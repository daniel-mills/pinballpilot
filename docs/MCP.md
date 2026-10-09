# Research with Codex or Claude Code

The connector lets your signed-in assistant read the shared machine library and propose sourced changes. The assistant performs research using its own available tools and subscription allowance. Pinball Pilot does not call an AI provider through MCP and does not receive your OpenAI or Anthropic credentials. Hosting/storage costs still apply, and assistant subscription limits are separate from the app's A$60 API ledger.

This is a local **stdio MCP server** connecting over HTTPS to the deployed Pinball Pilot backend. It works with Codex desktop/CLI/IDE and Claude Code. Claude Desktop can also launch the stdio configuration below. It is not a hosted HTTP connector for the Claude website or ChatGPT website.

## 1. Prepare the backend and admin

1. Configure Supabase and editor membership as described in `BACKEND.md`.
2. Apply all eight migrations in order, including the knowledge history and session-state migrations 006–008, and deploy the updated `pilot` edge function. Update the admin build too. The seed supplies the initial machine catalog; do not reset a live database.
3. Sign in to the connected admin as an editor or owner. Open **Settings → Research connectors**.
4. Create a named connector token for 7, 30 or 90 days. Copy it while displayed. Only its SHA-256 digest is stored in the database; the plaintext is not persisted by the admin browser.
5. Use a separate token for each assistant so you can revoke them independently. Tokens belong to their issuing editor and stop working if that membership is removed.

The browser-local sandbox cannot receive MCP submissions. A deployed backend (or the full local Supabase stack) is required; a token alone cannot connect the standalone sandbox.

## 2. Build and configure the local connector

Use Node 22.22.3 or later as permitted by the root package's engines field:

```powershell
npm ci
npm run mcp:build
Copy-Item mcp/.env.example .env.mcp.local
```

Edit `.env.mcp.local` with:

```dotenv
PILOT_API_URL=https://YOUR_PROJECT.supabase.co/functions/v1/pilot
PILOT_ANON_KEY=YOUR_PUBLIC_SUPABASE_ANON_KEY
PILOT_RESEARCH_TOKEN=YOUR_CONNECTOR_TOKEN_FROM_ADMIN_SETTINGS
```

Use the public Supabase key, never a service-role key. Keep this ignored file private. The connector accepts HTTPS, with HTTP allowed only for loopback development. Redirects are rejected to avoid forwarding credentials elsewhere. Do not store ChatGPT/Claude session tokens here.

## 3. Add to your assistant

For this workspace, the Codex configuration is:

```toml
[mcp_servers.pinball-pilot]
command = "node"
args = ["--env-file=D:/001-Repos/pinballpilot/.env.mcp.local", "D:/001-Repos/pinballpilot/mcp/dist/mcp/index.js"]
startup_timeout_sec = 20
tool_timeout_sec = 30
```

Add that block to your Codex MCP settings or `~/.codex/config.toml`, preserving existing settings. Set `command` to your Node executable's absolute path if it is not on PATH. The locally installed executable in this workspace is `D:/001-Repos/pinballpilot/.tools/node22/node-v22.22.3-win-x64/node.exe`.

For Claude Code, run:

```powershell
claude mcp add --transport stdio pinball-pilot -- node --env-file=D:/001-Repos/pinballpilot/.env.mcp.local D:/001-Repos/pinballpilot/mcp/dist/mcp/index.js
```

For a client that uses JSON MCP settings, including Claude Desktop:

```json
{
  "mcpServers": {
    "pinball-pilot": {
      "command": "node",
      "args": [
        "--env-file=D:/001-Repos/pinballpilot/.env.mcp.local",
        "D:/001-Repos/pinballpilot/mcp/dist/mcp/index.js"
      ]
    }
  }
}
```

Restart/reconnect the assistant's MCP client after adding it. Sign in to Codex or Claude through that product's normal subscription flow. Listing tools proves the local process starts; successfully calling `list_machines` also checks backend connectivity and token validity.

## 4. Research and review

Example request:

> Read our Iron Maiden Pro knowledge and previous proposals. Verify the Trooper Multiball route using manufacturer sources first. Identify contradictions, edition/software differences and missing steps. Submit individual sourced corrections and beginner recommendations for my review. Do not publish anything.

Available tools:

| Tool | Access |
|---|---|
| `list_machines` | Read up to 200 catalog editions, including unpublished machines |
| `get_machine_knowledge` | Read claims, source metadata and draft rules/strategies; follow `nextOffset` for more claims |
| `list_research_proposals` | Read proposed changes, outcomes and human review notes; paginated |
| `submit_research_proposal` | Add one pending fact/recommendation with citations; never approve or publish |

The `refine_machine_knowledge` prompt provides the same workflow with `variantId` and `focus` arguments. Research requires the host assistant's web tools or source documents; the connector itself does not fetch source URLs. Submitted source classifications and citations are unverified evidence until you review them.

Corrections require the existing claim ID and revision. New findings use null for both. Each submission includes a UUID `requestId`; retrying identical content with the same token and ID returns the existing proposal. Reusing an ID with different content fails. At most 200 pending proposals per token are accepted.

In **Research & review → Assistant proposals**, compare previous and proposed wording, check the citations, and enter a review note. Approval atomically updates the knowledge library and its audit history. If another edit has changed the claim, approval fails and the assistant must submit a fresh correction. Rejection leaves canonical facts unchanged.

In **Rules & guides**, select the machine and click **Load latest evidence into draft**. Update guide instructions to match the reviewed recommendations, save the draft and validate it. Publishing remains a separate editor action. Existing published snapshots are immutable.

The existing **Request new research** queue is still the separate API-backed research worker. Use the assistant conversation for subscription-funded MCP research; enabling paid API research is not required.

## Verification and limitations

- The backend rejects connector tokens on all non-research routes, including player history, photos, AI calls, budgets, token management, approval and publication.
- Tests cover MCP initialization/tool calls over actual stdio, strict proposal validation, denied capabilities, idempotency, stale corrections, approval auditing, revocation, expiry, and the admin workflow with mocked cloud responses.
- Live Supabase deployment and a signed-in Codex/Claude connection still require your backend configuration and a minted token. The stdio integration test uses a local HTTP test backend; it is not evidence of a live subscription session.
- The SDK is pinned to the supported v1 maintenance line to share the project's Zod 3 schemas. No MCP sampling is used, so hosts need only standard tools/prompts support.

Official references: [Codex MCP configuration](https://learn.chatgpt.com/docs/extend/mcp), [Claude Code MCP configuration](https://code.claude.com/docs/en/mcp), [MCP TypeScript SDK](https://github.com/modelcontextprotocol/typescript-sdk/tree/v1.x).
## Versioned knowledge proposals

The connector now returns registered software releases, current citation revisions and draft state definitions. Proposals can add `applicability` (`unknown`, `releases`, or `not_applicable`), source `relation` (`supports` or `contradicts`) and an optional SHA-256 `contentHash` of retrieved document bytes. Do not invent hashes or release IDs. Register a release through the admin portal before proposing applicability to it.

Stateful recommendations also include `ruleSpec`: the exact rule ID, shot ID, prerequisites, outcome, repeatable flag, conditions, effects and complete variable definitions. Human approval covers this specification as well as wording. Changing any of that behaviour requires a new reviewed revision. The previous proposal format remains accepted with unknown applicability. Read [DATA-MODEL.md](DATA-MODEL.md) for the contract and migration sequence.
