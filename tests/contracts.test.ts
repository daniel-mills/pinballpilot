import { describe, expect, it } from 'vitest'
import demo from '../content/demo-pack.json'
import { editClaim, packSchema, publicationErrors, rankShots } from '../shared/contracts'
describe('publication boundary', () => {
  it('accepts the complete fictional pack', () => expect(publicationErrors(demo)).toEqual([]))
  it('rejects stale approval, mixed editions and missing evidence', () => {
    const p = structuredClone(demo); p.claims[0]!.revision++; p.claims[1]!.variantId = 'different'; p.sources = []
    expect(publicationErrors(p).join(' ')).toMatch(/Review required/)
    expect(publicationErrors(p).join(' ')).toMatch(/another edition/)
    expect(publicationErrors(p).join(' ')).toMatch(/Missing source/)
  })
  it('invalidates approval when text changes', () => {
    const c = packSchema.parse(demo).claims[0]!
    expect(editClaim(c, { ...c, body: 'Changed rule' })).toMatchObject({ status: 'pending', revision: 2, reviewedRevision: null })
  })
  it('rejects geometry outside image and unreachable prerequisites', () => {
    const p = structuredClone(demo); p.shots[0]!.geometry.point.x = 1.1
    expect(publicationErrors(p).length).toBeGreaterThan(0)
    const cycle = structuredClone(demo); cycle.rules[0]!.prerequisites = [cycle.rules[0]!.outcome]
    expect(publicationErrors(cycle).join(' ')).toMatch(/cycle/)
  })
  it('rejects missing image rights and missing guide', () => {
    const p = structuredClone(demo); p.image.rightsConfirmed = false; p.strategies = []
    expect(publicationErrors(p)).toContain('Confirm reference image rights before publishing')
    expect(publicationErrors(p)).toContain('Missing simple multiball guide')
  })
})
describe('strategy engine', () => {
  const p = packSchema.parse(demo)
  it('respects locks and progresses to multiball', () => {
    const completed: string[] = []
    for (const id of ['light-lock','lock-one','lock-two','lock-three','jackpot']) {
      const next = rankShots(p,'multiball',false,completed)[0]!
      expect(next.rule.id).toBe(id); completed.push(next.rule.outcome)
    }
    expect(rankShots(p,'multiball',false,completed)).toEqual([])
  })
  it('separates simple and advanced scoring', () => {
    expect(rankShots(p,'scoring',false,[])[0]!.rule.id).toBe('ramp-build')
    expect(rankShots(p,'scoring',true,[])[0]!.rule.id).toBe('mode-qualify')
  })
})
