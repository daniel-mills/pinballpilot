<script setup lang="ts">
import { stateVariableSchema, initialState, conditionsMet, applyEffects, stateErrors, type GameState } from '../../../shared/state'
const w=useWorkshop()
const {pack}=w
const definitions=ref('[]')
const preview=ref<GameState>({})
const releaseVersion=ref('')
const releaseDate=ref('')
const localError=ref('')
watch(()=>[pack.value.variantId,pack.value.stateVariables],()=>{definitions.value=JSON.stringify(pack.value.stateVariables??[],null,2);preview.value=initialState(pack.value.stateVariables??[],Date.now())},{immediate:true})
function saveDefinitions() {
  try {
    const variables=stateVariableSchema.array().max(200).parse(JSON.parse(definitions.value))
    const errors=stateErrors(variables,pack.value.rules)
    if(errors.length) throw new Error(errors.join('. '))
    pack.value.stateVariables=variables;w.persist();localError.value=''
  } catch(e) {localError.value=e instanceof Error?e.message:'Invalid state definitions'}
}
function simulate(id:string) {
  try { const rule=pack.value.rules.find(r=>r.id===id)!;preview.value=applyEffects(pack.value.stateVariables??[],preview.value,rule.effects??[],Date.now());localError.value='' }
  catch(e) {localError.value=e instanceof Error?e.message:'Cannot simulate this rule'}
}
async function addRelease() {
  try {
    if(!releaseVersion.value.trim()) throw new Error('Enter the manufacturer version label')
    const release={id:`release-${crypto.randomUUID()}`,variantId:pack.value.variantId,version:releaseVersion.value.trim(),releasedAt:releaseDate.value||null}
    if(w.configured) await w.api('admin/releases',release)
    pack.value.releases=[...(pack.value.releases??[]),release];w.persist();releaseVersion.value='';localError.value=''
  } catch(e) {localError.value=e instanceof Error?e.message:'Release could not be saved'}
}
</script>
<template>
  <v-card class="pa-5 mb-6">
    <h2>Versions and game progress</h2>
    <p class="muted my-3">Keep software assumptions and repeatable progress explicit. New behaviour must be attached to a reviewed recommendation before publishing.</p>
    <v-alert v-if="localError" type="error" class="mb-4">{{localError}}</v-alert>
    <v-btn v-if="pack.schemaVersion===1" color="secondary" @click="w.upgradePack">Upgrade draft to version 2</v-btn>
    <template v-else>
      <v-chip class="mb-4">Pack format 2 · State engine {{pack.engineVersion}}</v-chip>
      <v-select v-if="pack.applicability" v-model="pack.applicability.status" label="Software applicability" :items="[{title:'Unknown — needs research',value:'unknown'},{title:'Specific releases',value:'releases'},{title:'Software does not apply',value:'not_applicable'}]" @update:model-value="value=>{if(value!=='releases')pack.applicability!.releaseIds=[];w.persist()}"/>
      <v-select v-if="pack.applicability?.status==='releases'" v-model="pack.applicability.releaseIds" label="Applicable releases" multiple :items="pack.releases??[]" item-title="version" item-value="id" @update:model-value="w.persist"/>
      <v-textarea v-if="pack.applicability" v-model="pack.applicability.settings" label="Operator settings and assumptions" rows="2" @change="w.persist"/>
      <v-expansion-panels>
        <v-expansion-panel title="Register a software release"><v-expansion-panel-text>
          <v-text-field v-model="releaseVersion" label="Manufacturer version label"/><v-text-field v-model="releaseDate" type="date" label="Release date (optional)"/>
          <v-btn @click="addRelease">Register release</v-btn><p class="muted mt-3">Registering a release does not establish that a claim applies to it. Cite and review that applicability separately.</p>
        </v-expansion-panel-text></v-expansion-panel>
        <v-expansion-panel title="State definitions and preview"><v-expansion-panel-text>
          <p class="muted mb-3">Types: boolean, counter, enum or timer. Scope: game, ball or mode. Set initial to null when unknown. Timer values are expiry times; startTimer effects take milliseconds.</p>
          <v-textarea v-model="definitions" label="State variable definitions (JSON)" rows="9"/>
          <v-btn @click="saveDefinitions">Apply definitions</v-btn>
          <p class="muted my-3">Edit rule conditions, effects and repeatable in the structured pack editor. This preview exercises state effects; it does not approve evidence or simulate the physical machine.</p>
          <v-btn variant="text" @click="preview=initialState(pack.stateVariables??[],Date.now())">Reset preview</v-btn>
          <pre style="white-space:pre-wrap">{{JSON.stringify(preview,null,2)}}</pre>
          <v-btn v-for="rule in pack.rules.filter(r=>r.effects?.length)" :key="rule.id" class="ma-1" :disabled="!conditionsMet(rule.conditions,preview,Date.now())" @click="simulate(rule.id)">{{rule.id}}</v-btn>
        </v-expansion-panel-text></v-expansion-panel>
      </v-expansion-panels>
    </template>
  </v-card>
</template>
