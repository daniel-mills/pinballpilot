import { createClient } from '@supabase/supabase-js'
import { ApiError, createHandler, type Identity, type Services } from './handler.ts'
import { modelJson, coachPrompt, identifyInstructions } from './provider.ts'
import { packSchema, publicationErrors, type MachinePack } from '../../../shared/contracts.ts'
import { runResearch } from './research.ts'
import { createConnectorServices } from './connector.ts'

const env=(key:string)=>Deno.env.get(key) ?? ''
const url=env('SUPABASE_URL'), anon=env('SUPABASE_ANON_KEY')
const root=createClient(url,env('SUPABASE_SERVICE_ROLE_KEY'),{auth:{persistSession:false}})
const provider={key:env('OPENAI_API_KEY'),model:env('AI_MODEL')}
function userClient(token:string) { return createClient(url,anon,{global:{headers:{Authorization:`Bearer ${token}`}},auth:{persistSession:false}}) }
function checked<T>(result:{data:T;error:unknown}): T {if(result.error) throw new ApiError(503,'Database operation failed. Reload and try again.');return result.data}
const claimDto=(c:Record<string,unknown>)=>({id:c.id,variantId:c.variant_id,kind:c.kind,title:c.title,body:c.body,sourceIds:c.source_ids,status:c.status,revision:c.revision,reviewedRevision:c.reviewed_revision,reviewNote:c.review_note,software:c.software})

