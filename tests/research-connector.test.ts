import { describe,it,expect,vi } from 'vitest'
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js'
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js'
import { createServer } from 'node:http'
import { createResearchClient,createResearchServer } from '../mcp/server'
import { createHandler,type Services,ApiError } from '../supabase/functions/pilot/handler'
import { proposalSchema } from '../shared/research'
import { tokenHash } from '../supabase/functions/pilot/connector'

export const proposal={requestId:'10000000-0000-4000-8000-000000000010',variantId:'iron-maiden-pro',targetClaimId:null,baseRevision:null,kind:'fact' as const,title:'Example finding',body:'A paraphrased finding supported by the cited page.',software:'Verify installed version',rationale:'Fill a knowledge gap.',uncertainty:'Operator settings need checking.',sources:[{title:'Manufacturer manual',url:'https://www.sternpinball.com/manual.pdf',kind:'manufacturer' as const,locator:'Page 12',accessedAt:'2026-10-09',notes:'Verify edition.'}]}
const token='pp_mcp_'+'a'.repeat(64)
function fixture() {
  const connector={authenticate:vi.fn(async()=>({id:'editor-id',role:'editor' as const,scope:'research' as const,tokenId:'token-id'})),machines:vi.fn(async()=>[{id:'iron-maiden-pro'}]),knowledge:vi.fn(async()=>({claims:[],sources:[]})),proposals:vi.fn(async()=>({items:[],nextOffset:null})),submit:vi.fn(async()=>({id:'proposal-id',status:'pending'})),review:vi.fn(async()=>{}),tokens:vi.fn(async()=>[]),createToken:vi.fn(async()=>({token:'secret',record:{}})),revokeToken:vi.fn(async()=>{})}
  const services={connector,authenticate:vi.fn(async()=>({id:'editor-id',role:'editor'})),publish:vi.fn(),reserve:vi.fn(),workspace:vi.fn(),sync:vi.fn()} as unknown as Services
  const handler=createHandler(services,'http://localhost:3000')
  const request=(path:string,body?:unknown,bearer=token)=>handler(new Request(`https://test.invalid/functions/v1/pilot/${path}`,{method:body?'POST':'GET',headers:{Authorization:`Bearer ${bearer}`},...(body?{body:JSON.stringify(body)}:{})}))
  return {connector,services,request}
}
describe('research connector boundaries',()=>{
  it('rejects approval fields, missing citations, unsafe source URLs and incomplete correction references',()=>{
    for(const value of [{...proposal,status:'approved'},{...proposal,sources:[]},{...proposal,targetClaimId:'claim-1'},{...proposal,sources:[{...proposal.sources[0],url:'javascript:alert(1)'}]}]) expect(proposalSchema.safeParse(value).success).toBe(false)
  })
  it('uses a deterministic one-way token digest',async()=>{expect(await tokenHash(token)).toMatch(/^[0-9a-f]{64}$/);expect(await tokenHash(token)).not.toBe(await tokenHash(token+'b'))})
  it('allows content reads and pending submission without an AI reservation',async()=>{const f=fixture();expect((await f.request('research/machines')).status).toBe(200);expect((await f.request('research/proposals',proposal)).status).toBe(202);expect(f.connector.submit).toHaveBeenCalledWith('token-id',proposal);expect(f.services.reserve).not.toHaveBeenCalled()})
  it('blocks connector credentials on every non-research capability',async()=>{
    const f=fixture()
    for(const path of ['admin/workspace','admin/review','admin/proposals/review','admin/publish','admin/connector-tokens','admin/budget','coach','identify','sync','activity','catalogue']) expect((await f.request(path,{})).status,path).toBe(403)
    expect(f.connector.review).not.toHaveBeenCalled();expect(f.services.publish).not.toHaveBeenCalled()
  })
  it('requires scoped credentials for submission and editor membership for content',async()=>{
    const f=fixture();expect((await f.request('research/proposals',proposal,'editor-jwt')).status).toBe(403)
    f.services.authenticate=vi.fn(async()=>({id:'tester',role:'tester'}));expect((await f.request('research/machines',undefined,'tester-jwt')).status).toBe(403)
  })
  it('fails closed when a connector is revoked',async()=>{const f=fixture();f.connector.authenticate.mockRejectedValue(new ApiError(401,'Revoked'));expect((await f.request('research/machines')).status).toBe(401);expect(f.connector.machines).not.toHaveBeenCalled()})
  it('requires an explicit review note and a full editor session',async()=>{const f=fixture();const body={id:proposal.requestId,status:'approved',note:'Verified manufacturer page'};expect((await f.request('admin/proposals/review',body,'editor-jwt')).status).toBe(200);expect((await f.request('admin/proposals/review',{...body,note:''},'editor-jwt')).status).toBe(400)})
  it('refuses insecure hosts and never follows redirects with a token',async()=>{
    expect(()=>createResearchClient('http://example.com/functions/v1/pilot',token,'')).toThrow(/HTTPS/)
    const fetcher=vi.fn(async()=>new Response('{}'))
    await createResearchClient('https://example.com/functions/v1/pilot',token,'public',fetcher).request('research/machines')
    expect(fetcher.mock.calls[0]![1]).toMatchObject({redirect:'error'})
  })
})
describe('MCP protocol',()=>{
  it('exposes only four constrained tools and a research prompt; validates submissions',async()=>{
    const calls:unknown[]=[]
    const server=createResearchServer({request:async(path,body)=>{calls.push({path,body});return {status:'pending'}}})
    const client=new Client({name:'test',version:'1'})
    const [a,b]=InMemoryTransport.createLinkedPair()
    await Promise.all([server.connect(a),client.connect(b)])
    try {
      expect((await client.listTools()).tools.map(t=>t.name)).toEqual(['list_machines','get_machine_knowledge','list_research_proposals','submit_research_proposal'])
      expect((await client.listPrompts()).prompts[0]?.name).toBe('refine_machine_knowledge')
      const invalid=await client.callTool({name:'submit_research_proposal',arguments:{proposal:{...proposal,sources:[]}}})
      expect(invalid.isError).toBe(true);expect(calls).toHaveLength(0)
      const saved=await client.callTool({name:'submit_research_proposal',arguments:{proposal}})
      expect(saved.isError).not.toBe(true);expect(calls).toEqual([{path:'research/proposals',body:proposal}])
    } finally {await client.close();await server.close()}
  })
  it('runs the built stdio entrypoint with a real MCP client and an HTTP backend',async()=>{
    const calls:{url?:string;auth?:string;body:string}[]=[]
    const http=createServer(async(req,res)=>{let body='';for await(const chunk of req)body+=chunk;calls.push({url:req.url,auth:req.headers.authorization,body});res.setHeader('Content-Type','application/json');res.end(JSON.stringify(req.method==='POST'?{status:'pending'}:[{id:'iron-maiden-pro'}]))})
    await new Promise<void>(resolve=>http.listen(0,'127.0.0.1',resolve))
    const address=http.address() as {port:number}
    const client=new Client({name:'stdio-test',version:'1'})
    const transport=new StdioClientTransport({command:process.execPath,args:['mcp/dist/mcp/index.js'],env:{PILOT_API_URL:`http://127.0.0.1:${address.port}/functions/v1/pilot`,PILOT_RESEARCH_TOKEN:token,PILOT_ANON_KEY:'public-test-key'},stderr:'pipe'})
    try {
      await client.connect(transport)
      await client.callTool({name:'list_machines',arguments:{}})
      await client.callTool({name:'submit_research_proposal',arguments:{proposal}})
      expect(calls).toHaveLength(2);expect(calls[0]?.auth).toBe(`Bearer ${token}`);expect(JSON.parse(calls[1]!.body)).toEqual(proposal)
    } finally {await client.close();await new Promise<void>(resolve=>http.close(()=>resolve()))}
  })
})
