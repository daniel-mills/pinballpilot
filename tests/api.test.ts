import { describe, expect, it, vi } from 'vitest'
import { createHandler, validateAnswer, syncSchema, type Services } from '../supabase/functions/pilot/handler'
import { modelJson } from '../supabase/functions/pilot/provider'
import { packSchema } from '../shared/contracts'
import demo from '../content/demo-pack.json'
const pack=packSchema.parse(demo)
function fixture(role:'tester'|'editor'|'owner'='tester') {
  const services:Services={authenticate:vi.fn(async()=>({id:'player',role})),catalogue:vi.fn(async()=>[]),latestPack:vi.fn(async()=>pack),workspace:vi.fn(async()=>({claims:[]})),review:vi.fn(async()=>{}),saveDraft:vi.fn(async()=>{}),publish:vi.fn(async()=>{}),research:vi.fn(async()=>{}),budget:vi.fn(async()=>{}),reserve:vi.fn(async()=>true),coach:vi.fn(async()=>({answer:'Try the left ramp.',kind:'ai_suggestion',claimIds:[pack.claims[0]!.id],shotIds:['left-ramp'],uncertainty:''})),identify:vi.fn(async()=>({candidates:[],observations:[],clarification:'Please add a clearer display photo.'})),activity:vi.fn(async()=>{})}
  return {services,handler:createHandler(services,'http://localhost:3000')}
}
const request=(path:string,body?:unknown,auth=true)=>new Request(`https://example.test/functions/v1/pilot/${path}`,{method:body?'POST':'GET',headers:{...(auth?{Authorization:'Bearer test-jwt'}:{}),'Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})})
const coachRequest={variantId:pack.variantId,question:'What should I shoot?',requestId:'00000000-0000-4000-8000-000000000001'}
describe('HTTP API boundaries',()=>{
  it('loads the pinned pack for coaching and historical downloads',async()=>{
    const {handler,services}=fixture()
    expect((await handler(request(`packs/${pack.variantId}?version=1`))).status).toBe(200)
    expect(services.latestPack).toHaveBeenCalledWith(pack.variantId,1)
    expect((await handler(request('coach',{...coachRequest,packVersion:1}))).status).toBe(200)
    expect(services.latestPack).toHaveBeenLastCalledWith(pack.variantId,1)
  })
  it('rejects state belonging to another pack before reserving money',async()=>{
    const {handler,services}=fixture()
    expect((await handler(request('coach',{...coachRequest,state:{invented:{value:2,origin:'player',observedAt:1}}}))).status).toBe(400)
    expect(services.reserve).not.toHaveBeenCalled()
  })
  it('protects evidence history and release creation from testers',async()=>{
    const {handler,services}=fixture();services.history=vi.fn(async()=>({}));services.release=vi.fn(async()=>{})
    expect((await handler(request('admin/history/claim-1'))).status).toBe(403)
    expect((await handler(request('admin/releases',{id:'v1',variantId:pack.variantId,version:'1.0',releasedAt:null}))).status).toBe(403)
    expect(services.history).not.toHaveBeenCalled();expect(services.release).not.toHaveBeenCalled()
  })
  it('requires authentication for every route',async()=>{const {handler}=fixture();expect((await handler(request('catalogue',undefined,false))).status).toBe(401)})
  it('does not let testers access editorial drafts',async()=>{const {handler,services}=fixture();expect((await handler(request('admin/workspace'))).status).toBe(403);expect(services.workspace).not.toHaveBeenCalled()})
  it('limits cap changes to the owner',async()=>{const {handler}=fixture('editor');expect((await handler(request('admin/budget',{limitMicroAud:60000000}))).status).toBe(403)})
  it('reviews the exact revision',async()=>{const {handler,services}=fixture('owner');const body={id:'claim-1',revision:3,status:'approved',reviewNote:'Verified on machine'};expect((await handler(request('admin/review',body))).status).toBe(200);expect(services.review).toHaveBeenCalledWith(body,'player')})
  it('abstains without spending when no approved pack exists',async()=>{const {handler,services}=fixture();services.latestPack=vi.fn(async()=>null);const response=await handler(request('coach',coachRequest));expect((await response.json()).kind).toBe('unknown');expect(services.reserve).not.toHaveBeenCalled()})
  it('blocks the provider when the cap refuses a reservation',async()=>{const {handler,services}=fixture();services.reserve=vi.fn(async()=>false);expect((await handler(request('coach',coachRequest))).status).toBe(429);expect(services.coach).not.toHaveBeenCalled()})
  it('returns grounded suggestions',async()=>{const {handler}=fixture();const response=await handler(request('coach',coachRequest));expect(response.status).toBe(200);expect((await response.json()).kind).toBe('ai_suggestion')})
  it('requires confirmation for vision observations',async()=>{const {handler}=fixture();const response=await handler(request('identify',{requestId:coachRequest.requestId,photos:['data:image/jpeg;base64,YQ==']}));expect((await response.json()).requiresConfirmation).toBe(true)})
  it('rejects remote photo URLs to prevent server-side fetching of arbitrary destinations',async()=>{const {handler,services}=fixture();expect((await handler(request('identify',{requestId:coachRequest.requestId,photos:['http://169.254.169.254/']}))).status).toBe(400);expect(services.identify).not.toHaveBeenCalled()})
  it('does not allow origin wildcard access',async()=>{const {handler}=fixture();const req=request('catalogue');req.headers.set('Origin','https://untrusted.example');expect((await handler(req)).status).toBe(403)})
  it('returns controlled errors for malformed JSON',async()=>{const {handler}=fixture();const req=new Request('https://example.test/pilot/coach',{method:'POST',headers:{Authorization:'Bearer token'},body:'{broken'});expect((await handler(req)).status).toBe(400)})
  it('does not leak provider errors',async()=>{const {handler,services}=fixture();services.coach=vi.fn(async()=>{throw new Error('secret-token-and-player-photo')});const response=await handler(request('coach',coachRequest));expect(await response.text()).not.toContain('secret-token')})
})
describe('AI output validation',()=>{
  it('rejects invented source and shot identifiers',()=>{expect(()=>validateAnswer({answer:'Advice',kind:'ai_suggestion',claimIds:['invented'],shotIds:[],uncertainty:''},pack)).toThrow(/unsupported/);expect(()=>validateAnswer({answer:'Advice',kind:'ai_suggestion',claimIds:[pack.claims[0]!.id],shotIds:['invented'],uncertainty:''},pack)).toThrow(/unsupported/)})
  it('never labels newly generated prose as approved',()=>{expect(validateAnswer({answer:'New idea',kind:'approved',claimIds:[pack.claims[0]!.id],shotIds:[],uncertainty:''},pack).kind).toBe('ai_suggestion')})
  it('bounds output and disables provider storage',async()=>{
    const fetcher=vi.fn(async()=>new Response(JSON.stringify({status:'completed',output:[{type:'message',content:[{type:'output_text',text:'{"ok":true}'}]}]}),{status:200}))
    expect(await modelJson({key:'test',model:'configured-model'},'Instructions','Question',[],fetcher)).toEqual({ok:true})
    const body=JSON.parse(fetcher.mock.calls[0]![1]!.body as string)
    expect(body.store).toBe(false);expect(body.max_output_tokens).toBe(1000)
  })
})

describe('Between-ball scans',()=>{
  const body={variantId:pack.variantId,packVersion:pack.version,requestId:coachRequest.requestId,photos:['data:image/jpeg;base64,YQ==','data:image/jpeg;base64,Yg==']}
  const reading={target:'score',variableId:null,value:1200,evidence:'PLAYER 1 1200',confidence:0.8}
  const result={machineMismatch:false,readings:[reading],note:''}
  it('pins the guide, uses the vision budget and returns unconfirmed readings without writing activity',async()=>{
    const {handler,services}=fixture();services.scan=vi.fn(async()=>result)
    const response=await handler(request('scan',body))
    expect(response.status).toBe(200)
    expect(await response.json()).toEqual({...result,requiresConfirmation:true})
    expect(services.latestPack).toHaveBeenCalledWith(pack.variantId,pack.version)
    expect(services.reserve).toHaveBeenCalledWith(body.requestId,'identify')
    expect(services.activity).not.toHaveBeenCalled()
  })
  it('does not spend on missing packs or invalid image inputs',async()=>{
    const {handler,services}=fixture();services.scan=vi.fn(async()=>result)
    expect((await handler(request('scan',{...body,photos:['https://example.test/image.jpg']}))).status).toBe(400)
    expect((await handler(request('scan',{...body,photos:[...body.photos,...body.photos]}))).status).toBe(400)
    services.latestPack=vi.fn(async()=>null)
    expect((await handler(request('scan',body))).status).toBe(409)
    expect(services.reserve).not.toHaveBeenCalled();expect(services.scan).not.toHaveBeenCalled()
  })
  it('honours authentication and the spending cap',async()=>{
    const {handler,services}=fixture();services.scan=vi.fn(async()=>result)
    expect((await handler(request('scan',body,false))).status).toBe(401)
    services.reserve=vi.fn(async()=>false)
    expect((await handler(request('scan',body))).status).toBe(429)
    expect(services.scan).not.toHaveBeenCalled()
  })
  it('rejects unsupported model output with a recoverable error',async()=>{
    const {handler,services}=fixture();services.scan=vi.fn(async()=>({...result,readings:[{...reading,target:'state',variableId:'invented'}]}))
    expect((await handler(request('scan',body))).status).toBe(502)
  })
  it('syncs confirmed readings and their original observations without image data',()=>{
    const payload={origin:'player',scoreBefore:null,scoreAfter:1200,scan:{capturedAt:123,packVersion:1,readings:[reading],detectedReadings:[{...reading,value:120} ]}}
    const parsed=syncSchema.parse({games:[],events:[{id:coachRequest.requestId,gameId:coachRequest.requestId,timestamp:125,kind:'progress',text:'Confirmed scan',payload}],learning:[],favourites:[]})
    expect(parsed.events[0]!.payload).toEqual(payload)
    expect(()=>syncSchema.parse({...parsed,events:[{...parsed.events[0],payload:{...payload,photos:body.photos}}]})).toThrow()
  })
})