const services: Services={
  connector:createConnectorServices(root),
  async authenticate(token) {
    const {data,error}=await root.auth.getUser(token)
    if(error||!data.user) throw new ApiError(401,'Sign in again to continue')
    const membership=checked(await root.from('memberships').select('role').eq('user_id',data.user.id).maybeSingle())
    if(!membership) throw new ApiError(403,'This account is not part of the private pilot yet')
    return {id:data.user.id,role:membership.role} as Identity
  },
  async catalogue() {
    const rows=checked(await root.from('machine_pack_versions').select('variant_id,version,payload,published_at').order('version',{ascending:false}))
    const seen=new Set<string>()
    return (rows??[]).filter(r=>{if(seen.has(r.variant_id))return false;seen.add(r.variant_id);return true}).map(r=>({variantId:r.variant_id,version:r.version,name:r.payload.name,edition:r.payload.edition,demo:r.payload.demo}))
  },
  async latestPack(id) {
    const row=checked(await root.from('machine_pack_versions').select('payload').eq('variant_id',id).order('version',{ascending:false}).limit(1).maybeSingle())
    return row ? packSchema.parse(row.payload) : null
  },
  async workspace() {
    const claims=checked(await root.from('claims').select('*').eq('variant_id','iron-maiden-pro').order('id'))
    const pack=checked(await root.from('pack_drafts').select('payload').eq('variant_id','workshop-demo').maybeSingle())
    const budget=checked(await root.from('budget_config').select('*').eq('id',true).single())
    const sources=checked(await root.from('knowledge_sources').select('*'))
    const jobs=checked(await root.from('research_jobs').select('id,query,state,error').order('created_at',{ascending:false}).limit(20))
    const drafts=checked(await root.from('pack_drafts').select('payload'))
    return {claims:(claims??[]).map(claimDto),sources:(sources??[]).map(s=>({id:s.id,title:s.title,url:s.url,kind:s.kind,locator:s.locator,accessedAt:s.accessed_at,notes:s.notes})),jobs:jobs??[],drafts:(drafts??[]).map(d=>d.payload),pack:pack?.payload,budget:{limitMicroAud:budget.limit_micro_aud,onlineEnabled:budget.online_enabled,infrastructureMicroAud:budget.infrastructure_micro_aud}}
  },
  async review(b,actor) {checked(await root.rpc('review_claim',{p_id:b.id,p_revision:b.revision,p_status:b.status,p_note:b.reviewNote,p_reviewer:actor}))},
  async saveDraft(pack) { checked(await root.from('pack_drafts').upsert({variant_id:pack.variantId,payload:pack},{onConflict:'variant_id'})) },
  async publish(id,version,actor) {
    const draft=checked(await root.from('pack_drafts').select('payload,revision').eq('variant_id',id).single())
    if(!draft) throw new ApiError(404,'Draft not found')
    const errors=publicationErrors(draft.payload)
    if(errors.length) throw new ApiError(422,errors.join('. '))
    const payload=packSchema.parse(draft.payload)
    if(payload.version!==version) throw new ApiError(409,'Draft version changed. Reload before publishing.')
    const hash=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(JSON.stringify(payload))))).map(b=>b.toString(16).padStart(2,'0')).join('')
    checked(await root.rpc('publish_pack',{p_variant:id,p_version:version,p_draft_revision:draft.revision,p_hash:hash,p_actor:actor}))
  },
  async research(id,query,actor) {
    const job=checked(await root.from('research_jobs').insert({variant_id:id,query,requested_by:actor}).select('id,variant_id,query').single())
    if(!job) throw new ApiError(503,'Research could not be queued')
    EdgeRuntime.waitUntil(runResearch(root,job,{key:provider.key,model:provider.model,maxMicroAud:Number(env('RESEARCH_MAX_MICRO_AUD')),verified:env('COST_BOUNDS_VERIFIED')==='true'}))
  },
  async budget(limitMicroAud) {checked(await root.from('budget_config').update({limit_micro_aud:limitMicroAud}).eq('id',true))},
  async reserve(requestId,kind) {
    const bound=Number(env(kind==='identify'?'VISION_MAX_MICRO_AUD':'COACH_MAX_MICRO_AUD'))
    // Bounds include worst-case input, output, image charges, taxes and FX margin.
    // Do not release reservations on ambiguous failures or client disconnects.
    if(!provider.key||!provider.model||!Number.isSafeInteger(bound)||bound<=0||env('COST_BOUNDS_VERIFIED')!=='true') return false
    return checked(await root.rpc('reserve_budget',{p_request_id:requestId,p_amount:bound,p_kind:kind}))===true
  },
  async coach(pack,question,confirmedOutcomes) {const p=coachPrompt(pack,question,confirmedOutcomes);return modelJson(provider,p.instructions,p.prompt)},
  async identify(photos) {return modelJson(provider,identifyInstructions,'Inspect these pinball photos. Return the specified JSON object.',photos)},
  async sync(body,actor,token) {
    const client=userClient(token)
    const now=Date.now()
    for(const game of body.games) {
      if(game.updatedAt>now+300000 || game.startedAt>game.updatedAt) throw new ApiError(400,'Game timestamps are invalid. Check your device clock.')
      checked(await client.rpc('sync_game',{p_game:game}))
    }
    if(body.events.length) checked(await client.from('player_activity').upsert(body.events.map(e=>({id:e.id,owner_id:actor.id,game_id:e.gameId,timestamp:new Date(e.timestamp).toISOString(),kind:e.kind,text:e.text})),{onConflict:'id',ignoreDuplicates:true}))
    const preferences=checked(await client.from('player_preferences').select('*').eq('owner_id',actor.id).maybeSingle())
    const learned=new Map<string,unknown>()
    for(const l of [...(preferences?.learning?.items??[]),...body.learning]) learned.set(`${l.variantId}:${l.outcome}`,l)
    const favourites=[...new Set([...(preferences?.favourites??[]),...body.favourites])]
    checked(await client.from('player_preferences').upsert({owner_id:actor.id,learning:{items:[...learned.values()]},favourites}))
    const games=checked(await client.from('player_games').select('*').order('updated_at',{ascending:false}).limit(500))
    return {games:(games??[]).map(g=>({id:g.id,variantId:g.variant_id,startedAt:Date.parse(g.started_at),updatedAt:Date.parse(g.updated_at),score:g.score,ended:g.ended,completed:g.completed})),learning:[...learned.values()],favourites}
  },
  async activity(body,actor,token) {
    // Deliberately use the player's JWT, not service-role access, for private writes.
    const client=userClient(token)
    const game=checked(await client.from('player_games').select('id').eq('id',body.gameId).maybeSingle())
    if(!game) checked(await client.from('player_games').insert({id:body.gameId,owner_id:actor.id,variant_id:'unknown',started_at:new Date(body.timestamp).toISOString()}))
    checked(await client.from('player_activity').upsert({id:body.id,owner_id:actor.id,game_id:body.gameId,timestamp:new Date(body.timestamp).toISOString(),kind:body.kind,text:body.text},{onConflict:'id'}))
  },
}
Deno.serve(createHandler(services,env('ADMIN_ORIGIN')||'http://localhost:3000'))
