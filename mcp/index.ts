import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js'
import { createResearchClient, createResearchServer } from './server.ts'

try {
  const client=createResearchClient(process.env.PILOT_API_URL??'',process.env.PILOT_RESEARCH_TOKEN??'',process.env.PILOT_ANON_KEY??'')
  await createResearchServer(client).connect(new StdioServerTransport())
} catch {
  // stdout is exclusively MCP protocol. Never print credentials or raw exceptions.
  process.stderr.write('Pinball Pilot MCP could not start. Check PILOT_API_URL, PILOT_RESEARCH_TOKEN and PILOT_ANON_KEY in your private environment file.\n')
  process.exitCode=1
}
