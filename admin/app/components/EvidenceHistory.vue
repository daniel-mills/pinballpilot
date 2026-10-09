<script setup lang="ts">
const props=defineProps<{claimId:string}>()
const w=useWorkshop()
const history=ref<any>(null)
const error=ref('')
const loading=ref(false)
function sourceFor(e:any) { return history.value?.sources?.find((s:any)=>s.source_id===e.source_id&&s.revision===e.source_revision)?.snapshot }
async function load() {
  loading.value=true
  try {history.value=await w.api(`admin/history/${encodeURIComponent(props.claimId)}`);error.value=''}
  catch(e) {error.value=e instanceof Error?e.message:'History unavailable'} finally {loading.value=false}
}
</script>
<template>
  <v-expansion-panels class="my-3"><v-expansion-panel title="Evidence revision history"><v-expansion-panel-text>
    <p v-if="!w.connected.value" class="muted">Connect the shared workspace to inspect permanent claim revisions and their original evidence.</p>
    <template v-else><v-btn :loading="loading" @click="load">Load revision history</v-btn><v-alert v-if="error" type="error">{{error}}</v-alert>
      <article v-for="revision in history?.revisions??[]" :key="revision.revision" class="my-4">
        <strong>Revision {{revision.revision}} · {{revision.recorded_at}}</strong><p>{{revision.snapshot.body}}</p>
        <p class="muted">Applicability: {{revision.snapshot.applicability}}</p>
        <p v-for="event in (history?.reviews??[]).filter((e:any)=>e.revision===revision.revision)" :key="event.id">{{event.status}} — {{event.note}} · {{event.created_at}}</p>
        <div v-for="evidence in (history?.evidence??[]).filter((e:any)=>e.claim_revision===revision.revision)" :key="evidence.source_id">
          <p>{{evidence.relation}} · revision {{evidence.source_revision}} · {{evidence.locator}}</p>
          <a v-if="sourceFor(evidence)" :href="sourceFor(evidence).url" target="_blank" rel="noopener noreferrer">{{sourceFor(evidence).title}}</a>
          <p class="muted">{{sourceFor(evidence)?.notes}} · accessed {{sourceFor(evidence)?.accessed_at}}</p>
        </div>
        <details v-if="revision.snapshot.rule_spec"><summary>Reviewed state behaviour</summary><pre style="white-space:pre-wrap">{{JSON.stringify(revision.snapshot.rule_spec,null,2)}}</pre></details>
      </article>
    </template>
  </v-expansion-panel-text></v-expansion-panel></v-expansion-panels>
</template>
