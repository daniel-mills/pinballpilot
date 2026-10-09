import { describe, expect, it } from 'vitest'
import demo from '../content/demo-pack.json'
import { packSchema, publicationErrors, rankShots } from '../shared/contracts'
import { applyEffects, conditionsMet, initialState, resetScope, stateErrors, type StateVariable } from '../shared/state'

const defs: StateVariable[] = [
  {id:'locks',label:'Lock credits',type:'counter',scope:'game',initial:0},
  {id:'bonus',label:'Ball bonus',type:'counter',scope:'ball',initial:0},
  {id:'save',label:'Ball save',type:'timer',scope:'mode',modeId:'multiball',initial:0},
  {id:'qualified',label:'Qualification',type:'boolean',scope:'game',initial:null},
]
function pack2() {
  const p=packSchema.parse(demo)
  p.schemaVersion=2;p.engineVersion=1;p.stateVariables=defs;p.releases=[]
  p.applicability={status:'unknown',releaseIds:[],settings:''}
  p.sources=p.sources.map(s=>({...s,revision:1,contentHash:null}))
  p.claims=p.claims.map(c=>({...c,applicability:p.applicability,citations:c.sourceIds.map(id=>({sourceId:id,sourceRevision:1,relation:'supports',locator:p.sources.find(s=>s.id===id)!.locator}))}))
  const rule=p.rules.find(r=>r.id==='ramp-build')!
  rule.repeatable=true;rule.conditions={all:[{variable:'locks',op:'lt',value:3}],any:[]};rule.effects=[{variable:'locks',op:'increment',value:1}]
  p.claims.find(c=>rule.claimIds.includes(c.id)&&c.kind==='recommendation')!.ruleSpec=structuredClone({ruleId:rule.id,shotId:rule.shotId,prerequisites:rule.prerequisites,outcome:rule.outcome,repeatable:true,conditions:rule.conditions,effects:rule.effects,variables:defs})
  return p
}
describe('versioned rule packs',()=>{
  it('supports old packs and complete v2 evidence',()=>{
    expect(publicationErrors(demo)).toEqual([])
    expect(publicationErrors(pack2())).toEqual([])
  })
  it('does not approve a behaviour edit just because its wording is unchanged',()=>{
    const p=pack2();p.rules.find(r=>r.id==='ramp-build')!.effects=[{variable:'locks',op:'increment',value:2}]
    expect(publicationErrors(p)).toContain('Review the state behaviour for ramp-build')
  })
  it('rejects edited reset policy and stale citations',()=>{
    const p=structuredClone(pack2());p.stateVariables![0]!.scope='ball';p.sources[0]!.revision=2
    expect(publicationErrors(p).join(' ')).toMatch(/state behaviour/)
    expect(publicationErrors(p).join(' ')).toMatch(/source revision/)
  })
  it('requires another review when a stateful rule is reassigned to a different shot',()=>{
    const p=pack2();p.rules.find(r=>r.id==='ramp-build')!.shotId='right-ramp'
    expect(publicationErrors(p)).toContain('Review the state behaviour for ramp-build')
  })
  it('requires known edition-specific releases',()=>{
    const p=pack2();p.applicability={status:'releases',releaseIds:['missing'],settings:''}
    expect(publicationErrors(p)).toContain('Missing software release missing')
    p.releases=[{id:'missing',variantId:'another-edition',version:'vendor 01.75',releasedAt:null}]
    expect(publicationErrors(p)).toContain('Software release belongs to another edition')
  })
  it('permits repeated progress up to a threshold, including a second cycle',()=>{
    const p=pack2(),rule=p.rules.find(r=>r.id==='ramp-build')!
    let state=initialState(defs,1000)
    for(let i=0;i<3;i++) {
      expect(rankShots(p,'scoring',false,[rule.outcome],state,1000).some(r=>r.rule.id===rule.id)).toBe(true)
      state=applyEffects(defs,state,rule.effects!,1000)
    }
    expect(rankShots(p,'scoring',false,[rule.outcome],state,1000).some(r=>r.rule.id===rule.id)).toBe(false)
    state=applyEffects(defs,state,[{variable:'locks',op:'reset'}],2000)
    expect(rankShots(p,'scoring',false,[rule.outcome],state,2000).some(r=>r.rule.id===rule.id)).toBe(true)
  })
})
describe('state semantics',()=>{
  it('unknown and inferred values never satisfy confirmed conditions',()=>{
    const state=initialState(defs,0),condition={all:[{variable:'qualified',op:'eq' as const,value:false}],any:[]}
    expect(conditionsMet(condition,state,0)).toBe(false)
    state.qualified={value:false,origin:'inferred',observedAt:0}
    expect(conditionsMet(condition,state,0)).toBe(false)
    state.qualified.origin='player'
    expect(conditionsMet(condition,state,0)).toBe(true)
  })
  it('preserves game progress on drain and resets only the specified mode',()=>{
    const state=applyEffects(defs,initialState(defs,1000),[{variable:'locks',op:'increment',value:2},{variable:'bonus',op:'increment',value:4},{variable:'save',op:'startTimer',value:30000}],1000)
    const ball=resetScope(defs,state,'ball',2000)
    expect(ball.locks!.value).toBe(2);expect(ball.bonus!.value).toBe(0);expect(ball.save!.value).toBe(31000)
    expect(resetScope(defs,ball,'mode',2000,'other').save!.value).toBe(31000)
    expect(resetScope(defs,ball,'mode',2000,'multiball').save!.value).toBe(0)
  })
  it('uses explicit time and OR conditions without treating an inactive timer as expired',()=>{
    const active={all:[{variable:'save',op:'active' as const}],any:[]},expired={all:[],any:[{variable:'save',op:'expired' as const}]}
    const initial=initialState(defs,1000)
    expect(conditionsMet(expired,initial,50000)).toBe(false)
    const state=applyEffects(defs,initial,[{variable:'save',op:'startTimer',value:1000}],1000)
    expect(conditionsMet(active,state,1999)).toBe(true)
    expect(conditionsMet(expired,state,2000)).toBe(true)
  })
  it('rejects unknown increments, negative counters, invalid enums and malformed expressions',()=>{
    const state=initialState(defs,0);state.locks!.value=null
    expect(()=>applyEffects(defs,state,[{variable:'locks',op:'increment',value:1}],0)).toThrow(/Confirm/)
    expect(()=>applyEffects(defs,initialState(defs,0),[{variable:'locks',op:'increment',value:-1}],0)).toThrow(/Invalid/)
    expect(stateErrors(defs,[{id:'bad',effects:[{variable:'qualified',op:'increment',value:1}]}])).toEqual(['Invalid effect in bad'])
  })
  it('does not mutate the state when a later effect fails',()=>{
    const state=initialState(defs,0)
    expect(()=>applyEffects(defs,state,[{variable:'locks',op:'increment',value:1},{variable:'missing',op:'reset'}],0)).toThrow()
    expect(state.locks!.value).toBe(0)
  })
})
