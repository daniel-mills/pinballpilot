<script setup lang="ts">
import type { ProposalRecord } from '../../../shared/research'
const props=defineProps<{mode:'settings'|'proposals'}>()
const w=useWorkshop()
const records=ref<ProposalRecord[]>([])
const nextOffset=ref<number|null>(null)
const tokens=ref<{id:string;label:string;expires_at:string;revoked_at:string|null}[]>([])
const secret=ref('')
const label=ref('My research assistant')
const days=ref(30)
const notes=reactive<Record<string,string>>({})
const status=ref('pending')
const loading=ref(false)
const error=ref('')
const message=ref('')
const shown=computed(()=>records.value.filter(p=>status.value==='all'||p.status===status.value))
async function action(task:()=>Promise<void>) {loading.value=true;error.value='';message.value='';try{await task()}catch(e){error.value=e instanceof Error?e.message:'Operation failed'}finally{loading.value=false}}
async function fetchRecords(offset=0) {
  if(props.mode==='settings') tokens.value=await w.api('admin/connector-tokens')
  else {const data=await w.api(`admin/proposals?offset=${offset}`);records.value=offset?[...records.value,...data.items]:data.items;nextOffset.value=data.nextOffset}
}
async function refresh() {if(w.connected.value) await action(()=>fetchRecords())}
async function createToken() {await action(async()=>{secret.value='';const data=await w.api('admin/connector-tokens',{label:label.value,days:Number(days.value)});secret.value=data.token;await fetchRecords()})}
async function revoke(id:string) {await action(async()=>{await w.api('admin/connector-tokens/revoke',{id});secret.value='';await fetchRecords();message.value='Connector revoked.'})}
async function review(p:ProposalRecord,decision:'approved'|'rejected') {await action(async()=>{await w.api('admin/proposals/review',{id:p.id,status:decision,note:notes[p.id]});await fetchRecords();message.value=decision==='approved'?'Evidence approved. Load it into the machine draft, check the guide, then publish a new version.':'Proposal rejected. Your note is available to the assistant.'})}
watch(()=>w.connected.value,()=>void refresh(),{immediate:true})
</script>
<template>
  <section class="mt-7">
    <div class="section-line"><h2>{{ mode==='settings'?'Research connectors':'Assistant proposals' }}</h2><v-btn variant="text" :disabled="!w.connected.value" :loading="loading" @click="refresh">Refresh {{ mode==='settings'?'connectors':'proposals' }}</v-btn></div>
    <p class="muted mb-5">{{ mode==='settings'?'Connect Codex or Claude Code to read machine knowledge and submit sourced proposals. Research runs in your assistant session and uses its plan allowance.':'Compare each proposed change with its sources. Approval updates the knowledge library; player guides change only when you publish a new version.' }}</p>
    <v-alert v-if="!w.connected.value" type="info" variant="tonal">Connect the backend and sign in as an editor to use MCP. The local sandbox cannot receive assistant proposals.</v-alert>
    <v-alert v-if="error" type="error" class="mb-4">{{ error }}</v-alert>
    <v-alert v-if="message" type="success" variant="tonal" class="mb-4">{{ message }}</v-alert>
    <template v-if="mode==='settings'">
      <v-card class="pa-6 mt-5">
        <div class="d-flex ga-4 flex-wrap"><v-text-field v-model="label" label="Connector name" maxlength="80"/><v-select v-model="days" label="Token expires after" :items="[{title:'7 days',value:7},{title:'30 days',value:30},{title:'90 days',value:90}]"/></div>
        <v-btn color="primary" :disabled="!w.connected.value||!label.trim()" :loading="loading" @click="createToken">Create connector token</v-btn>
        <div v-if="secret" class="mt-5"><v-alert type="warning" variant="tonal">Save this token in your private MCP environment file now. It is shown once and is not saved in browser storage.</v-alert><v-textarea :model-value="secret" readonly label="New connector token" rows="3" class="mt-4"/><v-btn variant="text" @click="secret=''">Hide token</v-btn></div>
        <p class="muted mt-5">Each assistant can have its own token. Tokens cannot approve, publish, spend on AI, or read player history. Setup instructions: <code>docs/MCP.md</code>.</p>
      </v-card>
      <v-list v-if="tokens.length" class="mt-4" bg-color="surface"><v-list-item v-for="token in tokens" :key="token.id" :title="token.label" :subtitle="`${token.revoked_at?'Revoked':'Expires'} · ${new Date(token.revoked_at||token.expires_at).toLocaleDateString()}`"><template #append><v-btn variant="text" color="error" :disabled="!!token.revoked_at||loading" @click="revoke(token.id)">Revoke</v-btn></template></v-list-item></v-list>
    </template>
    <template v-else-if="w.connected.value">
      <v-select v-model="status" label="Proposal status" :items="['pending','approved','rejected','all']"/>
      <p v-if="!shown.length" class="muted">No proposals in this loaded view.</p>
      <article v-for="p in shown" :key="p.id" class="claim-row">
        <div class="claim-header"><h3>{{ p.payload.title }}</h3><v-chip size="small">{{ p.status }}</v-chip></div>
        <p class="muted">{{ p.variant_id }} · {{ p.payload.kind }} · {{ p.payload.software }}</p>
        <div class="settings-grid my-5"><v-card class="pa-4"><h3>Current at submission</h3><p>{{ p.previous_claim?.body||'New knowledge' }}</p><p v-if="p.base_revision" class="muted mt-3">Revision {{ p.base_revision }}</p></v-card><v-card class="pa-4"><h3>Proposed wording</h3><p>{{ p.payload.body }}</p></v-card></div>
        <p class="mb-3"><strong>Reason:</strong> {{ p.payload.rationale }}</p><p class="mb-3"><strong>Uncertainty:</strong> {{ p.payload.uncertainty||'None reported by the assistant; verify the evidence.' }}</p>
        <p v-if="p.payload.applicability" class="mb-3"><strong>Applicability:</strong> {{p.payload.applicability}}</p>
        <details v-if="p.payload.ruleSpec" class="mb-3"><summary>State behaviour included in this approval</summary><pre style="white-space:pre-wrap">{{JSON.stringify(p.payload.ruleSpec,null,2)}}</pre></details>
        <div v-for="source in p.payload.sources" :key="source.url+source.locator" class="source-line"><a :href="source.url" target="_blank" rel="noopener noreferrer">{{ source.title }}</a><p class="muted">{{source.relation??'supports'}} · {{ source.kind }} (assistant classification) · {{ source.locator }} · accessed {{ source.accessedAt }}</p><p v-if="source.notes" class="muted">{{ source.notes }}</p></div>
        <div v-if="p.status==='pending'" class="review-controls"><v-text-field v-model="notes[p.id]" label="Proposal review note" placeholder="Record what you verified" hide-details/><v-btn color="success" variant="tonal" :disabled="loading||!notes[p.id]?.trim()" @click="review(p,'approved')">Approve proposal</v-btn><v-btn color="error" variant="text" :disabled="loading||!notes[p.id]?.trim()" @click="review(p,'rejected')">Reject proposal</v-btn></div>
        <p v-if="p.review_note" class="muted mt-4">Review: {{ p.review_note }}</p>
      </article>
      <v-btn v-if="nextOffset!==null" variant="tonal" :loading="loading" @click="action(()=>fetchRecords(nextOffset!))">Load more proposals</v-btn>
    </template>
  </section>
</template>
