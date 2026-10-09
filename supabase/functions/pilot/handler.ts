import { z } from 'zod'
import { packSchema, publicationErrors, type MachinePack } from '../../../shared/contracts.ts'
import { machineIdSchema, proposalSchema } from '../../../shared/research.ts'
import type { ConnectorServices } from './connector.ts'
import { gameStateSchema, validValue, type GameState } from '../../../shared/state.ts'
import { scanReadingSchema, validateScan } from '../../../shared/scan.ts'

export class ApiError extends Error { constructor(public status: number, message: string) { super(message) } }
export interface Identity { id: string; role: 'tester' | 'editor' | 'owner'; scope?:'research'; tokenId?:string }
export interface Services {
  authenticate(token: string): Promise<Identity>
  connector?: ConnectorServices
  catalogue(): Promise<unknown>
  latestPack(variantId: string, version?:number): Promise<MachinePack | null>
  history?(claimId:string):Promise<unknown>
  release?(body:{id:string;variantId:string;version:string;releasedAt:string|null}):Promise<void>
  workspace(): Promise<unknown>
  review(body: {id:string;revision:number;status:'approved'|'rejected';reviewNote:string}, actor: string): Promise<void>
  saveDraft(pack: MachinePack): Promise<void>
  publish(variantId: string, version: number, actor: string): Promise<void>
  research(variantId: string, query: string, actor: string): Promise<void>
  budget(limitMicroAud: number): Promise<void>
  reserve(requestId: string, kind: string): Promise<boolean>
  coach(pack: MachinePack, question: string, confirmedOutcomes: string[], state?:GameState): Promise<unknown>
  identify(photos: string[]): Promise<unknown>
  scan?(pack:MachinePack, photos:string[]):Promise<unknown>
  activity(body: {id:string;gameId:string;timestamp:number;kind:string;text:string}, actor: Identity, token:string): Promise<void>
  sync?(body: SyncPayload, actor:Identity, token:string):Promise<unknown>
}
export const syncSchema=z.object({
  games:z.array(z.object({id:z.string().uuid(),variantId:z.string().min(1).max(100),startedAt:z.number().int().nonnegative(),updatedAt:z.number().int().nonnegative(),score:z.number().int().nonnegative().nullable(),ended:z.boolean(),completed:z.array(z.string().max(100)).max(100),packVersion:z.number().int().positive().nullable().optional(),state:gameStateSchema.optional()})).max(500),
  events:z.array(z.object({id:z.string().uuid(),gameId:z.string().uuid(),timestamp:z.number().int().nonnegative(),kind:z.string().max(30),text:z.string().max(4000),payload:z.object({ruleId:z.string().max(100).optional(),origin:z.enum(['player','inferred']).optional(),before:gameStateSchema.optional(),after:gameStateSchema.optional(),completedBefore:z.array(z.string().max(100)).max(100).optional(),supersedes:z.string().uuid().optional(),scoreBefore:z.number().int().safe().nonnegative().nullable().optional(),scoreAfter:z.number().int().safe().nonnegative().nullable().optional(),scan:z.object({capturedAt:z.number().int().nonnegative(),packVersion:z.number().int().positive(),readings:z.array(scanReadingSchema).min(1).max(12),detectedReadings:z.array(scanReadingSchema).max(12)}).strict().optional()}).strict().optional()})).max(500),
  learning:z.array(z.object({variantId:z.string().max(100),outcome:z.string().max(100),learnedAt:z.number().int().nonnegative()})).max(1000),
  favourites:z.array(z.string().max(100)).max(500),
})
export type SyncPayload=z.infer<typeof syncSchema>
const requestId = z.string().uuid()
const variantId = z.string().min(1).max(100)
export const answerSchema = z.object({ answer:z.string().max(1800), kind:z.enum(['approved','ai_suggestion','unknown']), claimIds:z.array(z.string()).max(20), shotIds:z.array(z.string()).max(10), uncertainty:z.string().max(600) })
export const identificationSchema = z.object({ candidates:z.array(z.object({ name:z.string().max(200), edition:z.string().max(100), confidence:z.number().min(0).max(1) })).max(5), observations:z.array(z.string().max(300)).max(12), clarification:z.string().max(500) })
export function validateAnswer(value: unknown, pack: MachinePack) {
  const answer = answerSchema.parse(value)
  const approved = new Set(pack.claims.filter(c=>c.status==='approved'&&c.reviewedRevision===c.revision&&c.variantId===pack.variantId).map(c=>c.id))
  if (answer.claimIds.some(id=>!approved.has(id)) || answer.shotIds.some(id=>!pack.shots.some(s=>s.id===id))) throw new ApiError(502,'AI returned unsupported references. Use the verified guide instead.')
  if (answer.kind!=='unknown' && !answer.claimIds.length) throw new ApiError(502,'AI could not support its answer with reviewed evidence.')
  // Generated prose is always a suggestion unless it is an exact approved recommendation.
  if (answer.kind==='approved' && !pack.claims.some(c=>c.kind==='recommendation'&&approved.has(c.id)&&c.body===answer.answer)) answer.kind='ai_suggestion'
  return answer
}
export function createHandler(services: Services, allowedOrigin: string) {
  return async (request: Request): Promise<Response> => {
    const origin=request.headers.get('Origin')
    const cors: Record<string,string> = { 'Content-Type':'application/json', 'Vary':'Origin', 'Cache-Control':'no-store' }
    if(origin===allowedOrigin) { cors['Access-Control-Allow-Origin']=allowedOrigin; cors['Access-Control-Allow-Headers']='authorization,apikey,content-type'; cors['Access-Control-Allow-Methods']='GET,POST,OPTIONS' }
    const reply = (body:unknown,status=200)=>new Response(JSON.stringify(body),{status,headers:cors})
    try {
      if(origin && origin!==allowedOrigin) throw new ApiError(403,'Origin not allowed')
      if(request.method==='OPTIONS') return new Response(null,{status:204,headers:cors})
      const bearer=request.headers.get('Authorization')?.match(/^Bearer (.+)$/)?.[1]
      if(!bearer) throw new ApiError(401,'Sign in to continue')
      const actor:Identity=bearer.startsWith('pp_mcp_')&&services.connector
        ?await services.connector.authenticate(bearer):await services.authenticate(bearer)
      const path=new URL(request.url).pathname.split('/pilot/')[1] ?? ''
      if(actor.scope==='research'&&!path.startsWith('research/')) throw new ApiError(403,'Connector tokens can only read knowledge and submit proposals')
      if(path.startsWith('research/')&&actor.role==='tester') throw new ApiError(403,'Editor access required')
      const admin=path.startsWith('admin/')
      if(admin && actor.role==='tester') throw new ApiError(403,'Editor access required')
      if(request.method==='GET') {
        if(services.connector) {
          const search=new URL(request.url).searchParams
          const offset=z.coerce.number().int().min(0).max(100000).parse(search.get('offset')??0)
          if(path==='research/machines') return reply(await services.connector.machines())
          if(path.startsWith('research/knowledge/')) return reply(await services.connector.knowledge(machineIdSchema.parse(decodeURIComponent(path.slice('research/knowledge/'.length))),offset))
          if(path==='research/proposals'||path==='admin/proposals') return reply(await services.connector.proposals(search.has('variantId')?machineIdSchema.parse(search.get('variantId')):undefined,offset))
          if(path==='admin/connector-tokens') return reply(await services.connector.tokens(actor.id))
        }
        if(path==='catalogue') return reply(await services.catalogue())
        if(path==='admin/workspace') return reply(await services.workspace())
        if(path.startsWith('admin/history/') && services.history) return reply(await services.history(variantId.parse(decodeURIComponent(path.slice('admin/history/'.length)))))
        if(path.startsWith('packs/')) { const version=new URL(request.url).searchParams.get('version'); const p=await services.latestPack(decodeURIComponent(path.slice(6)),version===null?undefined:z.coerce.number().int().positive().parse(version)); if(!p) throw new ApiError(404,'No reviewed pack is published for this machine yet'); return reply(p) }
        throw new ApiError(404,'Route not found')
      }
      if(request.method!=='POST') throw new ApiError(405,'Method not allowed')
      if(Number(request.headers.get('Content-Length') ?? 0)>6_000_000) throw new ApiError(413,'Request too large')
      const raw=await request.text(); if(raw.length>6_000_000) throw new ApiError(413,'Request too large')
      let body:unknown; try {body=JSON.parse(raw)} catch {throw new ApiError(400,'Invalid JSON')}
      if(services.connector) {
        if(path==='research/proposals') {
          if(actor.scope!=='research'||!actor.tokenId) throw new ApiError(403,'Use a scoped connector token to submit research')
          return reply(await services.connector.submit(actor.tokenId,proposalSchema.parse(body)),202)
        }
        if(path==='admin/connector-tokens') {
          const b=z.object({label:z.string().trim().min(1).max(80),days:z.number().int().min(1).max(90)}).strict().parse(body)
          return reply(await services.connector.createToken(actor.id,b.label,b.days),201)
        }
        if(path==='admin/connector-tokens/revoke') {
          const b=z.object({id:z.string().uuid()}).strict().parse(body)
          await services.connector.revokeToken(actor.id,b.id);return reply({ok:true})
        }
        if(path==='admin/proposals/review') {
          const b=z.object({id:z.string().uuid(),status:z.enum(['approved','rejected']),note:z.string().trim().min(1).max(2000)}).strict().parse(body)
          await services.connector.review(b.id,b.status,b.note,actor.id);return reply({ok:true})
        }
      }
      if(path==='admin/review') {
        const b=z.object({id:variantId,revision:z.number().int().positive(),status:z.enum(['approved','rejected']),reviewNote:z.string().max(2000)}).parse(body)
        await services.review(b,actor.id); return reply({ok:true})
      }
      if(path==='admin/releases' && services.release) { await services.release(z.object({id:machineIdSchema,variantId,version:z.string().trim().min(1).max(100),releasedAt:z.string().date().nullable()}).strict().parse(body)); return reply({ok:true},201) }
      if(path==='admin/draft') { const b=z.object({pack:packSchema}).parse(body); await services.saveDraft(b.pack); return reply({ok:true}) }
      if(path==='admin/publish') { const b=z.object({variantId,version:z.number().int().positive()}).parse(body); await services.publish(b.variantId,b.version,actor.id); return reply({ok:true}) }
      if(path==='admin/research') { const b=z.object({variantId,query:z.string().min(5).max(2000)}).parse(body); await services.research(b.variantId,b.query,actor.id); return reply({ok:true},202) }
      if(path==='admin/budget') {
        if(actor.role!=='owner') throw new ApiError(403,'Only the owner can change the spending cap')
        const b=z.object({limitMicroAud:z.number().int().min(1_000_000).max(10_000_000_000)}).parse(body)
        await services.budget(b.limitMicroAud); return reply({ok:true})
      }
      if(path==='coach') {
        const b=z.object({variantId,question:z.string().trim().min(1).max(1000),requestId,confirmedOutcomes:z.array(z.string().max(100)).max(100).default([]),packVersion:z.number().int().positive().optional(),state:gameStateSchema.optional()}).parse(body)
        const pack=await services.latestPack(b.variantId,b.packVersion)
        if(!pack || publicationErrors(pack).length) return reply({answer:'There is no verified guide for this edition yet. I cannot give reliable machine-specific advice.',kind:'unknown',claimIds:[],shotIds:[],uncertainty:'Content awaits review.'})
        if(b.confirmedOutcomes.some(id=>!pack.rules.some(r=>r.outcome===id))) throw new ApiError(400,'Game progress does not match this machine pack')
        for(const [id,cell] of Object.entries(b.state??{})) { const def=pack.stateVariables?.find(d=>d.id===id); if(!def||!validValue(def,cell.value)) throw new ApiError(400,'Game state does not match this guide version') }
        if(!await services.reserve(b.requestId,'coach')) throw new ApiError(429,'Online AI is paused by the spending cap or is not configured. Downloaded guides still work.')
        return reply(validateAnswer(await services.coach(pack,b.question,b.confirmedOutcomes,b.state),pack))
      }
      if(path==='scan' && services.scan) {
        const b=z.object({requestId,variantId,packVersion:z.number().int().positive(),photos:z.array(z.string().max(2_800_000).regex(/^data:image\/(jpeg|png|webp);base64,[A-Za-z0-9+/=]+$/)).min(1).max(2)}).strict().parse(body)
        const pack=await services.latestPack(b.variantId,b.packVersion)
        if(!pack || publicationErrors(pack).length) throw new ApiError(409,'Download a reviewed guide for this edition before scanning progress.')
        if(!await services.reserve(b.requestId,'identify')) throw new ApiError(429,'Scanning is paused by the spending cap or is not configured. Update progress manually.')
        const result=await services.scan(pack,b.photos)
        try {return reply({...validateScan(result,pack),requiresConfirmation:true})}
        catch {throw new ApiError(502,'The display could not be read reliably. Try another scan or update progress manually.')}
      }
      if(path==='identify') {
        const b=z.object({requestId,photos:z.array(z.string().max(2_800_000).regex(/^data:image\/(jpeg|png|webp);base64,[A-Za-z0-9+/=]+$/)).min(1).max(2)}).parse(body)
        if(!await services.reserve(b.requestId,'identify')) throw new ApiError(429,'Photo analysis is paused by the spending cap or is not configured. Use manual search.')
        return reply({...identificationSchema.parse(await services.identify(b.photos)),requiresConfirmation:true})
      }
      if(path==='activity') {
        const b=z.object({id:requestId,gameId:requestId,timestamp:z.number().int().nonnegative(),kind:z.enum(['progress','photo','question','advice','game']),text:z.string().max(4000)}).parse(body)
        await services.activity(b,actor,bearer); return reply({ok:true})
      }
      if(path==='sync' && services.sync) return reply(await services.sync(syncSchema.parse(body),actor,bearer))
      throw new ApiError(404,'Route not found')
    } catch(e) {
      if(e instanceof ApiError) return reply({error:e.message},e.status)
      if(e instanceof z.ZodError) return reply({error:'Request or provider response did not match the expected format.'},400)
      // Never leak tokens, request contents, SQL or provider responses in public errors/logs.
      return reply({error:'Service unavailable. Use the downloaded guide and try again later.'},503)
    }
  }
}
