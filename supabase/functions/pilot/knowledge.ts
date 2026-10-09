import type { SupabaseClient } from '@supabase/supabase-js'
import { ApiError } from './handler.ts'

export const sourceDto = (s: Record<string, unknown>) => ({ id:s.id,title:s.title,url:s.url,kind:s.kind,locator:s.locator,accessedAt:s.accessed_at,notes:s.notes,revision:s.revision,contentHash:s.content_hash })
export const citationDto = (e: Record<string, unknown>) => ({ sourceId:e.source_id,sourceRevision:e.source_revision,locator:e.locator,relation:e.relation })
export const claimDto = (c: Record<string, unknown>, evidence: Record<string,unknown>[] = []) => ({
  id:c.id,variantId:c.variant_id,kind:c.kind,title:c.title,body:c.body,sourceIds:c.source_ids,status:c.status,revision:c.revision,reviewedRevision:c.reviewed_revision,reviewNote:c.review_note,software:c.software,
  applicability:c.applicability,ruleSpec:c.rule_spec,citations:evidence.filter(e=>e.claim_id===c.id&&e.claim_revision===c.revision).map(citationDto),
})
export async function evidenceFor(root: SupabaseClient, ids: string[], current = true) {
  if (!ids.length) return []
  const {data,error} = await root.from(current?'current_claim_evidence':'claim_revision_evidence').select('*').in('claim_id',ids).order('claim_revision',{ascending:false}).limit(1000)
  if(error) throw new ApiError(503,'Evidence history could not be loaded')
  return data ?? []
}
