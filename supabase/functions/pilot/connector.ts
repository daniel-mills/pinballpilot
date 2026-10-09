import type { SupabaseClient } from '@supabase/supabase-js'
import { ApiError } from './handler.ts'
import type { ResearchProposal } from '../../../shared/research.ts'
import { evidenceFor } from './knowledge.ts'

export async function tokenHash(token:string) {
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(token)))).map(b=>b.toString(16).padStart(2,'0')).join('')
}
function checked<T>({data,error}:{data:T;error:{code?:string}|null}):T {
  if(error) throw new ApiError(error.code==='P0002'?409:error.code==='42501'?403:503,
    error.code==='P0002'?'Knowledge or proposal state changed, or the pending queue is full. Refresh before retrying.':'Connector operation failed. Check access and try again.')
  return data
}
const metadata='id,label,created_at,expires_at,revoked_at'
export function createConnectorServices(root:SupabaseClient) {
  return {
    async authenticate(token:string) {
      if(!/^pp_mcp_[0-9a-f]{64}$/.test(token)) throw new ApiError(401,'Invalid research connector token')
      const row=checked(await root.from('research_tokens').select('id,owner_id,expires_at,revoked_at').eq('token_hash',await tokenHash(token)).maybeSingle())
      if(!row||row.revoked_at||Date.parse(row.expires_at)<=Date.now()) throw new ApiError(401,'Research connector token expired or revoked')
      const membership=checked(await root.from('memberships').select('role').eq('user_id',row.owner_id).maybeSingle())
      if(!membership||!['editor','owner'].includes(membership.role)) throw new ApiError(403,'Editor membership required')
      return {id:row.owner_id,role:membership.role as 'editor'|'owner',scope:'research' as const,tokenId:row.id}
    },
    async tokens(actor:string) {return checked(await root.from('research_tokens').select(metadata).eq('owner_id',actor).order('created_at',{ascending:false}))??[]},
    async createToken(actor:string,label:string,days:number) {
      const token='pp_mcp_'+Array.from(crypto.getRandomValues(new Uint8Array(32))).map(b=>b.toString(16).padStart(2,'0')).join('')
      const expires_at=new Date(Date.now()+days*86400000).toISOString()
      const record=checked(await root.from('research_tokens').insert({owner_id:actor,label,token_hash:await tokenHash(token),expires_at}).select(metadata).single())
      return {token,record}
    },
    async revokeToken(actor:string,id:string) {
      const row=checked(await root.from('research_tokens').update({revoked_at:new Date().toISOString()}).eq('id',id).eq('owner_id',actor).select('id').maybeSingle())
      if(!row) throw new ApiError(404,'Connector token not found')
    },
    async machines() {return checked(await root.from('machine_variants').select('id,edition,software,machines(name,manufacturer)').order('id').limit(200))??[]},
    async knowledge(id:string,offset:number) {
      const machine=checked(await root.from('machine_variants').select('id,edition,software,machines(name,manufacturer)').eq('id',id).maybeSingle())
      if(!machine) throw new ApiError(404,'Machine edition not found')
      const claims=checked(await root.from('claims').select('id,variant_id,kind,title,body,source_ids,status,revision,reviewed_revision,software,review_note,applicability,rule_spec').eq('variant_id',id).order('id').range(offset,offset+99))??[]
      const ids=[...new Set(claims.flatMap(c=>c.source_ids as string[]))]
      const sources=ids.length?checked(await root.from('knowledge_sources').select('*').in('id',ids)):[]
      const draft=checked(await root.from('pack_drafts').select('payload,revision').eq('variant_id',id).maybeSingle())
      const evidence=await evidenceFor(root,claims.map(c=>c.id))
      const releases=checked(await root.from('software_releases').select('*').eq('variant_id',id))??[]
      return {machine,claims,sources,evidence,releases,guideDraft:draft?{revision:draft.revision,rules:draft.payload.rules,strategies:draft.payload.strategies,stateVariables:draft.payload.stateVariables,applicability:draft.payload.applicability}:null,nextOffset:claims.length===100?offset+100:null}
    },
    async proposals(id:string|undefined,offset:number) {
      let query=root.from('research_proposals').select('id,variant_id,target_claim_id,base_revision,payload,previous_claim,status,created_at,review_note,applied_claim_id').order('created_at',{ascending:false}).order('id').range(offset,offset+49)
      if(id) query=query.eq('variant_id',id)
      const items=checked(await query)??[]
      return {items,nextOffset:items.length===50?offset+50:null}
    },
    async submit(tokenId:string,proposal:ResearchProposal) {return checked(await root.rpc('submit_research_proposal',{p_token:tokenId,p_payload:proposal}))},
    async review(id:string,status:'approved'|'rejected',note:string,actor:string) {checked(await root.rpc('review_research_proposal',{p_id:id,p_status:status,p_note:note,p_actor:actor}))},
  }
}
export type ConnectorServices=ReturnType<typeof createConnectorServices>
