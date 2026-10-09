import { z } from 'zod'

const id = z.string().min(1).max(100)
const text = z.string().min(1).max(4000)
const point = z.object({ x: z.number().min(0).max(1), y: z.number().min(0).max(1) })
export const geometrySchema = z.object({ point, polygon: z.array(point).min(3).optional() })
export const sourceSchema = z.object({
  id, title: text, url: z.string().url(), kind: z.enum(['manufacturer', 'rulesheet', 'community', 'video', 'original']),
  locator: z.string(), accessedAt: z.string(), notes: z.string(),
})
export const claimSchema = z.object({
  id, variantId: id, kind: z.enum(['fact', 'recommendation']), title: text, body: text,
  sourceIds: z.array(id).min(1), status: z.enum(['pending', 'approved', 'rejected']),
  revision: z.number().int().positive(), reviewedRevision: z.number().int().positive().nullable(),
  reviewNote: z.string(), software: z.string(),
})
export const shotSchema = z.object({
  id, name: text, type: z.enum(['ramp', 'orbit', 'target', 'scoop', 'lock', 'lane', 'bumper']),
  geometry: geometrySchema, description: text, difficulty: z.number().min(0).max(1), risk: z.number().min(0).max(1),
})
export const ruleSchema = z.object({
  id, shotId: id, claimIds: z.array(id).min(1), instruction: text, why: text,
  prerequisites: z.array(id), outcome: id, value: z.number().min(0).max(1), progression: z.number().min(0).max(1),
})
export const strategySchema = z.object({
  id, title: text, objective: z.enum(['scoring', 'multiball']), level: z.enum(['simple', 'advanced']),
  claimIds: z.array(id).min(1), steps: z.array(id).min(1),
})
export const packSchema = z.object({
  schemaVersion: z.literal(1), variantId: id, version: z.number().int().positive(),
  name: text, edition: text, manufacturer: text, demo: z.boolean(),
  image: z.object({ path: z.string().min(1), license: text, rightsConfirmed: z.boolean(), width: z.number().int().positive(), height: z.number().int().positive() }),
  shots: z.array(shotSchema), rules: z.array(ruleSchema), strategies: z.array(strategySchema),
  claims: z.array(claimSchema), sources: z.array(sourceSchema),
})
export type MachinePack = z.infer<typeof packSchema>
export type Claim = z.infer<typeof claimSchema>
export type Shot = z.infer<typeof shotSchema>

export function publicationErrors(input: unknown): string[] {
  const parsed = packSchema.safeParse(input)
  if (!parsed.success) return parsed.error.issues.map(i => `${i.path.join('.')}: ${i.message}`)
  const p = parsed.data, errors: string[] = []
  const unique = (items: { id: string }[], label: string) => { if (new Set(items.map(i => i.id)).size !== items.length) errors.push(`Duplicate ${label} identifiers`) }
  unique(p.shots, 'shot'); unique(p.rules, 'rule'); unique(p.claims, 'claim'); unique(p.sources, 'source'); unique(p.strategies, 'strategy')
  if (!p.image.rightsConfirmed) errors.push('Confirm reference image rights before publishing')
  if (p.shots.length < 10) errors.push('Annotate at least 10 shots')
  const shots = new Set(p.shots.map(s => s.id)), rules = new Set(p.rules.map(r => r.id)), sources = new Set(p.sources.map(s => s.id))
  const claims = new Map(p.claims.map(c => [c.id, c]))
  for (const c of p.claims) {
    if (c.variantId !== p.variantId) errors.push(`Claim ${c.id} belongs to another edition`)
    if (c.status !== 'approved' || c.reviewedRevision !== c.revision) errors.push(`Review required: ${c.title}`)
    for (const s of c.sourceIds) if (!sources.has(s)) errors.push(`Missing source ${s}`)
  }
  const checkClaims = (ids: string[]) => ids.forEach(c => { if (!claims.has(c)) errors.push(`Missing claim ${c}`) })
  const outcomes = new Set(p.rules.map(r => r.outcome))
  for (const r of p.rules) {
    if (!shots.has(r.shotId)) errors.push(`Unknown shot ${r.shotId}`)
    checkClaims(r.claimIds)
    if (!r.claimIds.some(id => { const c=claims.get(id); return c?.kind==='recommendation' && c.body===`${r.instruction} ${r.why}` })) errors.push(`Review the exact instruction and reason for ${r.id}`)
    for (const prerequisite of r.prerequisites) if (!outcomes.has(prerequisite)) errors.push(`Unknown prerequisite ${prerequisite}`)
  }
  // All prerequisites must be reachable; cycles cannot yield playable guides.
  const reached = new Set<string>()
  for (let pass = 0; pass < p.rules.length; pass++) for (const r of p.rules) if (r.prerequisites.every(x => reached.has(x))) reached.add(r.outcome)
  if (p.rules.some(r => !reached.has(r.outcome))) errors.push('Rule prerequisites contain an unreachable cycle')
  for (const s of p.strategies) {
    checkClaims(s.claimIds)
    for (const step of s.steps) if (!rules.has(step)) errors.push(`Unknown strategy step ${step}`)
  }
  for (const [objective, level] of [['scoring', 'simple'], ['scoring', 'advanced'], ['multiball', 'simple']])
    if (!p.strategies.some(s => s.objective === objective && s.level === level)) errors.push(`Missing ${level} ${objective} guide`)
  return [...new Set(errors)]
}

export function editClaim(claim: Claim, patch: Pick<Claim, 'title' | 'body' | 'sourceIds' | 'software'>): Claim {
  return { ...claim, ...patch, revision: claim.revision + 1, status: 'pending', reviewedRevision: null, reviewNote: '' }
}
export function rankShots(pack: MachinePack, objective: 'scoring' | 'multiball', advanced: boolean, completed: string[]) {
  const strategy = pack.strategies.find(s => s.objective === objective && s.level === (advanced && objective === 'scoring' ? 'advanced' : 'simple'))
  return pack.rules.filter(r => strategy?.steps.includes(r.id) && !completed.includes(r.outcome) && r.prerequisites.every(p => completed.includes(p)))
    .map(rule => { const shot = pack.shots.find(s => s.id === rule.shotId)!; return { rule, shot, score: rule.value * (advanced ? 4 : 2) + rule.progression * (objective === 'multiball' ? 5 : 2) - shot.risk * (advanced ? 1 : 3) - shot.difficulty * (advanced ? 1 : 2) } })
    .sort((a, b) => b.score - a.score || a.rule.id.localeCompare(b.rule.id))
}
