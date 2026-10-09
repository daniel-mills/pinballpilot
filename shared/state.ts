import { z } from 'zod'

const key = z.string().regex(/^[a-zA-Z0-9][a-zA-Z0-9_-]{0,99}$/)
export const stateValueSchema = z.union([z.boolean(), z.number().int().safe(), z.string().max(100)])
export const stateVariableSchema = z.object({
  id: key, label: z.string().min(1).max(200), type: z.enum(['boolean', 'counter', 'enum', 'timer']),
  scope: z.enum(['game', 'ball', 'mode']), modeId: key.optional(),
  initial: stateValueSchema.nullable(), values: z.array(z.string().min(1).max(100)).max(100).optional(),
}).strict()
export const predicateSchema = z.object({ variable: key, op: z.enum(['eq', 'gte', 'lt', 'active', 'expired']), value: stateValueSchema.optional() }).strict()
export const conditionsSchema = z.object({ all: z.array(predicateSchema).max(100).default([]), any: z.array(predicateSchema).max(100).default([]) }).strict()
export const effectSchema = z.object({ variable: key, op: z.enum(['set', 'increment', 'reset', 'startTimer']), value: stateValueSchema.optional() }).strict()
export const stateCellSchema = z.object({ value: stateValueSchema.nullable(), origin: z.enum(['initial', 'player', 'inferred']), observedAt: z.number().int().nonnegative() }).strict()
export const gameStateSchema = z.record(key, stateCellSchema).refine(v => Object.keys(v).length <= 200, 'Too many state variables')
export type StateVariable = z.infer<typeof stateVariableSchema>
export type GameState = z.infer<typeof gameStateSchema>
export type Conditions = z.infer<typeof conditionsSchema>
export type Effect = z.infer<typeof effectSchema>
export const ruleSpecSchema = z.object({
  ruleId: key, shotId: key, prerequisites: z.array(key).max(100), outcome: key,
  repeatable: z.boolean(), conditions: conditionsSchema,
  effects: z.array(effectSchema).max(100), variables: z.array(stateVariableSchema).max(200),
}).strict()
export function canonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`
  if (value && typeof value === 'object') return `{${Object.entries(value).filter(([,v]) => v !== undefined).sort(([a],[b]) => a.localeCompare(b)).map(([k,v]) => `${JSON.stringify(k)}:${canonical(v)}`).join(',')}}`
  return JSON.stringify(value)
}

export function validValue(def: StateVariable, value: unknown): boolean {
  if (value === null) return true
  if (def.type === 'boolean') return typeof value === 'boolean'
  if (def.type === 'enum') return typeof value === 'string' && !!def.values?.includes(value)
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}
export function initialState(defs: StateVariable[], at: number): GameState {
  return Object.fromEntries(defs.map(d => [d.id, { value: d.initial, origin: 'initial', observedAt: at }]))
}
export function conditionsMet(conditions: Conditions | undefined, state: GameState, now: number): boolean {
  const test = (p: z.infer<typeof predicateSchema>) => {
    const cell = state[p.variable]
    // An inferred observation never silently becomes confirmed game progress.
    if (!cell || cell.value === null || cell.origin === 'inferred') return false
    const v = cell.value
    if (p.op === 'eq') return v === p.value
    if (typeof v !== 'number') return false
    if (p.op === 'active') return v > now
    if (p.op === 'expired') return v > 0 && v <= now
    return typeof p.value === 'number' && (p.op === 'gte' ? v >= p.value : v < p.value)
  }
  return !conditions || conditions.all.every(test) && (!conditions.any.length || conditions.any.some(test))
}
export function applyEffects(defs: StateVariable[], state: GameState, effects: Effect[], at: number): GameState {
  const next = structuredClone(state)
  for (const effect of effects) {
    const def = defs.find(d => d.id === effect.variable)
    if (!def) throw new Error(`Unknown state variable ${effect.variable}`)
    let value = effect.value ?? null
    if (effect.op === 'reset') value = def.initial
    if (effect.op === 'increment') {
      const cell = next[def.id]
      if (def.type !== 'counter' || !cell || cell.origin === 'inferred' || typeof cell.value !== 'number' || typeof effect.value !== 'number') throw new Error(`Confirm ${def.label} before incrementing it`)
      value = cell.value + effect.value
    }
    if (effect.op === 'startTimer') {
      if (def.type !== 'timer' || typeof effect.value !== 'number' || effect.value <= 0) throw new Error('Invalid timer duration')
      value = at + effect.value
    }
    if (!validValue(def, value)) throw new Error(`Invalid value for ${def.label}`)
    next[def.id] = { value, origin: 'player', observedAt: at }
  }
  return next
}
export function resetScope(defs: StateVariable[], state: GameState, scope: 'ball' | 'mode', at: number, modeId?: string): GameState {
  return applyEffects(defs, state, defs.filter(d => d.scope === scope && (scope !== 'mode' || d.modeId === modeId)).map(d => ({ variable: d.id, op: 'reset' })), at)
}
export function stateErrors(defs: StateVariable[], rules: { id: string; conditions?: Conditions; effects?: Effect[] }[]): string[] {
  const errors: string[] = [], ids = new Set(defs.map(d => d.id))
  if (ids.size !== defs.length) errors.push('Duplicate state variable identifiers')
  for (const d of defs) {
    if (!validValue(d, d.initial) || d.type === 'enum' && !d.values?.length || d.scope === 'mode' && !d.modeId || d.type === 'timer' && d.initial !== null && d.initial !== 0) errors.push(`Invalid definition for ${d.id}`)
  }
  for (const r of rules) {
    for (const p of [...(r.conditions?.all ?? []), ...(r.conditions?.any ?? [])]) {
      const d = defs.find(d => d.id === p.variable)
      if (!d) { errors.push(`Unknown state variable ${p.variable}`); continue }
      if (['active', 'expired'].includes(p.op) ? d.type !== 'timer' || p.value !== undefined : p.value === undefined || !validValue(d, p.value) || ['gte', 'lt'].includes(p.op) && d.type !== 'counter') errors.push(`Invalid condition in ${r.id}`)
    }
    for (const e of r.effects ?? []) {
      const d = defs.find(d => d.id === e.variable)
      if (!d) { errors.push(`Unknown state variable ${e.variable}`); continue }
      if (e.op === 'reset' ? e.value !== undefined : e.op === 'increment' ? d.type !== 'counter' || typeof e.value !== 'number' || !Number.isSafeInteger(e.value) : e.op === 'startTimer' ? d.type !== 'timer' || typeof e.value !== 'number' || !Number.isSafeInteger(e.value) || e.value <= 0 : e.value === undefined || !validValue(d, e.value)) errors.push(`Invalid effect in ${r.id}`)
    }
  }
  return errors
}
