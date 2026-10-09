import { z } from 'zod'
import { applicabilitySchema } from './contracts.ts'
import { ruleSpecSchema, stateErrors } from './state.ts'

export const machineIdSchema = z.string().regex(/^[a-zA-Z0-9][a-zA-Z0-9_-]{0,99}$/)
export const evidenceSchema = z.object({
  title: z.string().trim().min(1).max(180),
  url: z.string().url().max(2000).refine(value => { const u=new URL(value); return u.protocol==='https:'&&!u.username&&!u.password }, 'Use an HTTPS source URL without credentials'),
  kind: z.enum(['manufacturer','rulesheet','community','video','original']),
  locator: z.string().trim().min(1).max(300),
  accessedAt: z.string().date(),
  notes: z.string().max(1000),
  relation: z.enum(['supports','contradicts']).optional(),
  contentHash: z.string().regex(/^[a-f0-9]{64}$/).nullable().optional(),
}).strict()
export const proposalSchema = z.object({
  requestId: z.string().uuid(),
  variantId: machineIdSchema,
  targetClaimId: z.string().min(1).max(100).nullable(),
  baseRevision: z.number().int().positive().nullable(),
  kind: z.enum(['fact','recommendation']),
  title: z.string().trim().min(1).max(160),
  body: z.string().trim().min(1).max(2000),
  software: z.string().trim().min(1).max(100),
  rationale: z.string().trim().min(1).max(2000),
  uncertainty: z.string().max(1000),
  sources: z.array(evidenceSchema).min(1).max(8),
  applicability: applicabilitySchema.optional(),
  ruleSpec: ruleSpecSchema.nullable().optional(),
}).strict().refine(p=>(p.targetClaimId===null)===(p.baseRevision===null), 'Corrections require a target claim and its current revision')
  .refine(p => p.sources.some(s => s.relation !== 'contradicts'), 'At least one supporting source is required')
  .refine(p => !p.ruleSpec || p.kind === 'recommendation' && stateErrors(p.ruleSpec.variables,[{id:p.ruleSpec.ruleId,...p.ruleSpec}]).length === 0, 'Invalid reviewed rule behaviour')
export type ResearchProposal = z.infer<typeof proposalSchema>
export interface ProposalRecord {
  id:string; variant_id:string; target_claim_id:string|null; base_revision:number|null;
  payload:ResearchProposal; previous_claim:Record<string,unknown>|null;
  status:'pending'|'approved'|'rejected'; created_at:string; review_note:string;
  applied_claim_id:string|null;
}
