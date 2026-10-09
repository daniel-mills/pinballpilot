import { describe, expect, it } from 'vitest'
import { packSchema } from '../shared/contracts'
import { validateScan } from '../shared/scan'
import { scanPrompt } from '../supabase/functions/pilot/provider'
import demo from '../content/demo-pack.json'

const pack=packSchema.parse({...demo,stateVariables:[
  {id:'locks',label:'Locked balls',type:'counter',scope:'game',initial:null},
  {id:'mode',label:'Current mode',type:'enum',scope:'game',initial:null,values:['none','battle']},
  {id:'clock',label:'Timer',type:'timer',scope:'mode',modeId:'battle',initial:null},
]})
const score={target:'score',variableId:null,value:123456,evidence:'PLAYER 1 123,456',confidence:0.8}
const reading={target:'state',variableId:'locks',value:2,evidence:'BALLS LOCKED 2',confidence:0.75}
const result=(readings:unknown[])=>({machineMismatch:false,readings,note:''})
describe('Display scan validation',()=>{
  it('accepts only typed, known readings and retains visible evidence',()=>{
    expect(validateScan(result([score,reading]),pack).readings).toEqual([score,reading])
  })
  it('rejects invented variables, timers, and invalid enum values',()=>{
    for(const r of [{...reading,variableId:'invented'},{...reading,variableId:'clock'},{...reading,variableId:'mode',value:'invented'},{...reading,value:'2'}]) {
      expect(()=>validateScan(result([r]),pack)).toThrow()
    }
  })
  it('rejects ambiguous duplicate readings and impossible score/ball values',()=>{
    expect(()=>validateScan(result([score,{...score,value:7}]),pack)).toThrow()
    for(const r of [{...score,value:-1},{...score,value:'123'},{...score,value:1.5},{...score,target:'ball',value:0},{...score,target:'ball',value:100},{...score,variableId:'locks'}]) expect(()=>validateScan(result([r]),pack)).toThrow()
  })
  it('returns no progress when another machine is visible or the display is unreadable',()=>{
    expect(validateScan({...result([score]),machineMismatch:true},pack).readings).toEqual([])
    expect(validateScan(result([]),pack).readings).toEqual([])
  })
  it('does not send current guesses or timer variables to the vision model',()=>{
    const pending={...pack.claims[0]!,id:'pending',status:'pending' as const}
    const prompt=JSON.parse(scanPrompt({...pack,claims:[...pack.claims,pending]}).prompt)
    expect(prompt.machine.packVersion).toBe(pack.version)
    expect(prompt.variables.map((v:{id:string})=>v.id)).toEqual(['locks','mode'])
    expect(prompt.evidence.some((c:{id:string})=>c.id==='pending')).toBe(false)
    expect(prompt.gameState).toBeUndefined()
  })
})
