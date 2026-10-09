import { z } from 'zod'
import type { MachinePack } from './contracts.ts'
import { stateValueSchema, validValue } from './state.ts'

export const scanReadingSchema = z.object({
  target:z.enum(['score','ball','state']), variableId:z.string().max(100).nullable(),
  value:stateValueSchema, evidence:z.string().trim().min(1).max(240), confidence:z.number().min(0).max(1),
}).strict()
export const scanResultSchema = z.object({
  machineMismatch:z.boolean(), readings:z.array(scanReadingSchema).max(12), note:z.string().max(500),
}).strict()
export type ScanReading = z.infer<typeof scanReadingSchema>
export function validateScan(value:unknown, pack:MachinePack) {
  const result=scanResultSchema.parse(value)
  const seen=new Set<string>()
  for(const reading of result.readings) {
    const key=`${reading.target}:${reading.variableId??''}`
    if(seen.has(key)) throw new Error('Duplicate scan reading')
    seen.add(key)
    if(reading.target==='state') {
      const def=pack.stateVariables?.find(d=>d.id===reading.variableId)
      if(!def||def.type==='timer'||!validValue(def,reading.value)) throw new Error('Unsupported scan progress')
    } else if(reading.variableId!==null||typeof reading.value!=='number'||!Number.isSafeInteger(reading.value)||reading.value<(reading.target==='ball'?1:0)||reading.target==='ball'&&reading.value>99) {
      throw new Error('Invalid scan number')
    }
  }
  // A visibly different machine must never supply progress for this game.
  if(result.machineMismatch) result.readings=[]
  return result
}
